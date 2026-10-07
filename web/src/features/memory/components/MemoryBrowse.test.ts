// @vitest-environment happy-dom
import { describe, it, expect, beforeEach } from 'vitest'

// SpaceMap wraps Leaflet, which we neither need nor can run here — stub the module out; the component is stubbed too.
import { vi } from 'vitest'
vi.mock('leaflet', () => ({ default: {} }))

import { mount } from '@vue/test-utils'
import MemoryBrowse from './MemoryBrowse.vue'
import MemoryCard from './MemoryCard.vue'
import { FOLDER_KIND, type NodeKind } from '../../../core/node/kind'
import { MEMORY_KIND } from '../kinds'
import { treeStore } from '../../../core/node/treeStore'
import { setViewMode } from '../../../shared/state/useViewMode'
import { sealNodeMeta } from '../nodeMeta'
import type { MemoryLocation } from '../memoryLocation'
import type { TreeNode } from '../../../core/node/tree'
import { epochsOf } from '../../spaces/epochsTestUtil'
import { activeSpace } from '../../../app/activeSpace'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import { realI18n, t } from '../../../test/i18n'

// A stub for the Leaflet map — captures the pins it received and re-emits `open` on demand.
const SpaceMapStub = {
  props: ['pins'],
  emits: ['open'],
  template: `<div class="space-map-stub">{{ pins.length }}</div>`,
}

