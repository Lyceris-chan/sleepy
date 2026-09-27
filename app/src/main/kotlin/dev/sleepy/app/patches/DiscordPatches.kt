package dev.sleepy.app.patches

import dev.sleepy.app.model.HermesPatch
import dev.sleepy.app.model.PatchSet
import dev.sleepy.app.model.SmaliPatch

object DiscordPatches {

    val SENTRY = PatchSet(
        id = "discord_sentry",
        label = "Disable Sentry Crash Reporting",
        description = "Stubs out Sentry NDK native library loading and Java crash reporter initialization.",
        smaliPatches = listOf(
            SmaliPatch(
                dexName = "classes.dex",
                smaliPath = "io/sentry/android/ndk/SentryNdk.smali",
                methodSignature = ".method public static loadNativeLibraries()V",
                replacementBody = """.method public static loadNativeLibraries()V
    .locals 0

    return-void
.end method"""
            )
        )
    )

    val TELEMETRY = PatchSet(
        id = "discord_telemetry",
        label = "Kill Native Telemetry & NetStats",
        description = "Suppresses AppsFlyer and background network statistics telemetry logging.",
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

    val PERF = PatchSet(
        id = "discord_perf",
        label = "Optimize OkHttp Cache Sharing",
        description = "Prevents React Native OkHttp instances from thrashing the shared disk cache.",
        smaliPatches = emptyList()
    )

    val HERMES = PatchSet(
        id = "discord_hermes",
        label = "Hermes JS Bytecode Telemetry Stubs",
        description = "Directly modifies index.android.bundle bytecode to stub the central analytics emitter and trackers.",
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
                functionName = "Orb / Monetization Setting Predicate",
                hasmStub = """
                    LoadConstFalse r0
                    Ret r0
                """.trimIndent()
            )
        )
    )

    val MANIFEST = PatchSet(
        id = "discord_manifest",
        label = "Manifest Permission Hygiene",
        description = "Disables dead tracking permissions (ADSERVICES, CONTACTS, INSTALL_REFERRER).",
        smaliPatches = emptyList()
    )

    val ALL = listOf(
        SENTRY,
        TELEMETRY,
        PERF,
        HERMES,
        MANIFEST
    )
}
