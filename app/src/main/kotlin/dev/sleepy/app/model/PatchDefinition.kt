package dev.sleepy.app.model

/**
 * A change to one method in a smali file, applied in memory and selected by signature, anchor
 * text, or switch case.
 *
 * A patch targets one of three locations in a smali file: a packed-switch case body, an anchor
 * block, or a whole method body identified by signature.
 *
 * @property title The step name shown for this patch, or null to fall back to the targeted
 *   signature or path.
 * @property explanation The explanation shown for this patch, or null when it records none.
 * @property dexName The DEX entry that holds the target class, for example `classes3.dex`, or null
 *   when the pipeline resolves the entry from the target APK.
 * @property smaliPath The path of the smali file inside the disassembled DEX.
 * @property methodSignature The signature that identifies the method body to replace, or null when
 *   the patch targets an anchor block or a switch case.
 * @property replacementBody The body that replaces the method [methodSignature] identifies, or
 *   null when the patch targets an anchor block or a switch case.
 * @property anchor The exact text the patch searches for in [smaliPath], or null when the patch
 *   targets a method body or a switch case.
 * @property replacement The text that replaces [anchor], or null when the patch targets a method
 *   body or a switch case.
 * @property switchCaseLabel The label of the packed-switch case to replace, or null when the patch
 *   targets a method body or an anchor block.
 * @property switchCaseBody The body that replaces the case [switchCaseLabel] names, or null when
 *   the patch targets a method body or an anchor block.
 * @property versionTag The app version the patch was written against, or null when the patch
 *   applies to every version the set supports.
 */
data class SmaliPatch(
    val title: String? = null,
    val explanation: String? = null,
    val dexName: String? = null,
    val smaliPath: String,
    val methodSignature: String? = null,
    val replacementBody: String? = null,
    val anchor: String? = null,
    val replacement: String? = null,
    val switchCaseLabel: String? = null,
    val switchCaseBody: String? = null,
    val versionTag: String? = null
)

/**
 * The value a stubbed Hermes function leaves in its return register.
 *
 * Callers of a patched function still receive whatever the stub produces, so the shape has to
 * match what the original returned: a function whose callers `await` it must still get a resolved
 * promise, and a React component must still get something renderable. These map one-to-one onto
 * the tables in the reference `core.py` (`TARGETS`, `PROMISE_TARGETS`, `ZERO_TARGETS`,
 * `NULL_TARGETS`, `FALSE_TARGETS`, `OBJECT_FALSE_TARGETS`).
 */
enum class HermesStubShape {
    /** `LoadConstUndefined r0; Ret r0` */
    UNDEFINED,

    /** `LoadConstFalse r0; Ret r0` */
    FALSE,

    /** `LoadConstTrue r0; Ret r0` */
    TRUE,

    /** `LoadConstNull r0; Ret r0` */
    NULL,

    /** `LoadConstUInt8 r0, 0; Ret r0` */
    ZERO,

    /** `Promise.resolve()`—for functions whose callers await the result. */
    PROMISE
}

/**
 * A bytecode function replacement in a Hermes bundle.
 *
 * [functionId] is per-bundle and changes on every Discord release, so it must be re-mapped
 * against the target bundle before use—the reference does this with `remap_hermes.py`.
 * [hasmStub] records the reference's source text for auditability; the bytes written come from
 * [stubShape], which is explicit, so a shape the engine cannot express produces an error instead
 * of a different value.
 *
 * @property title The step name shown for this patch, or null to build one from the function name
 *   and id.
 * @property explanation The explanation shown for this patch, or null when it records none.
 * @property functionId The Hermes function id to replace.
 * @property functionName The function's name, used in the default step name.
 * @property stubShape The value the stub leaves in the return register.
 * @property hasmStub The reference's source text for this stub, recorded for auditability.
 */
data class HermesPatch(
    val title: String? = null,
    val explanation: String? = null,
    val functionId: String,
    val functionName: String,
    val stubShape: HermesStubShape,
    val hasmStub: String = ""
)

/**
 * The target APK as a [PatchGenerator] reads it.
 *
 * A generated patch cannot be a static string: R8 renames the internals it has to spell out—
 * the OkHttp types behind the blocklist interceptor, for example—so the names differ on every
 * release. The generator therefore reads the build the user selected, and the patch is written
 * against what is in that build.
 *
 * @property classToDexIndex Class descriptor (for example `Lokhttp3/Response;`) to the DEX entry
 *   name holding that class.
 * @property dexEntries DEX entry name (for example `classes3.dex`) to its bytes.
 */
class TargetApk(
    val classToDexIndex: Map<String, String>,
    val dexEntries: Map<String, ByteArray>
)

/**
 * What a [PatchGenerator] made of a target APK.
 *
 * @property patches The patches to apply. Empty when the target build cannot support the set.
 * @property skipReason Why nothing could be generated. The pipeline reports it as the reason the
 *   set was skipped, so a build whose obfuscated names moved is reported as not patched instead
 *   of being left alone without a report—the failure mode this mechanism exists to prevent.
 */
data class GeneratedPatches(
    val patches: List<SmaliPatch>,
    val skipReason: String? = null
)

/**
 * A patch that is written against the target APK rather than ahead of time.
 */
fun interface PatchGenerator {
    /** Produces this set's patches for [target], or the reason it produced none. */
    fun generate(target: TargetApk): GeneratedPatches
}

/**
 * A [PatchGenerator] whose patch text is written from the target APK.
 *
 * Not every generator does that. A generator for a set whose edits are switched one at a time
 * selects among edits written down ahead of time, and the method it produces is assembled from
 * that text; a generator for a method that names this build's own internals writes the text
 * itself. The UI states this distinction: "the names it has to spell out are read from the APK
 * being patched" applies only to the second kind, so stating it about the first kind is a false
 * claim about the build. A generator that writes its own text therefore declares it here rather
 * than having the UI infer it from whether the set happens to hold patches.
 */
interface TargetWrittenGenerator

/**
 * A [PatchGenerator] that can also generate for a subset of its set's items.
 *
 * Generators were written to produce the whole set, because the set was the only switch there
 * was. Per-item selection requires a generator to accept a subset: the blocklist's interceptor is
 * compiled by walking its rule table, so honoring a subset means compiling a method that scans
 * for fewer rules. A generator that implements both this and [PatchGenerator] stays usable with
 * or without a selection, which is how the pipeline calls it.
 */
fun interface SelectivePatchGenerator {
    /** Produces this set's patches for [target], restricted to what [selection] switches on. */
    fun generate(target: TargetApk, selection: PatchSelection): GeneratedPatches
}

/**
 * A group of related patches that the user can switch on or off.
 *
 * A set can hold static patches, a generator, or both, and the pipeline applies the union.
 *
 * @property id The set's identifier, used in saved selections and in item keys.
 * @property label The name the UI shows for the set.
 * @property description The description the UI shows for the set.
 * @property smaliPatches The patches written ahead of time for this set.
 * @property hermesPatches The Hermes bundle patches written ahead of time for this set.
 * @property generator The generator for patches that cannot be written down ahead of time, or
 *   null when the set has none.
 */
data class PatchSet(
    val id: String,
    val label: String,
    val description: String,
    val smaliPatches: List<SmaliPatch> = emptyList(),
    val hermesPatches: List<HermesPatch> = emptyList(),
    val generator: PatchGenerator? = null
)
