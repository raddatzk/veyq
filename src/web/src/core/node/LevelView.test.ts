// @vitest-environment happy-dom
// Core's folder level view (cr-files-application P4b; the unified list, inbox #407) against a STUBBED registry (a fresh
// `createRegistry()` per test behind a delegating `registry` export), so each test chooses which kinds bring which rows. Kinds
// here are made-up tokens (`ALPHA`, `BETA`, …) and their row factory is `test/stubRows.ts`: core's view knows no application,
// and neither does its test. Every control is clicked for real. The real registry with Memories and Depot registered is in
// `views/SpaceView.test.ts`; the Memories level as a member meets it, in the golden. The tabs are `LevelView.tabs.test.ts`.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const holder = vi.hoisted(() => ({ reg: null as null | import('../applications/registry').Registry }))
const { toastError } = vi.hoisted(() => ({ toastError: vi.fn() }))
vi.mock('../../shared/state/useToast', () => ({ useToast: () => ({ success: vi.fn(), error: toastError, showUndo: vi.fn(), dismissUndo: vi.fn() }) }))
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

import { defineComponent, h } from 'vue'
import LevelView from './LevelView.vue'
import VeyqButton from '../../shared/ui/VeyqButton.vue'
import VeyqIcon from '../../shared/ui/VeyqIcon.vue'
import VeyqTooltip from '../../shared/ui/VeyqTooltip.vue'
import { createRegistry, type Registry } from '../applications/registry'
import { childrenOf } from './browse'
import { FOLDER_KIND, type NodeKind } from './kind'
import type { TreeNode } from './tree'
import { setViewMode, useViewMode } from '../../shared/state/useViewMode'
import { GRID_VIEW, LIST_VIEW } from './levelViews'
import { ALL_TAB } from './levelTabs'
import { setLevelTab } from '../../shared/state/useLevelTab'
import { realI18n, t } from '../../test/i18n'
import { chooseNew, hasNewMenu, isDisabled, newMenuLabels, openNewMenu } from '../../test/newMenu'
import { treeNode, memberAccessOf, memberTreeOf, guestAccessOf } from '../../test/models'
import { ShareRole } from '../../api/generated/sharing'
import { STUB_ROW_ICON, stubRows, type StubRows } from '../../test/stubRows'
import { ZipWriter } from '../export/zip'
import { epochsOf } from '../../features/spaces/epochsTestUtil'
import { actionIdsOf, clickAction, rowOf, titlesOf } from '../../test/levelRows'
import { chooseHeaderEntry, headerButtonIds, headerMenuIds } from '../../test/nodeHeader'
import { nothingOfItsOwnToRekey } from './rekey/hooks'
import { GUEST_DOORS, recordingHost } from '../../test/nodeHost'
import { testApp, testKind } from '../../test/testApplication'

function node(id: string, kind: NodeKind, parentId: string | null = null, over: Partial<TreeNode> = {}): TreeNode {
  return treeNode({ id, kind, name: id, parentId, orderIndex: 0, epoch: 0, wrappedContentKey: 'eA==', wrappedMeta: null, metaRevision: 0, placementRevision: 0, ...over })
}

const TRIPS = node('Trips', FOLDER_KIND)
/** The level as a member sees it. `pending`: the scope is pending deletion (`access.readonly`); `identityFolder`: the folder
 *  is a guest's identity folder (what the member's tree answers); `publicShares`: the instance offers public links. */
function mountLevel(
  tree: TreeNode[],
  over: {
    folder?: TreeNode | null; pending?: boolean; identityFolder?: boolean; publicShares?: boolean; folderName?: string; spaceName?: string; liveTick?: number;
    unlocked?: boolean; applicationsOff?: Set<string>
  } = {},
) {
  const folder = over.folder ?? null
  const source = memberTreeOf(() => tree)
  // The member's page: every door, each recording what the level asked of it.
  const rec = recordingHost()
  const w = mount(LevelView, {
    props: {
      folder, level: childrenOf(tree, folder?.id ?? null),
      access: memberAccessOf({
        readonly: over.pending ?? false, publicShares: over.publicShares ?? false, epochs: over.unlocked ? epochsOf(new Uint8Array(32)) : null,
        ...(over.applicationsOff ? { applicationsOff: over.applicationsOff } : {}),
      }),
      tree: { ...source, inIdentityFolder: () => over.identityFolder ?? false },
      folderName: over.folderName,
      spaceName: over.spaceName,
      liveTick: over.liveTick,
    },
    global: { plugins: [realI18n()], provide: rec.provide },
  })
  return Object.assign(w, { calls: rec.calls })
}
type Level = ReturnType<typeof mountLevel>
/** The level as a share guest sees it: the guest page's doors (the move only where the link moves anything). */
const mountGuest = (role: ShareRole, tree: TreeNode[] = [TRIPS], folder: TreeNode | null = TRIPS) => {
  const rec = recordingHost(role === ShareRole.ReadWrite ? GUEST_DOORS : GUEST_DOORS.filter((d) => d !== 'move' && d !== 'moveMany' && d !== 'moveTo'))
  const w = mount(LevelView, {
    props: { folder, level: childrenOf(tree, folder?.id ?? null), access: guestAccessOf(role), tree: memberTreeOf(() => tree) },
    global: { plugins: [realI18n()], provide: rec.provide },
  })
  return Object.assign(w, { calls: rec.calls })
}

/** The level's top-level blocks in DOM order - but "New" and the tools row, which have their own tests. */
function blocks(w: Level): string[] {
  return [...w.element.children].flatMap((el): string[] => [blockOf(el)].filter((b): b is string => b !== null))
}
function blockOf(el: Element): string | null {
  {
    // The head holds the header and "New" in one row, the tools row the tabs and the tools; both have their own tests.
    if (el.classList.contains('veyq-level-head')) return el.querySelector(':scope > .veyq-node-header') ? 'overview' : null
    if (el.classList.contains('veyq-level-tools')) return null
    if (el.classList.contains('veyq-empty')) return `empty:${el.textContent?.trim()}`
    if (el.classList.contains('veyq-level-groups')) return 'list'
    if (el.classList.contains('stub-presentation')) return `presentation:${el.getAttribute('data-name')}`
    if (el.classList.contains('veyq-node-header')) return 'overview'
    return `?${el.className}`
  }
}

beforeEach(() => {
  setViewMode('grid')
  holder.reg = createRegistry()
})
const reg = (): Registry => holder.reg!

/** Two kinds, each with its own row source - the shape of two applications. */
function twoKinds() {
  const a = stubRows('a')
  const b = stubRows('b')
  testKind(reg(), { rekeyContent: nothingOfItsOwnToRekey, kind: 'ALPHA', icon: 'images', list: { rows: a.factory } })
  testKind(reg(), { rekeyContent: nothingOfItsOwnToRekey, kind: 'BETA', icon: 'file', list: { rows: b.factory } })
  return { a, b }
}

/** The level's groups as a reader meets them: each group's heading (null: none) - read through the `aria-labelledby` that
 *  names its section, so a heading that names nothing does not count - and the titles in its own list. */
function groupsOf(w: Level): { heading: string | null; titles: string[] }[] {
  return w.findAll('.veyq-level-groups > .veyq-level-group').map((g) => {
    const by = g.attributes('aria-labelledby')
    const heading = by ? w.find(`[id="${by}"]`) : null
    return {
      heading: heading?.exists() && heading.element.tagName === 'H2' ? heading.text() : null,
      titles: g.findAll(':scope > ul.veyq-level-list .veyq-level-item-title').map((x) => x.text()),
    }
  })
}

