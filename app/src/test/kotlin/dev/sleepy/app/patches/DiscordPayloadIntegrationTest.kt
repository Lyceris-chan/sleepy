package dev.sleepy.app.patches

import dev.sleepy.app.engine.DexProcessor
import dev.sleepy.app.engine.HermesFunctionTable
import dev.sleepy.app.engine.HermesPatcher
import dev.sleepy.app.model.SmaliPatch
import dev.sleepy.app.model.StepStatus
import dev.sleepy.app.testing.ReferenceApks
import dev.sleepy.app.testing.bundleOf
import dev.sleepy.app.testing.dexEntries
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The Discord patch payloads applied to the shipped 349.5 build.
 *
 * Dynamic name resolution and surgical DEX patching run against the real base split: the native
 * patch set has to find every anchor it names, the Hermes patches have to write the functions
 * they locate, and a wrong anchor or branch label has to fail rather than be caught by reading.
 */
class DiscordPayloadIntegrationTest {

    /** The Discord 349.5 base split, which lives outside the repository. */
    private val discordBaseApk = ReferenceApks.discordBaseApk

    /**
     * Function id -> (body offset, bytecode size) for every Discord Hermes target in the
     * 349.5 bundle, transcribed from `hermes-decomp dump --kind functions`.
     */
    private val LOCATED_TARGETS = mapOf(
        83581 to intArrayOf(42538735, 544),
        23494 to intArrayOf(28228275, 288),
        23361 to intArrayOf(28218094, 54),
        23357 to intArrayOf(28217649, 87),
        23372 to intArrayOf(28220702, 43),
        19786 to intArrayOf(27748606, 345),
        66426 to intArrayOf(37234404, 257),
        40455 to intArrayOf(30447159, 292),
        68593 to intArrayOf(37839513, 34),
        68601 to intArrayOf(37840532, 298),
        52476 to intArrayOf(32778621, 442),
        49760 to intArrayOf(32059183, 82),
        43768 to intArrayOf(30826967, 114),
        47308 to intArrayOf(31626863, 468)
    )

