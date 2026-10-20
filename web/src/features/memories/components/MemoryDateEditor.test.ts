// @vitest-environment happy-dom
import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import MemoryDateEditor from './MemoryDateEditor.vue'
import type { MemoryDate } from '../memoryDate'
import { realI18n } from '../../../test/i18n'

// Stub the PrimeVue pieces so the editor's own logic (mode switch → fitting inputs, validation, buildDate) is
// what's under test — not the DatePicker overlay, which happy-dom can't drive.
const stubs = {
  Dialog: { template: '<div class="dlg"><slot /></div>' },
  Message: { template: '<div class="msg"><slot /></div>' },
  Button: {
    props: ['type', 'label', 'disabled'],
    template: `<button :type="type || 'button'" :disabled="disabled" @click="$emit('click', $event)">{{ label }}</button>`,
  },
  SelectButton: {
    props: ['modelValue', 'options'],
    emits: ['update:modelValue'],
    template: `<div class="modes"><button v-for="o in options" :key="o.value" :data-mode="o.value" @click="$emit('update:modelValue', o.value)">{{ o.label }}</button></div>`,
  },
  IsoDateField: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: `<input class="iso" :value="modelValue" @input="$emit('update:modelValue', $event.target.value)" />`,
  },
}

const AUTO: MemoryDate = { mode: 'auto', from: '2021-05-01', to: '2021-05-03' }

function mountEditor(date: MemoryDate | null, autoRange: MemoryDate = AUTO) {
  return mount(MemoryDateEditor, { props: { date, autoRange }, global: { plugins: [realI18n()], stubs } })
}

const pickMode = async (w: ReturnType<typeof mountEditor>, mode: string) =>
  w.find(`[data-mode="${mode}"]`).trigger('click')
const setIso = async (w: ReturnType<typeof mountEditor>, i: number, value: string) => {
  const inputs = w.findAll('.iso')
  await inputs[i].setValue(value)
}
const savedDate = (w: ReturnType<typeof mountEditor>): MemoryDate | undefined =>
  w.emitted('save')?.[0]?.[0] as MemoryDate | undefined

beforeEach(() => {})

describe('MemoryDateEditor', () => {
  it('auto → seals the materialized range (mode auto + the derived from/to)', async () => {
    const w = mountEditor(null) // null stored ⇒ defaults to auto
    await w.find('form').trigger('submit')
    expect(savedDate(w)).toEqual({ mode: 'auto', from: '2021-05-01', to: '2021-05-03' })
    expect(w.emitted('close')).toBeTruthy()
  })

  it('single → from == to', async () => {
    const w = mountEditor(null)
    await pickMode(w, 'single')
    await setIso(w, 0, '2026-06-15')
    await w.find('form').trigger('submit')
    expect(savedDate(w)).toEqual({ mode: 'single', from: '2026-06-15', to: '2026-06-15' })
  })

  it('range → both ends', async () => {
    const w = mountEditor(null)
    await pickMode(w, 'range')
    await setIso(w, 0, '2026-06-01') // from
    await setIso(w, 1, '2026-06-30') // to
    await w.find('form').trigger('submit')
    expect(savedDate(w)).toEqual({ mode: 'range', from: '2026-06-01', to: '2026-06-30' })
  })

  it('range with to < from is blocked (E4) — shows the error and does not save', async () => {
    const w = mountEditor(null)
    await pickMode(w, 'range')
    await setIso(w, 0, '2026-06-30') // from
    await setIso(w, 1, '2026-06-01') // to (before from)
    expect(w.find('.msg').exists()).toBe(true)
    await w.find('form').trigger('submit')
    expect(w.emitted('save')).toBeFalsy()
  })

  it('ongoing → from, no to', async () => {
    const w = mountEditor(null)
    await pickMode(w, 'ongoing')
    await setIso(w, 0, '2026-01-01')
    await w.find('form').trigger('submit')
    expect(savedDate(w)).toEqual({ mode: 'ongoing', from: '2026-01-01' })
  })

  it('prefills the current stored date + mode', async () => {
    const w = mountEditor({ mode: 'range', from: '2025-01-01', to: '2025-01-05' })
    const inputs = w.findAll('.iso')
    expect((inputs[0].element as HTMLInputElement).value).toBe('2025-01-01')
    expect((inputs[1].element as HTMLInputElement).value).toBe('2025-01-05')
  })
})
