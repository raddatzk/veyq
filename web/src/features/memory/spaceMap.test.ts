import { describe, it, expect, vi } from 'vitest'

// spaceMap pulls in memoryLocation/tree, which import the generated API client — stub it (no network in a model test).
vi.mock('../../api/client', () => ({
  contentApi: vi.fn(),
  loginApi: vi.fn(),
  tokenApi: vi.fn(),
  sessionApi: vi.fn(),
}))

import { collectLocatedPins, clusterPins, sortPinsByStartDate, type LocatedPin } from './spaceMap'
import type { MemoryDate } from './memoryDate'
import { sealNodeMeta } from './nodeMeta'
import type { MemoryLocation } from './memoryLocation'
import { NodeKind } from '../../api/generated/content'
import type { TreeNode } from './tree'
import { epochsOf } from '../spaces/epochsTestUtil'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'

const SCOPE_ID = '5c09e000-0000-4000-8000-0000000000aa'
const scopeKey = v1.randomBytes(32)
// Every seal binds its node (ADR 0072), so node ids are canonical UUIDs; the readable label rides in `name`.
const uuidOf = new Map<string, string>()
const uid = (label: string): string => {
  if (!uuidOf.has(label)) uuidOf.set(label, crypto.randomUUID())
  return uuidOf.get(label)!
}

const located = (lat: number, lng: number): MemoryLocation => ({
  name: `place-${lat}`,
  nameSource: 'manual',
  derivedMode: 'fixed',
  derived: { lat, lng },
})
// A pending auto place: armed at creation, no geotagged media yet → no `derived` position.
const pendingAuto: MemoryLocation = { name: '', nameSource: 'geocoded', derivedMode: 'auto' }

/** Build a content node (MEMORY/CHAPTER): mint a K_mem, wrap it under `parentKey`, seal the location under it —
 *  each bound to the node (the same contexts tree.ts / nodeMeta.ts open with). `metaFor` seals the meta as if it
 *  belonged to another node (a server moving one node's wrappedMeta onto another's row). */
function content(
  label: string,
  kind: NodeKind,
  parentLabel: string | null,
  parentKey: Uint8Array,
  opts: { location?: MemoryLocation; wrapUnder?: Uint8Array; metaFor?: string } = {},
): { node: TreeNode; key: Uint8Array } {
  const id = uid(label)
  const key = v1.randomBytes(32)
  const wrappedContentKey = b64std(
    v1.seal({ purpose: 'content-key', scopeId: SCOPE_ID, nodeId: id, epoch: 0 }, opts.wrapUnder ?? parentKey, key),
  )
  const metaNode = { scopeId: SCOPE_ID, nodeId: opts.metaFor ? uid(opts.metaFor) : id }
  const wrappedMeta = opts.location ? sealNodeMeta(key, metaNode, { v: 1, location: opts.location }) : null
  const parentId = parentLabel === null ? null : uid(parentLabel)
  return { node: { id, kind, parentId, orderIndex: 0, epoch: 0, name: label, wrappedContentKey, wrappedMeta, metaVersion: 0 }, key }
}

const folder = (label: string, parentLabel: string | null = null): TreeNode => ({
  id: uid(label), kind: NodeKind.Folder, parentId: parentLabel === null ? null : uid(parentLabel), orderIndex: 0,
  epoch: 0, name: label, wrappedContentKey: null, wrappedMeta: null, metaVersion: 0,
})

const ids = (pins: ReturnType<typeof collectLocatedPins>) => pins.map((p) => p.node.name).sort()

