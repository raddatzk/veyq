import 'fake-indexeddb/auto'
import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest'
import { IDBFactory } from 'fake-indexeddb'
import { fakeApi } from '../../../test/apiMock'
import type {
  AccountApi,
  AccountDeviceRegisterApi,
  AccountIdentityPinsApi,
  AccountRotationApi,
  DeviceRegister,
  IdentityPins,
  MasterKeyWrap,
} from '../../../api/generated/account'
import type { ScopeMember, ScopeSummary, ScopesApi } from '../../../api/generated/scopes'

// The APIs and the vault are mocked; the scope layer and **the crypto are real** — both wraps, the identity mint
// and the KEK derivation all run for real (the last at a light Argon cost, see the worker mock below), so
// these tests prove that what is posted actually opens with the credential offered, rather than that a stub
// was called.
//
// What carries the weight here is the gate (ADR 0060 Decision 2). It is the reason this feature is not
// net-negative: the attacker it defends against holds the account's Master Key and identity key and could
// otherwise rotate FIRST, locking the legitimate holder out permanently. What they do not hold is the Secret
// Key. So every refusal below is asserted together with "and nothing was posted" — a gate that refuses after
// writing is not a gate.
//
// The pin store and the device register are REAL too (only their endpoints are faked): the rotation re-seals both
// inside its one request (ADR 0060 § Consequences), and what has to be proven about that is that the blobs it
// sends OPEN under the new Master Key at the version they will be stored at — which a mocked re-seal cannot say.
vi.mock('../../../api/client', () => ({
  accountApi: vi.fn(),
  accountRotationApi: vi.fn(),
  accountIdentityPinsApi: vi.fn(),
  accountDeviceRegisterApi: vi.fn(),
  scopesApi: vi.fn(),
  tasksApi: vi.fn(),
}))
vi.mock('../../../app/sessionRefresh', () => ({ withRefresh: (f: () => unknown) => f() }))
vi.mock('../../../app/vault', () => ({
  masterKey: vi.fn(),
  setUnlocked: vi.fn(),
  unlockOrigin: vi.fn(),
  identityPrivate: vi.fn(),
}))
vi.mock('../../../core/favorites/favorites', () => ({ resealFavoritesUnderNewMasterKey: vi.fn() }))
vi.mock('../../memory/itemFavorites', () => ({ retagItemMarksUnderNewMasterKey: vi.fn() }))
vi.mock('../../spaces/members', () => ({ listScopeMembers: vi.fn() }))

// The two Argon-bearing KEK derivations run for REAL but at a light cost. They stay **input-sensitive**, which
// is what every refusal below depends on — a stub returning a constant would make "a wrong Secret Key is
// refused" pass against a gate that never looked. Their real-byte correctness at production cost is
// v1.conformance's job (v1 is stryker.mutate.json's mutate[0]); what this file pins is the wiring: which
// secret reaches which wrap. Real ARGON here would be 32 MiB × 3 per test, which the suite should not pay.
vi.mock('@veyq/crypto/argonWorker', async () => {
  const real = await vi.importActual<typeof import('@veyq/crypto/v1')>('@veyq/crypto/v1')
  const LIGHT = { t: 1, m: 64, p: 1, dkLen: 32 }
  return {
    unlockKekAsync: async (secretKey: Uint8Array, password: string, salt: Uint8Array) =>
      real.kekPasswordSecret(secretKey, password, salt, LIGHT),
    recoveryKekAsync: async (secretKey: Uint8Array, salt: Uint8Array) =>
      real.kekRecovery(secretKey, salt, LIGHT),
  }
})

import { rotateKeys, RotationRefused } from './rotateKeys'
import {
  accountApi,
  accountDeviceRegisterApi,
  accountIdentityPinsApi,
  accountRotationApi,
  scopesApi,
} from '../../../api/client'
import { identityPrivate, masterKey, setUnlocked, unlockOrigin } from '../../../app/vault'
import { getPin } from '../identityPins'
import { readRegisterView } from '../deviceRegister'
import { resealFavoritesUnderNewMasterKey } from '../../../core/favorites/favorites'
import { retagItemMarksUnderNewMasterKey } from '../../memory/itemFavorites'
import { httpError } from '../../../test/httpError'
import { registry } from '../../../core/applications/registry'
import { memoriesLifecycle } from '../../memory/lifecycle'
import { listScopeMembers } from '../../spaces/members'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { grantSignatureVerifies, signGrant, tagAdmission } from '../../spaces/grant'
import { raiseMarks, readMarks } from '../trustMarks'

// This file runs in the node environment (fake-indexeddb), where src/test/setup.ts registers no application. The REAL
// Memories hooks go into the REAL registry, so the re-seal tests below prove rotateKeys reaches the favorites modules
// (mocked above, at the module that seals) THROUGH the registry rather than by an import of its own.
beforeAll(() => registry.registerLifecycle('memories', memoriesLifecycle))

