// The ONE way to seal in veyq (ADR 0072). Every AEAD seal binds its purpose AND the identity of the object it is
// stored as, so the operator-blind server can no longer serve one object's ciphertext in another object's slot:
// the AAD carries the purpose, every id the opener addresses the object by, and the epoch or revision the wire
// stores it at. The opener supplies those values from the ADDRESS it fetched the object by — the route it called,
// the row it looked up, the scope it opened — never from fields of the response that carry the ciphertext
// (Decision 1). Where the key is unique to one object, the key is the binding and the context names the purpose
// only (Decision 2). Parents are not bound (Decision 4). Binding makes an epoch AUTHENTIC, not FRESH (Decision 5).
//
// A text or icon field is sealed by a seal of its own (`sealField`, below, ADR 0093 § 9): under a key derived per write
// from the key it would otherwise be sealed under, so a key handed over for one field opens that one revision of it.
//
// The unions below are data, not only types: the encoder walks them (so the field ORDER is the table's order), and
// the conformance test compares their purposes with `contracts/crypto/vectors/v1.json#seal` and `#field_seal` in both
// directions, and asserts that no purpose sits in two tables.
// Adding a purpose after V1 is additive (a new row + its vector); changing a row's fields changes bytes that are
// already stored, which is a suite v2.
import {
  asymUnwrap,
  asymWrap,
  concat,
  fieldKey,
  fieldSaltOf,
  fieldUnwrap,
  fieldWrap,
  FIELD_SALT_BYTES,
  lp,
  symUnwrap,
  symWrap,
  utf8,
  type HybridPublic,
  type HybridSecret,
} from './primitives'

/**
 * What a binding field is: a canonical lowercase UUID, a share id (the 43-character base64url bearer token a share
 * is addressed by — a capability, so it keeps its 256-bit form rather than becoming a UUID), an unsigned integer
 * (epoch/revision), a blob role (a bounded token), or a 32-byte digest (an identity pin's id). Statements (below) add five
 * kinds a seal never binds: an id that may be absent (encoded as the empty field), a scope kind, an opaque byte string
 * bound whole, a six-digit pairing code (its ASCII digits), and a contract operation id (its UTF-8 name).
 */
type FieldKind = 'id' | 'shareId' | 'uint' | 'role' | 'optionalId' | 'scopeKind' | 'digest' | 'bytes' | 'pairingCode' | 'operationId'

/**
 * The role a sealed `K_blob` plays for its owner: an open, bounded token (`BLOB_ROLE` below), the same token as the
 * contract's `NodeKind`. Core names no role; each application brings its own — Memories' ORIGINAL, THUMBNAIL and
 * PREVIEW of a media and COVER of a node, Files' FILE_CONTENT of a file node. A role is encoded as its UTF-8 name, so
 * opening the token added a value without changing the bytes of any existing one (ADR 0072: a new value is not a
 * field change). Together with `ownerId` — the object the blob belongs to, a file's own node id — the role is what
 * binds a blob's key wrap to its application and kind (ADR 0079 §9), so no application needs a purpose of its own.
 */
export type BlobRole = string

/** A scope's kind, as a grant statement signs it (ADR 0028 Decision 6). */
export type ScopeKind = 'TENANT' | 'GROUP' | 'PERSONAL'

type Schema = Readonly<Record<string, readonly (readonly [string, FieldKind])[]>>

