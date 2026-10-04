package dev.sleepy.app.patches

import dev.sleepy.app.model.SmaliPatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The user-facing content of the OctoGram patch items.
 *
 * Every item has a label, every edit has a title and an explanation that says what the edit does
 * rather than restating its name, and every entry is tagged for a build some source offers.
 * Explanations may live in the item detail or in the set's technical panel, but a path to each
 * one from the item must exist.
 */
class OctoGramPatchContentTest {

    private val sets = OctoGramPatches.ALL
    private val entries =
        sets.flatMap { set -> OctoGramPatchItems.entries(set.id).map { set.id to it } }
    private val edits = entries.flatMap { (setId, entry) -> entry.patches.map { setId to it } }

    @Test
    fun everySetAndItemHasSomethingToSayForItself() {
        assertEquals("the OctoGram catalog is sixteen sets", 16, sets.size)
        assertEquals("nineteen items over them", 19, entries.size)
        assertEquals("and forty-three edits behind the items", 43, edits.size)

        sets.forEach { set ->
            assertTrue("set ${set.id} has no label", set.label.isNotBlank())
            assertTrue("set ${set.id} has no description", set.description.isNotBlank())
            assertTrue(
                "${set.id} still carries edits of its own, which the engine applies whether or " +
                    "not they are selected",
                set.smaliPatches.isEmpty() && set.hermesPatches.isEmpty()
            )
        }
        assertEquals(
            "two sets reading the same in the list are one choice as far as the user is concerned",
            sets.size,
            sets.map { it.label }.distinct().size
        )
        assertEquals(
            "and one row per item, so no item of any set may be missing from the table",
            entries.size,
            entries.map { (setId, entry) -> OctoGramPatchItems.itemKeyOf(setId, entry.identity) }
                .distinct()
                .size
        )

        val unreadable = entries.filter { (_, entry) ->
            entry.label.isBlank() || entry.group.isBlank() || entry.description.isBlank()
        }
        assertEquals(
            "every item needs a name, a heading and a line saying what it does: there is " +
                "nothing else on its row",
            emptyList<String>(),
            unreadable.map { (setId, entry) -> "$setId:${entry.identity}" }
        )

        val unexplained = edits.filter {
            (_, patch) -> patch.title.isNullOrBlank() || patch.explanation.isNullOrBlank()
        }
        assertEquals(
            "every edit needs a title and an explanation: it is what the panel under the " +
                "set prints",
            emptyList<String>(),
            unexplained.map { (setId, patch) -> "$setId :: ${patch.smaliPath}" }
        )
    }

    /**
     * Every edit stays reachable from the item that applies it: an item over several edits lists
     * each of them by title in its own detail, and the set's technical panel prints all of them in
     * full. Text may move between the two places, and has moved: none of it may be dropped.
     */
    @Test
    fun everyItemIsReadableBeforeItIsApplied() {
        sets.forEach { set ->
            PatchItemCatalog.itemsOf(set.id).forEach { item ->
                val entry = OctoGramPatchItems.entries(set.id)
                    .first { it.identity == item.identity }
                val detail = OctoGramPatchItems.detail(item).orEmpty()
                assertTrue("${item.key} has no text behind its target", detail.isNotBlank())
                assertTrue(
                    "${item.key} does not say which saved key stands for it",
                    detail.contains(item.key)
                )
                if (entry.patches.size == 1) {
                    val patch = entry.patches.single()
                    assertTrue(
                        "${item.key} is the only place its edit can be read, so its explanation " +
                            "has to be there",
                        detail.contains(patch.title.orEmpty()) &&
                            detail.contains(patch.explanation.orEmpty())
                    )
                } else {
                    entry.patches.forEach { patch ->
                        assertTrue(
                            "${item.key} applies ${patch.title} and does not say so",
                            detail.contains(patch.title.orEmpty())
                        )
                    }
                }
            }
        }
    }

    /**
     * The anti-boilerplate rule, as a test: an edit may repeat another's explanation only if it
     * states in that same sentence that the two cannot be told apart—which is information, not
     * boilerplate. Twelve emitters sharing "Stubs log emitter to prevent logcat writes" is the
     * case this test prevents.
     */
    @Test
    fun noTwoEditsReuseOneSentenceWithoutSayingTheyAreIndistinguishable() {
        val repeated = edits
            .groupBy { (_, patch) -> patch.explanation }
            .filter { (explanation, sharing) ->
                sharing.size > 1 && explanation?.contains(INDISTINGUISHABLE) != true
            }
        assertEquals(
            "these explanations are boilerplate, repeated without saying why",
            emptyList<String>(),
            repeated.values.flatten().map { (setId, patch) -> "$setId :: ${patch.title}" }
        )
    }

