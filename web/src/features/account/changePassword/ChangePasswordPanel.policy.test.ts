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
vi.mock('../../../shared/state/useCapabilities', () => ({ useCapabilities: () => ({ hasCapability: () => false }) }))
// The breach lookup is the only thing stubbed of the check: the dialog's real guard runs, which is what the wiring
// case below pins.
vi.mock('../../../shared/pwned', () => ({ pwnedCount: vi.fn() }))

import ChangePasswordPanel from './ChangePasswordPanel.vue'
import { changePassword } from './changePassword'
import { pwnedCount } from '../../../shared/pwned'
import { isRememberSupported } from '../rememberMe/deviceKeyWrap'
import { realI18n, t } from '../../../test/i18n'

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
  // The breach-check box: a native checkbox, so a test ticks it with `setValue(true)`.
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

  it('with the breach check ticked, a hit stops the first submit and the second goes ahead', async () => {
    // The slip the composable's own test cannot see: a dialog that forgets to await the guard.
    vi.mocked(pwnedCount).mockResolvedValue(3)
    const w = mount(ChangePasswordPanel, { global: { plugins: [realI18n()], stubs } })
    await flushPromises()

    const pw = passwords(w)
    await pw[0].setValue('the-current-one')
    await pw[1].setValue('a long enough new password')
    await pw[2].setValue('a long enough new password')
    await w.find('input[name="secret-key"]').setValue('the-secret-key')
    await w.find('input[type="checkbox"]').setValue(true)
    await flushPromises()

    await w.find('form').trigger('submit')
    await flushPromises()
    expect(changePassword).not.toHaveBeenCalled()
    expect(w.text()).toContain(t('password.pwnedWarning', { count: 3 }))

    await w.find('form').trigger('submit')
    await flushPromises()
    expect(changePassword).toHaveBeenCalledOnce()
    expect(pwnedCount).toHaveBeenCalledOnce()
    expect(pwnedCount).toHaveBeenCalledWith('a long enough new password')
  })
})
