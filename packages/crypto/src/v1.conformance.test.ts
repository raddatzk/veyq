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
  if (v === 'ORIGINAL' || v === 'THUMBNAIL' || v === 'PREVIEW' || v === 'COVER' || v === 'FILE_CONTENT') {
    return v === 'COVER' ? 'ORIGINAL' : 'COVER'
  }
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
    // The normalization is part of the derivation: the address as typed, padded and capitalised, is the same account.
    expect(hex(v1.deriveAuthSalt(unhex(a.instance_salt), `  ${a.email_utf8.toUpperCase()}\t`))).toBe(a.auth_salt)
  })

  it('auth keypair (deriveAuthKeypair — Argon2id → Ed25519, ADR 0003)', () => {
    const a = vectors.auth.auth_keypair
    const kp = v1.deriveAuthKeypair(a.password_utf8, unhex(a.auth_salt), a.params)
    expect(hex(kp.privateKey)).toBe(a.private_key)
    expect(hex(kp.publicKey)).toBe(a.public_key)
  })

  // The keypair is the vector's, so no Argon runs here; Ed25519 is deterministic, but the vectors carry no login
  // signature, so the pin is that the server's check accepts it, and only for the message it was made over.
  it('auth signature (authSign — Ed25519 over the login nonce, ADR 0003)', () => {
    const a = vectors.auth.auth_keypair
    const nonce = new TextEncoder().encode('login-nonce')
    const sig = v1.authSign(unhex(a.private_key), nonce)
    expect(v1.authVerify(unhex(a.public_key), nonce, sig)).toBe(true)
    expect(v1.authVerify(unhex(a.public_key), new TextEncoder().encode('login-noncf'), sig)).toBe(false)
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
        ['two extra fields', { ...base, parentId: 'x', role: 'COVER' }, /node-name does not bind parentId, role$/],
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
      // The anchors are load-bearing: without `^` or `$`, an id with an extra character in front of or behind its
      // canonical core would still match the core.
      it.each([
        ['in front', (s: string) => `a${s}`],
        ['behind', (s: string) => `${s}a`],
      ])('an id or a share id with an extra character %s is refused', (_where, pad) => {
        const r = symCtx('share-key-recovery') as unknown as Record<string, string>
        expect(() => v1.encodeSealContext({ ...r, nodeId: pad(r.nodeId) } as unknown as v1.SealContext)).toThrow(
          /nodeId is not a canonical lowercase UUID/,
        )
        expect(() => v1.encodeSealContext({ ...r, shareId: pad(r.shareId) } as unknown as v1.SealContext)).toThrow(
          /shareId is not a share id/,
        )
      })
      // `typeof` is the guard here, not a formality: RegExp.prototype.test coerces its argument, so a boxed String
      // passes the pattern and would then be encoded exactly like the primitive.
      it('a boxed String is refused where an id, a share id or a pairing code is due', () => {
        const r = symCtx('share-key-recovery') as unknown as Record<string, string>
        expect(() =>
          v1.encodeSealContext({ ...r, nodeId: new String(r.nodeId) } as unknown as v1.SealContext),
        ).toThrow(/nodeId is not a canonical lowercase UUID/)
        expect(() =>
          v1.encodeSealContext({ ...r, shareId: new String(r.shareId) } as unknown as v1.SealContext),
        ).toThrow(/shareId is not a share id/)
        expect(() =>
          v1.encodeStatement({
            purpose: 'pairing-sign-in-answer',
            transportKey: new Uint8Array(32),
            code: new String('123456'),
          } as unknown as v1.StatementContext),
        ).toThrow(/code is not a six-digit pairing code/)
      })
      // The role is an open, bounded token (the contract's NodeKind token): Memories' four, Files' FILE_CONTENT, and
      // any other well-formed token — ALBUM included, which this suite refused while the role was a closed list of
      // four; an application's own rules, not the encoder, decide which role it writes.
      it.each(['ORIGINAL', 'THUMBNAIL', 'PREVIEW', 'COVER', 'FILE_CONTENT', 'ALBUM', 'A', 'A'.repeat(16), 'X1_'])(
        'blob role %s is accepted, encoded as its name',
        (role) => {
          const b = symCtx('blob-key')
          const encoded = hex(v1.encodeSealContext({ ...b, role } as v1.SealContext))
          expect(encoded.endsWith(hex(v1.lp(new TextEncoder().encode(role))))).toBe(true)
        },
      )
      // The anchors and the bound are load-bearing: a trailing newline or space, a lower-case or a non-ASCII letter,
      // a leading digit, an empty role or a 17th character would each still contain a well-formed token.
      it.each([
        ['lower-case', 'album'],
        ['empty', ''],
        ['17 characters', 'A'.repeat(17)],
        ['a leading digit', '1X'],
        ['a trailing newline', 'ORIGINAL\n'],
        ['a trailing space', 'ORIGINAL '],
        ['a leading space', ' ORIGINAL'],
        ['non-ASCII', 'ÄLBUM'],
        ['a hyphen', 'FILE-CONTENT'],
        ['a boxed String', new String('ORIGINAL')],
        ['a number', 1],
        ['absent', undefined],
      ])('a blob role that is not a token is refused: %s', (_name, role) => {
        const b = symCtx('blob-key')
        expect(() => v1.encodeSealContext({ ...b, role } as unknown as v1.SealContext)).toThrow(/role is not a blob role/)
      })
      it('the refusals are their own classes, so a caller can tell a bad address from a moved ciphertext', () => {
        expect(new v1.SealContextInvalid('x').name).toBe('SealContextInvalid')
        expect(new v1.OpenRefused('node-name').name).toBe('OpenRefused')
        expect(new v1.OpenRefused('node-name').purpose).toBe('node-name')
        expect(new v1.OpenRefused('node-name').message).toBe('open_refused: node-name')
      })
    })
  })

  // A Files blob (ADR 0079 §9): no purpose of its own — the blob-key seal with role FILE_CONTENT and ownerId = the
  // file node binds the wrap to Files' kind and node. The chain: parent content key → the file node's content key
  // (a content-key seal) → the blob key (a blob-key seal under the node key).
  describe('file blob (#file_blob)', () => {
    const f = vectors.file_blob
    const parentKey = unhex(f.parentContentKey)
    const nodeCtx = { purpose: 'content-key', ...f.node.context } as v1.SealContext
    const blobCtx = { purpose: 'blob-key', ...f.blobKey.context } as v1.SealContext
    it('chains from the content-key vector\'s node', () => {
      expect(f.parentNodeId).toBe(vectors.seal.sym['content-key'].context.nodeId)
      expect(f.parentContentKey).toBe(vectors.seal.sym['content-key'].plaintext)
      expect(f.blobKey.context.ownerId).toBe(f.node.context.nodeId)
    })
    it('encodes both contexts canonically', () => {
      expect(hex(v1.encodeSealContext(nodeCtx))).toBe(f.node.context_bytes)
      expect(hex(v1.encodeSealContext(blobCtx))).toBe(f.blobKey.context_bytes)
    })
    it('seals both links to the vector blobs', () => {
      const n = f.node
      const k = f.blobKey
      expect(hex(raw.symWrap(parentKey, unhex(n.plaintext), unhex(n.context_bytes), unhex(n.nonce)))).toBe(n.blob)
      expect(hex(raw.symWrap(unhex(n.plaintext), unhex(k.plaintext), unhex(k.context_bytes), unhex(k.nonce)))).toBe(k.blob)
    })
    it('opens top-down: the node key under the parent, the blob key under the node key', () => {
      const nodeKey = v1.open(nodeCtx, parentKey, unhex(f.node.blob))
      expect(hex(nodeKey)).toBe(f.node.plaintext)
      expect(hex(v1.open(blobCtx, nodeKey, unhex(f.blobKey.blob)))).toBe(f.blobKey.plaintext)
    })
    it.each(['ORIGINAL', 'THUMBNAIL', 'PREVIEW', 'COVER'])('does not open as a Memories %s', (role) => {
      expect(() =>
        v1.open({ ...blobCtx, role } as v1.SealContext, unhex(f.node.plaintext), unhex(f.blobKey.blob)),
      ).toThrow(new v1.OpenRefused('blob-key'))
    })
    it.each(['scopeId', 'ownerId', 'blobId'])('does not open with a different %s', (field) => {
      const moved = { ...blobCtx, [field]: otherValue(f.blobKey.context[field]) } as v1.SealContext
      expect(() => v1.open(moved, unhex(f.node.plaintext), unhex(f.blobKey.blob))).toThrow(new v1.OpenRefused('blob-key'))
    })
    it('does not open under the parent\'s key: the node key is the binding', () => {
      expect(() => v1.open(blobCtx, parentKey, unhex(f.blobKey.blob))).toThrow(new v1.OpenRefused('blob-key'))
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

  it('share write token (ADR 0076)', () => {
    const w = vectors.share_write_token
    const token = v1.shareWriteToken(unhex(w.kShare), w.shareId)
    expect(hex(token)).toBe(w.token)
    expect(hex(v1.sha256Digest(token))).toBe(w.verifier)
    // bound to its link: the same K_share under another share id derives another token
    expect(hex(v1.shareWriteToken(unhex(w.kShare), w.other_share_id))).toBe(w.other_token)
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
    // Each statement is built INSIDE its test, never while the suite is collected: a statement builder that throws
    // at collection fails the file's import, and Stryker's vitest runner counts a file that fails to import as zero
    // failing tests — a mutant that breaks the builder was reported Survived while it in fact killed the file.
    const cases: [string, () => Uint8Array, { domain: string; statement: string; signature: string }][] = [
      [
        'rotation',
        () => v1.rotationProofStatement({
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
        () => v1.passwordChangeProofStatement({
          currentAuthPublicKey: unhex(p.change_password.current_auth_public_key),
          authPublicKey: unhex(p.change_password.auth_public_key),
          passwordWrap: wrap(p.change_password.password_wrap),
        }),
        p.change_password,
      ],
      [
        'recovery',
        () => v1.recoveryProofStatement({
          token: p.recovery.token_utf8,
          authPublicKey: unhex(p.recovery.auth_public_key),
          passwordWrap: wrap(p.recovery.password_wrap),
        }),
        p.recovery,
      ],
    ]
    it.each(cases)('%s statement and signature', (_name, build, v) => {
      const stmt = build()
      expect(hex(stmt)).toBe(v.statement)
      expect(hex(v1.secretKeyProofSign(key.privateKey, stmt))).toBe(v.signature)
      expect(v1.authVerify(key.publicKey, stmt, unhex(v.signature))).toBe(true)
    })

    it('the three domains differ, so no statement answers for another flow', () => {
      const domains = new Set([p.rotation.domain, p.change_password.domain, p.recovery.domain])
      expect(domains.size).toBe(3)
      for (const [, build, v] of cases) {
        expect(new TextDecoder().decode(build().slice(4, 4 + v.domain.length))).toBe(v.domain)
      }
    })
  })

  describe('admission tag (ADR 0020 move 6)', () => {
    const a = vectors.admission_tag
    type Case = { context: v1.Admission; statement: string; tag: string }
    const cases: [string, Case][] = [
      ['tenant invite (no account yet: userId absent)', a.tenant_invite],
      ['group add (bound to the target account)', a.group_add],
    ]
    const epochKey = unhex(a.epoch_key)

    it('derives its MAC key from the epoch key under its own info string', () => {
      expect(hex(v1.hkdfSha256(epochKey, new Uint8Array(0), v1.utf8('veyq/v1/admission-tag'), 32))).toBe(a.tag_key)
    })

    it.each(cases)('%s: statement bytes and tag', (_name, c) => {
      expect(hex(v1.encodeStatement({ purpose: 'admission-tag', ...c.context }))).toBe(c.statement)
      expect(hex(v1.admissionTag(epochKey, c.context))).toBe(c.tag)
      expect(v1.admissionTagVerifies(epochKey, c.context, unhex(c.tag))).toBe(true)
    })

    // Each bound value, moved one step, and the epoch KEY itself: every one must stop the tag from verifying — the
    // epoch key is the one a worker at a later epoch holds, which is what makes a pre-rotation tag not admit.
    it.each(cases)('%s: a tag answers for nothing else', (_name, c) => {
      const tag = unhex(c.tag)
      for (const field of ['scopeId', 'epoch', 'admissionId'] as const) {
        const moved = { ...c.context, [field]: otherValue(c.context[field]) }
        expect(v1.admissionTagVerifies(epochKey, moved, tag), field).toBe(false)
      }
      const otherUser = c.context.userId === null ? 'b0b00000-0000-4000-8000-000000000009' : null
      expect(v1.admissionTagVerifies(epochKey, { ...c.context, userId: otherUser }, tag)).toBe(false)
      expect(v1.admissionTagVerifies(unhex('5b'.repeat(32)), c.context, tag)).toBe(false)
      expect(v1.admissionTagVerifies(epochKey, c.context, tag.slice(0, 31))).toBe(false)
      // A longer "tag" whose first 32 bytes ARE the tag must not pass either — the length is part of the check.
      expect(v1.admissionTagVerifies(epochKey, c.context, v1.concat(tag, Uint8Array.of(0)))).toBe(false)
      const flipped = tag.slice()
      flipped[31] ^= 1
      expect(v1.admissionTagVerifies(epochKey, c.context, flipped)).toBe(false)
    })

    it('an absent userId is explicit: undefined is a forgotten field, not a tenant invite', () => {
      const { userId: _u, ...rest } = a.tenant_invite.context as v1.Admission
      expect(() => v1.admissionTag(epochKey, rest as v1.Admission)).toThrow(v1.SealContextInvalid)
    })
  })

  describe('item-mark self-tag (ADR 0068 §9)', () => {
    const s = vectors.item_mark_self
    type Case = { context: { scopeId: string; nodeId: string; ids: string[] }; marks: string; statement: string; tag: string }
    const cases: [string, Case][] = [
      ['one id', s.one],
      ['several ids, unsorted, with a duplicate (pins the canonicalisation)', s.several_unsorted_with_duplicate],
    ]
    const masterKey = unhex(s.master_key)
    const kSelf = unhex(s.k_self)

    it('derives its key from the Master Key under its own info string', () => {
      expect(hex(v1.itemMarkSelfKey(masterKey))).toBe(s.k_self)
      expect(hex(v1.hkdfSha256(masterKey, new Uint8Array(0), v1.utf8('veyq/v1/item-mark-self'), 32))).toBe(s.k_self)
    })

    it.each(cases)('%s: statement bytes and tag', (_name, c) => {
      const { scopeId, nodeId } = c.context
      expect(hex(v1.encodeStatement({ purpose: 'item-mark-self', scopeId, nodeId, marks: unhex(c.marks) }))).toBe(c.statement)
      expect(hex(v1.itemMarkSelfTag(kSelf, c.context))).toBe(c.tag)
      expect(v1.itemMarkSelfTagVerifies(kSelf, c.context, unhex(c.tag))).toBe(true)
    })

    // The set, not the array: any order and any repetition of the same ids is the same tag. A reader re-derives the
    // tag from the ids it parsed, so a canonicalisation that depended on how they happened to be serialised would
    // turn an honest re-order into "not mine".
    it('the tag is over the SET of ids — order and repetition do not move it', () => {
      const c = s.several_unsorted_with_duplicate as Case
      const distinct = [...new Set(c.context.ids)]
      for (const ids of [distinct, [...distinct].reverse(), [...distinct, distinct[0]], new Set(distinct)]) {
        expect(hex(v1.itemMarkSelfTag(kSelf, { ...c.context, ids }))).toBe(c.tag)
      }
    })

    // Every bound value moved one step, and the key itself: each must stop the tag from verifying. The ids are what
    // make the tag content-bound (a copied tag over a different set does not verify), and the Master-Key-derived key
    // is what makes it the marking member's alone.
    it.each(cases)('%s: a tag answers for nothing else', (_name, c) => {
      const tag = unhex(c.tag)
      for (const field of ['scopeId', 'nodeId'] as const) {
        expect(v1.itemMarkSelfTagVerifies(kSelf, { ...c.context, [field]: otherValue(c.context[field]) }, tag), field).toBe(false)
      }
      expect(v1.itemMarkSelfTagVerifies(kSelf, { ...c.context, ids: [...c.context.ids, 'c0ffee00-0000-4000-8000-0000000000ff'] }, tag)).toBe(false)
      expect(v1.itemMarkSelfTagVerifies(kSelf, { ...c.context, ids: c.context.ids.filter((i) => i !== c.context.ids[0]) }, tag)).toBe(false)
      expect(v1.itemMarkSelfTagVerifies(kSelf, { ...c.context, ids: [] }, tag)).toBe(false)
      expect(v1.itemMarkSelfTagVerifies(v1.itemMarkSelfKey(unhex('6e'.repeat(32))), c.context, tag)).toBe(false)
      expect(v1.itemMarkSelfTagVerifies(masterKey, c.context, tag)).toBe(false) // the raw Master Key is not the tag key
      expect(v1.itemMarkSelfTagVerifies(kSelf, c.context, tag.slice(0, 31))).toBe(false)
      expect(v1.itemMarkSelfTagVerifies(kSelf, c.context, v1.concat(tag, Uint8Array.of(0)))).toBe(false)
      const flipped = tag.slice()
      flipped[0] ^= 1
      expect(v1.itemMarkSelfTagVerifies(kSelf, c.context, flipped)).toBe(false)
    })

    // The two vectors are one object: the item-favorites seal vector's plaintext is case one's `{tag, ids}`, so the
    // stored format and the tag cannot drift apart unseen.
    it('the item-favorites seal vector carries case one as its {tag, ids} plaintext', () => {
      const pt = JSON.parse(new TextDecoder().decode(unhex(vectors.seal.sym['item-favorites'].plaintext)))
      expect(Object.keys(pt)).toEqual(['tag', 'ids'])
      expect(hex(Uint8Array.from(Buffer.from(pt.tag, 'base64')))).toBe(s.one.tag)
      expect(pt.ids).toEqual(s.one.context.ids)
      expect(vectors.seal.sym['item-favorites'].context).toEqual({ scopeId: s.one.context.scopeId, nodeId: s.one.context.nodeId })
    })

    it('the statement\'s label domain-separates it from every seal purpose', () => {
      const stmt = v1.encodeStatement({ purpose: 'item-mark-self', scopeId: s.one.context.scopeId, nodeId: s.one.context.nodeId, marks: unhex(s.one.marks) })
      expect(new TextDecoder().decode(stmt.slice(4, 18))).toBe('item-mark-self')
      expect(Object.keys(vectors.seal.sym)).not.toContain('item-mark-self')
    })

    it('refuses an id that is not a canonical UUID, and a marks field that is not a digest', () => {
      const c = s.one as Case
      expect(() => v1.itemMarkSelfTag(kSelf, { ...c.context, nodeId: 'not-a-uuid' })).toThrow(v1.SealContextInvalid)
      expect(() =>
        v1.encodeStatement({ purpose: 'item-mark-self', scopeId: c.context.scopeId, nodeId: c.context.nodeId, marks: new Uint8Array(31) }),
      ).toThrow(v1.SealContextInvalid)
    })
  })

  describe('grant signature (ADR 0028 Decision 6)', () => {
    const g = vectors.grant_signature
    type Case = {
      context: { scopeId: string; scopeKind: v1.ScopeKind; epoch: number; recipientUserId: string }
      recipient_identity_public_key: string
      wrapped_grant: string
      statement: string
      signature: string
    }
    const input = (c: Case): v1.GrantStatementInput => ({
      ...c.context,
      recipientIdentityPublicKey: unhex(c.recipient_identity_public_key),
      wrappedGrant: unhex(c.wrapped_grant),
    })
    const cases: [string, Case][] = [
      ['personal', g.personal],
      ['group', g.group],
    ]
    const priv = unhex(g.sign_seed)
    const pub = unhex(g.sign_public)

    it('the signing key is the identity bundle\'s Ed25519 half', () => {
      const id = v1.identityPublicFromPrivate({ kemX: new Uint8Array(32).fill(1), mlkemSeed: new Uint8Array(64).fill(2), sign: priv })
      expect(hex(id.sign)).toBe(g.sign_public)
    })

    it.each(cases)('%s: statement bytes and signature', (_name, c) => {
      const stmt = v1.grantStatement(input(c))
      expect(hex(v1.encodeStatement(stmt))).toBe(c.statement)
      expect(hex(v1.identitySign(priv, stmt))).toBe(c.signature)
      expect(v1.identityVerify(pub, stmt, unhex(c.signature))).toBe(true)
    })

    it.each(cases)('%s: the signature answers for nothing else', (_name, c) => {
      const sig = unhex(c.signature)
      const base = input(c)
      const flip = (b: Uint8Array) => {
        const out = b.slice()
        out[0] ^= 1
        return out
      }
      const variants: [string, v1.GrantStatementInput][] = [
        ['scopeId', { ...base, scopeId: otherValue(base.scopeId) as string }],
        // The signed kind is what keeps a group's genuine grant from being presented as the personal scope.
        ['scopeKind', { ...base, scopeKind: base.scopeKind === 'PERSONAL' ? 'GROUP' : 'PERSONAL' }],
        ['epoch', { ...base, epoch: base.epoch + 1 }],
        ['recipientUserId', { ...base, recipientUserId: otherValue(base.recipientUserId) as string }],
        ['recipientIdentityPublicKey', { ...base, recipientIdentityPublicKey: flip(base.recipientIdentityPublicKey) }],
        ['wrappedGrant', { ...base, wrappedGrant: flip(base.wrappedGrant) }],
      ]
      for (const [name, v] of variants) expect(v1.identityVerify(pub, v1.grantStatement(v), sig), name).toBe(false)
      // A foreign key, and malformed key/signature bytes the server could serve: a refusal, never a throw.
      expect(v1.identityVerify(unhex(vectors.secret_key_proof.key.public_key), v1.grantStatement(base), sig)).toBe(false)
      expect(v1.identityVerify(pub.slice(0, 31), v1.grantStatement(base), sig)).toBe(false)
      expect(v1.identityVerify(pub, v1.grantStatement(base), sig.slice(0, 63))).toBe(false)
    })

    it('refuses a statement whose kind is not a scope kind, or whose digests are not SHA-256 digests', () => {
      const stmt = v1.grantStatement({
        scopeId: g.personal.context.scopeId, scopeKind: 'PERSONAL', epoch: 0, recipientUserId: g.personal.context.recipientUserId,
        recipientIdentityPublicKey: new Uint8Array(1), wrappedGrant: new Uint8Array(1),
      })
      const bad = (patch: Record<string, unknown>) => () => v1.encodeStatement({ ...stmt, ...patch } as never)
      expect(bad({ scopeKind: 'OWNER' })).toThrow(new v1.SealContextInvalid('grant.scopeKind is not a scope kind'))
      expect(bad({ scopeKind: 7 })).toThrow(new v1.SealContextInvalid('grant.scopeKind is not a scope kind'))
      expect(bad({ wrappedGrantDigest: new Uint8Array(31) })).toThrow(new v1.SealContextInvalid('grant.wrappedGrantDigest is not a SHA-256 digest'))
      expect(bad({ recipientIdentityDigest: 'x'.repeat(32) })).toThrow(new v1.SealContextInvalid('grant.recipientIdentityDigest is not a SHA-256 digest'))
      // Every kind the product has encodes as its own name — the vectors pin PERSONAL and GROUP, this pins all three.
      for (const kind of ['TENANT', 'GROUP', 'PERSONAL'] as const) {
        const bytes = v1.encodeStatement({ ...stmt, scopeKind: kind })
        expect(new TextDecoder().decode(bytes)).toContain(kind)
      }
    })

    it('the statement\'s label domain-separates it from the admission tag and from every seal purpose', () => {
      const stmt = v1.encodeStatement(v1.grantStatement(input(g.personal)))
      expect(new TextDecoder().decode(stmt.slice(4, 9))).toBe('grant')
      expect(Object.keys(vectors.seal.sym)).not.toContain('grant')
      expect(Object.keys(vectors.seal.asym)).not.toContain('grant')
    })
  })

  describe('pairing channel (ADR 0059 §11)', () => {
    const p = vectors.pairing_channel
    const secret = unhex(p.secret)
    const key = unhex(p.channel_key)
    const answer: v1.PairingStatement = { purpose: 'pairing-answer', transportKey: unhex(p.answer.transport_key) }
    const signIn: v1.PairingStatement = {
      purpose: 'pairing-sign-in-answer',
      transportKey: unhex(p.sign_in_answer.transport_key),
      code: p.sign_in_answer.code,
    }
    const envelope: v1.PairingStatement = { purpose: 'pairing-envelope', envelope: unhex(p.envelope.envelope) }
    const cases: [string, v1.PairingStatement, { statement: string; mac: string }][] = [
      ['add-a-device answer', answer, p.answer],
      ['sign-in answer', signIn, p.sign_in_answer],
      ['envelope', envelope, p.envelope],
    ]

    it('derives the handle and the channel key from the secret under two labels', () => {
      expect(hex(v1.pairingHandle(secret))).toBe(p.handle)
      expect(hex(v1.pairingChannelKey(secret))).toBe(p.channel_key)
      expect(p.handle).not.toBe(p.channel_key) // the handle the server sees is not the key it must not
    })

    it.each(cases)('%s: statement bytes and MAC', (_name, stmt, c) => {
      expect(hex(v1.encodeStatement(stmt))).toBe(c.statement)
      expect(hex(v1.pairingMac(key, stmt))).toBe(c.mac)
      expect(v1.pairingMacVerifies(key, stmt, unhex(c.mac))).toBe(true)
    })

    // What each MAC answers for — and the relay's position: every field it could swap, and every key it holds
    // instead of the channel key (the handle is the one derivation it does see).
    it.each(cases)('%s: the MAC answers for nothing else', (_name, stmt, c) => {
      const mac = unhex(c.mac)
      const flip = (b: Uint8Array) => {
        const out = b.slice()
        out[0] ^= 1
        return out
      }
      const moved: v1.PairingStatement =
        stmt.purpose === 'pairing-envelope'
          ? { ...stmt, envelope: flip(stmt.envelope) }
          : { ...stmt, transportKey: flip(stmt.transportKey) }
      expect(v1.pairingMacVerifies(key, moved, mac)).toBe(false)
      expect(v1.pairingMacVerifies(unhex(p.handle), stmt, mac)).toBe(false)
      expect(v1.pairingMacVerifies(key, stmt, mac.slice(0, 31))).toBe(false)
      expect(v1.pairingMacVerifies(key, stmt, v1.concat(mac, Uint8Array.of(0)))).toBe(false)
      expect(v1.pairingMacVerifies(key, stmt, flip(mac))).toBe(false)
    })

    it('binds the transcribed code, and the two answer labels are not interchangeable', () => {
      const mac = unhex(p.sign_in_answer.mac)
      expect(v1.pairingMacVerifies(key, { ...signIn, code: '481503' } as v1.PairingStatement, mac)).toBe(false)
      // Same key bytes, the other direction's label: neither answer passes for the other.
      expect(v1.pairingMacVerifies(key, answer, mac)).toBe(false)
      expect(v1.pairingMacVerifies(key, signIn, unhex(p.answer.mac))).toBe(false)
    })

    it('refuses a code that is not six digits, and a field that is not bytes', () => {
      for (const code of ['48150', '4815022', '48150a', '４８１５０２']) {
        expect(() => v1.pairingMac(key, { ...signIn, code } as v1.PairingStatement)).toThrow(
          new v1.SealContextInvalid('pairing-sign-in-answer.code is not a six-digit pairing code'),
        )
      }
      expect(() => v1.pairingMac(key, { purpose: 'pairing-envelope', envelope: 'x' } as never)).toThrow(
        new v1.SealContextInvalid('pairing-envelope.envelope is not a byte string'),
      )
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