/** Symmetric purposes (`seal`/`open`), each with its binding fields in canonical order. */
export const SEAL_PURPOSES = {
  // A node's key wrap binds the placement revision the wire stores it at (ADR 0080 Decision 3): 0 at birth, `n + 1`
  // for a move or rename built on `n`, so a wrap served at another revision — one a move superseded — does not open.
  // Added before V1 (ADR 0072: a stored-format change, so a code change and a reset, not a suite v2); the parent stays
  // unbound (ADR 0072 Decision 4) — the parent's key is the binding.
  'content-key': [['scopeId', 'id'], ['nodeId', 'id'], ['epoch', 'uint'], ['placementRevision', 'uint']],
  // ownerId is the media id for a media's blobs, the node id for a cover or a file's content; the role names which.
  'blob-key': [['scopeId', 'id'], ['ownerId', 'id'], ['blobId', 'id'], ['role', 'role']],
  // A share's K_share, recoverable by the Memory's scope members only: sealed under K_SC at the epoch stored beside
  // it — a key no share link carries (ADR 0036 point 4), where K_node is one every viewer/contributor link does.
  'share-key-recovery': [['scopeId', 'id'], ['nodeId', 'id'], ['shareId', 'shareId'], ['epoch', 'uint']],
  // A Memory's drop PRIVATE key (ADR 0031 point 7), under that Memory's K_node — a node field of its own, never
  // inside node-meta, so a date or location edit never carries it.
  'drop-private-key': [['scopeId', 'id'], ['nodeId', 'id']],
  // No member id: a reader addresses every member's entry by scope + node only, because the read deliberately
  // carries no member id (ADR 0068 §6 — the aggregate is anonymous in the DATA), so an opener could not supply one.
  // WHOSE an entry is rides inside the plaintext instead, as the `item-mark-self` statement's tag below (ADR 0068 §9).
  'item-favorites': [['scopeId', 'id'], ['nodeId', 'id']],
  favorites: [['scopeId', 'id']],
  'scope-epoch': [['scopeId', 'id'], ['epoch', 'uint']],
  'group-name': [['scopeId', 'id'], ['nameEpoch', 'uint']],
  'space-icon': [['scopeId', 'id'], ['iconEpoch', 'uint']],
  // The pin store's HEAD (ADR 0104): its version, entry count and the digest of the whole entry set, at its revision.
  'identity-pins': [['revision', 'uint']],
  // One pin of that store, sealed under the Master Key at the opaque id it is stored at (v1.ts `identityPinId`), so the
  // server cannot serve one contact's entry in another's slot.
  'identity-pin': [['pinId', 'digest']],
  'device-register': [['revision', 'uint']],
  // Purpose only: sealed under the share's own K_share (ADR 0072 Decision 2).
  'share-key': [],
  'share-password': [],
  // The Memory's drop PUBLIC key on a WRITE_ONLY share: the guest seals to it, so the server can neither read nor
  // substitute it.
  'drop-public-key': [],
  // Purpose only: a blob's sealed metadata (type, name, modification time — ./blobMeta.ts) under the blob's own
  // K_blob, which is the binding as it is for the blob's chunk frames. A frame's AAD is u32be(index) ‖ u32be(total),
  // 8 bytes; this one is 0x02 ‖ 'v1' ‖ lp('blob-meta'), 16 — the two can never open as each other.
  'blob-meta': [],
  // Purpose only: under a KEK with a fresh salt, or under the account's own Master Key.
  'master-key': [],
  'identity-priv': [],
  // A report share (ADR 0093 § 1, ./report.ts): the keys that open one reported item, sealed under the report's fresh
  // K_report. Addressed by the item the server stores it for — a node, a node's name, or an application's item inside one
  // (`NODE`, `NAME` or `ITEM`, as the report's wire names the kind) — so a share served for another item does not open.
  'report-share': [['itemKind', 'role'], ['itemId', 'id']],
  // The same for an upload link a WRITE_ONLY guest reports: addressed by the link's share id.
  'report-link': [['shareId', 'shareId']],
  // An Expenses revision's own random key K_rev (ADR 0108 decision 4), wrapped under the Pot's K_node and bound to the
  // revision it opens — the shape of a K_blob under its owner. A re-key after a rotation (ADR 0109) rewrites only this
  // wrap: the body K_rev opens (`expense-entry`, below) and its digest stay byte-identical. A wrap served at another
  // entry or revision, or in another Pot, does not open.
  'expense-entry-key': [['scopeId', 'id'], ['nodeId', 'id'], ['entryId', 'id'], ['revision', 'uint']],
  // A statement of reasons' facts, sealed once by the moderator under the statement's fresh K_facts (ADR 0093 § 7,
  // ./report.ts) — every affected member opens this one copy with the key wrapped to them (`moderation-facts-key`).
  // K_facts is unique to the statement, and the seal still binds the case and the statement id the moderator's browser
  // mints, as a field under its own K_field keeps its object binding (ADR 0072 Decisions 1-3).
  'moderation-statement-facts': [['caseId', 'id'], ['statementId', 'id']],
  // A member invite's link secret — the invite key's seed, the inviting owner's fingerprint and account (ADR 0111
  // decision 1, ./invite.ts) — under `Argon2id(code or password, salt)`. The KEK is unique to the link, and the seal
  // still binds the tenant and the invite, so a sealed part pasted behind another invite's token does not open.
  'invite-link': [['tenantId', 'id'], ['inviteId', 'id']],
} as const satisfies Schema

