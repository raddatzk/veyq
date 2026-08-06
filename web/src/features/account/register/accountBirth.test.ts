import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import * as v1 from '../../../shared/crypto/v1'
import { b64std, fromB64std } from '../../../shared/base64'
import { buildAccount } from './accountBirth'

// A byte-exact KAT over the cold master-key birth (cr-0177, ADR 0045). buildAccount's assertion strength was
// unmeasured (no test), and its output is RNG-nondeterministic — so we pin every source of randomness and every
// Argon2id KEK, then assert the WHOLE produced material against values computed here with HARDCODED AAD literals.
// That last point is the crux: the expected wraps below are built with the *literal* 'master-key'/'identity-priv',
// NOT buildAccount's own constants — so a mutated source AAD makes buildAccount's wrap disagree with ours (a naive
// roundtrip that reused the source const would be blind). The three Argon KEKs are MOCKED to fixed bytes: their
// real-byte correctness is v1.conformance's job (v1 is stryker.mutate.json's mutate[0]); accountBirth's job is
// WIRING the right primitive with the right AAD/input, which this pins. Mocking Argon also keeps the mutation run
// fast — one real buildAccount is ~2.6 s (3× Argon2id @ 32 MB), which Stryker would pay per mutant.

// The birth identity, generated once (real) before any mock, then pinned — so identityPublicKey + the identity
// private wrap are deterministic too.
const FIXED_IDENTITY = v1.generateIdentity()

// Fixed KEK/auth stand-ins for the three Argon-bearing primitives. Distinct so the PASSWORD and RECOVERY wraps
// cannot accidentally coincide.
const KEK_PW = new Uint8Array(32).fill(0xa1)
const KEK_REC = new Uint8Array(32).fill(0xb2)
const AUTH_PUB = new Uint8Array(32).fill(0xc3)

// Deterministic RNG: call k returns n bytes seeded by (k, i). Pure, so the test can reconstruct exactly what
// buildAccount consumed. buildAccount's direct v1.randomBytes calls, in order:
//   #0 masterKey(32) · #1 identity-priv nonce(12) · #2 secretKey(32) · #3 pw salt(16)
//   #4 pw-wrap nonce(12) · #5 rec salt(16) · #6 rec-wrap nonce(12)
// (generateIdentity is mocked, so it consumes none.)
const seededBytes = (call: number, n: number): Uint8Array => {
  const out = new Uint8Array(n)
  for (let i = 0; i < n; i++) out[i] = (call * 37 + i) & 0xff
  return out
}

beforeEach(() => {
  let call = 0
  vi.spyOn(v1, 'generateIdentity').mockReturnValue(FIXED_IDENTITY)
  vi.spyOn(v1, 'randomBytes').mockImplementation((n: number) => seededBytes(call++, n))
  vi.spyOn(v1, 'kekPasswordSecret').mockReturnValue(KEK_PW)
  vi.spyOn(v1, 'kekRecovery').mockReturnValue(KEK_REC)
  vi.spyOn(v1, 'deriveAuthKeypair').mockReturnValue({ publicKey: AUTH_PUB, privateKey: new Uint8Array(32) })
})
afterEach(() => vi.restoreAllMocks())

describe('buildAccount — cold master-key birth (cr-0177, ADR 0045)', () => {
  it('produces the exact operator-blind material — every wrap pinned with a hardcoded AAD literal', () => {
    const built = buildAccount('owner@example.test', 'correct horse battery staple', new Uint8Array(16).fill(9))

    // The seeded material buildAccount consumed (reconstructed independently).
    const masterKey = seededBytes(0, 32)
    const privNonce = seededBytes(1, 12)
    const secretKey = seededBytes(2, 32)
    const pwSalt = seededBytes(3, 16)
    const pwNonce = seededBytes(4, 12)
    const recSalt = seededBytes(5, 16)
    const recNonce = seededBytes(6, 12)

    // Expected wraps, built with the LITERAL AAD context — independent of buildAccount's own constants.
    const expectedPrivWrap = b64std(
      v1.symWrap(masterKey, v1.serializeIdentityPrivate(FIXED_IDENTITY), 'identity-priv', privNonce),
    )
    const expectedPwWrap = b64std(v1.symWrap(KEK_PW, masterKey, 'master-key', pwNonce))
    const expectedRecWrap = b64std(v1.symWrap(KEK_REC, masterKey, 'master-key', recNonce))

    expect(built.material).toEqual({
      suiteId: 'v1',
      identityPublicKey: b64std(v1.serializeIdentityPublic(FIXED_IDENTITY.pub)),
      wrappedIdentityPrivateKey: expectedPrivWrap,
      authPublicKey: b64std(AUTH_PUB),
      masterKeyWraps: [
        { method: 'PASSWORD', wrappedKey: expectedPwWrap, kdfParams: b64std(v1.encodeKdfParams(v1.ARGON, pwSalt)) },
        { method: 'RECOVERY', wrappedKey: expectedRecWrap, kdfParams: b64std(v1.encodeKdfParams(v1.ARGON, recSalt)) },
      ],
      recoveryMasterKeyTag: b64std(v1.recoveryAuthTag(masterKey)),
    })
    expect(built.emergencyKit).toEqual({ secretKey: b64std(secretKey) })
    expect(built.identity).toBe(FIXED_IDENTITY)
  })

  it('binds each master-key wrap to the AAD literal "master-key" — a mutated context refuses to open', () => {
    const built = buildAccount('a@b.test', 'pw', new Uint8Array(16))
    const masterKey = seededBytes(0, 32)
    const [pw, rec] = built.material.masterKeyWraps

    // Each wrap opens under its KEK ONLY with the exact literal 'master-key'; if the source AAD were mutated,
    // symUnwrap with the real literal throws (AEAD tag), so these lines red.
    expect(b64std(v1.symUnwrap(KEK_PW, fromB64std(pw.wrappedKey), 'master-key'))).toBe(b64std(masterKey))
    expect(b64std(v1.symUnwrap(KEK_REC, fromB64std(rec.wrappedKey), 'master-key'))).toBe(b64std(masterKey))
  })

  it('binds the identity-private wrap to the AAD literal "identity-priv"', () => {
    const built = buildAccount('a@b.test', 'pw', new Uint8Array(16))
    const masterKey = seededBytes(0, 32)
    const opened = v1.symUnwrap(masterKey, fromB64std(built.material.wrappedIdentityPrivateKey), 'identity-priv')
    expect(b64std(opened)).toBe(b64std(v1.serializeIdentityPrivate(FIXED_IDENTITY)))
  })

  it('feeds the two KEKs to the matching wrap — PASSWORD←kekPasswordSecret, RECOVERY←kekRecovery', () => {
    const built = buildAccount('a@b.test', 'pw', new Uint8Array(16))
    const masterKey = seededBytes(0, 32)
    const [pw, rec] = built.material.masterKeyWraps
    // The PASSWORD wrap opens under KEK_PW (not KEK_REC) and vice-versa — pins which KEK feeds which wrap.
    expect(() => v1.symUnwrap(KEK_REC, fromB64std(pw.wrappedKey), 'master-key')).toThrow()
    expect(() => v1.symUnwrap(KEK_PW, fromB64std(rec.wrappedKey), 'master-key')).toThrow()
    expect(b64std(v1.symUnwrap(KEK_PW, fromB64std(pw.wrappedKey), 'master-key'))).toBe(b64std(masterKey))
    expect(b64std(v1.symUnwrap(KEK_REC, fromB64std(rec.wrappedKey), 'master-key'))).toBe(b64std(masterKey))
  })
})
