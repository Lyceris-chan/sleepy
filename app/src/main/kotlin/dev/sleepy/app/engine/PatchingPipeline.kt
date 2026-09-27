package dev.sleepy.app.engine

import android.content.Context
import android.net.Uri
import dev.sleepy.app.model.*
import dev.sleepy.app.patches.DiscordHermesBundlePatch
import dev.sleepy.app.patches.DiscordHermesFunctionCatalog
import dev.sleepy.app.patches.DiscordPatches
import dev.sleepy.app.patches.PatchItemCatalog
import dev.sleepy.app.patches.PatchRegistry
import dev.sleepy.app.patches.PermissionCatalog
import dev.sleepy.app.util.Downloader
import dev.sleepy.app.util.HashUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Runs a full patch job: download, unpack, patch, reassemble, sign, verify.
 *
 * The steps are ordered so that a wrong assumption fails loudly instead of producing an APK
 * that installs and then crashes:
 *
 * - A split base APK is merged with its configuration splits **before** anything is
 *   patched, and the manifest is made standalone, because a base split carries no native
 *   libraries and declares `requiredSplitTypes` the platform enforces at install time.
 * - Every patch reports OK, SKIP or FAIL with the reason it reached that verdict, so the
 *   UI can show what actually happened rather than what was attempted.
 * - Signature and alignment claims come from [ApkVerifier] reading the finished file.
 *
 * Only what is being edited is held in memory. A Discord job is 96 MB of base split, 74 MB of
 * native libraries and a 131 MB result, and no two of those are ever bytes at the same time:
 * the download lands in [Context.getCacheDir], the split libraries are merged out to files
 * there, and the archive is rebuilt and signed file to file. That is what keeps the peak
 * inside the heap a phone grants an app rather than the 3 GB a desktop test JVM can be given.
 */
class PatchingPipeline(private val context: Context) {

    private val _progress = MutableStateFlow<PatchProgress>(PatchProgress.Idle)
    val progress: StateFlow<PatchProgress> = _progress.asStateFlow()

    private val _stepLog = MutableStateFlow<List<StepResult>>(emptyList())
    val stepLog: StateFlow<List<StepResult>> = _stepLog.asStateFlow()

    private var currentJob: Job? = null

    /**
     * Runs a job for the sets in [selectedPatchIds], narrowed by [selection] when one is given.
     *
     * [selection] is the per-item selection and is optional: a caller that has one hands it over
     * and gets only the items the user switched on, and a caller that does not (the set-level
     * switch this grew out of) gets each selected set in full. See [execute].
     */
    fun start(
        sourceUrl: String,
        originalPackageName: String,
        customPackageName: String? = null,
        selectedPatchIds: List<String>,
        selection: PatchSelection? = null,
        splitUrls: List<String> = emptyList(),
        expectedSha256: String? = null,
        scope: CoroutineScope
    ) {
        currentJob?.cancel()
        _stepLog.value = emptyList()

        // Scratch space for the job, owned by it alone so a cancelled job cannot delete the
        // directory of the one replacing it. Everything too large to hold lives in here.
        val workDir = File(context.cacheDir, "sleepy_work_${System.currentTimeMillis()}")

        currentJob = scope.launch(Dispatchers.IO) {
            try {
                execute(sourceUrl, originalPackageName, customPackageName, selectedPatchIds, selection, splitUrls, expectedSha256, workDir)
            } catch (e: CancellationException) {
                _progress.value = PatchProgress.Idle
            } catch (e: Exception) {
                _progress.value = PatchProgress.Failed(
                    message = e.message ?: "An unexpected error occurred during patching",
                    detail = e.stackTraceToString()
                )
            } finally {
                workDir.deleteRecursively()
            }
        }
    }

    fun cancel() {
        currentJob?.cancel()
        _progress.value = PatchProgress.Idle
    }

