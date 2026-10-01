// The identity codec and the hybrid halves re-derived from a stored private bundle. `web/` and `admin-web/` call
// all of them, but the mutation gate runs this package's own suite, which no `web/` test can reach — so these are
// pinned here, against `generateIdentity()`, or the gate cannot see them at all.
import { describe, expect, it } from 'vitest'
import * as v1 from './v1'

const grant = { purpose: 'scope-grant', scopeId: 'c0ffee00-0000-4000-8000-00000000000a', epoch: 3 } as const

describe('identity codec', () => {
  it('the public bundle round-trips through its serialization', () => {
    const id = v1.generateIdentity()
    expect(v1.deserializeIdentityPublic(v1.serializeIdentityPublic(id.pub))).toEqual(id.pub)
  })

  it('the private bundle round-trips through its serialization', () => {
    const id = v1.generateIdentity()
    expect(v1.deserializeIdentityPrivate(v1.serializeIdentityPrivate(id))).toEqual(id.priv)
  })

  // A served bundle arrives as a view into a larger buffer often enough (a base64 decode, a slice of a response);
  // reading the length prefixes from offset 0 of the BUFFER rather than of the view would read someone else's bytes.
  it('reads a bundle that sits at an offset inside a larger buffer', () => {
    const id = v1.generateIdentity()
    const pub = v1.serializeIdentityPublic(id.pub)
    const priv = v1.serializeIdentityPrivate(id)
    const pubView = v1.concat(new Uint8Array(7).fill(0xff), pub).subarray(7)
    const privView = v1.concat(new Uint8Array(7).fill(0xff), priv).subarray(7)
    expect(v1.deserializeIdentityPublic(pubView)).toEqual(id.pub)
    expect(v1.deserializeIdentityPrivate(privView)).toEqual(id.priv)
  })
})

// Both decoders share the drop-key codec's bounded reader, so each mangle is refused with its OWN message (a bare
// .toThrow() also passes on the DataView RangeError an unbounded reader throws — ADR 0045). The public bundle is
// served by the server for other members, so the overrun and wrong-length cases are what an operator could hand a
// client: both used to be read as a key.
describe('a malformed identity bundle is refused', () => {
  const kemMStart = 4 + 32
  const signStart = kemMStart + 4 + 1184
  const privSignStart = kemMStart + 4 + 64

  it.each([
    ['cut inside the first length prefix', 'truncated', (b: Uint8Array) => b.slice(0, 2)],
    ['cut inside the second length prefix', 'truncated', (b: Uint8Array) => b.slice(0, kemMStart + 2)],
    ['cut right after the second length prefix', 'malformed', (b: Uint8Array) => b.slice(0, kemMStart + 4)],
    ['short by its last byte', 'malformed', (b: Uint8Array) => b.slice(0, b.length - 1)],
    ['followed by trailing bytes', 'trailing bytes', (b: Uint8Array) => v1.concat(b, Uint8Array.of(0))],
    ['with a wrong first field length', 'malformed', (b: Uint8Array) => v1.concat(v1.lp(new Uint8Array(31)), b.slice(kemMStart))],
  ])('a bundle %s is refused as %s', (_name, verdict, mangle) => {
    const id = v1.generateIdentity()
    expect(() => v1.deserializeIdentityPublic(mangle(v1.serializeIdentityPublic(id.pub)))).toThrow(
      new RegExp(`^identity public key: ${verdict}$`),
    )
    expect(() => v1.deserializeIdentityPrivate(mangle(v1.serializeIdentityPrivate(id)))).toThrow(
      new RegExp(`^identity private key: ${verdict}$`),
    )
  })

  it('a last length prefix that overruns the bundle is refused, not clamped to the bytes that are there', () => {
    const id = v1.generateIdentity()
    const overrun = (b: Uint8Array, at: number) => v1.concat(b.slice(0, at), v1.lp(new Uint8Array(33)).slice(0, 4), b.slice(at + 4))
    expect(() => v1.deserializeIdentityPublic(overrun(v1.serializeIdentityPublic(id.pub), signStart))).toThrow(
      /^identity public key: malformed$/,
    )
    expect(() => v1.deserializeIdentityPrivate(overrun(v1.serializeIdentityPrivate(id), privSignStart))).toThrow(
      /^identity private key: malformed$/,
    )
  })

  it('a middle field of a self-consistent wrong length is refused', () => {
    const id = v1.generateIdentity()
    const pub = v1.serializeIdentityPublic(id.pub)
    const shortKemM = v1.concat(pub.slice(0, kemMStart), v1.lp(id.pub.kemM.slice(1)), pub.slice(signStart))
    expect(() => v1.deserializeIdentityPublic(shortKemM)).toThrow(/^identity public key: malformed$/)
    const priv = v1.serializeIdentityPrivate(id)
    const longSeed = v1.concat(priv.slice(0, kemMStart), v1.lp(v1.concat(id.priv.mlkemSeed, Uint8Array.of(0))), priv.slice(privSignStart))
    expect(() => v1.deserializeIdentityPrivate(longSeed)).toThrow(/^identity private key: malformed$/)
  })
})

describe('hybrid halves re-derived from the private bundle', () => {
  it('hybridPublic is the identity\'s own published KEM half', () => {
    const id = v1.generateIdentity()
    expect(v1.hybridPublic(id.priv)).toEqual({ x: id.pub.kemX, m: id.pub.kemM })
  })

  it('a seal to hybridPublic opens under hybridSecret, and under no other identity\'s', () => {
    const id = v1.generateIdentity()
    const other = v1.generateIdentity()
    const key = v1.randomBytes(32)
    const wrapped = v1.sealTo(grant, v1.hybridPublic(id.priv), key)
    expect(v1.openAs(grant, v1.hybridSecret(id.priv), v1.hybridPublic(id.priv), wrapped)).toEqual(key)
    expect(() => v1.openAs(grant, v1.hybridSecret(other.priv), v1.hybridPublic(other.priv), wrapped)).toThrow(
      v1.OpenRefused,
    )
  })

  it('hybridSecret carries the stored X25519 half as is', () => {
    const id = v1.generateIdentity()
    expect(v1.hybridSecret(id.priv).x).toEqual(id.priv.kemX)
  })
})
