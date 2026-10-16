// @vitest-environment happy-dom
// inbox #450 — a sign-in made while `core.remember-device.passwordless` is off, followed through a switch-on and a
// browser restart. ADR 0088 decision 5 promises that switching back on restores every remembered device; what can break
// that promise is not the wrap (the server only withholds it, and the client keeps its record) but the refresh cookie
// the sign-in in between asks for. A session cookie dies with the browser, so the restored wrap would then meet a
// device that has no session to fetch it with.
//
// The remember-me module is the REAL one, over a fake IndexedDB and a fake server that withholds DEVICE_KEY wraps while
// the switch is off, so the record the sign-in keeps is the record the restart opens. Login is modelled as the
// browser's cookie jar: the scope it asks for is the cookie it holds, and a restart keeps only a persistent one. Boot
// is modelled by its two steps that decide here — a cookie that revives the session, then `restoreDeviceKey` over the
// key material — not by `boot()`, whose own suite covers the refresh and the tiers.
import 'fake-indexeddb/auto'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { IDBFactory } from 'fake-indexeddb'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'

vi.mock('../api/client', () => ({
  accountWrapsApi: vi.fn(),
  accountApi: vi.fn(),
  accountEmailApi: vi.fn(),
  loginApi: vi.fn(),
  tokenApi: vi.fn(),
  sessionApi: vi.fn(),
}))
vi.mock('../features/account/login/login', () => ({ login: vi.fn() }))
vi.mock('../features/account/login/signOut', () => ({ signOut: vi.fn(), takeSignOutNotice: vi.fn() }))
vi.mock('../features/account/unlock/unlock', () => ({ unlockMasterKey: vi.fn(), unlockWithDevice: vi.fn() }))
vi.mock('../features/account/trustDevice/trustDevice', () => ({
  trustThisDevice: vi.fn(),
  untrustThisDevice: vi.fn(),
  isTrusted: vi.fn(),
  trustedEmails: vi.fn(),
}))
vi.mock('../features/account/rememberMe/sessionVault', () => ({ cacheSessionVault: vi.fn(), clearSessionVault: vi.fn() }))
const { switchedOff } = vi.hoisted(() => ({ switchedOff: new Set<string>() }))
vi.mock('../shared/state/useCapabilities', () => ({ useCapabilities: () => ({ hasCapability: (id: string) => !switchedOff.has(id) }) }))
vi.mock('../features/account/deviceRegister', () => ({ rememberThisDeviceWrap: vi.fn() }))
vi.mock('../features/account/deviceIdentity', () => ({ deviceId: vi.fn(), guessDeviceLabel: vi.fn() }))
vi.mock('../app/pendingRoute', () => ({ takeRoute: () => null }))
vi.mock('../shared/state/useConfirm', () => ({ useConfirm: () => ({ ask: vi.fn() }) }))
const MASTER_KEY = new Uint8Array(Array.from({ length: 32 }, (_, i) => i + 1))
vi.mock('../app/vault', async () => {
  const { reactive } = await import('vue')
  return { vaultState: reactive({ unlocked: false }), masterKey: () => MASTER_KEY }
})
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))

import LoginView from './LoginView.vue'
import { login } from '../features/account/login/login'
import { unlockMasterKey } from '../features/account/unlock/unlock'
import { trustThisDevice, isTrusted, trustedEmails } from '../features/account/trustDevice/trustDevice'
import { takeSignOutNotice } from '../features/account/login/signOut'
import { rememberThisDeviceWrap } from '../features/account/deviceRegister'
import { deviceId, guessDeviceLabel } from '../features/account/deviceIdentity'
import { cacheSessionVault } from '../features/account/rememberMe/sessionVault'
import { enrolDeviceKey, restoreDeviceKey } from '../features/account/rememberMe/deviceKeyWrap'
import { accountEmailApi, accountWrapsApi } from '../api/client'
import { fakeApi } from '../test/apiMock'
import { session } from '../app/session'
import type { AccountKeyMaterial, MasterKeyWrap } from '../api/generated/account'
import { LoginVerifyRequestSessionScopeEnum } from '../api/generated/auth'
import { CORE_REMEMBER_DEVICE_PASSWORDLESS } from '../features/platform/capabilities'
import { realI18n } from '../test/i18n'

const EMAIL = 'kevin@example.com'

/** The server's wraps of this account, and the key material it serves: DEVICE_KEY wraps left out while the switch is
 *  off and never deleted for it (`KeyMaterialHandler.forUser`). */
const server = {
  wraps: [] as MasterKeyWrap[],
  deletes: [] as string[],
  keyMaterial(): AccountKeyMaterial {
    const withheld = switchedOff.has(CORE_REMEMBER_DEVICE_PASSWORDLESS)
    return {
      userId: 'user-1',
      suiteId: 'v1',
      wrappedIdentityPrivateKey: '',
      wraps: this.wraps.filter((w) => !(withheld && w.method === 'DEVICE_KEY')),
    }
  },
}

/** The browser's refresh cookie: what the last login asked for. A restart keeps only a persistent one (ADR 0004). */
const jar = { cookie: null as LoginVerifyRequestSessionScopeEnum | null }

function restartBrowser() {
  if (jar.cookie === LoginVerifyRequestSessionScopeEnum.Session) jar.cookie = null
  session.accessToken = jar.cookie ? 'revived' : null
}

