// @vitest-environment happy-dom
// uc.account.sessions §E3 (web, cr-locked-sign-out-entry-point) — the sign-in screen's first harness.
//
// This view is 100 % of E3, and what only a mounted view can pin is the three things the flow rests on:
//   - the entry is OFFERED exactly where the Secret-Key field is, because that field is what closes the form;
//   - it logs in with `SESSION` scope **even with the remember toggle on**, and attempts no unlock — an
//     abandoned flow must not leave a persistent refresh cookie on a foreign browser, and a red "unlock failed"
//     would be the screen contradicting the button that reached it;
//   - the way back to unlocking persists NOTHING at any tier, which is asserted against the ordinary first
//     sign-in as its positive control — a bare "nothing was enrolled" is satisfied by any wholesale breakage of
//     the enrolment path, and would go on passing after the feature it guards was deleted.
//
// The vault module is mocked so `masterKey()` answers with bytes: `enrolConvenienceWrap` returns early without
// one, which would make the positive control pass for the wrong reason.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'

vi.mock('../features/account/login/login', () => ({ login: vi.fn(), logout: vi.fn() }))
vi.mock('../features/account/unlock/unlock', () => ({ unlockMasterKey: vi.fn(), unlockWithDevice: vi.fn() }))
vi.mock('../features/account/trustDevice/trustDevice', () => ({
  trustThisDevice: vi.fn(),
  untrustThisDevice: vi.fn(),
  isTrusted: vi.fn(),
}))
vi.mock('../features/account/rememberMe/deviceKeyWrap', () => ({
  enrolDeviceKey: vi.fn(),
  clearDeviceKey: vi.fn(),
  isRememberSupported: () => true,
}))
vi.mock('../features/account/rememberMe/sessionVault', () => ({
  cacheSessionVault: vi.fn(),
  clearSessionVault: vi.fn(),
}))
vi.mock('../shared/state/useCapabilities', () => ({ useCapabilities: () => ({ hasCapability: () => true }) }))
vi.mock('../features/account/emergencyKit/emergencyKit', () => ({ importKitFromFile: vi.fn() }))
vi.mock('../features/account/deviceRegister', () => ({ rememberThisDeviceWrap: vi.fn() }))
vi.mock('../features/account/deviceIdentity', () => ({
  deviceId: () => 'this-device',
  guessDeviceLabel: () => 'Firefox · macOS',
}))
vi.mock('../features/account/sessions/sessions', () => ({ revokeOtherSessionFamilies: vi.fn() }))
vi.mock('../app/authPrefill', () => ({ takeAuthPrefill: () => null }))
vi.mock('../app/pendingRoute', () => ({ takeRoute: () => null }))
vi.mock('../app/vault', async () => {
  const { reactive } = await import('vue')
  return { vaultState: reactive({ unlocked: false }), masterKey: () => new Uint8Array(32) }
})
const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))

import LoginView from './LoginView.vue'
import { login, logout } from '../features/account/login/login'
import { unlockMasterKey, unlockWithDevice } from '../features/account/unlock/unlock'
import { trustThisDevice, untrustThisDevice, isTrusted } from '../features/account/trustDevice/trustDevice'
import { enrolDeviceKey, clearDeviceKey } from '../features/account/rememberMe/deviceKeyWrap'
import { cacheSessionVault, clearSessionVault } from '../features/account/rememberMe/sessionVault'
import { revokeOtherSessionFamilies } from '../features/account/sessions/sessions'
import { LoginVerifyRequestSessionScopeEnum } from '../api/generated/auth'
import { i18n } from '../i18n'

const stubs = {
  Card: { template: '<div><slot name="title" /><slot name="content" /></div>' },
  // `emits: ['click']` is load-bearing: without it the parent's @click also arrives as a native fallthrough
  // listener, so one trigger fires the handler twice and every call count reads double.
  Button: {
    props: ['label', 'icon', 'severity', 'loading', 'disabled', 'type'],
    emits: ['click'],
    template: '<button :type="type" :disabled="loading || disabled" @click="$emit(\'click\')">{{ label }}</button>',
  },
  Message: { props: ['severity'], template: '<div class="msg"><slot /></div>' },
  InputText: {
    props: ['modelValue', 'type'],
    emits: ['update:modelValue'],
    template: '<input :type="type" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  Password: {
    props: ['modelValue', 'inputId'],
    emits: ['update:modelValue'],
    template: '<input type="password" :id="inputId" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  Checkbox: {
    props: ['modelValue', 'inputId', 'binary'],
    emits: ['update:modelValue'],
    template: '<input type="checkbox" :id="inputId" :checked="modelValue" @change="$emit(\'update:modelValue\', $event.target.checked)" />',
  },
  KitScanner: true,
}

