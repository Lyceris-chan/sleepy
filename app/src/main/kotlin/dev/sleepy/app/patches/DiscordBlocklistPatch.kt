package dev.sleepy.app.patches

import dev.sleepy.app.engine.OkHttpNameResolver
import dev.sleepy.app.engine.OkHttpResolution
import dev.sleepy.app.model.GeneratedPatches
import dev.sleepy.app.model.PatchGenerator
import dev.sleepy.app.model.PatchSet
import dev.sleepy.app.model.SmaliPatch

/**
 * The network blocklist interceptor for Discord, transcribed from
 * `quirky-noether/discord/patches/blocklist.py`.
 *
 * One method — `DeviceResourceUsageRecorder$Companion.requestStatsInterceptor` — is shared by
 * every OkHttp client that matters here: the React Native XHR client (which carries all of the
 * client's JavaScript traffic), the media download client, the bundle updater and Fresco. This
 * set replaces its body with one that matches the request URL against the rules below and
 * answers a match with a synthetic HTTP 204, so the request is never sent.
 *
 * The 204 rather than a dropped response is deliberate: the JavaScript side reads it as a
 * successful empty response and clears its retry buffer, whereas dropping the response makes the
 * client retry forever.
 *
 * Unlike every other set in this package this patch cannot be a static string. It constructs an
 * `okhttp3.Response` by hand, so it has to spell out names R8 renames on every release; the body
 * is therefore generated against the APK the user selected — see [OkHttpNameResolver]. Because it
 * answers a request without calling `chain.proceed()`, it only works where the interceptors are
 * on OkHttp's application list, which is what [DiscordNativePatches.INTERCEPTORS] arranges.
 */
object DiscordBlocklistPatch {

    /** The class whose stock interceptor this set rewrites. */
    private const val RECORDER_CLASS =
        "com/discord/resource_usage/DeviceResourceUsageRecorder${'$'}Companion.smali"

    /** The one method, matched exactly as the reference matches it. */
    private const val INTERCEPTOR_SIGNATURE =
        ".method private final requestStatsInterceptor(Lokhttp3/Interceptor${'$'}Chain;" +
            "Lcom/discord/resource_usage/DeviceResourceUsageRecorder${'$'}RequestStats;)" +
            "Lokhttp3/Response;"

    /**
     * Host rules, matched anywhere in the request URL.
     *
     * api.spotify.com and dealer.spotify.com are blocked on purpose: breaking the Spotify
     * integration (REST and the listen-along WebSocket) is intended, not an oversight. The
     * desktop reference notes that an audit flagged it as a feature regression and the user
     * confirmed the block is deliberate.
     */
    private val HOST_BLOCKLIST = listOf(
        "api.spotify.com",
        "dealer.spotify.com",
        // video QoE analytics beacon (Mux/Litix) hit by the media player
        "img.litix.io",
        // Firebase Analytics. The SDK logs to firebaselogging-pa.googleapis.com,
        // which a literal "firebaselogging.googleapis.com" entry never matched —
        // match the prefix instead. Firebase Installations and the FCM/mtalk hosts
        // are deliberately absent: blocking them breaks push.
        "firebaselogging",
        "app-measurement.com",
        // Google Analytics (covers ssl./www./region1. subdomains)
        "google-analytics.com",
        // Bare `sentry.io`, not `ingest.sentry.io`. Sentry moved to regional DSN
        // hosts (`o<org>.ingest.us.sentry.io`, `.de.`, `.eu.`), and the old rule
        // did not match any of them - it only matched the legacy single-region
        // form. The DSNs in this build are scrubbed so nothing can send today, but
        // a future version shipping a regional DSN would have sailed straight past
        // the old pattern. Bare `sentry.io` covers every DSN form and the docs.
        "sentry.io",
        // Sentry's replay bundle CDN
        "sentry-cdn.com",
        // AppsFlyer attribution. The SDK init is already no-op'd; this covers the
        // network side as well, including the OneLink short domain.
        "appsflyer.com",
        "appsflyersdk.com",
        "onelink.me",
        // Qualtrics surveys
        "qualtrics.com",
        // Datadog APM
        "datadog.discord.tools",
        // PayPal conversion beacon and the FraudNet script the checkout webview loads
        "b.stats.paypal.com",
        "c.paypal.com",
        // Google Mobile Ads attribution beacon. Reachable only if the ads path
        // were live (it is stubbed), but the host string is still in the dex.
        "pagead2.googlesyndication.com",
        "api.amplitude.com",
        "api.segment.io",
        "client-analytics.braintreegateway.com",
        "app.adjust.com"
    )

