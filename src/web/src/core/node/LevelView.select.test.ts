// @vitest-environment happy-dom
// The level's Select mode (inbox #421) against a STUBBED registry (as `LevelView.test.ts`): kinds are made-up tokens, each
// row's part in a selection is the stub's `selection` answer (an application's rights answer), a sub-folder's is core's
// `folderRights`. Every control is clicked for real: the toolbar's Select, each row's checkbox, the bar's All / None,
// the tabs, Move to… and Delete. What the level hands the host is the emitted part of the selection; the writes are the
// hosts' (`MoveNodesDialog`, `DeleteNodesDialog`).
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'

const holder = vi.hoisted(() => ({ reg: null as null | import('../applications/registry').Registry }))
vi.mock('../applications/registry', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../applications/registry')>()
  const r = () => (holder.reg ??= actual.createRegistry())
  return {
    ...actual,
    registry: new Proxy({} as import('../applications/registry').Registry, {
      get: (_t, name: string) => (r() as unknown as Record<string, (...a: unknown[]) => unknown>)[name].bind(r()),
    }),
  }
})

import LevelView from './LevelView.vue'
import { createRegistry } from '../applications/registry'
import { childrenOf } from './browse'
import { FOLDER_KIND, type NodeKind } from './kind'
import type { TreeNode } from './tree'
import type { Access } from '../access'
import { setViewMode } from '../../shared/state/useViewMode'
import { setLevelTab } from '../../shared/state/useLevelTab'
import { setAccountUserId } from '../../app/account'
import { GRID_VIEW, LIST_VIEW } from './levelViews'
import { realI18n, t } from '../../test/i18n'
import { treeNode, memberAccessOf, memberTreeOf, guestAccessOf } from '../../test/models'
import { ShareRole } from '../../api/generated/sharing'
import { stubRows } from '../../test/stubRows'
import { titlesOf } from '../../test/levelRows'
import type { LevelRowSelection } from './levelRow'

function node(id: string, kind: NodeKind, parentId: string | null = 'Trips'): TreeNode {
  return treeNode({ id, kind, name: id, parentId, orderIndex: 0, epoch: 0, wrappedContentKey: 'eA==', wrappedMeta: null, metaRevision: 0, placementRevision: 0 })
}
const TRIPS = node('Trips', FOLDER_KIND, null)
const OTHER = node('Other', FOLDER_KIND, null)
const LABEL_A = 'memory.memories'
const LABEL_B = 'depot.title'
const remove = vi.fn(async () => {})

// ALPHA moves and is deleted; BETA moves only, and carries its own delete door where it would be deleted (b-del).
const ALLOWS: Record<string, LevelRowSelection> = {
  ALPHA: { move: true, delete: true },
  BETA: { move: true, delete: false },
}
beforeEach(() => {
  holder.reg = createRegistry()
  localStorage.clear()
  setAccountUserId('user-1')
  setLevelTab('all')
  setViewMode('list')
  remove.mockClear()
  const a = stubRows('a', { selection: (n) => ALLOWS[n.kind] })
  const b = stubRows('b', { selection: (n) => (n.name === 'b-del' ? { move: false, delete: true, remove } : ALLOWS[n.kind]) })
  holder.reg.registerKind({ kind: 'ALPHA', icon: 'images', shareable: false, list: { rows: a.factory, tabLabel: LABEL_A } })
  holder.reg.registerKind({ kind: 'BETA', icon: 'file', shareable: false, list: { rows: b.factory, tabLabel: LABEL_B } })
})

const TREE = [
  TRIPS, OTHER, node('Sub', FOLDER_KIND), node('a1', 'ALPHA'), node('a2', 'ALPHA'), node('b1', 'BETA'), node('b-del', 'BETA'),
  node('o1', 'ALPHA', 'Other'),
]
const PLUGINS = { plugins: [realI18n()] }
function mountLevel(tree: TreeNode[] = TREE, access: Access = memberAccessOf({}), folder: TreeNode | null = TRIPS) {
  return mount(LevelView, {
    props: { folder, level: childrenOf(tree, folder?.id ?? null), access, tree: memberTreeOf(() => tree) },
    global: PLUGINS,
    attachTo: document.body,
  })
}
type Level = ReturnType<typeof mountLevel>

const selectButton = (w: Level) => w.find('.veyq-level-toolbar .veyq-select-button')
const bar = (w: Level) => w.find('.veyq-level-selection-bar')
const checkbox = (w: Level, name: string) => w.find(`input[type="checkbox"][aria-label="${name}"]`)
const checked = (w: Level) =>
  w.findAll('input[type="checkbox"]').filter((c) => (c.element as HTMLInputElement).checked).map((c) => c.attributes('aria-label'))
const count = (w: Level) => w.find('.veyq-level-selection-count').text()
const tab = (w: Level, label: string) => w.findAll('[role="tab"]').find((b) => b.text() === label)!
async function choose(w: Level, ...names: string[]) {
  for (const name of names) await checkbox(w, name).setValue(!(checkbox(w, name).element as HTMLInputElement).checked)
}
async function startSelecting(w: Level) {
  await selectButton(w).trigger('click')
}

