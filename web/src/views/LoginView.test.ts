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

vi.mock('../features/account/login/login', () => ({ login: vi.fn() }))
vi.mock('../features/account/login/signOut', () => ({ signOut: vi.fn(), takeSignOutNotice: vi.fn() }))
vi.mock('../features/account/unlock/unlock', () => ({ unlockMasterKey: vi.fn(), unlockWithDevice: vi.fn() }))
vi.mock('../features/account/trustDevice/trustDevice', () => ({
  trustThisDevice: vi.fn(),
  untrustThisDevice: vi.fn(),
  isTrusted: vi.fn(),
  trustedEmails: vi.fn(),
}))
vi.mock('../features/account/rememberMe/deviceKeyWrap', () => ({
  enrolDeviceKey: vi.fn(),
  clearDeviceKey: vi.fn(),
  isRememberSupported: vi.fn(),
  rememberedWrapId: vi.fn(),
  rememberedEmail: vi.fn(),
}))
vi.mock('../features/account/rememberMe/sessionVault', () => ({
  cacheSessionVault: vi.fn(),
  clearSessionVault: vi.fn(),
}))
// Every id is on unless a test puts it in `switchedOff`: the view reads each leaf on its own (ADR 0088 decision 3), so a
// test switches ONE leaf off and the others stay the control.
const { switchedOff } = vi.hoisted(() => ({ switchedOff: new Set<string>() }))
vi.mock('../shared/state/useCapabilities', () => ({ useCapabilities: () => ({ hasCapability: (id: string) => !switchedOff.has(id) }) }))
// `parseKit` mirrors the real contract (a kit payload or `null`) rather than being asserted on: what this file
// owns is the DISPATCH between the two codes, and the payload formats themselves are pinned in their own
// modules' tests (`@veyq/crypto/emergencyKit`, and `pairing.test.ts` for D5, both directions).
vi.mock('../features/account/emergencyKit/emergencyKit', () => ({ importKitFromFile: vi.fn() }))
vi.mock('@veyq/crypto/emergencyKit', () => ({
  parseKit: (text: string) =>
    text.startsWith('veyqkit:') ? { email: 'scanned@example.com', secretKey: 'scanned-secret-key' } : null,
}))
vi.mock('../features/account/deviceRegister', () => ({ rememberThisDeviceWrap: vi.fn() }))
vi.mock('../features/account/deviceIdentity', () => ({
  deviceId: vi.fn(),
  guessDeviceLabel: vi.fn(),
}))
vi.mock('../features/account/sessions/sessions', () => ({ revokeOtherSessionFamilies: vi.fn() }))
vi.mock('../app/pendingRoute', () => ({ takeRoute: () => null }))
// The locked sign-out this view renders confirms through the app's own dialog (cr-web-own-confirm-dialog),
// which is mounted in App.vue — out of reach of a view-level mount, so its answer is mocked here.
const { confirmAsk } = vi.hoisted(() => ({ confirmAsk: vi.fn() }))
vi.mock('../shared/state/useConfirm', () => ({ useConfirm: () => ({ ask: confirmAsk }) }))
vi.mock('../app/vault', async () => {
  const { reactive } = await import('vue')
  return { vaultState: reactive({ unlocked: false }), masterKey: () => new Uint8Array(32) }
})
const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))

