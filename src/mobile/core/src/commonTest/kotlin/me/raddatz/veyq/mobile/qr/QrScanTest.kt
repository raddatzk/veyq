package me.raddatz.veyq.mobile.qr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The scan model over a decoder the test plays: each frame's decode answers when the test says. */
class QrScanTest {
    /** A frame whose first byte names it, so the played decoder knows which one it was handed. */
    private fun frame(id: Int, width: Int = 64, height: Int = 48) = QrFrame(width, height, width, ByteArray(width * height).also { it[0] = id.toByte() })

    /**
     * [cancellable] false plays the real decoder, which is CPU-bound and does not stop when its job is cancelled: what it
     * read still arrives after a [QrScan.close].
     */
    private class PlayedDecoder(private val cancellable: Boolean = true) {
        val asked = mutableListOf<Int>()
        val answers = mutableMapOf<Int, CompletableDeferred<String?>>()
        suspend fun decode(frame: QrFrame): String? {
            val id = frame.luminance[0].toInt()
            asked += id
            val answer = answers.getOrPut(id) { CompletableDeferred() }
            return if (cancellable) answer.await() else withContext(NonCancellable) { answer.await() }
        }
        fun answer(id: Int, text: String?) = answers.getOrPut(id) { CompletableDeferred() }.complete(text)
    }

    /** A kit parser's shape: a `veyqkit:` text accepted as its token, anything else skipped. */
    private val kit: (String) -> String? = { it.removePrefix("veyqkit:").takeIf { token -> token != it } }

    private fun TestScope.scan(decoder: PlayedDecoder, accepts: (String) -> String? = kit) =
        QrScan(accepts, decoder::decode, backgroundScope)

    @Test
    fun oneFrameIsDecodedAtATimeAndAFrameOfferedMeanwhileIsDropped() = runTest {
        val decoder = PlayedDecoder()
        val scan = scan(decoder)
        scan.camera(QrScan.Camera.RUNNING)
        assertTrue(scan.offer(frame(1)))
        runCurrent()
        assertFalse(scan.offer(frame(2)), "a decode is in flight")
        decoder.answer(1, null)
        runCurrent()
        assertTrue(scan.offer(frame(3)))
        runCurrent()
        assertEquals(listOf(1, 3), decoder.asked)
        assertEquals(QrScan.State.Scanning, scan.current)
    }

    @Test
    fun aForeignCodeIsSkippedAndTheFirstAcceptedOneSettlesTheScan() = runTest {
        val decoder = PlayedDecoder()
        val scan = scan(decoder)
        scan.camera(QrScan.Camera.RUNNING)
        scan.offer(frame(1)); runCurrent()
        decoder.answer(1, "https://elsewhere.example/veyqkit:x"); runCurrent()
        assertEquals(QrScan.State.Scanning, scan.current, "a code not ours")
        scan.offer(frame(2)); runCurrent()
        decoder.answer(2, "veyqkit:token"); runCurrent()
        assertEquals(QrScan.State.Found("token"), scan.current)
        assertFalse(scan.offer(frame(3)), "nothing is read once found")
        scan.camera(QrScan.Camera.DENIED)
        assertEquals(QrScan.State.Found("token"), scan.current, "the camera changes nothing once found")
        assertEquals(listOf(1, 2), decoder.asked)
    }

    @Test
    fun aParserThatThrowsSkipsTheCode() = runTest {
        val decoder = PlayedDecoder()
        val scan = scan(decoder) { error("not a kit: $it") }
        scan.camera(QrScan.Camera.RUNNING)
        scan.offer(frame(1)); runCurrent()
        decoder.answer(1, "veyqkit:token"); runCurrent()
        assertEquals(QrScan.State.Scanning, scan.current)
        assertTrue(scan.offer(frame(2)), "the next frame is taken")
    }

    @Test
    fun closingStopsTheScanEvenWithADecodeInFlight() = runTest {
        val decoder = PlayedDecoder(cancellable = false)
        val scan = scan(decoder)
        scan.camera(QrScan.Camera.RUNNING)
        scan.offer(frame(1)); runCurrent()
        scan.close()
        decoder.answer(1, "veyqkit:token"); runCurrent()
        assertEquals(QrScan.State.Closed, scan.current)
        assertFalse(scan.offer(frame(2)))
        scan.camera(QrScan.Camera.RUNNING)
        assertEquals(QrScan.State.Closed, scan.current, "a closed scan does not restart")
    }

    @Test
    fun noFrameIsReadBeforeTheCameraRunsAndItsRefusalsAreStates() = runTest {
        val decoder = PlayedDecoder()
        val scan = scan(decoder)
        assertEquals(QrScan.State.Starting, scan.current)
        assertFalse(scan.offer(frame(1)))
        scan.camera(QrScan.Camera.NONE)
        assertEquals(QrScan.State.NoCamera, scan.current)
        scan.camera(QrScan.Camera.DENIED)
        assertEquals(QrScan.State.Denied, scan.current)
        assertFalse(scan.offer(frame(1)))
        runCurrent()
        assertEquals(emptyList(), decoder.asked)
    }

    @Test
    fun aFrameOverTheLongEdgeIsRefused() = runTest {
        val scan = scan(PlayedDecoder())
        scan.camera(QrScan.Camera.RUNNING)
        assertFailsWith<IllegalArgumentException> { scan.offer(frame(1, QrScan.MAX_EDGE + 1, 720)) }
        assertFailsWith<IllegalArgumentException> { scan.offer(frame(1, 720, QrScan.MAX_EDGE + 1)) }
        assertTrue(scan.offer(frame(1, QrScan.MAX_EDGE, 720)))
    }

    @Test
    fun theCoresDecoderSettlesAScanOnAFixture() = runTest {
        val scan = QrScan({ text -> text.takeIf { it.startsWith("veyqpair:") } }, scope = backgroundScope)
        val seen = mutableListOf<QrScan.State<String>>()
        scan.watch { seen += it }
        runCurrent()
        scan.camera(QrScan.Camera.RUNNING)
        assertTrue(scan.offer(QrFixtures.frame("pair-rotated")))
        runCurrent()
        assertEquals(QrScan.State.Found(QrFixtures.texts.getValue("pair")), scan.current)
        assertEquals(listOf(QrScan.State.Starting, QrScan.State.Scanning, QrScan.State.Found(QrFixtures.texts.getValue("pair"))), seen)
    }
}
