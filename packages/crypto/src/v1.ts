// veyq v1 crypto suite (TS), shared by web/ and admin-web/. Conformance: contracts/crypto/vectors/v1.json.
// Everything here is NOT a seal (KDFs, identity, fingerprint, tokens, frames) — plus the typed seal API
// re-exported from ./seal.ts, which is the only way this package lets a consumer seal (ADR 0072 Decision 6).
import { ed25519 } from '@noble/curves/ed25519.js'
import { hkdf } from '@noble/hashes/hkdf.js'
import { hmac } from '@noble/hashes/hmac.js'
import { sha256 } from '@noble/hashes/sha2.js'
import { argon2id } from '@noble/hashes/argon2.js'
import { aeadOpen, aeadSeal, concat, kemPublic, lp, u32be, utf8, type HybridPublic, type HybridSecret } from './primitives'
import { encodeStatement, type ScopeKind, type StatementContext } from './seal'

export { concat, lp, utf8, type HybridPublic, type HybridSecret }
export {
  seal,
  open,
  sealTo,
  openAs,
  sealField,
  openField,
  openFieldWith,
  fieldKeyOf,
  encodeSealContext,
  encodeSealToContext,
  encodeFieldContext,
  encodeStatement,
  OpenRefused,
  SealContextInvalid,
  type BlobRole,
  type SealContext,
  type SealToContext,
  type SealPurpose,
  type SealToPurpose,
  type FieldContext,
  type FieldPurpose,
  type ScopeKind,
  type StatementContext,
} from './seal'
export {
  sealBlobMeta,
  openBlobMeta,
  encodeBlobMeta,
  decodeBlobMeta,
  BlobMetaInvalid,
  BLOB_META,
  type BlobMeta,
  type OpenedBlobMeta,
} from './blobMeta'
export {
  REPORT,
  newReportKey,
  encodeReportKeys,
  decodeReportKeys,
  encodeReportBody,
  decodeReportBody,
  sealReport,
  openReport,
  encodeReportContact,
  decodeReportContact,
  sealReportContact,
  openReportContact,
  sealReportShare,
  openReportShare,
  encodeModerationNote,
  decodeModerationNote,
  sealModerationNote,
  openModerationNote,
  newFactsKey,
  sealStatementFacts,
  openStatementFacts,
  wrapFactsKey,
  unwrapFactsKey,
  sealObjection,
  openObjection,
  sealOrderReference,
  openOrderReference,
  sealStepFacts,
  openStepFacts,
  ReportInvalid,
  type ReportKey,
  type ReportBody,
  type ReportItemAddress,
} from './report'

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
 * Share write token (ADR 0076): proof that a guest naming themself on a link holds the link's key, not only its
 * id. writeToken = HKDF(K_share, "veyq/v1/share-write:" + shareId) — a label of its own, so it opens no seal and
 * says nothing about `K_share`, and the share id in the info binds it to its link (ADR 0072). The share stores
 * only `sha256Digest(writeToken)`; a database copy holds that hash, never the token.
 */
export function shareWriteToken(kShare: Uint8Array, shareId: string): Uint8Array {
  return hkdf(sha256, kShare, new Uint8Array(0), utf8('veyq/v1/share-write:' + shareId), 32)
}

/**
 * Media dedup token (AD-09) — pinned, NOT USED. Content dedup is declined (ADR 0013 §5): every blob has a random
 * K_blob, and no client calls this. The derivation stays fixed for a deliberate later dedup slice (ADR 0024):
 * dedupKey = HKDF(contentKey, "veyq/v1/media-dedup"); token = HMAC-SHA256(dedupKey, SHA256(content)).
 * Keyed so the blind server cannot compute tokens itself (no confirmation-of-file attack) — but storing one
 * ciphertext for equal tokens would still show the operator which blobs are equal, the leak ADR 0013 declined.
 */
export function dedupToken(contentKey: Uint8Array, content: Uint8Array): Uint8Array {
  const dedupKey = hkdf(sha256, contentKey, new Uint8Array(0), utf8('veyq/v1/media-dedup'), 32)
  return hmac(sha256, dedupKey, sha256(content))
}

