import { describe, expect, it } from 'vitest'
import { b64std, b64url } from './base64'
import { kitPayload, parseKit } from './emergencyKit'

// A real-shaped Secret Key (32 fixed bytes, padded standard base64 — what `accountBirth` puts in the kit) and an
// email with non-ASCII characters, because the UTF-8 path is the one place a rewrite of the codec can differ.
const SK = b64std(Uint8Array.from({ length: 32 }, (_, i) => (i * 7 + 3) & 0xff))
const KIT = { email: 'zoë.müller@example.com', secretKey: SK }

// The vector. Computed ONCE by running the codec `web` and `admin-web` each carried before it moved here, on KIT
// above — never recompute it from this package's code, or it proves nothing. It is what pins every kit already
// saved by either writer to the one reader that must still accept it.
const TOKEN =
  'veyqkit:eyJ2IjoxLCJlbWFpbCI6Inpvw6subcO8bGxlckBleGFtcGxlLmNvbSIsInNrIjoiQXdvUkdCOG1MVFE3UWtsUVYxNWxiSE42Z1lpUGxwMmtxN0s1d01mTzFkdz0ifQ'

/** A token over arbitrary bytes, for the cases `kitPayload` can never produce. */
const tokenOf = (bytes: Uint8Array) => 'veyqkit:' + b64url(bytes)
const tokenFor = (o: unknown) => tokenOf(new TextEncoder().encode(JSON.stringify(o)))
const skOf = (n: number) => b64std(new Uint8Array(n).fill(0x5a))

describe('emergencyKit codec', () => {
  it('writes the pinned vector', () => {
    expect(SK).toBe('AwoRGB8mLTQ7QklQV15lbHN6gYiPlp2kq7K5wMfO1dw=')
    expect(kitPayload(KIT)).toBe(TOKEN)
  })

  it('reads the pinned vector', () => {
    expect(parseKit(TOKEN)).toEqual(KIT)
  })

  it('finds the first token embedded in surrounding text (e.g. PDF bytes)', () => {
    const other = kitPayload({ email: 'other@example.com', secretKey: skOf(32) })
    expect(parseKit(`%PDF-1.7 …/Keywords (${TOKEN})… stream ${other}`)).toEqual(KIT)
  })

  it('accepts a well-formed token built by hand (the positive control for every case below)', () => {
    expect(parseKit(tokenFor({ v: 1, email: 'a@b.c', sk: skOf(32) }))).toEqual({ email: 'a@b.c', secretKey: skOf(32) })
  })

  describe('rejects', () => {
    it('text without a token', () => {
      expect(parseKit('just some pdf bytes, no kit here')).toBeNull()
    })

    it('a version other than 1', () => {
      expect(parseKit(tokenFor({ v: 2, email: 'a@b.c', sk: skOf(32) }))).toBeNull()
      expect(parseKit(tokenFor({ v: '1', email: 'a@b.c', sk: skOf(32) }))).toBeNull()
    })

    it('an email that is not a string', () => {
      expect(parseKit(tokenFor({ v: 1, email: 42, sk: skOf(32) }))).toBeNull()
    })

    it('an empty email', () => {
      expect(parseKit(tokenFor({ v: 1, email: '', sk: skOf(32) }))).toBeNull()
    })

    it('a Secret Key of 31 bytes', () => {
      expect(parseKit(tokenFor({ v: 1, email: 'a@b.c', sk: skOf(31) }))).toBeNull()
    })

    it('a Secret Key of 33 bytes', () => {
      expect(parseKit(tokenFor({ v: 1, email: 'a@b.c', sk: skOf(33) }))).toBeNull()
    })

    it('a Secret Key that is not base64', () => {
      expect(parseKit(tokenFor({ v: 1, email: 'a@b.c', sk: '!'.repeat(44) }))).toBeNull()
    })

    it('a Secret Key that is not a string', () => {
      for (const sk of [null, 32, true, [SK], { length: 32 }]) {
        expect(parseKit(tokenFor({ v: 1, email: 'a@b.c', sk }))).toBeNull()
      }
    })

    it('a payload that is not JSON', () => {
      expect(parseKit('veyqkit:not-valid-base64-json')).toBeNull()
      expect(parseKit(tokenFor(null))).toBeNull()
    })

    it('invalid UTF-8 (which the default decoder would turn into U+FFFD)', () => {
      const [head, tail] = JSON.stringify({ v: 1, email: 'a@b.c', sk: skOf(32) }).split('a@b.c')
      const enc = new TextEncoder()
      const bytes = Uint8Array.from([...enc.encode(head + 'a'), 0xff, ...enc.encode('@b.c' + tail)])
      expect(parseKit(tokenOf(bytes))).toBeNull()
    })

    it('a leading BOM (which the default decoder would strip)', () => {
      const json = new TextEncoder().encode(JSON.stringify({ v: 1, email: 'a@b.c', sk: skOf(32) }))
      expect(parseKit(tokenOf(Uint8Array.from([0xef, 0xbb, 0xbf, ...json])))).toBeNull()
    })
  })
})
