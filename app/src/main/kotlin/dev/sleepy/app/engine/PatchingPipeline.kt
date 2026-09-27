package dev.sleepy.app.engine

import android.content.Context
import android.net.Uri
import dev.sleepy.app.model.*
import dev.sleepy.app.patches.PatchRegistry
import dev.sleepy.app.util.Downloader
import dev.sleepy.app.util.HashUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Runs a full patch job: download, unpack, patch, reassemble, sign, verify.
 *
 * The pipeline is deliberately storage-agnostic — everything happens in memory — but the
 * steps are ordered so that a wrong assumption fails loudly instead of producing an APK
 * that installs and then crashes:
 *
 * - A split base APK is merged with its configuration splits **before** anything is
 *   patched, and the manifest is made standalone, because a base split carries no native
 *   libraries and declares `requiredSplitTypes` the platform enforces at install time.
 * - Every patch reports OK, SKIP or FAIL with the reason it reached that verdict, so the
 *   UI can show what actually happened rather than what was attempted.
 * - Signature and alignment claims come from [ApkVerifier] reading the finished bytes.
 */
class PatchingPipeline(private val context: Context) {

    private val _progress = MutableStateFlow<PatchProgress>(PatchProgress.Idle)
    val progress: StateFlow<PatchProgress> = _progress.asStateFlow()

    private val _stepLog = MutableStateFlow<List<StepResult>>(emptyList())
    val stepLog: StateFlow<List<StepResult>> = _stepLog.asStateFlow()

    private var currentJob: Job? = null

    fun start(
        sourceUrl: String,
        originalPackageName: String,
        customPackageName: String? = null,
        selectedPatchIds: List<String>,
        splitUrls: List<String> = emptyList(),
        expectedSha256: String? = null,
        scope: CoroutineScope
    ) {
        currentJob?.cancel()
        _stepLog.value = emptyList()

        currentJob = scope.launch(Dispatchers.IO) {
            try {
                execute(sourceUrl, originalPackageName, customPackageName, selectedPatchIds, splitUrls, expectedSha256)
            } catch (e: CancellationException) {
                _progress.value = PatchProgress.Idle
            } catch (e: Exception) {
                _progress.value = PatchProgress.Failed(
                    message = e.message ?: "An unexpected error occurred during patching",
                    detail = e.stackTraceToString()
                )
            }
        }
    }

    fun cancel() {
        currentJob?.cancel()
        _progress.value = PatchProgress.Idle
    }

    private suspend fun execute(
        sourceUrl: String,
        originalPackageName: String,
        customPackageName: String?,
        selectedPatchIds: List<String>,
        splitUrls: List<String>,
        expectedSha256: String?
    ) {
        // 1. Download the base APK.
        _progress.value = PatchProgress.Downloading(0, 0, 0)
        val apkBytes = Downloader.download(sourceUrl) { received, total ->
            val pct = if (total > 0) ((received * 100) / total).toInt() else 0
            _progress.value = PatchProgress.Downloading(pct, received, total)
        }
        log(
            StepResult(
                title = "Downloaded the original APK",
                explanation = "Fetched the unmodified build so every change in the result can be traced back to a known starting point.",
                technicalTarget = sourceUrl,
                status = StepStatus.OK,
                detail = "${apkBytes.size / (1024 * 1024)} MB"
            )
        )

        // 2. Check the published hash, if the source publishes one.
        if (expectedSha256 != null) {
            val actual = HashUtils.sha256Hex(apkBytes)
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                throw IllegalStateException(
                    "Source integrity check failed: expected SHA-256 $expectedSha256 but the downloaded file hashes to $actual. " +
                        "The download was modified or the published hash is stale, so nothing was patched."
                )
            }
            log(
                StepResult(
                    title = "Verified the download against its published SHA-256",
                    explanation = "Confirmed the file is byte-for-byte the build the source published, so nothing unexpected entered the pipeline.",
                    technicalTarget = "SHA-256 $actual",
                    status = StepStatus.OK
                )
            )
        } else {
            log(
                StepResult(
                    title = "No published hash to verify against",
                    explanation = "This source does not publish a SHA-256, so the download could not be checked. The patches below still report exactly what they changed.",
                    technicalTarget = "sha256_expected is null",
                    status = StepStatus.SKIP
                )
            )
        }

