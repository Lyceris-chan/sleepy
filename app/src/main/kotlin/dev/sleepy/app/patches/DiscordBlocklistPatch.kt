package dev.sleepy.app.patches

import dev.sleepy.app.engine.OkHttpNameResolver
import dev.sleepy.app.engine.OkHttpProtocolNames
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
 * The network blocklist interceptor for Discord.
 *
 * One stock interceptor is shared by every OkHttp client this set concerns. Its body is replaced
 * with one that matches the request URL against [DiscordBlocklistRules] and answers a match with
 * a synthetic HTTP 204 — a dropped response would make the client retry without a limit — so the
 * request is never sent. 353.2.0 registers that interceptor on OkHttp's network list through two
 * clients; [DiscordNativePatches.INTERCEPTORS] moves those registrations to the application list,
 * where answering without calling `proceed` is legal.
 *
 * The body cannot be a constant: it constructs an `okhttp3.Response` by hand, so it is generated
 * against the selected APK's own class and member names ([OkHttpNameResolver]). The synthetic
 * response carries the build's own empty `Headers` and zero-length `ResponseBody` instances,
 * because callers dereference the headers without a null check and the React Native bridge
 * reports a null body as a failed request.
 */
object DiscordBlocklistPatch {

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
            "${DiscordBlocklistRules.API_RULES.size} API path rules. The obfuscated OkHttp types and members the method has to spell out are read from the " +
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
                        "HTTP 204. Every OkHttp type and member it names was read from this build.",
                    smaliPath = "${resolution.names.interceptorClass}.smali",
                    methodSignature = resolution.names.interceptorSignature,
                    replacementBody = interceptorBody(
                        names = resolution.names,
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
     * Builds the replacement body for the stock interceptor.
     *
     * The body reads the request URL, walks the host and API-path rules, and answers a match with
     * a synthetic HTTP 204. `:not_proxied` and `:skip_<n>` bracket each API rule, and every host
     * rule branches to the shared `:block`, so the blocked response is constructed once.
     *
     * Every type and member name comes from [names], which was read from the target APK. That is
     * what makes this work on a build where R8 renamed the whole OkHttp surface.
     *
     * @param names The resolved OkHttp shape.
     * @param hostRules Host patterns to emit, in order. Defaults to the whole table.
     * @param apiRules API path patterns to emit; `:skip_<n>` labels are numbered within this list.
     */
    fun interceptorBody(
        names: OkHttpProtocolNames,
        hostRules: List<String> = DiscordBlocklistRules.HOST_RULES.map { it.pattern },
        apiRules: List<String> = DiscordBlocklistRules.API_RULES.map { it.pattern }
    ): String {
        val request = names.requestType
        val chain = names.chainType
        val response = names.responseType
        val protocol = "L${names.protocolClass};"
        val chainCall = if (names.chainIsInterface) "invoke-interface" else "invoke-virtual"
        val lines = mutableListOf(
            names.interceptorSignature,
            "    .locals 24",
            "",
            "    move-object/from16 v3, p1",
            "",
            "    iget-object v0, v3, ${chain}->${names.chainRequestField}:$request",
            ""
        )
        lines += listOf(
            "    iget-object v1, v0, ${request}->${names.requestUrlField}:" +
                names.requestUrlFieldType,
            "",
            "    check-cast v1, ${names.requestUrlType}",
            ""
        )
        lines += listOf(
            "    invoke-virtual {v1}, ${names.requestUrlType}->toString()Ljava/lang/String;",
            "",
            "    move-result-object v1",
            "",
            "    # Match the request URL against tracking and monetisation entries.",
            "",
            "    # v6 = is this a Discord API URL? Path rules only apply there.",
            "    const-string v2, \"/api/\"",
            "",
            "    invoke-virtual {v1, v2}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z",
            "",
            "    move-result v6",
            "",
            "    # The media proxy embeds the origin URL after /external/<hmac>/, so a",
            "    # proxied image whose source contains /api/ would otherwise be blocked.",
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
            "    $chainCall {v3, v0}, ${chain}->${names.chainProceedMethod}($request)$response",
            "",
            "    move-result-object v0",
            "",
            "    return-object v0",
            "",
            "    # Shared block: synthetic HTTP 204 No Content.",
            "    :block",
            "    new-instance v8, $response",
            "",
            "    move-object v9, v0",
            "",
            "    sget-object v10, $protocol->${names.protocolHttp11Field}:$protocol",
            "",
            "    const-string v11, \"No Content\"",
            "",
            "    const/16 v12, 0xcc",
            "",
            "    const/4 v13, 0x0",
            "",
            "    # A real response always carries a Headers instance. Callers read it",
            "    # without a null check, including the React Native networking bridge,",
            "    # so the synthetic response reads OkHttp's own empty headers constant.",
            "    sget-object v14, L${names.emptyResponseClass};->" +
                "${names.emptyHeadersField}:${names.emptyHeadersFieldType}",
            "",
            "    # A 204 has no content, but the React Native bridge reports a null",
            "    # body as a failed request before it reports the response. Use the",
            "    # same zero-length body OkHttp uses for a no-content response.",
            "    sget-object v15, L${names.emptyResponseClass};->" +
                "${names.emptyBodyField}:${names.emptyBodyFieldType}",
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
            "    invoke-direct/range {v8 .. v23}, ${response}-><init>${names.responseConstructorDescriptor}",
            "",
            "    move-object v0, v8",
            "",
            "    return-object v0",
            ".end method"
        )

        return lines.joinToString("\n")
    }
}
