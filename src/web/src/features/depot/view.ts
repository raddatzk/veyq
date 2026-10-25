// Reading a file to SHOW it (inbox #412): the decision what an opened file is, and the bytes that decision needs, no more.
//
// **The bytes decide, the claim only narrows.** The first chunk is opened and sniffed (`core/blob/sniff.ts`); the blob's
// sealed type and the node's name are a peer's statement and are consulted only where the bytes are ambiguous (text needs a
// text-ish claim, SVG needs the claim to agree) - a PNG claimed as `text/plain` is a PNG, a binary claimed as `image/png`
// is nothing we show. Nothing here renders anything: it returns what a view may be built from (a typed `Blob`, a string, or
// the container a media element streams), and a viewer builds the DOM.
//
// **Memory is bounded per kind** ([IMAGE_CAP], [TEXT_CAP], [PDF_CAP]): an image is collected chunk by chunk into one typed
// `Blob` (opened [FILE_VIEW_IN_FLIGHT] at a time, the stream pump of #371) only up to its cap, a text is cut at its cap and
// says so, a PDF is collected into ONE buffer allocated at its size (no parts to concatenate afterwards), and audio/video
// read nothing beyond chunk 0 here - the service worker's media route (ADR 0047) pulls the rest on demand. A file over its
// cap is not read past the first chunk.
//
// **A PDF is returned as bytes** (inbox #412): it is drawn by pdf.js onto canvases inside the page (`shared/pdf/pdfEngine.ts`), not
// by the browser's own viewer, which would need an `<iframe>`/`<object>` on a `blob:` URL against the app's policy
// (`frame-src 'none'; object-src 'none'`, `docs/crosscutting/cc.security.csp.md`). This module does not load pdf.js: it
// decides "this is a PDF" from the bytes and reads them; opening them is the viewer's.
//
// Operator-blind: the chunks are opened on this device under the keys the page holds; nothing here talks to a server but the
// provider's own presigned reads.
import { streamBlob, type ByteSink, type ChunkSource } from '../../core/blob/stream'
import {
  claimsSvg, claimsText, sniffImage, sniffPdf, sniffStream, sniffSvg, sniffText, type StreamKind, type TextEncoding,
} from '../../core/blob/sniff'
import { PLAINTEXT_CHUNK_SIZE, plaintextTotal } from '../../sw/range'

/** Largest image the page decrypts into memory. Over it, the download is offered instead. */
export const IMAGE_CAP = 64 * 1024 * 1024
/** Most text shown; a longer file shows its first [TEXT_CAP] bytes and a note. */
export const TEXT_CAP = 1024 * 1024
/** Largest PDF the page decrypts into memory (and hands to pdf.js, which holds its own copy while it draws). */
export const PDF_CAP = 64 * 1024 * 1024
/** Frames opened at once while collecting an image or text - the pump's bound (`streamBlob`). */
export const FILE_VIEW_IN_FLIGHT = 4

/** What a viewer knows of the file besides its bytes: the CLAIMS - the blob's sealed type and the node's/uploaded name. */
export type ViewHints = { type?: string | null; name?: string | null }

/** The part of a chunk provider a view reads. */
export type ViewSource = ChunkSource & { readonly byteSize: number }

/** Why a file is not shown. `format`: unknown to the sniffers (or an encoding we do not guess); `empty`: no content. */
export type UnsupportedReason = 'format' | 'empty'

/** What opening a file produced. `image`'s `blob` carries the SNIFFED type, never the claimed one. */
export type FileView =
  | { kind: 'image'; blob: Blob; type: string }
  | { kind: 'text'; text: string; truncated: boolean }
  | { kind: 'pdf'; bytes: Uint8Array }
  | { kind: 'stream'; element: 'video' | 'audio'; type: string }
  | { kind: 'too-large'; limit: number }
  | { kind: 'unsupported'; reason: UnsupportedReason }

function throwIfAborted(signal?: AbortSignal): void {
  if (signal?.aborted) throw signal.reason ?? new DOMException('Cancelled', 'AbortError')
}