        currentCoroutineContext().ensureActive()

        // 3. Merge App Bundle configuration splits into the base.
        var mergedLibraries = 0
        val nativeLibraryEntries = linkedMapOf<String, ZipRepacker.AdditionalEntry>()
        if (splitUrls.isNotEmpty()) {
            _progress.value = PatchProgress.MergingSplits("Fetching native library splits", 0, emptyList())
            val abis = linkedSetOf<String>()
            for ((index, splitUrl) in splitUrls.withIndex()) {
                currentCoroutineContext().ensureActive()
                _progress.value = PatchProgress.MergingSplits(
                    step = "Fetching split ${index + 1} of ${splitUrls.size}",
                    librariesMerged = mergedLibraries,
                    abis = abis.toList()
                )
                val splitBytes = Downloader.download(splitUrl) { _, _ -> }
                val report = SplitMerger.mergeNativeLibraries(splitBytes)
                for (entry in report.entries) {
                    nativeLibraryEntries[entry.name] = ZipRepacker.AdditionalEntry(
                        data = entry.data,
                        method = ZipEntry.DEFLATED
                    )
                }
                abis.addAll(report.abis.keys)
                mergedLibraries += report.libraryCount
            }

            _progress.value = PatchProgress.MergingSplits(
                step = "Merging native libraries into the base APK",
                librariesMerged = mergedLibraries,
                abis = abis.toList()
            )

            if (mergedLibraries > 0) {
                log(
                    StepResult(
                        title = "Merged the native libraries the base split was missing",
                        explanation = "An App Bundle base split ships without any lib/ directory — the ARM64 shared libraries live in a separate " +
                            "configuration split. Without them the app dies on its first System.loadLibrary call, which is why a base-only APK " +
                            "crashes the moment it opens.",
                        technicalTarget = "$mergedLibraries libraries for ${abis.joinToString(", ")}, ~${nativeLibraryEntries.values.sumOf { it.data.size.toLong() } / (1024 * 1024)} MB",
                        status = StepStatus.OK
                    )
                )
            } else {
                log(
                    StepResult(
                        title = "No native libraries found in the configuration splits",
                        explanation = "The splits were downloaded but contained no lib/ entries, so the merged APK may still be missing native code.",
                        technicalTarget = splitUrls.joinToString(", "),
                        status = StepStatus.SKIP
                    )
                )
            }
        }

        currentCoroutineContext().ensureActive()

        // 4. Inspect the APK in memory.
        _progress.value = PatchProgress.Decoding("Reading the APK in memory")
        val dexEntries = extractDexEntries(apkBytes)
        val bundleBytes = extractBundle(apkBytes)
        var manifestBytes = extractManifest(apkBytes)

        log(
            StepResult(
                title = "Opened the APK and located its code",
                explanation = "Everything is unpacked in memory only — the original APK on disk is never modified.",
                technicalTarget = "${dexEntries.size} DEX files, JS bundle ${if (bundleBytes != null) "present" else "absent"}",
                status = StepStatus.OK
            )
        )

        val classToDexIndex = DexProcessor.buildClassToDexIndex(dexEntries)
        val detectedOctoGramVersion = when {
            classToDexIndex.containsKey("Lorg/telegram/ui/e6;") -> "3.6.1"
            classToDexIndex.containsKey("Ly5l;") || classToDexIndex.containsKey("Lhxk;") || classToDexIndex.containsKey("Lorg/telegram/messenger/m0;") -> "3.6.0"
            else -> null
        }

