// The apps' QR writer read by the web's reader (cr-core-qr-decoder phase 2): `__fixtures__/core-pairing-code.png` is what
// the Kotlin core's `QrEncoder` + `QrPng` write for a pairing payload (its `QrPngImageTest` holds the file to that output),
// and the web's own `jsqr` must read it, so a code a desktop shows is one the web's scanner takes. The PNG is the core's
// 1-bit greyscale of stored deflate blocks, so `node:zlib` unpacks it with no image library.
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { inflateSync } from 'node:zlib'
import jsQR from 'jsqr'

/** A 1-bit greyscale, non-interlaced PNG's pixels as RGBA: IHDR's size, the IDAT chunks inflated, filter 0 rows. */
function grey1(png: Uint8Array): { width: number; height: number; rgba: Uint8ClampedArray } {
  const view = new DataView(png.buffer, png.byteOffset, png.byteLength)
  let width = 0
  let height = 0
  const idat: Uint8Array[] = []
  for (let at = 8; at < png.length; ) {
    const length = view.getUint32(at)
    const type = new TextDecoder().decode(png.subarray(at + 4, at + 8))
    const data = png.subarray(at + 8, at + 8 + length)
    if (type === 'IHDR') {
      width = view.getUint32(at + 8)
      height = view.getUint32(at + 12)
      expect([data[8], data[9], data[12]]).toEqual([1, 0, 0]) // bit depth 1, grey, no interlace
    }
    if (type === 'IDAT') idat.push(data)
    at += 12 + length
  }
  const raw = inflateSync(Buffer.concat(idat))
  const rowBytes = Math.ceil(width / 8)
  const rgba = new Uint8ClampedArray(width * height * 4).fill(255)
  for (let y = 0; y < height; y++) {
    expect(raw[y * (rowBytes + 1)]).toBe(0) // filter type None
    for (let x = 0; x < width; x++) {
      const bit = (raw[y * (rowBytes + 1) + 1 + (x >> 3)] >> (7 - (x & 7))) & 1
      if (bit === 0) rgba.fill(0, (y * width + x) * 4, (y * width + x) * 4 + 3)
    }
  }
  return { width, height, rgba }
}

describe("the apps' QR code", () => {
  it("is read by the web's jsqr as the text the core encoded", () => {
    const png = new Uint8Array(readFileSync(fileURLToPath(new URL('./__fixtures__/core-pairing-code.png', import.meta.url))))
    const { width, height, rgba } = grey1(png)
    expect(jsQR(rgba, width, height)?.data).toBe('veyqpair:3q2-7wABAgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhscHR4')
  })
})
