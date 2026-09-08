// @vitest-environment happy-dom
import { describe, it, expect, beforeEach } from 'vitest'

// SpaceMap wraps Leaflet, which we neither need nor can run here — stub the module out; the component is stubbed too.
import { vi } from 'vitest'
vi.mock('leaflet', () => ({ default: {} }))

import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import MemoryBrowse from './MemoryBrowse.vue'
import { NodeKind } from '../../../api/generated/content'
import { treeStore } from '../treeStore'
import { setViewMode } from '../../../shared/state/useViewMode'
import { sealNodeMeta } from '../nodeMeta'
import type { MemoryLocation } from '../memoryLocation'
import type { TreeNode } from '../tree'
import { epochsOf } from '../../spaces/epochsTestUtil'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages: {
    en: { memory: { memories: 'Memories', folders: 'Folders', memoryEmpty: 'No Memories', folderEmpty: 'No folders' } },
  },
})

// A stub for the Leaflet map — captures the pins it received and re-emits `open` on demand.
const SpaceMapStub = {
  props: ['pins'],
  emits: ['open'],
  template: `<div class="space-map-stub">{{ pins.length }}</div>`,
}

const node = (id: string, kind: NodeKind, name: string): TreeNode => ({
  id, kind, name, parentId: null, orderIndex: 0, epoch: 0, wrappedContentKey: null, wrappedMeta: null,
})

const scopeKey = v1.randomBytes(32)
const located = (lat: number, lng: number): MemoryLocation => ({ name: `p-${lat}`, nameSource: 'manual', derivedMode: 'fixed', derived: { lat, lng } })

/** A located Memory whose K_mem is wrapped under `scopeKey` and location sealed under it (mirrors tree.ts). */
function locatedMemory(id: string, parentId: string | null, loc: MemoryLocation): TreeNode {
  const key = v1.randomBytes(32)
  return {
    id, kind: NodeKind.Memory, name: id, parentId, orderIndex: 0, epoch: 0,
    wrappedContentKey: b64std(v1.symWrap(scopeKey, key, 'content-key', v1.randomBytes(12))),
    wrappedMeta: sealNodeMeta(key, { v: 1, location: loc }),
  }
}

const mountBrowse = (props: Partial<InstanceType<typeof MemoryBrowse>['$props']> & { folders: TreeNode[]; memories: TreeNode[] }) =>
  mount(MemoryBrowse, { props, global: { plugins: [i18n], stubs: { SpaceMap: SpaceMapStub } } })

beforeEach(() => {
  setViewMode('grid')
  treeStore.nodes = []
})

describe('MemoryBrowse', () => {
  it('renders the level (decrypted folder + Memory names) and emits drill/open on click', async () => {
    const folders = [node('f1', NodeKind.Folder, 'Trips')]
    const memories = [node('m1', NodeKind.Memory, 'Solo')]
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
    expect(wrapper.text()).toContain('No Memories')
    expect(wrapper.text()).toContain('No folders')
  })

  it('in map mode replaces the card grids with the subtree map and pins located nodes', () => {
    // folder fA holds a located memory; a second located memory sits at root — at root both are in scope.
    const memInA = locatedMemory('memInA', 'fA', located(1, 1))
    const memRoot = locatedMemory('memRoot', null, located(2, 2))
    treeStore.nodes = [node('fA', NodeKind.Folder, 'A'), memInA, memRoot]
    setViewMode('map')

    const wrapper = mountBrowse({ folder: null, folders: [], memories: [memRoot], epochs: epochsOf(scopeKey) })

    // the card grids are gone, the map is shown
    expect(wrapper.find('.memory-card-grid').exists()).toBe(false)
    const map = wrapper.findComponent(SpaceMapStub)
    expect(map.exists()).toBe(true)
    expect((map.props('pins') as { node: TreeNode }[]).map((p) => p.node.id).sort()).toEqual(['memInA', 'memRoot'])
  })

  it('scopes the map to the current folder subtree', () => {
    const memInA = locatedMemory('memInA', 'fA', located(1, 1))
    const memInB = locatedMemory('memInB', 'fB', located(2, 2))
    const fA = node('fA', NodeKind.Folder, 'A')
    treeStore.nodes = [fA, node('fB', NodeKind.Folder, 'B'), memInA, memInB]
    setViewMode('map')

    const wrapper = mountBrowse({ folder: fA, folders: [], memories: [], epochs: epochsOf(scopeKey) })
    const map = wrapper.findComponent(SpaceMapStub)
    expect((map.props('pins') as { node: TreeNode }[]).map((p) => p.node.id)).toEqual(['memInA'])
  })

  it('a pin open resolves to its TreeNode and emits openMemory', async () => {
    const memRoot = locatedMemory('memRoot', null, located(2, 2))
    treeStore.nodes = [memRoot]
    setViewMode('map')

    const wrapper = mountBrowse({ folder: null, folders: [], memories: [memRoot], epochs: epochsOf(scopeKey) })
    wrapper.findComponent(SpaceMapStub).vm.$emit('open', 'memRoot')
    await wrapper.vm.$nextTick()

    expect(wrapper.emitted('openMemory')?.[0]?.[0]).toMatchObject({ id: 'memRoot' })
  })

  it('grid mode still renders the card grids (map absent)', () => {
    setViewMode('grid')
    const wrapper = mountBrowse({ folders: [], memories: [node('m1', NodeKind.Memory, 'Solo')] })
    expect(wrapper.find('.memory-card-grid').exists()).toBe(true)
    expect(wrapper.findComponent(SpaceMapStub).exists()).toBe(false)
  })
})