const PASSWORD = 'correct horse battery staple'
const SECRET_KEY = v1.randomBytes(32)
const SECRET_KEY_B64 = b64std(SECRET_KEY)
/** The account's *current* Master Key — what the stored PASSWORD wrap opens and what the vault is holding. */
const MK = v1.randomBytes(32)
/** The account's *current* identity — the one its scope grants are wrapped to, and the vault still holds. */
const OLD_ID = v1.generateIdentity()
const oldPriv = () => ({ kemX: OLD_ID.priv.kemX.slice(), mlkemSeed: OLD_ID.priv.mlkemSeed.slice(), sign: OLD_ID.priv.sign.slice() })
/** Two scopes the caller holds — canonical ids, since every grant and chain link is sealed bound to its scope. */
const SCOPE_PERSONAL = '5c09e000-0000-4000-8000-0000000000e1'
const SCOPE_SHARED = '5c09e000-0000-4000-8000-0000000000e2'
/** The caller's account id, as its own key material names it. */
const ME = '0e000000-0000-4000-8000-0000000000e9'
/** A whole scope member; a test passes the identity it is about. */
const member = (o: Partial<ScopeMember> & Pick<ScopeMember, 'userId' | 'identityPublicKey'>): ScopeMember => ({
  username: o.userId, avatarVersion: 0, role: 'OWNER', hasGrant: true, ...o,
})
const OLD_PUB = b64std(v1.serializeIdentityPublic(OLD_ID.pub))
/** A scope the caller holds at epoch 0, its grant wrapping `e0` to the old identity — self-signed by it (ADR 0028
 *  Decision 6), since the caller birthed it. */
const heldAt0 = (id: string, e0: Uint8Array): ScopeSummary => {
  const wrappedGrant = b64std(v1.sealTo({ purpose: 'scope-grant', scopeId: id, epoch: 0 }, v1.hybridPublic(oldPriv()), e0))
  return {
    id, kind: 'PERSONAL', role: 'OWNER', tenantId: 't', nameEpoch: 0, iconEpoch: 0, currentEpoch: 0, grantedEpoch: 0, wrappedGrant,
    granterUserId: ME, granterIdentityPublicKey: OLD_PUB,
    grantSignature: signGrant(OLD_ID.priv.sign, { scopeId: id, kind: 'PERSONAL', epoch: 0 }, { userId: ME, identityPublicKeyB64: OLD_PUB }, wrappedGrant),
  }
}
/** The light cost the mocked worker above uses; the stored floor's KEK must be built with the same. Its WIRE
 *  profile stays `v1.ARGON` — the client refuses any other before deriving (ADR 0017 rule 5), and the mock above
 *  ignores the params it is handed. */
const KDF = { t: 1, m: 64, p: 1, dkLen: 32 }

/** The stored PASSWORD wrap, produced exactly as account birth produces it. */
function storedFloor(masterKeyBytes = MK, secret = SECRET_KEY, password = PASSWORD): MasterKeyWrap {
  const salt = v1.randomBytes(16)
  const kek = v1.kekPasswordSecret(secret, password, salt, KDF)
  return {
    id: 'w1',
    method: 'PASSWORD',
    wrappedKey: b64std(v1.seal({ purpose: 'master-key' }, kek, masterKeyBytes)),
    kdfParams: b64std(v1.encodeKdfParams(v1.ARGON, salt)),
  }
}

/** The two sealed account documents as fixtures seal them — under the CURRENT Master Key, bound to their version. */
const pinsAt = (version: number) => ({ purpose: 'identity-pins', version }) as const
const registerAt = (version: number) => ({ purpose: 'device-register', version }) as const
const enc = new TextEncoder()
const dec = new TextDecoder()
const PINNED = { u1: { key: 'a2V5', state: 'pinned', firstSeenAt: 1 } }
const sealedPins = (version: number, pins: Record<string, unknown> = PINNED, mk = MK) =>
  ({ blob: b64std(v1.seal(pinsAt(version), mk, enc.encode(JSON.stringify({ pins })))), version })
const REGISTERED = { devices: { d1: { label: 'Work laptop', lastSeenAt: 1 } }, families: { fam1: 'd1' }, wraps: {} }
const sealedRegister = (version: number, reg: Record<string, unknown> = REGISTERED, mk = MK) =>
  ({ blob: b64std(v1.seal(registerAt(version), mk, enc.encode(JSON.stringify(reg)))), version })

