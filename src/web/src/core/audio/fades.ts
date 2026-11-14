// Core's audio player (en.platform.audio-player, ADR 0135 decision 7): where and how long a crossfade runs. Tracks cross
// over 5 s at their trimmed edges; a hand-pressed next or previous over 1 s; a track shorter than two fades over a third of
// its trimmed length; and a seek into the fade window shortens the fade to what is left. Pure, milliseconds of the file.

/** The span that plays: from the end of the leading silence to the start of the trailing one. */
export type Span = { startMs: number; endMs: number }

export const FADE_MS = 5000
export const PRESS_FADE_MS = 1000

const lengthOf = (span: Span) => Math.max(0, span.endMs - span.startMs)

/** The fade a track ends with by itself: 5 s, or a third of its span where that is shorter than two fades. */
export function autoFadeMs(span: Span): number {
  const length = lengthOf(span)
  return length < 2 * FADE_MS ? length / 3 : FADE_MS
}

/** The fade a press (next, previous, another queue) runs: 1 s, or the track's own where that is shorter. */
export function pressFadeMs(span: Span): number {
  return Math.min(PRESS_FADE_MS, autoFadeMs(span))
}

/** Where in the file the track starts fading out by itself. */
export function fadeOutAtMs(span: Span): number {
  return span.endMs - autoFadeMs(span)
}

/** The fade that starts at `positionMs`: the track's own, shortened to what is left where a seek landed inside it. */
export function fadeLengthAt(span: Span, positionMs: number): number {
  return Math.max(0, Math.min(autoFadeMs(span), span.endMs - positionMs))
}
