// @vitest-environment happy-dom
// The application tabs of core's level view (inbox #348; a pure FILTER over the one list, inbox #407) with REAL DOM clicks on
// the rendered tab buttons, against a stubbed registry (as `LevelView.test.ts`): kinds are made-up tokens, tab labels are real
// catalog keys. The tabs filter by the KIND's application; the folders are shown in every tab.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

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
import { setViewMode } from '../../shared/state/useViewMode'
import { setLevelTab, useLevelTab, levelTabStorageKey } from '../../shared/state/useLevelTab'
import { setAccountUserId } from '../../app/account'
import { realI18n, t } from '../../test/i18n'
import { treeNode, memberAccessOf, memberTreeOf, guestAccessOf } from '../../test/models'
import { ShareRole } from '../../api/generated/sharing'
import { stubRows } from '../../test/stubRows'
import { titlesOf } from '../../test/levelRows'
import { isDisabled, newMenuLabels, openNewMenu } from '../../test/newMenu'
import { recordingHost } from '../../test/nodeHost'
import { epochsOf } from '../../features/spaces/epochsTestUtil'
import { ZipWriter } from '../export/zip'
import { nothingOfItsOwnToRekey } from './rekey/hooks'
import { testKind } from '../../test/testApplication'

function node(id: string, kind: NodeKind, parentId: string | null = null): TreeNode {
  return treeNode({ id, kind, name: id, parentId, orderIndex: 0, epoch: 0, wrappedContentKey: 'eA==', wrappedMeta: null, metaRevision: 0, placementRevision: 0 })
}
const TRIPS = node('Trips', FOLDER_KIND)
const SUB = node('Sub', FOLDER_KIND, 'Trips')

const PLUGINS = { plugins: [realI18n()] }
function mountLevel(tree: TreeNode[], guest = false, attachTo?: HTMLElement) {
  const level = childrenOf(tree, TRIPS.id)
  return mount(LevelView, {
    props: { folder: TRIPS, level, access: guest ? guestAccessOf(ShareRole.Read) : memberAccessOf({}), tree: memberTreeOf(() => tree) },
    global: PLUGINS,
    attachTo,
  })
}
type Level = ReturnType<typeof mountLevel>

// A pointer pick is the `mousedown` of a tab: Reka's tabs switch on the left button's press, not on `click`.
const tabButtons = (w: Level) => w.findAll('[role="tab"]')
const tabNamed = (w: Level, label: string) => tabButtons(w).find((b) => b.text() === label)!
const selected = (w: Level) => tabButtons(w).filter((b) => b.attributes('aria-selected') === 'true').map((b) => b.text())

const LABEL_A = 'memory.memories'
const LABEL_B = 'depot.title'
let b: ReturnType<typeof stubRows>

beforeEach(() => {
  holder.reg = createRegistry()
  localStorage.clear()
  setAccountUserId('user-1')
  setLevelTab('all')
  setViewMode('grid')
  const a = stubRows('a')
  b = stubRows('b')
  testKind(holder.reg, { rekeyContent: nothingOfItsOwnToRekey, kind: 'ALPHA', icon: 'images', list: { rows: a.factory, tabLabel: LABEL_A } })
  testKind(holder.reg, { rekeyContent: nothingOfItsOwnToRekey, kind: 'BETA', icon: 'file', list: { rows: b.factory, tabLabel: LABEL_B } })
})

const TREE = [TRIPS, SUB, node('a1', 'ALPHA', 'Trips'), node('b1', 'BETA', 'Trips'), node('x1', 'OTHER', 'Trips')]

