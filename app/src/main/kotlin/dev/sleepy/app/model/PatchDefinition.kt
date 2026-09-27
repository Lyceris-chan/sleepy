package dev.sleepy.app.model

/**
 * SmaliPatch: Precise in-memory method replacement by anchor signature
 */
data class SmaliPatch(
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
 * HermesPatch: HBC bytecode function replacement via hermes-decomp
 */
data class HermesPatch(
    val functionId: String,
    val functionName: String,
    val hasmStub: String
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
