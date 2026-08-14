// uc.account.onboard-device (web, ADR 0059) — the three client-side properties the pairing ceremony rests on
// and that no `scripts/` validator can see, because seeing them means parsing TypeScript:
//
//   1. the displayed code names no server (§4) — the payload is the marker and the handle, and nothing else,
//      under EITHER direction's marker, and neither marker ever parses as the other's;
//   2. the grant survives exactly one round trip through the ephemeral transport key, and nothing else opens it;
//   3. the check number is a function of the answering key alone (§5) — same key, same digits; different key,
//      different digits;
//   4. the pairing code's commitment binds the code to ONE key (§6) — a different key or a different code does
//      not verify, and the sign-in answer that carries it consumes its buffer exactly.
//
// Every negative here carries the positive control on the same predicate, because "it returned null" and "it
// threw" are produced just as well by a module that does nothing at all.
import { describe, it, expect, vi, afterEach } from 'vitest'
import * as v1 from '../../../shared/crypto/v1'
import { b64std, fromB64std } from '../../../shared/base64'
import {
  mintHandle,
  pairingPayload,
  parsePairingPayload,
  signInPayload,
  parseSignInPayload,
  mintPairingCode,
  codeCommitment,
  codeProofHolds,
  signInAnswer,
  readSignInAnswer,
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

describe('the sign-in payload (ADR 0059 §4, D1)', () => {
  it('round-trips a real handle unchanged — the control every negative below needs', () => {
    const handle = mintHandle()
    expect(parseSignInPayload(signInPayload(handle))).toBe(handle)
  })

  it('carries the second marker and the handle and nothing else — never the transport key', () => {
    expect(signInPayload('SGFuZGxl')).toBe('veyqsignin:SGFuZGxl')
  })

  it('is never URL-shaped: no scheme separator, no host, no path — over 200 minted handles', () => {
    for (let i = 0; i < 200; i++) {
      const payload = signInPayload(mintHandle())
      expect(payload).not.toContain('://')
      expect(payload.slice('veyqsignin:'.length)).not.toMatch(/^\//)
      expect(payload).not.toMatch(/https?:/)
      expect(payload).not.toMatch(/\./) // no host-shaped token — base64 has no dot
    }
  })

  // The same lever as the `veyqpair:` case, re-run under this marker: the re-mint loop lives in `mintHandle`,
  // so it carries over ONLY as long as this direction mints its handle there and nowhere else. 200 random
  // draws cannot show it — a leading '/' happens once in 64.
  it('re-mints a handle whose encoding would make the payload read `veyqsignin://`', () => {
    const leadingSlash = new Uint8Array(32).fill(0xff)
    const clean = new Uint8Array(32).fill(0x00)
    const draws = vi
      .spyOn(v1, 'randomBytes')
      .mockImplementationOnce(() => leadingSlash)
      .mockImplementationOnce(() => clean)

    expect(b64std(leadingSlash)).toMatch(/^\//) // the lever is wired: this draw WOULD produce `veyqsignin://`
    const handle = mintHandle()

    expect(draws).toHaveBeenCalledTimes(2)
    expect(handle).toBe(b64std(clean))
    expect(signInPayload(handle)).not.toContain('://')
  })

  it('refuses a payload that is not exactly the marker and a handle', () => {
    expect(parseSignInPayload('veyqkit:eyJ2IjoxfQ')).toBeNull() // the Emergency Kit's own payload
    expect(parseSignInPayload('veyqsignin:SGFuZGxl?to=https://evil.example')).toBeNull()
    expect(parseSignInPayload('https://evil.example/veyqsignin:SGFuZGxl')).toBeNull()
    expect(parseSignInPayload('veyqsignin:')).toBeNull()
    expect(parseSignInPayload('just some text')).toBeNull()
  })

  // D1's reason for a second marker at all. A sign-in code read as a pairing one would mount the join card and
  // burn the write-once answer slot before anyone saw a wrong screen — so the two must not be interchangeable,
  // in EITHER direction, and each parser's own payload is the live control that it still works.
  it('is not interchangeable with the add-a-device payload, in either direction', () => {
    const handle = mintHandle()

    expect(parsePairingPayload(signInPayload(handle))).toBeNull()
    expect(parseSignInPayload(pairingPayload(handle))).toBeNull()

    expect(parsePairingPayload(pairingPayload(handle))).toBe(handle)
    expect(parseSignInPayload(signInPayload(handle))).toBe(handle)
  })
})

describe('the pairing code (ADR 0059 §5, D5)', () => {
  it('is six digits', () => {
    for (let i = 0; i < 50; i++) expect(mintPairingCode()).toMatch(/^\d{6}$/)
  })

  // The bite for the rejection ceiling. Without the `continue`, 0xff would be taken as 255 % 10 = 5 and the
  // code would read '512345' — the modulo bias, made observable. A shape assertion cannot see it: both are
  // six digits.
  it('rejects a draw past the uniform ceiling instead of folding it — the modulo bias is not taken', () => {
    const biased = new Uint8Array([0xff, 1, 2, 3, 4, 5])
    const rest = new Uint8Array([7, 7, 7, 7, 7, 7])
    const draws = vi
      .spyOn(v1, 'randomBytes')
      .mockImplementationOnce(() => biased)
      .mockImplementationOnce(() => rest)

    expect(biased[0] % 10).toBe(5) // the lever is wired: folded, this byte WOULD lead the code
    expect(mintPairingCode()).toBe('123457')
    expect(draws).toHaveBeenCalledTimes(2) // it drew again rather than short-changing the code
  })

  // §5, stated as a test: anything derivable from the scanned QR is already the attacker's, so the code may
  // not be a function of the key. Two codes for one key must differ.
  it('is drawn fresh, never derived from the transport key', () => {
    const codes = new Set(Array.from({ length: 20 }, () => mintPairingCode()))

    expect(codes.size).toBeGreaterThan(1)
  })
})

describe('the code commitment and the sign-in answer (ADR 0059 §6, D2/D3)', () => {
  it('verifies for the key and the code it was made from — the control every negative below needs', () => {
    const transport = mintTransportKey()
    const code = mintPairingCode()

    expect(codeProofHolds(transport.pub, code, codeCommitment(transport.pub, code))).toBe(true)
  })

  it('does not verify over a different key, while the matching key still does', () => {
    const intended = mintTransportKey()
    const other = mintTransportKey()
    const code = '481502'
    const commitment = codeCommitment(intended.pub, code)

    expect(codeProofHolds(other.pub, code, commitment)).toBe(false)
    expect(codeProofHolds(intended.pub, code, commitment)).toBe(true)
  })

  it('does not verify over a different code, while the matching code still does', () => {
    const transport = mintTransportKey()
    const commitment = codeCommitment(transport.pub, '481502')

    expect(codeProofHolds(transport.pub, '481503', commitment)).toBe(false)
    expect(codeProofHolds(transport.pub, '481502', commitment)).toBe(true)
  })

  // A commitment that is not exactly the digest, in BOTH directions of wrong length. The long case is the one
  // the explicit length check in `codeProofHolds` exists for — the short cases already fail on the comparison
  // itself, which is why deleting that check leaves them green and only this line red.
  it('does not verify a commitment of the wrong length, in either direction', () => {
    const transport = mintTransportKey()
    const commitment = codeCommitment(transport.pub, '481502')
    const padded = new Uint8Array(commitment.length + 1)
    padded.set(commitment)

    expect(codeProofHolds(transport.pub, '481502', new Uint8Array(0))).toBe(false)
    expect(codeProofHolds(transport.pub, '481502', commitment.slice(0, 16))).toBe(false)
    expect(codeProofHolds(transport.pub, '481502', padded)).toBe(false) // the digest, plus one byte
    expect(codeProofHolds(transport.pub, '481502', commitment)).toBe(true)
  })

  it('round-trips key and commitment through the one relayed answer, and the grant seals to the key read back', () => {
    const transport = mintTransportKey()
    const code = mintPairingCode()

    const { pub, commitment } = readSignInAnswer(signInAnswer(transport.pub, code))

    expect(codeProofHolds(pub, code, commitment)).toBe(true)
    // The key the trusted device seals to is the one that came out of the answer, not a second copy.
    expect(openGrant(transport.priv, sealGrant(pub, grant())).email).toBe('someone@example.org')
  })

  // D2's whole reason for a separate reader: `deserializeIdentityPublic` asserts nothing about the end of its
  // buffer, so without this the two directions' answers would parse as each other's, silently.
  it('rejects a buffer with trailing bytes, while the exact one still reads', () => {
    const transport = mintTransportKey()
    const answer = signInAnswer(transport.pub, '481502')
    const withTail = b64std(v1.concat(fromB64std(answer), new Uint8Array([0x00])))

    expect(() => readSignInAnswer(withTail)).toThrow()
    expect(readSignInAnswer(answer).commitment.length).toBe(32)
  })

  // Anchored on THIS reader's own refusal, not on "it threw": an add-a-device answer's three length-prefixed
  // fields make the second read overshoot, and a reader without the end-of-buffer assertion still dies — as a
  // RangeError out of `deserializeIdentityPublic`, several layers down and for an unrelated reason. Naming the
  // message is what makes this test about the assertion under it.
  it('refuses an add-a-device answer as unreadable, naming its own refusal', () => {
    const transport = mintTransportKey()

    expect(() => readSignInAnswer(transportAnswer(transport.pub))).toThrow('sign_in_answer_unreadable')
    expect(() => readSignInAnswer(signInAnswer(transport.pub, '481502'))).not.toThrow()
  })

  it('stays inside the contract\'s 8192-char ceiling', () => {
    expect(signInAnswer(mintTransportKey().pub, '481502').length).toBeLessThan(8192)
  })
})