function fakeServer(
  opts: {
    wraps?: MasterKeyWrap[]
    sole?: string[]
    queued?: string[]
    held?: ScopeSummary[]
    pins?: IdentityPins
    register?: DeviceRegister
    /** How many rotate calls answer `409 stale_version` first — each as if another device had just written the
     *  pin store (its version moves one up, still sealed under the current key). */
    staleRotations?: number
  } = {},
) {
  const getAccountKeyMaterial = vi.fn<AccountApi['getAccountKeyMaterial']>(async () => ({
    userId: ME,
    suiteId: 'v1',
    wraps: opts.wraps ?? [storedFloor()],
    wrappedIdentityPrivateKey: 'aWdub3JlZA==',
  }))
  fakeApi(accountApi, { getAccountKeyMaterial })

  // The two documents as the server holds them, with the stores' real compare-and-set: a write whose version is
  // not the stored one is `409 stale_version`. Their own PUTs answer 500 — the outage a re-seal after the publish
  // used to swallow — so a rotation that still wrote them on their own would leave them under the retired key.
  const pins: IdentityPins = { ...(opts.pins ?? { blob: null, version: 0 }) }
  const register: DeviceRegister = { ...(opts.register ?? { blob: null, version: 0 }) }
  const putIdentityPins = vi.fn<AccountIdentityPinsApi['putIdentityPins']>(async () => {
    throw httpError(500)
  })
  fakeApi(accountIdentityPinsApi, { getIdentityPins: async () => ({ ...pins }), putIdentityPins })
  const putDeviceRegister = vi.fn<AccountDeviceRegisterApi['putDeviceRegister']>(async () => {
    throw httpError(500)
  })
  fakeApi(accountDeviceRegisterApi, { getDeviceRegister: async () => ({ ...register }), putDeviceRegister })

  let staleLeft = opts.staleRotations ?? 0
  const rotateAccountKeys = vi.fn<AccountRotationApi['rotateAccountKeys']>(async (body) => {
    const req = body.rotateAccountKeysRequest
    if (staleLeft > 0) {
      staleLeft -= 1
      Object.assign(pins, sealedPins(pins.version + 1)) // another device wrote meanwhile
      throw httpError(409, 'stale_version')
    }
    // One transaction: both documents or nothing (the backend's RotationSliceTest owns that half). A request
    // without them is applied as the pre-ADR-0060-amendment server did — which is what lets the stranding test
    // below run red against a client that still re-sealed after the publish.
    if (req.identityPins && req.identityPins.version !== pins.version) throw httpError(409, 'stale_version')
    if (req.deviceRegister && req.deviceRegister.version !== register.version) throw httpError(409, 'stale_version')
    if (req.identityPins) Object.assign(pins, { blob: req.identityPins.blob, version: pins.version + 1 })
    if (req.deviceRegister) Object.assign(register, { blob: req.deviceRegister.blob, version: register.version + 1 })
    return { soleMemberScopeIds: opts.sole ?? [], queuedScopeIds: opts.queued ?? [] }
  })
  fakeApi(accountRotationApi, { rotateAccountKeys })

  const rotateScope = vi.fn<ScopesApi['rotateScope']>(async (body) => ({ epoch: body.rotateScopeRequest.epoch }))
  // The caller's own grants, wrapped to the OLD identity the vault still holds while the sole scopes re-key.
  fakeApi(scopesApi, { rotateScope, listMyScopes: async () => opts.held ?? [] })

  return { getAccountKeyMaterial, rotateAccountKeys, rotateScope, pins, register, putIdentityPins, putDeviceRegister }
}

beforeEach(() => {
  vi.clearAllMocks()
  globalThis.indexedDB = new IDBFactory() // this device's trust-state marks (ADR 0028 Decision 7), fresh per test
  vi.mocked(unlockOrigin).mockReturnValue('cold')
  vi.mocked(masterKey).mockImplementation(() => MK.slice())
  vi.mocked(identityPrivate).mockImplementation(() => oldPriv())
  vi.mocked(listScopeMembers).mockResolvedValue([])
  vi.mocked(resealFavoritesUnderNewMasterKey).mockResolvedValue(undefined)
  vi.mocked(retagItemMarksUnderNewMasterKey).mockResolvedValue(undefined)
})

describe('the lifecycle gate: a rotation needs an application that carries the master-key hook', () => {
  // The registry is write-once and filled by the real composition root (src/test/setup.ts), so the "nothing
  // registered" build is simulated by making ITS gate answer as an empty registry does (proven separately in
  // registry.test.ts). This test pins the other half: rotateKeys asks BEFORE it proves, mints or posts anything.
  it('refuses to start when no application registered the hook, and touches nothing', async () => {
    const srv = fakeServer()
    const gate = vi.spyOn(registry, 'assertRotationHooks').mockImplementation(() => {
      throw new Error('no application registered an onMasterKeyRotated hook; refusing to rotate keys (fail closed)')
    })
    try {
      await expect(rotateKeys(PASSWORD, SECRET_KEY_B64)).rejects.toThrow(/refusing to rotate/)
      expect(srv.getAccountKeyMaterial).not.toHaveBeenCalled()
      expect(srv.rotateAccountKeys).not.toHaveBeenCalled()
      expect(setUnlocked).not.toHaveBeenCalled()
      expect(resealFavoritesUnderNewMasterKey).not.toHaveBeenCalled()
    } finally {
      gate.mockRestore()
    }
  })

  it('live control: with the real registry and the real Memories hooks the same call rotates and re-seals', async () => {
    const srv = fakeServer()
    await expect(rotateKeys(PASSWORD, SECRET_KEY_B64)).resolves.toBeDefined()
    expect(srv.rotateAccountKeys).toHaveBeenCalled()
    expect(resealFavoritesUnderNewMasterKey).toHaveBeenCalledTimes(1)
    expect(retagItemMarksUnderNewMasterKey).toHaveBeenCalledTimes(1)
  })
})

