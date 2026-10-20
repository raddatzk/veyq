// Pan + zoom over ONE content box shown inside a viewport (cr-lightbox-zoom-slideshow: the Memory lightbox's stage).
// The math is ImageCropper's (`ImageCropper.vue`): zoom about a point so the content under it stays put, clamp the pan
// to the content, capture the pointer for a drag. The one difference is the frame: the cropper's image FILLS its frame
// (no empty corner may show), this content is CONTAINED - fitted and centred at 1x - so the pan is bounded to the part of
// the zoomed content that overhangs the viewport, and an axis that does not overhang stays centred.
//
// Coordinates: the content is centred in the viewport and transformed `translate(x, y) scale(s)` about its own centre
// (CSS's default `transform-origin`), so a content point `u` from its centre lands at `viewport centre + (x, y) + s·u`.
// Every point handed in is relative to the viewport's centre.
//
// Presentation only: no network, no bytes. The gestures (double-tap / double-click toggles 1x <-> 2x at the pointer,
// a two-pointer pinch and a ctrl+wheel zoom continuously, a drag pans while zoomed) are plain handlers the host binds on
// its viewport; what a gesture means beyond the transform (pausing a slideshow, loading a sharper source) is the host's.
import { computed, ref } from 'vue'

export type Size = { w: number; h: number }
export type Point = { x: number; y: number }

export const MIN_ZOOM = 1
export const MAX_ZOOM = 8
/** The scale a double-tap / double-click zooms to from 1x. */
export const TAP_ZOOM = 2
/** The factor one `+` / `-` key press zooms by. */
export const KEY_ZOOM_STEP = 1.5
/** The pixels one arrow key press pans by while zoomed. */
export const KEY_PAN_STEP = 80
/** Two taps closer than this in time (ms) and space (px) are a double-tap. */
export const DOUBLE_TAP_MS = 300
export const DOUBLE_TAP_SLOP = 24
/** A press that moved less than this (px) is a tap, not a drag. */
export const TAP_MOVE_SLOP = 6
/** The zoom per wheel pixel (`exp(-deltaY * this)`), and a line's / a page's pixels for a wheel that reports those. */
export const WHEEL_ZOOM_PER_PX = 0.005
const WHEEL_LINE_PX = 16
const WHEEL_PAGE_PX = 400

const clamp = (v: number, min: number, max: number) => Math.min(max, Math.max(min, v))

/** The pan bounded so the zoomed content never shows a gap where it overhangs the viewport, centred where it does not. */
export function clampPan(offset: Point, scale: number, content: Size, viewport: Size): Point {
  const maxX = Math.max(0, (content.w * scale - viewport.w) / 2)
  const maxY = Math.max(0, (content.h * scale - viewport.h) / 2)
  return { x: clamp(offset.x, -maxX, maxX), y: clamp(offset.y, -maxY, maxY) }
}

/** The pan that keeps the content point under `at` under it when the scale goes from `scale` to `next`. */
export function zoomAbout(scale: number, offset: Point, next: number, at: Point): Point {
  return { x: at.x - (next * (at.x - offset.x)) / scale, y: at.y - (next * (at.y - offset.y)) / scale }
}

/** What the host measures on demand: the content's fitted (1x, untransformed) size and the viewport's box. */
export type PanZoomFrame = { content: Size; viewport: DOMRectReadOnly | { left: number; top: number; width: number; height: number } }

