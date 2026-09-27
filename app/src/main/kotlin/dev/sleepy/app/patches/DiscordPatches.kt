package dev.sleepy.app.patches

import dev.sleepy.app.model.HermesPatch
import dev.sleepy.app.model.PatchSet
import dev.sleepy.app.model.SmaliPatch

object DiscordPatches {

    val BUNDLE_LOCK = PatchSet(
        id = "discord_ota_bundle",
        label = "Lock APK Hermes JS Bundle",
        description = "Neutralizes Discord's BundleUpdater pref keys (`key_android_js_bundle`) so Discord is forced to execute our patched APK asset bundle instead of silently downloading an unpatched bundle that restores all telemetry.",
        smaliPatches = listOf(
            SmaliPatch(
                dexName = "classes.dex",
                smaliPath = "com/discord/bundle_updater/BundleUpdater.smali",
                methodSignature = ".method public final isLoaded()Z",
                replacementBody = """.method public final isLoaded()Z
    .locals 1

    const/4 v0, 0x1

    return v0
.end method"""
            )
        )
    )

    val SENTRY = PatchSet(
        id = "discord_sentry",
        label = "Disable Sentry Crash Reporting (NDK & Java)",
        description = "Stubs out Sentry NDK native shared library loading (`SentryNdk.loadNativeLibraries()`) and gates Java crash reporter initialization so crash dumps, thread states, and device info are never sent to Sentry.",
        smaliPatches = listOf(
            SmaliPatch(
                dexName = "classes.dex",
                smaliPath = "io/sentry/android/ndk/SentryNdk.smali",
                methodSignature = ".method public static loadNativeLibraries()V",
                replacementBody = """.method public static loadNativeLibraries()V
    .locals 0

    return-void
.end method"""
            ),
            SmaliPatch(
                dexName = "classes.dex",
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
                dexName = "classes.dex",
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
        description = "Rewrites index.android.bundle bytecode directly on-device using libhermes_decomp.so to stub central analytics emitters, Sentry breadcrumbs, and upsell banners:",
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