export const sha256Digest = (content: Uint8Array): Uint8Array => sha256(content)

/** An incremental SHA-256 for content too large to hold: `update` chunk by chunk, `digest` once. The subtree export
 *  (ADR 0082) hashes each payload while it streams past; the result is `sha256Digest` of the whole. */
export const sha256Stream = (): { update(chunk: Uint8Array): void; digest(): Uint8Array } => {
  const h = sha256.create()
  return { update: (chunk) => void h.update(chunk), digest: () => h.digest() }
}

/** cr-0118 (ADR 0037): a share-password token = Argon2id(password, salt). The client presents it (base64) as the
 *  guest bearer credential; its `sha256Digest` is the server-checked verifier. Reuses the pinned Argon2id params. */
export const sharesPwToken = (password: string, salt: Uint8Array, params: ArgonParams = ARGON): Uint8Array =>
  argon2id(utf8(password), salt, { ...params, dkLen: 32 })

/**
 * Chunked-AEAD frame (resumable upload + segmented streaming): a large blob is split into independent,
 * self-contained frames so chunks resume individually and segments decrypt without the whole file. Each
 * frame = nonce(12) || AEAD(contentKey, nonce, plaintextChunk, aad) with aad = u32be(index) || u32be(total),
 * binding the chunk's position so a dropped, reordered or appended frame fails to open (anti-truncation).
 * Outside the typed seal union on purpose (ADR 0072 Decision 2): `contentKey` here is the blob's own `K_blob`,
 * unique to one blob, so the key already is the object binding — and the `K_blob` wrap itself is bound to its blob.
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
//
// There is no user id in them, and nothing is lost: the server verifies under the CALLER's stored proof key, so a
// statement cannot be carried to another account, and the replaced state it binds (identity, auth key, token) is
// already that account's alone. (The recovery path could not carry one anyway: it has no session to read it from.)

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

/** The one email normalization (trim, lower-case) — `deriveAuthSalt`'s rule, and the server's `normalizeEmail`. A
 *  statement binds the address as both stacks store and salt it, never as it happened to be typed. */
const normalizedEmail = (email: string): Uint8Array => utf8(email.trim().toLowerCase())

/** Change-email (uc.account.change-email): the auth key it replaces, the one derived under the new address's salt,
 *  and that address. Not the password change's statement: there is no wrap to bind here, and the address is what
 *  this flow installs — so a new domain, with the address in the place the wrap digest holds there. */
export const emailChangeProofStatement = (s: {
  currentAuthPublicKey: Uint8Array
  authPublicKey: Uint8Array
  email: string
}): Uint8Array =>
  statement(
    'veyq/v1/secret-key-proof/change-email',
    s.currentAuthPublicKey,
    s.authPublicKey,
    normalizedEmail(s.email),
  )

/**
 * The attestation a change-email carries (uc.account.change-email): signed with the CURRENT auth private key (the
 * password being kept), over the account, the new address and the auth key derived under it. A password change's
 * attestation signs the new key's raw bytes alone; this one is domain-separated and binds the address, so neither
 * can stand for the other, and a captured one cannot install its key under another address or account.
 */
export const emailChangeAttestationStatement = (s: {
  userId: string
  email: string
  authPublicKey: Uint8Array
}): Uint8Array =>
  statement('veyq/v1/auth-attestation/change-email', utf8(s.userId), normalizedEmail(s.email), s.authPublicKey)

/**
 * The password proof a member's own account erasure carries (uc.account.erase-account, ADR 0101 decision 1): signed
 * with the account's auth private key over the account and `nonce` — the DECODED bytes of a login challenge the server
 * minted for the account's own address and spends with the request, so a captured proof erases nothing twice. Its own
 * domain, so no other attestation stands for it. Password only: no Secret Key proof rides with it.
 */
export const eraseAccountAttestationStatement = (s: { userId: string; nonce: Uint8Array }): Uint8Array =>
  statement('veyq/v1/auth-attestation/erase-account', utf8(s.userId), s.nonce)