describe('collectLocatedPins', () => {
  // fA ─ memA (located) ─ chapA (located, nested)
  // fB ─ memB (located)
  // memRoot (located, at root) · memPending (auto, no derived) · memLocked (key wrapped under a wrong parent)
  // memMoved (its located meta was sealed for another node — refused, never shown at that node's place)
  const fA = folder('fA')
  const fB = folder('fB')
  const memA = content('memA', NodeKind.Memory, 'fA', scopeKey, { location: located(1, 1) })
  const chapA = content('chapA', NodeKind.Chapter, 'memA', memA.key, { location: located(2, 2) })
  const memB = content('memB', NodeKind.Memory, 'fB', scopeKey, { location: located(3, 3) })
  const memRoot = content('memRoot', NodeKind.Memory, null, scopeKey, { location: located(4, 4) })
  const memPending = content('memPending', NodeKind.Memory, null, scopeKey, { location: pendingAuto })
  const memLocked = content('memLocked', NodeKind.Memory, null, scopeKey, {
    location: located(9, 9),
    wrapUnder: v1.randomBytes(32), // K_mem sealed under a key we don't have → nodeContentKey can't chain it
  })
  const memMoved = content('memMoved', NodeKind.Memory, null, scopeKey, { location: located(7, 7), metaFor: 'memRoot' })
  const tree: TreeNode[] = [
    fA, fB, memA.node, chapA.node, memB.node, memRoot.node, memPending.node, memLocked.node, memMoved.node,
  ]

  it('root (null) pins every located MEMORY/CHAPTER across the whole tree', () => {
    expect(ids(collectLocatedPins(SCOPE_ID, epochsOf(scopeKey), tree, null))).toEqual(['chapA', 'memA', 'memB', 'memRoot'])
  })

  it('carries each pin its unsealed location with the derived position', () => {
    const pins = collectLocatedPins(SCOPE_ID, epochsOf(scopeKey), tree, null)
    const pin = pins.find((p) => p.node.id === uid('chapA'))
    expect(pin?.location.derived).toEqual({ lat: 2, lng: 2 })
    expect(pin?.location.name).toBe('place-2')
  })

  it('scopes to a folder subtree recursively — a sibling folder is excluded', () => {
    // rooted at fA: its Memory + nested chapter; memB (under fB) and the root nodes are out
    expect(ids(collectLocatedPins(SCOPE_ID, epochsOf(scopeKey), tree, uid('fA')))).toEqual(['chapA', 'memA'])
    expect(ids(collectLocatedPins(SCOPE_ID, epochsOf(scopeKey), tree, uid('fB')))).toEqual(['memB'])
  })

  it('skips a pending auto place (no derived ⇒ no pin)', () => {
    expect(ids(collectLocatedPins(SCOPE_ID, epochsOf(scopeKey), tree, null))).not.toContain('memPending')
  })

  it('skips a node whose key cannot be chained (best-effort, never a broken map)', () => {
    expect(ids(collectLocatedPins(SCOPE_ID, epochsOf(scopeKey), tree, null))).not.toContain('memLocked')
  })

  it('skips a node whose location was sealed for another node (bound meta, ADR 0072)', () => {
    expect(ids(collectLocatedPins(SCOPE_ID, epochsOf(scopeKey), tree, null))).not.toContain('memMoved')
  })
})

describe('clusterPins', () => {
  // A trivial linear projection: 1 degree = 1 pixel, so pixel distance == degree distance (Leaflet-free).
  const project = (lat: number, lng: number) => ({ x: lng, y: lat })
  const pin = (id: string, lat: number, lng: number): LocatedPin => ({
    node: { id, kind: NodeKind.Memory, name: id, parentId: null, orderIndex: 0, epoch: 0, wrappedContentKey: null, wrappedMeta: null, metaVersion: 0 },
    location: { name: id, nameSource: 'manual', derivedMode: 'fixed', derived: { lat, lng } },
    date: null,
  })
  const keys = (cs: ReturnType<typeof clusterPins>) => cs.map((c) => c.pins.map((p) => p.node.id).sort().join('|')).sort()

  it('keeps far-apart pins as separate one-member clusters', () => {
    const cs = clusterPins([pin('a', 0, 0), pin('b', 50, 50)], project, 10)
    expect(keys(cs)).toEqual(['a', 'b'])
  })

  it('groups pins within the pixel radius and leaves distant ones out', () => {
    // a & b are 3px apart (grouped at radius 10); c is far
    const cs = clusterPins([pin('a', 0, 0), pin('b', 2, 2), pin('c', 40, 40)], project, 10)
    expect(keys(cs)).toEqual(['a|b', 'c'])
  })

  it('places a cluster at the centroid of its members', () => {
    const [c] = clusterPins([pin('a', 0, 0), pin('b', 2, 4)], project, 10)
    expect(c).toMatchObject({ lat: 1, lng: 2 })
    expect(c.pins).toHaveLength(2)
  })

  it('groups against the first-seen anchor (stable), not transitively', () => {
    // a at 0; b at 8 (within 10 of a → joins a); c at 16 (18px from a's anchor → NOT in a's group, its own)
    const cs = clusterPins([pin('a', 0, 0), pin('b', 0, 8), pin('c', 0, 16)], project, 10)
    expect(keys(cs)).toEqual(['a|b', 'c'])
  })
})

describe('sortPinsByStartDate', () => {
  const dated = (id: string, from?: string): LocatedPin => ({
    node: { id, kind: NodeKind.Memory, name: id, parentId: null, orderIndex: 0, epoch: 0, wrappedContentKey: null, wrappedMeta: null, metaVersion: 0 },
    location: { name: id, nameSource: 'manual', derivedMode: 'fixed', derived: { lat: 0, lng: 0 } },
    date: from ? ({ mode: 'single', from, to: from } as MemoryDate) : null,
  })
  const order = (pins: LocatedPin[]) => sortPinsByStartDate(pins).map((p) => p.node.id)

  it('orders by start day newest-first, undated last, stable within a group', () => {
    const pins = [dated('u1'), dated('may', '2026-05-01'), dated('jan', '2026-01-01'), dated('u2')]
    expect(order(pins)).toEqual(['may', 'jan', 'u1', 'u2'])
  })

  it('does not mutate the input', () => {
    const pins = [dated('a', '2026-01-01'), dated('b', '2026-02-01')]
    sortPinsByStartDate(pins)
    expect(pins.map((p) => p.node.id)).toEqual(['a', 'b'])
  })
})
