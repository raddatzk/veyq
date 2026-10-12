// @vitest-environment happy-dom
// The personal-space content host over the REAL registry (filled by `src/test/setup.ts`, as `main.ts` does: Memories
// and Files), core's real level view (`core/node/LevelView.vue`) and the real Memories detail adapter
// (`MemoryDetailHost`), with only the heavy Memories views (`MemoryDetail`, `MemoriesSection`) and the dialogs stubbed
// to prop recorders. What this pins: which component the host renders for which node kind, and that the generic
// context (buckets by kind, the member's access and tree, content key) arrives in the props the Memories components
// take. The section recorders record what the REAL capability functions answer for the access and the tree they were
// handed — what the real section would offer. The
// registry-variant cases (an unregistered detail, no section at all) are in `MemoryHomeView.registry.test.ts`, which
// stubs the registry itself.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { breadcrumb } from '../app/breadcrumb'
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
const { showUndo } = vi.hoisted(() => ({ showUndo: vi.fn() }))
vi.mock('../shared/state/useToast', () => ({ useToast: () => ({ success: vi.fn(), error: vi.fn(), showUndo, dismissUndo: vi.fn() }) }))
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
  restoreNode: vi.fn(async () => {}),
  // Each node's key as the chain would resolve it: the chapter `day1` has its own (7), every other node 9.
  nodeContentKey: (_scopeId: string, _epochs: unknown, _nodes: unknown, nodeId: string) => new Uint8Array(32).fill(nodeId === 'day1' ? 7 : 9),
}))

// Prop recorders: they render what they were handed, and expose the events the host and the level view listen to.
vi.mock('../features/memory/components/MemoryDetail.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      name: 'MemoryDetailStub',
      props: ['node', 'scopeId', 'memoryKey', 'scopeKey', 'personalScope', 'favorite', 'chapters', 'caps'],
      emits: ['toggleFavorite', 'rename', 'delete', 'openChapter', 'createChapter', 'share', 'shareUpload', 'metaUpdated'],
      setup: (p) => () => h('div', {
        class: 'detail-stub', 'data-shareable': String(p.caps.share), 'data-share-upload': String(p.caps.shareUpload),
        'data-upload': String(p.caps.upload), 'data-key': String(p.memoryKey?.[0]),
      },
        `${p.node.name}|${(p.chapters ?? []).map((c: { name: string }) => c.name).join(',')}`),
    }),
  }
})
vi.mock('../features/memory/components/MemoriesSection.vue', async () => {
  const { defineComponent, h } = await import('vue')
  const { memoryCapabilities } = await import('../features/memory/capabilities')
  const { targetOf } = await import('../core/access')
  return {
    default: defineComponent({
      name: 'MemoriesSectionStub',
      props: ['folder', 'nodes', 'epochs', 'access', 'tree', 'viewMode'],
      emits: ['openNode', 'create'],
      setup: (p) => () => h('section', {
        class: 'section-stub', 'data-readonly': String(!memoryCapabilities(p.access, targetOf(p.tree, p.folder)).createMemory),
        'data-view': p.viewMode,
      },
        `folder=${p.folder?.name ?? '-'}|memories=${p.nodes.map((n: { name: string }) => n.name).join(',')}`),
    }),
  }
})
vi.mock('../features/files/components/FilesSection.vue', async () => {
  const { defineComponent, h } = await import('vue')
  const { filesCapabilities } = await import('../features/files/capabilities')
  const { targetOf } = await import('../core/access')
  return {
    default: defineComponent({
      name: 'FilesSectionStub',
      props: ['folder', 'nodes', 'epochs', 'access', 'tree', 'viewMode'],
      emits: ['openNode', 'create', 'rename'],
      setup: (p) => () => h('section', {
        class: 'files-stub', 'data-deletable': String(filesCapabilities(p.access, targetOf(p.tree, p.folder)).deleteFile),
        'data-view': p.viewMode,
      }, `files=${p.nodes.map((n: { name: string }) => n.name).join(',')}`),
    }),
  }
})
vi.mock('../features/files/components/FileDetail.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return { default: defineComponent({ name: 'FileDetailStub', props: ['node', 'access', 'tree'], setup: (p) => () => h('div', { class: 'file-detail-stub' }, `file=${p.node.name}`) }) }
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
  return { default: defineComponent({ name: 'DeleteNodeStub', props: ['node'], emits: ['done', 'close'], setup: (p) => () => h('div', { class: 'delete-stub' }, p.node.name) }) }
})
vi.mock('../core/node/components/SpaceTrashDialog.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return { default: defineComponent({ setup: () => () => h('div', { class: 'trash-stub' }) }) }
})

