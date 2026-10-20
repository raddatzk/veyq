// @vitest-environment node
import { describe, expect, it } from 'vitest'
import { b64std, fromB64std, b64url, fromB64url } from './base64'

const STD_B64 = /^[A-Za-z0-9+/]*={0,2}$/

describe('base64 standard encoding (setup wire format)', () => {
  it('round-trips bytes of every length-mod-4 (incl. empty)', () => {
    for (let n = 0; n <= 9; n++) {
      const bytes = Uint8Array.from({ length: n }, (_, i) => (i * 73 + 11) & 0xff)
      expect(Array.from(fromB64std(b64std(bytes)))).toEqual(Array.from(bytes))
    }
  })

  it('produces padded STANDARD base64 (matches the contract regex)', () => {
    // 0xfb,0xff → standard "+/8=" (uses + and /, padded), NOT base64url "-_8"
    const bytes = Uint8Array.of(0xfb, 0xff)
    expect(b64std(bytes)).toBe('+/8=')
    expect(b64std(bytes)).toMatch(STD_B64)
    expect(b64url(bytes)).toBe('-_8') // the app-internal encoding the contract would 400-reject
  })

  it('decodes unpadded standard base64 too', () => {
    const bytes = Uint8Array.of(1, 2, 3, 4, 5)
    expect(Array.from(fromB64std(b64std(bytes).replace(/=+$/, '')))).toEqual([1, 2, 3, 4, 5])
  })

  it('b64url still round-trips (unchanged)', () => {
    const bytes = Uint8Array.of(0xfb, 0xff, 0x00, 0x10)
    expect(Array.from(fromB64url(b64url(bytes)))).toEqual([0xfb, 0xff, 0x00, 0x10])
  })
})