const node = (id: string, kind: NodeKind, name: string): TreeNode => ({
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
  return {
    id, kind: MEMORY_KIND, name: label, parentId: parentLabel === null ? null : uid(parentLabel), orderIndex: 0, epoch: 0,
    wrappedContentKey: wrapFor(id, parentLabel === null ? scopeKey : keyOf(parentLabel), key),
    wrappedMeta: sealNodeMeta(key, { scopeId: SCOPE_ID, nodeId: id }, { v: 1, location: loc }),
    metaVersion: 0, placementVersion: 0,
  }
}
/** A root folder whose id is the UUID of `label` (so a located Memory's parent label resolves to it), its key wrapped
 *  under `scopeKey`. */
const folderOf = (label: string, name: string): TreeNode =>
  ({ ...node(label, FOLDER_KIND, name), id: uid(label), wrappedContentKey: wrapFor(uid(label), scopeKey, keyOf(label)) })
const pinNames = (map: { props: (k: string) => unknown }) => (map.props('pins') as { node: TreeNode }[]).map((p) => p.node.name)

const mountBrowse = (props: Partial<InstanceType<typeof MemoryBrowse>['$props']> & { folders: TreeNode[]; memories: TreeNode[] }) =>
  mount(MemoryBrowse, { props, global: { plugins: [realI18n()], directives: { tooltip: {} }, stubs: { SpaceMap: SpaceMapStub } } })

beforeEach(() => {
  setViewMode('grid')
  treeStore.nodes = []
  activeSpace.scopeId = SCOPE_ID // the map binds every node's seals to the active scope
})

describe('MemoryBrowse', () => {
  it('renders the level (decrypted folder + Memory names) and emits drill/open on click', async () => {
    const folders = [node('f1', FOLDER_KIND, 'Trips')]
    const memories = [node('m1', MEMORY_KIND, 'Solo')]
    const wrapper = mountBrowse({ folders, memories })

    expect(wrapper.text()).toContain('Solo')
    expect(wrapper.text()).toContain('Trips')

    await wrapper.findAll('button').find((b) => b.text().includes('Solo'))!.trigger('click')
    expect(wrapper.emitted('openMemory')?.[0]?.[0]).toMatchObject({ id: 'm1' })

    await wrapper.findAll('button').find((b) => b.text().includes('Trips'))!.trigger('click')
    expect(wrapper.emitted('openFolder')?.[0]?.[0]).toMatchObject({ id: 'f1' })
  })

  it('shows the empty states when a level has no folders/Memories', () => {
    const wrapper = mountBrowse({ folders: [], memories: [] })
    expect(wrapper.text()).toContain(t('memory.memoryEmpty'))
    expect(wrapper.text()).toContain(t('memory.folderEmpty'))
  })

  it('in map mode replaces the card grids with the subtree map and pins located nodes', () => {
    // folder fA holds a located memory; a second located memory sits at root — at root both are in scope.
    const memInA = locatedMemory('memInA', 'fA', located(1, 1))
    const memRoot = locatedMemory('memRoot', null, located(2, 2))
    treeStore.nodes = [folderOf('fA', 'A'), memInA, memRoot]
    setViewMode('map')

    const wrapper = mountBrowse({ folder: null, folders: [], memories: [memRoot], epochs: epochsOf(scopeKey) })

    // the card grids are gone, the map is shown
    expect(wrapper.find('.memory-card-grid').exists()).toBe(false)
    const map = wrapper.findComponent(SpaceMapStub)
    expect(map.exists()).toBe(true)
    expect(pinNames(map).sort()).toEqual(['memInA', 'memRoot'])
  })

  it('inside a folder, dates each Memory under the FOLDER\'s key (ADR 0080) and orders the grid by them', () => {
    const dated = (label: string, from: string): TreeNode => {
      const id = uid(label)
      return {
        id, kind: MEMORY_KIND, name: label, parentId: uid('fD'), orderIndex: 0, epoch: 0,
        wrappedContentKey: wrapFor(id, keyOf('fD'), keyOf(label)),
        wrappedMeta: sealNodeMeta(keyOf(label), { scopeId: SCOPE_ID, nodeId: id }, { v: 1, date: { mode: 'single', from, to: from } }),
        metaVersion: 0, placementVersion: 0,
      }
    }
    const older = dated('older', '2020-01-01')
    const newer = dated('newer', '2024-01-01')
    const fD = folderOf('fD', 'D')
    treeStore.nodes = [fD, older, newer]

    const wrapper = mountBrowse({ folder: fD, folders: [], memories: [older, newer], epochs: epochsOf(scopeKey) })
    const cards = wrapper.findAllComponents(MemoryCard)
    expect(cards.map((c) => (c.props('memory') as TreeNode).name)).toEqual(['newer', 'older']) // newest first
    expect(cards.map((c) => (c.props('date') as { from?: string } | null)?.from)).toEqual(['2024-01-01', '2020-01-01'])
  })

  it('scopes the map to the current folder subtree', () => {
    const memInA = locatedMemory('memInA', 'fA', located(1, 1))
    const memInB = locatedMemory('memInB', 'fB', located(2, 2))
    const fA = folderOf('fA', 'A')
    treeStore.nodes = [fA, folderOf('fB', 'B'), memInA, memInB]
    setViewMode('map')

    const wrapper = mountBrowse({ folder: fA, folders: [], memories: [], epochs: epochsOf(scopeKey) })
    const map = wrapper.findComponent(SpaceMapStub)
    expect(pinNames(map)).toEqual(['memInA'])
  })

  it('a pin open resolves to its TreeNode and emits openMemory', async () => {
    const memRoot = locatedMemory('memRoot', null, located(2, 2))
    treeStore.nodes = [memRoot]
    setViewMode('map')

    const wrapper = mountBrowse({ folder: null, folders: [], memories: [memRoot], epochs: epochsOf(scopeKey) })
    wrapper.findComponent(SpaceMapStub).vm.$emit('open', uid('memRoot'))
    await wrapper.vm.$nextTick()

    expect(wrapper.emitted('openMemory')?.[0]?.[0]).toMatchObject({ id: uid('memRoot') })
  })

  it('grid mode still renders the card grids (map absent)', () => {
    setViewMode('grid')
    const wrapper = mountBrowse({ folders: [], memories: [node('m1', MEMORY_KIND, 'Solo')] })
    expect(wrapper.find('.memory-card-grid').exists()).toBe(true)
    expect(wrapper.findComponent(SpaceMapStub).exists()).toBe(false)
  })
})

// cr-node-key-hierarchy 5b.3: the folder overview card carries the share button the host allows (FOLDER's descriptor
// AND the capability — `MemoryHomeView.test.ts` pins that half), with a Memory's icon and label.
describe('MemoryBrowse — sharing the folder', () => {
  const shareButton = (w: ReturnType<typeof mountBrowse>) => w.find(`button[aria-label="${t('share.share')}"]`)

  it('shows the share button on a shareable folder and emits share', async () => {
    const w = mountBrowse({ folder: node('f', FOLDER_KIND, 'Trips'), folders: [], memories: [], shareable: true })
    expect(shareButton(w).exists()).toBe(true)
    expect(shareButton(w).find('.pi-share-alt').exists()).toBe(true)
    await shareButton(w).trigger('click')
    expect(w.emitted('share')).toHaveLength(1)
  })

  it('shows none when the host says the folder is not shareable', () => {
    const w = mountBrowse({ folder: node('f', FOLDER_KIND, 'Trips'), folders: [], memories: [], shareable: false })
    expect(shareButton(w).exists()).toBe(false)
    expect(w.find(`button[aria-label="${t('memory.rename')}"]`).exists(), 'control: the card actions render').toBe(true)
  })

  it('keeps the share (a read of the links) but no rename/delete during a pending deletion', () => {
    const w = mountBrowse({ folder: node('f', FOLDER_KIND, 'Trips'), folders: [], memories: [], shareable: true, readonly: true })
    expect(shareButton(w).exists()).toBe(true)
    expect(w.find(`button[aria-label="${t('memory.rename')}"]`).exists()).toBe(false)
  })
})