describe('LevelView: the folders, then the contents', () => {
  it('lists the folders first, then every other node MIXED BY NAME - not grouped by kind', () => {
    twoKinds()
    const tree = [
      TRIPS, node('Zed', FOLDER_KIND, 'Trips'), node('Alpha', FOLDER_KIND, 'Trips'),
      node('Bravo', 'ALPHA', 'Trips'), node('delta', 'ALPHA', 'Trips'), node('alpha', 'BETA', 'Trips'), node('Charlie', 'BETA', 'Trips'),
    ]
    const w = mountLevel(tree, { folder: TRIPS })
    // the folders by name, then a, b, c, d of two applications interleaved (case ignored); the tree's order decides nothing
    expect(titlesOf(w)).toEqual(['Alpha', 'Zed', 'alpha', 'Bravo', 'Charlie', 'delta'])
    expect(w.findAll('li.veyq-level-item').map((li) => li.attributes('data-kind'))).toEqual([FOLDER_KIND, FOLDER_KIND, 'BETA', 'ALPHA', 'BETA', 'ALPHA'])
    expect(blocks(w)).toEqual(['overview', 'list'])
  })

  for (const mode of ['grid', 'list'] as const) {
    it(`${mode}: with folders AND contents, two groups, each a section named by its h2 and its own list`, () => {
      twoKinds()
      setViewMode(mode)
      const tree = [TRIPS, node('Rome', FOLDER_KIND, 'Trips'), node('a1', 'ALPHA', 'Trips'), node('b1', 'BETA', 'Trips')]
      const w = mountLevel(tree, { folder: TRIPS })
      expect(groupsOf(w)).toEqual([
        { heading: t('node.groupFolders'), titles: ['Rome'] },
        { heading: t('node.groupContents'), titles: ['a1', 'b1'] },
      ])
      expect(w.findAll('section.veyq-level-group')).toHaveLength(2)
      expect(w.findAll('ul.veyq-level-list')).toHaveLength(2)
      // each group's list has the mode's look (in the list view each its own surface)
      expect(w.findAll('ul.veyq-level-list').map((ul) => ul.classes().includes('is-list'))).toEqual([mode === 'list', mode === 'list'])
      // the outline: the folder's name the one h1, the two groups h2 under it, nothing deeper
      expect(w.findAll('h1, h2, h3, h4, h5, h6').map((h) => `${h.element.tagName}:${h.text()}`)).toEqual([
        'H1:Trips', `H2:${t('node.groupFolders')}`, `H2:${t('node.groupContents')}`,
      ])
      // the two ids differ, and Tab reaches the folders before the contents (DOM order)
      const ids = w.findAll('h2').map((h) => h.attributes('id'))
      expect(new Set(ids).size).toBe(2)
      expect(w.findAll('button.veyq-level-item-main').map((b) => b.attributes('aria-label'))).toEqual(['Rome', 'open a1', 'open b1'])
    })
  }

  it('one group alone - only contents, only folders, or a tab that shows no contents - is one list with no heading', async () => {
    twoKinds()
    const contentsOnly = mountLevel([TRIPS, node('a1', 'ALPHA', 'Trips'), node('b1', 'BETA', 'Trips')], { folder: TRIPS })
    expect(groupsOf(contentsOnly)).toEqual([{ heading: null, titles: ['a1', 'b1'] }])
    const foldersOnly = mountLevel([TRIPS, node('Rome', FOLDER_KIND, 'Trips'), node('Oslo', FOLDER_KIND, 'Trips')], { folder: TRIPS })
    expect(groupsOf(foldersOnly)).toEqual([{ heading: null, titles: ['Oslo', 'Rome'] }])
    for (const w of [contentsOnly, foldersOnly]) {
      expect(w.findAll('ul.veyq-level-list')).toHaveLength(1)
      expect(w.find('section').exists(), 'no section without a heading').toBe(false)
      expect(w.find('[aria-labelledby]').exists()).toBe(false)
      expect(w.findAll('h1, h2, h3, h4, h5, h6').map((h) => h.element.tagName), "the folder header's own title is the only heading").toEqual(['H1'])
    }
  })

  it('both groups decide on what the shown tab holds: a tab with no contents is the folders alone, unheaded', async () => {
    holder.reg = createRegistry()
    const Dialog = { name: 'D', render: () => null }
    const allow = () => true
    testKind(reg(), { rekeyContent: nothingOfItsOwnToRekey, kind: 'ALPHA', icon: 'images', list: { rows: stubRows('a').factory, tabLabel: 'memory.memories' } })
    testKind(reg(), { rekeyContent: nothingOfItsOwnToRekey,
      kind: 'BETA', icon: 'file', createDialog: Dialog, createEntry: { label: 'node.anItem', icon: 'file', order: 20, allowed: allow },
      list: { rows: stubRows('b').factory, tabLabel: 'depot.title' },
    })
    const w = mountLevel([TRIPS, node('Rome', FOLDER_KIND, 'Trips'), node('a1', 'ALPHA', 'Trips')], { folder: TRIPS })
    expect(groupsOf(w).map((g) => g.heading)).toEqual([t('node.groupFolders'), t('node.groupContents')])
    const tab = w.findAll('[role="tab"]').find((b) => b.text() === t('depot.title'))!
    await tab.trigger('mousedown')
    expect(groupsOf(w)).toEqual([{ heading: null, titles: ['Rome'] }])
    setLevelTab(ALL_TAB) // the stored tab outlives the test
  })

  it('a folder\'s page has exactly one h1, the folder\'s name, for a member and a guest alike', () => {
    twoKinds()
    const tree = [TRIPS, node('Rome', FOLDER_KIND, 'Trips'), node('a1', 'ALPHA', 'Trips')]
    for (const w of [mountLevel(tree, { folder: TRIPS }), mountGuest(ShareRole.Read, tree)]) {
      const h1s = w.findAll('h1')
      expect(h1s).toHaveLength(1)
      expect(h1s[0].text()).toBe('Trips')
      expect(h1s[0].classes()).toContain('veyq-node-header-title')
    }
    // The name the host resolved (a sealed name, unsealed) is the h1's text, not the node's raw one.
    expect(mountLevel(tree, { folder: TRIPS, folderName: 'Italy 2026' }).get('h1').text()).toBe('Italy 2026')
  })

  it('the space\'s top level has exactly one h1, the space\'s name; the headings below it start at h2', () => {
    twoKinds()
    const w = mountLevel([node('Trips', FOLDER_KIND), node('a1', 'ALPHA')], { spaceName: 'Familie Raddatz' })
    const h1s = w.findAll('h1')
    expect(h1s).toHaveLength(1)
    expect(h1s[0].text()).toBe('Familie Raddatz')
    // The space is no node: its header offers nothing to act on.
    expect(w.findAll('.veyq-node-header-actions')).toHaveLength(0)
    expect(w.findAll('h3, h4, h5, h6')).toHaveLength(0)
    // A folder's own name wins inside it; without a name the top level draws no header at all.
    expect(mountLevel([TRIPS], { folder: TRIPS, spaceName: 'Familie Raddatz' }).get('h1').text()).toBe('Trips')
    expect(mountLevel([TRIPS]).findAll('h1')).toHaveLength(0)
  })

  it('reads digits as numbers (Foto 2 before Foto 10) across kinds', () => {
    twoKinds()
    const w = mountLevel([node('Foto 10', 'ALPHA'), node('Foto 2', 'BETA'), node('Foto 1', 'ALPHA')])
    expect(titlesOf(w)).toEqual(['Foto 1', 'Foto 2', 'Foto 10'])
  })

  it('draws core\'s item for every node: the factory\'s icon, title and meta; a folder\'s own kind noun and an aria-hidden icon', () => {
    twoKinds()
    const w = mountLevel([TRIPS, node('Rome', FOLDER_KIND, 'Trips'), node('a1', 'ALPHA', 'Trips')], { folder: TRIPS })
    const folder = rowOf(w, 'Rome')
    expect(folder.classes()).toContain('is-folder')
    expect(folder.find('.veyq-level-item-meta').text()).toBe(t('node.kindFolder'))
    expect(folder.find('.veyq-level-item-visual').attributes('aria-hidden')).toBe('true')
    expect(folder.find('.veyq-level-item-visual').findComponent(VeyqIcon).props('name')).toBe('folder')
    const row = rowOf(w, 'a1')
    expect(row.find('.veyq-level-item-visual').findComponent(VeyqIcon).props('name')).toBe(STUB_ROW_ICON)
    expect(row.find('.veyq-level-item-meta').text()).toBe('a meta')
  })

  it('calls a row factory ONCE per level view, shared by two kinds, handed the nodes of both (kinds in registration order)', async () => {
    const shared = stubRows('s')
    testKind(reg(), { rekeyContent: nothingOfItsOwnToRekey, kind: 'ALPHA', icon: 'images', list: { rows: shared.factory } })
    testKind(reg(), { rekeyContent: nothingOfItsOwnToRekey, kind: 'ALPHA_PART', icon: 'images', list: { rows: shared.factory } })
    const w = mountLevel([node('p1', 'ALPHA_PART'), node('a1', 'ALPHA'), node('a2', 'ALPHA')])
    expect(shared.calls()).toBe(1)
    expect(shared.ctx().value.nodes.map((n) => n.name)).toEqual(['a1', 'a2', 'p1'])
    await w.setProps({ liveTick: 1 })
    expect(shared.calls(), 'a prop change is no new call').toBe(1)
  })

  it('hands the factory the folder, the access and the tree, reactive: a live tick and a changed level reach it', async () => {
    const { a } = twoKinds()
    const w = mountLevel([TRIPS, node('a1', 'ALPHA', 'Trips')], { folder: TRIPS, pending: true })
    const ctx = a.ctx()
    expect(ctx.value.folder?.name).toBe('Trips')
    expect(ctx.value.access).toBe(w.props('access'))
    expect(ctx.value.tree).toBe(w.props('tree'))
    expect(ctx.value.access.mode === 'member' && ctx.value.access.readonly).toBe(true)
    expect(ctx.value.epochs).toBeNull()
    expect(ctx.value.viewMode).toBe('grid')
    expect(ctx.value.liveTick).toBeUndefined()
    await w.setProps({ liveTick: 3 })
    expect(a.ctx().value.liveTick).toBe(3)
    await w.setProps({ level: childrenOf([TRIPS, node('a1', 'ALPHA', 'Trips'), node('a2', 'ALPHA', 'Trips')], 'Trips') })
    expect(a.ctx().value.nodes.map((n) => n.name)).toEqual(['a1', 'a2'])
  })

  it('hands the identity-folder answer and the pending deletion apart, never folded into one flag', () => {
    const { a } = twoKinds()
    const handed = (over: { pending?: boolean; identityFolder?: boolean }) => {
      mountLevel([TRIPS, node('a1', 'ALPHA', 'Trips')], { folder: TRIPS, ...over })
      const { access, tree, folder } = a.ctx().value
      return [access.mode === 'member' && access.readonly, tree.inIdentityFolder(folder?.id ?? null)]
    }
    expect(handed({})).toEqual([false, false])
    expect(handed({ identityFolder: true })).toEqual([false, true])
    expect(handed({ pending: true })).toEqual([true, false])
  })

  it('a primary click opens the node, a menu action asks the host to rename it, the host\'s move reaches the level - all by real clicks', async () => {
    const { a } = twoKinds()
    const a1 = node('a1', 'ALPHA', 'Trips')
    const w = mountLevel([TRIPS, a1], { folder: TRIPS })
    await rowOf(w, 'a1').find('button.veyq-level-item-main').trigger('click')
    expect(w.calls.open).toEqual([[a1]])
    expect(await actionIdsOf(w, 'a1')).toEqual(['favorite', 'rename']) // core's heart first, then the application's
    await clickAction(w, 'a1', 'rename')
    // a row's node renamed in place reaches the host as `renameNode` with that node (never as the folder's rename)
    expect(w.calls.rename).toEqual([[a1]])
    a.host().move?.(a1)
    expect(w.calls.move).toEqual([[a1]])
  })

  it('a row the factory has none for is the explicit unsupported row, never dropped', () => {
    const nothing = { row: () => null }
    testKind(reg(), { rekeyContent: nothingOfItsOwnToRekey, kind: 'ALPHA', icon: 'images', list: { rows: () => nothing } })
    const w = mountLevel([node('a1', 'ALPHA')])
    const row = rowOf(w, 'a1')
    expect(row.classes()).toContain('is-unsupported')
    expect(row.find('button').exists()).toBe(false)
    expect(row.text()).toContain(t('home.unsupportedKind'))
  })
})

