package dev.sleepy.app.patches

import dev.sleepy.app.model.HermesPatch
import dev.sleepy.app.model.HermesStubShape
import dev.sleepy.app.model.PatchSet
import dev.sleepy.app.model.SmaliPatch

/**
 * Bytecode and Hermes JavaScript modifications for Discord (Alpha/Release).
 *
 * Transcribed from the reference suite in `quirky-noether/discord/patches/core.py`
 * (Discord 348.5 Alpha). Every entry here resolves to a real symbol in that file — no
 * patch in this object was invented.
 *
 * This object is the **core** subset, not the whole reference suite. The remaining smali
 * edits live in [DiscordNativePatches]; edits that rewrite `AndroidManifest.xml` or the
 * resource table are not portable to an on-device patcher and are absent by design. Hermes
 * function ids are per-bundle and shift on every Discord release, so the ids below are tied
 * to the 348.5 build the pipeline is pointed at.
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
                title = "Locking Stored Bundle Release Key",
                explanation = "Renames stored OTA bundle preference keys so Discord always executes our patched bundle from the APK asset",
                smaliPath = "com/discord/bundle_updater/BundleUpdater.smali",
                anchor = "key_android_js_bundle_release_name",
                replacement = "key_android_js_bundle_release_namX"
            ),
            SmaliPatch(
                title = "Neutralizing Cached JS Bundle Lookup",
                explanation = "Prevents BundleUpdater from loading unpatched OTA bundles cached in SharedPreferences",
                smaliPath = "com/discord/bundle_updater/BundleUpdater.smali",
                anchor = "key_android_js_bundle",
                replacement = "key_android_js_bundlX"
            ),
            SmaliPatch(
                title = "Rerouting OTA Bundle Host to Invalid Domain",
                explanation = "Reroutes OTA host domain from discord.com to invalid.com so background OTA bundle downloads cannot succeed",
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
                title = "Disabling Native Sentry NDK Shared Library Loading",
                explanation = "Stubs SentryNdk.loadNativeLibraries() with a no-op so native crash signal handlers are never installed",
                smaliPath = "io/sentry/ndk/SentryNdk.smali",
                methodSignature = ".method public static declared-synchronized loadNativeLibraries()V",
                replacementBody = """.method public static declared-synchronized loadNativeLibraries()V
    .locals 0

    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Forcing CrashReporting.isDisabled to Always True",
                explanation = "Gates all Java crash reporting and Sentry breadcrumb collection loops directly at the source",
                smaliPath = "com/discord/crash_reporting/CrashReporting.smali",
                methodSignature = ".method private final isDisabled()Z",
                replacementBody = """.method private final isDisabled()Z
    .locals 1

    const/4 v0, 0x1

    return v0
.end method"""
            ),
            SmaliPatch(
                title = "Neutralizing React Native Sentry SDK Init",
                explanation = "Resolves initNativeSdk with false so the React Native JS-to-Java crash bridge never binds",
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
                title = "Dropping Sentry Crash Envelopes",
                explanation = "Resolves captureEnvelope with null so queued error dumps are dropped instead of dispatched",
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
                title = "Nullifying Google Advertising ID Retrieval",
                explanation = "Returns null advertising ID to prevent ad tracking and device profile fingerprinting",
                smaliPath = "com/discord/ads/AdsModule.smali",
                methodSignature = ".method public getGoogleAdvertisingId(Lcom/facebook/react/bridge/Promise;)V",
                replacementBody = """.method public getGoogleAdvertisingId(Lcom/facebook/react/bridge/Promise;)V
    .locals 1

    invoke-direct {p0, p1}, Lcom/discord/ads/AdsModule;->resolveWithNullId(Lcom/facebook/react/bridge/Promise;)V

    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Dropping Install Referrer Attribution",
                explanation = "Resolves install referrer query with null so marketing campaign attribution is disabled",
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
                title = "Silencing Native Telemetry Ring Buffer",
                explanation = "Stubs TelemetryRing.append() to stop accumulating user interactions in the native log ring",
                smaliPath = "com/discord/crash_reporting/TelemetryRing.smali",
                methodSignature = ".method public final append(Ljava/lang/String;JLjava/lang/String;Ljava/util/Map;Ljava/util/List;)V",
                replacementBody = """.method public final append(Ljava/lang/String;JLjava/lang/String;Ljava/util/Map;Ljava/util/List;)V
    .locals 0

    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Disabling WebRTC Exception Reporting",
                explanation = "Returns empty string on WebRTC exceptions so call error diagnostics are never uploaded",
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
                title = "Silencing Central Analytics Event Emitter",
                explanation = "Stubs AnalyticsUtils.track so all science event dispatches return immediately without CPU/memory sampling. " +
                    "Its callers await the result, so the stub must still resolve a promise rather than hand back undefined.",
                functionId = "73760",
                stubShape = HermesStubShape.PROMISE,
                functionName = "AnalyticsUtils.track (central event emitter)",
                hasmStub = """
                    GetGlobalObject r0
                    TryGetById r1, r0, 0, "Promise"
                    GetByIdShort r0, r1, 0, "resolve"
                    Call1 r0, r0, r1
                    Ret r0
                """.trimIndent()
            ),
            HermesPatch(
                title = "Neutralizing AnalyticsStore Tracking Closure",
                explanation = "Silences secondary store event tracker to avoid CPU and memory sampling overhead",
                functionId = "23080",
                stubShape = HermesStubShape.UNDEFINED,
                functionName = "AnalyticsStore.track closure",
                hasmStub = """
                    LoadConstUndefined r0
                    Ret r0
                """.trimIndent()
            ),
            HermesPatch(
                title = "Disabling Sentry JS Breadcrumb Recording",
                explanation = "Prevents every UI click, navigation, and Redux action from being saved into the Sentry breadcrumb trail",
                functionId = "22947",
                stubShape = HermesStubShape.UNDEFINED,
                functionName = "SentryJS.addBreadcrumb",
                hasmStub = """
                    LoadConstUndefined r0
                    Ret r0
                """.trimIndent()
            ),
            HermesPatch(
                title = "Dropping JS Exception Captures",
                explanation = "Silences SentryJS.captureException to prevent uploading JS stack traces to Sentry backends",
                functionId = "22943",
                stubShape = HermesStubShape.UNDEFINED,
                functionName = "SentryJS.captureException",
                hasmStub = """
                    LoadConstUndefined r0
                    Ret r0
                """.trimIndent()
            ),
            HermesPatch(
                title = "Preventing Sentry JS SDK Initialization",
                explanation = "Stubs initSentry() so no global JS error hooks or unhandled promise rejection monitors are attached",
                functionId = "22958",
                stubShape = HermesStubShape.UNDEFINED,
                functionName = "initSentry (Sentry JS SDK initialiser)",
                hasmStub = """
                    LoadConstUndefined r0
                    Ret r0
                """.trimIndent()
            ),
            HermesPatch(
                title = "Disabling Device Fingerprint Tracking",
                explanation = "Neutralizes handleFingerprint to prevent tracking device identifiers across user sessions",
                functionId = "19432",
                stubShape = HermesStubShape.UNDEFINED,
                functionName = "handleFingerprint (fingerprint telemetry)",
                hasmStub = """
                    LoadConstUndefined r0
                    Ret r0
                """.trimIndent()
            ),
            HermesPatch(
                title = "Dropping Science Action Telemetry",
                explanation = "Stubs handleTrack action handler so background events are not buffered or sent to /science",
                functionId = "60648",
                stubShape = HermesStubShape.UNDEFINED,
                functionName = "handleTrack (science event action handler)",
                hasmStub = """
                    LoadConstUndefined r0
                    Ret r0
                """.trimIndent()
            ),
            HermesPatch(
                title = "Stopping Periodic Session Heartbeat Pings",
                explanation = "Prevents recurring timer from sending ping telemetry and network pings",
                functionId = "39965",
                stubShape = HermesStubShape.UNDEFINED,
                functionName = "initSessionHeartbeatScheduler (heartbeat timer)",
                hasmStub = """
                    LoadConstUndefined r0
                    Ret r0
                """.trimIndent()
            ),
            HermesPatch(
                title = "Hiding Quests from Discord Settings",
                explanation = "Forces Quest route predicate to false so the Quests tab is never constructed or displayed in Discord Settings",
                functionId = "62294",
                stubShape = HermesStubShape.FALSE,
                functionName = "QuestHomeSetting.usePredicate (settings route gate)",
                hasmStub = """
                    LoadConstFalse r0
                    Ret r0
                """.trimIndent()
            ),
            HermesPatch(
                title = "Neutralizing Quest Home Screen Entry",
                explanation = "Stubs the Quest Home screen component to prevent rendering dead promotional tasks",
                functionId = "62298",
                stubShape = HermesStubShape.UNDEFINED,
                functionName = "QuestHomeSetting (quest settings screen)",
                hasmStub = """
                    LoadConstUndefined r0
                    Ret r0
                """.trimIndent()
            ),
            HermesPatch(
                title = "Hiding Inline 'Get Nitro' Buttons",
                explanation = "Stubs NitroUpsellButton so inline upsell banners and buttons are never built",
                functionId = "49956",
                stubShape = HermesStubShape.UNDEFINED,
                functionName = "NitroUpsellButton (inline Nitro upsell button)",
                hasmStub = """
                    LoadConstUndefined r0
                    Ret r0
                """.trimIndent()
            ),
            HermesPatch(
                title = "Suppressing Nitro Upsell Modal Dialogs",
                explanation = "No-ops openPremiumModal so clicking premium features does not pop up nag screens",
                functionId = "47719",
                stubShape = HermesStubShape.UNDEFINED,
                functionName = "openPremiumModal (upsell modal launcher)",
                hasmStub = """
                    LoadConstUndefined r0
                    Ret r0
                """.trimIndent()
            ),
            HermesPatch(
                title = "Suppressing Nitro Upsell Action Sheets",
                explanation = "No-ops openPremiumUpsellActionSheet to block bottom sheet promotion sheets",
                functionId = "42692",
                stubShape = HermesStubShape.UNDEFINED,
                functionName = "openPremiumUpsellActionSheet (action sheet upsell launcher)",
                hasmStub = """
                    LoadConstUndefined r0
                    Ret r0
                """.trimIndent()
            ),
            HermesPatch(
                title = "Removing Heavy Animated Profile Card Effects",
                explanation = "Returns null for WrappedProfileEffect to eliminate battery-draining profile loop videos",
                functionId = "45655",
                stubShape = HermesStubShape.NULL,
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
