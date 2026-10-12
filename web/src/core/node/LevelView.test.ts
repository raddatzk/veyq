// @vitest-environment happy-dom
// Core's folder level view (cr-files-application P4b) against a STUBBED registry (a fresh `createRegistry()` per test
// behind a delegating `registry` export), so each test chooses which kinds bring which section. Kinds here are made-up
// tokens (`ALPHA`, `BETA`, …): core's view knows no application, and neither does its test. The overview-card cases
// moved here from the former `MemoryBrowse.test.ts`. The real registry with Memories and Files registered is in
// `views/MemoryHomeView.test.ts`; the Memories level as a member meets it, in the golden.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, h, type PropType } from 'vue'

const holder = vi.hoisted(() => ({ reg: null as null | import('../applications/registry').Registry }))
vi.mock('../applications/registry', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../applications/registry')>()
  const r = () => (holder.reg ??= actual.createRegistry())
  return {
    ...actual,
    // A Proxy, not a method list: a method the registry gains later is delegated too.
    registry: new Proxy({} as import('../applications/registry').Registry, {
      get: (_t, name: string) => (r() as unknown as Record<string, (...a: unknown[]) => unknown>)[name].bind(r()),
    }),
  }
})

import LevelView from './LevelView.vue'
import { createRegistry, type Registry } from '../applications/registry'
import { childrenOf } from './browse'
import { FOLDER_KIND, type NodeKind } from './kind'
import type { TreeNode } from './tree'
import { setViewMode, useViewMode, type ViewMode } from '../../shared/state/useViewMode'
import { realI18n, t } from '../../test/i18n'
import { chooseNew, hasNewMenu, isDisabled, newMenuLabels, openNewMenu } from '../../test/newMenu'
import { useLevelAction } from './levelActions'
import { treeNode, memberAccessOf, memberTreeOf, guestAccessOf } from '../../test/models'
import { ShareRole } from '../../api/generated/sharing'
import type { Access, TreeSource } from '../access'

/** A section stub: renders its name, what it was handed, and offers buttons for the three events. */
function sectionStub(name: string) {
  return defineComponent({
    name: `Section${name}`,
    props: {
      folder: { type: Object as PropType<TreeNode | null>, default: null },
      nodes: { type: Array as PropType<readonly TreeNode[]>, required: true },
      epochs: { type: Object, default: null },
      access: { type: Object as PropType<Access>, required: true },
      tree: { type: Object as PropType<TreeSource>, required: true },
      viewMode: { type: String as PropType<ViewMode>, required: true },
    },
    emits: ['openNode', 'create', 'rename'],
    setup: (p, { emit }) => () =>
      h('section', {
        class: 'stub-section', 'data-name': name, 'data-folder': p.folder?.name ?? '-', 'data-mode': p.access.mode,
        'data-readonly': String(p.access.mode === 'member' && p.access.readonly),
        'data-identity': String(p.tree.inIdentityFolder(p.folder?.id ?? null)), 'data-view': p.viewMode,
      }, [
        h('span', { class: 'stub-nodes' }, p.nodes.map((n) => n.name).join(',')),
        h('button', { class: 'stub-open', onClick: () => emit('openNode', p.nodes[0]) }),
        h('button', { class: 'stub-create', onClick: () => emit('create', `create-${name}`) }),
        h('button', { class: 'stub-rename', onClick: () => emit('rename', p.nodes[0]) }),
      ]),
  })
}
const A = sectionStub('a')
const B = sectionStub('b')

function node(id: string, kind: NodeKind, parentId: string | null = null, orderIndex = 0): TreeNode {
  return treeNode({ id, kind, name: id, parentId, orderIndex, epoch: 0, wrappedContentKey: 'eA==', wrappedMeta: null, metaVersion: 0, placementVersion: 0 })
}

const TRIPS = node('Trips', FOLDER_KIND)
/** The level as a member sees it. `pending`: the scope is pending deletion (`access.readonly`); `identityFolder`: the folder
 *  is a guest's identity folder (what the member's tree answers); `publicLinks`: the instance offers public links. */