        val activePatchSets = selectedPatchIds.mapNotNull { PatchRegistry.get(it) }
        val hermesPatches = activePatchSets.flatMap { it.hermesPatches }

        val smaliPatchesToApply = mutableListOf<SmaliPatch>()
        for (patchSet in activePatchSets) {
            val matchingPatches = patchSet.smaliPatches.filter { patch ->
                if (patch.versionTag != null && detectedOctoGramVersion != null && patch.versionTag != detectedOctoGramVersion) {
                    return@filter false
                }
                val descriptor = "L" + patch.smaliPath.removeSuffix(".smali") + ";"
                val targetDex = patch.dexName ?: classToDexIndex[descriptor]
                targetDex != null && dexEntries.containsKey(targetDex)
            }

            if (matchingPatches.isNotEmpty()) {
                matchingPatches.forEach { patch ->
                    val descriptor = "L" + patch.smaliPath.removeSuffix(".smali") + ";"
                    val actualDex = patch.dexName ?: classToDexIndex[descriptor]!!
                    smaliPatchesToApply.add(patch.copy(dexName = actualDex))
                }
            } else if (patchSet.smaliPatches.isNotEmpty()) {
                val reason = if (detectedOctoGramVersion != null && patchSet.smaliPatches.any { it.versionTag != null && it.versionTag != detectedOctoGramVersion }) {
                    "Written for a different app version, so it was not attempted on this build."
                } else {
                    "The classes this patch edits are not present in this APK, so it was not attempted."
                }
                log(
                    StepResult(
                        title = patchSet.label,
                        explanation = patchSet.description,
                        technicalTarget = "not applicable to this build",
                        status = StepStatus.SKIP,
                        detail = reason
                    )
                )
            }
        }

        currentCoroutineContext().ensureActive()

        // 5. Apply the bytecode patches.
        val totalPatchCount = smaliPatchesToApply.size + hermesPatches.size
        var completedPatchCount = 0
        val repackedDexMap = mutableMapOf<String, ByteArray>()
        val groupedPatches = smaliPatchesToApply.groupBy { it.dexName!! }

        for ((dexName, patchesForDex) in groupedPatches) {
            currentCoroutineContext().ensureActive()
            val dexBytes = dexEntries[dexName]
            if (dexBytes == null) {
                patchesForDex.forEach {
                    log(StepResult(it.smaliPath, StepStatus.SKIP, "DEX $dexName is not present in this APK"))
                }
                continue
            }

            _progress.value = PatchProgress.Patching(
                step = "Editing $dexName",
                current = completedPatchCount,
                total = totalPatchCount,
                explanation = null
            )
            val (patchedBytes, dexResults) = DexProcessor.patchDexSurgically(
                dexBytes = dexBytes,
                patches = patchesForDex,
                onPatchStart = { patch ->
                    _progress.value = PatchProgress.Patching(
                        step = patch.title ?: patch.smaliPath,
                        current = completedPatchCount,
                        total = totalPatchCount,
                        explanation = patch.explanation
                    )
                }
            )
            repackedDexMap[dexName] = patchedBytes
            dexResults.forEach { log(it) }
            completedPatchCount += patchesForDex.size
        }

        currentCoroutineContext().ensureActive()

        // 6. Apply Hermes JS bytecode patches.
        var finalBundle = bundleBytes
        if (hermesPatches.isNotEmpty() && bundleBytes != null) {
            _progress.value = PatchProgress.Patching(
                step = "Preparing JavaScript bytecode patches",
                current = completedPatchCount,
                total = totalPatchCount
            )
            val (patchedBundle, hermesResults) = HermesPatcher.applyPatches(
                bundleBytes = bundleBytes,
                patches = hermesPatches,
                onPatchStart = { patch ->
                    _progress.value = PatchProgress.Patching(
                        step = patch.title ?: patch.functionName,
                        current = completedPatchCount,
                        total = totalPatchCount,
                        explanation = patch.explanation
                    )
                }
            )
            finalBundle = patchedBundle
            hermesResults.forEach { log(it) }
            completedPatchCount += hermesPatches.size
        } else if (hermesPatches.isNotEmpty()) {
            log(
                StepResult(
                    title = "JavaScript patches skipped",
                    explanation = "This APK has no JavaScript bundle to patch.",
                    technicalTarget = "assets/index.android.bundle",
                    status = StepStatus.SKIP
                )
            )
        }

