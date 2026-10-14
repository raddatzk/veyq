// The lightbox's pan + zoom (cr-lightbox-zoom-slideshow): the math and the gestures, driven with plain event objects against
// a measured frame - a 640 x 480 content box fitted into a 800 x 600 viewport whose top-left is (100, 50), so its centre
// sits at client (500, 350).
import { describe, it, expect } from 'vitest'
import { clampPan, zoomAbout, usePanZoom, MAX_ZOOM, type PanZoomFrame } from './usePanZoom'

const FRAME: PanZoomFrame = { content: { w: 640, h: 480 }, viewport: { left: 100, top: 50, width: 800, height: 600 } }
const CENTRE = { x: 500, y: 350 }

function setup() {
  let t = 0
  const z = usePanZoom(() => FRAME, { now: () => t })
  const el = { setPointerCapture: () => {}, hasPointerCapture: () => true, releasePointerCapture: () => {} }
  const ev = (type: string, id: number, x: number, y: number, o: Record<string, unknown> = {}) =>
    ({ type, pointerId: id, clientX: x, clientY: y, pointerType: 'touch', button: 0, currentTarget: el, ...o }) as unknown as PointerEvent
  const down = (id: number, x: number, y: number) => z.onPointerDown(ev('pointerdown', id, x, y))
  const move = (id: number, x: number, y: number) => z.onPointerMove(ev('pointermove', id, x, y))
  const up = (id: number, x: number, y: number) => z.onPointerUp(ev('pointerup', id, x, y))
  const tap = (x: number, y: number) => { down(1, x, y); up(1, x, y) }
  return { z, down, move, up, tap, advance: (ms: number) => { t += ms } }
}

describe('pan/zoom math', () => {
  it('keeps the content point under the pointer under it', () => {
    // at 1x, the point 100 px right of centre is content point +100; at 2x it must still sit under the pointer
    const o = zoomAbout(1, { x: 0, y: 0 }, 2, { x: 100, y: 0 })
    expect(o).toEqual({ x: -100, y: 0 })
    expect(o.x + 2 * 100).toBe(100) // content point +100 lands at +100 again
  })

  it('bounds the pan to the overhang, and centres an axis that does not overhang', () => {
    // 640 x 480 at 3x = 1920 x 1440 in 800 x 600: overhang 560 / 420 each side
    expect(clampPan({ x: 999, y: -999 }, 3, FRAME.content, { w: 800, h: 600 })).toEqual({ x: 560, y: -420 })
    // at 1.2x the width (768) still fits: x is pinned to the centre, y (576 < 600) too
    expect(clampPan({ x: 50, y: 50 }, 1.2, FRAME.content, { w: 800, h: 600 })).toEqual({ x: 0, y: 0 })
  })
})

describe('usePanZoom gestures', () => {
  it('a double-tap zooms to 2x at the pointer, and a second one back to 1x', () => {
    const { z, tap, advance } = setup()
    tap(CENTRE.x + 100, CENTRE.y)
    advance(120)
    tap(CENTRE.x + 100, CENTRE.y)
    expect(z.scale.value).toBe(2)
    expect(z.x.value).toBe(-100) // zoomed about the tap, inside the 240 px overhang
    advance(1000)
    tap(CENTRE.x, CENTRE.y)
    advance(100)
    tap(CENTRE.x, CENTRE.y)
    expect(z.scale.value).toBe(1)
    expect([z.x.value, z.y.value]).toEqual([0, 0])
  })

  it('two taps too far apart in time or space are no double-tap', () => {
    const { z, tap, advance } = setup()
    tap(500, 350)
    advance(400)
    tap(500, 350)
    expect(z.scale.value, 'too slow').toBe(1)
    advance(1000)
    tap(500, 350)
    advance(100)
    tap(600, 350)
    expect(z.scale.value, 'too far').toBe(1)
  })

  it('a pinch between two taps breaks the double-tap', () => {
    const { z, tap, down, up, advance } = setup()
    tap(500, 350)
    advance(50)
    down(1, 450, 350)
    down(2, 550, 350)
    up(2, 550, 350)
    up(1, 450, 350)
    advance(50)
    tap(500, 350)
    expect(z.scale.value).toBe(1)
  })

  it('a pinch zooms continuously by the fingers’ spread, clamped to 8x', () => {
    const { z, down, move, up } = setup()
    down(1, 450, 350)
    down(2, 550, 350) // 100 px apart, about the centre
    move(2, 650, 350) // 200 px apart: 2x
    expect(z.scale.value).toBeCloseTo(2)
    move(2, 5000, 350)
    expect(z.scale.value).toBe(MAX_ZOOM)
    up(2, 5000, 350)
    up(1, 450, 350)
    expect(z.scale.value, 'lifting the fingers keeps the zoom').toBe(MAX_ZOOM)
  })

  it('a drag pans only while zoomed, and never past the content’s edge', () => {
    const { z, down, move, up } = setup()
    down(1, 500, 350)
    move(1, 700, 350)
    expect(z.gesturing.value, 'no grab at 1x').toBe(false)
    up(1, 700, 350)
    expect(z.x.value, 'nothing to pan at 1x').toBe(0)
    z.zoomTo(3)
    down(1, 500, 350)
    move(1, 550, 330)
    expect([z.x.value, z.y.value]).toEqual([50, -20])
    move(1, 5000, -5000)
    expect([z.x.value, z.y.value]).toEqual([560, -420]) // the overhang at 3x
    up(1, 5000, -5000)
  })

  it('ctrl+wheel zooms about the pointer; a plain wheel is left to the page', () => {
    const { z } = setup()
    let prevented = 0
    const wheel = (ctrlKey: boolean, deltaY: number, deltaMode = 0) =>
      z.onWheel({ ctrlKey, deltaY, deltaMode, clientX: 500, clientY: 350, preventDefault: () => void prevented++ } as unknown as WheelEvent)
    wheel(false, -200)
    expect([z.scale.value, prevented]).toEqual([1, 0])
    wheel(true, -200)
    expect(z.scale.value).toBeCloseTo(Math.E)
    expect(prevented).toBe(1)
    wheel(true, 200)
    wheel(true, -12.5, 1) // Firefox's mouse wheel counts lines: 12.5 lines of 16 px
    expect(z.scale.value).toBeCloseTo(Math.E)
    wheel(true, 1000)
    expect(z.scale.value, 'never below 1x').toBe(1)
  })

  it('+ / - / 0 are the keyboard path, and other keys are not taken', () => {
    const { z } = setup()
    expect(z.onKey('+')).toBe(true)
    expect(z.scale.value).toBe(1.5)
    z.onKey('+')
    z.onKey('-')
    expect(z.scale.value).toBeCloseTo(1.5)
    z.onKey('0')
    expect(z.scale.value).toBe(1)
    expect(z.onKey('ArrowLeft')).toBe(false)
  })

  it('reset drops a gesture under way', () => {
    const { z, down, move } = setup()
    z.zoomTo(2)
    down(1, 500, 350)
    move(1, 600, 350)
    z.reset()
    move(1, 700, 350)
    expect([z.scale.value, z.x.value, z.gesturing.value]).toEqual([1, 0, false])
  })
})
