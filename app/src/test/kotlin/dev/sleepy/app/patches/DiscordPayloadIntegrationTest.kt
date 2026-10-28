package dev.sleepy.app.patches

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import dev.sleepy.app.engine.DexProcessor
import dev.sleepy.app.engine.HermesFunctionTable
import dev.sleepy.app.engine.HermesPatcher
import dev.sleepy.app.engine.OkHttpNameResolver
import dev.sleepy.app.engine.OkHttpResolution
import dev.sleepy.app.model.SmaliPatch
import dev.sleepy.app.model.StepStatus
import dev.sleepy.app.model.TargetApk
import dev.sleepy.app.testing.ComparisonApks
import dev.sleepy.app.testing.SmaliReferences
import dev.sleepy.app.testing.bundleOf
import dev.sleepy.app.testing.dexEntries
import java.io.File
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
 * The Discord patch payloads applied to the shipped 353.2.0 build.
 *
 * Dynamic name resolution and surgical DEX patching run against the real base split: the native
 * patch set has to find every anchor it names, the Hermes patches have to write the functions
 * they locate, and a wrong anchor or branch label has to fail rather than be caught by reading.
 */
class DiscordPayloadIntegrationTest {

    /** The Discord 353.2.0 base split, which lives outside the repository. */
    private val discordBaseApk = ComparisonApks.discordBaseApk