    @Test
    fun everyLoggerEditSaysWhatThatEmitterWrites() {
        val emitters = editsOf(OctoGramPatches.OCTO_LOGGER.id, OctoGramPatches.LOG_EMITTERS)
        val uploaders = editsOf(OctoGramPatches.OCTO_LOGGER.id, OctoGramPatches.LOG_UPLOADERS)

        assertEquals("the log class has twelve emitters, one edit each", 12, emitters.size)
        assertEquals("and the app-log path has five uploaders", 5, uploaders.size)
        assertEquals(
            "every emitter's explanation has to be its own",
            emitters.size,
            emitters.map { it.explanation }.distinct().size
        )
        assertEquals(
            "every emitter's title has to be its own",
            emitters.size,
            emitters.map { it.title }.distinct().size
        )

        // The letters do not state the levels, so the titles have to.
        val titles = emitters.mapNotNull { it.title }.joinToString(" ").lowercase()
        listOf("debug", "info", "warning", "error").forEach { level ->
            assertTrue("no emitter says it writes at $level: $titles", titles.contains(level))
        }

        // Four emitters take a tag and a message and differ in nothing else. Each explanation has
        // to state that, rather than describe a distinction the emitters do not have.
        val fourIdenticalButForLevel = emitters.filter {
            it.methodSignature.orEmpty().contains("(Ljava/lang/String;Ljava/lang/String;)V")
        }
        assertEquals(4, fourIdenticalButForLevel.size)
        fourIdenticalButForLevel.forEach { emitter ->
            assertTrue(
                "${emitter.title} differs from its siblings only in level, and has to say so",
                emitter.explanation.orEmpty().contains("differ only in the level")
            )
        }

        val sink = emitters.first {
            it.methodSignature.orEmpty().startsWith(".method public static m(")
        }
        assertTrue(
            "the sink is where the log is written, so it has to say where to",
            sink.explanation.orEmpty().contains("logcat") &&
                sink.explanation.orEmpty().contains("log file")
        )
        val redacting = emitters.first {
            it.methodSignature.orEmpty().startsWith(".method public static n(")
        }
        assertTrue(
            "the redacting emitter is the one that keeps secrets out, and has to say so",
            redacting.explanation.orEmpty().contains("redact")
        )
        assertEquals(
            "the five uploaders have to be told apart too",
            uploaders.size,
            uploaders.map { it.explanation }.distinct().size
        )
    }

    /**
     * The two switches this app gained last: what each is, and why it is one switch rather than
     * several.
     *
     * In both cases a partial application leaves the app in a state it is not designed for: the
     * crash handler keeps installing itself regardless of the logging flag, and the premium rows
     * have to go together or a different premium row takes the place of the row that was hidden.
     * The wording has to state that, and the table has to offer each as a single item.
     */
    @Test
    fun theTwoNewSwitchesAreOneChoiceEachAndSayWhy() {
        val crashItems = PatchItemCatalog.itemsOf(OctoGramPatches.CRASH_REPORTER.id)
        assertEquals("the crash reporter is one decision", 1, crashItems.size)
        assertEquals(1, OctoGramPatchItems.patches(OctoGramPatches.CRASH_REPORTER.id).size)

        val crashText = listOf(
            OctoGramPatches.CRASH_REPORTER.label,
            OctoGramPatches.CRASH_REPORTER.description,
            crashItems.single().description
        ).joinToString(" ")
        assertTrue(
            "the crash switch has to say what it stops: $crashText",
            crashText.contains("crash log")
        )
        assertTrue(
            "and that the notification is what the user would have seen",
            crashText.contains("OctoGram just crashed!")
        )
        assertTrue(
            "a user who has switched off logging has to be told why this is a switch of its own",
            crashText.contains("logging flag") || crashText.contains("regardless of")
        )

        val premiumItems = PatchItemCatalog.itemsOf(OctoGramPatches.PREMIUM_SETTINGS.id)
        assertEquals(
            "the premium rows are one decision, however many edits they take",
            1,
            premiumItems.size
        )
        assertEquals(4, OctoGramPatchItems.patches(OctoGramPatches.PREMIUM_SETTINGS.id).size)

        val premiumText = listOf(
            OctoGramPatches.PREMIUM_SETTINGS.label,
            OctoGramPatches.PREMIUM_SETTINGS.description,
            premiumItems.single().description
        ).joinToString(" ")
        listOf("Premium row", "Send a Gift", "premium-sections").forEach { row ->
            assertTrue(
                "the rows this hides have to be named: $row is not in $premiumText",
                premiumText.contains(row)
            )
        }
        assertTrue(
            "and the wording has to say the three are one switch, because they are: $premiumText",
            premiumText.contains("one switch")
        )
        listOf("Stars", "TON", "Business").forEach { product ->
            assertTrue(
                "what stays visible is what stops the switch reading as a promise it does not " +
                    "keep: $product",
                premiumText.contains(product)
            )
        }
    }