describe('the cold-credential gate (ADR 0060 Decision 2)', () => {
  it.each(['device', 'none'] as const)(
    'refuses a %s-opened session that cannot produce the Secret Key, and posts nothing',
    async (origin) => {
      // The attacker's exact position: they hold a DEVICE_KEY wrap (or a Master Key handed over by a phished
      // grant, which opened no wrap at all — `none`, the value a boolean would have had to mislabel) and no
      // Emergency Kit. If they could rotate they would lock the legitimate holder out permanently.
      vi.mocked(unlockOrigin).mockReturnValue(origin)
      const srv = fakeServer()

      await expect(rotateKeys(PASSWORD, b64std(v1.randomBytes(32)))).rejects.toMatchObject({
        reason: 'credentialRejected',
      })
      expect(srv.rotateAccountKeys).not.toHaveBeenCalled()
    },
  )

  it.each(['device', 'none'] as const)(
    'lets a %s-opened session rotate once it proves the cold credential in place, and marks it cold',
    async (origin) => {
      // The control the refusals above need, and E1 as written: how the vault was opened does not gate this —
      // supplying password + Secret Key IS the cold proof. Turning the session away would add nothing over the
      // proof and would send somebody already holding their Emergency Kit back to the sign-in screen.
      vi.mocked(unlockOrigin).mockReturnValue(origin)
      const srv = fakeServer()

      await expect(rotateKeys(PASSWORD, SECRET_KEY_B64)).resolves.toBeDefined()
      expect(srv.rotateAccountKeys).toHaveBeenCalled()
      expect(vi.mocked(setUnlocked).mock.calls[0][2]).toBe('cold')
    },
  )

  it('refuses a wrong Secret Key even on a cold unlock, and posts nothing', async () => {
    // The live half of the gate: the vault says cold, but what was typed does not open the stored floor.
    const srv = fakeServer()
    await expect(rotateKeys(PASSWORD, b64std(v1.randomBytes(32)))).rejects.toMatchObject({
      reason: 'credentialRejected',
    })
    expect(srv.rotateAccountKeys).not.toHaveBeenCalled()
  })

  it('refuses a wrong password even with the right Secret Key, and posts nothing', async () => {
    const srv = fakeServer()
    await expect(rotateKeys('not the password', SECRET_KEY_B64)).rejects.toMatchObject({
      reason: 'credentialRejected',
    })
    expect(srv.rotateAccountKeys).not.toHaveBeenCalled()
  })

  it('refuses when the floor opens a DIFFERENT Master Key than this session holds', async () => {
    // The control the two negatives above cannot give: the credential is entirely correct for the wrap served,
    // and it is still refused — because the wrap was rewritten under us and rotating from a Master Key the
    // account no longer has would publish an identity nothing can open.
    const otherMk = v1.randomBytes(32)
    const srv = fakeServer({ wraps: [storedFloor(otherMk)] })

    await expect(rotateKeys(PASSWORD, SECRET_KEY_B64)).rejects.toMatchObject({ reason: 'credentialRejected' })
    expect(srv.rotateAccountKeys).not.toHaveBeenCalled()
  })

  it('refuses when the account has no PASSWORD floor to prove against', async () => {
    const srv = fakeServer({ wraps: [] })
    await expect(rotateKeys(PASSWORD, SECRET_KEY_B64)).rejects.toBeInstanceOf(RotationRefused)
    expect(srv.rotateAccountKeys).not.toHaveBeenCalled()
  })

  it('refuses a locked vault — there is no current Master Key to prove the credential against', async () => {
    vi.mocked(masterKey).mockReturnValue(null)
    const srv = fakeServer()
    await expect(rotateKeys(PASSWORD, SECRET_KEY_B64)).rejects.toMatchObject({ reason: 'vaultLocked' })
    expect(srv.rotateAccountKeys).not.toHaveBeenCalled()
  })
})

