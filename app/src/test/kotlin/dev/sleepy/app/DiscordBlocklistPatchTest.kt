package dev.sleepy.app

import dev.sleepy.app.engine.DexProcessor
import dev.sleepy.app.engine.OkHttpNameResolver
import dev.sleepy.app.engine.OkHttpResolution
import dev.sleepy.app.model.StepStatus
import dev.sleepy.app.model.TargetApk
import dev.sleepy.app.patches.DiscordBlocklistPatch
import dev.sleepy.app.patches.PatchRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * The Discord network blocklist interceptor, against the real base split and against the method
 * the desktop reference build itself generated for that release.
 *
 * A generated patch cannot be reviewed by reading the generator alone: the method it emits is a
 * thousand lines of smali that either reproduces the reference or does not. The reference build
 * ships the method `blocklist.py` produced for this exact release, so the acceptance test here is
 * textual — the generated body has to match it character for character once the three discovered
 * names are substituted in. A single wrong blank line, label or register would still reassemble,
 * so "it looks right" and "it assembles" are separate tests below.
 */
class DiscordBlocklistPatchTest {

    /**
     * The comparison that matters: byte-for-byte against the reference build's own method.
     *
     * This also pins the three discovered names, because a body that matches by coincidence of
     * names is not the same claim as a body that matches because the names were read correctly.
     */
    @Test
    fun generatedInterceptorMatchesTheReferenceBuild() {
        val dexEntries = dexEntriesOf(BASE_APK)
        val target = TargetApk(DexProcessor.buildClassToDexIndex(dexEntries), dexEntries)

        val resolution = OkHttpNameResolver.resolve(target)
        assertTrue(
            "the okhttp3 names must resolve from this build's DEX, got $resolution",
            resolution is OkHttpResolution.Resolved
        )
        val names = (resolution as OkHttpResolution.Resolved).names

        // What the reference resolved for 348.5, read out of its own generated method.
        assertEquals(
            "(Lokhttp3/Request;Lps/s;Ljava/lang/String;ILps/q;Lokhttp3/Headers;Lokhttp3/ResponseBody;" +
                "Lokhttp3/Response;Lokhttp3/Response;Lokhttp3/Response;JJLhc/k;)V",
            names.responseConstructorDescriptor
        )
        assertEquals("ps/s", names.protocolClass)
        assertEquals("i", names.protocolHttp11Field)

        val generated = DiscordBlocklistPatch.interceptorBody(
            ctorDescriptor = names.responseConstructorDescriptor,
            protocolClass = names.protocolClass,
            protocolField = names.protocolHttp11Field
        )

        val reference = referenceMethodBody()
        val referenceLines = reference.lines()
        val generatedLines = generated.lines()

        assertEquals(
            "the generated body must have the reference's line count",
            referenceLines.size,
            generatedLines.size
        )
        referenceLines.forEachIndexed { index, line ->
            assertEquals("line ${index + 1} of the generated body must match the reference", line, generatedLines[index])
        }
        // The line walk above pinpoints a difference; this catches the one it cannot see, a
        // trailing newline, which would otherwise change what follows the method in the file.
        assertTrue("the generated body must equal the reference method body", reference == generated)

        println("Blocklist interceptor: ${generatedLines.size} lines, identical to the reference build's method")
    }