    /**
     * API path rules, matched only on Discord API URLs — those containing `/api/`.
     *
     * Without that gate a bare substring such as `/track` would also block a CDN attachment named
     * `track1.mp3` or `shop.png`. A URL that looks proxied (one containing `/external/`) is never
     * treated as an API call, for the same reason: the media proxy embeds the origin URL after the
     * signature, so a proxied image could otherwise match a rule and be answered with a 204.
     */
    private val API_BLOCKLIST = listOf(
        // analytics / telemetry
        "/science",
        "/track",
        "scienc0",
        "/affinit",
        "/analytics_sessions",
        // client metrics upload — MonitoringAgent POSTs /metrics/v2 every 2 minutes
        "/metrics",
        // client_telemetry heartbeat (AnalyticsTrackingStore -> TelemetryEndpoints
        // .CLIENT_TELEMETRY). This one was live: it is a plain HTTP.post outside
        // the /science queue and was not blocked at all.
        "/beaker",
        // Discord-side Spotify content inventory (MY_SPOTIFY_CONTENT_INVENTORY) —
        // the one Spotify endpoint that is not on a Spotify host.
        "/content-inventory/users/@me/spotify",
        // The Spotify connection access token. `SpotifyActionCreators.getAccessToken`
        // GETs Discord's own /connections/spotify/<id>/access-token and uses it to
        // talk to the Spotify Web API. No host rule covers this - it is discord.com.
        // Without it the client has no credential even if it reaches api.spotify.com.
        "/connections/spotify",
        // usage statistics
        "/users/@me/activities/statistics",
        // app-rating and embedded surveys
        "/users/@me/survey",
        "/users/@me/embedded-survey",
        // ad attribution
        "/ads/",
        // debug endpoints
        "/debug/temporal",
        // storefront / promotions / quests
        "/storefront",
        "/quest",
        "/quest-home",
        "/promotion",
        "/bogo-promotions",
        "/shop",
        "/game-shop",
        "/collectibles",
        // `/collectibles` only matches a slash-prefixed segment. Two real endpoints
        // spell it as a hyphen compound, so the rule above misses both:
        // /users/@me/claim-premium-collectibles-product
        // /users/@me/valid-collectibles-gift-recipients-batch
        // Found by diffing every API-shaped path in the bundle against the rules.
        "-collectibles",
        "/wishlist",
        "/virtual_currency",
        "/perks",
        "/reward",
        // billing / monetisation
        "/users/@me/billing",
        "/billing",
        // Play Store in-app purchases
        "/google-play/",
        // store listings and price tiers
        "/store/",
        "/entitlement",
        "/gift",
        "/subscription",
        "/guild-role-subscription",
        "/guild_role_subscriptions",
        "/guild_boosting",
        "/skus",
        "/purchases",
        "/checkout",
        "/payment",
        "/user-offer",
        "/referral",
        "/nitro",
        "/guilds/premium",
        "/boost",
        "/premium",
        // typing indicator — NoType
        "/typing",
        // Hyphenated compounds the entries above miss. Matching is a plain
        // `contains`, so "/reward" does not match "-reward-" and
        // "/guild-role-subscription" does not match "/role-subscriptions". These
        // are the live 346.2 spellings (Endpoints table in Constants.tsx).
        "/creator-monetization",
        "/role-subscriptions",
        "/virtual-currency",
        "/outbound-promotions",
        "/partner-perks",
        "/program-rewards",
        "/tenure-reward",
        "/claim-reward",
        "/store-listing",
        "/application-storefront",
        "/applied-boosts",
        "/powerups",
        "/activities/statistics"
    )

