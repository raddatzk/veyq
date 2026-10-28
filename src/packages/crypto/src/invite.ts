// A member invite (ADR 0111 decisions 1-3): a placeholder member holding an owner-signed invite key.
//
//   - **The invite key** is an identity-shaped bundle (X-Wing KEM + Ed25519) derived from a 32-byte seed under labels of
//     its own (`inviteKeyFromSeed`), so a grant seals to it like to a member (`sealTo` purpose `scope-grant`) and it
//     signs like an identity (`identitySign`).
//   - **The owner signs it**: the `invite-key` statement over the tenant, the invite, SHA-256 of the invite bundle and
//     the expiry, with the owner's identity key.
//   - **The link carries the seed, sealed**: `https://<domain>/tenant/accept#<token>.<sealed>`, where `token` is the
//     server's single-use invite secret and `sealed` the base64url of `mode(1) ‖ kdfParams(32) ‖ seal`. The seal
//     (purpose `invite-link`, bound to the tenant and the invite) holds the seed, the owner's fingerprint hash and the
//     owner's account, under `Argon2id(secret, salt)` — the secret a generated code (`mode` 1) or a password the owner
//     chose (`mode` 2). The server mails the link and never sees the code, so the operator, who can guess offline,
//     faces a 50-bit code behind Argon2id at the production profile.
//   - **The invitee checks before it trusts**: the bundle it derives from the seed must be the one the server serves,
//     the served owner key must hash to the fingerprint inside the seal, and the owner's signature must verify under it
//     (`checkInvite`). Nothing it relies on comes from the server unchecked.
//   - **It grants itself and writes a join record**: the `invite-accept` proof (signed by the invite key, checked by the
//     server) and the join record — signed by the invite key AND the new identity over the `join-record` statement,
//     sealed under the tenant key as the field `member-join-record`.
//
// Pinned by `contracts/crypto/vectors/v1.json` (`#invite_key`, `#invite_link`, `#join_record`, `seal.sym['invite-link']`,
// `field_seal['member-join-record']`).
import { ed25519 } from '@noble/curves/ed25519.js'
import { hkdf } from '@noble/hashes/hkdf.js'
import { sha256 } from '@noble/hashes/sha2.js'
import { concat, kemPublic, lp, utf8 } from './primitives'
import { open, openField, seal, sealField } from './seal'
import {
  ARGON,
  decodeKdfParams,
  deserializeIdentityPublic,
  encodeKdfParams,
  fingerprintHash,
  identitySign,
  identityVerify,
  randomBytes,
  serializeIdentityPublic,
  type ArgonParams,
  type Identity,
} from './v1'
import { b64url, fromB64url } from './base64'

// ── The invite key ─────────────────────────────────────────────────────────────────────────────────────

/** The size of an invite key's seed — what the link carries. */
export const INVITE_SEED_BYTES = 32

/** A fresh seed — what the owner's client draws, seals into the link and forgets. */
export const generateInviteSeed = (): Uint8Array => randomBytes(INVITE_SEED_BYTES)

const inviteSubSeed = (seed: Uint8Array, label: 'kem' | 'sign'): Uint8Array => {
  if (!(seed instanceof Uint8Array) || seed.length !== INVITE_SEED_BYTES) throw new TypeError('invite seed: not 32 bytes')
  return hkdf(sha256, seed, new Uint8Array(0), utf8(`veyq/v1/invite/${label}`), 32)
}

/** The invite key a seed derives: an identity-shaped bundle, so a grant seals to it and it signs like an identity. */
export function inviteKeyFromSeed(seed: Uint8Array): Identity {
  const kemSeed = inviteSubSeed(seed, 'kem')
  const sign = inviteSubSeed(seed, 'sign')
  return { pub: { kem: kemPublic(kemSeed), sign: ed25519.getPublicKey(sign) }, priv: { kemSeed, sign } }
}

/** What the owner's signature on an invite key covers. `invitePublicKey` is the SERIALIZED bundle
 *  (`serializeIdentityPublic`), `expiresAt` the invite's expiry in whole Unix seconds. */
export type InviteKeyInput = { tenantId: string; inviteId: string; invitePublicKey: Uint8Array; expiresAt: number }

export const inviteKeyStatement = (i: InviteKeyInput) =>
  ({
    purpose: 'invite-key',
    tenantId: i.tenantId,
    inviteId: i.inviteId,
    inviteKeyDigest: sha256(i.invitePublicKey),
    expiresAt: i.expiresAt,
  }) as const