/** Sign a statement with the proof key's private half. Ed25519 is deterministic, so the vectors pin signatures. */
export const secretKeyProofSign = (privateKey: Uint8Array, stmt: Uint8Array): Uint8Array => ed25519.sign(stmt, privateKey)

export const randomBytes = (n: number): Uint8Array => crypto.getRandomValues(new Uint8Array(n))

// ── Admission tag (ADR 0020 move 6) ─────────────────────────────────────────────────────────────────────
// An owner's SESSION proves a password, not a key. So every membership that is to receive a grant carries a tag
// only a holder of the scope's current epoch key can compute, and no client wraps a scope key to anyone whose tag
// does not verify. `HMAC-SHA256(HKDF(E_epoch, salt = "", info = "veyq/v1/admission-tag"), statement)`, the
// statement being the canonical `admission-tag` encoding. A MAC, not a signature: it needs no trust root, and any
// key-holder computing it is no stronger than what a key-holder can already do with the key itself.

/** What an admission tag binds: the scope, the epoch whose key made it, the client-minted admission id, and the
 *  target's account for a group add (`null` for a tenant invite — the invitee has no account yet). */
export type Admission = { scopeId: string; epoch: number; admissionId: string; userId: string | null }

const ADMISSION_TAG_INFO = utf8('veyq/v1/admission-tag')

/** The admission tag under the epoch key `epochKey`, which must be the key of `a.epoch`. */
export function admissionTag(epochKey: Uint8Array, a: Admission): Uint8Array {
  const key = hkdf(sha256, epochKey, new Uint8Array(0), ADMISSION_TAG_INFO, 32)
  return hmac(sha256, key, encodeStatement({ purpose: 'admission-tag', ...a }))
}

/** Whether `tag` is the admission tag of `a` under `epochKey`. Constant-time over the tag bytes. The CALLER decides
 *  which epoch key it holds — a worker passes its own current one, so a tag made before a rotation does not admit. */
export const admissionTagVerifies = (epochKey: Uint8Array, a: Admission, tag: Uint8Array): boolean =>
  macEquals(admissionTag(epochKey, a), tag)

/** Constant-time over the expected MAC's bytes; a MAC of any other length is refused first. */
function macEquals(expected: Uint8Array, mac: Uint8Array): boolean {
  if (mac.length !== expected.length) return false
  return expected.reduce((diff, byte, i) => diff | (byte ^ mac[i]), 0) === 0
}

// ── Item-mark self-tag (ADR 0068 §9) ────────────────────────────────────────────────────────────────────
// An item mark is sealed under the scope key and bound to (scope, node) only — the read names no member — so which
// entry is the caller's own is a claim of the server's (`self`). The tag inside the plaintext is what the caller's
// client checks it against: `HMAC-SHA256(HKDF(masterKey, salt = "", info = "veyq/v1/item-mark-self"), statement)`,
// the statement binding the scope, the node and the marked SET. The key is the marking member's alone — anything a
// co-member also holds would let them compute every member's tag — and the set makes it content-bound: a copied tag
// verifies for no other set, and two versions of one set carry unrelated tags. Conformance: v1.json#item_mark_self.

/** What a self-tag binds: the slot the marks are stored at and the marked media ids (any order, repeats allowed). */
export type ItemMarks = { scopeId: string; nodeId: string; ids: Iterable<string> }

/** `HKDF-SHA256(masterKey, salt = "", info = "veyq/v1/item-mark-self", 32)` — the caller's self-tag key. */
export const itemMarkSelfKey = (masterKey: Uint8Array): Uint8Array =>
  hkdf(sha256, masterKey, new Uint8Array(0), utf8('veyq/v1/item-mark-self'), 32)

/** SHA-256 over `lp(utf8(id))…` of the ids, de-duplicated and sorted — the set, independent of how it was listed.
 *  The ids are canonical lowercase UUIDs, so the default sort's code-unit order is their byte order. */
const marksDigest = (ids: Iterable<string>): Uint8Array =>
  sha256(concat(...[...new Set(ids)].sort().map((id) => lp(utf8(id)))))