import MemoryHomeView from './MemoryHomeView.vue'
import { treeStore, loadTree } from '../core/node/treeStore'
import { listMyTenants } from '../features/spaces/scopes'
import { isFavorite } from '../features/memory/favoritesStore'
import { restoreNode, type TreeNode } from '../core/node/tree'
import { FOLDER_KIND } from '../core/node/kind'
import { MEMORY_KIND, CHAPTER_KIND } from '../features/memory/kinds'
import { FILE_KIND } from '../features/files/kinds'
import { capabilityState } from '../shared/state/capabilities'
import { SHARING_PUBLIC_LINKS } from '../features/platform/capabilities'
import { realI18n, t } from '../test/i18n'
import { chooseNew } from '../test/newMenu'
import PrimeVue from 'primevue/config'
import { registry } from '../core/applications/registry'
import { treeNode } from '../test/models'
import { setViewMode, useViewMode } from '../shared/state/useViewMode'
import { setLevelTab } from '../shared/state/useLevelTab'

function node(over: Partial<TreeNode> & Pick<TreeNode, 'id' | 'kind'>): TreeNode {
  return treeNode({ parentId: null, orderIndex: 0, epoch: 0, name: over.id, wrappedContentKey: 'eA==', wrappedMeta: null, metaVersion: 0, placementVersion: 0, ...over })
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
  const wrapper = mount(MemoryHomeView, { global: { plugins: [router, realI18n(), PrimeVue], directives: { tooltip: {} } } })
  await flushPromises()
  return wrapper
}
type Mounted = Awaited<ReturnType<typeof mountAt>>
const folderCards = (w: Mounted) => w.findAll('button.veyq-folder-card').map((b) => b.text())

