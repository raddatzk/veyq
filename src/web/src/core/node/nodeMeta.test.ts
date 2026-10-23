import { describe, it, expect, vi, afterEach } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import * as base64 from '@veyq/crypto/base64'
import { editField, editParticipantsOf, nodeMetaState, openNodeMetaForWrite, sealNodeMeta, unsealNodeMeta } from './nodeMeta'
import { MetaConflictError, MetaUnreadableError } from './sealedMetaWrite'
import { treeNode } from '../../test/models'

// Assertion-strength KAT for the wrappedMeta envelope (cr-0177, ADR 0045). nodeMeta was untested: the seal
// context 'node-meta' was unpinned, and unsealNodeMeta's best-effort (E3) fall-throughs to { v: 1 } had no oracle.

const KEY = new Uint8Array(32).fill(5)
const NODE = { scopeId: '5c09e000-0000-4000-8000-00000000000a', nodeId: '5c09e000-0000-4000-8000-00000000000b' }
const OTHER_NODE = { ...NODE, nodeId: '5c09e000-0000-4000-8000-00000000000c' }
const seal = (meta: unknown) =>
  b64std(v1.sealField({ purpose: 'node-meta', ...NODE }, KEY, new TextEncoder().encode(JSON.stringify(meta))))

afterEach(() => vi.restoreAllMocks())

describe('nodeMeta seal/unseal envelope (cr-0177, ADR 0045)', () => {
  it('pins the seal context (purpose "node-meta", bound to scope + node) against a HARDCODED literal', () => {
    // sealNodeMeta builds its context in source; open with a raw v1.open + the literal context. If the source
    // purpose or a bound field were mutated, v1.open throws OpenRefused → this reds. A roundtrip through
    // unsealNodeMeta would be blind (it shares the mutated context AND swallows the failure into { v: 1 }).
    const sealed = sealNodeMeta(KEY, NODE, { v: 1, date: { on: '2026-07-20' } })
    const ctx = { purpose: 'node-meta', scopeId: NODE.scopeId, nodeId: NODE.nodeId } as const
    const json = new TextDecoder().decode(v1.openField(ctx, KEY, fromB64std(sealed)))
    expect(JSON.parse(json)).toEqual({ v: 1, date: { on: '2026-07-20' } })
    // ...and the node is part of it: the same blob addressed as another node is refused
    expect(() => v1.openField({ ...ctx, nodeId: OTHER_NODE.nodeId }, KEY, fromB64std(sealed))).toThrow(v1.OpenRefused)
  })

  it('E3: a blob sealed for another node reads as { v: 1 } (a server moving wrappedMeta gains nothing)', () => {
    const sealed = sealNodeMeta(KEY, OTHER_NODE, { v: 1, date: 'x' })
    expect(unsealNodeMeta(KEY, OTHER_NODE, sealed)).toEqual({ v: 1, date: 'x' })
    expect(unsealNodeMeta(KEY, NODE, sealed)).toEqual({ v: 1 })
  })

  it('round-trips a sealed blob, preserving unknown sibling fields', () => {
    const sealed = sealNodeMeta(KEY, NODE, { v: 1, location: { name: 'Rom' }, cover: { mediaId: 'x' } })
    expect(unsealNodeMeta(KEY, NODE, sealed)).toEqual({ v: 1, location: { name: 'Rom' }, cover: { mediaId: 'x' } })
  })

  it('forces v to 1 even when the sealed blob claims another version', () => {
    // The `{ ...parsed, v: 1 }` line must normalize v back to 1 regardless of the stored value.
    expect(unsealNodeMeta(KEY, NODE, seal({ v: 99, date: 'd' }))).toEqual({ v: 1, date: 'd' })
  })

  // --- the three E3 best-effort fall-throughs to { v: 1 } (never a throw) ---

  it('E3: an absent blob yields { v: 1 }', () => {
    expect(unsealNodeMeta(KEY, NODE, null)).toEqual({ v: 1 })
    expect(unsealNodeMeta(KEY, NODE, undefined)).toEqual({ v: 1 })
    expect(unsealNodeMeta(KEY, NODE, '')).toEqual({ v: 1 })
  })

  it('E3: an absent blob short-circuits — the guard skips the decode path entirely (fromB64std uncalled)', () => {
    // Output alone cannot tell the guard from its removal: without `if (!wrappedMeta) return`, a falsy blob still
    // reaches { v: 1 } via fromB64std→throw→catch. What the guard actually DOES is avoid that wasteful decode on
    // the common "no metadata yet" read — so we pin exactly that: fromB64std must not be touched for a falsy blob.
    // (This is what kills the `if (!wrappedMeta)` → `if (false)` mutant, which otherwise calls fromB64std(null).)
    const spy = vi.spyOn(base64, 'fromB64std')
    expect(unsealNodeMeta(KEY, NODE, null)).toEqual({ v: 1 })
    expect(unsealNodeMeta(KEY, NODE, undefined)).toEqual({ v: 1 })
    expect(unsealNodeMeta(KEY, NODE, '')).toEqual({ v: 1 })
    expect(spy).not.toHaveBeenCalled()
  })

  it('E3: a wrong-key or malformed blob yields { v: 1 } (the catch, not a throw)', () => {
    const sealed = sealNodeMeta(KEY, NODE, { v: 1, date: 'x' })
    expect(unsealNodeMeta(new Uint8Array(32).fill(6), NODE, sealed)).toEqual({ v: 1 }) // wrong key → open refuses
    expect(unsealNodeMeta(KEY, NODE, '@@not-base64@@')).toEqual({ v: 1 }) // malformed → throws
  })

  it('E3: a blob whose JSON is a non-object yields { v: 1 }', () => {
    // A JSON string (not `42`): spreading a string yields indexed props, so `{ ...parsed, v: 1 }` would differ
    // from `{ v: 1 }` — which is what kills a mutant that drops the `typeof === 'object'` guard.
    expect(unsealNodeMeta(KEY, NODE, seal('just a string'))).toEqual({ v: 1 })
  })
})

