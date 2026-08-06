// @vitest-environment happy-dom
// cr-untrust-device-button — the "This device" settings panel surfaces a device's unlock tier and lets the user
// forget it, over the already-built logic (uc.account.trust-device § Untrust + uc.account.remember-this-device).
// A device holds at most one convenience tier: 'remembered' (password-less DEVICE_KEY) or 'trusted' (password-only
// DEVICE); forgetting drops BOTH (idempotent). Covers: each tier shows its status + the forget action; confirming
// forgets both tiers and flips to none; a plain device (or unsupported browser) shows status only, no action.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises, type DOMWrapper } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

const isTrusted = vi.fn()
const untrustThisDevice = vi.fn()
const isTrustSupported = vi.fn()
const isRemembered = vi.fn()
const clearDeviceKey = vi.fn()

vi.mock('./trustDevice', () => ({
  isTrusted: (...a: unknown[]) => isTrusted(...a),
  untrustThisDevice: (...a: unknown[]) => untrustThisDevice(...a),
  isTrustSupported: () => isTrustSupported(),
}))
vi.mock('../rememberMe/deviceKeyWrap', () => ({
  isRemembered: () => isRemembered(),
  clearDeviceKey: (...a: unknown[]) => clearDeviceKey(...a),
}))

import DeviceTrustPanel from './DeviceTrustPanel.vue'
import { account } from '../../../app/account'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  missingWarn: false,
  fallbackWarn: false,
  messages: {
    en: {
      common: { cancel: 'Cancel' },
      unlock: { untrust: 'Untrust this device' },
      settings: {
        deviceTitle: 'This device',
        deviceSub: 's',
        deviceRemembered: 'remembered',
        deviceTrusted: 'trusted',
        deviceUntrusted: 'no saved unlock',
        deviceUnsupported: 'unsupported',
        untrustConfirmTitle: 'Forget?',
        untrustConfirmBody: 'body',
        untrustFailed: 'failed',
      },
    },
  },
})

const stubs = {
  Dialog: { props: ['visible'], template: '<div class="dialog" v-if="visible"><slot /></div>' },
  Button: { props: ['label'], emits: ['click'], template: '<button @click="$emit(\'click\')">{{ label }}</button>' },
  Message: { template: '<div class="msg"><slot /></div>' },
}

const mountPanel = () => mount(DeviceTrustPanel, { global: { plugins: [i18n], stubs } })
const forgetButtons = (w: ReturnType<typeof mountPanel>): DOMWrapper<Element>[] =>
  w.findAll('button').filter((b) => b.text() === 'Untrust this device')

beforeEach(() => {
  account.email = 'me@x.z'
  isTrustSupported.mockReset().mockReturnValue(true)
  isTrusted.mockReset().mockResolvedValue(false)
  isRemembered.mockReset().mockResolvedValue(false)
  untrustThisDevice.mockReset().mockResolvedValue(undefined)
  clearDeviceKey.mockReset().mockResolvedValue(undefined)
})

describe('DeviceTrustPanel', () => {
  it('a trusted device shows the trusted status + the forget action', async () => {
    isTrusted.mockResolvedValue(true)
    const w = mountPanel()
    await flushPromises()
    expect(w.text()).toContain('trusted')
    expect(forgetButtons(w)).toHaveLength(1)
  })

  it('a remembered device shows the remembered status + the forget action (remember-me wins)', async () => {
    isRemembered.mockResolvedValue(true)
    isTrusted.mockResolvedValue(true) // even if both, remembered wins
    const w = mountPanel()
    await flushPromises()
    expect(w.text()).toContain('remembered')
    expect(forgetButtons(w)).toHaveLength(1)
  })

  it('forget: confirming drops BOTH tiers and the device flips to none', async () => {
    isRemembered.mockResolvedValueOnce(true).mockResolvedValueOnce(false) // before → after
    const w = mountPanel()
    await flushPromises()

    await forgetButtons(w)[0].trigger('click') // open confirm
    await flushPromises()
    await forgetButtons(w).at(-1)!.trigger('click') // confirm
    await flushPromises()

    expect(untrustThisDevice).toHaveBeenCalledWith('me@x.z')
    expect(clearDeviceKey).toHaveBeenCalled()
    expect(w.text()).toContain('no saved unlock')
    expect(forgetButtons(w)).toHaveLength(0)
  })

  it('a plain device (no tier) shows status only, no action', async () => {
    const w = mountPanel()
    await flushPromises()
    expect(w.text()).toContain('no saved unlock')
    expect(forgetButtons(w)).toHaveLength(0)
  })

  it('an unsupported browser shows the unsupported note, no action', async () => {
    isTrustSupported.mockReturnValue(false)
    const w = mountPanel()
    await flushPromises()
    expect(w.text()).toContain('unsupported')
    expect(forgetButtons(w)).toHaveLength(0)
  })
})
