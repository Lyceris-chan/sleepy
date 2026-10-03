package dev.sleepy.app.patches

import dev.sleepy.app.engine.DexProcessor
import dev.sleepy.app.engine.OkHttpNameResolver
import dev.sleepy.app.engine.OkHttpResolution
import dev.sleepy.app.model.StepStatus
import dev.sleepy.app.model.TargetApk
import dev.sleepy.app.testing.ReferenceApks
import dev.sleepy.app.testing.dexEntries
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The generated Discord blocklist interceptor, against the reference build.
 *
 * A generated patch is only reviewable at its output: the emitted method must match the method
 * the desktop reference build produced for this release, character for character once the
 * discovered names are substituted. A separate test assembles the generated method against the
 * real APK, because text that reads correctly does not always assemble.
 */
class DiscordBlocklistPatchTest {

    /**
     * The comparison: byte-for-byte against the reference build's own method.
     *
     * This also pins the three discovered names, because a body that matches by coincidence of
     * names is not the same claim as a body that matches because the names were read correctly.
     */
    @Test
    fun generatedInterceptorMatchesTheReferenceBuild() {
        val dexEntries = dexEntries(BASE_APK)
        val target = TargetApk(DexProcessor.buildClassToDexIndex(dexEntries), dexEntries)

        val resolution = OkHttpNameResolver.resolve(target)
        assertTrue(
            "the okhttp3 names must resolve from this build's DEX, got $resolution",
            resolution is OkHttpResolution.Resolved
        )
        val names = (resolution as OkHttpResolution.Resolved).names

        // The expected names come out of the reference build's own generated method rather than
        // a fixed spelling: R8 renames them on every release, and a hardcoded pair turns the next
        // bump into an edit of this test instead of a check of the resolver.
        val reference = referenceMethodBody()
        val ctorLine = Regex("Lokhttp3/Response;-><init>(\\([^\\n]*)").find(reference)
        assertNotNull("the reference method must construct a Response", ctorLine)
        val protocolLine = Regex("sget-object \\w+, L([^;]+);->(\\w+):L[^;]+;").find(reference)
        assertNotNull("the reference method must read the Protocol HTTP/1.1 constant", protocolLine)

        assertEquals(
            "the resolved constructor must be the one the reference build calls",
            ctorLine!!.groupValues[1],
            names.responseConstructorDescriptor
        )
        assertEquals(
            "the resolved Protocol class must be the one the reference build names",
            protocolLine!!.groupValues[1],
            names.protocolClass
        )
        assertEquals(
            "the resolved HTTP/1.1 field must be the one the reference build names",
            protocolLine.groupValues[2],
            names.protocolHttp11Field
        )

        val generated = DiscordBlocklistPatch.interceptorBody(
            ctorDescriptor = names.responseConstructorDescriptor,
            protocolClass = names.protocolClass,
            protocolField = names.protocolHttp11Field
        )

        val referenceLines = reference.lines()
        val generatedLines = generated.lines()

        assertEquals(
            "the generated body must have the reference's line count",
            referenceLines.size,
            generatedLines.size
        )
        referenceLines.forEachIndexed { index, line ->
            assertEquals(
                "line ${index + 1} of the generated body must match the reference",
                line,
                generatedLines[index]
            )
        }
        // The line walk above pinpoints a difference; this catches the one it cannot see, a
        // trailing newline, which otherwise changes what follows the method in the file.
        assertTrue(
            "the generated body must equal the reference method body",
            reference == generated
        )

        println(
            "Blocklist interceptor: ${generatedLines.size} lines, identical to the reference " +
                "build's method"
        )
    }

    /**
     * The generated method applied to the real APK.
     *
     * Reassembly is where a bad label or a register outside `.locals 24` fails, and it is the only
     * check that the body the generator emits is something smali builds and this pipeline
     * therefore writes into the user's APK.
     */
    @Test
    fun generatedInterceptorAssemblesAgainstTheRealApk() = runBlocking {
        val dexEntries = dexEntries(BASE_APK)
        val classToDex = DexProcessor.buildClassToDexIndex(dexEntries)
        val target = TargetApk(classToDex, dexEntries)

        // Through the registry, so the id this set is registered under is exercised too.
        val patchSet = PatchRegistry.get(DiscordBlocklistPatch.NETWORK_BLOCKLIST.id)
        assertEquals(
            "the blocklist set must be registered",
            DiscordBlocklistPatch.NETWORK_BLOCKLIST,
            patchSet
        )
        val generated = patchSet!!.generator!!.generate(target)

        assertEquals(
            "generation must succeed on this build: ${generated.skipReason}",
            1,
            generated.patches.size
        )
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
            assertEquals(
                "the generated patch must apply cleanly: ${it.detail ?: ""}",
                StepStatus.OK,
                it.status
            )
        }
        assertTrue("the patched DEX must be non-empty", patchedDex.isNotEmpty())

        val patchedText = String(patchedDex, Charsets.ISO_8859_1)
        // One host rule and one path rule, at opposite ends of the two tables.
        assertTrue(
            "the host rules must be in the patched DEX",
            patchedText.contains("img.litix.io")
        )
        assertTrue(
            "the API rules must be in the patched DEX",
            patchedText.contains("/guild_role_subscriptions")
        )
        println("Blocklist interceptor reassembled into $dexName (${patchedDex.size} bytes)")
    }

    /**
     * The `requestStatsInterceptor` method out of the reference build's decompiled tree, cut the
     * way `blocklist.py` cuts it: from the `.method` line through its `.end method`.
     *
     * A tree that has not been through the reference's blocklist step carries the stock method
     * instead, which is a different thing to compare against: the comparison is skipped with
     * that as its reason, rather than reported as a thousand-line mismatch between two methods
     * that are not meant to be equal.
     */
    private fun referenceMethodBody(): String {
        val relative = "com/discord/resource_usage/DeviceResourceUsageRecorder${'$'}Companion.smali"
        val file =
            SMALI_DIRS.map { File(REFERENCE_TREE, "$it/$relative") }.firstOrNull { it.exists() }
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
        /** The Discord 349.5 base split, which lives outside the repository. */
        val BASE_APK = ReferenceApks.discordBaseApk

        /** The reference build's decompiled tree—the patched one, which is what shipped. */
        val REFERENCE_TREE = ReferenceApks.discordDecompiledBase

        val SMALI_DIRS = listOf("smali", "smali_classes2", "smali_classes3", "smali_classes4")

        const val INTERCEPTOR_SIGNATURE =
            ".method private final requestStatsInterceptor(Lokhttp3/Interceptor\$Chain;" +
                "Lcom/discord/resource_usage/DeviceResourceUsageRecorder\$RequestStats;)" +
                "Lokhttp3/Response;"
    }
}
