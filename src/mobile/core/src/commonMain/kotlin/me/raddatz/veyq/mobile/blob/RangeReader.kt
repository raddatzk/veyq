package me.raddatz.veyq.mobile.blob

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.raddatz.veyq.mobile.crypto.V1

/**
 * A blob's plaintext read at any offset (the web's Service Worker range shim, ADR 0047): what a player asks for — a byte
 * range of a video it seeks through — is served from the frames that hold it, each read and opened on the device, so a
 * video plays without its whole plaintext held or written anywhere. A read past the cached frames reads [READ_AHEAD]
 * frames at once (a player reads on); at most [KEEP] frames stay held, the least recently read dropped first.
 * One read at a time: a player's threads queue on it.
 *
 * [frames] hands frames `first until until` to its sink in order (core's `ApplicationBlobs.FrameSource`); the constructor
 * is public for a source of frames outside the core too (the bridge's test door).
 */
class RangeReader(
    /** The plaintext's size. */
    val size: Long,
    private val chunkCount: Int,
    private val frames: suspend (first: Int, until: Int, sink: suspend (ByteArray) -> Unit) -> Unit,
) {
    private val lock = Mutex()
    private val held = LinkedHashMap<Int, ByteArray>()

    /**
     * Up to [length] bytes from [position] — fewer only at the end, none from the end on. Throws what reading a frame throws
     * (a frame that does not open, a store that does not answer).
     */
    @Throws(Exception::class)
    suspend fun read(position: Long, length: Int): ByteArray = lock.withLock {
        if (position < 0 || length < 0) throw IllegalArgumentException("range")
        val n = minOf(length.toLong(), size - position).coerceAtLeast(0).toInt()
        val out = ByteArray(n)
        var at = 0
        while (at < n) {
            val pos = position + at
            val index = (pos / V1.FRAME_PLAINTEXT).toInt()
            val inFrame = (pos % V1.FRAME_PLAINTEXT).toInt()
            val frame = held.remove(index)?.also { held[index] = it } ?: fill(index)
            val take = minOf(n - at, frame.size - inFrame)
            if (take <= 0) throw IllegalStateException("frame_short")
            frame.copyInto(out, at, inFrame, inFrame + take)
            at += take
        }
        out
    }

    /** Frame [index] and the [READ_AHEAD] after it not held yet, read in one go; returns frame [index]. */
    private suspend fun fill(index: Int): ByteArray {
        var until = index + 1
        while (until < minOf(chunkCount, index + READ_AHEAD) && until !in held) until++
        var i = index
        frames(index, until) { bytes ->
            held.remove(i)
            held[i++] = bytes
            while (held.size > KEEP) held.remove(held.keys.first())
        }
        return held[index] ?: throw IllegalStateException("frame_missing")
    }

    companion object {
        /** Frames read at once past what is held (1 MiB). */
        const val READ_AHEAD = 4
        /** Frames held at most (4 MiB). */
        const val KEEP = 16
    }
}
