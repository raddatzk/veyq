// uc.account.onboard-device steps 5–7 (web) — the shared tail, tested at the altitude where its ORDER is
// visible.
//
// The real `unlock.ts` runs here rather than a stub, which is the whole point: the order ADR 0059 §2 forces —
// adopt → unlock → register → enrol — is not observable if the step in the middle is a spy. Every one of those
// four "happened" in a wrong-order implementation too, so each assertion below is about *when*, anchored on
// state that could only have been installed by the step before it (the key-material fetch sees the adopted
// access token; write moment 1 sees the adopted family id).
//
// Only the leaves are mocked: the two API clients, the two enrolment tiers, the register, and this device's
// identity. `trustDevice` is mocked purely as a **tripwire** — the tail does not import it today, and the point
// of the spy is that it will fire the day somebody adds it (ADR 0005 §22: this device has no password to key a
// `DEVICE` wrap with, so that call must never appear on this path).
import 'fake-indexeddb/auto'
import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { IDBFactory } from 'fake-indexeddb'
import { httpError } from '../../../test/httpError'
import { fakeApi } from '../../../test/apiMock'
import type { TokenApi } from '../../../api/generated/auth'
import type { AccountApi, AccountIdentityPinsApi, AccountKeyMaterial } from '../../../api/generated/account'

vi.mock('../../../api/client', () => ({
  accountApi: vi.fn(),
  tokenApi: vi.fn(),
  loginApi: vi.fn(),
  sessionApi: vi.fn(),
  accountWrapsApi: vi.fn(),
  accountIdentityPinsApi: vi.fn(),
  tasksApi: vi.fn(),
}))
vi.mock('../rememberMe/deviceKeyWrap', () => ({
  enrolDeviceKey: vi.fn(),
  restoreDeviceKey: vi.fn(),
  isRememberSupported: vi.fn(),
}))
vi.mock('../rememberMe/sessionVault', () => ({
  cacheSessionVault: vi.fn(),
  loadSessionVault: vi.fn(),
  clearSessionVault: vi.fn(),
}))
vi.mock('../deviceRegister', () => ({ rememberThisDevice: vi.fn(), rememberThisDeviceWrap: vi.fn() }))
vi.mock('../localWrap', () => ({ localWrapId: vi.fn() }))
vi.mock('../deviceIdentity', () => ({
  deviceId: vi.fn(),
  guessDeviceLabel: vi.fn(),
}))
vi.mock('../trustDevice/trustDevice', () => ({
  trustThisDevice: vi.fn(),
  untrustThisDevice: vi.fn(),
  isTrusted: vi.fn(),
}))

import { runOnboardTail, OnboardTailError } from './onboardTail'
import { unlockWithGrantedMasterKey } from '../unlock/unlock'
import { accountApi, tokenApi, accountIdentityPinsApi } from '../../../api/client'
import { checkAndPin } from '../identityPins'
import { channelKeys, mintPairingSecret, openGrant, sealGrant } from './pairing'
import { enrolDeviceKey, isRememberSupported } from '../rememberMe/deviceKeyWrap'
import { deviceId, guessDeviceLabel } from '../deviceIdentity'
import { cacheSessionVault } from '../rememberMe/sessionVault'
import { rememberThisDevice, rememberThisDeviceWrap } from '../deviceRegister'
import { localWrapId } from '../localWrap'
import { trustThisDevice } from '../trustDevice/trustDevice'
import { session, clearTokens } from '../../../app/session'
import { account, clearAccount } from '../../../app/account'
import { isUnlocked, masterKey, identityPrivate, clearVault } from '../../../app/vault'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import type { PairingGrant } from './pairing'

const LABEL = 'Kevin’s iPad'
const EMAIL = 'kevin@example.com'


/** Exactly what the granting device sealed, plus the material the server serves the granted one. */
function fixture(label: string = LABEL) {
  const id = v1.generateIdentity()
  const mk = v1.randomBytes(32)
  const material: AccountKeyMaterial = {
    userId: 'user-1',
    suiteId: 'v1',
    wraps: [],
    wrappedIdentityPrivateKey: b64std(
      v1.seal({ purpose: 'identity-priv' }, mk, v1.serializeIdentityPrivate(id)),
    ),
  }
  const grant: PairingGrant = { email: EMAIL, masterKey: mk, refreshToken: 'the-grant-token', label }
  return { grant, material, id, mk }
}

