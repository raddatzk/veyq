import { describe, expect, it } from 'vitest'
import { b64std, b64url } from './base64'
import { kitPayload, parseKit } from './emergencyKit'
import { formatSecretKey } from './secretKey'

// A real-shaped Secret Key (32 fixed bytes in its printed form — what `accountBirth` puts in the kit; the bytes are
// contracts/crypto/vectors/v1.json § secret_key_text's first case) and an email with non-ASCII characters, because
// the UTF-8 path is the one place a rewrite of the codec can differ.
const SK = '0C5126-0Z4RPK-8ET295-85EQK5-DHSQN0-C8HYB9-V95BPA-WW1HYE-TQE0ND'
const KIT = { email: 'zoë.müller@example.com', secretKey: SK }

// The vector. Computed ONCE by an independent Python implementation (json.dumps without ASCII escapes and with
// compact separators, then unpadded base64url) on KIT above — never recompute it from this package's code, or it
// proves nothing. It is what pins every kit either writer saves to the one reader that must accept it.
const TOKEN =
  'veyqkit:eyJ2IjoyLCJlbWFpbCI6Inpvw6subcO8bGxlckBleGFtcGxlLmNvbSIsInNrIjoiMEM1MTI2LTBaNFJQSy04RVQyOTUtODVFUUs1LURIU1FOMC1DOEhZQjktVjk1QlBBLVdXMUhZRS1UUUUwTkQifQ'

/** A token over arbitrary bytes, for the cases `kitPayload` can never produce. */
const tokenOf = (bytes: Uint8Array) => 'veyqkit:' + b64url(bytes)
const tokenFor = (o: unknown) => tokenOf(new TextEncoder().encode(JSON.stringify(o)))
const skOf = (fill: number) => formatSecretKey(new Uint8Array(32).fill(fill))

describe('emergencyKit codec', () => {
  it('writes the pinned vector', () => {
    expect(kitPayload(KIT)).toBe(TOKEN)
  })

  it('writes the key in its printed form, however it was spelled', () => {
    expect(kitPayload({ ...KIT, secretKey: SK.toLowerCase().replace(/-/g, ' ') })).toBe(TOKEN)
  })

  it('refuses to write a key that does not read as one', () => {
    expect(() => kitPayload({ ...KIT, secretKey: SK.slice(0, -1) + (SK.endsWith('D') ? 'E' : 'D') })).toThrow(/typo/)
    expect(() => kitPayload({ ...KIT, secretKey: b64std(new Uint8Array(32)) })).toThrow(/malformed/)
  })

  it('reads the pinned vector', () => {
    expect(parseKit(TOKEN)).toEqual(KIT)
  })

  it('finds the first token embedded in surrounding text (e.g. PDF bytes)', () => {
    const other = kitPayload({ email: 'other@example.com', secretKey: skOf(0x5a) })
    expect(parseKit(`%PDF-1.7 …/Keywords (${TOKEN})… stream ${other}`)).toEqual(KIT)
  })

  it('accepts a well-formed token built by hand (the positive control for every case below)', () => {
    expect(parseKit(tokenFor({ v: 2, email: 'a@b.c', sk: skOf(0x5a) }))).toEqual({ email: 'a@b.c', secretKey: skOf(0x5a) })
  })

  it('reads a key spelled the way a person might type it, and hands back its printed form', () => {
    expect(parseKit(tokenFor({ v: 2, email: 'a@b.c', sk: SK.toLowerCase().replace(/-/g, '').replace(/0/g, 'o') }))).toEqual({ email: 'a@b.c', secretKey: SK })
  })

  describe('rejects', () => {
    it('text without a token', () => {
      expect(parseKit('just some pdf bytes, no kit here')).toBeNull()
    })

    it('a version other than 2 — version 1 included, whose key was base64', () => {
      expect(parseKit(tokenFor({ v: 1, email: 'a@b.c', sk: skOf(0x5a) }))).toBeNull()
      expect(parseKit(tokenFor({ v: 1, email: 'a@b.c', sk: b64std(new Uint8Array(32).fill(0x5a)) }))).toBeNull()
      expect(parseKit(tokenFor({ v: 3, email: 'a@b.c', sk: skOf(0x5a) }))).toBeNull()
      expect(parseKit(tokenFor({ v: '2', email: 'a@b.c', sk: skOf(0x5a) }))).toBeNull()
    })

    it('an email that is not a string', () => {
      expect(parseKit(tokenFor({ v: 2, email: 42, sk: skOf(0x5a) }))).toBeNull()
    })

    it('an empty email', () => {
      expect(parseKit(tokenFor({ v: 2, email: '', sk: skOf(0x5a) }))).toBeNull()
    })

    it('a Secret Key one symbol short', () => {
      expect(parseKit(tokenFor({ v: 2, email: 'a@b.c', sk: SK.slice(0, -1) }))).toBeNull()
    })

    it('a Secret Key with a typo (its check symbols disagree)', () => {
      expect(parseKit(tokenFor({ v: 2, email: 'a@b.c', sk: SK.slice(0, -1) + (SK.endsWith('D') ? 'E' : 'D') }))).toBeNull()
    })

    it('a Secret Key as base64', () => {
      expect(parseKit(tokenFor({ v: 2, email: 'a@b.c', sk: b64std(new Uint8Array(32).fill(0x5a)) }))).toBeNull()
    })

    it('a Secret Key that is not a string', () => {
      for (const sk of [null, 32, true, [SK], { length: 32 }]) {
        expect(parseKit(tokenFor({ v: 2, email: 'a@b.c', sk }))).toBeNull()
      }
    })

    it('a payload that is not JSON', () => {
      expect(parseKit('veyqkit:not-valid-base64-json')).toBeNull()
      expect(parseKit(tokenFor(null))).toBeNull()
    })

    it('invalid UTF-8 (which the default decoder would turn into U+FFFD)', () => {
      const [head, tail] = JSON.stringify({ v: 2, email: 'a@b.c', sk: skOf(0x5a) }).split('a@b.c')
      const enc = new TextEncoder()
      const bytes = Uint8Array.from([...enc.encode(head + 'a'), 0xff, ...enc.encode('@b.c' + tail)])
      expect(parseKit(tokenOf(bytes))).toBeNull()
    })

    it('a leading BOM (which the default decoder would strip)', () => {
      const json = new TextEncoder().encode(JSON.stringify({ v: 2, email: 'a@b.c', sk: skOf(0x5a) }))
      expect(parseKit(tokenOf(Uint8Array.from([0xef, 0xbb, 0xbf, ...json])))).toBeNull()
    })
  })
})
