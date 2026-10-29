// An image's pixel size read from the first bytes of its file, never from decoded pixels (inbox #558): the pick-time
// tile probe (`mediaDeriver.ts` `probeDimensions`) needs only an aspect ratio, and a 12-megapixel photo decoded is 48.8 MB
// against the few kilobytes that name its size. Picking 50 such photos decoded them all at once with `createImageBitmap`:
// the renderer peaked at 2.30-2.79 GiB at throttle 4, against 1.0-1.1 GiB for the rest of the upload. Read from the
// header, the batch's peak is the upload's own, 1.14-1.27 GiB (profile S2, 2026-10-29, three runs each at throttle 1 and
// 4). An `<img>` that is never drawn reads the size without a decode too, but it loads the whole file and the browser kept
// those bytes past the window: 1.46-1.55 GiB, and its loads cost the throttled main thread about 0.9 s. So the header is
// read here, and the element only for what this does not read.
//
// The formats a camera or a phone hands a browser and that are simple to read: JPEG (the frame header, with the EXIF
// orientation applied, as a browser draws it), PNG, GIF and WebP. Anything else - HEIC, AVIF, an unknown or damaged
// header - answers null, and the caller asks the browser instead. The reads are slices of the Blob, 64 KiB at a time,
// only as far as a JPEG's segments before its frame header reach; nothing here holds the file.
import type { Dimensions } from './mediaDeriver'

/** Bytes per read: a phone JPEG's EXIF segment, with its thumbnail, fits; one that does not costs a second read. */
const WINDOW = 64 * 1024
/** A JPEG's segments walked before its frame header, at most: a header that names none by then is not one we read. */
const MAX_SEGMENTS = 256

/** The JPEG markers that start a frame header (SOF0-SOF15, without DHT C4, JPG C8 and DAC CC): they carry the size. */
const SOF = new Set([0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf])

const u16be = (b: Uint8Array, at: number) => (b[at] << 8) | b[at + 1]
const u16le = (b: Uint8Array, at: number) => b[at] | (b[at + 1] << 8)
const u24le = (b: Uint8Array, at: number) => b[at] | (b[at + 1] << 8) | (b[at + 2] << 16)
const u32be = (b: Uint8Array, at: number) => ((b[at] << 24) >>> 0) + ((b[at + 1] << 16) | (b[at + 2] << 8) | b[at + 3])
const ascii = (b: Uint8Array, at: number, text: string) => [...text].every((c, i) => b[at + i] === c.charCodeAt(0))

async function bytesAt(blob: Blob, at: number, length: number): Promise<Uint8Array> {
  return new Uint8Array(await blob.slice(at, Math.min(blob.size, at + length)).arrayBuffer())
}

/** A size, or null where either side is zero (a header that names no size). */
const sized = (w: number, h: number): Dimensions | null => (w > 0 && h > 0 ? { w, h } : null)

/**
 * The pixel size the file's header names, as a browser shows it (a JPEG's EXIF orientation 5-8 swaps width and height,
 * as `image-orientation: from-image` and `createImageBitmap` do), or null for a format this does not read or a header it
 * cannot. Never rejects for a malformed file; a read the browser refuses rejects, as any read of the file would.
 */
export async function imageSizeFromHeader(blob: Blob): Promise<Dimensions | null> {
  const head = await bytesAt(blob, 0, WINDOW)
  if (head.length >= 3 && head[0] === 0xff && head[1] === 0xd8 && head[2] === 0xff) return jpegSize(blob, head)
  if (head.length >= 24 && head[0] === 0x89 && ascii(head, 1, 'PNG\r\n\x1a\n') && ascii(head, 12, 'IHDR')) {
    return sized(u32be(head, 16), u32be(head, 20))
  }
  if (head.length >= 10 && (ascii(head, 0, 'GIF87a') || ascii(head, 0, 'GIF89a'))) return sized(u16le(head, 6), u16le(head, 8))
  if (head.length >= 30 && ascii(head, 0, 'RIFF') && ascii(head, 8, 'WEBP')) return webpSize(head)
  return null
}