function mountLevel(
  tree: TreeNode[],
  over: { folder?: TreeNode | null; pending?: boolean; identityFolder?: boolean; publicLinks?: boolean; folderName?: string } = {},
) {
  const folder = over.folder ?? null
  const source = memberTreeOf(() => tree)
  return mount(LevelView, {
    props: {
      folder, level: childrenOf(tree, folder?.id ?? null),
      access: memberAccessOf({ readonly: over.pending ?? false, publicLinks: over.publicLinks ?? false }),
      tree: { ...source, inIdentityFolder: () => over.identityFolder ?? false },
      folderName: over.folderName,
    },
    global: { plugins: [realI18n()], directives: { tooltip: {} } },
  })
}

/** The level's top-level blocks in DOM order, each named by what it is — but the toolbar, which has its own tests. */
function blocks(w: ReturnType<typeof mountLevel>): string[] {
  return [...w.element.children].filter((el) => !el.classList.contains('veyq-level-toolbar')).map((el) => {
    if (el.classList.contains('veyq-empty')) return `empty:${el.textContent?.trim()}`
    if (el.classList.contains('stub-section')) return `section:${el.getAttribute('data-name')}`
    if (el.classList.contains('veyq-level-fallback')) return `fallback:${el.querySelector('h2')?.textContent}`
    if (el.classList.contains('veyq-node-header')) return 'overview'
    if (el.querySelector('h2')?.textContent === t('node.folders')) return 'folders'
    return `?${el.className}`
  })
}

beforeEach(() => {
  setViewMode('grid')
  holder.reg = createRegistry()
})
const reg = (): Registry => holder.reg!

describe('LevelView: application sections', () => {
  it('renders the sections sorted by order, whatever the registration order, between the overview and the folders', () => {
    reg().registerKind({ kind: 'BETA', icon: 'pi pi-b', shareable: false, section: { order: 20, component: B } })
    reg().registerKind({ kind: 'ALPHA', icon: 'pi pi-a', shareable: false, section: { order: 10, component: A } })
    const w = mountLevel([TRIPS, node('a1', 'ALPHA', 'Trips'), node('b1', 'BETA', 'Trips')], { folder: TRIPS })
    expect(blocks(w)).toEqual(['overview', 'section:a', 'section:b', 'folders'])
    expect(w.findAll('.stub-nodes').map((s) => s.text())).toEqual(['a1', 'b1'])
  })

  it('renders a section two kinds share ONCE, handed the children of both (kinds in registration order)', () => {
    reg().registerKind({ kind: 'ALPHA', icon: 'pi pi-a', shareable: false, section: { order: 10, component: A } })
    reg().registerKind({ kind: 'ALPHA_PART', icon: 'pi pi-a', shareable: false, section: { order: 10, component: A } })
    const w = mountLevel([node('p1', 'ALPHA_PART'), node('a1', 'ALPHA'), node('a2', 'ALPHA', null, 1)])
    expect(blocks(w)).toEqual(['section:a', 'folders'])
    expect(w.find('.stub-nodes').text()).toBe('a1,a2,p1')
    expect(w.find('.veyq-level-fallback').exists(), 'neither kind falls back').toBe(false)
  })

  it('renders a section with no child of its kinds at this level (its own empty state and create action are its own)', () => {
    reg().registerKind({ kind: 'ALPHA', icon: 'pi pi-a', shareable: false, section: { order: 10, component: A } })
    const w = mountLevel([])
    expect(blocks(w)).toEqual(['section:a', 'folders'])
    expect(w.find('.stub-nodes').text()).toBe('')
  })

  it('hands each section the folder, the access and the tree, and forwards its openNode, create and rename', async () => {
    reg().registerKind({ kind: 'ALPHA', icon: 'pi pi-a', shareable: false, section: { order: 10, component: A } })
    const a1 = node('a1', 'ALPHA', 'Trips')
    const w = mountLevel([TRIPS, a1], { folder: TRIPS, pending: true })
    expect(w.find('.stub-section').attributes('data-folder')).toBe('Trips')
    expect(w.find('.stub-section').attributes('data-mode')).toBe('member')
    expect(w.find('.stub-section').attributes('data-readonly')).toBe('true')
    expect(w.findComponent(A).props('access')).toBe(w.props('access'))
    expect(w.findComponent(A).props('tree')).toBe(w.props('tree'))
    await w.find('.stub-open').trigger('click')
    await w.find('.stub-create').trigger('click')
    await w.find('.stub-rename').trigger('click')
    expect(w.emitted('openNode')).toEqual([[a1]])
    expect(w.emitted('create')).toEqual([['create-a']])
    // a section's child renamed in place reaches the host as `renameNode` with that node (never as the folder's rename)
    expect(w.emitted('renameNode')).toEqual([[a1]])
    expect(w.emitted('renameFolder')).toBeUndefined()
  })
})

