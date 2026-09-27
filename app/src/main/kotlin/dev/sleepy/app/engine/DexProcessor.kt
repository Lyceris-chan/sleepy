package dev.sleepy.app.engine

import com.android.tools.smali.baksmali.Baksmali
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.io.File

object DexProcessor {

    suspend fun disassemble(dexBytes: ByteArray, apiLevel: Int = 35): Map<String, String> =
        withContext(Dispatchers.IO) {
            val tempDex = File.createTempFile("sleepy_", ".dex").apply {
                writeBytes(dexBytes)
                deleteOnExit()
            }
            val outDir = File(tempDex.parentFile, "sleepy_smali_${System.nanoTime()}").apply {
                mkdirs()
                deleteOnExit()
            }

            try {
                val options = BaksmaliOptions().apply {
                    this.apiLevel = apiLevel
                }
                val dexFile = DexFileFactory.loadDexFile(tempDex, Opcodes.forApi(apiLevel))
                val availableThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)

                Baksmali.disassembleDexFile(
                    dexFile,
                    outDir,
                    availableThreads,
                    options
                )

                val map = mutableMapOf<String, String>()
                outDir.walkTopDown()
                    .filter { it.isFile && it.extension == "smali" }
                    .forEach { file ->
                        val relPath = outDir.toURI().relativize(file.toURI()).path
                        map[relPath] = file.readText(Charsets.UTF_8)
                    }
                map
            } finally {
                tempDex.delete()
                outDir.deleteRecursively()
            }
        }

    suspend fun assemble(smaliFiles: Map<String, String>, apiLevel: Int = 35): ByteArray =
        withContext(Dispatchers.IO) {
            val smaliDir = File.createTempFile("sleepy_asm_", "").apply {
                delete()
                mkdirs()
                deleteOnExit()
            }
            val outDex = File.createTempFile("sleepy_out_", ".dex").apply {
                deleteOnExit()
            }

            try {
                smaliFiles.forEach { (path, code) ->
                    val file = File(smaliDir, path)
                    file.parentFile?.mkdirs()
                    file.writeText(code, Charsets.UTF_8)
                }

                val options = SmaliOptions().apply {
                    this.apiLevel = apiLevel
                    outputDexFile = outDex.absolutePath
                    jobs = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
                }

                Smali.assemble(options, listOf(smaliDir.absolutePath))
                outDex.readBytes()
            } finally {
                smaliDir.deleteRecursively()
                outDex.delete()
            }
        }

    suspend fun disassembleAll(
        dexMap: Map<String, ByteArray>,
        apiLevel: Int = 35
    ): Map<String, Map<String, String>> = withContext(Dispatchers.IO) {
        dexMap.entries.map { (name, bytes) ->
            async {
                name to disassemble(bytes, apiLevel)
            }
        }.awaitAll().toMap()
    }

    suspend fun assembleAll(
        smaliMaps: Map<String, Map<String, String>>,
        apiLevel: Int = 35
    ): Map<String, ByteArray> = withContext(Dispatchers.IO) {
        smaliMaps.entries.map { (name, smaliFiles) ->
            async {
                name to assemble(smaliFiles, apiLevel)
            }
        }.awaitAll().toMap()
    }
}
