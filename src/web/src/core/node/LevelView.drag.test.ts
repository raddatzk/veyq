// @vitest-environment happy-dom
// Drag and drop on the level (inbox #421, owner 2026-11-04) against a STUBBED registry (as `LevelView.select.test.ts`):
// kinds are made-up tokens whose part in a selection - `move` - is the stub's answer (an application's rights answer), a
// sub-folder's is core's `folderRights`. Each drag is the DOM's own events on the rendered items (`dragstart` on the dragged
// row, `dragover` / `drop` on the target), with the `dataTransfer` the browser would hand them. What the level hands the
// host is the `moveTo` door's call: the drop's run - its checks, writes and words - is the dialogs' (`moveRun.ts`,
// `nodeDialogs.moveTo.test.ts`).
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
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
import { MEMBER_DOORS, hostFor, recordingHost, type DoorName } from '../../test/nodeHost'
import { ShareRole } from '../../api/generated/sharing'
import { stubRows } from '../../test/stubRows'
import type { LevelRowSelection } from './levelRow'
import { nothingOfItsOwnToRekey } from './rekey/hooks'
import { testKind } from '../../test/testApplication'
import { NODE_DRAG_TYPE, endNodeDrag } from './nodeDrag'

function node(id: string, kind: NodeKind, parentId: string | null = 'Trips', over: Partial<TreeNode> = {}): TreeNode {
  return treeNode({ id, kind, name: id, parentId, ...over })
}
const TRIPS = node('Trips', FOLDER_KIND, null)
// ALPHA moves; BETA does not (its application's answer); a kind nobody registered is an unsupported row.
const ALLOWS: Record<string, LevelRowSelection> = { ALPHA: { move: true, delete: true }, BETA: { move: false, delete: true } }
const PLACED = { root: true, parents: [FOLDER_KIND] }
beforeEach(() => {
  holder.reg = createRegistry()
  localStorage.clear()
  setAccountUserId('user-1')
  setLevelTab('all')
  setViewMode('list')
  const rows = stubRows('a', { selection: (n) => ALLOWS[n.kind] })
  testKind(holder.reg, { rekeyContent: nothingOfItsOwnToRekey, kind: 'ALPHA', icon: 'images', placement: PLACED, list: { rows: rows.factory, tabLabel: 'memory.memories' } })
  testKind(holder.reg, { rekeyContent: nothingOfItsOwnToRekey, kind: 'BETA', icon: 'file', placement: PLACED, list: { rows: rows.factory, tabLabel: 'memory.memories' } })
})
afterEach(() => endNodeDrag())

// Trips (on screen) ─ Sub/, Box/ (a guest's identity folder), a1, a2, b1, X (a kind nobody registered)
const TREE = [
  TRIPS, node('Sub', FOLDER_KIND), node('Box', FOLDER_KIND, 'Trips', { shareIdentityId: 'id-1' }),
  node('a1', 'ALPHA'), node('a2', 'ALPHA'), node('b1', 'BETA'), node('X', 'UNKNOWN_KIND'),
]
function mountLevel(o: { access?: Access; doors?: readonly DoorName[]; tree?: TreeNode[] } = {}) {
  const tree = o.tree ?? TREE
  const access = o.access ?? memberAccessOf({})
  const rec = o.doors ? recordingHost(o.doors) : hostFor(access)
  const w = mount(LevelView, {
    props: { folder: TRIPS, level: childrenOf(tree, 'Trips'), access, tree: memberTreeOf(() => tree) },
    global: { plugins: [realI18n()], provide: rec.provide },
    attachTo: document.body,
  })
  return Object.assign(w, { calls: rec.calls })
}
type Level = ReturnType<typeof mountLevel>

const item = (w: Level, name: string) => w.findAll('li.veyq-level-item').find((li) => li.find('.veyq-level-item-title').text() === name)!
const draggable = (w: Level) => w.findAll('li.veyq-level-item').filter((li) => li.attributes('draggable') === 'true').map((li) => li.find('.veyq-level-item-title').text())