let adoptSession: Mock<TokenApi['adoptSession']>
let refreshSession: Mock<TokenApi['refreshSession']>
let getAccountKeyMaterial: Mock<AccountApi['getAccountKeyMaterial']>
/** What the session looked like at the moment each later step ran — the only way to assert an ORDER rather
 *  than a SET, since a wrong-order implementation also calls every one of these. */
let sessionAtFetch: { accessToken: string | null; familyId: string | null }
let sessionAtRegister: { accessToken: string | null; familyId: string | null }

function useMaterial(material: AccountKeyMaterial) {
  getAccountKeyMaterial = vi.fn<AccountApi['getAccountKeyMaterial']>(() => {
    sessionAtFetch = { accessToken: session.accessToken, familyId: session.familyId }
    return Promise.resolve(material)
  })
  fakeApi(accountApi, { getAccountKeyMaterial })
}

beforeEach(() => {
  vi.clearAllMocks()
  globalThis.indexedDB = new IDBFactory() // this browser has held no trust state yet
  clearVault()
  clearTokens()
  clearAccount()
  sessionAtFetch = { accessToken: 'NEVER-RAN', familyId: 'NEVER-RAN' }
  sessionAtRegister = { accessToken: 'NEVER-RAN', familyId: 'NEVER-RAN' }

  adoptSession = vi.fn<TokenApi['adoptSession']>(() =>
    Promise.resolve({ accessToken: 'access-1', family: { id: 'family-1' } }),
  )
  // No refresh token in the answer: a cookie-carried session's refresh rotates only the cookie.
  refreshSession = vi.fn<TokenApi['refreshSession']>(() => Promise.resolve({ accessToken: 'access-2' }))
  fakeApi(tokenApi, { adoptSession, refreshSession })

  vi.mocked(localWrapId).mockResolvedValue(null)
  vi.mocked(rememberThisDevice).mockImplementation(() => {
    sessionAtRegister = { accessToken: session.accessToken, familyId: session.familyId }
    return Promise.resolve()
  })
  vi.mocked(rememberThisDeviceWrap).mockResolvedValue(undefined)
  vi.mocked(enrolDeviceKey).mockResolvedValue('wrap-new')
  vi.mocked(cacheSessionVault).mockResolvedValue(undefined)
  vi.mocked(isRememberSupported).mockReturnValue(true)
  vi.mocked(deviceId).mockReturnValue('this-device')
  vi.mocked(guessDeviceLabel).mockReturnValue('THE-UA-GUESS')
})

/** Write moment 1 is fire-and-forget (`void … .then(…)`), so it lands a few microtasks after the unlock. */
const settle = () => new Promise((r) => setTimeout(r, 0))

describe('the shared tail — the order ADR 0059 §2 forces', () => {
  it('adopts BEFORE it unlocks, registers and enrols', async () => {
    const { grant, material, mk, id } = fixture()
    useMaterial(material)

    await runOnboardTail(grant, true)
    await settle()

    // 1. The adoption ran, with the handed-over token and the answered lifetime.
    expect(adoptSession).toHaveBeenCalledExactlyOnceWith({
      tokenAdoptRequest: { refreshToken: 'the-grant-token', sessionScope: 'PERSISTENT' },
    })
    // The successor rides the cookie: no body token is kept, and the family id is installed for write moment 1.
    expect(session.accessToken).toBe('access-1')
    expect(session.refreshToken).toBeNull()
    expect(session.familyId).toBe('family-1')

    // 2. …and the unlock ran AFTER it, which is only provable by what the fetch could see. A tail that
    //    unlocked first would reach this line with a null access token — and would still have "fetched".
    expect(getAccountKeyMaterial).toHaveBeenCalledTimes(1)
    expect(sessionAtFetch).toEqual({ accessToken: 'access-1', familyId: 'family-1' })
    expect(isUnlocked()).toBe(true)
    expect(masterKey()).toEqual(mk)
    expect(identityPrivate()).toEqual(id.priv) // the handed-over key really opened the served material
    expect(account.email).toBe(EMAIL)

    // 3. Write moment 1 ran after the adoption too, and joined this device to the family the adoption named.
    expect(rememberThisDevice).toHaveBeenCalledTimes(1)
    expect(sessionAtRegister).toEqual({ accessToken: 'access-1', familyId: 'family-1' })
    expect(vi.mocked(rememberThisDevice).mock.calls[0][2]).toBe('family-1')

    // 4. …and so did the enrolment.
    const order = (fn: { mock: { invocationCallOrder: number[] } }) => fn.mock.invocationCallOrder[0]
    expect(order(adoptSession)).toBeLessThan(order(getAccountKeyMaterial))
    expect(order(adoptSession)).toBeLessThan(order(vi.mocked(rememberThisDevice)))
    expect(order(adoptSession)).toBeLessThan(order(vi.mocked(enrolDeviceKey)))
  })
})