/** Boot after a restart: the cookie revives the session, then the device's DEVICE_KEY wrap opens the Master Key. */
async function opensDirectly(): Promise<boolean> {
  if (!jar.cookie) return false
  return (await restoreDeviceKey(server.keyMaterial())) !== null
}

const stubs = {
  Card: { template: '<div><slot name="title" /><slot name="content" /></div>' },
  Button: { props: ['label', 'type'], emits: ['click'], template: '<button :type="type" @click="$emit(\'click\')">{{ label }}</button>' },
  Message: { template: '<div><slot /></div>' },
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
  Checkbox: true,
  KitScanner: true,
  JoinDeviceCard: true,
  SignInWithCodeCard: true,
}

let mounted: VueWrapper | null = null

beforeEach(() => {
  vi.clearAllMocks()
  globalThis.indexedDB = new IDBFactory()
  server.wraps = []
  server.deletes = []
  jar.cookie = null
  session.accessToken = null
  switchedOff.clear()
  // The revived session belongs to this account, as the server answers for it (inbox #454).
  fakeApi(accountEmailApi, { getAccountEmail: async () => ({ email: EMAIL, confirmed: true }) })
  fakeApi(accountWrapsApi, {
    addAccountWrap: vi.fn(async ({ addWrapRequest: r }) => {
      const id = `wrap-${server.wraps.length + 1}`
      server.wraps.push({ id, method: r.method as MasterKeyWrap['method'], wrappedKey: r.wrappedKey, kdfParams: r.kdfParams })
      return { id }
    }),
    deleteAccountWrap: vi.fn(async ({ id }) => {
      server.deletes.push(id)
      server.wraps = server.wraps.filter((w) => w.id !== id)
    }),
  })
  vi.mocked(login).mockImplementation(async (_email, _password, scope) => {
    jar.cookie = scope ?? null
    session.accessToken = 'signed-in'
  })
  vi.mocked(unlockMasterKey).mockResolvedValue(undefined)
  vi.mocked(takeSignOutNotice).mockReturnValue(null)
  vi.mocked(isTrusted).mockResolvedValue(false)
  vi.mocked(trustedEmails).mockResolvedValue([])
  vi.mocked(rememberThisDeviceWrap).mockResolvedValue(undefined)
  vi.mocked(deviceId).mockReturnValue('this-device')
  vi.mocked(guessDeviceLabel).mockReturnValue('Firefox')
})

afterEach(() => {
  mounted?.unmount()
  mounted = null
  session.accessToken = null
})

describe('a remembered device signs in while passwordless is off (inbox #450)', () => {
  /** The sign-in screen: the member types their address even though it is prefilled (the measured path of the
   *  browser pass), then the password and the Secret Key the withheld wrap leaves them needing. */
  async function signIn() {
    mounted = mount(LoginView, { global: { plugins: [realI18n()], stubs } })
    const emailField = mounted.find('input[name="email"]')
    // The fake IndexedDB answers on its own timers, past a single promise flush.
    await vi.waitFor(() => expect((emailField.element as HTMLInputElement).value).toBe(EMAIL))
    await emailField.setValue('')
    await emailField.setValue(EMAIL)
    await emailField.trigger('change')
    await mounted.find('#login-password').setValue('the-password')
    await flushPromises()
    await mounted.find('input[name="secret-key"]').setValue('the-secret-key')
    await mounted.find('form').trigger('submit')
    await vi.waitFor(() => expect(cacheSessionVault).toHaveBeenCalled()) // the sign-in ran to its enrolment
    expect(trustThisDevice).not.toHaveBeenCalled()
    expect(server.deletes).toEqual([])
  }

  it('a new login keeps the persistent scope, so it opens directly again after the switch-on and a restart', async () => {
    // Remembered earlier with the toggle on: a DEVICE_KEY wrap and a persistent cookie.
    await enrolDeviceKey(EMAIL, MASTER_KEY)
    jar.cookie = LoginVerifyRequestSessionScopeEnum.Persistent

    switchedOff.add(CORE_REMEMBER_DEVICE_PASSWORDLESS)
    // The cookie ran out while the switch was off, so the restart revives no session and the sign-in is a new login,
    // which picks the cookie the next restart keeps or loses.
    jar.cookie = null
    restartBrowser()
    expect(await opensDirectly()).toBe(false) // control: no session to fetch the wrap with

    await signIn()
    expect(login).toHaveBeenCalledOnce() // control: the sign-in really was a new login
    expect(jar.cookie).toBe(LoginVerifyRequestSessionScopeEnum.Persistent)

    switchedOff.delete(CORE_REMEMBER_DEVICE_PASSWORDLESS)
    restartBrowser()
    expect(await opensDirectly()).toBe(true)
  })

  it('a revived session is unlocked inside (inbox #454), so its persistent cookie stays and the restart opens directly', async () => {
    await enrolDeviceKey(EMAIL, MASTER_KEY)
    jar.cookie = LoginVerifyRequestSessionScopeEnum.Persistent

    switchedOff.add(CORE_REMEMBER_DEVICE_PASSWORDLESS)
    restartBrowser()
    expect(await opensDirectly()).toBe(false) // control: the switch bites — the wrap is withheld

    await signIn()
    expect(login).not.toHaveBeenCalled() // the typed address is the session's: no second login, no new cookie
    expect(jar.cookie).toBe(LoginVerifyRequestSessionScopeEnum.Persistent)

    switchedOff.delete(CORE_REMEMBER_DEVICE_PASSWORDLESS)
    restartBrowser()
    expect(await opensDirectly()).toBe(true)
  })
})
