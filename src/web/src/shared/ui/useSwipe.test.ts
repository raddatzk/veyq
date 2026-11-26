// @vitest-environment happy-dom
// A horizontal swipe (inbox #863), driven with plain event objects: what counts as one, which way it goes, and every
// gesture that must not count - a mouse, a short or a steep travel, a second finger, a refusal, a <video>, a cancel.
import { describe, it, expect } from 'vitest'
import { useSwipe, SWIPE_MIN_PX, type SwipeDirection } from './useSwipe'

function setup(enabled = () => true) {
  const seen: SwipeDirection[] = []
  const s = useSwipe((d) => seen.push(d), { enabled })
  const ev = (type: string, id: number, x: number, y: number, o: Record<string, unknown> = {}) =>
    ({ type, pointerId: id, clientX: x, clientY: y, pointerType: 'touch', target: null, ...o }) as unknown as PointerEvent
  const down = (id: number, x: number, y: number, o?: Record<string, unknown>) => s.onPointerDown(ev('pointerdown', id, x, y, o))
  const up = (id: number, x: number, y: number, o?: Record<string, unknown>) => s.onPointerUp(ev('pointerup', id, x, y, o))
  const swipe = (dx: number, dy = 0, o?: Record<string, unknown>) => { down(1, 300, 300, o); up(1, 300 + dx, 300 + dy, o) }
  return { seen, down, up, swipe, s, ev }
}

describe('useSwipe', () => {
  it('a finger moving left brings the next, moving right the previous', () => {
    const { seen, swipe } = setup()
    swipe(-120)
    swipe(120)
    expect(seen).toEqual(['next', 'previous'])
  })

  it('a pen swipes like a finger', () => {
    const { seen, swipe } = setup()
    swipe(-120, 0, { pointerType: 'pen' })
    expect(seen).toEqual(['next'])
  })

  it('needs the minimum travel: one pixel short is no swipe, the minimum is', () => {
    const { seen, swipe } = setup()
    swipe(-(SWIPE_MIN_PX - 1))
    expect(seen, 'short').toEqual([])
    swipe(-SWIPE_MIN_PX)
    expect(seen).toEqual(['next'])
  })

  it('a travel more up or down than sideways is no swipe; a clearly sideways one with some slope is', () => {
    const { seen, swipe } = setup()
    swipe(-120, 90) // 120 < 1.5 x 90
    expect(seen, 'too steep').toEqual([])
    swipe(-120, 70) // 120 >= 1.5 x 70
    expect(seen).toEqual(['next'])
  })

  it('a mouse never swipes', () => {
    const { seen, swipe } = setup()
    swipe(-300, 0, { pointerType: 'mouse' })
    expect(seen).toEqual([])
  })

  it('a second finger makes it a pinch: no swipe, not even for the finger left on the glass', () => {
    const { seen, down, up, swipe } = setup()
    down(1, 300, 300)
    down(2, 500, 300)
    up(2, 500, 300)
    up(1, 100, 300) // travelled 200 px left on its own after the pinch
    expect(seen).toEqual([])
    swipe(-120)
    expect(seen, 'the next gesture is one again').toEqual(['next'])
  })

  it('the host refuses it when it begins or by its end (a zoomed photo pans instead)', () => {
    let zoomed = true
    const { seen, down, up } = setup(() => !zoomed)
    down(1, 300, 300)
    zoomed = false
    up(1, 100, 300)
    expect(seen, 'refused at the start').toEqual([])
    down(1, 300, 300)
    zoomed = true
    up(1, 100, 300)
    expect(seen, 'zoomed meanwhile').toEqual([])
    zoomed = false
    down(1, 300, 300)
    up(1, 100, 300)
    expect(seen, 'control').toEqual(['next'])
  })

  it('one that starts on a <video> is no swipe (its controls scrub on a sideways drag)', () => {
    const { seen, swipe } = setup()
    const video = document.createElement('video')
    const wrap = document.createElement('div')
    wrap.append(video)
    swipe(-120, 0, { target: video })
    expect(seen).toEqual([])
    swipe(-120, 0, { target: wrap })
    expect(seen, 'control: beside the video it is').toEqual(['next'])
  })

  it('a gesture the browser cancelled is no swipe', () => {
    const { seen, s, ev, down } = setup()
    down(1, 300, 300)
    s.onPointerUp(ev('pointercancel', 1, 100, 300))
    expect(seen).toEqual([])
  })
})