describe('what a rotation posts', () => {
  it('posts both cold wraps opening the NEW Master Key under the SAME secrets, with fresh salts', async () => {
    const srv = fakeServer()
    await rotateKeys(PASSWORD, SECRET_KEY_B64)

    const body = srv.rotateAccountKeys.mock.calls[0][0].rotateAccountKeysRequest

    // The floor still takes password + Secret Key — the Emergency Kit survives — but now yields a key that is
    // NOT the one the compromise handed over.
    const pw = v1.decodeKdfParams(fromB64std(body.passwordWrap.kdfParams))
    const newMk = v1.open(
      { purpose: 'master-key' },
      // KDF at the mocked worker's cost — the params ON the wrap are production ARGON, which is what the
      // client genuinely writes; only the derivation is light here.
      v1.kekPasswordSecret(SECRET_KEY, PASSWORD, pw.salt, KDF),
      fromB64std(body.passwordWrap.wrappedKey),
    )
    expect(b64std(newMk)).not.toBe(b64std(MK))

    // The recovery wrap must open the SAME new key with the Secret Key alone. If it did not, recovery would
    // restore the old Master Key and hand the account straight back.
    const rec = v1.decodeKdfParams(fromB64std(body.recoveryWrap.kdfParams))
    const fromRecovery = v1.open({ purpose: 'master-key' }, v1.kekRecovery(SECRET_KEY, rec.salt, KDF), fromB64std(body.recoveryWrap.wrappedKey))
    expect(b64std(fromRecovery)).toBe(b64std(newMk))

    // Fresh salt on both: re-using one leaks the rewrite to anyone diffing two dumps, and the recovery wrap's
    // stored salt is not even readable from here (ADR 0054 withholds it), so fresh is the only shape available.
    expect(body.passwordWrap.kdfParams).not.toBe(body.recoveryWrap.kdfParams)

    // The identity private key rides the new Master Key.
    expect(() =>
      v1.open({ purpose: 'identity-priv' }, newMk, fromB64std(body.wrappedIdentityPrivateKey)),
    ).not.toThrow()

    // The server's check, run here (ADR 0060 Decision 2): the statement rebuilt from what was SENT, from the
    // identity the vault held, verifies under the proof key the Secret Key derives — and under no other.
    const wrap = (w: { wrappedKey: string; kdfParams: string }) => ({
      wrappedKey: fromB64std(w.wrappedKey),
      kdfParams: fromB64std(w.kdfParams),
    })
    const stmt = v1.rotationProofStatement({
      currentIdentityPublicKey: v1.serializeIdentityPublic(OLD_ID.pub),
      identityPublicKey: fromB64std(body.identityPublicKey),
      wrappedIdentityPrivateKey: fromB64std(body.wrappedIdentityPrivateKey),
      passwordWrap: wrap(body.passwordWrap),
      recoveryWrap: wrap(body.recoveryWrap),
    })
    const sig = fromB64std(body.secretKeyProofSignature)
    expect(v1.authVerify(v1.deriveSecretKeyProofKeypair(SECRET_KEY).publicKey, stmt, sig)).toBe(true)
    expect(v1.authVerify(v1.deriveSecretKeyProofKeypair(v1.randomBytes(32)).publicKey, stmt, sig)).toBe(false)
  })

  it('carries no mode, no identityOnly and no scope subset — there is one rotation', async () => {
    // ADR 0060 Decision 1, asserted from the client side too: `validate_rotation_shape.py` pins the schema,
    // this pins what the one producer actually sends. A field the contract forbids could still be sent.
    const srv = fakeServer()
    await rotateKeys(PASSWORD, SECRET_KEY_B64)

    expect(Object.keys(srv.rotateAccountKeys.mock.calls[0][0].rotateAccountKeysRequest).sort()).toEqual([
      'deviceRegister',
      'identityPins',
      'identityPublicKey',
      'passwordWrap',
      'recoveryWrap',
      'secretKeyProofSignature',
      'wrappedIdentityPrivateKey',
    ])
  })

  it('never sends the password or the Secret Key', async () => {
    const srv = fakeServer()
    await rotateKeys(PASSWORD, SECRET_KEY_B64)
    const wire = JSON.stringify(srv.rotateAccountKeys.mock.calls[0][0])
    expect(wire).not.toContain(PASSWORD)
    expect(wire).not.toContain(SECRET_KEY_B64)
  })
})

