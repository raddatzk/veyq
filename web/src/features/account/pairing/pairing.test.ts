// uc.account.onboard-device (web, ADR 0059) — the client-side properties the pairing ceremony rests on and that
// no `scripts/` validator can see, because seeing them means parsing TypeScript:
//
//   1. the displayed code names no server (§4) — the payload is the marker and the secret, and nothing else,
//      under EITHER direction's marker, and neither marker ever parses as the other's;
//   2. the grant survives exactly one round trip through the ephemeral transport key, and nothing else opens it;
//   3. the check number is a function of the answering key alone (§5) — same key, same digits; different key,
//      different digits;
//   4. both relayed values are authenticated by the channel key only the QR's secret derives (§11): an answer or
//      an envelope made without it is refused, by name, and the transcribed code is bound into the sign-in answer.
//
// Every negative here carries the positive control on the same predicate, because "it returned null" and "it
// threw" are produced just as well by a module that does nothing at all — and every refusal is anchored on its
// own reason, not on "it threw".
import { describe, it, expect, vi, afterEach } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import {
  mintPairingSecret,
  channelKeys,
  pairingPayload,
  parsePairingPayload,
  signInPayload,
  parseSignInPayload,
  mintPairingCode,
  signInAnswer,
  readSignInAnswer,
  mintTransportKey,
  transportAnswer,
  readTransportAnswer,
  checkNumber,
  sealGrant,
  openGrant,
  PairingRefused,
  type PairingRefusal,
} from './pairing'

const grant = () => ({
  email: 'someone@example.org',
  masterKey: v1.randomBytes(32),
  refreshToken: 'cmVmcmVzaC10b2tlbi1vcGFxdWU',
  label: 'Küchen-Tablet',
})

/** Anchor a refusal on its NAME: a bare `toThrow()` is satisfied by a RangeError three layers down. */
const refusal = (fn: () => unknown): PairingRefusal | 'none' | 'other' => {
  try {
    fn()
    return 'none'
  } catch (e) {
    return e instanceof PairingRefused ? e.reason : 'other'
  }
}

const aChannel = () => channelKeys(mintPairingSecret())

afterEach(() => vi.restoreAllMocks())