/**
 * Field purposes (`sealField`/`openField`, ADR 0093 § 9): every text or icon field a member writes and a report may
 * have to hand over one of. Each seals under `K_field = HKDF(K_parent, salt, "veyq/v1/field/<purpose>")` with a salt
 * drawn per write, where K_parent is the key the field's object hands its fields: the parent's key for a node's name
 * (the directory entry, ADR 0080 Decision 2), the node's own key for its meta, `K_node` or a guest's `K_up` for a
 * medium's tiers, a bucket's name and a guest's display name, `K_share` for a share's fields, `K_tenant` for a member's
 * name and picture, an Expenses revision's own `K_rev` for its body. The AAD binds the object as it would for a plain seal (ADR 0072 Decision 1); the share's three
 * fields stay purpose-only, because `K_share` — and so each `K_field` under it — is unique to one share (Decision 2).
 * `group-name` and `space-icon` are not here: they derive their key per (scope, epoch), not per write, because a pending
 * invitee is handed that key (`group-name-key`).
 */
export const FIELD_PURPOSES = {
  // The directory entry binds the placement revision the wire stores it at (ADR 0080 Decision 3), as the key wrap
  // beside it does; its K_parent is the entry key — the parent's K_node, K_SC at the node's epoch for a scope root.
  'node-name': [['scopeId', 'id'], ['nodeId', 'id'], ['epoch', 'uint'], ['placementRevision', 'uint']],
  'node-meta': [['scopeId', 'id'], ['nodeId', 'id']],
  'media-meta': [['scopeId', 'id'], ['mediaId', 'id']],
  'media-exif': [['scopeId', 'id'], ['mediaId', 'id']],
  'bucket-name': [['scopeId', 'id'], ['nodeId', 'id'], ['bucketId', 'id']],
  'share-identity-name': [['scopeId', 'id'], ['shareId', 'shareId'], ['identityId', 'id']],
  'member-name': [['scopeId', 'id'], ['userId', 'id']],
  // A member's profile picture (ADR 0084): its own purpose, so a blob sealed for the name never opens as the picture.
  'member-avatar': [['scopeId', 'id'], ['userId', 'id']],
  // A member's join record (ADR 0111 decisions 3 and 12, ./invite.ts): written once at the invite's accept, beside the
  // name and the picture. Not `join-record`, which is the statement both of its signatures cover.
  'member-join-record': [['scopeId', 'id'], ['userId', 'id']],
  // A member's verification of another member (ADR 0111 decision 12, ./verification.ts): one row per (tenant, verifier,
  // subject), written only by the verifier, beside the name and the join record. It binds the tenant epoch it is sealed
  // under, so a reader takes that epoch's key from its chain and a row served at another epoch does not open; and the
  // subject, so a row moved to another subject's slot does not open. Not `verification`, which is the statement inside.
  'member-verification': [['scopeId', 'id'], ['userId', 'id'], ['subjectUserId', 'id'], ['epoch', 'uint']],
  // One revision of an Expenses entry (ADR 0108 decision 4), padded to its size class (./pad.ts): its K_parent is that
  // revision's own K_rev (`expense-entry-key`), never the Pot's key, so a re-key re-wraps K_rev and never re-seals the
  // body. It keeps the object binding though K_rev is unique to the revision (ADR 0072 Decision 2), so a body served as
  // another entry's or another revision's does not open.
  'expense-entry': [['scopeId', 'id'], ['nodeId', 'id'], ['entryId', 'id'], ['revision', 'uint']],
  'share-memory-name': [],
  'share-note': [],
  'share-name': [],
} as const satisfies Schema

