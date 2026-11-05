// The scope-succession statement (ADR 0115 decision 5) against contracts/crypto/vectors/v1.json#scope_succession —
// written by an independent implementation — and the path a holder walks from the epoch it held to the one it is
// granted. Each refusal below sits beside a positive control on the same inputs, so a check that refuses everything
// cannot pass for one that refuses the right thing.
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createPrivateKey, createPublicKey, hkdfSync, sign as nodeSign, verify as nodeVerify } from 'node:crypto'
import * as v1 from './v1'
import { SEAL_PURPOSES, STATEMENT_PURPOSES } from './seal'

const unhex = (s: string) => Uint8Array.from(Buffer.from(s, 'hex'))
const hex = (u: Uint8Array) => Buffer.from(u).toString('hex')
const here = dirname(fileURLToPath(import.meta.url))
const vectors = JSON.parse(readFileSync(resolve(here, '../../../../contracts/crypto/vectors/v1.json'), 'utf8'))
const v = vectors.scope_succession

// node's own Ed25519, from the raw seed / public key through their fixed DER prefixes (RFC 8410).
const nodePrivate = (seed: Uint8Array) =>
  createPrivateKey({ key: Buffer.concat([Buffer.from('302e020100300506032b657004220420', 'hex'), seed]), format: 'der', type: 'pkcs8' })
const nodePublic = (pub: Uint8Array) =>
  createPublicKey({ key: Buffer.concat([Buffer.from('302a300506032b6570032100', 'hex'), pub]), format: 'der', type: 'spki' })
const flip = (b: Uint8Array, at = 0) => {
  const out = b.slice()
  out[at] ^= 1
  return out
}