describe('LevelView: a kind no application serves (the unsupported row)', () => {
  it('lists a registered kind without a list: its icon, its name and the unsupported state, not clickable', () => {
    twoKinds()
    testKind(reg(), { rekeyContent: nothingOfItsOwnToRekey, kind: 'LEAF', icon: 'file-check' })
    const w = mountLevel([node('a1', 'ALPHA'), node('l1', 'LEAF'), node('l2', 'LEAF')])
    expect(titlesOf(w)).toEqual(['a1', 'l1', 'l2'])
    for (const name of ['l1', 'l2']) {
      const row = rowOf(w, name)
      expect(row.findComponent(VeyqIcon).props('name')).toBe('file-check')
      expect(row.text()).toContain(t('home.unsupportedKind'))
      expect(row.classes()).toContain('is-unsupported')
      expect(row.element.querySelectorAll('button')).toHaveLength(0)
    }
    expect(rowOf(w, 'a1').classes()).not.toContain('is-unsupported')
  })

  it('lists a kind nobody registered too (a generic icon), named by its kind token in the meta line', () => {
    const w = mountLevel([node('z1', 'ZULU'), node('y1', 'YANKEE')])
    expect(titlesOf(w)).toEqual(['y1', 'z1'])
    expect(w.findAllComponents(VeyqIcon).filter((i) => i.props('name') === 'question-circle' && i.element.closest('.veyq-level-item'))).toHaveLength(2)
    expect(rowOf(w, 'z1').find('.veyq-level-item-meta').text()).toBe(`ZULU · ${t('home.unsupportedKind')}`)
  })

  it('renders no unsupported row when every child kind has rows', () => {
    twoKinds()
    expect(mountLevel([node('a1', 'ALPHA')]).find('.is-unsupported').exists()).toBe(false)
  })

  // A kind registered `listed: false` (a future application's one pool per space, reached through its own entry,
  // docs/apps/inventory.md) is no row at all - neither its own nor the unsupported one - and offers the level nothing to
  // select or a tab; control: a kind registered without a list beside it still is the unsupported row.
  it('lists no row for a kind registered listed: false - not even the unsupported one', async () => {
    twoKinds()
    testKind(reg(), { rekeyContent: nothingOfItsOwnToRekey, kind: 'POOL', icon: 'inbox', listed: false })
    testKind(reg(), { rekeyContent: nothingOfItsOwnToRekey, kind: 'LEAF', icon: 'file-check' })
    const w = mountLevel([TRIPS, node('a1', 'ALPHA', 'Trips'), node('p1', 'POOL', 'Trips'), node('l1', 'LEAF', 'Trips')], { folder: TRIPS })
    expect(titlesOf(w)).toEqual(['a1', 'l1'])
    expect(rowOf(w, 'l1').classes(), 'control: the unlisted-by-omission kind is the unsupported row').toContain('is-unsupported')
    expect(w.text()).not.toContain('p1')
    // alone at a level, it leaves the level empty - the level says so, as for nothing at all
    const alone = mountLevel([TRIPS, node('p1', 'POOL', 'Trips')], { folder: TRIPS })
    expect(titlesOf(alone)).toEqual([])
    expect(alone.find('.veyq-empty').exists()).toBe(true)
  })
})

// Owner 2026-11-01: rename, move and delete are core's defaults for every kind (`standardRowActions.ts`), granted by the
// application's answer (`LevelRow.standard`), run through the level's doors; an application's own action replaces one.
describe('LevelView: core\'s default rename, move and delete', () => {
  const granted = (over: { rename?: boolean; move?: boolean; delete?: boolean } = { rename: true, move: true, delete: true }) =>
    stubRows('a', { noOwnRename: true, standard: () => over })

  it('adds the granted ones to a row - rename, move first, delete last in the menu - and runs each through the level\'s door', async () => {
    const a = granted()
    testKind(reg(), { rekeyContent: nothingOfItsOwnToRekey, kind: 'ALPHA', icon: 'images', list: { rows: a.factory } })
    const a1 = node('a1', 'ALPHA', 'Trips')
    const w = mountLevel([TRIPS, a1], { folder: TRIPS })
    expect(await actionIdsOf(w, 'a1')).toEqual(['favorite', 'rename', 'move', 'delete'])
    await clickAction(w, 'a1', 'rename')
    await clickAction(w, 'a1', 'move')
    await clickAction(w, 'a1', 'delete')
    expect(w.calls.rename).toEqual([[a1]])
    expect(w.calls.move).toEqual([[a1]])
    // the delete is the host's one question with its Undo, for this one node, with core's own write (no `remove`)
    expect(w.calls.delete).toEqual([[a1]])
  })

  it('offers only what the answer grants, and nothing without one (fail closed)', async () => {
    testKind(reg(), { rekeyContent: nothingOfItsOwnToRekey, kind: 'ALPHA', icon: 'images', list: { rows: granted({ move: true }).factory } })
    testKind(reg(), { rekeyContent: nothingOfItsOwnToRekey, kind: 'BETA', icon: 'file', list: { rows: stubRows('b', { noOwnRename: true }).factory } })
    const w = mountLevel([TRIPS, node('a1', 'ALPHA', 'Trips'), node('b1', 'BETA', 'Trips')], { folder: TRIPS })
    expect(await actionIdsOf(w, 'a1')).toEqual(['favorite', 'move'])
    expect(await actionIdsOf(w, 'b1'), 'no answer: no default').toEqual(['favorite'])
  })

  it('an application\'s own action of the id replaces core\'s default (the stub\'s own rename, its label and its door)', async () => {
    const a = stubRows('a', { standard: () => ({ rename: true, move: true, delete: true }) })
    testKind(reg(), { rekeyContent: nothingOfItsOwnToRekey, kind: 'ALPHA', icon: 'images', list: { rows: a.factory } })
    const a1 = node('a1', 'ALPHA', 'Trips')
    const w = mountLevel([TRIPS, a1], { folder: TRIPS })
    expect(await actionIdsOf(w, 'a1')).toEqual(['favorite', 'rename', 'move', 'delete'])
    const labels = async () => {
      await rowOf(w, 'a1').find('.veyq-level-item-more').trigger('click')
      await flushPromises()
      const out = [...document.body.querySelectorAll('.veyq-row-action-rename')].map((e) => e.textContent?.trim())
      await rowOf(w, 'a1').find('.veyq-level-item-more').trigger('click')
      await flushPromises()
      return out
    }
    expect(await labels(), 'one rename, the application\'s').toEqual(['rename a1'])
  })

  it('a share guest gets no default rename or delete whatever the answer, a READ_WRITE guest\'s move through the level\'s door', async () => {
    testKind(reg(), { rekeyContent: nothingOfItsOwnToRekey, kind: 'ALPHA', icon: 'images', list: { rows: granted().factory } })
    const tree = [TRIPS, node('a1', 'ALPHA', 'Trips')]
    // A READ link's page has no move door either (it moves nothing): no default at all, whatever the answer says.
    for (const [role, offered] of [[ShareRole.Read, []], [ShareRole.ReadWrite, ['move']]] as const) {
      const w = mountGuest(role, tree)
      expect((await actionIdsOf(w, 'a1')).filter((id) => ['rename', 'move', 'delete'].includes(id)), role).toEqual(offered)
      w.unmount()
    }
  })

  it('a folder row: core\'s answer for THAT folder (`folderRights`) - a member all three, read-only none, a READ guest none', async () => {
    const rome = node('Rome', FOLDER_KIND, 'Trips')
    const defaultsOf = async (w: Level) => (await actionIdsOf(w, 'Rome')).filter((id) => ['rename', 'move', 'delete'].includes(id))
    const member = mountLevel([TRIPS, rome], { folder: TRIPS })
    expect(await defaultsOf(member)).toEqual(['rename', 'move', 'delete'])
    await clickAction(member, 'Rome', 'delete')
    expect(member.calls.delete).toEqual([[rome]])
    member.unmount()
    const readonly = mountLevel([TRIPS, rome], { folder: TRIPS, pending: true })
    expect(await defaultsOf(readonly)).toEqual([])
    readonly.unmount()
    const guest = mountGuest(ShareRole.Read, [TRIPS, rome])
    expect(await defaultsOf(guest)).toEqual([])
  })
})

