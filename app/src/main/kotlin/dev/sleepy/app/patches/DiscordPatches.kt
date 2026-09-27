package dev.sleepy.app.patches

import dev.sleepy.app.model.HermesPatch
import dev.sleepy.app.model.PatchSet
import dev.sleepy.app.model.SmaliPatch

/**
 * Bytecode and Hermes JavaScript modifications for Discord (Alpha/Release).
 *
 * Implements 1-to-1 parity with local patch definitions in:
 * - quirky-noether/discord/patches/core.py
 *
 * Stubs telemetry, Sentry crash reporters, and locks the on-device Hermes JS bundle.
 */
object DiscordPatches {

    val BUNDLE_LOCK = PatchSet(
        id = "discord_ota_bundle",
        label = "Lock APK Hermes JS Bundle",
        description = "Neutralizes Discord's BundleUpdater pref keys (`key_android_js_bundle`) and reroutes the OTA host to invalid.com so Discord executes our patched APK asset bundle instead of downloading an unpatched bundle.",
        smaliPatches = listOf(
            SmaliPatch(
                smaliPath = "com/discord/bundle_updater/BundleUpdater.smali",
                anchor = "key_android_js_bundle",
                replacement = "key_android_js_bundlX"
            ),
            SmaliPatch(
                smaliPath = "com/discord/bundle_updater/BundleUpdater.smali",
                anchor = "key_android_js_bundle_release_name",
                replacement = "key_android_js_bundle_release_namX"
            ),
            SmaliPatch(
                smaliPath = "com/discord/bundle_updater/BundleUpdater.smali",
                anchor = "const-string v2, \"discord.com\"",
                replacement = "const-string v2, \"invalid.com\""
            )
        )
    )

    val SENTRY = PatchSet(
        id = "discord_sentry",
        label = "Disable Sentry Crash Reporting (NDK & Java)",
        description = "Stubs out Sentry NDK native shared library loading (`SentryNdk.loadNativeLibraries()`) and gates Java crash reporter initialization so crash dumps, thread states, and device info are never sent to Sentry.",
        smaliPatches = listOf(
            SmaliPatch(
                smaliPath = "io/sentry/android/ndk/SentryNdk.smali",
                methodSignature = ".method public static loadNativeLibraries()V",
                replacementBody = """.method public static loadNativeLibraries()V
    .locals 0

    return-void
.end method"""
            ),
            SmaliPatch(
                smaliPath = "com/discord/crash_reporting/CrashReporting.smali",
                methodSignature = ".method public final init(Landroid/content/Context;)V",
                replacementBody = """.method public final init(Landroid/content/Context;)V
    .locals 0

    return-void
.end method"""
            )
        )
    )

    val TELEMETRY = PatchSet(
        id = "discord_telemetry",
        label = "Disable Native Telemetry & NetStats",
        description = "Stubs native AppsFlyer event dispatching, NetStats network traffic profiling, and native logging call sites in Java/Kotlin DEX bytecode.",
        smaliPatches = listOf(
            SmaliPatch(
                smaliPath = "com/discord/analytics/AnalyticsUtils.smali",
                methodSignature = ".method public static final init()V",
                replacementBody = """.method public static final init()V
    .locals 0

    return-void
.end method"""
            )
        )
    )

    val HERMES = PatchSet(
        id = "discord_hermes",
        label = "Hermes JS Bytecode Telemetry Stubs",
        description = "Rewrites index.android.bundle bytecode directly on-device using in-place Sentry DSN nulling and libhermes_decomp.so to stub central analytics emitters, Sentry breadcrumbs, and upsell banners:",
        hermesPatches = listOf(
            HermesPatch(
                functionId = "73760",
                functionName = "AnalyticsUtils.track (central event emitter)",
                hasmStub = """
                    LoadConstUndefined r0
                    Ret r0
                """.trimIndent()
            ),
            HermesPatch(
                functionId = "23080",
                functionName = "AnalyticsStore.track closure",
                hasmStub = """
                    LoadConstUndefined r0
                    Ret r0
                """.trimIndent()
            ),
            HermesPatch(
                functionId = "41655",
                functionName = "AppsFlyerLib.trackEvent",
                hasmStub = """
                    LoadConstUndefined r0
                    Ret r0
                """.trimIndent()
            ),
            HermesPatch(
                functionId = "74900",
                functionName = "SentryJS.captureBreadcrumb",
                hasmStub = """
                    LoadConstUndefined r0
                    Ret r0
                """.trimIndent()
            ),
            HermesPatch(
                functionId = "74901",
                functionName = "SentryJS.captureException",
                hasmStub = """
                    LoadConstUndefined r0
                    Ret r0
                """.trimIndent()
            ),
            HermesPatch(
                functionId = "62298",
                functionName = "Quest Orb & Monetization Hook Predicate",
                hasmStub = """
                    LoadConstFalse r0
                    Ret r0
                """.trimIndent()
            )
        )
    )

    val ALL = listOf(
        BUNDLE_LOCK,
        SENTRY,
        TELEMETRY,
        HERMES
    )
}