    @Test
    fun nothingIsTaggedForABuildNoSourceOffers() {
        val tagged = edits.mapNotNull { (_, patch) -> patch.versionTag }
        assertEquals(
            "thirty-nine edits name the build they were derived from; the four Firebase " +
                "registrars name none",
            39,
            tagged.size
        )
        assertEquals(
            "an edit tagged for another build could never run, so none may be shipped",
            emptyList<String>(),
            tagged.filter { it != OctoGramPatches.REGISTERED_BUILD }
        )

        // The four without a tag are the Firebase registrars, which ship identically in every build
        // and so need no version to be found in. They are the only edits that a build this app
        // cannot offer may still receive, and they may not become a route for the rest to apply.
        val untagged = edits.filter { (_, patch) -> patch.versionTag == null }
        assertEquals(4, untagged.size)
        assertEquals(
            "an untagged edit may only be one of the Firebase registrars",
            emptyList<String>(),
            untagged
                .filter { (setId, _) -> !setId.startsWith("octogram_firebase") }
                .map { (setId, patch) -> "$setId :: ${patch.smaliPath}" }
        )

        // The classes the 3.6.0-only script edits, none of which may appear: three of them are not
        // in the 3.6.1 build at all, and the other two are different classes there.
        val dropped = listOf(
            "org/telegram/ui/o.smali",
            "org/telegram/messenger/m0.smali",
            "y5l.smali",
            "be6.smali",
            "hxk.smali"
        )
        val stillPorted = edits.map { (_, patch) -> patch.smaliPath }.filter { it in dropped }
        assertEquals("these target a build this app cannot offer", emptyList<String>(), stillPorted)

        val markedAsKept = edits.filter {
            (_, patch) -> patch.explanation.orEmpty().contains("audit trail")
        }
        assertEquals(
            "a dead entry may not be listed as kept for the record either: it is not shipped",
            emptyList<String>(),
            markedAsKept.map { (_, patch) -> patch.title.orEmpty() }
        )
    }

    @Test
    fun thePaywallRemovalSaysItIsPartial() {
        val premium = OctoGramPatches.PREMIUM_UPSELL
        assertTrue(
            "the label is what the user decides on: ${premium.label}",
            premium.label.contains("partial")
        )

        val description = premium.description
        assertTrue(
            "the reference's own count belongs in the description: $description",
            description.contains("53") && description.contains("61")
        )
        assertTrue(
            "and so does what still renders, or the description implies a completeness it lacks",
            description.contains("PremiumFeatureCell") && description.contains("LimitPreviewView")
        )
        assertTrue(
            "a subscriber loses the screen where they manage their subscription, which they " +
                "have to be told",
            description.contains("subscription")
        )
    }

    @Test
    fun theCatalogAndTheRegistryAgreeOnWhatIsRegistered() {
        OctoGramPatches.ALL.forEach { set ->
            assertEquals(
                "a set in the catalog has to be the registered set, not a copy of it",
                set,
                PatchRegistry.get(set.id)
            )
        }
    }

    /**
     * The edits of one set that belong to the given group, in the order the set applies them.
     *
     * The group's own size is asserted at each call site, so a group that this returns nothing for
     * fails there instead of passing as an empty list.
     */
    private fun editsOf(setId: String, group: List<SmaliPatch>): List<SmaliPatch> =
        OctoGramPatchItems.entries(setId).flatMap { it.patches }.filter { patch -> patch in group }

    private companion object {
        /** The phrase an explanation has to contain to reuse another's words on purpose. */
        const val INDISTINGUISHABLE = "indistinguishable"
    }
}
