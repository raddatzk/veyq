// veyq v1 crypto suite (TS), shared by web/ and admin-web/. Conformance: contracts/crypto/vectors/v1.json.
import { x25519, ed25519 } from '@noble/curves/ed25519.js'
import { ml_kem768 } from '@noble/post-quantum/ml-kem.js'
import { hkdf } from '@noble/hashes/hkdf.js'
import { hmac } from '@noble/hashes/hmac.js'
import { sha256 } from '@noble/hashes/sha2.js'
import { argon2id } from '@noble/hashes/argon2.js'
import { gcm } from '@noble/ciphers/aes.js'

const te = new TextEncoder()
export const utf8 = (s: string): Uint8Array => te.encode(s.normalize('NFC'))

export function concat(...arrs: Uint8Array[]): Uint8Array {
  const total = arrs.reduce((n, a) => n + a.length, 0)
  const out = new Uint8Array(total)
  let o = 0
  for (const a of arrs) {
    out.set(a, o)
    o += a.length
  }
  return out
}

const u32be = (n: number): Uint8Array => {
  const b = new Uint8Array(4)
  new DataView(b.buffer).setUint32(0, n >>> 0, false)
  return b
}
/** length-prefixed: u32(len) || bytes */
export const lp = (x: Uint8Array): Uint8Array => concat(u32be(x.length), x)

export type ArgonParams = { t: number; m: number; p: number; dkLen: number }
/** auth-path default: m in KiB = 32 MiB. Model B has no server-side verifier (ADR 0003). The one profile this
 * client writes into a wrap's kdfParams — and so the one `decodeKdfParams` accepts back (vectors carry their own). */
export const ARGON: ArgonParams = { t: 3, m: 32768, p: 1, dkLen: 32 }

/**
 * kdfParams wire format (crypto agility, AD-25): the unlock cost params travel WITH the wrap, so a later profile
 * stays readable once the client knows it. Layout: u32be t || m || p || dkLen || salt.
 */
export const encodeKdfParams = (p: ArgonParams, salt: Uint8Array): Uint8Array =>
  concat(u32be(p.t), u32be(p.m), u32be(p.p), u32be(p.dkLen), salt)

/** A served key-derivation profile this client does not write (ADR 0017 rule 5). Not a wrong password. */
export class KdfProfileRefused extends Error {
  constructor() {
    super('kdf_profile_refused')
    this.name = 'KdfProfileRefused'
  }
}

/**
 * The ONE gate between a served profile and Argon2id: every served kdfParams reaches a derivation only through
 * here. The server never chooses a cost parameter (ADR 0017 rule 5) — a profile is accepted only if it is the one
 * this client writes, `ARGON` field for field with a 16-byte salt, and anything else throws BEFORE any derivation,
 * because a served `t = 2³¹` or `m = 4 GiB` would run in a worker with no timeout and then again on the main
 * thread. A new profile is added here first and written only after every client knows it.
 */
export const decodeKdfParams = (b: Uint8Array): { params: ArgonParams; salt: Uint8Array } => {
  // The kdfParams come from the untrusted, operator-blind server; a short blob must fail cleanly rather
  // than throw an opaque DataView RangeError (which a malicious operator could use to crash the client).
  if (b.length < 16) throw new Error('malformed_kdf_params')
  const dv = new DataView(b.buffer, b.byteOffset, b.byteLength)
  const params = { t: dv.getUint32(0), m: dv.getUint32(4), p: dv.getUint32(8), dkLen: dv.getUint32(12) }
  const salt = b.slice(16)
  const written =
    params.t === ARGON.t && params.m === ARGON.m && params.p === ARGON.p && params.dkLen === ARGON.dkLen
  if (!written || salt.length !== 16) throw new KdfProfileRefused()
  return { params, salt }
}

/** HKDF-SHA256 as every v1 derivation uses it — exposed so the conformance suite can pin the RFC 5869 KAT. */
export const hkdfSha256 = (ikm: Uint8Array, salt: Uint8Array, info: Uint8Array, len: number): Uint8Array =>
  hkdf(sha256, ikm, salt, info, len)

