// The safety-number QR payload (ADR 0028 Decision 3, ADR 0111 decision 14). Un-mocked on purpose:
// the payload is drawn by the real `qrcode` encoder and read back by the real `jsqr` decoder, so the round trip is
// the libraries', not a stub's - and the size bound is measured on the QR version `qrcode` actually picks.
import { describe, it, expect } from 'vitest'
import QRCode from 'qrcode'
import jsQR from 'jsqr'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import { chainCode, compareScan, parseSafetyCode, safetyCodePayload, fingerprintDigestB64 } from './safetyCode'
import { safetyNumber } from './identityPins'

const bundle = () => b64std(v1.serializeIdentityPublic(v1.generateIdentity().pub))
/** A key-chain head: 32 bytes, as the directory serves it beside a key. */
const aHead = () => b64std(v1.randomBytes(32))
/** What the caller would trust for `key` at `head`: the fingerprint digest and the head. */
const expected = (key: string, head: string) => ({ fingerprint: fingerprintDigestB64(key), head })

/** Draw a payload with the real encoder and decode the pixels with the real decoder (4 px a module, quiet zone 4). */
function roundTrip(payload: string): string | null {
  const qr = QRCode.create(payload, { errorCorrectionLevel: 'M' })
  const scale = 4
  const quiet = 4
  const n = qr.modules.size
  const side = (n + 2 * quiet) * scale
  const rgba = new Uint8ClampedArray(side * side * 4).fill(255)
  for (let y = 0; y < n; y++) {
    for (let x = 0; x < n; x++) {
      if (!qr.modules.get(y, x)) continue
      for (let dy = 0; dy < scale; dy++) {
        for (let dx = 0; dx < scale; dx++) {
          const px = ((y + quiet) * scale + dy) * side + (x + quiet) * scale + dx
          rgba[px * 4] = rgba[px * 4 + 1] = rgba[px * 4 + 2] = 0
        }
      }
    }
  }
  return jsQR(rgba, side, side)?.data ?? null
}

describe('the safety-number QR payload', () => {
  it('is the marker, the version, the digest the 6x5 digits are rendered from and the head - nothing else', () => {
    const key = bundle()
    const head = aHead()
    const payload = safetyCodePayload(key, head)
    expect(payload).toMatch(/^veyqid:2:[A-Za-z0-9+/]{43}=:[A-Za-z0-9+/]{43}=$/)
    expect(payload).toBe(`veyqid:2:${fingerprintDigestB64(key)}:${head}`)
    // The digits a person reads are this same digest's: the vector-pinned safetyNumber is v1.fingerprint over it.
    const pub = v1.deserializeIdentityPublic(Uint8Array.from(atob(key), (c) => c.charCodeAt(0)))
    expect(b64std(v1.fingerprintHash(pub))).toBe(fingerprintDigestB64(key))
    expect(safetyNumber(key)).toBe(v1.fingerprint(pub))
    expect(parseSafetyCode(payload)).toEqual({ version: 2, fingerprint: fingerprintDigestB64(key), head })
  })

  it('the chain code is the two groups v1.chainCodeDigits renders from the head the payload carries', () => {
    const head = aHead()
    expect(chainCode(head)).toBe(v1.chainCodeDigits(Uint8Array.from(atob(head), (c) => c.charCodeAt(0))))
    expect(chainCode(head)).toMatch(/^\d{5} \d{5}$/)
    expect(() => chainCode(b64std(new Uint8Array(31)))).toThrow()
  })

  it('round-trips through the real encoder and decoder and then matches the key and head it was drawn from', () => {
    const key = bundle()
    const head = aHead()
    const scanned = roundTrip(safetyCodePayload(key, head))
    expect(scanned).toBe(safetyCodePayload(key, head))
    expect(compareScan(scanned!, expected(key, head))).toBe('match')
  })

  it('stays far below the jsqr bound src/web/CLAUDE.md rests on: a short payload and a low QR version', () => {
    const payload = safetyCodePayload(bundle(), aHead())
    expect(payload.length).toBe(98)
    // The exception is bounded at the known version-23 defect; our other markers sit at versions 3-4, this one at 6.
    expect(QRCode.create(payload, { errorCorrectionLevel: 'M' }).version).toBeLessThanOrEqual(6)
    // Control: the identity bundle this payload deliberately does NOT carry would void the exception.
    expect(QRCode.create(`veyqid:2:${bundle()}`, { errorCorrectionLevel: 'M' }).version).toBeGreaterThan(23)
  })

  it('a safety code of a DIFFERENT key is a mismatch, never a match', () => {
    const mine = bundle()
    const theirs = bundle()
    const head = aHead()
    expect(compareScan(safetyCodePayload(theirs, head), expected(mine, head))).toBe('mismatch')
    expect(compareScan(safetyCodePayload(mine, head), expected(mine, head))).toBe('match') // control on the same predicate
  })

  it('the same key at ANOTHER head - a step withheld from one side - is a mismatch, never a match', () => {
    const key = bundle()
    const mine = aHead()
    const withheld = aHead()
    expect(compareScan(safetyCodePayload(key, withheld), expected(key, mine))).toBe('mismatch')
    expect(compareScan(safetyCodePayload(key, mine), expected(key, mine))).toBe('match') // control on the same predicate
  })

  it('a well-formed veyqid:1 code is outdated - the other app shows no head - even where its digest is the key\'s', () => {
    const key = bundle()
    const head = aHead()
    const old = `veyqid:1:${fingerprintDigestB64(key)}`
    expect(parseSafetyCode(old)).toEqual({ version: 1, fingerprint: fingerprintDigestB64(key) })
    expect(compareScan(old, expected(key, head))).toBe('outdated')
    // control: a veyqid:1 code whose digest is no 32 bytes is no code at all, not an outdated one
    expect(compareScan(`veyqid:1:${b64std(new Uint8Array(31))}`, expected(key, head))).toBe('foreign')
  })

  it.each([
    ['another veyq marker (a pairing code)', (d: string, h: string) => `veyqpair:${d}:${h}`],
    ['an Emergency Kit', () => 'veyqkit:1:abc'],
    ['a URL', () => 'https://example.org/'],
    ['another version', (d: string, h: string) => `veyqid:3:${d}:${h}`],
    ['something riding after the head', (d: string, h: string) => `veyqid:2:${d}:${h}?x=1`],
    ['a third field', (d: string, h: string) => `veyqid:2:${d}:${h}:${h}`],
    ['a digest of the wrong length', (_d: string, h: string) => `veyqid:2:${b64std(new Uint8Array(31))}:${h}`],
    ['a head of the wrong length', (d: string) => `veyqid:2:${d}:${b64std(new Uint8Array(33))}`],
    ['no head at all', (d: string) => `veyqid:2:${d}`],
    ['no digest at all', () => 'veyqid:2:'],
  ])('%s is no safety code - foreign, not a mismatch', (_label, make) => {
    const key = bundle()
    const head = aHead()
    const digest = fingerprintDigestB64(key)
    expect(parseSafetyCode(make(digest, head))).toBeNull()
    expect(compareScan(make(digest, head), expected(key, head))).toBe('foreign')
  })

  it('control: the well-formed code of the same digest and head parses (so each refusal above is its own)', () => {
    const key = bundle()
    const head = aHead()
    const parsed = { version: 2, fingerprint: fingerprintDigestB64(key), head }
    expect(parseSafetyCode(`veyqid:2:${fingerprintDigestB64(key)}:${head}`)).toEqual(parsed)
    expect(parseSafetyCode(`  veyqid:2:${fingerprintDigestB64(key)}:${head}\n`)).toEqual(parsed)
  })
})
