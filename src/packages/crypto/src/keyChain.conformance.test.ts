// The key chain and the rotation stage (ADR 0111 decision 7, cr-secure-the-account decisions 2-3), against
// contracts/crypto/vectors/v1.json#key_chain and #rotation_stage — both written by an independent implementation.
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHash } from 'node:crypto'
import { ed25519 } from '@noble/curves/ed25519.js'
import * as v1 from './v1'
import { STATEMENT_PURPOSES } from './seal'

const unhex = (s: string) => Uint8Array.from(Buffer.from(s, 'hex'))
const hex = (u: Uint8Array) => Buffer.from(u).toString('hex')
const here = dirname(fileURLToPath(import.meta.url))
const vectors = JSON.parse(readFileSync(resolve(here, '../../../../contracts/crypto/vectors/v1.json'), 'utf8'))

type VectorLink = {
  index: number
  previous_head: string | null
  identity_public_key: string
  secret_key_proof_public_key: string
  identity_signature: string | null
  proof_signature: string | null
  statement: string
  head: string
}
type FailureLink = VectorLink & { reason: v1.KeyChainRefusal }

const kc = vectors.key_chain
const userId: string = kc.user_id
const vectorLinks: VectorLink[] = kc.links
const orNull = (s: string | null) => (s === null ? null : unhex(s))
const linkOf = (l: VectorLink): v1.KeyChainLink => ({
  index: l.index,
  previousHead: orNull(l.previous_head),
  identityPublicKey: unhex(l.identity_public_key),
  secretKeyProofPublicKey: unhex(l.secret_key_proof_public_key),
  identitySignature: orNull(l.identity_signature),
  proofSignature: orNull(l.proof_signature),
})
const chainOf = (ls: VectorLink[]) => ({ userId, links: ls.map(linkOf) })
const flip = (b: Uint8Array, at = 0) => {
  const out = b.slice()
  out[at] ^= 1
  return out
}

