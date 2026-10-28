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
     * Blanks the hard-coded Sentry DSNs, stubs the breadcrumb facade and stops libdiscore from
     * installing its own native Sentry reporter and crash handler, so crash and breadcrumb
     * reporting has no destination.
     */
    val SENTRY = PatchSet(
        id = "discord_native_sentry",
        label = "Crash reports blocked",
        description = "Empties the three hard-coded Sentry DSN constants in every class that carries them, stubs the app's breadcrumb builder and the SDK breadcrumb facade, stops libdiscore installing its native Sentry reporter and crash handler, and drops soft exceptions, so crash and breadcrumb reporting has no destination even on code paths that bypass the app-level switch.",
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
                title = "Stopping breadcrumb collection",
                explanation = "Replaces the app's breadcrumb builder with a no-op, so no event is assembled or handed to the Sentry SDK, including the native bridge's batches.",
                smaliPath = "com/discord/crash_reporting/CrashReporting.smali",
                methodSignature = ".method public final a(Ljava/lang/String;Ljava/util/Map;Ljava/lang/String;Lcom/discord/crash_reporting/h;Z)V",
                replacementBody = """.method public final a(Ljava/lang/String;Ljava/util/Map;Ljava/lang/String;Lcom/discord/crash_reporting/h;Z)V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Dropping React Native soft exceptions",
                explanation = "Discards soft-exception reports at the React Native module so non-fatal errors are not recorded or uploaded.",
                smaliPath = "com/discord/crash_reporting/l.smali",
                methodSignature = ".method public final logSoftException(Ljava/lang/String;Ljava/lang/Throwable;)V",
                replacementBody = """.method public final logSoftException(Ljava/lang/String;Ljava/lang/Throwable;)V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Disabling the Sentry breadcrumb facade directly",
                explanation = "Stubs the SDK method that every breadcrumb goes through, which also covers the three view call sites that reach the SDK without consulting the app's own crash-reporting switch.",
                smaliPath = "io/sentry/d4.smali",
                methodSignature = ".method public static a(Lio/sentry/e;)V",
                replacementBody = """.method public static a(Lio/sentry/e;)V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Stopping libdiscore's native Sentry reporter",
                explanation = "Replaces the libdiscore module's reporter-install call with a no-op, so the native library never attaches its own Sentry reporter behind the blanked Java DSNs.",
                smaliPath = "com/discord/libdiscore/LibdiscoreModuleClass${'$'}LibdiscoreModule.smali",
                methodSignature = ".method public final c()V",
                replacementBody = """.method public final c()V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Not installing libdiscore's native crash handler",
                explanation = "Returns from the companion's initNative call without invoking the JNI method, so libdiscore installs neither its native crash handler nor the breadcrumb collection behind it.",
                smaliPath = "com/discord/crash_reporting/NativeCrashReporting${'$'}Companion.smali",
                methodSignature = ".method public final initNative(Z)V",
                replacementBody = """.method public final initNative(Z)V
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
        label = "No deep-link tracking",
        description = "Stops the deep-link initializer that feeds campaign attribution, so incoming links are no longer resolved against AppsFlyer and the ads SDK.",
        smaliPatches = listOf(
            SmaliPatch(
                title = "Disabling deep-link attribution at startup",
                explanation = "Removes the AppsFlyer subscription, init and start calls from the startup task, so the attribution SDK is never initialized and incoming links are not resolved against it.",
                smaliPath = "d6/a.smali",
                anchor = """    :cond_54
    new-instance v4, Lxa/c;

    .line 86
    .line 87
    invoke-direct {v4}, Ljava/lang/Object;-><init>()V

    .line 88
    .line 89
    .line 90
    invoke-virtual {v0, v4}, Lcom/appsflyer/AppsFlyerLib;->subscribeForDeepLink(Lcom/appsflyer/deeplink/DeepLinkListener;)V

    .line 91
    .line 92
    .line 93
    const-string v4, "GtHaVQcNmvxMT8zPNShJWJ"

    .line 94
    .line 95
    invoke-virtual {v0, v4, v1, v3}, Lcom/appsflyer/AppsFlyerLib;->init(Ljava/lang/String;Lcom/appsflyer/AppsFlyerConversionListener;Landroid/content/Context;)Lcom/appsflyer/AppsFlyerLib;

    .line 96
    .line 97
    .line 98
    invoke-virtual {v0, v3}, Lcom/appsflyer/AppsFlyerLib;->start(Landroid/content/Context;)V""",
                replacement = """    :cond_54
    # Patch: no AppsFlyer deep-link subscription, init or start."""
            )
        )
    )

    /**
     * Stops the per-touch event logger from recording gestures.
     *
     * 353.2.0 already gates its JavaScript stall report on a 1000 ms threshold after subtracting
     * the same 500 ms grace the old edit added, so there is no remaining short-gap report to
     * suppress; only the touch logger still needs the change.
     */
    val WATCHDOG = PatchSet(
        id = "discord_native_watchdog",
        label = "No JavaScript watchdog",
        description = "Stops the per-touch event logger from recording gestures. This build already suppresses short JavaScript stalls itself.",
        smaliPatches = listOf(
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
        label = "No telemetry modules",
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
                title = "Returning an empty push notification log",
                explanation = "Answers the log request with an empty log serialized as JSON, the " +
                    "same shape the real method returns. The JavaScript wrapper runs JSON.parse on " +
                    "the result, so resolving an array here would throw instead of returning no " +
                    "history.",
                smaliPath = "com/discord/push_notification_monitor/PushNotificationMonitorModule.smali",
                methodSignature = ".method public getPushNotificationLogs(Ljava/lang/String;Lcom/facebook/react/bridge/Promise;)V",
                replacementBody = """.method public getPushNotificationLogs(Ljava/lang/String;Lcom/facebook/react/bridge/Promise;)V
    .locals 1
    const-string v0, "{\"pushNotifications\":[]}"
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
        label = "Quieter debug logging",
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
        label = "No background log capture",
        description = "Stops the permanently running logcat child process, the per-frame metrics listener, and the telemetry database that is opened, schema-checked and scanned even though nothing writes to it.",
        smaliPatches = listOf(
            SmaliPatch(
                title = "Stopping the permanent logcat capture process",
                explanation = "Returns zero from the system-log reader, so the crash reporter never starts a logcat process or keeps one alive for the app's lifetime.",
                smaliPath = "wa/c.smali",
                methodSignature = ".method public s()J",
                replacementBody = """.method public s()J
    .locals 2
    # Patch: do not start or read a logcat process.
    const-wide/16 v0, 0x0
    return-wide v0
.end method"""
            ),
            SmaliPatch(
                title = "Removing the per-frame metrics listener",
                explanation = "Replaces the frame-metrics listener with a no-op, so the Window callback does no per-frame work and the aggregator's updates are never recorded.",
                smaliPath = "h3/g.smali",
                methodSignature = ".method public final onFrameMetricsAvailable(Landroid/view/Window;Landroid/view/FrameMetrics;I)V",
                replacementBody = """.method public final onFrameMetricsAvailable(Landroid/view/Window;Landroid/view/FrameMetrics;I)V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Skipping the unused telemetry database",
                explanation = "Replaces the telemetry ring's initializer with a no-op, so its SQLite database is never opened or scanned.",
                smaliPath = "com/discord/crash_reporting/u.smali",
                methodSignature = ".method public static e(Landroid/content/ContextWrapper;Lcom/discord/crash_reporting/a0;)V",
                replacementBody = """.method public static e(Landroid/content/ContextWrapper;Lcom/discord/crash_reporting/a0;)V
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
        label = "One shared network cache",
        description = "Memoizes the OkHttp disk cache that React Native rebuilds for every client. Three clients opening the same directory thrash each other's journal and re-download the same images; sharing one cache is OkHttp's supported configuration.",
        smaliPatches = listOf(
            SmaliPatch(
                title = "Adding the shared cache field",
                explanation = "Declares the static field that holds the one disk cache this process opens.",
                smaliPath = "nx/a.smali",
                anchor = """.class public abstract Lnx/a;
.super Ljava/lang/Object;
.source "SourceFile"""",
                replacement = """.class public abstract Lnx/a;
.super Ljava/lang/Object;
.source "SourceFile"

# Patch: one disk cache shared by every React Native OkHttp client.
.field public static f:Lm50/f;"""
            ),
            SmaliPatch(
                title = "Reusing the existing disk cache",
                explanation = "Opens the http-cache directory once and stores the Cache in a static field, so later clients reuse the open journal instead of creating a second cache over the same files.",
                smaliPath = "nx/a.smali",
                anchor = """    new-instance p0, Lm50/f;

    .line 38
    .line 39
    const/high16 v2, 0xa00000

    .line 40
    .line 41
    int-to-long v2, v2

    .line 42
    invoke-direct {p0, v1, v2, v3}, Lm50/f;-><init>(Ljava/io/File;J)V

    .line 43
    .line 44
    .line 45
    iput-object p0, v0, Lm50/b0;->k:Lm50/f;""",
                replacement = """    sget-object p0, Lnx/a;->f:Lm50/f;

    if-eqz p0, :cache_ready

    new-instance p0, Lm50/f;

    const/high16 v2, 0xa00000

    int-to-long v2, v2

    invoke-direct {p0, v1, v2, v3}, Lm50/f;-><init>(Ljava/io/File;J)V

    sput-object p0, Lnx/a;->f:Lm50/f;

    :cache_ready
    iput-object p0, v0, Lm50/b0;->k:Lm50/f;"""
            )
        )
    )

    /**
     * Moves the resource-usage interceptor Discord installs on OkHttp's network list onto the
     * application list, where returning a response without calling proceed is legal.
     */
    val INTERCEPTORS = PatchSet(
        id = "discord_native_interceptors",
        label = "Blocklist safety",
        description = "Moves the resource-usage interceptor from OkHttp's network list to the application list on both clients that register it. A network interceptor must call proceed() exactly once, so the network blocklist's synthetic 204 would otherwise throw on the network thread and kill the app.",
        smaliPatches = listOf(
            SmaliPatch(
                title = "Registering the main client's interceptor on the application list",
                explanation = "Moves the request-stat interceptor from the network list to the application list, where the blocklist may answer a request without proceeding.",
                smaliPath = "jc/l.smali",
                anchor = "    iget-object v5, v2, Lm50/b0;->d:Ljava/util/ArrayList;",
                replacement = "    iget-object v5, v2, Lm50/b0;->c:Ljava/util/ArrayList;"
            ),
            SmaliPatch(
                title = "Registering the second client's interceptor on the application list",
                explanation = "The same move for the second client that installs the same resource-usage interceptor, so a blocked request through it also short-circuits legally.",
                smaliPath = "j60/e.smali",
                anchor = "    iget-object p1, p1, Lm50/b0;->d:Ljava/util/ArrayList;",
                replacement = "    iget-object p1, p1, Lm50/b0;->c:Ljava/util/ArrayList;"
            )
        )
    )

    /**
     * Cuts off the JavaScript resource-usage polls and the native counters behind them.
     *
     * The resource-usage interceptor these counters belong to is registered on OkHttp's network
     * list in this build; [INTERCEPTORS] moves it to the application list so the blocklist can
     * answer a request without proceeding.
     */
    val JS_POLLS = PatchSet(
        id = "discord_native_js_polls",
        label = "No diagnostic polling",
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
                explanation = "Reads zero instead of the recorded media-player byte count when the resource-usage map is built, so the JavaScript poll is answered with a zero.",
                smaliPath = "og/b.smali",
                anchor = "    sget-wide v18, Log/d;->b:J",
                replacement = "    const-wide/16 v18, 0x0"
            ),
            SmaliPatch(
                title = "Zeroing socket byte counts",
                explanation = "Reads zero instead of the recorded socket byte count when the resource-usage map is built, so the JavaScript poll is answered with a zero.",
                smaliPath = "og/b.smali",
                anchor = "    sget-wide v0, Log/d;->a:J",
                replacement = "    const-wide/16 v0, 0x0"
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
        label = "No systrace",
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
        label = "No crash-log spam",
        description = "Returns early from the crash-reporting entry points that still ran and logged under the SentryBreadcrumb tag even with reporting switched off, and drops the breadcrumb batches that arrive from the native layer over JNI.",
        smaliPatches = listOf(
            SmaliPatch(
                title = "Skipping exception captures when reporting is off",
                explanation = "Replaces the exception-capture entry point with a no-op, so a throwable is neither logged under SentryBreadcrumb nor turned into a Sentry event.",
                smaliPath = "com/discord/crash_reporting/CrashReporting.smali",
                methodSignature = ".method public static c(Ljava/lang/Throwable;Z)V",
                replacementBody = """.method public static c(Ljava/lang/Throwable;Z)V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Skipping tagged message captures when reporting is off",
                explanation = "Replaces the tagged-message entry point with a no-op, so the message is neither logged nor turned into an event.",
                smaliPath = "com/discord/crash_reporting/CrashReporting.smali",
                methodSignature = ".method public static e(Ljava/lang/String;Ljava/lang/String;Lcom/discord/crash_reporting/j;)V",
                replacementBody = """.method public static e(Ljava/lang/String;Ljava/lang/String;Lcom/discord/crash_reporting/j;)V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Skipping exception message captures when reporting is off",
                explanation = "Replaces the breadcrumb-value writer with a no-op, so a message and its value never reach the Sentry scope.",
                smaliPath = "com/discord/crash_reporting/CrashReporting.smali",
                methodSignature = ".method public static g(Lcom/discord/crash_reporting/i;Ljava/lang/String;Ljava/lang/String;)V",
                replacementBody = """.method public static g(Lcom/discord/crash_reporting/i;Ljava/lang/String;Ljava/lang/String;)V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Skipping last-crash persistence when reporting is off",
                explanation = "Replaces the routine that writes the last tombstone report to disk with a no-op, so nothing is serialized or stored.",
                smaliPath = "com/discord/crash_reporting/CrashReporting.smali",
                methodSignature = ".method public static h(JLjava/lang/String;)V",
                replacementBody = """.method public static h(JLjava/lang/String;)V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Skipping native breadcrumb batches when reporting is off",
                explanation = "Replaces the breadcrumb batch handler the native library calls over JNI with a no-op, so incoming batches are ignored.",
                smaliPath = "com/discord/crash_reporting/CrashReporting.smali",
                methodSignature = ".method public final addBreadcrumbBatchBinary(Ljava/nio/ByteBuffer;)V",
                replacementBody = """.method public final addBreadcrumbBatchBinary(Ljava/nio/ByteBuffer;)V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
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
     * Takes the jank recorder's startup work out of the launch path.
     *
     * 353.2.0's `MainApplication` still runs the recorder's one-time setup, hands it an
     * executor and feeds it Discord's per-screen experiment before the first frame. The
     * recorder is only a diagnostics source and the per-screen experiment is the treatment
     * this patch set wants off, so the setup branch is skipped, the recording runnable becomes
     * a no-op, the experiment flag is forced false, and the launch metric named after the
     * removed call is dropped.
     */
    val STARTUP_CLASS_LOAD = PatchSet(
        id = "discord_native_startup_class_load",
        label = "Less startup work",
        description = "Skips the jank recorder's one-time setup and its background runnable, " +
            "forces per-screen jank tracking off, and removes the launch metric that named the " +
            "removed recorder call.",
        smaliPatches = listOf(
            SmaliPatch(
                title = "Not reading the jank recorder's instance",
                explanation = "Skips the launch path's one-time jank-recorder setup branch, so " +
                    "the recorder is never initialized and its executor never starts.",
                smaliPath = "com/discord/MainApplication.smali",
                anchor = """    sget-boolean v0, Lrc/h;->c:Z

    .line 178
    .line 179
    if-eqz v0, :cond_b5""",
                replacement = """    # Patch: skip the jank recorder's one-time setup.
    const/4 v0, 0x1

    goto :goto_cf"""
            ),
            SmaliPatch(
                title = "Not initializing the jank recorder",
                explanation = "Replaces the jank session recorder's background setup with a " +
                    "no-op, so it neither scans the stored jank sessions nor starts recording.",
                smaliPath = "rc/f.smali",
                methodSignature = ".method public final run()V",
                replacementBody = """.method public final run()V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Not setting the jank recorder's experiment flag",
                explanation = "Forces the per-screen jank flag to false before it is stored, " +
                    "so the recorder cannot switch per-screen tracking on.",
                smaliPath = "com/discord/MainApplication.smali",
                anchor = "    sput-boolean v5, Lrc/h;->d:Z",
                replacement = """    # Patch: per-screen jank tracking stays off.
    const/4 v5, 0x0

    sput-boolean v5, Lrc/h;->d:Z"""
            ),
            SmaliPatch(
                title = "Removing the jank recorder's launch metric",
                explanation = "The launch path no longer initialises the recorder, so the " +
                    "launch metric named after it is a label for work that does not happen and " +
                    "the last thing in the application class that mentions the recorder.",
                smaliPath = "com/discord/MainApplication.smali",
                anchor = """    :goto_cf
    const-string v0, "JankSessionRecorder.init()"

    .line 209
    .line 210
    invoke-static {v0}, Lai/m;->b(Ljava/lang/String;)V""",
                replacement = """    :goto_cf
    # Patch: the launch metric names work this build no longer does."""
            )
        )
    )

    /**
     * Stops contact and installed-app fingerprinting and pins the React Native Fabric
     * flags to React Native's defaults.
     */
    val PRIVACY = PatchSet(
        id = "discord_native_privacy",
        label = "No contact or device identifiers",
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
                explanation = "Answers the contact photo request with an empty string so no contact " +
                    "image is read, even if a contact id is supplied. The JavaScript bridge calls " +
                    "replace() on the returned string, so null would be a TypeError there.",
                smaliPath = "ta/h.smali",
                methodSignature = ".method public static c(Lcom/facebook/react/bridge/ReactApplicationContext;Ljava/lang/String;)Ljava/lang/String;",
                replacementBody = """.method public static c(Lcom/facebook/react/bridge/ReactApplicationContext;Ljava/lang/String;)Ljava/lang/String;
    .locals 1
    const-string v0, ""
    return-object v0
.end method"""
            ),
            SmaliPatch(
                title = "Returning no contact image URIs",
                explanation = "Answers the contact image URI lookup with an empty string, so a caller " +
                    "that has a URI still gets no photo and no caller has to handle a null string.",
                smaliPath = "ta/h.smali",
                methodSignature = ".method public static a(Landroid/net/Uri;Lcom/facebook/react/bridge/ReactApplicationContext;)Ljava/lang/String;",
                replacementBody = """.method public static a(Landroid/net/Uri;Lcom/facebook/react/bridge/ReactApplicationContext;)Ljava/lang/String;
    .locals 1
    const-string v0, ""
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
                explanation = "Answers React Native's feature-flag provider with true so Discord's server experiment can no longer turn batched property updates off.",
                smaliPath = "pl/c.smali",
                methodSignature = ".method public final enableAccumulatedUpdatesInRawPropsAndroid()Z",
                replacementBody = """.method public final enableAccumulatedUpdatesInRawPropsAndroid()Z
    .locals 1
    const/4 v0, 0x1
    return v0
.end method"""
            ),
            SmaliPatch(
                title = "Forcing the pull model for property updates",
                explanation = "Answers React Native's feature-flag provider with true so Discord's server experiment can no longer switch the push model back on.",
                smaliPath = "pl/c.smali",
                methodSignature = ".method public final usePullModelOnAndroid()Z",
                replacementBody = """.method public final usePullModelOnAndroid()Z
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
        label = "No resource monitors",
        description = "Stubs the resource-usage sampler, the performance tracing loop, the jank aggregator and the jank session recorder so no CPU, memory or frame statistics are gathered in the background.",
        smaliPatches = listOf(
            SmaliPatch(
                title = "Preventing resource sampling from starting",
                explanation = "Skips the React Native module's initializer, so the resource-usage sampler thread is never started and CPU and memory statistics are not collected.",
                smaliPath = "com/discord/resource_usage/DeviceResourceUsageManagerModule.smali",
                methodSignature = ".method public initialize()V",
                replacementBody = """.method public initialize()V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Preventing performance tracing from starting",
                explanation = "Replaces the performance tracer's start with a no-op, so it neither registers a React marker listener nor opens a Sentry transaction.",
                smaliPath = "com/discord/crash_reporting/q.smali",
                methodSignature = ".method public final d()V",
                replacementBody = """.method public final d()V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Skipping jank aggregator setup",
                explanation = "Returns an empty aggregator without registering a frame-metrics listener on the window, so the jank aggregator never observes frame timing.",
                smaliPath = "h3/h.smali",
                methodSignature = ".method public static p(Landroid/view/Window;)Lh3/a;",
                replacementBody = """.method public static p(Landroid/view/Window;)Lh3/a;
    .locals 2
    # Patch: return an unattached, empty aggregator.
    new-instance v0, Ljava/util/ArrayList;
    invoke-direct {v0}, Ljava/util/ArrayList;-><init>()V
    new-instance v1, Lh3/a;
    invoke-direct {v1, v0}, Lh3/a;-><init>(Ljava/util/ArrayList;)V
    return-object v1
.end method"""
            ),
            SmaliPatch(
                title = "Keeping jank tracking disabled",
                explanation = "Replaces the tracker's enable/disable switch with a no-op, so it never adds or removes a frame-metrics listener.",
                smaliPath = "h3/h.smali",
                methodSignature = ".method public final r(Z)V",
                replacementBody = """.method public final r(Z)V
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
                title = "Preventing jank session recording",
                explanation = "Replaces the recorder's background runnable with a no-op, so it never scans the stored jank sessions or starts recording at application startup.",
                smaliPath = "rc/f.smali",
                methodSignature = ".method public final run()V",
                replacementBody = """.method public final run()V
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
        label = "Lighter call overhead",
        description = "Removes the profiler dump and parse that runs precisely when the JavaScript thread has stalled, the JavaScript-callable profiler switch, the Rust panic reporter the native layer calls over JNI, per-transaction tracing, and the per-video-tile statistics timer.",
        smaliPatches = listOf(
            SmaliPatch(
                title = "Dropping the profiler dump on JavaScript stalls",
                explanation = "Replaces the stall handler with a no-op, so a JavaScript stall neither starts the sampling profiler nor writes and parses a profile on a thread that is already behind.",
                smaliPath = "sc/c.smali",
                methodSignature = ".method public static a(Ljava/lang/String;IZ)V",
                replacementBody = """.method public static a(Ljava/lang/String;IZ)V
    .locals 0
    # Patch: no profiler dump, no stall report.
    return-void
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
                explanation = "Replaces the performance tracer's start with a no-op, so it neither registers a React marker listener nor opens a Sentry transaction.",
                smaliPath = "com/discord/crash_reporting/q.smali",
                methodSignature = ".method public final d()V",
                replacementBody = """.method public final d()V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            ),
            SmaliPatch(
                title = "Stopping the per-video-tile statistics timer",
                explanation = "Returns immediately from the renderer's statistics case, which otherwise built several strings and reposted itself every four seconds per video tile for a log line nothing reads.",
                smaliPath = "fd/a.smali",
                anchor = """    :pswitch_1d
    iget-object v0, p0, Lfd/a;->e:Lfd/c;""",
                replacement = """    :pswitch_1d
    # Patch: no statistics dump or repost.
    return-void

    iget-object v0, p0, Lfd/a;->e:Lfd/c;"""
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
                explanation = "Replaces the ActivityDelegate routine that checks for a blocking OTA update with a no-op, so no unbounded wait for the bundle updater runs on the UI thread after a crash.",
                smaliPath = "sf/b.smali",
                methodSignature = ".method public static h()V",
                replacementBody = """.method public static h()V
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
            )
        )
    )

    /**
     * Fixes the video renderer's UI-thread stall and logs the media callback failures that
     * were dropped.
     *
     * 353.2.0 replaced the media connection map and the coroutine scope construction the
     * 351-series edits targeted, so the connection-registration and supervisor-scope edits
     * no longer have a target; the renderer and callback paths below still do.
     */
    val MEDIA = PatchSet(
        id = "discord_native_media",
        label = "Voice and video fixes",
        description = "Stops the video renderer from blocking the UI thread while it creates a " +
            "graphics context, guards a pending EGL setup against a renderer that has already " +
            "been released, surfaces media callback failures in logcat under MediaEngineCB " +
            "instead of sending them to the crash reporter, replaces a connection that is " +
            "registered twice instead of throwing, tears down every connection without " +
            "concurrent modification, keeps the media engine's coroutine scope alive after a " +
            "failed operation, contains a failed screen-share thumbnail, and bounds the " +
            "UI-thread surface-release waits that can freeze a backgrounded call.",
        smaliPatches = listOf(
            SmaliPatch(
                title = "Stopping video tiles from freezing the interface",
                explanation = "Posts the graphics setup to the render thread instead of waiting for it, which removes the UI-thread stall that happens once per video tile.",
                smaliPath = "id/h.smali",
                anchor = "    invoke-static {v4, v0}, Lorg/webrtc/ThreadUtils;->invokeAtFrontUninterruptibly(Landroid/os/Handler;Ljava/lang/Runnable;)V",
                replacement = """    # Patch: do not block the UI thread while EGL is created. The
    # render-thread Looper is FIFO, so the surface-creation runnable
    # posted immediately after this still runs once eglBase is set.
    invoke-virtual {v4, v0}, Landroid/os/Handler;->post(Ljava/lang/Runnable;)Z"""
            ),
            SmaliPatch(
                title = "Skipping EGL setup once the renderer is released",
                explanation = "Returns early from the deferred graphics setup when the renderer has already been released, so teardown that jumps the render thread's queue cannot leave a graphics context nothing will free.",
                smaliPath = "fd/a.smali",
                anchor = """    iget-object v0, p0, Lfd/a;->e:Lfd/c;

    .line 7
    .line 8
    const-string v1, "Creating EglBase"""",
                replacement = """    iget-object v0, p0, Lfd/a;->e:Lfd/c;

    .line 7
    .line 8
    iget-object v2, v0, Lfd/c;->i:Ldx/a;

    if-nez v2, :egl_create

    return-void

    :egl_create
    const-string v1, "Creating EglBase""""
            ),
            SmaliPatch(
                title = "Logging media callback failures (outer callbacks)",
                explanation = "Replaces the five outer media-callback crash reports with a logcat line under the MediaEngineCB tag, so a swallowed failure stays visible without becoming a Sentry event.",
                smaliPath = "a2/d.smali",
                anchor = "    invoke-static {p3, p1}, Lcom/discord/crash_reporting/CrashReporting;->c(Ljava/lang/Throwable;Z)V",
                replacement = """    # Patch: surface the swallowed failure instead of reporting it.
    const-string p2, "MediaEngineCB"

    const-string p1, "media engine native callback threw"

    invoke-static {p2, p1, p3}, Lyc/a;->b(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)V"""
            ),
            SmaliPatch(
                title = "Logging media callback failures (MLS failure)",
                explanation = "Replaces the MLS-failure callback's crash report with the same MediaEngineCB log line.",
                smaliPath = "a2/d.smali",
                anchor = "    invoke-static {v0, p1}, Lcom/discord/crash_reporting/CrashReporting;->c(Ljava/lang/Throwable;Z)V",
                replacement = """    # Patch: surface the swallowed failure instead of reporting it.
    const-string p2, "MediaEngineCB"

    const-string p1, "media engine native callback threw"

    invoke-static {p2, p1, v0}, Lyc/a;->b(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)V"""
            ),
            SmaliPatch(
                title = "Logging media callback failures (speaking callback)",
                explanation = "Replaces the speaking-callback failure's crash report with the same MediaEngineCB log line.",
                smaliPath = "a2/d.smali",
                anchor = "    invoke-static {v2, v0}, Lcom/discord/crash_reporting/CrashReporting;->c(Ljava/lang/Throwable;Z)V",
                replacement = """    # Patch: surface the swallowed failure instead of reporting it.
    # This method has only `this` as a parameter, so there is no p1: use
    # the crash-reporting object and message locals, both dead here.
    const-string v1, "MediaEngineCB"

    const-string v3, "media engine native callback threw"

    invoke-static {v1, v3, v2}, Lyc/a;->b(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)V"""
            ),
            SmaliPatch(
                title = "Keeping the media engine alive after one failure",
                explanation = "Puts a supervisor job in the media engine's coroutine scope. With the plain job CoroutineScope installs, one failed operation cancels the scope itself and every later create, stop or device call becomes a no-op; under a supervisor the remaining children keep running.",
                smaliPath = "com/discord/media/engine/MediaEngineModule.smali",
                anchor = "    invoke-direct {v3, v2}, Lk40/w0;-><init>(Ljava/util/concurrent/Executor;)V",
                replacement = """    invoke-direct {v3, v2}, Lk40/w0;-><init>(Ljava/util/concurrent/Executor;)V

    # Patch: give the media engine a SupervisorJob. CoroutineScope() below
    # installs a plain Job when the context has none, and a plain Job cancels
    # every child - and every later launch - on the first failure. k40/t1 is
    # SupervisorJobImpl, built from k40/e1's constructor the same way
    # MainScope() does in k40/z.c().
    new-instance v2, Lk40/t1;

    invoke-direct {v2}, Lk40/e1;-><init>()V

    invoke-interface {v3, v2}, Lb10/h;->B(Lb10/h;)Lb10/h;

    move-result-object v3"""
            ),
            SmaliPatch(
                title = "Registering a reconnecting call instead of throwing",
                explanation = "Replaces the duplicate-connection checks in createVoiceConnection. A call that reconnects while the previous connection with the same id is still registered used to throw IllegalStateException, which cancelled the media engine's scope and every later call with it. The new connection now replaces the old entry, the displaced connection is disposed, and an instance that is already registered under another id is removed first so it cannot be disposed twice.",
                smaliPath = "cd/q0.smali",
                anchor = """    iget-object p1, p1, Lcd/b1;->a:Ljava/util/LinkedHashMap;

    .line 89
    .line 90
    iget v1, p0, Lcd/q0;->i:I

    .line 91
    .line 92
    invoke-static {v1}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;

    .line 93
    .line 94
    .line 95
    move-result-object v2

    .line 96
    invoke-interface {p1, v2}, Ljava/util/Map;->containsKey(Ljava/lang/Object;)Z

    .line 97
    .line 98
    .line 99
    move-result v2

    .line 100
    const-string v3, "Check failed."

    .line 101
    .line 102
    if-nez v2, :cond_7d

    .line 103
    .line 104
    invoke-virtual {p1, v0}, Ljava/util/LinkedHashMap;->containsValue(Ljava/lang/Object;)Z

    .line 105
    .line 106
    .line 107
    move-result v2

    .line 108
    if-nez v2, :cond_77

    .line 109
    .line 110
    invoke-static {v1}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;

    .line 111
    .line 112
    .line 113
    move-result-object v1

    .line 114
    invoke-interface {p1, v1, v0}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;

    .line 115
    .line 116
    .line 117
    sget-object p1, Lw00/a0;->a:Lw00/a0;

    .line 118
    .line 119
    return-object p1

    .line 120
    :cond_77
    new-instance p1, Ljava/lang/IllegalStateException;

    .line 121
    .line 122
    invoke-direct {p1, v3}, Ljava/lang/IllegalStateException;-><init>(Ljava/lang/String;)V

    .line 123
    .line 124
    .line 125
    throw p1

    .line 126
    :cond_7d
    new-instance p1, Ljava/lang/IllegalStateException;

    .line 127
    .line 128
    invoke-direct {p1, v3}, Ljava/lang/IllegalStateException;-><init>(Ljava/lang/String;)V

    .line 129
    .line 130
    .line 131
    throw p1""",
                replacement = """    iget-object p1, p1, Lcd/b1;->a:Ljava/util/LinkedHashMap;

    # Patch: a reconnect can register a connection id that is still present,
    # or re-register the same NativeConnection object. Replace instead of
    # throwing: the throw cancels the media engine scope and turns every later
    # call into a no-op. Remove an earlier entry for this exact instance first
    # so no connection ends up in the map twice.
    invoke-interface {p1}, Ljava/util/Map;->entrySet()Ljava/util/Set;

    move-result-object v2

    invoke-interface {v2}, Ljava/lang/Iterable;->iterator()Ljava/util/Iterator;

    move-result-object v2

    :goto_dup
    invoke-interface {v2}, Ljava/util/Iterator;->hasNext()Z

    move-result v3

    if-eqz v3, :cond_dup_done

    invoke-interface {v2}, Ljava/util/Iterator;->next()Ljava/lang/Object;

    move-result-object v3

    check-cast v3, Ljava/util/Map${'$'}Entry;

    invoke-interface {v3}, Ljava/util/Map${'$'}Entry;->getValue()Ljava/lang/Object;

    move-result-object v3

    if-ne v3, v0, :goto_dup

    invoke-interface {v2}, Ljava/util/Iterator;->remove()V

    goto :goto_dup

    :cond_dup_done
    iget v1, p0, Lcd/q0;->i:I

    invoke-static {v1}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;

    move-result-object v2

    invoke-interface {p1, v2, v0}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;

    move-result-object v2

    check-cast v2, Lcom/discord/native/engine/NativeConnection;

    if-eqz v2, :cond_no_old

    if-eq v2, v0, :cond_no_old

    invoke-static {}, Ljava/lang/System;->nanoTime()J

    invoke-virtual {v2}, Lcom/discord/native/engine/NativeConnection;->dispose()V

    :cond_no_old
    sget-object p1, Lw00/a0;->a:Lw00/a0;

    return-object p1"""
            ),
            SmaliPatch(
                title = "Tearing down every call connection",
                explanation = "Takes a snapshot of the connection ids before the media engine disposes them. The old loop removed entries from the live key set while iterating it, so a second connection made the iterator throw ConcurrentModificationException, abandon teardown and leave the native engine and the remaining connections alive.",
                smaliPath = "cd/x0.smali",
                anchor = """    invoke-virtual {v2}, Ljava/util/LinkedHashMap;->keySet()Ljava/util/Set;

    .line 158
    .line 159
    .line 160
    move-result-object v3

    .line 161
    invoke-interface {v3}, Ljava/lang/Iterable;->iterator()Ljava/util/Iterator;

    .line 162
    .line 163
    .line 164
    move-result-object v3

    .line 165
    :cond_a4
    :goto_a4
    invoke-interface {v3}, Ljava/util/Iterator;->hasNext()Z

    .line 166
    .line 167
    .line 168
    move-result v4

    .line 169
    if-eqz v4, :cond_c9

    .line 170
    .line 171
    invoke-interface {v3}, Ljava/util/Iterator;->next()Ljava/lang/Object;

    .line 172
    .line 173
    .line 174
    move-result-object v4

    .line 175
    check-cast v4, Ljava/lang/Number;

    .line 176
    .line 177
    invoke-virtual {v4}, Ljava/lang/Number;->intValue()I

    .line 178
    .line 179
    .line 180
    move-result v4""",
                replacement = """    invoke-virtual {v2}, Ljava/util/LinkedHashMap;->keySet()Ljava/util/Set;

    move-result-object v3

    # Patch: snapshot the keys before removing entries. Removing from the live
    # key set changes the map's modification count, so with more than one
    # connection the next hasNext() throws ConcurrentModificationException and
    # abandons the rest of teardown.
    invoke-interface {v3}, Ljava/util/Set;->toArray()[Ljava/lang/Object;

    move-result-object v3

    const/4 p0, 0x0

    :cond_a4
    :goto_a4
    array-length v5, v3

    if-ge p0, v5, :cond_c9

    aget-object v5, v3, p0

    check-cast v5, Ljava/lang/Number;

    invoke-virtual {v5}, Ljava/lang/Number;->intValue()I

    move-result v4

    add-int/lit8 p0, p0, 0x1"""
            ),
            SmaliPatch(
                title = "Bounding the texture-surface release wait",
                explanation = "Caps the wait in the video TextureView's surface-destroyed callback at two seconds. The callback runs on the UI thread, and stock waits with a timeout of Long.MAX_VALUE for the render thread to release the EGL surface; when the render thread is wedged - the state a backgrounded or screen-off call can leave it in - the UI thread blocks forever and the app freezes.",
                smaliPath = "id/e.smali",
                anchor = "    const-wide v3, 0x7fffffffffffffffL",
                replacement = """    # Patch: the surface-destroyed callback runs on the UI thread. Stock waits
    # for the render thread with Long.MAX_VALUE, so a wedged renderer turns
    # screen-off or backgrounding into a permanent freeze.
    const-wide/16 v3, 0x7d0"""
            ),
            SmaliPatch(
                title = "Bounding the video-tile release wait",
                explanation = "Caps the wait in the React Native video view's release at two seconds. The view manager drops the view on the UI thread and stock waits for the render thread with no timeout, so a renderer that is behind or wedged freezes the interface when the call UI is torn down.",
                smaliPath = "id/h.smali",
                anchor = "    invoke-static {v6}, Lorg/webrtc/ThreadUtils;->awaitUninterruptibly(Ljava/util/concurrent/CountDownLatch;)V",
                replacement = """    # Patch: bound the UI-thread release wait. v7/v8 are dead here: the code
    # below loads the current time into v7 before using it.
    const-wide/16 v7, 0x7d0

    invoke-static {v6, v7, v8}, Lorg/webrtc/ThreadUtils;->awaitUninterruptibly(Ljava/util/concurrent/CountDownLatch;J)Z"""
            ),
            SmaliPatch(
                title = "Bounding WebRTC's surface-release wait",
                explanation = "Caps SurfaceEglRenderer's surface-destroyed wait at two seconds. It is a SurfaceHolder callback on the UI thread and stock waits on the render thread without a timeout; the release still completes first in every normal case.",
                smaliPath = "org/webrtc/SurfaceEglRenderer.smali",
                anchor = "    invoke-static {p1}, Lorg/webrtc/ThreadUtils;->awaitUninterruptibly(Ljava/util/concurrent/CountDownLatch;)V",
                replacement = """    # Patch: bound the UI-thread wait; the EGL release normally completes in
    # microseconds, and only the wedged-renderer case changes.
    const-wide/16 v0, 0x7d0

    invoke-static {p1, v0, v1}, Lorg/webrtc/ThreadUtils;->awaitUninterruptibly(Ljava/util/concurrent/CountDownLatch;J)Z"""
            ),
            SmaliPatch(
                title = "Containing screen-share thumbnail failures",
                explanation = "Wraps one screen-share thumbnail coroutine in a catch-all. It compresses a bitmap and invokes a JavaScript callback; a recycled bitmap, a failed compression or a throwing callback escaped the coroutine as an uncaught exception, which ends the process while the call is sharing its screen. A failure now ends the thumbnail attempt normally, without touching the call.",
                smaliPath = "a7/b.smali",
                switchCaseLabel = ":pswitch_969",
                switchCaseBody = """    :pswitch_969
    :try_start_thumbnail
    sget-object v0, Lc10/a;->d:Lc10/a;

    iget v2, v1, La7/b;->e:I

    const/4 v8, 0x1

    if-eqz v2, :cond_thumbnail_first

    if-ne v2, v8, :cond_thumbnail_bad_state

    invoke-static/range {p1 .. p1}, Lxk/a;->R(Ljava/lang/Object;)V

    move-object/from16 v2, p1

    goto :goto_thumbnail_result

    :cond_thumbnail_bad_state
    new-instance v0, Ljava/lang/IllegalStateException;

    const-string v2, "call to 'resume' before 'invoke' with coroutine"

    invoke-direct {v0, v2}, Ljava/lang/IllegalStateException;-><init>(Ljava/lang/String;)V

    throw v0

    :cond_thumbnail_first
    invoke-static/range {p1 .. p1}, Lxk/a;->R(Ljava/lang/Object;)V

    sget-object v2, Lcd/p;->j:Ljava/util/Set;

    iget-object v2, v1, La7/b;->i:Ljava/lang/Object;

    check-cast v2, Landroid/graphics/Bitmap;

    iput v8, v1, La7/b;->e:I

    invoke-static {v2, v1}, Lkv/d;->r(Landroid/graphics/Bitmap;Ld10/c;)Ljava/lang/Object;

    move-result-object v2

    if-ne v2, v0, :cond_thumbnail_resume

    goto :goto_thumbnail_suspended

    :cond_thumbnail_resume
    :goto_thumbnail_result
    check-cast v2, Ljava/lang/String;

    iget-object v0, v1, La7/b;->v:Ljava/lang/Object;

    check-cast v0, Laf/g;

    invoke-virtual {v0, v2}, Laf/g;->invoke(Ljava/lang/Object;)Ljava/lang/Object;

    sget-object v0, Lw00/a0;->a:Lw00/a0;

    :goto_thumbnail_suspended
    :try_end_thumbnail
    return-object v0

    :catch_thumbnail
    move-exception v0

    sget-object v0, Lw00/a0;->a:Lw00/a0;

    return-object v0

    .catchall {:try_start_thumbnail .. :try_end_thumbnail} :catch_thumbnail"""
            ),
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
        label = "No camera frame-rate log",
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
        label = "Calls stay alive in the background",
        description = "Keeps the microphone and camera types in the foreground-service promotion chain while the app is backgrounded, and widens the per-type catch so a refusal falls through to the next candidate instead of escaping as an uncaught exception. Stock builds the chain from the window's foreground state, so a call that outlives the window is promoted without a microphone type and the OS cuts capture while the notification still says connected.",
        smaliPatches = listOf(
            SmaliPatch(
                title = "Keeping the microphone type while backgrounded",
                explanation = "Drops the window-foreground test from the microphone branch of the service-type chain, so a call that outlives the window is promoted with its microphone type. The app's permission checks stay in front of it, and the widened catches below make the OS refuse a type by falling through to the next candidate instead of aborting the promotion. The final fallback type is untouched.",
                smaliPath = "androidx/core/view/f1.smali",
                anchor = "    if-eqz v16, :cond_a8",
                replacement = """    # Patch: the microphone type stays in the chain while backgrounded.
    nop"""
            ),
            SmaliPatch(
                title = "Keeping the camera type while backgrounded",
                explanation = "Drops the same window-foreground test from the camera branch, so screen sharing and video keep their camera type after the app is backgrounded.",
                smaliPath = "androidx/core/view/f1.smali",
                anchor = "    if-eqz v16, :cond_c8",
                replacement = """    # Patch: the camera type stays in the chain while backgrounded.
    nop"""
            ),
            SmaliPatch(
                title = "Falling through to the next service type (first attempt)",
                explanation = "Catches any refusal around the first service-type attempt, not just the security exception, so a failed type falls through instead of aborting the promotion.",
                smaliPath = "androidx/core/view/f1.smali",
                anchor = "    .catch Ljava/lang/SecurityException; {:try_start_148 .. :try_end_174} :catch_193",
                replacement = "    .catch Ljava/lang/Exception; {:try_start_148 .. :try_end_174} :catch_193"
            ),
            SmaliPatch(
                title = "Falling through to the next service type (second attempt)",
                explanation = "Catches any refusal around the second service-type attempt so it falls through to the third.",
                smaliPath = "androidx/core/view/f1.smali",
                anchor = "    .catch Ljava/lang/SecurityException; {:try_start_176 .. :try_end_185} :catch_190",
                replacement = "    .catch Ljava/lang/Exception; {:try_start_176 .. :try_end_185} :catch_190"
            ),
            SmaliPatch(
                title = "Falling through to the next service type (third attempt)",
                explanation = "Catches any refusal around the third service-type attempt so it reaches the aggregate failure path instead of escaping as a security exception.",
                smaliPath = "androidx/core/view/f1.smali",
                anchor = "    .catch Ljava/lang/SecurityException; {:try_start_186 .. :try_end_18d} :catch_18e",
                replacement = "    .catch Ljava/lang/Exception; {:try_start_186 .. :try_end_18d} :catch_18e"
            )
        )
    )

    /**
     * Pins Discord's server-driven performance experiments to the cheapest treatment.
     */
    val EXPERIMENTS = PatchSet(
        id = "discord_native_experiments",
        label = "Cheaper performance settings",
        description = "Pins Discord's server-driven performance experiments to the cheaper " +
            "treatment: React Native's accumulated raw-props updates, the smaller Fresco cache, " +
            "the Hermes memory target, the shared ChatMosaic pool, per-screen jank tracking " +
            "switched off, the React Native mounting mode the individual flag patches assume, the " +
            "versioned UIManager constants cache, and chat preinflation deferred to the first " +
            "drawn message.",
        smaliPatches = listOf(
            SmaliPatch(
                title = "Enabling accumulated raw-props updates",
                explanation = "Pins the RMLE experiment to treatment 2, the level that enables React Native's accumulated raw-props updates without the pull-model change in treatment 3. The app's feature-flag bridge reads a value of 2 or more as enabled.",
                smaliPath = "me/j.smali",
                anchor = "    const-string v1, \"2026-01-android-rmle\"",
                replacement = """    const/4 v0, 0x2

    goto/16 :goto_19

    const-string v1, "2026-01-android-rmle""""
            ),
            SmaliPatch(
                title = "Deferring chat list preinflation",
                explanation = "Pins the chat preinflation experiment to treatment 1, which fills the recycled-view pool when the first message is drawn instead of while the chat screen is being built.",
                smaliPath = "me/j.smali",
                anchor = "    const-string v1, \"2026-10-android-chat-preinflation\"",
                replacement = """    const/4 v0, 0x1

    goto :goto_19

    const-string v1, "2026-10-android-chat-preinflation""""
            ),
            SmaliPatch(
                title = "Caching the UIManager constants",
                explanation = "Pins the UIManager constants cache to its enabled treatment, so React Native reads the view-manager constants from the versioned cache instead of rebuilding them at each launch.",
                smaliPath = "me/j.smali",
                anchor = "    const-string v1, \"2026-10-android-ui-manager-constants-cache\"",
                replacement = """    const/4 v0, 0x1

    goto :goto_19

    const-string v1, "2026-10-android-ui-manager-constants-cache""""
            ),
            SmaliPatch(
                title = "Using the smaller image cache experiment",
                explanation = "Pins the image cache experiment to its cheap treatment instead of following the server assignment.",
                smaliPath = "me/j.smali",
                anchor = "    const-string v5, \"2026-02-android-fresco-cache\"",
                replacement = """    # Patch: treatment 1, the 12 MB cache.
    const/4 v5, 0x1

    const/16 v0, 0xc

    invoke-static {v5, v0}, Lme/c;->a(II)Lme/c;

    move-result-object v5

    return-object v5

    const-string v5, "2026-02-android-fresco-cache""""
            ),
            SmaliPatch(
                title = "Using the background JavaScript memory target",
                explanation = "Pins the JavaScript engine memory-target experiment to its background-oriented treatment.",
                smaliPath = "me/j.smali",
                anchor = "    const-string v1, \"2026-03-mobile-hermes-occupancy-target\"",
                replacement = """    # Patch: treatment 1, the background-oriented target.
    sget-object v0, Lme/d;->c:Lme/e;

    return-object v0

    const-string v1, "2026-03-mobile-hermes-occupancy-target""""
            ),
            SmaliPatch(
                title = "Using the shared image pool for chat images",
                explanation = "Pins the chat image pool experiment to the shared-pool treatment.",
                smaliPath = "me/j.smali",
                anchor = "    const-string v5, \"2026-02-android-chat-mosaic-shared-pool\"",
                replacement = """    # Patch: treatment 1 enables the shared pool.
    new-instance v0, Lme/b;

    invoke-direct {v0, v4, v4}, Lme/b;-><init>(IZ)V

    return-object v0

    const-string v5, "2026-02-android-chat-mosaic-shared-pool""""
            ),
            SmaliPatch(
                title = "Turning per-screen jank tracking off",
                explanation = "Pins the per-screen jank experiment to control, which is the treatment that switches per-screen tracking off.",
                smaliPath = "me/j.smali",
                anchor = "    const-string v1, \"2026-08-android-jank-per-screen\"",
                replacement = """    # Patch: control keeps per-screen tracking off.
    sget-object v0, Lme/g;->b:Lme/h;

    return-object v0

    const-string v1, "2026-08-android-jank-per-screen""""
            ),
            SmaliPatch(
                title = "Pinning the React Native mounting mode",
                explanation = "Pins the mounting-mode experiment to the value that matches the individually forced React Native flags, so the two cannot drift apart.",
                smaliPath = "me/j.smali",
                anchor = "    const-string v1, \"2026-08-android-rn-reparenting-flag\"",
                replacement = """    # Patch: treatment 3 matches the forced React Native flags.
    const/4 v0, 0x3

    goto :goto_19

    const-string v1, "2026-08-android-rn-reparenting-flag""""
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
     * The exported constants stop reading the stored choice, and the in-app entry point itself
     * is re-pointed at the external-browser call, so a stale stored choice or a caller that
     * passes the in-app browser explicitly still leaves the app. This build already falls back
     * to Chrome when there is no stored choice, so no default-value edit is needed.
     *
     * `openTrackedCustomTab` is left alone: its callback is `Function1<Boolean, Unit>` where the
     * external call wants `Function1<Unit, Unit>`, and the erased generics would throw when the
     * callback ran. `openPlayStoreInline` is left alone because it opens a Play Store deep link
     * rather than a web link.
     */
    val EXTERNAL_BROWSER = PatchSet(
        id = "discord_native_external_browser",
        label = "Links open in your browser",
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
                anchor = "    const-string v3, \"SELECTED_BROWSER\"",
                replacement = "    const-string v3, \"SELECTED_BROWSER_IGNORED\""
            ),
            SmaliPatch(
                title = "Opening links from the in-app entry point externally",
                explanation = "Points the module's openInAppURL at the external-browser call, so a " +
                    "link still opens in your browser when something else selects the in-app tab - a " +
                    "stale setting, or a deep link that passes its own choice.",
                smaliPath = "com/discord/browser_manager/BrowserManagerModule.smali",
                anchor = "    invoke-static {v0, p2, v2}, Lt6/a;->c(Landroid/content/ContextWrapper;Landroid/net/Uri;Laf/i;)V",
                replacement = """    # Patch: hand the URL to the external-browser path instead of a Custom Tab.
    sget-object v2, Lt6/a;->a:Lt6/a;

    invoke-virtual {v2, v0, p1, v1}, Lt6/a;->d(Landroid/content/Context;Ljava/lang/String;Lkotlin/jvm/functions/Function1;)V"""
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
        EXTERNAL_BROWSER
    )
}