/** The self-tag of `m` under the self-tag key `key` ({@link itemMarkSelfKey}). */
export const itemMarkSelfTag = (key: Uint8Array, m: ItemMarks): Uint8Array =>
  hmac(sha256, key, encodeStatement({ purpose: 'item-mark-self', scopeId: m.scopeId, nodeId: m.nodeId, marks: marksDigest(m.ids) }))

/** Whether `tag` is the self-tag of `m` under `key`. Constant-time over the tag bytes. */
export const itemMarkSelfTagVerifies = (key: Uint8Array, m: ItemMarks, tag: Uint8Array): boolean =>
  macEquals(itemMarkSelfTag(key, m), tag)

// ── Pairing channel (ADR 0059 §11) ──────────────────────────────────────────────────────────────────────
// The pairing relay sees both halves of each binding step, so the QR carries a secret the relay never sees: 32
// random bytes, from which both devices derive the routing handle (the ONLY value the server receives) and a
// channel key. The channel key MACs the two values the relay carries — the answer and the sealed grant — in the
// typed-statement encoding, so a relay that substitutes its own transport key (or its own grant) cannot make the
// MAC verify, however many codes or check numbers it grinds. It does not cover an attacker who both reads the QR
// and runs the relay: they hold the channel key. Conformance: contracts/crypto/vectors/v1.json#pairing_channel.

/** The MACed statements of the pairing channel. */
export type PairingStatement = Extract<
  StatementContext,
  { purpose: 'pairing-answer' | 'pairing-sign-in-answer' | 'pairing-envelope' }
>

/** `HKDF-SHA256(secret, salt = "", info = "veyq/v1/pairing-handle", 32)` — the channel's routing handle. */
export const pairingHandle = (secret: Uint8Array): Uint8Array =>
  hkdf(sha256, secret, new Uint8Array(0), utf8('veyq/v1/pairing-handle'), 32)

/** `HKDF-SHA256(secret, salt = "", info = "veyq/v1/pairing-channel", 32)` — the key only the QR's holders derive. */
export const pairingChannelKey = (secret: Uint8Array): Uint8Array =>
  hkdf(sha256, secret, new Uint8Array(0), utf8('veyq/v1/pairing-channel'), 32)

/** `HMAC-SHA256(channelKey, statement)`. */
export const pairingMac = (channelKey: Uint8Array, statement: PairingStatement): Uint8Array =>
  hmac(sha256, channelKey, encodeStatement(statement))

/** Whether `mac` is the pairing MAC of `statement` under `channelKey`. Constant-time over the MAC bytes. */
export const pairingMacVerifies = (channelKey: Uint8Array, statement: PairingStatement, mac: Uint8Array): boolean =>
  macEquals(pairingMac(channelKey, statement), mac)

// ── Grant signature (ADR 0028 Decision 6) ───────────────────────────────────────────────────────────────
// A seal to a public key needs only that key, so without a signature the server can plant a scope key of its own
// choosing. Every grant therefore carries its granter's Ed25519 identity signature over the `grant` statement, and
// the recipient checks it before the key inside is used. Domain-separated from `authSign` (a different key, over a
// server nonce) and from the Secret-Key proof statements by the statement's own label.

/** One grant as its signature sees it: the object it is bound to, and the two byte strings it commits to. */
export type GrantStatementInput = {
  scopeId: string
  scopeKind: ScopeKind
  epoch: number
  recipientUserId: string
  /** The DECODED recipient identity public bundle the grant was sealed to (`serializeIdentityPublic`). */
  recipientIdentityPublicKey: Uint8Array
  /** The DECODED grant ciphertext. */
  wrappedGrant: Uint8Array
}

/** The typed `grant` statement for `g` — the two byte strings reduced to their SHA-256 digests. */
export const grantStatement = (g: GrantStatementInput): Extract<StatementContext, { purpose: 'grant' }> => ({
  purpose: 'grant',
  scopeId: g.scopeId,
  scopeKind: g.scopeKind,
  epoch: g.epoch,
  recipientUserId: g.recipientUserId,
  recipientIdentityDigest: sha256(g.recipientIdentityPublicKey),
  wrappedGrantDigest: sha256(g.wrappedGrant),
})