export type HybridPublic = { x: Uint8Array; m: Uint8Array }
export type HybridSecret = { x: Uint8Array; m: Uint8Array }

export const aeadSeal = (key: Uint8Array, nonce: Uint8Array, pt: Uint8Array, aad: Uint8Array): Uint8Array =>
  gcm(key, nonce, aad).encrypt(pt)
export const aeadOpen = (key: Uint8Array, nonce: Uint8Array, ct: Uint8Array, aad: Uint8Array): Uint8Array =>
  gcm(key, nonce, aad).decrypt(ct)

function combiner(
  ssM: Uint8Array,
  ssX: Uint8Array,
  ctM: Uint8Array,
  epkX: Uint8Array,
  pkM: Uint8Array,
  pkX: Uint8Array,
): Uint8Array {
  const ikm = concat(lp(ssM), lp(ssX))
  const salt = sha256(concat(lp(ctM), lp(epkX), lp(pkM), lp(pkX)))
  return hkdf(sha256, ikm, salt, utf8('veyq/v1/kem'), 32)
}

export function kemEncap(
  pk: HybridPublic,
  opts: { eskX?: Uint8Array; mlkemMsg?: Uint8Array } = {},
): { ct: Uint8Array; ss: Uint8Array } {
  const esk = opts.eskX ?? crypto.getRandomValues(new Uint8Array(32))
  const epkX = x25519.getPublicKey(esk)
  const ssX = x25519.getSharedSecret(esk, pk.x)
  const enc = ml_kem768.encapsulate(pk.m, opts.mlkemMsg)
  const ss = combiner(enc.sharedSecret, ssX, enc.cipherText, epkX, pk.m, pk.x)
  return { ct: concat(epkX, enc.cipherText), ss }
}

export function kemDecap(sk: HybridSecret, pk: HybridPublic, ct: Uint8Array): Uint8Array {
  const epkX = ct.slice(0, 32)
  const ctM = ct.slice(32)
  const ssX = x25519.getSharedSecret(sk.x, epkX)
  const ssM = ml_kem768.decapsulate(ctM, sk.m)
  return combiner(ssM, ssX, ctM, epkX, pk.m, pk.x)
}

// AAD binds the envelope TYPE byte (0x02 sym / 0x01 asym) alongside the suite+context, so a ciphertext
// authenticated for one wrap type can never be reinterpreted as the other (defense in depth vs confusion).
const SYM_TYPE = 0x02
const ASYM_TYPE = 0x01
const wrapAad = (type: number, context: string): Uint8Array => concat(Uint8Array.of(type), utf8('v1' + context))

/** §5b symmetric wrap: 0x02 || nonce(12) || aead */
export function symWrap(kek: Uint8Array, targetKey: Uint8Array, context: string, nonce: Uint8Array): Uint8Array {
  return concat(Uint8Array.of(SYM_TYPE), nonce, aeadSeal(kek, nonce, targetKey, wrapAad(SYM_TYPE, context)))
}
export function symUnwrap(kek: Uint8Array, blob: Uint8Array, context: string): Uint8Array {
  return aeadOpen(kek, blob.slice(1, 13), blob.slice(13), wrapAad(SYM_TYPE, context))
}

/** §5a asymmetric wrap: 0x01 || ct(1120) || nonce(12) || aead */
export function asymWrap(
  pk: HybridPublic,
  targetKey: Uint8Array,
  context: string,
  opts: { eskX?: Uint8Array; mlkemMsg?: Uint8Array; nonce: Uint8Array },
): Uint8Array {
  const { ct, ss } = kemEncap(pk, opts)
  const kek = hkdf(sha256, ss, new Uint8Array(0), utf8('veyq/v1/wrap' + context), 32)
  return concat(Uint8Array.of(ASYM_TYPE), ct, opts.nonce, aeadSeal(kek, opts.nonce, targetKey, wrapAad(ASYM_TYPE, context)))
}
export function asymUnwrap(sk: HybridSecret, pk: HybridPublic, blob: Uint8Array, context: string): Uint8Array {
  const ct = blob.slice(1, 1 + 1120)
  const nonce = blob.slice(1 + 1120, 1 + 1120 + 12)
  const aead = blob.slice(1 + 1120 + 12)
  const ss = kemDecap(sk, pk, ct)
  const kek = hkdf(sha256, ss, new Uint8Array(0), utf8('veyq/v1/wrap' + context), 32)
  return aeadOpen(kek, nonce, aead, wrapAad(ASYM_TYPE, context))
}