// The view debounces its IndexedDB trust lookup on a timer and clears it on unmount; a wrapper left mounted
// keeps resolving into a later test's assertions.
const mounted: VueWrapper[] = []

function mountView(): VueWrapper {
  const w = mount(LoginView, { global: { plugins: [i18n], stubs } })
  mounted.push(w)
  return w
}

const t = (key: string) => i18n.global.t(key)
const buttonLabelled = (w: VueWrapper, label: string) => w.findAll('button').find((b) => b.text() === label)

/** Fill in email + password and resolve the device state immediately (@change, not the debounce). */
async function fillCredentials(w: VueWrapper, email = 'kevin@example.com', password = 'the-password') {
  await w.find('input[name="email"]').setValue(email)
  await w.find('input[name="email"]').trigger('change')
  await w.find('#login-password').setValue(password)
  await flushPromises()
}

/** Turn "stay signed in on this device" on. */
async function turnRememberOn(w: VueWrapper) {
  await w.find('#remember-me').setValue(true)
  await flushPromises()
}

/** Take the locked entry: log in without unlocking, landing on §E3's one-action screen. */
async function enterLocked(w: VueWrapper) {
  await buttonLabelled(w, t('sessions.lockedEntry'))!.trigger('click')
  await flushPromises()
}

describe('LoginView (uc.account.sessions §E3, web)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    i18n.global.locale.value = 'en'
    // `clearAllMocks` clears calls, not implementations, so every resolved value is restated per test.
    vi.mocked(isTrusted).mockResolvedValue(false)
    vi.mocked(login).mockResolvedValue(undefined)
    vi.mocked(logout).mockResolvedValue(undefined)
    vi.mocked(unlockMasterKey).mockResolvedValue(undefined)
    vi.mocked(unlockWithDevice).mockResolvedValue(true)
    vi.mocked(trustThisDevice).mockResolvedValue('wrap-1')
    vi.mocked(untrustThisDevice).mockResolvedValue(undefined)
    vi.mocked(enrolDeviceKey).mockResolvedValue('wrap-2')
    vi.mocked(clearDeviceKey).mockResolvedValue(undefined)
    vi.mocked(cacheSessionVault).mockResolvedValue(undefined)
    vi.mocked(clearSessionVault).mockResolvedValue(undefined)
    vi.mocked(revokeOtherSessionFamilies).mockResolvedValue(undefined)
    window.confirm = vi.fn(() => true)
  })

  afterEach(() => {
    mounted.splice(0).forEach((w) => w.unmount())
  })

  it('offers the locked entry exactly where the Secret-Key field is', async () => {
    const wrapper = mountView()
    await fillCredentials(wrapper)

    expect(wrapper.find('input[name="secret-key"]').exists()).toBe(true)
    expect(buttonLabelled(wrapper, t('sessions.lockedEntry'))).toBeDefined()

    // The live control on the same predicate: a device that already carries a password wrap for this account
    // needs no Secret Key, so the form is not closed and there is nothing to offer a way around. Without it,
    // a button that never rendered at all would satisfy the negative half perfectly.
    vi.mocked(isTrusted).mockResolvedValue(true)
    const trusted = mountView()
    await fillCredentials(trusted)

    expect(trusted.find('input[name="secret-key"]').exists()).toBe(false)
    expect(buttonLabelled(trusted, t('sessions.lockedEntry'))).toBeUndefined()
    expect(buttonLabelled(trusted, t('login.submit'))).toBeDefined()
  })

  it('logs in with SESSION scope even with the remember toggle on, and attempts no unlock', async () => {
    const wrapper = mountView()
    await fillCredentials(wrapper)
    await turnRememberOn(wrapper)
    await enterLocked(wrapper)

    expect(login).toHaveBeenCalledWith(
      'kevin@example.com',
      'the-password',
      LoginVerifyRequestSessionScopeEnum.Session,
    )
    // Not unlocking is the INTENT here, not a failure to report: this person has no Emergency Kit with them.
    expect(unlockMasterKey).not.toHaveBeenCalled()
    expect(unlockWithDevice).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain(t('sessions.lockedTitle'))
  })

  it('the ordinary submit still honours the toggle — the control that makes the forcing above mean something', async () => {
    // Without this, a screen that could only ever pass SESSION would satisfy the assertion above. The force is
    // the behaviour under test, not the constant.
    const wrapper = mountView()
    await fillCredentials(wrapper)
    await turnRememberOn(wrapper)
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(login).toHaveBeenCalledWith(
      'kevin@example.com',
      'the-password',
      LoginVerifyRequestSessionScopeEnum.Persistent,
    )
  })

  it('the way back to unlocking persists nothing, at either toggle position', async () => {
    for (const remember of [false, true]) {
      vi.clearAllMocks()
      vi.mocked(isTrusted).mockResolvedValue(false)
      const wrapper = mountView()
      await fillCredentials(wrapper)
      if (remember) await turnRememberOn(wrapper)
      await enterLocked(wrapper)

      await buttonLabelled(wrapper, t('sessions.lockedBack'))!.trigger('click')
      await flushPromises()
      await wrapper.find('input[name="secret-key"]').setValue('the-secret-key')
      await wrapper.find('form').trigger('submit')
      await flushPromises()

      // The unlock itself must work — the way back is a way back, not a dead end.
      expect(unlockMasterKey).toHaveBeenCalledWith('the-password', 'the-secret-key')
      // …and it leaves this machine holding nothing. A saved key here is the exact asset the flow protects.
      expect(trustThisDevice).not.toHaveBeenCalled()
      expect(enrolDeviceKey).not.toHaveBeenCalled()
      expect(cacheSessionVault).not.toHaveBeenCalled()
    }
  })

  it('an ordinary first sign-in DOES persist — the positive control on the same predicate', async () => {
    // A bare "nothing was enrolled" above is satisfied by any wholesale breakage of the enrolment path, and
    // would keep passing after the feature it guards was deleted. Both toggle positions, because the flag has
    // to suppress both tiers.
    const wrapper = mountView()
    await fillCredentials(wrapper)
    await wrapper.find('input[name="secret-key"]').setValue('the-secret-key')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(cacheSessionVault).toHaveBeenCalled()
    expect(trustThisDevice).toHaveBeenCalled()

    vi.clearAllMocks()
    vi.mocked(isTrusted).mockResolvedValue(false)
    vi.mocked(enrolDeviceKey).mockResolvedValue('wrap-2')
    const remembering = mountView()
    await fillCredentials(remembering)
    await turnRememberOn(remembering)
    await remembering.find('input[name="secret-key"]').setValue('the-secret-key')
    await remembering.find('form').trigger('submit')
    await flushPromises()

    expect(enrolDeviceKey).toHaveBeenCalled()
  })

  it('signs this device out too, and says so on the form it hands back', async () => {
    const wrapper = mountView()
    await fillCredentials(wrapper)
    await enterLocked(wrapper)
    await buttonLabelled(wrapper, t('sessions.lockedAction'))!.trigger('click')
    await flushPromises()

    expect(revokeOtherSessionFamilies).toHaveBeenCalledTimes(1)
    expect(logout).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain(t('sessions.lockedDone'))
    // Nothing is left to do here, so nothing is left standing: no live session means no sign-out button.
    expect(buttonLabelled(wrapper, t('login.signOut'))).toBeUndefined()
  })

  it('a failed sign-out leaves this device’s own sign-out button reachable', async () => {
    vi.mocked(logout).mockRejectedValue(new Error('offline'))
    const wrapper = mountView()
    await fillCredentials(wrapper)
    await enterLocked(wrapper)
    await buttonLabelled(wrapper, t('sessions.lockedAction'))!.trigger('click')
    await flushPromises()

    // The other devices are gone; this one is still signed in and must not be told otherwise.
    expect(wrapper.text()).toContain(t('sessions.lockedDoneStillHere'))
    expect(buttonLabelled(wrapper, t('login.signOut'))).toBeDefined()
  })
})