/** Asymmetric purposes (`sealTo`/`openAs`) — a seal to one X-Wing encapsulation key. */
export const SEAL_TO_PURPOSES = {
  'scope-grant': [['scopeId', 'id'], ['epoch', 'uint']],
  'group-name-key': [['scopeId', 'id'], ['nameEpoch', 'uint']],
  // A WRITE_ONLY guest's own identity key K_up, wrapped to the Memory's drop public key (ADR 0031 point 7). The
  // reader addresses it by the Memory it opens and the identity the media names.
  'upload-identity-key': [['scopeId', 'id'], ['nodeId', 'id'], ['identityId', 'id']],
  // Purpose only: a single-use pairing envelope to a fresh ephemeral key (ADR 0072 Decision 2).
  'pairing-grant': [],
  // The instance moderation key M_n of one epoch, sealed to a moderator's identity (ADR 0099 § 2). There is one key per
  // epoch for the whole instance, so the epoch is the whole address; the recipient is the identity it is sealed to.
  'moderation-grant': [['epoch', 'uint']],
  // A report's sealed part — K_report, the reason, the context (ADR 0093 § 2, ./report.ts) — to the epoch's public
  // half. A report stays sealed to the epoch it was made under (ADR 0099 § 4), which the server stores beside it.
  'moderation-report': [['epoch', 'uint']],
  // The reporter's contact, sealed apart from the report's body to the same epoch, so the server can drop it alone six
  // months after the decision was told (ADR 0093 § 11). The report id is the server's, minted after the seal, so the
  // epoch is the whole address — as for the body it travels beside.
  'moderation-report-contact': [['epoch', 'uint']],
  // A moderator's free text — a decision's note, a hold extension's reason (ADR 0093 § 11, ./report.ts) — to the
  // epoch's public half, bound to its case so a note moved onto another case's event does not open there.
  'moderation-note': [['epoch', 'uint'], ['caseId', 'id']],
  // A statement's K_facts, wrapped by the moderator to one affected member's identity (ADR 0093 § 7), bound to the case,
  // the statement and the member — the member addresses it by the statement and their own account — so a wrap served
  // to another member, or under another statement, does not open.
  'moderation-facts-key': [['caseId', 'id'], ['statementId', 'id'], ['accountId', 'id']],
  // An affected member's objection to a measure (ADR 0093 § 7), to the epoch's public half, bound to the statement it
  // answers so an objection moved onto another statement does not open there.
  'moderation-objection': [['epoch', 'uint'], ['statementId', 'id']],
  // An authority's reference for an order (ADR 0093 § 12), sealed to the epoch's public half by the client that enters
  // the order — a moderator's browser, admin-web or veyq-admin, which needs no private half for it — and bound to the
  // order id that client mints, so a reference moved onto another order does not open there.
  'moderation-order': [['epoch', 'uint'], ['orderId', 'id']],
  // The facts of a Server Owner's own step over content — a member's sign-in block, a tenant taken offline (ADR 0093
  // § 7) — sealed for the log to the epoch's public half by admin-web or veyq-admin, which hold no private half, and
  // bound to the statement id that client mints: the step may have no case to bind to.
  'moderation-step-facts': [['epoch', 'uint'], ['statementId', 'id']],
} as const satisfies Schema

type KindValue<K extends FieldKind> = K extends 'uint'
  ? number
  : K extends 'role'
    ? BlobRole
    : K extends 'optionalId'
      ? string | null
      : K extends 'scopeKind'
        ? ScopeKind
        : K extends 'digest' | 'bytes'
          ? Uint8Array
          : string
