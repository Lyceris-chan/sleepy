package dev.sleepy.app.engine

import com.android.tools.smali.baksmali.Baksmali
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import dev.sleepy.app.model.SmaliPatch
import dev.sleepy.app.model.StepResult
import dev.sleepy.app.model.StepStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object DexProcessor {

    /**
     * Surgically patches a DEX file by disassembling and reassembling ONLY the classes
     * being modified, leaving all other classes untouched in binary form.
     *
     * This avoids OutOfMemory errors and takes seconds instead of minutes.
     */
    suspend fun patchDexSurgically(
        dexBytes: ByteArray,
        patches: List<SmaliPatch>,
        apiLevel: Int = 28
    ): Pair<ByteArray, List<StepResult>> = withContext(Dispatchers.IO) {
        if (patches.isEmpty()) return@withContext dexBytes to emptyList()

        val results = mutableListOf<StepResult>()
        val tempInDex = File.createTempFile("sleepy_in_", ".dex").apply {
            writeBytes(dexBytes)
            deleteOnExit()
        }
        val smaliDir = File.createTempFile("sleepy_smali_", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        val tempSingleDex = File.createTempFile("sleepy_single_", ".dex").apply {
            deleteOnExit()
        }
        val tempOutDex = File.createTempFile("sleepy_out_", ".dex").apply {
            deleteOnExit()
        }

        try {
            val opcodes = Opcodes.forApi(apiLevel)
            val originalDex = DexFileFactory.loadDexFile(tempInDex, opcodes)

            // Convert smali relative paths to DEX type descriptors:
            // "org/telegram/ui/o.smali" -> "Lorg/telegram/ui/o;"
            val targetDescriptors = patches.map { patch ->
                val clean = patch.smaliPath.removeSuffix(".smali")
                "L$clean;"
            }.distinct()

            // 1. Disassemble ONLY targeted classes
            val baksmaliOpts = BaksmaliOptions().apply {
                this.apiLevel = apiLevel
            }

            val disassembled = Baksmali.disassembleDexFile(
                originalDex,
                smaliDir,
                1,
                baksmaliOpts,
                targetDescriptors
            )

            if (!disassembled) {
                patches.forEach {
                    results.add(StepResult(it.smaliPath, StepStatus.FAIL, "Class disassembly failed"))
                }
                return@withContext dexBytes to results
            }

            // 2. Apply smali patches to the generated files
            val smaliFilesMap = mutableMapOf<String, String>()
            patches.forEach { patch ->
                val file = File(smaliDir, patch.smaliPath)
                if (file.exists()) {
                    smaliFilesMap[patch.smaliPath] = file.readText(Charsets.UTF_8)
                    val res = SmaliPatcher.apply(smaliFilesMap, patch)
                    results.add(res)
                    if (res.status == StepStatus.OK) {
                        file.writeText(smaliFilesMap[patch.smaliPath]!!, Charsets.UTF_8)
                    }
                } else {
                    results.add(StepResult(patch.smaliPath, StepStatus.SKIP, "Class not present in this DEX"))
                }
            }

            // Check if any patches actually modified smali
            val successfulPatches = results.filter { it.status == StepStatus.OK }
            if (successfulPatches.isEmpty()) {
                return@withContext dexBytes to results
            }

            // 3. Assemble ONLY the modified classes into a temporary single/multi-class DEX
            val smaliOpts = SmaliOptions().apply {
                this.apiLevel = apiLevel
                outputDexFile = tempSingleDex.absolutePath
                jobs = 1
            }

            val assembled = Smali.assemble(smaliOpts, listOf(smaliDir.absolutePath))
            if (!assembled || !tempSingleDex.exists() || tempSingleDex.length() == 0L) {
                results.add(StepResult("Smali reassembly", StepStatus.FAIL, "Failed to compile patched classes"))
                return@withContext dexBytes to results
            }

            // 4. Load the replacement classes
            val replacementDex = DexFileFactory.loadDexFile(tempSingleDex, opcodes)
            val replacementMap = replacementDex.classes.associateBy { it.type }

            // 5. Splice replacement classes into original classes collection
            val updatedClasses = originalDex.classes.map { originalClass ->
                replacementMap[originalClass.type] ?: originalClass
            }.toSet()

            val finalDex = object : DexFile {
                override fun getClasses(): Set<ClassDef> = updatedClasses
                override fun getOpcodes(): Opcodes = opcodes
            }

            // 6. Write final DEX using DexPool
            DexPool.writeTo(tempOutDex.absolutePath, finalDex)
            val outputBytes = tempOutDex.readBytes()

            outputBytes to results
        } finally {
            tempInDex.delete()
            smaliDir.deleteRecursively()
            tempSingleDex.delete()
            tempOutDex.delete()
        }
    }
}