describe('what a rotation leaves behind', () => {
  it('re-keys nothing in a queued scope and synthesises no status for it', async () => {
    // ADR 0060 Decision 6: between rotating and being re-admitted the caller is OUTSIDE every shared scope, and
    // how its covering rotation stands is the SERVER's to say, re-readable at any time (listQueuedRotations) — a
    // `waiting` made up here from the rotation's own response would never be read again, and would outlive a
    // dead-letter the server knows about.
    const srv = fakeServer({ queued: [SCOPE_SHARED] })

    const result = await rotateKeys(PASSWORD, SECRET_KEY_B64)

    expect(result.scopes).toEqual([])
    expect(srv.rotateScope).not.toHaveBeenCalled()
  })

  it('re-keys a sole-member scope to the new identity and reports it secured', async () => {
    const e0 = v1.randomBytes(32)
    const srv = fakeServer({ sole: [SCOPE_PERSONAL], held: [heldAt0(SCOPE_PERSONAL, e0)] })
    // The caller's own membership carries the admission it self-tagged at birth — a rotation carries it forward.
    const own = tagAdmission(e0, { scopeId: SCOPE_PERSONAL, kind: 'PERSONAL' }, 0, ME)
    vi.mocked(listScopeMembers).mockResolvedValue([member({ userId: ME, identityPublicKey: 'ignored', ...own })])

    const result = await rotateKeys(PASSWORD, SECRET_KEY_B64)

    expect(result.scopes).toEqual([{ scopeId: SCOPE_PERSONAL, status: 'secured' }])
    const grants = srv.rotateScope.mock.calls[0][0].rotateScopeRequest.grants
    expect(grants).toHaveLength(1)
    expect(grants[0].userId).toBe(ME)
    // The grant opens with the NEW identity private key the vault was just handed — which is the whole point:
    // the old identity, the one the compromise holds, no longer reads this scope's next epoch.
    const swapped = vi.mocked(setUnlocked).mock.calls[0]
    const priv = swapped[1]
    // Bound to the scope it re-keys, at the epoch the rotation creates (ADR 0072).
    const opened = v1.openAs(
      { purpose: 'scope-grant', scopeId: SCOPE_PERSONAL, epoch: 1 },
      v1.hybridSecret(priv),
      v1.hybridPublic(priv),
      fromB64std(grants[0].wrappedGrant),
    )
    expect(opened).toHaveLength(32)
    // Signed by the NEW identity (ADR 0028 Decision 6): it is opened later under that one, and must verify there —
    // a signature by the old, compromised identity would not.
    const newPub = v1.identityPublicFromPrivate(priv)
    const to = { userId: ME, identityPublicKeyB64: b64std(v1.serializeIdentityPublic(newPub)) }
    const next = { scopeId: SCOPE_PERSONAL, kind: 'PERSONAL' as const, epoch: 1 }
    expect(grantSignatureVerifies(newPub.sign, next, to, grants[0].wrappedGrant, grants[0].grantSignature)).toBe(true)
    expect(grantSignatureVerifies(OLD_ID.pub.sign, next, to, grants[0].wrappedGrant, grants[0].grantSignature)).toBe(false)
    // …and its own admission is carried into the new epoch.
    expect(srv.rotateScope.mock.calls[0][0].rotateScopeRequest.admissions.map((a: { admissionId: string }) => a.admissionId)).toEqual([own.admissionId])
    // It states the epoch it creates — the old grant's 0, plus one — and links that new epoch back to E_0, so the
    // new identity still reads everything already written.
    const request = srv.rotateScope.mock.calls[0][0].rotateScopeRequest
    expect(request.epoch).toBe(1)
    const link = { purpose: 'scope-epoch', scopeId: SCOPE_PERSONAL, epoch: 1 } as const
    expect(Array.from(v1.open(link, opened, fromB64std(request.wrappedPrev)))).toEqual(Array.from(e0))
    // And the vault is told this session is still cold — it was proven cold moments ago, so a rotation must
    // not leave the person unable to rotate again.
    expect(swapped[2]).toBe('cold')
  })

  it('refuses to re-key a "sole" scope that turns out to have another member', async () => {
    // The server split it as sole-member; if a member appeared in between, re-granting only ourselves would
    // lock that member out. The honest answer is to leave the scope alone and say it was not secured.
    const srv = fakeServer({ sole: [SCOPE_PERSONAL], held: [heldAt0(SCOPE_PERSONAL, v1.randomBytes(32))] })
    vi.mocked(listScopeMembers).mockResolvedValue([
      member({ userId: 'me', identityPublicKey: 'x' }),
      member({ userId: 'someone-else', identityPublicKey: 'y' }),
    ])

    const result = await rotateKeys(PASSWORD, SECRET_KEY_B64)

    expect(result.scopes).toEqual([{ scopeId: SCOPE_PERSONAL, status: 'failed' }])
    expect(srv.rotateScope).not.toHaveBeenCalled()
  })

  /**
   * Memory pins joined the Master-Key-sealed blobs when they moved off `K_SC` (ADR 0068), so a rotation has to
   * carry them or it silently empties every user's Favorites — a degradation nobody would attribute to the
   * rotation, because an unreadable pin blob decodes to an empty set rather than an error.
   *
   * It asserts the **scope list**, not just that the call happened: the pin set is stored per scope, and the
   * two result lists partition the caller's scopes, so re-sealing only one of them loses the other's pins.
   */
  it('carries the Memory pins of EVERY scope across the new Master Key', async () => {
    fakeServer({ sole: [SCOPE_PERSONAL], queued: [SCOPE_SHARED], held: [heldAt0(SCOPE_PERSONAL, v1.randomBytes(32))] })
    vi.mocked(listScopeMembers).mockResolvedValue([])

    await rotateKeys(PASSWORD, SECRET_KEY_B64)

    expect(resealFavoritesUnderNewMasterKey).toHaveBeenCalledTimes(1)
    const scopeIds = vi.mocked(resealFavoritesUnderNewMasterKey).mock.calls[0][2]
    expect([...scopeIds].sort()).toEqual([SCOPE_PERSONAL, SCOPE_SHARED])
  })

  it('carries this device\u2019s trust-state marks to the new Master Key — the epoch its own re-key made included', async () => {
    // The marks are keyed by the Master Key, so a rotation that did not carry them would leave this device with
    // none: the next served pin store, of any version, would be taken as the first one (ADR 0028 Decision 7).
    await raiseMarks(MK, { pins: 3, register: 2, epochs: { [SCOPE_SHARED]: 4 } })
    fakeServer({
      sole: [SCOPE_PERSONAL],
      held: [heldAt0(SCOPE_PERSONAL, v1.randomBytes(32))],
      pins: sealedPins(3),
      register: sealedRegister(2),
    })
    vi.mocked(listScopeMembers).mockResolvedValue([member({ userId: ME, identityPublicKey: 'ignored' })])

    await rotateKeys(PASSWORD, SECRET_KEY_B64)

    // The two documents' marks are the versions the rotation itself stored them at (served + 1), not merely the
    // ones carried across: this device wrote them, so it has held them.
    const newMk = vi.mocked(setUnlocked).mock.calls[0][0]
    expect(await readMarks(newMk)).toEqual({ pins: 4, register: 3, epochs: { [SCOPE_SHARED]: 4, [SCOPE_PERSONAL]: 1 } })
  })

  /**
   * **B6, at the rotation.** The item marks' self-tag key derives from the Master Key, so a rotation that did not
   * re-tag would empty *only mine* on every Memory, and the first toggle on each would replace the old set. The rider
   * must get the OLD key (it licenses the re-tag) and the NEW one the vault ends up holding — copied at call time,
   * because the rotation zeroes both buffers once it is done. It runs before the vault swap: it opens each scope's
   * grant with the old identity.
   */
  it('re-tags the caller’s item marks from the old Master Key to the one the vault ends up holding', async () => {
    let seen: [Uint8Array, Uint8Array] | null = null
    let swappedBefore = true
    vi.mocked(retagItemMarksUnderNewMasterKey).mockImplementationOnce(async (o, n) => {
      seen = [o.slice(), n.slice()]
      swappedBefore = vi.mocked(setUnlocked).mock.calls.length > 0
    })
    fakeServer({ queued: ['s1'] })

    await rotateKeys(PASSWORD, SECRET_KEY_B64)

    expect(retagItemMarksUnderNewMasterKey).toHaveBeenCalledTimes(1)
    expect(seen).not.toBeNull()
    expect(b64std(seen![0])).toBe(b64std(MK))
    expect(b64std(seen![1])).toBe(b64std(vi.mocked(setUnlocked).mock.calls[0][0]))
    expect(swappedBefore).toBe(false)
  })

  it('does not fail a published rotation because the favorites or the item marks could not be carried across', async () => {
    // The riders re-sealed after the publish (out of the request on purpose — per scope, and each degrades rather
    // than refuses by ADR 0068): an unpinned Memory is still there, and an unverified item mark reads as someone
    // else's set. The pin store and the register are no longer in this class — see the describe below.
    vi.mocked(resealFavoritesUnderNewMasterKey).mockRejectedValueOnce(new Error('offline'))
    vi.mocked(retagItemMarksUnderNewMasterKey).mockRejectedValueOnce(new Error('offline'))
    fakeServer({ queued: ['s1'] })

    await expect(rotateKeys(PASSWORD, SECRET_KEY_B64)).resolves.toBeDefined()
    expect(vi.mocked(setUnlocked)).toHaveBeenCalled()
  })
})

