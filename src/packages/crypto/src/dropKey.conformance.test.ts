// A drop box's key pair against contracts/crypto/vectors/v1.json#drop_key: the codec's bytes (lp(seed), lp(pk)), the
// public half derived from the seed, both seals at their fixed nonces, and the step from a drop box to a guest's folder
// (seal.asym['upload-identity-key'] opens under the drop key's secret). The native suite answers to the same group.
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import * as v1 from './v1'
import * as raw from './primitives'

const unhex = (s: string) => Uint8Array.from(Buffer.from(s, 'hex'))
const hex = (b: Uint8Array) => Buffer.from(b).toString('hex')
const here = dirname(fileURLToPath(import.meta.url))
const vectors = JSON.parse(readFileSync(resolve(here, '../../../../contracts/crypto/vectors/v1.json'), 'utf8'))
const d = vectors.drop_key

describe('drop key (#drop_key)', () => {
  it('serializes the seed and the public key as their fixed length-prefixed forms', () => {
    const key = { kemSeed: unhex(d.seed) }
    expect(hex(v1.serializeDropKey(key))).toBe(d.serialized)
    expect(hex(v1.dropKeyPublic(key))).toBe(d.publicKey)
    expect(hex(v1.serializeHybridPublic(v1.dropKeyPublic(key)))).toBe(d.serializedPublic)
    expect(hex(v1.deserializeDropKey(unhex(d.serialized)).kemSeed)).toBe(d.seed)
    expect(hex(v1.deserializeHybridPublic(unhex(d.serializedPublic)))).toBe(d.publicKey)
  })

  it('seals the private half under the drop box\'s key and the public half under a link\'s key, byte for byte', () => {
    const p = d.sealedPrivate
    const q = d.sealedPublic
    expect(hex(raw.symWrap(unhex(p.kek), unhex(p.plaintext), unhex(p.context_bytes), unhex(p.nonce)))).toBe(p.blob)
    expect(hex(raw.symWrap(unhex(q.kek), unhex(q.plaintext), unhex(q.context_bytes), unhex(q.nonce)))).toBe(q.blob)
    const opened = v1.open({ purpose: 'drop-private-key', ...p.context }, unhex(p.kek), unhex(p.blob))
    expect(hex(v1.deserializeDropKey(opened).kemSeed)).toBe(d.seed)
    const served = v1.open({ purpose: 'drop-public-key' }, unhex(q.kek), unhex(q.blob))
    expect(hex(v1.deserializeHybridPublic(served))).toBe(d.publicKey)
  })

  it('opens a guest\'s identity key sealed to its public half (the step into a guest\'s folder)', () => {
    const a = vectors.seal.asym['upload-identity-key']
    expect(a.pk).toBe(d.publicKey)
    expect(a.context.nodeId).toBe(d.sealedPrivate.context.nodeId)
    const secret = v1.dropKeySecret(v1.deserializeDropKey(unhex(d.serialized)))
    expect(hex(v1.openAs({ purpose: 'upload-identity-key', ...a.context }, secret, unhex(a.blob)))).toBe(a.plaintext)
  })
})
