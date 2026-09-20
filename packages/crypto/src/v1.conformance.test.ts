import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import * as v1 from './v1'
import { PRODUCTION_ARGON_TIMEOUT_MS } from './testArgon'

const unhex = (s: string) => Uint8Array.from(Buffer.from(s, 'hex'))
const hex = (u: Uint8Array) => Buffer.from(u).toString('hex')

const here = dirname(fileURLToPath(import.meta.url))
// repo contracts/crypto/vectors/v1.json (3 levels up from packages/crypto/src/)
const vectors = JSON.parse(
  readFileSync(resolve(here, '../../../contracts/crypto/vectors/v1.json'), 'utf8'),
)

// Every Argon2id case here runs at the params the VECTORS carry (`k.params`), including an explicit
// production-params one — that is this file's whole job, and the reason the cheap TEST_ARGON used elsewhere in
// the suite costs no coverage. So this is the one block that can never be made faster, only given room.
// See ./testArgon.ts.
describe('v1 crypto conformance (web TS vs shared vectors)', () => {
  it('targets suite v1', () => {
    expect(vectors.suite).toBe('v1')
  })

  it('HKDF-SHA256 (RFC 5869 case 1)', () => {
    const h = vectors.primitives.hkdf_sha256_rfc5869_case1
    expect(hex(v1.hkdfSha256(unhex(h.ikm), unhex(h.salt), unhex(h.info), h.length))).toBe(h.okm)
  })

  it('AES-256-GCM', () => {
    const p = vectors.primitives.aes256gcm
    expect(hex(v1.aeadSeal(unhex(p.key), unhex(p.nonce), unhex(p.plaintext), unhex(p.aad)))).toBe(
      p.ciphertext_and_tag,
    )
  })

  it('password + secret key KEK (Argon2id)', () => {
    const k = vectors.kek.password_secret_argon2id
    expect(hex(v1.kekPasswordSecret(unhex(k.secret_key), k.password_utf8, unhex(k.salt), k.params))).toBe(k.kek)
  })

  it('recovery code KEK (Argon2id)', () => {
    const k = vectors.kek.recovery_argon2id
    expect(hex(v1.kekRecovery(unhex(k.recovery_code), unhex(k.salt), k.params))).toBe(k.kek)
  })

  it('device + password KEK (Argon2id)', () => {
    const k = vectors.kek.device_password_argon2id
    expect(hex(v1.kekDevicePassword(unhex(k.device_factor), k.password_utf8, unhex(k.salt), k.params))).toBe(k.kek)
  })

  it('recovery KEK at PRODUCTION Argon2 params m=32 MiB (pins the invite-temp-key KEK)', () => {
    const k = vectors.kek.recovery_argon2id_m32768
    expect(hex(v1.kekRecovery(unhex(k.recovery_code), unhex(k.salt), k.params))).toBe(k.kek)
  })

  it('auth salt (deriveAuthSalt — HKDF, ADR 0003)', () => {
    const a = vectors.auth.auth_salt
    expect(hex(v1.deriveAuthSalt(unhex(a.instance_salt), a.email_utf8))).toBe(a.auth_salt)
  })

  it('auth keypair (deriveAuthKeypair — Argon2id → Ed25519, ADR 0003)', () => {
    const a = vectors.auth.auth_keypair
    const kp = v1.deriveAuthKeypair(a.password_utf8, unhex(a.auth_salt), a.params)
    expect(hex(kp.privateKey)).toBe(a.private_key)
    expect(hex(kp.publicKey)).toBe(a.public_key)
  })

  // The PRODUCTION cost parameters, pinned against the contract's own `kdf_params` vector — the params every
  // KEK derivation and the Model B auth keypair fall back to when a caller passes none. Added
  // 2026-09-08 (cr-admin-web-extraction) on a MEASURED gap, not a hunch: the CR's bite test injected
  // `t: 3 -> 4` into this constant and the entire tree — this package's 43 tests and web's 1018 — stayed
  // green, so a silent Argon2id cost DOWNGRADE (t=3 -> t=1, or m=32 MiB -> 64 KiB) was invisible to every
  // assertion we own. Stryker does not close it either: it has no numeric-literal mutator, so `t: 3` is not
  // in the mutant set that scores this file 100 %. Only `dkLen` bit at all, and only structurally (a 31-byte
  // AES key throws downstream). `toEqual` on the whole object, not field-by-field: a REMOVED field must fail
  // too, and the vector is the contract mobile decodes against, so this is an agreement check rather than the
  // literal restating itself.
  it('the default ARGON params are the contract\'s production kdf_params (cost is not silently downgradable)', () => {
    expect(v1.ARGON).toEqual(vectors.kdf_params.params)
  })

  it('kdfParams wire format (encode + decode round-trip)', () => {
    const k = vectors.kdf_params
    expect(hex(v1.encodeKdfParams(k.params, unhex(k.salt)))).toBe(k.encoded)
    const d = v1.decodeKdfParams(unhex(k.encoded))
    expect(d.params).toEqual(k.params)
    expect(hex(d.salt)).toBe(k.salt)
  })

  it('kdfParams length guard (the blob comes from the blind server — reject it cleanly)', () => {
    const k = vectors.kdf_params
    const encoded = unhex(k.encoded) // 16-byte header || 16-byte salt
    // Pin the MESSAGE, not merely that it throws: without the guard a truncated blob still throws — the
    // opaque DataView RangeError that decodeKdfParams exists to pre-empt. A bare .toThrow() passes either
    // way, so relaxing this assertion silently retires the guard it is here to prove.
    expect(() => v1.decodeKdfParams(encoded.slice(0, 15))).toThrow(/malformed_kdf_params/)
    // 16 bytes is exactly the four u32be reads, so the header alone is well-formed: params, empty salt.
    const d = v1.decodeKdfParams(encoded.slice(0, 16))
    expect(d.params).toEqual(k.params)
    expect(d.salt.length).toBe(0)
  })

  it('KEM-v1 decap (hybrid X25519 + ML-KEM-768)', () => {
    const d = vectors.kem_v1.decap_kat
    const ss = v1.kemDecap(
      { x: unhex(d.sk_x25519), m: unhex(d.sk_mlkem) },
      { x: unhex(d.pk_x25519), m: unhex(d.pk_mlkem) },
      unhex(d.ct),
    )
    expect(hex(ss)).toBe(d.shared_secret)
  })

  it('symmetric master-key wrap', () => {
    const w = vectors.wrap.sym_master_key
    expect(hex(v1.symWrap(unhex(w.kek), unhex(w.plaintext), w.context, unhex(w.nonce)))).toBe(w.blob)
  })

  it('symmetric wrap with the invite-temp-key context', () => {
    const w = vectors.wrap.sym_invite_temp_key
    expect(hex(v1.symWrap(unhex(w.kek), unhex(w.plaintext), w.context, unhex(w.nonce)))).toBe(w.blob)
  })

  it('symmetric wrap with the session-token context (cr-0023)', () => {
    const w = vectors.wrap.session_token
    expect(hex(v1.symWrap(unhex(w.kek), unhex(w.plaintext), w.context, unhex(w.nonce)))).toBe(w.blob)
    expect(hex(v1.symUnwrap(unhex(w.kek), unhex(w.blob), w.context))).toBe(w.plaintext)
  })

  it('symmetric wrap with the identity-pins context (cr-0098, ADR 0028)', () => {
    const w = vectors.wrap.sym_identity_pins
    expect(hex(v1.symWrap(unhex(w.kek), unhex(w.plaintext), w.context, unhex(w.nonce)))).toBe(w.blob)
    expect(hex(v1.symUnwrap(unhex(w.kek), unhex(w.blob), w.context))).toBe(w.plaintext)
  })

  it('symmetric wrap with the device-register context (en.account.device-register)', () => {
    const w = vectors.wrap.sym_device_register
    expect(hex(v1.symWrap(unhex(w.kek), unhex(w.plaintext), w.context, unhex(w.nonce)))).toBe(w.blob)
    expect(hex(v1.symUnwrap(unhex(w.kek), unhex(w.blob), w.context))).toBe(w.plaintext)
  })

  it('symmetric wrap with the favorites context (ADR 0068 — the private Memory pin)', () => {
    const w = vectors.wrap.sym_favorites
    expect(hex(v1.symWrap(unhex(w.kek), unhex(w.plaintext), w.context, unhex(w.nonce)))).toBe(w.blob)
    expect(hex(v1.symUnwrap(unhex(w.kek), unhex(w.blob), w.context))).toBe(w.plaintext)
  })

  it('symmetric wrap with the item-favorites context (ADR 0068 — the shared item mark)', () => {
    const w = vectors.wrap.sym_item_favorites
    expect(hex(v1.symWrap(unhex(w.kek), unhex(w.plaintext), w.context, unhex(w.nonce)))).toBe(w.blob)
    expect(hex(v1.symUnwrap(unhex(w.kek), unhex(w.blob), w.context))).toBe(w.plaintext)
  })

  it('the favorites and item-favorites contexts are domain-separated', () => {
    // The two favorite objects carry DIFFERENT audiences (ADR 0068): the pin set is private to the member,
    // the item marks are shared with the scope. These vectors share kek, nonce and plaintext so only the
    // context varies — without the separation a private pin blob would open in the shared slot and vice
    // versa, which is the leak this whole split exists to close.
    const f = vectors.wrap.sym_favorites
    const i = vectors.wrap.sym_item_favorites
    expect(f.kek).toBe(i.kek)
    expect(f.nonce).toBe(i.nonce)
    expect(f.plaintext).toBe(i.plaintext)
    expect(f.blob).not.toBe(i.blob)
    expect(() => v1.symUnwrap(unhex(f.kek), unhex(f.blob), i.context)).toThrow()
    expect(() => v1.symUnwrap(unhex(i.kek), unhex(i.blob), f.context)).toThrow()
  })

  it('the device-register and identity-pins contexts are domain-separated', () => {
    // Same kek, same nonce, same plaintext, different context ⇒ different blob, and neither opens under the
    // other's context. Without this a register blob could be replayed into the pin store's slot.
    const r = vectors.wrap.sym_device_register
    const p = vectors.wrap.sym_identity_pins
    expect(r.kek).toBe(p.kek)
    expect(hex(v1.symWrap(unhex(r.kek), unhex(r.plaintext), p.context, unhex(r.nonce)))).not.toBe(r.blob)
    expect(() => v1.symUnwrap(unhex(r.kek), unhex(r.blob), p.context)).toThrow()
  })

  it('asymmetric wrap round-trip (unwrap a recorded blob)', () => {
    const a = vectors.wrap.asym_scope_grant
    const d = vectors.kem_v1.decap_kat
    const recovered = v1.asymUnwrap(
      { x: unhex(d.sk_x25519), m: unhex(d.sk_mlkem) },
      { x: unhex(a.pk_x25519), m: unhex(a.pk_mlkem) },
      unhex(a.blob),
      a.context,
    )
    expect(hex(recovered)).toBe(a.plaintext)
  })

  it('group name-key derivation from the name epoch key (cr-0096, ADR 0027)', () => {
    const g = vectors.group_name_key
    // K_name = HKDF-SHA256(E_e, salt="", info=utf8("veyq/v1/group-name-key"), 32).
    expect(hex(v1.hkdfSha256(unhex(g.epoch_key), unhex(g.salt), unhex(g.info), g.length))).toBe(g.okm)
  })

  it('space icon-key derivation from the icon epoch key (uc.spaces.set-space-icon, ADR 0027)', () => {
    const s = vectors.space_icon_key
    // K_icon = HKDF-SHA256(E_e, salt="", info=utf8("veyq/v1/space-icon-key"), 32). Distinct info from
    // group_name_key (same epoch_key here) → a different okm: an icon-key wrap can never be a name-key.
    expect(hex(v1.hkdfSha256(unhex(s.epoch_key), unhex(s.salt), unhex(s.info), s.length))).toBe(s.okm)
  })

  it('symmetric wrap of the group name under K_name (node-name context, cr-0096)', () => {
    const w = vectors.wrap.sym_group_name
    // The canonical encryptedName: the group name sealed under its derived name-key (reusing the node-name seal).
    expect(hex(v1.symWrap(unhex(w.kek), unhex(w.plaintext), w.context, unhex(w.nonce)))).toBe(w.blob)
    expect(hex(v1.symUnwrap(unhex(w.kek), unhex(w.blob), w.context))).toBe(w.plaintext)
  })

  it('asymmetric wrap of K_name with the group-name-key context (cr-0096 invite name-key)', () => {
    const a = vectors.wrap.asym_group_name_key
    const d = vectors.kem_v1.decap_kat
    const recovered = v1.asymUnwrap(
      { x: unhex(d.sk_x25519), m: unhex(d.sk_mlkem) },
      { x: unhex(a.pk_x25519), m: unhex(a.pk_mlkem) },
      unhex(a.blob),
      a.context,
    )
    expect(hex(recovered)).toBe(a.plaintext) // unwraps to the 32-byte K_name
    // domain separation: the same blob must NOT open under the scope-grant context (distinct AAD).
    expect(() =>
      v1.asymUnwrap(
        { x: unhex(d.sk_x25519), m: unhex(d.sk_mlkem) },
        { x: unhex(a.pk_x25519), m: unhex(a.pk_mlkem) },
        unhex(a.blob),
        'scope-grant',
      ),
    ).toThrow()
  })

  it('blinded slug token (AD-26)', () => {
    const s = vectors.slug_token
    expect(hex(v1.slugToken(unhex(s.contentKey), s.parentSlot, s.slug))).toBe(s.token)
  })

  it('media dedup token (AD-09)', () => {
    const d = vectors.dedup_token
    expect(hex(v1.dedupToken(unhex(d.contentKey), unhex(d.content)))).toBe(d.token)
  })

  it('recovery commitment (password reset)', () => {
    const r = vectors.recovery_auth_tag
    expect(hex(v1.recoveryAuthTag(unhex(r.masterKey)))).toBe(r.tag)
  })

  it('journal seal (per-message sub-key)', () => {
    const j = vectors.journal_open
    expect(hex(v1.journalOpen(unhex(j.contentKey), unhex(j.blob)))).toBe(j.plaintext)
    // round-trip the SEAL side too (randomized salt/nonce, so we can't pin a fixed blob — but our own seal
    // must open to the same plaintext, exercising the seal-side HKDF/layout the fixed-blob vector can't).
    const sealed = v1.journalSeal(unhex(j.contentKey), unhex(j.plaintext))
    expect(hex(v1.journalOpen(unhex(j.contentKey), sealed))).toBe(j.plaintext)
  })

  it('chunked-AEAD frame (resumable upload / streaming)', () => {
    const f = vectors.chunk_frame
    const frame = v1.sealFrame(unhex(f.contentKey), f.index, f.total, unhex(f.plaintext), unhex(f.nonce))
    expect(hex(frame)).toBe(f.frame)
    expect(hex(v1.openFrame(unhex(f.contentKey), f.index, f.total, unhex(f.frame)))).toBe(f.plaintext)
  })

  it('identity fingerprint / safety number', () => {
    const f = vectors.fingerprint
    expect(v1.fingerprint({ kemX: unhex(f.kem_x), kemM: unhex(f.kem_m), sign: unhex(f.sign) })).toBe(f.digits)
  })
}, PRODUCTION_ARGON_TIMEOUT_MS)