    val NETWORK_BLOCKLIST = PatchSet(
        id = "discord_native_blocklist",
        label = "Block Tracking, Advertising and Monetisation Endpoints",
        description = "Rebuilds Discord's shared OkHttp interceptor so requests to tracking, advertising, survey and monetisation endpoints are answered " +
            "with an empty HTTP 204 instead of being sent, covering ${HOST_BLOCKLIST.size} host rules and ${API_BLOCKLIST.size} API path rules. The three " +
            "obfuscated OkHttp names the method has to spell out are read from the target build, so a release where they cannot be resolved is skipped " +
            "with a reason rather than patched with another release's names.",
        generator = PatchGenerator { target ->
            when (val resolution = OkHttpNameResolver.resolve(target)) {
                is OkHttpResolution.Resolved -> GeneratedPatches(
                    patches = listOf(
                        SmaliPatch(
                            title = "Rebuilding the request interceptor as a network blocklist",
                            explanation = "Replaces the stock resource-usage interceptor with one that matches the request URL against " +
                                "${HOST_BLOCKLIST.size} host and ${API_BLOCKLIST.size} API-path rules and answers a match with a synthetic empty " +
                                "HTTP 204. The OkHttp constructor, Protocol enum and HTTP/1.1 field it has to name were read from this build.",
                            smaliPath = RECORDER_CLASS,
                            methodSignature = INTERCEPTOR_SIGNATURE,
                            replacementBody = interceptorBody(
                                ctorDescriptor = resolution.names.responseConstructorDescriptor,
                                protocolClass = resolution.names.protocolClass,
                                protocolField = resolution.names.protocolHttp11Field
                            )
                        )
                    )
                )

                is OkHttpResolution.Unresolved -> GeneratedPatches(
                    patches = emptyList(),
                    skipReason = resolution.reason
                )
            }
        }
    )

    val ALL = listOf(NETWORK_BLOCKLIST)