type Simplify<T> = { [K in keyof T]: T[K] }
type ContextOf<S extends Schema> = {
  [P in keyof S]: Simplify<{ purpose: P } & { [F in S[P][number] as F[0]]: KindValue<F[1]> }>
}[keyof S]

/** A symmetric seal's context: the purpose and exactly its binding fields. */
export type SealContext = ContextOf<typeof SEAL_PURPOSES>
/** An asymmetric seal's context. */
export type SealToContext = ContextOf<typeof SEAL_TO_PURPOSES>
/** A field seal's context. */
export type FieldContext = ContextOf<typeof FIELD_PURPOSES>
export type SealPurpose = SealContext['purpose']
export type SealToPurpose = SealToContext['purpose']
export type FieldPurpose = FieldContext['purpose']

/** A context that does not describe an object: an unknown purpose, a missing/extra field, a non-canonical id. */
export class SealContextInvalid extends Error {
  constructor(detail: string) {
    super(`seal_context_invalid: ${detail}`)
    this.name = 'SealContextInvalid'
  }
}

/** A ciphertext that does not open as the object it was asked for: wrong key, wrong slot, or tampered. */
export class OpenRefused extends Error {
  constructor(readonly purpose: string) {
    super(`open_refused: ${purpose}`)
    this.name = 'OpenRefused'
  }
}

const CANONICAL_UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const SHARE_ID = /^[A-Za-z0-9_-]{43}$/
// The contract's `NodeKind` token (`contracts/api/_common.openapi.yaml`): keep the two equal. Without the `m` flag a
// JS `$` matches at the end of the input only — unlike Java's or Python's, never before a trailing newline.
const BLOB_ROLE = /^[A-Z][A-Z0-9_]{0,15}$/
const SCOPE_KINDS: readonly string[] = ['TENANT', 'GROUP', 'PERSONAL'] satisfies ScopeKind[]
const PAIRING_CODE = /^[0-9]{6}$/
// A contract operationId, as the generated interfaces name their methods: lower camel case, bounded.
const OPERATION_ID = /^[a-z][A-Za-z0-9]{0,63}$/

const u64be = (n: number): Uint8Array => {
  const b = new Uint8Array(8)
  new DataView(b.buffer).setBigUint64(0, BigInt(n), false)
  return b
}

function encodeField(purpose: string, name: string, kind: FieldKind, value: unknown): Uint8Array {
  switch (kind) {
    case 'id':
      // Canonical form only: an opener that spelled the same id differently would silently mismatch its sealer.
      if (typeof value !== 'string' || !CANONICAL_UUID.test(value)) {
        throw new SealContextInvalid(`${purpose}.${name} is not a canonical lowercase UUID`)
      }
      return utf8(value)
    case 'shareId':
      if (typeof value !== 'string' || !SHARE_ID.test(value)) {
        throw new SealContextInvalid(`${purpose}.${name} is not a share id`)
      }
      return utf8(value)
    case 'uint':
      // The `typeof` operand sits on a line of its own because a Stryker directive is line-wide: on the one-line form
      // it would also have switched off the killed mutants of the whole condition. The same holds for the two below.
      if (
        !Number.isSafeInteger(value) ||
        // Stryker disable next-line ConditionalExpression: equivalent, Number.isSafeInteger refused every non-number
        // (a boxed Number included) already, so `false` here admits nothing more; it only narrows `value` for TS.
        typeof value !== 'number' ||
        value < 0
      ) {
        throw new SealContextInvalid(`${purpose}.${name} is not an unsigned integer`)
      }
      return u64be(value)
    case 'role':
      // `typeof` first, as for an id: RegExp.prototype.test coerces its argument, so a boxed String would pass.
      if (typeof value !== 'string' || !BLOB_ROLE.test(value)) {
        throw new SealContextInvalid(`${purpose}.${name} is not a blob role`)
      }
      return utf8(value)
    case 'optionalId':
      // Absent is explicit (`null`) and encodes as the EMPTY field — never `undefined`, which is a forgotten field.
      if (value === null) return new Uint8Array(0)
      return encodeField(purpose, name, 'id', value)
    case 'scopeKind':
      if (
        !SCOPE_KINDS.includes(value as string) ||
        // Stryker disable next-line ConditionalExpression: equivalent, includes() compares by SameValueZero and so
        // refused every non-string (a boxed String included) already; this operand only narrows `value` for TS.
        typeof value !== 'string'
      ) {
        throw new SealContextInvalid(`${purpose}.${name} is not a scope kind`)
      }
      return utf8(value)
    case 'digest':
      if (!(value instanceof Uint8Array) || value.length !== 32) {
        throw new SealContextInvalid(`${purpose}.${name} is not a SHA-256 digest`)
      }
      return value
    case 'bytes':
      if (!(value instanceof Uint8Array)) throw new SealContextInvalid(`${purpose}.${name} is not a byte string`)
      return value
    case 'pairingCode':
      if (typeof value !== 'string' || !PAIRING_CODE.test(value)) {
        throw new SealContextInvalid(`${purpose}.${name} is not a six-digit pairing code`)
      }
      return utf8(value)
    case 'operationId':
      if (typeof value !== 'string' || !OPERATION_ID.test(value)) {
        throw new SealContextInvalid(`${purpose}.${name} is not an operation id`)
      }
      return utf8(value)
  }
}

