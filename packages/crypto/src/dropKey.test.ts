// The drop keypair codec (ADR 0031 point 7). The seals it rides in are pinned per purpose by the conformance
// vectors; this pins what the vectors cannot: that a stored private half re-derives the public half a guest was
// given, and that a malformed serialization is refused rather than read as a shorter key.
import { describe, expect, it } from 'vitest'
import * as v1 from './v1'

const ctx = {
  purpose: 'upload-identity-key',
  scopeId: 'c0ffee00-0000-4000-8000-00000000000a',
  nodeId: 'c0ffee00-0000-4000-8000-00000000000b',
  identityId: 'c0ffee00-0000-4000-8000-000000000010',
} as const

describe('drop key', () => {
  it('a guest seal to the served public half opens under the stored private half, round-tripped through both codecs', () => {
    const drop = v1.generateDropKey()
    const stored = v1.deserializeDropKey(v1.serializeDropKey(drop))
    const served = v1.deserializeHybridPublic(v1.serializeHybridPublic(v1.dropKeyPublic(drop)))
    const kUp = v1.randomBytes(32)
    const wrapped = v1.sealTo(ctx, served, kUp)
    expect(v1.openAs(ctx, v1.dropKeySecret(stored), v1.dropKeyPublic(stored), wrapped)).toEqual(kUp)
  })

  it('another Memory\'s drop key does not open it', () => {
    const drop = v1.generateDropKey()
    const other = v1.generateDropKey()
    const wrapped = v1.sealTo(ctx, v1.dropKeyPublic(drop), v1.randomBytes(32))
    expect(() => v1.openAs(ctx, v1.dropKeySecret(other), v1.dropKeyPublic(other), wrapped)).toThrow(v1.OpenRefused)
  })

  it('the serializations are the fixed-size length-prefixed forms', () => {
    const drop = v1.generateDropKey()
    expect(v1.serializeDropKey(drop).length).toBe(4 + 32 + 4 + 64)
    expect(v1.serializeHybridPublic(v1.dropKeyPublic(drop)).length).toBe(4 + 32 + 4 + 1184)
  })

  // Each mangle is refused with its OWN message. A match on /drop key/ alone cannot tell a truncation from a
  // malformed field from trailing bytes, and that looseness let two of readFixedLp's bounds checks be deleted with
  // every case still green: the read past the end just fell through to the next check's message.
  it.each([
    ['cut inside the first length prefix', (b: Uint8Array) => b.slice(0, 2), 'truncated'],
    ['cut inside the second length prefix', (b: Uint8Array) => b.slice(0, 4 + 32 + 2), 'truncated'],
    ['cut right after the second length prefix', (b: Uint8Array) => b.slice(0, 4 + 32 + 4), 'malformed'],
    ['short by its last byte', (b: Uint8Array) => b.slice(0, b.length - 1), 'malformed'],
    ['followed by trailing bytes', (b: Uint8Array) => v1.concat(b, Uint8Array.of(0)), 'trailing bytes'],
    ['a wrong field length', (b: Uint8Array) => v1.concat(v1.lp(new Uint8Array(31)), b.slice(4 + 32)), 'malformed'],
  ])('a serialization %s is refused as %s', (_name, mangle, verdict) => {
    const drop = v1.generateDropKey()
    expect(() => v1.deserializeDropKey(mangle(v1.serializeDropKey(drop)))).toThrow(
      new RegExp(`^drop key: ${verdict}$`),
    )
    expect(() => v1.deserializeHybridPublic(mangle(v1.serializeHybridPublic(v1.dropKeyPublic(drop))))).toThrow(
      new RegExp(`^hybrid public key: ${verdict}$`),
    )
  })
})