describe('LevelView application tabs', () => {
  it('offers All and one tab per application, with proper tablist semantics, All selected by default', () => {
    const w = mountLevel(TREE)
    expect(w.find('[role="tablist"]').attributes('aria-label')).toBe(t('node.tabs'))
    expect(tabButtons(w).map((x) => x.text())).toEqual([t('node.tabAll'), t(LABEL_A), t(LABEL_B)])
    expect(tabButtons(w).every((x) => x.element.tagName === 'BUTTON' && !x.attributes('href'))).toBe(true)
    expect(selected(w)).toEqual([t('node.tabAll')])
    // All = the whole level in ONE list: the folder, both applications' rows (mixed by name), the unsupported kind
    expect(titlesOf(w)).toEqual(['Sub', 'a1', 'b1', 'x1'])
    expect(w.find('.is-unsupported').exists()).toBe(true)
  })

  it('a click on an application tab filters the list to that application\'s nodes, keeps the sub-folders and drops the unsupported row', async () => {
    const w = mountLevel(TREE)
    await tabNamed(w, t(LABEL_B)).trigger('mousedown')
    expect(titlesOf(w)).toEqual(['Sub', 'b1'])
    expect(w.find('.is-unsupported').exists()).toBe(false)
    expect(selected(w)).toEqual([t(LABEL_B)])
    await tabNamed(w, t(LABEL_A)).trigger('mousedown')
    expect(titlesOf(w)).toEqual(['Sub', 'a1'])
    await tabNamed(w, t('node.tabAll')).trigger('mousedown')
    expect(titlesOf(w)).toEqual(['Sub', 'a1', 'b1', 'x1'])
  })

  it('keeps the folders in EVERY tab, however many there are, and the filter changes nothing about them', async () => {
    const tree = [...TREE, node('Zed', FOLDER_KIND, 'Trips'), node('Mid', FOLDER_KIND, 'Trips')]
    const w = mountLevel(tree)
    for (const label of [t('node.tabAll'), t(LABEL_A), t(LABEL_B)]) {
      await tabNamed(w, label).trigger('mousedown')
      expect(titlesOf(w).slice(0, 3), label).toEqual(['Mid', 'Sub', 'Zed'])
    }
  })

  it('persists the selection: a remount shows it, and the preference is in storage', async () => {
    const first = mountLevel(TREE)
    await tabNamed(first, t(LABEL_B)).trigger('mousedown')
    expect(localStorage.getItem(levelTabStorageKey()!)).toBe('BETA')
    first.unmount()
    const second = mountLevel(TREE)
    expect(titlesOf(second)).toEqual(['Sub', 'b1'])
    expect(selected(second)).toEqual([t(LABEL_B)])
  })

  it('shows All for a stored tab this level lacks, WITHOUT overwriting the stored preference', async () => {
    setLevelTab('BETA')
    // an identity folder offers no tab for a kind with nothing in it: here BETA has no node, so the bar has ALPHA and GAMMA only
    const c = stubRows('c')
    testKind(holder.reg!, { rekeyContent: nothingOfItsOwnToRekey, kind: 'GAMMA', icon: 'inbox', list: { rows: c.factory, tabLabel: 'expenses.title' } })
    const level = childrenOf([TRIPS, node('a1', 'ALPHA', 'Trips'), node('g1', 'GAMMA', 'Trips')], TRIPS.id)
    const tree = memberTreeOf(() => [TRIPS])
    const w = mount(LevelView, {
      props: { folder: TRIPS, level, access: memberAccessOf({}), tree: { ...tree, inIdentityFolder: () => true } },
      global: PLUGINS,
    })
    expect(tabButtons(w).map((x) => x.text())).toEqual([t('node.tabAll'), t(LABEL_A), t('expenses.title')])
    expect(selected(w)).toEqual([t('node.tabAll')])
    expect(titlesOf(w)).toEqual(['a1', 'g1'])
    expect(useLevelTab().levelTab.value).toBe('BETA')
    expect(localStorage.getItem(levelTabStorageKey()!)).toBe('BETA')
    // …and the level that has BETA shows it again
    w.unmount()
    expect(titlesOf(mountLevel(TREE))).toEqual(['Sub', 'b1'])
  })

  it('shows no tab bar with fewer than two applications that have a tab (and then no filtering, whatever is stored)', () => {
    setLevelTab('BETA')
    holder.reg = createRegistry()
    testKind(holder.reg, { rekeyContent: nothingOfItsOwnToRekey, kind: 'ALPHA', icon: 'images', list: { rows: stubRows('a').factory, tabLabel: LABEL_A } })
    const one = mountLevel(TREE)
    expect(one.find('[role="tablist"]').exists()).toBe(false)
    expect(titlesOf(one)).toEqual(['Sub', 'a1', 'b1', 'x1'])
    holder.reg = createRegistry()
    testKind(holder.reg, { rekeyContent: nothingOfItsOwnToRekey, kind: 'ALPHA', icon: 'images', list: { rows: stubRows('a').factory, tabLabel: LABEL_A } })
    testKind(holder.reg, { rekeyContent: nothingOfItsOwnToRekey, kind: 'BETA', icon: 'file', list: { rows: stubRows('b').factory } }) // no label: no tab
    const unlabelled = mountLevel(TREE)
    expect(unlabelled.find('[role="tablist"]').exists()).toBe(false)
    expect(titlesOf(unlabelled)).toEqual(['Sub', 'a1', 'b1', 'x1'])
  })

  it('moves between tabs with the arrow keys (roving tabindex) and selects with Enter', async () => {
    // attached, so focus moves for real: the bar is ONE tab stop, and tabbing into it lands on the picked tab
    const w = mountLevel(TREE, false, document.body)
    const all = tabNamed(w, t('node.tabAll'))
    const list = w.find('[role="tablist"]')
    await flushPromises() // the bar counts its tabs as they mount
    expect(list.attributes('tabindex')).toBe('0')
    await list.trigger('focus')
    expect(document.activeElement).toBe(all.element)
    expect(all.attributes('tabindex')).toBe('0')
    expect(tabNamed(w, t(LABEL_A)).attributes('tabindex')).toBe('-1')
    await all.trigger('keydown', { code: 'ArrowRight', key: 'ArrowRight' })
    await flushPromises()
    expect(document.activeElement).toBe(tabNamed(w, t(LABEL_A)).element)
    await tabNamed(w, t(LABEL_A)).trigger('keydown', { code: 'Enter', key: 'Enter' })
    expect(titlesOf(w)).toEqual(['Sub', 'a1'])
    w.unmount()
  })

  it('a guest gets the same bar and the same filter (share = member view)', async () => {
    const w = mountLevel(TREE, true)
    expect(tabButtons(w).map((x) => x.text())).toEqual([t('node.tabAll'), t(LABEL_A), t(LABEL_B)])
    await tabNamed(w, t(LABEL_A)).trigger('mousedown')
    expect(titlesOf(w)).toEqual(['Sub', 'a1'])
  })

  it('kinds sharing a tab label share ONE tab: its filter shows the nodes of both', async () => {
    holder.reg = createRegistry()
    const shared = stubRows('s')
    testKind(holder.reg, { rekeyContent: nothingOfItsOwnToRekey, kind: 'ALPHA', icon: 'images', list: { rows: shared.factory, tabLabel: LABEL_A } })
    testKind(holder.reg, { rekeyContent: nothingOfItsOwnToRekey, kind: 'ALPHA_PART', icon: 'images', list: { rows: shared.factory, tabLabel: LABEL_A } })
    testKind(holder.reg, { rekeyContent: nothingOfItsOwnToRekey, kind: 'BETA', icon: 'file', list: { rows: stubRows('b').factory, tabLabel: LABEL_B } })
    const w = mountLevel([TRIPS, node('a1', 'ALPHA', 'Trips'), node('p1', 'ALPHA_PART', 'Trips'), node('b1', 'BETA', 'Trips')])
    expect(tabButtons(w).map((x) => x.text())).toEqual([t('node.tabAll'), t(LABEL_A), t(LABEL_B)])
    await tabNamed(w, t(LABEL_A)).trigger('mousedown')
    expect(titlesOf(w)).toEqual(['a1', 'p1'])
  })

  it('a tab is offered where its kinds have a node here or the viewer may add one: a reader gets no empty tab', () => {
    const only = mountLevel([TRIPS, node('a1', 'ALPHA', 'Trips')], true)
    expect(only.find('[role="tablist"]').exists(), 'one tab with nodes is no choice').toBe(false)
    const both = mountLevel([TRIPS, node('a1', 'ALPHA', 'Trips'), node('b1', 'BETA', 'Trips')], true)
    expect(tabButtons(both)).toHaveLength(3)
  })

  it('the view switch offers what the tab shows: a tab of a kind without views of its own leaves the grid and the list', async () => {
    holder.reg = createRegistry()
    const map = { mode: 'map', icon: 'map', label: 'node.viewGrid' } as const
    testKind(holder.reg, { rekeyContent: nothingOfItsOwnToRekey, kind: 'ALPHA', icon: 'images', list: { rows: stubRows('a').factory, tabLabel: LABEL_A, views: [map] } })
    testKind(holder.reg, { rekeyContent: nothingOfItsOwnToRekey, kind: 'BETA', icon: 'file', list: { rows: stubRows('b').factory, tabLabel: LABEL_B } })
    const w = mountLevel(TREE)
    expect(w.findAll('.view-mode-button')).toHaveLength(3)
    await tabNamed(w, t(LABEL_B)).trigger('mousedown')
    expect(w.findAll('.view-mode-button')).toHaveLength(2)
  })
})

