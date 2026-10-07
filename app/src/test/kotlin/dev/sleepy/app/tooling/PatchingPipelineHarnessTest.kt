package dev.sleepy.app.tooling

import dev.sleepy.app.engine.ApkVerifier
import dev.sleepy.app.engine.BinaryXmlEditor
import dev.sleepy.app.engine.BinaryXmlModifier
import dev.sleepy.app.engine.DexProcessor
import dev.sleepy.app.engine.DiscordManifestEdits
import dev.sleepy.app.engine.HermesBundlePatcher
import dev.sleepy.app.engine.HermesPatcher
import dev.sleepy.app.engine.PatchVersionGate
import dev.sleepy.app.engine.ResourceTableMerger
import dev.sleepy.app.engine.SplitMerger
import dev.sleepy.app.engine.ZipRepacker
import dev.sleepy.app.model.PatchSelection
import dev.sleepy.app.model.SelectivePatchGenerator
import dev.sleepy.app.model.SmaliPatch
import dev.sleepy.app.model.StepResult
import dev.sleepy.app.model.StepStatus
import dev.sleepy.app.model.TargetApk
import dev.sleepy.app.patches.DeclaredPermissions
import dev.sleepy.app.patches.DiscordHermesBundlePatch
import dev.sleepy.app.patches.DiscordHermesFunctionCatalog
import dev.sleepy.app.patches.DiscordPatches
import dev.sleepy.app.patches.PatchItemCatalog
import dev.sleepy.app.patches.PatchRegistry
import dev.sleepy.app.patches.PermissionCatalog
import dev.sleepy.app.testing.ComparisonApks
import dev.sleepy.app.testing.sourceFile
import dev.sleepy.app.util.HashUtils
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Tooling: runs the pipeline's steps over the local Discord 349.5 fixtures and writes the
 * resulting APK to a file for comparison with the recorded build.
 *
 * This class is not a unit test. It reproduces `PatchingPipeline.execute` with the two steps that
 * need a `Context` replaced by file operations, so the artifact this app produces can be
 * inspected without a device. It lives in the tooling package and is the only class in the suite
 * that writes an APK to disk.
 */
class PatchingPipelineHarnessTest {

    private companion object {
        /**
         * `sources.json` -> `sources[] -> discord_349205 -> patch_ids`, in the order it declares
         * them. The order is not what the pipeline runs them in—`PatchViewModel.startPatch`
         * passes the *catalog's* order ([PatchItemCatalog.all]), which is what this reproduces—
         * but the membership is this list and nothing else.
         */
        val SOURCE_PATCH_IDS = listOf(
            "discord_ota_bundle",
            "discord_sentry",
            "discord_telemetry",
            "discord_hermes",
            "discord_native_sentry",
            "discord_native_deep_links",
            "discord_native_watchdog",
            "discord_native_telemetry_modules",
            "discord_native_log_noise",
            "discord_native_audit",
            "discord_native_okhttp_cache",
            "discord_native_interceptors",
            "discord_native_js_polls",
            "discord_native_systrace",
            "discord_native_crash_logcat",
            "discord_native_privacy",
            "discord_native_resource_monitors",
            "discord_native_call_path",
            "discord_native_media",
            "discord_native_foreground_service",
            "discord_native_experiments",
            "discord_native_blocklist"
        )

        /**
         * `sources.json` -> `discord_349205 -> splits`, as the local files its URLs name: the
         * ABI split first, then the density and language splits, in the declared order. The
         * x86_64 and armeabi-v7a splits that sit beside them are *not* merged, because the source
         * does not list them.
         */
        val SOURCE_SPLITS = listOf(
            "config.arm64_v8a.apk",
            "config.hdpi.apk",
            "config.de.apk",
            "config.en.apk"
        )

        const val ORIGINAL_PACKAGE = "com.discord"

        /** The harness copies its fixture from disk, so it has no download to check. */
        val EXPECTED_SHA256: String? = null

        const val BUNDLE_ENTRY = "assets/index.android.bundle"
        const val MANIFEST_ENTRY = "AndroidManifest.xml"
        const val RESOURCE_TABLE_ENTRY = "resources.arsc"

        /** Matches `classes.dex`, `classes2.dex`, ...—the DEX files of a single APK. */
        val DEX_ENTRY_NAME = Regex("classes\\d*\\.dex")

        /**
         * The patch sets this run applies, when a bisection asks for a subset of them.
         *
         * `null`—the property and the variable both unset—is the default build, and it means
         * [SOURCE_PATCH_IDS] exactly as before. A value is a comma-separated list of set ids, and
         * an *empty* value is a build with no patch sets at all: the split merge, the resource
         * table rebuild and the manifest pass still run, so "no patches" isolates the patch content
         * from the packaging path. Read from `-Dsleepy.patchIds` first and `SLEEPY_PATCH_IDS`
         * second, so a shell that already exports the variable does not have to be edited.
         */
        val PATCH_IDS_OVERRIDE: List<String>? = run {
            val raw = System.getProperty("sleepy.patchIds") ?: System.getenv("SLEEPY_PATCH_IDS")
            raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        }
    }

    /** The step log `execute()` writes, held here instead of in a `StateFlow`. */
    private val log = mutableListOf<StepResult>()

    private fun log(result: StepResult) {
        log.add(result)
        println(
            "  [${result.status}] ${result.title}" +
                (result.technicalTarget?.let { " :: $it" } ?: "") +
                (result.detail?.let { " (${it})" } ?: "")
        )
    }