describe('LevelView: the folders', () => {
  it('lists the sub-folders and opens one with a real click; the folder is created from the "New" menu, no tile', async () => {
    const rome = node('Rome', FOLDER_KIND, 'Trips')
    const w = mountLevel([TRIPS, rome], { folder: TRIPS })
    await rowOf(w, 'Rome').find('button.veyq-level-item-main').trigger('click')
    expect(w.calls.open).toEqual([[rome]])
    expect(rowOf(w, 'Rome').find('button.veyq-level-item-main').attributes('aria-label')).toBe('Rome')
    expect(w.find('.veyq-create-card').exists(), 'the create tile is retired (inbox #406)').toBe(false)
    // a folder's row: core's heart, and core's default rename, move and delete by `folderRights` (owner 2026-11-01)
    expect(await actionIdsOf(w, 'Rome')).toEqual(['favorite', 'rename', 'move', 'delete'])
    await chooseNew(w, t('node.folderCreate'))
    expect(w.calls.createChild).toEqual([[FOLDER_KIND, TRIPS]])
  })

  // Two folders of one name (a drop box's guests) are told apart as everywhere: the first created keeps it.
  it('numbers sub-folders of one name in creation order, and lists them so', () => {
    const later = treeNode({ ...node('f-a', FOLDER_KIND), name: 'Anna', createdAt: 2000 })
    const first = treeNode({ ...node('f-b', FOLDER_KIND), name: 'Anna', createdAt: 1000 })
    // listed in the tree's order (the later one first here): the equal names fall back to the creation order
    expect(titlesOf(mountLevel([later, first]))).toEqual(['Anna', 'Anna (2)'])
  })

  it('shows no text about folders when the level has none and something else is there', () => {
    twoKinds()
    const w = mountLevel([node('a1', 'ALPHA')])
    expect(blocks(w)).toEqual(['list'])
  })

  it('draws the list variant in list mode, and the tiles in grid mode', () => {
    twoKinds()
    setViewMode('list')
    const list = mountLevel([node('a1', 'ALPHA'), node('Rome', FOLDER_KIND)])
    expect(list.find('.veyq-level-list').classes()).toContain('is-list')
    expect(list.findAll('.veyq-level-item').every((li) => li.classes().includes('is-list'))).toBe(true)
    setViewMode('grid')
    const grid = mountLevel([node('a1', 'ALPHA'), node('Rome', FOLDER_KIND)])
    expect(grid.find('.veyq-level-list').classes()).not.toContain('is-list')
    expect(grid.findAll('.veyq-level-item').every((li) => li.classes().includes('is-grid'))).toBe(true)
  })

  it('read-only (a pending deletion, a guest\'s identity folder): no "New" menu, the folder rows stay', () => {
    for (const over of [{ pending: true }, { identityFolder: true }]) {
      const w = mountLevel([TRIPS, node('Rome', FOLDER_KIND, 'Trips')], { folder: TRIPS, ...over })
      expect(hasNewMenu(w)).toBe(false)
      expect(titlesOf(w)).toEqual(['Rome'])
    }
  })
})

// uc.sharing.share-public-viewer:folder-share-root: the folder header carries the share button core's folder rights
// allow (the instance's public links, a folder, not a guest's identity folder).
describe('LevelView: the folder header', () => {
  const shareButton = (w: Level) => w.find(`button[aria-label="${t('share.share')}"]`)

  // Inbox #393 (b): the heading says what the list said ("Anna (2)"), when the host knows it; else the folder's name.
  it('heads the card with the host\'s display name, else the folder\'s own', () => {
    expect(mountLevel([TRIPS], { folder: TRIPS, folderName: 'Trips (2)' }).find('.veyq-node-header-title').text()).toBe('Trips (2)')
    expect(mountLevel([TRIPS], { folder: TRIPS }).find('.veyq-node-header-title').text()).toBe('Trips')
  })

  it('shows no header at the scope root', () => {
    expect(blocks(mountLevel([]))).not.toContain('overview')
  })

  it('shows the share button on a shareable folder and emits share', async () => {
    const w = mountLevel([TRIPS], { folder: TRIPS, publicShares: true })
    expect(shareButton(w).exists()).toBe(true)
    expect(w.findAllComponents(VeyqButton).find((b) => b.element === shareButton(w).element)!.props('icon')).toBe('share-alt')
    await shareButton(w).trigger('click')
    expect(w.calls.share).toHaveLength(1)
  })

  it('shows none when the instance offers no public links', () => {
    const w = mountLevel([TRIPS], { folder: TRIPS, publicShares: false })
    expect(shareButton(w).exists()).toBe(false)
    expect(w.find('button[data-testid="node-header-rename"]').exists(), 'control: the name is renamable').toBe(true)
  })

  it('a folder\'s header in its priority (ADR 0110): favorite, share, download as buttons; move, copy, delete in the ⋯ - no rename, no cover entry', async () => {
    const w = mountLevel([TRIPS], { folder: TRIPS, publicShares: true, unlocked: true })
    expect(headerButtonIds(w)).toEqual(['favorite', 'share', 'download'])
    expect(await headerMenuIds(w)).toEqual(['move', 'copy', 'delete'])
    // The name and the cover are edited at their place: the name's button, the leading visual (the empty frame here).
    expect(w.find('button[data-testid="node-header-rename"]').exists()).toBe(true)
    expect(w.find('.veyq-node-header-leading [data-testid="node-header-cover"]').exists()).toBe(true)
  })

  it('renames the folder in place through the page\'s rename write, and deletes it from the ⋯', async () => {
    const w = mountLevel([TRIPS], { folder: TRIPS })
    await w.find('button[data-testid="node-header-rename"]').trigger('click')
    const field = w.find('input[data-testid="node-header-name-input"]')
    await field.setValue('Reisen')
    await field.trigger('keydown', { key: 'Enter' })
    await chooseHeaderEntry(w, 'delete')
    expect(w.calls.renameTo).toEqual([[TRIPS, 'Reisen']])
    expect(w.calls.rename, 'no dialog').toBeUndefined()
    expect(w.calls.delete).toHaveLength(1)
  })

  // Found in the browser, 2026-10-07: the three carried `memory-action-collapsible` / `memory-action-inline`, which
  // memory.css hid at 721–1120 px and ≤ 720 px. What does not fit is the ⋯ menu's now (ADR 0110), never hidden by a class:
  // jsdom has no layout, so the contract pinned is the DOM one — the folder's priority, delete the menu's last entry, and
  // no width-hiding class on any button.
  it('shows the folder\'s actions in its priority, delete in the ⋯ menu, none with a width-hiding class', async () => {
    const w = mountLevel([TRIPS], { folder: TRIPS, publicShares: true })
    const actions = w.findAll('.veyq-node-header-action')
    expect(actions.map((b) => b.attributes('aria-label'))).toEqual([t('node.favorite'), t('share.share')])
    expect(await headerMenuIds(w)).toEqual(['move', 'delete'])
    for (const b of actions) {
      expect(b.classes()).not.toContain('memory-action-collapsible')
      expect(b.classes()).not.toContain('memory-action-inline')
    }
  })

  it('keeps the share (a read of the links) but no rename/delete during a pending deletion', async () => {
    const w = mountLevel([TRIPS], { folder: TRIPS, publicShares: true, pending: true })
    expect(shareButton(w).exists()).toBe(true)
    expect(w.find('h1.veyq-node-header-title').text(), 'control: the header is drawn').toBe('Trips')
    expect(w.find('button[data-testid="node-header-rename"]').exists()).toBe(false)
    expect(await headerMenuIds(w)).not.toContain('delete')
  })

  // A guest's identity folder (inbox #385): a member looks, downloads and deletes there — the folder itself offers no
  // share, rename or delete; its one action is the move OUT of the drop box (inbox #358, #377 (a)).
  it('offers only the move out on a guest\'s identity folder', async () => {
    const w = mountLevel([TRIPS], { folder: TRIPS, publicShares: true, identityFolder: true })
    expect(headerButtonIds(w)).toEqual(['favorite'])
    expect(await headerMenuIds(w)).toEqual(['move'])
    expect(w.find('button[data-testid="node-header-rename"]').exists()).toBe(false)
    await chooseHeaderEntry(w, 'move')
    expect(w.calls.move).toEqual([[TRIPS]])
  })

  it('offers no move during a pending deletion', async () => {
    const w = mountLevel([TRIPS], { folder: TRIPS, publicShares: true, pending: true })
    expect(headerButtonIds(w), 'control: the header has its buttons').toContain('share')
    expect(await headerMenuIds(w)).not.toContain('move')
    expect(w.find(`button[aria-label="${t('node.move.action')}"]`).exists()).toBe(false)
  })
})

