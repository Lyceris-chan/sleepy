package dev.sleepy.app.engine

import android.content.Context
import dev.sleepy.app.model.HermesPatch
import dev.sleepy.app.model.HermesStubShape
import dev.sleepy.app.model.StepResult
import dev.sleepy.app.model.StepStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

object HermesPatcher {

    /**
     * Hermes bytecode magic: 0x1f1903c103bc1fc6 (in little-endian uint64).
     */
    val HERMES_MAGIC = byteArrayOf(
        0xc6.toByte(), 0x1f.toByte(), 0xbc.toByte(), 0x03.toByte(),
        0xc1.toByte(), 0x03.toByte(), 0x19.toByte(), 0x1f.toByte()
    )

    private const val NOP_ASYNC_BREAK_CHECK = 0x7E.toByte()

    // HBC v98 / Modern opcodes
    private val STUB_LOAD_CONST_UNDEFINED = byteArrayOf(0x93.toByte(), 0x00, 0x76.toByte(), 0x00) // LoadConstUndefined r0; Ret r0
    private val STUB_LOAD_CONST_FALSE = byteArrayOf(0x96.toByte(), 0x00, 0x76.toByte(), 0x00)     // LoadConstFalse r0; Ret r0
    private val STUB_LOAD_CONST_NULL = byteArrayOf(0x94.toByte(), 0x00, 0x76.toByte(), 0x00)      // LoadConstNull r0; Ret r0
    private val STUB_LOAD_CONST_TRUE = byteArrayOf(0x95.toByte(), 0x00, 0x76.toByte(), 0x00)      // LoadConstTrue r0; Ret r0

    /**
     * Validates whether [bytes] begins with the Hermes bytecode magic header.
     */
    fun isHermesBytecode(bytes: ByteArray): Boolean {
        if (bytes.size < 128) return false
        for (i in HERMES_MAGIC.indices) {
            if (bytes[i] != HERMES_MAGIC[i]) return false
        }
        return true
    }

    /**
     * Recomputes and writes the SHA-1 footer (last 20 bytes of bundle).
     */
    fun recomputeSha1Footer(bundleBytes: ByteArray) {
        if (bundleBytes.size < 20) return
        val md = MessageDigest.getInstance("SHA-1")
        md.update(bundleBytes, 0, bundleBytes.size - 20)
        val sha1 = md.digest()
        System.arraycopy(sha1, 0, bundleBytes, bundleBytes.size - 20, 20)
    }

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
            recomputeSha1Footer(patched)