// The "New" menu's upload action no longer depends on a mounted section: the row factory registered its handler for the
// level's life, so showing another application's tab greys nothing out (inbox #407). The menu leaves the entry out under that
// tab (owner 2026-11-09), and back in its own tab it is enabled again - the handler outlived the switch.
describe('LevelView application tabs: the level action does not depend on the tab', () => {
  it('"Upload"-like entry is left out under another application\'s tab and enabled again in its own', async () => {
    holder.reg = createRegistry()
    const a = stubRows('a', { action: { id: 'a.up', handler: { enabled: () => true, run: () => {} } } })
    testKind(holder.reg, { rekeyContent: nothingOfItsOwnToRekey,
      kind: 'ALPHA', icon: 'images', list: { rows: a.factory, tabLabel: LABEL_A },
      levelActions: [{ id: 'a.up', label: 'node.rename', icon: 'upload', order: 30, allowed: () => true }],
    })
    testKind(holder.reg, { rekeyContent: nothingOfItsOwnToRekey, kind: 'BETA', icon: 'file', list: { rows: stubRows('b').factory, tabLabel: LABEL_B } })
    const w = mountLevel([TRIPS, node('a1', 'ALPHA', 'Trips'), node('b1', 'BETA', 'Trips')])
    await tabNamed(w, t(LABEL_B)).trigger('mousedown')
    expect(titlesOf(w)).toEqual(['b1'])
    expect(w.find('.veyq-new-menu-button').exists(), 'no entry of the shown tab: no menu').toBe(false)
    await tabNamed(w, t(LABEL_A)).trigger('mousedown')
    const items = await openNewMenu(w)
    expect(isDisabled(items.find((i) => i.textContent?.trim() === t('node.rename'))!), 'enabled after the other tab was shown').toBe(false)
  })
})

