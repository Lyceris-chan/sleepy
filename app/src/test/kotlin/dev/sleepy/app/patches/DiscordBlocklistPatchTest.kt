package dev.sleepy.app.patches

import dev.sleepy.app.engine.DexProcessor
import dev.sleepy.app.engine.OkHttpNameResolver
import dev.sleepy.app.engine.OkHttpResolution
import dev.sleepy.app.model.StepStatus
import dev.sleepy.app.model.TargetApk
import dev.sleepy.app.testing.ComparisonApks
import dev.sleepy.app.testing.dexEntries
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The generated Discord blocklist interceptor.
 *
 * A generated patch is only reviewable at its output: the method the generator emits has to be
 * built from names the target build really carries, and it has to assemble. The first test checks
 * the names against that build's own DEX; the second assembles the generated method into it,
 * because text that reads correctly does not always assemble.
 */
class DiscordBlocklistPatchTest {

    /**
     * The three discovered names, checked against the build they were read from.
     *
     * The resolver reads an `okhttp3.Response` constructor, the `Protocol` class and that class's
     * HTTP/1.1 field out of the target's own DEX, because R8 renames all three on every release.
     * The earlier form of this test compared the generated method character for character against
     * a recorded patched build's copy of it. There is no recorded patched 351.2.0 build, so that
     * comparison is gone - which makes this the check that has to carry the claim: every name the
     * generator is handed must be a name the target actually carries, or the generated method is
     * assembled from another release's spellings and fails on the phone rather than here.
     */
    @Test
    fun theDiscoveredNamesAreTheOnesThisBuildCarries() {
        val dexEntries = dexEntries(BASE_APK)
        val classToDex = DexProcessor.buildClassToDexIndex(dexEntries)
        val target = TargetApk(classToDex, dexEntries)

        val resolution = OkHttpNameResolver.resolve(target)
        assertTrue(
            "the okhttp3 names must resolve from this build's DEX, got $resolution",
            resolution is OkHttpResolution.Resolved
        )
        val names = (resolution as OkHttpResolution.Resolved).names

        // The Protocol class has to be in the build, and it has to hold the field the resolver
        // named: a class name that resolves to something without an HTTP/1.1 constant is worse
        // than no resolution at all, because the generated method assembles and reads a field
        // that is not there.
        val protocolDescriptor = "L${names.protocolClass};"
        val protocolDex = requireNotNull(classToDex[protocolDescriptor]) {
            "the resolved Protocol class $protocolDescriptor is not in this build"
        }
        assertTrue(
            "the resolved Protocol class must hold ${names.protocolHttp11Field}",
            String(dexEntries.getValue(protocolDex), Charsets.ISO_8859_1)
                .contains(names.protocolHttp11Field)
        )

        // The descriptor is a parameter list, so the class it constructs is the one it is
        // written against rather than a name inside it. Every class the parameter list does name
        // has to be in the build too: a descriptor carrying another release's obfuscated type
        // assembles into a call the runtime cannot resolve.
        val named = Regex("L[^;]+;").findAll(names.responseConstructorDescriptor).map { it.value }
        val unresolved = named
            .filterNot { it in classToDex || PLATFORM_PREFIXES.any(it::startsWith) }
            .toList()
        assertEquals(
            "every class the constructor descriptor names must be in this build",
            emptyList<String>(),
            unresolved
        )

        val generated = DiscordBlocklistPatch.interceptorBody(
            ctorDescriptor = names.responseConstructorDescriptor,
            protocolClass = names.protocolClass,
            protocolField = names.protocolHttp11Field,
            chainRequestMethod = names.chainRequestMethod,
            chainProceedMethod = names.chainProceedMethod
        )
        // The chain accessors are the ones the runtime will look for, so the body has to call
        // them by the names this build gives them rather than by any release's.
        assertTrue(
            "the generated body must read the request through ${names.chainRequestMethod}()",
            generated.contains("Interceptor\$Chain;->${names.chainRequestMethod}()Lokhttp3/Request;")
        )
        assertTrue(
            "the generated body must proceed through ${names.chainProceedMethod}(Request)",
            generated.contains(
                "Interceptor\$Chain;->${names.chainProceedMethod}(Lokhttp3/Request;)Lokhttp3/Response;"
            )
        )
        assertTrue(
            "the generated body must construct okhttp3.Response with the resolved descriptor",
            generated.contains("Lokhttp3/Response;-><init>${names.responseConstructorDescriptor}")
        )
        val protocolReference =
            "L${names.protocolClass};->${names.protocolHttp11Field}:L${names.protocolClass};"
        assertTrue(
            "the generated body must read the resolved protocol field as $protocolReference",
            generated.contains(protocolReference)
        )
        println(
            "Blocklist interceptor: names ${names.responseConstructorDescriptor}, " +
                "${names.protocolClass}->${names.protocolHttp11Field}, all present in this build"
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

    private companion object {
        /** The Discord 351.2.0 base split, which lives outside the repository. */
        val BASE_APK = ComparisonApks.discordBaseApk

        /** Types every Android build carries, so they are not in the APK's own class index. */
        val PLATFORM_PREFIXES = listOf("Landroid/", "Ljava/", "Ljavax/", "Ldalvik/")

    }
}
