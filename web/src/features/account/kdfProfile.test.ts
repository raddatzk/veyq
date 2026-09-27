// uc.account.unlock — a served key-derivation profile is checked, never obeyed (ADR 0017 rule 5).
//
// Every path that derives a KEK from a profile the SERVER hands over: the PASSWORD floor, the DEVICE wrap, the
// cold-credential proof and the recovery release. Each must refuse any profile other than the one the client
// writes (`v1.ARGON` + a 16-byte salt) BEFORE a single Argon2id pass runs — a server that could pick the cost
// could make every unlock derive near-forever, in a worker with no timeout whose fallback reruns it on the main
// thread.
//
// The derivations are spies, not Argon2id: the defect is WHETHER the derivation is reached, and a real one at
// `t = 2³¹` would hang the suite instead of failing it. Each spy hands back the real KEK the fixture sealed under,
// so the ARGON control is a full, successful unlock rather than a mere "was called" — which is what gives each
// negative its meaning: the same fixture, the same spy, one field apart.
//
// The per-field negatives are here as well as at the check itself (`decodeKdfParams`, in @veyq/crypto's
// conformance suite, which kills its mutants): no CI run has executed that package's suites yet, so ADR 0040
// admits none of them, and these are the pins on an admitted row.
import { describe, it, expect, vi, beforeEach } from 'vitest'

const { unlockKekAsync, deviceKekAsync, recoveryKekAsync } = vi.hoisted(() => ({
  unlockKekAsync: vi.fn(),
  deviceKekAsync: vi.fn(),
  recoveryKekAsync: vi.fn(),
}))
vi.mock('@veyq/crypto/argonWorker', () => ({
  unlockKekAsync,
  deviceKekAsync,
  recoveryKekAsync,
  deriveAuthKeypairAsync: vi.fn(),
}))
vi.mock('../../api/client', () => ({
  accountApi: vi.fn(),
  loginApi: vi.fn(),
  tokenApi: vi.fn(),
  sessionApi: vi.fn(),
  recoveryApi: vi.fn(),
}))
vi.mock('./trustDevice/deviceKey', () => ({
  loadTrust: vi.fn(),
  deviceFactor: vi.fn(),
  clearTrust: vi.fn(),
}))

import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import { accountApi } from '../../api/client'
import * as device from './trustDevice/deviceKey'
import { clearVault, isUnlocked, masterKey, setUnlocked } from '../../app/vault'
import { unlockMasterKey, unlockWithDevice } from './unlock/unlock'
import { ColdCredentialRefused, proveColdCredential } from './coldCredential'
import { openWithSecretKey } from './recovery/recovery'
import type { RecoveryMaterial } from '../../api/generated/auth'

const PASSWORD = 'correct horse battery'
const WRAP_ID = 'device-wrap-id-1'
/** The live defect as the CR measured it: m and p as written, a pass count only the server could want. */
const HOSTILE = { ...v1.ARGON, t: 2 ** 31 }

/** One account: a Master Key sealed under a KEK the spies hand back, served under `profile` + `salt`. */
function account(profile: v1.ArgonParams, salt = v1.randomBytes(16)) {
  const id = v1.generateIdentity()
  const mk = v1.randomBytes(32)
  const kek = v1.randomBytes(32)
  const secretKey = v1.randomBytes(32)
  const wrap = {
    wrappedKey: b64std(v1.seal({ purpose: 'master-key' }, kek, mk)),
    kdfParams: b64std(v1.encodeKdfParams(profile, salt)),
  }
  const material = {
    suiteId: 'v1',
    wraps: [
      { method: 'PASSWORD', ...wrap },
      { id: WRAP_ID, method: 'DEVICE', ...wrap },
    ],
    wrappedIdentityPrivateKey: b64std(v1.seal({ purpose: 'identity-priv' }, mk, v1.serializeIdentityPrivate(id))),
  }
  const recovery: RecoveryMaterial = { email: 'a@example.com', suiteId: 'v1', ...wrap, wrappedIdentityPrivateKey: material.wrappedIdentityPrivateKey }
  for (const spy of [unlockKekAsync, deviceKekAsync, recoveryKekAsync]) spy.mockResolvedValue(kek)
  vi.mocked(accountApi).mockReturnValue({
    getAccountKeyMaterial: () => Promise.resolve(material),
  } as unknown as ReturnType<typeof accountApi>)
  return { id, mk, secretKey: b64std(secretKey), recovery }
}

/** Settle a promise into its outcome, so the spy assertion can run FIRST — its failure names the served profile
 *  the derivation was handed, which is the defect; "resolved instead of rejected" would only describe a symptom. */
const settle = (p: Promise<unknown>): Promise<unknown> => p.then((v) => ({ resolved: v }), (e: unknown) => e)

/** The refusal, anchored on its own name — a bare "it threw" is equally produced by an AEAD failure. */
function expectRefused(outcome: unknown) {
  expect(outcome).toBeInstanceOf(Error)
  expect((outcome as Error).message).toBe('kdf_profile_refused')
}

