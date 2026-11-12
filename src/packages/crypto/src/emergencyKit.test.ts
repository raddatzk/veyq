import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { b64std, b64url } from './base64'
import { isKitServer, kitPayload, parseKit, type KitData } from './emergencyKit'
import * as secretKey from './secretKey'
import { formatSecretKey } from './secretKey'

// A real-shaped Secret Key (32 fixed bytes in its printed form — what `accountBirth` puts in the kit; the bytes are
// contracts/crypto/vectors/v1.json § secret_key_text's first case) and an email with non-ASCII characters, because
// the UTF-8 path is the one place a rewrite of the codec can differ.
const SK = '0C5126-0Z4RPK-8ET295-85EQK5-DHSQN0-C8HYB9-V95BPA-WW1HYE-TQE0ND'
const KIT = { email: 'zoë.müller@example.com', secretKey: SK }

// The vectors, contracts/crypto/vectors/v1.json § emergency_kit_token: computed ONCE by an independent Python
// implementation (json.dumps without ASCII escapes and with compact separators, then unpadded base64url) — never
// recompute them from this package's code, or they prove nothing. They pin every kit either writer saves to the readers
// that must accept it, this package's and the native apps'.
const here = dirname(fileURLToPath(import.meta.url))
const vectors = JSON.parse(readFileSync(resolve(here, '../../../../contracts/crypto/vectors/v1.json'), 'utf8')).emergency_kit_token as {
  cases: { email: string; secret_key: string; server?: string; token: string }[]
  refused: { why: string; token: string }[]
}
const dataOf = (c: (typeof vectors.cases)[number]): KitData =>
  c.server === undefined ? { email: c.email, secretKey: c.secret_key } : { email: c.email, secretKey: c.secret_key, server: c.server }
// The case with no server is KIT's own token.
const TOKEN = vectors.cases.find((c) => c.server === undefined && c.email === KIT.email && c.secret_key === SK)!.token

/** A token over arbitrary bytes, for the cases `kitPayload` can never produce. */
const tokenOf = (bytes: Uint8Array) => 'veyqkit:' + b64url(bytes)
const tokenFor = (o: unknown) => tokenOf(new TextEncoder().encode(JSON.stringify(o)))
const skOf = (fill: number) => formatSecretKey(new Uint8Array(32).fill(fill))

