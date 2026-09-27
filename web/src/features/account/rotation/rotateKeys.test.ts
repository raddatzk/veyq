import { describe, it, expect, vi, beforeEach } from 'vitest'

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
vi.mock('../../../api/client', () => ({
  accountApi: vi.fn(),
  accountRotationApi: vi.fn(),
  scopesApi: vi.fn(),
}))
vi.mock('../../../app/sessionRefresh', () => ({ withRefresh: (f: () => unknown) => f() }))
vi.mock('../../../app/vault', () => ({
  masterKey: vi.fn(),
  setUnlocked: vi.fn(),
  unlockOrigin: vi.fn(),
  identityPrivate: vi.fn(),
}))
vi.mock('../identityPins', () => ({ resealPinsUnderNewMasterKey: vi.fn(async () => {}) }))
vi.mock('../deviceRegister', () => ({ resealRegisterUnderNewMasterKey: vi.fn(async () => {}) }))
vi.mock('../../memory/favorites', () => ({ resealFavoritesUnderNewMasterKey: vi.fn(async () => {}) }))
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
import { accountApi, accountRotationApi, scopesApi } from '../../../api/client'
import { identityPrivate, masterKey, setUnlocked, unlockOrigin } from '../../../app/vault'
import { resealPinsUnderNewMasterKey } from '../identityPins'
import { resealFavoritesUnderNewMasterKey } from '../../memory/favorites'
import { listScopeMembers } from '../../spaces/members'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'

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
/** A scope the caller holds at epoch 0, its grant wrapping `e0` to the old identity. */
const heldAt0 = (id: string, e0: Uint8Array) => ({
  id, kind: 'PERSONAL', tenantId: 't', grantedEpoch: 0,
  wrappedGrant: b64std(v1.sealTo({ purpose: 'scope-grant', scopeId: id, epoch: 0 }, v1.hybridPublic(oldPriv()), e0)),
})
/** The light cost the mocked worker above uses; the stored floor's KEK must be built with the same. Its WIRE
 *  profile stays `v1.ARGON` — the client refuses any other before deriving (ADR 0017 rule 5), and the mock above
 *  ignores the params it is handed. */
const KDF = { t: 1, m: 64, p: 1, dkLen: 32 }

/** The stored PASSWORD wrap, produced exactly as account birth produces it. */
function storedFloor(masterKeyBytes = MK, secret = SECRET_KEY, password = PASSWORD) {
  const salt = v1.randomBytes(16)
  const kek = v1.kekPasswordSecret(secret, password, salt, KDF)
  return {
    id: 'w1',
    method: 'PASSWORD',
    wrappedKey: b64std(v1.seal({ purpose: 'master-key' }, kek, masterKeyBytes)),
    kdfParams: b64std(v1.encodeKdfParams(v1.ARGON, salt)),
  }
}

function fakeServer(opts: { wraps?: unknown[]; sole?: string[]; queued?: string[]; held?: unknown[] } = {}) {
  const getAccountKeyMaterial = vi.fn(async () => ({
    suiteId: 'v1',
    wraps: opts.wraps ?? [storedFloor()],
    wrappedIdentityPrivateKey: 'aWdub3JlZA==',
  }))
  vi.mocked(accountApi).mockReturnValue({ getAccountKeyMaterial } as unknown as ReturnType<typeof accountApi>)

  type RotateBody = {
    rotateAccountKeysRequest: {
      identityPublicKey: string
      wrappedIdentityPrivateKey: string
      passwordWrap: { wrappedKey: string; kdfParams: string }
      recoveryWrap: { wrappedKey: string; kdfParams: string }
      secretKeyProofSignature: string
    }
  }
  const rotateAccountKeys = vi.fn(async (_body: RotateBody) => ({
    soleMemberScopeIds: opts.sole ?? [],
    queuedScopeIds: opts.queued ?? [],
  }))
  vi.mocked(accountRotationApi).mockReturnValue(
    { rotateAccountKeys } as unknown as ReturnType<typeof accountRotationApi>,
  )

  type RotateScopeBody = {
    scopeId: string
    rotateScopeRequest: { epoch: number; wrappedPrev: string; grants: { userId: string; wrappedGrant: string }[] }
  }
  const rotateScope = vi.fn(async (body: RotateScopeBody) => ({ epoch: body.rotateScopeRequest.epoch }))
  // The caller's own grants, wrapped to the OLD identity the vault still holds while the sole scopes re-key.
  const listMyScopes = vi.fn(async () => opts.held ?? [])
  vi.mocked(scopesApi).mockReturnValue({ rotateScope, listMyScopes } as unknown as ReturnType<typeof scopesApi>)

  return { getAccountKeyMaterial, rotateAccountKeys, rotateScope }
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(unlockOrigin).mockReturnValue('cold')
  vi.mocked(masterKey).mockImplementation(() => MK.slice())
  vi.mocked(identityPrivate).mockImplementation(() => oldPriv())
  vi.mocked(listScopeMembers).mockResolvedValue([])
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
  it('reports a queued scope as waiting and re-keys nothing there', async () => {
    // ADR 0060 Decision 6: between rotating and being re-admitted the caller is OUTSIDE every shared scope.
    // That is the property that stops an attacker rotating their way back in, so the status says it rather
    // than showing a spinner over it.
    const srv = fakeServer({ queued: [SCOPE_SHARED] })

    const result = await rotateKeys(PASSWORD, SECRET_KEY_B64)

    expect(result.scopes).toEqual([{ scopeId: SCOPE_SHARED, status: 'waiting' }])
    expect(srv.rotateScope).not.toHaveBeenCalled()
  })

  it('re-keys a sole-member scope to the new identity and reports it secured', async () => {
    const e0 = v1.randomBytes(32)
    const srv = fakeServer({ sole: [SCOPE_PERSONAL], held: [heldAt0(SCOPE_PERSONAL, e0)] })
    vi.mocked(listScopeMembers).mockResolvedValue([
      { userId: 'me', identityPublicKey: 'ignored' },
    ] as unknown as Awaited<ReturnType<typeof listScopeMembers>>)

    const result = await rotateKeys(PASSWORD, SECRET_KEY_B64)

    expect(result.scopes).toEqual([{ scopeId: SCOPE_PERSONAL, status: 'secured' }])
    const grants = srv.rotateScope.mock.calls[0][0].rotateScopeRequest.grants
    expect(grants).toHaveLength(1)
    expect(grants[0].userId).toBe('me')
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
      { userId: 'me', identityPublicKey: 'x' },
      { userId: 'someone-else', identityPublicKey: 'y' },
    ] as unknown as Awaited<ReturnType<typeof listScopeMembers>>)

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

  it('does not fail a published rotation because a Master-Key-sealed blob could not be re-sealed', async () => {
    // All three blobs degrade to empty rather than break (a lost contact-pin re-pins on first use, an unnamed
    // device still revokes, an unpinned Memory is still there), so none is worth failing a rotation the server
    // has already committed.
    vi.mocked(resealFavoritesUnderNewMasterKey).mockRejectedValueOnce(new Error('offline'))
    vi.mocked(resealPinsUnderNewMasterKey).mockRejectedValueOnce(new Error('offline'))
    fakeServer({ queued: ['s1'] })

    await expect(rotateKeys(PASSWORD, SECRET_KEY_B64)).resolves.toBeDefined()
    expect(vi.mocked(setUnlocked)).toHaveBeenCalled()
  })
})