// --- the WRITE side (ADR 0077): never rebuild a present blob from empty, and an editor's own-field conflict ---

describe('openNodeMetaForWrite — only an ABSENT blob is fresh (ADR 0077 point 4, inbox #273)', () => {
  it('an absent blob is a fresh { v: 1 }', () => {
    expect(openNodeMetaForWrite(KEY, NODE, null)).toEqual({ v: 1 })
    expect(openNodeMetaForWrite(KEY, NODE, '')).toEqual({ v: 1 })
  })

  it('a blob that opens comes back whole — unknown siblings kept, v forced to 1', () => {
    expect(openNodeMetaForWrite(KEY, NODE, seal({ v: 99, date: 'd', future: { x: 1 } }))).toEqual({ v: 1, date: 'd', future: { x: 1 } })
  })

  it('a present blob that does not open, or does not parse into an object, is REFUSED — never { v: 1 }', () => {
    const refused = (wrappedMeta: string) => () => openNodeMetaForWrite(KEY, NODE, wrappedMeta)
    expect(refused(sealNodeMeta(new Uint8Array(32).fill(6), NODE, { v: 1, date: 'x' }))).toThrow(MetaUnreadableError) // wrong key
    expect(refused(sealNodeMeta(KEY, OTHER_NODE, { v: 1, date: 'x' }))).toThrow(MetaUnreadableError) // another node's
    expect(refused('@@not-base64@@')).toThrow(MetaUnreadableError) // malformed
    expect(refused(seal('just a string'))).toThrow(MetaUnreadableError) // not an object
    expect(refused(seal(null))).toThrow(MetaUnreadableError) // JSON null — typeof null is 'object'
    expect(refused(seal(['a', 'b']))).toThrow(MetaUnreadableError) // an array is an object too
  })
})

describe('nodeMetaState', () => {
  it('is the blob, the revision and the node\'s epoch the tree holds, nothing else', () => {
    expect(nodeMetaState(treeNode({ wrappedMeta: 'YQ==', metaRevision: 7, epoch: 2, id: 'n' }))).toEqual({ wrappedMeta: 'YQ==', metaRevision: 7, nodeEpoch: 2 })
  })
})

