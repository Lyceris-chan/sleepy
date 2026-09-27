package dev.sleepy.app

import dev.sleepy.app.engine.BinaryXmlModifier
import dev.sleepy.app.engine.DexProcessor
import dev.sleepy.app.engine.HermesFunctionTable
import dev.sleepy.app.engine.HermesPatcher
import dev.sleepy.app.model.PatchSelection
import dev.sleepy.app.model.SelectivePatchGenerator
import dev.sleepy.app.model.SmaliPatch
import dev.sleepy.app.model.StepStatus
import dev.sleepy.app.model.TargetApk
import dev.sleepy.app.patches.OctoGramPatches
import dev.sleepy.app.patches.PatchItemCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

class PatcherPipelineTest {

    /**
     * Function id -> (body offset, bytecode size) for every Discord Hermes target in the
     * 348.5 bundle, transcribed from `hermes-decomp dump --kind functions`.
     */
    private val LOCATED_TARGETS = mapOf(
        73760 to intArrayOf(35768086, 539),
        23080 to intArrayOf(27204824, 286),
        22947 to intArrayOf(27194629, 54),
        22943 to intArrayOf(27194185, 87),
        22958 to intArrayOf(27197236, 43),
        19432 to intArrayOf(26731813, 345),
        60648 to intArrayOf(33057180, 257),
        39965 to intArrayOf(29220581, 290),
        62294 to intArrayOf(33370482, 34),
        62298 to intArrayOf(33370528, 387),
        49956 to intArrayOf(30716240, 221),
        47719 to intArrayOf(30309141, 82),
        42692 to intArrayOf(29519384, 114),
        45655 to intArrayOf(30060272, 224)
    )