describe('key chain (#key_chain, ADR 0111 decision 7)', () => {
  const statementOf = (i: number): v1.KeyChainGenesisStatement | v1.KeyChainStepStatement => {
    const l = linkOf(vectorLinks[i])
    if (i === 0) return v1.keyChainGenesisStatement(userId, l)
    return v1.keyChainStepStatement({
      userId,
      index: i,
      previousHead: l.previousHead as Uint8Array,
      retired: linkOf(vectorLinks[i - 1]),
      installed: l,
    })
  }

  it('each step\'s keys are the bundle and proof key its seeds derive', () => {
    vectorLinks.forEach((l, i) => {
      const sign = ed25519.getPublicKey(unhex(kc.sign_seeds[i]))
      expect(hex(v1.deserializeIdentityPublic(unhex(l.identity_public_key)).sign)).toBe(hex(sign))
      expect(l.secret_key_proof_public_key).toBe(hex(v1.deriveSecretKeyProofKeypair(unhex(kc.secret_keys[i])).publicKey))
    })
  })

  it.each([0, 1, 2])('step %i: statement bytes and head', (i) => {
    const stmt = statementOf(i)
    expect(hex(v1.encodeStatement(stmt))).toBe(vectorLinks[i].statement)
    expect(hex(v1.keyChainHead(stmt))).toBe(vectorLinks[i].head)
    // The head is the note's formula, recomputed with node.
    const h = createHash('sha256').update(Buffer.from('veyq/v1/key-chain/head', 'utf8')).update(unhex(vectorLinks[i].statement))
    expect(h.digest('hex')).toBe(vectorLinks[i].head)
  })

  it.each([1, 2])('step %i names the head of the step before it, and both its signatures are the vector\'s', (i) => {
    const l = vectorLinks[i]
    expect(l.previous_head).toBe(vectorLinks[i - 1].head)
    const sigs = v1.signKeyChainStep(
      statementOf(i) as v1.KeyChainStepStatement,
      unhex(kc.sign_seeds[i - 1]),
      v1.deriveSecretKeyProofKeypair(unhex(kc.secret_keys[i - 1])).privateKey,
    )
    expect(hex(sigs.identitySignature)).toBe(l.identity_signature)
    expect(hex(sigs.proofSignature)).toBe(l.proof_signature)
  })

  it('the genesis and the step are two labels of their own, signed only by an identity as a step', () => {
    const genesis = v1.encodeStatement(statementOf(0))
    const step = v1.encodeStatement(statementOf(1))
    expect(new TextDecoder().decode(genesis.slice(4, 4 + 'key-chain-genesis'.length))).toBe('key-chain-genesis')
    expect(new TextDecoder().decode(step.slice(4, 4 + 'key-chain-step'.length))).toBe('key-chain-step')
    expect(Object.keys(STATEMENT_PURPOSES)).toEqual(expect.arrayContaining(['key-chain-genesis', 'key-chain-step']))
    expect(Object.keys(vectors.seal.sym)).not.toContain('key-chain-step')
    expect(Object.keys(vectors.seal.asym)).not.toContain('key-chain-step')
  })

  it('a step index below 1 is refused', () => {
    const base = { userId, previousHead: new Uint8Array(32), retired: linkOf(vectorLinks[0]), installed: linkOf(vectorLinks[1]) }
    for (const index of [0, -1, 1.5]) {
      expect(() => v1.keyChainStepStatement({ ...base, index })).toThrow(new v1.SealContextInvalid('key-chain-step index is not 1 or more'))
    }
    expect(v1.keyChainStepStatement({ ...base, index: 1 }).index).toBe(1)
  })

  describe('verifyKeyChain', () => {
    it('the whole chain from genesis: the keys and head at the newest step', () => {
      const v = v1.verifyKeyChain(chainOf(vectorLinks), null)
      expect(v).toEqual({
        ok: true,
        head: unhex(vectorLinks[2].head),
        index: 2,
        fromIndex: null,
        identityPublicKey: unhex(vectorLinks[2].identity_public_key),
        secretKeyProofPublicKey: unhex(vectorLinks[2].secret_key_proof_public_key),
      })
    })

    it('a genesis alone is a chain: its own keys and head', () => {
      const v = v1.verifyKeyChain(chainOf(vectorLinks.slice(0, 1)), unhex(vectorLinks[0].head))
      expect(v).toMatchObject({ ok: true, index: 0, fromIndex: 0, head: unhex(vectorLinks[0].head) })
    })

    it.each([0, 1, 2])('from the head of step %i: where the walk passed it', (k) => {
      const v = v1.verifyKeyChain(chainOf(vectorLinks), unhex(vectorLinks[k].head))
      expect(v).toMatchObject({ ok: true, index: 2, fromIndex: k })
    })

    it('a head the chain never reaches — a rollback, or another account\'s — is refused', () => {
      // The chain cut back to step 1, read from the head of step 2: the server rolled the person back.
      expect(v1.verifyKeyChain(chainOf(vectorLinks.slice(0, 2)), unhex(vectorLinks[2].head))).toEqual({
        ok: false,
        step: 1,
        reason: 'head_not_in_chain',
      })
      expect(v1.verifyKeyChain(chainOf(vectorLinks), flip(unhex(vectorLinks[1].head)))).toEqual({
        ok: false,
        step: 2,
        reason: 'head_not_in_chain',
      })
      // The same links under another account: every statement names the user, so nothing verifies past genesis.
      const other = v1.verifyKeyChain({ ...chainOf(vectorLinks), userId: 'b0b00000-0000-4000-8000-0000000000c2' }, null)
      expect(other).toEqual({ ok: false, step: 1, reason: 'predecessor' })
    })

    const failures = Object.entries(kc.failures as Record<string, FailureLink>)
    it.each(failures)('%s: refused at step 2 for its reason', (_name, f) => {
      const v = v1.verifyKeyChain(chainOf([vectorLinks[0], vectorLinks[1], f]), null)
      expect(v).toEqual({ ok: false, step: 2, reason: f.reason })
    })

    it('the vector\'s failures cover the three reasons a signed step can fail for', () => {
      expect(failures.map(([, f]) => f.reason).sort()).toEqual(['identity_signature', 'predecessor', 'proof_signature'])
    })

    it('the wrong predecessor carries genuine signatures, so only the head comparison refuses it', () => {
      const f = kc.failures.wrong_predecessor as FailureLink
      expect(f.previous_head).toBe(vectorLinks[0].head)
      const stmt = v1.keyChainStepStatement({
        userId,
        index: 2,
        previousHead: unhex(vectorLinks[0].head),
        retired: linkOf(vectorLinks[1]),
        installed: linkOf(f),
      })
      expect(hex(v1.encodeStatement(stmt))).toBe(f.statement)
      expect(v1.identityVerify(v1.deserializeIdentityPublic(unhex(vectorLinks[1].identity_public_key)).sign, stmt, unhex(f.identity_signature as string))).toBe(true)
      expect(v1.authVerify(unhex(vectorLinks[1].secret_key_proof_public_key), unhex(f.statement), unhex(f.proof_signature as string))).toBe(true)
    })

    it('a step signed by its OWN new keys, not the retired ones, is refused', () => {
      // What a thief holding the new keys alone would write: the step's statement signed by the keys it installs.
      const stmt = v1.keyChainStepStatement({
        userId,
        index: 1,
        previousHead: unhex(vectorLinks[0].head),
        retired: linkOf(vectorLinks[0]),
        installed: linkOf(vectorLinks[1]),
      })
      const sigs = v1.signKeyChainStep(stmt, unhex(kc.sign_seeds[1]), v1.deriveSecretKeyProofKeypair(unhex(kc.secret_keys[1])).privateKey)
      const forged = { ...linkOf(vectorLinks[1]), ...sigs }
      expect(v1.verifyKeyChain({ userId, links: [linkOf(vectorLinks[0]), forged] }, null)).toEqual({
        ok: false,
        step: 1,
        reason: 'identity_signature',
      })
      // The identity half genuine, the proof half by the new key: the proof check alone refuses it.
      const halfForged = { ...linkOf(vectorLinks[1]), proofSignature: sigs.proofSignature }
      expect(v1.verifyKeyChain({ userId, links: [linkOf(vectorLinks[0]), halfForged] }, null)).toEqual({
        ok: false,
        step: 1,
        reason: 'proof_signature',
      })
    })

    it('a step whose installed keys were swapped after signing is refused by the signature, at that step', () => {
      const l = linkOf(vectorLinks[2])
      const swappedProof = { ...l, secretKeyProofPublicKey: unhex(vectorLinks[0].secret_key_proof_public_key) }
      expect(v1.verifyKeyChain({ userId, links: [linkOf(vectorLinks[0]), linkOf(vectorLinks[1]), swappedProof] }, null)).toEqual({
        ok: false,
        step: 2,
        reason: 'identity_signature',
      })
      const swappedIdentity = { ...l, identityPublicKey: unhex(vectorLinks[0].identity_public_key) }
      expect(v1.verifyKeyChain({ userId, links: [linkOf(vectorLinks[0]), linkOf(vectorLinks[1]), swappedIdentity] }, null)).toEqual({
        ok: false,
        step: 2,
        reason: 'identity_signature',
      })
    })

    it('a genesis served with other keys makes another head, so a step after it no longer chains', () => {
      const g = { ...linkOf(vectorLinks[0]), secretKeyProofPublicKey: unhex(vectorLinks[2].secret_key_proof_public_key) }
      expect(v1.verifyKeyChain({ userId, links: [g, linkOf(vectorLinks[1])] }, null)).toEqual({ ok: false, step: 1, reason: 'predecessor' })
      // …and read from the head pinned at genesis, the substituted genesis alone is not the pinned one.
      expect(v1.verifyKeyChain({ userId, links: [g] }, unhex(vectorLinks[0].head))).toEqual({ ok: false, step: 0, reason: 'head_not_in_chain' })
    })

    describe('a malformed chain is refused at the step that is malformed — never a throw', () => {
      const l = vectorLinks.map(linkOf)
      const cases: [string, v1.KeyChainLink[], number][] = [
        ['an empty chain', [], 0],
        ['a genesis that carries a predecessor', [{ ...l[0], previousHead: new Uint8Array(32) }], 0],
        ['a genesis that carries an identity signature', [{ ...l[0], identitySignature: new Uint8Array(64) }], 0],
        ['a genesis that carries a proof signature', [{ ...l[0], proofSignature: new Uint8Array(64) }], 0],
        ['a genesis at index 1', [{ ...l[0], index: 1 }], 0],
        ['a step without a predecessor', [l[0], { ...l[1], previousHead: null }], 1],
        ['a step without an identity signature', [l[0], { ...l[1], identitySignature: null }], 1],
        ['a step without a proof signature', [l[0], { ...l[1], proofSignature: null }], 1],
        ['steps out of order', [l[0], l[2], l[1]], 1],
        ['a step listed twice', [l[0], l[1], l[1]], 2],
        ['an identity bundle that does not decode', [l[0], { ...l[1], identityPublicKey: l[1].identityPublicKey.slice(0, -1) }], 1],
        ['a genesis bundle that does not decode', [{ ...l[0], identityPublicKey: new Uint8Array(3) }], 0],
        ['a proof key of 31 bytes', [l[0], { ...l[1], secretKeyProofPublicKey: l[1].secretKeyProofPublicKey.slice(0, 31) }], 1],
        ['a proof key of 33 bytes', [{ ...l[0], secretKeyProofPublicKey: v1.concat(l[0].secretKeyProofPublicKey, Uint8Array.of(0)) }], 0],
        ['a proof key that is not bytes', [{ ...l[0], secretKeyProofPublicKey: 'x'.repeat(32) as unknown as Uint8Array }], 0],
      ]
      it.each(cases)('%s', (_name, links, step) => {
        expect(v1.verifyKeyChain({ userId, links }, null)).toEqual({ ok: false, step, reason: 'malformed' })
      })

      it('a short predecessor is a predecessor refusal, short signatures and a proof key off the curve are signature refusals', () => {
        expect(v1.verifyKeyChain({ userId, links: [l[0], { ...l[1], previousHead: l[1].previousHead!.slice(0, 31) }] }, null))
          .toEqual({ ok: false, step: 1, reason: 'predecessor' })
        expect(v1.verifyKeyChain({ userId, links: [l[0], { ...l[1], identitySignature: l[1].identitySignature!.slice(0, 63) }] }, null))
          .toEqual({ ok: false, step: 1, reason: 'identity_signature' })
        expect(v1.verifyKeyChain({ userId, links: [l[0], { ...l[1], proofSignature: l[1].proofSignature!.slice(0, 63) }] }, null))
          .toEqual({ ok: false, step: 1, reason: 'proof_signature' })
        // A retired proof key that is no curve point, under a step whose identity half is genuine: the proof verify
        // fails inside, and the walk answers a refusal rather than a throw.
        const genesis = { ...l[0], secretKeyProofPublicKey: new Uint8Array(32).fill(0xff) }
        const head = v1.keyChainHead(v1.keyChainGenesisStatement(userId, genesis))
        const stmt = v1.keyChainStepStatement({ userId, index: 1, previousHead: head, retired: genesis, installed: l[1] })
        const step = { ...l[1], previousHead: head, identitySignature: v1.identitySign(unhex(kc.sign_seeds[0]), stmt) }
        expect(v1.verifyKeyChain({ userId, links: [genesis, step] }, null)).toEqual({ ok: false, step: 1, reason: 'proof_signature' })
      })
    })
  })
})

