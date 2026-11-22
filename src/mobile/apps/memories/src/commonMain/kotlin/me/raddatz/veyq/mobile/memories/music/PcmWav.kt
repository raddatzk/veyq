package me.raddatz.veyq.mobile.memories.music

/**
 * Interleaved PCM wrapped as a WAV file's bytes (a 44-byte RIFF header, `fmt ` and `data`), as [Wav] reads it: how a
 * platform hands the core a decoded end of a track (inbox #932, `MusicUpload.Measure.ends`) — integer PCM of [bits] bits,
 * or 32-bit float where [float].
 */
object PcmWav {
    fun of(pcm: ByteArray, channels: Int, sampleRate: Int, bits: Int, float: Boolean = false): ByteArray {
        require(channels > 0 && sampleRate > 0 && bits in setOf(8, 16, 24, 32)) { "a PCM format" }
        val block = channels * bits / 8
        val data = pcm.size - pcm.size % block
        val out = ByteArray(44 + data)
        fun str(at: Int, s: String) = s.encodeToByteArray().copyInto(out, at)
        fun u16(at: Int, v: Int) { out[at] = v.toByte(); out[at + 1] = (v shr 8).toByte() }
        fun u32(at: Int, v: Int) { u16(at, v and 0xffff); u16(at + 2, (v ushr 16) and 0xffff) }
        str(0, "RIFF"); u32(4, 36 + data); str(8, "WAVE")
        str(12, "fmt "); u32(16, 16); u16(20, if (float) 3 else 1); u16(22, channels); u32(24, sampleRate)
        u32(28, sampleRate * block); u16(32, block); u16(34, bits)
        str(36, "data"); u32(40, data)
        pcm.copyInto(out, 44, 0, data)
        return out
    }
}
