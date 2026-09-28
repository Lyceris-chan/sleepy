package dev.sleepy.app.engine

import android.content.Context
import android.net.Uri
import dev.sleepy.app.model.DeclarationMismatch
import dev.sleepy.app.model.PatchProgress
import dev.sleepy.app.model.PatchSelection
import dev.sleepy.app.model.PermissionCheck
import dev.sleepy.app.model.SelectivePatchGenerator
import dev.sleepy.app.model.SmaliPatch
import dev.sleepy.app.model.StepResult
import dev.sleepy.app.model.StepStatus
import dev.sleepy.app.model.TargetApk
import dev.sleepy.app.model.VerificationReport
import dev.sleepy.app.patches.DeclaredPermissions
import dev.sleepy.app.patches.DiscordHermesBundlePatch
import dev.sleepy.app.patches.DiscordHermesFunctionCatalog
import dev.sleepy.app.patches.DiscordPatches
import dev.sleepy.app.patches.PatchItemCatalog
import dev.sleepy.app.patches.PatchRegistry
import dev.sleepy.app.patches.PermissionCatalog
import dev.sleepy.app.util.Downloader
import dev.sleepy.app.util.HashUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
     * The list the section shows is the one shipped with the app for the release it supports, so
     * this read is the cross-check rather than the source: it answers whether the build at the
     * source is still the build that list describes, and [execute] reads the same thing again from
     * the bytes it has already fetched to do the same. It is also the only list there is for a build
     * nothing is shipped for, which is why the caller is given the declarations themselves rather
     * than a verdict on them.
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
        var mergedResources = 0
        var mergedLibraryBytes = 0L
        var mergedResourceBytes = 0L
        // The table that makes the merged resources resolve, once one has been built and checked.
        // It is held rather than written out because the repack is what puts entries into the
        // archive, and a replacement there keeps the entry STORED and aligned as it was.
        var mergedResourceTable: ByteArray? = null
        // The split-install metadata the rebuilt table stopped naming, and so the files the repack
        // may drop. Empty until a rebuild has landed without them: the file and the row that names
        // it go together or not at all, and this is what makes "not at all" the default.
        var droppedSplitMetadata: Set<String> = emptySet()
        val splitResourceTables = mutableListOf<ByteArray>()
        val mergedSplitEntries = linkedMapOf<String, ZipRepacker.AdditionalEntry>()
        if (splitUrls.isNotEmpty()) {
            _progress.value = PatchProgress.MergingSplits("Fetching configuration splits", 0, emptyList())
            val abis = linkedSetOf<String>()
            for ((index, splitUrl) in splitUrls.withIndex()) {
                currentCoroutineContext().ensureActive()
                _progress.value = PatchProgress.MergingSplits(
                    step = "Fetching split ${index + 1} of ${splitUrls.size}",
                    librariesMerged = mergedLibraries,
                    abis = abis.toList()
                )
                // Each entry is merged straight to a file, and the map holds where each one
                // is rather than what it weighs: the entries themselves are what the repack
                // streams back in, so the 74 MB of an ABI split never has to be bytes.
                val fetched = fetchSplit(splitUrl, index, workDir)
                val report = fetched.report
                // A split's own table is the only part of it a file copy cannot carry across,
                // and the split file it came out of is deleted before this loop ends, so it is
                // read now or not at all.
                fetched.resourceTable?.let { splitResourceTables.add(it) }
                for (entry in report.entries) {
                    mergedSplitEntries[entry.name] = ZipRepacker.AdditionalEntry(
                        file = entry.file,
                        method = ZipEntry.DEFLATED
                    )
                }
                abis.addAll(report.abis.keys)
                mergedLibraries += report.libraryCount
                mergedResources += report.resourceCount
                mergedLibraryBytes += report.libraryBytes
                mergedResourceBytes += report.resourceBytes
            }

            _progress.value = PatchProgress.MergingSplits(
                step = "Merging the configuration splits into the base APK",
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
                        technicalTarget = "$mergedLibraries libraries for ${abis.joinToString(", ")}, ~${mergedLibraryBytes / (1024 * 1024)} MB",
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

            // A split with no resources is the normal case for an ABI split, so this is
            // reported only when there is something to report and never as a skip.
            if (mergedResources > 0) {
                log(
                    StepResult(
                        title = "Merged the resources the base split was missing",
                        explanation = "A density configuration split carries the bitmaps for the screen densities it covers, and the base split " +
                            "carries none of them: an App Bundle installs them side by side, and the platform draws each one from whichever split " +
                            "holds it. This puts those files back at the paths the desktop build's merged APK has them at, which is what the " +
                            "resource table rebuilt just below then points at.",
                        technicalTarget = "$mergedResources resources, ~${mergedResourceBytes / (1024 * 1024)} MB",
                        status = StepStatus.OK
                    )
                )
            }

            // 3b. Rebuild the resource table. Without this the files above arrive at the right
            // paths with nothing referring to them: every split ships a partial table naming only
            // what that split carries, and the base's table names none of the split's files.
            if (splitResourceTables.isNotEmpty()) {
                val baseTable = extractEntry(sourceApk, RESOURCE_TABLE_ENTRY)
                val merge = if (baseTable == null) {
                    null
                } else {
                    mergeResourceTables(
                        baseTable = baseTable,
                        splitTables = splitResourceTables,
                        mergedEntryNames = mergedSplitEntries.keys,
                        droppablePaths = setOf(SplitMerger.SPLIT_INSTALL_METADATA)
                    )
                }
                when (merge) {
                    null -> log(
                        StepResult(
                            title = "The resource table could not be rebuilt",
                            explanation = "This APK carries no resources.arsc of its own, so there is nothing to merge the splits' tables into. " +
                                "The merged-in resource files are in the archive but nothing in it names them.",
                            technicalTarget = RESOURCE_TABLE_ENTRY,
                            status = StepStatus.SKIP
                        )
                    )

                    is ResourceTableMerger.Result.Merged -> {
                        mergedResourceTable = merge.table
                        log(
                            StepResult(
                                title = "Rebuilt the resource table around the merged resources",
                                explanation = "An App Bundle deals its resource ids out across the splits, and each split ships a table naming only " +
                                    "what it holds. The base's table names none of the density split's 1,249 files, so without this the merged files " +
                                    "would be present and unresolvable. The tables are merged chunk by chunk rather than relinked: the entries are " +
                                    "copied across byte for byte with the configuration and file path they were compiled with, because a relink " +
                                    "through apktool's decoder rewrites those paths and would name files this archive does not contain.",
                                technicalTarget = "${merge.sourceCount} tables -> ${merge.resourceCount} resources over ${merge.typeCount} types, " +
                                    "${merge.table.size / 1024} KB",
                                status = StepStatus.OK
                            )
                        )
                        // The split-install metadata, which the base's table named and the rebuilt one
                        // does not. It is reported here and dropped from the archive at the repack,
                        // because the two halves of the removal are decided by the same fact: the
                        // table that stopped naming the file is what says the file may go, and a file
                        // dropped while a table still resolved paths into it would be the broken half
                        // of this rather than a smaller version of it.
                        droppedSplitMetadata = merge.droppedPaths
                        if (droppedSplitMetadata.isNotEmpty()) {
                            log(
                                StepResult(
                                    title = "Dropped the split-install metadata and the table row that named it",
                                    explanation = "A base split ships res/xml/splits0.xml, which lists the configuration splits an installation has " +
                                        "and is read by Play's split installer — the resource-side twin of the Play split markers removed from the " +
                                        "manifest. Everything those splits carried is inside this APK now, so the file describes an installation " +
                                        "that no longer exists, and it goes with the row the table named it by: the row was left out of the rebuild " +
                                        "above and the entry follows it out of the archive, so the table never resolves to a file this APK does not hold.",
                                    technicalTarget = buildString {
                                        append(droppedSplitMetadata.joinToString(", "))
                                        append(", and the ")
                                        append(if (droppedSplitMetadata.size == 1) "row" else "rows")
                                        append(" naming it")
                                    },
                                    status = StepStatus.OK
                                )
                            )
                        }
                    }

                    is ResourceTableMerger.Result.Refused -> log(
                        StepResult(
                            title = "Left the resource table as the base split shipped it",
                            explanation = "The merged table could not be shown to be sound, so the base's own table was kept: a resource table naming " +
                                "files the APK does not hold is worse than one naming none of the split's. The merged resource files are in the " +
                                "archive, but the base's table does not name them.",
                            technicalTarget = "${splitResourceTables.size} split tables were not merged",
                            status = StepStatus.SKIP,
                            detail = merge.reason
                        )
                    )
                }
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

        // What this build declares, read from the manifest that was just extracted.
        val declaredPermissions = manifestBytes?.let { manifest ->
            BinaryXmlEditor.readElementAttributeValues(
                xml = manifest,
                namePrefix = BinaryXmlEditor.ELEMENT_USES_PERMISSION,
                attributeId = BinaryXmlEditor.ATTR_NAME
            )
        } ?: emptyList()
        // The list a run goes by is the one shipped for the release, because that is the list the
        // switches were shown against; a build nothing is shipped for supplies its own.
        val shippedPermissions = DeclaredPermissions.forPackage(originalPackageName)
        val chosenPermissions = shippedPermissions ?: declaredPermissions
        // Where the build no longer matches the shipped list, the difference is reported rather than
        // substituted: a declaration the list does not name has no row for the user to have seen, so
        // it is never removed — and one they are not told about is one they cannot choose about.
        val permissionMismatches = when (
            val check = shippedPermissions?.let { PermissionCheck.of(it, declaredPermissions) }
        ) {
            is PermissionCheck.Disagrees -> check.mismatches
            else -> emptyList()
        }
        // A selection that names no permission removes none of them, whatever the build declares
        // — see PermissionCatalog.removals, which decides this and is where the locks live. The
        // package goes in with it because the declarations this build removes by itself are not
        // the user's to remove, and this list is the user's: a name that reached both would be a
        // selector the manifest pass is handed twice.
        val permissionRemovals = if (selection == null) {
            emptyList()
        } else {
            PermissionCatalog.removals(chosenPermissions, selection, originalPackageName)
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
        // A replacement inherits the source entry's compression method, so the table stays
        // STORED exactly as it was and the repack's alignment pass gives it its 4-byte start
        // without anything here having to arrange either.
        mergedResourceTable?.let { replacements[RESOURCE_TABLE_ENTRY] = it }

        // The manifest is edited before the clone rename, because an element is matched on the
        // name exactly as the build writes it and the rename rewrites every string that begins
        // with the package name — including the one permission whose name is built from it.
        //
        // Which edits this run asks for is decided in [DiscordManifestEdits], which says why each
        // one is there and what switches it; what follows is the single pass that applies them.
        val manifestEdits = DiscordManifestEdits.plan(
            packageName = originalPackageName,
            activePatchIds = activePatchSets.map { it.id }.toSet(),
            mergedLibraries = mergedLibraries,
            removedPermissions = permissionRemovals
        )

        // One pass over the manifest for every reason there is to edit it: a merged APK is no
        // longer a split, so the split declarations must go and the native libraries are now
        // DEFLATE-compressed so they must be extracted at install; a permission the user switched
        // off must be declared no more; and the components that belong to the things being
        // disabled have to go with them. One pass rather than five because each pass rewrites the
        // document and the next would have to re-read what it produced.
        if (manifestBytes != null && (mergedLibraries > 0 || !manifestEdits.isEmpty)) {
            val edit = if (mergedLibraries > 0) {
                BinaryXmlEditor.makeStandaloneManifest(
                    manifestBytes, manifestEdits.removals, manifestEdits.overrides
                )
            } else {
                // Nothing was merged, so nothing needs making standalone: this pass is the
                // removals and overrides and nothing else. Turning extractNativeLibs on here would
                // change an attribute the user never asked about.
                BinaryXmlEditor.edit(
                    xml = manifestBytes,
                    removeElements = manifestEdits.removals,
                    elementOverrides = manifestEdits.overrides
                )
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

            // Each group of edits is reported against its own selectors rather than against the
            // pass's total: one pass carries the permissions, the crash reporter's providers, the
            // Play markers and the attribution query, and a count taken from the whole edit would
            // credit every group with the others' removals.
            //
            // This group goes first because it is the one no switch moves: the declarations the
            // build has no code behind are removed on every run, and the permissions the user
            // switched off are removed after them. Both report through the same helper, so a build
            // that declares fewer dead permissions than were asked for says so rather than
            // reporting six removals it did not make.
            if (manifestEdits.deadPermissions.isNotEmpty()) {
                log(
                    manifestRemovalStep(
                        selectors = manifestEdits.deadPermissions,
                        edit = edit,
                        title = { count ->
                            "Removed $count dead permission " +
                                "${if (count == 1) "declaration" else "declarations"} from the manifest"
                        },
                        explanation = "Nothing in this build reads these: the code that would have used them is stubbed out, or was never there. " +
                            "A permission the app does not use is a capability it still asks the platform for, so the declarations are removed " +
                            "rather than left declared and unexercised — and unlike the permissions the user chose about, this is the same answer " +
                            "for every run."
                    )
                )
            }

            if (manifestEdits.permissions.isNotEmpty()) {
                log(
                    manifestRemovalStep(
                        selectors = manifestEdits.permissions,
                        edit = edit,
                        title = { count -> "Removed $count permission ${if (count == 1) "declaration" else "declarations"} from the manifest" },
                        explanation = "Android grants an app only the permissions its manifest declares, and an installed app cannot declare " +
                            "another one later, so these are gone for good: the app can never ask for them again. The declarations themselves " +
                            "were deleted — the rest of the manifest, its string pool and every other attribute, is byte-for-byte what the build shipped."
                    )
                )
            }

            if (manifestEdits.sentryProviders.isNotEmpty()) {
                log(
                    manifestRemovalStep(
                        selectors = manifestEdits.sentryProviders,
                        edit = edit,
                        title = { count -> "Removed $count Sentry ${if (count == 1) "provider" else "providers"} from the manifest" },
                        explanation = "Sentry declares these as content providers, and the platform instantiates a declared provider while the " +
                            "process starts — before any Java the crash-reporting patch stubs is reached. Removing the declaration is what stops " +
                            "the reporter starting at all rather than starting and then discarding what it collects."
                    )
                )
            }

            if (manifestEdits.playSplitMarkers.isNotEmpty()) {
                log(
                    manifestRemovalStep(
                        selectors = manifestEdits.playSplitMarkers,
                        edit = edit,
                        title = { count -> "Removed $count Play split ${if (count == 1) "marker" else "markers"} from the manifest" },
                        explanation = "These entries describe an APK that is one split of an App Bundle. The libraries and resources those " +
                            "splits carried are inside this APK now, so the markers describe an installation that no longer exists — and " +
                            "com.android.vending.splits.required is read as a claim that the app is missing the rest of its splits."
                    )
                )
            }

            if (manifestEdits.attributionQuery.isNotEmpty()) {
                log(
                    manifestRemovalStep(
                        selectors = manifestEdits.attributionQuery,
                        edit = edit,
                        title = { count -> "Removed $count AppsFlyer attribution ${if (count == 1) "query" else "queries"} from the manifest" },
                        explanation = "The query exists so the app can see whether the AppsFlyer install-referrer provider is installed on the " +
                            "device. Its only initialiser is the deep-link init this run no-ops, so the answer could not be used, and a " +
                            "package-visibility declaration left in place is a permission the app no longer has a purpose for."
                    )
                )
            }

            // The Google Analytics components. Disabled in place rather than removed, so what says
            // whether the edit landed is not a removal count but which of the three elements the
            // override found — the same signal the RPC service is reported on below.
            //
            // Like the RPC service it is reported whether or not it landed, but only for the app it
            // is about: on a build that never declared these components the override matched nothing
            // because it was never meant to, and a step saying so would be a line about Discord in
            // an OctoGram job. For Discord, "no such component" is worth saying — it is the build
            // having moved on from the one this was written against.
            val analyticsLabels = manifestEdits.googleAnalytics.map { it.element.label }
            val analyticsFound = analyticsLabels.count { it in edit.elementOverridesApplied }
            val analyticsDisabled = BinaryXmlEditor.ATTR_ENABLED in edit.attributesRewritten
            if (analyticsFound > 0 || analyticsDisabled || originalPackageName == DiscordManifestEdits.PACKAGE_NAME) {
                log(
                    StepResult(
                        title = when {
                            analyticsDisabled -> "Disabled the Google Analytics components"
                            analyticsFound > 0 -> "The Google Analytics components were already disabled"
                            else -> "This build declares no Google Analytics components"
                        },
                        explanation = "Google Analytics is inert in this build: the stub patches cut every path that would report through it, so " +
                            "its receiver has nothing to hand on and its job service has nothing to run. They are switched off rather than " +
                            "deleted because the SDK's classes are still in the dex — android:enabled=\"false\" is what the platform reads to " +
                            "leave a declared component uninstantiated, and it is the edit the reference makes.",
                        technicalTarget = "$analyticsFound of ${analyticsLabels.size} components, android:enabled=false",
                        status = if (analyticsDisabled) StepStatus.OK else StepStatus.SKIP,
                        detail = when {
                            analyticsFound < analyticsLabels.size ->
                                "no <receiver> or <service> with these names is declared, so there was nothing to disable"
                            !analyticsDisabled -> "every component this build declares was already switched off"
                            else -> null
                        }
                    )
                )
            }

            // Closing the RPC service carries no switch either, and it is reported whether or not
            // it landed, but only for the app it is about: on a build that has never declared this
            // component the edit matched nothing because it was never meant to, and a step saying so
            // would be a line about Discord in an OctoGram job. For Discord, "no such service" is
            // worth saying — it is the build having moved on from the one this was written against.
            val rpcLabel = manifestEdits.rpcService.element.label
            val rpcFound = rpcLabel in edit.elementOverridesApplied
            val rpcClosed = BinaryXmlEditor.ATTR_EXPORTED in edit.attributesRewritten
            if (rpcFound || rpcClosed || originalPackageName == DiscordManifestEdits.PACKAGE_NAME) {
                log(
                    StepResult(
                        title = when {
                            rpcClosed -> "Closed the RPC service to other apps"
                            rpcFound -> "The RPC service was already closed"
                            else -> "This build declares no RPC service"
                        },
                        explanation = "DiscordRpcService is exported with no permission on it and checks nothing about its caller, so any app " +
                            "installed on the device can bind it and publish presence frames as the user. It is closed unconditionally rather " +
                            "than behind a switch, because the other position of such a switch would be to leave that open.",
                        technicalTarget = "$rpcLabel android:exported=${
                            if (rpcClosed) "false (was true)" else "false (unchanged)"
                        }",
                        status = if (rpcClosed) StepStatus.OK else StepStatus.SKIP,
                        detail = if (rpcFound) null else "no <service> with this name is declared, so there was nothing to close"
                    )
                )
            }
        }

        if (permissionMismatches.isNotEmpty()) {
            val unlisted = permissionMismatches
                .filterIsInstance<DeclarationMismatch.Unlisted>()
                .map { it.name }
            val absent = permissionMismatches
                .filterIsInstance<DeclarationMismatch.Absent>()
                .map { it.name }
            log(
                StepResult(
                    title = "This build's permissions are not the ones sleepy lists for it",
                    explanation = "The permissions this release declares are listed in the app, and this build declares others than " +
                        "those. Nothing was removed to close the gap: the list is what the switches were shown against, a permission it " +
                        "does not name has no switch to have been moved, and deleting a declaration nobody was shown would be a change " +
                        "nobody chose. A build that no longer matches the list is one to check before patching.",
                    technicalTarget = buildList {
                        if (unlisted.isNotEmpty()) {
                            add("declared but not listed: ${unlisted.joinToString(", ")}")
                        }
                        if (absent.isNotEmpty()) {
                            add("listed but not declared: ${absent.joinToString(", ")}")
                        }
                    }.joinToString("; "),
                    status = StepStatus.SKIP
                )
            )
        }

        if (manifestEdits.permissions.isEmpty() && selection != null && PermissionCatalog.isEngaged(selection)) {
            log(
                StepResult(
                    title = "Kept every permission this build declares",
                    explanation = "Permissions were chosen about but none of them was switched off, so every declaration the build shipped is " +
                        "still in the manifest that was produced. The dead declarations removed above are the exception, and no choice moves " +
                        "those: they go because nothing in the build reads them, not because anyone asked.",
                    technicalTarget = "${chosenPermissions.size} declarations, none removed by choice",
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
                additionalEntries = mergedSplitEntries,
                droppedEntries = droppedArtefacts + droppedSplitMetadata
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
        val v1Verdict = when (verification.v1SignatureValid) {
            true -> "valid"
            false -> "NOT VALID"
            null -> "not applicable below API 24"
        }
        log(
            StepResult(
                title = "Checked the signatures on the finished APK",
                explanation = "Re-read the signed file and confirmed which signature schemes actually verify, rather than assuming signing worked. " +
                    "JAR signing (v1) is only honoured below API 24, so for a build that declares a higher minSdkVersion there is no verdict to " +
                    "report on it and the scheme is named as not applicable rather than failed.",
                technicalTarget = "v1=$v1Verdict, v2=${verification.v2SignatureValid}, v3=${verification.v3SignatureValid}",
                // A scheme that failed where it applies is a failure, and a scheme that does
                // not apply is not: only the first of those may fail this step.
                status = if (verification.v1SignatureValid == false ||
                    !(verification.v2SignatureValid || verification.v3SignatureValid)
                ) {
                    StepStatus.FAIL
                } else {
                    StepStatus.OK
                },
                detail = verification.v1NotApplicableReason
                    ?: verification.signatureErrors.takeIf { it.isNotEmpty() }?.joinToString("; ")
            )
        )
        log(
            StepResult(
                title = "Checked ZIP alignment on the finished APK",
                explanation = "Confirmed every uncompressed entry starts where the platform needs it to — 4-byte alignment for the " +
                    "resource table, page alignment for any library stored uncompressed. Compressed entries have no alignment " +
                    "requirement, so they are not counted. An archive whose central directory cannot be read is reported as " +
                    "unchecked, never as aligned.",
                technicalTarget = when (verification.zipalignPassed) {
                    true -> "all entries 4-byte aligned"
                    false -> "${verification.misalignedEntries.size} misaligned"
                    null -> "the archive's central directory could not be read"
                },
                // An archive this app cannot read is one it cannot vouch for, so an unknown
                // answer fails the step rather than passing quietly.
                status = if (verification.zipalignPassed == true) StepStatus.OK else StepStatus.FAIL,
                detail = verification.directoryError
                    ?: verification.misalignedEntries.take(5).takeIf { it.isNotEmpty() }?.joinToString(", ")
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
                mergedResourceFiles = mergedResources,
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
     * The step result for one group of [selectors] the manifest pass was asked to remove.
     *
     * The edit reports what it did for the whole pass, and one pass carries several unrelated
     * groups — the permissions the user switched off, the crash reporter's providers, the Play
     * split markers, the attribution query. Counting the pass would credit every group with the
     * others' work, so each of them is counted against its own selectors here.
     *
     * A group whose elements were not all found is a SKIP with the ones that were absent named,
     * never a quiet pass: a build that has moved on from the one these selectors were written
     * against is something the user has to be told about, not something to absorb.
     */
    private fun manifestRemovalStep(
        selectors: List<BinaryXmlEditor.ElementSelector>,
        edit: BinaryXmlEditor.EditResult,
        title: (Int) -> String,
        explanation: String
    ): StepResult {
        val labels = selectors.map { it.label }
        val removed = edit.elementsRemoved.filter { it in labels }
        val missing = edit.elementsMissing.filter { it in labels }
        return StepResult(
            title = title(removed.size),
            explanation = explanation,
            technicalTarget = removed
                .take(6)
                .joinToString(", ")
                .let { if (removed.size > 6) "$it and ${removed.size - 6} more" else it }
                .ifEmpty { "${labels.size} asked for, none present" },
            status = if (missing.isEmpty()) StepStatus.OK else StepStatus.SKIP,
            detail = missing.take(5).takeIf { it.isNotEmpty() }
                ?.joinToString("; ") { "$it is not declared by this build, so there was nothing to remove" }
        )
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

    /** What a fetched split contributes: its files, and its resource table when it has one. */
    private class FetchedSplit(
        val report: SplitMerger.MergeReport<SplitMerger.MergedFileEntry>,
        val resourceTable: ByteArray?
    )

    /**
     * Downloads configuration split [index] and merges its native libraries into [workDir].
     *
     * The split itself is a file for the length of the merge and then deleted: it is another
     * tens of megabytes, and only the libraries inside it are wanted. As with
     * [downloadSource], the split's bytes never become a local of [execute] — only its resource
     * table does, and a split's table is a few hundred kilobytes rather than tens of megabytes.
     */
    private suspend fun fetchSplit(url: String, index: Int, workDir: File): FetchedSplit {
        val splitApk = File(workDir, "split_$index.apk")
        try {
            splitApk.writeBytes(Downloader.download(url) { _, _ -> })
            // Read before the merge rather than after: the merge writes the split's entries out
            // as files and the split itself is deleted on the way out of this call, so a table
            // not read here would have to be downloaded again.
            val table = extractEntry(splitApk, RESOURCE_TABLE_ENTRY)
            return FetchedSplit(SplitMerger.mergeSplitToDir(splitApk, workDir), table)
        } finally {
            splitApk.delete()
        }
    }

    /**
     * The one table naming every merged resource, or why one was not built.
     *
     * [ResourceTableMerger] checks what it builds structurally. This adds the check that is about
     * the archive rather than the table, and it is the same claim in both directions: the files the
     * table names are exactly the resource files the repack is about to write — the base's own,
     * less the ones [droppablePaths] says this run drops, plus the ones the split merge brought in.
     * A path named and absent is a resource that resolves to nothing; a file present and unnamed is
     * a resource nothing can ask for, which is what "merged but unreachable" means. Either one is
     * the failure this step exists to remove, so a table that would introduce one is refused and
     * the caller keeps the base's.
     *
     * Only the names the merge was asked to drop are subtracted, never the whole of
     * [droppablePaths]: what the archive is about to lose is what the table actually stopped
     * naming, and [ResourceTableMerger.Result.Merged.droppedPaths] is that and nothing more.
     */
    private fun mergeResourceTables(
        baseTable: ByteArray,
        splitTables: List<ByteArray>,
        mergedEntryNames: Set<String>,
        droppablePaths: Set<String>
    ): ResourceTableMerger.Result {
        val result = ResourceTableMerger.merge(baseTable, splitTables, droppablePaths)
        if (result !is ResourceTableMerger.Result.Merged) return result

        val basePaths = ResourceTableMerger.namedPaths(baseTable) ?: return ResourceTableMerger.Result.Refused(
            "the base's own resource table could not be read back for comparison"
        )
        val named = ResourceTableMerger.namedPaths(result.table) ?: return ResourceTableMerger.Result.Refused(
            "the merged resource table could not be read back for comparison"
        )
        val held = (basePaths - result.droppedPaths) + mergedEntryNames.filter { it.startsWith(RES_DIR) }
        val unheld = named - held
        if (unheld.isNotEmpty()) {
            return ResourceTableMerger.Result.Refused(
                "the merged table names ${unheld.size} files this APK would not hold, starting with ${unheld.first()}"
            )
        }
        val unnamed = held - named
        if (unnamed.isNotEmpty()) {
            return ResourceTableMerger.Result.Refused(
                "this APK would hold ${unnamed.size} resource files the merged table does not name, starting with ${unnamed.first()}"
            )
        }
        return result
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
        const val RESOURCE_TABLE_ENTRY = "resources.arsc"

        /**
         * The directory a merged entry must sit under to be a resource the table can name.
         *
         * A merged split contributes shared objects as well as resources, and the table names files
         * under `res/` and nothing else: an entry under any other directory is one the table could
         * not name, so it is not part of what the two sets are compared over.
         */
        const val RES_DIR = "res/"
    }
}