/** The `dataTransfer` of one drag, as the browser hands the same one to every event of it. */
function transfer(types: string[] = []) {
  return {
    types: [...types],
    dropEffect: 'unset',
    effectAllowed: 'unset',
    setData(type: string) {
      this.types.push(type)
    },
    setDragImage: vi.fn(),
  }
}
async function drag(w: Level, from: string) {
  const data = transfer()
  await item(w, from).trigger('dragstart', { dataTransfer: data })
  return data
}
/** A `dragover` on `name`'s item: whether the page took it there (its default prevented - by the item or, as it bubbles, by
 *  the level: a drag of nodes must be taken by neither where it may not drop). */
async function over(w: Level, name: string, data: ReturnType<typeof transfer>): Promise<boolean> {
  const e = new Event('dragover', { bubbles: true, cancelable: true })
  Object.defineProperty(e, 'dataTransfer', { value: data })
  item(w, name).element.dispatchEvent(e)
  await w.vm.$nextTick()
  return e.defaultPrevented
}

describe('LevelView drag and drop: what may be dragged', () => {
  it('a member drags every node it may move - a folder and a node its application lets move - and nothing else', () => {
    const w = mountLevel()
    expect(draggable(w)).toEqual(['Box', 'Sub', 'a1', 'a2'])
    expect(item(w, 'b1').attributes('draggable'), 'its application does not let it move: no attribute at all').toBeUndefined()
    expect(item(w, 'X').attributes('draggable'), 'a kind this client cannot show').toBeUndefined()
  })

  it('no move door, no drag: a READ guest\'s page drags nothing, whatever the rows say', () => {
    expect(draggable(mountLevel({ access: guestAccessOf(ShareRole.Read) }))).toEqual([])
    expect(draggable(mountLevel({ doors: MEMBER_DOORS.filter((d) => d !== 'moveTo') }))).toEqual([])
  })

  it('a READ_WRITE guest drags what its move dialog moves: the same rows, the same rights', () => {
    const w = mountLevel({ access: guestAccessOf(ShareRole.ReadWrite) })
    expect(draggable(w)).toEqual(['Sub', 'a1', 'a2'])
    expect(item(w, 'Box').attributes('draggable'), "a guest never moves a guest's identity folder (core's folderRights)").toBeUndefined()
    expect(item(w, 'b1').attributes('draggable')).toBeUndefined()
  })

  it('the primary click still opens the node: a click is no drag', async () => {
    const w = mountLevel()
    await item(w, 'Sub').find('button.veyq-level-item-main').trigger('click')
    expect(w.calls.open).toEqual([[expect.objectContaining({ id: 'Sub' })]])
    expect(w.calls.moveTo).toBeUndefined()
  })

  it('the grid drags its tiles as the list its rows', async () => {
    setViewMode('grid')
    const w = mountLevel()
    expect(item(w, 'a1').classes()).toContain('is-grid')
    expect(draggable(w)).toEqual(['Box', 'Sub', 'a1', 'a2'])
  })

  it('an application\'s own presentation (the map) has no rows, so nothing to drag', async () => {
    holder.reg = createRegistry()
    const MAP = { mode: 'map', icon: 'map', label: 'node.folders' } as const
    const a = stubRows('a', { presentationFor: 'map', selection: () => ({ move: true, delete: true }) })
    testKind(holder.reg, { rekeyContent: nothingOfItsOwnToRekey, kind: 'ALPHA', icon: 'images', placement: PLACED, list: { rows: a.factory, views: [GRID_VIEW, LIST_VIEW, MAP] } })
    setViewMode('map')
    const w = mountLevel({ tree: [TRIPS, node('a1', 'ALPHA')] })
    expect(w.find('.stub-presentation').exists()).toBe(true)
    expect(w.findAll('[draggable="true"]')).toHaveLength(0)
  })
})