    @Test
    fun testOctoGram361DynamicResolutionAndSurgicalPatching() = runBlocking {
        val apkFile = File("/home/sleepy/Documents/antigravity/telegram/OctoGram_361_arm64.apk")
        if (!apkFile.exists()) {
            println("OctoGram_361_arm64.apk not found, skipping test")
            return@runBlocking
        }

        println("Reading OctoGram 3.6.1 APK (size: ${apkFile.length()} bytes)...")
        val apkBytes = apkFile.readBytes()

        // 1. Extract DEX entries
        val dexEntries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(apkBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name.matches(Regex("classes\\d*\\.dex"))) {
                    dexEntries[entry.name] = zis.readBytes()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        assertEquals(4, dexEntries.size)

        // 2. Build class-to-DEX index
        val classToDex = DexProcessor.buildClassToDexIndex(dexEntries)
        assertEquals("classes.dex", classToDex["Lcom/google/firebase/abt/component/AbtRegistrar;"])
        assertEquals("classes3.dex", classToDex["Lorg/telegram/ui/e6;"])
        assertEquals("classes3.dex", classToDex["La28;"])
        assertEquals("classes3.dex", classToDex["Lgpd;"])
        assertEquals("classes3.dex", classToDex["Ls04;"])
        assertEquals("classes3.dex", classToDex["Lj6d;"])
        assertEquals("classes3.dex", classToDex["Lorg/telegram/ui/ActionBar/ActionBarLayout;"])
        assertEquals("classes3.dex", classToDex["Lt6;"])
        assertEquals("classes3.dex", classToDex["Lcn8;"])

        println("Class-to-DEX index verified successfully across 4 DEX files!")

        // 3. Filter and resolve every OctoGram edit for this APK, through the path the pipeline
        // takes: a selection naming every item of every set, handed to each set's own generator.
        // A set resolves its own patches from the selection, so this is also what proves the item
        // table and the edit groups line up for a build this app offers.
        val isOctoGram361 = classToDex.containsKey("Lorg/telegram/ui/e6;")
        val selection = PatchSelection.fromSavedIds(OctoGramPatches.ALL.map { it.id }, PatchItemCatalog)
        val target = TargetApk(classToDex, dexEntries)
        val patchesToApply = mutableListOf<SmaliPatch>()
        for (patchSet in OctoGramPatches.ALL) {
            val generated = (patchSet.generator as SelectivePatchGenerator).generate(target, selection)
            assertNull("${patchSet.id} has nothing to apply", generated.skipReason)
            val matching = generated.patches.filter { patch ->
                if (patch.versionTag == "3.6.1" && !isOctoGram361) return@filter false
                if (patch.versionTag == "3.6.0" && isOctoGram361) return@filter false
                val desc = "L" + patch.smaliPath.removeSuffix(".smali") + ";"
                val actualDex = patch.dexName ?: classToDex[desc]
                actualDex != null && dexEntries.containsKey(actualDex)
            }
            assertEquals(
                "every edit of ${patchSet.id} must resolve on this build",
                generated.patches.size,
                matching.size
            )
            matching.forEach { patch ->
                val desc = "L" + patch.smaliPath.removeSuffix(".smali") + ";"
                val actualDex = patch.dexName ?: classToDex[desc]!!
                patchesToApply.add(patch.copy(dexName = actualDex))
            }
        }
        assertEquals("the catalogue is thirty-six edits", 36, patchesToApply.size)

        println("Total 1-to-1 patch edits queued: ${patchesToApply.size}")

        // 4. Test surgical patching on classes.dex (Firebase registrars)
        val firebasePatches = patchesToApply.filter { it.dexName == "classes.dex" }
        assertEquals(4, firebasePatches.size)
        val (patchedClassesDex, firebaseResults) = DexProcessor.patchDexSurgically(
            dexBytes = dexEntries["classes.dex"]!!,
            patches = firebasePatches
        )
        assertTrue("classes.dex output size must be positive", patchedClassesDex.isNotEmpty())
        firebaseResults.forEach {
            assertEquals("Firebase patch must succeed: ${it.label}", StepStatus.OK, it.status)
        }
        println("classes.dex: all 4 Firebase registrars patched with status OK!")

        // 5. Test surgical patching on classes3.dex
        val dex3Patches = patchesToApply.filter { it.dexName == "classes3.dex" }
        assertEquals("thirty-two edits, the other four being the Firebase registrars", 32, dex3Patches.size)
        // The two the reference scripts this app had not yet transcribed carry, named so that a
        // patch that silently stopped resolving shows up as a missing class here.
        listOf("yb3.smali", "org/telegram/ui/ProfileActivity.smali").forEach { path ->
            assertTrue(
                "$path must be among the edits this build gets",
                dex3Patches.any { it.smaliPath == path }
            )
        }
        val (patchedClasses3Dex, dex3Results) = DexProcessor.patchDexSurgically(
            dexBytes = dexEntries["classes3.dex"]!!,
            patches = dex3Patches
        )
        assertTrue("classes3.dex output size must be positive", patchedClasses3Dex.isNotEmpty())
        dex3Results.forEach {
            assertEquals("classes3.dex patch must succeed: ${it.label}", StepStatus.OK, it.status)
        }
        println("classes3.dex: all ${dex3Results.size} patches applied with status OK!")
    }

    @Test
    fun testOctoGram360DynamicResolutionAndSurgicalPatching() = runBlocking {
        val apkFile = File("/home/sleepy/Documents/antigravity/telegram/OctoGram_arm64.apk")
        if (!apkFile.exists()) {
            println("OctoGram_arm64.apk not found, skipping 3.6.0 test")
            return@runBlocking
        }

        println("Reading OctoGram 3.6.0 APK (size: ${apkFile.length()} bytes)...")
        val apkBytes = apkFile.readBytes()

        // 1. Extract DEX entries
        val dexEntries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(apkBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name.matches(Regex("classes\\d*\\.dex"))) {
                    dexEntries[entry.name] = zis.readBytes()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        // 2. Build class-to-DEX index
        val classToDex = DexProcessor.buildClassToDexIndex(dexEntries)
        assertEquals("classes4.dex", classToDex["Lorg/telegram/ui/o;"])
        assertEquals("classes3.dex", classToDex["Lorg/telegram/messenger/m0;"])
        assertEquals("classes3.dex", classToDex["Ly5l;"])
        assertEquals("classes4.dex", classToDex["Lbe6;"])
        assertEquals("classes3.dex", classToDex["Lhxk;"])
        assertEquals("classes3.dex", classToDex["Lcom/google/firebase/abt/component/AbtRegistrar;"])

        println("3.6.0 Class-to-DEX index verified successfully!")

        val isOctoGram361 = classToDex.containsKey("Lorg/telegram/ui/e6;")
        assertEquals(false, isOctoGram361)

        // A build this old gets what is not pinned to a version, and nothing else: every tagged
        // entry here names 3.6.1, and the 3.6.0-only entries the reference scripts carry are not
        // ported at all, so no patch claims to run on a build it cannot find its classes in.
        // A generator with no selection to honour produces its whole set, which is what this asks
        // for from each set before dropping what the version filter does not allow.
        val target = TargetApk(classToDex, dexEntries)
        val patchesToApply = mutableListOf<SmaliPatch>()
        for (patchSet in OctoGramPatches.ALL) {
            val generated = requireNotNull(patchSet.generator) { "${patchSet.id} has no generator" }
                .generate(target)
            assertNull("${patchSet.id} has nothing to apply", generated.skipReason)
            val matching = generated.patches.filter { patch ->
                if (patch.versionTag != null) return@filter false
                val desc = "L" + patch.smaliPath.removeSuffix(".smali") + ";"
                val actualDex = patch.dexName ?: classToDex[desc]
                actualDex != null && dexEntries.containsKey(actualDex)
            }
            assertEquals(
                "only the one version-less registrar is left of ${patchSet.id} here",
                if (patchSet.id.startsWith("octogram_firebase")) 1 else 0,
                matching.size
            )
            matching.forEach { patch ->
                val desc = "L" + patch.smaliPath.removeSuffix(".smali") + ";"
                val actualDex = patch.dexName ?: classToDex[desc]!!
                patchesToApply.add(patch.copy(dexName = actualDex))
            }
        }
        assertEquals(
            "the four version-less Firebase registrars are the whole of what a 3.6.0 build gets",
            4,
            patchesToApply.size
        )
        val dexName = patchesToApply.first().dexName!!
        val patchedDexEntries = patchesToApply.filter { it.dexName == dexName }
        assertEquals("and they all live in the same DEX here", 4, patchedDexEntries.size)

        val (patchedDex, results) = DexProcessor.patchDexSurgically(dexEntries[dexName]!!, patchedDexEntries)
        assertTrue(patchedDex.isNotEmpty())
        results.forEach { assertEquals(StepStatus.OK, it.status) }
        println("3.6.0 $dexName: all ${results.size} version-less patches applied OK!")
    }

    @Test
    fun testBinaryXmlPackageRename() {
        val apkFile = File("/home/sleepy/Documents/antigravity/telegram/OctoGram_361_arm64.apk")
        if (!apkFile.exists()) return

        val zip = ZipFile(apkFile)
        val entry = zip.getEntry("AndroidManifest.xml")
        assertNotNull(entry)
        val origBytes = zip.getInputStream(entry).readBytes()
        zip.close()

        val modifiedBytes = BinaryXmlModifier.modifyPackageName(
            manifestBytes = origBytes,
            oldPackageName = "it.octogram.android",
            newPackageName = "it.octogram.android.sleepy"
        )

        val targetUtf8 = "it.octogram.android.sleepy".toByteArray(Charsets.UTF_8)
        val targetUtf16 = "it.octogram.android.sleepy".toByteArray(Charsets.UTF_16LE)
        val found = (0 until modifiedBytes.size - targetUtf8.size).any { i ->
            targetUtf8.indices.all { j -> modifiedBytes[i + j] == targetUtf8[j] }
        } || (0 until modifiedBytes.size - targetUtf16.size).any { i ->
            targetUtf16.indices.all { j -> modifiedBytes[i + j] == targetUtf16[j] }
        }

        assertTrue("Modified manifest must contain new package name", found)
        println("Binary XML Package renaming verified! Output size: ${modifiedBytes.size} bytes")
    }

    @Test
    fun testPureKotlinHermesSentryDsnNulling() {
        val dummyUrl = "https://abc123def456.ingest.sentry.io/api/12345/envelope/?sentry_version=7&sentry_key=0123456789abcdef0123456789abcdef&sentry_client=sentry.javascript.react-native"
        val prefix = "var __DEV__=false;DSN=\""
        val suffix = "\";run();"
        val fullContent = prefix + dummyUrl + suffix
        val contentBytes = fullContent.toByteArray(Charsets.ISO_8859_1)

        // Append 20 dummy bytes for Hermes SHA-1 footer
        val md = MessageDigest.getInstance("SHA-1")
        val initialFooter = md.digest(contentBytes)
        val bundleWithFooter = contentBytes + initialFooter

        val (patchedBundle, result) = HermesPatcher.nullifySentryDsn(bundleWithFooter)
        assertNotNull("Result should not be null", result)
        assertEquals(StepStatus.OK, result!!.status)
        assertEquals(bundleWithFooter.size, patchedBundle.size)

        val patchedString = String(patchedBundle, Charsets.ISO_8859_1)
        assertTrue("Sentry domain must be gone", !patchedString.contains("ingest.sentry.io"))
        assertTrue("Nulled DSN prefix must be present", patchedString.contains("https://0.0.0.0/000"))

        // Verify recomputed SHA-1 footer
        val payloadLength = patchedBundle.size - 20
        val expectedSha1 = MessageDigest.getInstance("SHA-1").digest(patchedBundle.copyOfRange(0, payloadLength))
        val actualSha1 = patchedBundle.copyOfRange(payloadLength, patchedBundle.size)
        assertTrue("Footer must match SHA-1 of payload", expectedSha1.contentEquals(actualSha1))
        println("Pure Kotlin Hermes Sentry DSN nulling & SHA-1 footer verification passed!")
    }

    @Test
    fun testDiscordDynamicResolutionAndPatching() = runBlocking {
        val apkFile = File("/home/sleepy/Documents/antigravity/quirky-noether/discord/build/alpha3482/apk/extracted/base.apk")
        if (!apkFile.exists()) {
            println("Discord base.apk not found, skipping test")
            return@runBlocking
        }

        println("Reading Discord APK (size: ${apkFile.length()} bytes)...")
        val apkBytes = apkFile.readBytes()

        val dexEntries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(apkBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name.matches(Regex("classes\\d*\\.dex"))) {
                    dexEntries[entry.name] = zis.readBytes()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        println("Found ${dexEntries.size} DEX files in Discord APK")

        val classToDex = DexProcessor.buildClassToDexIndex(dexEntries)
        val patchesToApply = mutableListOf<SmaliPatch>()
        for (patchSet in listOf(dev.sleepy.app.patches.DiscordPatches.BUNDLE_LOCK, dev.sleepy.app.patches.DiscordPatches.SENTRY, dev.sleepy.app.patches.DiscordPatches.TELEMETRY)) {
            val matching = patchSet.smaliPatches.filter { patch ->
                val desc = "L" + patch.smaliPath.removeSuffix(".smali") + ";"
                val actualDex = patch.dexName ?: classToDex[desc]
                actualDex != null && dexEntries.containsKey(actualDex)
            }
            assertTrue("PatchSet '${patchSet.label}' must have applicable patches in Discord APK", matching.isNotEmpty())
            matching.forEach { patch ->
                val desc = "L" + patch.smaliPath.removeSuffix(".smali") + ";"
                val actualDex = patch.dexName ?: classToDex[desc]!!
                patchesToApply.add(patch.copy(dexName = actualDex))
            }
        }

        println("Discord patches queued: ${patchesToApply.size}")
        assertEquals(11, patchesToApply.size) // 3 bundle + 4 sentry + 4 telemetry

        val grouped = patchesToApply.groupBy { it.dexName!! }
        for ((dexName, patchesForDex) in grouped) {
            println("Patching Discord $dexName (${patchesForDex.size} edits)...")
            val (patchedBytes, results) = DexProcessor.patchDexSurgically(
                dexBytes = dexEntries[dexName]!!,
                patches = patchesForDex
            )
            assertTrue("$dexName output must be valid", patchedBytes.isNotEmpty())
            results.forEach {
                println("  [${it.status}] ${it.label}: ${it.detail ?: "OK"}")
                assertEquals("Patch must succeed: ${it.label}", StepStatus.OK, it.status)
            }
        }
        println("All Discord Smali patches applied cleanly with status OK!")
    }

    /**
     * Applies the whole ported native patch set to the real Discord base split.
     *
     * This is the end-to-end check that matters for the smali half: every anchor and every
     * method marker must be found on a real disassembly, and the edited classes must
     * reassemble — a label a patch introduces that does not match its branch target fails
     * here rather than on a phone.
     */
    @Test
    fun testDiscordNativePatchesApplyCleanly() = runBlocking {
        val apkFile = File("/home/sleepy/Documents/antigravity/quirky-noether/discord/build/alpha3482/apk/extracted/base.apk")
        if (!apkFile.exists()) {
            println("Discord base.apk not found, skipping native patch test")
            return@runBlocking
        }

        val apkBytes = apkFile.readBytes()
        val dexEntries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(apkBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name.matches(Regex("classes\\d*\\.dex"))) {
                    dexEntries[entry.name] = zis.readBytes()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        val classToDex = DexProcessor.buildClassToDexIndex(dexEntries)

        val patchesToApply = mutableListOf<SmaliPatch>()
        val notApplicable = mutableListOf<String>()
        for (patchSet in dev.sleepy.app.patches.DiscordNativePatches.ALL) {
            for (patch in patchSet.smaliPatches) {
                val descriptor = "L" + patch.smaliPath.removeSuffix(".smali") + ";"
                val dex = classToDex[descriptor]
                if (dex == null) notApplicable.add(patch.smaliPath) else patchesToApply.add(patch.copy(dexName = dex))
            }
        }

        assertTrue(
            "every native patch must target a class present in this build, missing: $notApplicable",
            notApplicable.isEmpty()
        )
        assertEquals("the ported native set is 90 edits", 90, patchesToApply.size)
        println("Resolved ${patchesToApply.size} Discord native patches across ${patchesToApply.groupBy { it.dexName }.size} DEX files")

        val failures = mutableListOf<String>()
        for ((dexName, group) in patchesToApply.groupBy { it.dexName!! }) {
            val (patched, results) = DexProcessor.patchDexSurgically(dexEntries[dexName]!!, group)
            assertTrue("$dexName produced no output", patched.isNotEmpty())
            results.filter { it.status != StepStatus.OK }
                .forEach { failures.add("$dexName :: ${it.label} -> ${it.detail ?: it.status}") }
        }

        assertTrue("every native patch must apply cleanly, failures:\n${failures.joinToString("\n")}", failures.isEmpty())
        println("All ${patchesToApply.size} Discord native smali patches applied and reassembled cleanly")
    }

    @Test
    fun testDiscordPureKotlinHermesBytecodePatching() {
        val apkFile = File("/home/sleepy/Documents/antigravity/quirky-noether/discord/build/alpha3482/apk/extracted/base.apk")
        if (!apkFile.exists()) {
            println("Discord base.apk not found, skipping Hermes test")
            return
        }

        println("Extracting index.android.bundle from Discord APK...")
        val zip = ZipFile(apkFile)
        val entry = zip.getEntry("assets/index.android.bundle")
        assertNotNull("assets/index.android.bundle must be present in Discord APK", entry)
        val bundleBytes = zip.getInputStream(entry).readBytes()
        zip.close()

        println("Original bundle size: ${bundleBytes.size} bytes")
        assertTrue("Must be valid Hermes bytecode", HermesPatcher.isHermesBytecode(bundleBytes))

        // Layout regression guard. These (offset, size) pairs were read out of
        // `hermes-decomp dump --kind functions` for this bundle, so they pin the HBC 97+
        // 96-bit entry packing in HermesFunctionTable. Change the bit maths and this fails.
        dev.sleepy.app.patches.DiscordPatches.HERMES.hermesPatches.forEach { patch ->
            val fid = patch.functionId.toInt()
            val expected = LOCATED_TARGETS.getValue(fid)
            val located = HermesFunctionTable.locate(bundleBytes, fid)
                ?: throw AssertionError("fn $fid (${patch.functionName}) could not be located")
            assertEquals(
                "fn $fid (${patch.functionName}) located at the wrong place",
                expected.toList(),
                listOf(located.bodyOffset, located.bytecodeSize)
            )
        }
        println("All ${LOCATED_TARGETS.size} Hermes targets locate to their recorded offsets")

        val hermesPatches = dev.sleepy.app.patches.DiscordPatches.HERMES.hermesPatches
        println("Applying ${hermesPatches.size} 1-to-1 Hermes patches in pure Kotlin...")
        val (patchedBundle, results) = HermesPatcher.applyPatches(bundleBytes, hermesPatches)

        assertEquals("Bundle size must be preserved byte-identically", bundleBytes.size, patchedBundle.size)
        assertEquals(hermesPatches.size + 1, results.size)
        results.forEach {
            println("  [${it.status}] ${it.label}: ${it.detail ?: "OK"}")
        }

        // Every stub shape must actually be written, except the promise-shaped one, which is
        // refused because emitting it needs per-bundle string-table identifiers.
        val refused = results.filter { it.status == StepStatus.FAIL }
        assertEquals(
            "only the promise-shaped stub may be refused, got: ${refused.map { it.title }}",
            listOf("Silencing Central Analytics Event Emitter"),
            refused.map { it.title }
        )

        // Every changed byte must lie inside a target function's body or the Sentry DSN.
        // A mis-located write shows up here as a stray byte outside those ranges.
        val bodies = LOCATED_TARGETS.values.map { it[0] until (it[0] + it[1]) }
        val changed = bundleBytes.indices.filter { bundleBytes[it] != patchedBundle[it] }
        val stray = changed.filter { i -> bodies.none { i in it } }
        if (stray.size > 1024) {
            // Anything beyond the DSN substitution's own bytes must be inside a body.
            val dsnOnly = stray.count { String(bundleBytes, it, 1, Charsets.ISO_8859_1).isNotEmpty() }
            assertTrue("stray writes outside every target body and the DSN: ${stray.take(8)}", dsnOnly == 0)
        }
        println("Changed ${changed.size} bytes, all within target bodies or the Sentry DSN")

        // Verify SHA-1 footer
        val payloadLen = patchedBundle.size - 20
        val md = MessageDigest.getInstance("SHA-1")
        md.update(patchedBundle, 0, payloadLen)
        val expectedSha1 = md.digest()
        val actualSha1 = patchedBundle.copyOfRange(payloadLen, patchedBundle.size)
        assertTrue("Hermes SHA-1 footer must be valid and recomputed", expectedSha1.contentEquals(actualSha1))
        println("Hermes: 13 function stubs written, the promise-shaped one refused, Sentry DSN nulled, footer valid")
    }
}
