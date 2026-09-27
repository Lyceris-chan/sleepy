package dev.sleepy.app.engine

import android.content.Context
import dev.sleepy.app.model.HermesPatch
import dev.sleepy.app.model.StepResult
import dev.sleepy.app.model.StepStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

object HermesPatcher {

    /**
     * In-place length-preserving Sentry DSN neutralization and Hermes bytecode SHA-1 footer re-hash.
     * Pure Kotlin implementation matching core.py patch_js_sentry_dsn.
     */
    fun nullifySentryDsn(bundleBytes: ByteArray): Pair<ByteArray, StepResult?> {
        val bundleString = String(bundleBytes, Charsets.ISO_8859_1)
        val regex = Regex("https://[0-9a-z]+\\.ingest\\.sentry\\.io/api/\\d+/envelope/\\?sentry_version=\\d+&sentry_key=[0-9a-f]+&sentry_client=[A-Za-z0-9._%]+")
        val match = regex.find(bundleString)
        if (match != null) {
            val orig = match.value
            val repl = "https://0.0.0.0/" + "0".repeat(orig.length - "https://0.0.0.0/".length)
            val replBytes = repl.toByteArray(Charsets.ISO_8859_1)
            val startIndex = match.range.first

            val patched = bundleBytes.copyOf()
            System.arraycopy(replBytes, 0, patched, startIndex, replBytes.size)

            // Recompute Hermes SHA-1 footer (last 20 bytes of file)
            val md = MessageDigest.getInstance("SHA-1")
            md.update(patched, 0, patched.size - 20)
            val sha1Footer = md.digest()
            System.arraycopy(sha1Footer, 0, patched, patched.size - 20, 20)

            return patched to StepResult("hermes: In-place Sentry DSN nulling & SHA-1 footer re-hash", StepStatus.OK)
        }
        return bundleBytes to null
    }

    suspend fun applyPatches(
        context: Context,
        bundleBytes: ByteArray,
        patches: List<HermesPatch>
    ): Pair<ByteArray, List<StepResult>> = withContext(Dispatchers.IO) {
        val results = mutableListOf<StepResult>()

        // 1. Pure Kotlin length-preserving in-place Sentry DSN neutralization
        val (afterDsnBundle, dsnResult) = nullifySentryDsn(bundleBytes)
        if (dsnResult != null) {
            results.add(dsnResult)
        }

        var currentBundle = afterDsnBundle

        // 2. Binary HBC function patching via libhermes_decomp.so if present
        val hermesBinary = File(context.applicationInfo.nativeLibraryDir, "libhermes_decomp.so")
        if (!hermesBinary.exists() || !hermesBinary.canExecute()) {
            results.add(
                StepResult(
                    label = "hermes: Function AST stubs (${patches.size} targets)",
                    status = StepStatus.SKIP,
                    detail = "Native telemetry suppressed via pure Kotlin in-place DSN nulling & DEX gates. On-device AST rewriting requires desktop x86_64 hermes-decomp toolchain."
                )
            )
            return@withContext currentBundle to results
        }

        val workDir = File(context.cacheDir, "hermes_work_${System.currentTimeMillis()}").apply { mkdirs() }
        val bundleFile = File(workDir, "index.android.bundle").apply { writeBytes(currentBundle) }

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

            val patchedBytes = if (bundleFile.exists()) bundleFile.readBytes() else currentBundle
            patchedBytes to results
        } finally {
            workDir.deleteRecursively()
        }
    }
}
