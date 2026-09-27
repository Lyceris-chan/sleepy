package dev.sleepy.app.engine

import dev.sleepy.app.model.SmaliPatch
import dev.sleepy.app.model.StepResult
import dev.sleepy.app.model.StepStatus

object SmaliPatcher {

    /**
     * Applies a [SmaliPatch] to the memory-cached [smaliFiles] map.
     * Supports:
     * 1. Switch-case body replacement (e.g., :pswitch_160 in packed-switch dispatchers)
     * 2. Exact anchor block replacement / insertion
     * 3. Whole method body replacement by signature through .end method
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

        // 1. Switch-case replacement
        if (patch.switchCaseLabel != null && patch.switchCaseBody != null) {
            val lines = content.lines().toMutableList()
            var start = -1
            for (i in lines.indices) {
                if (lines[i].trim() == patch.switchCaseLabel.trim()) {
                    start = i
                    break
                }
            }
            if (start == -1) {
                return StepResult(
                    label = "${patch.smaliPath} :: case ${patch.switchCaseLabel}",
                    status = StepStatus.SKIP,
                    detail = "Switch case label not found: ${patch.switchCaseLabel}"
                )
            }

            var end = -1
            for (j in (start + 1) until lines.size) {
                val trimmed = lines[j].trim()
                if (trimmed.startsWith(":pswitch_") && !trimmed.startsWith(":pswitch_data")) {
                    end = j
                    break
                }
            }
            if (end == -1) {
                return StepResult(
                    label = "${patch.smaliPath} :: case ${patch.switchCaseLabel}",
                    status = StepStatus.FAIL,
                    detail = "Closing switch case label not found after ${patch.switchCaseLabel}"
                )
            }

            val newBlockLines = patch.switchCaseBody.trimEnd().lines()
            lines.subList(start, end).clear()
            lines.addAll(start, newBlockLines)
            smaliFiles[patch.smaliPath] = lines.joinToString("\n")

            return StepResult(
                label = "${patch.smaliPath} :: case ${patch.switchCaseLabel}",
                status = StepStatus.OK
            )
        }

        // 2. Anchor replacement / insertion
        if (patch.anchor != null && patch.replacement != null) {
            if (!content.contains(patch.anchor)) {
                return StepResult(
                    label = "${patch.smaliPath} :: anchor patch",
                    status = StepStatus.SKIP,
                    detail = "Anchor pattern not found in file"
                )
            }

            val patched = content.replace(patch.anchor, patch.replacement)
            smaliFiles[patch.smaliPath] = patched

            return StepResult(
                label = "${patch.smaliPath} :: anchor patch",
                status = StepStatus.OK
            )
        }

        // 3. Whole method replacement by signature
        if (patch.methodSignature != null && patch.replacementBody != null) {
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

        return StepResult(
            label = patch.smaliPath,
            status = StepStatus.FAIL,
            detail = "Invalid patch configuration: no methodSignature, anchor, or switchCase defined"
        )
    }
}