// Owner 2026-11-04: a level's upload-only link - an application's node born under it with its WRITE_ONLY link
// (`app.folderUploadShare`, Depot's drop box) - is offered through the share door: beside the folder's own roles in its
// header's share, and alone in the space's top level's header, which shares nothing else (a scope root is no link's root).
describe('LevelView: the upload-only link', () => {
  const SHARE = '.veyq-node-header-action[data-action="share"]'
  /** An application filling the seam: kind BOX at the root and in a folder, its drop page claimed, `allowed` as given. */
  function uploadApp(allowed: () => boolean = () => true, placement = { root: true, parents: [FOLDER_KIND] as NodeKind[] }) {
    testKind(holder.reg!, { rekeyContent: nothingOfItsOwnToRekey, kind: 'BOX', icon: 'inbox', placement })
    testApp(holder.reg!).shareBody('BOX', defineComponent({ render: () => null }))
    testApp(holder.reg!).folderUploadShare('BOX', { allowed, hint: 'test.hint', create: vi.fn() })
  }

  it('the top level offers a share for it alone: the door is asked with no node, upload and nothing else', async () => {
    uploadApp()
    const w = mountLevel([TRIPS], { spaceName: 'Familie Raddatz' })
    expect(headerButtonIds(w)).toEqual(['share'])
    await w.get(SHARE).trigger('click')
    expect(w.calls.share).toEqual([[null, { view: false, contribute: false, upload: true }]])
  })

  it.each<[string, () => void, Parameters<typeof mountLevel>[1]]>([
    ['nobody registered it', () => {}, {}],
    ['the application says no', () => uploadApp(() => false), {}],
    ['the application is switched off', () => uploadApp(), { applicationsOff: new Set(['test']) }],
    ['its kind does not live at the root', () => uploadApp(() => true, { root: false, parents: [FOLDER_KIND] }), {}],
  ])('the top level offers no share where %s', (_, register, over) => {
    register()
    const w = mountLevel([TRIPS], { spaceName: 'Familie Raddatz', ...over })
    expect(w.get('h1').text(), 'control: the top level\'s header renders').toBe('Familie Raddatz')
    expect(w.find(SHARE).exists()).toBe(false)
  })

  it('a folder\'s share offers it beside the folder\'s own roles, and is shown for it alone where public links are off', async () => {
    uploadApp()
    const on = mountLevel([TRIPS], { folder: TRIPS, publicShares: true })
    await on.get(SHARE).trigger('click')
    expect(on.calls.share).toEqual([[TRIPS, { view: true, contribute: true, upload: true }]])
    const off = mountLevel([TRIPS], { folder: TRIPS, publicShares: false })
    await off.get(SHARE).trigger('click')
    expect(off.calls.share).toEqual([[TRIPS, { view: false, contribute: true, upload: true }]])
  })

  it.each<[string, () => void, Parameters<typeof mountLevel>[1]]>([
    ['the application says no', () => uploadApp(() => false), {}],
    ['its kind does not live in a folder', () => uploadApp(() => true, { root: true, parents: [] }), {}],
    ['the folder is a guest\'s identity folder', () => uploadApp(), { identityFolder: true }],
  ])('a folder offers no upload-only link where %s', (_, register, over) => {
    register()
    const w = mountLevel([TRIPS], { folder: TRIPS, publicShares: false, ...over })
    expect(w.find('.veyq-node-header-title').text(), 'control: the folder\'s header renders').toBe('Trips')
    expect(w.find(SHARE).exists()).toBe(false)
  })

  it('a share guest is never offered it: its page has no share door', () => {
    uploadApp()
    const w = mountGuest('READ_WRITE' as ShareRole)
    expect(w.find('.veyq-node-header-title').text(), 'control: the folder\'s header renders').toBe('Trips')
    expect(w.find(SHARE).exists()).toBe(false)
  })
})

// inbox #370/#385 (f): the space's trash holds every application's nodes, so it is the level view's own button.
// The head and the tools row (owner 2026-11-04): the header and "New" in ONE row; right under it ONE tools row - the tabs on the
// left, then Select and the trash as icon buttons (their words the accessible name and the tooltip), then the view switch.
describe('LevelView: the head and the tools row', () => {
  const trash = (w: Level) => w.find(`.veyq-level-tools .veyq-level-toolbar button[aria-label="${t('node.trash.open')}"]`)
  /** The tooltip that wraps a control (`VeyqTooltip` renders its one child itself, around nothing). */
  const tooltipOf = (w: Level, el: Element) => w.findAllComponents(VeyqTooltip).find((c) => c.find('button').element === el)?.props('text')
  function tabbedKinds() {
    testKind(reg(), { rekeyContent: nothingOfItsOwnToRekey, kind: 'ALPHA', icon: 'images', list: { rows: stubRows('a').factory, tabLabel: 'node.kindFolder' } })
    testKind(reg(), { rekeyContent: nothingOfItsOwnToRekey, kind: 'BETA', icon: 'file', list: { rows: stubRows('b').factory, tabLabel: 'node.folders' } })
  }
  const tabbedTree = [TRIPS, node('Sub', FOLDER_KIND, 'Trips'), node('a1', 'ALPHA', 'Trips'), node('b1', 'BETA', 'Trips')]

  it('the head holds the header and "New" - nothing else -, at the root and in a folder', () => {
    twoKinds()
    for (const w of [mountLevel([]), mountLevel([TRIPS], { folder: TRIPS })]) {
      // In the head's one row, after the name: the reading order is the Tab order (owner 2026-11-01).
      const head = w.element.firstElementChild!
      expect(head.classList.contains('veyq-level-head')).toBe(true)
      const parts = [...head.children].map((el) => el.className.split(' ')[0])
      expect(parts).toEqual([...(w.find('.veyq-node-header').exists() ? ['veyq-node-header'] : []), 'veyq-level-new'])
      expect(head.querySelector('.veyq-level-new .veyq-new-menu-button')?.textContent?.trim()).toBe(t('node.new'))
      expect(head.querySelector('.veyq-level-toolbar, .veyq-select-button, .veyq-trash-button, .view-mode-toggle'), 'no tool in the head').toBeNull()
    }
  })

  it('one tools row right under the head: the tabs on the left, then Select, the trash and the view switch', () => {
    tabbedKinds()
    const w = mountLevel(tabbedTree, { folder: TRIPS })
    const [head, tools] = [...w.element.children]
    expect(head.classList.contains('veyq-level-head')).toBe(true)
    expect(tools.classList.contains('veyq-level-tools'), 'the tools row follows the head at once').toBe(true)
    expect(tools.classList.contains('has-tabs')).toBe(true)
    expect([...tools.children].map((el) => el.classList.contains('veyq-level-tabs') ? 'tabs' : el.className.split(' ')[0])).toEqual(['tabs', 'veyq-level-toolbar'])
    expect(tools.querySelectorAll('[role="tab"]').length, 'control: the tab bar is drawn').toBe(3)
    const toolbar = tools.querySelector('.veyq-level-toolbar')!
    expect([...toolbar.children].map((el) => el.className.split(' ')[0])).toEqual(['veyq-button', 'veyq-button', 'view-mode-toggle'])
    expect([...toolbar.querySelectorAll(':scope > button')].map((b) => b.getAttribute('aria-label'))).toEqual([t('node.select.action'), t('node.trash.open')])
  })

  it('Select and the trash are icon buttons: no visible words, named by them, the same words their tooltip', () => {
    tabbedKinds()
    const w = mountLevel(tabbedTree, { folder: TRIPS })
    for (const [selector, label] of [['.veyq-select-button', t('node.select.action')], ['.veyq-trash-button', t('node.trash.open')]] as const) {
      const button = w.find(`.veyq-level-toolbar ${selector}`)
      expect(button.exists(), label).toBe(true)
      expect(button.text(), `${label}: no visible words`).toBe('')
      expect(button.classes()).toContain('veyq-button--icon-only')
      expect(button.attributes('aria-label')).toBe(label)
      expect(tooltipOf(w, button.element), `${label}: its tooltip`).toBe(label)
    }
  })

  it('offers the trash at the root and in a folder, and a click asks the page for it', async () => {
    twoKinds()
    for (const w of [mountLevel([]), mountLevel([TRIPS], { folder: TRIPS })]) {
      await trash(w).trigger('click')
      expect(w.calls.openTrash).toHaveLength(1)
    }
  })

  it('without a tab bar the row holds the tools alone; with nothing to offer, no row', () => {
    twoKinds()
    const w = mountLevel([TRIPS, node('a1', 'ALPHA', 'Trips')], { folder: TRIPS })
    expect(w.find('[role="tablist"]').exists(), 'control: no tab bar here').toBe(false)
    const tools = w.find('.veyq-level-tools')
    expect(tools.classes()).not.toContain('has-tabs')
    expect([...tools.element.children].map((el) => el.className.split(' ')[0])).toEqual(['veyq-level-toolbar'])
    expect(trash(w).exists()).toBe(true)
    // an empty identity folder: nothing to write, select or switch - no tools row at all, the header still there
    const empty = mountLevel([TRIPS], { folder: TRIPS, identityFolder: true })
    expect(empty.find('.veyq-node-header').exists(), 'control: the head renders').toBe(true)
    expect(empty.find('.veyq-level-tools').exists()).toBe(false)
  })

  it('has no trash where nothing is written (a pending deletion, a guest\'s folder)', () => {
    twoKinds()
    const pending = mountLevel([TRIPS, node('a1', 'ALPHA', 'Trips')], { folder: TRIPS, pending: true })
    expect(pending.find('.veyq-level-toolbar .view-mode-toggle').exists(), 'control: the tools row is there').toBe(true)
    expect(trash(pending).exists()).toBe(false)
  })
})

