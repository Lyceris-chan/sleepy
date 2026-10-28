package dev.sleepy.app.engine

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import dev.sleepy.app.model.TargetApk

/**
 * Every obfuscated OkHttp name the generated blocklist interceptor has to write out.
 *
 * R8 renames the whole OkHttp surface on each Discord release. The names are therefore read from
 * the selected APK's own DEX and handed to the generator, never carried from a previous build.
 *
 * @property interceptorClass Smali path of the stock resource-usage interceptor, without `.smali`.
 * @property interceptorSignature The `.method` header of its `intercept` method.
 * @property requestClass The `okhttp3.Request` type, for example `ar/n`.
 * @property requestUrlField The Request field holding the URL.
 * @property requestUrlType The URL type the Request constructor takes.
 * @property requestUrlFieldType The type the field is declared with; R8 merges it to
 *   `Ljava/lang/Object;`, so the generated body reads it as that and casts to [requestUrlType].
 * @property chainClass The `okhttp3.Interceptor$Chain` type.
 * @property chainRequestField The Chain field holding the request it was handed.
 * @property chainProceedMethod The Chain method taking a Request and returning a Response.
 * @property chainIsInterface True when the Chain is an interface, so the call is `invoke-interface`.
 * @property responseClass The `okhttp3.Response` type.
 * @property responseConstructorDescriptor The Response constructor's parameter list and return
 *   type, for example `(Lar/n;Lm50/d0;...)V`.
 * @property headersType The `okhttp3.Headers` type as a descriptor, read from the Response
 *   constructor. The synthetic response needs a real, empty instance: every response has one, and
 *   callers dereference it without a null check.
 * @property emptyResponseClass The class carrying OkHttp's own empty `Headers` and `ResponseBody`
 *   constants, for example `n50/b`.
 * @property emptyHeadersField The static field of [headersType] to read.
 * @property emptyHeadersFieldType The field's declared type.
 * @property emptyBodyField The static field whose type implements the Response constructor's body
 *   parameter.
 * @property emptyBodyFieldType The field's declared type.
 * @property protocolClass The `okhttp3.Protocol` enum.
 * @property protocolHttp11Field The enum field holding `HTTP_1_1`.
 */
data class OkHttpProtocolNames(
    val interceptorClass: String,
    val interceptorSignature: String,
    val requestClass: String,
    val requestUrlField: String,
    val requestUrlType: String,
    val requestUrlFieldType: CharSequence,
    val chainClass: String,
    val chainRequestField: String,
    val chainProceedMethod: String,
    val chainIsInterface: Boolean,
    val responseClass: String,
    val responseConstructorDescriptor: String,
    val headersType: String,
    val emptyResponseClass: String,
    val emptyHeadersField: String,
    val emptyHeadersFieldType: String,
    val emptyBodyField: String,
    val emptyBodyFieldType: String,
    val protocolClass: String,
    val protocolHttp11Field: String
) {
    /** The Response type as a descriptor, for example `Lm50/i0;`. */
    val responseType: String get() = "L$responseClass;"

    /** The Chain type as a descriptor. */
    val chainType: String get() = "L$chainClass;"

    /** The Request type as a descriptor. */
    val requestType: String get() = "L$requestClass;"
}

/** Either the preceding names, or why they could not be read. */
sealed interface OkHttpResolution {
    /** The names, read from the target build. */
    data class Resolved(val names: OkHttpProtocolNames) : OkHttpResolution

    /** The reason this build cannot carry the interceptor, phrased for the user. */
    data class Unresolved(val reason: String) : OkHttpResolution
}