/** A WebP's size from its first chunk: lossy (`VP8 `), lossless (`VP8L`) or extended (`VP8X`). */
function webpSize(b: Uint8Array): Dimensions | null {
  if (ascii(b, 12, 'VP8 ')) return b[23] === 0x9d && b[24] === 0x01 && b[25] === 0x2a ? sized(u16le(b, 26) & 0x3fff, u16le(b, 28) & 0x3fff) : null
  if (ascii(b, 12, 'VP8L')) {
    if (b[20] !== 0x2f) return null
    const bits = b[21] | (b[22] << 8) | (b[23] << 16) | (b[24] << 24)
    return sized((bits & 0x3fff) + 1, ((bits >>> 14) & 0x3fff) + 1)
  }
  if (ascii(b, 12, 'VP8X')) return sized(u24le(b, 24) + 1, u24le(b, 27) + 1)
  return null
}

/** A JPEG's size from its frame header, walking the segments before it; the EXIF orientation from an APP1 `Exif` segment. */
async function jpegSize(blob: Blob, head: Uint8Array): Promise<Dimensions | null> {
  let window = { at: 0, bytes: head }
  /** The bytes [at, at + length) in the window, read anew when they are not; null past the file's end. */
  const view = async (at: number, length: number): Promise<Uint8Array | null> => {
    if (at + length > blob.size) return null
    if (at < window.at || at + length > window.at + window.bytes.length) window = { at, bytes: await bytesAt(blob, at, Math.max(length, WINDOW)) }
    return window.bytes.subarray(at - window.at, at - window.at + length)
  }
  let orientation = 1
  let at = 2
  for (let segment = 0; segment < MAX_SEGMENTS; segment++) {
    const marker = await view(at, 4)
    if (!marker || marker[0] !== 0xff) return null
    const kind = marker[1]
    if (kind === 0xff) {
      at += 1 // a fill byte before the marker
      continue
    }
    if (kind === 0xd8 || kind === 0x01 || (kind >= 0xd0 && kind <= 0xd7)) {
      at += 2 // a marker without a segment
      continue
    }
    if (kind === 0xd9 || kind === 0xda) return null // the image ends, or its data starts, before any frame header
    const length = u16be(marker, 2)
    if (length < 2) return null
    if (SOF.has(kind)) {
      const frame = await view(at, 9)
      if (!frame) return null
      const h = u16be(frame, 5)
      const w = u16be(frame, 7)
      return orientation >= 5 && orientation <= 8 ? sized(h, w) : sized(w, h)
    }
    if (kind === 0xe1) {
      const app1 = await view(at + 4, length - 2)
      if (app1 && ascii(app1, 0, 'Exif\0\0')) orientation = exifOrientation(app1.subarray(6)) ?? orientation
    }
    at += 2 + length
  }
  return null
}

/** The `Orientation` tag (0x0112) of a TIFF header's first IFD, or null where it has none or the header is not one. */
function exifOrientation(tiff: Uint8Array): number | null {
  if (tiff.length < 8) return null
  const little = tiff[0] === 0x49 && tiff[1] === 0x49
  if (!little && !(tiff[0] === 0x4d && tiff[1] === 0x4d)) return null
  const u16 = (at: number) => (little ? u16le(tiff, at) : u16be(tiff, at))
  const u32 = (at: number) => (little ? (u16le(tiff, at) | (u16le(tiff, at + 2) << 16)) >>> 0 : u32be(tiff, at))
  if (u16(2) !== 42) return null
  const ifd = u32(4)
  if (ifd + 2 > tiff.length) return null
  const count = u16(ifd)
  for (let i = 0; i < count; i++) {
    const entry = ifd + 2 + i * 12
    if (entry + 12 > tiff.length) return null
    if (u16(entry) === 0x0112) return u16(entry + 8)
  }
  return null
}