        currentCoroutineContext().ensureActive()

        // 7. Rebuild the archive.
        _progress.value = PatchProgress.Assembling("Rebuilding the APK package")

        val replacements = repackedDexMap.toMutableMap<String, ByteArray>()
        if (finalBundle != null && bundleBytes != null) {
            replacements["assets/index.android.bundle"] = finalBundle
        }

        if (!customPackageName.isNullOrBlank() && customPackageName != originalPackageName && manifestBytes != null) {
            manifestBytes = BinaryXmlModifier.modifyPackageName(
                manifestBytes = manifestBytes,
                oldPackageName = originalPackageName,
                newPackageName = customPackageName
            )
            log(
                StepResult(
                    title = "Renamed the package so it installs alongside the original",
                    explanation = "Changing the application ID lets the patched build coexist with the official app instead of replacing it.",
                    technicalTarget = "$originalPackageName -> $customPackageName",
                    status = StepStatus.OK
                )
            )
        }

        // A merged APK is no longer a split, so the split declarations must go, and the
        // native libraries are now DEFLATE-compressed so they must be extracted at install.
        if (mergedLibraries > 0 && manifestBytes != null) {
            val edit = BinaryXmlEditor.makeStandaloneManifest(manifestBytes)
            if (edit.attributesRemoved.isNotEmpty() || edit.attributesRewritten.isNotEmpty()) {
                manifestBytes = edit.bytes
                log(
                    StepResult(
                        title = "Made the manifest standalone",
                        explanation = "The base split declares that it requires the ABI and density splits. The platform refuses to launch an app " +
                            "whose required splits are missing, so those declarations are removed now that the libraries are inside this APK. " +
                            "extractNativeLibs is turned on so the compressed libraries are unpacked at install time.",
                        technicalTarget = buildString {
                            if (edit.attributesRemoved.isNotEmpty()) append("removed requiredSplitTypes/splitTypes")
                            if (edit.attributesRewritten.isNotEmpty()) {
                                if (isNotEmpty()) append(", ")
                                append("extractNativeLibs=true")
                            }
                        },
                        status = StepStatus.OK
                    )
                )
            } else {
                log(
                    StepResult(
                        title = "Manifest needed no split changes",
                        explanation = "No split declarations were present, so nothing had to be removed.",
                        technicalTarget = "requiredSplitTypes / splitTypes absent",
                        status = StepStatus.SKIP
                    )
                )
            }
            if (edit.missing.isNotEmpty()) {
                log(
                    StepResult(
                        title = "Some expected manifest attributes were absent",
                        explanation = "These attributes were not present in the manifest, so they were left alone.",
                        technicalTarget = edit.missing.joinToString { "0x%08x".format(it) },
                        status = StepStatus.SKIP
                    )
                )
            }
        }

        if (manifestBytes != null) {
            replacements["AndroidManifest.xml"] = manifestBytes
        }

        val repack = ZipRepacker.repack(
            inputApkBytes = apkBytes,
            replacements = replacements,
            additionalEntries = nativeLibraryEntries,
            alignment = 4
        )
        log(
            StepResult(
                title = "Rebuilt the APK with correct alignment",
                explanation = "Repacked the archive and re-aligned every entry to a 4-byte boundary. Android requires uncompressed entries — the " +
                    "resource table above all — to be aligned, and re-compressing even one file shifts everything after it.",
                technicalTarget = "${repack.replacedEntries.size} entries replaced, ${repack.addedEntries.size} added, ${repack.droppedEntries.size} dropped",
                status = StepStatus.OK
            )
        )

