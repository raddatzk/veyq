// @vitest-environment node
// An image's size from its header (inbox #558): crafted headers of each format it reads, the EXIF orientations that turn
// a JPEG, a frame header past the first read, and the headers it must not guess at. A real file anchors it: the repo's
// EXIF sample, whose size `sips` reads. That the profile's 12-megapixel photos read so in Chromium is S2's reading.
//
// Bites: drop the orientation swap and the "orientation 6" case goes red; read only the first window and the "past the
// first read" case answers null; read the frame header at the wrong offsets and every JPEG case goes red.
import { describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { imageSizeFromHeader } from './imageHeader'

const u16be = (n: number) => [(n >> 8) & 0xff, n & 0xff]
const u16le = (n: number) => [n & 0xff, (n >> 8) & 0xff]
const u32be = (n: number) => [(n >>> 24) & 0xff, (n >> 16) & 0xff, (n >> 8) & 0xff, n & 0xff]
const u32le = (n: number) => [n & 0xff, (n >> 8) & 0xff, (n >> 16) & 0xff, (n >>> 24) & 0xff]
const text = (s: string) => [...s].map((c) => c.charCodeAt(0))
const blob = (bytes: number[] | Uint8Array) => new Blob([new Uint8Array(bytes) as BlobPart])

/** A segment: marker, length (its own two bytes included), payload. */
const segment = (marker: number, payload: number[]) => [0xff, marker, ...u16be(payload.length + 2), ...payload]
/** An APP1 `Exif` segment whose first IFD holds one entry, `Orientation` = `value`, in either byte order. */
function exif(value: number, little = false): number[] {
  const u16 = little ? u16le : u16be
  const u32 = little ? u32le : u32be
  const tiff = [...text(little ? 'II' : 'MM'), ...u16(42), ...u32(8), ...u16(1), ...u16(0x0112), ...u16(3), ...u32(1), ...u16(value), 0, 0, ...u32(0)]
  return segment(0xe1, [...text('Exif'), 0, 0, ...tiff])
}
/** A JPEG up to its frame header (SOF `marker`, `w` × `h`), the segments before it as given, and some scan bytes. */
const jpeg = (w: number, h: number, before: number[][] = [], marker = 0xc0) =>
  [0xff, 0xd8, ...before.flat(), ...segment(marker, [8, ...u16be(h), ...u16be(w), 3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1]), 0xff, 0xda, 0, 2, 1, 2, 3]

describe('a JPEG: its frame header, turned as its EXIF orientation says', () => {
  it('a baseline frame', async () => expect(await imageSizeFromHeader(blob(jpeg(4032, 3024)))).toEqual({ w: 4032, h: 3024 }))
  it('a progressive frame (SOF2)', async () => expect(await imageSizeFromHeader(blob(jpeg(640, 480, [], 0xc2)))).toEqual({ w: 640, h: 480 }))
  it('after an APP0, a DQT and a DHT, which carry no size', async () => {
    const before = [segment(0xe0, text('JFIF\0')), segment(0xdb, new Array(65).fill(1)), segment(0xc4, new Array(30).fill(2))]
    expect(await imageSizeFromHeader(blob(jpeg(1200, 800, before)))).toEqual({ w: 1200, h: 800 })
  })
  it.each([1, 2, 3, 4])('orientation %i keeps width and height', async (o) =>
    expect(await imageSizeFromHeader(blob(jpeg(4032, 3024, [exif(o)])))).toEqual({ w: 4032, h: 3024 }))
  it.each([5, 6, 7, 8])('orientation %i swaps them, as the browser draws it turned', async (o) =>
    expect(await imageSizeFromHeader(blob(jpeg(4032, 3024, [exif(o)])))).toEqual({ w: 3024, h: 4032 }))
  it('a little-endian EXIF reads the same', async () =>
    expect(await imageSizeFromHeader(blob(jpeg(4032, 3024, [exif(6, true)])))).toEqual({ w: 3024, h: 4032 }))
  it('an APP1 that is not EXIF (XMP) turns nothing', async () =>
    expect(await imageSizeFromHeader(blob(jpeg(4032, 3024, [segment(0xe1, text('http://ns.adobe.com/xap/1.0/\0<x/>'))])))).toEqual({ w: 4032, h: 3024 }))

  it('a frame header past the first read (a large APP2 before it) is read by a second slice, never the whole file', async () => {
    const big = segment(0xe2, new Array(65_000).fill(7))
    const file = blob([...jpeg(800, 600, [exif(6), big, big]), ...new Array(4_000_000).fill(0)])
    const slice = vi.spyOn(file, 'slice')
    expect(await imageSizeFromHeader(file)).toEqual({ w: 600, h: 800 })
    const read = slice.mock.calls.reduce((sum, [a = 0, b = file.size]) => sum + (b - a), 0)
    expect(read, 'a few windows of 64 KiB, not 4 MB').toBeLessThan(4 * 64 * 1024)
  })

  it('a scan before any frame header, a broken marker, or a cut file answers null', async () => {
    expect(await imageSizeFromHeader(blob([0xff, 0xd8, 0xff, 0xda, 0, 2, 1]))).toBeNull()
    expect(await imageSizeFromHeader(blob([0xff, 0xd8, 0xff, 0xe0, 0, 4, 1, 2, 0x00, 0x00, 0, 0]))).toBeNull()
    expect(await imageSizeFromHeader(blob(jpeg(800, 600).slice(0, 9))), 'cut inside the frame header').toBeNull()
  })

  it('a real file: the repo\'s EXIF sample (camera EXIF, GPS), 1 × 1 as `sips` reads it', async () => {
    const sample = readFileSync(new URL('../../features/memories/__fixtures__/exif-sample.jpg', import.meta.url))
    expect(await imageSizeFromHeader(blob(sample))).toEqual({ w: 1, h: 1 })
  })
})

describe('PNG, GIF and WebP: the size their first chunk names', () => {
  it('a PNG', async () =>
    expect(await imageSizeFromHeader(blob([0x89, ...text('PNG\r\n\x1a\n'), ...u32be(13), ...text('IHDR'), ...u32be(1920), ...u32be(1080), 8, 6, 0, 0, 0])))
      .toEqual({ w: 1920, h: 1080 }))
  it('a GIF', async () => expect(await imageSizeFromHeader(blob([...text('GIF89a'), ...u16le(320), ...u16le(240), 0, 0, 0]))).toEqual({ w: 320, h: 240 }))
  const riff = (chunk: string, body: number[]) => [...text('RIFF'), ...u32le(4 + 8 + body.length), ...text('WEBP'), ...text(chunk), ...u32le(body.length), ...body]
  it('a lossy WebP (VP8)', async () =>
    expect(await imageSizeFromHeader(blob(riff('VP8 ', [0, 0, 0, 0x9d, 0x01, 0x2a, ...u16le(1024), ...u16le(768), 0, 0])))).toEqual({ w: 1024, h: 768 }))
  it('a lossless WebP (VP8L)', async () => {
    const bits = (1024 - 1) | ((768 - 1) << 14)
    expect(await imageSizeFromHeader(blob(riff('VP8L', [0x2f, ...u32le(bits), 0, 0, 0, 0, 0])))).toEqual({ w: 1024, h: 768 })
  })
  it('an extended WebP (VP8X)', async () =>
    expect(await imageSizeFromHeader(blob(riff('VP8X', [0, 0, 0, 0, 0xff, 0x0f, 0, 0x37, 0x0b, 0, 0, 0])))).toEqual({ w: 4096, h: 2872 }))
})

describe('what it does not read answers null, for the browser to read instead', () => {
  it.each([
    ['a HEIC', [0, 0, 0, 0x18, ...text('ftypheic'), 0, 0, 0, 0, ...text('mif1heic')]],
    ['an AVIF', [0, 0, 0, 0x1c, ...text('ftypavif'), 0, 0, 0, 0, ...text('avifmif1miaf')]],
    ['no image at all', text('hello, world, this is text')],
    ['nothing', []],
  ])('%s', async (_, bytes) => expect(await imageSizeFromHeader(blob(bytes))).toBeNull())
  it('a header that names a zero size', async () => expect(await imageSizeFromHeader(blob([...text('GIF89a'), 0, 0, 0, 0, 0]))).toBeNull())
})
