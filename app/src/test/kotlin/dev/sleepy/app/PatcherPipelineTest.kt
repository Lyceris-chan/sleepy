package dev.sleepy.app

import com.android.tools.smali.baksmali.Baksmali
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import dev.sleepy.app.engine.SmaliPatcher
import dev.sleepy.app.patches.OctoGramPatches
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class PatcherPipelineTest {

    @Test
    fun testSurgicalDexClassReplacement() = runBlocking {
        val apkFile = File("/home/sleepy/Documents/antigravity/telegram/OctoGram_arm64.apk")
        if (!apkFile.exists()) {
            println("APK not found, skipping test")
            return@runBlocking
        }

        println("Extracting classes4.dex from OctoGram_arm64.apk...")
        val zip = ZipFile(apkFile)
        val entry = zip.getEntry("classes4.dex")
        assertNotNull("classes4.dex should exist in APK", entry)
        val dexBytes = zip.getInputStream(entry).readBytes()
        zip.close()

        val tempDex = File.createTempFile("test_in_", ".dex").apply { writeBytes(dexBytes) }
        val opcodes = Opcodes.forApi(35)
        val originalDex = DexFileFactory.loadDexFile(tempDex, opcodes)
        println("Loaded DEX with ${originalDex.classes.size} classes")

        // 1. Target class type in DEX descriptor format: Lorg/telegram/ui/o;
        val targetClassType = "Lorg/telegram/ui/o;"
        val smaliRelPath = "org/telegram/ui/o.smali"

        // 2. Disassemble ONLY the target class
        val smaliDir = File.createTempFile("test_smali_", "").apply { delete(); mkdirs() }
        val baksmaliOpts = BaksmaliOptions().apply { apiLevel = 35 }
        val disassembled = Baksmali.disassembleDexFile(
            originalDex,
            smaliDir,
            1,
            baksmaliOpts,
            listOf(targetClassType)
        )
        assertTrue("Disassembly of target class must succeed", disassembled)

        val targetFile = File(smaliDir, smaliRelPath)
        assertTrue("Target smali file must exist: ${targetFile.absolutePath}", targetFile.exists())
        println("Target smali size: ${targetFile.length()} bytes (disassembled ONLY this class!)")

        // 3. Patch smali text
        val map = mutableMapOf(smaliRelPath to targetFile.readText())
        val patch = OctoGramPatches.SPONSORED_MSGS.smaliPatches.first { it.smaliPath == smaliRelPath }
        val patchResult = SmaliPatcher.apply(map, patch)
        println("Patch applied: ${patchResult.status}")
        assertTrue("Patch must succeed", patchResult.status == dev.sleepy.app.model.StepStatus.OK)
        targetFile.writeText(map[smaliRelPath]!!)

        // 4. Assemble ONLY this single class into a tiny 1-class DEX
        val singleClassDex = File.createTempFile("test_single_", ".dex")
        val smaliOpts = SmaliOptions().apply {
            apiLevel = 28
            outputDexFile = singleClassDex.absolutePath
            jobs = 1
        }
        val assembleSuccess = Smali.assemble(smaliOpts, listOf(smaliDir.absolutePath))
        assertTrue("Smali assemble of single class must succeed", assembleSuccess)
        println("Single-class DEX assembled: ${singleClassDex.length()} bytes")

        // 5. Load replacement class
        val replacementDex = DexFileFactory.loadDexFile(singleClassDex, opcodes)
        val replacementClassDef = replacementDex.classes.first { it.type == targetClassType }
        assertNotNull("Replacement class found", replacementClassDef)

        // 6. Splice the replacement ClassDef into the original DexFile
        val modifiedClasses = originalDex.classes.map { cls ->
            if (cls.type == targetClassType) replacementClassDef else cls
        }.toSet()

        val finalDexFile = object : DexFile {
            override fun getClasses(): Set<ClassDef> = modifiedClasses
            override fun getOpcodes(): Opcodes = opcodes
        }

        // 7. Write output DEX using DexPool
        val outDex = File.createTempFile("test_final_", ".dex")
        println("Writing final modified DEX to ${outDex.absolutePath} using DexPool...")
        DexPool.writeTo(outDex.absolutePath, finalDexFile)
        println("SUCCESS! Output DEX size: ${outDex.length()} bytes")
        assertTrue("Output DEX exists and has content", outDex.length() > 0)

        // Clean up
        tempDex.delete()
        singleClassDex.delete()
        outDex.delete()
        smaliDir.deleteRecursively()
    }

    @Test
    fun testBinaryXmlPackageRename() {
        val apkFile = File("/home/sleepy/Documents/antigravity/telegram/OctoGram_arm64.apk")
        if (!apkFile.exists()) return

        val zip = ZipFile(apkFile)
        val entry = zip.getEntry("AndroidManifest.xml")
        assertNotNull(entry)
        val origBytes = zip.getInputStream(entry).readBytes()
        zip.close()

        val modifiedBytes = dev.sleepy.app.engine.BinaryXmlModifier.modifyPackageName(
            manifestBytes = origBytes,
            oldPackageName = "it.octogram.android",
            newPackageName = "it.octogram.android.sleepy"
        )

        val targetUtf16 = "it.octogram.android.sleepy".toByteArray(Charsets.UTF_16LE)
        val found = (0 until modifiedBytes.size - targetUtf16.size).any { i ->
            targetUtf16.indices.all { j -> modifiedBytes[i + j] == targetUtf16[j] }
        }
        assertTrue("Modified manifest must contain new package name in UTF-16", found)
        println("Binary XML Package renaming verified successfully! Modified size: ${modifiedBytes.size} bytes")
    }
}