beforeEach(() => {
  clearVault()
  vi.clearAllMocks()
  vi.mocked(device.loadTrust).mockResolvedValue({ cryptoKey: {} as CryptoKey, wrapId: WRAP_ID })
  vi.mocked(device.deviceFactor).mockResolvedValue(new Uint8Array(32).fill(0x44))
})

describe('a served key-derivation profile is checked before anything derives (ADR 0017 rule 5)', () => {
  it('the PASSWORD floor refuses a hostile pass count without deriving', async () => {
    const { secretKey } = account(HOSTILE)
    const outcome = await settle(unlockMasterKey(PASSWORD, secretKey))
    expect(unlockKekAsync).not.toHaveBeenCalled()
    expectRefused(outcome)
    expect(isUnlocked()).toBe(false)
  })

  // One field off the written profile, each way, and the salt length — each on the floor path, each anchored on
  // the refusal's own name. The control is the test below: the same fixture at exactly `v1.ARGON`.
  it.each([
    ['t', { ...v1.ARGON, t: v1.ARGON.t + 1 }, 16], ['t', { ...v1.ARGON, t: v1.ARGON.t - 1 }, 16],
    ['m', { ...v1.ARGON, m: v1.ARGON.m + 1 }, 16], ['m', { ...v1.ARGON, m: v1.ARGON.m - 1 }, 16],
    ['p', { ...v1.ARGON, p: v1.ARGON.p + 1 }, 16], ['p', { ...v1.ARGON, p: v1.ARGON.p - 1 }, 16],
    ['dkLen', { ...v1.ARGON, dkLen: v1.ARGON.dkLen + 1 }, 16], ['dkLen', { ...v1.ARGON, dkLen: v1.ARGON.dkLen - 1 }, 16],
    ['salt', v1.ARGON, 15], ['salt', v1.ARGON, 17], ['salt', v1.ARGON, 0],
  ] as const)('the PASSWORD floor refuses %s off the written profile (%o, %d-byte salt)', async (_, profile, saltLen) => {
    const { secretKey } = account(profile, v1.randomBytes(saltLen))
    const outcome = await settle(unlockMasterKey(PASSWORD, secretKey))
    expect(unlockKekAsync).not.toHaveBeenCalled()
    expectRefused(outcome)
  })

  it('the PASSWORD floor derives once, with the profile it writes — the control', async () => {
    const { mk, secretKey } = account(v1.ARGON)
    await unlockMasterKey(PASSWORD, secretKey)
    expect(unlockKekAsync).toHaveBeenCalledTimes(1)
    expect(unlockKekAsync.mock.calls[0][3]).toEqual(v1.ARGON)
    expect(masterKey()).toEqual(mk)
  })

  it('the DEVICE wrap refuses it too — so the sign-in screen falls back to the floor, blaming nothing', async () => {
    account(HOSTILE)
    const outcome = await settle(unlockWithDevice('a@example.com', PASSWORD))
    expect(deviceKekAsync).not.toHaveBeenCalled()
    expectRefused(outcome)
    expect(isUnlocked()).toBe(false)
  })

  it('the DEVICE wrap derives once under the profile it writes — the control', async () => {
    const { mk } = account(v1.ARGON)
    expect(await unlockWithDevice('a@example.com', PASSWORD)).toBe(true)
    expect(deviceKekAsync).toHaveBeenCalledTimes(1)
    expect(masterKey()).toEqual(mk)
  })

  it('the cold-credential proof refuses it as a failure, never as a rejected credential', async () => {
    const { id, mk, secretKey } = account(HOSTILE)
    setUnlocked(mk, id.priv, 'cold')
    const outcome = await settle(proveColdCredential(PASSWORD, secretKey))
    expect(unlockKekAsync).not.toHaveBeenCalled()
    // Not a ColdCredentialRefused: that one reads "your password or Secret Key didn't match" on both screens.
    expect(outcome).not.toBeInstanceOf(ColdCredentialRefused)
    expectRefused(outcome)
  })

  it('the cold-credential proof passes under the profile it writes — the control', async () => {
    const { id, mk, secretKey } = account(v1.ARGON)
    setUnlocked(mk, id.priv, 'cold')
    expect(await proveColdCredential(PASSWORD, secretKey)).toEqual(mk)
    expect(unlockKekAsync).toHaveBeenCalledTimes(1)
  })

  it('the recovery release refuses it without deriving', async () => {
    const { secretKey, recovery } = account(HOSTILE)
    const outcome = await settle(openWithSecretKey(recovery, secretKey))
    expect(recoveryKekAsync).not.toHaveBeenCalled()
    expectRefused(outcome)
  })

  it('the recovery release opens under the profile it writes — the control', async () => {
    const { mk, secretKey, recovery } = account(v1.ARGON)
    expect((await openWithSecretKey(recovery, secretKey)).masterKey).toEqual(mk)
    expect(recoveryKekAsync).toHaveBeenCalledTimes(1)
  })
})
