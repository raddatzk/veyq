package me.raddatz.veyq.mobile.blob

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.crypto.V1
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A blob read at any offset (`RangeReader`): every byte where it lies, frames read ahead and held, none read twice. */
class RangeReaderTest {
    private val f = V1.FRAME_PLAINTEXT

    /** A blob of [size] bytes, byte i being i mod 251, read frame by frame; [asked] records each read's frames. */
    private class Blob(val size: Long) {
        val chunkCount = V1.chunkCount(size)
        val asked = ArrayList<IntRange>()
        fun byte(i: Long) = (i % 251).toByte()
        val reader = RangeReader(size, chunkCount) { first, until, sink ->
            asked += first until until
            for (i in first until until) {
                val from = i.toLong() * V1.FRAME_PLAINTEXT
                val n = minOf(size - from, V1.FRAME_PLAINTEXT.toLong()).toInt()
                sink(ByteArray(n) { byte(from + it) })
            }
        }
        fun expected(from: Long, n: Int) = ByteArray(n) { byte(from + it) }
    }

    @Test
    fun everyByteIsWhereItLiesAcrossFrames() = runTest {
        val b = Blob(10L * f + 1234)
        // across a frame boundary, inside one frame, the last bytes, past the end
        assertContentEquals(b.expected(f - 10L, 30), b.reader.read(f - 10L, 30))
        assertContentEquals(b.expected(3L * f + 5, 100), b.reader.read(3L * f + 5, 100))
        assertContentEquals(b.expected(b.size - 4, 4), b.reader.read(b.size - 4, 100))
        assertEquals(0, b.reader.read(b.size, 10).size)
        assertEquals(0, b.reader.read(b.size + 10, 10).size)
        // a long read spans many frames
        assertContentEquals(b.expected(7, 5 * f), b.reader.read(7, 5 * f))
    }

    @Test
    fun aReadReadsAheadAndWhatIsHeldIsNotReadAgain() = runTest {
        val b = Blob(20L * f)
        b.reader.read(0, 10)
        assertEquals(listOf(0 until RangeReader.READ_AHEAD), b.asked)
        // the frames read ahead serve the player reading on, without a read
        b.reader.read(f.toLong(), 2 * f)
        assertEquals(1, b.asked.size)
        // a seek reads from where it lands, and stops before a frame it holds
        b.reader.read(10L * f, 1)
        b.reader.read(8L * f, 1)
        assertEquals(listOf(0 until 4, 10 until 14, 8 until 10), b.asked)
    }

    @Test
    fun atMostKeepFramesAreHeldTheOldestDroppedFirst() = runTest {
        val b = Blob(64L * f)
        for (i in 0 until 64 step RangeReader.READ_AHEAD) b.reader.read(i.toLong() * f, 1)
        b.asked.clear()
        // the last KEEP frames are held, the first long dropped
        b.reader.read(63L * f, 1)
        b.reader.read((64L - RangeReader.KEEP) * f, 1)
        assertTrue(b.asked.isEmpty(), "held: ${b.asked}")
        b.reader.read(0, 1)
        assertEquals(listOf(0 until RangeReader.READ_AHEAD), b.asked)
    }

    @Test
    fun anEmptyBlobReadsNothing() = runTest {
        val b = Blob(0)
        assertEquals(0, b.reader.read(0, 10).size)
        assertTrue(b.asked.isEmpty())
    }
}
