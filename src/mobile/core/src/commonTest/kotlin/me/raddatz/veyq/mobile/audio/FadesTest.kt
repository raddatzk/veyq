package me.raddatz.veyq.mobile.audio

import kotlin.test.Test
import kotlin.test.assertEquals

/** The web's `core/audio/fades.test.ts`, case for case (ADR 0135 decision 7, ADR 0142 decision 1). */
class FadesTest {
    private val song = Span(400, 180_400)

    @Test
    fun aTrackFadesOutOverFiveSecondsEndingAtItsTrimmedEnd() {
        assertEquals(FADE_MS to 175_400L, autoFadeMs(song) to fadeOutAtMs(song))
    }

    @Test
    fun aHandPressedNextOrPreviousFadesOverOneSecond() {
        assertEquals(1000L, pressFadeMs(song))
    }

    @Test
    fun aTrackShorterThanTwoFadesFadesOverAThirdOfItsSpanAndAPressNoLongerThanThat() {
        val short = Span(1000, 7000)
        assertEquals(2000L to 5000L, autoFadeMs(short) to fadeOutAtMs(short))
        val tiny = Span(0, 1500)
        assertEquals(500L to 500L, autoFadeMs(tiny) to pressFadeMs(tiny))
    }

    @Test
    fun aSeekIntoTheFadeWindowShortensTheFadeToWhatIsLeftBeforeItTheFadeIsWhole() {
        assertEquals(2000L, fadeLengthAt(song, 178_400))
        assertEquals(FADE_MS, fadeLengthAt(song, 175_400))
        assertEquals(FADE_MS, fadeLengthAt(song, 100_000))
        assertEquals(0L, fadeLengthAt(song, 190_000))
    }
}