/** The owner signs the invite key with their identity signing key. */
export const signInviteKey = (ownerSignPrivateKey: Uint8Array, i: InviteKeyInput): Uint8Array =>
  identitySign(ownerSignPrivateKey, inviteKeyStatement(i))

/** Whether `signature` is the owner's (`ownerSignPublicKey`) over this invite key. False — never a throw. */
export const inviteKeySignatureVerifies = (ownerSignPublicKey: Uint8Array, i: InviteKeyInput, signature: Uint8Array): boolean =>
  identityVerify(ownerSignPublicKey, inviteKeyStatement(i), signature)

// ── The accept proof ──────────────────────────────────────────────────────────────────────────────────

/** What the invite key signs at its redeem: the invite, the new account, its identity bundle (serialized). */
export type InviteAcceptInput = { inviteId: string; userId: string; identityPublicKey: Uint8Array }

export const inviteAcceptStatement = (a: InviteAcceptInput) =>
  ({ purpose: 'invite-accept', inviteId: a.inviteId, userId: a.userId, identityDigest: sha256(a.identityPublicKey) }) as const

/** The invite key's proof that its holder redeems it for this account and identity. */
export const signInviteAccept = (inviteSignPrivateKey: Uint8Array, a: InviteAcceptInput): Uint8Array =>
  identitySign(inviteSignPrivateKey, inviteAcceptStatement(a))

// ── The link ──────────────────────────────────────────────────────────────────────────────────────────

/** How the link's secret was chosen: a generated code, or a password the owner typed. In the clear in the link, so
 *  the invitee's client asks for the right one; a flipped mode only derives another KEK, which opens nothing. */
export const INVITE_LINK_MODE = { code: 1, password: 2 } as const
export type InviteLinkMode = keyof typeof INVITE_LINK_MODE

/** Crockford's base32 alphabet: no I, L, O, U. A code is 10 of them — 50 bits. */
const CROCKFORD = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'
export const INVITE_CODE_LENGTH = 10

/** A fresh code: 10 Crockford base32 characters, each the low 5 bits of a random byte (256 is a multiple of 32, so
 *  every character is uniform). */
export const generateInviteCode = (): string =>
  Array.from(randomBytes(INVITE_CODE_LENGTH), (b) => CROCKFORD[b & 31]).join('')

/** A code as shown and passed on: two groups of five. */
export const formatInviteCode = (code: string): string => `${code.slice(0, 5)}-${code.slice(5)}`

/**
 * A typed code in its canonical form, or null when it cannot be one: case folded, spaces and hyphens dropped, and
 * Crockford's look-alikes read as the digit they stand for (I and L as 1, O as 0). U is not in the alphabet.
 */
export function normalizeInviteCode(typed: string): string | null {
  const s = typed
    .toUpperCase()
    .replace(/[\s-]/g, '')
    .replace(/[IL]/g, '1')
    .replace(/O/g, '0')
  if (s.length !== INVITE_CODE_LENGTH || [...s].some((c) => !CROCKFORD.includes(c))) return null
  return s
}

/** The bytes the KEK is stretched from: the canonical code's UTF-8, or the password's (NFC) exactly as typed. Null for
 *  a code that is no code — nothing to derive. */
export function inviteLinkSecret(mode: InviteLinkMode, typed: string): Uint8Array | null {
  if (mode === 'password') return typed.length > 0 ? utf8(typed) : null
  const code = normalizeInviteCode(typed)
  return code === null ? null : utf8(code)
}

/** What the link's seal holds: the invite key's seed, and who signed the invite — the owner's fingerprint hash and
 *  account. The fingerprint is inside the seal, so an operator cannot swap it together with the served key. */
export type InviteLinkPayload = { seed: Uint8Array; signerFingerprintHash: Uint8Array; signerUserId: string }

/** A link secret's address: the tenant and the invite it opens. */
export type InviteAddress = { tenantId: string; inviteId: string }

export class InviteLinkInvalid extends Error {
  constructor(reason: string) {
    super(`invite_link_invalid: ${reason}`)
    this.name = 'InviteLinkInvalid'
  }
}

const UUID_BYTES = 36

/** `lp(seed) ‖ lp(signerFingerprintHash) ‖ lp(utf8(signerUserId))`. */
export function encodeInviteLinkPayload(p: InviteLinkPayload): Uint8Array {
  if (p.seed.length !== INVITE_SEED_BYTES) throw new InviteLinkInvalid('seed')
  if (p.signerFingerprintHash.length !== 32) throw new InviteLinkInvalid('fingerprint')
  const userId = utf8(p.signerUserId)
  if (userId.length !== UUID_BYTES) throw new InviteLinkInvalid('signer')
  return concat(lp(p.seed), lp(p.signerFingerprintHash), lp(userId))
}