describe('the shared tail — the label the device was handed (D14)', () => {
  it('writes its own register entry under the handed-over label, not the user-agent guess', async () => {
    const { grant, material } = fixture()
    useMaterial(material)

    await runOnboardTail(grant, false)
    await settle()

    expect(rememberThisDevice).toHaveBeenCalledTimes(1)
    const [deviceIdArg, fallbackLabel] = vi.mocked(rememberThisDevice).mock.calls[0]
    expect(deviceIdArg).toBe('this-device')
    expect(fallbackLabel).toBe(LABEL)
    // The negative a happy-path test cannot see: on a device with no prior entry the fallback IS the label, so
    // an unthreaded label would list this device under a user-agent string nobody chose.
    expect(fallbackLabel).not.toBe('THE-UA-GUESS')
  })

  it('every other unlock path still writes the guess — the control on the same predicate', async () => {
    // Without this, "the label is used" would be satisfied by an implementation that had simply replaced the
    // guess everywhere, which would rename every existing device on its next unlock.
    const { material, mk } = fixture()
    useMaterial(material)

    await unlockWithGrantedMasterKey(EMAIL, mk)
    await settle()

    expect(vi.mocked(rememberThisDevice).mock.calls[0][1]).toBe('THE-UA-GUESS')
  })

  it('falls back to the guess when the granting device left the name blank', async () => {
    const { grant, material } = fixture('   ')
    useMaterial(material)

    await runOnboardTail(grant, false)
    await settle()

    expect(vi.mocked(rememberThisDevice).mock.calls[0][1]).toBe('THE-UA-GUESS')
  })
})

describe('the shared tail — the tier the device is born at (D15, ADR 0005 §22)', () => {
  it('accepted: enrols a DEVICE_KEY wrap and records it, and caches no session vault', async () => {
    const { grant, material, mk } = fixture()
    useMaterial(material)

    await runOnboardTail(grant, true)
    await settle()

    expect(enrolDeviceKey).toHaveBeenCalledTimes(1)
    expect(vi.mocked(enrolDeviceKey).mock.calls[0][0]).toBe(EMAIL)
    expect(vi.mocked(enrolDeviceKey).mock.calls[0][1]).toEqual(mk)
    // Write moment 2 — the one instant the new wrap id exists.
    expect(rememberThisDeviceWrap).toHaveBeenCalledWith('this-device', LABEL, 'wrap-new')
    expect(cacheSessionVault).not.toHaveBeenCalled()
    expect(trustThisDevice).not.toHaveBeenCalled()
  })

  it('declined: no wrap of EITHER kind — and in particular no password DEVICE wrap', async () => {
    const { grant, material, mk } = fixture()
    useMaterial(material)

    await runOnboardTail(grant, false)
    await settle()

    // The Off tier of a linked device is the ordinary Off recipe MINUS the DEVICE wrap: this device has never
    // seen a password, so `enrolConvenienceWrap`'s declined arm — which keeps one — does not transfer.
    expect(trustThisDevice).not.toHaveBeenCalled()
    expect(enrolDeviceKey).not.toHaveBeenCalled()
    expect(rememberThisDeviceWrap).not.toHaveBeenCalled()
    // …and the Master Key is held for the browser session only.
    expect(cacheSessionVault).toHaveBeenCalledTimes(1)
    expect(vi.mocked(cacheSessionVault).mock.calls[0][0]).toBe(EMAIL)
    expect(vi.mocked(cacheSessionVault).mock.calls[0][1]).toEqual(mk)
  })

  it('the answered lifetime reaches adoption, both ways', async () => {
    // The tier and the cookie lifetime are ONE answer (ADR 0004 § web refresh cookie lifetimes): a build that
    // wired the wrap but left the scope constant would satisfy every assertion above.
    for (const [stay, scope] of [
      [true, 'PERSISTENT'],
      [false, 'SESSION'],
    ] as const) {
      vi.clearAllMocks()
      clearVault()
      clearTokens()
      vi.mocked(localWrapId).mockResolvedValue(null)
      vi.mocked(enrolDeviceKey).mockResolvedValue('wrap-new')
      const { grant, material } = fixture()
      useMaterial(material)
      fakeApi(tokenApi, { adoptSession, refreshSession })

      await runOnboardTail(grant, stay)

      expect(adoptSession).toHaveBeenLastCalledWith({
        tokenAdoptRequest: { refreshToken: 'the-grant-token', sessionScope: scope },
      })
    }
  })
})