    @Test
    fun buildsTheDiscordAlpha3495ApkTheWayPatchingPipelineDoes() = runBlocking {
        val extracted = ComparisonApks.discordExtracted
        val baseApk = File(extracted, "base.apk")
        val splits = SOURCE_SPLITS.map { File(extracted, it) }
        assumeTrue(
            "the Discord fixtures are not on this machine (${extracted.path})",
            baseApk.isFile && splits.all { it.isFile }
        )
        assertSourceTablesStillMatchSourcesJson()

        println("Heap: max ${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MB")

        val outputDir = ComparisonApks.harnessOutputDir.apply { mkdirs() }
        val outputFile = File(outputDir, "ours.apk")
        // `PatchingPipeline.start` owns a scratch directory for the job and deletes it on the way
        // out. The same directory here, left in place afterward so a follow-up diff can read the
        // merged split entries back.
        val workDir = File(outputDir, "harness-work")
        workDir.deleteRecursively()
        assertTrue("cannot create ${workDir.absolutePath}", workDir.mkdirs())

        // ---------------------------------------------------------------- 1. / 2. downloadSource
        // `execute` line 169-171. The download's bytes land on disk and do not become a local of
        // this coroutine, which is why `downloadSource` exists.
        val sourceApk = File(workDir, "source.apk")
        baseApk.copyTo(sourceApk, overwrite = true)
        log(
            StepResult(
                title = "Took the original APK from disk",
                explanation = "Stands in for the download: the file is the one the source's URL " +
                    "serves, copied rather than fetched.",
                technicalTarget = baseApk.path,
                status = StepStatus.OK,
                detail = "${sourceApk.length() / (1024 * 1024)} MB"
            )
        )
        if (EXPECTED_SHA256 != null) {
            val actual = HashUtils.sha256Hex(sourceApk)
            if (!actual.equals(EXPECTED_SHA256, ignoreCase = true)) {
                throw IllegalStateException(
                    "Source integrity check failed: expected SHA-256 $EXPECTED_SHA256 but the " +
                        "local file hashes to $actual."
                )
            }
            log(
                StepResult(
                    title = "Verified the download against its published SHA-256",
                    explanation = "Confirmed the file is byte-for-byte the build the source " +
                        "published.",
                    technicalTarget = "SHA-256 $actual",
                    status = StepStatus.OK
                )
            )
        } else {
            log(
                StepResult(
                    title = "No published hash to verify against",
                    explanation = "This source does not publish a SHA-256.",
                    technicalTarget = "sha256_expected is null",
                    status = StepStatus.SKIP
                )
            )
        }

        // ------------------------------------------------------------- 3. merge the config splits
        // `execute` lines 176-313.
        var mergedLibraries = 0
        var mergedResources = 0
        var mergedLibraryBytes = 0L
        var mergedResourceBytes = 0L
        var mergedResourceTable: ByteArray? = null
        // The split-install metadata the rebuilt table stopped naming, and so what the repack may
        // drop. Empty until a rebuild lands without the row.
        var droppedSplitMetadata: Set<String> = emptySet()
        val splitResourceTables = mutableListOf<ByteArray>()
        val mergedSplitEntries = linkedMapOf<String, ZipRepacker.AdditionalEntry>()
        if (splits.isNotEmpty()) {
            val abis = linkedSetOf<String>()
            for ((index, splitFile) in splits.withIndex()) {
                println("Fetching split ${index + 1} of ${splits.size}: ${splitFile.name}")
                // `fetchSplit`: the split is a file in the work directory for the length of the
                // merge, named exactly as the pipeline names it, and deleted on the way out.
                val splitApk = File(workDir, "split_$index.apk")
                try {
                    splitFile.copyTo(splitApk, overwrite = true)
                    // Read before the merge: the merge writes the split's entries out as files and
                    // the split itself is deleted on the way out of this call.
                    val table = extractEntry(splitApk, RESOURCE_TABLE_ENTRY)
                    val report = SplitMerger.mergeSplitToDir(splitApk, workDir)
                    table?.let { splitResourceTables.add(it) }
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
                } finally {
                    splitApk.delete()
                }
            }

            if (mergedLibraries > 0) {
                log(
                    StepResult(
                        title = "Merged the native libraries the base split was missing",
                        explanation = "An App Bundle base split ships without any lib/ directory.",
                        technicalTarget = "$mergedLibraries libraries for " +
                            "${abis.joinToString(", ")}, ~${mergedLibraryBytes / (1024 * 1024)} MB",
                        status = StepStatus.OK
                    )
                )
            } else {
                log(
                    StepResult(
                        title = "No native libraries found in the configuration splits",
                        explanation = "The splits contained no lib/ entries.",
                        technicalTarget = splits.joinToString(", ") { it.name },
                        status = StepStatus.SKIP
                    )
                )
            }

            if (mergedResources > 0) {
                log(
                    StepResult(
                        title = "Merged the resources the base split was missing",
                        explanation = "The density and language splits' res/ trees, put back at " +
                            "the paths the merged APK has them at.",
                        technicalTarget = "$mergedResources resources, " +
                            "~${mergedResourceBytes / (1024 * 1024)} MB",
                        status = StepStatus.OK
                    )
                )
            }

            // 3b. The resource table. `execute` lines 265-312.
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
                            explanation = "This APK carries no resources.arsc of its own.",
                            technicalTarget = RESOURCE_TABLE_ENTRY,
                            status = StepStatus.SKIP
                        )
                    )

