package dev.sleepy.app.patches

import dev.sleepy.app.model.PatchSet
import dev.sleepy.app.model.SmaliPatch

/**
 * Native (smali/DEX) modifications for Discord 353.2.0.
 *
 * Every entry is a call the recorded 349.5 change set makes, re-pointed at this build. All 47
 * class paths survived unchanged, but four kinds of name inside them moved: five
 * experiment-lambda signatures, `FrameMetricsAggregator.bindTo` and `enableTracking`, two
 * coroutine classes, and the obfuscated okhttp cache type. Each is read out of the build's own
 * smali here rather than carried.
 *
 * Manifest edits, resource-table changes and bundled-file deletions are applied elsewhere in the
 * pipeline because a DEX edit cannot make them. The recorded edits that are off by default stay
 * off: they change behaviour upstream depends on.
 *
 * [versionTag] and [dexName] are null because Discord ships every class in a
 * version-dependent DEX; the pipeline resolves it from the class descriptor.
 */
object DiscordNativePatches {

    /**
     * Blanks the hard-coded Sentry DSNs and stubs the breadcrumb facade, so crash and
     * breadcrumb reporting has no destination.
     */
    val SENTRY = PatchSet(
        id = "discord_native_sentry",
        label = "Blank the crash reporter's keys",
        description = "Empties the three hard-coded Sentry DSN constants in every class that carries them, gates CrashReporting.addBreadcrumb behind isDisabled(), drops soft exceptions and the SDK breadcrumb facade, so crash and breadcrumb reporting has no destination even on code paths that bypass the app-level switch.",
        smaliPatches = listOf(
            SmaliPatch(
                title = "Blanking the Sentry DSN in BuildConfig",
                explanation = "Removes the alpha/beta Sentry endpoint hard-coded in the generated BuildConfig so nothing in the SDK can address it.",
                smaliPath = "u5/b.smali",
                anchor = "\"https://9a42ef460144a03b30c8b2d5321cfe11@o64374.ingest.sentry.io/5992375\"",
                replacement = "\"\""
            ),
            SmaliPatch(
                title = "Blanking the production Sentry DSN in BuildConfig",
                explanation = "Removes the production Sentry endpoint hard-coded in the generated BuildConfig.",
                smaliPath = "u5/b.smali",
                anchor = "\"https://70545531dfe34835bf4dd0996821e8b6@o64374.ingest.sentry.io/5992375\"",
                replacement = "\"\""
            ),
            SmaliPatch(
                title = "Blanking the staff Sentry DSN in BuildConfig",
                explanation = "Removes the staff-build Sentry endpoint hard-coded in the generated BuildConfig.",
                smaliPath = "u5/b.smali",
                anchor = "\"https://90509cba01573ee4e14a2f5e15aee5ca@o64374.ingest.sentry.io/5992375\"",
                replacement = "\"\""
            ),
            SmaliPatch(
                title = "Blanking the Sentry DSN in ClientInfo",
                explanation = "Removes the alpha/beta Sentry endpoint from the client-info table that the crash reporter reads at runtime.",
                smaliPath = "com/discord/client_info/react/ClientInfoModule.smali",
                anchor = "\"https://9a42ef460144a03b30c8b2d5321cfe11@o64374.ingest.sentry.io/5992375\"",
                replacement = "\"\""
            ),
            SmaliPatch(
                title = "Blanking the production Sentry DSN in ClientInfo",
                explanation = "Removes the production Sentry endpoint from the client-info table.",
                smaliPath = "com/discord/client_info/react/ClientInfoModule.smali",
                anchor = "\"https://70545531dfe34835bf4dd0996821e8b6@o64374.ingest.sentry.io/5992375\"",
                replacement = "\"\""
            ),
            SmaliPatch(
                title = "Blanking the staff Sentry DSN in ClientInfo",
                explanation = "Removes the staff-build Sentry endpoint from the client-info table.",
                smaliPath = "com/discord/client_info/react/ClientInfoModule.smali",
                anchor = "\"https://90509cba01573ee4e14a2f5e15aee5ca@o64374.ingest.sentry.io/5992375\"",
                replacement = "\"\""
            ),
            SmaliPatch(
                title = "Blanking the Sentry DSN in the React Native client-info module",
                explanation = "Removes the alpha/beta Sentry endpoint that the JavaScript side can read through the React Native bridge.",
                smaliPath = "com/discord/client_info/react/ClientInfoModule.smali",
                anchor = "\"https://9a42ef460144a03b30c8b2d5321cfe11@o64374.ingest.sentry.io/5992375\"",
                replacement = "\"\""
            ),
            SmaliPatch(
                title = "Blanking the production Sentry DSN in the React Native client-info module",
                explanation = "Removes the production Sentry endpoint exposed over the React Native bridge.",
                smaliPath = "com/discord/client_info/react/ClientInfoModule.smali",
                anchor = "\"https://70545531dfe34835bf4dd0996821e8b6@o64374.ingest.sentry.io/5992375\"",
                replacement = "\"\""
            ),
            SmaliPatch(
                title = "Blanking the staff Sentry DSN in the React Native client-info module",
                explanation = "Removes the staff-build Sentry endpoint exposed over the React Native bridge.",
                smaliPath = "com/discord/client_info/react/ClientInfoModule.smali",
                anchor = "\"https://90509cba01573ee4e14a2f5e15aee5ca@o64374.ingest.sentry.io/5992375\"",
                replacement = "\"\""
            ),
            SmaliPatch(
                title = "Blanking the Sentry DSN inside CrashReporting",
                explanation = "Removes the alpha/beta Sentry endpoint held by the crash reporter itself, so its own initialization finds nothing to send to.",
                smaliPath = "com/discord/crash_reporting/CrashReporting.smali",
                anchor = "\"https://9a42ef460144a03b30c8b2d5321cfe11@o64374.ingest.sentry.io/5992375\"",
                replacement = "\"\""
            ),
            SmaliPatch(
                title = "Blanking the production Sentry DSN inside CrashReporting",
                explanation = "Removes the production Sentry endpoint held by the crash reporter itself.",
                smaliPath = "com/discord/crash_reporting/CrashReporting.smali",
                anchor = "\"https://70545531dfe34835bf4dd0996821e8b6@o64374.ingest.sentry.io/5992375\"",
                replacement = "\"\""
            ),
            SmaliPatch(
                title = "Blanking the staff Sentry DSN inside CrashReporting",
                explanation = "Removes the staff-build Sentry endpoint held by the crash reporter itself.",
                smaliPath = "com/discord/crash_reporting/CrashReporting.smali",
                anchor = "\"https://90509cba01573ee4e14a2f5e15aee5ca@o64374.ingest.sentry.io/5992375\"",
                replacement = "\"\""
            ),
            SmaliPatch(
                title = "Stopping breadcrumb collection when crash reporting is off",
                explanation = "Returns from addBreadcrumb immediately when crash reporting is disabled, instead of building the breadcrumb and writing the SentryBreadcrumb log line behind it.",
                smaliPath = "com/discord/crash_reporting/CrashReporting.smali",
                anchor = """.line 1
    const-string v2, "breadcrumbMessage"

    .line 2
    .line 3
    invoke-static {p1, v2}, Lkotlin/jvm/internal/Intrinsics;->checkNotNullParameter(Ljava/lang/Object;Ljava/lang/String;)V""",
                replacement = """
    invoke-direct {p0}, Lcom/discord/crash_reporting/CrashReporting;->isDisabled()Z
    move-result v0
    if-eqz v0, :cond_patch_skip
    return-void
    :cond_patch_skip
.line 1
    const-string v2, "breadcrumbMessage"

    .line 2
    .line 3
    invoke-static {p1, v2}, Lkotlin/jvm/internal/Intrinsics;->checkNotNullParameter(Ljava/lang/Object;Ljava/lang/String;)V"""
            ),
            SmaliPatch(
                title = "Dropping React Native soft exceptions",
                explanation = "Discards soft-exception reports at the React Native module so non-fatal errors are not recorded or uploaded.",
                smaliPath = "com/discord/crash_reporting/l.smali",
                methodSignature = ".method public logSoftException(Ljava/lang/String;Ljava/lang/Throwable;)V",
                replacementBody = """.method public logSoftException(Ljava/lang/String;Ljava/lang/Throwable;)V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Disabling the Sentry breadcrumb facade directly",
                explanation = "Stubs the SDK method that every breadcrumb goes through, which also covers the three view call sites that reach the SDK without consulting the app's own crash-reporting switch.",
                smaliPath = "io/sentry/d4.smali",
                methodSignature = ".method public static a(Lio/sentry/Breadcrumb;)V",
                replacementBody = """.method public static a(Lio/sentry/Breadcrumb;)V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            )
        )
    )

    /**
     * Stops the deep-link initializer that feeds campaign attribution to AppsFlyer and the
     * ads SDK.
     */
    val DEEP_LINKS = PatchSet(
        id = "discord_native_deep_links",
        label = "Stop deep-link attribution",
        description = "Stops the deep-link initializer that feeds campaign attribution, so incoming links are no longer resolved against AppsFlyer and the ads SDK.",
        smaliPatches = listOf(
            SmaliPatch(
                title = "Disabling deep-link attribution at startup",
                explanation = "Skips the deep-link initialization that hands campaign data to the attribution SDKs.",
                smaliPath = "xa/d.smali",
                methodSignature = ".method public final init(Landroid/content/Context;)V",
                replacementBody = """.method public final init(Landroid/content/Context;)V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            )
        )
    )

    /**
     * Suppresses short-gap JavaScript stall reports and stops the per-touch event logger
     * from recording gestures.
     */
    val WATCHDOG = PatchSet(
        id = "discord_native_watchdog",
        label = "Stop the JavaScript watchdog",
        description = "Suppresses the JavaScript stall report unless the app is genuinely behind, and stops the per-touch event logger from recording gestures.",
        smaliPatches = listOf(
            SmaliPatch(
                title = "Skipping JavaScript stall reports for short gaps",
                explanation = "Only reports a JavaScript stall when the gap really is behind schedule, so brief pauses no longer trigger a full stall report.",
                smaliPath = "sf/e.smali",
                anchor = """    sub-long/2addr v0, p1

    .line 10""",
                replacement = """    sub-long/2addr v0, p1
    const-wide/16 v2, 0x0
    cmp-long v2, v0, v2
    if-lez v2, :cond_no_stall

    .line 10"""
            ),
            SmaliPatch(
                title = "Finishing the short-gap stall check",
                explanation = "Supplies the branch target that the short-gap check above jumps to, so the report is skipped cleanly rather than saved.",
                smaliPath = "sf/e.smali",
                anchor = """    invoke-direct {p0, p1, p3, p4}, Lcom/discord/js_watchdog/JSWatchdogManager;->saveStallReport(ILjava/lang/String;Z)V

    .line 29
    .line 30
    .line 31
    return-void""",
                replacement = """    invoke-direct {p0, p1, p3, p4}, Lcom/discord/js_watchdog/JSWatchdogManager;->saveStallReport(ILjava/lang/String;Z)V

    .line 29
    .line 30
    .line 31
    :cond_no_stall

    return-void"""
            ),
            SmaliPatch(
                title = "Disabling touch event logging",
                explanation = "Stops the native module from enabling per-touch logging, so tap and gesture history is not recorded.",
                smaliPath = "com/discord/analytics/touch/TouchEventAnalyticsModule.smali",
                methodSignature = ".method public enableTouchLogging()V",
                replacementBody = """.method public enableTouchLogging()V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            )
        )
    )

