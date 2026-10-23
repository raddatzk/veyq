// @vitest-environment happy-dom
// inbox #454 — the sign-in screen reached with a session a boot restored. Boot revives a session from the refresh
// cookie and may not open the vault (a wrap withheld or gone, a session vault lost with its tab), so the device lands
// here to unlock. Typing the email used to drop that session, so the unlock became a second login and the restored
// family stayed listed in Devices and sessions until it expired (seven after the browser pass of #447).
//
// Asserted where the families are made and ended: the REAL `login` and the real refresh path over a fake server that
// keeps the families and knows whose session a bearer token is, so "no new family" is a count of `verifyLogin` calls
// and "ends the old one" is the family's state there, not a mocked function's call. The unlock itself is mocked: its
// derivation is `unlock.test.ts`'s, and what this file owns is which session it runs on.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'

vi.mock('../api/client', () => ({
  loginApi: vi.fn(),
  tokenApi: vi.fn(),
  sessionApi: vi.fn(),
  accountEmailApi: vi.fn(),
}))
// The login derives its auth key with production Argon2id; the fake server verifies no signature, so a fixed key
// keeps the real request sequence without the seconds.
vi.mock('@veyq/crypto/argonWorker', () => ({
  deriveAuthKeypairAsync: async () => ({ privateKey: new Uint8Array(32).fill(7), publicKey: new Uint8Array(32) }),
}))
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
vi.mock('../features/account/rememberMe/sessionVault', () => ({ cacheSessionVault: vi.fn(), clearSessionVault: vi.fn() }))
vi.mock('../shared/state/useCapabilities', () => ({ useCapabilities: () => ({ hasCapability: () => true }) }))
vi.mock('../features/account/deviceRegister', () => ({ rememberThisDeviceWrap: vi.fn() }))
vi.mock('../features/account/deviceIdentity', () => ({ deviceId: vi.fn(), guessDeviceLabel: vi.fn() }))
vi.mock('../app/pendingRoute', () => ({ takeRoute: () => null }))
vi.mock('../shared/state/useConfirm', () => ({ useConfirm: () => ({ ask: vi.fn() }) }))
vi.mock('../app/vault', async () => {
  const { reactive } = await import('vue')
  return { vaultState: reactive({ unlocked: false }), masterKey: () => new Uint8Array(32) }
})
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))

import LoginView from './LoginView.vue'
import { unlockMasterKey, unlockWithDevice } from '../features/account/unlock/unlock'
import { isTrusted, trustedEmails } from '../features/account/trustDevice/trustDevice'
import { isRememberSupported, rememberedEmail, rememberedWrapId, enrolDeviceKey } from '../features/account/rememberMe/deviceKeyWrap'
import { takeSignOutNotice } from '../features/account/login/signOut'
import { deviceId, guessDeviceLabel } from '../features/account/deviceIdentity'
import { accountEmailApi, loginApi, sessionApi, tokenApi } from '../api/client'
import { fakeApi, type ApiMocks } from '../test/apiMock'
import { session } from '../app/session'
import { LoginVerifyRequestSessionScopeEnum, type LoginApi, type SessionApi, type TokenApi } from '../api/generated/auth'
import type { AccountEmailApi } from '../api/generated/account'
import { realI18n } from '../test/i18n'

const KEVIN = 'kevin@example.com'
const OTHER = 'other@example.com'

/** The server's session families, and whose session each access token is: the bearer the fake reads from `session`,
 *  as the real client sends it. */
type Family = { id: string; account: string; scope: string | null; active: boolean }
const server = {
  families: [] as Family[],
  tokens: new Map<string, Family>(),
  /** A family a boot restored: issued earlier, revived from the cookie in this document. */
  restore(account: string): Family {
    const family: Family = { id: 'restored', account, scope: null, active: true }
    this.families.push(family)
    this.tokens.set('restored-access', family)
    session.accessToken = 'restored-access'
    return family
  },
  caller(): Family {
    const family = session.accessToken ? this.tokens.get(session.accessToken) : undefined
    if (!family?.active) throw Object.assign(new Error('401'), { response: { status: 401 } })
    return family
  },
  active(account: string): string[] {
    return this.families.filter((f) => f.active && f.account === account).map((f) => f.id)
  },
}

let wire: {
  login: ApiMocks<LoginApi, 'requestLoginChallenge' | 'verifyLogin'>
  token: ApiMocks<TokenApi, 'refreshSession'>
  session: ApiMocks<SessionApi, 'logout'>
  email: ApiMocks<AccountEmailApi, 'getAccountEmail'>
}