// A tab that is offered but holds nothing says so in its application's own words (`list.emptyText`), else a neutral line.
describe('LevelView application tabs: an empty tab', () => {
  it('shows the application\'s own text, else the neutral one, once, beside the folders', async () => {
    holder.reg = createRegistry()
    const allow = () => true
    const Dialog = { name: 'D', render: () => null }
    testKind(holder.reg, { rekeyContent: nothingOfItsOwnToRekey,
      kind: 'ALPHA', icon: 'images', createDialog: Dialog, createEntry: { label: 'node.anItem', icon: 'images', order: 10, allowed: allow },
      list: { rows: stubRows('a').factory, tabLabel: LABEL_A, emptyText: () => 'memory.memoryEmpty' },
    })
    testKind(holder.reg, { rekeyContent: nothingOfItsOwnToRekey,
      kind: 'BETA', icon: 'file', createDialog: Dialog, createEntry: { label: 'node.anItem', icon: 'file', order: 20, allowed: allow },
      list: { rows: stubRows('b').factory, tabLabel: LABEL_B },
    })
    const w = mountLevel([TRIPS, SUB])
    expect(tabButtons(w)).toHaveLength(3) // the viewer may add to both, so both tabs are offered although empty
    await tabNamed(w, t(LABEL_A)).trigger('mousedown')
    expect(w.find('.veyq-empty').text()).toBe(t('memory.memoryEmpty'))
    expect(titlesOf(w)).toEqual(['Sub'])
    await tabNamed(w, t(LABEL_B)).trigger('mousedown')
    expect(w.find('.veyq-empty').text()).toBe(t('node.tabEmpty'))
    await tabNamed(w, t('node.tabAll')).trigger('mousedown')
    expect(w.find('.veyq-empty').exists(), 'under All the folder is something').toBe(false)
  })
})

