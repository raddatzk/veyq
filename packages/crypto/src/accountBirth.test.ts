import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import * as v1 from './v1'
import { b64std, fromB64std } from './base64'
import { buildAccount } from './accountBirth'
import * as raw from './primitives'

// The two seal contexts as HARDCODED bytes — `lp(utf8(purpose))`, the canonical encoding of a purpose-only seal
// (ADR 0072) — never through seal.ts's encoder, so a mutated purpose or encoding in the source disagrees with these.
const MASTER_KEY_CTX = Uint8Array.from(Buffer.from('0000000a' + '6d61737465722d6b6579', 'hex')) // 'master-key'
const IDENTITY_PRIV_CTX = Uint8Array.from(Buffer.from('0000000d' + '6964656e746974792d70726976', 'hex')) // 'identity-priv'

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
// buildAccount consumed. buildAccount's platform-RNG draws, in order:
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
  // The platform RNG itself, not v1.randomBytes: the typed seal draws its own nonce (ADR 0072), so a spy on the
  // wrapper would miss the three wrap nonces and the expected material could no longer be reconstructed.
  vi.spyOn(globalThis.crypto, 'getRandomValues').mockImplementation(<T extends ArrayBufferView | null>(arr: T): T => {
    const view = arr as unknown as Uint8Array
    view.set(seededBytes(call++, view.length))
    return arr
  })
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
      raw.symWrap(masterKey, v1.serializeIdentityPrivate(FIXED_IDENTITY), IDENTITY_PRIV_CTX, privNonce),
    )
    const expectedPwWrap = b64std(raw.symWrap(KEK_PW, masterKey, MASTER_KEY_CTX, pwNonce))
    const expectedRecWrap = b64std(raw.symWrap(KEK_REC, masterKey, MASTER_KEY_CTX, recNonce))

    expect(built.material).toEqual({
      suiteId: 'v1',
      identityPublicKey: b64std(v1.serializeIdentityPublic(FIXED_IDENTITY.pub)),
      wrappedIdentityPrivateKey: expectedPrivWrap,
      authPublicKey: b64std(AUTH_PUB),
      masterKeyWraps: [
        { method: 'PASSWORD', wrappedKey: expectedPwWrap, kdfParams: b64std(v1.encodeKdfParams(v1.ARGON, pwSalt)) },
        { method: 'RECOVERY', wrappedKey: expectedRecWrap, kdfParams: b64std(v1.encodeKdfParams(v1.ARGON, recSalt)) },
      ],
      // Derived from the kit's Secret Key and nothing else — the Master Key would be one a linked device holds.
      secretKeyProofPublicKey: b64std(v1.deriveSecretKeyProofKeypair(secretKey).publicKey),
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
    expect(b64std(raw.symUnwrap(KEK_PW, fromB64std(pw.wrappedKey), MASTER_KEY_CTX))).toBe(b64std(masterKey))
    expect(b64std(raw.symUnwrap(KEK_REC, fromB64std(rec.wrappedKey), MASTER_KEY_CTX))).toBe(b64std(masterKey))
  })

  it('binds the identity-private wrap to the AAD literal "identity-priv"', () => {
    const built = buildAccount('a@b.test', 'pw', new Uint8Array(16))
    const masterKey = seededBytes(0, 32)
    const opened = raw.symUnwrap(masterKey, fromB64std(built.material.wrappedIdentityPrivateKey), IDENTITY_PRIV_CTX)
    expect(b64std(opened)).toBe(b64std(v1.serializeIdentityPrivate(FIXED_IDENTITY)))
  })

  // `opts.secretKey` — the dev-bootstrap re-seed path (an existing Emergency Kit stays valid across a `down -v`).
  // The bite: a buildAccount that took the option but kept minting its own key would still return SOME 32 bytes,
  // so asserting "it is 32 bytes" proves nothing. We assert the GIVEN bytes come back AND reach both KEK
  // derivations — the two places a stale key would silently produce an unopenable wrap.
  describe('opts.secretKey — re-use an existing Emergency Kit (dev re-seed only)', () => {
    const MINE = new Uint8Array(32).fill(0x5e)

    it('uses the supplied Secret Key for the kit and for BOTH KEK derivations', () => {
      const built = buildAccount('a@b.test', 'pw', new Uint8Array(16), { secretKey: MINE })

      expect(built.emergencyKit).toEqual({ secretKey: b64std(MINE) })
      // One fewer randomBytes call than the default path, so the salts shift down by one: #2 pw salt, #4 rec salt.
      expect(v1.kekPasswordSecret).toHaveBeenCalledWith(MINE, 'pw', seededBytes(2, 16), v1.ARGON)
      expect(v1.kekRecovery).toHaveBeenCalledWith(MINE, seededBytes(4, 16), v1.ARGON)
      // And the proof key, or the re-seeded account could never rotate, change its password or recover.
      expect(built.material.secretKeyProofPublicKey).toBe(b64std(v1.deriveSecretKeyProofKeypair(MINE).publicKey))
    })

    it('still births everything else fresh — the Master Key is random, not derived from the kit', () => {
      const built = buildAccount('a@b.test', 'pw', new Uint8Array(16), { secretKey: MINE })
      // The Master Key is randomBytes #0, untouched by the option (a wrap that opened to MINE would mean the
      // re-seed had collapsed the two into one secret).
      expect(b64std(raw.symUnwrap(KEK_PW, fromB64std(built.material.masterKeyWraps[0].wrappedKey), MASTER_KEY_CTX)))
        .toBe(b64std(seededBytes(0, 32)))
    })

    it('rejects a Secret Key that is not 32 bytes, and mints a fresh one when the option is absent', () => {
      expect(() => buildAccount('a@b.test', 'pw', new Uint8Array(16), { secretKey: MINE.slice(0, 31) })).toThrow()
      expect(buildAccount('a@b.test', 'pw', new Uint8Array(16)).emergencyKit.secretKey).toBe(b64std(seededBytes(2, 32)))
    })
  })

  it('feeds the two KEKs to the matching wrap — PASSWORD←kekPasswordSecret, RECOVERY←kekRecovery', () => {
    const built = buildAccount('a@b.test', 'pw', new Uint8Array(16))
    const masterKey = seededBytes(0, 32)
    const [pw, rec] = built.material.masterKeyWraps
    // The PASSWORD wrap opens under KEK_PW (not KEK_REC) and vice-versa — pins which KEK feeds which wrap.
    expect(() => raw.symUnwrap(KEK_REC, fromB64std(pw.wrappedKey), MASTER_KEY_CTX)).toThrow()
    expect(() => raw.symUnwrap(KEK_PW, fromB64std(rec.wrappedKey), MASTER_KEY_CTX)).toThrow()
    expect(b64std(raw.symUnwrap(KEK_PW, fromB64std(pw.wrappedKey), MASTER_KEY_CTX))).toBe(b64std(masterKey))
    expect(b64std(raw.symUnwrap(KEK_REC, fromB64std(rec.wrappedKey), MASTER_KEY_CTX))).toBe(b64std(masterKey))
  })
})
