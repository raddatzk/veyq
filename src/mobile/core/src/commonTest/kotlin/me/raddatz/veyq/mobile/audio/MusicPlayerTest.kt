package me.raddatz.veyq.mobile.audio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.navigation.ApplicationId
import me.raddatz.veyq.mobile.navigation.ObjectRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The web's `core/audio/player.test.ts`, case for case, and the interrupt cases of ADR 0142 decision 13, against the decks
 * and the system's controls a test plays ([PlayedAudio]) on virtual time: what the player opens and releases, when it
 * fades, what it skips, and what an interruption's end resumes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MusicPlayerTest {
    private class Harness(val audio: PlayedAudio, val player: MusicPlayer, private val test: TestScope) {
        val now: NowPlaying? get() = player.current.nowPlaying
        fun settle() = test.runCurrent()
        fun tick() {
            test.advanceTimeBy(MusicPlayer.TICK_MS)
            test.runCurrent()
        }

        /** Past a fade's end, where the outgoing deck is let go. */
        fun after(ms: Long) {
            test.advanceTimeBy(ms + MusicPlayer.CLEAR_AFTER_FADE_MS)
            test.runCurrent()
        }
        fun playing() = audio.playing()!!
    }

    private fun TestScope.harness(lengths: Map<String, Long> = emptyMap()): Harness {
        val audio = PlayedAudio(lengths)
        val player = MusicPlayer(audio::newDeck, audio.controls, random = { 0 }, scope = backgroundScope)
        return Harness(audio, player, this)
    }

    private val lisbon = ObjectRef("s1", "n1", "MEMORY", ApplicationId.MEMORIES, "Lisbon")

    @Test
    fun playsTheFirstTrackFromItsTrimmedStartFadingInOverOneSecondAndSaysWhatPlays() = runTest {
        val h = harness(mapOf("a" to 62_000))
        val a = h.audio.Track("a")
        h.player.play(PlayerQueue("n1", listOf(a.t), album = "Lisbon", route = lisbon))
        h.settle()
        val deck = h.playing()
        assertEquals("a" to 1000L, deck.name to deck.position)
        assertTrue(PlayedAudio.Ramp(deck.index, 1.0, 1000) in h.audio.ramps, "${h.audio.ramps}")
        assertEquals(NowPlaying("n1", "a", "Song a", "Lisbon", lisbon, playing = true, positionMs = 0, durationMs = 60_000), h.now)
        assertEquals(h.now, h.audio.controls.shown)
        assertSame(h.player, h.audio.controls.commands)
    }

    @Test
    fun crossfadesIntoTheNextTrackOverFiveSecondsOnceThePlayingOneReachesItsFadeOpeningItAhead() = runTest {
        val h = harness(mapOf("a" to 62_000, "b" to 62_000))
        val a = h.audio.Track("a")
        val b = h.audio.Track("b")
        h.player.play(PlayerQueue("n1", listOf(a.t, b.t)))
        h.settle()
        val first = h.playing()
        val firstId = h.now!!.trackId
        first.position = 41_000 // 15 s before the fade at 56 s: the next one is opened and loaded
        h.tick()
        assertEquals(2, a.opens + b.opens)
        h.audio.ramps.clear()
        first.position = 56_000 // the fade's start: trimmed end 61 s minus 5 s
        h.tick()
        assertEquals(listOf(1.0 to 5000L, 0.0 to 5000L), h.audio.fades.map { it.to to it.ms })
        assertNotEquals(firstId, h.now!!.trackId)
        // the faded-out source is let go once its fade has run
        assertEquals(0, a.releases + b.releases)
        h.after(FADE_MS)
        assertEquals(1, a.releases + b.releases)
    }

    @Test
    fun aSeekIntoTheFadeWindowShortensTheFadeToWhatIsLeft() = runTest {
        val h = harness(mapOf("a" to 62_000, "b" to 62_000))
        h.player.play(PlayerQueue("n1", listOf(h.audio.Track("a").t, h.audio.Track("b").t)))
        h.settle()
        h.player.seek(58_000) // into the span: 1 s + 58 s = 59 s of the file, 2 s before the trimmed end
        h.settle()
        assertEquals(59_000L, h.playing().position)
        h.audio.ramps.clear()
        h.tick()
        assertEquals(listOf(2000L, 2000L), h.audio.fades.map { it.ms })
    }

    @Test
    fun aFailingOpenSkipsTheTrack() = runTest {
        val h = harness(mapOf("b" to 62_000))
        val bad = h.audio.Track("a", name = "fail")
        val good = h.audio.Track("b")
        h.player.play(PlayerQueue("n1", listOf(bad.t, good.t), startId = "a"))
        h.settle()
        assertEquals("b", h.now?.trackId)
        assertEquals(setOf("a"), h.player.current.skipped)
    }

    @Test
    fun aQueueNoneOfWhoseTracksPlaysEndsAndSaysSoEachTrackTriedOnce() = runTest {
        val h = harness()
        val tracks = listOf(h.audio.Track("x", name = "fail"), h.audio.Track("y", name = "broken"), h.audio.Track("z", name = "unplayable"))
        h.player.play(PlayerQueue("n2", tracks.map { it.t }))
        h.settle()
        assertEquals(null to PlayerNotice.UNPLAYABLE, h.now to h.player.current.notice)
        // each dropped from the order once it failed: none tried again
        assertEquals(listOf(1, 1, 1), tracks.map { it.opens })
        assertEquals(setOf("x", "y", "z"), h.player.current.skipped)
        assertEquals(0, h.audio.openReaders)
    }

    @Test
    fun aNewQueueReplacesTheOnePlayingCrossfadingOverOneSecondAndReleasingTheOldSource() = runTest {
        val h = harness(mapOf("a" to 62_000, "m" to 62_000))
        val a = h.audio.Track("a")
        h.player.play(PlayerQueue("n1", listOf(a.t)))
        h.settle()
        h.audio.ramps.clear()
        h.player.play(PlayerQueue("n2", listOf(h.audio.Track("m").t)))
        h.settle()
        assertEquals("n2" to "m", h.now!!.key to h.now!!.trackId)
        assertEquals(listOf(1.0 to 1000L, 0.0 to 1000L), h.audio.fades.map { it.to to it.ms })
        h.after(PRESS_FADE_MS)
        assertEquals(1, a.releases)
    }

    @Test
    fun stopReleasesEverySourceItOpenedThePlayingOneAndOneOpenedAheadAndClearsTheSystemsControls() = runTest {
        val h = harness(mapOf("a" to 62_000, "b" to 62_000))
        val a = h.audio.Track("a")
        val b = h.audio.Track("b")
        h.player.play(PlayerQueue("n1", listOf(a.t, b.t)))
        h.settle()
        h.playing().position = 45_000
        h.tick()
        assertEquals(1 to 1, a.opens to b.opens)
        h.player.stop()
        h.settle()
        assertEquals(1 to 1, a.releases to b.releases)
        assertNull(h.now)
        assertEquals(Triple(null, null, 1), Triple(h.audio.controls.commands, h.audio.controls.shown, h.audio.controls.cleared))
        assertTrue(h.audio.decks.none { it.playing })
    }

    @Test
    fun aSourceThatOpensAfterTheQueueWasStoppedIsReleasedAtOnce() = runTest {
        val h = harness(mapOf("a" to 62_000))
        val gate = CompletableDeferred<Unit>()
        val slow = h.audio.Track("s", name = "a", gate = gate)
        h.player.play(PlayerQueue("n1", listOf(slow.t)))
        h.settle()
        h.player.stop()
        h.settle()
        gate.complete(Unit)
        h.settle()
        assertEquals(1, slow.releases)
        assertEquals(0, h.audio.openReaders)
        assertNull(h.now)
    }

    @Test
    fun nextCrossfadesOverOneSecondPreviousRestartsAfterThreeSecondsAndGoesBackBefore() = runTest {
        val h = harness(mapOf("a" to 62_000, "b" to 62_000, "c" to 62_000))
        h.player.play(PlayerQueue("n1", listOf(h.audio.Track("a").t, h.audio.Track("b").t, h.audio.Track("c").t)))
        h.settle()
        val first = h.now!!.trackId
        h.audio.ramps.clear()
        h.player.next()
        h.settle()
        val second = h.now!!.trackId
        assertNotEquals(first, second)
        assertEquals(listOf(1000L, 1000L), h.audio.fades.map { it.ms })
        h.playing().position = 1000 + 4000 // 4 s into the span
        h.player.previous()
        h.settle()
        assertEquals(second to 1000L, h.now!!.trackId to h.playing().position)
        h.playing().position = 1000 + 2000 // 2 s in
        h.player.previous()
        h.settle()
        assertEquals(first, h.now!!.trackId)
        // the system's controls reach the same doors
        h.audio.controls.commands!!.next()
        h.settle()
        assertEquals(second, h.now!!.trackId)
    }

    @Test
    fun aFileThatEndsBeforeItsSpanMovesOnAtOnceWithoutAFade() = runTest {
        // the web's "without Web Audio" case: every deck here ramps, so a cut happens only where the deck ends first
        val h = harness(mapOf("a" to 30_000, "b" to 62_000))
        h.player.play(PlayerQueue("n1", listOf(h.audio.Track("a").t, h.audio.Track("b").t)))
        h.settle()
        val first = h.now!!.trackId
        val deck = h.playing()
        deck.position = 30_000
        h.tick()
        assertEquals(first, h.now!!.trackId, "no change before the deck says it ended: its fade is still 26 s away")
        h.audio.ramps.clear()
        deck.events.ended()
        h.settle()
        assertNotEquals(first, h.now!!.trackId)
        assertFalse(deck.playing, "the old one stops at once")
        assertEquals(emptyList(), h.audio.fades)
    }

    @Test
    fun aTrackWithoutFactsPlaysItsWholeFileItsLengthReadFromTheDeck() = runTest {
        val h = harness(mapOf("a" to 30_000))
        h.player.play(PlayerQueue("n1", listOf(h.audio.Track("a", startMs = null, endMs = null).t)))
        h.settle()
        assertEquals(0L to 30_000L, h.playing().position to h.now!!.durationMs)
    }

    @Test
    fun pauseAndResumeReachThePlayingDeckAndTheSystemsControls() = runTest {
        val h = harness(mapOf("a" to 62_000))
        h.player.play(PlayerQueue("n1", listOf(h.audio.Track("a").t)))
        h.settle()
        val deck = h.playing()
        h.audio.controls.commands!!.pause()
        h.settle()
        assertEquals(Triple(false, false, false), Triple(deck.playing, h.now!!.playing, h.audio.controls.shown!!.playing))
        h.player.resume()
        h.settle()
        assertEquals(Triple(true, true, true), Triple(deck.playing, h.now!!.playing, h.audio.controls.shown!!.playing))
    }

    @Test
    fun theSystemsControlsHearThePositionOnceASecondWhilePlaying() = runTest {
        val h = harness(mapOf("a" to 62_000))
        h.player.play(PlayerQueue("n1", listOf(h.audio.Track("a").t)))
        h.settle()
        val deck = h.playing()
        deck.position = 1000 + 1500
        h.tick()
        assertEquals(1500L, h.audio.controls.shown!!.positionMs)
        deck.position = 1000 + 1700
        h.tick()
        assertEquals(1700L to 1500L, h.now!!.positionMs to h.audio.controls.shown!!.positionMs)
    }

    // --- a lightbox video interrupts the music (ADR 0142 decision 13; phase 2's web cases) -------------------------------

    @Test
    fun anInterruptionPausesThePlayingQueueAndTheSystemsControlsShowItPaused() = runTest {
        val h = harness(mapOf("a" to 62_000))
        h.player.play(PlayerQueue("n1", listOf(h.audio.Track("a").t)))
        h.settle()
        val deck = h.playing()
        h.player.interrupt()
        h.settle()
        assertEquals(Triple(false, false, false), Triple(deck.playing, h.now!!.playing, h.audio.controls.shown!!.playing))
    }

    @Test
    fun anInterruptionsEndResumesWhatItPaused() = runTest {
        val h = harness(mapOf("a" to 62_000))
        h.player.play(PlayerQueue("n1", listOf(h.audio.Track("a").t)))
        h.settle()
        val deck = h.playing()
        val video = h.player.interrupt()
        h.settle()
        video.end()
        h.settle()
        assertEquals(Triple(true, true, true), Triple(deck.playing, h.now!!.playing, h.audio.controls.shown!!.playing))
        // an end said twice resumes once
        val plays = deck.plays
        h.player.pause()
        video.end()
        h.settle()
        assertEquals(plays to false, deck.plays to deck.playing)
    }

    @Test
    fun aListenersPauseDuringTheVideoKeepsTheMusicPausedAfterIt() = runTest {
        val h = harness(mapOf("a" to 62_000))
        h.player.play(PlayerQueue("n1", listOf(h.audio.Track("a").t)))
        h.settle()
        val deck = h.playing()
        val video = h.player.interrupt()
        h.settle()
        h.audio.controls.commands!!.pause()
        video.end()
        h.settle()
        assertEquals(false to false, deck.playing to h.now!!.playing)
    }

    @Test
    fun aQueuePausedBeforeTheVideoStaysPausedAfterIt() = runTest {
        val h = harness(mapOf("a" to 62_000))
        h.player.play(PlayerQueue("n1", listOf(h.audio.Track("a").t)))
        h.settle()
        val deck = h.playing()
        h.player.pause()
        val video = h.player.interrupt()
        h.settle()
        video.end()
        h.settle()
        assertEquals(false to false, deck.playing to h.now!!.playing)
    }

    @Test
    fun aListenersPlayDuringTheVideoSoundsBothAndTheEndThenDoesNothing() = runTest {
        val h = harness(mapOf("a" to 62_000))
        h.player.play(PlayerQueue("n1", listOf(h.audio.Track("a").t)))
        h.settle()
        val deck = h.playing()
        val video = h.player.interrupt()
        h.settle()
        h.player.resume()
        h.settle()
        assertTrue(deck.playing)
        val plays = deck.plays
        video.end()
        h.settle()
        assertEquals(plays, deck.plays, "the end played nothing again")
    }

    @Test
    fun aStopDuringTheVideoMakesTheEndDoNothing() = runTest {
        val h = harness(mapOf("a" to 62_000))
        val a = h.audio.Track("a")
        h.player.play(PlayerQueue("n1", listOf(a.t)))
        h.settle()
        val video = h.player.interrupt()
        h.settle()
        h.player.stop()
        video.end()
        h.settle()
        assertNull(h.now)
        assertTrue(h.audio.decks.none { it.playing })
        assertEquals(1, a.opens)
    }

    @Test
    fun aNewQueueDuringTheVideoMakesTheEndDoNothing() = runTest {
        val h = harness(mapOf("a" to 62_000, "m" to 62_000))
        h.player.play(PlayerQueue("n1", listOf(h.audio.Track("a").t)))
        h.settle()
        val video = h.player.interrupt()
        h.settle()
        h.player.play(PlayerQueue("n2", listOf(h.audio.Track("m").t)))
        h.settle()
        val deck = h.playing()
        assertEquals("m", deck.name)
        val plays = deck.plays
        video.end()
        h.settle()
        assertEquals(plays, deck.plays, "the end played nothing again")
    }

    @Test
    fun aTrackStillLoadingWhenTheVideoStartsStaysSilentUntilItsEnd() = runTest {
        val h = harness(mapOf("a" to 62_000))
        val gate = CompletableDeferred<Unit>()
        h.player.play(PlayerQueue("n1", listOf(h.audio.Track("a", gate = gate).t)))
        h.settle()
        val video = h.player.interrupt()
        h.settle()
        gate.complete(Unit)
        h.settle()
        assertEquals(0, h.audio.decks.sumOf { it.plays }, "no sound during the video")
        assertEquals(false, h.now!!.playing)
        video.end()
        h.settle()
        assertEquals("a", h.playing().name)
        assertEquals(true, h.now!!.playing)
    }

    @Test
    fun aNextTrackStillLoadingWhenTheVideoStartsStaysSilentUntilItsEnd() = runTest {
        val h = harness(mapOf("a" to 62_000, "b" to 62_000))
        val gate = CompletableDeferred<Unit>()
        h.player.play(PlayerQueue("n1", listOf(h.audio.Track("a").t, h.audio.Track("b", gate = gate).t), startId = "a"))
        h.settle()
        h.player.next()
        h.settle()
        val video = h.player.interrupt()
        h.settle()
        gate.complete(Unit)
        h.settle()
        assertTrue(h.audio.decks.none { it.playing }, "no sound during the video")
        video.end()
        h.settle()
        assertEquals("b", h.playing().name)
    }

    @Test
    fun aListenersPlayWhileTheHeldTrackLoadsStartsIt() = runTest {
        val h = harness(mapOf("a" to 62_000))
        val gate = CompletableDeferred<Unit>()
        h.player.play(PlayerQueue("n1", listOf(h.audio.Track("a", gate = gate).t)))
        h.settle()
        h.player.interrupt()
        h.settle()
        h.player.resume()
        gate.complete(Unit)
        h.settle()
        assertEquals("a", h.playing().name)
    }

    @Test
    fun anInterruptionWithNothingPlayingIsHarmless() = runTest {
        val h = harness()
        val video = h.player.interrupt()
        video.end()
        h.settle()
        assertNull(h.now)
    }
}
