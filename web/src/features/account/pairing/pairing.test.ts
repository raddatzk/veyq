// uc.account.onboard-device (web, ADR 0059) — the three client-side properties the pairing ceremony rests on
// and that no `scripts/` validator can see, because seeing them means parsing TypeScript:
//
//   1. the displayed code names no server (§4) — the payload is the marker and the handle, and nothing else;
//   2. the grant survives exactly one round trip through the ephemeral transport key, and nothing else opens it;
//   3. the check number is a function of the answering key alone (§5) — same key, same digits; different key,
//      different digits.
//
// Every negative here carries the positive control on the same predicate, because "it returned null" and "it
// threw" are produced just as well by a module that does nothing at all.
import { describe, it, expect, vi, afterEach } from 'vitest'
import * as v1 from '../../../shared/crypto/v1'
import { b64std } from '../../../shared/base64'
import {
  mintHandle,
  pairingPayload,
  parsePairingPayload,
  mintTransportKey,
  transportAnswer,
  readTransportAnswer,
  checkNumber,
  sealGrant,
  openGrant,
} from './pairing'

const grant = () => ({
  email: 'someone@example.org',
  masterKey: v1.randomBytes(32),
  refreshToken: 'cmVmcmVzaC10b2tlbi1vcGFxdWU',
  label: 'Küchen-Tablet',
})

afterEach(() => vi.restoreAllMocks())

describe('the pairing payload (ADR 0059 §4, D5)', () => {
  it('round-trips a real handle unchanged — the control every negative below needs', () => {
    const handle = mintHandle()
    expect(parsePairingPayload(pairingPayload(handle))).toBe(handle)
  })

  it('carries the marker and the handle and nothing else', () => {
    expect(pairingPayload('SGFuZGxl')).toBe('veyqpair:SGFuZGxl')
  })

  it('is never URL-shaped: no scheme separator, no host, no path — over 200 minted handles', () => {
    for (let i = 0; i < 200; i++) {
      const payload = pairingPayload(mintHandle())
      expect(payload).not.toContain('://')
      expect(payload.slice('veyqpair:'.length)).not.toMatch(/^\//)
      expect(payload).not.toMatch(/https?:/)
      expect(payload).not.toMatch(/\./) // no host-shaped token — base64 has no dot
    }
  })

  // The bite for the loop in `mintHandle`. 200 random handles cannot show it: a leading '/' happens once in 64,
  // so the sweep above passes ~4% of the time with the guard deleted. Forcing the first draw is what makes the
  // property observable — a first byte of 0xFF encodes to '/' as base64's 63rd symbol.
  it('re-mints a handle whose encoding would make the payload read `veyqpair://`', () => {
    const leadingSlash = new Uint8Array(32).fill(0xff)
    const clean = new Uint8Array(32).fill(0x00)
    const draws = vi
      .spyOn(v1, 'randomBytes')
      .mockImplementationOnce(() => leadingSlash)
      .mockImplementationOnce(() => clean)

    expect(b64std(leadingSlash)).toMatch(/^\//) // the lever is wired: this draw WOULD produce `veyqpair://`
    const handle = mintHandle()

    expect(draws).toHaveBeenCalledTimes(2)
    expect(handle).toBe(b64std(clean))
    expect(pairingPayload(handle)).not.toContain('://')
  })

  it('refuses a payload that is not exactly the marker and a handle', () => {
    expect(parsePairingPayload('veyqkit:eyJ2IjoxfQ')).toBeNull() // the Emergency Kit's own payload
    expect(parsePairingPayload('veyqpair:SGFuZGxl?to=https://evil.example')).toBeNull()
    expect(parsePairingPayload('https://evil.example/veyqpair:SGFuZGxl')).toBeNull()
    expect(parsePairingPayload('veyqpair:')).toBeNull()
    expect(parsePairingPayload('just some text')).toBeNull()
  })
})

describe('the sealed grant (ADR 0059 §3, D4)', () => {
  it('round-trips every field through the transport key', () => {
    const transport = mintTransportKey()
    const original = grant()

    const opened = openGrant(transport.priv, sealGrant(transport.pub, original))

    expect(opened.email).toBe(original.email)
    expect(opened.masterKey).toEqual(original.masterKey)
    expect(opened.refreshToken).toBe(original.refreshToken)
    expect(opened.label).toBe(original.label)
  })

  // The negative that matters: an envelope is sealed to ONE key. Anchored on the failed open of a real
  // envelope by a real second key — not on "it threw", which a malformed input produces just as well.
  it('does not open with a different transport key, while the intended one still does', () => {
    const intended = mintTransportKey()
    const other = mintTransportKey()
    const envelope = sealGrant(intended.pub, grant())

    expect(() => openGrant(other.priv, envelope)).toThrow()
    expect(openGrant(intended.priv, envelope).label).toBe('Küchen-Tablet')
  })

  it('travels as the answering key does — the trusted device reads the answer off the wire and seals to it', () => {
    const transport = mintTransportKey()
    const asRead = readTransportAnswer(transportAnswer(transport.pub))

    expect(openGrant(transport.priv, sealGrant(asRead, grant())).email).toBe('someone@example.org')
  })

  it('stays inside the contract\'s 8192-char ceiling for a realistic grant', () => {
    const transport = mintTransportKey()
    const envelope = sealGrant(transport.pub, { ...grant(), label: 'x'.repeat(200) })

    expect(envelope.length).toBeLessThan(8192)
  })
})

describe('the check number (ADR 0059 §5, D2)', () => {
  it('is five digits, and both sides derive the same one from the same answering key', () => {
    const transport = mintTransportKey()
    // The new device derives it from the bundle it minted; the trusted device from the bundle it read off the
    // wire. Same number, or the compare means nothing.
    const onTheNewDevice = checkNumber(transport.pub)
    const onTheTrustedDevice = checkNumber(readTransportAnswer(transportAnswer(transport.pub)))

    expect(onTheNewDevice).toMatch(/^\d{5}$/)
    expect(onTheTrustedDevice).toBe(onTheNewDevice)
  })

  // The photographed-QR race, at the only altitude a unit test reaches: a second device answering the channel
  // shows a DIFFERENT number, which is the whole reason the user is asked to compare.
  it('differs for a different answering key', () => {
    expect(checkNumber(mintTransportKey().pub)).not.toBe(checkNumber(mintTransportKey().pub))
  })

  it('is the first group of the already-pinned safety-number digest, not a new derivation', () => {
    const pub = mintTransportKey().pub

    expect(checkNumber(pub)).toBe(v1.fingerprint(pub).split(' ')[0])
  })
})