describe('rotation stage digest (#rotation_stage, cr-secure-the-account decision 3)', () => {
  const rs = vectors.rotation_stage
  type Entry = { scopeId: string; epoch: number; wrapped_grant: string }
  const entriesOf = (es: Entry[]): v1.RotationStageEntry[] =>
    es.map((e) => ({ scopeId: e.scopeId, epoch: e.epoch, wrappedGrant: unhex(e.wrapped_grant) }))
  const two = entriesOf(rs.entries)

  it('the digest of the two entries and of none', () => {
    expect(hex(v1.rotationStageDigest(two))).toBe(rs.digest)
    expect(hex(v1.rotationStageDigest([]))).toBe(rs.empty)
  })

  it('is over the SET in scopeId order — the order the entries are handed in does not move it', () => {
    expect(rs.entries[0].scopeId > rs.entries[1].scopeId).toBe(true) // listed opposite to scopeId order
    expect(hex(v1.rotationStageDigest([...two].reverse()))).toBe(rs.digest)
    expect(hex(v1.rotationStageDigest(new Set(two)))).toBe(rs.digest)
  })

  it('is the note\'s formula, recomputed with node', () => {
    const lpb = (b: Uint8Array | Buffer) =>
      Buffer.concat([Buffer.from([b.length >>> 24, (b.length >>> 16) & 0xff, (b.length >>> 8) & 0xff, b.length & 0xff]), Buffer.from(b)])
    const h = createHash('sha256').update(Buffer.from('veyq/v1/rotation-stage', 'utf8'))
    for (const e of [...rs.entries as Entry[]].sort((a, b) => (a.scopeId < b.scopeId ? -1 : 1))) {
      const epoch = Buffer.alloc(8)
      epoch.writeBigUInt64BE(BigInt(e.epoch))
      h.update(Buffer.concat([lpb(Buffer.from(e.scopeId, 'utf8')), lpb(epoch), lpb(createHash('sha256').update(unhex(e.wrapped_grant)).digest())]))
    }
    expect(h.digest('hex')).toBe(rs.digest)
  })

  it('a withheld, added, moved, re-epoched or re-wrapped entry each changes the digest', () => {
    const [a, b] = two
    const variants: [string, v1.RotationStageEntry[]][] = [
      ['withheld', [a]],
      ['added', [a, b, { ...a, scopeId: 'c0ffee00-0000-4000-8000-0000000000f3' }]],
      ['moved to another scope', [{ ...a, scopeId: 'c0ffee00-0000-4000-8000-0000000000f3' }, b]],
      ['another epoch', [{ ...a, epoch: a.epoch + 1 }, b]],
      ['another ciphertext', [{ ...a, wrappedGrant: flip(a.wrappedGrant, a.wrappedGrant.length - 1) }, b]],
      ['epochs swapped between two scopes', [{ ...a, epoch: b.epoch }, { ...b, epoch: a.epoch }]],
    ]
    for (const [name, es] of variants) expect(hex(v1.rotationStageDigest(es)), name).not.toBe(rs.digest)
  })

  it('an epoch beyond 32 bits is encoded whole, not cut', () => {
    const [a] = two
    expect(hex(v1.rotationStageDigest([{ ...a, epoch: 2 ** 32 + a.epoch }]))).not.toBe(hex(v1.rotationStageDigest([a])))
  })

  it('refuses a scope id that is not a canonical UUID, an epoch that is not an unsigned integer, one scope twice', () => {
    const [a, b] = two
    const badId = new v1.SealContextInvalid('rotation-stage scopeId is not a canonical lowercase UUID')
    for (const scopeId of [a.scopeId.toUpperCase(), `${a.scopeId}0`, 'root', new String(a.scopeId) as unknown as string]) {
      expect(() => v1.rotationStageDigest([b, { ...a, scopeId }])).toThrow(badId)
    }
    const badEpoch = new v1.SealContextInvalid('rotation-stage epoch is not an unsigned integer')
    for (const epoch of [-1, 1.5, Number.MAX_SAFE_INTEGER + 1, '3' as unknown as number]) {
      expect(() => v1.rotationStageDigest([b, { ...a, epoch }])).toThrow(badEpoch)
    }
    expect(() => v1.rotationStageDigest([a, b, { ...a, epoch: a.epoch + 1 }])).toThrow(
      new v1.SealContextInvalid('rotation stage holds one scope twice'),
    )
    expect(v1.rotationStageDigest([{ ...a, epoch: 0 }])).toHaveLength(32)
  })
})