/** Sign a statement with the identity's Ed25519 private half (`Identity['priv'].sign`). Only a typed statement can
 *  be signed, so this key never signs bytes whose meaning it does not name. Deterministic, so the vectors pin it. */
export const identitySign = (signPrivateKey: Uint8Array, statement: Extract<StatementContext, { purpose: 'grant' }>): Uint8Array =>
  ed25519.sign(encodeStatement(statement), signPrivateKey)

/** Verify an identity signature over a typed statement. False — never a throw — for a malformed key or signature:
 *  both come from the server, and a refusal is the answer either way. */
export function identityVerify(
  signPublicKey: Uint8Array,
  statement: Extract<StatementContext, { purpose: 'grant' }>,
  signature: Uint8Array,
): boolean {
  try {
    return ed25519.verify(signature, encodeStatement(statement), signPublicKey)
  } catch {
    return false
  }
}

// ── The instance moderation key (ADR 0099 § 2) ─────────────────────────────────────────────────────────
// One key per epoch for the whole instance: a 32-byte random M_n, drawn by admin-web (or veyq-admin) and never held by
// the server. From it, under labels of their own, an X-Wing key — reports seal to its public half — and an Ed25519 key,
// which signs the `moderation-proof` statement every key-proved moderation call carries. The epoch's public bundle is
// what the server publishes and stores; a moderator's client compares it with the one it derives on every unlock. M_n
// reaches a moderator only as a `moderation-grant` seal to their identity. Pinned by `moderation_key`,
// `moderation_proof` and `seal.asym['moderation-grant']` in the vectors.

/** The size of an epoch's `M_n`. */
export const MODERATION_KEY_BYTES = 32

/** An epoch's public bundle: the X-Wing encapsulation key reports seal to, and the Ed25519 key that checks a proof. */
export type ModerationBundle = { kem: HybridPublic; sign: Uint8Array }

const moderationSeed = (m: Uint8Array, label: 'kem' | 'sign'): Uint8Array => {
  if (!(m instanceof Uint8Array) || m.length !== MODERATION_KEY_BYTES) throw new TypeError('moderation key: not 32 bytes')
  return hkdf(sha256, m, new Uint8Array(0), utf8(`veyq/v1/moderation/${label}`), 32)
}

/** A fresh `M_n` — what a mint draws, seals to every moderator, and forgets. */
export const generateModerationKey = (): Uint8Array => randomBytes(MODERATION_KEY_BYTES)

/** The X-Wing decapsulation key of an epoch (its seed), for opening what was sealed to the epoch's bundle. */
export const moderationKemSecret = (m: Uint8Array): HybridSecret => moderationSeed(m, 'kem')

/** The public bundle `M_n` derives — what a mint uploads and what a moderator compares with the published one. */
export function moderationBundle(m: Uint8Array): ModerationBundle {
  return { kem: kemPublic(moderationSeed(m, 'kem')), sign: ed25519.getPublicKey(moderationSeed(m, 'sign')) }
}

/** Whether two bundles are the same key, byte for byte in both halves. */
export const moderationBundleEquals = (a: ModerationBundle, b: ModerationBundle): boolean =>
  bytesEqual(a.kem, b.kem) && bytesEqual(a.sign, b.sign)

const bytesEqual = (a: Uint8Array, b: Uint8Array): boolean => a.length === b.length && a.every((x, i) => x === b[i])

/** One key proof as its statement sees it: the server's challenge, the contract operation, the case (or none). */
export type ModerationProofInput = { challenge: Uint8Array; operationId: string; caseId: string | null }

/** The typed `moderation-proof` statement. */
export const moderationProofStatement = (p: ModerationProofInput): Extract<StatementContext, { purpose: 'moderation-proof' }> => ({
  purpose: 'moderation-proof',
  challenge: p.challenge,
  operationId: p.operationId,
  caseId: p.caseId,
})

/** Sign a key proof with the epoch's Ed25519 key, derived from `M_n`. Only the typed statement can be signed. */
export const moderationSign = (m: Uint8Array, statement: Extract<StatementContext, { purpose: 'moderation-proof' }>): Uint8Array =>
  ed25519.sign(encodeStatement(statement), moderationSeed(m, 'sign'))