    /**
     * The permissions the build at [sourceUrl] declares, in the order it declares them.
     *
     * This reads the target APK's own manifest rather than a list kept anywhere, which is the
     * whole point: what a build declares is a fact about that build, and a list written down here
     * would be wrong the first time either app updated. It costs the same download the patch does,
     * so a caller reads it once and shows the result; [execute] reads it again from the bytes it
     * has already fetched, and that second read is the one the removed declarations come from.
     *
     * An APK with no manifest, or one that cannot be read, declares nothing here: the failure is
     * reported as "nothing to remove" rather than as an error, because that is the safe reading —
     * a build whose permissions could not be read is a build whose permissions are left alone.
     */
    suspend fun readDeclaredPermissions(sourceUrl: String): List<String> = withContext(Dispatchers.IO) {
        // The download lands in a file rather than a local: this reads ~100 MB to look at one
        // 100 KB manifest, and the archive should not be on the heap while it does.
        val apk = File.createTempFile("sleepy_permissions_", ".apk", context.cacheDir)
        try {
            apk.writeBytes(Downloader.download(sourceUrl) { _, _ -> })
            val manifest = extractManifest(apk) ?: return@withContext emptyList()
            BinaryXmlEditor.readElementAttributeValues(
                xml = manifest,
                namePrefix = BinaryXmlEditor.ELEMENT_USES_PERMISSION,
                attributeId = BinaryXmlEditor.ATTR_NAME
            )
        } finally {
            apk.delete()
        }
    }

