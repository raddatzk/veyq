// @vitest-environment happy-dom
// The personal-space content host over the REAL registry (filled by `src/test/setup.ts`, as `main.ts` does: Memories
// and Files), core's real level view (`core/node/LevelView.vue`) and the real Memories detail adapter
// (`MemoryDetailHost`), with only the heavy Memories views (`MemoryDetail`, `MemoriesSection`) and the dialogs stubbed
// to prop recorders. What this pins: which component the host renders for which node kind, and that the generic
// context (buckets by kind, shareable, content key) arrives in the props the Memories components take. The
// registry-variant cases (an unregistered detail, no section at all) are in `MemoryHomeView.registry.test.ts`, which
// stubs the registry itself.
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
  // Each node's key as the chain would resolve it: the chapter `day1` has its own (7), every other node 9.
  nodeContentKey: (_scopeId: string, _epochs: unknown, _nodes: unknown, nodeId: string) => new Uint8Array(32).fill(nodeId === 'day1' ? 7 : 9),
}))

// Prop recorders: they render what they were handed, and expose the events the host and the level view listen to.
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
vi.mock('../features/memory/components/MemoriesSection.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      name: 'MemoriesSectionStub',
      props: ['folder', 'nodes', 'epochs', 'readonly'],
      emits: ['openNode', 'create', 'openTrash'],
      setup: (p) => () => h('section', { class: 'section-stub', 'data-readonly': String(p.readonly) },
        `folder=${p.folder?.name ?? '-'}|memories=${p.nodes.map((n: { name: string }) => n.name).join(',')}`),
    }),
  }
})
// Core's name dialog (folder create, rename of any kind) and Memories' create dialog (its kinds' `createDialog`).
vi.mock('../core/node/FolderNameDialog.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return { default: defineComponent({ props: ['mode', 'parentId', 'node'], setup: (p) => () => h('div', { class: 'name-stub' }, `${p.mode}|${p.parentId ?? '-'}|${p.node?.name ?? '-'}`) }) }
})
vi.mock('../features/memory/components/MemoryNodeDialog.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      props: ['kind', 'parentId', 'parentKey'],
      setup: (p) => () => h('div', { class: 'create-stub', 'data-key': String(p.parentKey?.[0]) }, `${p.kind}|${p.parentId ?? '-'}`),
    }),
  }
})
vi.mock('../features/memory/components/ShareDialog.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      props: { nodeId: String, uploader: Boolean, nodeName: String, nodeKey: Uint8Array, nodeKind: String },
      setup: (p) => () => h('div', { class: 'share-stub', 'data-name': p.nodeName, 'data-key': String(p.nodeKey?.[0]), 'data-kind': p.nodeKind },
        `${p.nodeId}|${p.uploader ? 'uploader' : 'viewer'}`),
    }),
  }
})
// FOLDER's share dialog, pre-registered by the registry (lazily: a `flushPromises` resolves it).
vi.mock('../core/sharing/FolderShareDialog.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      props: { scopeId: String, nodeId: String, nodeName: String, nodeKey: Uint8Array, nodeKind: String, epochs: Object },
      setup: (p) => () => h('div', { class: 'folder-share-stub', 'data-name': p.nodeName, 'data-key': String(p.nodeKey?.[0]), 'data-kind': p.nodeKind },
        `${p.scopeId}|${p.nodeId}`),
    }),
  }
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
import { FOLDER_KIND } from '../core/node/kind'
import { MEMORY_KIND, CHAPTER_KIND } from '../features/memory/kinds'
import { FILE_KIND } from '../features/files/kinds'
import { capabilityState } from '../shared/state/capabilities'
import { SHARING_PUBLIC_LINKS } from '../features/platform/capabilities'
import { realI18n, t } from '../test/i18n'
import { registry } from '../core/applications/registry'

function node(over: Partial<TreeNode> & Pick<TreeNode, 'id' | 'kind'>): TreeNode {
  return { parentId: null, orderIndex: 0, epoch: 0, name: over.id, wrappedContentKey: 'eA==', wrappedMeta: null, metaVersion: 0, placementVersion: 0, ...over }
}
const TREE: TreeNode[] = [
  node({ id: 'trips', kind: FOLDER_KIND, name: 'Trips' }),
  node({ id: 'solo', kind: MEMORY_KIND, name: 'Solo' }),
  node({ id: 'italy', kind: MEMORY_KIND, name: 'Italy', parentId: 'trips' }),
  node({ id: 'day2', kind: CHAPTER_KIND, name: 'Day2', parentId: 'italy', orderIndex: 1 }),
  node({ id: 'day1', kind: CHAPTER_KIND, name: 'Day1', parentId: 'italy', orderIndex: 0 }),
]

async function mountAt(hash: string) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'home', component: { template: '<div />' } }] })
  await router.replace({ name: 'home', hash })
  await router.isReady()
  const wrapper = mount(MemoryHomeView, { global: { plugins: [router, realI18n()], directives: { tooltip: {} } } })
  await flushPromises()
  return wrapper
}
type Mounted = Awaited<ReturnType<typeof mountAt>>
const folderCards = (w: Mounted) => w.findAll('button.memory-folder-card').map((b) => b.text())

