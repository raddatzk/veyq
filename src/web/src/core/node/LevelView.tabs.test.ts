// @vitest-environment happy-dom
// The application tabs of core's level view (inbox #348; a pure FILTER over the one list, inbox #407) with REAL DOM clicks on
// the rendered tab buttons, against a stubbed registry (as `LevelView.test.ts`): kinds are made-up tokens, tab labels are real
// catalog keys. The tabs filter by the KIND's application; the folders are shown in every tab.
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
import { setViewMode } from '../../shared/state/useViewMode'
import { setLevelTab, useLevelTab, levelTabStorageKey } from '../../shared/state/useLevelTab'
import { setAccountUserId } from '../../app/account'
import { realI18n, t } from '../../test/i18n'
import { treeNode, memberAccessOf, memberTreeOf, guestAccessOf } from '../../test/models'
import { ShareRole } from '../../api/generated/sharing'
import { stubRows } from '../../test/stubRows'
import { titlesOf } from '../../test/levelRows'
import { isDisabled, openNewMenu } from '../../test/newMenu'

function node(id: string, kind: NodeKind, parentId: string | null = null): TreeNode {
  return treeNode({ id, kind, name: id, parentId, orderIndex: 0, epoch: 0, wrappedContentKey: 'eA==', wrappedMeta: null, metaRevision: 0, placementRevision: 0 })
}
const TRIPS = node('Trips', FOLDER_KIND)
const SUB = node('Sub', FOLDER_KIND, 'Trips')

const PLUGINS = { plugins: [realI18n()] }
function mountLevel(tree: TreeNode[], guest = false) {
  const level = childrenOf(tree, TRIPS.id)
  return mount(LevelView, {
    props: { folder: TRIPS, level, access: guest ? guestAccessOf(ShareRole.Read) : memberAccessOf({}), tree: memberTreeOf(() => tree) },
    global: PLUGINS,
  })
}
type Level = ReturnType<typeof mountLevel>

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
  holder.reg.registerKind({ kind: 'ALPHA', icon: 'images', shareable: false, list: { rows: a.factory, tabLabel: LABEL_A } })
  holder.reg.registerKind({ kind: 'BETA', icon: 'file', shareable: false, list: { rows: b.factory, tabLabel: LABEL_B } })
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
    await tabNamed(w, t(LABEL_B)).trigger('click')
    expect(titlesOf(w)).toEqual(['Sub', 'b1'])
    expect(w.find('.is-unsupported').exists()).toBe(false)
    expect(selected(w)).toEqual([t(LABEL_B)])
    await tabNamed(w, t(LABEL_A)).trigger('click')
    expect(titlesOf(w)).toEqual(['Sub', 'a1'])
    await tabNamed(w, t('node.tabAll')).trigger('click')
    expect(titlesOf(w)).toEqual(['Sub', 'a1', 'b1', 'x1'])
  })

  it('keeps the folders in EVERY tab, however many there are, and the filter changes nothing about them', async () => {
    const tree = [...TREE, node('Zed', FOLDER_KIND, 'Trips'), node('Mid', FOLDER_KIND, 'Trips')]
    const w = mountLevel(tree)
    for (const label of [t('node.tabAll'), t(LABEL_A), t(LABEL_B)]) {
      await tabNamed(w, label).trigger('click')
      expect(titlesOf(w).slice(0, 3), label).toEqual(['Mid', 'Sub', 'Zed'])
    }
  })

  it('persists the selection: a remount shows it, and the preference is in storage', async () => {
    const first = mountLevel(TREE)
    await tabNamed(first, t(LABEL_B)).trigger('click')
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
    holder.reg!.registerKind({ kind: 'GAMMA', icon: 'inbox', shareable: false, list: { rows: c.factory, tabLabel: 'depot.dropBox.section' } })
    const level = childrenOf([TRIPS, node('a1', 'ALPHA', 'Trips'), node('g1', 'GAMMA', 'Trips')], TRIPS.id)
    const tree = memberTreeOf(() => [TRIPS])
    const w = mount(LevelView, {
      props: { folder: TRIPS, level, access: memberAccessOf({}), tree: { ...tree, inIdentityFolder: () => true } },
      global: PLUGINS,
    })
    expect(tabButtons(w).map((x) => x.text())).toEqual([t('node.tabAll'), t(LABEL_A), t('depot.dropBox.section')])
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
    holder.reg.registerKind({ kind: 'ALPHA', icon: 'images', shareable: false, list: { rows: stubRows('a').factory, tabLabel: LABEL_A } })
    const one = mountLevel(TREE)
    expect(one.find('[role="tablist"]').exists()).toBe(false)
    expect(titlesOf(one)).toEqual(['Sub', 'a1', 'b1', 'x1'])
    holder.reg = createRegistry()
    holder.reg.registerKind({ kind: 'ALPHA', icon: 'images', shareable: false, list: { rows: stubRows('a').factory, tabLabel: LABEL_A } })
    holder.reg.registerKind({ kind: 'BETA', icon: 'file', shareable: false, list: { rows: stubRows('b').factory } }) // no label: no tab
    const unlabelled = mountLevel(TREE)
    expect(unlabelled.find('[role="tablist"]').exists()).toBe(false)
    expect(titlesOf(unlabelled)).toEqual(['Sub', 'a1', 'b1', 'x1'])
  })

  it('moves between tabs with the arrow keys (roving tabindex) and selects with Enter', async () => {
    const w = mountLevel(TREE)
    const all = tabNamed(w, t('node.tabAll'))
    expect(all.attributes('tabindex')).toBe('0')
    expect(tabNamed(w, t(LABEL_A)).attributes('tabindex')).toBe('-1')
    await all.trigger('keydown', { code: 'ArrowRight', key: 'ArrowRight' })
    await tabNamed(w, t(LABEL_A)).trigger('keydown', { code: 'Enter', key: 'Enter' })
    expect(titlesOf(w)).toEqual(['Sub', 'a1'])
  })

  it('a guest gets the same bar and the same filter (share = member view)', async () => {
    const w = mountLevel(TREE, true)
    expect(tabButtons(w).map((x) => x.text())).toEqual([t('node.tabAll'), t(LABEL_A), t(LABEL_B)])
    await tabNamed(w, t(LABEL_A)).trigger('click')
    expect(titlesOf(w)).toEqual(['Sub', 'a1'])
  })

  it('kinds sharing a tab label share ONE tab: its filter shows the nodes of both', async () => {
    holder.reg = createRegistry()
    const shared = stubRows('s')
    holder.reg.registerKind({ kind: 'ALPHA', icon: 'images', shareable: false, list: { rows: shared.factory, tabLabel: LABEL_A } })
    holder.reg.registerKind({ kind: 'ALPHA_PART', icon: 'images', shareable: false, list: { rows: shared.factory, tabLabel: LABEL_A } })
    holder.reg.registerKind({ kind: 'BETA', icon: 'file', shareable: false, list: { rows: stubRows('b').factory, tabLabel: LABEL_B } })
    const w = mountLevel([TRIPS, node('a1', 'ALPHA', 'Trips'), node('p1', 'ALPHA_PART', 'Trips'), node('b1', 'BETA', 'Trips')])
    expect(tabButtons(w).map((x) => x.text())).toEqual([t('node.tabAll'), t(LABEL_A), t(LABEL_B)])
    await tabNamed(w, t(LABEL_A)).trigger('click')
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
    holder.reg.registerKind({ kind: 'ALPHA', icon: 'images', shareable: false, list: { rows: stubRows('a').factory, tabLabel: LABEL_A, views: [map] } })
    holder.reg.registerKind({ kind: 'BETA', icon: 'file', shareable: false, list: { rows: stubRows('b').factory, tabLabel: LABEL_B } })
    const w = mountLevel(TREE)
    expect(w.findAll('.view-mode-button')).toHaveLength(3)
    await tabNamed(w, t(LABEL_B)).trigger('click')
    expect(w.findAll('.view-mode-button')).toHaveLength(2)
  })
})

