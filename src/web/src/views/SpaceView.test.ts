// @vitest-environment happy-dom
// The personal-space content host over the REAL registry (filled by `src/test/setup.ts`, as `main.ts` does: Memories
// and Depot), core's real level view (`core/node/LevelView.vue`) and the real Memories detail adapter
// (`MemoryDetailHost`), with only the heavy Memories view (`MemoryDetail`) and the dialogs stubbed to prop recorders.
// What this pins: which component the host renders for which node kind, and that the generic context (buckets by kind,
// the member's access and tree, content key) arrives in the props the Memories components take. Since inbox #407 the
// level is ONE list drawn by core, with the applications' REAL row factories (nothing of the level is stubbed): the
// cases read the rows, the "New" menu and the row actions the way a person finds them. The registry-variant cases (an
// unregistered detail, no row source at all) are in `SpaceView.registry.test.ts`, which stubs the registry itself.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { breadcrumb } from '../app/breadcrumb'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

// Leaflet is neither needed nor runnable here (the Memories map is stubbed below).
vi.mock('leaflet', () => ({ default: {} }))
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
vi.mock('../core/favorites/favoritesStore', () => ({
  favoritesStore: { loaded: true, ids: new Set<string>() },
  loadFavoritesFor: vi.fn(),
  isFavorite: vi.fn(),
  toggleFavorite: vi.fn(async () => {}),
}))
// Depot's row factory lists the blobs of the level's files; here there are none (the rows read "not uploaded yet").
vi.mock('../features/depot/api', () => ({ filesApi: vi.fn() }))
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
  // The key a Memory row opens its meta under (nothing is sealed in this file's tree).
  parentContentKey: () => new Uint8Array(32).fill(9),
  // Each node's key as the chain would resolve it: the chapter `day1` has its own (7), every other node 9.
  nodeContentKey: (_scopeId: string, _epochs: unknown, _nodes: unknown, nodeId: string) => new Uint8Array(32).fill(nodeId === 'day1' ? 7 : 9),
}))

// Prop recorders: they render what they were handed, and expose the events the host and the level view listen to.
vi.mock('../features/memories/components/MemoryDetail.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      name: 'MemoryDetailStub',
      props: ['node', 'scopeId', 'memoryKey', 'scopeKey', 'personalScope', 'favorite', 'chapters', 'rights', 'renameTo'],
      emits: ['toggleFavorite', 'delete', 'openChapter', 'createChapter', 'share', 'metaUpdated'],
      setup: (p) => () => h('div', {
        class: 'detail-stub', 'data-shareable': String(p.rights.share), 'data-share-upload': String(p.rights.shareUpload),
        'data-upload': String(p.rights.upload), 'data-key': String(p.memoryKey?.[0]),
      },
        `${p.node.name}|${(p.chapters ?? []).map((c: { name: string }) => c.name).join(',')}`),
    }),
  }
})
vi.mock('../features/depot/components/FileDetail.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return { default: defineComponent({ name: 'FileDetailStub', props: ['node', 'access', 'tree'], setup: (p) => () => h('div', { class: 'file-detail-stub' }, `file=${p.node.name}`) }) }
})
// Core's name dialog (folder create, rename of any kind) and Memories' create dialog (its kinds' `createDialog`).
vi.mock('../core/node/FolderNameDialog.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return { default: defineComponent({ props: ['mode', 'parentId', 'node'], setup: (p) => () => h('div', { class: 'name-stub' }, `${p.mode}|${p.parentId ?? '-'}|${p.node?.name ?? '-'}`) }) }
})
vi.mock('../features/memories/components/MemoryNodeDialog.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      props: ['kind', 'parentId', 'parentKey'],
      setup: (p) => () => h('div', { class: 'create-stub', 'data-key': String(p.parentKey?.[0]) }, `${p.kind}|${p.parentId ?? '-'}`),
    }),
  }
})
vi.mock('../features/memories/components/ShareDialog.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      props: { nodeId: String, view: Boolean, contribute: Boolean, upload: Boolean, nodeName: String, nodeKey: Uint8Array, nodeKind: String },
      setup: (p) => () => h('div', { class: 'share-stub', 'data-name': p.nodeName, 'data-key': String(p.nodeKey?.[0]), 'data-kind': p.nodeKind, 'data-contribute': String(p.contribute) },
        `${p.nodeId}|${[p.view && 'view', p.contribute && 'contribute', p.upload && 'upload'].filter(Boolean).join('+') || 'none'}`),
    }),
  }
})
// FOLDER's share dialog, pre-registered by the registry (lazily: a `flushPromises` resolves it).
vi.mock('../core/sharing/FolderShareDialog.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      props: {
        scopeId: String, nodeId: String, nodeName: String, nodeKey: Uint8Array, nodeKind: String, epochs: Object, nodeScopeEpoch: Number,
        view: Boolean, contribute: Boolean, upload: Boolean,
      },
      setup: (p) => () => h('div', {
        class: 'folder-share-stub', 'data-name': p.nodeName, 'data-key': String(p.nodeKey?.[0]), 'data-kind': p.nodeKind,
        'data-offer': `${p.view}|${p.contribute}|${p.upload}`, 'data-scope-epoch': String(p.nodeScopeEpoch),
      }, `${p.scopeId}|${p.nodeId}`),
    }),
  }
})
// Core's delete question with its Undo, for a selection, a row's node and a page's own node alike (its write and its Undo are
// `DeleteNodesDialog.test.ts`'s): the stub names what it was handed and hands the host's after-undo on.
vi.mock('../core/node/components/DeleteNodesDialog.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      name: 'DeleteNodesStub', props: ['items', 'afterUndo'], emits: ['done', 'close'],
      setup: (p) => () => h('div', { class: 'delete-stub' }, (p.items as { node: { name: string } }[]).map((i) => i.node.name).join(', ')),
    }),
  }
})
vi.mock('../core/node/components/SpaceTrashDialog.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return { default: defineComponent({ setup: () => () => h('div', { class: 'trash-stub' }) }) }
})