beforeEach(() => {
  vi.mocked(listMyTenants).mockResolvedValue([])
  vi.mocked(isFavorite).mockReturnValue(false)
  treeStore.nodes = TREE.map((n) => ({ ...n }))
  capabilityState.ids = [SHARING_PUBLIC_LINKS]
})

describe('MemoryHomeView over the real registry', () => {
  it("renders core's level view at the scope root: Memories' section with the Memories bucket, then the folders", async () => {
    const w = await mountAt('#~me')
    expect(w.find('.section-stub').text()).toBe('folder=-|memories=Solo')
    expect(folderCards(w)).toEqual(['Trips'])
    expect(w.find('.detail-stub').exists()).toBe(false)
  })

  it('renders the level view for a folder, handing the section the folder and its own children', async () => {
    const w = await mountAt('#~me/trips')
    expect(w.find('.section-stub').text()).toBe('folder=Trips|memories=Italy')
    expect(folderCards(w)).toEqual([])
    expect(w.find('.memory-folder-overview-card h2').text()).toBe('Trips')
    expect(w.find('.section-stub').attributes('data-readonly')).toBe('false')
  })

  // Files registers FILE with no section yet (F0): a file in a folder is listed in core's fallback section — its icon,
  // its name, the unsupported state — never dropped, while the Memory beside it stays in Memories' section. F1 replaces
  // this with Files' own section.
  it('lists a FILE child in the fallback section, with the Files icon and the unsupported state', async () => {
    treeStore.nodes = [...TREE.map((n) => ({ ...n })), node({ id: 'report', kind: FILE_KIND, name: 'report.pdf', parentId: 'trips' })]
    const w = await mountAt('#~me/trips')
    expect(w.find('.section-stub').text()).toBe('folder=Trips|memories=Italy')
    const fallback = w.find('.veyq-level-fallback')
    expect(fallback.find('h2').text()).toBe(FILE_KIND)
    const row = fallback.find('.veyq-node-card')
    expect(row.find('strong').text()).toBe('report.pdf')
    expect(row.find('.pi-file').exists()).toBe(true)
    expect(row.text()).toContain(t('home.unsupportedKind'))
  })

  it('opens a Memory to the registered detail: content key, ordered chapters bucket, shareable', async () => {
    const w = await mountAt('#~me/trips/italy')
    const d = w.find('.detail-stub')
    expect(d.text()).toBe('Italy|Day1,Day2')
    expect(d.attributes('data-key')).toBe('9')
    expect(d.attributes('data-shareable')).toBe('true')
    expect(w.find('.section-stub').exists()).toBe(false)
  })

  // A chapter is a share root of its own (ADR 0080 Decision 13): the same detail offers the share affordances, and both
  // dialogs open for the chapter itself — its id, its own K_mem (the key chain's, not the Memory's) and its name as the
  // tree opened it, which the dialog re-seals under K_share.
  it('opens a chapter to the same detail, shareable, and shares the chapter itself (both dialogs)', async () => {
    const w = await mountAt('#~me/trips/italy/day1')
    expect(w.find('.detail-stub').text()).toBe('Day1|')
    expect(w.find('.detail-stub').attributes('data-shareable')).toBe('true')

    const detail = w.findComponent({ name: 'MemoryDetailStub' })
    detail.vm.$emit('share')
    detail.vm.$emit('shareUpload')
    await flushPromises()
    const dialogs = w.findAll('.share-stub')
    expect(dialogs.map((s) => s.text())).toEqual(['day1|viewer', 'day1|uploader'])
    expect(dialogs.map((s) => s.attributes('data-name'))).toEqual(['Day1', 'Day1'])
    expect(dialogs.map((s) => s.attributes('data-key')), "the chapter's own key, not its Memory's (9)").toEqual(['7', '7'])
    // the node's kind travels with it, so the application's dialog can say "chapter" (5b.5)
    expect(dialogs.map((s) => s.attributes('data-kind'))).toEqual(['CHAPTER', 'CHAPTER'])
  })

  it('hides the share affordance when the instance turns public links off, even for a shareable kind', async () => {
    capabilityState.ids = []
    const w = await mountAt('#~me/trips/italy')
    expect(w.find('.detail-stub').attributes('data-shareable')).toBe('false')
  })

  it("wires the detail events to the kind's dialogs: rename (core's), create-chapter, share, uploader share, delete", async () => {
    const w = await mountAt('#~me/trips/italy')
    const detail = w.findComponent({ name: 'MemoryDetailStub' })

    detail.vm.$emit('rename')
    await flushPromises()
    expect(w.find('.name-stub').text(), "a Memory's rename is core's name dialog").toBe('rename|italy|Italy')

    detail.vm.$emit('createChapter')
    await flushPromises()
    // CHAPTER's createDialog (Memories'), under the open Memory and its key; the rename dialog gives way (one at a time)
    expect(w.find('.create-stub').text()).toBe('CHAPTER|italy')
    expect(w.find('.create-stub').attributes('data-key')).toBe('9')
    expect(w.find('.name-stub').exists()).toBe(false)

    detail.vm.$emit('share')
    detail.vm.$emit('shareUpload')
    await flushPromises()
    expect(w.findAll('.share-stub').map((s) => s.text())).toEqual(['italy|viewer', 'italy|uploader'])

    detail.vm.$emit('delete')
    await flushPromises()
    expect(w.find('.delete-stub').text()).toBe('Italy')
  })

  it("wires the level events: a create by kind (MEMORY's dialog from the section, core's for a folder), trash, and opening a child navigates", async () => {
    const w = await mountAt('#~me')
    const section = w.findComponent({ name: 'MemoriesSectionStub' })

    section.vm.$emit('create', MEMORY_KIND)
    await flushPromises()
    expect(w.find('.create-stub').text()).toBe('MEMORY|-')
    expect(w.find('.name-stub').exists()).toBe(false)
    await w.find('.memory-create-card').trigger('click')
    await flushPromises()
    expect(w.find('.name-stub').text()).toBe('create|-|-')
    expect(w.find('.create-stub').exists(), 'the Memory dialog gives way').toBe(false)

    section.vm.$emit('openTrash')
    await flushPromises()
    expect(w.find('.trash-stub').exists()).toBe(true)

    section.vm.$emit('openNode', TREE[1])
    await flushPromises()
    expect(w.find('.detail-stub').text()).toBe('Solo|')
  })

  // A kind whose application registered no createDialog (Files' FILE, F0): a create of it opens nothing — no dialog of
  // another kind, no core dialog — and the level stays as it was.
  it('a create of a kind with no createDialog opens nothing', async () => {
    const w = await mountAt('#~me/trips')
    const section = w.findComponent({ name: 'MemoriesSectionStub' })
    section.vm.$emit('create', FILE_KIND)
    await flushPromises()
    expect(w.find('.create-stub').exists()).toBe(false)
    expect(w.find('.name-stub').exists()).toBe(false)
    expect(w.find('.section-stub').text(), 'the level is untouched').toBe('folder=Trips|memories=Italy')
    section.vm.$emit('create', MEMORY_KIND) // control: the same path with a registered dialog opens it
    await flushPromises()
    expect(w.find('.create-stub').text()).toBe('MEMORY|trips')
  })

  it('wires the folder overview card (rename, delete for the folder) and a folder card navigates', async () => {
    const w = await mountAt('#~me/trips')
    await w.find(`button[aria-label="${t('memory.rename')}"]`).trigger('click')
    await flushPromises()
    expect(w.find('.name-stub').text()).toBe('rename|trips|Trips')
    await w.find(`button[aria-label="${t('memory.delete')}"]`).trigger('click')
    await flushPromises()
    expect(w.find('.delete-stub').text()).toBe('Trips')

    const root = await mountAt('#~me')
    await root.findAll('button.memory-folder-card').find((b) => b.text() === 'Trips')!.trigger('click')
    await flushPromises()
    expect(root.find('.section-stub').text()).toBe('folder=Trips|memories=Italy')
  })
})