export function decodeInviteLinkPayload(b: Uint8Array): InviteLinkPayload {
  const [seed, fp, user] = readFixed(b, [INVITE_SEED_BYTES, 32, UUID_BYTES], (r) => new InviteLinkInvalid(r))
  return { seed, signerFingerprintHash: fp, signerUserId: new TextDecoder().decode(user) }
}

const HEADER_BYTES = 1 + 32 // mode ‖ kdfParams (16 bytes of params, a 16-byte salt)

/** A parsed link secret — still sealed. `params` passed the client's one-profile gate (`decodeKdfParams`). */
export type SealedInviteLink = { mode: InviteLinkMode; params: ArgonParams; salt: Uint8Array; sealed: Uint8Array }

/** The link's sealed part, given the KEK `Argon2id(secret, salt)` the caller derived through the worker. */
export function sealInviteLink(
  address: InviteAddress, mode: InviteLinkMode, kek: Uint8Array, salt: Uint8Array, payload: InviteLinkPayload,
): string {
  if (salt.length !== 16) throw new InviteLinkInvalid('salt')
  const sealed = seal({ purpose: 'invite-link', ...address }, kek, encodeInviteLinkPayload(payload))
  return b64url(concat(Uint8Array.of(INVITE_LINK_MODE[mode]), encodeKdfParams(ARGON, salt), sealed))
}

/** Parse a link's sealed part before any derivation: the mode and the profile, which must be the one this client
 *  writes — the link came through the server's mail, so a cost parameter in it is the operator's choice otherwise. */
export function parseSealedInviteLink(part: string): SealedInviteLink {
  let b: Uint8Array
  try {
    b = fromB64url(part)
  } catch {
    throw new InviteLinkInvalid('encoding')
  }
  if (b.length <= HEADER_BYTES) throw new InviteLinkInvalid('length')
  const mode = b[0] === INVITE_LINK_MODE.code ? 'code' : b[0] === INVITE_LINK_MODE.password ? 'password' : null
  if (mode === null) throw new InviteLinkInvalid('mode')
  const { params, salt } = decodeKdfParams(b.slice(1, HEADER_BYTES))
  return { mode, params, salt, sealed: b.slice(HEADER_BYTES) }
}

/** Open the link's payload with the KEK; `OpenRefused` for a wrong code or another invite's seal. */
export const openInviteLink = (address: InviteAddress, kek: Uint8Array, link: SealedInviteLink): InviteLinkPayload =>
  decodeInviteLinkPayload(open({ purpose: 'invite-link', ...address }, kek, link.sealed))

/** The fragment the mailed link carries: the token alone (an owner invite) or `token.sealed` (a member invite). The
 *  token is base64url and so holds no `.`. */
export function parseInviteFragment(fragment: string): { token: string; sealed: string | null } {
  const raw = fragment.startsWith('#') ? fragment.slice(1) : fragment
  const dot = raw.indexOf('.')
  if (dot < 0) return { token: raw, sealed: null }
  return { token: raw.slice(0, dot), sealed: raw.slice(dot + 1) || null }
}

// ── The invitee's checks ──────────────────────────────────────────────────────────────────────────────

/** What the server serves about an invite (the preview) — every byte of it unchecked until `checkInvite` ran. */
export type ServedInvite = InviteAddress & {
  invitePublicKey: Uint8Array
  inviteKeySignature: Uint8Array
  expiresAt: number
  signerUserId: string
  signerIdentityPublicKey: Uint8Array
}

/** Which check refused an invite. One refusal for the user ("ask for a new one"), one reason each for the tests. */
export type InviteCheckFailure = 'key_mismatch' | 'signer_mismatch' | 'fingerprint_mismatch' | 'bad_signature'

export class InviteCheckFailed extends Error {
  constructor(readonly reason: InviteCheckFailure) {
    super(`invite_check_failed: ${reason}`)
    this.name = 'InviteCheckFailed'
  }
}

/**
 * The invitee's checks, in the order an operator would have to beat them (ADR 0111 decision 1): the key the seed
 * derives must be the served one (the operator cannot choose the seed), the served signer must be the account and the
 * key the seal names (the fingerprint is inside the seal), and the owner's signature over the invite key must verify
 * under that key. Returns the derived invite key; throws {@link InviteCheckFailed} naming the first check that failed.
 */