import SpaceView from './SpaceView.vue'
import { treeStore } from '../core/node/treeStore'
import { listMyTenants } from '../features/spaces/scopes'
import { isFavorite } from '../core/favorites/favoritesStore'
import type { TreeNode } from '../core/node/tree'
import { FOLDER_KIND } from '../core/node/kind'
import { MEMORY_KIND, CHAPTER_KIND } from '../features/memories/kinds'
import { FILE_KIND } from '../features/depot/kinds'
import { capabilityState } from '../shared/state/capabilities'
import { CORE_PUBLIC_SHARES, CORE_PUBLIC_SHARES_CONTRIBUTE, CORE_PUBLIC_SHARES_DROP_BOX, DEPOT, MEMORIES } from '../features/platform/capabilities'
import { realI18n, t } from '../test/i18n'
import { chooseNew, hasNewMenu, newMenuLabels } from '../test/newMenu'
import { actionIdsOf, clickAction, rowOf, titlesOf } from '../test/levelRows'
import LevelView from '../core/node/LevelView.vue'
import { registry } from '../core/applications/registry'
import { treeNode } from '../test/models'
import { stubApi } from '../test/apiMock'
import { filesApi } from '../features/depot/api'
import { setViewMode, useViewMode } from '../shared/state/useViewMode'
import { setLevelTab } from '../shared/state/useLevelTab'
import { chooseHeaderEntry } from '../test/nodeHeader'

function node(over: Partial<TreeNode> & Pick<TreeNode, 'id' | 'kind'>): TreeNode {
  return treeNode({ parentId: null, orderIndex: 0, epoch: 0, name: over.id, wrappedContentKey: 'eA==', wrappedMeta: null, metaRevision: 0, placementRevision: 0, ...over })
}
const TREE: TreeNode[] = [
  node({ id: 'trips', kind: FOLDER_KIND, name: 'Trips' }),
  node({ id: 'solo', kind: MEMORY_KIND, name: 'Solo' }),
  node({ id: 'italy', kind: MEMORY_KIND, name: 'Italy', parentId: 'trips' }),
  node({ id: 'day2', kind: CHAPTER_KIND, name: 'Day2', parentId: 'italy', orderIndex: 1 }),
  node({ id: 'day1', kind: CHAPTER_KIND, name: 'Day1', parentId: 'italy', orderIndex: 0 }),
]

