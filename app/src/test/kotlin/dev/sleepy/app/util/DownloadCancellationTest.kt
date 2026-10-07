package dev.sleepy.app.util

import dev.sleepy.app.testing.workingDirectory
import java.io.File
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Stopping a run, from the side the transfer sees it.
 *
 * Pressing Stop cancels the job, and a coroutine only observes that at a suspension point. The
 * download's read loop is a socket read and a buffer write, neither of which suspends, so before
 * the loop checked for itself a Stop pressed during a transfer did nothing at all until the
 * transfer finished: on a 96 MB download over a slow connection that is minutes of an app that
 * appears to have ignored the button.
 *
 * Two tests, because they establish different things. The first runs the transfer the way the
 * pipeline does and checks that a cancelled coroutine stops rather than returning bytes. The
 * second reads the loop, because the case that actually went wrong is the one in the middle of a
 * transfer and there is no way to drive that here: the streaming path is HTTPS-only, and serving
 * one would mean a certificate this suite has no business generating. A source check is weaker
 * than a behavioural one and is labelled as such rather than presented as the stronger thing.
 */
class DownloadCancellationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** A payload carrying the ZIP magic number the reader requires. */
    private val payload = "PK\u0003\u0004 sleepy source payload".toByteArray(Charsets.ISO_8859_1)

    /**
     * A cancelled coroutine does not get the bytes.
     *
     * The local-file path is one blocking read, so the check that stops it has to come before the
     * read rather than inside a loop. The deferred is what makes the cancel land while the call is
     * in flight rather than before it starts, which is the shape of the real thing.
     */
    @Test
    fun aCancelledRunGetsNoBytes() = runBlocking {
        val file = File(tempFolder.root, "source.apk").apply { writeBytes(payload) }
        val reached = CompletableDeferred<Unit>()

        val transfer = async(Dispatchers.IO) {
            reached.complete(Unit)
            Downloader.download("file://${file.absolutePath}") { _, _ -> }
        }
        reached.await()
        delay(1)
        transfer.cancel()

        val thrown = try {
            transfer.await()
            null
        } catch (e: CancellationException) {
            e
        }
        assertNotNull("a cancelled transfer must not return its bytes", thrown)
    }

    /**
     * The streaming loop checks for cancellation on every chunk.
     *
     * Read out of the source rather than driven, for the reason in the class KDoc. If the check
     * moves out of the loop body, this fails and says so; it cannot tell you the check works.
     */
    @Test
    fun theStreamingLoopChecksForCancellationOnEveryChunk() {
        val source = generateSequence(workingDirectory()) { it.parentFile }
            .map { File(it, "src/main/kotlin/dev/sleepy/app/util/Downloader.kt") }
            .firstOrNull { it.isFile }
        assertNotNull("Downloader.kt was not found above ${workingDirectory()}", source)

        val text = source!!.readText()
        val loopStart = text.indexOf("while (inStream.read(chunk)")
        assertTrue("the streaming read loop is not where this test looks for it", loopStart > 0)

        val loopEnd = text.indexOf("\n            }", loopStart)
        assertTrue("the streaming read loop has no closing brace", loopEnd > loopStart)

        val body = text.substring(loopStart, loopEnd)
        assertTrue(
            "the streaming loop does not check for cancellation, so a transfer runs to the end " +
                "however many times Stop is pressed",
            body.contains("ensureActive()")
        )
        assertEquals(
            "the loop reads one chunk and writes it; a check anywhere else does not cover the " +
                "transfer",
            1,
            body.split("ensureActive()").size - 1
        )
    }
}