import LoginView from './LoginView.vue'
import { login } from '../features/account/login/login'
import { signOut, takeSignOutNotice } from '../features/account/login/signOut'
import { session } from '../app/session'
import { unlockMasterKey, unlockWithDevice } from '../features/account/unlock/unlock'
import { trustThisDevice, untrustThisDevice, isTrusted, trustedEmails } from '../features/account/trustDevice/trustDevice'
import {
  enrolDeviceKey,
  clearDeviceKey,
  isRememberSupported,
  rememberedWrapId,
  rememberedEmail,
} from '../features/account/rememberMe/deviceKeyWrap'
import { deviceId, guessDeviceLabel } from '../features/account/deviceIdentity'
import { cacheSessionVault, clearSessionVault } from '../features/account/rememberMe/sessionVault'
import { revokeOtherSessionFamilies } from '../features/account/sessions/sessions'
import { LoginVerifyRequestSessionScopeEnum } from '../api/generated/auth'
import { realI18n, t } from '../test/i18n'
import { CORE_DEVICE_LINKING, CORE_REMEMBER_DEVICE, CORE_REMEMBER_DEVICE_PASSWORDLESS } from '../features/platform/capabilities'
import { KdfProfileRefused } from '@veyq/crypto/v1'

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
  // Stubbed because the real one mints a transport key and touches the channel the moment it is rendered —
  // its own behaviour is `JoinDeviceCard.test.ts`'s. What this file asserts is that it is REACHED, with the
  // scanned secret, and that reaching it disturbs nothing else on the card.
  JoinDeviceCard: true,
  // Same reason: the real one mints a transport key and opens a channel the moment it renders. Its behaviour is
  // `SignInWithCodeCard.test.ts`'s; what this file owns is that it is REACHED without a camera and without a
  // credential, and that reaching it disturbs nothing else on the card.
  SignInWithCodeCard: true,
}

// The view debounces its IndexedDB trust lookup on a timer and clears it on unmount; a wrapper left mounted
// keeps resolving into a later test's assertions.
const mounted: VueWrapper[] = []

function mountView(): VueWrapper {
  const w = mount(LoginView, { global: { plugins: [realI18n()], stubs } })
  mounted.push(w)
  return w
}

/** A pairing code's secret is exactly 32 bytes (ADR 0059 §11): bytes 0..31, standard base64. */
const PAIRING_SECRET = 'AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8='
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

const scanButton = (w: VueWrapper) =>
  w.findAll('button').find((b) => b.attributes('aria-label') === t('unlock.scan'))

/** Open the shared scan control and hand it a decoded string, the way the camera would. */
async function scan(w: VueWrapper, text: string) {
  await scanButton(w)!.trigger('click')
  const scanner = w.findComponent({ name: 'KitScanner' })
  scanner.vm.$emit('found', text)
  await flushPromises()
}