// The level's items as the grid draws them (owner 2026-11-04): the folders a compact group (first, `levelList.ts`) above the
// flat tiles of every other node; the list draws every item the same way.
describe('LevelView: the grid\'s folder group', () => {
  const kinds = (w: Level) => w.findAll('li.veyq-level-item').map((li) => (li.classes().includes('is-compact') ? 'compact' : 'flat'))
  it('in the grid every folder is a compact tile and they all come before the tiles; in the list none is compact', () => {
    twoKinds()
    const tree = [TRIPS, node('b1', 'BETA', 'Trips'), node('Zed', FOLDER_KIND, 'Trips'), node('a1', 'ALPHA', 'Trips'), node('Alpha', FOLDER_KIND, 'Trips')]
    const grid = mountLevel(tree, { folder: TRIPS })
    expect(titlesOf(grid)).toEqual(['Alpha', 'Zed', 'a1', 'b1'])
    expect(kinds(grid)).toEqual(['compact', 'compact', 'flat', 'flat'])
    expect(grid.findAll('li.veyq-level-item.is-compact').every((li) => li.classes().includes('is-folder'))).toBe(true)
    setViewMode('list')
    const list = mountLevel(tree, { folder: TRIPS })
    expect(kinds(list)).toEqual(['flat', 'flat', 'flat', 'flat'])
    expect(list.findAll('li.veyq-level-item').every((li) => li.classes().includes('is-list'))).toBe(true)
  })
})

// inbox #370 / #407: the view switch governs the whole list, so it is the level's — offered as the union of the presentations
// on screen: core's grid and list for every row, and each shown kind's own `views` (the stand-in for Memories' map).
describe('LevelView: the view switch', () => {
  const MAP = { mode: 'map', icon: 'map', label: 'node.folders' } as const // core's test names no application key
  const trash = (w: Level) => w.findAll('.veyq-level-toolbar button').find((b) => b.attributes('aria-label') === t('node.trash.open'))
  const switchLabels = (w: Level) => w.findAll('.veyq-level-toolbar .view-mode-button').map((b) => b.attributes('aria-label'))

  it('offers the grid and the list for the rows; a click switches every item to the list', async () => {
    twoKinds()
    const w = mountLevel([TRIPS, node('a1', 'ALPHA', 'Trips')], { folder: TRIPS })
    expect(switchLabels(w)).toEqual([t('node.viewGrid'), t('node.viewList')])
    await w.findAll('.view-mode-button')[1].trigger('click')
    expect(w.find('.veyq-level-list').classes()).toContain('is-list')
    expect(w.findAll('.veyq-level-item').every((li) => li.classes().includes('is-list'))).toBe(true)
  })

  it('adds a kind\'s own view, each mode once in the order first named; choosing it replaces the list by that presentation', async () => {
    const a = stubRows('a', { presentationFor: 'map' })
    testKind(reg(), { rekeyContent: nothingOfItsOwnToRekey, kind: 'ALPHA', icon: 'images', list: { rows: a.factory, views: [GRID_VIEW, MAP] } })
    const w = mountLevel([TRIPS, node('a1', 'ALPHA', 'Trips')], { folder: TRIPS })
    expect(switchLabels(w)).toEqual([t('node.viewGrid'), t('node.folders'), t('node.viewList')])
    await w.findAll('.view-mode-button')[1].trigger('click')
    expect(blocks(w)).toEqual(['overview', 'presentation:a'])
    expect(w.find('.stub-presentation').text()).toBe('a1')
    expect(a.ctx().value.viewMode, 'the factory is told the mode').toBe('map')
  })

  it('is offered to a guest too, without the trash; and where nothing is listed, not at all', () => {
    twoKinds()
    const guest = mountGuest(ShareRole.Read)
    expect(trash(guest)).toBeUndefined()
    expect(guest.findAll('.veyq-level-toolbar .view-mode-button')).toHaveLength(2)
    // an empty identity folder: no list, nothing to switch
    expect(mountLevel([TRIPS], { folder: TRIPS, identityFolder: true }).find('.view-mode-toggle').exists()).toBe(false)
  })
})

// inbox #404: the level decides which mode it shows — the stored one where something on screen offers it, else the grid —
// and hands that to its factories; the stored preference is never rewritten by the clamp.
describe('LevelView: the mode it shows', () => {
  const MAP = { mode: 'map', icon: 'map', label: 'node.folders' } as const
  const pressed = (w: Level) =>
    w.findAll('.veyq-level-toolbar .view-mode-button').filter((b) => b.attributes('aria-pressed') === 'true').map((b) => b.attributes('aria-label'))
  const stored = () => useViewMode().viewMode.value
  let a: ReturnType<typeof stubRows>
  let b: ReturnType<typeof stubRows>
  beforeEach(() => {
    // ALPHA offers the map (its empty tab is left out in an identity folder); BETA has the rows only, as Depot's.
    a = stubRows('a', { presentationFor: 'map' })
    b = stubRows('b')
    testKind(reg(), { rekeyContent: nothingOfItsOwnToRekey, kind: 'ALPHA', icon: 'images', list: { rows: a.factory, views: [GRID_VIEW, LIST_VIEW, MAP] } })
    testKind(reg(), { rekeyContent: nothingOfItsOwnToRekey, kind: 'BETA', icon: 'file', list: { rows: b.factory } })
    setViewMode('map')
  })
  const anna = node('Anna', FOLDER_KIND, 'Trips')

  it('a stored map in an identity folder without the map: the rows in the grid, the grid pressed, the preference kept', async () => {
    const w = mountLevel([TRIPS, anna, node('scan', 'BETA', 'Anna'), node('Deep', FOLDER_KIND, 'Anna')], { folder: anna, identityFolder: true })
    expect(blocks(w)).toEqual(['overview', 'list'])
    expect(titlesOf(w)).toEqual(['Deep', 'scan'])
    expect(b.ctx().value.viewMode).toBe('grid')
    expect(pressed(w)).toEqual([t('node.viewGrid')])
    expect(stored(), 'the clamp leaves the preference alone').toBe('map')
    // a click is the member's choice: it is stored, and every factory follows
    await w.findAll('.view-mode-button')[1].trigger('click')
    expect(stored()).toBe('list')
    expect(b.ctx().value.viewMode).toBe('list')
    expect(w.find('.veyq-level-list').classes()).toContain('is-list')
  })

  it('a level whose kinds offer the map shows it again: the map handed in, the rows stepping aside', () => {
    // first the identity folder (grid), then a level with ALPHA on screen: the stored map comes back
    mountLevel([TRIPS, anna, node('scan', 'BETA', 'Anna')], { folder: anna, identityFolder: true })
    const w = mountLevel([TRIPS, anna, node('a1', 'ALPHA', 'Trips'), node('scan', 'BETA', 'Trips')], { folder: TRIPS })
    expect(a.ctx().value.viewMode).toBe('map')
    expect(blocks(w)).toEqual(['overview', 'presentation:a'])
    expect(pressed(w)).toEqual([t('node.folders')]) // MAP's label in this test
  })

  it('any stored mode nothing on screen offers (a map, a list…) is the grid there, and stays stored', () => {
    setViewMode('list')
    const w = mountLevel([TRIPS, anna, node('scan', 'BETA', 'Anna')], { folder: anna, identityFolder: true })
    // core draws the list itself, so a stored list IS offered wherever rows are listed
    expect(w.find('.veyq-level-list').classes()).toContain('is-list')
    setViewMode('map')
    expect(blocks(mountLevel([TRIPS, anna, node('scan', 'BETA', 'Anna')], { folder: anna, identityFolder: true }))).toEqual(['overview', 'list'])
    expect(stored()).toBe('map')
  })
})