/**
 * Reads the obfuscated OkHttp names the blocklist interceptor needs from the target's DEX.
 *
 * The interceptor has to construct a `Response`, read the `Request` from the chain it is handed
 * and call the chain's `proceed`. All of those types and members are renamed by R8. Rather than
 * spelling any of them out, this walks the build's own class graph:
 *
 * 1. The `Protocol` enum is the class whose static initializer builds a constant from the
 *    `"http/1.1"` string and stores it in a static field of its own type.
 * 2. The `Response` class is the one with a constructor whose second parameter is that Protocol
 *    type and whose first is the `Request` type.
 * 3. The Response constructor also names `Headers` and `ResponseBody`; the empty header and body
 *    the synthetic response is built from are read from those types in the same build.
 * 4. The `Request` type's URL field is the first object field its five-parameter constructor
 *    writes.
 * 5. The `Chain` is the class with a Request field and a method from Request to Response.
 * 6. The stock resource-usage interceptor is the class with a Chain-to-Response method whose body
 *    calls a static helper with `(Chain, <stats>) -> Response`.
 *
 * 353.2.0 obfuscates OkHttp completely; there is no `Lokhttp3/...` descriptor to anchor on. This
 * is the shape-based resolution that replaces the old hardcoded names.
 */
object OkHttpNameResolver {

    private const val PROTOCOL_STRING = "http/1.1"
    private const val PROTOCOL_CONSTANT = "HTTP_1_1"
    private const val CLOSEABLE = "Ljava/io/Closeable;"
    private const val OBJECT_TYPE = "Ljava/lang/Object;"

