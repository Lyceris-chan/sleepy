package dev.sleepy.app.model

/**
 * Which of the interceptor's two matching regimes a rule belongs to.
 *
 * The two kinds are tested differently: a [HOST] rule is tested against every request URL, while
 * an [API] rule is tested only against URLs that contain `/api/` and are not recognized as
 * proxied. This distinction determines whether one rule can make another redundant—see
 * [BlocklistCoverage.covers].
 */
enum class BlocklistRuleKind {
    /** A host (or host prefix) matched anywhere in the URL, on every request. */
    HOST,

    /** A path fragment, matched only on Discord API URLs. */
    API
}

/**
 * One entry of the network blocklist: a pattern the interceptor searches for, and what blocking it
 * prevents.
 *
 * @property kind Which matching regime the entry is applied under.
 * @property pattern The literal substring the interceptor searches for. The pattern is the rule:
 *   it is what is compiled into the interceptor and what makes two entries the same entry, which
 *   is why it is also the entry's stable identity rather than a label or an index.
 * @property description What blocking this prevents, in the user's terms.
 */
data class BlocklistRule(
    val kind: BlocklistRuleKind,
    val pattern: String,
    val description: String
) {
    /**
     * The rule's identity within the blocklist, and the second half of its item key.
     *
     * Kind and pattern, for example `api:/typing` or `host:sentry.io`: a pattern can legitimately
     * appear under both regimes, and the two are then different rules with different switches.
     */
    val identity: String = "${kind.name.lowercase()}:$pattern"
}

/**
 * A test the interceptor applies to every request before it applies any rule, and which is not
 * itself a rule.
 *
 * Gates are not switchable. They confine the rules to the requests the rules were written for, so
 * dropping one does not block fewer requests; it blocks requests outside the intended scope, such
 * as an attachment named `track1.mp3` or a proxied image whose source path contains `/api/` and a
 * blocked word.
 *
 * @property pattern The literal the gate tests for.
 * @property label The gate's name in the UI.
 * @property description What the gate is, in the user's terms.
 * @property reason Why the row cannot be switched off. Stated separately from a covered rule's
 *   reason, because the two are different statements: a covered rule is redundant, while a gate
 *   is applied to every request.
 */
data class BlocklistGate(
    val pattern: String,
    val label: String,
    val description: String,
    val reason: String
)
