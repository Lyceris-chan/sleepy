package dev.sleepy.app.model

/**
 * One row of the blocklist list the UI shows: a rule with a switch, or a gate without one.
 *
 * @property label the row's name.
 * @property description what the row does, in the user's terms.
 * @property lockedReason why the switch cannot be used, or null when it can. There are exactly
 *   two reasons and they are different claims — see [BlocklistCoverage.COVERED_REASON_PREFIX] and
 *   [BlocklistCoverage.REQUIRED_REASON_PREFIX]. A rule can carry the first, a gate the second.
 */
sealed interface BlocklistRow {
    val label: String
    val description: String
    val lockedReason: String?

    /** True when the user may toggle this row. */
    val switchable: Boolean get() = lockedReason == null
}

/**
 * A blocklist entry, its switch, and why that switch may be inert.
 *
 * @property enabled whether the rule is switched on.
 * @property coveredBy the enabled rule that makes this one redundant, if there is one. Non-null
 *   whether or not this rule is itself on: a rule that is off is just as inert when something
 *   else already answers every request it would have answered.
 */
data class BlocklistRuleRow(
    val rule: BlocklistRule,
    val enabled: Boolean,
    val coveredBy: BlocklistRule? = null
) : BlocklistRow {
    override val label: String get() = rule.pattern
    override val description: String get() = rule.description

    override val lockedReason: String?
        get() = coveredBy?.let { BlocklistCoverage.coveredReason(it) }
}

/** One of the interceptor's prefix gates: always applied, and not a choice. */
data class BlocklistGateRow(val gate: BlocklistGate) : BlocklistRow {
    override val label: String get() = gate.label
    override val description: String get() = gate.description
    override val lockedReason: String get() = gate.reason
}

/**
 * Which blocklist rules are redundant because another enabled rule already answers their
 * requests.
 *
 * Rules are matched with a plain `contains` and each match answers the request on its own, so a
 * rule only does something if there is a request it blocks that no other enabled rule blocks.
 * [covers] is that test, and [evaluate] applies it to a whole table.
 *
 * Nothing here knows about the selection: it takes "is this rule switched on", so the same call
 * both renders the list and recomputes it after a toggle. Turning a coverer off makes everything
 * it covered live again, with no state to keep in step.
 */
object BlocklistCoverage {

    /** How every "this rule is redundant" reason begins. */
    const val COVERED_REASON_PREFIX = "Already covered by"

    /** How every "this row cannot be switched off" reason begins. */
    const val REQUIRED_REASON_PREFIX = "Required"

    /**
     * The reason a covered rule's switch is inert, naming the rule that covers it.
     *
     * Phrased for both states of the covered switch, because coverage does not depend on it: a
     * covered rule that is off would add nothing by being switched on either.
     */
    fun coveredReason(coverer: BlocklistRule): String =
        "$COVERED_REASON_PREFIX \"${coverer.pattern}\": every URL this rule matches also contains " +
            "that pattern, and that rule is switched on, so this switch makes no difference while " +
            "it stays on."

    /**
     * Whether [cover] makes [covered] redundant: every URL containing [covered]'s pattern also
     * contains [cover]'s, which — matching being a plain `contains` — is exactly [cover]'s
     * pattern being a substring of [covered]'s.
     *
     * Position in the table does not come into it. If the coverer is tested first it blocks the
     * request; if it is tested last, the covered rule blocks it first and the coverer would have
     * blocked it anyway. Either way switching the covered rule off changes nothing.
     *
     * The kind is part of the test because the gates are not symmetric. A host rule is applied to
     * every URL, so a host rule can cover an API rule: every `/api/` URL is a URL. An API rule
     * cannot cover a host rule, even when its pattern is a substring — a URL matching the host
     * rule without containing `/api/` is never tested against the API rule at all, and switching
     * the host rule off would leave that request unblocked.
     */
    fun covers(cover: BlocklistRule, covered: BlocklistRule): Boolean {
        if (cover.identity == covered.identity) return false
        if (!covered.pattern.contains(cover.pattern)) return false
        return cover.kind == BlocklistRuleKind.HOST || covered.kind == BlocklistRuleKind.API
    }

    /**
     * Every rule as a row, each naming the rule that covers it, or left switchable when none
     * does.
     *
     * Only enabled rules cover, which is what makes this recompute correctly as the user toggles.
     * When several enabled rules cover the same one, the first in table order is named, so the
     * reason a row shows stays put instead of changing with map iteration order.
     */
    fun evaluate(
        rules: List<BlocklistRule>,
        isEnabled: (BlocklistRule) -> Boolean
    ): List<BlocklistRuleRow> = rules.map { rule ->
        val coverer = rules.firstOrNull { candidate ->
            candidate.identity != rule.identity && isEnabled(candidate) && covers(candidate, rule)
        }
        BlocklistRuleRow(rule = rule, enabled = isEnabled(rule), coveredBy = coverer)
    }

    /** The switchable rows of [rules], plus the always-on [gates], in table order. */
    fun rows(
        rules: List<BlocklistRule>,
        gates: List<BlocklistGate>,
        isEnabled: (BlocklistRule) -> Boolean
    ): List<BlocklistRow> = gates.map { BlocklistGateRow(it) } + evaluate(rules, isEnabled)
}
