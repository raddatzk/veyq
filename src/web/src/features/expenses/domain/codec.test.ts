// The format v1 codec through the real crypto (`@veyq/crypto`, no mocked seal): a revision opens only at the address it
// was sealed for, its sealed body is exactly one of the contract's four lengths, and a re-key re-wraps K_rev without
// touching the body — so the digest later heads name stays.
import { describe, it, expect } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import { openRevision, revisionDigest, revisionKeyOf, rewrapRevisionKey, RevisionUnreadable, sealRevision, SIZE_CLASSES, type RevisionAddress } from './codec'
import { encodeRevisionBody, type RevisionBody } from './format'

const SCOPE = '22222222-2222-4222-8222-222222222222'
const POT = '0e000000-0000-4000-b000-000000000001'
const OTHER_POT = '0e000000-0000-4000-b000-000000000002'
const A = '0e000000-0000-4000-9000-000000000001'
const B = '0e000000-0000-4000-9000-000000000002'
const P = '0e000000-0000-4000-8000-000000000001'
const Q = '0e000000-0000-4000-8000-000000000002'
const R = '0e000000-0000-4000-8000-000000000003'
const at = (entryId: string, revision = 1, nodeId = POT): RevisionAddress => ({ scopeId: SCOPE, nodeId, entryId, revision })
const potKey = v1.randomBytes(32)

const transfer: RevisionBody = { v: 1, heads: [], kind: 'transfer', date: '2026-07-03', currency: 'EUR', minorUnits: 2, rate: '1', amount: 2000, from: P, to: Q, receipts: [] }
const head = { entryId: B, revision: 3, digest: revisionDigest(new Uint8Array(744)) }
const expense: RevisionBody = {
  v: 1, heads: [head], kind: 'expense', title: 'Dinner', date: '2026-07-01', category: 'food', currency: 'EUR', minorUnits: 2, rate: '1', amount: 10000,
  payers: [{ participant: P, amount: 10000 }], allocation: { items: [], rest: { mode: 'equal', participants: [P, Q, R] } }, receipts: [],
}

/** The sealed body lengths the contract takes, in base64 characters — one per size class. */
const WIRE_LENGTHS = [744, 2792, 10984, 21908]

describe('sealing a revision', () => {
  it('opens at its own address to the body it sealed', () => {
    const sealed = sealRevision(at(A), potKey, expense)
    expect(openRevision(at(A), potKey, sealed)).toEqual(expense)
  })

  it('pads to a size class whose sealed length is one the contract takes: a transfer to 512 B, an expense with one head to 2 KiB', () => {
    expect(b64std(sealRevision(at(A), potKey, transfer).sealedBody).length).toBe(744)
    // The vectors' Dinner (three people, one payer) with one head: 545 bytes of JSON, past the 508 the 512 B class carries.
    expect(encodeRevisionBody(expense).length).toBe(545)
    expect(b64std(sealRevision(at(A), potKey, expense).sealedBody).length).toBe(2792)
    const big: RevisionBody = { ...expense, allocation: { items: Array.from({ length: 40 }, (_, i) => ({ label: `Item ${i} `.padEnd(120, '·'), amount: 1, split: { mode: 'equal', participants: [P] } })), rest: { mode: 'equal', participants: [P, Q, R] } } }
    const bigLength = b64std(sealRevision(at(A), potKey, big).sealedBody).length
    expect(WIRE_LENGTHS).toContain(bigLength)
    expect(bigLength).toBeGreaterThan(2792)
    expect(SIZE_CLASSES.map((c) => Math.ceil((c + (744 * 3) / 4 - 512) / 3) * 4)).toEqual(WIRE_LENGTHS) // the classes ARE the four
  })

  it('refuses a body past the 16 KiB cap', () => {
    const huge: RevisionBody = { ...expense, allocation: { items: Array.from({ length: 200 }, (_, i) => ({ label: `${i}`.padEnd(200, 'x'), amount: 1, split: { mode: 'equal', participants: [P] } })), rest: { mode: 'equal', participants: [P, Q, R] } } }
    expect(() => sealRevision(at(A), potKey, huge)).toThrow('pad_too_large')
  })

  it('draws a fresh K_rev each time, so two seals of one body share no key and no bytes', () => {
    const one = sealRevision(at(A), potKey, transfer)
    const two = sealRevision(at(A), potKey, transfer)
    expect(b64std(revisionKeyOf(at(A), potKey, one.wrappedRevisionKey))).not.toBe(b64std(revisionKeyOf(at(A), potKey, two.wrappedRevisionKey)))
    expect(revisionDigest(one.sealedBody)).not.toBe(revisionDigest(two.sealedBody))
  })
})