const mounted: { unmount(): void }[] = []
async function mountAt(hash: string) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'home', component: { template: '<div />' } }] })
  await router.replace({ name: 'home', hash })
  await router.isReady()
  const SpaceMapStub = { name: 'SpaceMap', props: ['pins', 'covers'], emits: ['open'], template: '<div class="space-map-stub" />' }
  const wrapper = mount(SpaceView, { global: { plugins: [router, realI18n()], stubs: { SpaceMap: SpaceMapStub } } })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}
type Mounted = Awaited<ReturnType<typeof mountAt>>
const folderCards = (w: Mounted) => w.findAll('li.veyq-level-item.is-folder .veyq-level-item-title').map((b) => b.text())

// The "New" and row menus are popups teleported to the body; leave none behind for the next case.
afterEach(() => { mounted.splice(0).forEach((w) => w.unmount()) })

beforeEach(() => {
  setViewMode('grid')
  setLevelTab('all')
  vi.mocked(listMyTenants).mockResolvedValue([])
  stubApi(filesApi, { listFiles: { items: [] } })
  vi.mocked(isFavorite).mockReturnValue(false)
  treeStore.nodes = TREE.map((n) => ({ ...n }))
  capabilityState.ids = [MEMORIES, DEPOT, CORE_PUBLIC_SHARES, CORE_PUBLIC_SHARES_CONTRIBUTE, CORE_PUBLIC_SHARES_DROP_BOX]
})

