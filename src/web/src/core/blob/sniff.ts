// What a decrypted blob IS, read from its own leading bytes (inbox #412). A blob's sealed type and an object's name are
// a PEER's statement about the bytes (the uploader sealed them); nothing here lets that statement decide how the bytes
// are RENDERED. The functions answer from the bytes alone ("is this a PNG?"), and a viewer combines the answer with the
// claimed type only to NARROW (text needs a text-ish claim too, SVG needs the claim to agree), never to widen: a PNG
// claimed as `text/plain` is a PNG, and a binary claimed as `image/png` is neither.
//
// Core's, kind-neutral and pure: no DOM, no crypto, no application. Memories' `sniffMediaType` (the upload's fallback
// type) shares the byte readers below; it answers a different question (a type to SEAL), so it stays its own.

/** The ASCII text at `offset` (`length` bytes, fewer at the end of `b`). */
export function asciiAt(b: Uint8Array, offset: number, length: number): string {
  let s = ''
  for (let i = offset; i < Math.min(b.length, offset + length); i++) s += String.fromCharCode(b[i])
  return s
}

/** Whether `ascii` sits at `offset`. */
export function hasAscii(b: Uint8Array, offset: number, ascii: string): boolean {
  return b.length >= offset + ascii.length && asciiAt(b, offset, ascii.length) === ascii
}

/**
 * Best-effort read of an EBML file's `DocType` string ("webm" / "matroska"). Scans the header region for the
 * `DocType` element id (`0x42 0x82`), then reads its single-byte-vint length and that many ASCII bytes. Returns
 * undefined if not found in the first 1 KiB (the DocType always sits at the very top of a well-formed file).
 */
export function readEbmlDocType(b: Uint8Array): string | undefined {
  const end = Math.min(b.length - 1, 1024)
  for (let i = 4; i < end; i++) {
    if (b[i] !== 0x42 || b[i + 1] !== 0x82) continue
    const sizeByte = b[i + 2]
    if (sizeByte === undefined || (sizeByte & 0x80) === 0) return undefined // only the single-byte vint is expected here
    const len = sizeByte & 0x7f
    if (len === 0 || i + 3 + len > b.length) return undefined
    return asciiAt(b, i + 3, len)
  }
  return undefined
}

// --- images ----------------------------------------------------------------------------------------------------------

/** The raster image formats a viewer tries to show; HEIC is tried too (Safari decodes it) and falls back on a decode failure. */
export type ImageType = 'image/jpeg' | 'image/png' | 'image/gif' | 'image/webp' | 'image/avif' | 'image/bmp' | 'image/heic'

const AVIF_BRANDS = new Set(['avif', 'avis'])
const HEIC_BRANDS = new Set(['heic', 'heix', 'hevc', 'heim', 'heis', 'hevm', 'hevs', 'heif', 'mif1', 'msf1'])
/** A BMP's DIB header sizes (BITMAPCOREHEADER … BITMAPV5HEADER): `BM` alone is two letters any text file may start with. */
const BMP_DIB_SIZES = new Set([12, 40, 52, 56, 64, 108, 124])

/** The raster image format `head` is, by its magic bytes, or undefined. SVG is text and answered by {@link sniffSvg}. */
export function sniffImage(head: Uint8Array): ImageType | undefined {
  const b = head
  if (b.length >= 3 && b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff) return 'image/jpeg'
  if (b.length >= 8 && b[0] === 0x89 && hasAscii(b, 1, 'PNG\r\n') && b[6] === 0x1a && b[7] === 0x0a) return 'image/png'
  if (hasAscii(b, 0, 'GIF87a') || hasAscii(b, 0, 'GIF89a')) return 'image/gif'
  if (hasAscii(b, 0, 'RIFF') && hasAscii(b, 8, 'WEBP')) return 'image/webp'
  if (b.length >= 26 && b[0] === 0x42 && b[1] === 0x4d && b[15] === 0 && b[16] === 0 && b[17] === 0 && BMP_DIB_SIZES.has(b[14])) {
    return 'image/bmp'
  }
  if (hasAscii(b, 4, 'ftyp')) {
    const brand = asciiAt(b, 8, 4)
    if (AVIF_BRANDS.has(brand)) return 'image/avif'
    if (HEIC_BRANDS.has(brand)) return 'image/heic'
  }
  return undefined
}

// --- text ------------------------------------------------------------------------------------------------------------

