package dev.sleepy.app.model

/**
 * Which of the interceptor's two matching regimes a rule belongs to.
 *
 * The distinction is structural, not cosmetic: a [HOST] rule is tested against every request URL,
 * while an [API] rule is tested only against URLs that already contain `/api/` and do not look
 * proxied. That is what decides whether one rule can make another redundant — see
 * [BlocklistCoverage.covers].
 */
enum class BlocklistRuleKind {
    /** A host (or host prefix) matched anywhere in the URL, on every request. */
    HOST,

    /** A path fragment, matched only on Discord API URLs. */
    API
}

/**
 * One entry of the network blocklist: a pattern the interceptor scans for, and what blocking it
 * prevents.
 *
 * @property kind which matching regime the entry is applied under.
 * @property pattern the literal substring the interceptor looks for. This *is* the rule — it is
 *   what is compiled into the interceptor and what makes two entries the same entry, which is why
 *   it is also the entry's stable identity rather than a label or an index.
 * @property description what blocking this prevents, in the user's terms.
 */
data class BlocklistRule(
    val kind: BlocklistRuleKind,
    val pattern: String,
    val description: String
) {
    /**
     * The rule's identity within the blocklist, and the second half of its item key.
     *
     * Kind and pattern, e.g. `api:/typing` or `host:sentry.io`: a pattern may legitimately appear
     * under both regimes, and they are then two different rules with two different switches.
     */
    val identity: String = "${kind.name.lowercase()}:$pattern"
}

/**
 * A test the interceptor applies to every request before it consults any rule, and which is not
 * itself a rule.
 *
 * Gates are not switchable. They are what confines the rules to the requests they were written
 * for, so a build that dropped one would not block less, it would block the wrong things — an
 * attachment named `track1.mp3` or a proxied image whose source path contains `/api/` and a
 * blocked word.
 *
 * @property pattern the literal the gate tests for.
 * @property label the gate's name in the UI.
 * @property description what the gate is, in the user's terms.
 * @property reason why the row cannot be switched off. Stated separately from a covered rule's
 *   reason, because the two are different claims: a covered rule is inert, while a gate is
 *   load-bearing.
 */
data class BlocklistGate(
    val pattern: String,
    val label: String,
    val description: String,
    val reason: String
)
