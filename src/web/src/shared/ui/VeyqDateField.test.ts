// @vitest-environment happy-dom
// The layer's date field (ADR 0107 § Behaviour changed on purpose): segments in the language's order with a
// leading zero and the language's names, a typed day outside min/max refused (the model never holds it, and leaving
// the field shows the day it held on entry), and a `<label>` around it meaning its first segment, not its button.
import { describe, it, expect } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h, ref } from 'vue'
import VeyqDateField from './VeyqDateField.vue'
import { realI18n } from '../../test/i18n'

function host(start: string, maxDate?: string) {
  const day = ref(start)
  const Host = defineComponent({
    setup() {
      return () => [
        h('label', { class: 'wrap' }, [
          h('span', { class: 'caption' }, 'From'),
          h(VeyqDateField, { modelValue: day.value, 'onUpdate:modelValue': (v: string) => (day.value = v), ariaLabel: 'From', maxDate }),
        ]),
        h('button', { class: 'after', type: 'button' }, 'Next'),
      ]
    },
  })
  return { day, wrapper: mount(Host, { attachTo: document.body, global: { plugins: [realI18n()] } }) }
}

const segments = () => [...document.querySelectorAll<HTMLElement>('.veyq-date-field__segment')]
const shown = () => [...document.querySelectorAll('[data-reka-date-field-segment]')].map((s) => s.textContent).join('')

async function type(segment: HTMLElement, digits: string) {
  for (const key of digits) {
    segment.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true }))
    await flushPromises()
  }
}

describe('VeyqDateField', () => {
  it('writes the day as the language does, with a leading zero and named segments', async () => {
    const { wrapper } = host('2026-05-03')
    await flushPromises()
    expect(shown()).toBe('05/03/2026')
    expect(segments().map((s) => s.getAttribute('aria-label'))).toEqual(['Month', 'Day', 'Year'])
    const { i18n } = await import('../../i18n')
    i18n.global.locale.value = 'de'
    await flushPromises()
    expect(shown()).toBe('03.05.2026')
    expect(segments().map((s) => s.getAttribute('aria-label'))).toEqual(['Tag', 'Monat', 'Jahr'])
    wrapper.unmount()
  })

  it('refuses a typed day after maxDate and shows the day it held once focus leaves', async () => {
    const { day, wrapper } = host('2026-05-12', '2026-05-18')
    await flushPromises()
    const dayPart = segments()[1]!
    dayPart.focus()
    dayPart.dispatchEvent(new FocusEvent('focusin', { bubbles: true }))
    // "1" is the valid 1 May on the way to the refused 19 May
    await type(dayPart, '19')
    expect(day.value).not.toBe('2026-05-19')
    expect(shown()).toBe('05/19/2026')
    const after = document.querySelector<HTMLElement>('.after')!
    dayPart.dispatchEvent(new FocusEvent('focusout', { bubbles: true, relatedTarget: after }))
    after.focus()
    await flushPromises()
    expect(day.value).toBe('2026-05-12')
    expect(shown()).toBe('05/12/2026')
    wrapper.unmount()
  })

  it('takes a typed day within the bounds', async () => {
    const { day, wrapper } = host('2026-05-12', '2026-05-18')
    await flushPromises()
    const dayPart = segments()[1]!
    dayPart.focus()
    await type(dayPart, '17')
    expect(day.value).toBe('2026-05-17')
    wrapper.unmount()
  })

  // Owner 2026-11-01: a month of five or four weeks shortened the panel, so "next month" moved under the pointer. Every
  // month shows six week rows: February 2027 begins on a Monday and fills four weeks, its neighbours five.
  it('shows six week rows in every month, so the month buttons stay where they are', async () => {
    const { wrapper } = host('2027-02-10')
    await flushPromises()
    const trigger = document.querySelector<HTMLElement>('.veyq-date-field__trigger')!
    trigger.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }))
    trigger.click()
    await flushPromises()
    const weeks = () => document.querySelectorAll('.veyq-date-panel__grid tbody .veyq-date-panel__row').length
    expect(weeks()).toBe(6)
    document.querySelectorAll<HTMLElement>('.veyq-date-panel__nav')[1]!.click() // March
    await flushPromises()
    expect(weeks()).toBe(6)
    wrapper.unmount()
  })

  it('reads a click its label forwards to the calendar button as the first segment, and a click inside as its own', async () => {
    const { wrapper } = host('2026-05-12')
    await flushPromises()
    const trigger = document.querySelector<HTMLElement>('.veyq-date-field__trigger')!
    // what a <label> does on a click of its text: a click on its first labelable element, with no press before it
    const forwarded = new MouseEvent('click', { bubbles: true, cancelable: true })
    trigger.dispatchEvent(forwarded)
    await flushPromises()
    expect(forwarded.defaultPrevented).toBe(true)
    expect(document.activeElement).toBe(segments()[0])
    // a click on a segment is not handed on to the label
    const inside = new MouseEvent('click', { bubbles: true, cancelable: true })
    segments()[1]!.dispatchEvent(inside)
    expect(inside.defaultPrevented).toBe(true)
    // a real press of the button is the button's
    trigger.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }))
    const pressed = new MouseEvent('click', { bubbles: true, cancelable: true })
    trigger.dispatchEvent(pressed)
    expect(pressed.defaultPrevented).toBe(false)
    wrapper.unmount()
  })
})
