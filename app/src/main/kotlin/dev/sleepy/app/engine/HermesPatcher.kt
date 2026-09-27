package dev.sleepy.app.engine

import android.content.Context
import dev.sleepy.app.model.HermesPatch
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

    private const val HEADER_SLOT_START = 128
    private const val HEADER_SLOT_SIZE = 12
    private const val FLAG_OVERFLOWED = 0x20
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

    private fun readU32Le(bytes: ByteArray, offset: Int): Long {
        val b0 = bytes[offset].toLong() and 0xFF
        val b1 = bytes[offset + 1].toLong() and 0xFF
        val b2 = bytes[offset + 2].toLong() and 0xFF
        val b3 = bytes[offset + 3].toLong() and 0xFF
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
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

            return patched to StepResult("hermes: In-place Sentry DSN nulling & SHA-1 footer re-hash", StepStatus.OK)
        }
        return bundleBytes to null
    }

    /**
     * Patches a Hermes function in-place in Modern12 (HBC v97+) bytecode without moving tables or offsets.
     * Rewrites function bytecode prologue with the stub and pads remaining bytes with AsyncBreakCheck (0x7E).
     */
    fun patchFunctionInPlace(bundleBytes: ByteArray, patch: HermesPatch): StepResult {
        if (!isHermesBytecode(bundleBytes)) {
            return StepResult(
                label = "hermes: ${patch.functionName} (fn ${patch.functionId})",
                status = StepStatus.SKIP,
                detail = "Asset is not a Hermes bytecode bundle"
            )
        }

        val fid = patch.functionId.toIntOrNull()
            ?: return StepResult(
                label = "hermes: ${patch.functionName} (fn ${patch.functionId})",
                status = StepStatus.FAIL,
                detail = "Invalid function ID: ${patch.functionId}"
            )

        val slot = HEADER_SLOT_START + fid * HEADER_SLOT_SIZE
        if (slot + HEADER_SLOT_SIZE > bundleBytes.size) {
            return StepResult(
                label = "hermes: ${patch.functionName} (fn $fid)",
                status = StepStatus.SKIP,
                detail = "Function ID $fid header slot is out of bounds in HBC table"
            )
        }

        val flags = bundleBytes[slot + 11].toInt() and 0xFF
        val isOverflowed = (flags and FLAG_OVERFLOWED) != 0

        val bodyOffset: Long
        val bcSize: Long

        if (isOverflowed) {
            val b0 = bundleBytes[slot + 0].toInt() and 0xFF
            val b1 = bundleBytes[slot + 1].toInt() and 0xFF
            val b2 = bundleBytes[slot + 2].toInt() and 0xFF
            val b5 = bundleBytes[slot + 5].toInt() and 0xFF
            val b6 = bundleBytes[slot + 6].toInt() and 0xFF
            val offset = b0 or (b1 shl 8) or (b2 shl 16)
            val name = ((b5 ushr 6) and 0x03) or ((b6 and 0x3F) shl 2)
            val largePtr = ((name shl 24) or offset).toLong() and 0xFFFFFFFFL

            if (largePtr.toInt() + 16 > bundleBytes.size) {
                return StepResult(
                    label = "hermes: ${patch.functionName} (fn $fid)",
                    status = StepStatus.FAIL,
                    detail = "Large header pointer ($largePtr) out of bounds"
                )
            }
            bodyOffset = readU32Le(bundleBytes, largePtr.toInt() + 0)
            bcSize = readU32Le(bundleBytes, largePtr.toInt() + 12)
        } else {
            val b0 = bundleBytes[slot + 0].toInt() and 0xFF
            val b1 = bundleBytes[slot + 1].toInt() and 0xFF
            val b2 = bundleBytes[slot + 2].toInt() and 0xFF
            val b3 = bundleBytes[slot + 3].toInt() and 0xFF
            val b4 = bundleBytes[slot + 4].toInt() and 0xFF
            val b5 = bundleBytes[slot + 5].toInt() and 0xFF
            bodyOffset = (b0 or (b1 shl 8) or (b2 shl 16) or ((b3 and 0x01) shl 24)).toLong() and 0xFFFFFFFFL
            bcSize = (b4 or ((b5 and 0x3F) shl 8)).toLong()
        }

        val stub = when {
            patch.hasmStub.contains("LoadConstFalse", ignoreCase = true) -> STUB_LOAD_CONST_FALSE
            patch.hasmStub.contains("LoadConstNull", ignoreCase = true) -> STUB_LOAD_CONST_NULL
            patch.hasmStub.contains("LoadConstTrue", ignoreCase = true) -> STUB_LOAD_CONST_TRUE
            else -> STUB_LOAD_CONST_UNDEFINED
        }

        val start = bodyOffset.toInt()
        val len = bcSize.toInt()

        if (start < 0 || start + len > bundleBytes.size) {
            return StepResult(
                label = "hermes: ${patch.functionName} (fn $fid)",
                status = StepStatus.FAIL,
                detail = "Function bytecode bounds [$start..${start + len}] exceed bundle size (${bundleBytes.size})"
            )
        }

        if (len < stub.size) {
            return StepResult(
                label = "hermes: ${patch.functionName} (fn $fid)",
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
            label = "hermes: ${patch.functionName} (fn $fid)",
            status = StepStatus.OK
        )
    }

    fun applyPatches(
        bundleBytes: ByteArray,
        patches: List<HermesPatch>
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
        patches: List<HermesPatch>
    ): Pair<ByteArray, List<StepResult>> = withContext(Dispatchers.IO) {
        applyPatches(bundleBytes, patches)
    }
}