            return patched to StepResult(
                title = "Nullifying JS Sentry DSN",
                explanation = "Replaces Sentry envelope ingest endpoint with invalid 0.0.0.0 in JavaScript bytecode to drop crash telemetry",
                technicalTarget = "assets/index.android.bundle :: sentry.io/api -> https://0.0.0.0/...",
                status = StepStatus.OK
            )
        }
        return bundleBytes to null
    }

    /**
     * Patches a Hermes function in-place in Modern12 (HBC v97+) bytecode without moving tables or offsets.
     * Rewrites function bytecode prologue with the stub and pads remaining bytes with AsyncBreakCheck (0x7E).
     */
    fun patchFunctionInPlace(bundleBytes: ByteArray, patch: HermesPatch): StepResult {
        val title = patch.title ?: "hermes: ${patch.functionName} (fn ${patch.functionId})"
        val explanation = patch.explanation
        val technicalTarget = "Hermes fn ${patch.functionId} (${patch.functionName}) -> ${patch.hasmStub.trim().lines().firstOrNull() ?: "stub"}"

        if (!isHermesBytecode(bundleBytes)) {
            return StepResult(
                title = title,
                explanation = explanation,
                technicalTarget = technicalTarget,
                status = StepStatus.SKIP,
                detail = "Asset is not a Hermes bytecode bundle"
            )
        }

        val fid = patch.functionId.toIntOrNull()
            ?: return StepResult(
                title = title,
                explanation = explanation,
                technicalTarget = technicalTarget,
                status = StepStatus.FAIL,
                detail = "Invalid function ID: ${patch.functionId}"
            )

        val location = HermesFunctionTable.locate(bundleBytes, fid)
        if (location == null) {
            return StepResult(
                title = title,
                explanation = explanation,
                technicalTarget = technicalTarget,
                status = StepStatus.FAIL,
                detail = "Function $fid could not be located in this bundle's function table, so nothing was written. " +
                    "Hermes does not document where that table lives or how its entries are packed, and guessing was " +
                    "previously corrupting the JavaScript bytecode."
            )
        }

        val bodyOffset = location.bodyOffset
        val bcSize = location.bytecodeSize

        // The shape is stated by the patch, never inferred from its documentation text.
        // Inferring it silently turned an awaiting caller's promise into `undefined`, which
        // is the failure mode the reference's PROMISE_TARGETS table exists to prevent.
        val stub = when (patch.stubShape) {
            HermesStubShape.UNDEFINED -> STUB_LOAD_CONST_UNDEFINED
            HermesStubShape.FALSE -> STUB_LOAD_CONST_FALSE
            HermesStubShape.TRUE -> STUB_LOAD_CONST_TRUE
            HermesStubShape.NULL -> STUB_LOAD_CONST_NULL
            HermesStubShape.ZERO, HermesStubShape.PROMISE -> return StepResult(
                title = title,
                explanation = explanation,
                technicalTarget = technicalTarget,
                status = StepStatus.FAIL,
                detail = "This patch needs a ${patch.stubShape.name.lowercase()}-shaped stub. That encoding has not been " +
                    "verified against a real Hermes bundle yet, so no bytes were written rather than writing an " +
                    "unverified return value into the bundle."
            )
        }

        val start = bodyOffset
        val len = bcSize

        if (start < 0 || start + len > bundleBytes.size) {
            return StepResult(
                title = title,
                explanation = explanation,
                technicalTarget = technicalTarget,
                status = StepStatus.FAIL,
                detail = "Function bytecode bounds [$start..${start + len}] exceed bundle size (${bundleBytes.size})"
            )
        }

        if (len < stub.size) {
            return StepResult(
                title = title,
                explanation = explanation,
                technicalTarget = technicalTarget,
                status = StepStatus.SKIP,
                detail = "Bytecode size ($len bytes) is smaller than stub (${stub.size} bytes)"
            )
        }

        // Write stub in-place and pad remainder with AsyncBreakCheck (0x7E) NOPs
        System.arraycopy(stub, 0, bundleBytes, start, stub.size)
        for (i in (start + stub.size) until (start + len)) {
            bundleBytes[i] = NOP_ASYNC_BREAK_CHECK
        }

        return StepResult(
            title = title,
            explanation = explanation,
            technicalTarget = technicalTarget,
            status = StepStatus.OK
        )
    }

    fun applyPatches(
        bundleBytes: ByteArray,
        patches: List<HermesPatch>,
        onPatchStart: ((HermesPatch) -> Unit)? = null
    ): Pair<ByteArray, List<StepResult>> {
        val results = mutableListOf<StepResult>()

        if (!isHermesBytecode(bundleBytes)) {
            results.add(
                StepResult(
                    label = "hermes: Bundle inspection",
                    status = StepStatus.SKIP,
                    detail = "Asset is not a Hermes bytecode bundle"
                )
            )
            return bundleBytes to results
        }

        // 1. Pure Kotlin length-preserving in-place Sentry DSN neutralization
        val (afterDsnBundle, dsnResult) = nullifySentryDsn(bundleBytes)
        if (dsnResult != null) {
            results.add(dsnResult)
        }

        val currentBundle = afterDsnBundle.copyOf()
        var patchedCount = 0

        // 2. Pure Kotlin Modern12 in-place HBC function patching
        for (patch in patches) {
            onPatchStart?.invoke(patch)
            val result = patchFunctionInPlace(currentBundle, patch)
            results.add(result)
            if (result.status == StepStatus.OK) {
                patchedCount++
            }
        }

        // 3. Recompute SHA-1 footer if any function modifications were made
        if (patchedCount > 0) {
            recomputeSha1Footer(currentBundle)
        }

        return currentBundle to results
    }

    suspend fun applyPatches(
        context: Context,
        bundleBytes: ByteArray,
        patches: List<HermesPatch>,
        onPatchStart: ((HermesPatch) -> Unit)? = null
    ): Pair<ByteArray, List<StepResult>> = withContext(Dispatchers.IO) {
        applyPatches(bundleBytes, patches, onPatchStart)
    }
}
