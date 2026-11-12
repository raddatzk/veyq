// @vitest-environment happy-dom
// The kit step's own checks: the optional address the Server Owner signs in at reaches the kit as its server, an
// address that is none blocks the download, and a kit downloaded before the address changed is no longer the one
// confirmed. The PDF itself is emergencyKit.test.ts's; here the renderer and the save are stubbed, `kitServerOf` is real.
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

vi.mock('./emergencyKit', async (original) => ({
  ...(await original<typeof import('./emergencyKit')>()),
  renderKitPdf: vi.fn(),
  saveKitPdf: vi.fn(),
}))

import EmergencyKitSave from './EmergencyKitSave.vue'
import { renderKitPdf, saveKitPdf } from './emergencyKit'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  missingWarn: false,
  fallbackWarn: false,
  messages: {
    en: {
      kit: {
        download: 'Download', downloaded: 'Downloaded', downloadFailed: 'failed', backupDownloadConfirm: 'saved',
        serverLabel: 'Address', serverHint: 'hint', serverInvalid: 'not an address',
      },
    },
  },
})

// The layer's components are stubbed to bare elements, so a test drives a plain input, button and checkbox.
const stubs = {
  VeyqButton: { props: ['label', 'disabled'], template: '<button class="btn" :disabled="disabled">{{ label }}</button>' },
  VeyqMessage: { template: '<div class="msg"><slot /></div>' },
  VeyqIcon: { template: '<i />' },
  VeyqInput: {
    props: ['modelValue', 'invalid'],
    emits: ['update:modelValue'],
    template: '<input class="address" :aria-invalid="invalid || undefined" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  VeyqCheckbox: {
    props: ['modelValue', 'disabled'],
    emits: ['update:modelValue'],
    template: '<input type="checkbox" class="confirm" :disabled="disabled" :checked="modelValue" @change="$emit(\'update:modelValue\', $event.target.checked)" />',
  },
}

const mountStep = () =>
  mount(EmergencyKitSave, { props: { email: 'owner@example.com', secretKey: 'KEY' }, global: { plugins: [i18n], stubs } })

beforeEach(() => {
  vi.mocked(renderKitPdf).mockReset().mockResolvedValue(new Uint8Array([1]))
  vi.mocked(saveKitPdf).mockReset()
})

describe('EmergencyKitSave', () => {
  it('makes a kit with no server while no address is typed', async () => {
    const w = mountStep()
    await w.find('button.btn').trigger('click')
    await flushPromises()
    expect(vi.mocked(renderKitPdf).mock.calls[0][0]).toStrictEqual({ email: 'owner@example.com', secretKey: 'KEY' })
    expect(saveKitPdf).toHaveBeenCalledOnce()
  })

  it('puts the typed address into the kit as its origin', async () => {
    const w = mountStep()
    await w.find('input.address').setValue('Familie.example.org')
    await w.find('button.btn').trigger('click')
    await flushPromises()
    expect(vi.mocked(renderKitPdf).mock.calls[0][0]).toStrictEqual({ email: 'owner@example.com', secretKey: 'KEY', server: 'https://familie.example.org' })
  })

  it('blocks the download and says why while the address is none', async () => {
    const w = mountStep()
    expect(w.find('button.btn').attributes('disabled')).toBeUndefined()
    await w.find('input.address').setValue('familie.example.org/app')
    expect(w.find('button.btn').attributes('disabled')).toBeDefined()
    expect(w.find('input.address').attributes('aria-invalid')).toBe('true')
    expect(w.text()).toContain('not an address')
    await w.find('button.btn').trigger('click')
    await flushPromises()
    expect(renderKitPdf).not.toHaveBeenCalled()
  })

  it('asks for a new download and confirm once the address changes after one', async () => {
    const w = mountStep()
    await w.find('input.address').setValue('familie.example.org')
    await w.find('button.btn').trigger('click')
    await flushPromises()
    await w.find('input.confirm').setValue(true)
    expect(w.emitted('update:modelValue')!.at(-1)).toEqual([true])

    await w.find('input.address').setValue('veyq.example.org')
    expect(w.emitted('update:modelValue')!.at(-1)).toEqual([false])
    expect(w.text()).not.toContain('Downloaded')
    expect(w.find('input.confirm').attributes('disabled')).toBeDefined()
  })
})