/**
 * Blinded slug token (AD-26): a deterministic, keyed, one-way token of a slug, so the server can enforce
 * per-parent slug uniqueness without ever seeing the name. The slug key is derived PER PARENT —
 * slugKey = HKDF(contentKey, "veyq/v1/slug-token:" + parentSlot) — so the same slug under different parents
 * yields different tokens (no cross-parent name-equality leak). `parentSlot` is the node's parent slot:
 * the parent node id, or the scope id for a root-level node (matching the server's parent_id sentinel).
 * token = HMAC-SHA256(slugKey, NFC(slug)).
 */
export function slugToken(contentKey: Uint8Array, parentSlot: string, slug: string): Uint8Array {
  const slugKey = hkdf(sha256, contentKey, new Uint8Array(0), utf8('veyq/v1/slug-token:' + parentSlot), 32)
  return hmac(sha256, slugKey, utf8(slug))
}

/**
 * Media dedup token (AD-09): a per-scope, keyed token of the plaintext so the blind server can deduplicate
 * identical media among members WITHOUT a confirmation-of-file attack (it can't compute tokens — the key is
 * client-held). dedupKey = HKDF(contentKey, "veyq/v1/media-dedup"); token = HMAC-SHA256(dedupKey, SHA256(content)).
 * Same plaintext in a scope → same token → the server stores one ciphertext (ref-counted), all members decrypt
 * it via the shared content key. An ex-member who kept the key can test existence but cannot decrypt.
 */
export function dedupToken(contentKey: Uint8Array, content: Uint8Array): Uint8Array {
  const dedupKey = hkdf(sha256, contentKey, new Uint8Array(0), utf8('veyq/v1/media-dedup'), 32)
  return hmac(sha256, dedupKey, sha256(content))
}

export const sha256Digest = (content: Uint8Array): Uint8Array => sha256(content)

/** cr-0118 (ADR 0037): a share-password token = Argon2id(password, salt). The client presents it (base64) as the
 *  guest bearer credential; its `sha256Digest` is the server-checked verifier. Reuses the pinned Argon2id params. */
export const sharesPwToken = (password: string, salt: Uint8Array, params: ArgonParams = ARGON): Uint8Array =>
  argon2id(utf8(password), salt, { ...params, dkLen: 32 })

/**
 * Recovery commitment (AD: password reset). A domain-separated hash of the master key, stored server-side
 * when the RECOVERY wrap is written. Password reset must reproduce it — which needs the master key, i.e. the
 * Secret Key — so an email-token-only attacker cannot hijack the server account. Not a secret: the master key
 * is 32 random bytes and the hash is not invertible.
 */
export function recoveryAuthTag(masterKey: Uint8Array): Uint8Array {
  return sha256(concat(utf8('veyq/v1/recovery-auth'), masterKey))
}

/**
 * Journal sealing (live relay frames + persisted snapshot/update blobs). Each message gets a FRESH per-message
 * sub-key derived from a random 16-byte salt: salt(16) || nonce(12) || AES-256-GCM(HKDF(contentKey, salt,
 * "veyq/v1/journal"), nonce, plaintext). Because every blob carries its own salt, every GCM key is used exactly
 * once, so the random 96-bit nonce can never collide under one long-lived content key (no birthday bound, even
 * under months of keystroke-rate co-editing). Anyone with the content key derives the sub-key from the salt.
 */