// The "New" menu follows the shown tab like the list (owner 2026-11-09): under an application's tab only its entries, core's
// folder entry in every tab, a kind with no tab under `All` only. A dropped upload under a tab that does not show its kind
// switches to the tab that does - on the drop alone, never on a drag over the level, never for an import or a refused drop.
describe('LevelView application tabs: the "New" menu and a drop follow the tab', () => {
  const Dialog = { name: 'D', render: () => null }
  const ready = { value: true }
  const dropped: string[][] = []
  const droppedDirs: string[] = []

  beforeEach(() => {
    ready.value = true
    dropped.length = 0
    droppedDirs.length = 0
    holder.reg = createRegistry()
    const allow = () => true
    const upload = stubRows('b', {
      action: {
        id: 'b.upload',
        handler: {
          enabled: () => ready.value, run: () => {}, drop: (files) => dropped.push(files.map((f) => f.name)),
          dropDirectory: (dir) => droppedDirs.push(dir.name),
        },
      },
    })
    testKind(holder.reg, { rekeyContent: nothingOfItsOwnToRekey,
      kind: 'ALPHA', icon: 'images', createDialog: Dialog, createEntry: { label: 'node.anItem', icon: 'images', order: 10, allowed: allow },
      list: { rows: stubRows('a').factory, tabLabel: LABEL_A },
    })
    testKind(holder.reg, { rekeyContent: nothingOfItsOwnToRekey,
      kind: 'BETA', icon: 'file', list: { rows: upload.factory, tabLabel: LABEL_B },
      levelActions: [{ id: 'b.upload', label: 'node.rename', icon: 'upload', order: 30, allowed: allow, acceptsDrop: true }],
    })
    // a kind with no tab (core's bookmark is one): its entry is under `All` only
    testKind(holder.reg, { rekeyContent: nothingOfItsOwnToRekey,
      kind: 'MARK', icon: 'link', createDialog: Dialog, createEntry: { label: 'node.bookmark.newEntry', icon: 'link', order: 40, allowed: allow },
      list: { rows: stubRows('m').factory },
    })
  })

  /** The member's page with every door (the create entries need `createChild`); `unlocked` gives the epochs an import needs. */
  function mountMember(unlocked = false) {
    const tree = [TRIPS, SUB]
    return mount(LevelView, {
      props: {
        folder: TRIPS, level: childrenOf(tree, TRIPS.id), tree: memberTreeOf(() => tree),
        access: memberAccessOf({ epochs: unlocked ? epochsOf(new Uint8Array(32)) : null }),
      },
      global: { plugins: [realI18n()], provide: recordingHost().provide },
      attachTo: document.body,
    })
  }
  const pick = async (w: Level, label: string) => {
    await tabNamed(w, label).trigger('mousedown')
    await flushPromises()
  }
  const dropFiles = async (w: Level, ...files: File[]) => {
    await w.find('.veyq-level-view').trigger('drop', { dataTransfer: { files } })
    await flushPromises()
  }
  const closeMenu = () => document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))

  it('offers core\'s folder entry in every tab and only the shown application\'s own entries beside it', async () => {
    const w = mountMember()
    expect(tabButtons(w).map((x) => x.text()), 'both tabs offered: the viewer may add to both').toEqual([t('node.tabAll'), t(LABEL_A), t(LABEL_B)])
    expect(await newMenuLabels(w)).toEqual([t('node.folderCreate'), t('node.anItem'), t('node.rename'), t('node.bookmark.newEntry')])
    closeMenu()
    await pick(w, t(LABEL_A))
    expect(selected(w)).toEqual([t(LABEL_A)])
    expect(await newMenuLabels(w)).toEqual([t('node.folderCreate'), t('node.anItem')])
    closeMenu()
    await pick(w, t(LABEL_B))
    expect(await newMenuLabels(w)).toEqual([t('node.folderCreate'), t('node.rename')])
    w.unmount()
  })

  it('a file drop under another application\'s tab is uploaded and shows the tab of the kind that took it, stored', async () => {
    const w = mountMember()
    await pick(w, t(LABEL_A))
    await dropFiles(w, new File(['x'], 'a.txt'))
    expect(dropped).toEqual([['a.txt']])
    expect(selected(w)).toEqual([t(LABEL_B)])
    expect(useLevelTab().levelTab.value).toBe('BETA')
    w.unmount()
  })

  it('a dropped directory (the folder upload) does the same', async () => {
    const w = mountMember()
    await pick(w, t(LABEL_A))
    const dir = { isFile: false, isDirectory: true, name: 'Holiday', createReader: () => ({ readEntries: (ok: (e: unknown[]) => void) => ok([]) }) }
    await w.find('.veyq-level-view').trigger('drop', { dataTransfer: { files: [new File([], 'Holiday')], items: [{ kind: 'file', webkitGetAsEntry: () => dir }] } })
    await flushPromises()
    expect(droppedDirs).toEqual(['Holiday'])
    expect(selected(w)).toEqual([t(LABEL_B)])
    w.unmount()
  })

  it('a drag over the level switches nothing - only the drop does', async () => {
    const w = mountMember()
    await pick(w, t(LABEL_A))
    await w.find('.veyq-level-view').trigger('dragover', { dataTransfer: { types: ['Files'] } })
    await flushPromises()
    expect(selected(w)).toEqual([t(LABEL_A)])
    w.unmount()
  })

  it('switches nothing under All or the tab that already shows the kind', async () => {
    const w = mountMember()
    await dropFiles(w, new File(['x'], 'a.txt'))
    expect(selected(w)).toEqual([t('node.tabAll')])
    await pick(w, t(LABEL_B))
    await dropFiles(w, new File(['x'], 'b.txt'))
    expect(selected(w)).toEqual([t(LABEL_B)])
    expect(dropped).toEqual([['a.txt'], ['b.txt']])
    w.unmount()
  })

  it('switches nothing for a refused drop (the handler not ready)', async () => {
    ready.value = false
    const w = mountMember()
    await pick(w, t(LABEL_A))
    await dropFiles(w, new File(['x'], 'a.txt'))
    expect(dropped).toEqual([])
    expect(selected(w)).toEqual([t(LABEL_A)])
    w.unmount()
  })

  it('switches nothing for a dropped download offered as an import - but its "Upload as a file" does', async () => {
    const parts: Uint8Array[] = []
    const zip = new ZipWriter({ write: async (b: Uint8Array) => void parts.push(b.slice()), close: async () => {}, abort: async () => {} })
    const manifest = { format: 'veyq-subtree', version: 2, exportedAt: 'x', appVersion: 'x', applications: [], root: { id: 'n1', kind: 'FOLDER', name: 'Trip', path: 'Trip' } }
    await zip.addBytes('.veyq/manifest.json', new TextEncoder().encode(JSON.stringify(manifest)))
    await zip.finish()
    const rec = recordingHost()
    const tree = [TRIPS, SUB]
    const w = mount(LevelView, {
      props: { folder: TRIPS, level: childrenOf(tree, TRIPS.id), tree: memberTreeOf(() => tree), access: memberAccessOf({ epochs: epochsOf(new Uint8Array(32)) }) },
      global: { plugins: [realI18n()], provide: rec.provide },
    })
    await pick(w, t(LABEL_A))
    await dropFiles(w, new File(parts as BlobPart[], 'trip.zip'))
    expect(rec.calls.importArchive, 'the import was offered').toHaveLength(1)
    expect(dropped).toEqual([])
    expect(selected(w)).toEqual([t(LABEL_A)])
    // the dialog's "Upload as a file": the dropped download is uploaded after all, and then shown in its tab
    const [[offer]] = rec.calls.importArchive as [{ uploadAsFile?: () => void }][]
    offer.uploadAsFile!()
    await flushPromises()
    expect(dropped).toEqual([['trip.zip']])
    expect(selected(w)).toEqual([t(LABEL_B)])
  })

  it('a drop taken by a kind with no tab shows All', async () => {
    holder.reg = createRegistry()
    const taker = stubRows('m', { action: { id: 'm.upload', handler: { enabled: () => true, run: () => {}, drop: (f) => dropped.push(f.map((x) => x.name)) } } })
    testKind(holder.reg, { rekeyContent: nothingOfItsOwnToRekey, kind: 'ALPHA', icon: 'images', list: { rows: stubRows('a').factory, tabLabel: LABEL_A } })
    testKind(holder.reg, { rekeyContent: nothingOfItsOwnToRekey, kind: 'BETA', icon: 'file', list: { rows: stubRows('b').factory, tabLabel: LABEL_B } })
    testKind(holder.reg, { rekeyContent: nothingOfItsOwnToRekey,
      kind: 'MARK', icon: 'link', list: { rows: taker.factory },
      levelActions: [{ id: 'm.upload', label: 'node.rename', icon: 'upload', order: 30, allowed: () => true, acceptsDrop: true }],
    })
    const tree = [TRIPS, node('a1', 'ALPHA', 'Trips'), node('b1', 'BETA', 'Trips')]
    const w = mount(LevelView, {
      props: { folder: TRIPS, level: childrenOf(tree, TRIPS.id), tree: memberTreeOf(() => tree), access: memberAccessOf({}) },
      global: { plugins: [realI18n()], provide: recordingHost().provide },
    })
    await pick(w, t(LABEL_A))
    await dropFiles(w, new File(['x'], 'a.txt'))
    expect(dropped).toEqual([['a.txt']])
    expect(selected(w)).toEqual([t('node.tabAll')])
  })
})
