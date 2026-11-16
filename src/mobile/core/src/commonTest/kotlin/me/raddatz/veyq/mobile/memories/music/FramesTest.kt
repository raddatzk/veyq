package me.raddatz.veyq.mobile.memories.music

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.memories.music.AudioFiles.MP3_FRAME
import me.raddatz.veyq.mobile.memories.music.AudioFiles.adtsHeader
import me.raddatz.veyq.mobile.memories.music.AudioFiles.adtsStream
import me.raddatz.veyq.mobile.memories.music.AudioFiles.mp3Stream
import me.raddatz.veyq.mobile.memories.music.AudioFiles.source
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The web's `frames.test.ts`, case for case: the frame headers a tail slice is aligned on, and a raw ADTS file's count. */
class FramesTest {
    private fun bytes(vararg b: Int) = b.map { it.toByte() }.toByteArray()

    @Test
    fun readsAnMp3FramesLengthFromItsHeaderAndNothingWhereNoHeaderStands() {
        assertEquals(MP3_FRAME, Frames.mp3FrameLength(bytes(0xff, 0xfb, 0x90, 0x00), 0))
        assertEquals(MP3_FRAME + 1, Frames.mp3FrameLength(bytes(0xff, 0xfb, 0x92, 0x00), 0), "the padding byte")
        assertEquals(261, Frames.mp3FrameLength(bytes(0xff, 0xf3, 0x90, 0x00), 0), "MPEG-2: index 9 is 80 kbit/s, 72 x 80000 / 22050")
        for (bad in listOf(bytes(0xff, 0xfb, 0xf0, 0x00), bytes(0xff, 0xfb, 0x0c, 0x00), bytes(0xff, 0xfd, 0x90, 0x00), bytes(0xff, 0xeb, 0x90, 0x00), bytes(0xfe, 0xfb, 0x90, 0x00))) {
            assertEquals(0, Frames.mp3FrameLength(bad, 0), bad.joinToString(" "))
        }
    }

    @Test
    fun readsAnAdtsFramesLengthRateAndSamples() {
        assertEquals(Frames.Adts(512, 44100, 1024), Frames.adtsFrame(adtsHeader(512), 0))
        assertEquals(0, Frames.adtsFrameLength(bytes(0xff, 0xf1, 0x3c, 0x80, 0, 0, 0), 0), "a rate index past the table")
    }

    @Test
    fun alignsOnTheFirstHeaderThatBeginsAChainOfThreeNotOnALoneTwoBytePattern() {
        val b = mp3Stream(20 * MP3_FRAME, 200)
        bytes(0xff, 0xfb, 0x90, 0x00).copyInto(b, 50) // a lone frame header in the tag's body: its successor is no header
        assertEquals(200, Frames.firstFrameChain(b, Frames::mp3FrameLength))
        assertEquals(-1, Frames.firstFrameChain(ByteArray(4096), Frames::mp3FrameLength))
        val cut = adtsStream(40).copyOfRange(300, 40 * 512) // a slice cut inside a frame
        assertEquals(512 - 300, Frames.firstFrameChain(cut, Frames::adtsFrameLength))
    }

    @Test
    fun countsEveryAdtsFrameAcrossTheSlicesItReads() = runTest {
        // 10 000 frames of 1024 samples at 44.1 kHz = 232.2 s, 5.1 MB: read in two steps
        assertEquals((10_000 * 1024 * 1000.0 / 44100).roundToLong(), Frames.adtsDurationMs(source(adtsStream(10_000))))
    }

    @Test
    fun theCountEndsAtATagAfterTheLastFrameAndGivesUpWhereTheFramesBreak() = runTest {
        assertEquals((100 * 1024 * 1000.0 / 44100).roundToLong(), Frames.adtsDurationMs(source(adtsStream(100, 512, 128))))
        val broken = adtsStream(100) + ByteArray(200_000) + adtsStream(100)
        assertNull(Frames.adtsDurationMs(source(broken)))
        assertNull(Frames.adtsDurationMs(source(ByteArray(1000))))
    }
}