export function journalSeal(contentKey: Uint8Array, plaintext: Uint8Array): Uint8Array {
  const salt = randomBytes(16)
  const subkey = hkdf(sha256, contentKey, salt, utf8('veyq/v1/journal'), 32)
  const nonce = randomBytes(12)
  return concat(salt, nonce, aeadSeal(subkey, nonce, plaintext, new Uint8Array(0)))
}
export function journalOpen(contentKey: Uint8Array, blob: Uint8Array): Uint8Array {
  const subkey = hkdf(sha256, contentKey, blob.slice(0, 16), utf8('veyq/v1/journal'), 32)
  return aeadOpen(subkey, blob.slice(16, 28), blob.slice(28), new Uint8Array(0))
}

/**
 * Chunked-AEAD frame (resumable upload + segmented streaming): a large blob is split into independent,
 * self-contained frames so chunks resume individually and segments decrypt without the whole file. Each
 * frame = nonce(12) || AEAD(contentKey, nonce, plaintextChunk, aad) with aad = u32be(index) || u32be(total),
 * binding the chunk's position so a dropped, reordered or appended frame fails to open (anti-truncation).
 */
const frameAad = (index: number, total: number): Uint8Array => concat(u32be(index), u32be(total))
export function sealFrame(contentKey: Uint8Array, index: number, total: number, plaintext: Uint8Array, nonce: Uint8Array): Uint8Array {
  return concat(nonce, aeadSeal(contentKey, nonce, plaintext, frameAad(index, total)))
}
export function openFrame(contentKey: Uint8Array, index: number, total: number, frame: Uint8Array): Uint8Array {
  const n = CHUNK_FRAME_SIZE.nonceBytes
  return aeadOpen(contentKey, frame.slice(0, n), frame.slice(n), frameAad(index, total))
}

/**
 * The frame size — a contract constant (vector `chunk_frame_size`, ADR 0013 §3), pinned by the conformance test.
 * Every frame but the last carries exactly `plaintextBytes`; the last carries the remainder, and an empty blob is
 * one frame carrying nothing. The backend derives `chunkCount` from `byteSize` on exactly this, and range
 * streaming (ADR 0047) seeks on it, so a client that chunked differently would be refused at upload.
 */
export const CHUNK_FRAME_SIZE = { plaintextBytes: 262144, nonceBytes: 12, tagBytes: 16 } as const
/** What sealing adds to a frame's plaintext: its nonce in front, its tag behind. */
export const FRAME_OVERHEAD = CHUNK_FRAME_SIZE.nonceBytes + CHUNK_FRAME_SIZE.tagBytes

export const kekPasswordSecret = (
  secretKey: Uint8Array,
  password: string,
  salt: Uint8Array,
  params: ArgonParams = ARGON,
): Uint8Array => argon2id(concat(lp(secretKey), lp(utf8(password))), salt, params)

export const kekRecovery = (recoveryCode: Uint8Array, salt: Uint8Array, params: ArgonParams = ARGON): Uint8Array =>
  argon2id(recoveryCode, salt, params)

/**
 * DEVICE-wrap KEK (uc.account.trust-device): `Argon2id(deviceFactor + password)`. Same construction as
 * kekPasswordSecret — factor first, password second — but the device-bound factor (the HMAC of a
 * non-extractable WebCrypto key, computed by the platform glue) replaces the cold Secret Key, so a trusted
 * device unlocks with only the password. v1 takes the already-derived `deviceFactor` bytes; it never sees
 * the non-extractable key itself.
 */
export const kekDevicePassword = (
  deviceFactor: Uint8Array,
  password: string,
  salt: Uint8Array,
  params: ArgonParams = ARGON,
): Uint8Array => argon2id(concat(lp(deviceFactor), lp(utf8(password))), salt, params)