/** Verify a key proof against an epoch's published signing key. False — never a throw — for malformed input. */
export function moderationVerify(
  signPublicKey: Uint8Array,
  statement: Extract<StatementContext, { purpose: 'moderation-proof' }>,
  signature: Uint8Array,
): boolean {
  try {
    return ed25519.verify(signature, encodeStatement(statement), signPublicKey)
  } catch {
    return false
  }
}

/** The user's identity: an X-Wing KEM keypair (X25519 + ML-KEM-768, ADR 0017 decision 2) + an Ed25519 signing
 *  keypair. The KEM's private half is its 32-byte seed — X-Wing's decapsulation key — and its public half the
 *  1216-byte encapsulation key derived from it. */
export type Identity = {
  pub: { kem: Uint8Array; sign: Uint8Array }
  priv: { kemSeed: Uint8Array; sign: Uint8Array }
}

/** The KEM secret `openAs` takes, from a stored private bundle: the seed itself, which X-Wing expands on use. */
export const hybridSecret = (priv: Identity['priv']): HybridSecret => priv.kemSeed

/** The KEM PUBLIC half re-derived from a stored private bundle — for sealing (`sealTo`) to one's own identity
 *  (e.g. an owner's first scope grant). Pure derivation; mirrors [hybridSecret]. */
export const hybridPublic = (priv: Identity['priv']): HybridPublic => kemPublic(priv.kemSeed)

/** The full identity PUBLIC bundle re-derived from a stored private bundle — the KEM's encapsulation key and the
 *  Ed25519 signing key. For rendering one's OWN safety-number / QR in the verify ceremony (cr-0099) without
 *  re-fetching from the directory. Mirrors [hybridPublic] but also carries the signing key. */
export const identityPublicFromPrivate = (priv: Identity['priv']): Identity['pub'] => ({
  kem: kemPublic(priv.kemSeed),
  sign: ed25519.getPublicKey(priv.sign),
})

export function generateIdentity(): Identity {
  const kemSeed = randomBytes(KEM_SEED_BYTES)
  const sign = randomBytes(32)
  return {
    pub: { kem: kemPublic(kemSeed), sign: ed25519.getPublicKey(sign) },
    priv: { kemSeed, sign },
  }
}

/** Canonical identity PUBLIC serialization: lp(kem) || lp(sign) — mirrors the private form. */
export const serializeIdentityPublic = (pub: Identity['pub']): Uint8Array => concat(lp(pub.kem), lp(pub.sign))

/**
 * Identity fingerprint ("safety number"): a deterministic, human-readable digest of a user's FULL identity
 * public key (X-Wing KEM + signing), so two users can compare it out-of-band (read aloud / QR) and detect an
 * active-operator key-substitution — a swapped kem or sign changes the digits. Both components are covered via the
 * length-prefixed canonical serialization (no field-boundary ambiguity). The digest is sha256
 * over a domain-separated input, rendered as 6 groups of 5 decimal digits (Signal-parity, language-neutral):
 * each group is a 40-bit big-endian hash chunk mod 100000. 30 digits carry ~99 bits, so grinding a substitute
 * key whose fingerprint matches the display is infeasible.
 *
 * This is VERIFICATION only — the trust anchor stays a client-pinned key; full Key Transparency is the later
 * end-state. Conformance: contracts/crypto/vectors/v1.json#fingerprint.
 */
export function fingerprint(pub: Identity['pub']): string {
  const h = fingerprintHash(pub)
  const groups: string[] = []
  for (let i = 0; i < 6; i++) {
    let v = 0
    for (let j = 0; j < 5; j++) v = v * 256 + h[i * 5 + j] // 40-bit big-endian, exact in JS (< 2^53)
    groups.push(String(v % 100000).padStart(5, '0'))
  }
  return groups.join(' ')
}

/**
 * The full 32-byte digest {@link fingerprint} renders its digits from — what a machine compares where a person
 * compares the digits: the safety-number QR carries it (ADR 0028 Decision 3, `web/src/features/account/safetyCode.ts`),
 * never the identity bundle itself. One derivation for both, so the QR and the digits cannot disagree about a key.
 */
