package me.raddatz.veyq.mobile.memories.music

import me.raddatz.veyq.mobile.audio.AudioFiles

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.audio.AudioFiles.Spec
import me.raddatz.veyq.mobile.audio.AudioFiles.wavBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The web's `wav.test.ts`, case for case: a WAV's ends read from its PCM — no decode, whatever its size. */
class WavTest {
    private val parts = listOf(1.0 to 0.0, 2.0 to 0.5, 0.5 to 0.0)

    @Test
    fun findsTheSilenceAtBothEndsOfEveryPcmFormatItReads() = runTest {
        val formats = listOf(
            "8-bit" to Spec(8, 2, 8000, parts), "16-bit" to Spec(16, 2, 8000, parts), "24-bit" to Spec(24, 2, 8000, parts),
            "32-bit" to Spec(32, 2, 8000, parts), "32-bit float" to Spec(32, 2, 8000, parts, float = true),
            "extensible 16-bit" to Spec(16, 2, 8000, parts, format = 0xfffe),
            "extensible float" to Spec(32, 2, 8000, parts, float = true, format = 0xfffe),
            "a LIST chunk before the data" to Spec(16, 2, 8000, parts, listBytes = 3001),
        )
        for ((what, spec) in formats) {
            val (head, tail) = Wav.readEnds(AudioFiles.source(wavBytes(spec)), 30_000)
            assertEquals(listOf(2L, 8000L, 1000L, 500L), listOf(head.channels.size.toLong(), head.sampleRate.toLong(), AudioFacts.leadingSilenceMs(head), AudioFacts.trailingSilenceMs(tail)), what)
        }
    }

    @Test
    fun readsOnlyTheFirstAndTheLast30SecondsOfALongFile() = runTest {
        val file = AudioFiles.Counted(wavBytes(Spec(16, 1, 8000, listOf(1.0 to 0.0, 100.0 to 0.5, 2.0 to 0.0))))
        val (head, tail) = Wav.readEnds(file.source, 30_000)
        assertEquals(240_000 to 240_000, head.length to tail.length)
        assertEquals(1000L to 2000L, AudioFacts.leadingSilenceMs(head) to AudioFacts.trailingSilenceMs(tail))
        assertTrue(file.reads.maxOf { it.second } <= 480_000, "no read larger than 30 s of the data: ${file.reads}")
    }

    @Test
    fun refusesAFormatItDoesNotReadAndAFileThatIsNoWav() {
        val adpcm = wavBytes(Spec(16, 1, 8000, parts, format = 2))
        assertEquals("wav_format", assertFailsWith<Wav.Unreadable> { Wav.parseHeader(adpcm, adpcm.size.toLong()) }.message)
        val float64 = wavBytes(Spec(32, 1, 8000, parts, format = 3))
        float64[34] = 64 // bits 64, the block no longer agrees
        float64[35] = 0
        assertEquals("wav_format", assertFailsWith<Wav.Unreadable> { Wav.parseHeader(float64, float64.size.toLong()) }.message)
        assertEquals("not_wav", assertFailsWith<Wav.Unreadable> { Wav.parseHeader(ByteArray(64), 64) }.message)
    }

    @Test
    fun takesADataSizeOfZeroOrPastTheEndAsRunningToTheFilesEnd() {
        val bytes = wavBytes(Spec(16, 1, 8000, parts))
        val at = bytes.size - (3.5 * 8000 * 2).toInt() - 4 // the data chunk's size field
        for (i in 0 until 4) bytes[at + i] = 0
        assertEquals((3.5 * 8000 * 2).toLong(), Wav.parseHeader(bytes, bytes.size.toLong())?.dataBytes)
    }
}