    /**
     * Resolves [OkHttpProtocolNames] from [target], or explains why it cannot.
     *
     * @param apiLevel the opcode set to read the DEX with. Only affects how instructions are
     *   decoded, which the names this returns do not depend on.
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

        val classes = mutableListOf<ClassDef>()
        for ((dexName, dexBytes) in target.dexEntries) {
            val dexFile = loadedDexFiles.getOrPut(dexName) { DexBackedDexFile(opcodes, dexBytes) }
            classes += dexFile.classes
        }
        val byType = classes.associateBy { it.type }

        // 1. Protocol: the enum that builds HTTP/1.1 from the string and stores it in itself.
        val protocols = classes.mapNotNull { protocolClassOf(it) }
        if (protocols.isEmpty()) {
            return OkHttpResolution.Unresolved(
                "No class builds an HTTP/1.1 constant from the \"$PROTOCOL_STRING\" string, so the " +
                    "okhttp3 Protocol enum could not be identified."
            )
        }

        // 2. Response: a class whose constructor takes the Protocol as its second parameter and
        // the Request as its first, and which implements Closeable.
        var protocol: ProtocolCandidate? = null
        var response: ResponseCandidate? = null
        for (candidate in protocols) {
            val found = classes.asSequence()
                .filter { it.type != candidate.type }
                .filter { AccessFlags.INTERFACE.isSet(it.accessFlags).not() }
                .filter { isCloseable(it, byType) }
                .mapNotNull { responseClassOf(it, candidate.type) }
                .firstOrNull()
            if (found != null) {
                protocol = candidate
                response = found
                break
            }
        }
        if (protocol == null || response == null) {
            return OkHttpResolution.Unresolved(
                "The HTTP/1.1 enum exists but no okhttp3 Response constructor takes it together " +
                    "with a Request, so the Response shape could not be identified."
            )
        }

        // 3. The Response's empty Headers and ResponseBody constants. Every caller reads the
        //    headers without a null check, and the React Native bridge rejects a null body before
        //    it reports the response, so the synthetic 204 reads OkHttp's own empty pair rather
        //    than passing nulls.
        val emptyResponse = emptyResponseOf(classes, response.headersType, response.bodyType, byType)
            ?: return OkHttpResolution.Unresolved(
                "No class carries a public static ${response.headersType} field together with a " +
                    "${response.bodyType} implementation, so the empty response headers and " +
                    "body could not be identified."
            )

        // 4. Request URL: the first object field the Request's five-parameter constructor writes.
        val requestType = response.requestType
        val request = byType[requestType] ?: return OkHttpResolution.Unresolved(
            "$requestType is not in this APK, so the Request shape cannot be read."
        )
        val requestCtor = request.methods.firstOrNull { method ->
            method.parameterTypes.size == 5 &&
                method.parameterTypes[1].toString() == "Ljava/lang/String;" &&
                hasStringToString(byType[method.parameterTypes[0].toString()])
        } ?: return OkHttpResolution.Unresolved(
            "$requestType has no five-parameter constructor that stores a URL and a method name."
        )
        val urlType = requestCtor.parameterTypes[0].toString()
        val urlField = firstObjectFieldWritten(requestCtor, request)
            ?: return OkHttpResolution.Unresolved(
                "$requestType's constructor does not write an object field, so its URL field " +
                    "could not be named."
            )

        // 4. Chain: a class with a Request field and a Request -> Response method.
        val chain = classes.asSequence()
            .filter { it.type != response.responseType && it.type != requestType }
            .mapNotNull { chainClassOf(it, requestType, response.responseType) }
            .firstOrNull()
            ?: return OkHttpResolution.Unresolved(
                "No class carries a Request and a method from Request to Response, so the OkHttp " +
                    "chain could not be identified."
            )

        // 5. Interceptor: a Chain -> Response method whose body calls (Chain, stats) -> Response.
        val interceptor = classes.asSequence()
            .filter { it.type != chain.type && it.type != response.responseType }
            .mapNotNull { interceptorMethodOf(it, chain.type, response.responseType) }
            .firstOrNull()
            ?: return OkHttpResolution.Unresolved(
                "No resource-usage interceptor takes the resolved chain and returns the resolved " +
                    "Response, so there is nothing to rewrite."
            )

        return OkHttpResolution.Resolved(
            OkHttpProtocolNames(
                interceptorClass = interceptor.declaringClass.removePrefix("L").removeSuffix(";"),
                interceptorSignature = interceptor.signature,
                requestClass = requestType.removePrefix("L").removeSuffix(";"),
                requestUrlField = urlField.name,
                requestUrlType = urlType,
                requestUrlFieldType = urlField.type,
                chainClass = chain.type.removePrefix("L").removeSuffix(";"),
                chainRequestField = chain.requestField,
                chainProceedMethod = chain.proceedMethod,
                chainIsInterface = AccessFlags.INTERFACE.isSet(chain.accessFlags),
                responseClass = response.responseType.removePrefix("L").removeSuffix(";"),
                responseConstructorDescriptor = response.constructorDescriptor,
                headersType = response.headersType,
                emptyResponseClass =
                    emptyResponse.type.removePrefix("L").removeSuffix(";"),
                emptyHeadersField = emptyResponse.headersField.name,
                emptyHeadersFieldType = emptyResponse.headersField.type.toString(),
                emptyBodyField = emptyResponse.bodyField.name,
                emptyBodyFieldType = emptyResponse.bodyField.type.toString(),
                protocolClass = protocol.type.removePrefix("L").removeSuffix(";"),
                protocolHttp11Field = protocol.http11Field
            )
        )
    }

    private data class ProtocolCandidate(val type: String, val http11Field: String)

    private data class ResponseCandidate(
        val responseType: String,
        val requestType: String,
        val constructorDescriptor: String,
        val headersType: String,
        val bodyType: String
    )

    private data class EmptyResponseParts(
        val type: String,
        val headersField: FieldShape,
        val bodyField: FieldShape
    )

    private data class ChainCandidate(
        val type: String,
        val accessFlags: Int,
        val requestField: String,
        val proceedMethod: String
    )

    private data class InterceptorCandidate(
        val declaringClass: String,
        val signature: String
    )

    /** The HTTP/1.1 enum, when [classDef] is one. */
    private fun protocolClassOf(classDef: ClassDef): ProtocolCandidate? {
        val field = http11FieldOf(classDef) ?: return null
        return ProtocolCandidate(classDef.type, field)
    }