describe('editField — an editor re-applies only its own field, and refuses when THAT field moved (ADR 0077 point 3)', () => {
  const state = { wrappedMeta: 'c3RhdGU=', metaRevision: 3 }
  const first = { retry: false, state }
  const again = { retry: true, state }

  it('sets the field, keeping every sibling', () => {
    expect(editField('date', 'new', 'old')({ v: 1, date: 'old', location: 'L' }, first)).toEqual({ v: 1, date: 'new', location: 'L' })
  })

  it('drops the field for null and for undefined — the KEY goes, not only its value', () => {
    // `toEqual` treats `{ cover: undefined }` as `{}`, so the undefined case asserts on the keys: a field set to
    // undefined instead of deleted is the mutant this pins.
    expect(Object.keys(editField('cover', null, 'c')({ v: 1, cover: 'c', date: 'D' }, first))).toEqual(['v', 'date'])
    expect(Object.keys(editField('cover', undefined, 'c')({ v: 1, cover: 'c', date: 'D' }, first))).toEqual(['v', 'date'])
  })

  it('on the first attempt never checks — the base IS what the editor opened', () => {
    expect(editField('date', 'new', 'opened')({ v: 1, date: 'something else' }, first)).toEqual({ v: 1, date: 'new' })
  })

  it('on a re-run, a sibling moved → merges; its OWN field moved → MetaConflictError carrying the state', () => {
    expect(editField('date', 'new', { a: 1, b: 2 })({ v: 1, date: { b: 2, a: 1 }, location: 'peer' }, again))
      .toEqual({ v: 1, date: 'new', location: 'peer' }) // same field, other key order: no conflict
    let thrown: unknown
    try {
      editField('date', 'new', 'opened')({ v: 1, date: 'peer' }, again)
    } catch (e) {
      thrown = e
    }
    expect(thrown).toBeInstanceOf(MetaConflictError)
    expect((thrown as MetaConflictError).current).toEqual(state)
  })

  it('surface = false re-applies even over its own moved field (the cover after a blob operation, D9)', () => {
    expect(editField('cover', 'mine', 'opened', false)({ v: 1, cover: 'peer' }, again)).toEqual({ v: 1, cover: 'mine' })
  })
})

describe('editParticipantsOf — the participants editor merges instead of surfacing a conflict (ADR 0083, ADR 0077)', () => {
  const ctx = { retry: true, state: { wrappedMeta: 'c3RhdGU=', metaRevision: 4 } }

  it('hands merge only the stored ids that are non-empty strings, deduplicated, in stored order', () => {
    const merge = vi.fn((stored: string[]) => [...stored, 'c'])
    const out = editParticipantsOf(merge)({ v: 1, participants: { userIds: ['a', '', 7, null, 'a', 'b'] } }, ctx)
    expect(merge).toHaveBeenCalledWith(['a', 'b'])
    expect(out).toEqual({ v: 1, participants: { userIds: ['a', 'b', 'c'] } })
  })

  it('writes nothing (null) when merge returns the stored list unchanged', () => {
    expect(editParticipantsOf((stored) => stored)({ v: 1, participants: { userIds: ['a', 'b'] } }, ctx)).toBeNull()
  })

  it('an empty result drops the sub-object KEY, keeping every sibling', () => {
    const out = editParticipantsOf(() => [])({ v: 1, date: 'D', participants: { userIds: ['a'] } }, ctx)
    expect(out && Object.keys(out)).toEqual(['v', 'date'])
  })

  it('keeps an unknown field inside the sub-object and replaces only userIds', () => {
    expect(editParticipantsOf(() => ['b'])({ v: 1, participants: { userIds: ['a'], future: 'kept' } }, ctx))
      .toEqual({ v: 1, participants: { userIds: ['b'], future: 'kept' } })
  })

  it.each([
    ['absent', undefined],
    ['null', null],
    ['a string', 'xy'],
    ['a number', 5],
    ['an object without userIds', {}],
    ['userIds not an array', { userIds: 'a' }],
  ])('participants %s reads as no ids, and a write starts a clean sub-object', (_label, participants) => {
    const merge = vi.fn(() => ['u'])
    const meta = participants === undefined ? { v: 1 } : { v: 1, participants }
    // A string's characters must not be spread into the sub-object as `0`/`1` keys.
    const expected = participants !== null && typeof participants === 'object' ? { ...participants, userIds: ['u'] } : { userIds: ['u'] }
    expect(editParticipantsOf(merge)(meta, ctx)).toEqual({ v: 1, participants: expected })
    expect(merge).toHaveBeenCalledWith([])
  })

  it('participants as an array (a foreign client\'s JSON) reads as no ids, and a write starts a clean sub-object', () => {
    // An array is a non-null object: its elements must not be spread into the sub-object as `0`/`1` keys (inbox #473).
    const merge = vi.fn(() => ['u'])
    const out = editParticipantsOf(merge)({ v: 1, participants: ['x', 'y'] }, ctx)
    expect(merge).toHaveBeenCalledWith([])
    expect(out).toEqual({ v: 1, participants: { userIds: ['u'] } })
    expect(out && Object.keys(out.participants as object)).toEqual(['userIds'])
  })
})
