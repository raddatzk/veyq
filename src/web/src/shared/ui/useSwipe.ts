// A horizontal swipe on a touch screen (inbox #863: the Memory lightbox pages on it, as its help page says). One finger or
// pen that travels far enough sideways, and clearly more sideways than up or down, is a swipe; anything else is left to
// whoever else listens on the same element - the lightbox's pan + zoom (`usePanZoom.ts`) binds the same pointer events.
//
// What is no swipe: a mouse (a mouse drags, it does not swipe - the arrow buttons and keys are there), a second finger
// (a pinch, from its first touch to the last finger's lift, even if one finger then travels on its own), a gesture the
// host refused when it began (`enabled()`: the lightbox refuses while a photo is zoomed, where a drag pans), one that
// starts on a <video> (its own controls scrub on a horizontal drag), and one the browser cancelled.
//
// The direction is the content's: a finger moving left brings the next item, as on every phone. The host decides what a
// swipe does; this file only says that one happened. Presentation only: no network, no bytes.

/** A finger that travelled less than this (px) sideways did not swipe. */
export const SWIPE_MIN_PX = 50
/** The sideways travel must be at least this many times the vertical one. */
export const SWIPE_AXIS_RATIO = 1.5

export type SwipeDirection = 'next' | 'previous'

export function useSwipe(onSwipe: (direction: SwipeDirection) => void, o: { enabled?: () => boolean } = {}) {
  const enabled = o.enabled ?? (() => true)
  // Every finger down on the element, so a second one turns the gesture into no swipe until all have lifted.
  const down = new Set<number>()
  let gesture: { id: number; x: number; y: number } | null = null
  let spoiled = false

  function onPointerDown(e: PointerEvent) {
    if (e.pointerType === 'mouse') return
    down.add(e.pointerId)
    if (down.size > 1) {
      spoiled = true // a pinch, or a second finger: no swipe until every finger is up again
      gesture = null
      return
    }
    spoiled = false
    const startsOnVideo = !!(e.target as Element | null)?.closest?.('video')
    gesture = enabled() && !startsOnVideo ? { id: e.pointerId, x: e.clientX, y: e.clientY } : null
  }

  function onPointerUp(e: PointerEvent) {
    if (!down.delete(e.pointerId)) return
    const g = gesture
    if (!g || g.id !== e.pointerId) return
    gesture = null
    if (spoiled || e.type !== 'pointerup' || !enabled()) return // cancelled by the browser, or zoomed meanwhile
    const dx = e.clientX - g.x
    const dy = e.clientY - g.y
    if (Math.abs(dx) < SWIPE_MIN_PX || Math.abs(dx) < SWIPE_AXIS_RATIO * Math.abs(dy)) return
    onSwipe(dx < 0 ? 'next' : 'previous')
  }

  return { onPointerDown, onPointerUp }
}