        currentCoroutineContext().ensureActive()

        // 8. Sign.
        _progress.value = PatchProgress.Signing("Signing the APK")
        val signedApkBytes = ApkSignerHelper.sign(context, repack.bytes)
        log(
            StepResult(
                title = "Signed the APK",
                explanation = "Android will not install an unsigned APK. This build is signed with a key generated on this device, so it installs as " +
                    "a different app identity than the official one and will not receive official updates.",
                technicalTarget = "APK Signature Scheme v1 + v2 + v3",
                status = StepStatus.OK
            )
        )

        // 9. Verify what was actually produced.
        _progress.value = PatchProgress.Signing("Verifying the signed result")
        val verification = ApkVerifier.verify(signedApkBytes)
        log(
            StepResult(
                title = "Checked the signatures on the finished APK",
                explanation = "Re-read the signed file and confirmed which signature schemes actually verify, rather than assuming signing worked.",
                technicalTarget = "v1=${verification.v1SignatureValid}, v2=${verification.v2SignatureValid}, v3=${verification.v3SignatureValid}",
                status = if (verification.v2SignatureValid || verification.v3SignatureValid) StepStatus.OK else StepStatus.FAIL,
                detail = verification.signatureErrors.takeIf { it.isNotEmpty() }?.joinToString("; ")
            )
        )
        log(
            StepResult(
                title = "Checked ZIP alignment on the finished APK",
                explanation = "Confirmed every entry starts on a 4-byte boundary, which is what stops the resource table from failing to load.",
                technicalTarget = if (verification.zipalignPassed) "all entries 4-byte aligned" else "${verification.misalignedEntries.size} misaligned",
                status = if (verification.zipalignPassed) StepStatus.OK else StepStatus.FAIL,
                detail = verification.misalignedEntries.take(5).takeIf { it.isNotEmpty() }?.joinToString(", ")
            )
        )

        val outputFile = File(context.cacheDir, "sleepy_patched_${System.currentTimeMillis()}.apk").apply {
            writeBytes(signedApkBytes)
        }
        val sha256 = HashUtils.sha256Hex(signedApkBytes)

        val applied = _stepLog.value.filter { it.status == StepStatus.OK }.map { it.title }
        val skipped = _stepLog.value.filter { it.status == StepStatus.SKIP }.map { it.title }
        val failed = _stepLog.value.filter { it.status == StepStatus.FAIL }.map { it.title }

        _progress.value = PatchProgress.Done(
            outputUri = Uri.fromFile(outputFile),
            sha256 = sha256,
            report = VerificationReport(
                v1SignatureValid = verification.v1SignatureValid,
                v2SignatureValid = verification.v2SignatureValid,
                v3SignatureValid = verification.v3SignatureValid,
                zipalignPassed = verification.zipalignPassed,
                sourceIntegrityVerified = expectedSha256?.let { true },
                mergedNativeLibraries = mergedLibraries,
                outputBytes = signedApkBytes.size.toLong(),
                patchesApplied = applied,
                patchesSkipped = skipped,
                patchesFailed = failed
            )
        )
    }

    private fun log(result: StepResult) {
        _stepLog.value = _stepLog.value + result
    }

    private fun extractDexEntries(apkBytes: ByteArray): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(apkBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name.matches(Regex("classes\\d*\\.dex"))) {
                    result[entry.name] = zis.readBytes()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return result
    }

    private fun extractBundle(apkBytes: ByteArray): ByteArray? = extractEntry(apkBytes, "assets/index.android.bundle")

    private fun extractManifest(apkBytes: ByteArray): ByteArray? = extractEntry(apkBytes, "AndroidManifest.xml")

    private fun extractEntry(apkBytes: ByteArray, name: String): ByteArray? {
        ZipInputStream(ByteArrayInputStream(apkBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == name) return zis.readBytes()
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return null
    }
}