// inbox #385 (b): a guest's folder in a drop box (the tree's `inIdentityFolder`) shows only what is there.
describe('LevelView: a guest\'s identity folder', () => {
  beforeEach(() => twoKinds())

  it('lists what is there and says once when nothing is left - no "none yet" for a kind nobody can add', () => {
    const rome = node('Rome', FOLDER_KIND, 'Trips')
    expect(blocks(mountLevel([TRIPS, rome], { folder: TRIPS, identityFolder: true }))).toEqual(['overview', 'list'])
    expect(blocks(mountLevel([TRIPS], { folder: TRIPS, identityFolder: true }))).toEqual(['overview', `empty:${t('node.levelEmpty')}`])
  })
})

// inbox #407: ONE plain text for an empty level, composed from what the viewer may create; no create tile.
describe('LevelView: the empty level', () => {
  const Dialog = defineComponent({ name: 'StubDialog', setup: () => () => h('div') })
  // the application's rights function: no write during a pending deletion, none for a READ guest
  const allowAll = (ctx: { access: { mode: string; readonly?: boolean; role?: string } }) =>
    ctx.access.mode === 'member' ? !ctx.access.readonly : ctx.access.role !== ShareRole.Read
  beforeEach(() => {
    const a = stubRows('a')
    testKind(reg(), { rekeyContent: nothingOfItsOwnToRekey,
      kind: 'ALPHA', icon: 'images', createDialog: Dialog, list: { rows: a.factory, tabLabel: 'memory.memories', emptyText: () => 'memory.memoryEmpty' },
      createEntry: { label: 'node.anItem', icon: 'images', order: 10, allowed: allowAll },
    })
  })

  it('a member who may create is told what "New" offers - folder and the application\'s entries - once, in plain text', () => {
    const w = mountLevel([])
    expect(blocks(w)).toEqual([`empty:${t('node.levelEmptyNew', { entries: `${t('node.folderCreate')}, ${t('node.anItem')}` })}`])
    expect(w.find('.veyq-create-card').exists()).toBe(false)
  })

  it('a viewer who may create nothing gets the plain "nothing here"', () => {
    expect(blocks(mountLevel([], { pending: true }))).toEqual([`empty:${t('node.levelEmpty')}`])
    expect(blocks(mountGuest(ShareRole.Read, [TRIPS], TRIPS))).toEqual(['overview', `empty:${t('node.levelEmpty')}`])
  })

  it('says nothing about the whole level once a folder is there (the list shows it)', () => {
    expect(blocks(mountLevel([node('Rome', FOLDER_KIND)]))).toEqual(['list'])
  })
})

