// @vitest-environment happy-dom
// The personal-space content host over the REAL registry (filled by `src/test/setup.ts`, as `main.ts` does) and the
// real Memories adapters (`MemoryDetailHost`, `MemoryLevelHost`), with only the two heavy Memories views and the
// dialogs stubbed to prop recorders. What this pins: which component the host renders for which node kind, and that
// the generic context (buckets by kind, shareable, content key) arrives in the props `MemoryDetail`/`MemoryBrowse`
// already take. The registry-variant cases (an unregistered detail, an unclaimed slot) are in
// `MemoryHomeView.registry.test.ts`, which stubs the registry itself.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('../app/activeSpace', async () => {
  const { reactive } = await import('vue')
  return {
    activeSpace: reactive({
      status: 'ready', scopeId: 'scope-1', tenantId: 't-1', kind: 'PERSONAL', name: null, epoch: 0,
      deletedAt: null, purgeAfter: null, trustRefusal: null,
    }),
    enterScope: vi.fn(async () => {}),
    activeEpochKeys: () => ({ current: 0, keys: new Map([[0, new Uint8Array(32).fill(1)]]) }),
  }
})
vi.mock('../features/memory/favoritesStore', () => ({
  favoritesStore: { loaded: true, ids: new Set<string>() },
  loadFavoritesFor: vi.fn(),
  isFavorite: vi.fn(),
  toggleFavorite: vi.fn(),
}))
vi.mock('../features/spaces/scopes', () => ({ listMyTenants: vi.fn() }))
vi.mock('../core/node/treeStore', async () => {
  const { reactive } = await import('vue')
  return {
    treeStore: reactive({ nodes: [], loaded: true, error: false }),
    loadTree: vi.fn(async () => {}),
    patchNodeMeta: vi.fn(),
  }
})
vi.mock('../core/node/tree', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../core/node/tree')>()),
  nodeContentKey: () => new Uint8Array(32).fill(9),
}))

// Prop recorders: they render what they were handed, and expose the events the adapters listen to.
vi.mock('../features/memory/components/MemoryDetail.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      name: 'MemoryDetailStub',
      props: ['node', 'scopeId', 'memoryKey', 'scopeKey', 'personalScope', 'favorite', 'chapters', 'shareable'],
      emits: ['toggleFavorite', 'rename', 'delete', 'openChapter', 'createChapter', 'share', 'shareUpload', 'metaUpdated'],
      setup: (p) => () => h('div', { class: 'detail-stub', 'data-shareable': String(p.shareable), 'data-key': String(p.memoryKey?.[0]) },
        `${p.node.name}|${(p.chapters ?? []).map((c: { name: string }) => c.name).join(',')}`),
    }),
  }
})
vi.mock('../features/memory/components/MemoryBrowse.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      name: 'MemoryBrowseStub',
      props: ['folder', 'folders', 'memories', 'epochs', 'readonly'],
      emits: ['openFolder', 'openMemory', 'toggleFavorite', 'createFolder', 'createMemory', 'renameFolder', 'deleteFolder', 'openTrash'],
      setup: (p) => () => h('div', { class: 'browse-stub' },
        `folder=${p.folder?.name ?? '-'}|folders=${p.folders.map((n: { name: string }) => n.name).join(',')}|memories=${p.memories.map((n: { name: string }) => n.name).join(',')}`),
    }),
  }
})
vi.mock('../features/memory/components/NodeMutationDialog.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return { default: defineComponent({ props: ['mode', 'parentId', 'node'], setup: (p) => () => h('div', { class: 'mutation-stub' }, `${p.mode}|${p.parentId ?? '-'}|${p.node?.name ?? '-'}`) }) }
})
vi.mock('../features/memory/components/ShareDialog.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return { default: defineComponent({ props: { nodeId: String, uploader: Boolean }, setup: (p) => () => h('div', { class: 'share-stub' }, `${p.nodeId}|${p.uploader ? 'uploader' : 'viewer'}`) }) }
})
vi.mock('../core/node/components/DeleteNodeDialog.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return { default: defineComponent({ props: ['node'], setup: (p) => () => h('div', { class: 'delete-stub' }, p.node.name) }) }
})
vi.mock('../core/node/components/SpaceTrashDialog.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return { default: defineComponent({ setup: () => () => h('div', { class: 'trash-stub' }) }) }
})

import MemoryHomeView from './MemoryHomeView.vue'
import { treeStore } from '../core/node/treeStore'
import { listMyTenants } from '../features/spaces/scopes'
import { isFavorite } from '../features/memory/favoritesStore'
import type { TreeNode } from '../core/node/tree'
import { NodeKind } from '../api/generated/content'
import { capabilityState } from '../shared/state/capabilities'
import { SHARING_PUBLIC_LINKS } from '../features/platform/capabilities'
import { realI18n } from '../test/i18n'
import { registry } from '../core/applications/registry'