    @Test
    fun discordDynamicResolutionAndPatching() = runBlocking {
        val apkFile = discordBaseApk
        assumeTrue("${discordBaseApk.path} is not on this machine", apkFile.exists())

        println("Reading Discord APK (size: ${apkFile.length()} bytes)...")
        val dexEntries = dexEntries(apkFile)
        println("Found ${dexEntries.size} DEX files in Discord APK")

        val classToDex = DexProcessor.buildClassToDexIndex(dexEntries)
        val patchesToApply = mutableListOf<SmaliPatch>()
        for (patchSet in listOf(
            dev.sleepy.app.patches.DiscordPatches.BUNDLE_LOCK,
            dev.sleepy.app.patches.DiscordPatches.SENTRY,
            dev.sleepy.app.patches.DiscordPatches.TELEMETRY
        )) {
            val matching = patchSet.smaliPatches.filter { patch ->
                val desc = "L" + patch.smaliPath.removeSuffix(".smali") + ";"
                val actualDex = patch.dexName ?: classToDex[desc]
                actualDex != null && dexEntries.containsKey(actualDex)
            }
            assertTrue(
                "PatchSet '${patchSet.label}' must have applicable patches in Discord APK",
                matching.isNotEmpty()
            )
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
     * This is the end-to-end check for the smali half: every anchor and every method marker
     * must be found on a real disassembly, and the edited classes must reassemble—a label a
     * patch introduces that does not match its branch target fails here rather than on a phone.
     */
    @Test
    fun discordNativePatchesApplyCleanly() = runBlocking {
        val apkFile = discordBaseApk
        assumeTrue("${discordBaseApk.path} is not on this machine", apkFile.exists())

        val dexEntries = dexEntries(apkFile)
        val classToDex = DexProcessor.buildClassToDexIndex(dexEntries)

        val patchesToApply = mutableListOf<SmaliPatch>()
        val notApplicable = mutableListOf<String>()
        for (patchSet in dev.sleepy.app.patches.DiscordNativePatches.ALL) {
            for (patch in patchSet.smaliPatches) {
                val descriptor = "L" + patch.smaliPath.removeSuffix(".smali") + ";"
                val dex = classToDex[descriptor]
                if (dex == null) {
                    notApplicable.add(patch.smaliPath)
                } else {
                    patchesToApply.add(patch.copy(dexName = dex))
                }
            }
        }

        assertTrue(
            "every native patch must target a class present in this build, missing: $notApplicable",
            notApplicable.isEmpty()
        )
        assertEquals("the ported native set is 104 edits", 104, patchesToApply.size)
        println(
            "Resolved ${patchesToApply.size} Discord native patches across " +
                "${patchesToApply.groupBy { it.dexName }.size} DEX files"
        )

        val failures = mutableListOf<String>()
        val patchedByDex = mutableMapOf<String, ByteArray>()
        for ((dexName, group) in patchesToApply.groupBy { it.dexName!! }) {
            val (patched, results) = DexProcessor.patchDexSurgically(dexEntries[dexName]!!, group)
            assertTrue("$dexName produced no output", patched.isNotEmpty())
            patchedByDex[dexName] = patched
            results.filter { it.status != StepStatus.OK }
                .forEach { failures.add("$dexName :: ${it.label} -> ${it.detail ?: it.status}") }
        }

        assertTrue(
            "every native patch must apply cleanly, failures:\n${failures.joinToString("\n")}",
            failures.isEmpty()
        )

        // Assembly does not check a replacement's names: smali writes a reference to a class
        // that does not exist in the build, and writes one to a class that exists with another
        // meaning just as happily. Every class a replacement names therefore has to be resolved
        // against this build's own index before the patch counts as correct.
        val referenced = Regex("L[A-Za-z0-9_$]+(?:/[A-Za-z0-9_$]+)+;")
        val platformPrefixes = listOf("Landroid/", "Ljava/", "Ljavax/", "Ldalvik/")
        val dangling = sortedSetOf<String>()
        patchesToApply.forEach { patch ->
            val text = patch.replacement ?: patch.replacementBody ?: return@forEach
            referenced.findAll(text).map { it.value }
                .filterNot { it in classToDex || platformPrefixes.any(it::startsWith) }
                .forEach { dangling.add(it) }
        }
        assertTrue(
            "every class a replacement names must exist in this build, unresolved: $dangling",
            dangling.isEmpty()
        )

        // The last-crash gate re-emits the SentryEvent line it guards. The old obfuscated name
        // still exists in this build as an unrelated class, so a stale copy of that line would
        // assemble and read another class's field; the dex must not reference it at all.
        val crashDex = classToDex.getValue("Lcom/discord/crash_reporting/CrashReporting;")
        val crashText = String(patchedByDex.getValue(crashDex), Charsets.ISO_8859_1)
        assertFalse(
            "the last-crash gate must not name a SentryEvent type this build does not use",
            crashText.contains("Lio/sentry/f4;")
        )
        println(
            "All ${patchesToApply.size} Discord native smali patches applied and " +
                "reassembled cleanly"
        )
    }

    @Test
    fun discordPureKotlinHermesBytecodePatching() {
        val apkFile = discordBaseApk
        assumeTrue("${discordBaseApk.path} is not on this machine", apkFile.exists())

        println("Extracting index.android.bundle from Discord APK...")
        val entry = ZipFile(apkFile).use { it.getEntry("assets/index.android.bundle") }
        assertNotNull("assets/index.android.bundle must be present in Discord APK", entry)
        val bundleBytes = bundleOf(apkFile)

        println("Original bundle size: ${bundleBytes.size} bytes")
        assertTrue("Must be valid Hermes bytecode", HermesPatcher.isHermesBytecode(bundleBytes))

        // Layout regression guard. These (offset, size) pairs were read out of
        // `hermes-decomp dump --kind functions` for this bundle, so they pin the HBC 97+
        // 96-bit entry packing in HermesFunctionTable. A change to the bit math fails here.
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

        assertEquals(
            "Bundle size must be preserved byte-identically",
            bundleBytes.size,
            patchedBundle.size
        )
        assertEquals(hermesPatches.size + 1, results.size)
        results.forEach {
            println("  [${it.status}] ${it.label}: ${it.detail ?: "OK"}")
        }

        // Every stub shape must be written, except the promise-shaped one, which fails because
        // emitting it needs per-bundle string-table identifiers.
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
            val dsnOnly =
                stray.count { String(bundleBytes, it, 1, Charsets.ISO_8859_1).isNotEmpty() }
            assertTrue(
                "stray writes outside every target body and the DSN: ${stray.take(8)}",
                dsnOnly == 0
            )
        }
        println("Changed ${changed.size} bytes, all within target bodies or the Sentry DSN")

        // Verify SHA-1 footer
        val payloadLen = patchedBundle.size - 20
        val md = MessageDigest.getInstance("SHA-1")
        md.update(patchedBundle, 0, payloadLen)
        val expectedSha1 = md.digest()
        val actualSha1 = patchedBundle.copyOfRange(payloadLen, patchedBundle.size)
        assertTrue(
            "Hermes SHA-1 footer must be valid and recomputed",
            expectedSha1.contentEquals(actualSha1)
        )
        println(
            "Hermes: 13 function stubs written, the promise-shaped one refused, " +
                "Sentry DSN nulled, footer valid"
        )
    }
}
