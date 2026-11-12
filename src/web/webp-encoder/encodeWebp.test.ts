// ADR 0127 — the real encoder, run in Node: the committed `.wasm` is read from disk and handed to `loadWebpEncoder`,
// and every assertion is on the bytes it returns. The bitstream is read here by hand (RIFF chunks, the VP8 key frame
// header, the VP8X canvas), because the encoder is all this directory holds; the size a decoder would report is the
// size those headers carry.
import { readFileSync } from 'node:fs'
import { gunzipSync } from 'node:zlib'
import { beforeAll, describe, expect, it } from 'vitest'
import { encodeWebp, loadWebpEncoder } from './encodeWebp'

const here = (name: string) => new URL(name, import.meta.url)

/** The pad a space icon and a member picture are sealed in (ADR 0084), less its length prefix: what the WebP may use. */
const PAD_BUDGET = 16_380

type Chunk = { fourcc: string; data: Uint8Array }

/** The RIFF/WEBP container's chunks, refusing anything that is not one. */
function chunks(webp: Uint8Array): Chunk[] {
  const view = new DataView(webp.buffer, webp.byteOffset, webp.byteLength)
  const ascii = (at: number) => String.fromCharCode(...webp.subarray(at, at + 4))
  expect(ascii(0)).toBe('RIFF')
  expect(ascii(8)).toBe('WEBP')
  expect(view.getUint32(4, true)).toBe(webp.length - 8)
  const found: Chunk[] = []
  for (let at = 12; at < webp.length; ) {
    const size = view.getUint32(at + 4, true)
    found.push({ fourcc: ascii(at), data: webp.subarray(at + 8, at + 8 + size) })
    at += 8 + size + (size % 2)
  }
  return found
}

/** The picture size a decoder reads: the VP8X canvas when there is one, else the VP8 key frame's header. */
function decodedSize(webp: Uint8Array): { width: number; height: number } {
  const list = chunks(webp)
  const vp8x = list.find((c) => c.fourcc === 'VP8X')
  const vp8 = list.find((c) => c.fourcc === 'VP8 ')
  expect(vp8, 'a lossy VP8 bitstream').toBeDefined()
  const frame = vp8!.data
  expect([...frame.subarray(3, 6)], 'the VP8 key frame start code').toEqual([0x9d, 0x01, 0x2a])
  const frameSize = { width: (frame[6] | (frame[7] << 8)) & 0x3fff, height: (frame[8] | (frame[9] << 8)) & 0x3fff }
  if (!vp8x) return frameSize
  const u24 = (at: number) => vp8x.data[at] | (vp8x.data[at + 1] << 8) | (vp8x.data[at + 2] << 16)
  const canvas = { width: u24(4) + 1, height: u24(7) + 1 }
  expect(canvas, 'the VP8X canvas and the frame agree').toEqual(frameSize)
  return canvas
}

/** A generated photo-like picture (fixtures/make_photo_fixture.py), never a person's. */
function photo(): Uint8Array {
  const rgba = gunzipSync(readFileSync(here('./fixtures/photo-256.rgba.gz')))
  expect(rgba.length).toBe(256 * 256 * 4)
  return new Uint8Array(rgba.buffer, rgba.byteOffset, rgba.length)
}

/** A logo: an opaque disc on a fully transparent square, with a soft (semi-transparent) rim. */
function logo(edge: number): Uint8Array {
  const rgba = new Uint8Array(edge * edge * 4)
  for (let y = 0; y < edge; y++) {
    for (let x = 0; x < edge; x++) {
      const d = Math.hypot(x - edge / 2, y - edge / 2)
      const alpha = Math.max(0, Math.min(1, edge / 3 - d))
      rgba.set([200, 40, 90, Math.round(alpha * 255)], (y * edge + x) * 4)
    }
  }
  return rgba
}

beforeAll(async () => {
  await loadWebpEncoder(readFileSync(here('./wasm/webp_encoder.wasm')))
})

describe('encodeWebp — the committed libwebp build, on real bytes', () => {
  it('writes a RIFF/WEBP file', async () => {
    const webp = await encodeWebp(photo(), 256, 256, 0.9)
    expect(chunks(webp).length).toBeGreaterThan(0)
  })

  it('encodes an opaque picture as one lossy `VP8 ` chunk, with no alpha', async () => {
    const webp = await encodeWebp(photo(), 256, 256, 0.9)
    expect(chunks(webp).map((c) => c.fourcc)).toEqual(['VP8 '])
  })

  it('keeps transparency: `VP8X` with its alpha flag, then `ALPH`, then the lossy `VP8 `', async () => {
    const webp = await encodeWebp(logo(64), 64, 64, 0.9)
    const list = chunks(webp)
    expect(list.map((c) => c.fourcc)).toEqual(['VP8X', 'ALPH', 'VP8 '])
    expect(list[0].data[0] & 0x10, 'the VP8X alpha flag').toBe(0x10)
  })

  it('writes fewer bytes as the quality falls', async () => {
    const sizes = []
    for (const quality of [1, 0.9, 0.72, 0.5, 0.2]) sizes.push((await encodeWebp(photo(), 256, 256, quality)).length)
    for (let i = 1; i < sizes.length; i++) expect(sizes[i], `sizes ${sizes.join(' > ')}`).toBeLessThan(sizes[i - 1])
  })

  it('decodes to the size it was given, square or not', async () => {
    expect(decodedSize(await encodeWebp(photo(), 256, 256, 0.9))).toEqual({ width: 256, height: 256 })
    expect(decodedSize(await encodeWebp(logo(64).subarray(0, 64 * 37 * 4), 64, 37, 0.9))).toEqual({ width: 64, height: 37 })
    expect(decodedSize(await encodeWebp(logo(64), 64, 64, 0.5))).toEqual({ width: 64, height: 64 })
  })

  it('fits a 256 px photo in the pad at quality 0.9, the first step of the icon ladder', async () => {
    const webp = await encodeWebp(photo(), 256, 256, 0.9)
    expect(webp.length).toBeLessThanOrEqual(PAD_BUDGET)
  })

  it('refuses input that does not match its size, and a quality outside 0–1', async () => {
    await expect(encodeWebp(new Uint8Array(15), 2, 2, 0.9)).rejects.toThrow('webp_encode_pixels')
    await expect(encodeWebp(new Uint8Array(0), 0, 0, 0.9)).rejects.toThrow('webp_encode_size')
    await expect(encodeWebp(new Uint8Array(16), 2, 2, 1.5)).rejects.toThrow('webp_encode_quality')
  })
})
