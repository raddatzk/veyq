// A blob's sealed metadata on the read side (ADR 0072 Decision 2; inbox #397): what an upload declared about its bytes —
// type, the name it was uploaded under, its modified time — sealed under the blob's own `K_blob` with the `blob-meta`
// seal (`@veyq/crypto/v1` `sealBlobMeta`/`openBlobMeta`) and served back, unchanged, on every read ref beside the
// `K_blob` wrap. Written once at the declare (`planBlob`'s `meta`), never rewritten: a replace is a new blob with its
// own meta, a rename the owning object's business. Size and upload time are not in it — the server holds both.
//
// Core's, kind-neutral: an application hands in the ref it built and the `K_blob` it unwrapped (the chunk provider's
// `meta()`, or `readBlobMeta` in `transport.ts` for a ref it only lists) and decides what to show. Also core's: the one
// download-name rule every application saves under ([downloadName]) and the type → extension table it uses, both the
// platform's Kotlin (`@veyq/core-js/files`, ADR 0155 decision 7.2).
import * as v1 from '@veyq/crypto/v1'
import { fromB64std } from '@veyq/crypto/base64'
import * as core from '@veyq/core-js/files'

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

/** The download extension a sealed media type names — exactly as sealed, own rows only (a type is a peer's statement) —, or
 *  undefined for one the table does not name. The table is the platform's (`blob/DownloadName.kt`, through
 *  `@veyq/core-js/files`), so Depot, Memories and the apps name a type alike; every type a sniff produces for a medium or a
 *  track has its row. */
export function typeExtension(type: string | null | undefined): string | undefined {
  return core.typeExtension(type ?? null) ?? undefined
}

/**
 * The one name a blob is saved under: the owning object's own name when it has one (a file's node name — a rename
 * wins over the name it was uploaded under), else the name sealed in the blob's meta, else `fallback()` (the
 * application's derived name, made only where it is needed). The chosen name gains the extension `meta.type` names
 * ({@link typeExtension}) when it has none; one it has is never replaced. Names are trimmed; the save target sanitises
 * for its route. The rule is the platform's (`blob/DownloadName.kt`, ADR 0155 decision 7.2).
 */
export function downloadName(objectName: string | null | undefined, meta: BlobMeta | null | undefined, fallback: () => string): string {
  const type = meta?.type ?? null
  const sealed = meta?.name ?? null
  return core.downloadName(objectName ?? null, sealed, type, null) ?? (core.downloadName(objectName ?? null, sealed, type, fallback()) as string)
}
