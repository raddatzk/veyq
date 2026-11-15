package me.raddatz.veyq.mobile.audio

// The core's music player (ADR 0142 decision 1; ADR 0135 decision 7), the web's `core/audio/fades.ts` ported: where and
// how long a crossfade runs. Tracks cross over 5 s at their trimmed edges; a hand-pressed next or previous over 1 s; a
// track shorter than two fades over a third of its trimmed length; and a seek into the fade window shortens the fade to
// what is left. Pure, milliseconds of the file.

/** The span that plays: from the end of the leading silence to the start of the trailing one. [UNKNOWN_END] where the
 *  file's length is not known (the web's `Infinity`). */
data class Span(val startMs: Long, val endMs: Long) {
    val lengthMs: Long get() = (endMs - startMs).coerceAtLeast(0)
    val known: Boolean get() = endMs != UNKNOWN_END

    companion object {
        const val UNKNOWN_END = Long.MAX_VALUE
    }
}

const val FADE_MS = 5000L
const val PRESS_FADE_MS = 1000L

/** The fade a track ends with by itself: 5 s, or a third of its span where that is shorter than two fades. */
fun autoFadeMs(span: Span): Long {
    val length = span.lengthMs
    return if (length < 2 * FADE_MS) length / 3 else FADE_MS
}

/** The fade a press (next, previous, another queue) runs: 1 s, or the track's own where that is shorter. */
fun pressFadeMs(span: Span): Long = minOf(PRESS_FADE_MS, autoFadeMs(span))

/** Where in the file the track starts fading out by itself. */
fun fadeOutAtMs(span: Span): Long = span.endMs - autoFadeMs(span)

/** The fade that starts at [positionMs]: the track's own, shortened to what is left where a seek landed inside it. */
fun fadeLengthAt(span: Span, positionMs: Long): Long = maxOf(0, minOf(autoFadeMs(span), span.endMs - positionMs))
