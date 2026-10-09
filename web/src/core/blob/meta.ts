// A blob's sealed metadata on the read side (ADR 0072 Decision 2; inbox #397): what an upload declared about its bytes —
// type, the name it was uploaded under, its modified time — sealed under the blob's own `K_blob` with the `blob-meta`
// seal (`@veyq/crypto/v1` `sealBlobMeta`/`openBlobMeta`) and served back, unchanged, on every read ref beside the
// `K_blob` wrap. Written once at the declare (`planBlob`'s `meta`), never rewritten: a replace is a new blob with its
// own meta, a rename the owning object's business. Size and upload time are not in it — the server holds both.
//
// Core's, kind-neutral: an application hands in the ref it built and the `K_blob` it unwrapped (the chunk provider's
// `meta()`, or `readBlobMeta` in `transport.ts` for a ref it only lists) and decides what to show. Also core's: the one
// download-name rule every application saves under ([downloadName]) and the type → extension table it uses.
import * as v1 from '@veyq/crypto/v1'
import { fromB64std } from '@veyq/crypto/base64'

/** What a writer declares (`planBlob`'s `meta`): `{ type, name?, modifiedAt? }` — the crypto suite's `BlobMeta`. */
export type BlobMeta = v1.BlobMeta
/** What a reader gets back: the known fields typed, unknown ones kept (the crypto suite's `OpenedBlobMeta`). */
export type OpenedBlobMeta = v1.OpenedBlobMeta

/**
 * Open a ref's `wrappedBlobMeta` under its `K_blob`, **for display**: null when the ref carries none (a blob uploaded
 * before the field, or by a writer that sent none), when it does not open under this key (`OpenRefused`: another blob's
 * meta served in this ref's place), when it is no metadata document (`BlobMetaInvalid`) or not even base64. Never
 * throws — a missing meta is a row without a type, not a failed listing.
 */
export function openBlobMetaOf(ref: { wrappedBlobMeta?: string | null }, kBlob: Uint8Array): OpenedBlobMeta | null {
  if (!ref.wrappedBlobMeta) return null
  try {
    return v1.openBlobMeta(kBlob, fromB64std(ref.wrappedBlobMeta))
  } catch {
    return null
  }
}

/**
 * Download extension per media type — the suffix a saved name gains when it has none. Moved from Memories'
 * `features/memory/download.ts` (inbox #397) unchanged, so Files and Memories name a type alike; Memories' magic-byte
 * sniff (`sniffMediaType`) produces only types this table names — a type added there needs its row here.
 */
export const MIME_TO_EXT: Readonly<Record<string, string>> = {
  'image/jpeg': 'jpg',
  'image/png': 'png',
  'image/gif': 'gif',
  'image/webp': 'webp',
  'image/heic': 'heic',
  'application/pdf': 'pdf',
  'video/mp4': 'mp4',
  'video/quicktime': 'mov',
  'video/webm': 'webm',
  'video/x-matroska': 'mkv',
}

/** Whether `name` ends in an extension: a dot after at least one other character, then 1–16 characters that are no
 *  dot and no whitespace (`Bericht.PDF` does, `Rom 2026`, `.profile` and `v1.2 notes` do not). */
const HAS_EXTENSION = /[^.]\.[^.\s]{1,16}$/

/**
 * The one name a blob is saved under: the owning object's own name when it has one (a file's node name — a rename
 * wins over the name it was uploaded under), else the name sealed in the blob's meta, else `fallback()` (the
 * application's derived name). The chosen name gains the extension `meta.type` names ([MIME_TO_EXT]) when it has none
 * of its own; an extension it has is never replaced. Names are trimmed; the save target sanitises for its route.
 */
export function downloadName(objectName: string | null | undefined, meta: BlobMeta | null | undefined, fallback: () => string): string {
  const own = objectName?.trim()
  const sealed = meta?.name?.trim()
  const name = own || sealed || fallback()
  // An own row only: a sealed type is a peer's statement, and `toString` must not index the object's prototype.
  const ext = meta?.type && Object.hasOwn(MIME_TO_EXT, meta.type) ? MIME_TO_EXT[meta.type] : undefined
  return ext && !HAS_EXTENSION.test(name) ? `${name}.${ext}` : name
}
