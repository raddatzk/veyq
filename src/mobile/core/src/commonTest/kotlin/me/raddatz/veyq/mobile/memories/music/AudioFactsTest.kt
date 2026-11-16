package me.raddatz.veyq.mobile.memories.music

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.raddatz.veyq.mobile.memories.music.AudioFacts.Companion.SILENCE_PEAK
import me.raddatz.veyq.mobile.memories.music.AudioFiles.Spec
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The web's `audioFacts.test.ts`: the scan case for case, the sealed tier's facts (`audioFactsFrom`) case for case, and
 * the measuring as the apps narrow it (ADR 0142 decision 7): a WAV trimmed from its PCM, an ADTS file's length counted,
 * every other container untrimmed — where the web decodes slices or the whole file, the apps decode nothing.
 */
class AudioFactsTest {
    private val rate = 8000

    /** Generated PCM: each part `seconds to amplitude` — 0 is digital silence, anything else a 440 Hz tone at that peak. */
    private fun pcm(parts: List<Pair<Double, Double>>, channels: Int = 1, onlyChannel: Int? = null): Decoded {
        val length = (parts.sumOf { it.first } * rate).roundToInt()
        val data = List(channels) { FloatArray(length) }
        var at = 0
        for ((sec, amp) in parts) {
            val n = (sec * rate).roundToInt()
            for (i in 0 until n) {
                val v = (amp * sin(2 * PI * 440 * i / rate)).toFloat()
                for (c in 0 until channels) if (onlyChannel == null || onlyChannel == c) data[c][at + i] = v
            }
            at += n
        }
        return Decoded(data, rate)
    }

    private fun whole(audio: Decoded) =
        AudioFacts.fromSilence((audio.length.toDouble() / audio.sampleRate * 1000).roundToLong(), AudioFacts.leadingSilenceMs(audio), AudioFacts.trailingSilenceMs(audio))

    @Test
    fun trimsLeadingAndTrailingSilenceToTheWindowsThatAreNotSilent() {
        assertEquals(AudioFacts(4500, 500, 3500), whole(pcm(listOf(0.5 to 0.0, 3.0 to 0.5, 1.0 to 0.0))))
    }

    @Test
    fun aFileOfAllSilenceKeepsTheWholeFile() {
        assertEquals(AudioFacts(3000, 0, 3000), whole(pcm(listOf(3.0 to 0.0))))
    }

    @Test
    fun underOneSecondOfContentKeepsTheWholeFileOneSecondOrMoreIsTrimmedToIt() {
        assertEquals(AudioFacts(2500, 0, 2500), whole(pcm(listOf(1.0 to 0.0, 0.5 to 0.5, 1.0 to 0.0))))
        assertEquals(AudioFacts(3000, 1000, 2000), whole(pcm(listOf(1.0 to 0.0, 1.0 to 0.5, 1.0 to 0.0))))
    }

    @Test
    fun aFileShorterThanASecondIsPlayedWholeAnEmptyOneHasNoLength() {
        assertEquals(AudioFacts(400, 0, 400), whole(pcm(listOf(0.4 to 0.5))))
        assertEquals(AudioFacts(0, 0, 0), whole(pcm(emptyList())))
    }

    @Test
    fun minusSixtyDbfsIsTheLine() {
        assertEquals(AudioFacts(4000, 0, 4000), whole(pcm(listOf(1.0 to 0.0, 2.0 to SILENCE_PEAK * 0.9, 1.0 to 0.0))))
        assertEquals(AudioFacts(4000, 1000, 3000), whole(pcm(listOf(1.0 to 0.0, 2.0 to SILENCE_PEAK * 1.1, 1.0 to 0.0))))
    }

    @Test
    fun aWindowIsLoudWhenAnyChannelIs() {
        assertEquals(AudioFacts(3000, 500, 2500), whole(pcm(listOf(0.5 to 0.0, 2.0 to 0.5, 0.5 to 0.0), 2, onlyChannel = 1)))
    }

