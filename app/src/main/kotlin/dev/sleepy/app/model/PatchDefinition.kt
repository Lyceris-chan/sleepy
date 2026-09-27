package dev.sleepy.app.model

/**
 * SmaliPatch: Precise in-memory method replacement by anchor signature
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
 * Callers of a patched function still receive whatever the stub produces, so the shape has
 * to match what the original returned: a function whose callers `await` it must still get a
 * resolved promise, and a React component must still get something renderable. These map
 * one-to-one onto the tables in the reference `core.py` (`TARGETS`, `PROMISE_TARGETS`,
 * `ZERO_TARGETS`, `NULL_TARGETS`, `FALSE_TARGETS`, `OBJECT_FALSE_TARGETS`).
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

    /** `Promise.resolve()` — for functions whose callers await the result. */
    PROMISE
}

/**
 * HermesPatch: HBC bytecode function replacement.
 *
 * [functionId] is per-bundle and shifts on every Discord release, so it must be re-mapped
 * against the target bundle before it can be trusted — the reference does this with
 * `remap_hermes.py`. [hasmStub] records the reference's source text for auditability; the
 * bytes written come from [stubShape], which is explicit so a shape the engine cannot
 * express fails loudly instead of silently becoming something else.
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
 * The target APK as a [PatchGenerator] sees it.
 *
 * A generated patch cannot be a static string: R8 renames the internals it has to spell out —
 * the OkHttp types behind the blocklist interceptor, for instance — so the names differ on
 * every release. The generator therefore reads the build the user actually selected, and the
 * patch is written against what is in it.
 *
 * @property classToDexIndex class descriptor (e.g. `Lokhttp3/Response;`) to the DEX entry name
 *   holding that class.
 * @property dexEntries DEX entry name (e.g. `classes3.dex`) to its bytes.
 */
class TargetApk(
    val classToDexIndex: Map<String, String>,
    val dexEntries: Map<String, ByteArray>
)

/**
 * What a [PatchGenerator] made of a target APK.
 *
 * @property patches the patches to apply. Empty when the target build cannot support the set.
 * @property skipReason why nothing could be generated. The pipeline reports it as the reason the
 *   set was skipped, so a build whose obfuscated names moved is visibly not patched rather than
 *   silently left alone — the failure mode this whole mechanism exists to prevent.
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
 * A [PatchGenerator] that can also generate for a subset of its set's items.
 *
 * Generators were written to produce the whole set, because the set was the only switch there was.
 * Per-item selection needs to reach inside that: the blocklist's interceptor is compiled by
 * walking its rule table, so honouring a subset means compiling a method that scans for fewer
 * rules. A generator that implements both this and [PatchGenerator] stays usable with or without
 * a selection, which is how the pipeline calls it.
 */
fun interface SelectivePatchGenerator {
    /** Produces this set's patches for [target], restricted to what [selection] switches on. */
    fun generate(target: TargetApk, selection: PatchSelection): GeneratedPatches
}

/**
 * PatchSet: User-toggleable group of related patches
 *
 * [generator] is for the patches that cannot be written down ahead of time; a set may have
 * static patches, a generator, or both, and the pipeline applies the union.
 */
data class PatchSet(
    val id: String,
    val label: String,
    val description: String,
    val smaliPatches: List<SmaliPatch> = emptyList(),
    val hermesPatches: List<HermesPatch> = emptyList(),
    val generator: PatchGenerator? = null
)