    /**
     * The job itself.
     *
     * [selection] narrows what a selected set contributes, and is null when the caller works at
     * set level. It never widens anything: a set that is not in [selectedPatchIds] is not applied
     * however many of its items a selection names, and a set that is selected but has no item
     * switched on contributes nothing — the same thing an unselected set has always contributed.
     */
    private suspend fun execute(
        sourceUrl: String,
        originalPackageName: String,
        customPackageName: String?,
        selectedPatchIds: List<String>,
        selection: PatchSelection?,
        splitUrls: List<String>,
        expectedSha256: String?,
        workDir: File
    ) {
        // 1. Download the base APK, and 2. check the published hash, if the source publishes
        // one. Both happen inside a call of their own so that the downloaded bytes are not a
        // local of this coroutine: it suspends, which makes its locals fields of the
        // continuation, and 96 MB that stays reachable for the rest of the job is the whole
        // reason this pipeline ran out of heap. On disk the archive can sit until the repack.
        _progress.value = PatchProgress.Downloading(0, 0, 0)
        val sourceApk = File(workDir, "source.apk")
        downloadSource(sourceUrl, expectedSha256, sourceApk)

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
                // Each library is merged straight to a file, and the map holds where each one
                // is rather than what it weighs: the entries themselves are what the repack
                // streams back in, so the 74 MB of an ABI split never has to be bytes.
                val report = fetchSplitLibraries(splitUrl, index, workDir)
                for (entry in report.entries) {
                    nativeLibraryEntries[entry.name] = ZipRepacker.AdditionalEntry(
                        file = entry.file,
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
                        technicalTarget = "$mergedLibraries libraries for ${abis.joinToString(", ")}, ~${nativeLibraryEntries.values.sumOf { it.size } / (1024 * 1024)} MB",
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

        // 4. Inspect the APK. Only the entries that are going to be edited are read out of it:
        // the DEX files, the JS bundle and the manifest, not the resources and assets beside
        // them, which the repack copies straight through from the file.
        _progress.value = PatchProgress.Decoding("Reading the APK in memory")
        val dexEntries = extractDexEntries(sourceApk)
        val bundleBytes = extractBundle(sourceApk)
        var manifestBytes = extractManifest(sourceApk)

        log(
            StepResult(
                title = "Opened the APK and located its code",
                explanation = "Only the parts being patched are unpacked — the original APK file is never modified.",
                technicalTarget = "${dexEntries.size} DEX files, JS bundle ${if (bundleBytes != null) "present" else "absent"}",
                status = StepStatus.OK
            )
        )

        // What this build declares, read from the manifest that was just extracted rather than
        // from a table kept anywhere: the selection names permissions by the name the build
        // writes, and reading them from the build is what keeps the two from drifting apart.
        val declaredPermissions = manifestBytes?.let { manifest ->
            BinaryXmlEditor.readElementAttributeValues(
                xml = manifest,
                namePrefix = BinaryXmlEditor.ELEMENT_USES_PERMISSION,
                attributeId = BinaryXmlEditor.ATTR_NAME
            )
        } ?: emptyList()
        // A selection that names no permission removes none of them, whatever the build declares
        // — see PermissionCatalog.removals, which decides this and is where the two locks live.
        val permissionRemovals = if (selection == null) {
            emptyList()
        } else {
            PermissionCatalog.removals(declaredPermissions, selection)
        }

        val classToDexIndex = DexProcessor.buildClassToDexIndex(dexEntries)
        val detectedOctoGramVersion = when {
            classToDexIndex.containsKey("Lorg/telegram/ui/e6;") -> "3.6.1"
            classToDexIndex.containsKey("Ly5l;") || classToDexIndex.containsKey("Lhxk;") || classToDexIndex.containsKey("Lorg/telegram/messenger/m0;") -> "3.6.0"
            else -> null
        }

        val activePatchSets = selectedPatchIds
            .mapNotNull { PatchRegistry.get(it) }
            .filter { set -> selection == null || PatchItemCatalog.itemsOf(set.id).any { selection.contains(it.key) } }
        // [DiscordPatches.HERMES] catalogues the stub shapes for audit; the bundle itself is
        // patched from the pinned table, so the progress total follows whichever will run. With a
        // selection that table is a subset, which is what the filtering below produces.
        val hermesSelected = activePatchSets.any { it.id == DiscordPatches.HERMES.id }
        val hermesPatches = activePatchSets.flatMap { it.hermesPatches }
        val hermesTableToApply = if (hermesSelected && selection != null) {
            DiscordHermesFunctionCatalog.selectPatches(selection)
        } else {
            DiscordHermesBundlePatch.PATCHES
        }
        val hermesWorkCount = if (bundleBytes != null && bundleBytes.size == DiscordHermesBundlePatch.TARGET_BUNDLE_SIZE) {
            hermesTableToApply.size
        } else {
            hermesPatches.size
        }

        val smaliPatchesToApply = mutableListOf<SmaliPatch>()
        val targetApk = TargetApk(classToDexIndex = classToDexIndex, dexEntries = dexEntries)
        for (patchSet in activePatchSets) {
            // A generated set has nothing to filter until it has read the target APK, so it is
            // asked first and its patches join the set's static ones. A set that can generate for
            // a selection gets one; a set that cannot is generated whole, since its selection is
            // already expressed by whether it is in activePatchSets at all.
            val generator = patchSet.generator
            val generated = if (selection != null && generator is SelectivePatchGenerator) {
                generator.generate(targetApk, selection)
            } else {
                generator?.generate(targetApk)
            }
            val candidates = patchSet.smaliPatches + (generated?.patches ?: emptyList())

            val matchingPatches = candidates.filter { patch ->
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
            } else if (generated?.skipReason != null) {
                log(
                    StepResult(
                        title = patchSet.label,
                        explanation = patchSet.description,
                        technicalTarget = "not applicable to this build",
                        status = StepStatus.SKIP,
                        detail = generated.skipReason
                    )
                )
            } else if (candidates.isNotEmpty()) {
                val reason = if (detectedOctoGramVersion != null && candidates.any { it.versionTag != null && it.versionTag != detectedOctoGramVersion }) {
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
        val totalPatchCount = smaliPatchesToApply.size + hermesWorkCount
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

        // 6. Apply the Hermes JS bytecode patches.
        var finalBundle = bundleBytes
        if (hermesSelected && bundleBytes != null) {
            _progress.value = PatchProgress.Patching(
                step = "Neutralizing the JavaScript Sentry endpoint",
                current = completedPatchCount,
                total = totalPatchCount
            )
            val (afterDsn, dsnResult) = HermesPatcher.nullifySentryDsn(bundleBytes)
            if (dsnResult != null) log(dsnResult)
            finalBundle = afterDsn

            if (bundleBytes.size == DiscordHermesBundlePatch.TARGET_BUNDLE_SIZE) {
                _progress.value = PatchProgress.Patching(
                    step = "Patching JavaScript functions",
                    current = completedPatchCount,
                    total = totalPatchCount
                )
                val outcome = HermesBundlePatcher.apply(afterDsn, hermesTableToApply)
                finalBundle = outcome.bundleBytes
                completedPatchCount += outcome.appliedCount

                log(
                    StepResult(
                        title = "Neutralized ${outcome.appliedCount} JavaScript functions",
                        explanation = "Discord's JavaScript drives its analytics, its crash reporting and the promotional screens " +
                            "that keep appearing. Each function below was replaced with one that returns a neutral value, using the " +
                            "same bytes the desktop reference build produces for this release.",
                        technicalTarget = buildString {
                            append("${outcome.writtenInPlace.size} rewritten in place")
                            if (outcome.relocated.isNotEmpty()) append(", ${outcome.relocated.size} moved to make room")
                        },
                        status = if (outcome.skipped.isEmpty()) StepStatus.OK else StepStatus.FAIL,
                        detail = outcome.skipped.take(5)
                            .joinToString("; ") { "${it.name.ifBlank { "fn ${it.functionId}" }}: ${it.detail}" }
                            .takeIf { it.isNotEmpty() }
                    )
                )
            } else {
                log(
                    StepResult(
                        title = "JavaScript patches skipped",
                        explanation = "This build's JavaScript bundle is not the one these patches were derived from. Hermes numbers " +
                            "its functions per bundle, so an id from one release points at an unrelated function in another, and " +
                            "patching it would break the app rather than fix it. Nothing was written to the bundle.",
                        technicalTarget = "bundle is ${bundleBytes.size} bytes, patches target ${DiscordHermesBundlePatch.TARGET_BUNDLE_SIZE}",
                        status = StepStatus.SKIP
                    )
                )
            }
        } else if (hermesSelected) {
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

        // The manifest is edited before the clone rename, because a removal is matched on the
        // permission name exactly as the build writes it and the rename rewrites every string
        // that begins with the package name — including the one permission whose name is built
        // from it.
        val permissionSelectors = permissionRemovals.map { permission ->
            BinaryXmlEditor.ElementSelector(
                namePrefix = BinaryXmlEditor.ELEMENT_USES_PERMISSION,
                attributeId = BinaryXmlEditor.ATTR_NAME,
                attributeValue = permission
            )
        }

        // One pass over the manifest for both reasons there is to edit it: a merged APK is no
        // longer a split, so the split declarations must go and the native libraries are now
        // DEFLATE-compressed so they must be extracted at install; and a permission the user
        // switched off must be declared no more.
        if (manifestBytes != null && (mergedLibraries > 0 || permissionSelectors.isNotEmpty())) {
            val edit = if (mergedLibraries > 0) {
                BinaryXmlEditor.makeStandaloneManifest(manifestBytes, permissionSelectors)
            } else {
                // Nothing was merged, so nothing needs making standalone: this pass is the
                // permission removals and nothing else. Turning extractNativeLibs on here would
                // change an attribute the user never asked about.
                BinaryXmlEditor.edit(manifestBytes, removeElements = permissionSelectors)
            }
            manifestBytes = edit.bytes

            if (mergedLibraries > 0) {
                if (edit.attributesRemoved.isNotEmpty() || edit.attributesRewritten.isNotEmpty()) {
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

            if (permissionSelectors.isNotEmpty()) {
                val removedCount = edit.elementsRemoved.size
                log(
                    StepResult(
                        title = "Removed $removedCount permission ${if (removedCount == 1) "declaration" else "declarations"} from the manifest",
                        explanation = "Android grants an app only the permissions its manifest declares, and an installed app cannot declare " +
                            "another one later, so these are gone for good: the app can never ask for them again. The declarations themselves " +
                            "were deleted — the rest of the manifest, its string pool and every other attribute, is byte-for-byte what the build shipped.",
                        technicalTarget = edit.elementsRemoved
                            .take(6)
                            .joinToString(", ")
                            .let { if (removedCount > 6) "$it and ${removedCount - 6} more" else it },
                        status = if (edit.elementsMissing.isEmpty()) StepStatus.OK else StepStatus.SKIP,
                        detail = edit.elementsMissing.take(5).takeIf { it.isNotEmpty() }
                            ?.joinToString("; ") { "$it is not declared by this build, so there was nothing to remove" }
                    )
                )
            }
        }

        if (permissionSelectors.isEmpty() && selection != null && PermissionCatalog.isEngaged(selection)) {
            log(
                StepResult(
                    title = "Kept every permission this build declares",
                    explanation = "Permissions were chosen about but none of them was switched off, so every declaration the build shipped is " +
                        "still in the manifest that was produced.",
                    technicalTarget = "${declaredPermissions.size} declarations, none removed",
                    status = StepStatus.SKIP
                )
            )
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

        if (manifestBytes != null) {
            replacements["AndroidManifest.xml"] = manifestBytes
        }

        // Artefacts that carry a crash reporter rather than call it go only when that
        // reporter is being disabled, so the drop is tied to the selected patch set.
        val droppedArtefacts = if (activePatchSets.any { it.id == DiscordPatches.SENTRY.id }) {
            DiscordPatches.SENTRY_ARTEFACTS
        } else {
            emptySet()
        }

        // The rebuild reads the source file entry by entry and writes the result straight to
        // another file, so the ~131 MB archive exists once. Materialising it here instead is
        // what made this step the peak: an output buffer sized for the whole archive plus the
        // copy `toByteArray()` makes of it, while the base APK and the merged libraries were
        // still live.
        val rebuiltApk = File(workDir, "rebuilt.apk")
        val repack = FileOutputStream(rebuiltApk).use { output ->
            ZipRepacker.repackTo(
                inputApk = sourceApk,
                output = output,
                replacements = replacements,
                additionalEntries = nativeLibraryEntries,
                droppedEntries = droppedArtefacts
            )
        }
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

        // 8. Sign. apksig reads the rebuilt file and writes the signed one, and the signed one
        // is the artefact the user gets, so this is the last place the archive is copied — and
        // it is copied by the filesystem rather than by the heap.
        _progress.value = PatchProgress.Signing("Signing the APK")
        val outputFile = File(context.cacheDir, "sleepy_patched_${System.currentTimeMillis()}.apk")
        ApkSignerHelper.sign(context, rebuiltApk, outputFile)
        log(
            StepResult(
                title = "Signed the APK",
                explanation = "Android will not install an unsigned APK. This build is signed with a key generated on this device, so it installs as " +
                    "a different app identity than the official one and will not receive official updates.",
                technicalTarget = "APK Signature Scheme v1 + v2 + v3",
                status = StepStatus.OK
            )
        )

        // 9. Verify what was actually produced. The finished APK is read where it lies, so
        // checking it does not mean loading it back into memory.
        _progress.value = PatchProgress.Signing("Verifying the signed result")
        val verification = ApkVerifier.verify(outputFile)
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
                explanation = "Confirmed every uncompressed entry starts where the platform needs it to — 4-byte alignment for the " +
                    "resource table, page alignment for any library stored uncompressed. Compressed entries have no alignment " +
                    "requirement, so they are not counted.",
                technicalTarget = if (verification.zipalignPassed) "all entries 4-byte aligned" else "${verification.misalignedEntries.size} misaligned",
                status = if (verification.zipalignPassed) StepStatus.OK else StepStatus.FAIL,
                detail = verification.misalignedEntries.take(5).takeIf { it.isNotEmpty() }?.joinToString(", ")
            )
        )

        val sha256 = HashUtils.sha256Hex(outputFile)

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
                outputBytes = outputFile.length(),
                patchesApplied = applied,
                patchesSkipped = skipped,
                patchesFailed = failed
            )
        )
    }

    private fun log(result: StepResult) {
        _stepLog.value = _stepLog.value + result
    }

    /**
     * Fetches the base APK to [destination], checking it against [expectedSha256] when the
     * source publishes one, and returns how large it was.
     *
     * The body lives outside [execute] on purpose: `execute` is a suspend function, so its
     * locals are fields of the continuation, and a 96 MB array parked there stays reachable
     * for the whole job. Here it dies with the call.
     */
    private suspend fun downloadSource(url: String, expectedSha256: String?, destination: File): Long {
        val bytes = Downloader.download(url) { received, total ->
            val pct = if (total > 0) ((received * 100) / total).toInt() else 0
            _progress.value = PatchProgress.Downloading(pct, received, total)
        }
        log(
            StepResult(
                title = "Downloaded the original APK",
                explanation = "Fetched the unmodified build so every change in the result can be traced back to a known starting point.",
                technicalTarget = url,
                status = StepStatus.OK,
                detail = "${bytes.size / (1024 * 1024)} MB"
            )
        )

        if (expectedSha256 != null) {
            val actual = HashUtils.sha256Hex(bytes)
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

        destination.parentFile?.mkdirs()
        destination.writeBytes(bytes)
        return bytes.size.toLong()
    }

    /**
     * Downloads configuration split [index] and merges its native libraries into [workDir].
     *
     * The split itself is a file for the length of the merge and then deleted: it is another
     * tens of megabytes, and only the libraries inside it are wanted. As with
     * [downloadSource], the split's bytes never become a local of [execute].
     */
    private suspend fun fetchSplitLibraries(
        url: String,
        index: Int,
        workDir: File
    ): SplitMerger.MergeReport<SplitMerger.MergedFileEntry> {
        val splitApk = File(workDir, "split_$index.apk")
        try {
            splitApk.writeBytes(Downloader.download(url) { _, _ -> })
            return SplitMerger.mergeNativeLibrariesToDir(splitApk, workDir)
        } finally {
            splitApk.delete()
        }
    }

    private fun extractDexEntries(apk: File): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        ZipInputStream(BufferedInputStream(apk.inputStream())).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (DEX_ENTRY_NAME.matches(entry.name)) {
                    result[entry.name] = zis.readBytes()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return result
    }

    private fun extractBundle(apk: File): ByteArray? = extractEntry(apk, BUNDLE_ENTRY)

    private fun extractManifest(apk: File): ByteArray? = extractEntry(apk, MANIFEST_ENTRY)

    /** Reads one entry out of [apk], stopping as soon as it is found. */
    private fun extractEntry(apk: File, name: String): ByteArray? {
        ZipInputStream(BufferedInputStream(apk.inputStream())).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == name) return zis.readBytes()
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return null
    }

    private companion object {
        /** Matches `classes.dex`, `classes2.dex`, ... — the DEX files of a single APK. */
        val DEX_ENTRY_NAME = Regex("classes\\d*\\.dex")

        const val BUNDLE_ENTRY = "assets/index.android.bundle"
        const val MANIFEST_ENTRY = "AndroidManifest.xml"
    }
}
