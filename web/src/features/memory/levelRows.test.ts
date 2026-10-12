// @vitest-environment happy-dom
// Memories' rows of the unified level list (inbox #407), as a MEMBER meets them: the real row factory beside core's real item
// component (`test/levelRows.ts`), the real catalog and real crypto (every date and pin below is sealed and unsealed for
// real). It replaces `MemoriesSection.test.ts` (the former `MemoryBrowse.test.ts`); every case of it is ported, its selectors
// renamed on purpose (a Memory's card is core's `veyq-level-item`, its heart a first-class action, the map the factory's
// `presentation('map')`). The level as a whole, through core's view and the real registry, is pinned by the golden
// (`views/MemoryHomeView.golden.test.ts`).
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'

// SpaceMap wraps Leaflet, which we neither need nor can run here - stub the module out; the component is stubbed too.
vi.mock('leaflet', () => ({ default: {} }))

import { flushPromises } from '@vue/test-utils'
import { memoryRows } from './levelRows'
import { registerMemories } from './register'
import { createRegistry } from '../../core/applications/registry'
import { FOLDER_KIND, type NodeKind } from '../../core/node/kind'
import { MEMORY_KIND } from './kinds'
import { treeStore } from '../../core/node/treeStore'
import type { ViewMode } from '../../shared/state/useViewMode'
import { sealNodeMeta } from '../../core/node/nodeMeta'
import type { MemoryLocation } from './memoryLocation'
import type { TreeNode } from '../../core/node/tree'
import { epochsOf } from '../spaces/epochsTestUtil'
import { activeSpace } from '../../app/activeSpace'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import { t } from '../../test/i18n'
import { formatClosedRange } from './memoryDateRange'
import { treeNode, memberAccessOf, memberTreeOf } from '../../test/models'
import { actionIdsOf, clickAction, mountRows, rowOf, titlesOf, type RowsHarness } from '../../test/levelRows'

// A stub for the Leaflet map - captures the pins it received and re-emits `open` on demand.
const SpaceMapStub = {
  name: 'SpaceMap',
  props: ['pins', 'covers'],
  emits: ['open'],
  template: `<div class="space-map-stub">{{ pins.length }}</div>`,
}

const node = (id: string, kind: NodeKind, name: string): TreeNode => treeNode({
  id, kind, name, parentId: null, orderIndex: 0, epoch: 0, wrappedContentKey: 'eA==', wrappedMeta: null, metaVersion: 0, placementVersion: 0,
})

const SCOPE_ID = '5c09e000-0000-4000-8000-0000000000bb'
const scopeKey = v1.randomBytes(32)
const located = (lat: number, lng: number): MemoryLocation => ({ name: `p-${lat}`, nameSource: 'manual', derivedMode: 'fixed', derived: { lat, lng } })
// Every seal binds its node (ADR 0072), so the map fixtures use canonical UUID ids; the readable label is the name.
const uuidOf = new Map<string, string>()
const uid = (label: string): string => {
  if (!uuidOf.has(label)) uuidOf.set(label, crypto.randomUUID())
  return uuidOf.get(label)!
}

// Every node's content key, by label, minted on first use (a Memory may be built before its folder).
const keyOfLabel = new Map<string, Uint8Array>()
const keyOf = (label: string): Uint8Array => {
  if (!keyOfLabel.has(label)) keyOfLabel.set(label, v1.randomBytes(32))
  return keyOfLabel.get(label)!
}
const wrapFor = (id: string, under: Uint8Array, key: Uint8Array) =>
  b64std(v1.seal({ purpose: 'content-key', scopeId: SCOPE_ID, nodeId: id, epoch: 0, placementVersion: 0 }, under, key))

/** A located Memory whose K_mem is wrapped under its parent's key (`scopeKey` at the root, its folder's key inside
 *  one: ADR 0080) and location sealed under it (mirrors tree.ts), both bound to the node. `id`/`parentId` are
 *  labels, resolved to their UUIDs. */
function locatedMemory(label: string, parentLabel: string | null, loc: MemoryLocation): TreeNode {
  const key = keyOf(label)
  const id = uid(label)
  return treeNode({
    id, kind: MEMORY_KIND, name: label, parentId: parentLabel === null ? null : uid(parentLabel), orderIndex: 0, epoch: 0,
    wrappedContentKey: wrapFor(id, parentLabel === null ? scopeKey : keyOf(parentLabel), key),
    wrappedMeta: sealNodeMeta(key, { scopeId: SCOPE_ID, nodeId: id }, { v: 1, location: loc }),
    metaVersion: 0, placementVersion: 0,
  })
}
/** A root folder whose id is the UUID of `label` (so a located Memory's parent label resolves to it), its key wrapped
 *  under `scopeKey`. */
const folderOf = (label: string, name: string): TreeNode =>
  ({ ...node(label, FOLDER_KIND, name), id: uid(label), wrappedContentKey: wrapFor(uid(label), scopeKey, keyOf(label)) })