// cr-node-key-hierarchy 5b.3: a FOLDER is shareable (core's descriptor), so its level view offers the share button
// exactly as a Memory's detail does — descriptor AND capability — and the share opens FOLDER's share dialog (core's,
// pre-registered by the registry, P4c) with the folder's own key from the chain, never Memories' dialog.
describe('MemoryHomeView sharing a folder', () => {
  const shareButton = (w: Mounted) => w.find(`.memory-folder-overview-card button[aria-label="${t('share.share')}"]`)

  it("offers the share on the folder overview card, and it opens core's dialog for the folder itself", async () => {
    const w = await mountAt('#~me/trips')
    expect(shareButton(w).exists()).toBe(true)

    await shareButton(w).trigger('click')
    await flushPromises()

    const dialog = w.find('.folder-share-stub')
    expect(dialog.text()).toBe('scope-1|trips')
    expect(dialog.attributes('data-name')).toBe('Trips')
    expect(dialog.attributes('data-key'), "the folder's own key from the chain").toBe('9')
    expect(dialog.attributes('data-kind')).toBe(FOLDER_KIND)
    expect(w.find('.share-stub').exists(), "never the application's Memory dialog").toBe(false)
  })

  it('offers no share at the scope root (no node to share, no overview card)', async () => {
    const w = await mountAt('#~me')
    expect(w.find('.memory-folder-overview-card').exists()).toBe(false)
    expect(w.find(`button[aria-label="${t('share.share')}"]`).exists()).toBe(false)
  })

  it('hides the folder share when the instance turns public links off', async () => {
    capabilityState.ids = []
    const w = await mountAt('#~me/trips')
    expect(shareButton(w).exists()).toBe(false)
    expect(w.find(`.memory-folder-overview-card button[aria-label="${t('memory.rename')}"]`).exists(), 'control: the card renders').toBe(true)
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