describe('the shared tail — no password and no Secret Key reaches this device', () => {
  it('completes without either, and never calls a password-derived enrolment', async () => {
    const { grant, material } = fixture()
    useMaterial(material)

    await runOnboardTail(grant, true)
    await settle()

    expect(isUnlocked()).toBe(true)
    // The grant carries four fields and none of them is a password or a Secret Key — the type says so, and
    // this asserts nobody re-introduced one through the only call that would need it.
    expect(Object.keys(grant).sort()).toEqual(['email', 'label', 'masterKey', 'refreshToken'])
    expect(trustThisDevice).not.toHaveBeenCalled()
    // The positive control that the demand still exists elsewhere lives at the sign-in form
    // (`LoginView.test.ts` — the ordinary first sign-in still requires password AND Secret Key).
  })
})

describe('the shared tail — every terminal state, and the call that may never be retried (D16)', () => {
  it('a 401 on adopt is terminal, and adopt is presented EXACTLY ONCE', async () => {
    const { grant, material } = fixture()
    useMaterial(material)
    adoptSession.mockRejectedValue(httpError(401))

    await expect(runOnboardTail(grant, true)).rejects.toBeInstanceOf(OnboardTailError)
    await expect(runOnboardTail(grant, true)).rejects.toMatchObject({ reason: 'gone' })

    // Two invocations above, two calls — never four. A `withRefresh` around adopt would fire on exactly this
    // 401 and present the spent token a second time, which is ADR 0004's clone case: it revokes the family of
    // the device standing right here, and there is no password to come back with.
    expect(adoptSession).toHaveBeenCalledTimes(2)
    // Nothing downstream ran: no unlock, no register, no enrolment, no cached vault.
    expect(getAccountKeyMaterial).not.toHaveBeenCalled()
    expect(isUnlocked()).toBe(false)
    expect(rememberThisDevice).not.toHaveBeenCalled()
    expect(enrolDeviceKey).not.toHaveBeenCalled()
    expect(cacheSessionVault).not.toHaveBeenCalled()
  })

  it('refresh-and-retry IS wired on the rest of the tail — the control that the count above means something', async () => {
    // Without this, "adopt was called once" is satisfied by a client with no retry anywhere, and the assertion
    // would go on passing after the protection it describes became irrelevant.
    const { grant, material } = fixture()
    let fetches = 0
    getAccountKeyMaterial = vi.fn<AccountApi['getAccountKeyMaterial']>(() => {
      fetches += 1
      return fetches === 1 ? Promise.reject(httpError(401)) : Promise.resolve(material)
    })
    fakeApi(accountApi, { getAccountKeyMaterial })

    await runOnboardTail(grant, true)

    expect(fetches).toBe(2) // the key-material fetch DID refresh and retry…
    expect(refreshSession).toHaveBeenCalledTimes(1)
    expect(adoptSession).toHaveBeenCalledTimes(1) // …and adoption, on the same 401 class, did not
  })

  it('a 400 on adopt is a DIFFERENT terminal state — this browser already holds a session', async () => {
    const { grant, material } = fixture()
    useMaterial(material)
    adoptSession.mockRejectedValue(httpError(400))

    await expect(runOnboardTail(grant, true)).rejects.toMatchObject({ reason: 'session_present' })
    // Not folded into `gone`: the remedy differs, so the two must not collapse into one message.
    expect(adoptSession).toHaveBeenCalledTimes(1)
    expect(getAccountKeyMaterial).not.toHaveBeenCalled()
  })

  it('any other failure propagates as itself — the mapping is on the status, not on "it threw"', async () => {
    const { grant, material } = fixture()
    useMaterial(material)
    adoptSession.mockRejectedValue(httpError(503))

    await expect(runOnboardTail(grant, true)).rejects.not.toBeInstanceOf(OnboardTailError)
    expect(adoptSession).toHaveBeenCalledTimes(1)
  })

  it('a failure AFTER the adoption is terminal too, and still presents adopt only once', async () => {
    const { grant } = fixture()
    getAccountKeyMaterial = vi.fn<AccountApi['getAccountKeyMaterial']>(() => Promise.reject(httpError(500)))
    fakeApi(accountApi, { getAccountKeyMaterial })

    await expect(runOnboardTail(grant, true)).rejects.toThrow()

    expect(adoptSession).toHaveBeenCalledTimes(1)
    expect(isUnlocked()).toBe(false)
    expect(enrolDeviceKey).not.toHaveBeenCalled()
    expect(cacheSessionVault).not.toHaveBeenCalled()
  })
})

