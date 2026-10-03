package dev.sleepy.app.engine

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import dev.sleepy.app.model.TargetApk

/**
 * The obfuscated okhttp3 names that the generated blocklist interceptor has to write out.
 */
data class OkHttpProtocolNames(
    /**
     * `okhttp3.Response.<init>`'s descriptor—parameter list *and* return type, for example
     * `(...)V`.
     */
    val responseConstructorDescriptor: String,
    /** The `okhttp3.Protocol` enum, without the `L`/`;` a descriptor carries—such as `ps/s`. */
    val protocolClass: String,
    /** Field in [protocolClass] holding the `HTTP_1_1` constant—such as `i`. */
    val protocolHttp11Field: String
)

/** Either the preceding names, or why they could not be read. */
sealed interface OkHttpResolution {
    /** The names, read from the target build. */
    data class Resolved(val names: OkHttpProtocolNames) : OkHttpResolution

    /** The reason this build cannot carry the interceptor, phrased for the user. */
    data class Unresolved(val reason: String) : OkHttpResolution
}

/**
 * Reads the okhttp3 names R8 renames on every release, from the target's DEX.
 *
 * The blocklist interceptor constructs an `okhttp3.Response` manually, so it has to name the
 * `Response.<init>` descriptor, the `Protocol` enum and that enum's `HTTP_1_1` field. On 346.2
 * they were `Lcs/t;`, `Lcs/q;` and `Lgc/k;`; on 347.5 two of the three had moved, which produced
 * a `NoClassDefFoundError: Lcs/t;` on every request until the desktop reference stopped writing
 * them down. This is that same lookup, reading the DEX rather than an apktool tree, because an
 * on-device patcher does not have the tree.
 *
 * Reference: `quirky-noether/discord/patches/blocklist.py::discover_okhttp`.
 */
object OkHttpNameResolver {

    private const val RESPONSE_TYPE = "Lokhttp3/Response;"
    private const val CONSTRUCTOR_NAME = "<init>"

    /** The protocol string the `HTTP_1_1` enum constant is built from. */
    private const val PROTOCOL_STRING = "http/1.1"

    /** The enum constant whose field the interceptor has to read. */
    private const val PROTOCOL_CONSTANT = "HTTP_1_1"

    /**
     * Resolves [OkHttpProtocolNames] from [target], or explains why it cannot.
     *
     * @param apiLevel the opcode set to read the DEX with. Only affects how instructions are
     *   decoded, which these three names do not depend on.
     */
    fun resolve(target: TargetApk, apiLevel: Int = 28): OkHttpResolution = try {
        resolveOrThrow(target, apiLevel)
    } catch (e: Exception) {
        // This runs on whatever APK the user picked. A malformed DEX should cost this patch set,
        // not the whole job, so a parse failure is reported the way a missing name is.
        OkHttpResolution.Unresolved(
            "This APK's bytecode could not be read: ${e.message ?: e::class.java.simpleName}"
        )
    }

    private fun resolveOrThrow(target: TargetApk, apiLevel: Int): OkHttpResolution {
        val opcodes = Opcodes.forApi(apiLevel)
        val loadedDexFiles = mutableMapOf<String, DexBackedDexFile>()

        fun classDefOf(type: String): ClassDef? {
            val dexName = target.classToDexIndex[type] ?: return null
            val dexBytes = target.dexEntries[dexName] ?: return null
            val dexFile = loadedDexFiles.getOrPut(dexName) { DexBackedDexFile(opcodes, dexBytes) }
            return dexFile.classes.firstOrNull { it.type == type }
        }

        val response = classDefOf(RESPONSE_TYPE) ?: return OkHttpResolution.Unresolved(
            "$RESPONSE_TYPE is not in this APK, so the request interceptor cannot be rebuilt."
        )

        val constructor = response.methods.firstOrNull {
            it.name == CONSTRUCTOR_NAME && AccessFlags.PUBLIC.isSet(it.accessFlags)
        } ?: return OkHttpResolution.Unresolved(
            "$RESPONSE_TYPE has no public $CONSTRUCTOR_NAME, so its descriptor cannot be read."
        )

        // A descriptor is `(...)V`. The return type is part of it: dropping the trailing V
        // produces a smali parse error much later, with no hint as to the cause.
        val descriptor = buildString {
            append('(')
            constructor.parameterTypes.forEach { append(it) }
            append(')')
            append(constructor.returnType)
        }

        // Which obfuscated class the Protocol enum is moves per release, so it is recognized by
        // what it does rather than by name. It is the only class the constructor takes that
        // builds a constant out of the "http/1.1" string and assigns it to a static field of its
        // own type.
        for (parameter in constructor.parameterTypes) {
            val type = parameter.toString()
            if (!type.startsWith("L") || !type.endsWith(";")) continue
            val candidate = classDefOf(type) ?: continue
            val field = http11FieldOf(candidate) ?: continue
            return OkHttpResolution.Resolved(
                OkHttpProtocolNames(
                    responseConstructorDescriptor = descriptor,
                    protocolClass = type.substring(1, type.length - 1),
                    protocolHttp11Field = field
                )
            )
        }

        return OkHttpResolution.Unresolved(
            "None of the classes $RESPONSE_TYPE's constructor takes holds an HTTP/1.1 constant, " +
                "so the Protocol enum could not be identified."
        )
    }

    /**
     * Finds the field [classDef] assigns its `HTTP_1_1` constant to, or null when [classDef] is
     * not the enum that holds the protocols.
     *
     * The class is accepted on the same two facts the reference uses: its `<clinit>` builds a
     * constant from the `"http/1.1"` string, and it assigns a static field of the class's own
     * type. The field name then comes from the assignment that *follows* the `"HTTP_1_1"` marker,
     * because the enum writes six fields of its own type and only that marker identifies which of
     * them is HTTP/1.1—the marker is the constant's own name, and the enum constructor consumes
     * it immediately before the field is stored.
     */
    private fun http11FieldOf(classDef: ClassDef): String? {
        val clinit = classDef.methods.firstOrNull { it.name == "<clinit>" } ?: return null
        val instructions = clinit.implementation?.instructions ?: return null

        var buildsProtocol = false
        var http11ConstantSeen = false
        var http11Field: String? = null

        for (instruction in instructions) {
            val reference = (instruction as? ReferenceInstruction)?.reference ?: continue
            if (reference is StringReference) {
                // Only const-string carries a string reference, so the opcode needs no checking.
                when (reference.string) {
                    PROTOCOL_STRING -> buildsProtocol = true
                    PROTOCOL_CONSTANT -> http11ConstantSeen = true
                }
            } else if (instruction.opcode == Opcode.SPUT_OBJECT && reference is FieldReference) {
                val storesOwnType =
                    reference.definingClass == classDef.type && reference.type == classDef.type
                if (storesOwnType && http11ConstantSeen && http11Field == null) {
                    http11Field = reference.name
                }
            }
        }

        return if (buildsProtocol) http11Field else null
    }
}
