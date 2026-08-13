// @vitest-environment happy-dom
// ADR 0059 §7 — the gesture has to be one you carry out. Every case here is really the same negative: a
// confirmation that did not travel the whole way does not fire, because the reflex is what this control exists
// to refuse. Each carries its positive control on the same predicate, since "it did not emit" is satisfied
// perfectly by a control that emits never.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'

import SlideToConfirm from './SlideToConfirm.vue'

const LABEL = 'Slide to grant'
const DONE = 'Granted'
const TRACK_WIDTH = 300

const mountSlide = (props: Record<string, unknown> = {}) => {
  const w = mount(SlideToConfirm, { props: { label: LABEL, doneLabel: DONE, ...props } })
  // happy-dom lays nothing out, so the track measures 0 wide and the component's own zero-width guard would
  // refuse every drag. Giving it a width is what makes the POINTER path observable at all.
  vi.spyOn(w.element as HTMLElement, 'getBoundingClientRect').mockReturnValue({
    left: 0,
    width: TRACK_WIDTH,
  } as DOMRect)
  return w
}

const dragTo = async (w: VueWrapper, fraction: number, release = true) => {
  await w.trigger('pointerdown', { pointerId: 1, clientX: 0 })
  await w.trigger('pointermove', { pointerId: 1, clientX: TRACK_WIDTH * fraction })
  if (release) await w.trigger('pointerup', { pointerId: 1, clientX: TRACK_WIDTH * fraction })
}

const press = async (w: VueWrapper, key: string, times: number) => {
  for (let i = 0; i < times; i++) await w.trigger('keydown', { key })
}

beforeEach(() => vi.restoreAllMocks())

describe('SlideToConfirm (ADR 0059 §7)', () => {
  it('confirms on a slide that reaches the end', async () => {
    const w = mountSlide()

    await dragTo(w, 1)

    expect(w.emitted('confirmed')).toHaveLength(1)
    expect(w.text()).toContain(DONE)
  })

  it('does not confirm on a slide that stops short, and snaps back rather than banking the progress', async () => {
    const w = mountSlide()

    await dragTo(w, 0.9)

    expect(w.emitted('confirmed')).toBeUndefined()
    expect(w.attributes('aria-valuenow')).toBe('0') // not 90: two half-gestures are not one confirmation
    expect(w.text()).toContain(LABEL)

    // The control: the same control, one full slide later, does confirm.
    await dragTo(w, 1)
    expect(w.emitted('confirmed')).toHaveLength(1)
  })

  it('tracks the drag while the pointer is down, without confirming mid-way', async () => {
    const w = mountSlide()

    await dragTo(w, 0.5, false)

    expect(w.attributes('aria-valuenow')).toBe('50')
    expect(w.emitted('confirmed')).toBeUndefined()
  })

  // A tap is exactly what §7 refuses, and a keyboard key that jumps to the end is a tap. Ten steps, or nothing.
  it('needs the full run of arrow steps on the keyboard, and End is not bound', async () => {
    const w = mountSlide()

    await w.trigger('keydown', { key: 'End' })
    expect(w.attributes('aria-valuenow')).toBe('0')

    await press(w, 'ArrowRight', 9)
    expect(w.emitted('confirmed')).toBeUndefined()
    expect(w.attributes('aria-valuenow')).toBe('90')

    await press(w, 'ArrowRight', 1)
    expect(w.emitted('confirmed')).toHaveLength(1)
  })

  it('goes back on the arrow the other way', async () => {
    const w = mountSlide()

    await press(w, 'ArrowRight', 5)
    await press(w, 'ArrowLeft', 5)

    expect(w.attributes('aria-valuenow')).toBe('0')
    expect(w.emitted('confirmed')).toBeUndefined()
  })

  it('ignores everything while disabled, and works once it is not', async () => {
    const w = mountSlide({ disabled: true })

    await dragTo(w, 1)
    await press(w, 'ArrowRight', 10)

    expect(w.emitted('confirmed')).toBeUndefined()
    expect(w.attributes('aria-disabled')).toBe('true')
    expect(w.attributes('tabindex')).toBe('-1')

    await w.setProps({ disabled: false })
    await dragTo(w, 1)
    expect(w.emitted('confirmed')).toHaveLength(1)
  })

  it('confirms once and then stops responding — the grant is not a thing to fire twice', async () => {
    const w = mountSlide()

    await dragTo(w, 1)
    await dragTo(w, 1)
    await press(w, 'ArrowLeft', 3)

    expect(w.emitted('confirmed')).toHaveLength(1)
    expect(w.attributes('aria-valuenow')).toBe('100')
  })
})
