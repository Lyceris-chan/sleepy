package dev.sleepy.app.model

/**
 * One row of the blocklist that the UI shows: a rule with a switch, or a gate without one.
 *
 * @property label The row's name.
 * @property description What the row does, in the user's terms.
 * @property lockedReason Why the switch cannot be used, or null when it can. Two reasons share
 *   this field and they are different statements—see
 *   [BlocklistCoverage.COVERED_REASON_PREFIX] and [BlocklistCoverage.REQUIRED_REASON_PREFIX]. A
 *   rule carries the first, a gate the second.
 */
sealed interface BlocklistRow {
    val label: String
    val description: String
    val lockedReason: String?

    /** True when the user can toggle this row. */
    val switchable: Boolean get() = lockedReason == null
}

/**
 * A blocklist entry, its switch, and why that switch can be inert.
 *
 * @property enabled Whether the rule is switched on.
 * @property coveredBy The enabled rule that makes this one redundant, if there is one. Non-null
 *   whether or not this rule is itself on: a rule that is off is equally redundant when another
 *   rule already blocks every request it matches.
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

/** One of the interceptor's prefix gates: applied to every request, and not switchable. */
data class BlocklistGateRow(val gate: BlocklistGate) : BlocklistRow {
    override val label: String get() = gate.label
    override val description: String get() = gate.description
    override val lockedReason: String get() = gate.reason
}

/**
 * Which blocklist rules are redundant because another enabled rule already blocks their requests.
 *
 * Rules are matched with a plain `contains`, and each matching rule blocks the request by itself,
 * so a rule changes the outcome only if there is a request it blocks that no other enabled rule
 * blocks. [covers] performs that test, and [evaluate] applies it to a whole table.
 *
 * This object does not read the selection: it takes "is this rule switched on" as an argument, so
 * the same call both renders the list and recomputes it after a toggle. Switching a coverer off
 * makes every rule it covered switchable again, with no stored state to keep in step.
 */
object BlocklistCoverage {

    /** How every "this rule is redundant" reason begins. */
    const val COVERED_REASON_PREFIX = "Already covered by"

    /** How every "this row cannot be switched off" reason begins. */
    const val REQUIRED_REASON_PREFIX = "Required"

    /**
     * The reason a covered rule's switch is inert, naming the rule that covers it.
     *
     * The text applies to both states of the covered switch, because coverage does not depend on
     * it: switching a covered rule on adds nothing while the covering rule is on.
     */
    fun coveredReason(coverer: BlocklistRule): String =
        "$COVERED_REASON_PREFIX \"${coverer.pattern}\": every URL this rule matches also " +
            "contains that pattern, and that rule is switched on, so this switch makes no " +
            "difference while it stays on."

    /**
     * Whether [cover] makes [covered] redundant: every URL containing [covered]'s pattern also
     * contains [cover]'s, which, with a plain `contains` match, means [cover]'s pattern is a
     * substring of [covered]'s.
     *
     * Position in the table does not affect the result. If the interceptor tests the coverer
     * first, it blocks the request; if it tests the covered rule first, that rule blocks the
     * request, and the request is blocked either way. Switching the covered rule off therefore
     * changes nothing.
     *
     * The kind is part of the test because the two regimes are not symmetric. A host rule is
     * applied to every URL, so a host rule can cover an API rule: every URL that contains `/api/`
     * is also tested against the host rule. An API rule cannot cover a host rule, even when its
     * pattern is a substring, because a URL that matches the host rule and does not contain
     * `/api/` is not tested against the API rule, and switching the host rule off leaves that
     * request unblocked.
     */
    fun covers(cover: BlocklistRule, covered: BlocklistRule): Boolean {
        if (cover.identity == covered.identity) return false
        if (!covered.pattern.contains(cover.pattern)) return false
        return cover.kind == BlocklistRuleKind.HOST || covered.kind == BlocklistRuleKind.API
    }

    /**
     * Every rule as a row, each naming the rule that covers it, or switchable when none does.
     *
     * Only enabled rules cover, so the rows change as the user toggles. When several enabled
     * rules cover the same one, the first in table order is named, so the reason shown for a row
     * does not change with map iteration order.
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

    /** The switchable rows of [rules], plus the fixed [gates], in table order. */
    fun rows(
        rules: List<BlocklistRule>,
        gates: List<BlocklistGate>,
        isEnabled: (BlocklistRule) -> Boolean
    ): List<BlocklistRow> = gates.map { BlocklistGateRow(it) } + evaluate(rules, isEnabled)
}