describe('a revision served anywhere else does not open', () => {
  const sealed = sealRevision(at(A), potKey, expense)
  const reason = (address: RevisionAddress, key = potKey, s = sealed) => {
    try {
      openRevision(address, key, s)
      return 'opened'
    } catch (e) {
      return e instanceof RevisionUnreadable ? e.reason : 'other'
    }
  }

  it.each([
    ['another entry', at(B)],
    ['another revision of its entry', at(A, 2)],
    ['another Pot', at(A, 1, OTHER_POT)],
    ['another scope', { ...at(A), scopeId: '33333333-3333-4333-8333-333333333333' }],
  ])('at %s', (_, address) => {
    expect(reason(address)).toBe('key')
    expect(reason(at(A))).toBe('opened') // control: its own address
  })

  it('under another Pot\'s key', () => {
    expect(reason(at(A), v1.randomBytes(32))).toBe('key')
  })

  it('a body beside another revision\'s wrap (both genuine, swapped by the server)', () => {
    const other = sealRevision(at(A), potKey, transfer)
    expect(reason(at(A), potKey, { sealedBody: other.sealedBody, wrappedRevisionKey: sealed.wrappedRevisionKey })).toBe('body')
  })

  it('a body that opens but is no v1 revision', () => {
    const kRev = v1.randomBytes(32)
    const junk = {
      sealedBody: v1.sealField({ purpose: 'expense-entry', ...at(A) }, kRev, v1.padToClass(new TextEncoder().encode('{"v":2}'), SIZE_CLASSES)),
      wrappedRevisionKey: v1.seal({ purpose: 'expense-entry-key', ...at(A) }, potKey, kRev),
    }
    expect(reason(at(A), potKey, junk)).toBe('format')
  })
})

describe('a re-key re-wraps K_rev and never the body', () => {
  it('the new wrap opens under the new key only, the body and its digest are the very bytes they were', () => {
    const sealed = sealRevision(at(A), potKey, expense)
    const digest = revisionDigest(sealed.sealedBody)
    const newKey = v1.randomBytes(32)
    const rewrapped = rewrapRevisionKey(at(A), potKey, newKey, sealed.wrappedRevisionKey)
    const moved = { sealedBody: sealed.sealedBody, wrappedRevisionKey: rewrapped }
    expect(openRevision(at(A), newKey, moved)).toEqual(expense)
    expect(revisionDigest(moved.sealedBody)).toBe(digest)
    expect(() => openRevision(at(A), potKey, moved)).toThrow(RevisionUnreadable) // the old key is out
    expect(b64std(revisionKeyOf(at(A), newKey, rewrapped))).toBe(b64std(revisionKeyOf(at(A), potKey, sealed.wrappedRevisionKey)))
  })

  it('refuses to re-wrap a wrap that does not open under the old key (nothing is re-wrapped blind)', () => {
    const sealed = sealRevision(at(A), potKey, expense)
    expect(() => rewrapRevisionKey(at(B), potKey, v1.randomBytes(32), sealed.wrappedRevisionKey)).toThrow(RevisionUnreadable)
  })
})