// Inbox #391/#401: where nothing is placed (a guest's identity folder) a member still deletes — what a section offers
// there is its application's capability over the access and the tree it is handed; the level only passes them on.
describe('LevelView: what a section learns about the folder', () => {
  beforeEach(() => reg().registerKind({ kind: 'ALPHA', icon: 'pi pi-a', shareable: false, section: { order: 10, component: A } }))
  const handed = (over: { pending?: boolean; identityFolder?: boolean }) => {
    const s = mountLevel([TRIPS, node('a1', 'ALPHA', 'Trips')], { folder: TRIPS, ...over }).find('.stub-section')
    return [s.attributes('data-readonly'), s.attributes('data-identity')]
  }

  it('hands a section the pending deletion and the identity-folder answer apart, never folded into one flag', () => {
    expect(handed({})).toEqual(['false', 'false'])
    expect(handed({ identityFolder: true })).toEqual(['false', 'true'])
    expect(handed({ pending: true })).toEqual(['true', 'false'])
  })
})

describe('LevelView: the fallback section (a kind no section serves)', () => {
  it('lists a registered kind without a section: its icon, each name and the unsupported state, not clickable', () => {
    reg().registerKind({ kind: 'ALPHA', icon: 'pi pi-a', shareable: false, section: { order: 10, component: A } })
    reg().registerKind({ kind: 'LEAF', icon: 'pi pi-leaf', shareable: false })
    const w = mountLevel([node('a1', 'ALPHA'), node('l1', 'LEAF'), node('l2', 'LEAF', null, 1)])
    expect(blocks(w)).toEqual(['section:a', 'fallback:LEAF', 'folders'])
    const fb = w.find('.veyq-level-fallback')
    expect(fb.find('.veyq-section-header span').text()).toBe('2')
    const rows = fb.findAll('.veyq-node-card')
    expect(rows.map((r) => r.find('strong').text())).toEqual(['l1', 'l2'])
    for (const r of rows) {
      expect(r.find('.pi-leaf').exists()).toBe(true)
      expect(r.text()).toContain(t('home.unsupportedKind'))
      expect(r.element.tagName).toBe('DIV')
    }
    expect(fb.findAll('button')).toHaveLength(0)
  })

  it('lists a kind nobody registered too (a generic icon), one fallback per kind, sorted by kind', () => {
    const w = mountLevel([node('z1', 'ZULU'), node('y1', 'YANKEE')])
    expect(blocks(w)).toEqual(['fallback:YANKEE', 'fallback:ZULU', 'folders'])
    expect(w.findAll('.veyq-level-fallback .pi-question-circle')).toHaveLength(2)
  })

  it('renders no fallback when every child kind has a section', () => {
    reg().registerKind({ kind: 'ALPHA', icon: 'pi pi-a', shareable: false, section: { order: 10, component: A } })
    expect(mountLevel([node('a1', 'ALPHA')]).find('.veyq-level-fallback').exists()).toBe(false)
  })
})

