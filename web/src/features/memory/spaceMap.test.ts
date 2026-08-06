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
import * as v1 from '../../shared/crypto/v1'
import { b64std } from '../../shared/base64'

// The K_mem wrap context, kept byte-identical to tree.ts's KMEM_CTX so nodeContentKey can re-open what we seal here.
const KMEM_CTX = 'content-key'
const scopeKey = v1.randomBytes(32)

const located = (lat: number, lng: number): MemoryLocation => ({
  name: `place-${lat}`,
  nameSource: 'manual',
  derivedMode: 'fixed',
  derived: { lat, lng },
})
// A pending auto place: armed at creation, no geotagged media yet → no `derived` position.
const pendingAuto: MemoryLocation = { name: '', nameSource: 'geocoded', derivedMode: 'auto' }

/** Build a content node (MEMORY/CHAPTER): mint a K_mem, wrap it under `parentKey`, seal the location under it. */
function content(
  id: string,
  kind: NodeKind,
  parentId: string | null,
  parentKey: Uint8Array,
  opts: { location?: MemoryLocation; wrapUnder?: Uint8Array } = {},
): { node: TreeNode; key: Uint8Array } {
  const key = v1.randomBytes(32)
  const wrappedContentKey = b64std(v1.symWrap(opts.wrapUnder ?? parentKey, key, KMEM_CTX, v1.randomBytes(12)))
  const wrappedMeta = opts.location ? sealNodeMeta(key, { v: 1, location: opts.location }) : null
  return { node: { id, kind, parentId, orderIndex: 0, epoch: 0, name: id, wrappedContentKey, wrappedMeta }, key }
}

const folder = (id: string, parentId: string | null = null): TreeNode => ({
  id, kind: NodeKind.Folder, parentId, orderIndex: 0, epoch: 0, name: id, wrappedContentKey: null, wrappedMeta: null,
})

const ids = (pins: ReturnType<typeof collectLocatedPins>) => pins.map((p) => p.node.id).sort()

describe('collectLocatedPins', () => {
  // fA ─ memA (located) ─ chapA (located, nested)
  // fB ─ memB (located)
  // memRoot (located, at root) · memPending (auto, no derived) · memLocked (key wrapped under a wrong parent)
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
  const tree: TreeNode[] = [fA, fB, memA.node, chapA.node, memB.node, memRoot.node, memPending.node, memLocked.node]

  it('root (null) pins every located MEMORY/CHAPTER across the whole tree', () => {
    expect(ids(collectLocatedPins(epochsOf(scopeKey), tree, null))).toEqual(['chapA', 'memA', 'memB', 'memRoot'])
  })

  it('carries each pin its unsealed location with the derived position', () => {
    const pins = collectLocatedPins(epochsOf(scopeKey), tree, null)
    const pin = pins.find((p) => p.node.id === 'chapA')
    expect(pin?.location.derived).toEqual({ lat: 2, lng: 2 })
    expect(pin?.location.name).toBe('place-2')
  })

  it('scopes to a folder subtree recursively — a sibling folder is excluded', () => {
    // rooted at fA: its Memory + nested chapter; memB (under fB) and the root nodes are out
    expect(ids(collectLocatedPins(epochsOf(scopeKey), tree, 'fA'))).toEqual(['chapA', 'memA'])
    expect(ids(collectLocatedPins(epochsOf(scopeKey), tree, 'fB'))).toEqual(['memB'])
  })

  it('skips a pending auto place (no derived ⇒ no pin)', () => {
    expect(ids(collectLocatedPins(epochsOf(scopeKey), tree, null))).not.toContain('memPending')
  })

  it('skips a node whose key cannot be chained (best-effort, never a broken map)', () => {
    expect(ids(collectLocatedPins(epochsOf(scopeKey), tree, null))).not.toContain('memLocked')
  })
})

describe('clusterPins', () => {
  // A trivial linear projection: 1 degree = 1 pixel, so pixel distance == degree distance (Leaflet-free).
  const project = (lat: number, lng: number) => ({ x: lng, y: lat })
  const pin = (id: string, lat: number, lng: number): LocatedPin => ({
    node: { id, kind: NodeKind.Memory, name: id, parentId: null, orderIndex: 0, epoch: 0, wrappedContentKey: null, wrappedMeta: null },
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
    node: { id, kind: NodeKind.Memory, name: id, parentId: null, orderIndex: 0, epoch: 0, wrappedContentKey: null, wrappedMeta: null },
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
