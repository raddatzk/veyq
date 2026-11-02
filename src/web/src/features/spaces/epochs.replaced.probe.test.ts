// ADR 0115's probe on the web, now its rule: a scope has one key at a time, so a device holding epoch 2 holds that key
// and no other — nothing it is served opens an older one. Before ADR 0115 `deriveEpochKeys` peeled the chain links down
// to E_0, and this probe was red against it (quoted in the phase report); the peel and the links are gone.
import { describe, it, expect } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { copyScopeKey, scopeKeyOf, zeroScopeKey } from './epochs'
import { deriveGroupNameKey, openGroupNameWith, sealGroupName } from './inviteName'

const SCOPE = '5c09e000-0000-4000-8000-0000000000e1'

describe('ADR 0115: a held scope key opens no earlier one', () => {
  it('a device holding epoch 2 holds exactly the epoch-2 key', () => {
    const e2 = v1.randomBytes(32)
    const sk = scopeKeyOf(e2, 2)
    expect(sk.key).toEqual(e2) // control: the granted key is held
    expect(sk.epoch).toBe(2)
    expect(Object.keys(sk).sort()).toEqual(['epoch', 'key']) // nothing beside it: no per-epoch set to look an old key up in
  })

  it('what was sealed under an older key does not open under the held one; re-sealed under it, it does', () => {
    const e1 = v1.randomBytes(32)
    const e2 = v1.randomBytes(32)
    const held = scopeKeyOf(e2, 2)
    const under1 = sealGroupName(deriveGroupNameKey(e1), SCOPE, 1, 'Familie')
    // under the held key at either epoch label: the old key's ciphertext is shut
    expect(() => openGroupNameWith(deriveGroupNameKey(held.key), SCOPE, 1, under1)).toThrow(v1.OpenRefused)
    expect(() => openGroupNameWith(deriveGroupNameKey(held.key), SCOPE, 2, under1)).toThrow(v1.OpenRefused)
    // control: the rotation's re-seal of the same name under the new key at its epoch opens
    const under2 = sealGroupName(deriveGroupNameKey(e2), SCOPE, 2, 'Familie')
    expect(openGroupNameWith(deriveGroupNameKey(held.key), SCOPE, 2, under2)).toBe('Familie')
  })

  it('the succession statement that proves epoch 2 carries neither key', () => {
    const e1 = v1.randomBytes(32)
    const e2 = v1.randomBytes(32)
    const successionPublicKey = v1.successionKeyPair(e2).publicKey
    const signature = v1.signSuccession(e1, { scopeId: SCOPE, epoch: 2, successionPublicKey })
    const served = Buffer.concat([Buffer.from(successionPublicKey), Buffer.from(signature)])
    for (const key of [e1, e2]) expect(served.includes(Buffer.from(key))).toBe(false)
    // control: it proves epoch 2 from epoch 1's key — and only from it
    expect(v1.verifySuccession(v1.successionKeyPair(e1).publicKey, { scopeId: SCOPE, epoch: 2, successionPublicKey }, signature)).toBe(true)
    expect(v1.verifySuccession(v1.successionKeyPair(e2).publicKey, { scopeId: SCOPE, epoch: 2, successionPublicKey }, signature)).toBe(false)
  })

  it('a copy is the same key at the same epoch, zeroed apart from the original', () => {
    const sk = scopeKeyOf(v1.randomBytes(32), 3)
    const copy = copyScopeKey(sk)
    expect(copy).toEqual(sk)
    expect(copy.key).not.toBe(sk.key)
    zeroScopeKey(copy)
    expect(copy.key.every((b) => b === 0)).toBe(true)
    expect(sk.key.some((b) => b !== 0)).toBe(true) // control: the original is untouched
  })
})
