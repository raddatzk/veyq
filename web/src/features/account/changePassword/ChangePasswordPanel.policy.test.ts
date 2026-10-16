// @vitest-environment happy-dom
// The password floor on the change-password screen. Its own screen-level file rather than a case bolted onto
// `changePassword.test.ts`, which tests the data layer: what is asserted here is what the FORM refuses, and
// the data layer never sees a password the form declined to send.
//
// The floor is client-side by construction, not by omission. Under Model B (ADR 0003) the password never
// leaves this device — the server receives an Ed25519 public key derived from it and stores no credential at
// all — so there is no server rule to mirror and none that would catch a bypass. That makes this check the
// whole control rather than a convenience over a server-side one, which is why it is pinned at the boundary.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('./changePassword', () => ({
  changePassword: vi.fn(),
  ChangePasswordRefused: class extends Error {},
}))
vi.mock('../rememberMe/deviceKeyWrap', () => ({
  isRememberSupported: vi.fn(),
  rememberThisDevice: vi.fn(),
}))
// Off by default; a test puts the ids it is about into `switchedOn`, so each leaf is read on its own (ADR 0088 decision 3).
const { switchedOn } = vi.hoisted(() => ({ switchedOn: new Set<string>() }))
vi.mock('../../../shared/state/useCapabilities', () => ({
  useCapabilities: () => ({
    hasCapability: (id: string) => switchedOn.has(id),
    // The breach check's address, as a new instance's manifest carries it (uc.admin.configure-external-services).
    capabilityValues: (id: string) => (id === 'core.breached-password' ? { id, serviceUrl: 'https://api.pwnedpasswords.com/range/' } : undefined),
  }),
}))
// The breach lookup is the only thing stubbed of the check: the dialog's real guard runs, which is what the wiring
// cases below pin. Clean by default — the check is automatic, so every submit here passes through it.
vi.mock('../../../shared/pwned', () => ({ pwnedCount: vi.fn() }))

import ChangePasswordPanel from './ChangePasswordPanel.vue'
import { changePassword } from './changePassword'
import { pwnedCount } from '../../../shared/pwned'
import { isRememberSupported } from '../rememberMe/deviceKeyWrap'
import { realI18n, t } from '../../../test/i18n'
import { CORE_REMEMBER_DEVICE, CORE_REMEMBER_DEVICE_PASSWORDLESS } from '../../platform/capabilities'

const stubs = {
  // The panel's only route link — recovery, where a forgotten password goes now that this screen refuses to
  // mint a credential without one.
  RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' },
  Button: { props: ['label', 'loading', 'disabled'], template: '<button :disabled="disabled">{{ label }}</button>' },
  Message: { template: '<div class="msg"><slot /></div>' },
  InputText: {
    props: ['modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  Password: {
    props: ['modelValue'],
    template: '<input type="password" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  // The breach warning's "Use it anyway": a native checkbox, so a test ticks it with `setValue(true)`.
  Checkbox: {
    props: ['modelValue'],
    template: '<input type="checkbox" :checked="modelValue" @change="$emit(\'update:modelValue\', $event.target.checked)" />',
  },
}

/** `[current, new, confirm]` — the three password fields, in template order. */
const passwords = (w: ReturnType<typeof mount>) => w.findAll('input[type="password"]')
const submitDisabled = (w: ReturnType<typeof mount>) =>
  w.find('button[type="submit"]').attributes('disabled') !== undefined

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(changePassword).mockResolvedValue(undefined)
  vi.mocked(isRememberSupported).mockReturnValue(false)
  switchedOn.clear()
  vi.mocked(pwnedCount).mockResolvedValue(0)
})

