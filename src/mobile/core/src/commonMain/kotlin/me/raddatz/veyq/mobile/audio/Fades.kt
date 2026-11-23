package me.raddatz.veyq.mobile.audio

// The core's music player (ADR 0142 decision 1; ADR 0135 decision 7): where and how long a crossfade runs — every client's
// rule, the web's through `@veyq/core-js/audio` (ADR 0155 decision 7.2), held by `contracts/platform/audio/v1-vectors.json`.
// Tracks cross over 5 s at their trimmed edges; a hand-pressed next or previous over 1 s; a track shorter than two fades
// over a third of its trimmed length; and a seek into the fade window shortens the fade to what is left. Pure,
// milliseconds of the file, as `Double`s: a file's duration in seconds times 1000 is fractional, a third of a length is
// not rounded, and an unknown end is `Double.POSITIVE_INFINITY` (the web's answers, which the vectors recorded). The apps'
// player keeps its `Long` clock through [Span], whose answers are these, truncated toward zero.

const val FADE_MS = 5000L
const val PRESS_FADE_MS = 1000L

private const val FADE = 5000.0 // FADE_MS
private const val PRESS_FADE = 1000.0 // PRESS_FADE_MS

/** The span's length: from its start to its end, none where the end lies before the start. */
private fun lengthOf(startMs: Double, endMs: Double): Double = maxOf(0.0, endMs - startMs)

/** The fade a track ends with by itself: 5 s, or a third of its span where that is shorter than two fades. */
fun autoFadeMs(startMs: Double, endMs: Double): Double {
    val length = lengthOf(startMs, endMs)
    return if (length < 2 * FADE) length / 3 else FADE
}

/** The fade a press (next, previous, another queue) runs: 1 s, or the track's own where that is shorter. */
fun pressFadeMs(startMs: Double, endMs: Double): Double = minOf(PRESS_FADE, autoFadeMs(startMs, endMs))

/** Where in the file the track starts fading out by itself. */
fun fadeOutAtMs(startMs: Double, endMs: Double): Double = endMs - autoFadeMs(startMs, endMs)

/** The fade that starts at [positionMs]: the track's own, shortened to what is left where a seek landed inside it. */
fun fadeLengthAt(startMs: Double, endMs: Double, positionMs: Double): Double =
    maxOf(0.0, minOf(autoFadeMs(startMs, endMs), endMs - positionMs))

/** The span that plays, on the apps' `Long` clock: from the end of the leading silence to the start of the trailing one.
 *  [UNKNOWN_END] where the file's length is not known. */
data class Span(val startMs: Long, val endMs: Long) {
    val lengthMs: Long get() = (endMs - startMs).coerceAtLeast(0)
    val known: Boolean get() = endMs != UNKNOWN_END

    internal val start: Double get() = startMs.toDouble()
    internal val end: Double get() = if (known) endMs.toDouble() else Double.POSITIVE_INFINITY

    companion object {
        const val UNKNOWN_END = Long.MAX_VALUE
    }
}

/** The fade's rule answered on the `Long` clock: truncated, and an infinite answer (an unknown end) [Span.UNKNOWN_END]. */
private fun clock(ms: Double): Long = if (ms == Double.POSITIVE_INFINITY) Span.UNKNOWN_END else ms.toLong()

fun autoFadeMs(span: Span): Long = clock(autoFadeMs(span.start, span.end))

fun pressFadeMs(span: Span): Long = clock(pressFadeMs(span.start, span.end))

fun fadeOutAtMs(span: Span): Long = clock(fadeOutAtMs(span.start, span.end))

fun fadeLengthAt(span: Span, positionMs: Long): Long = clock(fadeLengthAt(span.start, span.end, positionMs.toDouble()))