describe('SpaceView over the real registry', () => {
  // Since inbox #407 the level is one list: the folders first, then the Memory rows (no section of Memories' own).
  it("renders core's level view at the scope root: the folders, then the Memories as rows of the one list", async () => {
    const w = await mountAt('#~me')
    expect(titlesOf(w)).toEqual(['Trips', 'Solo'])
    expect(folderCards(w)).toEqual(['Trips'])
    expect(w.find('li.veyq-level-item[data-kind="MEMORY"] .veyq-level-item-meta').text()).toContain(t('memory.kindMemory'))
    expect(w.find('.detail-stub').exists()).toBe(false)
  })

  it('renders the level view for a folder: its header and its own children as rows', async () => {
    const w = await mountAt('#~me/trips')
    expect(titlesOf(w)).toEqual(['Italy'])
    expect(folderCards(w)).toEqual([])
    expect(w.find('.veyq-node-header-title').text()).toBe('Trips')
    expect(await newMenuLabels(w), 'a member may add a Memory here').toContain(t('memory.create'))
  })

  // A guest's identity folder (ADR 0080 decision 11) and a folder below it: a member places nothing there, so the level
  // is read-only (the server refuses a create or a move in); the folder beside it stays writable. It offers no share and
  // leaves out its empty tabs (inbox #385): with only the file there, the level has one tab - no tab bar - and in the
  // folder below it nothing is offered to add (no "New" menu).
  it('renders a guest identity folder, and a folder below it, read-only, unshared and without its empty tabs', async () => {
    treeStore.nodes = [
      ...TREE.map((n) => ({ ...n })),
      node({ id: 'anna', kind: FOLDER_KIND, name: 'Anna', parentId: 'trips', shareIdentityId: 'identity-1' }),
      node({ id: 'deep', kind: FOLDER_KIND, name: 'Deep', parentId: 'anna' }),
      node({ id: 'memo', kind: MEMORY_KIND, name: 'Memo', parentId: 'deep' }),
      node({ id: 'scan', kind: FILE_KIND, name: 'scan.pdf', parentId: 'anna' }),
    ]
    const share = (w: Mounted) => w.find(`button[aria-label="${t('share.share')}"]`)
    const anna = await mountAt('#~me/trips/anna')
    expect(titlesOf(anna)).toEqual(['Deep', 'scan.pdf'])
    expect(anna.find('li.veyq-level-item[data-kind="MEMORY"]').exists(), 'no Memory row, no empty Memories tab').toBe(false)
    expect(anna.find('.veyq-level-tabs').exists(), 'one tab only: no tab bar').toBe(false)
    // …but a member deletes what a guest sent there, file by file (inbox #391)
    expect(await actionIdsOf(anna, 'scan.pdf')).toContain('delete')
    expect(folderCards(anna)).toEqual(['Deep'])
    expect(share(anna).exists()).toBe(false)
    expect(hasNewMenu(anna), "nothing may be added in a guest's folder").toBe(false)
    expect(anna.find(`.veyq-level-toolbar button[aria-label="${t('node.trash.open')}"]`).exists(), 'no trash where nothing is written').toBe(false)
    const deep = await mountAt('#~me/trips/anna/deep')
    expect(titlesOf(deep)).toEqual(['Memo'])
    expect(hasNewMenu(deep), 'read-only below it as well').toBe(false)
    // control: the folder beside it is writable, shared, and offers the New menu
    const trips = await mountAt('#~me/trips')
    expect(await newMenuLabels(trips)).toContain(t('memory.create'))
    expect(share(trips).exists()).toBe(true)
  })

  // Inbox #404: the view mode is one app-wide preference, but the level shows it only where something on screen offers it.
  // A member who last chose the map opens a guest's identity folder: Memories (the map's) has no node and no entry there,
  // so the level shows the grid - Depot's rows and the folders, not a blank page - and offers the switch with the grid
  // pressed; the stored map is kept, and the next level with Memories shows the map again.
  it('a stored map shows an identity folder in the grid, and the map again where Memories draws it', async () => {
    treeStore.nodes = [
      ...TREE.map((n) => ({ ...n })),
      node({ id: 'anna', kind: FOLDER_KIND, name: 'Anna', parentId: 'trips', shareIdentityId: 'identity-1' }),
      node({ id: 'deep', kind: FOLDER_KIND, name: 'Deep', parentId: 'anna' }),
      node({ id: 'scan', kind: FILE_KIND, name: 'scan.pdf', parentId: 'anna' }),
    ]
    setViewMode('map')
    const anna = await mountAt('#~me/trips/anna')
    expect(anna.find('.space-map-stub').exists(), 'no map where Memories has nothing to offer').toBe(false)
    expect(anna.find('ul.veyq-level-list').classes()).not.toContain('is-list')
    expect(anna.find('li.veyq-level-item.is-grid').exists()).toBe(true)
    expect(titlesOf(anna)).toEqual(['Deep', 'scan.pdf'])
    expect(folderCards(anna)).toEqual(['Deep'])
    const pressed = anna.findAll('.veyq-level-toolbar .view-mode-button').filter((b) => b.attributes('aria-pressed') === 'true')
    expect(pressed.map((b) => b.attributes('aria-label'))).toEqual([t('node.viewGrid')])
    expect(useViewMode().viewMode.value, 'the stored preference is kept').toBe('map')
    const trips = await mountAt('#~me/trips')
    expect(trips.find('.space-map-stub').exists(), "Memories' presentation replaces the list").toBe(true)
    expect(trips.find('ul.veyq-level-list').exists()).toBe(false)
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

  // Depot's row factory lists a file at the level beside the Memory (inbox #407: one list, mixed by name, not grouped by
  // application and not Memories-first as the sections were); no fallback row for it.
  it("lists a FILE child as Depot's row beside the Memory, with no unsupported fallback", async () => {
    treeStore.nodes = [...TREE.map((n) => ({ ...n })), node({ id: 'report', kind: FILE_KIND, name: 'report.pdf', parentId: 'trips' })]
    const w = await mountAt('#~me/trips')
    // Order is by name now (Italy < report.pdf), whatever the kind - it used to be Memories' section, then Depot's.
    expect(titlesOf(w)).toEqual(['Italy', 'report.pdf'])
    expect(w.find('li.veyq-level-item[data-kind="FILE"] .veyq-level-item-meta').text()).toContain(t('depot.kindFile'))
    expect(w.find('li.is-unsupported').exists()).toBe(false)
    expect(w.text()).not.toContain(t('home.unsupportedKind'))
  })

  // A row's `rename` (F1b: a file is renamed from its row, it never opens) reaches the host through core's level view:
  // core's name dialog opens in rename mode for THAT node, at its level - by a real click on the row's menu entry.
  it("a row's rename opens core's name dialog in rename mode for the node it names", async () => {
    const report = node({ id: 'report', kind: FILE_KIND, name: 'report.pdf', parentId: 'trips' })
    treeStore.nodes = [...TREE.map((n) => ({ ...n })), report]
    const w = await mountAt('#~me/trips')
    expect(w.find('.name-stub').exists()).toBe(false)
    await clickAction(w, 'report.pdf', 'rename')
    // a rename names no parent: the node is renamed where it is
    expect(w.find('.name-stub').text()).toBe('rename|-|report.pdf')
  })

  it('opens a Memory to the registered detail: content key, ordered chapters bucket, shareable', async () => {
    const w = await mountAt('#~me/trips/memories/italy')
    const d = w.find('.detail-stub')
    expect(d.text()).toBe('Italy|Day1,Day2')
    expect(d.attributes('data-key')).toBe('9')
    expect(d.attributes('data-shareable')).toBe('true')
    expect(w.find('ul.veyq-level-list').exists(), 'the detail replaces the level').toBe(false)
  })

  // A chapter is a share root of its own (ADR 0080 Decision 13): the same detail offers the share affordance, and its one
  // dialog opens for the chapter itself — its id, its own K_node (the key chain's, not the Memory's) and its name as the
  // tree opened it, which the dialog re-seals under K_share.
  it('opens a chapter to the same detail, shareable, and shares the chapter itself (whatever the offer)', async () => {
    const w = await mountAt('#~me/trips/memories/italy/day1')
    expect(w.find('.detail-stub').text()).toBe('Day1|')
    expect(w.find('.detail-stub').attributes('data-shareable')).toBe('true')

    const detail = w.findComponent({ name: 'MemoryDetailStub' })
    // One share dialog at a time: opened with every role, then with the upload role alone.
    const shared: { text: string; name?: string; key?: string; kind?: string; contribute?: string }[] = []
    for (const ask of [
      () => detail.vm.$emit('share', { view: true, contribute: true, upload: true }),
      () => detail.vm.$emit('share', { view: false, contribute: false, upload: true }),
    ]) {
      ask()
      await flushPromises()
      const dialog = w.findAll('.share-stub')
      expect(dialog).toHaveLength(1)
      shared.push({ text: dialog[0].text(), name: dialog[0].attributes('data-name'), key: dialog[0].attributes('data-key'), kind: dialog[0].attributes('data-kind'), contribute: dialog[0].attributes('data-contribute') })
    }
    expect(shared.map((s) => s.text)).toEqual(['day1|view+contribute+upload', 'day1|upload'])
    expect(shared.map((s) => s.name)).toEqual(['Day1', 'Day1'])
    expect(shared.map((s) => s.key), "the chapter's own key, not its Memory's (9)").toEqual(['7', '7'])
    // the node's kind travels with it, so the application's dialog can say "chapter"
    expect(shared.map((s) => s.kind)).toEqual(['CHAPTER', 'CHAPTER'])
    // the offer the detail's rights function made reaches the dialog as props, role by role (the host never reads it again)
    expect(shared.map((s) => s.contribute)).toEqual(['true', 'false'])
  })

  it('hides the share affordance when the instance turns public links off, even for a shareable kind', async () => {
    capabilityState.ids = [MEMORIES, DEPOT]
    const w = await mountAt('#~me/trips/memories/italy')
    expect(w.find('.detail-stub').attributes('data-shareable')).toBe('false')
  })

  it("wires the detail to the kind's dialogs: the rename in place (core's write), create-chapter, share, delete", async () => {
    const w = await mountAt('#~me/trips/memories/italy')
    const detail = w.findComponent({ name: 'MemoryDetailStub' })

    // A Memory's rename is its header's name, edited in place through the page's `renameTo` door (ADR 0110 decision 11) -
    // no name dialog (`SpaceView.rename.test.ts` writes through it).
    expect(typeof detail.props('renameTo'), "the page's rename in place").toBe('function')

    detail.vm.$emit('createChapter')
    await flushPromises()
    // CHAPTER's createDialog (Memories'), under the open Memory and its key
    expect(w.find('.create-stub').text()).toBe('CHAPTER|italy')
    expect(w.find('.create-stub').attributes('data-key')).toBe('9')
    expect(w.find('.name-stub').exists()).toBe(false)

    detail.vm.$emit('share', { view: true, contribute: false, upload: false })
    await flushPromises()
    expect(w.findAll('.share-stub').map((s) => s.text())).toEqual(['italy|view'])
    // control: an offer without contribute reaches the dialog as false (fail closed)
    expect(w.find('.share-stub').attributes('data-contribute')).toBe('false')
    detail.vm.$emit('share', { view: false, contribute: false, upload: true })
    await flushPromises()
    expect(w.findAll('.share-stub').map((s) => s.text()), 'a second share opens the one dialog afresh, with its own offer').toEqual(['italy|upload'])

    detail.vm.$emit('delete')
    await flushPromises()
    expect(w.find('.delete-stub').text(), "the page's own node: core's one question with its Undo").toBe('Italy')
  })

  it("wires the level events: a create by kind (MEMORY's dialog from the New menu, core's for a folder), trash, and opening a child navigates", async () => {
    const w = await mountAt('#~me')

    await chooseNew(w, t('memory.create')) // the real registry's entry of the Memory kind
    await flushPromises()
    expect(w.find('.create-stub').text()).toBe('MEMORY|-')
    expect(w.find('.name-stub').exists()).toBe(false)
    await chooseNew(w, t('node.folderCreate'))
    await flushPromises()
    expect(w.find('.name-stub').text()).toBe('create|-|-')
    expect(w.find('.create-stub').exists(), 'the Memory dialog gives way').toBe(false)

    await w.find(`.veyq-level-toolbar button[aria-label="${t('node.trash.open')}"]`).trigger('click')
    await flushPromises()
    expect(w.find('.trash-stub').exists()).toBe(true)

    await rowOf(w, 'Solo').find('button.veyq-level-item-main').trigger('click')
    await flushPromises()
    expect(w.find('.detail-stub').text()).toBe('Solo|')
  })

  // A kind whose application registered no createDialog (Depot's FILE, F0) has no create entry at all in the New menu
  // (Depot's upload is a level action, not a create of a kind): choosing "Upload files" opens no dialog of another kind and
  // no core dialog, and the level stays as it was; control: the entry with a registered dialog opens it.
  it('a New entry of a kind with no createDialog opens nothing', async () => {
    const w = await mountAt('#~me/trips')
    const labels = await newMenuLabels(w)
    expect(labels).toContain(t('depot.upload'))
    await chooseNew(w, t('depot.upload'))
    expect(w.find('.create-stub').exists()).toBe(false)
    expect(w.find('.name-stub').exists()).toBe(false)
    expect(titlesOf(w), 'the level is untouched').toEqual(['Italy'])
    await chooseNew(w, t('memory.create')) // control: the same path with a registered dialog opens it
    expect(w.find('.create-stub').text()).toBe('MEMORY|trips')
  })

  it('wires the folder header (rename in place, delete for the folder) and a folder row navigates', async () => {
    const w = await mountAt('#~me/trips')
    await w.find('.veyq-node-header button[data-testid="node-header-rename"]').trigger('click')
    await flushPromises()
    expect(w.find('.veyq-node-header input[data-testid="node-header-name-input"]').exists(), 'the name, editable in place').toBe(true)
    expect(w.find('.name-stub').exists(), 'no name dialog').toBe(false)

    // The header's delete is the row's (inbox #385 (c)): core's one question with its Undo, `DeleteNodesDialog`, for the one
    // folder - the kind's own question and the Undo are that dialog's (`DeleteNodesDialog.test.ts`).
    await chooseHeaderEntry(w, 'delete')
    const question = w.findComponent({ name: 'DeleteNodesStub' })
    expect(question.find('.delete-stub').text()).toBe('Trips')
    expect(question.props('items')).toEqual([{ node: expect.objectContaining({ id: 'trips' }) }])
    expect(typeof question.props('afterUndo')).toBe('function')
    const hash = () => (w.vm as unknown as { $router: { currentRoute: { value: { hash: string } } } }).$router.currentRoute.value.hash
    // refused (the folder is still there): the page stays
    question.vm.$emit('done')
    await flushPromises()
    expect(hash()).toBe('#~me/trips/') // the folder's own path, kept
    // it went: the page is left for its parent
    const before = treeStore.nodes
    treeStore.nodes = before.filter((n) => n.id !== 'trips' && n.parentId !== 'trips')
    question.vm.$emit('done')
    await flushPromises()
    expect(hash()).toBe('#~me')
    treeStore.nodes = before

    const root = await mountAt('#~me')
    await rowOf(root, 'Trips').find('button.veyq-level-item-main').trigger('click')
    await flushPromises()
    expect(titlesOf(root)).toEqual(['Italy'])
    expect(root.find('.veyq-node-header-title').text()).toBe('Trips')
  })
})

// uc.sharing.share-public-viewer:folder-share-root: a FOLDER is shareable (core's descriptor), so its level view offers
// the share button exactly as a Memory's detail does — descriptor AND right — and the share opens FOLDER's share dialog
// (core's, pre-registered by the registry, P4c) with the folder's own key from the chain, never Memories' dialog.
describe('SpaceView sharing a folder', () => {
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
    // owner 2026-11-04: READ, READ_WRITE and the upload-only link (Depot's drop box), with the folder's scope epoch its create states
    expect(dialog.attributes('data-offer')).toBe('true|true|true')
    expect(dialog.attributes('data-scope-epoch')).toBe(String(treeStore.nodes.find((n) => n.id === 'trips')!.scopeEpoch))
    expect(w.find('.share-stub').exists(), "never the application's Memory dialog").toBe(false)
  })

  it('offers the folder share for the upload-only link alone where public links are off but drop boxes on', async () => {
    capabilityState.ids = [MEMORIES, DEPOT, CORE_PUBLIC_SHARES_DROP_BOX]
    const w = await mountAt('#~me/trips')
    await shareButton(w).trigger('click')
    await flushPromises()
    expect(w.get('.folder-share-stub').attributes('data-offer')).toBe('false|false|true')
  })

  // Owner 2026-11-04: the space's top level is no link's root, but a drop box may be born there with its link - its header's
  // share offers that upload-only link alone (core's folder dialog with no node), never READ or READ_WRITE.
  it('offers the top level a share that opens core\'s dialog with no node, for the upload-only link alone', async () => {
    const w = await mountAt('#~me')
    expect(w.get('.veyq-node-header-title').text()).toBe(t('shell.personal'))
    expect(shareButton(w).exists()).toBe(true)
    await shareButton(w).trigger('click')
    await flushPromises()
    const dialog = w.get('.folder-share-stub')
    expect(dialog.text()).toBe('scope-1|undefined')
    expect(dialog.attributes('data-offer')).toBe('false|false|true')
  })

  it.each([
    ['the instance\'s drop-box leaf is off', [MEMORIES, DEPOT, CORE_PUBLIC_SHARES, CORE_PUBLIC_SHARES_CONTRIBUTE]],
    ['Depot is switched off', [MEMORIES, CORE_PUBLIC_SHARES, CORE_PUBLIC_SHARES_CONTRIBUTE, CORE_PUBLIC_SHARES_DROP_BOX]],
  ])('offers no share at the top level where %s (the space is no node: nothing else is shared there)', async (_, ids) => {
    capabilityState.ids = ids
    const w = await mountAt('#~me')
    // The header names the space (its top level's h1), the control that it rendered.
    expect(w.get('.veyq-node-header-title').text()).toBe(t('shell.personal'))
    expect(w.find('.veyq-node-header-actions').exists()).toBe(false)
    expect(shareButton(w).exists()).toBe(false)
  })

  it('hides the folder share when the instance turns public links off', async () => {
    capabilityState.ids = [MEMORIES, DEPOT]
    const w = await mountAt('#~me/trips')
    expect(shareButton(w).exists()).toBe(false)
    expect(w.find('.veyq-node-header button[data-testid="node-header-rename"]').exists(), 'control: the card renders').toBe(true)
  })
})

