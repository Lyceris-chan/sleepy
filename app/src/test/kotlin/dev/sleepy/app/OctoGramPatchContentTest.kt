package dev.sleepy.app

import dev.sleepy.app.patches.OctoGramPatches
import dev.sleepy.app.patches.PatchRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the OctoGram entries say, and that they say it about the build this app can actually patch.
 *
 * An OctoGram set is one switch, so the text is the whole of what the user gets to decide with:
 * a set's label and description are the only thing naming the choice, and an entry's title and
 * explanation — shown in the set card's technical panel and again while the entry runs — are the
 * only thing saying what it does. The two failures guarded against here are the ones this content
 * had: twelve emitters sharing one boilerplate sentence, and entries tagged for a build no source
 * offers, which can never run and read as if they will.
 */
class OctoGramPatchContentTest {

    private val entries = OctoGramPatches.ALL.flatMap { set -> set.smaliPatches.map { set.id to it } }

    @Test
    fun everySetAndEntryHasSomethingToSayForItself() {
        assertEquals("the OctoGram catalogue is eleven sets", 11, OctoGramPatches.ALL.size)
        assertEquals(
            "thirty-two entries: the five a 3.6.0-only script carried are not shipped",
            32,
            entries.size
        )

        OctoGramPatches.ALL.forEach { set ->
            assertTrue("set ${set.id} has no label", set.label.isNotBlank())
            assertTrue("set ${set.id} has no description", set.description.isNotBlank())
            assertTrue("${set.id} is still a set: it needs an entry to apply", set.smaliPatches.isNotEmpty())
        }
        assertEquals(
            "two sets reading the same in the list are one choice as far as the user is concerned",
            OctoGramPatches.ALL.size,
            OctoGramPatches.ALL.map { it.label }.distinct().size
        )

        val blank = entries.filter { (_, patch) -> patch.title.isNullOrBlank() || patch.explanation.isNullOrBlank() }
        assertEquals(
            "every entry needs a title and an explanation: it is the only description there is",
            emptyList<String>(),
            blank.map { (setId, patch) -> "$setId :: ${patch.smaliPath}" }
        )
    }

    /**
     * The anti-boilerplate rule, as a test: an entry may repeat another's explanation only if it
     * says in that same sentence that the two cannot be told apart — which is information, not
     * boilerplate. Twelve emitters sharing "Stubs log emitter to prevent logcat writes" is what
     * this exists to prevent.
     */
    @Test
    fun noTwoEntriesReuseOneSentenceWithoutSayingTheyAreIndistinguishable() {
        val repeated = entries
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
    fun everyLoggerEntrySaysWhatThatEmitterWrites() {
        val emitters = OctoGramPatches.OCTO_LOGGER.smaliPatches.filter { it.smaliPath == "cn8.smali" }
        val uploaders = OctoGramPatches.OCTO_LOGGER.smaliPatches.filter { it.smaliPath != "cn8.smali" }

        assertEquals("the log class has twelve emitters, one entry each", 12, emitters.size)
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

        // The levels are the one thing the letters do not say, so they are what the titles say.
        val titles = emitters.mapNotNull { it.title }.joinToString(" ").lowercase()
        listOf("debug", "info", "warning", "error").forEach { level ->
            assertTrue("no emitter says it writes at $level: $titles", titles.contains(level))
        }

        // Four emitters take a tag and a message and differ in nothing else. Each has to admit it,
        // rather than describing itself in words that pretend to a distinction it does not have.
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

        val sink = emitters.first { it.methodSignature.orEmpty().startsWith(".method public static m(") }
        assertTrue(
            "the sink is where the log is written, so it has to say where to",
            sink.explanation.orEmpty().contains("logcat") && sink.explanation.orEmpty().contains("log file")
        )
        val redacting = emitters.first { it.methodSignature.orEmpty().startsWith(".method public static n(") }
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

    @Test
    fun nothingIsTaggedForABuildNoSourceOffers() {
        val tagged = entries.mapNotNull { (_, patch) -> patch.versionTag }
        assertTrue("version tags are how the obfuscated names are kept to one build", tagged.isNotEmpty())
        assertEquals(
            "an entry tagged for another build could never run, so none may be shipped",
            emptyList<String>(),
            tagged.filter { it != OctoGramPatches.REGISTERED_BUILD }
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
        val stillPorted = entries.map { (_, patch) -> patch.smaliPath }.filter { it in dropped }
        assertEquals("these target a build this app cannot offer", emptyList<String>(), stillPorted)

        val markedAsKept = entries.filter { (_, patch) -> patch.explanation.orEmpty().contains("audit trail") }
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
            "a subscriber loses the screen where they manage their subscription, which they have to be told",
            description.contains("subscription")
        )
    }

    @Test
    fun theCatalogueAndTheRegistryAgreeOnWhatIsRegistered() {
        OctoGramPatches.ALL.forEach { set ->
            assertEquals(
                "a set in the catalogue has to be the registered set, not a copy of it",
                set,
                PatchRegistry.get(set.id)
            )
        }
    }

    private companion object {
        /** The phrase an explanation has to contain to reuse another's words on purpose. */
        const val INDISTINGUISHABLE = "indistinguishable"
    }
}
