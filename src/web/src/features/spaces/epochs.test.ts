import { describe, it, expect } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import { deriveEpochKeys, keyAt, writeKey, epochLinkContext, type EpochLink } from './epochs'

const SCOPE = '5c09e000-0000-4000-8000-0000000000d1'
const OTHER_SCOPE = '5c09e000-0000-4000-8000-0000000000d2'

// Forge a chain-link exactly as the rotating owner would (ADR 0024): wrappedPrev(e) = E_{e-1} sealed under E_e,
// bound to its scope and to the epoch it is stored at (ADR 0072).
function link(epoch: number, keyE: Uint8Array, keyPrev: Uint8Array, scopeId = SCOPE): EpochLink {
  return { epoch, wrappedPrev: b64std(v1.seal(epochLinkContext(scopeId, epoch), keyE, keyPrev)) }
}

describe('deriveEpochKeys — the epoch chain peel (cr-0089)', () => {
  it('a member holding the latest epoch peels the whole chain down to E_0', () => {
    const e0 = v1.randomBytes(32)
    const e1 = v1.randomBytes(32)
    const e2 = v1.randomBytes(32)
    const links = [link(1, e1, e0), link(2, e2, e1)]

    // The caller holds E_2 (their grant), granted at epoch 2 → they should derive E_1 and E_0 too.
    const ek = deriveEpochKeys(SCOPE, e2, 2, links)

    expect(ek.current).toBe(2)
    expect(writeKey(ek)).toEqual(e2)
    expect(keyAt(ek, 2)).toEqual(e2)
    expect(keyAt(ek, 1)).toEqual(e1)
    expect(keyAt(ek, 0)).toEqual(e0)
  })

  it('an unrotated scope (grantedEpoch 0, no links) is just { 0: grantKey }', () => {
    const e0 = v1.randomBytes(32)
    const ek = deriveEpochKeys(SCOPE, e0, 0, [])

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
    const ek = deriveEpochKeys(SCOPE, e1, 1, links)

    expect(ek.current).toBe(1)
    expect(keyAt(ek, 1)).toEqual(e1)
    expect(keyAt(ek, 0)).toEqual(e0)
    expect(ek.keys.has(2)).toBe(false)
  })

  it('stops cleanly at the first missing link instead of throwing', () => {
    const e2 = v1.randomBytes(32)
    // Granted at epoch 2 but the link for epoch 2→1 is absent → peel stops, only E_2 is held.
    const ek = deriveEpochKeys(SCOPE, e2, 2, [])

    expect(ek.current).toBe(2)
    expect(ek.keys.size).toBe(1)
    expect(keyAt(ek, 2)).toEqual(e2)
  })

  it('a link served for another scope, or at another epoch, does not open (ADR 0072)', () => {
    const e0 = v1.randomBytes(32)
    const e1 = v1.randomBytes(32)
    // A link sealed for another scope's chain, served in this one — same keys, so only the binding refuses it.
    expect(() => deriveEpochKeys(SCOPE, e1, 1, [link(1, e1, e0, OTHER_SCOPE)])).toThrow(new v1.OpenRefused('scope-epoch'))
    // The epoch-2 link served as epoch 1's.
    const moved = { epoch: 1, wrappedPrev: link(2, e1, e0).wrappedPrev }
    expect(() => deriveEpochKeys(SCOPE, e1, 1, [moved])).toThrow(new v1.OpenRefused('scope-epoch'))
    // control: its own link peels
    expect(keyAt(deriveEpochKeys(SCOPE, e1, 1, [link(1, e1, e0)]), 0)).toEqual(e0)
  })
})
