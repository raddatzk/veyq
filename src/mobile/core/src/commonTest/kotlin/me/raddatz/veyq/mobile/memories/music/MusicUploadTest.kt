package me.raddatz.veyq.mobile.memories.music

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.memories.music.AudioFiles.Spec
import me.raddatz.veyq.mobile.memories.music.AudioFiles.picked
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The web's `audio.test.ts` `checkAudioFile` cases, case for case, and the listed tier a track is sealed with (ADR 0142
 * decision 7): the refusals in their order, the type its bytes name (never the claimed one), its facts.
 */
class MusicUploadTest {
    private fun ascii(s: String) = s.encodeToByteArray()
    private fun m4a() = ByteArray(64).also { ascii("ftyp").copyInto(it, 4); ascii("M4A ").copyInto(it, 8) }
    private fun id3Mp3() = ascii("ID3") + byteArrayOf(4, 0, 0, 0, 0, 0, 0) + ByteArray(54)
    private fun frameMp3() = byteArrayOf(0xff.toByte(), 0xfb.toByte(), 0x90.toByte(), 0x64) + ByteArray(60)
    private fun wav() = ByteArray(64).also { ascii("RIFF").copyInto(it, 0); ascii("WAVE").copyInto(it, 8) }
    private fun mp4Video() = ByteArray(64).also { ascii("ftyp").copyInto(it, 4); ascii("isom").copyInto(it, 8) }
    private fun jpeg() = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte()) + ByteArray(60)

    /** The platform's length, and which types it was asked for. */
    private class Durations(val ms: Long? = 3000) {
        val asked = mutableListOf<String>()
        suspend fun of(type: String): Long? = ms.also { asked += type }
    }

    @Test
    fun typesAnM4aAnMp3AndAWavByTheirHeadsNeverByTheClaimedType() = runTest {
        val d = Durations()
        val types = listOf(
            picked(m4a(), "a.m4a", "video/mp4"), picked(id3Mp3(), "b.mp3"), picked(frameMp3(), "c.mp3", "audio/mp3"), picked(wav(), "d.wav", "audio/x-wav"),
        ).map { (MusicUpload.check(it, durationOf = d::of) as MusicUpload.Check.Taken).type }
        assertEquals(listOf("audio/mp4", "audio/mpeg", "audio/mpeg", "audio/wav"), types, "an M4A is sealed audio/mp4, never the video/mp4 claimed")
        assertEquals(types, d.asked, "the platform is asked for the length of the type the bytes name")
    }

    @Test
    fun handsTheFactsOverWithTheFile() = runTest {
        val bytes = AudioFiles.wavBytes(Spec(16, 1, 8000, listOf(2.0 to 0.0, 3.0 to 0.5, 2.0 to 0.0)))
        val f = picked(bytes, "tone.wav")
        val taken = assertIs<MusicUpload.Check.Taken>(MusicUpload.check(f) { 7000 })
        assertEquals(f, taken.source)
        assertEquals(AudioFacts(7000, 2000, 5000), taken.facts)
    }

    @Test
    fun refusesAFileOver200MiBWithoutReadingIt() = runTest {
        var reads = 0
        val big = UploadSource.of("big.wav", MusicUpload.MAX_BYTES + 1, null, null) { _, _ -> reads++; wav() }
        val d = Durations()
        val refused = assertIs<MusicUpload.Check.Refused>(MusicUpload.check(big, durationOf = d::of))
        assertEquals(MusicUpload.Problem.TOO_LARGE, refused.problem)
        assertEquals(Notice(Msg.MUSIC_TOO_LARGE, "big.wav"), refused.notice)
        assertEquals(0 to 0, reads to d.asked.size)
        val atCap = UploadSource.of("cap.wav", MusicUpload.MAX_BYTES, null, null) { _, n -> wav().copyOf(minOf(n, 64)) }
        assertEquals("audio/wav", (MusicUpload.check(atCap, durationOf = d::of) as MusicUpload.Check.Taken).type, "200 MiB itself is taken")
    }

    @Test
    fun refusesWhatIsNoAudioByItsBytesWithoutMeasuringIt() = runTest {
        val d = Durations()
        for (f in listOf(picked(jpeg(), "p.jpg"), picked(mp4Video(), "v.mp4"), picked(jpeg(), "fake.mp3", "audio/mpeg"), picked(ByteArray(0), "empty.mp3"))) {
            assertEquals(MusicUpload.Problem.NOT_AUDIO, (MusicUpload.check(f, durationOf = d::of) as MusicUpload.Check.Refused).problem, f.name)
        }
        assertTrue(d.asked.isEmpty())
    }

    @Test
    fun refusesAFileWhoseLengthThePlatformCannotRead() = runTest {
        for (d in listOf(Durations(null), Durations(0))) {
            assertEquals(MusicUpload.Problem.UNPLAYABLE, (MusicUpload.check(picked(id3Mp3(), "b.mp3"), durationOf = d::of) as MusicUpload.Check.Refused).problem)
        }
        val threw = MusicUpload.check(picked(id3Mp3(), "b.mp3")) { throw IllegalStateException("no player") }
        assertEquals(Notice(Msg.MUSIC_UNPLAYABLE, "b.mp3"), (threw as MusicUpload.Check.Refused).notice)
    }

    @Test
    fun aTracksListedTierIsTheFilesDateThenItsThreeFactsInTheWebsOrder() {
        assertEquals(
            """{"v":1,"capturedAt":1700000000000,"capturedAtSource":"file","durationMs":7000,"trimStartMs":2000,"trimEndMs":5000}""",
            MusicUpload.metaTier(AudioFacts(7000, 2000, 5000), 1_700_000_000_000L).toString(),
        )
        assertEquals("""{"v":1,"durationMs":3000,"trimStartMs":0,"trimEndMs":3000}""", MusicUpload.metaTier(AudioFacts.untrimmed(3000), null).toString())
    }
}