function encode(schema: Schema, context: { purpose: string }): Uint8Array {
  const fields = Object.hasOwn(schema, context.purpose) ? schema[context.purpose] : undefined
  if (!fields) throw new SealContextInvalid(`unknown purpose ${context.purpose}`)
  const record = context as unknown as Record<string, unknown>
  const extra = Object.keys(record).filter((k) => k !== 'purpose' && !fields.some(([name]) => name === k))
  if (extra.length > 0) throw new SealContextInvalid(`${context.purpose} does not bind ${extra.join(', ')}`)
  return concat(
    lp(utf8(context.purpose)),
    ...fields.map(([name, kind]) => lp(encodeField(context.purpose, name, kind, record[name]))),
  )
}

/**
 * The canonical encoding of a context: `lp(utf8(purpose)) || lp(field)…` in the table's field order, where an id
 * is its UTF-8 canonical string, an integer is u64be, and a role is its UTF-8 name. Exposed because a signature
 * over a bound object reuses it (a signed statement), and pinned per purpose by the vectors' `aad`.
 */
export const encodeSealContext = (context: SealContext): Uint8Array => encode(SEAL_PURPOSES, context)
export const encodeSealToContext = (context: SealToContext): Uint8Array => encode(SEAL_TO_PURPOSES, context)
export const encodeFieldContext = (context: FieldContext): Uint8Array => encode(FIELD_PURPOSES, context)

