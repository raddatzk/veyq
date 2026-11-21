package me.raddatz.veyq.mobile.audio

import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.media.ByteSource
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Audio files a test makes (the web's `src/test/audioFrames.ts` and `src/test/wavFile.ts`, byte for byte): MP3 and ADTS
 * frame streams and a WAV of tone and silence, and the sources core reads them through — each read counted.
 */
object AudioFiles {
    /** An MPEG-1 Layer III frame at 128 kbit/s, 44.1 kHz, no padding: `FF FB 90 00`, 417 bytes. */
    const val MP3_FRAME = 417

    /** One ADTS frame at 44.1 kHz carrying one raw block (1024 samples), [length] bytes with its 7-byte header. */
    fun adtsHeader(length: Int): ByteArray = intArrayOf(
        0xff, 0xf1, (1 shl 6) or (4 shl 2), 0x80 or ((length shr 11) and 0x03), (length shr 3) and 0xff, ((length and 0x07) shl 5) or 0x1f, 0xfc,
    ).map { it.toByte() }.toByteArray()

    /** [bytes] bytes of MP3 frames from offset [lead] (what comes before is zeros, as a tag's body). */
    fun mp3Stream(bytes: Int, lead: Int = 0): ByteArray {
        val b = ByteArray(bytes)
        var at = lead
        while (at + 4 <= bytes) {
            intArrayOf(0xff, 0xfb, 0x90, 0x00).forEachIndexed { i, v -> b[at + i] = v.toByte() }
            at += MP3_FRAME
        }
        return b
    }

    /** [frames] ADTS frames of [length] bytes each, then [trailer] bytes of a tag. */
    fun adtsStream(frames: Int, length: Int = 512, trailer: Int = 0): ByteArray {
        val b = ByteArray(frames * length + trailer)
        val h = adtsHeader(length)
        for (i in 0 until frames) h.copyInto(b, i * length)
        if (trailer > 0) "TAG".encodeToByteArray().copyInto(b, frames * length)
        return b
    }

    /** A WAV: [parts] `seconds to amplitude` (0 is silence, else a 440 Hz tone at that peak). */
    data class Spec(
        val bits: Int, val channels: Int, val rate: Int, val parts: List<Pair<Double, Double>>,
        val float: Boolean = false, val format: Int? = null, val listBytes: Int = 0,
    )

    fun wavBytes(spec: Spec): ByteArray {
        val frames = (spec.parts.sumOf { it.first } * spec.rate).roundToInt()
        val width = spec.bits / 8
        val blockAlign = spec.channels * width
        val extensible = spec.format == 0xfffe
        val fmtSize = if (extensible) 40 else 16
        val list = spec.listBytes
        val listPad = list % 2
        val dataBytes = frames * blockAlign
        val total = 12 + 8 + fmtSize + (if (list > 0) 8 + list + listPad else 0) + 8 + dataBytes
        val out = ByteArray(total)
        fun str(at: Int, s: String) = s.encodeToByteArray().copyInto(out, at)
        fun u16(at: Int, v: Int) { out[at] = v.toByte(); out[at + 1] = (v shr 8).toByte() }
        fun u32(at: Int, v: Int) { u16(at, v and 0xffff); u16(at + 2, (v ushr 16) and 0xffff) }
        str(0, "RIFF"); u32(4, total - 8); str(8, "WAVE")
        var at = 12
        str(at, "fmt "); u32(at + 4, fmtSize)
        u16(at + 8, spec.format ?: if (spec.float) 3 else 1)
        u16(at + 10, spec.channels); u32(at + 12, spec.rate)
        u32(at + 16, spec.rate * blockAlign); u16(at + 20, blockAlign); u16(at + 22, spec.bits)
        if (extensible) { u16(at + 24, 22); u16(at + 32, if (spec.float) 3 else 1) }
        at += 8 + fmtSize
        if (list > 0) { str(at, "LIST"); u32(at + 4, list); at += 8 + list + listPad }
        str(at, "data"); u32(at + 4, dataBytes); at += 8
        var frame = 0
        for ((sec, amp) in spec.parts) {
            val n = (sec * spec.rate).roundToInt()
            for (i in 0 until n) {
                val x = amp * sin(2 * PI * 440 * i / spec.rate)
                for (c in 0 until spec.channels) {
                    val o = at + frame * blockAlign + c * width
                    when {
                        spec.float -> u32(o, x.toFloat().toRawBits())
                        spec.bits == 8 -> out[o] = ((x * 127).roundToInt() + 128).toByte()
                        spec.bits == 16 -> u16(o, (x * 32767).roundToInt())
                        spec.bits == 24 -> (x * 8388607).roundToInt().let { s -> out[o] = s.toByte(); out[o + 1] = (s shr 8).toByte(); out[o + 2] = (s shr 16).toByte() }
                        else -> u32(o, (x * 2147483647).roundToLong().toInt())
                    }
                }
                frame++
            }
        }
        return out
    }

    /** A source over [bytes] whose reads are counted ([reads]: each `from` to its length). */
    class Counted(val bytes: ByteArray) {
        val reads = mutableListOf<Pair<Long, Int>>()
        val source = ByteSource(bytes.size.toLong()) { from, length ->
            reads += from to length
            bytes.copyOfRange(from.toInt(), minOf(bytes.size.toLong(), from + length).toInt())
        }
    }

    fun source(bytes: ByteArray): ByteSource = Counted(bytes).source

    /** A picked file of [bytes] named [name], claimed [type]; [size] overrides its size (its reads then fail past the bytes). */
    fun picked(bytes: ByteArray, name: String, type: String? = null, size: Long = bytes.size.toLong(), modifiedAt: Long? = 1_700_000_000_000L): UploadSource =
        UploadSource.of(name, size, type, modifiedAt) { from, length -> bytes.copyOfRange(from.toInt(), minOf(bytes.size.toLong(), from + length).toInt()) }
}