/** How a text is decoded: UTF-8 (a BOM is dropped), or UTF-16 when — and only when — a byte-order mark says so. Every
 *  other 8-bit encoding (Windows-1252, ISO-8859-x, Shift-JIS …) is not guessed: it is not valid UTF-8, so it is not shown. */
export type TextEncoding = 'utf-8' | 'utf-16le' | 'utf-16be'

/** Control bytes a text may hold: tab, line feed, form feed, carriage return, escape (ANSI colours in logs). */
const TEXT_CONTROLS = new Set([0x09, 0x0a, 0x0c, 0x0d, 0x1b])

/**
 * The encoding `head` is valid text in, or undefined for anything else: no NUL byte and no other control byte (but for
 * [TEXT_CONTROLS]) in valid UTF-8 — a sequence cut off by the end of `head` is not an error while `head` is a prefix, but
 * is when it is the `complete` file — or a UTF-16 byte-order mark followed by a decodable body. Only the prefix is judged;
 * what follows decodes with U+FFFD.
 */
export function sniffText(head: Uint8Array, complete = false): TextEncoding | undefined {
  const b = head
  const bom16 = b.length >= 2 ? (b[0] === 0xff && b[1] === 0xfe ? 'utf-16le' : b[0] === 0xfe && b[1] === 0xff ? 'utf-16be' : null) : null
  if (bom16) {
    try {
      new TextDecoder(bom16, { fatal: true }).decode(b.subarray(0, b.length - (b.length % 2)))
      return bom16
    } catch {
      return undefined
    }
  }
  for (let i = 0; i < b.length; i++) if (b[i] < 0x20 && !TEXT_CONTROLS.has(b[i])) return undefined
  try {
    new TextDecoder('utf-8', { fatal: true }).decode(b, { stream: !complete })
    return 'utf-8'
  } catch {
    return undefined
  }
}

const TEXT_TYPES = new Set([
  'application/json', 'application/ld+json', 'application/x-ndjson', 'application/xml', 'application/javascript',
  'application/x-javascript', 'application/typescript', 'application/x-yaml', 'application/yaml', 'application/toml',
  'application/x-sh', 'application/x-httpd-php', 'application/sql', 'application/x-tex', 'application/rtf',
])
const TEXT_EXTENSIONS = new Set([
  'txt', 'text', 'md', 'markdown', 'rst', 'adoc', 'csv', 'tsv', 'json', 'jsonl', 'ndjson', 'xml', 'yaml', 'yml', 'toml', 'ini',
  'cfg', 'conf', 'env', 'properties', 'log', 'sql', 'html', 'htm', 'css', 'scss', 'less', 'js', 'mjs', 'cjs', 'ts', 'tsx',
  'jsx', 'vue', 'svelte', 'py', 'rb', 'php', 'java', 'kt', 'kts', 'scala', 'go', 'rs', 'c', 'h', 'cc', 'cpp', 'hpp', 'cs', 'swift',
  'm', 'sh', 'bash', 'zsh', 'fish', 'ps1', 'bat', 'lua', 'pl', 'r', 'tex', 'srt', 'vtt', 'gradle', 'dockerfile', 'gitignore',
  'lock', 'diff', 'patch', 'svg',
])

/** The lower-cased extension of `name` (no dot), or '' when it has none. */
export function extensionOf(name: string | null | undefined): string {
  const m = /[^.]\.([^.\s/\\]{1,16})$/.exec(name ?? '')
  return m ? m[1].toLowerCase() : ''
}

/** Whether the CLAIMED type or name says "text" — a claim that may only narrow what the bytes already allow. */
export function claimsText(type: string | null | undefined, name: string | null | undefined): boolean {
  const t = (type ?? '').split(';')[0].trim().toLowerCase()
  if (t.startsWith('text/') || TEXT_TYPES.has(t) || t.endsWith('+json') || t.endsWith('+xml')) return true
  return TEXT_EXTENSIONS.has(extensionOf(name))
}

/** Whether the CLAIM names SVG: the sealed type or the extension. */
export function claimsSvg(type: string | null | undefined, name: string | null | undefined): boolean {
  return (type ?? '').split(';')[0].trim().toLowerCase() === 'image/svg+xml' || extensionOf(name) === 'svg'
}

/**
 * Whether `head` is an SVG document: text (BOM and leading blanks aside) whose first element is `<svg`, after any XML
 * prolog, comments and doctype. The bytes decide; the caller still requires a claim that agrees ({@link claimsSvg}) and
 * shows it only as an `<img>` from a blob URL, never inline.
 */