describe('LevelView: the folders section', () => {
  it('lists the sub-folders and opens one; the folder is created from the "New" menu, no tile', async () => {
    const rome = node('Rome', FOLDER_KIND, 'Trips')
    const w = mountLevel([TRIPS, rome], { folder: TRIPS })
    const card = w.findAll('button.veyq-folder-card').find((b) => b.text() === 'Rome')!
    await card.trigger('click')
    expect(w.emitted('openNode')).toEqual([[rome]])
    expect(w.find('.veyq-level-grid .veyq-create-card').exists(), 'the create tile is retired (inbox #406)').toBe(false)
    await chooseNew(w, t('node.folderCreate'))
    expect(w.emitted('create')).toEqual([[FOLDER_KIND]])
  })

  // inbox #385 (g): a tile is named by its text alone — its icon glyph is hidden from assistive technology.
  it('names each folder card by its text, its icon hidden', () => {
    const w = mountLevel([TRIPS, node('Rome', FOLDER_KIND, 'Trips')], { folder: TRIPS })
    expect(w.find('button.veyq-folder-card .pi').attributes('aria-hidden')).toBe('true')
  })

  // Two folders of one name (a drop box's guests) are told apart as everywhere: the first created keeps it.
  it('numbers sub-folders of one name in creation order', () => {
    const later = treeNode({ ...node('f-a', FOLDER_KIND), name: 'Anna', createdAt: 2000 })
    const first = treeNode({ ...node('f-b', FOLDER_KIND), name: 'Anna', createdAt: 1000 })
    // listed in the level's order (the later one first here), numbered by creation: the earlier keeps the plain name
    expect(mountLevel([later, first]).findAll('.veyq-folder-card strong').map((s) => s.text())).toEqual(['Anna (2)', 'Anna'])
  })

  it('shows its empty state when the level has no folder', () => {
    expect(mountLevel([]).text()).toContain(t('node.folderEmpty'))
  })

  it('steps aside in map mode (the map spans the subtree) and takes the list variant in list mode', () => {
    setViewMode('list')
    expect(mountLevel([]).find('.veyq-level-grid').classes()).toContain('is-list')
    setViewMode('map')
    // the map only where a shown section draws one (inbox #404): without one, the folders stay, in the grid
    expect(blocks(mountLevel([]))).toEqual(['folders'])
    const MAP = { mode: 'map', icon: 'pi pi-map', label: 'node.folders' } as const
    reg().registerKind({ kind: 'ALPHA', icon: 'pi pi-a', shareable: false, section: { order: 10, component: A, views: [MAP] } })
    expect(blocks(mountLevel([]))).toEqual(['section:a'])
  })

  it('read-only (a pending deletion, a guest\'s identity folder): no "New" menu, the folder cards stay', () => {
    for (const over of [{ pending: true }, { identityFolder: true }]) {
      const w = mountLevel([TRIPS, node('Rome', FOLDER_KIND, 'Trips')], { folder: TRIPS, ...over })
      expect(hasNewMenu(w)).toBe(false)
      expect(w.text()).toContain('Rome')
    }
  })
})

