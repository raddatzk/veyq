// @vitest-environment happy-dom
// The kit step's two answers (ADR 0157 decision 4): `ready` (the v-model a host gates its next action on) and `kept`, the
// one moment a host that PUTs the kit record writes it — once the PDF was saved AND confirmed kept, never at render, never
// on the download alone, and once.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('./emergencyKit', () => ({ renderKitPdf: vi.fn(), saveKitPdf: vi.fn() }))

import EmergencyKitSave from './EmergencyKitSave.vue'
import { renderKitPdf, saveKitPdf } from './emergencyKit'
import { realI18n } from '../../../test/i18n'
import { TYPED_SECRET_KEY } from '../../../test/secretKey'

const stubs = {
  VeyqButton: { props: ['label'], emits: ['click'], template: '<button class="download" @click="$emit(\'click\')">{{ label }}</button>' },
  VeyqCheckbox: {
    props: ['modelValue', 'disabled'],
    emits: ['update:modelValue'],
    template: '<input type="checkbox" class="confirm" :disabled="disabled" :checked="modelValue" @change="$emit(\'update:modelValue\', $event.target.checked)" />',
  },
}

function mountSave() {
  return mount(EmergencyKitSave, { props: { email: 'bea@example.org', secretKey: TYPED_SECRET_KEY }, global: { plugins: [realI18n()], stubs } })
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(renderKitPdf).mockResolvedValue(new Uint8Array([1]))
})

describe('EmergencyKitSave — kept', () => {
  it('emits kept once the kit was downloaded AND confirmed — not at render, not on the download alone', async () => {
    const w = mountSave()
    await flushPromises()
    expect(w.emitted('kept')).toBeUndefined()

    await w.find('.download').trigger('click')
    await flushPromises()
    expect(saveKitPdf).toHaveBeenCalled()
    expect(w.emitted('kept'), 'downloaded, not yet confirmed').toBeUndefined()

    await w.find('.confirm').setValue(true)
    await flushPromises()
    expect(w.emitted('kept')).toHaveLength(1)
    expect(w.emitted('update:modelValue')?.at(-1)).toEqual([true])
  })

  it('emits it once: unticking and ticking again does not write a second time', async () => {
    const w = mountSave()
    await w.find('.download').trigger('click')
    await flushPromises()
    await w.find('.confirm').setValue(true)
    await w.find('.confirm').setValue(false)
    await w.find('.confirm').setValue(true)
    await flushPromises()
    expect(w.emitted('kept')).toHaveLength(1)
  })

  it('a kit that could not be made is never kept', async () => {
    vi.mocked(renderKitPdf).mockRejectedValue(new Error('no pdf'))
    const w = mountSave()
    await w.find('.download').trigger('click')
    await flushPromises()
    expect((w.find('.confirm').element as HTMLInputElement).disabled).toBe(true)
    expect(w.emitted('kept')).toBeUndefined()
  })
})