export function checkInvite(payload: InviteLinkPayload, served: ServedInvite): Identity {
  const invite = inviteKeyFromSeed(payload.seed)
  if (!bytesEqual(serializeIdentityPublic(invite.pub), served.invitePublicKey)) throw new InviteCheckFailed('key_mismatch')
  if (payload.signerUserId !== served.signerUserId) throw new InviteCheckFailed('signer_mismatch')
  let signer: Identity['pub']
  try {
    signer = deserializeIdentityPublic(served.signerIdentityPublicKey)
  } catch {
    throw new InviteCheckFailed('fingerprint_mismatch')
  }
  if (!bytesEqual(fingerprintHash(signer), payload.signerFingerprintHash)) throw new InviteCheckFailed('fingerprint_mismatch')
  const signed = inviteKeySignatureVerifies(
    signer.sign,
    { tenantId: served.tenantId, inviteId: served.inviteId, invitePublicKey: served.invitePublicKey, expiresAt: served.expiresAt },
    served.inviteKeySignature,
  )
  if (!signed) throw new InviteCheckFailed('bad_signature')
  return invite
}

// ── The join record ───────────────────────────────────────────────────────────────────────────────────

/** Who joined, through which owner-signed invite, and both signatures over it. Byte strings are DECODED; the public
 *  keys are serialized bundles; `inviteExpiresAt` is the invite's expiry in Unix seconds (the owner's signature
 *  covers it, so a later verifier needs it). The tenant and the account are the record's address, not its content. */
export type JoinRecord = {
  identityPublicKey: Uint8Array
  secretKeyProofPublicKey: Uint8Array
  inviteId: string
  invitePublicKey: Uint8Array
  inviteKeySignature: Uint8Array
  inviteExpiresAt: number
  signerUserId: string
  signerIdentityPublicKey: Uint8Array
  inviteSignature: Uint8Array
  identitySignature: Uint8Array
}

/** A join record's address: the tenant it is sealed in and the member it is about. */
export type JoinRecordAddress = { tenantId: string; userId: string }

type JoinRecordBody = Omit<JoinRecord, 'inviteSignature' | 'identitySignature'>

export const joinRecordStatement = (a: JoinRecordAddress, r: JoinRecordBody) =>
  ({
    purpose: 'join-record',
    tenantId: a.tenantId,
    userId: a.userId,
    identityDigest: sha256(r.identityPublicKey),
    secretKeyProofPublicKey: r.secretKeyProofPublicKey,
    inviteId: r.inviteId,
    inviteKeyDigest: sha256(r.invitePublicKey),
    inviteKeySignature: r.inviteKeySignature,
    inviteExpiresAt: r.inviteExpiresAt,
    signerUserId: r.signerUserId,
    signerIdentityDigest: sha256(r.signerIdentityPublicKey),
  }) as const

/** Sign a join record twice — by the invite key and by the new identity, over the same statement. */
export function signJoinRecord(
  a: JoinRecordAddress, r: JoinRecordBody, inviteSignPrivateKey: Uint8Array, identitySignPrivateKey: Uint8Array,
): JoinRecord {
  const stmt = joinRecordStatement(a, r)
  return { ...r, inviteSignature: identitySign(inviteSignPrivateKey, stmt), identitySignature: identitySign(identitySignPrivateKey, stmt) }
}

/** Whether a join record holds: the owner signed its invite key, and the invite key and the new identity both signed
 *  the record. False — never a throw — for any malformed part. */
export function joinRecordVerifies(a: JoinRecordAddress, r: JoinRecord): boolean {
  try {
    const stmt = joinRecordStatement(a, r)
    const invite = deserializeIdentityPublic(r.invitePublicKey)
    const identity = deserializeIdentityPublic(r.identityPublicKey)
    const signer = deserializeIdentityPublic(r.signerIdentityPublicKey)
    return (
      inviteKeySignatureVerifies(
        signer.sign,
        { tenantId: a.tenantId, inviteId: r.inviteId, invitePublicKey: r.invitePublicKey, expiresAt: r.inviteExpiresAt },
        r.inviteKeySignature,
      ) &&
      identityVerify(invite.sign, stmt, r.inviteSignature) &&
      identityVerify(identity.sign, stmt, r.identitySignature)
    )
  } catch {
    return false
  }
}

const JOIN_RECORD_VERSION = 1