// cr-node-key-hierarchy 5b.3 (moved from `MemoryBrowse.test.ts`): the folder overview card carries the share button
// core's folder capabilities allow (the instance's public links, a folder, not a guest's identity folder), with a
// Memory's icon and label.
describe('LevelView: the folder overview card', () => {
  const shareButton = (w: ReturnType<typeof mountLevel>) => w.find(`button[aria-label="${t('share.share')}"]`)

  // Inbox #393 (b): the heading says what the list said ("Anna (2)"), when the host knows it; else the folder's name.
  it('heads the card with the host\'s display name, else the folder\'s own', () => {
    expect(mountLevel([TRIPS], { folder: TRIPS, folderName: 'Trips (2)' }).find('.veyq-node-header-title').text()).toBe('Trips (2)')
    expect(mountLevel([TRIPS], { folder: TRIPS }).find('.veyq-node-header-title').text()).toBe('Trips')
  })

  it('shows no overview card at the scope root', () => {
    expect(blocks(mountLevel([]))).toEqual(['folders'])
  })

  it('shows the share button on a shareable folder and emits share', async () => {
    const w = mountLevel([TRIPS], { folder: TRIPS, publicLinks: true })
    expect(shareButton(w).exists()).toBe(true)
    expect(shareButton(w).find('.pi-share-alt').exists()).toBe(true)
    await shareButton(w).trigger('click')
    expect(w.emitted('share')).toHaveLength(1)
  })

  it('shows none when the instance offers no public links', () => {
    const w = mountLevel([TRIPS], { folder: TRIPS, publicLinks: false })
    expect(shareButton(w).exists()).toBe(false)
    expect(w.find(`button[aria-label="${t('node.rename')}"]`).exists(), 'control: the card actions render').toBe(true)
  })

  it('emits renameFolder and deleteFolder from its actions', async () => {
    const w = mountLevel([TRIPS], { folder: TRIPS })
    await w.find(`button[aria-label="${t('node.rename')}"]`).trigger('click')
    await w.find(`button[aria-label="${t('node.delete')}"]`).trigger('click')
    expect(w.emitted('renameFolder')).toHaveLength(1)
    expect(w.emitted('deleteFolder')).toHaveLength(1)
  })

  // Found in the browser, 2026-10-07: the three carried `memory-action-collapsible` / `memory-action-inline`, which
  // memory.css hid at 721–1120 px and ≤ 720 px — and this card has no overflow menu to offer them instead. jsdom has no
  // layout, so the contract pinned is the DOM one: no width-hiding class on any of them.
  it('keeps share, rename, move and delete free of any width-hiding class (the card has no overflow menu)', () => {
    const w = mountLevel([TRIPS], { folder: TRIPS, publicLinks: true })
    const actions = w.findAll('.veyq-node-header-actions button')
    expect(actions.map((b) => b.attributes('aria-label'))).toEqual([t('share.share'), t('node.rename'), t('node.move.action'), t('node.delete')])
    for (const b of actions) {
      expect(b.classes()).not.toContain('memory-action-collapsible')
      expect(b.classes()).not.toContain('memory-action-inline')
    }
  })

  it('keeps the share (a read of the links) but no rename/delete during a pending deletion', () => {
    const w = mountLevel([TRIPS], { folder: TRIPS, publicLinks: true, pending: true })
    expect(shareButton(w).exists()).toBe(true)
    expect(w.find(`button[aria-label="${t('node.rename')}"]`).exists()).toBe(false)
  })

  // A guest's identity folder (inbox #385): a member looks, downloads and deletes there — the folder itself offers no
  // share, rename or delete; its one action is the move OUT of the drop box (inbox #358, #377 (a)).
  it('offers only the move out on a guest\'s identity folder', async () => {
    const w = mountLevel([TRIPS], { folder: TRIPS, publicLinks: true, identityFolder: true })
    const actions = w.findAll('.veyq-node-header-actions button')
    expect(actions.map((b) => b.attributes('aria-label'))).toEqual([t('node.move.action')])
    await actions[0].trigger('click')
    expect(w.emitted('moveNode')).toEqual([[TRIPS]])
  })

  it('offers no move during a pending deletion', () => {
    const w = mountLevel([TRIPS], { folder: TRIPS, publicLinks: true, pending: true })
    expect(w.find(`button[aria-label="${t('node.move.action')}"]`).exists()).toBe(false)
  })
})

// inbox #370/#385 (f): the space's trash holds every application's nodes, so it is the level view's own button — no
// section's (it sat in Memories' header, where it read as Memories' trash).
describe('LevelView: the toolbar', () => {
  const trash = (w: ReturnType<typeof mountLevel>) => w.findAll('.veyq-level-toolbar button').find((b) => b.text() === t('node.trash.open'))

  it('offers the space\'s trash first, at the root and in a folder, and emits openTrash', async () => {
    reg().registerKind({ kind: 'ALPHA', icon: 'pi pi-a', shareable: false, section: { order: 10, component: A } })
    for (const w of [mountLevel([]), mountLevel([TRIPS], { folder: TRIPS })]) {
      expect(w.element.firstElementChild?.classList.contains('veyq-level-toolbar')).toBe(true)
      await trash(w)!.trigger('click')
      expect(w.emitted('openTrash')).toHaveLength(1)
    }
  })

  it('has no trash where nothing is written (a pending deletion, a guest\'s folder)', () => {
    expect(trash(mountLevel([], { pending: true }))).toBeUndefined()
    // an empty identity folder shows no section and no folders: nothing to switch either, so no toolbar at all
    expect(mountLevel([TRIPS], { folder: TRIPS, identityFolder: true }).find('.veyq-level-toolbar').exists()).toBe(false)
  })
})

