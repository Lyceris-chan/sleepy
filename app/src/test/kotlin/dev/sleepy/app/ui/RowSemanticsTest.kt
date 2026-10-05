package dev.sleepy.app.ui

import dev.sleepy.app.testing.source
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The accessibility contract of the list-row components.
 *
 * A clickable container merges its descendants into one node, so a row that names its action in
 * `onClickLabel` and repeats the same words in a child icon's `contentDescription` announces the
 * action twice. The tests read the component sources and check that each row names its action
 * once, and that an expandable step reports its role and state.
 */
class RowSemanticsTest {

    /**
     * Every row names its action once: the clickable container keeps the `onClickLabel`, and the
     * glyphs inside it carry no description of their own.
     */
    @Test
    fun noRowNamesItsActionTwice() {
        val card = source(PATCH_SECTION_CARD)
        val itemRow = source(PATCH_ITEM_ROW)
        val stepLog = source(STEP_LOG_ITEM)

        assertTrue(
            "the disclosure row no longer keeps the action in its onClickLabel",
            card.contains("clickable(onClickLabel = onClickLabel, onClick = onClick)")
        )
        assertFalse(
            "the disclosure glyph repeats the row's onClickLabel as its own description",
            card.contains("contentDescription = onClickLabel")
        )
        for ((path, text) in listOf(PATCH_ITEM_ROW to itemRow, STEP_LOG_ITEM to stepLog)) {
            val repeated = describedStrings(text) intersect actionLabels(text)
            assertTrue(
                "$path gives an icon the row's action as its description: $repeated",
                repeated.isEmpty()
            )
        }
    }

    /**
     * An expandable step reads as a button that names the action and reports whether its detail is
     * showing. The status badge stays the one exception: it replaces its subtree with the status
     * word, so the icon inside it contributes no second node.
     */
    @Test
    fun theStepLogItemAnnouncesItsRoleAndExpansionState() {
        val stepLog = source(STEP_LOG_ITEM)

        assertTrue(
            "the expandable step exposes no control role",
            stepLog.contains("role = Role.Button")
        )
        val actions = valuesAfter(stepLog, "onClickLabel =").joinToString(" ")
        assertTrue(
            "the expandable step does not name the action that shows its detail",
            actions.contains("\"Show technical detail\"")
        )
        assertTrue(
            "the expandable step does not name the action that hides its detail",
            actions.contains("\"Hide technical detail\"")
        )
        val states = valuesAfter(stepLog, "stateDescription =").joinToString(" ")
        assertTrue(
            "the expandable step does not announce its expanded state",
            states.contains("\"Expanded\"")
        )
        assertTrue(
            "the expandable step does not announce its collapsed state",
            states.contains("\"Collapsed\"")
        )
        assertTrue(
            "the status badge no longer carries its single label",
            stepLog.contains("clearAndSetSemantics { contentDescription = statusLabel }")
        )
    }

    /**
     * The value of every `marker` occurrence: from the marker to the comma or bracket that ends
     * it.
     */
    private fun valuesAfter(source: String, marker: String): List<String> {
        val values = mutableListOf<String>()
        var index = source.indexOf(marker)
        while (index >= 0) {
            var depth = 0
            var end = index + marker.length
            while (end < source.length) {
                when (source[end]) {
                    '(' -> depth++
                    ')' -> if (depth == 0) break else depth--
                    ',' -> if (depth == 0) break
                }
                end++
            }
            values += source.substring(index, end)
            index = source.indexOf(marker, end)
        }
        return values
    }

    /** The strings that the file passes to `contentDescription`. */
    private fun describedStrings(source: String): Set<String> =
        valuesAfter(source, "contentDescription =").flatMap { stringLiterals(it) }.toSet()

    /** The strings that the file passes to `onClickLabel`. */
    private fun actionLabels(source: String): Set<String> =
        valuesAfter(source, "onClickLabel =").flatMap { stringLiterals(it) }.toSet()

    /** The quoted strings in `region`, without their quotes. */
    private fun stringLiterals(region: String): List<String> =
        QUOTED.findAll(region).map { it.value.trim('"') }.toList()

    private companion object {
        const val COMPONENTS = "app/src/main/kotlin/dev/sleepy/app/ui/components"
        const val PATCH_SECTION_CARD = "$COMPONENTS/PatchSectionCard.kt"
        const val PATCH_ITEM_ROW = "$COMPONENTS/PatchItemRow.kt"
        const val STEP_LOG_ITEM = "$COMPONENTS/StepLogItem.kt"

        val QUOTED = Regex("\"[^\"]*\"")
    }
}
