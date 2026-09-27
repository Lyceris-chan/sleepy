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
            // Order is critical: key_android_js_bundle_release_name MUST be replaced before key_android_js_bundle
            // to avoid corrupting the longer key into key_android_js_bundlX_release_name.
            SmaliPatch(
                smaliPath = "com/discord/bundle_updater/BundleUpdater.smali",
                anchor = "key_android_js_bundle_release_name",
                replacement = "key_android_js_bundle_release_namX"
            ),
            SmaliPatch(
                smaliPath = "com/discord/bundle_updater/BundleUpdater.smali",
                anchor = "key_android_js_bundle",
                replacement = "key_android_js_bundlX"
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
        description = "Stubs out Sentry NDK native shared library loading (`SentryNdk.loadNativeLibraries()`), forces `CrashReporting.isDisabled() -> true`, and disables RNSentryModuleImpl SDK init and envelope dispatching so crash dumps, thread states, and device info are never sent to Sentry.",
        smaliPatches = listOf(
            SmaliPatch(
                smaliPath = "io/sentry/ndk/SentryNdk.smali",
                methodSignature = ".method public static declared-synchronized loadNativeLibraries()V",
                replacementBody = """.method public static declared-synchronized loadNativeLibraries()V
    .locals 0

    return-void
.end method"""
            ),
            SmaliPatch(
                smaliPath = "com/discord/crash_reporting/CrashReporting.smali",
                methodSignature = ".method private final isDisabled()Z",
                replacementBody = """.method private final isDisabled()Z
    .locals 1

    const/4 v0, 0x1

    return v0
.end method"""
            ),
            SmaliPatch(
                smaliPath = "io/sentry/react/RNSentryModuleImpl.smali",
                methodSignature = ".method public initNativeSdk(Lcom/facebook/react/bridge/ReadableMap;Lcom/facebook/react/bridge/Promise;)V",
                replacementBody = """.method public initNativeSdk(Lcom/facebook/react/bridge/ReadableMap;Lcom/facebook/react/bridge/Promise;)V
    .locals 1

    sget-object v0, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;

    invoke-interface {p2, v0}, Lcom/facebook/react/bridge/Promise;->resolve(Ljava/lang/Object;)V

    return-void
.end method"""
            ),
            SmaliPatch(
                smaliPath = "io/sentry/react/RNSentryModuleImpl.smali",
                methodSignature = ".method public captureEnvelope(Ljava/lang/String;Lcom/facebook/react/bridge/ReadableMap;Lcom/facebook/react/bridge/Promise;)V",
                replacementBody = """.method public captureEnvelope(Ljava/lang/String;Lcom/facebook/react/bridge/ReadableMap;Lcom/facebook/react/bridge/Promise;)V
    .locals 1

    const/4 v0, 0x0

    invoke-interface {p3, v0}, Lcom/facebook/react/bridge/Promise;->resolve(Ljava/lang/Object;)V

    return-void
.end method"""
            )
        )
    )

    val TELEMETRY = PatchSet(
        id = "discord_telemetry",
        label = "Disable Native Telemetry & NetStats",
        description = "Stubs native Google Advertising ID retrieval, InstallReferrerModule, TelemetryRing buffer appending, and WebRTC crash reporting so native event telemetry is dropped.",
        smaliPatches = listOf(
            SmaliPatch(
                smaliPath = "com/discord/ads/AdsModule.smali",
                methodSignature = ".method public getGoogleAdvertisingId(Lcom/facebook/react/bridge/Promise;)V",
                replacementBody = """.method public getGoogleAdvertisingId(Lcom/facebook/react/bridge/Promise;)V
    .locals 1

    invoke-direct {p0, p1}, Lcom/discord/ads/AdsModule;->resolveWithNullId(Lcom/facebook/react/bridge/Promise;)V

    return-void
.end method"""
            ),
            SmaliPatch(
                smaliPath = "com/discord/analytics/InstallReferrerModule.smali",
                methodSignature = ".method public final get(Lcom/facebook/react/bridge/Promise;)V",
                replacementBody = """.method public final get(Lcom/facebook/react/bridge/Promise;)V
    .locals 1

    const/4 v0, 0x0

    invoke-interface {p1, v0}, Lcom/facebook/react/bridge/Promise;->resolve(Ljava/lang/Object;)V

    return-void
.end method"""
            ),
            SmaliPatch(
                smaliPath = "com/discord/crash_reporting/TelemetryRing.smali",
                methodSignature = ".method public final append(Ljava/lang/String;JLjava/lang/String;Ljava/util/Map;Ljava/util/List;)V",
                replacementBody = """.method public final append(Ljava/lang/String;JLjava/lang/String;Ljava/util/Map;Ljava/util/List;)V
    .locals 0

    return-void
.end method"""
            ),
            SmaliPatch(
                smaliPath = "com/discord/crash_reporting/WebrtcCrashReporting.smali",
                methodSignature = ".method public static reportWebrtcException(Ljava/lang/Throwable;)Ljava/lang/String;",
                replacementBody = """.method public static reportWebrtcException(Ljava/lang/Throwable;)Ljava/lang/String;
    .locals 1

    const-string v0, ""

    return-object v0
.end method"""
            )
        )
    )

    val HERMES = PatchSet(
        id = "discord_hermes",
        label = "Hermes JS Bytecode Telemetry Stubs",
        description = "Rewrites index.android.bundle bytecode directly on-device in pure Kotlin using Modern12 in-place opcode stubs and Sentry DSN nulling without native binary dependencies:",
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
                functionId = "22947",
                functionName = "SentryJS.addBreadcrumb",
                hasmStub = """
                    LoadConstUndefined r0
                    Ret r0
                """.trimIndent()
            ),
            HermesPatch(
                functionId = "22943",
                functionName = "SentryJS.captureException",
                hasmStub = """
                    LoadConstUndefined r0
                    Ret r0
                """.trimIndent()
            ),
            HermesPatch(
                functionId = "22958",
                functionName = "initSentry (Sentry JS SDK initialiser)",
                hasmStub = """
                    LoadConstUndefined r0
                    Ret r0
                """.trimIndent()
            ),
            HermesPatch(
                functionId = "19432",
                functionName = "handleFingerprint (fingerprint telemetry)",
                hasmStub = """
                    LoadConstUndefined r0
                    Ret r0
                """.trimIndent()
            ),
            HermesPatch(
                functionId = "60648",
                functionName = "handleTrack (science event action handler)",
                hasmStub = """
                    LoadConstUndefined r0
                    Ret r0
                """.trimIndent()
            ),
            HermesPatch(
                functionId = "39965",
                functionName = "initSessionHeartbeatScheduler (heartbeat timer)",
                hasmStub = """
                    LoadConstUndefined r0
                    Ret r0
                """.trimIndent()
            ),
            HermesPatch(
                functionId = "62294",
                functionName = "QuestHomeSetting.usePredicate (settings route gate)",
                hasmStub = """
                    LoadConstFalse r0
                    Ret r0
                """.trimIndent()
            ),
            HermesPatch(
                functionId = "62298",
                functionName = "QuestHomeSetting (quest settings screen)",
                hasmStub = """
                    LoadConstFalse r0
                    Ret r0
                """.trimIndent()
            ),
            HermesPatch(
                functionId = "49956",
                functionName = "NitroUpsellButton (inline Nitro upsell button)",
                hasmStub = """
                    LoadConstNull r0
                    Ret r0
                """.trimIndent()
            ),
            HermesPatch(
                functionId = "47719",
                functionName = "openPremiumModal (upsell modal launcher)",
                hasmStub = """
                    LoadConstUndefined r0
                    Ret r0
                """.trimIndent()
            ),
            HermesPatch(
                functionId = "42692",
                functionName = "openPremiumUpsellActionSheet (action sheet upsell launcher)",
                hasmStub = """
                    LoadConstUndefined r0
                    Ret r0
                """.trimIndent()
            ),
            HermesPatch(
                functionId = "45655",
                functionName = "WrappedProfileEffect (animated profile card decoration)",
                hasmStub = """
                    LoadConstNull r0
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