describe('the pairing payload (ADR 0059 §4, §11)', () => {
  it('round-trips a real secret unchanged — the control every negative below needs', () => {
    const secret = mintPairingSecret()
    expect(parsePairingPayload(pairingPayload(secret))).toBe(secret)
  })

  it('carries the marker and the 32-byte secret and nothing else', () => {
    const secret = b64std(new Uint8Array(32).fill(7))
    expect(pairingPayload(secret)).toBe(`veyqpair:${secret}`)
    expect(fromB64std(parsePairingPayload(pairingPayload(secret))!)).toHaveLength(32)
  })

  it('is never URL-shaped: no scheme separator, no host, no path — over 200 minted secrets', () => {
    for (let i = 0; i < 200; i++) {
      const payload = pairingPayload(mintPairingSecret())
      expect(payload).not.toContain('://')
      expect(payload.slice('veyqpair:'.length)).not.toMatch(/^\//)
      expect(payload).not.toMatch(/https?:/)
      expect(payload).not.toMatch(/\./) // no host-shaped token — base64 has no dot
    }
  })

  // The bite for the loop in `mintPairingSecret`. 200 random secrets cannot show it: a leading '/' happens once
  // in 64, so the sweep above passes ~4% of the time with the guard deleted. Forcing the first draw is what makes
  // the property observable — a first byte of 0xFF encodes to '/' as base64's 63rd symbol.
  it('re-mints a secret whose encoding would make the payload read `veyqpair://`', () => {
    const leadingSlash = new Uint8Array(32).fill(0xff)
    const clean = new Uint8Array(32).fill(0x00)
    const draws = vi
      .spyOn(v1, 'randomBytes')
      .mockImplementationOnce(() => leadingSlash)
      .mockImplementationOnce(() => clean)

    expect(b64std(leadingSlash)).toMatch(/^\//) // the lever is wired: this draw WOULD produce `veyqpair://`
    const secret = mintPairingSecret()

    expect(draws).toHaveBeenCalledTimes(2)
    expect(secret).toBe(b64std(clean))
    expect(pairingPayload(secret)).not.toContain('://')
  })

  it('refuses a payload that is not exactly the marker and a 32-byte secret', () => {
    const secret = mintPairingSecret()
    expect(parsePairingPayload('veyqkit:eyJ2IjoxfQ')).toBeNull() // the Emergency Kit's own payload
    expect(parsePairingPayload(`veyqpair:${secret}?to=https://evil.example`)).toBeNull()
    expect(parsePairingPayload(`https://evil.example/veyqpair:${secret}`)).toBeNull()
    expect(parsePairingPayload('veyqpair:')).toBeNull()
    expect(parsePairingPayload('veyqpair:SGFuZGxl')).toBeNull() // six bytes: not a secret this client minted
    expect(parsePairingPayload('just some text')).toBeNull()
    expect(parsePairingPayload(`veyqpair:${secret}`)).toBe(secret)
  })
})

describe('the channel the secret derives (ADR 0059 §11)', () => {
  it('both devices derive the same handle and key from the same secret', () => {
    const secret = mintPairingSecret()
    expect(channelKeys(secret)).toEqual(channelKeys(parsePairingPayload(pairingPayload(secret))!))
  })

  it('the handle is the vector-pinned derivation, and it is not the key the server must never see', () => {
    const secret = mintPairingSecret()
    const { handle, key } = channelKeys(secret)
    expect(handle).toBe(b64std(v1.pairingHandle(fromB64std(secret))))
    expect(key).toEqual(v1.pairingChannelKey(fromB64std(secret)))
    expect(fromB64std(handle)).not.toEqual(key)
    expect(handle).not.toBe(secret)
  })
})

describe('the sealed grant (ADR 0059 §3, §11)', () => {
  it('round-trips every field through the transport key and the channel key', () => {
    const transport = mintTransportKey()
    const { key } = aChannel()
    const original = grant()

    const opened = openGrant(key, transport.priv, sealGrant(key, transport.pub, original))

    expect(opened.email).toBe(original.email)
    expect(opened.masterKey).toEqual(original.masterKey)
    expect(opened.refreshToken).toBe(original.refreshToken)
    expect(opened.label).toBe(original.label)
  })

  // An envelope is sealed to ONE key. Anchored on the failed open of a real envelope by a real second key.
  it('does not open with a different transport key, while the intended one still does', () => {
    const intended = mintTransportKey()
    const other = mintTransportKey()
    const { key } = aChannel()
    const envelope = sealGrant(key, intended.pub, grant())

    expect(() => openGrant(key, other.priv, envelope)).toThrow(v1.OpenRefused)
    expect(openGrant(key, intended.priv, envelope).label).toBe('Küchen-Tablet')
  })

  // §11's envelope half: anyone can seal to the new device's public key — the relay read it off the answer. The
  // trusted device's own envelope is the control.
  it('refuses an envelope sealed to the right key without the channel key — the relay\'s own grant', () => {
    const transport = mintTransportKey()
    const { key } = aChannel()
    const relayGrant = sealGrant(aChannel().key, transport.pub, grant()) // a valid seal, MACed under a key it guessed

    expect(refusal(() => openGrant(key, transport.priv, relayGrant))).toBe('envelope_refused')
    expect(refusal(() => openGrant(key, transport.priv, sealGrant(key, transport.pub, grant())))).toBe('none')
  })

  it('refuses a bare seal with no MAC at all as unreadable, by name', () => {
    const transport = mintTransportKey()
    const { key } = aChannel()
    const bare = b64std(v1.sealTo({ purpose: 'pairing-grant' }, { x: transport.pub.kemX, m: transport.pub.kemM }, v1.utf8('{}')))

    expect(refusal(() => openGrant(key, transport.priv, bare))).toBe('envelope_unreadable')
  })

  it('travels as the answering key does — the trusted device reads the answer off the wire and seals to it', () => {
    const transport = mintTransportKey()
    const { key } = aChannel()
    const asRead = readTransportAnswer(key, transportAnswer(key, transport.pub))

    expect(openGrant(key, transport.priv, sealGrant(key, asRead, grant())).email).toBe('someone@example.org')
  })

  // ADR 0028 Decision 7: the linking device's trust-state marks ride inside the same authenticated envelope.
  it('carries the linking device\u2019s trust-state marks, exactly', () => {
    const transport = mintTransportKey()
    const { key } = aChannel()
    const marks = { pins: 5, register: 3, epochs: { 'scope-a': 2 } }
    expect(openGrant(key, transport.priv, sealGrant(key, transport.pub, { ...grant(), marks })).marks).toEqual(marks)
    // control: an envelope sealed without them (an older linker) opens with none — nothing to inherit
    expect(openGrant(key, transport.priv, sealGrant(key, transport.pub, grant()))).not.toHaveProperty('marks')
  })

  it('refuses marks in a shape this client does not write, rather than half-reading them', () => {
    const transport = mintTransportKey()
    const { key } = aChannel()
    const bad = { pins: -1, register: 0, epochs: {} } as unknown as { pins: number; register: number; epochs: Record<string, number> }
    expect(() => openGrant(key, transport.priv, sealGrant(key, transport.pub, { ...grant(), marks: bad }))).toThrow('pairing_grant_unreadable')
    const badEpoch = { pins: 1, register: 0, epochs: { s: 'x' } } as unknown as { pins: number; register: number; epochs: Record<string, number> }
    expect(() => openGrant(key, transport.priv, sealGrant(key, transport.pub, { ...grant(), marks: badEpoch }))).toThrow('pairing_grant_unreadable')
  })

  it('stays inside the contract\'s 8192-char ceiling for a realistic grant', () => {
    const transport = mintTransportKey()
    const { key } = aChannel()
    const envelope = sealGrant(key, transport.pub, { ...grant(), label: 'x'.repeat(200) })

    expect(envelope.length).toBeLessThan(8192)
  })

  // Only a ROTATED scope has an epoch mark (an epoch-0 floor is never stored), so this is the bound that matters:
  // a hundred scopes that each lost a member, plus the longest label, still fit.
  it('stays inside the ceiling with the marks of a hundred rotated scopes', () => {
    const transport = mintTransportKey()
    const { key } = aChannel()
    const epochs = Object.fromEntries(Array.from({ length: 100 }, () => [crypto.randomUUID(), 12345]))
    const envelope = sealGrant(key, transport.pub, { ...grant(), label: 'x'.repeat(200), marks: { pins: 99999, register: 99999, epochs } })

    expect(envelope.length).toBeLessThan(8192)
  })
})

describe('the add-a-device answer (ADR 0059 §5, §11)', () => {
  it('reads back the key it carries under the channel key — the control every negative below needs', () => {
    const transport = mintTransportKey()
    const { key } = aChannel()

    expect(readTransportAnswer(key, transportAnswer(key, transport.pub))).toEqual(transport.pub)
  })

  // The relay's position: it knows the handle and the answer, never the channel key. Any key it could MAC under
  // is some other key, and swapping the bundle while keeping the honest MAC breaks the MAC.
  it('is refused when the MAC is under another key, or the key is swapped under the honest MAC', () => {
    const honest = mintTransportKey()
    const relay = mintTransportKey()
    const { handle, key } = aChannel()
    const honestAnswer = transportAnswer(key, honest.pub)
    const honestMac = fromB64std(honestAnswer).slice(-32)
    const swapped = b64std(v1.concat(v1.lp(v1.serializeIdentityPublic(relay.pub)), v1.lp(honestMac)))

    expect(refusal(() => readTransportAnswer(key, transportAnswer(fromB64std(handle), relay.pub)))).toBe('answer_refused')
    expect(refusal(() => readTransportAnswer(key, swapped))).toBe('answer_refused')
    expect(refusal(() => readTransportAnswer(key, honestAnswer))).toBe('none')
  })

  it('refuses the bare key it used to be, and trailing bytes, as unreadable', () => {
    const transport = mintTransportKey()
    const { key } = aChannel()
    const answer = transportAnswer(key, transport.pub)

    expect(refusal(() => readTransportAnswer(key, b64std(v1.serializeIdentityPublic(transport.pub))))).toBe('answer_unreadable')
    expect(refusal(() => readTransportAnswer(key, b64std(v1.concat(fromB64std(answer), Uint8Array.of(0)))))).toBe('answer_unreadable')
    expect(refusal(() => readTransportAnswer(key, answer))).toBe('none')
  })
})

describe('the check number (ADR 0059 §5, D2)', () => {
  it('is five digits, and both sides derive the same one from the same answering key', () => {
    const transport = mintTransportKey()
    const { key } = aChannel()
    // The new device derives it from the bundle it minted; the trusted device from the bundle it read off the
    // wire. Same number, or the compare means nothing.
    const onTheNewDevice = checkNumber(transport.pub)
    const onTheTrustedDevice = checkNumber(readTransportAnswer(key, transportAnswer(key, transport.pub)))

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
  it('round-trips a real secret unchanged — the control every negative below needs', () => {
    const secret = mintPairingSecret()
    expect(parseSignInPayload(signInPayload(secret))).toBe(secret)
  })

  it('carries the second marker and the secret and nothing else — never the transport key', () => {
    const secret = b64std(new Uint8Array(32).fill(7))
    expect(signInPayload(secret)).toBe(`veyqsignin:${secret}`)
  })

  it('is never URL-shaped: no scheme separator, no host, no path — over 200 minted secrets', () => {
    for (let i = 0; i < 200; i++) {
      const payload = signInPayload(mintPairingSecret())
      expect(payload).not.toContain('://')
      expect(payload.slice('veyqsignin:'.length)).not.toMatch(/^\//)
      expect(payload).not.toMatch(/https?:/)
      expect(payload).not.toMatch(/\./) // no host-shaped token — base64 has no dot
    }
  })

  // The same lever as the `veyqpair:` case, re-run under this marker: the re-mint loop lives in
  // `mintPairingSecret`, so it carries over ONLY as long as this direction mints its secret there.
  it('re-mints a secret whose encoding would make the payload read `veyqsignin://`', () => {
    const leadingSlash = new Uint8Array(32).fill(0xff)
    const clean = new Uint8Array(32).fill(0x00)
    const draws = vi
      .spyOn(v1, 'randomBytes')
      .mockImplementationOnce(() => leadingSlash)
      .mockImplementationOnce(() => clean)

    expect(b64std(leadingSlash)).toMatch(/^\//) // the lever is wired: this draw WOULD produce `veyqsignin://`
    const secret = mintPairingSecret()

    expect(draws).toHaveBeenCalledTimes(2)
    expect(secret).toBe(b64std(clean))
    expect(signInPayload(secret)).not.toContain('://')
  })

  it('refuses a payload that is not exactly the marker and a 32-byte secret', () => {
    const secret = mintPairingSecret()
    expect(parseSignInPayload('veyqkit:eyJ2IjoxfQ')).toBeNull() // the Emergency Kit's own payload
    expect(parseSignInPayload(`veyqsignin:${secret}?to=https://evil.example`)).toBeNull()
    expect(parseSignInPayload(`https://evil.example/veyqsignin:${secret}`)).toBeNull()
    expect(parseSignInPayload('veyqsignin:')).toBeNull()
    expect(parseSignInPayload('veyqsignin:SGFuZGxl')).toBeNull()
    expect(parseSignInPayload('just some text')).toBeNull()
    expect(parseSignInPayload(`veyqsignin:${secret}`)).toBe(secret)
  })

  // D1's reason for a second marker at all. A sign-in code read as a pairing one would mount the join card and
  // burn the write-once answer slot before anyone saw a wrong screen — so the two must not be interchangeable,
  // in EITHER direction, and each parser's own payload is the live control that it still works.
  it('is not interchangeable with the add-a-device payload, in either direction', () => {
    const secret = mintPairingSecret()

    expect(parsePairingPayload(signInPayload(secret))).toBeNull()
    expect(parseSignInPayload(pairingPayload(secret))).toBeNull()

    expect(parsePairingPayload(pairingPayload(secret))).toBe(secret)
    expect(parseSignInPayload(signInPayload(secret))).toBe(secret)
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

describe('the sign-in answer (ADR 0059 §6, §11)', () => {
  it('reads back the key for the channel key and the code it was made with — the control every negative needs', () => {
    const transport = mintTransportKey()
    const { key } = aChannel()
    const code = mintPairingCode()

    expect(readSignInAnswer(key, code, signInAnswer(key, transport.pub, code))).toEqual(transport.pub)
  })

  it('is refused for a different code, while the displayed one still reads', () => {
    const transport = mintTransportKey()
    const { key } = aChannel()
    const answer = signInAnswer(key, transport.pub, '481502')

    expect(refusal(() => readSignInAnswer(key, '481503', answer))).toBe('answer_refused')
    expect(refusal(() => readSignInAnswer(key, '481502', answer))).toBe('none')
  })

  // What the relay would need, and does not have: even KNOWING the code, it cannot prove it for its own key
  // without the channel key — the proof is no longer a function of (key, code) anybody can compute.
  it('is refused for a relay that knows the code but not the channel key', () => {
    const relay = mintTransportKey()
    const { handle, key } = aChannel()

    expect(refusal(() => readSignInAnswer(key, '481502', signInAnswer(fromB64std(handle), relay.pub, '481502')))).toBe(
      'answer_refused',
    )
    expect(refusal(() => readSignInAnswer(key, '481502', signInAnswer(key, relay.pub, '481502')))).toBe('none')
  })

  // The two labels: the two directions' answers now have ONE shape, so what keeps them from passing for each
  // other is the MAC's label — refused by name, in both directions, with each reader's own answer as control.
  it('is not interchangeable with an add-a-device answer, in either direction', () => {
    const transport = mintTransportKey()
    const { key } = aChannel()
    const addAnswer = transportAnswer(key, transport.pub)
    const signIn = signInAnswer(key, transport.pub, '481502')

    expect(refusal(() => readSignInAnswer(key, '481502', addAnswer))).toBe('answer_refused')
    expect(refusal(() => readTransportAnswer(key, signIn))).toBe('answer_refused')
    expect(refusal(() => readTransportAnswer(key, addAnswer))).toBe('none')
    expect(refusal(() => readSignInAnswer(key, '481502', signIn))).toBe('none')
  })

  it('rejects a buffer with trailing bytes as unreadable, while the exact one still reads', () => {
    const transport = mintTransportKey()
    const { key } = aChannel()
    const answer = signInAnswer(key, transport.pub, '481502')
    const withTail = b64std(v1.concat(fromB64std(answer), new Uint8Array([0x00])))

    expect(refusal(() => readSignInAnswer(key, '481502', withTail))).toBe('answer_unreadable')
    expect(refusal(() => readSignInAnswer(key, '481502', answer))).toBe('none')
  })

  it('both answers have one length, and stay inside the contract\'s 8192-char ceiling', () => {
    const transport = mintTransportKey()
    const { key } = aChannel()
    const signIn = signInAnswer(key, transport.pub, '481502')

    expect(signIn.length).toBe(transportAnswer(key, transport.pub).length)
    expect(signIn.length).toBeLessThan(8192)
  })
})