// inbox #370: the view switch governs every section and the folders, so it is the level's — offered as the union of the
// presentations on screen: the folders' grid and list, and each shown section's `views` from its kind's descriptor.
describe('LevelView: the view switch', () => {
  const MAP = { mode: 'map', icon: 'pi pi-map', label: 'node.folders' } as const // core's test names no application key
  const trash = (w: ReturnType<typeof mountLevel>) => w.findAll('.veyq-level-toolbar button').find((b) => b.text() === t('node.trash.open'))
  const switchLabels = (w: ReturnType<typeof mountLevel>) =>
    w.findAll('.veyq-level-toolbar .view-mode-button').map((b) => b.attributes('aria-label'))

  it('offers the folders\' grid and list where no section brings a view; a click switches the folders grid', async () => {
    reg().registerKind({ kind: 'ALPHA', icon: 'pi pi-a', shareable: false, section: { order: 10, component: A } })
    const w = mountLevel([TRIPS])
    expect(switchLabels(w)).toEqual([t('node.viewGrid'), t('node.viewList')])
    await w.findAll('.view-mode-button')[1].trigger('click')
    expect(w.find('.veyq-level-grid').classes()).toContain('is-list')
  })

  it('adds a section\'s own view, each mode once in the order first named; its mode takes the folders section away', async () => {
    reg().registerKind({ kind: 'ALPHA', icon: 'pi pi-a', shareable: false, section: { order: 10, component: A, views: [{ mode: 'grid', icon: 'pi pi-th-large', label: 'node.viewGrid' }, MAP] } })
    const w = mountLevel([TRIPS])
    expect(switchLabels(w)).toEqual([t('node.viewGrid'), t('node.folders'), t('node.viewList')])
    await w.findAll('.view-mode-button')[1].trigger('click')
    expect(blocks(w)).toEqual(['section:a'])
  })

  it('is offered to a guest too, without the trash; and where only one presentation is on screen, not at all', () => {
    reg().registerKind({ kind: 'ALPHA', icon: 'pi pi-a', shareable: false, section: { order: 10, component: A } })
    const guest = mount(LevelView, {
      props: {
        folder: TRIPS, level: childrenOf([TRIPS], TRIPS.id), access: guestAccessOf(ShareRole.Read), tree: memberTreeOf(() => [TRIPS]),
      },
      global: { plugins: [realI18n()], directives: { tooltip: {} } },
    })
    expect(trash(guest)).toBeUndefined()
    expect(guest.findAll('.veyq-level-toolbar .view-mode-button')).toHaveLength(2)
    // an identity folder with a section of one presentation and no folders: nothing to switch between — whether the
    // section names no view or exactly one
    reg().registerKind({ kind: 'BETA', icon: 'pi pi-b', shareable: false, section: { order: 20, component: B, views: [{ mode: 'grid', icon: 'pi pi-th-large', label: 'node.viewGrid' }] } })
    for (const kind of ['ALPHA', 'BETA']) {
      const w = mountLevel([TRIPS, node('only', kind, TRIPS.id)], { folder: TRIPS, identityFolder: true })
      expect(blocks(w)).toEqual(['overview', `section:${kind === 'ALPHA' ? 'a' : 'b'}`])
      expect(w.find('.view-mode-toggle').exists(), kind).toBe(false)
    }
  })
})