// ── Operator-blind password auth (ADR 0003 / Model B) ────────────────────────────────────────────────
// The password is never sent to the server. Instead the client derives an Ed25519 *auth keypair* from the
// password and registers only `authPublicKey`; login is challenge-response (the client signs a server nonce).
// `seed = Argon2id(password, authSalt)` is domain-separated from the unlock KEK (`Argon2id(secretKey+password)`)
// by its distinct, auth-only salt — they share no inputs and never collide.

/**
 * Deterministic per-account auth salt for the password→keypair derivation (ADR 0003):
 * `authSalt = HKDF(ikm = normalizedEmail, salt = instanceSalt, info = "veyq/v1/auth-salt")`.
 *
 * The `instanceSalt` is the instance-wide public constant served by GET /api/setup (registration) and the
 * login challenge response — binding the salt to both the instance and the email. The SAME value must be
 * used at registration and at login, or the derived keypairs diverge and no signature verifies.
 */
export const deriveAuthSalt = (instanceSalt: Uint8Array, email: string): Uint8Array =>
  hkdf(sha256, utf8(email.trim().toLowerCase()), instanceSalt, utf8('veyq/v1/auth-salt'), 16)

export type AuthKeypair = { publicKey: Uint8Array; privateKey: Uint8Array }

/** Derive the Ed25519 auth keypair from the password + per-account auth salt (ADR 0003). */
export function deriveAuthKeypair(password: string, authSalt: Uint8Array, params: ArgonParams = ARGON): AuthKeypair {
  const seed = argon2id(utf8(password), authSalt, { ...params, dkLen: 32 })
  return { privateKey: seed, publicKey: ed25519.getPublicKey(seed) }
}

/** Sign a server login nonce with the auth private key (Ed25519) — proves possession at login (ADR 0003). */
export const authSign = (privateKey: Uint8Array, message: Uint8Array): Uint8Array => ed25519.sign(message, privateKey)

/** Verify an Ed25519 auth signature (the server's job; exposed for tests + future identity checks). */
export const authVerify = (publicKey: Uint8Array, message: Uint8Array, signature: Uint8Array): boolean =>
  ed25519.verify(signature, message, publicKey)

// ── Secret-Key proof key (ADR 0060 Decision 2, ADR 0054 Decision 1) ─────────────────────────────────────
// The server half of the cold-credential gate. Every flow that MINTS a cold credential — rotation, change-password,
// recovery completion — carries an Ed25519 signature under a key derived from the Secret Key ALONE, verified
// against the public half registered at account birth. It is the one factor both attackers ADR 0060 names lack: a
// linked device holds the Master Key and never saw the Secret Key, a phished password is not the Secret Key. A
// proof derived from the Master Key (the retired recovery tag) is one a linked device can compute and a database
// dump contains; one derived from the PASSWORD floor's KEK would change on every password change and cannot exist
// on the recovery path (ADR 0060 (i)).
//
// Each statement is `lp(domain) || lp(field)…`: domain-separated per flow so a signature for one can never be
// presented to another, and binding what the flow installs AND the state it replaces, so a captured signature
// cannot be replayed once that state has moved. Every field is the DECODED bytes of the wire value, never its
// base64 text — so both stacks rebuild it from bytes, not from an encoding either could spell differently. The
// backend rebuilds the same bytes itself; `secret_key_proof` in the vectors pins both stacks to one encoding.

/** `seed = HKDF-SHA256(ikm = secretKey, salt = "", info = "veyq/v1/secret-key-proof")`, and from it an Ed25519
 *  keypair. Deterministic: the Secret Key never changes in these flows, so neither does this key. */
export function deriveSecretKeyProofKeypair(secretKey: Uint8Array): AuthKeypair {
  const seed = hkdf(sha256, secretKey, new Uint8Array(0), utf8('veyq/v1/secret-key-proof'), 32)
  return { privateKey: seed, publicKey: ed25519.getPublicKey(seed) }
}

/** One wrap as a statement sees it: its ciphertext and its KDF params, both decoded. */
export type ProofWrap = { wrappedKey: Uint8Array; kdfParams: Uint8Array }