describe('scope succession (#scope_succession, ADR 0115 decision 5)', () => {
  const input = (): v1.SuccessionInput => ({
    scopeId: v.scope_id,
    epoch: v.next_epoch,
    successionPublicKey: unhex(v.next_succession_public_key),
  })

  it('each epoch\'s key derives the vector\'s seed and public key', () => {
    for (const [key, seed, pub] of [
      [v.scope_key, v.succession_seed, v.succession_public_key],
      [v.next_scope_key, v.next_succession_seed, v.next_succession_public_key],
    ]) {
      const pair = v1.successionKeyPair(unhex(key))
      expect(hex(pair.privateKey)).toBe(seed)
      expect(hex(pair.publicKey)).toBe(pub)
      // The note's formula, recomputed by node: HKDF-SHA256 with an empty salt under the label, and Ed25519 from it.
      expect(hex(new Uint8Array(hkdfSync('sha256', unhex(key), new Uint8Array(0), 'veyq/v1/scope-succession', 32)))).toBe(seed)
      expect(hex(new Uint8Array(nodePublic(pair.publicKey).export({ format: 'der', type: 'spki' }).subarray(12)))).toBe(pub)
      expect(hex(new Uint8Array(createPublicKey(nodePrivate(unhex(seed))).export({ format: 'der', type: 'spki' }).subarray(12)))).toBe(pub)
    }
  })

  it('statement bytes and signature, in both directions', () => {
    expect(hex(v1.encodeStatement(v1.scopeSuccessionStatement(input())))).toBe(v.statement)
    expect(hex(v1.signSuccession(unhex(v.scope_key), input()))).toBe(v.signature)
    expect(v1.verifySuccession(unhex(v.succession_public_key), input(), unhex(v.signature))).toBe(true)
    // The vector's signature is node's Ed25519 over the vector's bytes, and verifies under the vector's key there too.
    expect(hex(new Uint8Array(nodeSign(null, unhex(v.statement), nodePrivate(unhex(v.succession_seed)))))).toBe(v.signature)
    expect(nodeVerify(null, unhex(v.statement), nodePublic(unhex(v.succession_public_key)), unhex(v.signature))).toBe(true)
  })

  it('the vector\'s one step is a path from the epoch before it', () => {
    const statements = [{ epoch: v.next_epoch, successionPublicKey: unhex(v.next_succession_public_key), successionSignature: unhex(v.signature) }]
    const path = (targetPublicKey: string) =>
      v1.verifySuccessionPath({
        scopeId: v.scope_id,
        heldEpoch: v.epoch,
        heldPublicKey: v1.successionKeyPair(unhex(v.scope_key)).publicKey,
        statements,
        targetEpoch: v.next_epoch,
        targetPublicKey: unhex(targetPublicKey),
      })
    expect(path(v.next_succession_public_key)).toBe(true)
    expect(path(v.succession_public_key)).toBe(false)
  })

  it('the statement is a label of its own, and the scope-epoch seal it replaced is gone', () => {
    const stmt = v1.encodeStatement(v1.scopeSuccessionStatement(input()))
    expect(new TextDecoder().decode(stmt.slice(4, 4 + 'scope-succession'.length))).toBe('scope-succession')
    expect(STATEMENT_PURPOSES['scope-succession'].map(([name]) => name)).toEqual(['scopeId', 'epoch', 'successionPublicKey'])
    expect(Object.keys(vectors.seal.sym)).not.toContain('scope-succession')
    expect(Object.keys(vectors.seal.asym)).not.toContain('scope-succession')
    // ADR 0115 decision 1: a scope key is replaced, never chained — no seal makes one key readable from another.
    expect(Object.keys(SEAL_PURPOSES)).not.toContain('scope-epoch')
    expect(Object.keys(vectors.seal.sym)).not.toContain('scope-epoch')
    expect(Object.keys(SEAL_PURPOSES)).toContain('group-name') // control: the table is the one read
  })

  it('a scope key that is not 32 bytes derives nothing', () => {
    for (const bad of [new Uint8Array(31), new Uint8Array(33), [...unhex(v.scope_key)] as unknown as Uint8Array]) {
      expect(() => v1.successionKeyPair(bad)).toThrow(new TypeError('scope key: not 32 bytes'))
      expect(() => v1.signSuccession(bad, input())).toThrow(new TypeError('scope key: not 32 bytes'))
    }
  })

  it('refuses to state epoch 0, a non-integer epoch, and a key that is not 32 bytes', () => {
    for (const epoch of [0, -1, 1.5, Number.NaN]) {
      expect(() => v1.signSuccession(unhex(v.scope_key), { ...input(), epoch })).toThrow(
        new v1.SealContextInvalid('scope-succession epoch is not 1 or more'),
      )
    }
    expect(v1.scopeSuccessionStatement({ ...input(), epoch: 1 }).epoch).toBe(1)
    const pub = unhex(v.next_succession_public_key)
    for (const successionPublicKey of [pub.slice(0, 31), new Uint8Array(33), [...pub] as unknown as Uint8Array]) {
      expect(() => v1.scopeSuccessionStatement({ ...input(), successionPublicKey })).toThrow(
        new v1.SealContextInvalid('scope-succession successionPublicKey is not 32 bytes'),
      )
    }
    expect(() => v1.scopeSuccessionStatement({ ...input(), scopeId: v.scope_id.toUpperCase() })).not.toThrow()
    expect(() => v1.encodeStatement(v1.scopeSuccessionStatement({ ...input(), scopeId: v.scope_id.toUpperCase() }))).toThrow(
      new v1.SealContextInvalid('scope-succession.scopeId is not a canonical lowercase UUID'),
    )
  })

  it('verifySuccession answers false, never a throw, for whatever else it is served', () => {
    const pub = unhex(v.succession_public_key)
    const sig = unhex(v.signature)
    expect(v1.verifySuccession(pub, input(), sig)).toBe(true)
    expect(v1.verifySuccession(pub, input(), flip(sig, 5))).toBe(false)
    expect(v1.verifySuccession(pub, input(), sig.slice(0, 63))).toBe(false)
    expect(v1.verifySuccession(pub.slice(0, 31), input(), sig)).toBe(false)
    expect(v1.verifySuccession(unhex(v.next_succession_public_key), input(), sig)).toBe(false)
    expect(v1.verifySuccession(pub, { ...input(), epoch: v.next_epoch + 1 }, sig)).toBe(false)
    expect(v1.verifySuccession(pub, { ...input(), epoch: 0 }, sig)).toBe(false)
    expect(v1.verifySuccession(pub, { ...input(), scopeId: 'c0ffee00-0000-4000-8000-0000000000e6' }, sig)).toBe(false)
    expect(v1.verifySuccession(pub, { ...input(), scopeId: v.scope_id.toUpperCase() }, sig)).toBe(false)
    expect(v1.verifySuccession(pub, { ...input(), successionPublicKey: flip(unhex(v.next_succession_public_key), 31) }, sig)).toBe(false)
    expect(v1.verifySuccession(pub, { ...input(), successionPublicKey: new Uint8Array(31) }, sig)).toBe(false)
  })
})