describe('the pin store and the register ride in the publish (ADR 0060 § Consequences, inbox #192)', () => {
  /** The rotation's one request — the `n`th call's body. */
  const sent = (srv: ReturnType<typeof fakeServer>, n = 0) => srv.rotateAccountKeys.mock.calls[n][0].rotateAccountKeysRequest
  const newMasterKey = () => vi.mocked(setUnlocked).mock.calls[0][0]

  it('leaves neither document under the retired key, even when their own endpoints are down', async () => {
    // The live defect: the rotation used to re-seal both AFTER the publish, best-effort, each in its own PUT — and
    // a PUT that failed (500 here) was swallowed, leaving the document sealed under a Master Key the account no
    // longer has. Every device then refuses it as unreadable, forever. The lever is the served `{ blob, version }`.
    const srv = fakeServer({ pins: sealedPins(3), register: sealedRegister(2) })

    await rotateKeys(PASSWORD, SECRET_KEY_B64)

    // The vault has switched; read both back the way the app does.
    vi.mocked(masterKey).mockImplementation(() => newMasterKey().slice())
    expect(await getPin('u1')).toEqual(PINNED.u1)
    expect((await readRegisterView(['fam1'], null, 'me')).names).toEqual({ fam1: 'Work laptop' })

    // Asserted by OPENING what was sent, not by its presence: under MK', at the version it is stored at.
    const req = sent(srv)
    expect(req.identityPins.version).toBe(3)
    const pinsPlain = JSON.parse(dec.decode(v1.open(pinsAt(4), newMasterKey(), fromB64std(req.identityPins.blob))))
    expect(pinsPlain).toEqual({ pins: PINNED })
    expect(req.deviceRegister.version).toBe(2)
    const regPlain = JSON.parse(dec.decode(v1.open(registerAt(3), newMasterKey(), fromB64std(req.deviceRegister.blob))))
    expect(regPlain).toEqual(REGISTERED)
    expect(srv.putIdentityPins).not.toHaveBeenCalled()
    expect(srv.putDeviceRegister).not.toHaveBeenCalled()
  })

  it('an account that never wrote either document sends both empty, sealed for version 1 against 0', async () => {
    const srv = fakeServer()
    await rotateKeys(PASSWORD, SECRET_KEY_B64)
    const req = sent(srv)
    expect(req.identityPins.version).toBe(0)
    expect(JSON.parse(dec.decode(v1.open(pinsAt(1), newMasterKey(), fromB64std(req.identityPins.blob))))).toEqual({ pins: {} })
    expect(req.deviceRegister.version).toBe(0)
    expect(JSON.parse(dec.decode(v1.open(registerAt(1), newMasterKey(), fromB64std(req.deviceRegister.blob))))).toEqual({
      devices: {},
      families: {},
      wraps: {},
    })
  })

  it('a stale document fails the attempt, not the rotation: it re-reads, re-seals and publishes once', async () => {
    // Another device wrote the pin store twice while this one was rotating. Each 409 means nothing was written, so
    // the rotation re-reads, re-seals against the version it now reads, and resubmits the SAME new keys under the
    // SAME signature — the documents are not part of what the Secret-Key proof signs.
    const srv = fakeServer({ pins: sealedPins(3), register: sealedRegister(2), staleRotations: 2 })

    await rotateKeys(PASSWORD, SECRET_KEY_B64)

    expect(srv.rotateAccountKeys).toHaveBeenCalledTimes(3)
    expect(sent(srv, 2).identityPins.version).toBe(5)
    expect(() => v1.open(pinsAt(6), newMasterKey(), fromB64std(sent(srv, 2).identityPins.blob))).not.toThrow()
    expect(sent(srv, 2).identityPublicKey).toBe(sent(srv, 0).identityPublicKey)
    expect(sent(srv, 2).secretKeyProofSignature).toBe(sent(srv, 0).secretKeyProofSignature)
    expect(srv.pins.version).toBe(6)
    expect(vi.mocked(setUnlocked)).toHaveBeenCalledTimes(1)
  })

  it('control: a first attempt that lands is the only call', async () => {
    const srv = fakeServer({ pins: sealedPins(3), register: sealedRegister(2) })
    await rotateKeys(PASSWORD, SECRET_KEY_B64)
    expect(srv.rotateAccountKeys).toHaveBeenCalledTimes(1)
  })

  it('past the bound it rejects — and the vault still holds the old key, because nothing was published', async () => {
    const srv = fakeServer({ pins: sealedPins(3), register: sealedRegister(2), staleRotations: 99 })

    await expect(rotateKeys(PASSWORD, SECRET_KEY_B64)).rejects.toMatchObject({ message: 'http 409 stale_version' })

    expect(srv.rotateAccountKeys).toHaveBeenCalledTimes(5)
    expect(vi.mocked(setUnlocked)).not.toHaveBeenCalled()
  })

  it.each([
    ['pins', 'a pin store below the mark', { pins: 5 }, () => ({ pins: sealedPins(4) })],
    ['register', 'a register below the mark', { register: 5 }, () => ({ register: sealedRegister(4) })],
    ['pins', 'a pin store that does not open', {}, () => ({ pins: sealedPins(3, PINNED, v1.randomBytes(32)) })],
  ] as const)('%s: %s is refused before anything is written — no laundering into the new key', async (_d, _n, marks, served) => {
    // A rotation that re-sealed a rolled-back document under MK' would make the rollback its own, and one that
    // re-sealed an unreadable one as empty would be the wipe ADR 0028 Decision 7 refuses. So both are opened
    // through the marks under the OLD key, and a refusal there is a refusal of the rotation (uc §E8): no publish,
    // no document write. The negative anchors on the recorded writes, not on "it threw".
    await raiseMarks(MK, marks)
    const srv = fakeServer(served())

    await expect(rotateKeys(PASSWORD, SECRET_KEY_B64)).rejects.toMatchObject({ reason: 'trustStateRefused' })

    expect(srv.rotateAccountKeys).not.toHaveBeenCalled()
    expect(srv.putIdentityPins).not.toHaveBeenCalled()
    expect(srv.putDeviceRegister).not.toHaveBeenCalled()
    expect(vi.mocked(setUnlocked)).not.toHaveBeenCalled()
  })

  it('control: served AT the mark, the rotation proceeds', async () => {
    await raiseMarks(MK, { pins: 5, register: 5 })
    const srv = fakeServer({ pins: sealedPins(5), register: sealedRegister(5) })
    await expect(rotateKeys(PASSWORD, SECRET_KEY_B64)).resolves.toBeDefined()
    expect(srv.rotateAccountKeys).toHaveBeenCalledTimes(1)
  })
})
