package dev.sleepy.app

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import dev.sleepy.app.engine.BinaryXmlModifier
import dev.sleepy.app.engine.DexProcessor
import dev.sleepy.app.engine.HermesPatcher
import dev.sleepy.app.model.SmaliPatch
import dev.sleepy.app.model.StepStatus
import dev.sleepy.app.patches.OctoGramPatches
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

class PatcherPipelineTest {

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

        // 3. Filter and resolve all 1-to-1 OctoGram patches for this APK
        val isOctoGram361 = classToDex.containsKey("Lorg/telegram/ui/e6;")
        val patchesToApply = mutableListOf<SmaliPatch>()
        for (patchSet in OctoGramPatches.ALL) {
            val matching = patchSet.smaliPatches.filter { patch ->
                if (patch.versionTag == "3.6.1" && !isOctoGram361) return@filter false
                if (patch.versionTag == "3.6.0" && isOctoGram361) return@filter false
                val desc = "L" + patch.smaliPath.removeSuffix(".smali") + ";"
                val actualDex = patch.dexName ?: classToDex[desc]
                actualDex != null && dexEntries.containsKey(actualDex)
            }
            assertTrue("PatchSet '${patchSet.label}' must have applicable patches in 3.6.1 APK", matching.isNotEmpty())
            matching.forEach { patch ->
                val desc = "L" + patch.smaliPath.removeSuffix(".smali") + ";"
                val actualDex = patch.dexName ?: classToDex[desc]!!
                patchesToApply.add(patch.copy(dexName = actualDex))
            }
        }

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
        assertTrue("classes3.dex must have patches", dex3Patches.isNotEmpty())
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

        val patchesToApply = mutableListOf<SmaliPatch>()
        for (patchSet in OctoGramPatches.ALL) {
            val matching = patchSet.smaliPatches.filter { patch ->
                if (patch.versionTag == "3.6.1" && !isOctoGram361) return@filter false
                if (patch.versionTag == "3.6.0" && isOctoGram361) return@filter false
                val desc = "L" + patch.smaliPath.removeSuffix(".smali") + ";"
                val actualDex = patch.dexName ?: classToDex[desc]
                actualDex != null && dexEntries.containsKey(actualDex)
            }
            matching.forEach { patch ->
                val desc = "L" + patch.smaliPath.removeSuffix(".smali") + ";"
                val actualDex = patch.dexName ?: classToDex[desc]!!
                patchesToApply.add(patch.copy(dexName = actualDex))
            }
        }

        val dex4Patches = patchesToApply.filter { it.dexName == "classes4.dex" }
        assertEquals(2, dex4Patches.size)
        val (patched4, res4) = DexProcessor.patchDexSurgically(dexEntries["classes4.dex"]!!, dex4Patches)
        assertTrue(patched4.isNotEmpty())
        res4.forEach { assertEquals(StepStatus.OK, it.status) }
        println("3.6.0 classes4.dex: all patches OK!")
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
}
