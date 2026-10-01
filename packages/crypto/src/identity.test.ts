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

  it('a bundle cut inside a length prefix is refused', () => {
    const id = v1.generateIdentity()
    const pub = v1.serializeIdentityPublic(id.pub)
    const priv = v1.serializeIdentityPrivate(id)
    expect(() => v1.deserializeIdentityPublic(pub.slice(0, 4 + 32 + 2))).toThrow()
    expect(() => v1.deserializeIdentityPrivate(priv.slice(0, 4 + 32 + 2))).toThrow()
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