export function fingerprintHash(pub: Identity['pub']): Uint8Array {
  return sha256(concat(utf8('veyq/v1/fingerprint'), serializeIdentityPublic(pub)))
}

/** Serialize the identity private bundle (wrapped under the master key). */
export const serializeIdentityPrivate = (id: Identity): Uint8Array => concat(lp(id.priv.kemSeed), lp(id.priv.sign))

/** X-Wing's encapsulation key (`pk_M ‖ pk_X`) and decapsulation key (the seed), draft -11 § 5. */
const KEM_PUBLIC_BYTES = 1216
const KEM_SEED_BYTES = 32

/** Read exactly one length-prefixed field per entry of `sizes`, each of exactly that size, and nothing after them.
 *  Every v1 length-prefixed codec reads through this, so each has ONE encoding per value: the server serves the
 *  identity bundles of other members, and pairing relies on the decoded key being byte for byte the MACed one. */
function readFixedLp(bytes: Uint8Array, sizes: readonly number[], what: string): Uint8Array[] {
  const out: Uint8Array[] = []
  let o = 0
  for (const size of sizes) {
    if (o + 4 > bytes.length) throw new Error(`${what}: truncated`)
    const len = new DataView(bytes.buffer, bytes.byteOffset + o, 4).getUint32(0, false)
    o += 4
    if (len !== size || o + len > bytes.length) throw new Error(`${what}: malformed`)
    out.push(bytes.slice(o, o + len))
    o += len
  }
  if (o !== bytes.length) throw new Error(`${what}: trailing bytes`)
  return out
}

/** Inverse of serializeIdentityPublic → a public identity bundle. Used to seal a scope key to *another* member's
 *  published identity (`sealTo` a foreign identity — cr-0076), so its bytes are the server's. */
export function deserializeIdentityPublic(bytes: Uint8Array): Identity['pub'] {
  const [kem, sign] = readFixedLp(bytes, [KEM_PUBLIC_BYTES, 32], 'identity public key')
  return { kem, sign }
}

/** Inverse of serializeIdentityPrivate. */
export function deserializeIdentityPrivate(bytes: Uint8Array): Identity['priv'] {
  const [kemSeed, sign] = readFixedLp(bytes, [KEM_SEED_BYTES, 32], 'identity private key')
  return { kemSeed, sign }
}

/**
 * A Memory's **drop keypair** (ADR 0031 point 7): an X-Wing KEM keypair with no signing half, held as its 32-byte
 * seed. Its private half is sealed under the Memory's `K_node` (`drop-private-key`), its public half under each
 * WRITE_ONLY share's `K_share` (`drop-public-key`). A WRITE_ONLY guest wraps its own identity key to the public half
 * (`upload-identity-key`), so the guest never holds `K_node` and every reader of the Memory opens what it uploaded.
 */
export type DropKey = { kemSeed: Uint8Array }

export const generateDropKey = (): DropKey => ({ kemSeed: randomBytes(KEM_SEED_BYTES) })

/** The drop key's public half, re-derived from the private half (the Memory stores only the private half). */
export const dropKeyPublic = (k: DropKey): HybridPublic => kemPublic(k.kemSeed)

/** The drop key as `openAs` takes it — the seed itself (cf. [hybridSecret]). */
export const dropKeySecret = (k: DropKey): HybridSecret => k.kemSeed

/** Drop private serialization: lp(kemSeed) — the plaintext of a `drop-private-key` seal. */
export const serializeDropKey = (k: DropKey): Uint8Array => lp(k.kemSeed)
export function deserializeDropKey(bytes: Uint8Array): DropKey {
  const [kemSeed] = readFixedLp(bytes, [KEM_SEED_BYTES], 'drop key')
  return { kemSeed }
}

/** KEM public serialization: lp(kem) — the plaintext of a `drop-public-key` seal. */
export const serializeHybridPublic = (p: HybridPublic): Uint8Array => lp(p)
export function deserializeHybridPublic(bytes: Uint8Array): HybridPublic {
  const [kem] = readFixedLp(bytes, [KEM_PUBLIC_BYTES], 'hybrid public key')
  return kem
}