    @Test
    fun looksAtMostThirtySecondsIntoEachEnd() {
        val audio = pcm(listOf(40.0 to 0.0, 5.0 to 0.5, 35.0 to 0.0))
        assertEquals(30_000L to 30_000L, AudioFacts.leadingSilenceMs(audio) to AudioFacts.trailingSilenceMs(audio))
    }

    // --- the measuring, the apps' (ADR 0142 decision 7) ---------------------------------------------------------------

    @Test
    fun aWavIsTrimmedFromItsPcmWhateverItsLength() = runTest {
        val wav = AudioFiles.wavBytes(Spec(16, 2, 8000, listOf(1.5 to 0.0, 6.0 to 0.5, 0.5 to 0.0)))
        val long = 3 * 7 * 60_000L // the platform's length, whatever it is: only the ends are read
        assertEquals(AudioFacts(long, 1500, long - 500), AudioFacts.measure(AudioFiles.source(wav), "audio/wav", long))
    }

    @Test
    fun aWavWhoseFormatIsNotReadIsSealedUntrimmed() = runTest {
        val adpcm = AudioFiles.wavBytes(Spec(16, 1, 8000, listOf(0.5 to 0.0, 1.5 to 0.5), format = 2))
        assertEquals(AudioFacts.untrimmed(2000), AudioFacts.measure(AudioFiles.source(adpcm), "audio/wav", 2000))
    }

    @Test
    fun everyDecodedFormatIsSealedUntrimmedNothingOfItRead() = runTest {
        for (type in listOf("audio/mpeg", "audio/mp4", "audio/flac", "audio/ogg")) {
            val file = AudioFiles.Counted(AudioFiles.mp3Stream(10 * AudioFiles.MP3_FRAME))
            assertEquals(AudioFacts.untrimmed(183_000), AudioFacts.measure(file.source, type, 183_000), type)
            assertTrue(file.reads.isEmpty(), "$type: nothing read")
        }
    }

    @Test
    fun anAdtsFilesLengthIsCountedFromItsFramesNotTakenFromThePlatformsEstimate() = runTest {
        val adts = AudioFiles.adtsStream(1000)
        val counted = (1000 * 1024 * 1000.0 / 44100).roundToLong()
        assertEquals(AudioFacts.untrimmed(counted), AudioFacts.measure(AudioFiles.source(adts), "audio/aac", 8 * 3_600_000L))
        // where the frames do not count, the platform's length stays
        assertEquals(AudioFacts.untrimmed(5000), AudioFacts.measure(AudioFiles.source(ByteArray(1000)), "audio/aac", 5000))
    }

    // --- a sealed tier's facts (`audioFactsFrom`) ----------------------------------------------------------------------

    private fun tier(s: String) = Json.parseToJsonElement(s) as JsonObject

    @Test
    fun takesThreeSoundIntegersAndRefusesAnythingElse() {
        assertEquals(AudioFacts(9000, 10, 8000), AudioFacts.from(tier("""{"durationMs":9000,"trimStartMs":10,"trimEndMs":8000}""")))
        assertNull(AudioFacts.from(null))
        assertNull(AudioFacts.from(tier("""{"durationMs":9000,"trimStartMs":10}""")))
        assertNull(AudioFacts.from(tier("""{"durationMs":9000,"trimStartMs":10.5,"trimEndMs":8000}""")))
        assertNull(AudioFacts.from(tier("""{"durationMs":9000,"trimStartMs":-1,"trimEndMs":8000}""")))
        assertNull(AudioFacts.from(tier("""{"durationMs":9000,"trimStartMs":8000,"trimEndMs":8000}""")), "start = end is no span")
        assertNull(AudioFacts.from(tier("""{"durationMs":9000,"trimStartMs":8001,"trimEndMs":8000}""")), "start after end is no span")
        assertNull(AudioFacts.from(tier("""{"durationMs":7000,"trimStartMs":10,"trimEndMs":8000}""")))
        assertNull(AudioFacts.from(tier("""{"durationMs":"9000","trimStartMs":10,"trimEndMs":8000}""")))
    }
}
