package dev.sleepy.app.patches

import dev.sleepy.app.engine.OkHttpNameResolver
import dev.sleepy.app.engine.OkHttpResolution
import dev.sleepy.app.model.BlocklistCoverage
import dev.sleepy.app.model.BlocklistRow
import dev.sleepy.app.model.BlocklistRule
import dev.sleepy.app.model.GeneratedPatches
import dev.sleepy.app.model.PatchGenerator
import dev.sleepy.app.model.PatchItem
import dev.sleepy.app.model.PatchSelection
import dev.sleepy.app.model.PatchSet
import dev.sleepy.app.model.SelectivePatchGenerator
import dev.sleepy.app.model.SmaliPatch
import dev.sleepy.app.model.TargetApk
import dev.sleepy.app.model.TargetWrittenGenerator

/**
 * The network blocklist interceptor for Discord, transcribed from
 * `quirky-noether/discord/patches/blocklist.py`.
 *
 * One method—`DeviceResourceUsageRecorder$Companion.requestStatsInterceptor`—is shared by
 * every OkHttp client this patch concerns: the React Native XHR client (which carries all of the
 * client's JavaScript traffic), the media download client, the bundle updater and Fresco. This
 * set replaces its body with one that matches the request URL against the rules in
 * [DiscordBlocklistRules] and answers a match with a synthetic HTTP 204, so the request is not
 * sent.
 *
 * The 204 rather than a dropped response is deliberate: the JavaScript side reads it as a
 * successful empty response and clears its retry buffer, whereas dropping the response makes the
 * client retry without a limit.
 *
 * Unlike every other set in this package this patch cannot be a static string. It constructs an
 * `okhttp3.Response` by hand, so it has to spell out names R8 renames on every release; the body
 * is therefore generated against the APK the user selected—see [OkHttpNameResolver]. Because it
 * answers a request without calling `chain.proceed()`, it only works where the interceptors are
 * on OkHttp's application list, which is what [DiscordNativePatches.INTERCEPTORS] arranges.
 *
 * The rules are data ([DiscordBlocklistRules]) rather than lists compiled in here, so a build can
 * carry some of them: a selected subset compiles an interceptor that scans for those rules and
 * answers the rest of the traffic normally. See [generatedPatches].
 */
object DiscordBlocklistPatch {

    /** The class whose stock interceptor this set rewrites. */
    private const val RECORDER_CLASS =
        "com/discord/resource_usage/DeviceResourceUsageRecorder${'$'}Companion.smali"

    /** The one method, matched as the reference matches it. */
    private const val INTERCEPTOR_SIGNATURE =
        ".method private final requestStatsInterceptor(Lokhttp3/Interceptor${'$'}Chain;" +
            "Lcom/discord/resource_usage/DeviceResourceUsageRecorder${'$'}RequestStats;)" +
            "Lokhttp3/Response;"

    /**
     * The blocklist set: rebuilds the shared OkHttp interceptor so requests matching a rule are
     * answered with a synthetic HTTP 204. The replacement body is generated against the target
     * APK, because it has to name members that R8 renames.
     */
    val NETWORK_BLOCKLIST = PatchSet(
        id = "discord_native_blocklist",
        label = "Block tracking and advertising",
        description = "Rebuilds Discord's shared OkHttp interceptor so requests to tracking, advertising, survey and monetization endpoints are answered " +
            "with an empty HTTP 204 instead of being sent, covering ${DiscordBlocklistRules.HOST_RULES.size} host rules and " +
            "${DiscordBlocklistRules.API_RULES.size} API path rules. The three obfuscated OkHttp names the method has to spell out are read from the " +
            "target build, so a release where they cannot be resolved is skipped with a reason rather than patched with another release's names.",
        generator = BlocklistGenerator
    )

    /** Every set this object defines. */
    val ALL = listOf(NETWORK_BLOCKLIST)

    /**
     * This set's item key for [rule], for example `discord_native_blocklist:api:/typing`.
     *
     * The rule's identity is its kind and its pattern, so a saved selection keeps meaning the same
     * rule across releases—the pattern is what the interceptor scans for, and a rule that
     * changes pattern is a different rule.
     */
    fun itemKeyOf(rule: BlocklistRule): String =
        PatchItem.keyOf(NETWORK_BLOCKLIST.id, rule.identity)

    /** Every rule of this set, as selectable items, in the order the interceptor applies them. */
    fun items(): List<PatchItem> = DiscordBlocklistRules.items(NETWORK_BLOCKLIST.id)

    /** The rules [selection] switches on, in table order. */
    fun selectedRules(selection: PatchSelection): List<BlocklistRule> =
        DiscordBlocklistRules.ALL.filter { selection.contains(itemKeyOf(it)) }

    /**
     * This set's rows as the UI shows them: the two gates as locked rows, then every rule with its
     * switch and the reason that switch can be inert.
     *
     * Recomputed from [selection] on every call rather than cached, so turning a covering rule off
     * makes everything it covered live again with nothing to keep in step.
     */
    fun rows(selection: PatchSelection): List<BlocklistRow> = BlocklistCoverage.rows(
        rules = DiscordBlocklistRules.ALL,
        gates = DiscordBlocklistRules.GATES,
        isEnabled = { selection.contains(itemKeyOf(it)) }
    )