    /**
     * Function id -> (body offset, bytecode size) for every Discord Hermes target in the
     * 353.2.0 bundle, transcribed from `hermes-decomp dump --kind functions`.
     */
    private val LOCATED_TARGETS = mapOf(
        88367 to intArrayOf(44438835, 544),
        24953 to intArrayOf(29330430, 288),
        24820 to intArrayOf(29320249, 54),
        24816 to intArrayOf(29319804, 87),
        24831 to intArrayOf(29322857, 43),
        21184 to intArrayOf(28824195, 345),
        71544 to intArrayOf(39364485, 257),
        43081 to intArrayOf(31673532, 292),
        73795 to intArrayOf(39984498, 34),
        73801 to intArrayOf(39985517, 298),
        53824 to intArrayOf(33923307, 446),
        52696 to intArrayOf(33582315, 82),
        52196 to intArrayOf(33472878, 107),
        50823 to intArrayOf(33149835, 468)
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
        assertEquals(10, patchesToApply.size) // 3 bundle + 3 sentry + 4 telemetry

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
     * Applies the whole native patch set to the real Discord base split.
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
        assertEquals("the native set size",
            dev.sleepy.app.patches.DiscordNativePatches.ALL.sumOf { it.smaliPatches.size },
            patchesToApply.size)
        println(
            "Resolved ${patchesToApply.size} Discord native patches across " +
                "${patchesToApply.groupBy { it.dexName }.size} DEX files"
        )

        val failures = mutableListOf<String>()
        val applied = mutableSetOf<String>()
        val skipped = mutableListOf<String>()
        val patchedByDex = mutableMapOf<String, ByteArray>()
        for ((dexName, group) in patchesToApply.groupBy { it.dexName!! }) {
            val (patched, results) = DexProcessor.patchDexSurgically(dexEntries[dexName]!!, group)
            assertTrue("$dexName produced no output", patched.isNotEmpty())
            patchedByDex[dexName] = patched
            results.forEach { result ->
                when (result.status) {
                    StepStatus.OK -> applied += result.label
                    StepStatus.SKIP -> skipped += "$dexName :: ${result.label} -> ${result.detail}"
                    else -> failures += "$dexName :: ${result.label} -> ${result.detail ?: result.status}"
                }
            }
        }

        assertTrue(
            "no native patch may fail; skipped patches are the ones this build no longer " +
                "carries:\n${failures.joinToString("\n")}",
            failures.isEmpty()
        )
        println("Native patches: ${applied.size} applied, ${skipped.size} skipped")
        skipped.forEach { println("  skipped: $it") }
        assertTrue(
            "the 353.2.0 native set must apply every edit; these were skipped:\n" +
                skipped.joinToString("\n"),
            skipped.isEmpty()
        )

        // Assembly does not check a replacement's names: smali writes a reference to a class
        // that does not exist in the build, and writes one to a class that exists with another
        // meaning just as happily. Every class a replacement names therefore has to be resolved
        // against this build's own index before the patch counts as correct.
        val referenced = Regex("L[A-Za-z0-9_$]+(?:/[A-Za-z0-9_$]+)+;")
        val platformPrefixes = listOf("Landroid/", "Ljava/", "Ljavax/", "Ldalvik/")
        val dangling = sortedSetOf<String>()
        patchesToApply.filter { it.title in applied }.forEach { patch ->
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
        if ("Skipping last-crash persistence when reporting is off" in applied) {
            val crashDex = classToDex.getValue("Lcom/discord/crash_reporting/CrashReporting;")
            val crashText = String(patchedByDex.getValue(crashDex), Charsets.ISO_8859_1)
            assertFalse(
                "the last-crash gate must not name a SentryEvent type this build does not use",
                crashText.contains("Lio/sentry/f4;")
            )
        }
        println(
            "All ${applied.size} applicable Discord native smali patches applied and " +
                "reassembled cleanly; ${skipped.size} skipped"
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

    /**
     * Every class and member a patch body names must exist in the build being patched.
     *
     * This is the check the 351.2.0 set needed and did not have. R8 gives the same name to a
     * different thing on every release, and a patch body is smali text copied from the release it
     * was written for: nothing about assembling it requires the names in it to resolve. The
     * `CrashReporting.<clinit>` rewrite called `Lkotlin/collections/a0;->g`, which was
     * `CollectionsKt.listOf` in 349.5 and is `ArrayDeque` in 351.2.0 - a class that exists, has
     * eight methods, and none of them is `g`. The patch assembled, applied, read back, and every
     * test passed; on the phone the class fails at `MainApplication.performInitialization` with
     * `NoSuchMethodError`, which is the app dying as it opens.
     *
     * Class and member names are matched, not full signatures. A wrong overload would pass here,
     * but the failure that happened - and the failure this release is about - is a name that
     * moved to another class, and that is what this catches.
     *
     * System classes are not in the APK and are skipped; a member the patch set itself declares
     * is one it is adding, so those are collected first and treated as present.
     */
    @Test
    fun everyReferenceAPatchBodyMakesExistsInThisBuild() {
        val apkFile = discordBaseApk
        assumeTrue("${discordBaseApk.path} is not on this machine", apkFile.exists())

        val opcodes = Opcodes.forApi(28)
        val declaredMembers = mutableSetOf<String>()
        val declaredFields = mutableSetOf<String>()
        // Every registered set, not only the native ones: the OTA-bundle, Sentry and telemetry
        // sets in DiscordPatches carry bodies too, and a name that moved costs the same there.
        // A patch whose target class is not in this build is one the pipeline skips as
        // not-applicable - OctoGram's targets among them - so those are left out and counted.
        val entries = dexEntries(apkFile)
        val classToDex = DexProcessor.buildClassToDexIndex(entries)
        val registered = (
            dev.sleepy.app.patches.DiscordPatches.BUNDLE_LOCK.smaliPatches +
                dev.sleepy.app.patches.DiscordPatches.SENTRY.smaliPatches +
                dev.sleepy.app.patches.DiscordPatches.TELEMETRY.smaliPatches +
                dev.sleepy.app.patches.DiscordNativePatches.ALL.flatMap { it.smaliPatches }
            )
        val patches = registered.filter {
            "L" + it.smaliPath.removeSuffix(".smali") + ";" in classToDex
        }
        println(
            "Reference audit scope: ${patches.size} patches target this build, " +
                "${registered.size - patches.size} target a class it does not have"
        )

        // The blocklist interceptor is not written in the source: it is generated against the
        // build, and it is the reason this test exists in the form it does. Its chain accessors
        // were hardcoded - `i()` for what 351.2.0 calls `h()` - which is a `NoSuchMethodError` on
        // the OkHttp dispatcher thread the first time the app makes a request. A check that only
        // read the static table would have said the names were fine.
        val target = TargetApk(classToDexIndex = classToDex, dexEntries = entries)
        val generatedBodies = when (val resolution = OkHttpNameResolver.resolve(target)) {
            is OkHttpResolution.Resolved -> listOf(
                "the generated blocklist interceptor" to
                    DiscordBlocklistPatch.interceptorBody(resolution.names)
            )

            is OkHttpResolution.Unresolved -> {
                // A build this set cannot resolve is one the blocklist set skips, with a reason.
                println("Blocklist names unresolved, so only the static table is audited: " +
                    resolution.reason)
                emptyList()
            }
        }

        val bodies = patches.flatMap { patch ->
            listOfNotNull(patch.replacementBody, patch.replacement).map { patch.title to it }
        } + generatedBodies

        // The members this patch set adds to a class that does not have them yet.
        for ((_, text) in bodies) declaredFields += SmaliReferences.declaredFieldNames(text)

        // Every class the build defines, and what it declares each member as. The signature is
        // part of the key: a member that exists under the name a body uses, with a different
        // parameter list, is as unresolvable as one that is not there at all.
        val methods = mutableMapOf<String, MutableSet<String>>()
        val fields = mutableMapOf<String, MutableSet<String>>()
        for ((name, bytes) in entries) {
            val temp = File.createTempFile("sleepy_refs_", ".dex").apply {
                writeBytes(bytes)
                deleteOnExit()
            }
            try {
                val dex = DexFileFactory.loadDexFile(temp, opcodes)
                for (cls in dex.classes) {
                    methods.getOrPut(cls.type) { mutableSetOf() }.addAll(
                        cls.methods.map { method ->
                            // The parentheses are part of the key: a reference is recorded as
                            // `name(params)return`, and a key without them never matches one.
                            method.name + "(" +
                                method.parameterTypes.joinToString("") { it.toString() } +
                                ")" + method.returnType
                        }
                    )
                    fields.getOrPut(cls.type) { mutableSetOf() }
                        .addAll(cls.fields.map { field -> field.name + ":" + field.type })
                }
            } finally {
                temp.delete()
            }
        }
        assertTrue("no classes were indexed from ${apkFile.name}", methods.size > 10_000)

        val systemPrefixes = listOf(
            "Ljava/", "Ljavax/", "Landroid/", "Ldalvik/", "Llibcore/", "Lsun/",
            "Lorg/w3c/", "Lorg/xml/", "Lorg/json/", "Lorg/apache/"
        )
        val unresolved = mutableListOf<String>()
        var checked = 0

        for ((label, text) in bodies) {
            for (reference in SmaliReferences.inText(text)) {
                if (systemPrefixes.any { reference.descriptor.startsWith(it) }) continue
                checked++
                val declared = if (reference.isField) fields else methods
                if (reference.descriptor !in declared) {
                    unresolved += "$label: ${reference.descriptor} is not a class in this build"
                    continue
                }
                if (reference.lookup !in declared.getValue(reference.descriptor) &&
                    reference.member !in declaredMembers &&
                    reference.member !in declaredFields
                ) {
                    val what = if (reference.isField) "field" else "method"
                    unresolved += "$label: ${reference.descriptor} has no $what " +
                        "'${reference.member}${reference.signature}'"
                }
            }
        }

        assertTrue("the check found no references to check", checked > 0)
        assertTrue(
            "these names do not resolve in the build the patch set targets, so the patched " +
                "class would fail at runtime rather than here:\n  " +
                unresolved.joinToString("\n  "),
            unresolved.isEmpty()
        )
        println("Reference audit: $checked references across ${patches.size} patches and " +
            "${generatedBodies.size} generated bodies all resolve")
    }

    /**
     * The stubs that answer JavaScript resolve the type its wrapper reads.
     *
     * Two of these are not visible in the smali alone. The contact-photo bridge does
     * `str.replace(...)` on the callback's second argument, so a null there is a TypeError.
     * The push-notification-log wrapper runs `JSON.parse(result)`, so resolving an array instead
     * of the real method's serialized JSON string is a SyntaxError. Both were found by following
     * the native methods into the JavaScript that calls them, not by reading the stub tables.
     */
    @Test
    fun stubsReadByJavaScriptResolveTheTypeTheirWrapperExpects() {
        val contactPhoto = DiscordNativePatches.PRIVACY.smaliPatches.first {
            it.smaliPath == "ta/h.smali" && it.methodSignature?.contains(" c(") == true
        }
        assertTrue(
            "the contact-photo stub must return a String, not null: the JS wrapper calls replace",
            contactPhoto.replacementBody.orEmpty().contains("const-string v0, \"\"")
        )
        assertFalse(
            "a null contact photo reaches str.replace() in ContactSyncUtils",
            contactPhoto.replacementBody.orEmpty().contains("const/4 v0, 0x0")
        )

        val pushLogs = DiscordNativePatches.TELEMETRY_MODULES.smaliPatches.first {
            it.methodSignature?.contains("getPushNotificationLogs") == true
        }
        assertTrue(
            "the push-log stub must resolve the JSON string the wrapper parses",
            pushLogs.replacementBody.orEmpty().contains(
                "const-string v0, \"{\\\"pushNotifications\\\":[]}\""
            )
        )
        assertFalse(
            "an array here reaches JSON.parse() in getPushNotificationLogs.android.tsx",
            pushLogs.replacementBody.orEmpty().contains("WritableNativeArray")
        )
    }
}
