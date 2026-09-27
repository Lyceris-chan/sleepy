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
 * PatchSet: User-toggleable group of related patches
 */
data class PatchSet(
    val id: String,
    val label: String,
    val description: String,
    val smaliPatches: List<SmaliPatch> = emptyList(),
    val hermesPatches: List<HermesPatch> = emptyList()
)