const pinNames = (map: { props: (k: string) => unknown }) => (map.props('pins') as { node: TreeNode }[]).map((p) => p.node.name)

let harness: RowsHarness | null = null
/** `pending`: the scope is pending deletion (the member's `access.readonly`). `viewMode`: the mode the level hands in
 *  (inbox #404), the grid by default. */
function mountSection({ pending = false, viewMode = 'grid', folder = null, nodes, epochs = null }: {
  folder?: TreeNode | null; nodes: TreeNode[]; epochs?: ReturnType<typeof epochsOf> | null; pending?: boolean; viewMode?: ViewMode
}) {
  harness = mountRows(memoryRows, {
    folder, nodes, epochs, viewMode, liveTick: undefined,
    access: memberAccessOf({ scopeId: SCOPE_ID, epochs, readonly: pending }),
    tree: memberTreeOf(() => treeStore.nodes, { scopeId: SCOPE_ID, epochs }),
  }, 'grid', { stubs: { SpaceMap: SpaceMapStub } })
  return harness
}
const metaOf = (h: RowsHarness, title: string) => rowOf(h.w, title).find('.veyq-level-item-meta').text()

beforeEach(() => {
  treeStore.nodes = []
  activeSpace.scopeId = SCOPE_ID // the map binds every node's seals to the active scope
})
afterEach(() => {
  harness?.w.unmount()
  harness = null
})