// ADR 0028 Decision 7 / ADR 0059: a linked device inherits its linker's trust-state marks inside the authenticated
// envelope, so the server cannot hand it an older store than the device that vouched for it had already opened.
describe('the shared tail — a linked device starts from its linker\u2019s trust-state marks', () => {
  /** The linker seals, this device opens through the MAC check and runs the tail — exactly the receiving path. */
  async function linkedBy(linkerPinMark: number) {
    const { grant, material, mk } = fixture()
    useMaterial(material)
    const channel = channelKeys(mintPairingSecret())
    const transport = v1.generateIdentity()
    const envelope = sealGrant(channel.key, transport.pub, { ...grant, marks: { pins: linkerPinMark, register: 0, epochs: {} } })
    await runOnboardTail(openGrant(channel.key, transport.priv, envelope), false)
    await settle()
    return mk
  }
  /** The pin store as the server serves it on this device's first read: a valid seal at `version`. */
  function servePins(mk: Uint8Array, version: number) {
    const blob = b64std(v1.seal({ purpose: 'identity-pins', version }, mk, new TextEncoder().encode(JSON.stringify({ pins: {} }))))
    const putIdentityPins = vi.fn<AccountIdentityPinsApi['putIdentityPins']>()
    fakeApi(accountIdentityPinsApi, { getIdentityPins: async () => ({ blob, version }), putIdentityPins })
    return putIdentityPins
  }

  it('linked by a device whose pin mark is 5, a v4 pin store on the first read is refused', async () => {
    const mk = await linkedBy(5)
    const put = servePins(mk, 4)
    await expect(checkAndPin('u1', 'a2V5LW9uZQ==')).rejects.toMatchObject({ code: 'trust_state_rolled_back' })
    expect(put).not.toHaveBeenCalled()
  })

  it('control: served v5, the store is accepted', async () => {
    const mk = await linkedBy(5)
    const put = servePins(mk, 5)
    // The write lands, and the store answers with what it now holds.
    put.mockImplementation(async ({ identityPinsUpdate }) => ({
      blob: identityPinsUpdate.blob,
      version: identityPinsUpdate.version + 1,
    }))
    expect(await checkAndPin('u1', 'a2V5LW9uZQ==')).toBe('firstUse')
  })
})
