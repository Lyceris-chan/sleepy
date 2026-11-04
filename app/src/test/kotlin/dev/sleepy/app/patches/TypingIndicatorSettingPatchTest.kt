package dev.sleepy.app.patches

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Settings-overview edit that removes the Typing Indicator row.
 *
 * The overview builds a dense array of settings entries and filters nullish entries out before
 * rendering. The replacement keeps the array shape and nulls the Typing Indicator slot, so the
 * filter drops that row and leaves the other sections alone. The rest of the function, including
 * the stored setting and the editor behind it, is unchanged.
 */
class TypingIndicatorSettingPatchTest {

    private val patch = DiscordHermesBundlePatch.PATCHES
        .single { it.functionId == SETTINGS_OVERVIEW }

    @Test
    fun theSettingsListBodyStillReplacesTheWholeFunction() {
        assertEquals(SETTINGS_OVERVIEW_BODY_SIZE, patch.replacement.size)
    }

    @Test
    fun theTypingIndicatorSlotLoadsNull() {
        assertArrayEquals(
            byteArrayOf(0x94.toByte(), 0x08, 0x7e, 0x7e, 0x7e, 0x7e),
            patch.replacement.copyOfRange(TYPING_SLOT_OFFSET, TYPING_SLOT_OFFSET + 6)
        )
    }

    @Test
    fun theOtherSettingsRowsAreUntouched() {
        val body = patch.replacement
        val webBrowser = byteArrayOf(0x45, 0x08, 0x07, 0x20, 0x41, 0x35)
        val notifications = byteArrayOf(0x45, 0x08, 0x07, 0x21, 0xf6.toByte(), 0x17)
        assertTrue(
            "the row after the removed one must still fetch WEB_BROWSER",
            body.copyOfRange(WEB_BROWSER_OFFSET, WEB_BROWSER_OFFSET + 6)
                .contentEquals(webBrowser)
        )
        assertTrue(
            "the following rows must retain their reads",
            body.copyOfRange(NOTIFICATIONS_OFFSET, NOTIFICATIONS_OFFSET + 6)
                .contentEquals(notifications)
        )
    }

    private companion object {
        const val SETTINGS_OVERVIEW = 116092
        const val SETTINGS_OVERVIEW_BODY_SIZE = 1259
        const val TYPING_SLOT_OFFSET = 0x248
        const val WEB_BROWSER_OFFSET = 0x252
        const val NOTIFICATIONS_OFFSET = 0x25c
    }
}