beforeEach(() => {
  setViewMode('grid')
  setLevelTab('all')
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
    expect(w.find('.veyq-node-header-title').text()).toBe('Trips')
    expect(w.find('.section-stub').attributes('data-readonly')).toBe('false')
  })

  // A guest's identity folder (ADR 0080 decision 11) and a folder below it: a member places nothing there, so the level
  // is read-only (the server refuses a create or a move in); the folder beside it stays writable. It offers no share and
  // leaves out its empty sections (inbox #385): Memories' empty one is not rendered there, Files' with the file is.
  it('renders a guest identity folder, and a folder below it, read-only, unshared and without its empty sections', async () => {
    treeStore.nodes = [
      ...TREE.map((n) => ({ ...n })),
      node({ id: 'anna', kind: FOLDER_KIND, name: 'Anna', parentId: 'trips', shareIdentityId: 'identity-1' }),
      node({ id: 'deep', kind: FOLDER_KIND, name: 'Deep', parentId: 'anna' }),
      node({ id: 'memo', kind: MEMORY_KIND, name: 'Memo', parentId: 'deep' }),
      node({ id: 'scan', kind: FILE_KIND, name: 'scan.pdf', parentId: 'anna' }),
    ]
    const share = (w: Mounted) => w.find(`button[aria-label="${t('share.share')}"]`)
    const anna = await mountAt('#~me/trips/anna')
    expect(anna.find('.section-stub').exists(), 'no empty Memories section').toBe(false)
    expect(anna.find('.files-stub').text()).toBe('files=scan.pdf')
    // …but a member deletes what a guest sent there, file by file (inbox #391)
    expect(anna.find('.files-stub').attributes('data-deletable')).toBe('true')
    expect(folderCards(anna)).toEqual(['Deep'])
    expect(share(anna).exists()).toBe(false)
    expect(anna.find(`.veyq-level-toolbar button[aria-label="${t('node.trash.open')}"]`).exists(), 'no trash where nothing is written').toBe(false)
    const deep = await mountAt('#~me/trips/anna/deep')
    expect(deep.find('.section-stub').attributes('data-readonly')).toBe('true')
    expect(deep.find('.files-stub').exists(), 'no empty Files section').toBe(false)
    // control: the folder beside it is writable, shared, and shows its sections empty or not
    const trips = await mountAt('#~me/trips')
    expect(trips.find('.section-stub').attributes('data-readonly')).toBe('false')
    expect(share(trips).exists()).toBe(true)
  })

  // Inbox #404: the view mode is one app-wide preference, but the level shows it only where a shown section offers it.
  // A member who last chose the map opens a guest's identity folder: Memories' section (the map's) is left out there,
  // so the level shows the grid — Files' rows and the folders, not a blank page — and offers the folders' switch with
  // the grid pressed; the stored map is kept, and the next level with Memories' section shows it again.
  it('a stored map shows an identity folder in the grid, and the map again where Memories draws it', async () => {
    treeStore.nodes = [
      ...TREE.map((n) => ({ ...n })),
      node({ id: 'anna', kind: FOLDER_KIND, name: 'Anna', parentId: 'trips', shareIdentityId: 'identity-1' }),
      node({ id: 'deep', kind: FOLDER_KIND, name: 'Deep', parentId: 'anna' }),
      node({ id: 'scan', kind: FILE_KIND, name: 'scan.pdf', parentId: 'anna' }),
    ]
    setViewMode('map')
    const anna = await mountAt('#~me/trips/anna')
    expect(anna.find('.section-stub').exists(), "Memories' empty section is left out").toBe(false)
    expect(anna.find('.files-stub').attributes('data-view')).toBe('grid')
    expect(anna.find('.files-stub').text()).toBe('files=scan.pdf')
    expect(folderCards(anna)).toEqual(['Deep'])
    const pressed = anna.findAll('.veyq-level-toolbar .view-mode-button').filter((b) => b.attributes('aria-pressed') === 'true')
    expect(pressed.map((b) => b.attributes('aria-label'))).toEqual([t('node.viewGrid')])
    expect(useViewMode().viewMode.value, 'the stored preference is kept').toBe('map')
    const trips = await mountAt('#~me/trips')
    expect(trips.find('.section-stub').attributes('data-view')).toBe('map')
    expect(trips.find('.files-stub').attributes('data-view')).toBe('map')
    expect(folderCards(trips), 'the folders step aside for the map').toEqual([])
  })

  // Inbox #393 (b): two guests of one name — the folder at `anna-2` is headed, and its crumb named, "Anna (2)", as the
  // drop box lists it (creation order), the first one plain "Anna".
  it('heads an identity folder and its crumb with its numbered name', async () => {
    treeStore.nodes = [
      ...TREE.map((n) => ({ ...n })),
      node({ id: 'anna-late', kind: FOLDER_KIND, name: 'Anna', parentId: 'trips', shareIdentityId: 'identity-2', createdAt: 2000 }),
      node({ id: 'anna-early', kind: FOLDER_KIND, name: 'Anna', parentId: 'trips', shareIdentityId: 'identity-1', createdAt: 1000 }),
    ]
    const second = await mountAt('#~me/trips/anna-2')
    expect(second.find('.veyq-node-header-title').text()).toBe('Anna (2)')
    expect(breadcrumb.trail.at(-1)).toMatchObject({ id: 'anna-late', name: 'Anna (2)' })
    const first = await mountAt('#~me/trips/anna')
    expect(first.find('.veyq-node-header-title').text()).toBe('Anna')
  })

  // Files registers FILE with its own section (F1b): a file in a folder is listed there, after Memories' section, and no
  // longer in core's fallback; the Memory beside it stays in Memories' section.
  it('hands a FILE child to Files\' section, after Memories\', and leaves no fallback', async () => {
    treeStore.nodes = [...TREE.map((n) => ({ ...n })), node({ id: 'report', kind: FILE_KIND, name: 'report.pdf', parentId: 'trips' })]
    const w = await mountAt('#~me/trips')
    expect(w.find('.section-stub').text()).toBe('folder=Trips|memories=Italy')
    expect(w.find('.files-stub').text()).toBe('files=report.pdf')
    expect(w.findAll('section').map((s) => s.classes()).findIndex((c) => c.includes('files-stub')))
      .toBeGreaterThan(w.findAll('section').map((s) => s.classes()).findIndex((c) => c.includes('section-stub')))
    expect(w.find('.veyq-level-fallback').exists()).toBe(false)
    expect(w.text()).not.toContain(t('home.unsupportedKind'))
  })

  // A section's `rename` (F1b: a file is renamed from its row, it never opens) reaches the host through core's level
  // view: core's name dialog opens in rename mode for THAT node, at its level.
  it('a section\'s rename opens core\'s name dialog in rename mode for the node it names', async () => {
    const report = node({ id: 'report', kind: FILE_KIND, name: 'report.pdf', parentId: 'trips' })
    treeStore.nodes = [...TREE.map((n) => ({ ...n })), report]
    const w = await mountAt('#~me/trips')
    expect(w.find('.name-stub').exists()).toBe(false)
    w.findComponent({ name: 'FilesSectionStub' }).vm.$emit('rename', report)
    await flushPromises()
    expect(w.find('.name-stub').text()).toBe('rename|trips|report.pdf')
  })

  it('opens a Memory to the registered detail: content key, ordered chapters bucket, shareable', async () => {
    const w = await mountAt('#~me/trips/memories/italy')
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
    const w = await mountAt('#~me/trips/memories/italy/day1')
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
    const w = await mountAt('#~me/trips/memories/italy')
    expect(w.find('.detail-stub').attributes('data-shareable')).toBe('false')
  })

  it("wires the detail events to the kind's dialogs: rename (core's), create-chapter, share, uploader share, delete", async () => {
    const w = await mountAt('#~me/trips/memories/italy')
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

    await chooseNew(w, t('memory.create')) // the real registry's entry of the Memory kind
    await flushPromises()
    expect(w.find('.create-stub').text()).toBe('MEMORY|-')
    expect(w.find('.name-stub').exists()).toBe(false)
    await chooseNew(w, t('node.folderCreate'))
    await flushPromises()
    expect(w.find('.name-stub').text()).toBe('create|-|-')
    expect(w.find('.create-stub').exists(), 'the Memory dialog gives way').toBe(false)

    await w.find(`.veyq-level-toolbar button[aria-label="${t('node.trash.open')}"], .veyq-level-toolbar button:not(.veyq-new-menu-button)`).trigger('click')
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
    await w.find(`button[aria-label="${t('node.rename')}"]`).trigger('click')
    await flushPromises()
    expect(w.find('.name-stub').text()).toBe('rename|trips|Trips')
    await w.find(`button[aria-label="${t('node.delete')}"]`).trigger('click')
    await flushPromises()
    expect(w.find('.delete-stub').text()).toBe('Trips')
    // the dialog deleted it (inbox #385 (c)): the host says so with an undo that restores the folder as the trash does
    showUndo.mockClear()
    w.findComponent({ name: 'DeleteNodeStub' }).vm.$emit('done')
    await flushPromises()
    expect(showUndo).toHaveBeenCalledOnce()
    expect(showUndo.mock.calls[0][0].summary).toBe(t('node.trash.moved', { name: 'Trips' }))
    vi.mocked(loadTree).mockClear()
    showUndo.mock.calls[0][0].onUndo()
    await flushPromises()
    expect(vi.mocked(restoreNode).mock.calls.map((c) => [c[0], (c[3] as TreeNode).id])).toEqual([['scope-1', 'trips']])
    expect(loadTree).toHaveBeenCalled()

    const root = await mountAt('#~me')
    await root.findAll('button.veyq-folder-card').find((b) => b.text() === 'Trips')!.trigger('click')
    await flushPromises()
    expect(root.find('.section-stub').text()).toBe('folder=Trips|memories=Italy')
  })
})