function node(over: Partial<TreeNode> & Pick<TreeNode, 'id' | 'kind'>): TreeNode {
  return { parentId: null, orderIndex: 0, epoch: 0, name: over.id, wrappedContentKey: null, wrappedMeta: null, metaVersion: 0, ...over }
}
const TREE: TreeNode[] = [
  node({ id: 'trips', kind: NodeKind.Folder, name: 'Trips' }),
  node({ id: 'solo', kind: NodeKind.Memory, name: 'Solo' }),
  node({ id: 'italy', kind: NodeKind.Memory, name: 'Italy', parentId: 'trips' }),
  node({ id: 'day2', kind: NodeKind.Chapter, name: 'Day2', parentId: 'italy', orderIndex: 1 }),
  node({ id: 'day1', kind: NodeKind.Chapter, name: 'Day1', parentId: 'italy', orderIndex: 0 }),
]

async function mountAt(hash: string) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'home', component: { template: '<div />' } }] })
  await router.replace({ name: 'home', hash })
  await router.isReady()
  const wrapper = mount(MemoryHomeView, { global: { plugins: [router, realI18n()] } })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.mocked(listMyTenants).mockResolvedValue([])
  vi.mocked(isFavorite).mockReturnValue(false)
  treeStore.nodes = TREE.map((n) => ({ ...n }))
  capabilityState.ids = [SHARING_PUBLIC_LINKS]
})

describe('MemoryHomeView over the real registry', () => {
  it('renders the application level view at the scope root, with the Memories bucket from byKind', async () => {
    const w = await mountAt('#~me')
    expect(w.find('.browse-stub').text()).toBe('folder=-|folders=Trips|memories=Solo')
    expect(w.find('.detail-stub').exists()).toBe(false)
  })

  it('renders the level view for a folder, handing it the folder and its own children', async () => {
    const w = await mountAt('#~me/trips')
    expect(w.find('.browse-stub').text()).toBe('folder=Trips|folders=|memories=Italy')
  })

  it('opens a Memory to the registered detail: content key, ordered chapters bucket, shareable', async () => {
    const w = await mountAt('#~me/trips/italy')
    const d = w.find('.detail-stub')
    expect(d.text()).toBe('Italy|Day1,Day2')
    expect(d.attributes('data-key')).toBe('9')
    expect(d.attributes('data-shareable')).toBe('true')
    expect(w.find('.browse-stub').exists()).toBe(false)
  })

  it('opens a chapter to the same detail, but not shareable (its descriptor says so)', async () => {
    const w = await mountAt('#~me/trips/italy/day1')
    expect(w.find('.detail-stub').text()).toBe('Day1|')
    expect(w.find('.detail-stub').attributes('data-shareable')).toBe('false')
  })

  it('hides the share affordance when the instance turns public links off, even for a shareable kind', async () => {
    capabilityState.ids = []
    const w = await mountAt('#~me/trips/italy')
    expect(w.find('.detail-stub').attributes('data-shareable')).toBe('false')
  })

  it('wires the detail events to the claimed dialogs: rename, create-chapter, share, uploader share, delete', async () => {
    const w = await mountAt('#~me/trips/italy')
    const detail = w.findComponent({ name: 'MemoryDetailStub' })

    detail.vm.$emit('rename')
    await flushPromises()
    expect(w.find('.mutation-stub').text()).toBe('rename|italy|Italy')

    detail.vm.$emit('createChapter')
    await flushPromises()
    expect(w.find('.mutation-stub').text()).toBe('create-chapter|italy|-')

    detail.vm.$emit('share')
    detail.vm.$emit('shareUpload')
    await flushPromises()
    expect(w.findAll('.share-stub').map((s) => s.text())).toEqual(['italy|viewer', 'italy|uploader'])

    detail.vm.$emit('delete')
    await flushPromises()
    expect(w.find('.delete-stub').text()).toBe('Italy')
  })

  it('wires the level events: create modes (folder, memory), trash, and opening a child navigates', async () => {
    const w = await mountAt('#~me')
    const level = w.findComponent({ name: 'MemoryBrowseStub' })

    level.vm.$emit('createMemory')
    await flushPromises()
    expect(w.find('.mutation-stub').text()).toBe('create-memory|-|-')
    level.vm.$emit('createFolder')
    await flushPromises()
    expect(w.find('.mutation-stub').text()).toBe('create-folder|-|-')

    level.vm.$emit('openTrash')
    await flushPromises()
    expect(w.find('.trash-stub').exists()).toBe(true)

    level.vm.$emit('openMemory', TREE[1])
    await flushPromises()
    expect(w.find('.detail-stub').text()).toBe('Solo|')
  })
})

describe('MemoryHomeView entering a scope', () => {
  it('asks the registry to let each application load its per-scope state, once the scope is ready', async () => {
    const entered = vi.spyOn(registry, 'scopeEntered').mockResolvedValue(undefined)
    try {
      await mountAt('#~me')
      expect(entered).toHaveBeenCalledWith('scope-1')
    } finally {
      entered.mockRestore()
    }
  })
})