/**
 * Statements a key MACs or signs rather than seals — in the same canonical encoding, so their label domain-separates
 * them from each other and from every seal purpose. Pinned by `contracts/crypto/vectors/v1.json` (`#grant_signature`,
 * `#item_mark_self`, `#pairing_channel`, `#moderation_proof`, `#invite_key`, `#join_record`, `#key_chain`).
 *   - `grant` (ADR 0028 Decision 6): what a granter's identity key signs over a grant it posts. The scope KIND is
 *     signed so a group's genuine grant cannot be presented as the recipient's personal scope; the two digests are
 *     SHA-256 over the DECODED recipient identity public bundle and the DECODED grant ciphertext. A grant to an open
 *     tenant invite names the invite's id as its recipient and the invite key's bundle as its identity.
 *   - `item-mark-self` (ADR 0068 §9): what a member's Master-Key-derived key MACs over their marks on one node, so
 *     their own client can tell their entry from a co-member's without the read naming anyone. `marks` is SHA-256
 *     over the marked ids, de-duplicated and sorted — the SET, bound whole, so a copied tag answers for no other set.
 *   - `pairing-answer`, `pairing-sign-in-answer`, `pairing-envelope` (ADR 0059 §11): what the pairing channel key
 *     MACs over the two values the relay carries — the new device's serialized transport public bundle (and, when
 *     signing in with a QR code, the transcribed code), and the sealed grant's DECODED ciphertext. Two answer labels
 *     keep one direction's answer from passing for the other's.
 *   - `moderation-proof` (ADR 0099 § 2): what the current epoch's moderation signing key signs to prove the key on one
 *     moderation call — the server's single-use challenge, the contract operation it is for, and the case it acts on
 *     (absent, the empty field, for an operation on no case). The server rebuilds it from the operation it matched.
 *   - `invite-key` (ADR 0111 decision 1): what an inviting owner's identity key signs over an invite's key — the
 *     tenant, the invite, SHA-256 of the invite key's public bundle and the invite's expiry in Unix seconds.
 *   - `invite-accept` (ADR 0111 decision 3): what the invite key signs when its invitee redeems it — the invite, the
 *     new member's account and SHA-256 of their identity public bundle. The server checks it under the stored invite
 *     key, so a redeem with the token alone is refused.
 *   - `join-record` (ADR 0111 decisions 3 and 12): what the invite key AND the new identity both sign — who joined
 *     (account, identity digest, Secret-Key proof public key) through which owner-signed invite.
 *   - `key-chain-genesis` (ADR 0111 decision 7, cr-secure-the-account): step 0 of an account's key chain — the identity
 *     public bundle and the Secret-Key proof public key the account was born with, in full. Never signed: there is no
 *     key before it. Its own label, so a genesis can never be read as a step and no step as a genesis.
 *   - `key-chain-step` (the same): step `index ≥ 1` — the head of the step before it, SHA-256 of the identity bundle and
 *     of the proof public key it retires, and the new bundle and proof public key in full. Signed twice over the same
 *     bytes, by the retired identity AND by the retired proof key, so a thief holding a device's identity alone cannot
 *     forge one.
 *   - `verification` (ADR 0111 decisions 12 and 13): what a verifier's identity signs after a safety-number compare —
 *     in this tenant, this verifier at this head of their own chain confirmed this subject at this head of theirs. It
 *     counts only while it names the subject's current head and is signed by the verifier's current key; a rotation
 *     re-signs the outgoing key's verifications with the new one (./verification.ts).
 */
export const STATEMENT_PURPOSES = {
  grant: [
    ['scopeId', 'id'],
    ['scopeKind', 'scopeKind'],
    ['epoch', 'uint'],
    ['recipientUserId', 'id'],
    ['recipientIdentityDigest', 'digest'],
    ['wrappedGrantDigest', 'digest'],
  ],
  'item-mark-self': [['scopeId', 'id'], ['nodeId', 'id'], ['marks', 'digest']],
  'pairing-answer': [['transportKey', 'bytes']],
  'pairing-sign-in-answer': [['transportKey', 'bytes'], ['code', 'pairingCode']],
  'pairing-envelope': [['envelope', 'bytes']],
  'moderation-proof': [['challenge', 'bytes'], ['operationId', 'operationId'], ['caseId', 'optionalId']],
  'invite-key': [['tenantId', 'id'], ['inviteId', 'id'], ['inviteKeyDigest', 'digest'], ['expiresAt', 'uint']],
  'invite-accept': [['inviteId', 'id'], ['userId', 'id'], ['identityDigest', 'digest']],
  'join-record': [
    ['tenantId', 'id'],
    ['userId', 'id'],
    ['identityDigest', 'digest'],
    ['secretKeyProofPublicKey', 'bytes'],
    ['inviteId', 'id'],
    ['inviteKeyDigest', 'digest'],
    ['inviteKeySignature', 'bytes'],
    ['inviteExpiresAt', 'uint'],
    ['signerUserId', 'id'],
    ['signerIdentityDigest', 'digest'],
  ],
  'key-chain-genesis': [['userId', 'id'], ['identityPublicKey', 'bytes'], ['secretKeyProofPublicKey', 'bytes']],
  verification: [
    ['tenantId', 'id'],
    ['verifierUserId', 'id'],
    ['subjectUserId', 'id'],
    ['subjectHead', 'digest'],
    ['verifierHead', 'digest'],
  ],
  'key-chain-step': [
    ['userId', 'id'],
    ['index', 'uint'],
    ['previousHead', 'digest'],
    ['previousIdentityDigest', 'digest'],
    ['previousProofKeyDigest', 'digest'],
    ['identityPublicKey', 'bytes'],
    ['secretKeyProofPublicKey', 'bytes'],
  ],
} as const satisfies Schema