    /**
     * The generated method applied to the real APK.
     *
     * Reassembly is where a bad label or a register outside `.locals 24` fails, and it is the only
     * check that the body the generator emits is something smali will build and this pipeline will
     * therefore actually write into the user's APK.
     */
    @Test
    fun generatedInterceptorAssemblesAgainstTheRealApk() = runBlocking {
        val dexEntries = dexEntriesOf(BASE_APK)
        val classToDex = DexProcessor.buildClassToDexIndex(dexEntries)
        val target = TargetApk(classToDex, dexEntries)

        // Through the registry, so the id this set is registered under is exercised too.
        val patchSet = PatchRegistry.get(DiscordBlocklistPatch.NETWORK_BLOCKLIST.id)
        assertEquals("the blocklist set must be registered", DiscordBlocklistPatch.NETWORK_BLOCKLIST, patchSet)
        val generated = patchSet!!.generator!!.generate(target)

        assertEquals("generation must succeed on this build: ${generated.skipReason}", 1, generated.patches.size)
        val patch = generated.patches.single()
        val dexName = classToDex.getValue("L" + patch.smaliPath.removeSuffix(".smali") + ";")
        println("Blocklist interceptor targets $dexName")

        val originalDex = dexEntries.getValue(dexName)
        assertTrue(
            "precondition: the stock DEX must not already carry the blocklist",
            !String(originalDex, Charsets.ISO_8859_1).contains("img.litix.io")
        )

        val (patchedDex, results) = DexProcessor.patchDexSurgically(
            dexBytes = originalDex,
            patches = listOf(patch.copy(dexName = dexName))
        )
        results.forEach {
            assertEquals("the generated patch must apply cleanly: ${it.detail ?: ""}", StepStatus.OK, it.status)
        }
        assertTrue("the patched DEX must be non-empty", patchedDex.isNotEmpty())

        val patchedText = String(patchedDex, Charsets.ISO_8859_1)
        // One host rule and one path rule, at opposite ends of the two tables.
        assertTrue("the host rules must be in the patched DEX", patchedText.contains("img.litix.io"))
        assertTrue("the API rules must be in the patched DEX", patchedText.contains("/guild_role_subscriptions"))
        println("Blocklist interceptor reassembled into $dexName (${patchedDex.size} bytes)")
    }

    /**
     * Reads the APK's DEX files.
     *
     * The fixture is a build output outside the repository, so a machine without it reports
     * these tests as skipped rather than passing them without having read anything.
     */
    private fun dexEntriesOf(apk: File): Map<String, ByteArray> {
        assumeTrue("${apk.path} is not on this machine", apk.exists())
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(apk.readBytes())).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name.matches(Regex("classes\\d*\\.dex"))) {
                    entries[entry.name] = zis.readBytes()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return entries
    }

    /**
     * The `requestStatsInterceptor` method out of the reference build's decompiled tree, cut the
     * way `blocklist.py` cuts it: from the `.method` line through its `.end method`.
     *
     * A tree that has not been through the reference's blocklist step carries the stock method
     * instead, which is a different thing to compare against: the comparison is skipped with
     * that as its reason, rather than reported as a thousand-line mismatch between two methods
     * that were never meant to be equal.
     */
    private fun referenceMethodBody(): String {
        val relative = "com/discord/resource_usage/DeviceResourceUsageRecorder${'$'}Companion.smali"
        val file = SMALI_DIRS.map { File(REFERENCE_TREE, "$it/$relative") }.firstOrNull { it.exists() }
        assumeTrue("the reference tree is not on this machine ($relative)", file != null)

        val source = file!!.readText(Charsets.UTF_8)
        val start = source.indexOf(INTERCEPTOR_SIGNATURE)
        val end = if (start == -1) -1 else source.indexOf(".end method", start)
        assumeTrue(
            "the reference tree has no generated $INTERCEPTOR_SIGNATURE",
            start != -1 && end != -1
        )
        val body = source.substring(start, end + ".end method".length)
        assumeTrue(
            "the reference tree carries the stock interceptor rather than the blocklist",
            body.contains("img.litix.io")
        )
        return body
    }

    private companion object {
        val BASE_APK = File(
            "/home/sleepy/Documents/antigravity/quirky-noether/discord/build/alpha3482/apk/extracted/base.apk"
        )

        /** The reference build's decompiled tree — the patched one, which is what shipped. */
        val REFERENCE_TREE = File(
            "/home/sleepy/Documents/antigravity/quirky-noether/discord/build/alpha3482/decompiled/base"
        )

        val SMALI_DIRS = listOf("smali", "smali_classes2", "smali_classes3", "smali_classes4")

        const val INTERCEPTOR_SIGNATURE =
            ".method private final requestStatsInterceptor(Lokhttp3/Interceptor\$Chain;" +
                "Lcom/discord/resource_usage/DeviceResourceUsageRecorder\$RequestStats;)" +
                "Lokhttp3/Response;"
    }
}