describe('SpaceView entering a scope', () => {
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

// Back to where the member was (`core/node/returnTo.ts`): going UP - a crumb, Back, a crumb further up - hands the level the
// child the member came from, which it brings back into sight; going down, or into another scope, hands it nothing.
describe('SpaceView coming back up the tree', () => {
  it('hands the level the child it came up from, by crumb and by Back alike', async () => {
    const w = await mountAt('#~me')
    const router = (w.vm as unknown as { $router: import('vue-router').Router }).$router
    const returnTo = () => w.findComponent(LevelView).props('returnTo')
    expect(returnTo(), 'a first view came from nowhere').toBeNull()

    await rowOf(w, 'Trips').find('button.veyq-level-item-main').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.hash).toBe('#~me/trips/')
    expect(returnTo(), 'a step down').toBeNull()

    await rowOf(w, 'Italy').find('button.veyq-level-item-main').trigger('click')
    await flushPromises()
    expect(w.findComponent(LevelView).exists(), 'the Memory is open').toBe(false)
    router.back()
    await flushPromises()
    expect(router.currentRoute.value.hash).toBe('#~me/trips/')
    expect(returnTo(), 'Back from the Memory').toBe('italy')

    await router.push({ hash: '#~me' }) // what a crumb does (`AppTopbar`)
    await flushPromises()
    expect(returnTo(), 'the crumb of the scope root').toBe('trips')
  })

  it('a crumb further up names the child the member came through', async () => {
    const w = await mountAt('#~me/trips/memories/italy')
    expect(w.findComponent(LevelView).exists()).toBe(false)
    const router = (w.vm as unknown as { $router: import('vue-router').Router }).$router
    await router.push({ hash: '#~me' })
    await flushPromises()
    expect(w.findComponent(LevelView).props('returnTo')).toBe('trips')
  })

  it('a move into another scope is no way up', async () => {
    const w = await mountAt('#~me/trips/')
    const router = (w.vm as unknown as { $router: import('vue-router').Router }).$router
    await router.push({ hash: '' }) // the tenant's top level: the personal tree's ids name nothing there
    await flushPromises()
    expect(w.findComponent(LevelView).props('returnTo')).toBeNull()
  })
})

// Inbox #328 over real URLs (ADR 0015, amended 2026-10-11): the host resolves a fragment by `location.ts`'s grammar —
// a folder ends in a slash, a Depot node is its plain name, a Memory sits behind Memories' segment.
describe('SpaceView resolving the fragment grammar', () => {
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
    expect(titlesOf(old)).toContain('Solo') // collapsed to the scope root
    expect(old.get('.veyq-node-header-title').text()).toBe(t('shell.personal'))
  })

  it('a slug shared by a file and a folder: the file without the slash, the folder with it or with a child behind it', async () => {
    const file = await mountAt('#~me/trips/report')
    expect(file.find('ul.veyq-level-list').exists()).toBe(false)
    expect(file.find('.file-detail-stub').text()).toBe('file=Report') // a file opens its detail (Depot's FileDetail)
    const folder = await mountAt('#~me/trips/report/')
    // (the folder beside the same-named file is headed "Report (2)": equal names are numbered across kinds)
    expect(folder.find('.veyq-node-header').exists()).toBe(true)
    expect(titlesOf(folder)).toEqual(['Inner'])
    const inner = await mountAt('#~me/trips/report/inner/')
    expect(inner.find('.veyq-node-header-title').text()).toBe('Inner')
  })

  it('a folder called "Memories" lives at `memories-2`; the bare application segment opens nothing', async () => {
    expect((await mountAt('#~me/memories-2/')).find('.veyq-node-header-title').text()).toBe('Memories')
    const bare = await mountAt('#~me/memories/')
    expect(bare.get('.veyq-node-header-title').text(), 'collapsed to the scope root').toBe(t('shell.personal'))
    expect(titlesOf(bare)).toContain('Solo')
  })

  it('clicking a folder card pushes the fragment `location.ts` builds: the folder\'s path with its slash', async () => {
    const w = await mountAt('#~me')
    await rowOf(w, 'Trips').find('button.veyq-level-item-main').trigger('click')
    await flushPromises()
    expect(w.vm.$route.hash).toBe('#~me/trips/')
    expect(w.find('.veyq-node-header-title').text()).toBe('Trips')
  })
})