    /**
     * Finds the field [classDef] assigns its `HTTP_1_1` constant to, or null when [classDef] is
     * not the enum that holds the protocols.
     *
     * The class is accepted on two facts: its `<clinit>` builds a constant from the
     * `"http/1.1"` string, and it assigns a static field of the class's own type *after* the
     * `"HTTP_1_1"` marker. The enum writes six fields of its own type and only that marker
     * identifies which one is HTTP/1.1.
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

    /** True when [classDef] implements Closeable directly or through an interface it lists. */
    private fun isCloseable(classDef: ClassDef, byType: Map<String, ClassDef>): Boolean {
        if (CLOSEABLE in classDef.interfaces) return true
        val superclass = classDef.superclass ?: return false
        val parent = byType[superclass] ?: return false
        return CLOSEABLE in parent.interfaces
    }

    /** The Response shape, when [classDef]'s second constructor parameter is [protocolType]. */
    private fun responseClassOf(classDef: ClassDef, protocolType: String): ResponseCandidate? {
        val ctor = classDef.methods.firstOrNull { method ->
            method.name == "<init>" &&
                AccessFlags.PUBLIC.isSet(method.accessFlags) &&
                method.parameterTypes.size >= 10 &&
                method.parameterTypes.getOrNull(1)?.toString() == protocolType &&
                method.parameterTypes.first().toString() != protocolType
        } ?: return null
        val params = ctor.parameterTypes.map { it.toString() }
        // The okhttp3.Response constructor, in this order. A build that changes it needs a
        // generator change, not a guessed register layout.
        if (params.size != 13 || params[2] != "Ljava/lang/String;" || params[3] != "I" ||
            params[10] != "J" || params[11] != "J"
        ) return null
        return ResponseCandidate(
            responseType = classDef.type,
            requestType = params[0],
            constructorDescriptor = "(${params.joinToString("")})${ctor.returnType}",
            headersType = params[5],
            bodyType = params[6]
        )
    }

    /**
     * The class carrying OkHttp's own empty `Headers` and `ResponseBody` constants, or null when
     * this build has none.
     *
     * The supported build keeps both as public static fields on its `Util` class: the headers
     * field is declared as the Response constructor's headers type, and the body field is a
     * subtype of its body parameter. A build that moves or renames them is reported as unresolved
     * rather than written with another release's field names.
     */
    private fun emptyResponseOf(
        classes: List<ClassDef>,
        headersType: String,
        bodyType: String,
        byType: Map<String, ClassDef>
    ): EmptyResponseParts? {
        for (classDef in classes) {
            val publicStatics = classDef.fields.filter { field ->
                AccessFlags.STATIC.isSet(field.accessFlags) &&
                    AccessFlags.PUBLIC.isSet(field.accessFlags)
            }
            val headers = publicStatics.firstOrNull { it.type == headersType }
                ?: continue
            val body = publicStatics.firstOrNull { field ->
                field.type == bodyType ||
                    byType[field.type]?.let { extendsType(it, bodyType, byType) } == true
            } ?: continue
            if (headers.name == body.name) continue
            return EmptyResponseParts(
                type = classDef.type,
                headersField = FieldShape(headers.name, headers.type),
                bodyField = FieldShape(body.name, body.type)
            )
        }
        return null
    }

    /** True when [classDef] inherits from [type], directly or through its superclasses. */
    private fun extendsType(
        classDef: ClassDef,
        type: String,
        byType: Map<String, ClassDef>
    ): Boolean {
        val seen = mutableSetOf<String>()
        var current = classDef.superclass ?: return false
        while (seen.add(current)) {
            if (current == type) return true
            current = byType[current]?.superclass ?: return false
        }
        return false
    }

    /** True when [classDef] has an instance `toString()String`. */
    private fun hasStringToString(classDef: ClassDef?): Boolean =
        classDef?.methods?.any {
            it.name == "toString" &&
                it.parameterTypes.isEmpty() &&
                it.returnType == "Ljava/lang/String;"
        } == true