    /**
     * Stubs the native modules that buffer, snapshot and ship performance and telemetry
     * data.
     */
    val TELEMETRY_MODULES = PatchSet(
        id = "discord_native_telemetry_modules",
        label = "Stop native telemetry modules",
        description = "Stubs the native modules that buffer, snapshot and ship performance and telemetry data: the telemetry ring, metric monitors, jank tracking, TTI reporting and the push-notification log.",
        smaliPatches = listOf(
            SmaliPatch(
                title = "Stopping telemetry ring writes from JavaScript",
                explanation = "Discards ring-buffer entries coming from JavaScript so the native telemetry buffer stays empty.",
                smaliPath = "com/discord/crash_reporting/TelemetryRingModule.smali",
                methodSignature = ".method public append(Ljava/lang/String;DLjava/lang/String;Lcom/facebook/react/bridge/ReadableMap;Lcom/facebook/react/bridge/ReadableArray;)V",
                replacementBody = """.method public append(Ljava/lang/String;DLjava/lang/String;Lcom/facebook/react/bridge/ReadableMap;Lcom/facebook/react/bridge/ReadableArray;)V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Dropping telemetry ring clears",
                explanation = "Makes the clear call a no-op, which is harmless once nothing can be appended.",
                smaliPath = "com/discord/crash_reporting/TelemetryRingModule.smali",
                methodSignature = ".method public clear()V",
                replacementBody = """.method public clear()V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Ignoring metric subscriptions",
                explanation = "Refuses metric-topic subscriptions so the metric monitor never starts sampling.",
                smaliPath = "com/discord/metric_monitor/MetricMonitorModule.smali",
                methodSignature = ".method public addListener(Ljava/lang/String;)V",
                replacementBody = """.method public addListener(Ljava/lang/String;)V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Dropping metric unsubscriptions",
                explanation = "No-ops the removal call, which React Native invokes for every unmounted component and which has nothing left to do.",
                smaliPath = "com/discord/metric_monitor/MetricMonitorModule.smali",
                methodSignature = ".method public removeListeners(D)V",
                replacementBody = """.method public removeListeners(D)V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Stopping metric counters",
                explanation = "Discards every metric increment so no counters are accumulated or reported.",
                smaliPath = "n6/j.smali",
                methodSignature = ".method public final increment(Lcom/discord/metric_monitor/MetricEvent;)V",
                replacementBody = """.method public final increment(Lcom/discord/metric_monitor/MetricEvent;)V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Dropping jank session launch identifiers",
                explanation = "Discards the launch identifier used to correlate jank sessions with a launch, so reports cannot be tied back to it.",
                smaliPath = "com/discord/jank_stats/JankSessionModule.smali",
                methodSignature = ".method public hydrateLaunchId(Ljava/lang/String;)V",
                replacementBody = """.method public hydrateLaunchId(Ljava/lang/String;)V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Ignoring jank report acknowledgments",
                explanation = "No-ops the acknowledgment that would clear buffered jank reports, which are never collected anyway.",
                smaliPath = "com/discord/jank_stats/JankSessionModule.smali",
                methodSignature = ".method public ackReports(Lcom/facebook/react/bridge/ReadableArray;)V",
                replacementBody = """.method public ackReports(Lcom/facebook/react/bridge/ReadableArray;)V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Preventing jank tracking from starting",
                explanation = "Refuses to start the jank tracker, so no frame timing is collected while the app is in the foreground.",
                smaliPath = "com/discord/jank_stats/JankStatsModule.smali",
                methodSignature = ".method public startTracking()V",
                replacementBody = """.method public startTracking()V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Dropping jank tracking stops",
                explanation = "No-ops the matching stop call, which has nothing left to stop.",
                smaliPath = "com/discord/jank_stats/JankStatsModule.smali",
                methodSignature = ".method public stopTracking()V",
                replacementBody = """.method public stopTracking()V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Dropping time-to-interactive logging",
                explanation = "Discards the time-to-interactive marker so launch timing is not recorded.",
                smaliPath = "com/discord/tti_manager/TTIManagerModule.smali",
                methodSignature = ".method public trackTTILogged()V",
                replacementBody = """.method public trackTTILogged()V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Stopping on-device TTI records",
                explanation = "Prevents time-to-interactive measurements from being written to the device log.",
                smaliPath = "com/discord/tti_manager/TTIManagerModule.smali",
                methodSignature = ".method public logToDevice(Ljava/lang/String;)V",
                replacementBody = """.method public logToDevice(Ljava/lang/String;)V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Ignoring fully-drawn reports",
                explanation = "Stops the fully-drawn callback from being recorded and uploaded.",
                smaliPath = "com/discord/tti_manager/TTIManagerModule.smali",
                methodSignature = ".method public reportFullyDrawn()V",
                replacementBody = """.method public reportFullyDrawn()V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Dropping push notification log clears",
                explanation = "No-ops the push-notification log clear, leaving the buffer untouched because nothing writes to it.",
                smaliPath = "com/discord/push_notification_monitor/PushNotificationMonitorModule.smali",
                methodSignature = ".method public clearLogs()V",
                replacementBody = """.method public clearLogs()V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Returning empty telemetry snapshots",
                explanation = "Answers the JavaScript telemetry snapshot request with an empty map instead of the collected buffer.",
                smaliPath = "com/discord/crash_reporting/TelemetryRingModule.smali",
                methodSignature = ".method public snapshot(Lcom/facebook/react/bridge/ReadableArray;DLcom/facebook/react/bridge/ReadableMap;Ljava/lang/Double;Lcom/facebook/react/bridge/Promise;)V",
                replacementBody = """.method public snapshot(Lcom/facebook/react/bridge/ReadableArray;DLcom/facebook/react/bridge/ReadableMap;Ljava/lang/Double;Lcom/facebook/react/bridge/Promise;)V
    .locals 1
    new-instance v0, Lcom/facebook/react/bridge/WritableNativeMap;
    invoke-direct {v0}, Lcom/facebook/react/bridge/WritableNativeMap;-><init>()V
    invoke-interface {p5, v0}, Lcom/facebook/react/bridge/Promise;->resolve(Ljava/lang/Object;)V
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Returning no pending jank reports",
                explanation = "Answers the pending-report request with an empty array so no jank data reaches JavaScript.",
                smaliPath = "com/discord/jank_stats/JankSessionModule.smali",
                methodSignature = ".method public getPendingReports(Lcom/facebook/react/bridge/Promise;)V",
                replacementBody = """.method public getPendingReports(Lcom/facebook/react/bridge/Promise;)V
    .locals 1
    new-instance v0, Lcom/facebook/react/bridge/WritableNativeArray;
    invoke-direct {v0}, Lcom/facebook/react/bridge/WritableNativeArray;-><init>()V
    invoke-interface {p1, v0}, Lcom/facebook/react/bridge/Promise;->resolve(Ljava/lang/Object;)V
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Returning an empty jank report",
                explanation = "Answers the report request with an empty map instead of frame statistics.",
                smaliPath = "com/discord/jank_stats/JankStatsModule.smali",
                methodSignature = ".method public requestReport()Lcom/facebook/react/bridge/WritableMap;",
                replacementBody = """.method public requestReport()Lcom/facebook/react/bridge/WritableMap;
    .locals 1
    new-instance v0, Lcom/facebook/react/bridge/WritableNativeMap;
    invoke-direct {v0}, Lcom/facebook/react/bridge/WritableNativeMap;-><init>()V
    return-object v0
.end method"""
            ),
            SmaliPatch(
                title = "Returning no push notification logs",
                explanation = "Answers the log request with an empty array so no notification history is handed out.",
                smaliPath = "com/discord/push_notification_monitor/PushNotificationMonitorModule.smali",
                methodSignature = ".method public getPushNotificationLogs(Ljava/lang/String;Lcom/facebook/react/bridge/Promise;)V",
                replacementBody = """.method public getPushNotificationLogs(Ljava/lang/String;Lcom/facebook/react/bridge/Promise;)V
    .locals 1
    new-instance v0, Lcom/facebook/react/bridge/WritableNativeArray;
    invoke-direct {v0}, Lcom/facebook/react/bridge/WritableNativeArray;-><init>()V
    invoke-interface {p2, v0}, Lcom/facebook/react/bridge/Promise;->resolve(Ljava/lang/Object;)V
    return-void
.end method"""
            )
        )
    )

    /**
     * Stubs the debug-level logging entry points that walk the stack on every call. Error,
     * warning and info logging is kept for diagnostics.
     */
    val LOG_NOISE = PatchSet(
        id = "discord_native_log_noise",
        label = "Silence debug logging",
        description = "Stubs the debug-level logging entry points, which walk the stack on every call to derive a tag. Error, warning and info logging is kept for diagnostics.",
        smaliPatches = listOf(
            SmaliPatch(
                title = "Silencing the Java log facade (a)",
                explanation = "Removes the first of the app's three Java log entry points, so its tag and message never reach the log tree.",
                smaliPath = "yc/a.smali",
                methodSignature = ".method public static a(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)V",
                replacementBody = """.method public static a(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Silencing the Java log facade (b)",
                explanation = "Removes the second Java log entry point, which writes through the same tree.",
                smaliPath = "yc/a.smali",
                methodSignature = ".method public static b(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)V",
                replacementBody = """.method public static b(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Silencing the Java log facade (c)",
                explanation = "Removes the third Java log entry point. With all three gone the app's Timber facade is silent.",
                smaliPath = "yc/a.smali",
                methodSignature = ".method public static c(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)V",
                replacementBody = """.method public static c(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            )
        )
    )

    /**
     * Stops the permanent logcat capture process, the per-frame metrics listener and the
     * unused telemetry database.
     */
    val AUDIT = PatchSet(
        id = "discord_native_audit",
        label = "Stop logcat capture and frame metrics",
        description = "Stops the permanently running logcat child process, the per-frame metrics listener, and the telemetry database that is opened, schema-checked and scanned even though nothing writes to it.",
        smaliPatches = listOf(
            SmaliPatch(
                title = "Stopping the permanent logcat capture process",
                explanation = "Prevents the crash reporter from keeping a system logcat process alive for the whole app lifetime.",
                smaliPath = "wa/g.smali",
                methodSignature = ".method public final initSystemLogCapture(Landroid/content/Context;)V",
                replacementBody = """.method public final initSystemLogCapture(Landroid/content/Context;)V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Removing the per-frame metrics listener",
                explanation = "Stops the frame-metrics listener and its dedicated thread from running for the whole foreground session.",
                smaliPath = "h3/h.smali",
                methodSignature = ".method private final bindTo(Landroid/view/Window;)V",
                replacementBody = """.method private final bindTo(Landroid/view/Window;)V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Skipping the unused telemetry database",
                explanation = "Avoids opening and scanning a telemetry database that can no longer be written to.",
                smaliPath = "com/discord/crash_reporting/u.smali",
                methodSignature = ".method public final init(Landroid/content/Context;Lcom/discord/crash_reporting/TelemetryRingTypes${'$'}Budget;)V",
                replacementBody = """.method public final init(Landroid/content/Context;Lcom/discord/crash_reporting/TelemetryRingTypes${'$'}Budget;)V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            )
        )
    )

    /**
     * Shares one OkHttp disk cache between the React Native clients that rebuild it.
     */
    val OKHTTP_CACHE = PatchSet(
        id = "discord_native_okhttp_cache",
        label = "Share one network cache",
        description = "Memoizes the OkHttp disk cache that React Native rebuilds for every client. Three clients opening the same directory thrash each other's journal and re-download the same images; sharing one cache is OkHttp's supported configuration.",
        smaliPatches = listOf(
            SmaliPatch(
                title = "Reusing the existing disk cache",
                explanation = "Hands back the already-open disk cache instead of creating a second one over the same directory.",
                smaliPath = "nx/a.smali",
                anchor = """    :cond_c
    new-instance v1, Ljava/io/File;""",
                replacement = """    :cond_c
    # Patch: reuse one disk cache instead of opening three over the same dir.
    sget-object v1, Lcom/facebook/react/modules/network/OkHttpClientProvider;->httpCache:Lus/g;

    if-eqz v1, :new_cache

    iput-object v1, v0, Lokhttp3/OkHttpClient${'$'}Builder;->k:Lus/g;

    return-object v0

    :new_cache
    new-instance v1, Ljava/io/File;"""
            ),
            SmaliPatch(
                title = "Remembering the disk cache for reuse",
                explanation = "Stores the newly created disk cache so later clients reuse it instead of opening the directory again.",
                smaliPath = "nx/a.smali",
                anchor = """    iput-object p0, v0, Lokhttp3/OkHttpClient${'$'}Builder;->k:Lus/g;

    return-object v0
.end method""",
                replacement = """    sput-object p0, Lcom/facebook/react/modules/network/OkHttpClientProvider;->httpCache:Lus/g;

    iput-object p0, v0, Lokhttp3/OkHttpClient${'$'}Builder;->k:Lus/g;

    return-object v0
.end method"""
            ),
            SmaliPatch(
                title = "Adding the shared cache field",
                explanation = "Declares the static field that holds the shared disk cache.",
                smaliPath = "nx/a.smali",
                anchor = ".field private static client:Lokhttp3/OkHttpClient;",
                replacement = """.field private static client:Lokhttp3/OkHttpClient;

.field private static httpCache:Lus/g;"""
            )
        )
    )

    /**
     * Moves React Native's request interceptors onto OkHttp's application list, where
     * answering a request with a synthetic 204 does not kill the process.
     */
    val INTERCEPTORS = PatchSet(
        id = "discord_native_interceptors",
        label = "Stop the blocklist crashing the app",
        description = "Moves React Native's request interceptors onto OkHttp's application list, where answering a blocked request with a synthetic 204 is legal. On the network list OkHttp throws and the app dies on launch.",
        smaliPatches = listOf(
            SmaliPatch(
                title = "Registering interceptors on the application list",
                explanation = "Moves the interceptor registration to the list where short-circuiting a request without proceeding is allowed, which removes a fatal exception on the network thread.",
                smaliPath = "jc/l.smali",
                anchor = "Lokhttp3/OkHttpClient${'$'}Builder;->d:Ljava/util/ArrayList;",
                replacement = "Lokhttp3/OkHttpClient${'$'}Builder;->c:Ljava/util/ArrayList;"
            )
        )
    )

    /**
     * Cuts off the JavaScript resource-usage polls and the native counters behind them.
     */
    val JS_POLLS = PatchSet(
        id = "discord_native_js_polls",
        label = "Stop JavaScript diagnostic polls",
        description = "Cuts off the resource-usage polls the JavaScript side runs every few seconds, the native byte counters behind them, and the React Native bridge that forwards JavaScript breadcrumbs into the native crash reporter.",
        smaliPatches = listOf(
            SmaliPatch(
                title = "Leaving the network usage poll unanswered",
                explanation = "Never answers the JavaScript network-usage poll, so nothing is collected and the recurring NetStats log line disappears.",
                smaliPath = "com/discord/resource_usage/DeviceResourceUsageManagerModule.smali",
                methodSignature = ".method public final getNetworkUsage(Lcom/facebook/react/bridge/Callback;)V",
                replacementBody = """.method public final getNetworkUsage(Lcom/facebook/react/bridge/Callback;)V
    .locals 0
    # Patch: resource telemetry off - the JS poll never gets an answer, so
    # nothing is collected and "[NetStats]: Updating Network Info" is not logged.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Zeroing media player byte counts",
                explanation = "Reports zero bytes received by the media player so no media bandwidth statistics are exposed.",
                smaliPath = "og/b.smali",
                methodSignature = ".method public final getMediaPlayerBytesReceived()J",
                replacementBody = """.method public final getMediaPlayerBytesReceived()J
    .locals 2
    const-wide/16 v0, 0x0
    return-wide v0
.end method"""
            ),
            SmaliPatch(
                title = "Zeroing socket byte counts",
                explanation = "Reports zero bytes received on the socket so no network bandwidth statistics are exposed.",
                smaliPath = "og/b.smali",
                methodSignature = ".method public final getSocketBytesReceived()J",
                replacementBody = """.method public final getSocketBytesReceived()J
    .locals 2
    const-wide/16 v0, 0x0
    return-wide v0
.end method"""
            ),
            SmaliPatch(
                title = "Ignoring media player byte updates",
                explanation = "Discards the counter update that feeds the media player byte statistics.",
                smaliPath = "og/b.smali",
                methodSignature = ".method public final setMediaPlayerBytesReceived(J)V",
                replacementBody = """.method public final setMediaPlayerBytesReceived(J)V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Ignoring socket byte updates",
                explanation = "Discards the counter update that feeds the socket byte statistics.",
                smaliPath = "og/b.smali",
                methodSignature = ".method public final setSocketBytesReceived(J)V",
                replacementBody = """.method public final setSocketBytesReceived(J)V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Dropping JavaScript breadcrumbs at the bridge",
                explanation = "Discards breadcrumbs arriving over the React Native bridge so they never enter the native crash reporter's scope.",
                smaliPath = "io/sentry/react/RNSentryModuleImpl.smali",
                methodSignature = ".method public addBreadcrumb(Lcom/facebook/react/bridge/ReadableMap;)V",
                replacementBody = """.method public addBreadcrumb(Lcom/facebook/react/bridge/ReadableMap;)V
    .locals 0
    # Patch: telemetry off - drop JS breadcrumbs at the bridge.
    return-void
.end method"""
            )
        )
    )

    /**
     * Stubs the React Native systrace module, which only emitted debug trace events for
     * the JavaScript bridge.
     */
    val SYSTRACE = PatchSet(
        id = "discord_native_systrace",
        label = "Turn off systrace",
        description = "Stubs the React Native systrace module, which only ever emitted debug trace events for the JavaScript bridge and is not used by anything in the app.",
        smaliPatches = listOf(
            SmaliPatch(
                title = "Disabling systrace begin events",
                explanation = "Stops trace sections from being opened for the JavaScript bridge.",
                smaliPath = "com/discord/systrace/SystraceModule.smali",
                methodSignature = ".method public beginEvent(Ljava/lang/String;)V",
                replacementBody = """.method public beginEvent(Ljava/lang/String;)V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Disabling systrace end events",
                explanation = "Stops the matching trace sections from being closed.",
                smaliPath = "com/discord/systrace/SystraceModule.smali",
                methodSignature = ".method public endEvent()V",
                replacementBody = """.method public endEvent()V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Disabling systrace counters",
                explanation = "Stops counter values from being written into the system trace.",
                smaliPath = "com/discord/systrace/SystraceModule.smali",
                methodSignature = ".method public counterEvent(Ljava/lang/String;D)V",
                replacementBody = """.method public counterEvent(Ljava/lang/String;D)V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Disabling async systrace end events",
                explanation = "Stops asynchronous trace sections from being closed.",
                smaliPath = "com/discord/systrace/SystraceModule.smali",
                methodSignature = ".method public endAsyncEvent(Ljava/lang/String;D)V",
                replacementBody = """.method public endAsyncEvent(Ljava/lang/String;D)V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Disabling async systrace begin events",
                explanation = "Opens no asynchronous trace section; the callers only use the returned value as a token, so zero is returned.",
                smaliPath = "com/discord/systrace/SystraceModule.smali",
                methodSignature = ".method public beginAsyncEvent(Ljava/lang/String;)D",
                replacementBody = """.method public beginAsyncEvent(Ljava/lang/String;)D
    .locals 2
    # Patch: debug instrumentation off.
    const-wide/16 v0, 0x0
    return-wide v0
.end method"""
            )
        )
    )

    /**
     * Returns early from the crash-reporting entry points that still ran and logged while
     * reporting is off.
     */
    val CRASH_LOGCAT = PatchSet(
        id = "discord_native_crash_logcat",
        label = "Stop crash-report log spam",
        description = "Returns early from the crash-reporting entry points that still ran and logged under the SentryBreadcrumb tag even with reporting switched off, and drops the breadcrumb batches that arrive from the native layer over JNI.",
        smaliPatches = listOf(
            SmaliPatch(
                title = "Skipping exception captures when reporting is off",
                explanation = "Returns immediately from exception capture so nothing is logged or turned into an event while crash reporting is disabled.",
                smaliPath = "com/discord/crash_reporting/CrashReporting.smali",
                anchor = """    const-string/jumbo v0, "throwable"

    .line 2
    .line 3
    .line 4
    invoke-static {p1, v0}, Lkotlin/jvm/internal/Intrinsics;->checkNotNullParameter(Ljava/lang/Object;Ljava/lang/String;)V""",
                replacement = """    # Patch: crash reporting is disabled - do not log or build events.
    invoke-direct/range {p0 .. p0}, Lcom/discord/crash_reporting/CrashReporting;->isDisabled()Z

    move-result v0

    if-eqz v0, :cond_gate_skip

    return-void

    :cond_gate_skip

    const-string/jumbo v0, "throwable"

    .line 2
    .line 3
    .line 4
    invoke-static {p1, v0}, Lkotlin/jvm/internal/Intrinsics;->checkNotNullParameter(Ljava/lang/Object;Ljava/lang/String;)V"""
            ),
            SmaliPatch(
                title = "Skipping tagged message captures when reporting is off",
                explanation = "Returns immediately from message capture so the message is neither logged nor turned into an event.",
                smaliPath = "com/discord/crash_reporting/CrashReporting.smali",
                anchor = """    const-string/jumbo v0, "tag"

    invoke-static {p1, v0}, Lkotlin/jvm/internal/Intrinsics;->checkNotNullParameter(Ljava/lang/Object;Ljava/lang/String;)V

    const-string v0, "message"

    invoke-static {p2, v0}, Lkotlin/jvm/internal/Intrinsics;->checkNotNullParameter(Ljava/lang/Object;Ljava/lang/String;)V""",
                replacement = """    # Patch: crash reporting is disabled - do not log or build events.
    invoke-direct/range {p0 .. p0}, Lcom/discord/crash_reporting/CrashReporting;->isDisabled()Z

    move-result v0

    if-eqz v0, :cond_gate_skip

    return-void

    :cond_gate_skip

    const-string/jumbo v0, "tag"

    invoke-static {p1, v0}, Lkotlin/jvm/internal/Intrinsics;->checkNotNullParameter(Ljava/lang/Object;Ljava/lang/String;)V

    const-string v0, "message"

    invoke-static {p2, v0}, Lkotlin/jvm/internal/Intrinsics;->checkNotNullParameter(Ljava/lang/Object;Ljava/lang/String;)V"""
            ),
            SmaliPatch(
                title = "Skipping exception message captures when reporting is off",
                explanation = "Returns immediately from the exception-taking message capture so it is neither logged nor turned into an event.",
                smaliPath = "com/discord/crash_reporting/CrashReporting.smali",
                anchor = """    const-string/jumbo v0, "tag"

    invoke-static {p1, v0}, Lkotlin/jvm/internal/Intrinsics;->checkNotNullParameter(Ljava/lang/Object;Ljava/lang/String;)V

    const-string v0, "exception"

    invoke-static {p2, v0}, Lkotlin/jvm/internal/Intrinsics;->checkNotNullParameter(Ljava/lang/Object;Ljava/lang/String;)V""",
                replacement = """    # Patch: crash reporting is disabled - do not log or build events.
    invoke-direct/range {p0 .. p0}, Lcom/discord/crash_reporting/CrashReporting;->isDisabled()Z

    move-result v0

    if-eqz v0, :cond_gate_skip

    return-void

    :cond_gate_skip

    const-string/jumbo v0, "tag"

    invoke-static {p1, v0}, Lkotlin/jvm/internal/Intrinsics;->checkNotNullParameter(Ljava/lang/Object;Ljava/lang/String;)V

    const-string v0, "exception"

    invoke-static {p2, v0}, Lkotlin/jvm/internal/Intrinsics;->checkNotNullParameter(Ljava/lang/Object;Ljava/lang/String;)V"""
            ),
            SmaliPatch(
                title = "Skipping last-crash persistence when reporting is off",
                explanation = "Returns immediately from the routine that writes the last crash to disk, so nothing is serialized or stored.",
                smaliPath = "com/discord/crash_reporting/CrashReporting.smali",
                anchor = """    :try_start_0
    iget-object v0, p2, Lio/sentry/g4;->d:Lio/sentry/protocol/v;""",
                replacement = """    # Patch: crash reporting is disabled - do not log or build events.
    invoke-direct/range {p0 .. p0}, Lcom/discord/crash_reporting/CrashReporting;->isDisabled()Z

    move-result v0

    if-eqz v0, :cond_gate_skip

    return-void

    :cond_gate_skip

    :try_start_0
    iget-object v0, p2, Lio/sentry/g4;->d:Lio/sentry/protocol/v;"""
            ),
            SmaliPatch(
                title = "Skipping native breadcrumb batches when reporting is off",
                explanation = "Returns immediately from the breadcrumb batch handler the native library calls over JNI, so incoming batches are ignored.",
                smaliPath = "com/discord/crash_reporting/CrashReporting.smali",
                anchor = "    move-object/from16 v0, p1",
                replacement = """    # Patch: crash reporting is disabled - do not log or build events.
    invoke-direct/range {p0 .. p0}, Lcom/discord/crash_reporting/CrashReporting;->isDisabled()Z

    move-result v0

    if-eqz v0, :cond_gate_skip

    return-void

    :cond_gate_skip

    move-object/from16 v0, p1"""
            ),
            SmaliPatch(
                title = "Dropping native breadcrumb additions",
                explanation = "Discards single breadcrumbs the native library reports over JNI so they never reach the crash reporter.",
                smaliPath = "com/discord/crash_reporting/CrashReporting.smali",
                methodSignature = ".method public static final libdiscoreAddBreadcrumb(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
                replacementBody = """.method public static final libdiscoreAddBreadcrumb(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            )
        )
    )

    /**
     * Takes the two class loads the startup path pays for a feature that is already disabled.
     *
     * `MainApplication.performInitialization` loads `JankSessionRecorder` to call `init` - whose
     * body is already a stub - and calls `setPerScreenExperiment` on the same instance further
     * down the method. That is class load and verification of a large class, spent to invoke
     * nothing, and Discord's own TTI instrumentation times the window and prints it as
     * `JankSessionRecorder.init()`.
     *
     * `CrashReporting.<clinit>` is the other. It exists to build one list of seven exception
     * KClasses: each `const-class` makes ART resolve and load that class, and each
     * `getOrCreateKotlinClass` drags in the Kotlin reflection stack. The list only feeds
     * `isIgnorableNetworkException`, which decides whether a network exception is worth
     * *reporting* - and nothing is reported in this build. `MainApplication` touches the class
     * early to call `CrashReporting.init`, so the initializer runs on the startup path.
     *
     * The rewrite keeps the field non-null, because its one reader calls `contains` on it, and
     * keeps `INSTANCE`, so the call site is unaffected.
     */
    val STARTUP_CLASS_LOAD = PatchSet(
        id = "discord_native_startup_class_load",
        label = "Stop unused work at launch",
        description = "The launch path loads the jank recorder to call two methods that are " +
            "already stubs, and the crash reporter's static initializer loads seven exception " +
            "classes and the Kotlin reflection stack for a list that only decides whether a " +
            "network error is worth reporting - in a build that reports nothing. The crash " +
            "reporter is then set up on a thread the launch path waits for, reading the stored " +
            "OTA bundle for a label nothing reads. This removes all of it from startup, along " +
            "with the launch metric the removed recorder call was named by.",
        smaliPatches = listOf(
            SmaliPatch(
                title = "Not reading the jank recorder's instance",
                explanation = "Stops the launch path reading the jank recorder's instance, which " +
                    "is what loaded and verified the class, and calling its setup method, which " +
                    "is already a stub.",
                smaliPath = "com/discord/MainApplication.smali",
                anchor = "    sget-object v0, Lcom/discord/jank_stats/JankSessionRecorder;->INSTANCE:Lcom/discord/jank_stats/JankSessionRecorder;\n",
                replacement = "    # Patch: JankSessionRecorder unloaded\n"
            ),
            SmaliPatch(
                title = "Not initializing the jank recorder",
                explanation = "Stops the launch path calling the jank recorder's init, whose " +
                    "body is already a stub.",
                smaliPath = "com/discord/MainApplication.smali",
                anchor = "    invoke-virtual {v0, p0}, Lcom/discord/jank_stats/JankSessionRecorder;->init(Landroid/content/Context;)V\n",
                replacement = "    # Patch: JankSessionRecorder unloaded\n"
            ),
            SmaliPatch(
                title = "Not setting the jank recorder's experiment flag",
                explanation = "Stops the launch path calling setPerScreenExperiment on the jank " +
                    "recorder, whose only reader is the recorder's own dead code.",
                smaliPath = "com/discord/MainApplication.smali",
                anchor = "    invoke-virtual {v0, v6}, Lcom/discord/jank_stats/JankSessionRecorder;->setPerScreenExperiment(Z)V\n",
                replacement = "    # Patch: JankSessionRecorder unloaded\n"
            ),
            SmaliPatch(
                title = "Not setting the crash reporter up on a thread of its own",
                explanation = "The launch path hands the crash reporter's setup to a second " +
                    "thread and waits for it, and the setup reads the stored OTA bundle to " +
                    "label the report and installs the foreground-service logger behind a " +
                    "breadcrumb. Crash reporting is off at the source in this build, so the " +
                    "setup returns without doing any of it and the label is read only to be " +
                    "passed to a call that ignores it. The sequence becomes the one object its " +
                    "caller expects back.",
                smaliPath = "com/discord/MainApplication.smali",
                methodSignature = ".method private static final performInitialization" +
                    "${'$'}lambda${'$'}1(Lcom/discord/MainApplication;)" +
                    "Lcom/discord/crash_reporting/CrashReporting${'$'}CrashReportingReady;",
                replacementBody = """.method private static final performInitialization${'$'}lambda${'$'}1(Lcom/discord/MainApplication;)Lcom/discord/crash_reporting/CrashReporting${'$'}CrashReportingReady;
    .registers 2

    # Patch: this read the OTA bundle's release name out of shared preferences, handed it to
    # CrashReporting.init, recorded a launch metric and installed the foreground-service logger.
    # init returns immediately in this build because isDisabled() is forced true, so the release
    # name is never read; the logger's only callback writes a breadcrumb that addBreadcrumb drops
    # for the same reason; and the metric is Discord's label for work that no longer happens.
    #
    # All the caller does with the result is set tags on it, and setTag is gated on the same
    # isDisabled(), so a fresh object is what this has always effectively produced here.
    new-instance v0, Lcom/discord/crash_reporting/CrashReporting${'$'}CrashReportingReady;

    invoke-direct {v0}, Lcom/discord/crash_reporting/CrashReporting${'$'}CrashReportingReady;-><init>()V

    return-object v0
.end method"""
            ),
            SmaliPatch(
                title = "Removing the jank recorder's launch metric",
                explanation = "The launch path no longer initialises the recorder, so the " +
                    "launch metric named after it is a label for work that does not happen and " +
                    "the last thing in the application class that mentions the recorder. The " +
                    "metric is not a cost: what it measured is a timestamp taken after a call " +
                    "that is no longer made.",
                smaliPath = "com/discord/MainApplication.smali",
                anchor = """    const-string v5, "JankSessionRecorder.init()"

    .line 141
    .line 142
    const/16 v10, 0xe

    .line 143
    .line 144
    const/4 v11, 0x0

    .line 145
    const-wide/16 v6, 0x0

    .line 146
    .line 147
    const/4 v8, 0x0

    .line 148
    const/4 v9, 0x0

    .line 149
    invoke-static/range {v4 .. v11}, Lcom/discord/tti_manager/TTIMetrics;->record${'$'}default(Lcom/discord/tti_manager/TTIMetrics;Ljava/lang/String;JLjava/lang/String;ZILjava/lang/Object;)V
""",
                replacement = """    # Patch: the recorder is not initialised here any more, so this metric names
    # work that no longer happens. Its label was the last mention of the recorder
    # in this class.
"""
            )
        )
    )

    /**
     * Stops contact and installed-app fingerprinting and pins the React Native Fabric
     * flags to React Native's defaults.
     */
    val PRIVACY = PatchSet(
        id = "discord_native_privacy",
        label = "Stop contact and app fingerprinting",
        description = "Stops the address book from being read, contact photos from being fetched, installed third-party apps from being enumerated, and pins the React Native Fabric flags Discord gates on a server experiment to React Native's own defaults.",
        smaliPatches = listOf(
            SmaliPatch(
                title = "Returning no contacts",
                explanation = "Answers the contact sync request with an empty map so the address book is never read.",
                smaliPath = "ta/h.smali",
                methodSignature = ".method public static b(Lcom/facebook/react/bridge/ReactApplicationContext;)Ljava/util/LinkedHashMap;",
                replacementBody = """.method public static b(Lcom/facebook/react/bridge/ReactApplicationContext;)Ljava/util/LinkedHashMap;
    .locals 1
    new-instance v0, Ljava/util/LinkedHashMap;
    invoke-direct {v0}, Ljava/util/LinkedHashMap;-><init>()V
    return-object v0
.end method"""
            ),
            SmaliPatch(
                title = "Returning no contact photos",
                explanation = "Answers the contact photo request with null so no contact image is read, even if a contact id is supplied.",
                smaliPath = "ta/h.smali",
                methodSignature = ".method public static c(Lcom/facebook/react/bridge/ReactApplicationContext;Ljava/lang/String;)Ljava/lang/String;",
                replacementBody = """.method public static c(Lcom/facebook/react/bridge/ReactApplicationContext;Ljava/lang/String;)Ljava/lang/String;
    .locals 1
    const/4 v0, 0x0
    return-object v0
.end method"""
            ),
            SmaliPatch(
                title = "Returning no contact image URIs",
                explanation = "Answers the contact image URI lookup with null, so a caller that has a URI still gets no photo.",
                smaliPath = "ta/h.smali",
                methodSignature = ".method public static a(Landroid/net/Uri;Lcom/facebook/react/bridge/ReactApplicationContext;)Ljava/lang/String;",
                replacementBody = """.method public static a(Landroid/net/Uri;Lcom/facebook/react/bridge/ReactApplicationContext;)Ljava/lang/String;
    .locals 1
    const/4 v0, 0x0
    return-object v0
.end method"""
            ),
            SmaliPatch(
                title = "Refusing app-presence probes",
                explanation = "Answers the URL-scheme probe with false so the installed-app list cannot be fingerprinting.",
                smaliPath = "com/discord/intents/IntentsModule.smali",
                methodSignature = ".method public canOpenUrlScheme(Ljava/lang/String;)Z",
                replacementBody = """.method public canOpenUrlScheme(Ljava/lang/String;)Z
    .locals 1
    const/4 v0, 0x0
    return v0
.end method"""
            ),
            SmaliPatch(
                title = "Forcing accumulated raw-props updates on",
                explanation = "Sets the React Native flag to its own default so Discord's server experiment can no longer turn batched property updates off.",
                smaliPath = "com/discord/MainApplication.smali",
                methodSignature = ".method public enableAccumulatedUpdatesInRawPropsAndroid()Z",
                replacementBody = """.method public enableAccumulatedUpdatesInRawPropsAndroid()Z
    .locals 1
    const/4 v0, 0x1
    return v0
.end method"""
            ),
            SmaliPatch(
                title = "Forcing the pull model for property updates",
                explanation = "Sets the React Native flag to its own default so Discord's server experiment can no longer switch the push model back on.",
                smaliPath = "com/discord/MainApplication.smali",
                methodSignature = ".method public usePullModelOnAndroid()Z",
                replacementBody = """.method public usePullModelOnAndroid()Z
    .locals 1
    const/4 v0, 0x1
    return v0
.end method"""
            )
        )
    )

    /**
     * Stubs the resource-usage sampler, the performance tracing loop and the jank
     * aggregator.
     */
    val RESOURCE_MONITORS = PatchSet(
        id = "discord_native_resource_monitors",
        label = "Stop resource monitoring",
        description = "Stubs the resource-usage sampler, the performance tracing loop and the jank aggregator so no CPU, memory or frame statistics are gathered in the background.",
        smaliPatches = listOf(
            SmaliPatch(
                title = "Preventing resource sampling from starting",
                explanation = "Refuses to start the resource-usage sampler, so CPU and memory statistics are not collected.",
                smaliPath = "com/discord/resource_usage/DeviceResourceUsageManagerModule.smali",
                methodSignature = ".method public final start()V",
                replacementBody = """.method public final start()V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Preventing performance tracing from starting",
                explanation = "Refuses to start performance tracing so no transaction collection runs.",
                smaliPath = "com/discord/crash_reporting/q.smali",
                methodSignature = ".method public final start()V",
                replacementBody = """.method public final start()V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Skipping jank aggregator setup",
                explanation = "Skips the window hookup that would let the jank aggregator observe frame timing.",
                smaliPath = "h3/h.smali",
                methodSignature = ".method public final initialize(Landroid/view/Window;)V",
                replacementBody = """.method public final initialize(Landroid/view/Window;)V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Keeping jank tracking disabled",
                explanation = "Leaves jank tracking off so frame timings are not aggregated.",
                smaliPath = "h3/h.smali",
                methodSignature = ".method public final enableTracking()V",
                replacementBody = """.method public final enableTracking()V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Skipping the emoji font load",
                explanation = "Skips the font load AndroidX Startup posts half a second after the first activity resumes, which asks the Google Fonts provider for a font this build never draws with. The initializer still runs, so the singleton stays built and callers that read it keep working.",
                smaliPath = "e60/a.smali",
                methodSignature = ".method public final run()V",
                replacementBody = """.method public final run()V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Skipping frame metrics aggregator setup",
                explanation = "Stops the per-frame FrameMetrics listener from installing on the window.",
                smaliPath = "h3/h.smali",
                methodSignature = ".method public final enableTracking(Landroid/view/Window;)V",
                replacementBody = """.method public final enableTracking(Landroid/view/Window;)V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Preventing jank session recording",
                explanation = "Stops jank session recording and SharedPreferences writes from starting at application startup.",
                smaliPath = "rc/h.smali",
                methodSignature = ".method public final init(Landroid/content/Context;)V",
                replacementBody = """.method public final init(Landroid/content/Context;)V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            )
        )
    )

    /**
     * Removes the profiler dump and parse that runs when the JavaScript thread has
     * stalled, the Rust panic reporter and per-transaction tracing.
     */
    val CALL_PATH = PatchSet(
        id = "discord_native_call_path",
        label = "Cut work from the call path",
        description = "Removes the profiler dump and parse that runs precisely when the JavaScript thread has stalled, the JavaScript-callable profiler switch, the Rust panic reporter the native layer calls over JNI, per-transaction tracing, and the per-video-tile statistics timer.",
        smaliPatches = listOf(
            SmaliPatch(
                title = "Dropping the profiler dump on JavaScript stalls",
                explanation = "Returns nothing instead of starting the sampling profiler, writing a profile to disk, reading it back and parsing it at the moment the JavaScript thread has already stalled.",
                smaliPath = "sc/c.smali",
                methodSignature = ".method public final findSampleTrace(Ljava/io/File;)Ljava/lang/String;",
                replacementBody = """.method public final findSampleTrace(Ljava/io/File;)Ljava/lang/String;
    .locals 1
    # Patch: stub.
    const/4 v0, 0x0
    return-object v0
.end method"""
            ),
            SmaliPatch(
                title = "Refusing to start the JavaScript profiler",
                explanation = "Refuses the profiler switch the JavaScript side can call, which runs inline on the JavaScript thread.",
                smaliPath = "com/releaseprofiler/ReleaseProfilerModule.smali",
                methodSignature = ".method public final startProfiling()Z",
                replacementBody = """.method public final startProfiling()Z
    .locals 1
    # Patch: stub.
    const/4 v0, 0x0
    return v0
.end method"""
            ),
            SmaliPatch(
                title = "Returning no panic report identifier",
                explanation = "Answers the Rust panic reporter the native library calls over JNI with an empty string so nothing is sent.",
                smaliPath = "com/discord/crash_reporting/CrashReporting.smali",
                methodSignature = ".method public static final libdiscoreEmitRustPanic(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                replacementBody = """.method public static final libdiscoreEmitRustPanic(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    .locals 1
    # Patch: stub.
    const-string v0, ""
    return-object v0
.end method"""
            ),
            SmaliPatch(
                title = "Dropping performance transactions",
                explanation = "Discards performance transactions so nothing is traced or reported.",
                smaliPath = "com/discord/crash_reporting/q.smali",
                methodSignature = ".method private final startTransaction(Lcom/discord/crash_reporting/TraceTransaction;)V",
                replacementBody = """.method private final startTransaction(Lcom/discord/crash_reporting/TraceTransaction;)V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Stopping the per-video-tile statistics timer",
                explanation = "Stops the renderer's self-reposting statistics task, which built several strings every few seconds per video tile for a log line nothing ever reads.",
                smaliPath = "a50/c.smali",
                methodSignature = ".method private static final logStatisticsRunnable${'$'}lambda${'$'}0(Lcom/discord/media/engine/video/egl_renderer/EglRenderer;)V",
                replacementBody = """.method private static final logStatisticsRunnable${'$'}lambda${'$'}0(Lcom/discord/media/engine/video/egl_renderer/EglRenderer;)V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Stopping the statistics repost itself",
                explanation = "Returns immediately from the runnable that reposted itself every four seconds for the whole call, once per rendered video tile. Its statistics lambda is already a no-op, so the wakeup was the only work left.",
                smaliPath = "org/webrtc/EglRenderer${'$'}2.smali",
                methodSignature = ".method public run()V",
                replacementBody = """.method public run()V
    .locals 0
    # Patch: no-op; the statistics computation this reposted for is stubbed.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Disabling blocking OTA recovery after crashes",
                explanation = "Stops ActivityDelegate from doing an unbounded Future.get() on the UI thread checking for OTA updates after a crash.",
                smaliPath = "sf/b.smali",
                methodSignature = ".method private final needsBlockingOtaRecovery()Z",
                replacementBody = """.method private final needsBlockingOtaRecovery()Z
    .locals 1
    # Patch: stub.
    const/4 v0, 0x0
    return v0
.end method"""
            )
        )
    )

    /**
     * Fixes the video renderer's UI-thread stall, logs the media callback failures that
     * were dropped, and keeps the media engine alive after one failure.
     */
    val MEDIA = PatchSet(
        id = "discord_native_media",
        label = "Fix the media engine and voice calls",
        description = "Stops the video renderer from blocking the UI thread while it creates a " +
            "graphics context, surfaces media callback failures in logcat instead of dropping " +
            "them, builds both media coroutine scopes on a supervisor job and the app's " +
            "coroutine exception handler so one failure can neither disable every later media " +
            "call nor reach the process kill, registers a repeated connection id rather " +
            "than throwing on it, and disposes every connection on reset instead of letting " +
            "the map's iterator throw partway through teardown.",
        smaliPatches = listOf(
            SmaliPatch(
                title = "Stopping video tiles from freezing the interface",
                explanation = "Posts the graphics setup to the render thread instead of waiting for it, which removes the UI-thread stall that happens once per video tile.",
                smaliPath = "a50/c.smali",
                anchor = "    invoke-static {v4, v3}, Lorg/webrtc/ThreadUtils;->invokeAtFrontUninterruptibly(Landroid/os/Handler;Ljava/lang/Runnable;)V",
                replacement = """    # Patch: do not block the UI thread while EGL is created. The
    # render-thread Looper is FIFO, so the surface-creation runnable
    # posted immediately after this still runs once eglBase is set.
    invoke-virtual {v4, v3}, Landroid/os/Handler;->post(Ljava/lang/Runnable;)Z"""
            ),
            SmaliPatch(
                title = "Skipping EGL setup once the renderer is released",
                explanation = "Makes the deferred graphics setup return early when the renderer has already been released, so a teardown that jumps the render thread's queue cannot leave a graphics context that nothing will free.",
                smaliPath = "a50/c.smali",
                anchor = """.method private static final init${'$'}lambda${'$'}0${'$'}4(Lcom/discord/media/engine/video/egl_renderer/EglRenderer;J)V
    .registers 3""",
                replacement = """.method private static final init${'$'}lambda${'$'}0${'$'}4(Lcom/discord/media/engine/video/egl_renderer/EglRenderer;J)V
    .registers 4

    # Patch: released-guard. `release()` nulls renderThreadHandler and
    # jumps its cleanup to the FRONT of this queue, so a create still
    # pending further back would otherwise build an EGL context after
    # cleanup already ran - and nothing would ever free it.
    iget-object v0, p0, Lcom/discord/media/engine/video/egl_renderer/EglRenderer;->renderThreadHandler:Landroid/os/Handler;

    if-nez v0, :egl_create

    return-void

    :egl_create"""
            ),
            SmaliPatch(
                title = "Logging media callback failures (main path)",
                explanation = "Writes the swallowed media callback failure to logcat under the MediaEngineCB tag instead of dropping it silently.",
                smaliPath = "a2/d.smali",
                anchor = "    invoke-static {p1, p2, p4, p0, p3}, Lcom/discord/crash_reporting/CrashReporting;->captureException${'$'}default(Lcom/discord/crash_reporting/CrashReporting;Ljava/lang/Throwable;ZILjava/lang/Object;)V",
                replacement = """    # Patch: surface the swallowed failure instead of dropping it.
    sget-object p0, Lcom/discord/logging/Log;->INSTANCE:Lcom/discord/logging/Log;

    const-string p4, "MediaEngineCB"

    const-string p3, "media engine native callback threw"

    invoke-virtual {p0, p4, p3, p2}, Lcom/discord/logging/Log;->e(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)V"""
            ),
            SmaliPatch(
                title = "Logging media callback failures (second path)",
                explanation = "Writes another swallowed media callback failure to logcat under the MediaEngineCB tag.",
                smaliPath = "a2/d.smali",
                anchor = "    invoke-static {p1, p2, v0, p0, p3}, Lcom/discord/crash_reporting/CrashReporting;->captureException${'$'}default(Lcom/discord/crash_reporting/CrashReporting;Ljava/lang/Throwable;ZILjava/lang/Object;)V",
                replacement = """    # Patch: surface the swallowed failure instead of dropping it.
    sget-object p0, Lcom/discord/logging/Log;->INSTANCE:Lcom/discord/logging/Log;

    const-string v0, "MediaEngineCB"

    const-string p3, "media engine native callback threw"

    invoke-virtual {p0, v0, p3, p2}, Lcom/discord/logging/Log;->e(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)V"""
            ),
            SmaliPatch(
                title = "Logging media callback failures (third path)",
                explanation = "Writes another swallowed media callback failure to logcat under the MediaEngineCB tag.",
                smaliPath = "a2/d.smali",
                anchor = "    invoke-static {p1, v0, v2, p0, v1}, Lcom/discord/crash_reporting/CrashReporting;->captureException${'$'}default(Lcom/discord/crash_reporting/CrashReporting;Ljava/lang/Throwable;ZILjava/lang/Object;)V",
                replacement = """    # Patch: surface the swallowed failure instead of dropping it.
    sget-object p0, Lcom/discord/logging/Log;->INSTANCE:Lcom/discord/logging/Log;

    const-string v2, "MediaEngineCB"

    const-string v1, "media engine native callback threw"

    invoke-virtual {p0, v2, v1, v0}, Lcom/discord/logging/Log;->e(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)V"""
            ),
            SmaliPatch(
                title = "Logging media callback failures (fourth path)",
                explanation = "Writes another swallowed media callback failure to logcat under the MediaEngineCB tag.",
                smaliPath = "a2/d.smali",
                anchor = "    invoke-static {p1, p2, v1, p0, v0}, Lcom/discord/crash_reporting/CrashReporting;->captureException${'$'}default(Lcom/discord/crash_reporting/CrashReporting;Ljava/lang/Throwable;ZILjava/lang/Object;)V",
                replacement = """    # Patch: surface the swallowed failure instead of dropping it.
    sget-object p0, Lcom/discord/logging/Log;->INSTANCE:Lcom/discord/logging/Log;

    const-string v1, "MediaEngineCB"

    const-string v0, "media engine native callback threw"

    invoke-virtual {p0, v1, v0, p2}, Lcom/discord/logging/Log;->e(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)V"""
            ),
            SmaliPatch(
                title = "Logging media callback failures (native callback path)",
                explanation = "Writes the failure from the native media engine's callback wrapper to logcat under the MediaEngineCB tag.",
                smaliPath = "a2/d.smali",
                anchor = "    invoke-static {v0, v1, v2, p1, p2}, Lcom/discord/crash_reporting/CrashReporting;->captureException${'$'}default(Lcom/discord/crash_reporting/CrashReporting;Ljava/lang/Throwable;ZILjava/lang/Object;)V",
                replacement = """    # Patch: surface the swallowed failure instead of dropping it.
    sget-object p1, Lcom/discord/logging/Log;->INSTANCE:Lcom/discord/logging/Log;

    const-string v2, "MediaEngineCB"

    const-string p2, "media engine native callback threw"

    invoke-virtual {p1, v2, p2, v1}, Lcom/discord/logging/Log;->e(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)V"""
            ),
            SmaliPatch(
                title = "Keeping the media engine alive after one failure",
                explanation = "Builds the media engine's coroutine scope on a supervisor job " +
                    "and attaches the app's coroutine exception handler to it. The supervisor " +
                    "keeps a failed operation from cancelling the scope; the handler keeps the " +
                    "same failure from reaching the thread's uncaught-exception handler, which " +
                    "is what ends the call when a camera cannot start.",
                smaliPath = "com/discord/media/engine/MediaEngineModule.smali",
                anchor = """    invoke-direct {v2, v1}, Lsr/r0;-><init>(Ljava/util/concurrent/Executor;)V
""",
                replacement = """    invoke-direct {v2, v1}, Lsr/r0;-><init>(Ljava/util/concurrent/Executor;)V

    # Patch: supervisor scope - a plain Job cancels itself and every
    # child on the first throw, turning all ~66 appScope.launch sites
    # into no-ops for the rest of the process. Copied from Discord's own
    # MainImmediateScopeKt.MainImmediateScope().
    new-instance v1, Lsr/m1;

    invoke-direct {v1}, Lsr/y0;-><init>()V

    invoke-static {v1, v2}, Lkotlin/coroutines/e;->c(Lkotlin/coroutines/CoroutineContext${'$'}Element;Lkotlin/coroutines/CoroutineContext;)Lkotlin/coroutines/CoroutineContext;

    move-result-object v2

    # Patch: a supervisor job keeps the scope alive, but it does not stop
    # the exception from reaching the thread's uncaught-exception handler.
    # Add the app's own CoroutineExceptionHandler (tr/b) to the context so
    # a camera that cannot start is handled instead of killing the process.
    new-instance v1, Ltr/b;

    invoke-direct {v1}, Ltr/b;-><init>()V

    invoke-static {v1, v2}, Lkotlin/coroutines/e;->c(Lkotlin/coroutines/CoroutineContext${'$'}Element;Lkotlin/coroutines/CoroutineContext;)Lkotlin/coroutines/CoroutineContext;

    move-result-object v2

"""
            ),
            SmaliPatch(
                title = "Keeping the broadcast thumbnail scope alive after one failure",
                explanation = "Builds the media engine's own coroutine scope on a supervisor " +
                    "job and the app's exception handler. That scope has a single launch site, " +
                    "the screen-share thumbnail encoder, and it was built on a plain Job with " +
                    "no handler - the same shape that let a failed camera end the process.",
                smaliPath = "a2/d.smali",
                anchor = """    invoke-static {p2, p1}, Lkotlin/coroutines/e;->c(Lkotlin/coroutines/CoroutineContext${'$'}Element;Lkotlin/coroutines/CoroutineContext;)Lkotlin/coroutines/CoroutineContext;

    move-result-object p1
""",
                replacement = """    invoke-static {p2, p1}, Lkotlin/coroutines/e;->c(Lkotlin/coroutines/CoroutineContext${'$'}Element;Lkotlin/coroutines/CoroutineContext;)Lkotlin/coroutines/CoroutineContext;

    move-result-object p1

    # Patch: this constructor merges only the dispatcher and a CoroutineName,
    # so CoroutineScope() installs a plain Job and there is no exception
    # handler in the context. The scope's one launch site encodes a
    # screen-share thumbnail: Bitmap.compress and the React callback can both
    # throw, and an uncaught throw there reaches the thread's uncaught-
    # exception handler and kills the process. A supervisor keeps the scope
    # usable; the handler contains the throw. p2 is the dispatcher parameter,
    # whose last use is the merge above, so it is free as scratch.
    new-instance p2, Lsr/m1;

    invoke-direct {p2}, Lsr/y0;-><init>()V

    invoke-static {p2, p1}, Lkotlin/coroutines/e;->c(Lkotlin/coroutines/CoroutineContext${'$'}Element;Lkotlin/coroutines/CoroutineContext;)Lkotlin/coroutines/CoroutineContext;

    move-result-object p1

    new-instance p2, Ltr/b;

    invoke-direct {p2}, Ltr/b;-><init>()V

    invoke-static {p2, p1}, Lkotlin/coroutines/e;->c(Lkotlin/coroutines/CoroutineContext${'$'}Element;Lkotlin/coroutines/CoroutineContext;)Lkotlin/coroutines/CoroutineContext;

    move-result-object p1
"""
            ),
            SmaliPatch(
                title = "Logging uncaught coroutine exceptions",
                explanation = "Writes an uncaught coroutine exception to logcat under the " +
                    "MediaEngineCB tag before the app's handler runs, so a camera failure that " +
                    "is now contained leaves a trace instead of disappearing.",
                smaliPath = "tr/b.smali",
                anchor = """.method public final N(Ljava/lang/Throwable;Lkotlin/coroutines/CoroutineContext;)V
    .registers 5
""",
                replacement = """.method public final N(Ljava/lang/Throwable;Lkotlin/coroutines/CoroutineContext;)V
    .registers 8

    # Patch: this handler is now in both media engine scopes, where it turns
    # an uncaught coroutine exception (a camera that could not start, a
    # duplicate connection, a camera another app holds, a thumbnail that
    # cannot be encoded) into a handled failure instead of a process kill.
    # Log it first so it stays visible.
    sget-object v0, Lcom/discord/logging/Log;->INSTANCE:Lcom/discord/logging/Log;

    const-string v1, "MediaEngineCB"

    const-string v2, "uncaught coroutine exception; media engine kept running"

    invoke-virtual {v0, v1, v2, p1}, Lcom/discord/logging/Log;->e(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)V
"""
            ),
            SmaliPatch(
                title = "Registering a media connection instead of refusing to",
                explanation = "Registers the newest connection for an id rather than throwing " +
                    "when the id is already taken, so the camera path cannot fail with an " +
                    "exception JavaScript is never told about.",
                smaliPath = "cd/p.smali",
                methodSignature = ".method public final register(ILcom/discord/native/engine/NativeConnection;)V",
                replacementBody = """.method public final register(ILcom/discord/native/engine/NativeConnection;)V
    .registers 8
    .param p2    # Lcom/discord/native/engine/NativeConnection;
        .annotation build Lorg/jetbrains/annotations/NotNull;
        .end annotation
    .end param

    const-string v0, "connection"

    invoke-static {p2, v0}, Lkotlin/jvm/internal/Intrinsics;->checkNotNullParameter(Ljava/lang/Object;Ljava/lang/String;)V

    # Patch: this threw IllegalStateException("Check failed.") when the id was already
    # registered, and again when the connection itself already was. Its one caller is
    # MediaEngine.createVoiceConnection, which evaluates getEngine().createVoiceConnection(..)
    # first - so the throw discarded a live native connection nobody could ever dispose - and
    # runs inside the coroutine MediaEngineModule.createOwnStreamConnectionWithOptions launches
    # on appScope. That is the camera path. The throw is never reported to JavaScript, so its
    # callback is never invoked and its caller waits forever.
    #
    # The id now names the connection the caller just created, and the one it replaced is
    # disposed rather than leaked. A repeat registration of the very same object is left alone,
    # because disposing it would free the connection the map now holds.
    iget-object v0, p0, Lcom/discord/media/engine/MediaEngineNativeConnections;->connections:Ljava/util/Map;

    invoke-static {p1}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;

    move-result-object v1

    invoke-interface {v0, v1, p2}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;

    move-result-object v0

    check-cast v0, Lcom/discord/native/engine/NativeConnection;

    if-eqz v0, :registered

    if-eq v0, p2, :registered

    # Patch: log the replacement under the tag the media callbacks use, so a device
    # capture shows it happening instead of showing nothing at all.
    sget-object v1, Lcom/discord/logging/Log;->INSTANCE:Lcom/discord/logging/Log;

    const-string v2, "MediaEngineCB"

    const-string v3, "media engine connection id was already registered; the connection it named was disposed"

    const/4 v4, 0x0

    invoke-virtual {v1, v2, v3, v4}, Lcom/discord/logging/Log;->e(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)V

    invoke-virtual {v0}, Lcom/discord/native/engine/NativeConnection;->dispose()V

    :registered
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Disposing every media connection on reset",
                explanation = "Takes the connection keys before removing them. The stock loop " +
                    "removes from the map it is iterating, so two live connections make it " +
                    "throw ConcurrentModificationException and abandon the rest of reset() - " +
                    "leaving the remaining connections and the native engine undisposed.",
                smaliPath = "cd/p.smali",
                methodSignature = ".method public final removeAndDisposeAll()V",
                replacementBody = """.method public final removeAndDisposeAll()V
    .registers 5

    # Patch: the stock body iterates connections.keySet() and calls
    # removeAndDispose(id), which removes from that same map. With two live
    # connections the iterator's next() sees the changed modCount and throws
    # ConcurrentModificationException. reset() then abandons teardown before
    # nativeEngine.dispose(), so the remaining connections and the native
    # engine leak. Snapshot the keys, then remove each one.
    iget-object v0, p0, Lcom/discord/media/engine/MediaEngineNativeConnections;->connections:Ljava/util/Map;

    invoke-interface {v0}, Ljava/util/Map;->keySet()Ljava/util/Set;

    move-result-object v0

    invoke-interface {v0}, Ljava/util/Collection;->toArray()[Ljava/lang/Object;

    move-result-object v0

    array-length v1, v0

    const/4 v2, 0x0

    :goto_patch
    if-ge v2, v1, :cond_patch

    aget-object v3, v0, v2

    check-cast v3, Ljava/lang/Number;

    invoke-virtual {v3}, Ljava/lang/Number;->intValue()I

    move-result v3

    invoke-virtual {p0, v3}, Lcom/discord/media/engine/MediaEngineNativeConnections;->removeAndDispose(I)V

    add-int/lit8 v2, v2, 0x1

    goto :goto_patch

    :cond_patch
    iget-object v0, p0, Lcom/discord/media/engine/MediaEngineNativeConnections;->connections:Ljava/util/Map;

    invoke-interface {v0}, Ljava/util/Map;->isEmpty()Z

    move-result v0

    if-eqz v0, :cond_throw

    return-void

    :cond_throw
    new-instance v0, Ljava/lang/IllegalStateException;

    const-string v1, "Check failed."

    invoke-direct {v0, v1}, Ljava/lang/IllegalStateException;-><init>(Ljava/lang/String;)V

    throw v0
.end method"""
            )
        )
    )

    /**
     * Stops the camera building a frame-rate string every two seconds for a log nothing reads.
     *
     * `CameraVideoCapturer$CameraStatistics$1.run()` reposts itself for the whole camera session
     * and, on every tick, computes the frame rate, builds a string and hands it to
     * `org.webrtc.Logging.d` - which, with no loggable injected, falls through to
     * `java.util.logging` and builds a second string before writing a logcat line nobody reads.
     *
     * The tag constant that call uses is kept. A freeze-detection branch further down the same
     * method reuses `v1` as its tag without reloading it, so removing the constant along with the
     * log call would leave `v1` undefined on every path to that branch and ART would reject the
     * class at load - which is the moment the camera opens. The frozen-camera detection itself is
     * untouched.
     */
    val CAMERA_LOG = PatchSet(
        id = "discord_native_camera_log",
        label = "Stop the camera's frame-rate log",
        description = "The camera builds a frame-rate string every two seconds for the whole of a " +
            "video call and writes it to a log nothing reads. This drops the string build and " +
            "the log call, keeping the frozen-camera detection and the log tag it reuses.",
        smaliPatches = listOf(
            SmaliPatch(
                title = "Dropping the camera's frame-rate log",
                explanation = "Removes the string the camera builds every two seconds while it is " +
                    "open, and the log line it feeds, so a video call no longer allocates two " +
                    "strings and writes a logcat line nobody reads on every tick. The " +
                    "frozen-camera check and the log tag it reuses are kept.",
                smaliPath = "org/webrtc/CameraVideoCapturer\$CameraStatistics\$1.smali",
                anchor = """    new-instance v1, Ljava/lang/StringBuilder;

    .line 19
    .line 20
    const-string v2, "Camera fps: "

    .line 21
    .line 22
    invoke-direct {v1, v2}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    .line 23
    .line 24
    .line 25
    invoke-virtual {v1, v0}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;

    .line 26
    .line 27
    .line 28
    const-string v0, "."

    .line 29
    .line 30
    invoke-virtual {v1, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    .line 31
    .line 32
    .line 33
    invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    .line 34
    .line 35
    .line 36
    move-result-object v0

    .line 37
    const-string v1, "CameraStatistics"

    .line 38
    .line 39
    invoke-static {v1, v0}, Lorg/webrtc/Logging;->d(Ljava/lang/String;Ljava/lang/String;)V
""",
                replacement = """    # Patch: fps log removed - the string was built every 2 s per
    # camera and written to a sink nobody reads. The tag constant
    # below is KEPT: the freeze-detection branch further down reuses
    # v1 as its tag without reloading it, and deleting it fails ART
    # verification at class load.
    const-string v1, "CameraStatistics"
"""
            )
        )
    )

    /**
     * Keeps the microphone and camera foreground-service types while the app is
     * backgrounded and falls through to the next candidate when a type is not permitted.
     */
    val FOREGROUND_SERVICE = PatchSet(
        id = "discord_native_foreground_service",
        label = "Keep calls alive in the background",
        description = "Widens the per-type catch when promoting the foreground service so a refusal falls through to the next candidate instead of escaping as an uncaught exception, which is what makes a call drop while the notification still says connected. The chain itself is left as Discord built it: which service types the app is eligible for is a fact about the call, not about whether the window is in front.",
        smaliPatches = listOf(
            SmaliPatch(
                title = "Falling through to the next service type on any refusal",
                explanation = "Catches any refusal when promoting the foreground service, not just the security exception, so the failed type falls through to the next candidate instead of aborting the promotion.",
                smaliPath = "androidx/core/view/f1.smali",
                anchor = "    .catch Ljava/lang/SecurityException; {:try_start_7a .. :try_end_d8} :catch_d9",
                replacement = """    # Patch: any refusal falls through to the next type, not just SecurityException.
    .catch Ljava/lang/Exception; {:try_start_7a .. :try_end_d8} :catch_d9"""
            )
        )
    )

    /**
     * Pins Discord's server-driven performance experiments to the cheapest treatment.
     */
    val EXPERIMENTS = PatchSet(
        id = "discord_native_experiments",
        label = "Force cheaper performance settings",
        description = "Pins Discord's server-driven performance experiments to the cheapest treatment: the smaller Fresco cache, the Hermes memory target, the shared ChatMosaic pool, per-screen jank tracking switched off, the React Native mounting mode that the individual flag patches assume, the versioned UIManager constants cache, and chat preinflation deferred to the first drawn message.",
        smaliPatches = listOf(
            SmaliPatch(
                title = "Deferring chat list preinflation",
                explanation = "Pins the chat preinflation experiment to treatment 1, which fills the recycled-view pool when the first message is drawn instead of while the chat screen is being built.",
                smaliPath = "me/l.smali",
                methodSignature = ".method private static final chatPreinflationExperiment_delegate${'$'}lambda${'$'}0(Lcom/discord/native_experiments/NativeExperiments;)I",
                replacementBody = """.method private static final chatPreinflationExperiment_delegate${'$'}lambda${'$'}0(Lcom/discord/native_experiments/NativeExperiments;)I
    .locals 1
    # Patch: treatment 1 defers the adapter fill to the first drawn message.
    const/4 v0, 0x1
    return v0
.end method"""
            ),
            SmaliPatch(
                title = "Caching the UIManager constants",
                explanation = "Pins the UIManager constants cache to its enabled treatment, so React Native reads the view-manager constants from the versioned cache instead of rebuilding them at each launch.",
                smaliPath = "me/l.smali",
                methodSignature = ".method private static final androidUIManagerConstantsCacheExperiment_delegate${'$'}lambda${'$'}0(Lcom/discord/native_experiments/NativeExperiments;)I",
                replacementBody = """.method private static final androidUIManagerConstantsCacheExperiment_delegate${'$'}lambda${'$'}0(Lcom/discord/native_experiments/NativeExperiments;)I
    .locals 1
    # Patch: treatment 1 enables the versioned UIManager constants cache.
    const/4 v0, 0x1
    return v0
.end method"""
            ),
            SmaliPatch(
                title = "Using the smaller image cache experiment",
                explanation = "Pins the image cache experiment to its cheap treatment instead of following the server assignment.",
                smaliPath = "me/l.smali",
                methodSignature = ".method private static final frescoCacheExperimentSettings_delegate${'$'}lambda${'$'}0(Lcom/discord/native_experiments/NativeExperiments;)Lcom/discord/native_experiments/FrescoMemoryCacheExperimentSettings;",
                replacementBody = """.method private static final frescoCacheExperimentSettings_delegate${'$'}lambda${'$'}0(Lcom/discord/native_experiments/NativeExperiments;)Lcom/discord/native_experiments/FrescoMemoryCacheExperimentSettings;
    .locals 2
    sget-object v0, Lcom/discord/native_experiments/FrescoMemoryCacheExperimentBootstrap;->INSTANCE:Lcom/discord/native_experiments/FrescoMemoryCacheExperimentBootstrap;
    const/4 p0, 0x1
    invoke-virtual {v0, p0}, Lcom/discord/native_experiments/FrescoMemoryCacheExperimentBootstrap;->fromTreatmentId(I)Lcom/discord/native_experiments/FrescoMemoryCacheExperimentSettings;
    move-result-object p0
    return-object p0
.end method"""
            ),
            SmaliPatch(
                title = "Using the background JavaScript memory target",
                explanation = "Pins the JavaScript engine memory-target experiment to its background-oriented treatment.",
                smaliPath = "me/l.smali",
                methodSignature = ".method private static final hermesOccupancyTargetExperimentSettings_delegate${'$'}lambda${'$'}0(Lcom/discord/native_experiments/NativeExperiments;)Lcom/discord/native_experiments/HermesOccupancyTargetExperimentSettings;",
                replacementBody = """.method private static final hermesOccupancyTargetExperimentSettings_delegate${'$'}lambda${'$'}0(Lcom/discord/native_experiments/NativeExperiments;)Lcom/discord/native_experiments/HermesOccupancyTargetExperimentSettings;
    .locals 2
    sget-object v0, Lcom/discord/native_experiments/HermesOccupancyTargetExperimentBootstrap;->INSTANCE:Lcom/discord/native_experiments/HermesOccupancyTargetExperimentBootstrap;
    const/4 p0, 0x1
    invoke-virtual {v0, p0}, Lcom/discord/native_experiments/HermesOccupancyTargetExperimentBootstrap;->fromTreatmentId(I)Lcom/discord/native_experiments/HermesOccupancyTargetExperimentSettings;
    move-result-object p0
    return-object p0
.end method"""
            ),
            SmaliPatch(
                title = "Using the shared image pool for chat images",
                explanation = "Pins the chat image pool experiment to the shared-pool treatment.",
                smaliPath = "me/l.smali",
                methodSignature = ".method private static final chatMosaicSharedPoolExperimentSettings_delegate${'$'}lambda${'$'}0(Lcom/discord/native_experiments/NativeExperiments;)Lcom/discord/native_experiments/ChatMosaicSharedPoolExperimentSettings;",
                replacementBody = """.method private static final chatMosaicSharedPoolExperimentSettings_delegate${'$'}lambda${'$'}0(Lcom/discord/native_experiments/NativeExperiments;)Lcom/discord/native_experiments/ChatMosaicSharedPoolExperimentSettings;
    .locals 2
    sget-object v0, Lcom/discord/native_experiments/ChatMosaicSharedPoolExperimentBootstrap;->INSTANCE:Lcom/discord/native_experiments/ChatMosaicSharedPoolExperimentBootstrap;
    const/4 p0, 0x1
    invoke-virtual {v0, p0}, Lcom/discord/native_experiments/ChatMosaicSharedPoolExperimentBootstrap;->fromTreatmentId(I)Lcom/discord/native_experiments/ChatMosaicSharedPoolExperimentSettings;
    move-result-object p0
    return-object p0
.end method"""
            ),
            SmaliPatch(
                title = "Turning per-screen jank tracking off",
                explanation = "Pins the per-screen jank experiment to control, which is the treatment that switches per-screen tracking off.",
                smaliPath = "me/l.smali",
                methodSignature = ".method private static final jankPerScreenExperimentSettings_delegate${'$'}lambda${'$'}0(Lcom/discord/native_experiments/NativeExperiments;)Lcom/discord/native_experiments/JankPerScreenExperimentSettings;",
                replacementBody = """.method private static final jankPerScreenExperimentSettings_delegate${'$'}lambda${'$'}0(Lcom/discord/native_experiments/NativeExperiments;)Lcom/discord/native_experiments/JankPerScreenExperimentSettings;
    .locals 2
    sget-object v0, Lcom/discord/native_experiments/JankPerScreenExperimentBootstrap;->INSTANCE:Lcom/discord/native_experiments/JankPerScreenExperimentBootstrap;
    const/4 p0, 0x0
    invoke-virtual {v0, p0}, Lcom/discord/native_experiments/JankPerScreenExperimentBootstrap;->fromTreatmentId(I)Lcom/discord/native_experiments/JankPerScreenExperimentSettings;
    move-result-object p0
    return-object p0
.end method"""
            ),
            SmaliPatch(
                title = "Pinning the React Native mounting mode",
                explanation = "Pins the mounting-mode experiment to the value that matches the individually forced React Native flags, so the two cannot drift apart.",
                smaliPath = "me/l.smali",
                methodSignature = ".method private static final mountingModeExperiment_delegate${'$'}lambda${'$'}0(Lcom/discord/native_experiments/NativeExperiments;)I",
                replacementBody = """.method private static final mountingModeExperiment_delegate${'$'}lambda${'$'}0(Lcom/discord/native_experiments/NativeExperiments;)I
    .locals 1
    const/4 p0, 0x3
    return p0
.end method"""
            )
        )
    )

    /**
     * Keeps every web link out of Discord's own in-app tab.
     *
     * Discord's whole link surface is one React Native module. It reports the browser the user
     * picked to the JavaScript side, which then branches: the in-app choice renders a Chrome
     * Custom Tab inside Discord's own task, and the Chrome choice hands the URL to the system
     * browser with a plain ACTION_VIEW. Both paths ship already, so this set only decides which
     * one a link takes.
     *
     * The edits are deliberately redundant. The exported constants stop reading the stored
     * choice and fall back to Chrome, and the in-app entry point itself is re-pointed at the
     * external-browser call, so a stale stored choice or a caller that passes the in-app browser
     * explicitly still leaves the app.
     *
     * `openTrackedCustomTab` is left alone: its callback is `Function1<Boolean, Unit>` where the
     * external call wants `Function1<Unit, Unit>`, and the erased generics would throw when the
     * callback ran. `openPlayStoreInline` is left alone because it opens a Play Store deep link
     * rather than a web link.
     */
    val EXTERNAL_BROWSER = PatchSet(
        id = "discord_native_external_browser",
        label = "Open links in your browser",
        description = "Discord renders links in Chrome Custom Tabs inside its own task, under its " +
            "toolbar and with its session. This makes the browser setting report Chrome instead " +
            "of the in-app tab and exits from the module's in-app entry point, so a link to a " +
            "shop page, a quest page or anything else opens in the browser you chose.",
        smaliPatches = listOf(
            SmaliPatch(
                title = "Forgetting the stored in-app browser choice",
                explanation = "Reads a cache key nothing writes when the app asks which browser was " +
                    "chosen, so a device that had already settled on the in-app tab goes back to the " +
                    "default instead of that stored choice.",
                smaliPath = "com/discord/browser_manager/BrowserManagerModule.smali",
                anchor = "    const-string v2, \"SELECTED_BROWSER\"",
                replacement = "    const-string v2, \"SELECTED_BROWSER_IGNORED\""
            ),
            SmaliPatch(
                title = "Making the browser the default choice",
                explanation = "Changes the default the browser setting falls back to from the in-app " +
                    "tab to Chrome, so a device with Custom Tabs available reports the browser as the " +
                    "chosen one.",
                smaliPath = "com/discord/browser_manager/BrowserManagerModule.smali",
                anchor = """    :cond_28
    if-eqz v0, :cond_2c

    .line 42
    .line 43
    const/4 v1, 0x1""",
                replacement = """    :cond_28
    if-eqz v0, :cond_2c

    .line 42
    .line 43
    const/4 v1, 0x2"""
            ),
            SmaliPatch(
                title = "Opening links from the in-app entry point externally",
                explanation = "Points the module's openInAppURL at the external-browser call, so a " +
                    "link still opens in your browser when something else selects the in-app tab - a " +
                    "stale setting, or a deep link that passes its own choice.",
                smaliPath = "com/discord/browser_manager/BrowserManagerModule.smali",
                anchor = """    invoke-virtual {v0, v1, p1, v2}, Lcom/discord/browser_manager/BrowserManager;->tryOpenUrlWithCustomTabs(Landroid/content/Context;Ljava/lang/String;Lkotlin/jvm/functions/Function1;)V""",
                replacement = """    invoke-virtual {v0, v1, p1, v2}, Lcom/discord/browser_manager/BrowserManager;->tryOpenUrlExternally(Landroid/content/Context;Ljava/lang/String;Lkotlin/jvm/functions/Function1;)V"""
            )
        )
    )

    /**
     * Takes the avatar fetch off the incoming-call screen's blocking path.
     *
     * `IncomingCallActivity` runs three nested `runBlocking` calls on the main thread, and the
     * image fetch behind them builds the Fresco pipeline and then suspends on a network
     * round-trip, so the interface is parked until the caller's picture downloads. `fetchImage`
     * is the suspend function those calls wait on; returning null completes them at once.
     *
     * The callers already handle null: the result is cast to `Bitmap`, which a null passes, and
     * handed to `ImageView.setImageBitmap`, which clears the view. Only the picture is dropped;
     * the caller's name still shows. Fresco itself is not lost, because the normal startup path
     * builds it as well.
     */
    val INCOMING_CALL = PatchSet(
        id = "discord_native_incoming_call",
        label = "Stop a call freezing the screen",
        description = "The incoming-call screen waits on the main thread for the caller's avatar " +
            "to download, which freezes the interface while a call arrives. This drops the image " +
            "fetch, so the screen comes up at once with the caller's name and no picture.",
        smaliPatches = listOf(
            SmaliPatch(
                title = "Dropping the caller's avatar on the incoming-call screen",
                explanation = "Returns no avatar instead of fetching one, so the incoming-call " +
                    "screen no longer waits on a Fresco initialisation and a network round-trip on " +
                    "the main thread. The caller's name still shows; only the picture is missing.",
                smaliPath = "com/discord/notifications/renderer/IncomingCallActivity.smali",
                methodSignature = ".method private final fetchImage(Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;",
                replacementBody = """.method private final fetchImage(Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;
    .locals 0

    # Patch: no network wait. Returning null without suspending is a valid
    # completion; the caller casts it to Bitmap (null casts fine) and clears
    # the ImageView with it. Removes the Fresco init + network round-trip that
    # `runBlocking` on the main thread was waiting for.
    const/4 p1, 0x0

    return-object p1
.end method"""
            )
        )
    )

    /** Every set this object defines. */
    val ALL = listOf(
        SENTRY,
        DEEP_LINKS,
        WATCHDOG,
        TELEMETRY_MODULES,
        LOG_NOISE,
        AUDIT,
        OKHTTP_CACHE,
        INTERCEPTORS,
        JS_POLLS,
        SYSTRACE,
        CRASH_LOGCAT,
        STARTUP_CLASS_LOAD,
        PRIVACY,
        RESOURCE_MONITORS,
        CALL_PATH,
        MEDIA,
        CAMERA_LOG,
        FOREGROUND_SERVICE,
        EXPERIMENTS,
        EXTERNAL_BROWSER,
        INCOMING_CALL
    )
}
