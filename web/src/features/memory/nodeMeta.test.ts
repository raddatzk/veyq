import { describe, it, expect, vi, afterEach } from 'vitest'
import * as v1 from '../../shared/crypto/v1'
import { b64std, fromB64std } from '../../shared/base64'
import * as base64 from '../../shared/base64'
import { sealNodeMeta, unsealNodeMeta, NODE_META_CTX } from './nodeMeta'

// Assertion-strength KAT for the wrappedMeta envelope (cr-0177, ADR 0045). nodeMeta was untested: the AAD
// 'node-meta' was unpinned, and unsealNodeMeta's best-effort (E3) fall-throughs to { v: 1 } had no oracle.

const KEY = new Uint8Array(32).fill(5)
const seal = (meta: unknown) =>
  b64std(v1.symWrap(KEY, new TextEncoder().encode(JSON.stringify(meta)), 'node-meta', v1.randomBytes(12)))

afterEach(() => vi.restoreAllMocks())

describe('nodeMeta seal/unseal envelope (cr-0177, ADR 0045)', () => {
  it('pins the AAD context "node-meta" two independent ways', () => {
    expect(NODE_META_CTX).toBe('node-meta') // direct byte pin of the constant
    // Cross-check: sealNodeMeta uses the SOURCE const; open with a raw symUnwrap + the HARDCODED literal. If the
    // source const were mutated, symUnwrap('node-meta') throws on the AEAD tag → this reds. A roundtrip through
    // unsealNodeMeta would be blind (it shares the mutated const AND swallows the failure into { v: 1 }).
    const sealed = sealNodeMeta(KEY, { v: 1, date: { on: '2026-07-20' } })
    const json = new TextDecoder().decode(v1.symUnwrap(KEY, fromB64std(sealed), 'node-meta'))
    expect(JSON.parse(json)).toEqual({ v: 1, date: { on: '2026-07-20' } })
  })

  it('round-trips a sealed blob, preserving unknown sibling fields', () => {
    const sealed = sealNodeMeta(KEY, { v: 1, location: { name: 'Rom' }, cover: { mediaId: 'x' } })
    expect(unsealNodeMeta(KEY, sealed)).toEqual({ v: 1, location: { name: 'Rom' }, cover: { mediaId: 'x' } })
  })

  it('forces v to 1 even when the sealed blob claims another version', () => {
    // The `{ ...parsed, v: 1 }` line must normalize v back to 1 regardless of the stored value.
    expect(unsealNodeMeta(KEY, seal({ v: 99, date: 'd' }))).toEqual({ v: 1, date: 'd' })
  })

  // --- the three E3 best-effort fall-throughs to { v: 1 } (never a throw) ---

  it('E3: an absent blob yields { v: 1 }', () => {
    expect(unsealNodeMeta(KEY, null)).toEqual({ v: 1 })
    expect(unsealNodeMeta(KEY, undefined)).toEqual({ v: 1 })
    expect(unsealNodeMeta(KEY, '')).toEqual({ v: 1 })
  })

  it('E3: an absent blob short-circuits — the guard skips the decode path entirely (fromB64std uncalled)', () => {
    // Output alone cannot tell the guard from its removal: without `if (!wrappedMeta) return`, a falsy blob still
    // reaches { v: 1 } via fromB64std→throw→catch. What the guard actually DOES is avoid that wasteful decode on
    // the common "no metadata yet" read — so we pin exactly that: fromB64std must not be touched for a falsy blob.
    // (This is what kills the `if (!wrappedMeta)` → `if (false)` mutant, which otherwise calls fromB64std(null).)
    const spy = vi.spyOn(base64, 'fromB64std')
    expect(unsealNodeMeta(KEY, null)).toEqual({ v: 1 })
    expect(unsealNodeMeta(KEY, undefined)).toEqual({ v: 1 })
    expect(unsealNodeMeta(KEY, '')).toEqual({ v: 1 })
    expect(spy).not.toHaveBeenCalled()
  })

  it('E3: a wrong-key or malformed blob yields { v: 1 } (the catch, not a throw)', () => {
    const sealed = sealNodeMeta(KEY, { v: 1, date: 'x' })
    expect(unsealNodeMeta(new Uint8Array(32).fill(6), sealed)).toEqual({ v: 1 }) // wrong key → symUnwrap throws
    expect(unsealNodeMeta(KEY, '@@not-base64@@')).toEqual({ v: 1 }) // malformed → throws
  })

  it('E3: a blob whose JSON is a non-object yields { v: 1 }', () => {
    // A JSON string (not `42`): spreading a string yields indexed props, so `{ ...parsed, v: 1 }` would differ
    // from `{ v: 1 }` — which is what kills a mutant that drops the `typeof === 'object'` guard.
    expect(unsealNodeMeta(KEY, seal('just a string'))).toEqual({ v: 1 })
  })
})
