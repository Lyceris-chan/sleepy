package dev.sleepy.app.engine

import android.content.Context
import dev.sleepy.app.model.HermesPatch
import dev.sleepy.app.model.StepResult
import dev.sleepy.app.model.StepStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object HermesPatcher {

    suspend fun applyPatches(
        context: Context,
        bundleBytes: ByteArray,
        patches: List<HermesPatch>
    ): Pair<ByteArray, List<StepResult>> = withContext(Dispatchers.IO) {
        val hermesBinary = File(context.applicationInfo.nativeLibraryDir, "libhermes_decomp.so")
        val results = mutableListOf<StepResult>()

        if (!hermesBinary.exists() || !hermesBinary.canExecute()) {
            patches.forEach { patch ->
                results.add(
                    StepResult(
                        label = "hermes: ${patch.functionName} (fn ${patch.functionId})",
                        status = StepStatus.SKIP,
                        detail = "libhermes_decomp.so not available or not executable on this device architecture"
                    )
                )
            }
            return@withContext bundleBytes to results
        }

        val workDir = File(context.cacheDir, "hermes_work_${System.currentTimeMillis()}").apply { mkdirs() }
        val bundleFile = File(workDir, "index.android.bundle").apply { writeBytes(bundleBytes) }

        try {
            for (patch in patches) {
                val hasmFile = File(workDir, "stub_${patch.functionId}.hasm").apply {
                    writeText(patch.hasmStub.trimIndent() + "\n", Charsets.UTF_8)
                }
                val outputFile = File(workDir, "out_${patch.functionId}.bundle")

                try {
                    val process = ProcessBuilder(
                        hermesBinary.absolutePath,
                        "patch-function",
                        bundleFile.absolutePath,
                        "--function", patch.functionId,
                        "--hasm", hasmFile.absolutePath,
                        "-o", outputFile.absolutePath
                    )
                        .redirectErrorStream(true)
                        .start()

                    val outputText = process.inputStream.bufferedReader().readText()
                    val exitCode = process.waitFor()

                    if (exitCode == 0 && outputFile.exists() && outputFile.length() > 0) {
                        outputFile.copyTo(bundleFile, overwrite = true)
                        results.add(
                            StepResult(
                                label = "hermes: ${patch.functionName} (fn ${patch.functionId})",
                                status = StepStatus.OK
                            )
                        )
                    } else {
                        results.add(
                            StepResult(
                                label = "hermes: ${patch.functionName} (fn ${patch.functionId})",
                                status = StepStatus.SKIP,
                                detail = outputText.take(200).ifBlank { "Function not found in HBC" }
                            )
                        )
                    }
                } catch (e: Exception) {
                    results.add(
                        StepResult(
                            label = "hermes: ${patch.functionName} (fn ${patch.functionId})",
                            status = StepStatus.FAIL,
                            detail = e.message
                        )
                    )
                } finally {
                    hasmFile.delete()
                    outputFile.delete()
                }
            }

            val patchedBytes = if (bundleFile.exists()) bundleFile.readBytes() else bundleBytes
            patchedBytes to results
        } finally {
            workDir.deleteRecursively()
        }
    }
}
