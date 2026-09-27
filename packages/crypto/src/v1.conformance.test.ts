import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import * as v1 from './v1'
// The raw primitives are not in the package's exports (ADR 0072 Decision 6); only this suite reaches them.
import * as raw from './primitives'
import { SEAL_PURPOSES, SEAL_TO_PURPOSES } from './seal'
import { PRODUCTION_ARGON_TIMEOUT_MS } from './testArgon'

const unhex = (s: string) => Uint8Array.from(Buffer.from(s, 'hex'))
const hex = (u: Uint8Array) => Buffer.from(u).toString('hex')

const here = dirname(fileURLToPath(import.meta.url))
// repo contracts/crypto/vectors/v1.json (3 levels up from packages/crypto/src/)
const vectors = JSON.parse(
  readFileSync(resolve(here, '../../../contracts/crypto/vectors/v1.json'), 'utf8'),
)

type SymVector = { context: Record<string, string | number>; context_bytes: string; kek: string; nonce: string; plaintext: string; blob: string }
type AsymVector = {
  context: Record<string, string | number>
  context_bytes: string
  pk_x25519: string
  pk_mlkem: string
  esk_x25519: string
  mlkem_msg: string
  nonce: string
  plaintext: string
  blob: string
}
/** A binding value one step away from `v`: another canonical id, the next integer, another blob role. */
const otherValue = (v: string | number): string | number => {
  if (typeof v === 'number') return v + 1
  if (v === 'ORIGINAL' || v === 'THUMBNAIL' || v === 'PREVIEW' || v === 'COVER') return v === 'COVER' ? 'ORIGINAL' : 'COVER'
  return v.slice(0, -1) + (v.endsWith('0') ? '1' : '0')
}

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
    expect(hex(raw.aeadSeal(unhex(p.key), unhex(p.nonce), unhex(p.plaintext), unhex(p.aad)))).toBe(
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
    // The one profile the refusal below admits — the vector's, which the ARGON pin above makes the client's own.
    expect(d.params).toEqual(v1.ARGON)
  })

  it('kdfParams length guard (the blob comes from the blind server — reject it cleanly)', () => {
    const k = vectors.kdf_params
    const encoded = unhex(k.encoded) // 16-byte header || 16-byte salt
    // Pin the MESSAGE, not merely that it throws: without the guard a truncated blob still throws — the
    // opaque DataView RangeError that decodeKdfParams exists to pre-empt. A bare .toThrow() passes either
    // way, so relaxing this assertion silently retires the guard it is here to prove.
    expect(() => v1.decodeKdfParams(encoded.slice(0, 15))).toThrow(/malformed_kdf_params/)
    // 16 bytes is exactly the four u32be reads, so the header alone is well-formed — and still refused: an
    // empty salt is not a profile this client writes (ADR 0017 rule 5, below).
    expect(() => v1.decodeKdfParams(encoded.slice(0, 16))).toThrow(/^kdf_profile_refused$/)
  })

  // ADR 0017 rule 5: the server never chooses a cost parameter. A served profile is accepted only if it is the
  // one this client writes — `ARGON` field for field, and a 16-byte salt — and refused BEFORE any Argon2id runs,
  // because a served `t = 2³¹` or `m = 4 GiB` would otherwise make every unlock derive near-forever. One negative
  // per field, each one step off the accepted profile in each direction, each anchored on its own name: the
  // positive control is the round-trip case above, the same encoder one field apart.
  describe('a served kdf profile other than the one the client writes is refused (ADR 0017 rule 5)', () => {
    const k = vectors.kdf_params
    const salt = unhex(k.salt)
    const off = (field: keyof v1.ArgonParams, delta: number) => ({ ...v1.ARGON, [field]: v1.ARGON[field] + delta })
    it.each([
      ['t', 1], ['t', -1], ['t', 2 ** 31 - 3],
      ['m', 1], ['m', -1],
      ['p', 1], ['p', -1],
      ['dkLen', 1], ['dkLen', -1],
    ] as const)('%s %+d', (field, delta) => {
      expect(() => v1.decodeKdfParams(v1.encodeKdfParams(off(field, delta), salt))).toThrow(
        new v1.KdfProfileRefused(),
      )
    })
    it.each([15, 17, 32])('a %d-byte salt', (n) => {
      expect(() => v1.decodeKdfParams(v1.encodeKdfParams(v1.ARGON, new Uint8Array(n)))).toThrow(/^kdf_profile_refused$/)
    })
    it('the refusal is its own class, so a caller can tell it from a wrong password', () => {
      expect(new v1.KdfProfileRefused()).toBeInstanceOf(Error)
      expect(new v1.KdfProfileRefused().message).toBe('kdf_profile_refused')
      expect(new v1.KdfProfileRefused().name).toBe('KdfProfileRefused')
    })
  })

  it('KEM-v1 decap (hybrid X25519 + ML-KEM-768)', () => {
    const d = vectors.kem_v1.decap_kat
    const ss = raw.kemDecap(
      { x: unhex(d.sk_x25519), m: unhex(d.sk_mlkem) },
      { x: unhex(d.pk_x25519), m: unhex(d.pk_mlkem) },
      unhex(d.ct),
    )
    expect(hex(ss)).toBe(d.shared_secret)
  })

  // ── Typed seal (ADR 0072) ──────────────────────────────────────────────────────────────────────────────────
  // One vector per purpose, keyed by it. The union is data (SEAL_PURPOSES / SEAL_TO_PURPOSES), so its purposes
  // are compared with the vector set in BOTH directions: a purpose added to the union without a vector, and a
  // vector left behind by a deleted purpose, each redden their own case by name.
  describe('typed seal (ADR 0072)', () => {
    const symVectors: Record<string, SymVector> = vectors.seal.sym
    const asymVectors: Record<string, AsymVector> = vectors.seal.asym
    const d = vectors.kem_v1.decap_kat
    const sk = { x: unhex(d.sk_x25519), m: unhex(d.sk_mlkem) }
    const symCtx = (purpose: string): v1.SealContext =>
      ({ purpose, ...symVectors[purpose].context }) as v1.SealContext
    const asymCtx = (purpose: string): v1.SealToContext =>
      ({ purpose, ...asymVectors[purpose].context }) as v1.SealToContext
    const refusal = (purpose: string) => new v1.OpenRefused(purpose)

    it('every purpose in the union has a vector', () => {
      expect(Object.keys(SEAL_PURPOSES).filter((p) => !(p in symVectors))).toEqual([])
      expect(Object.keys(SEAL_TO_PURPOSES).filter((p) => !(p in asymVectors))).toEqual([])
    })

    it('every vector names a live purpose in the union', () => {
      expect(Object.keys(symVectors).filter((p) => !Object.hasOwn(SEAL_PURPOSES, p))).toEqual([])
      expect(Object.keys(asymVectors).filter((p) => !Object.hasOwn(SEAL_TO_PURPOSES, p))).toEqual([])
    })

    it('every vector context carries exactly its purpose\'s binding fields, in the union\'s order', () => {
      const table: Record<string, readonly (readonly [string, string])[]> = { ...SEAL_PURPOSES, ...SEAL_TO_PURPOSES }
      for (const [purpose, v] of [...Object.entries(symVectors), ...Object.entries(asymVectors)]) {
        expect(Object.keys(v.context), purpose).toEqual(table[purpose].map(([name]) => name))
      }
    })

    describe.each(Object.keys(symVectors))('%s (symmetric)', (purpose) => {
      const v = symVectors[purpose]
      it('encodes its context canonically', () => {
        expect(hex(v1.encodeSealContext(symCtx(purpose)))).toBe(v.context_bytes)
      })
      it('seals to the vector blob, and opens it back', () => {
        expect(hex(raw.symWrap(unhex(v.kek), unhex(v.plaintext), unhex(v.context_bytes), unhex(v.nonce)))).toBe(v.blob)
        expect(hex(v1.open(symCtx(purpose), unhex(v.kek), unhex(v.blob)))).toBe(v.plaintext)
      })
      it('seal draws a fresh nonce and round-trips through open', () => {
        const a = v1.seal(symCtx(purpose), unhex(v.kek), unhex(v.plaintext))
        const b = v1.seal(symCtx(purpose), unhex(v.kek), unhex(v.plaintext))
        expect(hex(a.slice(1, 13))).not.toBe(hex(b.slice(1, 13)))
        expect(hex(v1.open(symCtx(purpose), unhex(v.kek), a))).toBe(v.plaintext)
      })
      // Every binding field is load-bearing: the same blob, opened at an address one field away, is refused.
      it.each(Object.keys(v.context))('does not open with a different %s', (field) => {
        const moved = { ...symCtx(purpose), [field]: otherValue(v.context[field]) } as v1.SealContext
        expect(() => v1.open(moved, unhex(v.kek), unhex(v.blob))).toThrow(refusal(purpose))
      })
      it('does not open as any other purpose', () => {
        for (const other of Object.keys(symVectors).filter((p) => p !== purpose)) {
          expect(() => v1.open(symCtx(other), unhex(v.kek), unhex(v.blob))).toThrow(refusal(other))
        }
      })
    })

    describe.each(Object.keys(asymVectors))('%s (asymmetric)', (purpose) => {
      const v = asymVectors[purpose]
      const pk = { x: unhex(v.pk_x25519), m: unhex(v.pk_mlkem) }
      it('encodes its context canonically', () => {
        expect(hex(v1.encodeSealToContext(asymCtx(purpose)))).toBe(v.context_bytes)
      })
      it('seals to the vector blob (deterministic KEM), and opens it back', () => {
        const blob = raw.asymWrap(pk, unhex(v.plaintext), unhex(v.context_bytes), {
          eskX: unhex(v.esk_x25519),
          mlkemMsg: unhex(v.mlkem_msg),
          nonce: unhex(v.nonce),
        })
        expect(hex(blob)).toBe(v.blob)
        expect(hex(v1.openAs(asymCtx(purpose), sk, pk, unhex(v.blob)))).toBe(v.plaintext)
      })
      it('sealTo round-trips through openAs', () => {
        expect(hex(v1.openAs(asymCtx(purpose), sk, pk, v1.sealTo(asymCtx(purpose), pk, unhex(v.plaintext))))).toBe(
          v.plaintext,
        )
      })
      it.each(Object.keys(v.context))('does not open with a different %s', (field) => {
        const moved = { ...asymCtx(purpose), [field]: otherValue(v.context[field]) } as v1.SealToContext
        expect(() => v1.openAs(moved, sk, pk, unhex(v.blob))).toThrow(refusal(purpose))
      })
      it('does not open as any other purpose', () => {
        for (const other of Object.keys(asymVectors).filter((p) => p !== purpose)) {
          expect(() => v1.openAs(asymCtx(other), sk, pk, unhex(v.blob))).toThrow(refusal(other))
        }
      })
    })

    describe('a context that does not describe an object is refused before any AEAD runs', () => {
      const v = symVectors['node-name']
      const base = symCtx('node-name')
      it.each([
        ['an upper-case id', { ...base, nodeId: (base as { nodeId: string }).nodeId.toUpperCase() }, /nodeId is not a canonical lowercase UUID/],
        ['a non-UUID id', { ...base, scopeId: 'root' }, /scopeId is not a canonical lowercase UUID/],
        ['a missing field', { purpose: 'node-name', scopeId: (base as { scopeId: string }).scopeId, nodeId: (base as { nodeId: string }).nodeId }, /epoch is not an unsigned integer/],
        ['a negative epoch', { ...base, epoch: -1 }, /epoch is not an unsigned integer/],
        ['a fractional epoch', { ...base, epoch: 1.5 }, /epoch is not an unsigned integer/],
        ['an extra field', { ...base, parentId: (base as { nodeId: string }).nodeId }, /node-name does not bind parentId/],
        ['an unknown purpose', { purpose: 'album-name' }, /unknown purpose album-name/],
        ['an inherited purpose', { purpose: 'toString' }, /unknown purpose toString/],
      ])('%s', (_name, ctx, message) => {
        expect(() => v1.seal(ctx as v1.SealContext, unhex(v.kek), new Uint8Array(1))).toThrow(v1.SealContextInvalid)
        expect(() => v1.open(ctx as v1.SealContext, unhex(v.kek), unhex(v.blob))).toThrow(message)
      })
      it('a share id that is not the 43-character token is refused', () => {
        const r = symCtx('share-key-recovery')
        expect(() =>
          v1.encodeSealContext({ ...r, shareId: (r as { nodeId: string }).nodeId } as v1.SealContext),
        ).toThrow(/shareId is not a share id/)
      })
      it('a blob role outside the four is refused', () => {
        const b = symCtx('blob-key')
        expect(() => v1.encodeSealContext({ ...b, role: 'ALBUM' } as unknown as v1.SealContext)).toThrow(
          /role is not a blob role/,
        )
      })
      it('the refusals are their own classes, so a caller can tell a bad address from a moved ciphertext', () => {
        expect(new v1.SealContextInvalid('x').name).toBe('SealContextInvalid')
        expect(new v1.OpenRefused('node-name').name).toBe('OpenRefused')
        expect(new v1.OpenRefused('node-name').purpose).toBe('node-name')
        expect(new v1.OpenRefused('node-name').message).toBe('open_refused: node-name')
      })
    })
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

  it('blinded slug token (AD-26)', () => {
    const s = vectors.slug_token
    expect(hex(v1.slugToken(unhex(s.contentKey), s.parentSlot, s.slug))).toBe(s.token)
  })

  it('media dedup token (AD-09)', () => {
    const d = vectors.dedup_token
    expect(hex(v1.dedupToken(unhex(d.contentKey), unhex(d.content)))).toBe(d.token)
  })

  describe('Secret-Key proof key (ADR 0060 Decision 2)', () => {
    const p = vectors.secret_key_proof
    const wrap = (w: { wrapped_key: string; kdf_params: string }) => ({
      wrappedKey: unhex(w.wrapped_key),
      kdfParams: unhex(w.kdf_params),
    })
    const key = v1.deriveSecretKeyProofKeypair(unhex(p.key.secret_key))

    it('derives the keypair from the Secret Key alone', () => {
      expect(hex(key.privateKey)).toBe(p.key.private_key)
      expect(hex(key.publicKey)).toBe(p.key.public_key)
    })

    // Each case pins the bytes AND the signature: the backend rebuilds the statement itself, so an encoding
    // drift on either side is a refused request in production, not a local test failure.
    const cases: [string, Uint8Array, { domain: string; statement: string; signature: string }][] = [
      [
        'rotation',
        v1.rotationProofStatement({
          currentIdentityPublicKey: unhex(p.rotation.current_identity_public_key),
          identityPublicKey: unhex(p.rotation.identity_public_key),
          wrappedIdentityPrivateKey: unhex(p.rotation.wrapped_identity_private_key),
          passwordWrap: wrap(p.rotation.password_wrap),
          recoveryWrap: wrap(p.rotation.recovery_wrap),
        }),
        p.rotation,
      ],
      [
        'change-password',
        v1.passwordChangeProofStatement({
          currentAuthPublicKey: unhex(p.change_password.current_auth_public_key),
          authPublicKey: unhex(p.change_password.auth_public_key),
          passwordWrap: wrap(p.change_password.password_wrap),
        }),
        p.change_password,
      ],
      [
        'recovery',
        v1.recoveryProofStatement({
          token: p.recovery.token_utf8,
          authPublicKey: unhex(p.recovery.auth_public_key),
          passwordWrap: wrap(p.recovery.password_wrap),
        }),
        p.recovery,
      ],
    ]
    it.each(cases)('%s statement and signature', (_name, stmt, v) => {
      expect(hex(stmt)).toBe(v.statement)
      expect(hex(v1.secretKeyProofSign(key.privateKey, stmt))).toBe(v.signature)
      expect(v1.authVerify(key.publicKey, stmt, unhex(v.signature))).toBe(true)
    })

    it('the three domains differ, so no statement answers for another flow', () => {
      const domains = new Set([p.rotation.domain, p.change_password.domain, p.recovery.domain])
      expect(domains.size).toBe(3)
      for (const [, stmt, v] of cases) {
        expect(new TextDecoder().decode(stmt.slice(4, 4 + v.domain.length))).toBe(v.domain)
      }
    })
  })

  it('chunked-AEAD frame (resumable upload / streaming)', () => {
    const f = vectors.chunk_frame
    const frame = v1.sealFrame(unhex(f.contentKey), f.index, f.total, unhex(f.plaintext), unhex(f.nonce))
    expect(hex(frame)).toBe(f.frame)
    expect(hex(v1.openFrame(unhex(f.contentKey), f.index, f.total, unhex(f.frame)))).toBe(f.plaintext)
  })

  it('the frame size is the contract\'s chunk_frame_size, and sealing adds exactly its overhead', () => {
    const { note: _note, ...size } = vectors.chunk_frame_size
    expect(v1.CHUNK_FRAME_SIZE).toEqual(size)
    expect(v1.FRAME_OVERHEAD).toBe(size.nonceBytes + size.tagBytes)
    // The constant must describe what sealFrame actually emits, not only agree with the file: a full frame and
    // an empty one, sealed for real.
    const f = vectors.chunk_frame
    const full = v1.sealFrame(unhex(f.contentKey), 0, 2, new Uint8Array(size.plaintextBytes), unhex(f.nonce))
    expect(full.length).toBe(size.nonceBytes + size.plaintextBytes + size.tagBytes)
    expect(v1.sealFrame(unhex(f.contentKey), 0, 1, new Uint8Array(0), unhex(f.nonce)).length).toBe(v1.FRAME_OVERHEAD)
  })

  it('identity fingerprint / safety number', () => {
    const f = vectors.fingerprint
    expect(v1.fingerprint({ kemX: unhex(f.kem_x), kemM: unhex(f.kem_m), sign: unhex(f.sign) })).toBe(f.digits)
  })
}, PRODUCTION_ARGON_TIMEOUT_MS)