describe('emergencyKit codec', () => {
  it('has vectors with and without a server, and refused ones (the positive control for the loops below)', () => {
    expect(vectors.cases.filter((c) => c.server !== undefined).length).toBeGreaterThanOrEqual(2)
    expect(vectors.cases.some((c) => c.server === undefined)).toBe(true)
    expect(vectors.refused.length).toBeGreaterThanOrEqual(5)
  })

  it('writes every pinned vector', () => {
    for (const c of vectors.cases) expect(kitPayload(dataOf(c))).toBe(c.token)
  })

  it('reads every pinned vector back to its data, with no server where it has none', () => {
    for (const c of vectors.cases) expect(parseKit(c.token)).toStrictEqual(dataOf(c))
  })

  it('refuses every pinned refused token', () => {
    for (const r of vectors.refused) expect(parseKit(r.token), r.why).toBeNull()
  })

  it('refuses to write a server that is no origin', () => {
    for (const server of ['https://veyq.example.com/', 'veyq.example.com', 'ftp://veyq.example.com', '']) {
      expect(() => kitPayload({ ...KIT, server })).toThrow(/origin/)
    }
  })

  it('takes exactly an http or https origin as a server', () => {
    expect(isKitServer('https://veyq.example.com')).toBe(true)
    expect(isKitServer('http://veyq.localhost:8081')).toBe(true)
    expect(isKitServer('https://xn--mller-kva.example')).toBe(true)
    for (const s of ['https://veyq.example.com/', 'https://veyq.example.com:443', 'https://Veyq.example.com', 'https://müller.example', 'blob:https://a.b', 'file:///x', 42, undefined]) {
      expect(isKitServer(s), String(s)).toBe(false)
    }
  })

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
    expect(parseKit(tokenFor({ v: 3, email: 'a@b.c', sk: skOf(0x5a) }))).toEqual({ email: 'a@b.c', secretKey: skOf(0x5a) })
  })

  it('reads a key spelled the way a person might type it, and hands back its printed form', () => {
    expect(parseKit(tokenFor({ v: 3, email: 'a@b.c', sk: SK.toLowerCase().replace(/-/g, '').replace(/0/g, 'o') }))).toEqual({ email: 'a@b.c', secretKey: SK })
  })

  // The codec decodes the Secret Key to its 32 bytes only to check and re-spell it; neither the token nor the kit it reads
  // back carries the bytes, so they are wiped before it returns. The spies are the only way to see them: the buffer is
  // what `readSecretKey` / `secretKeyBytes` handed the codec, and it must read as zeros once the codec is done with it.
  describe('wipes the key bytes it decoded', () => {
    afterEach(() => vi.restoreAllMocks())

    it('once the token is written', () => {
      const read = vi.spyOn(secretKey, 'readSecretKey')
      expect(kitPayload(KIT)).toBe(TOKEN)
      expect(read).toHaveBeenCalledTimes(1)
      const reading = read.mock.results[0].value as secretKey.SecretKeyReading
      expect(reading.ok && reading.key).toEqual(new Uint8Array(32))
    })

    it('once the key is printed again (the printed form is taken before the wipe)', () => {
      const bytes = vi.spyOn(secretKey, 'secretKeyBytes')
      expect(parseKit(TOKEN)).toEqual(KIT)
      expect(bytes).toHaveBeenCalledTimes(1)
      expect(bytes.mock.results[0].value).toEqual(new Uint8Array(32))
    })
  })

  describe('rejects', () => {
    it('text without a token', () => {
      expect(parseKit('just some pdf bytes, no kit here')).toBeNull()
    })

    it('a version other than 3 — 1, whose key was base64, and 2, which had no server, included', () => {
      expect(parseKit(tokenFor({ v: 1, email: 'a@b.c', sk: skOf(0x5a) }))).toBeNull()
      expect(parseKit(tokenFor({ v: 1, email: 'a@b.c', sk: b64std(new Uint8Array(32).fill(0x5a)) }))).toBeNull()
      expect(parseKit(tokenFor({ v: 2, email: 'a@b.c', sk: skOf(0x5a) }))).toBeNull()
      expect(parseKit(tokenFor({ v: 4, email: 'a@b.c', sk: skOf(0x5a) }))).toBeNull()
      expect(parseKit(tokenFor({ v: '3', email: 'a@b.c', sk: skOf(0x5a) }))).toBeNull()
    })

    it('a server that is no origin, though everything else reads', () => {
      expect(parseKit(tokenFor({ v: 3, email: 'a@b.c', sk: skOf(0x5a), srv: 'https://a.b' }))).toEqual({ email: 'a@b.c', secretKey: skOf(0x5a), server: 'https://a.b' })
      expect(parseKit(tokenFor({ v: 3, email: 'a@b.c', sk: skOf(0x5a), srv: 'https://a.b/' }))).toBeNull()
      expect(parseKit(tokenFor({ v: 3, email: 'a@b.c', sk: skOf(0x5a), srv: null }))).toBeNull()
    })

    it('an email that is not a string', () => {
      expect(parseKit(tokenFor({ v: 3, email: 42, sk: skOf(0x5a) }))).toBeNull()
    })

    it('an empty email', () => {
      expect(parseKit(tokenFor({ v: 3, email: '', sk: skOf(0x5a) }))).toBeNull()
    })

    it('a Secret Key one symbol short', () => {
      expect(parseKit(tokenFor({ v: 3, email: 'a@b.c', sk: SK.slice(0, -1) }))).toBeNull()
    })

    it('a Secret Key with a typo (its check symbols disagree)', () => {
      expect(parseKit(tokenFor({ v: 3, email: 'a@b.c', sk: SK.slice(0, -1) + (SK.endsWith('D') ? 'E' : 'D') }))).toBeNull()
    })

    it('a Secret Key as base64', () => {
      expect(parseKit(tokenFor({ v: 3, email: 'a@b.c', sk: b64std(new Uint8Array(32).fill(0x5a)) }))).toBeNull()
    })

    it('a Secret Key that is not a string', () => {
      for (const sk of [null, 32, true, [SK], { length: 32 }]) {
        expect(parseKit(tokenFor({ v: 3, email: 'a@b.c', sk }))).toBeNull()
      }
    })

    it('a payload that is not JSON', () => {
      expect(parseKit('veyqkit:not-valid-base64-json')).toBeNull()
      expect(parseKit(tokenFor(null))).toBeNull()
    })

    it('invalid UTF-8 (which the default decoder would turn into U+FFFD)', () => {
      const [head, tail] = JSON.stringify({ v: 3, email: 'a@b.c', sk: skOf(0x5a) }).split('a@b.c')
      const enc = new TextEncoder()
      const bytes = Uint8Array.from([...enc.encode(head + 'a'), 0xff, ...enc.encode('@b.c' + tail)])
      expect(parseKit(tokenOf(bytes))).toBeNull()
    })

    it('a leading BOM (which the default decoder would strip)', () => {
      const json = new TextEncoder().encode(JSON.stringify({ v: 3, email: 'a@b.c', sk: skOf(0x5a) }))
      expect(parseKit(tokenOf(Uint8Array.from([0xef, 0xbb, 0xbf, ...json])))).toBeNull()
    })
  })
})
