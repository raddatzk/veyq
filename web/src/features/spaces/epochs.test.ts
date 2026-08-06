import { describe, it, expect } from 'vitest'
import * as v1 from '../../shared/crypto/v1'
import { b64std } from '../../shared/base64'
import { deriveEpochKeys, keyAt, writeKey, EPOCH_LINK, type EpochLink } from './epochs'

// Forge a chain-link exactly as the rotating owner would (ADR 0024): wrappedPrev(e) = symWrap(E_e, E_{e-1}).
function link(epoch: number, keyE: Uint8Array, keyPrev: Uint8Array): EpochLink {
  return { epoch, wrappedPrev: b64std(v1.symWrap(keyE, keyPrev, EPOCH_LINK, v1.randomBytes(12))) }
}

describe('deriveEpochKeys — the epoch chain peel (cr-0089)', () => {
  it('a member holding the latest epoch peels the whole chain down to E_0', () => {
    const e0 = v1.randomBytes(32)
    const e1 = v1.randomBytes(32)
    const e2 = v1.randomBytes(32)
    const links = [link(1, e1, e0), link(2, e2, e1)]

    // The caller holds E_2 (their grant), granted at epoch 2 → they should derive E_1 and E_0 too.
    const ek = deriveEpochKeys(e2, 2, links)

    expect(ek.current).toBe(2)
    expect(writeKey(ek)).toEqual(e2)
    expect(keyAt(ek, 2)).toEqual(e2)
    expect(keyAt(ek, 1)).toEqual(e1)
    expect(keyAt(ek, 0)).toEqual(e0)
  })

  it('an unrotated scope (grantedEpoch 0, no links) is just { 0: grantKey }', () => {
    const e0 = v1.randomBytes(32)
    const ek = deriveEpochKeys(e0, 0, [])

    expect(ek.current).toBe(0)
    expect(ek.keys.size).toBe(1)
    expect(keyAt(ek, 0)).toEqual(e0)
    // An epoch the caller doesn't hold falls back to the write key (never happens for a current member).
    expect(keyAt(ek, 1)).toEqual(e0)
  })

  it('holding only an older epoch derives no newer ones (the chain is one-directional)', () => {
    const e0 = v1.randomBytes(32)
    const e1 = v1.randomBytes(32)
    const e2 = v1.randomBytes(32)
    const links = [link(1, e1, e0), link(2, e2, e1)]

    // A caller granted at epoch 1 can peel down to E_0 but cannot climb up to E_2.
    const ek = deriveEpochKeys(e1, 1, links)

    expect(ek.current).toBe(1)
    expect(keyAt(ek, 1)).toEqual(e1)
    expect(keyAt(ek, 0)).toEqual(e0)
    expect(ek.keys.has(2)).toBe(false)
  })

  it('stops cleanly at the first missing link instead of throwing', () => {
    const e2 = v1.randomBytes(32)
    // Granted at epoch 2 but the link for epoch 2→1 is absent → peel stops, only E_2 is held.
    const ek = deriveEpochKeys(e2, 2, [])

    expect(ek.current).toBe(2)
    expect(ek.keys.size).toBe(1)
    expect(keyAt(ek, 2)).toEqual(e2)
  })
})