// cr-node-key-hierarchy 5b.3: a FOLDER is shareable (core's descriptor), so its level view offers the share button
// exactly as a Memory's detail does — descriptor AND capability — and the share opens FOLDER's share dialog (core's,
// pre-registered by the registry, P4c) with the folder's own key from the chain, never Memories' dialog.
describe('MemoryHomeView sharing a folder', () => {
  const shareButton = (w: Mounted) => w.find(`.veyq-node-header button[aria-label="${t('share.share')}"]`)

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
    expect(w.find('.veyq-node-header').exists()).toBe(false)
    expect(w.find(`button[aria-label="${t('share.share')}"]`).exists()).toBe(false)
  })

  it('hides the folder share when the instance turns public links off', async () => {
    capabilityState.ids = []
    const w = await mountAt('#~me/trips')
    expect(shareButton(w).exists()).toBe(false)
    expect(w.find(`.veyq-node-header button[aria-label="${t('node.rename')}"]`).exists(), 'control: the card renders').toBe(true)
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

// Inbox #328 over real URLs (ADR 0015, amended 2026-10-11): the host resolves a fragment by `location.ts`'s grammar —
// a folder ends in a slash, a Files node is its plain name, a Memory sits behind Memories' segment.
describe('MemoryHomeView resolving the fragment grammar', () => {
  const REPORT_TREE: TreeNode[] = [
    ...TREE.map((n) => ({ ...n })),
    node({ id: 'rfile', kind: FILE_KIND, name: 'Report', parentId: 'trips', createdAt: 5 }),
    node({ id: 'rdir', kind: FOLDER_KIND, name: 'Report', parentId: 'trips', createdAt: 6 }),
    node({ id: 'inner', kind: FOLDER_KIND, name: 'Inner', parentId: 'rdir', createdAt: 7 }),
    node({ id: 'mem-folder', kind: FOLDER_KIND, name: 'Memories', createdAt: 8 }),
  ]
  beforeEach(() => {
    treeStore.nodes = REPORT_TREE.map((n) => ({ ...n }))
  })

  it('a Memory is reached behind its application\'s segment; the segment-less path of the old URLs opens nothing', async () => {
    expect((await mountAt('#~me/trips/memories/italy')).find('.detail-stub').text()).toContain('Italy')
    const old = await mountAt('#~me/trips/italy')
    expect(old.find('.detail-stub').exists()).toBe(false)
    expect(old.find('.section-stub').text()).toBe('folder=-|memories=Solo') // collapsed to the scope root
  })

  it('a slug shared by a file and a folder: the file without the slash, the folder with it or with a child behind it', async () => {
    const file = await mountAt('#~me/trips/report')
    expect(file.find('.section-stub').exists()).toBe(false)
    expect(file.find('.file-detail-stub').text()).toBe('file=Report') // a file opens its detail (Files' FileDetail)
    const folder = await mountAt('#~me/trips/report/')
    expect(folder.find('.section-stub').text()).toContain('folder=Report')
    const inner = await mountAt('#~me/trips/report/inner/')
    expect(inner.find('.section-stub').text()).toContain('folder=Inner')
  })

  it('a folder called "Memories" lives at `memories-2`; the bare application segment opens nothing', async () => {
    expect((await mountAt('#~me/memories-2/')).find('.section-stub').text()).toContain('folder=Memories')
    expect((await mountAt('#~me/memories/')).find('.section-stub').text()).toBe('folder=-|memories=Solo')
  })

  it('clicking a folder card pushes the fragment `location.ts` builds: the folder\'s path with its slash', async () => {
    const w = await mountAt('#~me')
    await w.findAll('button.veyq-folder-card').find((b) => b.text() === 'Trips')!.trigger('click')
    await flushPromises()
    expect(w.vm.$route.hash).toBe('#~me/trips/')
    expect(w.find('.section-stub').text()).toContain('folder=Trips')
  })
})
