package dev.sleepy.app.patches

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The message-card patch keeps the parts of a message that carry meaning.
 *
 * `createMessageContent` reads eight display options at the top of its body. The Reseam-derived
 * replacement forces all eight false, which also removes embed and link cards and every reply
 * preview - including the reference that shows which message a reply answers. Those two reads
 * are restored to the bundle's original `GetById` instructions; the six that hide reaction
 * chips, gift cards, activity invites and emoji/GIF animation stay forced off.
 *
 * The offsets and bytes are those of the 354.1 body the table carries. The test is deliberately
 * byte-level: no fixture is needed, and a future retarget that regenerates the body must make
 * the same decision explicitly rather than silently hiding replies again.
 */
class MessageCardPatchTest {

    private val patch = DiscordHermesBundlePatch.PATCHES
        .single { it.functionId == CREATE_MESSAGE_CONTENT }

    @Test
    fun theBodyStillReplacesTheWholeFunction() {
        assertEquals(CREATE_MESSAGE_CONTENT_BODY_SIZE, patch.replacement.size)
    }

    @Test
    fun embedsAndReplyPreviewsAreReadFromTheMessage() {
        assertBytes(
            "renderEmbeds must stay a GetById read",
            GET_BY_ID_RENDER_EMBEDS,
            RENDER_EMBEDS_OFFSET
        )
        assertBytes(
            "renderReplies must stay a GetById read",
            GET_BY_ID_RENDER_REPLIES,
            RENDER_REPLIES_OFFSET
        )
    }

    @Test
    fun theDeclutterReadsStayForcedOff() {
        val forcedFalse = mapOf(
            "renderReactions" to 139,
            "animateEmoji" to 169,
            "gifAutoPlay" to 181,
            "renderGiftCode" to 203,
            "renderActivityInstanceEmbed" to 209,
            "renderActivityInviteEmbed" to 215
        )
        for ((name, offset) in forcedFalse) {
            val body = patch.replacement
            assertEquals(
                "$name must load false at $offset",
                LOAD_CONST_FALSE,
                body[offset].toInt() and 0xFF
            )
            assertTrue(
                "$name must stay padded with AsyncBreakCheck after $offset",
                (offset + 2 until offset + 6).all {
                    body[it].toInt() and 0xFF == ASYNC_BREAK_CHECK
                }
            )
        }
    }

    private fun assertBytes(message: String, expected: ByteArray, offset: Int) {
        val actual = patch.replacement.copyOfRange(offset, offset + expected.size)
        assertTrue(
            "$message: expected ${expected.toHex()} at $offset, found ${actual.toHex()}",
            expected.contentEquals(actual)
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val CREATE_MESSAGE_CONTENT = 47120
        const val CREATE_MESSAGE_CONTENT_BODY_SIZE = 8346

        /** `GetById r23, r15, 12, "renderEmbeds"` in the 354.1 bundle. */
        val GET_BY_ID_RENDER_EMBEDS = byteArrayOf(0x45, 0x17, 0x0f, 0x0c, 0xea.toByte(), 0xe2.toByte())
        const val RENDER_EMBEDS_OFFSET = 133

        /** `GetById r31, r15, 28, "renderReplies"` in the 354.1 bundle. */
        val GET_BY_ID_RENDER_REPLIES =
            byteArrayOf(0x45, 0x1f, 0x0f, 0x1c, 0x07, 0xe3.toByte())
        const val RENDER_REPLIES_OFFSET = 233

        /** The first byte of `LoadConstFalse <register>` in this bytecode version. */
        const val LOAD_CONST_FALSE = 0x96

        /** The opcode a shorter replacement is padded with. */
        const val ASYNC_BREAK_CHECK = 0x7e
    }
}