const wrapDigest = (w: ProofWrap): Uint8Array => sha256(concat(lp(w.wrappedKey), lp(w.kdfParams)))

const statement = (domain: string, ...fields: Uint8Array[]): Uint8Array =>
  concat(lp(utf8(domain)), ...fields.map(lp))

/**
 * Rotation (uc.account.rotate-keys): the identity it replaces, and everything it installs — the new identity
 * public bundle, the new identity private key under the new Master Key, and both rewritten cold wraps. Binding the
 * CURRENT identity is what makes a replay fail after the rotation it authorised: that identity is gone.
 */
export const rotationProofStatement = (s: {
  currentIdentityPublicKey: Uint8Array
  identityPublicKey: Uint8Array
  wrappedIdentityPrivateKey: Uint8Array
  passwordWrap: ProofWrap
  recoveryWrap: ProofWrap
}): Uint8Array =>
  statement(
    'veyq/v1/secret-key-proof/rotation',
    sha256(s.currentIdentityPublicKey),
    sha256(s.identityPublicKey),
    sha256(s.wrappedIdentityPrivateKey),
    wrapDigest(s.passwordWrap),
    wrapDigest(s.recoveryWrap),
  )

/** Change-password (uc.account.change-password): the auth key it replaces, the one it installs, and the new
 *  `PASSWORD` floor. */
export const passwordChangeProofStatement = (s: {
  currentAuthPublicKey: Uint8Array
  authPublicKey: Uint8Array
  passwordWrap: ProofWrap
}): Uint8Array =>
  statement(
    'veyq/v1/secret-key-proof/change-password',
    s.currentAuthPublicKey,
    s.authPublicKey,
    wrapDigest(s.passwordWrap),
  )

/** Recovery completion (uc.account.recover-access): the one-time token it spends — single-use and bound to one
 *  account, so it is the replaced state — the new auth key, and the new `PASSWORD` floor. */
export const recoveryProofStatement = (s: {
  token: string
  authPublicKey: Uint8Array
  passwordWrap: ProofWrap
}): Uint8Array =>
  statement(
    'veyq/v1/secret-key-proof/recovery',
    sha256(utf8(s.token)),
    s.authPublicKey,
    wrapDigest(s.passwordWrap),
  )

/** Sign a statement with the proof key's private half. Ed25519 is deterministic, so the vectors pin signatures. */
export const secretKeyProofSign = (privateKey: Uint8Array, stmt: Uint8Array): Uint8Array => ed25519.sign(stmt, privateKey)

export const randomBytes = (n: number): Uint8Array => crypto.getRandomValues(new Uint8Array(n))

/** The user's identity: a hybrid KEM keypair (X25519 + ML-KEM-768) + an Ed25519 signing keypair. */
export type Identity = {
  pub: { kemX: Uint8Array; kemM: Uint8Array; sign: Uint8Array }
  priv: { kemX: Uint8Array; mlkemSeed: Uint8Array; sign: Uint8Array }
}

/**
 * Build a HybridSecret usable for kemDecap/asymUnwrap from a stored private bundle. noble decapsulates
 * with the *expanded* ML-KEM secret key, so we re-derive it from the stored 64-byte seed (the other
 * stacks reconstruct from the seed directly).
 */
export const hybridSecret = (priv: Identity['priv']): HybridSecret => ({
  x: priv.kemX,
  m: ml_kem768.keygen(priv.mlkemSeed).secretKey,
})

/** The hybrid KEM PUBLIC bundle re-derived from a stored private bundle — for sealing (asymWrap) to one's
 *  own identity (e.g. an owner's first scope grant). Pure derivation; mirrors [hybridSecret]. */
export const hybridPublic = (priv: Identity['priv']): HybridPublic => ({
  x: x25519.getPublicKey(priv.kemX),
  m: ml_kem768.keygen(priv.mlkemSeed).publicKey,
})

