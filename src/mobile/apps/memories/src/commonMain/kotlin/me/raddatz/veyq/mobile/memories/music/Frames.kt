package me.raddatz.veyq.mobile.memories.music

import me.raddatz.veyq.mobile.media.ByteSource
import kotlin.math.roundToLong

/**
 * The frame headers of MPEG audio Layer III (MP3) and ADTS AAC (the web's `features/memories/music/frames.ts`, ADR 0135
 * decision 4): a frame's length from its header, the first header that begins a chain of three (one alone is a two-byte
 * pattern any data may hold), and a raw ADTS file's length counted from its frames — it has no index, so a player only
 * estimates it (ADR 0142 decision 7: the core counts it, as the web does). Pure; no key, no request.
 */
object Frames {
    private val MPEG1_KBPS = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val MPEG2_KBPS = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
    private val MPEG_RATES = mapOf(3 to intArrayOf(44100, 48000, 32000), 2 to intArrayOf(22050, 24000, 16000), 0 to intArrayOf(11025, 12000, 8000))
    private val ADTS_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xff

    /** The byte length of the MP3 (Layer III) frame whose header stands at [at], or 0 where none does. */
    fun mp3FrameLength(b: ByteArray, at: Int): Int {
        if (at < 0 || at + 4 > b.size || u(b, at) != 0xff || (u(b, at + 1) and 0xe0) != 0xe0) return 0
        val version = (u(b, at + 1) shr 3) and 3
        val layer = (u(b, at + 1) shr 1) and 3
        val bitrate = u(b, at + 2) shr 4
        val rate = (u(b, at + 2) shr 2) and 3
        if (version == 1 || layer != 1 || bitrate == 0 || bitrate == 15 || rate == 3) return 0
        val kbps = (if (version == 3) MPEG1_KBPS else MPEG2_KBPS)[bitrate]
        val padding = (u(b, at + 2) shr 1) and 1
        return ((if (version == 3) 144 else 72) * kbps * 1000) / MPEG_RATES.getValue(version)[rate] + padding
    }

    /** An ADTS frame: its byte length, its sample rate, its samples. */
    data class Adts(val length: Int, val sampleRate: Int, val samples: Int)

    /** The ADTS frame whose header stands at [at]; null where none does. */
    fun adtsFrame(b: ByteArray, at: Int): Adts? {
        if (at < 0 || at + 7 > b.size || u(b, at) != 0xff || (u(b, at + 1) and 0xf6) != 0xf0) return null
        val rate = ADTS_RATES.getOrNull((u(b, at + 2) shr 2) and 0x0f) ?: return null
        val length = ((u(b, at + 3) and 0x03) shl 11) or (u(b, at + 4) shl 3) or (u(b, at + 5) shr 5)
        val headerBytes = if (u(b, at + 1) and 1 == 1) 7 else 9
        if (length < headerBytes) return null
        return Adts(length, rate, 1024 * ((u(b, at + 6) and 0x03) + 1))
    }

    fun adtsFrameLength(b: ByteArray, at: Int): Int = adtsFrame(b, at)?.length ?: 0

    /** The first offset in [b] where [frameAt] reads [chain] frames in a row, or -1. */
    fun firstFrameChain(b: ByteArray, frameAt: (ByteArray, Int) -> Int, chain: Int = 3): Int {
        var at = 0
        while (at + 4 <= b.size) {
            var p = at
            var n = 0
            while (n < chain) {
                val len = frameAt(b, p)
                if (len == 0) break
                p += len
                n++
            }
            if (n == chain) return at
            at++
        }
        return -1
    }

    /** How much of the file each step of the ADTS count reads. */
    private const val COUNT_STEP = 4 * 1024 * 1024

    /** What may follow the last frame (an ID3v1 or APE tag) without the count giving up. */
    private const val TRAILER_MAX = 64 * 1024

    /**
     * A raw ADTS file's length in ms, counted from its frame headers: from its first chain of frames, each header read and
     * the next one found by its length, a slice at a time; a tag of up to 64 KiB after the last frame ends it. Null where
     * the chain breaks before that (the caller then keeps the platform's length).
     */
    suspend fun adtsDurationMs(file: ByteSource): Long? {
        val size = file.size
        val first = file.read(0, minOf(size, 64L * 1024).toInt())
        var at = firstFrameChain(first, ::adtsFrameLength).toLong()
        if (at < 0) return null
        var seconds = 0.0
        while (at < size) {
            val chunk = file.read(at, minOf(size - at, COUNT_STEP.toLong()).toInt())
            val step = adtsCount(chunk, at.toDouble(), size.toDouble(), seconds)
            seconds = step.seconds
            if (step.read == 0) return adtsEnd(seconds, (size - at).toDouble())
            at += step.read
        }
        return adtsMs(seconds)
    }

    /** One slice's part of the ADTS count: the bytes its whole frames span ([read]) and the [seconds] counted so far. */
    internal class AdtsCount(val read: Int, val seconds: Double)

    /**
     * The frames of [chunk], read from the file at [at] of [size] bytes, counted onto [seconds] (the count's step, which the
     * web's loop over `Blob.slice` runs too): header by header, each next one found by its length, stopping before a frame
     * the slice cuts off unless it is the file's last. Offsets are doubles: exact to 2^53, and no `Long` on the web's path.
     */
    internal fun adtsCount(chunk: ByteArray, at: Double, size: Double, seconds: Double): AdtsCount {
        var s = seconds
        var p = 0
        while (true) {
            val frame = adtsFrame(chunk, p) ?: break
            if (p + frame.length > chunk.size && at + p + frame.length < size) break // read on from this header
            s += frame.samples.toDouble() / frame.sampleRate
            p += frame.length
            if (at + p >= size) break
        }
        return AdtsCount(p, s)
    }

    /** The count's answer where a slice holds no frame, [remaining] bytes before the end: a tag after the last frame, or a break (null). */
    internal fun adtsEnd(seconds: Double, remaining: Double): Long? = if (remaining <= TRAILER_MAX) adtsMs(seconds) else null

    /** [seconds] counted, in whole ms. */
    internal fun adtsMs(seconds: Double): Long = (seconds * 1000).roundToLong()
}
