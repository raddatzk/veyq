// A verification (ADR 0111 decisions 12–14) against the vectors an independent Python implementation generated
// (`#verification`, `field_seal['member-verification']`, `#fingerprint.chain_code`): the statement, the signature, the
// record and its seal — each with a crafted negative that fails exactly one check, beside its positive control.
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import * as v1 from './v1'
import * as verification from './verification'

const unhex = (s: string) => Uint8Array.from(Buffer.from(s, 'hex'))
const hex = (u: Uint8Array) => Buffer.from(u).toString('hex')
const here = dirname(fileURLToPath(import.meta.url))
const vectors = JSON.parse(readFileSync(resolve(here, '../../../../contracts/crypto/vectors/v1.json'), 'utf8'))
const vv = vectors.verification
const fs = vectors.field_seal['member-verification']
const flip = (b: Uint8Array, i = 0) => {
  const out = b.slice()
  out[i] ^= 1
  return out
}
const OTHER = 'c0ffee00-0000-4000-8000-0000000000f9'

const address: verification.VerificationAddress = vv.context
const heads = { subjectHead: unhex(vv.subject_head), verifierHead: unhex(vv.verifier_head) }
const signed: verification.Verification = { ...heads, signature: unhex(vv.signature) }
const verifierKey = unhex(vv.sign_public)

describe('verification statement and signature (#verification)', () => {
  it('statement bytes, signature and record', () => {
    expect(hex(v1.encodeStatement(verification.verificationStatement(address, heads)))).toBe(vv.statement)
    expect(hex(verification.signVerification(address, heads, unhex(vv.sign_seed)).signature)).toBe(vv.signature)
    expect(hex(verification.encodeVerification(signed))).toBe(vv.record)
    expect(verification.decodeVerification(unhex(vv.record))).toEqual(signed)
  })

  it('verifies under the verifier key — the control for every negative below', () => {
    expect(verification.verificationVerifies(address, signed, verifierKey)).toBe(true)
  })

  it.each([
    ['another tenant', { ...address, tenantId: OTHER }, signed],
    ['another verifier', { ...address, verifierUserId: OTHER }, signed],
    ['another subject', { ...address, subjectUserId: OTHER }, signed],
    ['another subject head', address, { ...signed, subjectHead: flip(signed.subjectHead) }],
    ['another verifier head', address, { ...signed, verifierHead: flip(signed.verifierHead) }],
    ['a tampered signature', address, { ...signed, signature: flip(signed.signature, 5) }],
  ] as const)('answers for nothing else: %s', (_name, a, v) => {
    expect(verification.verificationVerifies(a, v, verifierKey)).toBe(false)
  })

  it('a signature by another key — the retired key a thief holds — is not the verifier key\'s', () => {
    const thief = verification.signVerification(address, heads, unhex('8d'.repeat(32)))
    expect(verification.verificationVerifies(address, thief, verifierKey)).toBe(false)
  })

  it('nobody verifies themselves: refused to sign, and false to read', () => {
    const self = { ...address, subjectUserId: address.verifierUserId }
    expect(() => verification.signVerification(self, heads, unhex(vv.sign_seed))).toThrow(verification.VerificationInvalid)
    const forged = { ...heads, signature: v1.identitySign(unhex(vv.sign_seed), verification.verificationStatement(self, heads)) }
    expect(verification.verificationVerifies(self, forged, verifierKey)).toBe(false)
  })

  it('a malformed key or signature is false, never a throw', () => {
    expect(verification.verificationVerifies(address, signed, new Uint8Array(3))).toBe(false)
    expect(verification.verificationVerifies(address, { ...signed, signature: new Uint8Array(2) }, verifierKey)).toBe(false)
  })
})

describe('the record encoding', () => {
  it.each([
    ['another version', () => { const b = unhex(vv.record); b[0] = 2; return b }, 'version'],
    ['nothing at all', () => new Uint8Array(0), 'version'],
    ['a truncated signature', () => unhex(vv.record).slice(0, -1), 'size'],
    ['a byte behind it', () => new Uint8Array([...unhex(vv.record), 0]), 'trailing'],
  ] as const)('%s is refused', (_name, make, reason) => {
    expect(() => verification.decodeVerification(make())).toThrow(new verification.VerificationInvalid(reason))
  })

  it('a head of the wrong length does not encode', () => {
    expect(() => verification.encodeVerification({ ...signed, subjectHead: new Uint8Array(31) })).toThrow(
      new verification.VerificationInvalid('size'),
    )
  })
})

describe('the seal (field_seal[\'member-verification\'])', () => {
  const slot = { tenantId: fs.context.scopeId, verifierUserId: fs.context.userId, subjectUserId: fs.context.subjectUserId, epoch: fs.context.epoch }

  it('opens under the epoch key at its own slot — the control', () => {
    expect(fs.plaintext).toBe(vv.record)
    expect(verification.openVerification(unhex(fs.parent_key), slot, unhex(fs.envelope))).toEqual(signed)
  })

  it.each([
    ['another tenant', { ...slot, tenantId: OTHER }],
    ['another verifier', { ...slot, verifierUserId: OTHER }],
    ['another subject', { ...slot, subjectUserId: OTHER }],
    ['another epoch', { ...slot, epoch: slot.epoch + 1 }],
  ])('does not open at %s', (_name, moved) => {
    expect(() => verification.openVerification(unhex(fs.parent_key), moved, unhex(fs.envelope))).toThrow(v1.OpenRefused)
  })

  it('round-trips through a fresh seal', () => {
    const env = verification.sealVerification(unhex(fs.parent_key), slot, signed)
    expect(verification.openVerification(unhex(fs.parent_key), slot, env)).toEqual(signed)
  })
})

describe('chain code (#fingerprint.chain_code)', () => {
  type Case = { head: string; digits: string }
  it.each((vectors.fingerprint.chain_code as Case[]).map((c) => [c.head.slice(0, 8), c] as const))('%s', (_h, c) => {
    expect(v1.chainCodeDigits(unhex(c.head))).toBe(c.digits)
    expect(c.digits).toMatch(/^\d{5} \d{5}$/)
  })

  it('two heads of one chain read as two codes — a withheld step shows', () => {
    const [a, b] = vectors.fingerprint.chain_code as Case[]
    expect(a.digits).not.toBe(b.digits)
  })

  it('is the safety number\'s rendering, cut to its first two groups', () => {
    const h = unhex(vectors.key_chain.links[0].head)
    expect(v1.chainCodeDigits(h)).toBe(v1.fingerprintDigits(h).split(' ').slice(0, 2).join(' '))
  })

  it('refuses anything but a 32-byte head', () => {
    expect(() => v1.chainCodeDigits(new Uint8Array(31))).toThrow(new Error('chain code: not a 32-byte head'))
  })
})
