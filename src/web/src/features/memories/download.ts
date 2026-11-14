// Download naming + save for the Memory gallery. A media's original downloads under **its own file name** — the one
// the uploader's device reported, sealed into the original's blob meta at upload (core's `blob-meta` seal under the
// original's `K_blob`, inbox #397) and served on the listing, so the name is known the moment the lightbox opens and
// does not change when more media arrive. Only a media whose original carries no name falls back to the name cr-0049
// derived for all of them: the decrypted Memory name + its 1-based gallery position, the extension from the blob
// meta's type. Either way core's `downloadName` gives a name without an extension the one its type names.
// Operator-blind: the server never saw a filename or a media type.
//
// **The save streams (inbox #371 S4).** [downloadOriginal] writes the original chunk by chunk into the target
// `shared/saveStream.ts` opens (a picked file, the Service Worker's download route, or — only up to its cap — an
// in-memory Blob), so the tab holds a few chunks, not the original. It used to hold the whole decrypted original.
import { streamBlob, STREAM_IN_FLIGHT, type ByteSink } from '../../core/blob/stream'
import { MIME_TO_EXT, downloadName, type BlobMeta } from '../../core/blob/meta'
import { asciiAt, hasAscii, readEbmlDocType } from '../../core/blob/sniff'
import { openSaveTarget } from '../../shared/saveStream'
import type { ChunkProvider } from '../../sw/protocol'
import { plaintextTotal } from '../../sw/range'

/**
 * Sniff the **full MIME type** of a decrypted original from its leading magic bytes — a small known set, else
 * `undefined`. Operator-blind: the bytes are plaintext the client already holds, and the type is derived on
 * device; the server never saw a filename or a media type ([ADR 0044](../../../../../docs/decisions/0044-field-visibility-declared-at-the-field.md)).
 * It is the upload's fallback when the picked file names no type (`media.ts` `resolveMediaType`, an empty `File.type`
 * from Safari's Photos import): the result is sealed as the original's blob meta type, and a `video/*` one is what
 * tells the lightbox to render a `<video>` and the tile to draw a play badge (#104/#105), so **do not** trust `ftyp` alone: WebM/MKV carry no `ftyp` box and are handled
 * via their EBML header, or a `.webm` upload would silently lose its badge and its `<video type>`.
 */
export function sniffMediaType(bytes: Uint8Array): string | undefined {
  const b = bytes
  if (b.length >= 3 && b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff) return 'image/jpeg'
  if (b.length >= 4 && b[0] === 0x89 && b[1] === 0x50 && b[2] === 0x4e && b[3] === 0x47) return 'image/png'
  if (b.length >= 4 && b[0] === 0x47 && b[1] === 0x49 && b[2] === 0x46 && b[3] === 0x38) return 'image/gif'
  if (b.length >= 12 && hasAscii(b, 0, 'RIFF') && hasAscii(b, 8, 'WEBP')) return 'image/webp'
  if (b.length >= 4 && b[0] === 0x25 && b[1] === 0x50 && b[2] === 0x44 && b[3] === 0x46) return 'application/pdf'
  if (b.length >= 12 && hasAscii(b, 4, 'ftyp')) {
    const brand = asciiAt(b, 8, 4)
    if (brand === 'heic' || brand === 'heif' || brand === 'mif1' || brand === 'msf1') return 'image/heic'
    if (brand === 'qt  ') return 'video/quicktime' // .mov — a distinct browser MIME, worth keeping honest
    // An M4A (and its audiobook and protected kin) is audio, as core's `sniffStream` says: a music track (ADR 0135 decision
    // 3) sealed as `video/mp4` would be a gallery tile with a play badge.
    if (brand === 'M4A ' || brand === 'M4B ' || brand === 'M4P ') return 'audio/mp4'
    return 'video/mp4' // isom / mp4* and friends
  }
  // EBML (WebM / Matroska) — no `ftyp`; read the DocType to tell the browser-playable WebM from a generic MKV.
  if (b.length >= 4 && b[0] === 0x1a && b[1] === 0x45 && b[2] === 0xdf && b[3] === 0xa3) {
    const docType = readEbmlDocType(b)
    if (docType === 'webm') return 'video/webm'
    if (docType === 'matroska') return 'video/x-matroska'
    return 'video/webm' // EBML with an unreadable DocType → the common playable case
  }
  return undefined
}

/** The download extension for a media type, or undefined for one this client does not name (then: `bin`). The table
 *  is core's (`core/blob/meta.ts` `MIME_TO_EXT`, shared with Depot's download names since inbox #397); every type
 *  [sniffMediaType] returns has its row there. Own rows only: the type is a peer's sealed statement. */
export function extensionOf(mediaType: string | undefined): string | undefined {
  return mediaType && Object.hasOwn(MIME_TO_EXT, mediaType) ? MIME_TO_EXT[mediaType] : undefined
}

/** The derived fallback name: the decrypted Memory name + a 1-based index + an extension (e.g. `Rom 2026 3.jpg`). A
 *  name that reduces to nothing falls back to `memory`. */
export function namedDownload(memoryName: string, index: number, extension: string): string {
  const base = memoryName.replace(/[\\/ -]+/g, ' ').trim() || 'memory'
  return `${base} ${index + 1}.${extension}`
}

/**
 * The name a media's original is saved under — core's one rule (`downloadName`) with Memories' inputs: Memories media
 * has no mutable display name of its own, so the name sealed in the original's blob meta (`meta.name`, the picked
 * file's), else the derived [namedDownload] fallback (Memory name + gallery position, the extension the meta's type
 * names, else `bin`). A sealed name without an extension gains the type's. Synchronous — the meta rode the listing.
 */
export function originalDownloadName(meta: BlobMeta | null | undefined, memoryName: string, index: number): string {
  return downloadName(null, meta, () => namedDownload(memoryName, index, extensionOf(meta?.type) ?? 'bin'))
}

/** Where [downloadOriginal] saves — `openSaveTarget` unless a test injects one. */
export type OpenSaveTarget = (name: string, size: number) => Promise<ByteSink>

/**
 * Save a media's original under `name` ([originalDownloadName]), streamed from `source` (the original's chunk
 * provider) into the target `openSaveTarget` picks.
 *
 * **Ordering (the save picker needs the click's user activation).** Nothing is awaited before the save target opens,
 * so the File System Access picker is the first await of the click, as in Depot: the name is known synchronously.
 * (Before inbox #397 a media without a sealed type had chunk 0 read and sniffed first; the type is the blob's meta now.)
 *
 * Throws what `openSaveTarget` throws (`SaveCancelled`, `SaveTooLarge` — before any chunk is read) or the stream's
 * failure, after which the target is aborted, never closed.
 */
export async function downloadOriginal(source: ChunkProvider, name: string, open: OpenSaveTarget = openSaveTarget): Promise<void> {
  const sink = await open(name, plaintextTotal(source.byteSize, source.chunkCount))
  await streamBlob(source, sink, { inFlight: STREAM_IN_FLIGHT })
}