describe('verifySuccessionPath (ADR 0115 decision 5)', () => {
  const scopeId = 'c0ffee00-0000-4000-8000-0000000000e5'
  const otherScopeId = 'c0ffee00-0000-4000-8000-0000000000e6'
  const keyOf = (epoch: number) => new Uint8Array(32).fill(0x40 + epoch)
  const strangerKey = new Uint8Array(32).fill(0x99)
  const pkOf = (epoch: number) => v1.successionKeyPair(keyOf(epoch)).publicKey
  const linkFor = (
    epoch: number,
    o: { signer?: Uint8Array; scopeId?: string; successionPublicKey?: Uint8Array } = {},
  ): v1.SuccessionLink => {
    const successionPublicKey = o.successionPublicKey ?? pkOf(epoch)
    const signer = o.signer ?? keyOf(epoch - 1)
    return {
      epoch,
      successionPublicKey,
      successionSignature: v1.signSuccession(signer, { scopeId: o.scopeId ?? scopeId, epoch, successionPublicKey }),
    }
  }
  // A holder of epoch 2, granted epoch 5: three successions, 3, 4 and 5.
  const genuine = (): v1.SuccessionLink[] => [linkFor(3), linkFor(4), linkFor(5)]
  const path = (statements: readonly v1.SuccessionLink[], over: Partial<Parameters<typeof v1.verifySuccessionPath>[0]> = {}) =>
    v1.verifySuccessionPath({
      scopeId,
      heldEpoch: 2,
      heldPublicKey: pkOf(2),
      statements,
      targetEpoch: 5,
      targetPublicKey: pkOf(5),
      ...over,
    })
  const replaced = (epoch: number, link: v1.SuccessionLink) => genuine().map((l) => (l.epoch === epoch ? link : l))

  it('a genuine three-step path verifies, in any order, and statements outside the range are not read', () => {
    expect(path(genuine())).toBe(true)
    expect(path(genuine().reverse())).toBe(true)
    expect(path([genuine()[1], genuine()[2], genuine()[0]])).toBe(true)
    // An earlier step and a later one, even a forged one, sit outside 3…5.
    expect(path([linkFor(2, { signer: strangerKey }), ...genuine(), linkFor(6, { signer: strangerKey })])).toBe(true)
    // Not read, so not refused as a duplicate either: two for the held epoch itself, two for an epoch past the target.
    expect(path([linkFor(2), linkFor(2, { signer: strangerKey }), ...genuine()])).toBe(true)
    expect(path([...genuine(), linkFor(6), linkFor(6, { signer: strangerKey })])).toBe(true)
    // From a scope's birth: epoch 0's key derives the first public key, and no statement precedes it.
    expect(path([linkFor(1), linkFor(2), ...genuine()], { heldEpoch: 0, heldPublicKey: pkOf(0) })).toBe(true)
  })

  it('a gap is refused, wherever it is', () => {
    expect(path(genuine())).toBe(true)
    for (const missing of [3, 4, 5]) {
      expect(path(genuine().filter((l) => l.epoch !== missing)), `without ${missing}`).toBe(false)
    }
    expect(path([])).toBe(false)
    // A step that skips an epoch is a gap even when its signature verifies: epoch 4 signed with epoch 2's key.
    expect(path([linkFor(3), linkFor(4), linkFor(5)])).toBe(true)
    expect(path([linkFor(4, { signer: keyOf(2) }), linkFor(5)])).toBe(false)
  })

  it('a statement signed by any key but the epoch before it is refused', () => {
    expect(path(replaced(4, linkFor(4, { signer: keyOf(3) })))).toBe(true)
    expect(path(replaced(4, linkFor(4, { signer: keyOf(2) })))).toBe(false)
    expect(path(replaced(4, linkFor(4, { signer: keyOf(4) })))).toBe(false)
    expect(path(replaced(4, linkFor(4, { signer: strangerKey })))).toBe(false)
    // The first step must verify under the HELD key: a path from the wrong start does not count.
    expect(path(genuine(), { heldPublicKey: pkOf(1) })).toBe(false)
    // A tampered signature, a statement whose key was swapped after signing.
    const l4 = linkFor(4)
    expect(path(replaced(4, { ...l4, successionSignature: flip(l4.successionSignature, 7) }))).toBe(false)
    expect(path(replaced(4, { ...l4, successionPublicKey: pkOf(6) }))).toBe(false)
  })

  it('another scope\'s statement is refused, though its signer is genuine', () => {
    expect(path(replaced(4, linkFor(4, { scopeId })))).toBe(true)
    expect(path(replaced(4, linkFor(4, { scopeId: otherScopeId })))).toBe(false)
    expect(path(genuine(), { scopeId: otherScopeId })).toBe(false)
  })

  it('the last key must be the target key, byte for byte', () => {
    expect(path(genuine(), { targetPublicKey: pkOf(5) })).toBe(true)
    expect(path(genuine(), { targetPublicKey: pkOf(4) })).toBe(false)
    expect(path(genuine(), { targetPublicKey: pkOf(6) })).toBe(false)
    expect(path(genuine(), { targetPublicKey: flip(pkOf(5), 31) })).toBe(false)
    expect(path(genuine(), { targetPublicKey: pkOf(5).slice(0, 31) })).toBe(false)
    // A path to an earlier epoch ends at that epoch's key; the statements beyond it are not read.
    expect(path(genuine(), { targetEpoch: 4, targetPublicKey: pkOf(4) })).toBe(true)
    expect(path(genuine(), { targetEpoch: 4, targetPublicKey: pkOf(5) })).toBe(false)
  })

  it('the held epoch proves only the held key, and an older target nothing', () => {
    expect(path([], { targetEpoch: 2, targetPublicKey: pkOf(2) })).toBe(true)
    expect(path(genuine(), { targetEpoch: 2, targetPublicKey: pkOf(2) })).toBe(true)
    expect(path([], { targetEpoch: 2, targetPublicKey: pkOf(3) })).toBe(false)
    // Below the held epoch even the held key itself is refused: the epoch is what is older.
    expect(path([], { targetEpoch: 1, targetPublicKey: pkOf(2) })).toBe(false)
    // Epoch 1's genuine key, with its genuine statement beside it: still below what the holder held.
    expect(path([linkFor(1), linkFor(2)], { targetEpoch: 1, targetPublicKey: pkOf(1) })).toBe(false)
    expect(path([linkFor(1), linkFor(2)], { heldEpoch: 0, heldPublicKey: pkOf(0), targetEpoch: 1, targetPublicKey: pkOf(1) })).toBe(true)
  })

  it('two statements for one epoch are refused, never chosen between', () => {
    const forged = linkFor(4, { signer: strangerKey })
    expect(path(genuine())).toBe(true)
    expect(path([...genuine(), forged])).toBe(false)
    expect(path([forged, ...genuine()])).toBe(false)
    expect(path([...genuine(), linkFor(4)])).toBe(false)
  })

  it('an epoch that is no unsigned integer refuses the path', () => {
    expect(path(genuine())).toBe(true)
    for (const epoch of [-1, 3.5, Number.NaN, Number.POSITIVE_INFINITY]) {
      expect(path([...genuine(), { ...linkFor(4), epoch }]), `statement epoch ${epoch}`).toBe(false)
      expect(path(genuine(), { heldEpoch: epoch }), `held epoch ${epoch}`).toBe(false)
      expect(path(genuine(), { targetEpoch: epoch }), `target epoch ${epoch}`).toBe(false)
    }
    // Where nothing after the epoch check could refuse — no statement to walk, the target key the held one — a held
    // epoch, a target epoch, or both that are no unsigned integer still refuse (control: the same at epoch 2).
    expect(path([], { targetEpoch: 2, targetPublicKey: pkOf(2) })).toBe(true)
    for (const [heldEpoch, targetEpoch] of [[2.5, 2.5], [-1, -1], [1.5, 2], [Number.NaN, 2], [2, 2.5]]) {
      expect(path([], { heldEpoch, targetEpoch, targetPublicKey: pkOf(2) }), `held ${heldEpoch}, target ${targetEpoch}`).toBe(false)
    }
    expect(path(genuine(), { heldPublicKey: pkOf(2).slice(0, 31) })).toBe(false)
    expect(path([], { targetEpoch: 2, heldPublicKey: new Uint8Array(31), targetPublicKey: new Uint8Array(31) })).toBe(false)
    expect(path([], { targetEpoch: 2, targetPublicKey: [...pkOf(2)] as unknown as Uint8Array })).toBe(false)
    expect(path([], { targetEpoch: 2, heldPublicKey: [...pkOf(2)] as unknown as Uint8Array, targetPublicKey: pkOf(2) })).toBe(false)
  })
})