// inbox #406: the creation actions are ONE menu in the toolbar, derived from the registry, each entry only where its own
// rights function says yes. Real clicks on the button and on the entries.
describe('LevelView: the "New" menu', () => {
  const Dialog = defineComponent({ name: 'StubDialog', setup: () => () => h('div') })
  const grant = { value: true }
  const ready = { value: true }
  const ran: string[] = []
  const dropped: string[][] = []
  const droppedDirs: { name: string; paths: string[] }[] = []
  let rows: StubRows
  const allowed = (ctx: { access: { mode: string; role?: string } }) => grant.value && !(ctx.access.mode === 'guest' && ctx.access.role === ShareRole.Read)
  beforeEach(() => {
    grant.value = true
    ready.value = true
    ran.length = 0
    dropped.length = 0
    droppedDirs.length = 0
    // The row factory registers the handler while the LEVEL lives - not while a tab is shown (below).
    toastError.mockReset()
    rows = stubRows('a', {
      action: {
        id: 'x.upload',
        handler: {
          enabled: () => ready.value, run: () => ran.push('upload'), drop: (files) => dropped.push(files.map((f) => f.name)),
          dropDirectory: (dir) => droppedDirs.push({ name: dir.name, paths: dir.files.map((f) => f.path).sort() }),
        },
      },
    })
    testKind(reg(), { rekeyContent: nothingOfItsOwnToRekey,
      kind: 'ALPHA', icon: 'images', createDialog: Dialog, list: { rows: rows.factory },
      createEntry: { label: 'node.anItem', icon: 'images', order: 10, allowed },
      levelActions: [{ id: 'x.upload', label: 'node.rename', icon: 'upload', order: 30, allowed, acceptsDrop: true }],
    })
  })

  it('lists core\'s folder entry and the registry\'s entries by order, and each choice acts: create by kind, an action by its handler', async () => {
    const w = mountLevel([TRIPS], { folder: TRIPS })
    // no import entry (ADR 0082 decision 7): an upload recognises a download itself
    expect(await newMenuLabels(w)).toEqual([t('node.folderCreate'), t('node.anItem'), t('node.rename')])
    await chooseNew(w, t('node.anItem'))
    await chooseNew(w, t('node.rename'))
    await chooseNew(w, t('node.folderCreate'))
    expect(w.calls.createChild).toEqual([['ALPHA', TRIPS], [FOLDER_KIND, TRIPS]])

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

  it('fails closed: an entry whose rights function says no is not drawn; none left, no button', async () => {
    grant.value = false
    const w = mountLevel([TRIPS], { folder: TRIPS })
    expect(await newMenuLabels(w)).toEqual([t('node.folderCreate')])
    const readonly = mountLevel([TRIPS], { folder: TRIPS, pending: true })
    expect(hasNewMenu(readonly)).toBe(false)
  })

  it('an action whose handler is not ready is disabled, not a dead click', async () => {
    ready.value = false
    const w = mountLevel([TRIPS], { folder: TRIPS })
    const items = await openNewMenu(w)
    const upload = items.find((i) => i.textContent?.trim() === t('node.rename'))!
    expect(isDisabled(upload)).toBe(true)
    expect(isDisabled(items.find((i) => i.textContent?.trim() === t('node.anItem'))!)).toBe(false)
  })

  it('a READ guest gets no menu; a READ_WRITE guest gets the entries its rights grant', () => {
    expect(hasNewMenu(mountGuest(ShareRole.Read))).toBe(false)
    expect(hasNewMenu(mountGuest(ShareRole.ReadWrite))).toBe(true)
  })

  // A drop is only ever as enabled as the menu entry: allowed AND the handler enabled.
  describe('dropped files', () => {
    const drop = async (w: Level, ...names: string[]) => {
      await w.find('.veyq-level-view').trigger('drop', { dataTransfer: { files: names.map((n) => new File(['x'], n)) } })
      await flushPromises()
    }

    it('are handed to the action that takes them, while it is allowed and enabled', async () => {
      const w = mountLevel([TRIPS], { folder: TRIPS })
      await drop(w, 'a.txt', 'b.txt')
      expect(dropped).toEqual([['a.txt', 'b.txt']])
    })

    it('are ignored where the handler is not enabled (keys locked, a pending deletion)', async () => {
      ready.value = false
      await drop(mountLevel([TRIPS], { folder: TRIPS }), 'a.txt')
      expect(dropped).toEqual([])
    })

    it('are ignored where the rights function says no (a READ guest, a read-only level)', async () => {
      await drop(mountGuest(ShareRole.Read), 'a.txt')
      grant.value = false
      await drop(mountLevel([TRIPS], { folder: TRIPS }), 'a.txt')
      expect(dropped).toEqual([])
    })

    it('are ignored when an entry did not declare that it takes files (no `acceptsDrop`)', async () => {
      holder.reg = createRegistry()
      const rows = stubRows('a', { action: { id: 'x.upload', handler: { enabled: () => true, run: () => {}, drop: (f) => dropped.push(f.map((x) => x.name)) } } })
      testKind(holder.reg, { rekeyContent: nothingOfItsOwnToRekey,
        kind: 'ALPHA', icon: 'images', list: { rows: rows.factory },
        levelActions: [{ id: 'x.upload', label: 'node.rename', icon: 'upload', order: 30, allowed: () => true }],
      })
      await drop(mountLevel([TRIPS], { folder: TRIPS }), 'a.txt')
      expect(dropped).toEqual([])
    })

    // ADR 0082 decision 7: a download is recognised by its content and offered as an import to a member who may create here,
    // with "Upload as a file" beside it; a guest never gets the offer.
    const downloadFile = async (name = 'whatever.bin', version: 1 | 2 = 2) => {
      const parts: Uint8Array[] = []
      const sink = { write: async (b: Uint8Array) => void parts.push(b.slice()), close: async () => {}, abort: async () => {} }
      const zip = new ZipWriter(sink)
      const manifest = version === 1
        ? { format: 'veyq-subtree', version: 1, exportedAt: 'x', appVersion: 'x', root: { id: 'n1', kind: 'FOLDER', name: 'Trip' } }
        : { format: 'veyq-subtree', version: 2, exportedAt: 'x', appVersion: 'x', applications: [], root: { id: 'n1', kind: 'FOLDER', name: 'Trip', path: 'Trip' } }
      await zip.addBytes(version === 1 ? 'manifest.json' : '.veyq/manifest.json', new TextEncoder().encode(JSON.stringify(manifest)))
      await zip.finish()
      return new File(parts as BlobPart[], name)
    }
    const dropFiles = async (w: Level, ...files: File[]) => {
      await w.find('.veyq-level-view').trigger('drop', { dataTransfer: { files } })
      await flushPromises()
    }
    type Offer = { input: { kind: string; file?: File; name?: string; files?: { path: string; file: File }[] }; uploadAsFile?: () => void }
    const offers = (w: Level) => (w.calls.importArchive ?? []).map(([o]) => o as Offer)

    it('a download dropped on a member level is offered as an import, whatever its name; "Upload as a file" uploads it', async () => {
      for (const version of [1, 2] as const) {
        dropped.length = 0
        const w = mountLevel([TRIPS], { folder: TRIPS, unlocked: true })
        const file = await downloadFile('renamed.bin', version)
        await dropFiles(w, file)
        const [offer] = offers(w)
        expect(offer.input).toEqual({ kind: 'zip', file })
        expect(dropped).toEqual([])
        offer.uploadAsFile!()
        expect(dropped).toEqual([['renamed.bin']])
      }
    })

    it('one import per drop: the other files of the drop are uploaded at once', async () => {
      const w = mountLevel([TRIPS], { folder: TRIPS, unlocked: true })
      const download = await downloadFile('trip.zip')
      await dropFiles(w, new File(['x'], 'a.txt'), download, await downloadFile('second.zip'))
      expect(offers(w).map((o) => o.input.file)).toEqual([download])
      expect(dropped).toEqual([['a.txt', 'second.zip']])
    })

    it('a zip that is no download stays an ordinary upload; a guest never gets the import offer and uploads it', async () => {
      const w = mountLevel([TRIPS], { folder: TRIPS, unlocked: true })
      await drop(w, 'photos.zip')
      expect(w.calls.importArchive).toBeUndefined()
      expect(dropped).toEqual([['photos.zip']])
      const g = mountGuest(ShareRole.ReadWrite)
      await dropFiles(g, await downloadFile('trip.zip'))
      expect(g.calls.importArchive).toBeUndefined()
      expect(dropped).toEqual([['photos.zip'], ['trip.zip']])
    })

    it('files PICKED for an upload pass the same screen: a download is offered, the rest is uploaded', async () => {
      const w = mountLevel([TRIPS], { folder: TRIPS, unlocked: true })
      const host = rows.host()
      const back: string[][] = []
      const download = await downloadFile('trip.zip')
      const rest = await host.screenUpload!([new File(['x'], 'a.txt'), download], (f) => back.push(f.map((x) => x.name)))
      expect(rest.map((f) => f.name)).toEqual(['a.txt'])
      const [offer] = offers(w)
      expect(offer.input).toEqual({ kind: 'zip', file: download })
      offer.uploadAsFile!()
      expect(back).toEqual([['trip.zip']])
      // a guest's picks are never screened
      const g = mountGuest(ShareRole.ReadWrite)
      expect((await rows.host().screenUpload!([download], () => {})).map((f) => f.name)).toEqual(['trip.zip'])
      expect(g.calls.importArchive).toBeUndefined()
    })

    // A dropped directory, as `DataTransferItem.webkitGetAsEntry` hands it out.
    interface FakeFile { isFile: true; isDirectory: false; name: string; file(ok: (f: File) => void): void }
    interface FakeDir {
      isFile: false; isDirectory: true; name: string; children: (FakeFile | FakeDir)[]
      createReader(): { readEntries(ok: (e: (FakeFile | FakeDir)[]) => void): void }
      getFile(path: string, o: object | undefined, ok: (e: FakeFile | FakeDir) => void, fail: () => void): void
    }
    type Fake = FakeFile | FakeDir
    const fileEntry = (name: string, body = 'x'): FakeFile => ({ isFile: true, isDirectory: false, name, file: (ok) => ok(new File([body], name)) })
    function dirEntry(name: string, children: Fake[]): FakeDir {
      const dir: FakeDir = {
        isFile: false, isDirectory: true, name, children,
        createReader() {
          let given = false
          return { readEntries: (ok) => { ok(given ? [] : children); given = true } }
        },
        getFile(path, _o, ok, fail) {
          let at: Fake | undefined = dir
          for (const seg of path.split('/')) at = at && !at.isFile ? at.children.find((c) => c.name === seg) : undefined
          if (at) ok(at)
          else fail()
        },
      }
      return dir
    }
    const dropEntries = async (w: Level, ...entries: Fake[]) => {
      const files = entries.map((e) => new File([], e.name))
      const items = entries.map((e) => ({ kind: 'file', webkitGetAsEntry: () => e }))
      await w.find('.veyq-level-view').trigger('drop', { dataTransfer: { files, items } })
      await flushPromises()
    }

    it('a dropped directory holding .veyq/manifest.json (an unpacked download) is offered as an import from its files', async () => {
      const w = mountLevel([TRIPS], { folder: TRIPS, unlocked: true })
      const unpacked = dirEntry('Trip download', [dirEntry('.veyq', [fileEntry('manifest.json', '{}')]), dirEntry('Trip', [fileEntry('one.txt')])])
      await dropEntries(w, unpacked)
      const [offer] = offers(w)
      expect(offer.input.kind).toBe('directory')
      expect(offer.input.name).toBe('Trip download')
      expect(offer.input.files!.map((f) => f.path).sort()).toEqual(['.veyq/manifest.json', 'Trip/one.txt'])
      expect(offer.uploadAsFile).toBeUndefined()
      expect(dropped).toEqual([])
    })

    it('a directory without the manifest is the folder upload: handed to the action that takes directories, nothing imported', async () => {
      const w = mountLevel([TRIPS], { folder: TRIPS, unlocked: true })
      await dropEntries(w, dirEntry('Holiday', [fileEntry('a.jpg'), dirEntry('Day 1', [fileEntry('b.jpg')])]))
      expect(w.calls.importArchive).toBeUndefined()
      expect(dropped).toEqual([])
      expect(droppedDirs).toEqual([{ name: 'Holiday', paths: ['Day 1/b.jpg', 'a.jpg'] }])
      expect(toastError).not.toHaveBeenCalled()
    })

    it('one import per drop: a second unpacked download in the same drop is a folder upload', async () => {
      const w = mountLevel([TRIPS], { folder: TRIPS, unlocked: true })
      const unpacked = (name: string) => dirEntry(name, [dirEntry('.veyq', [fileEntry('manifest.json', '{}')])])
      await dropEntries(w, unpacked('One'), unpacked('Two'))
      expect(offers(w).map((o) => o.input.name)).toEqual(['One'])
      expect(droppedDirs.map((d) => d.name)).toEqual(['Two'])
    })

    it('a guest\'s dropped directory is always the folder upload, its .veyq/ included - never the import', async () => {
      const g = mountGuest(ShareRole.ReadWrite)
      await dropEntries(g, dirEntry('Trip download', [dirEntry('.veyq', [fileEntry('manifest.json', '{}')])]))
      expect(g.calls.importArchive).toBeUndefined()
      expect(droppedDirs).toEqual([{ name: 'Trip download', paths: ['.veyq/manifest.json'] }])
    })

    it('a dropped directory goes nowhere where no action takes it (not enabled, or none declared)', async () => {
      ready.value = false
      await dropEntries(mountLevel([TRIPS], { folder: TRIPS, unlocked: true }), dirEntry('Holiday', [fileEntry('a.jpg')]))
      expect(droppedDirs).toEqual([])
    })

    it('a directory PICKED for a folder upload passes the same screen: a download unpacked is the import (true), any other is not', async () => {
      const w = mountLevel([TRIPS], { folder: TRIPS, unlocked: true })
      const host = rows.host()
      const file = (path: string) => ({ path, file: new File(['x'], path.split('/').pop()!) })
      const unpacked = { kind: 'directory' as const, name: 'Trip download', files: [file('.veyq/manifest.json'), file('Trip/a.txt')] }
      // a manifest below the root (a download unpacked INTO a folder of it) does not make the directory one
      const plain = { kind: 'directory' as const, name: 'Holiday', files: [file('a.txt'), file('.veyq/other.json'), file('Old/.veyq/manifest.json')] }
      expect(host.screenDirectory!(plain)).toBe(false)
      expect(w.calls.importArchive).toBeUndefined()
      expect(host.screenDirectory!(unpacked)).toBe(true)
      expect(offers(w)).toEqual([{ input: unpacked }])
      // a guest's pick is never screened into the import
      mountGuest(ShareRole.ReadWrite)
      expect(rows.host().screenDirectory!(unpacked)).toBe(false)
      // nor a member's while the keys are locked
      mountLevel([TRIPS], { folder: TRIPS })
      expect(rows.host().screenDirectory!(unpacked)).toBe(false)
    })

    it('while the keys are locked a download is dropped as a plain upload', async () => {
      const w = mountLevel([TRIPS], { folder: TRIPS })
      await dropFiles(w, await downloadFile('trip.zip'))
      expect(w.calls.importArchive).toBeUndefined()
      expect(dropped).toEqual([['trip.zip']])
    })

    it('a drop with no file in it (text dragged in) does nothing, and the default is still prevented', async () => {
      const w = mountLevel([TRIPS], { folder: TRIPS })
      await w.find('.veyq-level-view').trigger('drop', { dataTransfer: { files: [] } })
      expect(dropped).toEqual([])
    })
  })
})