const stubs = {
  VeyqCard: { template: '<div><slot name="title" /><slot /></div>' },
  VeyqButton: { props: ['label', 'type'], emits: ['click'], template: '<button :type="type ?? \'button\'" @click="$emit(\'click\')">{{ label }}</button>' },
  VeyqMessage: { template: '<div><slot /></div>' },
  VeyqInput: {
    props: ['modelValue', 'type'],
    emits: ['update:modelValue'],
    template: '<input :type="type" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  VeyqPassword: {
    props: ['modelValue', 'inputId'],
    emits: ['update:modelValue'],
    template: '<input type="password" :id="inputId" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  VeyqCheckbox: {
    props: ['modelValue', 'inputId'],
    emits: ['update:modelValue'],
    template: '<input type="checkbox" :id="inputId" :checked="modelValue" @change="$emit(\'update:modelValue\', $event.target.checked)" />',
  },
  KitScanner: true,
  JoinDeviceCard: true,
  SignInWithCodeCard: true,
}

let mounted: VueWrapper | null = null

async function mountView(): Promise<VueWrapper> {
  mounted = mount(LoginView, { global: { plugins: [realI18n()], stubs } })
  await flushPromises()
  return mounted
}

/** Type the address the way a member does — into the field, with the change event — then the password and the key. */
async function signIn(w: VueWrapper, opts: { email?: string; remember?: boolean } = {}) {
  if (opts.email !== undefined) {
    await w.find('input[name="email"]').setValue(opts.email)
    await w.find('input[name="email"]').trigger('change')
  }
  await w.find('#login-password').setValue('the-password')
  await flushPromises()
  if (opts.remember) await w.find('#remember-me').setValue(true)
  await w.find('input[name="secret-key"]').setValue('the-secret-key')
  await w.find('form').trigger('submit')
  await flushPromises()
}

beforeEach(() => {
  vi.clearAllMocks()
  server.families = []
  server.tokens = new Map()
  session.accessToken = null
  session.refreshToken = null
  session.familyId = null
  vi.mocked(takeSignOutNotice).mockReturnValue(null)
  vi.mocked(deviceId).mockReturnValue('this-device')
  vi.mocked(guessDeviceLabel).mockReturnValue('Firefox')
  vi.mocked(isTrusted).mockResolvedValue(false)
  vi.mocked(trustedEmails).mockResolvedValue([])
  vi.mocked(isRememberSupported).mockReturnValue(true)
  vi.mocked(rememberedEmail).mockResolvedValue(null)
  vi.mocked(rememberedWrapId).mockResolvedValue(null)
  vi.mocked(enrolDeviceKey).mockResolvedValue('wrap-1')
  vi.mocked(unlockMasterKey).mockResolvedValue(undefined)
  vi.mocked(unlockWithDevice).mockResolvedValue(true)
  wire = {
    login: fakeApi(loginApi, {
      requestLoginChallenge: async () => ({ nonce: 'bm9uY2U=', instanceSalt: 'c2FsdHNhbHRzYWx0c2FsdA==' }),
      verifyLogin: async ({ loginVerifyRequest: r }) => {
        const family: Family = { id: `fam-${server.families.length + 1}`, account: r.email.trim().toLowerCase(), scope: r.sessionScope ?? null, active: true }
        server.families.push(family)
        server.tokens.set(`${family.id}-access`, family)
        return { accessToken: `${family.id}-access`, family: { id: family.id } }
      },
    }),
    token: fakeApi(tokenApi, { refreshSession: async () => ({ accessToken: 'rotated' }) }),
    session: fakeApi(sessionApi, {
      logout: async () => {
        const family = session.accessToken ? server.tokens.get(session.accessToken) : undefined
        if (family) family.active = false
      },
    }),
    email: fakeApi(accountEmailApi, {
      getAccountEmail: async () => ({ email: server.caller().account, confirmed: true }),
    }),
  }
})

afterEach(() => {
  mounted?.unmount()
  mounted = null
  session.accessToken = null
})

