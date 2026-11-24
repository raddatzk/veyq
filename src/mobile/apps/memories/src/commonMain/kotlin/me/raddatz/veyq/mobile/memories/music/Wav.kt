package me.raddatz.veyq.mobile.memories.music

import me.raddatz.veyq.mobile.media.ByteSource

/**
 * A WAV file's samples at both ends, read straight from the file (the web's `features/memories/music/wav.ts`, ADR 0135
 * decision 4; ADR 0142 decision 7: the one container whose trim the apps find): its `fmt ` and `data` chunks parsed from
 * its head, and only the bytes of the first and the last `limitMs` read and converted — a 200 MiB WAV costs a few MiB
 * here. Integer PCM of 8, 16, 24 and 32 bit and 32-bit float, plain or as WAVE_FORMAT_EXTENSIBLE; anything else throws,
 * and the caller then decides without it. No key, no request.
 */
object Wav {
    /** What the scan needs of a WAV's header. */
    data class Format(
        val channels: Int, val sampleRate: Int, val bitsPerSample: Int, val float: Boolean, val blockAlign: Int,
        val dataOffset: Long, val dataBytes: Long,
    )

    /** How much of the head is read to find the `data` chunk, at most (a `LIST` chunk of tags may stand before it). */
    private const val HEADER_MAX = 1024 * 1024
    private const val PCM = 1
    private const val IEEE_FLOAT = 3
    private const val EXTENSIBLE = 0xfffe

    /** A format this does not read, or a file that is no WAV. */
    class Unreadable(message: String) : Exception(message)

    private fun ascii(b: ByteArray, at: Int) = (0 until 4).joinToString("") { (b[at + it].toInt() and 0xff).toChar().toString() }
    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)
    private fun u32(b: ByteArray, at: Int) = u16(b, at).toLong() or (u16(b, at + 2).toLong() shl 16)

    /** The header in [head] (the file's first bytes) of a file of [fileSize] bytes; null where `data` lies beyond it. */
    fun parseHeader(head: ByteArray, fileSize: Long): Format? {
        if (head.size < 12 || ascii(head, 0) != "RIFF" || ascii(head, 8) != "WAVE") throw Unreadable("not_wav")
        var fmt: Format? = null
        var at = 12L
        while (at + 8 <= head.size) {
            val i = at.toInt()
            val id = ascii(head, i)
            val size = u32(head, i + 4)
            val body = i + 8
            if (id == "fmt ") {
                if (body + 16 > head.size) return null
                var format = u16(head, body)
                val channels = u16(head, body + 2)
                val sampleRate = u32(head, body + 4)
                val blockAlign = u16(head, body + 12)
                val bits = u16(head, body + 14)
                if (format == EXTENSIBLE && size >= 40 && body + 26 <= head.size) format = u16(head, body + 24)
                val float = format == IEEE_FLOAT
                if (!(format == PCM && bits in setOf(8, 16, 24, 32)) && !(float && bits == 32)) throw Unreadable("wav_format")
                if (channels < 1 || sampleRate < 1 || blockAlign != channels * (bits / 8)) throw Unreadable("wav_format")
                fmt = Format(channels, sampleRate.toInt(), bits, float, blockAlign, 0, 0)
            } else if (id == "data") {
                val f = fmt ?: throw Unreadable("wav_format")
                // a writer that streamed the file may leave the size at 0 or past the end: the data then runs to the file's end
                val dataBytes = if (size == 0L || body + size > fileSize) fileSize - body else size
                return f.copy(dataOffset = body.toLong(), dataBytes = dataBytes - (dataBytes % f.blockAlign))
            }
            at = body + size + (size % 2) // chunks are word-aligned
        }
        return null
    }

    /** Whole blocks of [bytes] as samples per channel in [-1, 1]. */
    fun samples(bytes: ByteArray, fmt: Format): Decoded {
        val frames = bytes.size / fmt.blockAlign
        val data = List(fmt.channels) { FloatArray(frames) }
        val width = fmt.bitsPerSample / 8
        fun read(o: Int): Float {
            fun b(k: Int) = bytes[o + k].toInt() and 0xff
            if (fmt.float) return Float.fromBits(b(0) or (b(1) shl 8) or (b(2) shl 16) or (b(3) shl 24))
            return when (fmt.bitsPerSample) {
                8 -> (b(0) - 128) / 128f
                16 -> ((b(0) or (b(1) shl 8)).toShort().toInt()) / 32768f
                24 -> (((b(2) shl 24) or (b(1) shl 16) or (b(0) shl 8)) shr 8) / 8388608f
                else -> ((b(0) or (b(1) shl 8) or (b(2) shl 16) or (b(3) shl 24)).toDouble() / 2147483648.0).toFloat()
            }
        }
        for (f in 0 until frames) for (c in 0 until fmt.channels) data[c][f] = read(f * fmt.blockAlign + c * width)
        return Decoded(data, fmt.sampleRate)
    }

    /** How many bytes of [fmt]'s data the first and the last [limitMs] each span: whole blocks, at most all of it. */
    internal fun endBytes(fmt: Format, limitMs: Long): Long = minOf(fmt.dataBytes, (fmt.sampleRate.toLong() * limitMs + 999) / 1000 * fmt.blockAlign)

    /** The samples of the first and the last [limitMs] of a WAV file's data (both the whole data where it is shorter). */
    suspend fun readEnds(file: ByteSource, limitMs: Long): Pair<Decoded, Decoded> {
        suspend fun headOf(size: Int) = parseHeader(file.read(0, minOf(file.size, size.toLong()).toInt()), file.size)
        val fmt = headOf(64 * 1024) ?: headOf(HEADER_MAX) ?: throw Unreadable("wav_no_data")
        val want = endBytes(fmt, limitMs)
        val end = fmt.dataOffset + fmt.dataBytes
        suspend fun bytesAt(from: Long) = file.read(from, want.toInt())
        return samples(bytesAt(fmt.dataOffset), fmt) to samples(bytesAt(end - want), fmt)
    }
}