/** Collect the first `chunks` chunks of `source` into parts (chunk 0 is `head`, already open), reporting progress. */
async function collect(
  source: ViewSource, head: Uint8Array, chunks: number, signal: AbortSignal | undefined, onProgress: ((f: number) => void) | undefined,
): Promise<Uint8Array[]> {
  const parts: Uint8Array[] = []
  const sink: ByteSink = { write: async (b) => void parts.push(b), close: async () => {}, abort: async () => void (parts.length = 0) }
  const from: ChunkSource = {
    chunkCount: chunks,
    getPlaintextChunk: (i) => (i === 0 ? Promise.resolve(head) : source.getPlaintextChunk(i)),
  }
  await streamBlob(from, sink, { inFlight: FILE_VIEW_IN_FLIGHT, signal, onProgress })
  return parts
}

/** Collect all `chunks` chunks of `source` (chunk 0 is `head`) into ONE buffer of `total` bytes, reporting progress. */
async function collectInto(
  source: ViewSource, head: Uint8Array, chunks: number, total: number, signal: AbortSignal | undefined, onProgress: ((f: number) => void) | undefined,
): Promise<Uint8Array> {
  const out = new Uint8Array(total)
  let at = 0
  const sink: ByteSink = {
    write: async (b) => {
      if (at + b.length > out.length) throw new RangeError('the file is larger than its size says')
      out.set(b, at)
      at += b.length
    },
    close: async () => {},
    abort: async () => {},
  }
  const from: ChunkSource = {
    chunkCount: chunks,
    getPlaintextChunk: (i) => (i === 0 ? Promise.resolve(head) : source.getPlaintextChunk(i)),
  }
  await streamBlob(from, sink, { inFlight: FILE_VIEW_IN_FLIGHT, signal, onProgress })
  return at === out.length ? out : out.subarray(0, at)
}

/** `cut`: the bytes end where the cap did, so a multi-byte sequence cut in half is withheld instead of printing U+FFFD for it. */
function decode(encoding: TextEncoding, bytes: Uint8Array, cut: boolean): string {
  return new TextDecoder(encoding, { fatal: false }).decode(bytes, { stream: cut })
}

/**
 * Open `source` far enough to say what it is, and read what showing it needs. Throws what the provider throws (a chunk that
 * does not open, a failed read) or the signal's reason - the caller maps both to its states; never returns a partial view.
 */
export async function loadFileView(
  source: ViewSource, hints: ViewHints, options: { signal?: AbortSignal; onProgress?: (fraction: number) => void } = {},
): Promise<FileView> {
  const { signal, onProgress } = options
  if (source.chunkCount < 1) return { kind: 'unsupported', reason: 'empty' }
  throwIfAborted(signal)
  const head = await source.getPlaintextChunk(0)
  throwIfAborted(signal)
  if (head.length === 0) return { kind: 'unsupported', reason: 'empty' }
  const total = Math.max(0, plaintextTotal(source.byteSize, source.chunkCount))

  const raster = sniffImage(head)
  const svg = !raster && claimsSvg(hints.type, hints.name) && sniffSvg(head)
  if (raster || svg) {
    if (total > IMAGE_CAP) return { kind: 'too-large', limit: IMAGE_CAP }
    const type = raster ?? 'image/svg+xml'
    const parts = await collect(source, head, source.chunkCount, signal, onProgress)
    return { kind: 'image', blob: new Blob(parts as BlobPart[], { type }), type }
  }
  const media: StreamKind | undefined = sniffStream(head)
  if (media) return { kind: 'stream', element: media.element, type: media.type }
  if (sniffPdf(head)) {
    if (total > PDF_CAP) return { kind: 'too-large', limit: PDF_CAP }
    return { kind: 'pdf', bytes: await collectInto(source, head, source.chunkCount, total, signal, onProgress) }
  }
  const encoding = claimsText(hints.type, hints.name) ? sniffText(head, source.chunkCount === 1) : undefined
  if (encoding) {
    const truncated = total > TEXT_CAP
    const chunks = Math.min(source.chunkCount, Math.ceil(TEXT_CAP / PLAINTEXT_CHUNK_SIZE))
    const parts = await collect(source, head, chunks, signal, onProgress)
    let bytes = new Uint8Array(parts.reduce((n, p) => n + p.length, 0))
    let at = 0
    for (const p of parts) { bytes.set(p, at); at += p.length }
    if (bytes.length > TEXT_CAP) bytes = bytes.subarray(0, TEXT_CAP)
    return { kind: 'text', text: decode(encoding, bytes, truncated), truncated }
  }
  return { kind: 'unsupported', reason: 'format' }
}