describe('Memories rows', () => {
  it('draws each Memory (decrypted name, the kind noun) and opens it on the primary click', async () => {
    const h = mountSection({ nodes: [node('m1', MEMORY_KIND, 'Solo')] })
    expect(titlesOf(h.w)).toEqual(['Solo'])
    expect(metaOf(h, 'Solo')).toBe(t('memory.kindMemory'))
    await rowOf(h.w, 'Solo').find('button.veyq-level-item-main').trigger('click')
    expect(h.events.open[0]).toMatchObject({ id: 'm1' })
  })

  it('always hands core a cover component: a tile stays a cover tile (the placeholder) while the Memory has no cover yet', () => {
    const h = mountSection({ nodes: [node('m1', MEMORY_KIND, 'Solo')] })
    expect(rowOf(h.w, 'Solo').classes()).toContain('has-cover')
    expect(rowOf(h.w, 'Solo').find('.memory-cover-image-ph').exists()).toBe(true)
  })

  it('draws the cover the level resolved (core\'s store, inbox #411) with its crop - and the placeholder again once it is gone', async () => {
    const h = mountSection({ nodes: [node('m1', MEMORY_KIND, 'Solo')] })
    h.covers.value = new Map([['m1', { url: 'blob:solo', crop: { x: 0, y: 0, width: 0.5, height: 1 } }]])
    await flushPromises()
    const img = rowOf(h.w, 'Solo').find('.memory-cover-image img')
    expect(img.attributes('src')).toBe('blob:solo')
    expect(img.attributes('style')).toContain('200%') // the crop is applied
    expect(rowOf(h.w, 'Solo').find('.memory-cover-image-ph').exists()).toBe(false)
    h.covers.value = new Map() // the cover was removed: the level dropped its image
    await flushPromises()
    expect(rowOf(h.w, 'Solo').find('.memory-cover-image img').exists()).toBe(false)
    expect(rowOf(h.w, 'Solo').find('.memory-cover-image-ph').exists()).toBe(true)
    expect(h.events.ensuredCovers, 'a grid row asks for nothing beyond the level: the level resolved its covers').toEqual([])
  })

  it('the tab\'s empty text is the descriptor\'s plain text; there is no create tile and no trash button here (core\'s toolbar, inbox #385, #406)', () => {
    const r = createRegistry()
    registerMemories(r)
    const tab = r.tabs().find((x) => x.id === MEMORY_KIND)!
    expect(tab.emptyText!({ access: memberAccessOf({ scopeId: SCOPE_ID }), tree: memberTreeOf(() => []), folder: null, epochs: null })).toBe('memory.memoryEmpty')
    const h = mountSection({ nodes: [] })
    expect(h.w.find('.veyq-create-card').exists()).toBe(false)
    expect(h.w.find('button').exists()).toBe(false)
    expect(h.w.text()).not.toContain(t('node.trash.open'))
  })

  it('read-only (a scope pending deletion): the rows stay, with no rename or move (the heart is core\'s row action, not this factory\'s)', async () => {
    const h = mountSection({ nodes: [node('m1', MEMORY_KIND, 'Solo')], pending: true })
    expect(titlesOf(h.w)).toEqual(['Solo'])
    expect(await actionIdsOf(h.w, 'Solo')).toEqual([])
  })

  it('a member\'s row offers rename and move in its menu, and no heart of its own: one heart, core\'s (inbox #409)', async () => {
    const h = mountSection({ nodes: [node('m1', MEMORY_KIND, 'Solo')] })
    expect(await actionIdsOf(h.w, 'Solo')).toEqual(['rename', 'move'])
    await clickAction(h.w, 'Solo', 'rename')
    await clickAction(h.w, 'Solo', 'move')
    expect(h.events.rename.map((n) => n.id)).toEqual(['m1'])
    expect(h.events.move.map((n) => n.id)).toEqual(['m1'])
  })

  it('in map mode asks core to draw the subtree map in place of the list and pins located nodes', () => {
    // folder fA holds a located memory; a second located memory sits at root - at root both are in scope.
    const memInA = locatedMemory('memInA', 'fA', located(1, 1))
    const memRoot = locatedMemory('memRoot', null, located(2, 2))
    treeStore.nodes = [folderOf('fA', 'A'), memInA, memRoot]

    const h = mountSection({ folder: null, nodes: [memRoot], epochs: epochsOf(scopeKey), viewMode: 'map' })

    // the rows are gone, the map is shown
    expect(h.w.find('.veyq-level-item').exists()).toBe(false)
    const map = h.w.findComponent(SpaceMapStub)
    expect(map.exists()).toBe(true)
    expect(pinNames(map).sort()).toEqual(['memInA', 'memRoot'])
  })

  it('inside a folder, dates each Memory under the FOLDER\'s key (ADR 0080) and shows the range in its meta line', () => {
    const dated = (label: string, from: string): TreeNode => {
      const id = uid(label)
      return treeNode({
        id, kind: MEMORY_KIND, name: label, parentId: uid('fD'), orderIndex: 0, epoch: 0,
        wrappedContentKey: wrapFor(id, keyOf('fD'), keyOf(label)),
        wrappedMeta: sealNodeMeta(keyOf(label), { scopeId: SCOPE_ID, nodeId: id }, { v: 1, date: { mode: 'single', from, to: from } }),
        metaVersion: 0, placementVersion: 0,
      })
    }
    const older = dated('older', '2020-01-01')
    const newer = dated('newer', '2024-01-01')
    const fD = folderOf('fD', 'D')
    treeStore.nodes = [fD, older, newer]

    const h = mountSection({ folder: fD, nodes: [older, newer], epochs: epochsOf(scopeKey) })
    expect(metaOf(h, 'newer')).toBe(`${t('memory.kindMemory')} · ${formatClosedRange('2024-01-01', '2024-01-01', 'en')}`)
    expect(metaOf(h, 'older')).toBe(`${t('memory.kindMemory')} · ${formatClosedRange('2020-01-01', '2020-01-01', 'en')}`)
    // core orders the list by name: the date no longer decides (inbox #407)
    expect(titlesOf(h.w)).toEqual(['newer', 'older'])
  })

  it('scopes the map to the current folder subtree', () => {
    const memInA = locatedMemory('memInA', 'fA', located(1, 1))
    const memInB = locatedMemory('memInB', 'fB', located(2, 2))
    const fA = folderOf('fA', 'A')
    treeStore.nodes = [fA, folderOf('fB', 'B'), memInA, memInB]

    const h = mountSection({ folder: fA, nodes: [], epochs: epochsOf(scopeKey), viewMode: 'map' })
    expect(pinNames(h.w.findComponent(SpaceMapStub))).toEqual(['memInA'])
  })

  it('a pin open resolves to its TreeNode and asks the host to open it', async () => {
    const memRoot = locatedMemory('memRoot', null, located(2, 2))
    treeStore.nodes = [memRoot]

    const h = mountSection({ folder: null, nodes: [memRoot], epochs: epochsOf(scopeKey), viewMode: 'map' })
    h.w.findComponent(SpaceMapStub).vm.$emit('open', uid('memRoot'))
    await flushPromises()

    expect(h.events.open[0]).toMatchObject({ id: uid('memRoot') })
  })

  it('grid mode draws the rows and has no map presentation', () => {
    const h = mountSection({ nodes: [node('m1', MEMORY_KIND, 'Solo')], viewMode: 'grid' })
    expect(h.w.find('.veyq-level-item').exists()).toBe(true)
    expect(h.w.findComponent(SpaceMapStub).exists()).toBe(false)
    expect(h.source().presentation!('grid')).toBeNull()
    expect(h.source().presentation!('list')).toBeNull()
  })

  // inbox #404: the level decides the mode; the stored preference is not the factory's to read.
  it('draws the mode the level hands it and follows a change of it', async () => {
    const h = mountSection({ nodes: [node('m1', MEMORY_KIND, 'Solo')], viewMode: 'grid' })
    expect(h.w.find('.veyq-level-item').exists()).toBe(true)
    expect(h.w.findComponent(SpaceMapStub).exists()).toBe(false)
    await h.setCtx({ viewMode: 'map' })
    expect(h.w.find('.veyq-level-item').exists()).toBe(false)
    expect(h.w.findComponent(SpaceMapStub).exists()).toBe(true)
  })

  it('serves a node of another kind with no row (core shows the unsupported row)', () => {
    const h = mountSection({ nodes: [] })
    expect(h.source().row(node('x', 'FILE', 'a.txt'))).toBeNull()
  })
})