export function usePanZoom(frame: () => PanZoomFrame | null, o: { now?: () => number } = {}) {
  const now = o.now ?? (() => performance.now())
  const scale = ref(1)
  const x = ref(0)
  const y = ref(0)
  /** A drag or a pinch is under way (the host drops its transition then). */
  const gesturing = ref(false)
  const zoomed = computed(() => scale.value > MIN_ZOOM)
  const transform = computed(() =>
    scale.value === MIN_ZOOM && x.value === 0 && y.value === 0 ? undefined : `translate(${x.value}px, ${y.value}px) scale(${scale.value})`,
  )

  const measured = () => {
    const f = frame()
    return f ? { content: f.content, viewport: { w: f.viewport.width, h: f.viewport.height }, box: f.viewport } : null
  }
  function place(offset: Point) {
    const m = measured()
    const p = m ? clampPan(offset, scale.value, m.content, m.viewport) : { x: 0, y: 0 }
    x.value = p.x
    y.value = p.y
  }
  /** A client point relative to the viewport's centre. */
  function fromClient(clientX: number, clientY: number): Point {
    const box = frame()?.viewport
    return box ? { x: clientX - (box.left + box.width / 2), y: clientY - (box.top + box.height / 2) } : { x: 0, y: 0 }
  }

  function zoomTo(next: number, at: Point = { x: 0, y: 0 }) {
    const s = clamp(next, MIN_ZOOM, MAX_ZOOM)
    if (s === scale.value) return
    const offset = zoomAbout(scale.value, { x: x.value, y: y.value }, s, at)
    scale.value = s
    place(offset)
  }
  const zoomBy = (factor: number, at?: Point) => zoomTo(scale.value * factor, at)
  function reset() {
    scale.value = MIN_ZOOM
    x.value = 0
    y.value = 0
    pointers.clear()
    pinch = null
    drag = null
    lastTap = null
    gesturing.value = false
  }
  /** 1x -> 2x at `at`; any zoom -> 1x. */
  const toggle = (at?: Point) => (zoomed.value ? reset() : zoomTo(TAP_ZOOM, at))

  // --- gestures ------------------------------------------------------------------------------------------------------
  const pointers = new Map<number, Point>()
  let pinch: { distance: number; scale: number } | null = null
  let drag: { id: number; start: Point; offset: Point; moved: boolean } | null = null
  let lastTap: { at: number; client: Point } | null = null

  const twoPointers = () => [...pointers.values()].slice(0, 2)
  const distance = (a: Point, b: Point) => Math.hypot(a.x - b.x, a.y - b.y)

  function onPointerDown(e: PointerEvent) {
    if (e.pointerType === 'mouse' && e.button !== 0) return
    pointers.set(e.pointerId, { x: e.clientX, y: e.clientY })
    ;(e.currentTarget as Element | null)?.setPointerCapture(e.pointerId)
    if (pointers.size === 2) {
      const [a, b] = twoPointers()
      pinch = { distance: distance(a, b) || 1, scale: scale.value }
      drag = null
      lastTap = null // a pinch is no tap
      gesturing.value = true
    } else if (pointers.size === 1) {
      drag = { id: e.pointerId, start: { x: e.clientX, y: e.clientY }, offset: { x: x.value, y: y.value }, moved: false }
    }
  }

  function onPointerMove(e: PointerEvent) {
    if (!pointers.has(e.pointerId)) return
    pointers.set(e.pointerId, { x: e.clientX, y: e.clientY })
    if (pinch && pointers.size >= 2) {
      const [a, b] = twoPointers()
      zoomTo(pinch.scale * (distance(a, b) / pinch.distance), fromClient((a.x + b.x) / 2, (a.y + b.y) / 2))
      return
    }
    if (!drag || drag.id !== e.pointerId) return
    const dx = e.clientX - drag.start.x
    const dy = e.clientY - drag.start.y
    if (!drag.moved && Math.hypot(dx, dy) < TAP_MOVE_SLOP) return
    drag.moved = true
    if (!zoomed.value) return // at 1x there is nothing to pan
    gesturing.value = true
    place({ x: drag.offset.x + dx, y: drag.offset.y + dy })
  }

  function onPointerUp(e: PointerEvent) {
    if (!pointers.delete(e.pointerId)) return
    const target = e.currentTarget as Element | null
    if (target?.hasPointerCapture(e.pointerId)) target.releasePointerCapture(e.pointerId)
    if (pinch) {
      if (pointers.size < 2) pinch = null
      // the finger left on the glass carries on as a drag from where it is, never as a tap
      const [rest] = [...pointers.entries()]
      drag = rest ? { id: rest[0], start: rest[1], offset: { x: x.value, y: y.value }, moved: true } : null
      gesturing.value = pointers.size > 0
      return
    }
    const wasTap = !!drag && drag.id === e.pointerId && !drag.moved && e.type === 'pointerup'
    drag = null
    gesturing.value = false
    if (!wasTap) return
    const t = now()
    const client = { x: e.clientX, y: e.clientY }
    if (lastTap && t - lastTap.at <= DOUBLE_TAP_MS && distance(lastTap.client, client) <= DOUBLE_TAP_SLOP) {
      lastTap = null
      toggle(fromClient(client.x, client.y))
    } else {
      lastTap = { at: t, client }
    }
  }

  /** ctrl+wheel - and a trackpad's pinch, which browsers report as one - zooms about the pointer; a plain wheel is left alone.
   *  The delta is read in pixels (Firefox reports a mouse wheel in lines): one ~100 px notch zooms by about 1.65x. */
  function onWheel(e: WheelEvent) {
    if (!e.ctrlKey) return
    e.preventDefault()
    const px = e.deltaY * (e.deltaMode === 1 ? WHEEL_LINE_PX : e.deltaMode === 2 ? WHEEL_PAGE_PX : 1)
    zoomBy(Math.exp(-px * WHEEL_ZOOM_PER_PX), fromClient(e.clientX, e.clientY))
  }

  /** The arrow keys' pan while zoomed: an arrow looks that way, so the content moves the other. */
  const ARROW_PAN: Record<string, Point> = {
    ArrowLeft: { x: KEY_PAN_STEP, y: 0 },
    ArrowRight: { x: -KEY_PAN_STEP, y: 0 },
    ArrowUp: { x: 0, y: KEY_PAN_STEP },
    ArrowDown: { x: 0, y: -KEY_PAN_STEP },
  }

  /** `+` / `-` zoom about the centre, `0` back to 1x, and while zoomed the arrow keys pan (at 1x they are the host's). True
   *  when the key was taken. */
  function onKey(key: string): boolean {
    const pan = zoomed.value ? ARROW_PAN[key] : undefined
    if (pan) place({ x: x.value + pan.x, y: y.value + pan.y })
    else if (key === '+' || key === '=') zoomBy(KEY_ZOOM_STEP)
    else if (key === '-') zoomBy(1 / KEY_ZOOM_STEP)
    else if (key === '0') reset()
    else return false
    return true
  }

  return {
    scale, x, y, zoomed, gesturing, transform,
    zoomTo, zoomBy, reset, toggle,
    onPointerDown, onPointerMove, onPointerUp, onWheel, onKey,
  }
}