describe('ChangePasswordPanel — the password floor', () => {
  it('refuses a new password under the floor and names it; accepts exactly at the floor', async () => {
    const w = mount(ChangePasswordPanel, { global: { plugins: [realI18n()], stubs } })
    await flushPromises()

    const pw = passwords(w)
    await pw[0].setValue('the-current-one')
    await pw[1].setValue('short')  // 5 < 10
    await pw[2].setValue('short')
    await w.find('input[name="secret-key"]').setValue('the-secret-key')
    await flushPromises()

    expect(w.text()).toContain(t('password.minLength'))
    expect(submitDisabled(w)).toBe(true)
    await w.find('form').trigger('submit')
    await flushPromises()
    expect(changePassword).not.toHaveBeenCalled()

    // The boundary, not merely "long enough": `<` vs `<=` here silently makes the floor 11.
    await pw[1].setValue('0123456789')
    await pw[2].setValue('0123456789')
    await flushPromises()

    expect(submitDisabled(w)).toBe(false)
    await w.find('form').trigger('submit')
    await flushPromises()
    expect(changePassword).toHaveBeenCalledOnce()
  })

  it('does not apply the floor to the CURRENT password — it is proof of knowledge, not a new choice', async () => {
    // An account whose password predates the policy must still be able to change it. Gating the field that
    // proves you know the old one is the same mistake as gating a sign-in, one screen over.
    const w = mount(ChangePasswordPanel, { global: { plugins: [realI18n()], stubs } })
    await flushPromises()

    const pw = passwords(w)
    await pw[0].setValue('old')    // 3 < 10, and correctly irrelevant
    await pw[1].setValue('a long enough new password')
    await pw[2].setValue('a long enough new password')
    await w.find('input[name="secret-key"]').setValue('the-secret-key')
    await flushPromises()

    expect(submitDisabled(w)).toBe(false)
    await w.find('form').trigger('submit')
    await flushPromises()
    expect(changePassword).toHaveBeenCalledWith('a long enough new password', 'the-secret-key', 'old')
  })

  it('checks the finished new password when its field is left — never while typing, once per password', async () => {
    const w = mount(ChangePasswordPanel, { global: { plugins: [realI18n()], stubs } })
    await flushPromises()
    expect(w.text()).toContain(t('password.pwnedNote'))

    const pw = passwords(w)
    await pw[1].setValue('a long enough')
    await pw[1].setValue('a long enough new password')
    await flushPromises()
    expect(pwnedCount).not.toHaveBeenCalled()

    await pw[1].trigger('blur')
    await pw[1].trigger('blur')
    await flushPromises()
    expect(pwnedCount).toHaveBeenCalledOnce()
    expect(pwnedCount).toHaveBeenCalledWith('a long enough new password', 'https://api.pwnedpasswords.com/range/')
  })

  it('a hit blocks the submit until "Use it anyway" — pressing submit again is not the acceptance', async () => {
    // The slip the composable's own test cannot see: a dialog that forgets to await the guard.
    vi.mocked(pwnedCount).mockResolvedValue(3)
    const w = mount(ChangePasswordPanel, { global: { plugins: [realI18n()], stubs } })
    await flushPromises()

    const pw = passwords(w)
    await pw[0].setValue('the-current-one')
    await pw[1].setValue('a long enough new password')
    await pw[1].trigger('blur')
    await pw[2].setValue('a long enough new password')
    await w.find('input[name="secret-key"]').setValue('the-secret-key')
    await flushPromises()
    expect(w.text()).toContain(t('password.pwnedWarning', { count: 3 }))

    await w.find('form').trigger('submit')
    await flushPromises()
    await w.find('form').trigger('submit')
    await flushPromises()
    expect(changePassword).not.toHaveBeenCalled()

    await w.find('input[type="checkbox"]').setValue(true)
    await w.find('form').trigger('submit')
    await flushPromises()
    expect(changePassword).toHaveBeenCalledOnce()
    expect(pwnedCount).toHaveBeenCalledOnce()
  })

  it('a check that could not run goes ahead, and says so in one line', async () => {
    vi.mocked(pwnedCount).mockResolvedValue(null)
    const w = mount(ChangePasswordPanel, { global: { plugins: [realI18n()], stubs } })
    await flushPromises()

    const pw = passwords(w)
    await pw[0].setValue('the-current-one')
    await pw[1].setValue('a long enough new password')
    await pw[1].trigger('blur')
    await pw[2].setValue('a long enough new password')
    await w.find('input[name="secret-key"]').setValue('the-secret-key')
    await flushPromises()
    expect(w.text()).toContain(t('password.pwnedUnavailable'))

    await w.find('form').trigger('submit')
    await flushPromises()
    expect(changePassword).toHaveBeenCalledOnce()
    expect(pwnedCount).toHaveBeenCalledOnce()
  })
})

describe('ChangePasswordPanel — the re-enrol offer after a change', () => {
  // The offer enrols a password-less DEVICE_KEY wrap, so only `core.remember-device.passwordless` (and this browser's
  // support) gates it: its parent leaf alone does not, because the server ANDs the path (ADR 0088 decision 2).
  async function changeDone(): Promise<ReturnType<typeof mount>> {
    const w = mount(ChangePasswordPanel, { global: { plugins: [realI18n()], stubs } })
    await flushPromises()
    const pw = passwords(w)
    await pw[0].setValue('the-current-one')
    await pw[1].setValue('0123456789')
    await pw[2].setValue('0123456789')
    await w.find('input[name="secret-key"]').setValue('the-secret-key')
    await flushPromises()
    await w.find('form').trigger('submit')
    await flushPromises()
    expect(changePassword).toHaveBeenCalledOnce()
    return w
  }
  const offered = (w: ReturnType<typeof mount>) => w.text().includes(t('changePassword.rememberDevice'))

  it('is offered where the browser supports it and the passwordless leaf is on', async () => {
    vi.mocked(isRememberSupported).mockReturnValue(true)
    switchedOn.add(CORE_REMEMBER_DEVICE_PASSWORDLESS)
    expect(offered(await changeDone())).toBe(true)
  })

  it('is not offered with the passwordless leaf off, even if its parent and the browser allow it', async () => {
    vi.mocked(isRememberSupported).mockReturnValue(true)
    switchedOn.add(CORE_REMEMBER_DEVICE)
    expect(offered(await changeDone())).toBe(false)
  })

  it('is not offered where the browser cannot hold a device key — the control on the leaf being on', async () => {
    switchedOn.add(CORE_REMEMBER_DEVICE_PASSWORDLESS)
    expect(offered(await changeDone())).toBe(false)
  })
})
