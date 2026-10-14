// The safety-number QR payload (ADR 0028 Decision 3, Decision 5 of cr-identity-verification). Un-mocked on purpose:
// the payload is drawn by the real `qrcode` encoder and read back by the real `jsqr` decoder, so the round trip is
// the libraries', not a stub's - and the size bound is measured on the QR version `qrcode` actually picks.
import { describe, it, expect } from 'vitest'
import QRCode from 'qrcode'
import jsQR from 'jsqr'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import { compareScan, parseSafetyCode, safetyCodePayload, fingerprintDigestB64 } from './safetyCode'
import { safetyNumber } from './identityPins'

const bundle = () => b64std(v1.serializeIdentityPublic(v1.generateIdentity().pub))

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
  it('is the marker, the version and the digest the 6x5 digits are rendered from - nothing else', () => {
    const key = bundle()
    const payload = safetyCodePayload(key)
    expect(payload).toMatch(/^veyqid:1:[A-Za-z0-9+/]{43}=$/)
    expect(payload).toBe(`veyqid:1:${fingerprintDigestB64(key)}`)
    // The digits a person reads are this same digest's: the vector-pinned safetyNumber is v1.fingerprint over it.
    const pub = v1.deserializeIdentityPublic(Uint8Array.from(atob(key), (c) => c.charCodeAt(0)))
    expect(b64std(v1.fingerprintHash(pub))).toBe(fingerprintDigestB64(key))
    expect(safetyNumber(key)).toBe(v1.fingerprint(pub))
  })

  it('round-trips through the real encoder and decoder and then matches the key it was drawn from', () => {
    const key = bundle()
    const scanned = roundTrip(safetyCodePayload(key))
    expect(scanned).toBe(safetyCodePayload(key))
    expect(compareScan(scanned!, key)).toBe('match')
  })

  it('stays far below the jsqr bound web/CLAUDE.md rests on: a short payload and a low QR version', () => {
    const payload = safetyCodePayload(bundle())
    expect(payload.length).toBe(53)
    // The exception is bounded at the known version-23 defect; our other markers sit at versions 3-4.
    expect(QRCode.create(payload, { errorCorrectionLevel: 'M' }).version).toBeLessThanOrEqual(4)
    // Control: the identity bundle this payload deliberately does NOT carry would void the exception.
    expect(QRCode.create(`veyqid:1:${bundle()}`, { errorCorrectionLevel: 'M' }).version).toBeGreaterThan(23)
  })

  it('a safety code of a DIFFERENT key is a mismatch, never a match', () => {
    const mine = bundle()
    const theirs = bundle()
    expect(compareScan(safetyCodePayload(theirs), mine)).toBe('mismatch')
    expect(compareScan(safetyCodePayload(mine), mine)).toBe('match') // control on the same predicate
  })

  it.each([
    ['another veyq marker (a pairing code)', (d: string) => `veyqpair:${d}`],
    ['an Emergency Kit', () => 'veyqkit:1:abc'],
    ['a URL', () => 'https://example.org/'],
    ['another version', (d: string) => `veyqid:2:${d}`],
    ['something riding after the digest', (d: string) => `veyqid:1:${d}?x=1`],
    ['a digest of the wrong length', () => `veyqid:1:${b64std(new Uint8Array(31))}`],
    ['no digest at all', () => 'veyqid:1:'],
  ])('%s is no safety code - foreign, not a mismatch', (_label, make) => {
    const key = bundle()
    const digest = fingerprintDigestB64(key)
    expect(parseSafetyCode(make(digest))).toBeNull()
    expect(compareScan(make(digest), key)).toBe('foreign')
  })

  it('control: the well-formed code of the same digest parses (so each refusal above is its own)', () => {
    const key = bundle()
    expect(parseSafetyCode(`veyqid:1:${fingerprintDigestB64(key)}`)).toBe(fingerprintDigestB64(key))
    expect(parseSafetyCode(`  veyqid:1:${fingerprintDigestB64(key)}\n`)).toBe(fingerprintDigestB64(key))
  })
})