describe('LevelView: Select', () => {
  it('a toolbar button beside "New" and the trash; pressed, it puts a labelled checkbox on every row and shows the bar', async () => {
    const w = mountLevel()
    expect(w.findAll('input[type="checkbox"]'), 'no checkbox before the mode').toHaveLength(0)
    expect(selectButton(w).attributes('aria-pressed')).toBe('false')
    expect(selectButton(w).text()).toBe(t('node.select.action'))
    await startSelecting(w)
    expect(selectButton(w).attributes('aria-pressed')).toBe('true')
    // every row, by the node's own name - a real checkbox
    expect(w.findAll('input[type="checkbox"]').map((c) => c.attributes('aria-label'))).toEqual(titlesOf(w))
    expect(bar(w).attributes('role')).toBe('group')
    expect(bar(w).attributes('aria-label')).toBe(t('node.select.bar'))
    expect(w.find('.veyq-level-selection-count').attributes('aria-live')).toBe('polite')
    expect(count(w)).toBe(t('node.select.count', { count: 0 }))
  })

  it('a checkbox selects its row; the row\'s primary click still opens the node and selects nothing', async () => {
    const w = mountLevel()
    await startSelecting(w)
    await choose(w, 'a1')
    expect(checked(w)).toEqual(['a1'])
    expect(count(w)).toBe(t('node.select.count', { count: 1 }))
    await w.find('button[aria-label="open a2"]').trigger('click')
    expect(w.emitted('openNode')).toEqual([[expect.objectContaining({ id: 'a2' })]])
    expect(checked(w)).toEqual(['a1'])
    await choose(w, 'a1')
    expect(checked(w)).toEqual([])
  })

  it('All selects every row on screen, None takes them away', async () => {
    const w = mountLevel()
    await startSelecting(w)
    await w.find('.veyq-select-all').trigger('click')
    expect(checked(w)).toEqual(['Sub', 'a1', 'a2', 'b-del', 'b1'])
    expect(count(w)).toBe(t('node.select.count', { count: 5 }))
    await w.find('.veyq-select-none').trigger('click')
    expect(checked(w)).toEqual([])
  })

  it('is kept across a tab switch: the count says how many of the selected the tab hides; All / None act on the tab shown', async () => {
    const w = mountLevel()
    await startSelecting(w)
    await choose(w, 'a1', 'b1')
    await tab(w, t(LABEL_A)).trigger('click')
    expect(titlesOf(w)).toEqual(['Sub', 'a1', 'a2'])
    expect(checked(w)).toEqual(['a1'])
    expect(count(w)).toBe(t('node.select.countHidden', { count: 2, hidden: 1 }))
    await w.find('.veyq-select-all').trigger('click')
    expect(count(w)).toBe(t('node.select.countHidden', { count: 4, hidden: 1 }))
    await w.find('.veyq-select-none').trigger('click')
    expect(count(w), 'None leaves the hidden part').toBe(t('node.select.countHidden', { count: 1, hidden: 1 }))
    await tab(w, t('node.tabAll')).trigger('click')
    expect(checked(w)).toEqual(['b1'])
    expect(count(w)).toBe(t('node.select.count', { count: 1 }))
  })

  it('leaving the mode clears it: pressed again, it starts empty', async () => {
    const w = mountLevel()
    await startSelecting(w)
    await choose(w, 'a1', 'a2')
    await startSelecting(w) // off
    expect(bar(w).exists()).toBe(false)
    expect(w.findAll('input[type="checkbox"]')).toHaveLength(0)
    await startSelecting(w)
    expect(checked(w)).toEqual([])
  })

  it('another folder clears it and leaves the mode; a node that leaves the level drops out of it', async () => {
    const w = mountLevel()
    await startSelecting(w)
    await choose(w, 'a1', 'a2')
    // a2 moved away (the tree re-read): the selection keeps a1 only
    const after = TREE.filter((n) => n.id !== 'a2')
    await w.setProps({ level: childrenOf(after, 'Trips'), tree: memberTreeOf(() => after) })
    expect(checked(w)).toEqual(['a1'])
    expect(count(w)).toBe(t('node.select.count', { count: 1 }))
    // it comes back (an Undo moved it back): not selected again - it dropped out when it left
    await w.setProps({ level: childrenOf(TREE, 'Trips'), tree: memberTreeOf(() => TREE) })
    expect(checked(w)).toEqual(['a1'])
    await w.setProps({ folder: OTHER, level: childrenOf(TREE, 'Other') })
    expect(selectButton(w).attributes('aria-pressed')).toBe('false')
    await w.setProps({ folder: TRIPS, level: childrenOf(TREE, 'Trips') })
    await startSelecting(w)
    expect(checked(w)).toEqual([])
  })

  it('no Select in an application\'s own presentation (the map has no rows); switching to it leaves the mode', async () => {
    holder.reg = createRegistry()
    const MAP = { mode: 'map', icon: 'map', label: 'node.folders' } as const
    const a = stubRows('a', { presentationFor: 'map', selection: () => ({ move: true, delete: true }) })
    holder.reg.registerKind({ kind: 'ALPHA', icon: 'images', shareable: false, list: { rows: a.factory, views: [GRID_VIEW, LIST_VIEW, MAP] } })
    const tree = [TRIPS, node('a1', 'ALPHA')]
    const w = mountLevel(tree)
    await startSelecting(w)
    expect(bar(w).exists()).toBe(true)
    setViewMode('map')
    await w.vm.$nextTick()
    expect(selectButton(w).exists()).toBe(false)
    expect(bar(w).exists()).toBe(false)
    setViewMode('grid')
    await w.vm.$nextTick()
    expect(selectButton(w).attributes('aria-pressed')).toBe('false')
  })

  it('no Select where no node here allows an action (a READ guest; rows without a selection answer)', () => {
    // a READ guest's sub-folders: core's matrix grants it nothing
    expect(selectButton(mountLevel([TRIPS, node('Sub', FOLDER_KIND)], guestAccessOf(ShareRole.Read))).exists()).toBe(false)
    holder.reg = createRegistry()
    holder.reg.registerKind({ kind: 'ALPHA', icon: 'images', shareable: false, list: { rows: stubRows('a').factory } })
    expect(selectButton(mountLevel([TRIPS, node('a1', 'ALPHA')], memberAccessOf({ readonly: true }))).exists()).toBe(false)
    // control: the same rows for a member who writes - a sub-folder moves
    expect(selectButton(mountLevel([TRIPS, node('Sub', FOLDER_KIND), node('a1', 'ALPHA')])).exists()).toBe(true)
  })
})

