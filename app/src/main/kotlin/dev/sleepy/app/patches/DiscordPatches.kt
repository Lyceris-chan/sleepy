package dev.sleepy.app.patches

import dev.sleepy.app.engine.BinaryXmlEditor
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
 * edits live in [DiscordNativePatches]. The manifest edits the reference makes by rewriting
 * text are here as the facts they match on — element names, attribute values, component
 * names — and are applied by `BinaryXmlEditor`, which edits the compiled document rather than
 * the text form an on-device patcher never has. The resource table is rebuilt by
 * `ResourceTableMerger` for the same reason. Hermes function ids are per-bundle and shift on
 * every Discord release, so the ids below are tied to the 348.5 build the pipeline is pointed
 * at.
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

    /**
     * The JavaScript stub catalogue.
     *
     * These are the auditable shapes — one entry per reference table membership, naming the
     * function and the value its stub must return. The pipeline does **not** apply them
     * directly: a Hermes function id is only meaningful for the bundle it was taken from, and
     * these ids are pinned to Discord 348.5. What actually runs is
     * [DiscordHermesBundlePatch], whose 145 entries were extracted from a paired
     * base/patched bundle of that exact release and are applied only when the bundle matches
     * it byte-length for byte-length.
     *
     * This object is kept because it is what makes the 145 auditable against the reference
     * tables: it records *why* each function is stubbed, which the extracted bodies cannot.
     */
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

    /**
     * Files that carry the crash reporter rather than call it, and so cannot be neutralised by
     * editing code.
     *
     * The reference build deletes these alongside stubbing the Sentry SDK. The native shared
     * objects install the signal handlers and the `unknown/` paths are what the SDK writes a
     * tombstone through, so leaving them in place leaves the mechanism intact even with every
     * Java entry point stubbed.
     */
    val SENTRY_ARTEFACTS = setOf(
        "lib/arm64-v8a/libsentry.so",
        "lib/arm64-v8a/libsentry-android.so",
        "lib/armeabi-v7a/libsentry.so",
        "lib/armeabi-v7a/libsentry-android.so",
        "lib/x86/libsentry.so",
        "lib/x86/libsentry-android.so",
        "lib/x86_64/libsentry.so",
        "lib/x86_64/libsentry-android.so",
        // Note the separator differs between these two entries in the real APK: this one is
        // a path segment, the one below is a dot inside a filename. They cannot be unified.
        "META-INF/io/sentry/sentry-android-replay/verification.properties",
        "META-INF/native-image/io.sentry/sentry/native-image.properties",
        "META-INF/sentry-android-replay_release.kotlin_module",
        "io/sentry/android/core/internal/tombstone/tombstone.proto"
    )

    /**
     * The two Sentry components AndroidManifest.xml declares as `<provider>`s.
     *
     * They are the reason stubbing the SDK's entry points is not enough on its own: the platform
     * instantiates every declared content provider while the process starts, before any Java the
     * patches touch is entered, and the SDK's own provider is what starts the reporter. Removing
     * the declaration is what actually keeps it from starting.
     */
    val SENTRY_PROVIDERS = listOf(
        "io.sentry.android.core.SentryInitProvider",
        "io.sentry.android.core.SentryPerformanceProvider"
    )

    /**
     * The `<meta-data>` entries the Play Core split installer writes into a bundle's manifest.
     *
     * They describe an APK that is one split of a bundle. This patcher merges the splits into a
     * single APK, so the markers describe an installation that no longer exists — and
     * `com.android.vending.splits.required` is read by the Play Store as a claim that the app is
     * missing the rest of its splits, which is the opposite of true once they are merged in.
     */
    val PLAY_SPLIT_MARKERS = listOf(
        "com.android.vending.splits.required",
        "com.android.vending.splits",
        "com.android.vending.derived.apk.id"
    )

    /**
     * The permissions this build declares and has no live code behind, so the reference strips them
     * from every build it makes.
     *
     * These are not a preference, which is why joining this list is a decision about the app and
     * not a switch over it: each was checked against the patched tree before being written down,
     * and a permission is only listed here when nothing reachable refers to it — stripping one that
     * is still used turns a working call into a SecurityException.
     *
     *   `READ_CONTACTS`                     - the only reference left is React Native's
     *       permission-request module; contact sync is stubbed out, so the address book is never
     *       read. Removing the declaration makes that structural rather than dependent on the stub
     *       holding.
     *   `AD_ID`                             - the advertising identifier. [TELEMETRY] resolves every
     *       request for it with null, so nothing downstream can be attributed to this device.
     *   `ACCESS_ADSERVICES_ATTRIBUTION`     - Privacy Sandbox attribution, with no ad SDK left to
     *       report through it.
     *   `READ_APP_INFO` (Samsung)           - a dead declaration: no code in the dex names it.
     *   `GET_COMMON_DATA` (Huawei)          - the same, for the Huawei app market.
     *   `BIND_GET_INSTALL_REFERRER_SERVICE` - bound only by the Play Install Referrer SDK and
     *       AppsFlyer: `InstallReferrerModule` is stubbed and `AppsFlyerLib.start()` has no callers
     *       anywhere, so nothing ever binds the service.
     */
    val DEAD_PERMISSIONS = listOf(
        "android.permission.READ_CONTACTS",
        "com.google.android.gms.permission.AD_ID",
        "android.permission.ACCESS_ADSERVICES_ATTRIBUTION",
        "com.samsung.android.mapsagent.permission.READ_APP_INFO",
        "com.huawei.appmarket.service.commondata.permission.GET_COMMON_DATA",
        "com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE"
    )

    /**
     * One component a manifest declares: the element it is declared in, and its `android:name`.
     *
     * Most of the components this object matches on share an element, which is why
     * [SENTRY_PROVIDERS] and [PLAY_SPLIT_MARKERS] are lists of bare names. The Google Analytics set
     * below is not uniform — a receiver and two services — so what element a component lives in is
     * part of the fact rather than something the manifest pass should read back out of its name.
     */
    data class ManifestComponent(val element: String, val name: String)

    /**
     * The Google Analytics components the reference build switches off.
     *
     * Google Analytics is inert in this app: the SDK's classes ship in the dex and its components
     * are declared, but every tracker initialisation site is on a code path the patches have
     * already cut. The reference sets `android:enabled="false"` on all three rather than deleting
     * the declarations, and the difference matters — the platform never instantiates a disabled
     * component, so the receiver never sees a broadcast and the JobService is never bound, while
     * the manifest still describes the classes the dex actually holds.
     */
    val GOOGLE_ANALYTICS_COMPONENTS = listOf(
        ManifestComponent(BinaryXmlEditor.ELEMENT_RECEIVER, "com.google.android.gms.analytics.AnalyticsReceiver"),
        ManifestComponent(BinaryXmlEditor.ELEMENT_SERVICE, "com.google.android.gms.analytics.AnalyticsService"),
        ManifestComponent(BinaryXmlEditor.ELEMENT_SERVICE, "com.google.android.gms.analytics.AnalyticsJobService")
    )

    /** The `<action>` naming the AppsFlyer install-referrer query in the manifest's `<queries>`. */
    const val APPSFLYER_INSTALL_PROVIDER_ACTION = "com.appsflyer.referrer.INSTALL_PROVIDER"

    /**
     * The service that publishes Discord's Rich Presence to other applications on the device.
     *
     * The reference build closes it because it is exported with no permission and checks nothing
     * about the caller, so any installed app can bind it and push arbitrary presence frames as the
     * user. See [dev.sleepy.app.engine.DiscordManifestEdits] for why this one carries no switch.
     */
    const val RPC_SERVICE_NAME = "com.discord.socialrpc.DiscordRpcService"

    val ALL = listOf(
        BUNDLE_LOCK,
        SENTRY,
        TELEMETRY,
        HERMES
    )
}