    /**
     * Generates the interceptor for the rules [selection] switches on.
     *
     * A selection that names no rule of this set generates nothing, with a reason: the pipeline
     * drops a set with no selected item before it gets here, and a set that is selected but has
     * nothing to scan for would otherwise emit a method that only rebuilds the stock path.
     */
    fun generatedPatches(target: TargetApk, selection: PatchSelection): GeneratedPatches {
        val hostRules =
            DiscordBlocklistRules.HOST_RULES.filter { selection.contains(itemKeyOf(it)) }
        val apiRules = DiscordBlocklistRules.API_RULES.filter { selection.contains(itemKeyOf(it)) }
        if (hostRules.isEmpty() && apiRules.isEmpty()) {
            return GeneratedPatches(
                patches = emptyList(),
                skipReason = "No rule of this set is selected, so there is no blocklist to compile."
            )
        }
        return generate(
            target = target,
            hostRules = hostRules.map { it.pattern },
            apiRules = apiRules.map { it.pattern }
        )
    }

    /** The generator, usable with a selection and without one. */
    private object BlocklistGenerator :
        PatchGenerator,
        SelectivePatchGenerator,
        TargetWrittenGenerator {

        override fun generate(target: TargetApk): GeneratedPatches = generate(
            target = target,
            hostRules = DiscordBlocklistRules.HOST_RULES.map { it.pattern },
            apiRules = DiscordBlocklistRules.API_RULES.map { it.pattern }
        )

        override fun generate(target: TargetApk, selection: PatchSelection): GeneratedPatches =
            generatedPatches(target, selection)
    }

    /**
     * Compiles the interceptor for [hostRules] and [apiRules].
     *
     * The resolution is unchanged from the whole-set path: names this build cannot supply cost the
     * set, not the job.
     */
    private fun generate(
        target: TargetApk,
        hostRules: List<String>,
        apiRules: List<String>
    ): GeneratedPatches = when (val resolution = OkHttpNameResolver.resolve(target)) {
        is OkHttpResolution.Resolved -> GeneratedPatches(
            patches = listOf(
                SmaliPatch(
                    title = "Rebuilding the request interceptor as a network blocklist",
                    explanation = "Replaces the stock resource-usage interceptor with one that matches the request URL against " +
                        "${hostRules.size} host and ${apiRules.size} API-path rules and answers a match with a synthetic empty " +
                        "HTTP 204. The OkHttp constructor, Protocol enum and HTTP/1.1 field it has to name were read from this build.",
                    smaliPath = RECORDER_CLASS,
                    methodSignature = INTERCEPTOR_SIGNATURE,
                    replacementBody = interceptorBody(
                        ctorDescriptor = resolution.names.responseConstructorDescriptor,
                        protocolClass = resolution.names.protocolClass,
                        protocolField = resolution.names.protocolHttp11Field,
                        hostRules = hostRules,
                        apiRules = apiRules
                    )
                )
            )
        )

        is OkHttpResolution.Unresolved -> GeneratedPatches(
            patches = emptyList(),
            skipReason = resolution.reason
        )
    }

    /**
     * Builds the replacement body for `requestStatsInterceptor`.
     *
     * The reference gates two debug blocks on a `BLOCKLIST_LOG` environment variable, which is
     * how it was tuned against the live app. An on-device patcher has no such switch and the
     * shipped desktop build is generated without it, so the body here is what that build's method
     * contains: the block in full, the debug logging absent. Everything else is reproduced as
     * written, down to the blank lines and the label names—a subset changes which rules are
     * emitted and nothing else, so the reference comparison still holds rule for rule.
     *
     * The labels are the interesting part. `:not_proxied` and `:skip_<n>` bracket each API rule so
     * a rule only blocks when the URL is an API call, and every host rule branches to the shared
     * `:block`, which is emitted after the early return—smali resolves forward branches, so the
     * blocked path can be built once at the end of the method instead of per rule.
     *
     * @param ctorDescriptor `Lokhttp3/Response;`'s hand-built constructor, return type included.
     * @param protocolClass The Protocol enum, without `L`/`;`.
     * @param protocolField The Protocol field holding the `HTTP_1_1` constant.
     * @param hostRules The host patterns to emit, in order. Defaults to the whole table.
     * @param apiRules The API path patterns to emit, in order. The `:skip_<n>` labels are numbered
     *   within this list, so it has to match the rules the caller selected.
     */
    fun interceptorBody(
        ctorDescriptor: String,
        protocolClass: String,
        protocolField: String,
        hostRules: List<String> = DiscordBlocklistRules.HOST_RULES.map { it.pattern },
        apiRules: List<String> = DiscordBlocklistRules.API_RULES.map { it.pattern }
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

        for (host in hostRules) {
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

        apiRules.forEachIndexed { index, entry ->
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
