package dev.sleepy.app.engine

import dev.sleepy.app.model.SmaliPatch
import dev.sleepy.app.model.StepResult
import dev.sleepy.app.model.StepStatus

object SmaliPatcher {

    /**
     * Replaces the method matching [patch.methodSignature] through its closing `.end method`
     * with [patch.replacementBody] inside the memory-cached [smaliFiles] map.
     */
    fun apply(
        smaliFiles: MutableMap<String, String>,
        patch: SmaliPatch
    ): StepResult {
        val content = smaliFiles[patch.smaliPath]
            ?: return StepResult(
                label = patch.smaliPath,
                status = StepStatus.SKIP,
                detail = "Smali file not found in disassembled DEX"
            )

        val sigIndex = content.indexOf(patch.methodSignature)
        if (sigIndex == -1) {
            return StepResult(
                label = "${patch.smaliPath} :: ${patch.methodSignature}",
                status = StepStatus.SKIP,
                detail = "Anchor signature not found"
            )
        }

        val endIndex = content.indexOf(".end method", sigIndex)
        if (endIndex == -1) {
            return StepResult(
                label = "${patch.smaliPath} :: ${patch.methodSignature}",
                status = StepStatus.FAIL,
                detail = "Closing .end method not found after signature"
            )
        }

        val endOfBlock = endIndex + ".end method".length
        val patched = content.substring(0, sigIndex) +
                patch.replacementBody +
                content.substring(endOfBlock)

        smaliFiles[patch.smaliPath] = patched

        return StepResult(
            label = "${patch.smaliPath} :: ${patch.methodSignature}",
            status = StepStatus.OK
        )
    }
}
