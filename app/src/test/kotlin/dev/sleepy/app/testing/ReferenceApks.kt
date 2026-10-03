package dev.sleepy.app.testing

import java.io.File

/**
 * The build outputs and tools this suite reads that are not in the repository.
 *
 * The defaults are the desktop checkout the fixtures were extracted from. A machine that has
 * them elsewhere sets `SLEEPY_FIXTURES_ROOT` to a directory holding:
 *
 * ```
 * discord/extracted/base.apk
 * discord/out/discord-alpha-349.5-patched-unsigned.apk
 * discord/decompiled/base
 * discord/tools/hermes-decomp
 * octogram/OctoGram_arm64.apk
 * octogram/OctoGram_361_arm64.apk
 * ```
 *
 * The Android SDK comes from `ANDROID_HOME` or `ANDROID_SDK_ROOT`, falling back to the portable
 * install beside this checkout. Tests that need a file or tool that is not installed report
 * themselves as skipped rather than passing without having read anything.
 */
object ReferenceApks {

    private val fixturesRoot: File? = System.getenv("SLEEPY_FIXTURES_ROOT")?.let(::File)

    private fun external(relative: String, default: String): File =
        if (fixturesRoot != null) File(fixturesRoot, relative) else File(default)

    /** The directory the Discord 349.5 base split and its splits were extracted into. */
    val discordExtracted: File = external(
        "discord/extracted",
        "/home/sleepy/Documents/antigravity/quirky-noether/discord/build/alpha3495/apk/extracted"
    )

    /** The Discord 349.5 base split. */
    val discordBaseApk: File = File(discordExtracted, "base.apk")

    /** The desktop build's patched Discord 349.5 APK, the parity reference. */
    val discordReferenceApk: File = external(
        "discord/out/discord-alpha-349.5-patched-unsigned.apk",
        "/home/sleepy/Documents/antigravity/quirky-noether/discord/build/alpha3495/out/" +
            "discord-alpha-349.5-patched-unsigned.apk"
    )

    /** The desktop build's decompiled tree for Discord 349.5. */
    val discordDecompiledBase: File = external(
        "discord/decompiled/base",
        "/home/sleepy/Documents/antigravity/quirky-noether/discord/build/alpha3495/decompiled/base"
    )

    /** The reference build's Hermes disassembler. */
    val hermesDecomp: File = external(
        "discord/tools/hermes-decomp",
        "/home/sleepy/Documents/antigravity/quirky-noether/discord/tools/hermes-decomp"
    )

    /**
     * The OctoGram 3.6.0 arm64 APK. No source offers this build, so the version gate must leave
     * it unidentified and refuse every edit tagged for the registered build.
     */
    val octoGramArm64: File = external(
        "octogram/OctoGram_arm64.apk",
        "/home/sleepy/Documents/antigravity/telegram/OctoGram_arm64.apk"
    )

    /** The OctoGram 3.6.1 arm64 APK. */
    val octoGram361Arm64: File = external(
        "octogram/OctoGram_361_arm64.apk",
        "/home/sleepy/Documents/antigravity/telegram/OctoGram_361_arm64.apk"
    )

    /** The directory the tooling harness writes its artifact into. */
    val harnessOutputDir: File = File(
        System.getenv("SLEEPY_HARNESS_OUT_DIR") ?: "/home/sleepy/sleepy-work"
    )

    /**
     * The newest installed version of the build tool [name], or null when no Android SDK is
     * installed.
     */
    fun buildTool(name: String): File? {
        val sdkRoots = listOfNotNull(
            System.getenv("ANDROID_HOME")?.let(::File),
            System.getenv("ANDROID_SDK_ROOT")?.let(::File),
            File("/home/sleepy/portable-tools/android-sdk")
        )
        return sdkRoots.asSequence()
            .map { File(it, "build-tools") }
            .filter { it.isDirectory }
            .flatMap { root -> root.listFiles().orEmpty().asSequence() }
            .filter { it.isDirectory && File(it, name).canExecute() }
            .sortedByDescending { it.name }
            .map { File(it, name) }
            .firstOrNull()
    }
}