describe('LoginView (uc.account.sessions §E3, web)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // `clearAllMocks` clears calls, not implementations, so every resolved value is restated per test.
    vi.mocked(isTrusted).mockResolvedValue(false)
    vi.mocked(login).mockResolvedValue(undefined)
    vi.mocked(signOut).mockResolvedValue(undefined)
    vi.mocked(takeSignOutNotice).mockReturnValue(null)
    session.accessToken = null
    vi.mocked(unlockMasterKey).mockResolvedValue(undefined)
    vi.mocked(unlockWithDevice).mockResolvedValue(true)
    vi.mocked(trustThisDevice).mockResolvedValue('wrap-1')
    vi.mocked(untrustThisDevice).mockResolvedValue(undefined)
    vi.mocked(enrolDeviceKey).mockResolvedValue('wrap-2')
    vi.mocked(clearDeviceKey).mockResolvedValue(undefined)
    vi.mocked(isRememberSupported).mockReturnValue(true)
    vi.mocked(rememberedWrapId).mockResolvedValue(null)
    vi.mocked(rememberedEmail).mockResolvedValue(null)
    vi.mocked(trustedEmails).mockResolvedValue([])
    vi.mocked(deviceId).mockReturnValue('this-device')
    vi.mocked(guessDeviceLabel).mockReturnValue('Firefox · macOS')
    vi.mocked(cacheSessionVault).mockResolvedValue(undefined)
    vi.mocked(clearSessionVault).mockResolvedValue(undefined)
    vi.mocked(revokeOtherSessionFamilies).mockResolvedValue(undefined)
    confirmAsk.mockResolvedValue(true)
    switchedOff.clear()
    // The scan entry is capability-gated on a camera; happy-dom has none, so the control would not render.
    Object.defineProperty(navigator, 'mediaDevices', { value: { getUserMedia: vi.fn() }, configurable: true })
  })

  afterEach(() => {
    mounted.splice(0).forEach((w) => w.unmount())
    session.accessToken = null
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

  // ADR 0088 decision 11: the toggle and the two wraps are two leaves, each read on its own. Every case switches ONE off and
  // asserts on the same submit as the positive control above.
  async function submitOrdinary(w: VueWrapper, remember: boolean) {
    await fillCredentials(w)
    if (remember) await turnRememberOn(w)
    await w.find('input[name="secret-key"]').setValue('the-secret-key')
    await w.find('form').trigger('submit')
    await flushPromises()
  }

  it('remember-device.passwordless off: no toggle and no DEVICE_KEY wrap, but the password DEVICE wrap is still enrolled', async () => {
    switchedOff.add(CORE_REMEMBER_DEVICE_PASSWORDLESS)
    const w = mountView()
    await submitOrdinary(w, false)
    expect(w.find('#remember-me').exists()).toBe(false)
    expect(enrolDeviceKey).not.toHaveBeenCalled()
    expect(trustThisDevice).toHaveBeenCalled()
  })

  it('remember-device off: the toggle stays and enrols its DEVICE_KEY; the toggle-off path enrols no DEVICE wrap', async () => {
    switchedOff.add(CORE_REMEMBER_DEVICE)
    const off = mountView()
    await submitOrdinary(off, false)
    expect(cacheSessionVault).toHaveBeenCalled() // control: the sign-in itself went through
    expect(trustThisDevice).not.toHaveBeenCalled()

    vi.clearAllMocks()
    vi.mocked(isTrusted).mockResolvedValue(false)
    vi.mocked(enrolDeviceKey).mockResolvedValue('wrap-2')
    const on = mountView()
    expect(on.find('#remember-me').exists()).toBe(true)
    await submitOrdinary(on, true)
    expect(enrolDeviceKey).toHaveBeenCalled()
  })

  // ADR 0088 decision 5: a switch-off withholds the device's own wrap from the key material without
  // deleting it, so the device falls back to the floor (password AND Secret Key, for both tiers: a device holds one
  // convenience wrap) and keeps its local record, so switching back on restores it. What must NOT happen is the
  // enrolment after the fallback replacing the withheld tier with the other one. The mock does not AND the path as
  // the server does, so a parent switched off switches its child off here by hand.
  const secretKeyField = (w: VueWrapper) => w.find('input[name="secret-key"]')

  it('remember-device off: a trusted device asks for the password and the Secret Key at once, says why, and keeps its trust', async () => {
    switchedOff.add(CORE_REMEMBER_DEVICE).add(CORE_REMEMBER_DEVICE_PASSWORDLESS)
    vi.mocked(isTrusted).mockResolvedValue(true)
    vi.mocked(unlockWithDevice).mockResolvedValue(false) // its DEVICE wrap is withheld
    const w = mountView()
    await fillCredentials(w)

    expect(secretKeyField(w).exists()).toBe(true)
    expect(w.text()).toContain(t('unlock.deviceHeldBack'))
    expect(w.text()).not.toContain(t('unlock.secretKeyHint'))
    await secretKeyField(w).setValue('the-secret-key')
    await w.find('form').trigger('submit')
    await flushPromises()

    expect(unlockMasterKey).toHaveBeenCalledWith('the-password', 'the-secret-key')
    expect(unlockWithDevice).not.toHaveBeenCalled()
    expect(cacheSessionVault).toHaveBeenCalled() // control: the sign-in itself went through
    expect(untrustThisDevice).not.toHaveBeenCalled()
    expect(trustThisDevice).not.toHaveBeenCalled()
    expect(clearDeviceKey).not.toHaveBeenCalled()
    expect(enrolDeviceKey).not.toHaveBeenCalled()
  })

  it('remember-device on again: the same trusted device unlocks with the password alone and enrols nothing', async () => {
    vi.mocked(isTrusted).mockResolvedValue(true)
    const w = mountView()
    await fillCredentials(w)

    expect(secretKeyField(w).exists()).toBe(false)
    await w.find('form').trigger('submit')
    await flushPromises()

    expect(unlockWithDevice).toHaveBeenCalledWith('kevin@example.com', 'the-password')
    expect(unlockMasterKey).not.toHaveBeenCalled()
    expect(trustThisDevice).not.toHaveBeenCalled()
    expect(enrolDeviceKey).not.toHaveBeenCalled()
  })

  it('remember-device.passwordless off: a remembered device asks for the password and the Secret Key, says why, and is not made a trusted one', async () => {
    switchedOff.add(CORE_REMEMBER_DEVICE_PASSWORDLESS)
    vi.mocked(rememberedWrapId).mockResolvedValue('wrap-remembered')
    const w = mountView()
    await fillCredentials(w)

    expect(secretKeyField(w).exists()).toBe(true)
    expect(w.text()).toContain(t('unlock.deviceHeldBack'))
    await secretKeyField(w).setValue('the-secret-key')
    await w.find('form').trigger('submit')
    await flushPromises()

    expect(unlockMasterKey).toHaveBeenCalledWith('the-password', 'the-secret-key')
    expect(cacheSessionVault).toHaveBeenCalled() // control: the sign-in itself went through
    // Replacing the withheld DEVICE_KEY wrap with a DEVICE one would delete it, and its local key with it.
    expect(clearDeviceKey).not.toHaveBeenCalled()
    expect(trustThisDevice).not.toHaveBeenCalled()
  })

  it('control: with its switch on, a remembered device whose wrap is gone is re-enrolled as before, and nothing is said', async () => {
    // The skip above is the switch's, not the record's: a wrap absent while its switch is on was removed, and the
    // sign-in replaces the stale record (uc.account.remember-this-device E2).
    vi.mocked(rememberedWrapId).mockResolvedValue('wrap-remembered')
    const w = mountView()
    await fillCredentials(w)

    expect(w.text()).not.toContain(t('unlock.deviceHeldBack'))
    expect(w.text()).toContain(t('unlock.secretKeyHint'))
    await secretKeyField(w).setValue('the-secret-key')
    await w.find('form').trigger('submit')
    await flushPromises()

    expect(clearDeviceKey).toHaveBeenCalled()
    expect(trustThisDevice).toHaveBeenCalled()
  })

  // inbox #450: a sign-in made while a device switch is off. The remembered device keeps the persistent cookie it had,
  // so switching back on restores it across a browser restart (ADR 0088 decision 5); the chain itself, restart
  // included, is `LoginView.deviceSwitch.test.ts`'s.
  const scopeOfLogin = () => vi.mocked(login).mock.calls[0]?.[2]

  it('remember-device.passwordless off: a device that keeps its DEVICE_KEY record signs in with the persistent scope', async () => {
    switchedOff.add(CORE_REMEMBER_DEVICE_PASSWORDLESS)
    vi.mocked(rememberedWrapId).mockResolvedValue('wrap-remembered')
    const w = mountView()
    await fillCredentials(w)
    expect(w.find('#remember-me').exists()).toBe(false) // the toggle stays hidden: nothing new is offered
    await secretKeyField(w).setValue('the-secret-key')
    await w.find('form').trigger('submit')
    await flushPromises()

    expect(scopeOfLogin()).toBe(LoginVerifyRequestSessionScopeEnum.Persistent)
    expect(enrolDeviceKey).not.toHaveBeenCalled() // no DEVICE_KEY wrap is enrolled while its switch is off
  })

  it('control: with passwordless off, a device without such a record still gets the session scope', async () => {
    // The persistent scope is the record's, not the switch's: a device that never chose "stay signed in" is not
    // handed a cookie that outlives the browser.
    switchedOff.add(CORE_REMEMBER_DEVICE_PASSWORDLESS)
    const w = mountView()
    await submitOrdinary(w, false)

    expect(scopeOfLogin()).toBe(LoginVerifyRequestSessionScopeEnum.Session)
    expect(trustThisDevice).toHaveBeenCalled() // control: the sign-in went through and enrolled as before
  })

  it('control: with passwordless on, the toggle alone picks the scope, a record or not', async () => {
    vi.mocked(rememberedWrapId).mockResolvedValue('wrap-remembered')
    const w = mountView()
    await submitOrdinary(w, false)

    expect(scopeOfLogin()).toBe(LoginVerifyRequestSessionScopeEnum.Session)
  })

  it('prefills the email from the device’s one local record, so the hint is right before anything is typed', async () => {
    switchedOff.add(CORE_REMEMBER_DEVICE_PASSWORDLESS)
    vi.mocked(rememberedEmail).mockResolvedValue('kevin@example.com')
    vi.mocked(rememberedWrapId).mockResolvedValue('wrap-remembered')
    const w = mountView()
    await flushPromises()

    expect((w.find('input[name="email"]').element as HTMLInputElement).value).toBe('kevin@example.com')
    expect(w.text()).toContain(t('unlock.deviceHeldBack'))
    expect(w.text()).not.toContain(t('unlock.secretKeyHint'))
  })

  it('prefills from a trusted device’s record too, and the password alone then unlocks it', async () => {
    vi.mocked(trustedEmails).mockResolvedValue(['kevin@example.com'])
    vi.mocked(isTrusted).mockImplementation(async (e) => e === 'kevin@example.com')
    const w = mountView()
    await flushPromises()

    expect((w.find('input[name="email"]').element as HTMLInputElement).value).toBe('kevin@example.com')
    expect(secretKeyField(w).exists()).toBe(false)
  })

  it('prefills nothing where the device holds records of two accounts, or where something is already typed', async () => {
    vi.mocked(trustedEmails).mockResolvedValue(['a@example.com', 'b@example.com'])
    const two = mountView()
    await flushPromises()
    expect((two.find('input[name="email"]').element as HTMLInputElement).value).toBe('')

    vi.mocked(trustedEmails).mockResolvedValue([])
    // The record answers late, after the member started typing: their address wins.
    let answer: (email: string | null) => void = () => {}
    vi.mocked(rememberedEmail).mockReturnValue(new Promise((r) => { answer = r }))
    const typing = mountView()
    await typing.find('input[name="email"]').setValue('typed@example.com')
    answer('kevin@example.com')
    await flushPromises()
    expect((typing.find('input[name="email"]').element as HTMLInputElement).value).toBe('typed@example.com')
  })

  it('the prefilled email keeps a session a boot restored: the unlock is no second login', async () => {
    // A prefill is not "a different account": dropping the restored session for it would make every sign-in on such a
    // device a new login (the second session that typing the email causes, which this row does not fix).
    session.accessToken = 'restored'
    switchedOff.add(CORE_REMEMBER_DEVICE_PASSWORDLESS)
    vi.mocked(rememberedEmail).mockResolvedValue('kevin@example.com')
    vi.mocked(rememberedWrapId).mockResolvedValue('wrap-remembered')
    const w = mountView()
    await flushPromises()
    await w.find('#login-password').setValue('the-password')
    await secretKeyField(w).setValue('the-secret-key')
    await w.find('form').trigger('submit')
    await flushPromises()

    expect(login).not.toHaveBeenCalled()
    expect(unlockMasterKey).toHaveBeenCalledWith('the-password', 'the-secret-key')
  })

  it('device-linking off: no "sign in with code" entry, and a scanned pairing code is not accepted (the kit still is)', async () => {
    switchedOff.add(CORE_DEVICE_LINKING)
    const w = mountView()
    await fillCredentials(w)
    expect(buttonLabelled(w, t('pairing.signInEntry'))).toBeUndefined()
    await scanButton(w)!.trigger('click')
    const accepts = w.findComponent({ name: 'KitScanner' }).props('accepts') as (t: string) => boolean
    expect(accepts(`veyqpair:${PAIRING_SECRET}`)).toBe(false)
    expect(accepts('veyqkit:whatever')).toBe(true) // control: the kit scan is unrelated to linking
    // …and with the leaf on (the default), both are offered: the control of the three tests above
    switchedOff.clear()
    const on = mountView()
    await fillCredentials(on)
    expect(buttonLabelled(on, t('pairing.signInEntry'))).toBeDefined()
  })

  it('signs this device out too, through the one sign-out', async () => {
    const wrapper = mountView()
    await fillCredentials(wrapper)
    await enterLocked(wrapper)
    await buttonLabelled(wrapper, t('sessions.lockedAction'))!.trigger('click')
    await flushPromises()

    expect(revokeOtherSessionFamilies).toHaveBeenCalledTimes(1)
    expect(signOut).toHaveBeenCalledExactlyOnceWith({ everywhere: true })
  })

  it('says every device was signed out on the page that sign-out lands on', async () => {
    // The sign-out ends in a new document, so its outcome arrives as the notice it left, read at mount.
    vi.mocked(takeSignOutNotice).mockReturnValue('everywhere')
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain(t('sessions.lockedDone'))
    // Nothing is left to do here, so nothing is left standing: no live session means no sign-out button.
    expect(buttonLabelled(wrapper, t('login.signOut'))).toBeUndefined()
  })

  // --- uc.account.onboard-device: the join entry, `the-locked-entry-adds-no-shell-route`'s behavioural half ---

  it('offers the join through the SAME scan entry, which now accepts both of this app’s codes', async () => {
    const wrapper = mountView()
    await fillCredentials(wrapper)

    // Offered exactly where the Emergency-Kit scan already was — one control, one camera lifecycle, one
    // permission prompt, and no second button whose difference a user cannot see.
    expect(scanButton(wrapper)).toBeDefined()
    await scanButton(wrapper)!.trigger('click')
    const accepts = wrapper.findComponent({ name: 'KitScanner' }).props('accepts') as (t: string) => boolean
    expect(accepts(`veyqpair:${PAIRING_SECRET}`)).toBe(true)
    expect(accepts('veyqkit:whatever')).toBe(true)
    // The negative, on the same predicate: a foreign QR is scanned PAST, not reported as a failure.
    expect(accepts('https://example.com/')).toBe(false)
    expect(accepts('')).toBe(false)
  })

  it('a scanned pairing code opens the join card with that secret — and attempts no unlock, and no login', async () => {
    const wrapper = mountView()
    await fillCredentials(wrapper)

    await scan(wrapper, `veyqpair:${PAIRING_SECRET}`)

    const join = wrapper.findComponent({ name: 'JoinDeviceCard' })
    expect(join.exists()).toBe(true)
    expect(join.props('secret')).toBe(PAIRING_SECRET)
    expect(wrapper.text()).toContain(t('pairing.joinTitle'))
    // This device is about to be handed everything by another one. Nothing here may reach for a credential it
    // does not have — a "login failed" or "unlock failed" would be the screen contradicting the code it scanned.
    expect(login).not.toHaveBeenCalled()
    expect(unlockMasterKey).not.toHaveBeenCalled()
    expect(unlockWithDevice).not.toHaveBeenCalled()
    // The form is replaced, not hidden behind a route: no navigation happened.
    expect(wrapper.find('form').exists()).toBe(false)
    expect(push).not.toHaveBeenCalled()
  })

  it('the Emergency-Kit scan still applies the kit through the widened dispatch — the regression control', async () => {
    // This is what a shared, dispatching scan control most plausibly breaks, and a bare "the join opened" would
    // not notice it: the widening must leave the older path byte-identical in behaviour.
    const wrapper = mountView()
    await fillCredentials(wrapper)

    await scan(wrapper, 'veyqkit:whatever')

    expect((wrapper.find('input[name="email"]').element as HTMLInputElement).value).toBe('scanned@example.com')
    expect((wrapper.find('input[name="secret-key"]').element as HTMLInputElement).value).toBe('scanned-secret-key')
    expect(wrapper.findComponent({ name: 'JoinDeviceCard' }).exists()).toBe(false)
    expect(wrapper.find('form').exists()).toBe(true)
  })

  it('leaving the join hands the sign-in form back, and finishing it routes into the shell', async () => {
    const wrapper = mountView()
    await fillCredentials(wrapper)
    await scan(wrapper, `veyqpair:${PAIRING_SECRET}`)

    wrapper.findComponent({ name: 'JoinDeviceCard' }).vm.$emit('cancel')
    await flushPromises()
    expect(wrapper.find('form').exists()).toBe(true)
    expect(wrapper.findComponent({ name: 'JoinDeviceCard' }).exists()).toBe(false)

    await scan(wrapper, `veyqpair:${PAIRING_SECRET}`)
    wrapper.findComponent({ name: 'JoinDeviceCard' }).vm.$emit('done')
    await flushPromises()
    expect(push).toHaveBeenCalledWith({ name: 'home' })
  })

  // --- uc.account.onboard-device: the OTHER direction's entry, and its behavioural half of the same criterion ---

  it('offers the QR sign-in entry with and without a camera — where the scan control is camera-gated', async () => {
    // Both halves are the assertion. Without the no-camera case the entry could be quietly gated and nobody
    // would notice; without the with-camera case "it is always absent" would pass the no-camera half.
    const withCamera = mountView()
    await fillCredentials(withCamera)
    expect(scanButton(withCamera)).toBeDefined()
    expect(buttonLabelled(withCamera, t('pairing.signInEntry'))).toBeDefined()

    // A camera-less laptop is PRECISELY this direction's case: this device only displays a code, and the camera
    // it needs is on the other one (ADR 0059 §1 — no direction is the camera one).
    Object.defineProperty(navigator, 'mediaDevices', { value: undefined, configurable: true })
    const withoutCamera = mountView()
    await fillCredentials(withoutCamera)

    expect(scanButton(withoutCamera)).toBeUndefined() // the control on the same predicate: that one IS gated
    expect(buttonLabelled(withoutCamera, t('pairing.signInEntry'))).toBeDefined()
  })

  it('choosing it swaps in the routeless card — and attempts no login, no unlock, no navigation', async () => {
    const wrapper = mountView()
    await fillCredentials(wrapper)

    await buttonLabelled(wrapper, t('pairing.signInEntry'))!.trigger('click')
    await flushPromises()

    expect(wrapper.findComponent({ name: 'SignInWithCodeCard' }).exists()).toBe(true)
    expect(wrapper.text()).toContain(t('pairing.signInTitle'))
    // This device is about to be handed everything by another one. Nothing here may reach for a credential it
    // does not have — and the entry sits beside a `required` Secret-Key field it must not trip.
    expect(login).not.toHaveBeenCalled()
    expect(unlockMasterKey).not.toHaveBeenCalled()
    expect(unlockWithDevice).not.toHaveBeenCalled()
    // The form is replaced, not hidden behind a route: no navigation happened.
    expect(wrapper.find('form').exists()).toBe(false)
    expect(push).not.toHaveBeenCalled()
  })

  it('the sign-in form’s own ways out are untouched by the new entry — the regression control', async () => {
    // What a third button on this card most plausibly breaks: E3's locked entry and recovery, which sit beside
    // it, and the Emergency-Kit scan the same card already carries.
    const wrapper = mountView()
    await fillCredentials(wrapper)

    expect(buttonLabelled(wrapper, t('sessions.lockedEntry'))).toBeDefined()
    expect(buttonLabelled(wrapper, t('recovery.forgot'))).toBeDefined()
    await scan(wrapper, 'veyqkit:whatever')
    expect((wrapper.find('input[name="email"]').element as HTMLInputElement).value).toBe('scanned@example.com')
    expect(wrapper.findComponent({ name: 'SignInWithCodeCard' }).exists()).toBe(false)

    // The kit above authoritatively overwrote the email, which is the Emergency-Kit path working; E3's entry
    // then logs in with what is on the form, still SESSION-scoped and still attempting no unlock.
    await enterLocked(wrapper)
    expect(login).toHaveBeenCalledWith('scanned@example.com', 'the-password', LoginVerifyRequestSessionScopeEnum.Session)
    expect(unlockMasterKey).not.toHaveBeenCalled()
  })

  it('leaving the QR sign-in hands the form back, and finishing it routes into the shell', async () => {
    const wrapper = mountView()
    await fillCredentials(wrapper)
    await buttonLabelled(wrapper, t('pairing.signInEntry'))!.trigger('click')
    await flushPromises()

    wrapper.findComponent({ name: 'SignInWithCodeCard' }).vm.$emit('cancel')
    await flushPromises()
    expect(wrapper.find('form').exists()).toBe(true)
    expect(wrapper.findComponent({ name: 'SignInWithCodeCard' }).exists()).toBe(false)

    await buttonLabelled(wrapper, t('pairing.signInEntry'))!.trigger('click')
    await flushPromises()
    wrapper.findComponent({ name: 'SignInWithCodeCard' }).vm.$emit('done')
    await flushPromises()
    expect(push).toHaveBeenCalledWith({ name: 'home' })
  })

  it('a failed sign-out leaves this device’s own sign-out button reachable', async () => {
    // The new document booted with the refresh cookie the failed revoke left live, so it holds a session again —
    // locked, since the ending dropped every convenience tier.
    vi.mocked(takeSignOutNotice).mockReturnValue('everywhereUnconfirmed')
    session.accessToken = 'restored'
    const wrapper = mountView()
    await flushPromises()

    // The other devices are gone; this one is still signed in and must not be told otherwise.
    expect(wrapper.text()).toContain(t('sessions.lockedDoneStillHere'))
    expect(buttonLabelled(wrapper, t('login.signOut'))).toBeDefined()
  })

  it('says the session ended while a device was being given access, and that none was let in (uc.account.onboard-device E6)', async () => {
    vi.mocked(takeSignOutNotice).mockReturnValue('grantInterrupted')
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain(t('pairing.grantInterrupted'))
  })

  it.each([
    ['refused', 'login.sessionEnded'],
    ['otherTab', 'login.sessionEndedOtherTab'],
  ] as const)('says why the session ended when this tab did not ask: %s → %s (inbox #322)', async (left, key) => {
    vi.mocked(takeSignOutNotice).mockReturnValue(left)
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain(t(key))
  })

  it('says a sign-out did not reach the server, and offers the retry (uc.account.sign-out E4)', async () => {
    vi.mocked(takeSignOutNotice).mockReturnValue('unconfirmed')
    session.accessToken = 'restored'
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain(t('login.signOutUnconfirmed'))
    await buttonLabelled(wrapper, t('login.signOut'))!.trigger('click')
    await flushPromises()
    // The sign-in card is one of the five entry points: its sign-out is the one sequence, not a copy.
    expect(signOut).toHaveBeenCalledExactlyOnceWith()
  })

  it('says nothing about a sign-out when none left a notice', async () => {
    // The control for the two above: the warnings come from the notice, not from the restored session.
    session.accessToken = 'restored'
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).not.toContain(t('login.signOutUnconfirmed'))
    expect(wrapper.text()).not.toContain(t('sessions.lockedDoneStillHere'))
    expect(buttonLabelled(wrapper, t('login.signOut'))).toBeDefined()
  })

  it('a served key-derivation profile the client refused does not blame the password or Secret Key', async () => {
    // ADR 0017 rule 5: the refusal is the server's doing, so "check your password and Secret Key" would send
    // someone to retype a right credential. Control on the same submit: any other unlock failure still says it.
    for (const [failure, shown, notShown] of [
      [new KdfProfileRefused(), t('common.unreachable'), t('unlock.failed')],
      [new Error('aead'), t('unlock.failed'), t('common.unreachable')],
    ] as const) {
      vi.mocked(unlockMasterKey).mockRejectedValueOnce(failure)
      const wrapper = mountView()
      await fillCredentials(wrapper)
      await wrapper.find('input[name="secret-key"]').setValue('the-secret-key')
      await wrapper.find('form').trigger('submit')
      await flushPromises()

      expect(wrapper.text()).toContain(shown)
      expect(wrapper.text()).not.toContain(notShown)
    }
  })
})
