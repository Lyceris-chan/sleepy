package dev.sleepy.app.ui

import dev.sleepy.app.testing.source
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every screen title and section title carries heading semantics.
 *
 * Heading navigation is how a screen reader moves between sections; a title without the semantics
 * forces the reader to walk every row. Each title is checked in the source that declares it,
 * because the screens need a device to compose.
 */
class ScreenHeadingsTest {

    /**
     * One heading: the literal that identifies the title in [relative], and the anchor to search
     * from—the quoted text, or a code anchor where the title is not a literal.
     */
    private data class Site(val relative: String, val anchor: String, val label: String)

    private val sites = listOf(
        Site("ui/screens/HomeScreen.kt", "text = \"sleepy\"", "the home title"),
        Site("ui/screens/HomeScreen.kt", "text = \"Target applications\"", "the target list title"),
        Site(
            "ui/screens/PatchSelectScreen.kt",
            "text = source?.displayName ?: \"Select patches\"",
            "the selection title"
        ),
        Site(
            "ui/screens/PatchSelectScreen.kt",
            "text = \"Configure modding pipeline\"",
            "the pipeline title"
        ),
        Site(
            "ui/screens/PatchSelectScreen.kt",
            "text = \"Permissions\"",
            "the permission section title"
        ),
        Site(
            "ui/screens/SettingsScreen.kt",
            "text = \"Settings & Transparency\"",
            "the settings title"
        ),
        Site(
            "ui/screens/SettingsScreen.kt",
            "private fun SettingsSectionTitle",
            "the settings group title"
        ),
        Site("ui/screens/ProgressScreen.kt", "text = \"What changed\"", "the step list title"),
        Site("ui/screens/ResultScreen.kt", "text = presentation.headline", "the result headline"),
        Site("ui/screens/ResultScreen.kt", "private fun SectionLabel", "the result card titles")
    )

    @Test
    fun everyTitleTheAuditListedIsAHeading() {
        sites.forEach { site ->
            val source = source("app/src/main/kotlin/dev/sleepy/app/${site.relative}")
            val anchor = source.indexOf(site.anchor)
            assertTrue("${site.label} was not found in ${site.relative}", anchor >= 0)
            assertTrue(
                "${site.label} is not a heading in ${site.relative}",
                source.indexOf("heading()", anchor) in anchor until anchor + 700
            )
        }
    }

    /** The file at [relative], found upward from the working directory the tests run in. */
}