// The "New" menu's upload action no longer depends on a mounted section: the row factory registered its handler for the
// level's life, so another application's tab being shown greys nothing out (inbox #407).
describe('LevelView application tabs: the level action does not depend on the tab', () => {
  it('"Upload"-like entry of a kind in another tab stays enabled', async () => {
    holder.reg = createRegistry()
    const a = stubRows('a', { action: { id: 'a.up', handler: { enabled: () => true, run: () => {} } } })
    holder.reg.registerKind({
      kind: 'ALPHA', icon: 'images', shareable: false, list: { rows: a.factory, tabLabel: LABEL_A },
      levelActions: [{ id: 'a.up', label: 'node.rename', icon: 'upload', order: 30, allowed: () => true }],
    })
    holder.reg.registerKind({ kind: 'BETA', icon: 'file', shareable: false, list: { rows: stubRows('b').factory, tabLabel: LABEL_B } })
    const w = mountLevel([TRIPS, node('a1', 'ALPHA', 'Trips'), node('b1', 'BETA', 'Trips')])
    await tabNamed(w, t(LABEL_B)).trigger('click')
    expect(titlesOf(w)).toEqual(['b1'])
    const items = await openNewMenu(w)
    expect(isDisabled(items.find((i) => i.textContent?.trim() === t('node.rename'))!), 'enabled while the other application\'s tab is shown').toBe(false)
  })
})

// A tab that is offered but holds nothing says so in its application's own words (`list.emptyText`), else a neutral line.
describe('LevelView application tabs: an empty tab', () => {
  it('shows the application\'s own text, else the neutral one, once, beside the folders', async () => {
    holder.reg = createRegistry()
    const allow = () => true
    const Dialog = { name: 'D', render: () => null }
    holder.reg.registerKind({
      kind: 'ALPHA', icon: 'images', shareable: false, createDialog: Dialog, createEntry: { label: 'node.anItem', icon: 'images', order: 10, allowed: allow },
      list: { rows: stubRows('a').factory, tabLabel: LABEL_A, emptyText: () => 'memory.memoryEmpty' },
    })
    holder.reg.registerKind({
      kind: 'BETA', icon: 'file', shareable: false, createDialog: Dialog, createEntry: { label: 'node.anItem', icon: 'file', order: 20, allowed: allow },
      list: { rows: stubRows('b').factory, tabLabel: LABEL_B },
    })
    const w = mountLevel([TRIPS, SUB])
    expect(tabButtons(w)).toHaveLength(3) // the viewer may add to both, so both tabs are offered although empty
    await tabNamed(w, t(LABEL_A)).trigger('click')
    expect(w.find('.veyq-empty').text()).toBe(t('memory.memoryEmpty'))
    expect(titlesOf(w)).toEqual(['Sub'])
    await tabNamed(w, t(LABEL_B)).trigger('click')
    expect(w.find('.veyq-empty').text()).toBe(t('node.tabEmpty'))
    await tabNamed(w, t('node.tabAll')).trigger('click')
    expect(w.find('.veyq-empty').exists(), 'under All the folder is something').toBe(false)
  })
})