// inbox #404: the level decides which mode it shows — the stored one where something on screen offers it, else the grid —
// and hands that to its sections; the stored preference is never rewritten by the clamp.
describe('LevelView: the mode it shows', () => {
  const MAP = { mode: 'map', icon: 'pi pi-map', label: 'node.folders' } as const // core's test names no application key
  const GRID = { mode: 'grid', icon: 'pi pi-th-large', label: 'node.viewGrid' } as const
  const LIST = { mode: 'list', icon: 'pi pi-bars', label: 'node.viewList' } as const
  const views = (w: ReturnType<typeof mountLevel>) => w.findAll('.stub-section').map((s) => `${s.attributes('data-name')}:${s.attributes('data-view')}`)
  const pressed = (w: ReturnType<typeof mountLevel>) =>
    w.findAll('.veyq-level-toolbar .view-mode-button').filter((b) => b.attributes('aria-pressed') === 'true').map((b) => b.attributes('aria-label'))
  const stored = () => useViewMode().viewMode.value
  beforeEach(() => {
    // ALPHA draws the map (its empty section is left out in an identity folder); BETA has one presentation, as Files'.
    reg().registerKind({ kind: 'ALPHA', icon: 'pi pi-a', shareable: false, section: { order: 10, component: A, views: [GRID, LIST, MAP] } })
    reg().registerKind({ kind: 'BETA', icon: 'pi pi-b', shareable: false, section: { order: 20, component: B } })
    setViewMode('map')
  })
  const anna = node('Anna', FOLDER_KIND, 'Trips')

  it('a stored map in an identity folder without the map: the sections and the folders in the grid, the grid pressed', async () => {
    const w = mountLevel([TRIPS, anna, node('scan', 'BETA', 'Anna'), node('Deep', FOLDER_KIND, 'Anna')], { folder: anna, identityFolder: true })
    expect(blocks(w)).toEqual(['overview', 'section:b', 'folders'])
    expect(views(w)).toEqual(['b:grid'])
    expect(w.find('.veyq-level-grid').classes()).not.toContain('is-list')
    // the folders' grid and list are offered, the grid shown and pressed
    expect(pressed(w)).toEqual([t('node.viewGrid')])
    expect(stored(), 'the clamp leaves the preference alone').toBe('map')
    // a click is the member's choice: it is stored, and every section follows
    await w.findAll('.view-mode-button')[1].trigger('click')
    expect(stored()).toBe('list')
    expect(views(w)).toEqual(['b:list'])
    expect(w.find('.veyq-level-grid').classes()).toContain('is-list')
  })

  it('with one presentation left on screen: no switch, and the section is handed the grid, not the map', () => {
    const w = mountLevel([TRIPS, anna, node('scan', 'BETA', 'Anna')], { folder: anna, identityFolder: true })
    expect(blocks(w)).toEqual(['overview', 'section:b'])
    expect(views(w)).toEqual(['b:grid'])
    expect(w.find('.view-mode-toggle').exists()).toBe(false)
    expect(stored()).toBe('map')
  })

  it('a level whose sections draw the map shows it again: the map handed in, the folders stepping aside', () => {
    // first the identity folder (grid), then a level with ALPHA on screen: the stored map comes back
    mountLevel([TRIPS, anna, node('scan', 'BETA', 'Anna')], { folder: anna, identityFolder: true })
    const w = mountLevel([TRIPS, anna, node('scan', 'BETA', 'Trips')], { folder: TRIPS })
    expect(views(w)).toEqual(['a:map', 'b:map'])
    expect(blocks(w)).toEqual(['overview', 'section:a', 'section:b'])
    expect(pressed(w)).toEqual([t('node.folders')]) // MAP's label in this test
  })

  it('any stored mode nothing on screen offers (a list, here) is the grid there, and stays stored', () => {
    setViewMode('list')
    const w = mountLevel([TRIPS, anna, node('scan', 'BETA', 'Anna')], { folder: anna, identityFolder: true })
    expect(views(w)).toEqual(['b:grid']) // nothing on screen has a list
    expect(stored()).toBe('list')
  })
})

// inbox #385 (b): a guest's folder in a drop box (the tree's `inIdentityFolder`) shows only what is there.
describe('LevelView: a guest\'s identity folder hides empty sections', () => {
  beforeEach(() => {
    reg().registerKind({ kind: 'ALPHA', icon: 'pi pi-a', shareable: false, section: { order: 10, component: A } })
    reg().registerKind({ kind: 'BETA', icon: 'pi pi-b', shareable: false, section: { order: 20, component: B } })
  })

  it('leaves out every section and the folders section with nothing to show', () => {
    const w = mountLevel([TRIPS, node('b1', 'BETA', 'Trips')], { folder: TRIPS, identityFolder: true })
    expect(blocks(w)).toEqual(['overview', 'section:b'])
    // control: read-only for another reason (a pending deletion), every section shows, empty or not
    expect(blocks(mountLevel([TRIPS, node('b1', 'BETA', 'Trips')], { folder: TRIPS, pending: true }))).toEqual(['overview', 'section:a', 'section:b', 'folders'])
  })

  it('keeps a section or the folders that have something, and says once when nothing is left', () => {
    const rome = node('Rome', FOLDER_KIND, 'Trips')
    expect(blocks(mountLevel([TRIPS, rome], { folder: TRIPS, identityFolder: true }))).toEqual(['overview', 'folders'])
    expect(blocks(mountLevel([TRIPS], { folder: TRIPS, identityFolder: true }))).toEqual(['overview', `empty:${t('node.levelEmpty')}`])
  })
})