/** A statement's context: its label and exactly its fields. */
export type StatementContext = ContextOf<typeof STATEMENT_PURPOSES>
export const encodeStatement = (context: StatementContext): Uint8Array => encode(STATEMENT_PURPOSES, context)

const randomNonce = (): Uint8Array => crypto.getRandomValues(new Uint8Array(12))

/** Seal `plaintext` under `key` as the object `context` names. Draws its own nonce. */
export const seal = (context: SealContext, key: Uint8Array, plaintext: Uint8Array): Uint8Array =>
  symWrap(key, plaintext, encodeSealContext(context), randomNonce())

/** Open `blob` as the object `context` names — the context built from the opener's ADDRESS, never the envelope. */
export function open(context: SealContext, key: Uint8Array, blob: Uint8Array): Uint8Array {
  const aad = encodeSealContext(context)
  try {
    return symUnwrap(key, blob, aad)
  } catch {
    throw new OpenRefused(context.purpose)
  }
}

/** Seal `plaintext` to one X-Wing encapsulation key (an identity's or a drop key's) as the object `context` names. */
export const sealTo = (context: SealToContext, recipient: HybridPublic, plaintext: Uint8Array): Uint8Array =>
  asymWrap(recipient, plaintext, encodeSealToContext(context), { nonce: randomNonce() })

/** Open a seal addressed to this identity as the object `context` names. */
export function openAs(context: SealToContext, sk: HybridSecret, blob: Uint8Array): Uint8Array {
  const aad = encodeSealToContext(context)
  try {
    return asymUnwrap(sk, blob, aad)
  } catch {
    throw new OpenRefused(context.purpose)
  }
}

/**
 * Seal one revision of a field as the object `context` names, under a key of its own: a fresh salt and nonce are drawn,
 * `K_field` is derived from `parentKey` and the salt, and the envelope `0x03 ‖ salt ‖ nonce ‖ aead` carries both.
 */
export function sealField(context: FieldContext, parentKey: Uint8Array, plaintext: Uint8Array): Uint8Array {
  const aad = encodeFieldContext(context)
  const salt = crypto.getRandomValues(new Uint8Array(FIELD_SALT_BYTES))
  return fieldWrap(fieldKey(parentKey, salt, context.purpose), plaintext, aad, salt, randomNonce())
}

/** Open a field with its own `K_field` — what a moderator holds once a report hands that one key over. */
export function openFieldWith(context: FieldContext, key: Uint8Array, envelope: Uint8Array): Uint8Array {
  const aad = encodeFieldContext(context)
  try {
    return fieldUnwrap(key, envelope, aad)
  } catch {
    throw new OpenRefused(context.purpose)
  }
}

/** Open a field as a member or guest does: with the key it was sealed under, the context built from the ADDRESS. */
export const openField = (context: FieldContext, parentKey: Uint8Array, envelope: Uint8Array): Uint8Array =>
  openFieldWith(context, fieldKey(parentKey, fieldSaltOf(envelope), context.purpose), envelope)

/**
 * The `K_field` of this one revision of this one field — the key a reporter's client hands over. It opens the envelope
 * before it is returned, so a key that does not open what it is handed over for is never handed over.
 */
export function fieldKeyOf(context: FieldContext, parentKey: Uint8Array, envelope: Uint8Array): Uint8Array {
  const key = fieldKey(parentKey, fieldSaltOf(envelope), context.purpose)
  openFieldWith(context, key, envelope)
  return key
}