describe('the sign-in screen reached with a restored session (inbox #454)', () => {
  it('typing the same address unlocks inside the restored session: no login, no new family', async () => {
    server.restore(KEVIN)
    const w = await mountView()
    await signIn(w, { email: KEVIN })

    expect(unlockMasterKey).toHaveBeenCalledWith('the-password', 'the-secret-key') // control: the unlock ran
    expect(wire.login.requestLoginChallenge).not.toHaveBeenCalled()
    expect(wire.login.verifyLogin).not.toHaveBeenCalled()
    expect(wire.token.refreshSession).not.toHaveBeenCalled()
    expect(wire.session.logout).not.toHaveBeenCalled()
    expect(server.active(KEVIN)).toEqual(['restored'])
  })

  it('the same address in another spelling is the same account: the one normalization, trim and lower-case', async () => {
    server.restore(KEVIN)
    const w = await mountView()
    await signIn(w, { email: '  Kevin@Example.COM ' })

    expect(wire.login.verifyLogin).not.toHaveBeenCalled()
    expect(server.active(KEVIN)).toEqual(['restored'])
  })

  it('a different address ends the restored family first, then signs in fresh', async () => {
    server.restore(KEVIN)
    const w = await mountView()
    await signIn(w, { email: OTHER })

    expect(wire.session.logout).toHaveBeenCalledOnce()
    expect(wire.login.verifyLogin).toHaveBeenCalledOnce()
    // Ended BEFORE the login: logout clears the browser's refresh cookies, so after it would clear the new one.
    expect(wire.session.logout.mock.invocationCallOrder[0]).toBeLessThan(wire.login.verifyLogin.mock.invocationCallOrder[0])
    expect(server.active(KEVIN)).toEqual([])
    expect(server.active(OTHER)).toEqual(['fam-2'])
    expect(unlockMasterKey).toHaveBeenCalledOnce()
  })

  it('a device whose restored session is not reachable at logout mints no family beside it', async () => {
    server.restore(KEVIN)
    wire.session.logout.mockRejectedValueOnce(new TypeError('Failed to fetch'))
    const w = await mountView()
    await signIn(w, { email: OTHER })

    expect(wire.login.verifyLogin).not.toHaveBeenCalled()
    expect(unlockMasterKey).not.toHaveBeenCalled()
    expect(server.active(KEVIN)).toEqual(['restored'])
  })

  it('the prefilled address (#450) still keeps the session: no login', async () => {
    server.restore(KEVIN)
    vi.mocked(rememberedEmail).mockResolvedValue(KEVIN)
    const w = await mountView()
    expect((w.find('input[name="email"]').element as HTMLInputElement).value).toBe(KEVIN)
    await signIn(w)

    expect(wire.login.verifyLogin).not.toHaveBeenCalled()
    expect(server.active(KEVIN)).toEqual(['restored'])
    expect(unlockMasterKey).toHaveBeenCalledOnce()
  })

  it('a prefill is checked like a typed address: a record of another account than the session’s signs in fresh', async () => {
    // The device record names one account, the cookie revived another's session. Unlocking on that session with the
    // first account's password would open the wrong vault, or none; the screen must not take the record's word.
    server.restore(OTHER)
    vi.mocked(rememberedEmail).mockResolvedValue(KEVIN)
    const w = await mountView()
    await signIn(w)

    expect(wire.session.logout).toHaveBeenCalledOnce()
    expect(wire.login.verifyLogin).toHaveBeenCalledOnce()
    expect(server.active(OTHER)).toEqual([])
    expect(server.active(KEVIN)).toEqual(['fam-2'])
  })

  it('turning "remember me" on asks for a persistent cookie the restored session may lack: it is replaced, not left', async () => {
    // A refresh can never raise a cookie's lifetime (ADR 0004), and the client cannot read which one it holds.
    server.restore(KEVIN)
    const w = await mountView()
    await signIn(w, { email: KEVIN, remember: true })

    expect(wire.login.verifyLogin).toHaveBeenCalledOnce()
    expect(wire.login.verifyLogin.mock.calls[0][0].loginVerifyRequest.sessionScope).toBe(LoginVerifyRequestSessionScopeEnum.Persistent)
    expect(server.active(KEVIN)).toEqual(['fam-2'])
  })

  it('a Secret-Key retry after this screen’s own login stays on that session', async () => {
    const w = await mountView()
    vi.mocked(unlockMasterKey).mockRejectedValueOnce(new Error('aead'))
    await signIn(w, { email: KEVIN })
    await w.find('input[name="secret-key"]').setValue('the-right-key')
    await w.find('form').trigger('submit')
    await flushPromises()

    expect(wire.login.verifyLogin).toHaveBeenCalledOnce()
    expect(wire.email.getAccountEmail).not.toHaveBeenCalled() // its own login named the account
    expect(unlockMasterKey).toHaveBeenLastCalledWith('the-password', 'the-right-key')
    expect(server.active(KEVIN)).toEqual(['fam-1'])
  })

  it('a changed address after this screen’s own login ends that family too', async () => {
    const w = await mountView()
    vi.mocked(unlockMasterKey).mockRejectedValueOnce(new Error('aead'))
    await signIn(w, { email: OTHER })
    await signIn(w, { email: KEVIN })

    expect(wire.login.verifyLogin).toHaveBeenCalledTimes(2)
    expect(server.active(OTHER)).toEqual([])
    expect(server.active(KEVIN)).toEqual(['fam-2'])
  })

  it('without a restored session the submit is a plain login, and asks the server nothing first', async () => {
    const w = await mountView()
    await signIn(w, { email: KEVIN })

    expect(wire.email.getAccountEmail).not.toHaveBeenCalled()
    expect(wire.session.logout).not.toHaveBeenCalled()
    expect(wire.login.verifyLogin).toHaveBeenCalledOnce()
  })
})