describe('LevelView: what the selection may do', () => {
  it('Move to… and Delete are disabled until a selected node allows them', async () => {
    const w = mountLevel()
    await startSelecting(w)
    const move = w.find('.veyq-select-move')
    const del = w.find('.veyq-select-delete')
    expect(move.attributes('disabled')).toBeDefined()
    expect(del.attributes('disabled')).toBeDefined()
    await choose(w, 'b1') // moves, is not deleted
    expect(move.attributes('disabled')).toBeUndefined()
    expect(del.attributes('disabled'), 'no selected node may be deleted').toBeDefined()
    expect(w.find('#veyq-select-cannot-delete').text()).toBe(t('node.select.cannotDelete', { n: 1, m: 1 }))
    expect(del.attributes('aria-describedby')).toBe('veyq-select-cannot-delete')
    await choose(w, 'b1', 'b-del') // deleted, does not move
    expect(move.attributes('disabled')).toBeDefined()
    expect(del.attributes('disabled')).toBeUndefined()
  })

  it('says "n of m" for the part an action cannot reach, and hands the host exactly the part it can', async () => {
    const w = mountLevel()
    await startSelecting(w)
    await choose(w, 'Sub', 'a1', 'b1', 'b-del')
    expect(w.find('#veyq-select-cannot-move').text()).toBe(t('node.select.cannotMove', { n: 1, m: 4 }))
    expect(w.find('#veyq-select-cannot-delete').text()).toBe(t('node.select.cannotDelete', { n: 1, m: 4 }))
    await w.find('.veyq-select-move').trigger('click')
    expect((w.emitted('moveNodes')![0][0] as TreeNode[]).map((n) => n.id).sort()).toEqual(['Sub', 'a1', 'b1'])
    await w.find('.veyq-select-delete').trigger('click')
    const handed = w.emitted('deleteNodes')![0][0] as { node: TreeNode; remove?: unknown }[]
    expect(handed.map((x) => x.node.id).sort()).toEqual(['Sub', 'a1', 'b-del'])
    expect(handed.find((x) => x.node.id === 'b-del')!.remove, 'its kind\'s own door travels with it').toBe(remove)
    expect(handed.find((x) => x.node.id === 'a1')!.remove, 'none: core\'s delete').toBeUndefined()
  })

  it('a selected node the tab hides moves along with the rest', async () => {
    const w = mountLevel()
    await startSelecting(w)
    await choose(w, 'b1')
    await tab(w, t(LABEL_A)).trigger('click')
    await choose(w, 'a1')
    await w.find('.veyq-select-move').trigger('click')
    expect((w.emitted('moveNodes')![0][0] as TreeNode[]).map((n) => n.id).sort()).toEqual(['a1', 'b1'])
  })

  it('a READ_WRITE guest selects and moves, and is offered no Delete (a guest deletes no node)', async () => {
    const w = mountLevel([TRIPS, node('Sub', FOLDER_KIND), node('Sub2', FOLDER_KIND)], guestAccessOf(ShareRole.ReadWrite))
    await startSelecting(w)
    expect(w.find('.veyq-select-move').exists()).toBe(true)
    expect(w.find('.veyq-select-delete').exists()).toBe(false)
    await w.find('.veyq-select-all').trigger('click')
    expect(w.find('#veyq-select-cannot-delete').exists()).toBe(false)
    await w.find('.veyq-select-move').trigger('click')
    expect((w.emitted('moveNodes')![0][0] as TreeNode[]).map((n) => n.id)).toEqual(['Sub', 'Sub2'])
  })
})