describe('LevelView drag and drop: where it drops', () => {
  it('a sub-folder takes it and is lit; the dragged item dims; the drop hands the node and the folder to the move door', async () => {
    const w = mountLevel()
    const data = await drag(w, 'a1')
    expect(data.types).toContain(NODE_DRAG_TYPE)
    expect(data.effectAllowed).toBe('move')
    expect(item(w, 'a1').classes()).toContain('is-dragging')
    expect(item(w, 'a2').classes()).not.toContain('is-dragging')
    expect(await over(w, 'Sub', data)).toBe(true)
    expect(data.dropEffect).toBe('move')
    expect(item(w, 'Sub').classes()).toContain('is-drop-target')
    await item(w, 'Sub').trigger('drop', { dataTransfer: data })
    expect(w.calls.moveTo).toEqual([[[expect.objectContaining({ id: 'a1' })], 'Sub']])
    expect(item(w, 'Sub').classes()).not.toContain('is-drop-target')
    expect(w.calls.importArchive, 'never an upload or import').toBeUndefined()
  })

  it('a place that refuses is not lit and does not take it: itself, a guest\'s identity folder, and anything that is no folder', async () => {
    const w = mountLevel()
    let data = await drag(w, 'Sub')
    expect(await over(w, 'Sub', data), 'itself').toBe(false)
    expect(data.dropEffect).toBe('none')
    expect(item(w, 'Sub').classes()).not.toContain('is-drop-target')
    await item(w, 'Sub').trigger('dragend')
    data = await drag(w, 'a1')
    expect(await over(w, 'Box', data), "a guest's identity folder takes nothing").toBe(false)
    expect(item(w, 'Box').classes()).not.toContain('is-drop-target')
    expect(await over(w, 'a2', data), 'a node that is no folder is no target').toBe(false)
    // a drop where nothing took it moves nothing
    await item(w, 'Box').trigger('drop', { dataTransfer: data })
    await item(w, 'a2').trigger('drop', { dataTransfer: data })
    expect(w.calls.moveTo).toBeUndefined()
  })

  it('a drag of files from the operating system lights no folder: it stays the level\'s upload', async () => {
    const w = mountLevel()
    const files = transfer(['Files'])
    expect(await over(w, 'Sub', files), 'the level takes a file drop wherever it lands').toBe(true)
    expect(item(w, 'Sub').classes()).not.toContain('is-drop-target')
    expect(files.dropEffect, 'not told it is a move').toBe('unset')
  })

  it('the drag ends: nothing dims or is lit any more', async () => {
    const w = mountLevel()
    const data = await drag(w, 'a1')
    await over(w, 'Sub', data)
    await item(w, 'a1').trigger('dragend')
    expect(item(w, 'a1').classes()).not.toContain('is-dragging')
    expect(item(w, 'Sub').classes()).not.toContain('is-drop-target')
  })
})

describe('LevelView drag and drop in Select mode', () => {
  async function select(w: Level, ...names: string[]) {
    await w.find('.veyq-level-toolbar .veyq-select-button').trigger('click')
    for (const name of names) await w.find(`input[type="checkbox"][aria-label="${name}"]`).setValue(true)
  }

  it('a selected node drags the selection - what Move to… would move -, the picture saying how many', async () => {
    const w = mountLevel()
    await select(w, 'a1', 'a2', 'b1') // b1 may not move: left out, as the bar's Move to… leaves it out
    const data = await drag(w, 'a1')
    expect(['a1', 'a2', 'b1'].map((n) => item(w, n).classes().includes('is-dragging'))).toEqual([true, true, false])
    expect((data.setDragImage.mock.calls[0]?.[0] as HTMLElement | undefined)?.textContent).toBe(t('node.drag.count', { count: 2 }, 2))
    await over(w, 'Sub', data)
    await item(w, 'Sub').trigger('drop', { dataTransfer: data })
    expect(w.calls.moveTo).toEqual([[[expect.objectContaining({ id: 'a1' }), expect.objectContaining({ id: 'a2' })], 'Sub']])
    // the very nodes the bar's Move to… hands the dialog
    await w.find('.veyq-select-move').trigger('click')
    expect(w.calls.moveMany?.[0][0]).toEqual(w.calls.moveTo?.[0][0])
  })

  it('an unselected node drags itself alone', async () => {
    const w = mountLevel()
    await select(w, 'a1', 'a2')
    const data = await drag(w, 'Box')
    expect(item(w, 'a1').classes()).not.toContain('is-dragging')
    expect(data.setDragImage).not.toHaveBeenCalled()
    await over(w, 'Sub', data)
    await item(w, 'Sub').trigger('drop', { dataTransfer: data })
    expect(w.calls.moveTo).toEqual([[[expect.objectContaining({ id: 'Box' })], 'Sub']])
  })

  it('a selection that holds the target folder is not taken by it', async () => {
    const w = mountLevel()
    await select(w, 'a1', 'Sub')
    const data = await drag(w, 'a1')
    expect(await over(w, 'Sub', data)).toBe(false)
    expect(item(w, 'Sub').classes()).not.toContain('is-drop-target')
  })
})
