// A blob's sealed metadata: what a member or guest knows about an uploaded blob besides its bytes — its type, the
// name it was uploaded under, the time it was last modified — sealed under the blob's own K_blob with the
// purpose-only `blob-meta` seal (./seal.ts; ADR 0072 Decision 2: the key is unique to the blob, so the key is the
// binding). Written once, at the upload's declare, and never rewritten: a rename is the owning object's business
// (a file node's name), not the blob's.
//
// The plaintext is compact UTF-8 JSON in a fixed key order — `{v, type, name?, modifiedAt?, sha256?}` — padded with spaces
// (0x20, which JSON.parse skips) to the next multiple of BUCKET bytes, so the ciphertext's length tells the server
// only which of four buckets the metadata fell into, not how long a name is. Pinned by
// `contracts/crypto/vectors/v1.json#seal.sym['blob-meta']`, whose plaintext is such a padded document. What it
// deliberately does NOT carry: the plaintext size (the server already knows it — byteSize minus the frames'
// overhead) and the upload time (the server's own `created_at`), so nothing in here repeats what the server sees.
//
// `sha256` is the SHA-256 of the blob's plaintext, 43 base64url characters without padding, written where the blob holds a
// person's file as it is (a gallery original, a video banner): a later upload of an equal file in the same Memory shares the
// stored object instead of storing it again (cr-cover-banner-upload-only-and-dedup, ADR 0013 § 5). It is sealed under the
// blob's own key, so only a holder of that key can confirm a file by it - the same party who can read the file.
import { utf8 } from './primitives'
import { open, seal } from './seal'

/** What a writer seals. `type` may be '' (unknown); `name` and `modifiedAt` are optional. */
export type BlobMeta = { type: string; name?: string; modifiedAt?: number; sha256?: string }

/** A content digest as `sha256` carries it: 32 bytes in base64url, no padding. */
export const SHA256_B64URL = /^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$/

/**
 * What a reader gets back: the known fields, typed, plus every field it does not know, kept as written — a newer
 * writer's additions survive a reader that predates them, and a higher `v` is read rather than refused.
 */
export type OpenedBlobMeta = BlobMeta & { v: number; readonly [field: string]: unknown }

/** The layout's bounds: the padding bucket, the largest plaintext, the type's and the name's UTF-8 byte caps. */
export const BLOB_META = { bucketBytes: 256, maxBytes: 1024, typeBytes: 127, nameBytes: 255 } as const

/** An authentic plaintext that is not a metadata document: not JSON, not an object, or no version. */
export class BlobMetaInvalid extends Error {
  constructor(detail: string) {
    super(`blob_meta_invalid: ${detail}`)
    this.name = 'BlobMetaInvalid'
  }
}

// Control characters (C0, DEL, C1): never part of a type or a name a person reads, and the C0 ones are the only
// characters JSON.stringify expands to six bytes — kept out, so the escaped document stays inside `maxBytes`.
const CONTROL = /\p{Cc}/u
const CONTROLS = /\p{Cc}/gu

/** The longest prefix of `s` whose UTF-8 fits `max` bytes, cut between code points. */
function clampUtf8(s: string, max: number): string {
  let bytes = 0
  let out = ''
  for (const ch of s) {
    bytes += utf8(ch).length
    if (bytes > max) break
    out += ch
  }
  return out
}

/** A name as the layout stores it: no controls, NFC, trimmed, at most `nameBytes`; '' when nothing is left. */
const normalizeName = (name: string): string =>
  clampUtf8(name.replace(CONTROLS, '').normalize('NFC').trim(), BLOB_META.nameBytes).trimEnd()

/**
 * The padded plaintext for `meta`. A type over `typeBytes` or carrying a control character is written as '' —
 * an unknown type is honest, a truncated one is wrong; a name is normalized and clamped (`normalizeName`); a
 * `modifiedAt` that is not an unsigned safe integer of milliseconds is left out, and so is a `sha256` that is not a 32-byte
 * digest in base64url. Never larger than `maxBytes`:
 * every field is bounded and, with the controls gone, JSON.stringify escapes nothing to more than two bytes per
 * UTF-8 byte (`"` and `\`; a lone surrogate counts three bytes and escapes to six) — at most 876 bytes in all (the digest
 * adds 55).
 */
export function encodeBlobMeta(meta: BlobMeta): Uint8Array {
  const type = !CONTROL.test(meta.type) && utf8(meta.type).length <= BLOB_META.typeBytes ? meta.type : ''
  const name = meta.name === undefined ? '' : normalizeName(meta.name)
  const doc: Record<string, unknown> = { v: 1, type }
  if (name !== '') doc.name = name
  if (Number.isSafeInteger(meta.modifiedAt) && (meta.modifiedAt as number) >= 0) doc.modifiedAt = meta.modifiedAt
  if (typeof meta.sha256 === 'string' && SHA256_B64URL.test(meta.sha256)) doc.sha256 = meta.sha256
  const body = utf8(JSON.stringify(doc))
  const out = new Uint8Array(Math.ceil(body.length / BLOB_META.bucketBytes) * BLOB_META.bucketBytes).fill(0x20)
  out.set(body)
  return out
}

/** Read a plaintext back. Tolerant of what it does not know; refuses only what is no metadata document at all. */
export function decodeBlobMeta(plaintext: Uint8Array): OpenedBlobMeta {
  let parsed: unknown
  try {
    parsed = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(plaintext))
  } catch {
    throw new BlobMetaInvalid('not a JSON document')
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) throw new BlobMetaInvalid('not an object')
  const { v, type, name, modifiedAt, sha256, ...rest } = parsed as Record<string, unknown>
  if (!Number.isSafeInteger(v) || (v as number) < 1) throw new BlobMetaInvalid('no version')
  const out: OpenedBlobMeta = { ...rest, v: v as number, type: typeof type === 'string' ? type : '' }
  if (typeof name === 'string' && name !== '') out.name = name
  if (Number.isSafeInteger(modifiedAt) && (modifiedAt as number) >= 0) out.modifiedAt = modifiedAt as number
  if (typeof sha256 === 'string' && SHA256_B64URL.test(sha256)) out.sha256 = sha256
  return out
}

/** Seal `meta` under the blob's own key. The result is what an upload declares as `wrappedBlobMeta`. */
export const sealBlobMeta = (kBlob: Uint8Array, meta: BlobMeta): Uint8Array =>
  seal({ purpose: 'blob-meta' }, kBlob, encodeBlobMeta(meta))

/** Open a served `wrappedBlobMeta` under the blob's key: OpenRefused if it is not this blob's, BlobMetaInvalid if malformed. */
export const openBlobMeta = (kBlob: Uint8Array, sealed: Uint8Array): OpenedBlobMeta =>
  decodeBlobMeta(open({ purpose: 'blob-meta' }, kBlob, sealed))