export function sniffSvg(head: Uint8Array): boolean {
  const enc = sniffText(head)
  if (enc !== 'utf-8') return false
  let s = new TextDecoder('utf-8').decode(head.subarray(0, 4096), { stream: true })
  for (let guard = 0; guard < 16; guard++) {
    s = s.replace(/^\s+/, '')
    const skip = /^(<\?[\s\S]*?\?>|<!--[\s\S]*?-->|<!DOCTYPE[^>[]*(\[[\s\S]*?\])?\s*>)/i.exec(s)
    if (!skip) break
    s = s.slice(skip[0].length)
  }
  return /^<svg[\s>/]/i.test(s)
}

// --- audio and video -------------------------------------------------------------------------------------------------

/** A streamable container: the element that plays it and the MIME the service worker serves it under. */
export type StreamKind = { element: 'video' | 'audio'; type: string }

/** The audio/video container `head` is, by its magic bytes (ADR 0047: the demuxer, not this, decides if it can PLAY it). */
export function sniffStream(head: Uint8Array): StreamKind | undefined {
  const b = head
  if (hasAscii(b, 4, 'ftyp')) {
    const brand = asciiAt(b, 8, 4)
    if (brand === 'qt  ') return { element: 'video', type: 'video/quicktime' }
    if (brand === 'M4A ' || brand === 'M4B ' || brand === 'M4P ') return { element: 'audio', type: 'audio/mp4' }
    if (AVIF_BRANDS.has(brand) || HEIC_BRANDS.has(brand)) return undefined // an image, answered by sniffImage
    return { element: 'video', type: 'video/mp4' }
  }
  if (b.length >= 4 && b[0] === 0x1a && b[1] === 0x45 && b[2] === 0xdf && b[3] === 0xa3) {
    return readEbmlDocType(b) === 'matroska' ? { element: 'video', type: 'video/x-matroska' } : { element: 'video', type: 'video/webm' }
  }
  if (hasAscii(b, 0, 'OggS')) {
    return asciiAt(b, 0, 160).includes('theora') ? { element: 'video', type: 'video/ogg' } : { element: 'audio', type: 'audio/ogg' }
  }
  if (hasAscii(b, 0, 'RIFF') && hasAscii(b, 8, 'WAVE')) return { element: 'audio', type: 'audio/wav' }
  if (hasAscii(b, 0, 'fLaC')) return { element: 'audio', type: 'audio/flac' }
  if (hasAscii(b, 0, 'ID3')) return { element: 'audio', type: 'audio/mpeg' }
  if (b.length >= 2 && b[0] === 0xff && (b[1] & 0xe0) === 0xe0) {
    const layer = (b[1] >> 1) & 0x03
    const version = (b[1] >> 3) & 0x03
    if (layer === 0 && (b[1] & 0xf6) === 0xf0) return { element: 'audio', type: 'audio/aac' } // ADTS
    // An MPEG audio frame header, Layer III only (what mp3 files are; `FF FE` - a UTF-16 text's byte-order mark - is Layer I):
    // a valid version, bitrate and sample-rate index.
    const bitrate = b.length > 2 ? b[2] >> 4 : 1
    const rate = b.length > 2 ? (b[2] >> 2) & 0x03 : 0
    if (layer === 1 && version !== 1 && bitrate !== 0x0f && rate !== 0x03) return { element: 'audio', type: 'audio/mpeg' }
  }
  return undefined
}

// --- documents -------------------------------------------------------------------------------------------------------

/** How far into the file a PDF reader looks for the `%PDF-` header (pdf.js does the same: the first KiB). */
const PDF_HEADER_WINDOW = 1024

/**
 * Whether `head` is a PDF: the `%PDF-` header at byte 0, or - a reader tolerates junk before it - within the first
 * {@link PDF_HEADER_WINDOW} bytes, but then only where `head` is NOT valid text: a text that merely mentions the header
 * (a note about PDFs, a diff of one) is a text, not a document, and must not be taken for a PDF that then fails to open.
 * The bytes decide; no claimed type is consulted.
 */
export function sniffPdf(head: Uint8Array): boolean {
  if (hasAscii(head, 0, '%PDF-')) return true
  for (let i = 1; i <= PDF_HEADER_WINDOW; i++) {
    if (head[i] === 0x25 && hasAscii(head, i, '%PDF-')) return sniffText(head) === undefined
  }
  return false
}