    /**
     * Builds the replacement body for `requestStatsInterceptor`.
     *
     * The reference gates two debug blocks on a `BLOCKLIST_LOG` environment variable, which is
     * how it was tuned against the live app. An on-device patcher has no such switch and the
     * shipped desktop build is generated without it, so the body here is what that build's method
     * contains: the block in full, the debug logging absent. Everything else is reproduced as
     * written, down to the blank lines and the label names.
     *
     * The labels are the interesting part. `:not_proxied` and `:skip_<n>` bracket each API rule so
     * a rule only blocks when the URL is an API call, and every host rule branches to the shared
     * `:block`, which is emitted after the early return — smali resolves forward branches, so the
     * blocked path can be built once at the end of the method instead of per rule.
     *
     * @param ctorDescriptor `Lokhttp3/Response;`'s hand-built constructor, return type included.
     * @param protocolClass the Protocol enum, without `L`/`;`.
     * @param protocolField the Protocol field holding the `HTTP_1_1` constant.
     */
    fun interceptorBody(
        ctorDescriptor: String,
        protocolClass: String,
        protocolField: String
    ): String {
        val protocol = "L$protocolClass;"
        val lines = mutableListOf(
            INTERCEPTOR_SIGNATURE,
            "    .locals 24",
            "",
            "    move-object/from16 v3, p1",
            "",
            "    invoke-interface {v3}, Lokhttp3/Interceptor${'$'}Chain;->i()Lokhttp3/Request;",
            "",
            "    move-result-object v0",
            "",
            "    # Patch: match on the URL, NOT Request.toString(). OkHttp's",
            "    # Request.toString() renders the method, the URL *and every header*",
            "    # into a StringBuilder - and Discord's X-Super-Properties header",
            "    # alone is a multi-KB base64 blob. Doing that on every request (all",
            "    # four OkHttp clients, and the RN XHR client carries all JS traffic)",
            "    # just to run 81 substring scans over the result was a large",
            "    # allocation on the hottest shared path. Every rule below is a URL",
            "    # or host substring, so the URL is all we need.",
            "    iget-object v1, v0, Lokhttp3/Request;->a:Lokhttp3/HttpUrl;",
            "",
            "    invoke-virtual {v1}, Lokhttp3/HttpUrl;->toString()Ljava/lang/String;",
            "",
            "    move-result-object v1",
            "",
            "    # Patch: blocklist - match request URL against tracking/monetisation entries.",
            "",
            "    # v6 = is this a Discord API URL? Path rules only apply there.",
            "    const-string v2, \"/api/\"",
            "",
            "    invoke-virtual {v1, v2}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z",
            "",
            "    move-result v6",
            "",
            "    # The media proxy embeds the origin URL after /external/<hmac>/, so a",
            "    # proxied image whose source contains \"/api/\" and a blocked word would",
            "    # otherwise be 204'd and render broken. Never treat a proxied URL as an",
            "    # API call.",
            "    const-string v2, \"/external/\"",
            "",
            "    invoke-virtual {v1, v2}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z",
            "",
            "    move-result v4",
            "",
            "    if-eqz v4, :not_proxied",
            "",
            "    const/4 v6, 0x0",
            "",
            "    :not_proxied",
            ""
        )

        for (host in HOST_BLOCKLIST) {
            lines += listOf(
                "    const-string v2, \"$host\"",
                "",
                "    invoke-virtual {v1, v2}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z",
                "",
                "    move-result v4",
                "",
                "    if-nez v4, :block",
                ""
            )
        }

        API_BLOCKLIST.forEachIndexed { index, entry ->
            lines += listOf(
                "    const-string v2, \"$entry\"",
                "",
                "    invoke-virtual {v1, v2}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z",
                "",
                "    move-result v4",
                "",
                "    if-eqz v4, :skip_$index",
                "",
                "    if-nez v6, :block",
                "",
                "    :skip_$index",
                ""
            )
        }

        lines += listOf(
            "    # Not blocked - proceed with the (possibly rewritten) request.",
            "    invoke-interface {v3, v0}, Lokhttp3/Interceptor${'$'}Chain;->a(Lokhttp3/Request;)Lokhttp3/Response;",
            "",
            "    move-result-object v0",
            "",
            "    return-object v0",
            "",
            "    # Shared block: synthetic HTTP 204 No Content.",
            "    :block",
            "    new-instance v8, Lokhttp3/Response;",
            "",
            "    move-object v9, v0",
            "",
            "    sget-object v10, $protocol->$protocolField:$protocol",
            "",
            "    const-string v11, \"No Content\"",
            "",
            "    const/16 v12, 0xcc",
            "",
            "    const/4 v13, 0x0",
            "",
            "    const/4 v6, 0x0",
            "",
            "    new-array v6, v6, [Ljava/lang/String;",
            "",
            "    new-instance v14, Lokhttp3/Headers;",
            "",
            "    invoke-direct {v14, v6}, Lokhttp3/Headers;-><init>([Ljava/lang/String;)V",
            "",
            "    const-string v6, \"\"",
            "",
            "    const/4 v5, 0x0",
            "",
            "    invoke-static {v6, v5}, Lokhttp3/ResponseBody;->create(Ljava/lang/String;Lokhttp3/MediaType;)Lokhttp3/ResponseBody;",
            "",
            "    move-result-object v15",
            "",
            "    const/16 v16, 0x0",
            "",
            "    const/16 v17, 0x0",
            "",
            "    const/16 v18, 0x0",
            "",
            "    const-wide/16 v19, 0x0",
            "",
            "    const-wide/16 v21, 0x0",
            "",
            "    const/16 v23, 0x0",
            "",
            "    invoke-direct/range {v8 .. v23}, Lokhttp3/Response;-><init>$ctorDescriptor",
            "",
            "    move-object v0, v8",
            "",
            "    return-object v0",
            ".end method"
        )

        return lines.joinToString("\n")
    }
}