                    is ResourceTableMerger.Result.Merged -> {
                        mergedResourceTable = merge.table
                        log(
                            StepResult(
                                title = "Rebuilt the resource table around the merged resources",
                                explanation = "The base's table and the splits' tables, merged " +
                                    "chunk by chunk rather than relinked.",
                                technicalTarget = "${merge.sourceCount} tables -> " +
                                    "${merge.resourceCount} resources over " +
                                    "${merge.typeCount} types, ${merge.table.size / 1024} KB",
                                status = StepStatus.OK
                            )
                        )
                        droppedSplitMetadata = merge.droppedPaths
                        if (droppedSplitMetadata.isNotEmpty()) {
                            log(
                                StepResult(
                                    title = "Dropped the split-install metadata and the table " +
                                        "row that named it",
                                    explanation = "res/xml/splits0.xml lists the configuration " +
                                        "splits an installation has: it is the resource-side " +
                                        "twin of the Play split markers, and it goes with the " +
                                        "table row that named it.",
                                    technicalTarget = "${droppedSplitMetadata.joinToString(", ")}" +
                                        ", and the row naming it",
                                    status = StepStatus.OK
                                )
                            )
                        }
                    }

                    is ResourceTableMerger.Result.Refused -> log(
                        StepResult(
                            title = "Left the resource table as the base split shipped it",
                            explanation = "The merged table could not be shown to be sound.",
                            technicalTarget = "${splitResourceTables.size} split tables were " +
                                "not merged",
                            status = StepStatus.SKIP,
                            detail = merge.reason
                        )
                    )
                }
            }
        }

        // ----------------------------------------------------------------------- 4. inspect
        // `execute` lines 317-445.
        val dexEntries = extractDexEntries(sourceApk)
        val bundleBytes = extractEntry(sourceApk, BUNDLE_ENTRY)
        var manifestBytes = extractEntry(sourceApk, MANIFEST_ENTRY)

        log(
            StepResult(
                title = "Opened the APK and located its code",
                explanation = "Only the parts being patched are unpacked.",
                technicalTarget = "${dexEntries.size} DEX files, JS bundle " +
                    "${if (bundleBytes != null) "present" else "absent"}",
                status = StepStatus.OK
            )
        )
        println(
            "DEX files: ${dexEntries.entries.joinToString(", ") { "${it.key}=${it.value.size}" }}"
        )
        println("Bundle: ${bundleBytes?.size ?: 0} bytes")

        val declaredPermissions = manifestBytes?.let { manifest ->
            BinaryXmlEditor.readElementAttributeValues(
                xml = manifest,
                namePrefix = BinaryXmlEditor.ELEMENT_USES_PERMISSION,
                attributeId = BinaryXmlEditor.ATTR_NAME
            )
        } ?: emptyList()
        val shippedPermissions = DeclaredPermissions.forPackage(ORIGINAL_PACKAGE)
        val chosenPermissions = shippedPermissions ?: declaredPermissions
        println(
            "Permissions: ${declaredPermissions.size} declared, " +
                "${shippedPermissions?.size ?: 0} shipped for $ORIGINAL_PACKAGE"
        )

        // The selection a default build makes. `PatchViewModel.selectSource` expands the source's
        // patch ids into items and seeds every permission it ships as *kept*—a run removes only
        // what the user switches off, and a default build switches nothing off.
        // A bisection build asks for a subset (or none) of the source's patch sets; a default
        // build is the whole list. Everything downstream reads this, so the two cannot drift.
        val patchIds = PATCH_IDS_OVERRIDE ?: SOURCE_PATCH_IDS
        println(
            "Patch sets for this run " +
                "(${if (PATCH_IDS_OVERRIDE == null) "default" else "override"}): " +
                "${patchIds.ifEmpty { listOf("none") }}"
        )
        val selection = PatchSelection.fromSavedIds(patchIds, PatchItemCatalog)
            .with(PermissionCatalog.itemsOf(shippedPermissions.orEmpty()))
        val permissionRemovals =
            PermissionCatalog.removals(chosenPermissions, selection, ORIGINAL_PACKAGE)
        println(
            "Permission removals for this selection: " +
                "${permissionRemovals.ifEmpty { listOf("none") }}"
        )
        assertTrue(
            "a default build switches nothing off, so nothing is the user's to remove",
            permissionRemovals.isEmpty()
        )
        // The declarations this build removes by itself are the plan's own group, not something the
        // user asked for, and the manifest pass receives them once. A saved selection made before
        // their rows stopped offering a switch does not name them, so this is a state the app can
        // still be in—and reading it as six removals puts every one of them in the list the pass
        // is given as well, where the second attempt reports an element the build no longer has.
        val staleSelection =
            selection.without(PermissionCatalog.itemsOf(DiscordPatches.DEAD_PERMISSIONS))
        assertTrue(
            "a selection that does not name the dead declarations must not ask for them either",
            PermissionCatalog.removals(chosenPermissions, staleSelection, ORIGINAL_PACKAGE)
                .isEmpty()
        )

        val classToDexIndex = DexProcessor.buildClassToDexIndex(dexEntries)
        val detectedOctoGramVersion = PatchVersionGate.detectOctoGramVersion(classToDexIndex)
        println("Detected OctoGram version: $detectedOctoGramVersion (expect null on Discord)")

        // `PatchViewModel.startPatch` passes the pipeline the *catalog's* order of the selected
        // sets, not `sources.json`'s, so the order is reproduced from the catalog.
        val selectedSetIds = PatchItemCatalog.all()
            .filter { selection.contains(it) }
            .map { it.setId }
            .distinct()
        assertEquals(
            "the sets this run applies must be exactly the ids it was handed",
            patchIds.toSet(),
            selectedSetIds.toSet()
        )
        val activePatchSets = selectedSetIds
            .mapNotNull { PatchRegistry.get(it) }
            .filter { set -> PatchItemCatalog.itemsOf(set.id).any { selection.contains(it.key) } }
        println(
            "Active patch sets (${activePatchSets.size}): " +
                "${activePatchSets.joinToString(", ") { it.id }}"
        )

        val hermesSelected = activePatchSets.any { it.id == DiscordPatches.HERMES.id }
        val hermesPatches = activePatchSets.flatMap { it.hermesPatches }
        val hermesTableToApply = if (hermesSelected) {
            DiscordHermesFunctionCatalog.selectPatches(selection)
        } else {
            DiscordHermesBundlePatch.PATCHES
        }
        val hermesWorkCount =
            if (bundleBytes != null &&
                bundleBytes.size == DiscordHermesBundlePatch.TARGET_BUNDLE_SIZE
            ) {
                hermesTableToApply.size
            } else {
                hermesPatches.size
            }

        val smaliPatchesToApply = mutableListOf<SmaliPatch>()
        val targetApk = TargetApk(classToDexIndex = classToDexIndex, dexEntries = dexEntries)
        for (patchSet in activePatchSets) {
            val generator = patchSet.generator
            val generated = if (generator is SelectivePatchGenerator) {
                generator.generate(targetApk, selection)
            } else {
                generator?.generate(targetApk)
            }
            val candidates = patchSet.smaliPatches + (generated?.patches ?: emptyList())

            val matchingPatches = candidates.filter { patch ->
                if (!PatchVersionGate.admits(patch, detectedOctoGramVersion)) {
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
                val reason = PatchVersionGate.refusalReason(candidates, detectedOctoGramVersion)
                    ?: "The classes this patch edits are not present in this APK, so it was " +
                        "not attempted."
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
        println(
            "Smali patches resolved: ${smaliPatchesToApply.size} across " +
                "${smaliPatchesToApply.groupBy { it.dexName }.size} DEX files, " +
                "Hermes patches queued: $hermesWorkCount"
        )

        // -------------------------------------------------------------- 5. apply the DEX patches
        // `execute` lines 449-486.
        val repackedDexMap = mutableMapOf<String, ByteArray>()
        val groupedPatches = smaliPatchesToApply.groupBy { it.dexName!! }
        for ((dexName, patchesForDex) in groupedPatches) {
            val dexBytes = dexEntries[dexName]
            if (dexBytes == null) {
                patchesForDex.forEach {
                    log(
                        StepResult(
                            it.smaliPath,
                            StepStatus.SKIP,
                            "DEX $dexName is not present in this APK"
                        )
                    )
                }
                continue
            }
            val (patchedBytes, dexResults) = DexProcessor.patchDexSurgically(
                dexBytes = dexBytes,
                patches = patchesForDex
                // `onPatchStart` is the progress bar's callback and nothing else; left at its
                // default here.
            )
            repackedDexMap[dexName] = patchedBytes
            dexResults.forEach { log(it) }
        }
        println(
            "Patched DEX: " +
                repackedDexMap.entries.joinToString(", ") { "${it.key}=${it.value.size}" } +
                " (" +
                dexEntries.entries.joinToString(", ") { "${it.key}=${it.value.size}" } +
                " before)"
        )

        // ------------------------------------------------------------ 6. apply the Hermes patches
        // `execute` lines 490-549.
        var finalBundle = bundleBytes
        if (hermesSelected && bundleBytes != null) {
            val (afterDsn, dsnResult) = HermesPatcher.nullifySentryDsn(bundleBytes)
            if (dsnResult != null) log(dsnResult)
            finalBundle = afterDsn

            if (bundleBytes.size == DiscordHermesBundlePatch.TARGET_BUNDLE_SIZE) {
                val outcome = HermesBundlePatcher.apply(afterDsn, hermesTableToApply)
                finalBundle = outcome.bundleBytes
                log(
                    StepResult(
                        title = "Neutralized ${outcome.appliedCount} JavaScript functions",
                        explanation = "Discord's JavaScript analytics, crash reporting and " +
                            "promotional screens.",
                        technicalTarget = buildString {
                            append("${outcome.writtenInPlace.size} rewritten in place")
                            if (outcome.relocated.isNotEmpty()) {
                                append(", ${outcome.relocated.size} moved to make room")
                            }
                        },
                        status = if (outcome.skipped.isEmpty()) StepStatus.OK else StepStatus.FAIL,
                        detail = outcome.skipped.take(5)
                            .joinToString("; ") {
                                "${it.name.ifBlank { "fn ${it.functionId}" }}: ${it.detail}"
                            }
                            .takeIf { it.isNotEmpty() }
                    )
                )
                println(
                    "Bundle: ${bundleBytes.size} -> ${outcome.bundleBytes.size} bytes, " +
                        "${outcome.appliedCount} applied " +
                        "(${outcome.writtenInPlace.size} in place, " +
                        "${outcome.relocated.size} relocated), ${outcome.skipped.size} skipped"
                )
            } else {
                log(
                    StepResult(
                        title = "JavaScript patches skipped",
                        explanation = "This build's JavaScript bundle is not the one these " +
                            "patches were derived from.",
                        technicalTarget = "bundle is ${bundleBytes.size} bytes, patches target " +
                            "${DiscordHermesBundlePatch.TARGET_BUNDLE_SIZE}",
                        status = StepStatus.SKIP
                    )
                )
            }
        } else if (hermesSelected) {
            log(
                StepResult(
                    title = "JavaScript patches skipped",
                    explanation = "This APK has no JavaScript bundle to patch.",
                    technicalTarget = BUNDLE_ENTRY,
                    status = StepStatus.SKIP
                )
            )
        }

        // ---------------------------------------------------------------------- 7. rebuild
        // `execute` lines 553-815.
        val replacements = repackedDexMap.toMutableMap<String, ByteArray>()
        if (finalBundle != null && bundleBytes != null) {
            replacements[BUNDLE_ENTRY] = finalBundle
        }
        mergedResourceTable?.let { replacements[RESOURCE_TABLE_ENTRY] = it }

        val manifestEdits = DiscordManifestEdits.plan(
            packageName = ORIGINAL_PACKAGE,
            activePatchIds = activePatchSets.map { it.id }.toSet(),
            mergedLibraries = mergedLibraries,
            removedPermissions = permissionRemovals
        )
        println(
            "Manifest plan: ${manifestEdits.deadPermissions.size} dead permissions, " +
                "${manifestEdits.permissions.size} permissions, " +
                "${manifestEdits.sentryProviders.size} sentry providers, " +
                "${manifestEdits.playSplitMarkers.size} play markers, " +
                "${manifestEdits.attributionQuery.size} attribution queries, " +
                "${manifestEdits.overrides.size} overrides"
        )

        // One selector per declaration, and the two groups are disjoint: a name in both would be
        // the same element asked for twice in one pass, and the second attempt would report an
        // element the manifest no longer has. The permission list keeps the dead declarations out
        // of the user's removals, so this is the other half of that check—the pass's own group
        // is what removes them.
        val askedTwice = manifestEdits.deadPermissions.map { it.attributeValue }.toSet() intersect
            manifestEdits.permissions.map { it.attributeValue }.toSet()
        assertTrue(
            "no declaration may be removed by both groups, found: $askedTwice",
            askedTwice.isEmpty()
        )
        assertEquals(
            "the pass's own group must be exactly the declarations this build removes",
            DiscordPatches.DEAD_PERMISSIONS,
            manifestEdits.deadPermissions.map { it.attributeValue }
        )

        // Held out here so the checks at the end can report which of the plan's edits the pass
        // made; `execute` keeps it inside the branch because nothing after it reads it.
        var manifestEdit: BinaryXmlEditor.EditResult? = null
        if (manifestBytes != null && (mergedLibraries > 0 || !manifestEdits.isEmpty)) {
            val edit = if (mergedLibraries > 0) {
                BinaryXmlEditor.makeStandaloneManifest(
                    manifestBytes, manifestEdits.removals, manifestEdits.overrides
                )
            } else {
                BinaryXmlEditor.edit(
                    xml = manifestBytes,
                    removeElements = manifestEdits.removals,
                    elementOverrides = manifestEdits.overrides
                )
            }
            manifestEdit = edit
            manifestBytes = edit.bytes

            log(
                StepResult(
                    title = "Edited the manifest",
                    explanation = "One pass: split declarations out, extractNativeLibs on, the " +
                        "removals and overrides the plan asked for.",
                    technicalTarget = "${edit.elementsRemoved.size} elements removed, " +
                        "${edit.elementsMissing.size} missing, " +
                        "${edit.attributesRemoved.size} attributes removed, " +
                        "${edit.attributesRewritten.size} rewritten, " +
                        "${edit.elementOverridesApplied.size} overrides applied",
                    status = StepStatus.OK,
                    detail = "removed: ${edit.elementsRemoved.ifEmpty { listOf("none") }}; " +
                        "missing: ${edit.elementsMissing.ifEmpty { listOf("none") }}"
                )
            )
        }

        // The clone rename. `execute` lines 764-778. A default build is not in clone mode
        // (`PatchViewModel.isCloneMode` starts false), so `customPackageName` is null and this
        // branch is skipped—held here as the same branch it is in `execute`, not a deletion.
        //
        // Overridable so the rename can be exercised on a real device without editing this file:
        // `-Dsleepy.clonePackageName=com.discord.sleepy`. Unset means the default build.
        val customPackageName: String? =
            (System.getProperty("sleepy.clonePackageName") ?: System.getenv("SLEEPY_CLONE_PACKAGE"))
                ?.takeIf { it.isNotBlank() }
        if (!customPackageName.isNullOrBlank() &&
            customPackageName != ORIGINAL_PACKAGE &&
            manifestBytes != null
        ) {
            manifestBytes = BinaryXmlModifier.modifyPackageName(
                manifestBytes = manifestBytes,
                oldPackageName = ORIGINAL_PACKAGE,
                newPackageName = customPackageName
            )
            log(
                StepResult(
                    title = "Renamed the package so it installs alongside the original",
                    explanation = "Changing the application ID lets the patched build coexist " +
                        "with the official app.",
                    technicalTarget = "$ORIGINAL_PACKAGE -> $customPackageName",
                    status = StepStatus.OK
                )
            )
            // `execute` lines 786-813: the table's package chunk names the package too, and it is
            // that copy a name-based resource lookup is resolved against. Renamed here together
            // with the manifest, because the two are the same fact.
            val shippingTable = mergedResourceTable ?: extractEntry(sourceApk, RESOURCE_TABLE_ENTRY)
            val renamedTable = shippingTable?.let {
                ResourceTableMerger.renamePackage(it, customPackageName)
            }
            when {
                renamedTable == null -> log(
                    StepResult(
                        title = "Left the resource table's package as it was",
                        explanation = "This APK's resource table has no package chunk that " +
                            "could be renamed, or the name is longer than the 128 characters " +
                            "that field holds.",
                        technicalTarget = RESOURCE_TABLE_ENTRY,
                        status = StepStatus.FAIL
                    )
                )

                ResourceTableMerger.packageName(renamedTable) != customPackageName -> log(
                    StepResult(
                        title = "Left the resource table's package as it was",
                        explanation = "The rename was written and did not read back as the " +
                            "name it was given.",
                        status = StepStatus.FAIL,
                        technicalTarget = "$RESOURCE_TABLE_ENTRY says " +
                            "${ResourceTableMerger.packageName(renamedTable) ?: "nothing readable"}"
                    )
                )

                else -> {
                    replacements[RESOURCE_TABLE_ENTRY] = renamedTable
                    log(
                        StepResult(
                            title = "Renamed the resource table's package to match the manifest",
                            explanation = "The package name is spelled in the resource table " +
                                "as well as in the manifest, and the table's copy is what the " +
                                "platform resolves a name-based lookup against.",
                            technicalTarget = "$ORIGINAL_PACKAGE -> $customPackageName in " +
                                "$RESOURCE_TABLE_ENTRY",
                            status = StepStatus.OK
                        )
                    )
                }
            }
        } else {
            println("Clone rename: not requested by a default build, so the package is unchanged")
        }

        if (manifestBytes != null) {
            replacements[MANIFEST_ENTRY] = manifestBytes
        }

        val droppedArtefacts = if (activePatchSets.any { it.id == DiscordPatches.SENTRY.id }) {
            DiscordPatches.SENTRY_ARTEFACTS
        } else {
            emptySet()
        }

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
                explanation = "Repacked the archive and re-aligned every entry to a " +
                    "4-byte boundary.",
                technicalTarget = "${repack.replacedEntries.size} entries replaced, " +
                    "${repack.addedEntries.size} added, ${repack.droppedEntries.size} dropped",
                status = StepStatus.OK
            )
        )

        // ------------------------------------------------------------ 8. sign—SKIPPED ON PURPOSE
        // `execute` line 824 (`ApkSignerHelper.sign(context, rebuiltApk, outputFile)`) is the one
        // step that needs a `Context` for anything other than a download, and the ask is an
        // unsigned artifact. Nothing above or below it is affected: the signer reads `rebuiltApk`
        // and writes a new file, so the bytes it receives are exactly the bytes copied out below.
        println(
            "Signing skipped by design (ApkSignerHelper needs a Context for its keystore): " +
                "the repack output is the artifact"
        )
        rebuiltApk.copyTo(outputFile, overwrite = true)

        // ------------------------------------------------------------------ 9. verify the result
        // `execute` lines 837-906, minus the `Keep`/`Uri`/`StateFlow` parts of the report.
        val verification = ApkVerifier.verify(outputFile)
        log(
            StepResult(
                title = "Checked the signatures on the finished APK",
                explanation = "Re-read the file and confirmed which signature schemes verify.",
                technicalTarget = "v1=${verification.v1SignatureValid}, " +
                    "v2=${verification.v2SignatureValid}, v3=${verification.v3SignatureValid}",
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
                explanation = "Every uncompressed entry starts where the platform needs it to.",
                technicalTarget = when (verification.zipalignPassed) {
                    true -> "all entries 4-byte aligned"
                    false -> "${verification.misalignedEntries.size} misaligned"
                    null -> "the archive's central directory could not be read"
                },
                status = if (verification.zipalignPassed == true) {
                    StepStatus.OK
                } else {
                    StepStatus.FAIL
                },
                detail = verification.directoryError
                    ?: verification.misalignedEntries.take(5).takeIf { it.isNotEmpty() }
                        ?.joinToString(", ")
            )
        )

        val sha256 = HashUtils.sha256Hex(outputFile)

        // ------------------------------------------------------------------------------ report
        val applied = log.filter { it.status == StepStatus.OK }.map { it.title }
        val skipped = log.filter { it.status == StepStatus.SKIP }.map { it.title }
        val failed = log.filter { it.status == StepStatus.FAIL }.map { it.title }

        println(
            """
            |
            |----------------- harness result -----------------
            | output              : ${outputFile.path}
            | bytes               : ${outputFile.length()}
            | sha256              : $sha256
            | work dir (kept)     : ${workDir.path}
            | native libs merged  : $mergedLibraries
            | resource files      : $mergedResources
            | smali patches       : ${smaliPatchesToApply.size} resolved over ${groupedPatches.size} DEX files
            | hermes patches      : $hermesWorkCount queued
            | resource table      : ${if (mergedResourceTable != null) "rebuilt, ${mergedResourceTable.size} bytes" else "kept as the base shipped it"}
            | manifest edits      : ${manifestEdits.removals.size} removals (${manifestEdits.deadPermissions.size} of them dead), ${manifestEdits.overrides.size} overrides
            | replacements        : ${replacements.keys.joinToString(", ")}
            | dropped             : ${droppedArtefacts.size} artifacts, ${droppedSplitMetadata.size} split-install metadata
            | analytics disabled  : ${manifestEdits.googleAnalytics.size} components
            | v1/v2/v3            : ${verification.v1SignatureValid}/${verification.v2SignatureValid}/${verification.v3SignatureValid}
            | zipalign            : ${verification.zipalignPassed} ${verification.directoryError ?: ""}
            | steps OK/SKIP/FAIL  : ${applied.size}/${skipped.size}/${failed.size}
            | failed steps        : ${failed.ifEmpty { listOf("none") }}
            | skipped steps       : ${skipped.ifEmpty { listOf("none") }}
            |--------------------------------------------------
            """.trimMargin()
        )

        // The artifact is what this run produces, so the claims about it are asserted rather
        // than printed.
        assertTrue("the output must be written", outputFile.length() > 0)
        assertNull(
            "the rebuilt archive must be readable: ${verification.directoryError}",
            verification.directoryError
        )
        assertEquals(
            "the rebuilt archive must pass the alignment check: ${verification.misalignedEntries}",
            true,
            verification.zipalignPassed
        )
        assertNotNull("the resource table must have been rebuilt", mergedResourceTable)
        // A patch step that failed means an edit did not land, which is exactly the kind of
        // difference this run exists to surface. The signature step is excluded because an
        // unsigned artifact is the ask, and the Hermes step because its own verdict—how many
        // JavaScript functions were skipped—is reported above rather than asserted away.
        val patchFailures = log.filter {
            it.status == StepStatus.FAIL &&
                it.title != "Checked the signatures on the finished APK" &&
                !it.title.startsWith("Neutralized ")
        }
        assertTrue("no patch step may fail, failures: $patchFailures", patchFailures.isEmpty())

        // ------------------------------------------------- against the recorded build
        // The three ways this artifact used to differ from the recorded build, read back off the
        // finished file rather than off the plan that produced it: a plan that asked for an edit
        // is not the same claim as an archive that carries it.
        val shippedEntries = ZipFile(outputFile).use { zip ->
            zip.entries().asSequence().map { it.name }.toSet()
        }

        // 1. What the run dropped is gone, whichever archive it came from. The crash reporter's
        //    arm64 shared objects are not in the base split—they arrive with the ABI split the
        //    merge pulls in—so a drop applied to the base alone leaves them in the output. That
        //    is the failure this assertion is here to catch, and it is why the check is against
        //    what was asked for rather than against the handful of names that used to be safe.
        val survivedTheDrop = droppedArtefacts.filter { it in shippedEntries }
        assertTrue(
            "the run asked for ${droppedArtefacts.size} entries to be dropped and the archive " +
                "still holds: $survivedTheDrop",
            survivedTheDrop.isEmpty()
        )
        // The source stamp is not in the drop list any caller supplies: the repack leaves it out
        // because the recorded build does, and a rebuilt APK has no provenance to stamp.
        assertTrue(
            "a repacked APK must carry no source stamp",
            "stamp-cert-sha256" !in shippedEntries
        )

        // 2. The split-install metadata is gone, and the rebuilt table stopped naming it. Both
        //    halves are asserted, because either one alone is the broken half of this removal: a
        //    file dropped while the table still resolved a path into it, or a row dropped while the
        //    file stayed behind.
        assertTrue(
            "the split-install metadata must not survive into the merged archive",
            SplitMerger.SPLIT_INSTALL_METADATA !in shippedEntries
        )
        val builtTable = requireNotNull(mergedResourceTable) {
            "the resource table was not rebuilt"
        }
        val namedPaths = requireNotNull(ResourceTableMerger.namedPaths(builtTable)) {
            "the rebuilt table does not read back"
        }
        assertTrue(
            "the rebuilt table must not name a file the merge dropped",
            namedPaths.none { it in droppedSplitMetadata }
        )

        // 3. The named-paths invariant, on the finished archive rather than on the plan that
        //    produced it: every file the rebuilt table names is a file the APK holds, and every
        //    resource file it holds is named. A path named but absent resolves to nothing; a file
        //    present but unnamed is one nothing can ask for. Both are what a half-done merge looks
        //    like, and both are zero here.
        val heldResources = shippedEntries.filter { it.startsWith(SplitMerger.RES_DIR) }.toSet()
        assertEquals(
            "the rebuilt table names files the APK does not hold",
            emptySet<String>(),
            namedPaths - heldResources
        )
        assertEquals(
            "the APK holds resource files the rebuilt table does not name",
            emptySet<String>(),
            heldResources - namedPaths
        )

        // 4. The dead declarations are gone from the manifest that was packaged. Asserted on the
        //    file the platform reads, because the plan asking for six removals is not the same
        //    thing as six removals having landed.
        val shippedManifest = requireNotNull(extractEntry(outputFile, MANIFEST_ENTRY)) {
            "the repacked archive has no $MANIFEST_ENTRY"
        }
        val packagedPermissions = BinaryXmlEditor.readElementAttributeValues(
            xml = shippedManifest,
            namePrefix = BinaryXmlEditor.ELEMENT_USES_PERMISSION,
            attributeId = BinaryXmlEditor.ATTR_NAME
        )
        val stillDeclared = DiscordPatches.DEAD_PERMISSIONS.filter { it in packagedPermissions }
        assertTrue(
            "the dead declarations must not survive into the packaged manifest, " +
                "still there: $stillDeclared",
            stillDeclared.isEmpty()
        )

        // 5. The Google Analytics components are still declared and switched off, which is the
        //    shape the recorded build has: disabling a component is not the same edit as removing
        //    one, so both halves of it are asserted—the three elements are in the packaged
        //    manifest, and the pass that produced it found all three and rewrote the flag.
        val shippedComponents = listOf(
            BinaryXmlEditor.ELEMENT_RECEIVER, BinaryXmlEditor.ELEMENT_SERVICE
        ).flatMap { element ->
            BinaryXmlEditor.readElementAttributeValues(
                xml = shippedManifest,
                namePrefix = element,
                attributeId = BinaryXmlEditor.ATTR_NAME
            )
        }
        val analyticsNames = DiscordPatches.GOOGLE_ANALYTICS_COMPONENTS.map { it.name }
        assertEquals(
            "the analytics components must still be declared: $shippedComponents",
            analyticsNames,
            shippedComponents.filter { it in analyticsNames }
        )
        val edit = requireNotNull(manifestEdit) { "the manifest pass did not run" }
        val analyticsApplied = analyticsNames.filter { it in edit.elementOverridesApplied }
        assertEquals(
            "every analytics override must have found its element",
            analyticsNames,
            analyticsApplied
        )
        assertTrue(
            "the components must have been switched off, not left as they were",
            BinaryXmlEditor.ATTR_ENABLED in edit.attributesRewritten
        )

        // 6. The resource table the archive carries names the package the manifest declares. A
        //    clone whose table says one name and whose getPackageName() says another answers 0 to
        //    every name-based resource lookup, which is a fact about the shipped file rather than
        //    about the merge, so it is asserted here off the archive. Without a clone rename the
        //    two are the original name, which is the same invariant.
        val shippedTable = requireNotNull(extractEntry(outputFile, RESOURCE_TABLE_ENTRY)) {
            "the repacked archive has no $RESOURCE_TABLE_ENTRY"
        }
        assertEquals(
            "the shipped resource table must name the package the manifest declares",
            customPackageName ?: ORIGINAL_PACKAGE,
            requireNotNull(ResourceTableMerger.packageName(shippedTable)) {
                "the shipped resource table's package name does not read back"
            }
        )
    }

    /**
     * The one place this test can drift from what the app does: the two tables it copies out of
     * `sources.json`. They are checked against the file itself rather than trusted.
     */
    private fun assertSourceTablesStillMatchSourcesJson() {
        val json = sourceFile("sources.json")
        assumeTrue("${json.path} is not on this machine", json.isFile)
        val text = json.readText()
        for (id in SOURCE_PATCH_IDS) {
            assertTrue(
                "patch id '$id' is not in ${json.path} any more—the harness is out of date",
                text.contains("\"$id\"")
            )
        }
        for (split in SOURCE_SPLITS) {
            val name = split.removeSuffix(".apk")
            assertTrue(
                "split '$name' is not in ${json.path} any more—the harness is out of date",
                text.contains(name)
            )
        }
    }

    // ---------------------------------------------------------------------------- the pipeline's
    // ---------------------------------------------------------------------------- own helpers,
    // ---------------------------------------------------------------------------- copied verbatim

    /**
     * `PatchingPipeline.mergeResourceTables` (lines 1105-1150), copied: the structural checks in
     * [ResourceTableMerger] are the table's own, and the archive-level checks—the files the
     * rebuilt table names are exactly the resource files the repack is about to write—live in the
     * pipeline, not in the merger.
     */
    private fun mergeResourceTables(
        baseTable: ByteArray,
        splitTables: List<ByteArray>,
        mergedEntryNames: Set<String>,
        droppablePaths: Set<String>
    ): ResourceTableMerger.Result {
        val result = ResourceTableMerger.merge(baseTable, splitTables, droppablePaths)
        if (result !is ResourceTableMerger.Result.Merged) return result

        val basePaths = ResourceTableMerger.namedPaths(baseTable)
            ?: return ResourceTableMerger.Result.Refused(
                "the base's own resource table could not be read back for comparison"
            )
        val named = ResourceTableMerger.namedPaths(result.table)
            ?: return ResourceTableMerger.Result.Refused(
                "the merged resource table could not be read back for comparison"
            )
        val held = (basePaths - result.droppedPaths) +
            mergedEntryNames.filter { it.startsWith(SplitMerger.RES_DIR) }
        val unheld = named - held
        if (unheld.isNotEmpty()) {
            return ResourceTableMerger.Result.Refused(
                "the merged table names ${unheld.size} files this APK would not hold, " +
                    "starting with ${unheld.first()}"
            )
        }
        val unnamed = held - named
        if (unnamed.isNotEmpty()) {
            return ResourceTableMerger.Result.Refused(
                "this APK would hold ${unnamed.size} resource files the merged table " +
                    "does not name, starting with ${unnamed.first()}"
            )
        }
        return result
    }

    /** `PatchingPipeline.extractDexEntries`. */
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

    /** `PatchingPipeline.extractEntry`. */
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
}