    /** A field reference's name and declared type. */
    private data class FieldShape(val name: String, val type: CharSequence)

    /** The first object field [constructor] writes on [declaringClass]. */
    private fun firstObjectFieldWritten(constructor: Method, declaringClass: ClassDef): FieldShape? {
        val instructions = constructor.implementation?.instructions ?: return null
        for (instruction in instructions) {
            if (instruction.opcode != Opcode.IPUT_OBJECT) continue
            val reference = (instruction as? ReferenceInstruction)?.reference as? FieldReference
                ?: continue
            if (reference.definingClass == declaringClass.type) {
                return FieldShape(reference.name, reference.type)
            }
        }
        return null
    }

    /** The Chain shape, when [classDef] carries [requestType] and proceeds to [responseType]. */
    private fun chainClassOf(
        classDef: ClassDef,
        requestType: String,
        responseType: String
    ): ChainCandidate? {
        val requestField = classDef.fields.firstOrNull { it.type == requestType } ?: return null
        val proceed = classDef.methods.firstOrNull { method ->
            method.name != "<init>" &&
                !AccessFlags.STATIC.isSet(method.accessFlags) &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes.first().toString() == requestType &&
                method.returnType == responseType
        } ?: return null
        return ChainCandidate(
            type = classDef.type,
            accessFlags = classDef.accessFlags,
            requestField = requestField.name,
            proceedMethod = proceed.name
        )
    }

    /**
     * The stock interceptor, when [classDef] has a Chain -> Response method that delegates to a
     * static `(Chain, stats) -> Response` helper. That helper is what records the byte counters;
     * the other OkHttp interceptors either call `proceed` directly or do not take the chain.
     */
    private fun interceptorMethodOf(
        classDef: ClassDef,
        chainType: String,
        responseType: String
    ): InterceptorCandidate? {
        for (method in classDef.methods) {
            if (method.name == "<init>" || AccessFlags.STATIC.isSet(method.accessFlags)) continue
            if (method.parameterTypes.size != 1) continue
            if (method.parameterTypes.first().toString() != chainType) continue
            if (method.returnType != responseType) continue

            val instructions = method.implementation?.instructions ?: continue
            val delegatesToStatsHelper = instructions.any { instruction ->
                if (instruction.opcode != Opcode.INVOKE_STATIC &&
                    instruction.opcode != Opcode.INVOKE_STATIC_RANGE
                ) return@any false
                val reference = (instruction as? ReferenceInstruction)?.reference
                    as? MethodReference ?: return@any false
                reference.parameterTypes.size == 2 &&
                    reference.parameterTypes.first().toString() == chainType &&
                    reference.returnType == responseType
            }
            if (!delegatesToStatsHelper) continue

            return InterceptorCandidate(
                declaringClass = classDef.type,
                signature = methodSignatureOf(method)
            )
        }
        return null
    }

    /** `.method <access> <name><descriptor>` for [method], matching its smali header. */
    private fun methodSignatureOf(method: Method): String {
        val access = buildList {
            if (AccessFlags.PUBLIC.isSet(method.accessFlags)) add("public")
            if (AccessFlags.PRIVATE.isSet(method.accessFlags)) add("private")
            if (AccessFlags.PROTECTED.isSet(method.accessFlags)) add("protected")
            if (AccessFlags.STATIC.isSet(method.accessFlags)) add("static")
            if (AccessFlags.FINAL.isSet(method.accessFlags)) add("final")
            if (AccessFlags.SYNCHRONIZED.isSet(method.accessFlags)) add("declared-synchronized")
        }.joinToString(" ")
        val descriptor = "(${method.parameterTypes.joinToString("")})${method.returnType}"
        return ".method $access ${method.name}$descriptor".replace("  ", " ")
    }
}