const u64be = (n: number): Uint8Array => {
  const b = new Uint8Array(8)
  new DataView(b.buffer).setBigUint64(0, BigInt(n), false)
  return b
}

/** `0x01 ‖ lp(identity) ‖ lp(proofKey) ‖ lp(inviteId) ‖ lp(inviteKey) ‖ lp(inviteKeySignature) ‖ lp(u64be(expiresAt))
 *  ‖ lp(signerUserId) ‖ lp(signerKey) ‖ lp(inviteSignature) ‖ lp(identitySignature)`. */
export function encodeJoinRecord(r: JoinRecord): Uint8Array {
  if (!Number.isSafeInteger(r.inviteExpiresAt) || r.inviteExpiresAt < 0) throw new InviteLinkInvalid('expiry')
  return concat(
    Uint8Array.of(JOIN_RECORD_VERSION),
    lp(r.identityPublicKey),
    lp(r.secretKeyProofPublicKey),
    lp(utf8(r.inviteId)),
    lp(r.invitePublicKey),
    lp(r.inviteKeySignature),
    lp(u64be(r.inviteExpiresAt)),
    lp(utf8(r.signerUserId)),
    lp(r.signerIdentityPublicKey),
    lp(r.inviteSignature),
    lp(r.identitySignature),
  )
}

export class JoinRecordInvalid extends Error {
  constructor(reason: string) {
    super(`join_record_invalid: ${reason}`)
    this.name = 'JoinRecordInvalid'
  }
}

export function decodeJoinRecord(b: Uint8Array): JoinRecord {
  if (b.length < 1 || b[0] !== JOIN_RECORD_VERSION) throw new JoinRecordInvalid('version')
  const parts = readLp(b.slice(1), 10, (r) => new JoinRecordInvalid(r))
  const [identity, proof, inviteId, invite, inviteKeySig, expiry, signerUserId, signer, inviteSig, identitySig] = parts
  if (expiry.length !== 8) throw new JoinRecordInvalid('expiry')
  const td = new TextDecoder()
  return {
    identityPublicKey: identity,
    secretKeyProofPublicKey: proof,
    inviteId: td.decode(inviteId),
    invitePublicKey: invite,
    inviteKeySignature: inviteKeySig,
    inviteExpiresAt: Number(new DataView(expiry.buffer, expiry.byteOffset, 8).getBigUint64(0, false)),
    signerUserId: td.decode(signerUserId),
    signerIdentityPublicKey: signer,
    inviteSignature: inviteSig,
    identitySignature: identitySig,
  }
}

const joinRecordContext = (a: JoinRecordAddress) => ({ purpose: 'member-join-record', scopeId: a.tenantId, userId: a.userId }) as const

/** Seal a join record under the tenant key, bound to (tenant, member) like the member's name. */
export const sealJoinRecord = (tenantKey: Uint8Array, a: JoinRecordAddress, r: JoinRecord): Uint8Array =>
  sealField(joinRecordContext(a), tenantKey, encodeJoinRecord(r))

/** Open a join record with the tenant key it was sealed under. */
export const openJoinRecord = (tenantKey: Uint8Array, a: JoinRecordAddress, envelope: Uint8Array): JoinRecord =>
  decodeJoinRecord(openField(joinRecordContext(a), tenantKey, envelope))

// ── helpers ───────────────────────────────────────────────────────────────────────────────────────────

function bytesEqual(a: Uint8Array, b: Uint8Array): boolean {
  return a.length === b.length && a.every((x, i) => x === b[i])
}

/** Exactly `count` length-prefixed fields and nothing after them. */
function readLp(b: Uint8Array, count: number, err: (reason: string) => Error): Uint8Array[] {
  const out: Uint8Array[] = []
  let off = 0
  const dv = new DataView(b.buffer, b.byteOffset, b.byteLength)
  for (let i = 0; i < count; i++) {
    if (off + 4 > b.length) throw err('truncated')
    const n = dv.getUint32(off)
    off += 4
    if (off + n > b.length) throw err('truncated')
    out.push(b.slice(off, off + n))
    off += n
  }
  if (off !== b.length) throw err('trailing')
  return out
}

/** Length-prefixed fields of exactly these sizes. */
function readFixed(b: Uint8Array, sizes: readonly number[], err: (reason: string) => Error): Uint8Array[] {
  const parts = readLp(b, sizes.length, err)
  parts.forEach((p, i) => {
    if (p.length !== sizes[i]) throw err('size')
  })
  return parts
}