/** The full identity PUBLIC bundle re-derived from a stored private bundle — kemX, kemM (from the ML-KEM seed)
 *  and the Ed25519 signing key. For rendering one's OWN safety-number / QR in the verify ceremony (cr-0099)
 *  without re-fetching from the directory. Mirrors [hybridPublic] but also carries the signing key. */
export const identityPublicFromPrivate = (priv: Identity['priv']): Identity['pub'] => ({
  kemX: x25519.getPublicKey(priv.kemX),
  kemM: ml_kem768.keygen(priv.mlkemSeed).publicKey,
  sign: ed25519.getPublicKey(priv.sign),
})

export function generateIdentity(): Identity {
  const kemX = randomBytes(32)
  const mlkemSeed = randomBytes(64)
  const mk = ml_kem768.keygen(mlkemSeed)
  const sign = randomBytes(32)
  return {
    pub: { kemX: x25519.getPublicKey(kemX), kemM: mk.publicKey, sign: ed25519.getPublicKey(sign) },
    priv: { kemX, mlkemSeed, sign },
  }
}

/** Canonical identity PUBLIC serialization: lp(kemX) || lp(kemM) || lp(sign) — mirrors the private form. */
export const serializeIdentityPublic = (pub: Identity['pub']): Uint8Array =>
  concat(lp(pub.kemX), lp(pub.kemM), lp(pub.sign))

/**
 * Identity fingerprint ("safety number"): a deterministic, human-readable digest of a user's FULL identity
 * public key (hybrid KEM + signing), so two users can compare it out-of-band (read aloud / QR) and detect an
 * active-operator key-substitution — a swapped kemX, kemM or sign changes the digits. All three components are
 * covered via the length-prefixed canonical serialization (no field-boundary ambiguity). The digest is sha256
 * over a domain-separated input, rendered as 6 groups of 5 decimal digits (Signal-parity, language-neutral):
 * each group is a 40-bit big-endian hash chunk mod 100000. 30 digits carry ~99 bits, so grinding a substitute
 * key whose fingerprint matches the display is infeasible.
 *
 * This is VERIFICATION only — the trust anchor stays a client-pinned key; full Key Transparency is the later
 * end-state. Conformance: contracts/crypto/vectors/v1.json#fingerprint.
 */
export function fingerprint(pub: Identity['pub']): string {
  const h = sha256(concat(utf8('veyq/v1/fingerprint'), serializeIdentityPublic(pub)))
  const groups: string[] = []
  for (let i = 0; i < 6; i++) {
    let v = 0
    for (let j = 0; j < 5; j++) v = v * 256 + h[i * 5 + j] // 40-bit big-endian, exact in JS (< 2^53)
    groups.push(String(v % 100000).padStart(5, '0'))
  }
  return groups.join(' ')
}

/** Serialize the identity private bundle (wrapped under the master key). */
export const serializeIdentityPrivate = (id: Identity): Uint8Array =>
  concat(lp(id.priv.kemX), lp(id.priv.mlkemSeed), lp(id.priv.sign))

/** Inverse of serializeIdentityPublic: read three length-prefixed fields → a public identity bundle. Used to
 *  wrap a scope key to *another* member's published identity (asymWrap to a foreign identity — cr-0076). */
export function deserializeIdentityPublic(bytes: Uint8Array): Identity['pub'] {
  let o = 0
  const next = (): Uint8Array => {
    const len = new DataView(bytes.buffer, bytes.byteOffset + o, 4).getUint32(0, false)
    o += 4
    const v = bytes.slice(o, o + len)
    o += len
    return v
  }
  return { kemX: next(), kemM: next(), sign: next() }
}

/** Inverse of serializeIdentityPrivate: read three length-prefixed fields. */
export function deserializeIdentityPrivate(bytes: Uint8Array): Identity['priv'] {
  let o = 0
  const next = (): Uint8Array => {
    const len = new DataView(bytes.buffer, bytes.byteOffset + o, 4).getUint32(0, false)
    o += 4
    const v = bytes.slice(o, o + len)
    o += len
    return v
  }
  return { kemX: next(), mlkemSeed: next(), sign: next() }
}