// inbox #406: the creation actions are ONE menu in the toolbar, derived from the registry, each entry only where its own
// capability function says yes. Real clicks on the button and on the entries.
describe('LevelView: the "New" menu', () => {
  const Dialog = defineComponent({ name: 'StubDialog', setup: () => () => h('div') })
  const grant = { value: true }
  const ready = { value: true }
  const ran: string[] = []
  const allowed = (ctx: { access: Access }) => grant.value && !(ctx.access.mode === 'guest' && ctx.access.role === ShareRole.Read)
  /** A section stub that registers a level action while mounted, like Files' section does. */
  const StubUploader = defineComponent({
    name: 'StubUploader',
    props: { nodes: { type: Array, default: () => [] } },
    setup() {
      useLevelAction('x.upload', { enabled: () => ready.value, run: () => ran.push('upload') })
      return () => h('section', { class: 'stub-section', 'data-name': 'u' })
    },
  })
  beforeEach(() => {
    grant.value = true
    ready.value = true
    ran.length = 0
    reg().registerKind({
      kind: 'ALPHA', icon: 'pi pi-a', shareable: false, createDialog: Dialog,
      createEntry: { label: 'node.anItem', icon: 'pi pi-a', order: 10, allowed: allowed },
      levelActions: [{ id: 'x.upload', label: 'node.rename', icon: 'pi pi-upload', order: 30, allowed: allowed }],
      section: { order: 10, component: StubUploader },
    })
  })

  it('lists core\'s folder entry and the registry\'s entries by order, and each choice acts: create by kind, an action by its handler', async () => {
    const w = mountLevel([TRIPS], { folder: TRIPS })
    expect(await newMenuLabels(w)).toEqual([t('node.folderCreate'), t('node.anItem'), t('node.rename')])
    await chooseNew(w, t('node.anItem'))
    await chooseNew(w, t('node.rename'))
    await chooseNew(w, t('node.folderCreate'))
    expect(w.emitted('create')).toEqual([['ALPHA'], [FOLDER_KIND]])
    expect(ran).toEqual(['upload'])
  })

  it('is keyboard reachable: a real button with a popup menu, entries are menu items', async () => {
    const w = mountLevel([TRIPS], { folder: TRIPS })
    const button = w.find('.veyq-new-menu-button')
    expect(button.element.tagName).toBe('BUTTON')
    expect(button.attributes('aria-haspopup')).toBe('menu')
    const items = await openNewMenu(w)
    expect(items.every((i) => i.getAttribute('role') === 'menuitem')).toBe(true)
  })

  it('fails closed: an entry whose capability function says no is not drawn; none left, no button', async () => {
    grant.value = false
    const w = mountLevel([TRIPS], { folder: TRIPS })
    expect(await newMenuLabels(w)).toEqual([t('node.folderCreate')])
    const readonly = mountLevel([TRIPS], { folder: TRIPS, pending: true })
    expect(hasNewMenu(readonly)).toBe(false)
  })

  it('an action whose handler is not ready (or whose section is not mounted: another tab) is disabled, not a dead click', async () => {
    ready.value = false
    const w = mountLevel([TRIPS], { folder: TRIPS })
    const items = await openNewMenu(w)
    const upload = items.find((i) => i.textContent?.trim() === t('node.rename'))!
    expect(isDisabled(upload)).toBe(true)
    expect(isDisabled(items.find((i) => i.textContent?.trim() === t('node.anItem'))!)).toBe(false)
  })

  it('a READ guest gets no menu; a READ_WRITE guest gets the entries its capabilities grant', () => {
    const mountGuest = (role: ShareRole) =>
      mount(LevelView, {
        props: { folder: TRIPS, level: childrenOf([TRIPS], TRIPS.id), access: guestAccessOf(role), tree: memberTreeOf(() => [TRIPS]) },
        global: { plugins: [realI18n()], directives: { tooltip: {} } },
      })
    expect(hasNewMenu(mountGuest(ShareRole.Read))).toBe(false)
    expect(hasNewMenu(mountGuest(ShareRole.ReadWrite))).toBe(true)
  })
})
