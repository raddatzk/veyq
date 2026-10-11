// @vitest-environment happy-dom
// The application tabs of core's level view (inbox #348) with REAL DOM clicks on the rendered tab buttons, against a
// stubbed registry (as `LevelView.test.ts`): kinds are made-up tokens, tab labels are two real catalog keys.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, h, type PropType } from 'vue'
import PrimeVue from 'primevue/config'

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
import { setViewMode, type ViewMode } from '../../shared/state/useViewMode'
import { setLevelTab, useLevelTab, levelTabStorageKey } from '../../shared/state/useLevelTab'
import { setAccountUserId } from '../../app/account'
import { realI18n, t } from '../../test/i18n'
import { treeNode, memberAccessOf, memberTreeOf, guestAccessOf } from '../../test/models'
import { ShareRole } from '../../api/generated/sharing'

function sectionStub(name: string) {
  return defineComponent({
    name: `Section${name}`,
    props: {
      folder: { type: Object as PropType<TreeNode | null>, default: null },
      nodes: { type: Array as PropType<readonly TreeNode[]>, required: true },
      epochs: { type: Object, default: null },
      access: { type: Object, required: true },
      tree: { type: Object, required: true },
      viewMode: { type: String as PropType<ViewMode>, required: true },
    },
    setup: (p) => () => h('section', { class: 'stub-section', 'data-name': name }, [h('span', { class: 'stub-nodes' }, p.nodes.map((n) => n.name).join(','))]),
  })
}
const A = sectionStub('a')
const B = sectionStub('b')
const C = sectionStub('c')

function node(id: string, kind: NodeKind, parentId: string | null = null): TreeNode {
  return treeNode({ id, kind, name: id, parentId, orderIndex: 0, epoch: 0, wrappedContentKey: 'eA==', wrappedMeta: null, metaVersion: 0, placementVersion: 0 })
}
const TRIPS = node('Trips', FOLDER_KIND)
const SUB = node('Sub', FOLDER_KIND, 'Trips')

const PLUGINS = { plugins: [realI18n(), PrimeVue], directives: { tooltip: {} } }
function mountLevel(tree: TreeNode[], guest = false) {
  const level = childrenOf(tree, TRIPS.id)
  return mount(LevelView, {
    props: { folder: TRIPS, level, access: guest ? guestAccessOf(ShareRole.Read) : memberAccessOf({}), tree: memberTreeOf(() => tree) },
    global: PLUGINS,
  })
}

const shown = (w: ReturnType<typeof mountLevel>) => w.findAll('.stub-section').map((s) => s.attributes('data-name'))
const folderCards = (w: ReturnType<typeof mountLevel>) => w.findAll('button.veyq-folder-card').map((b) => b.text())
const tabButtons = (w: ReturnType<typeof mountLevel>) => w.findAll('[role="tab"]')
const tabNamed = (w: ReturnType<typeof mountLevel>, label: string) => tabButtons(w).find((b) => b.text() === label)!
const selected = (w: ReturnType<typeof mountLevel>) => tabButtons(w).filter((b) => b.attributes('aria-selected') === 'true').map((b) => b.text())

const LABEL_A = 'memory.memories'
const LABEL_B = 'files.title'

beforeEach(() => {
  holder.reg = createRegistry()
  localStorage.clear()
  setAccountUserId('user-1')
  setLevelTab('all')
  setViewMode('grid')
  holder.reg.registerKind({ kind: 'ALPHA', icon: 'pi pi-a', shareable: false, section: { order: 10, component: A, tabLabel: LABEL_A } })
  holder.reg.registerKind({ kind: 'BETA', icon: 'pi pi-b', shareable: false, section: { order: 20, component: B, tabLabel: LABEL_B } })
})

const TREE = [TRIPS, SUB, node('a1', 'ALPHA', 'Trips'), node('b1', 'BETA', 'Trips'), node('x1', 'OTHER', 'Trips')]

describe('LevelView application tabs', () => {
  it('offers All and one tab per section, with proper tablist semantics, All selected by default', () => {
    const w = mountLevel(TREE)
    expect(w.find('[role="tablist"]').attributes('aria-label')).toBe(t('node.tabs'))
    expect(tabButtons(w).map((b) => b.text())).toEqual([t('node.tabAll'), t(LABEL_A), t(LABEL_B)])
    expect(tabButtons(w).every((b) => b.element.tagName === 'BUTTON' && !b.attributes('href'))).toBe(true)
    expect(selected(w)).toEqual([t('node.tabAll')])
    // All = today's view: both sections, the fallback for the unknown kind, the folders
    expect(shown(w)).toEqual(['a', 'b'])
    expect(w.find('.veyq-level-fallback').exists()).toBe(true)
    expect(folderCards(w)).toEqual(['Sub'])
  })

  it('a click on an application tab shows only that section, keeps the sub-folders and drops the fallback', async () => {
    const w = mountLevel(TREE)
    await tabNamed(w, t(LABEL_B)).trigger('click')
    expect(shown(w)).toEqual(['b'])
    expect(w.find('.stub-nodes').text()).toBe('b1')
    expect(w.find('.veyq-level-fallback').exists()).toBe(false)
    expect(folderCards(w)).toEqual(['Sub'])
    expect(selected(w)).toEqual([t(LABEL_B)])
    await tabNamed(w, t(LABEL_A)).trigger('click')
    expect(shown(w)).toEqual(['a'])
    await tabNamed(w, t('node.tabAll')).trigger('click')
    expect(shown(w)).toEqual(['a', 'b'])
    expect(w.find('.veyq-level-fallback').exists()).toBe(true)
  })

  it('persists the selection: a remount shows it, and the preference is in storage', async () => {
    const first = mountLevel(TREE)
    await tabNamed(first, t(LABEL_B)).trigger('click')
    expect(localStorage.getItem(levelTabStorageKey()!)).toBe('BETA')
    first.unmount()
    const second = mountLevel(TREE)
    expect(shown(second)).toEqual(['b'])
    expect(selected(second)).toEqual([t(LABEL_B)])
  })

  it('shows All for a stored tab this level lacks, WITHOUT overwriting the stored preference', async () => {
    setLevelTab('BETA')
    // an identity folder leaves empty sections out: here BETA has no node, so the bar has ALPHA and GAMMA only
    holder.reg!.registerKind({ kind: 'GAMMA', icon: 'pi pi-c', shareable: false, section: { order: 30, component: C, tabLabel: 'files.dropBox.section' } })
    const level = childrenOf([TRIPS, node('a1', 'ALPHA', 'Trips'), node('g1', 'GAMMA', 'Trips')], TRIPS.id)
    const tree = memberTreeOf(() => [TRIPS])
    const w = mount(LevelView, {
      props: { folder: TRIPS, level, access: memberAccessOf({}), tree: { ...tree, inIdentityFolder: () => true } },
      global: PLUGINS,
    })
    expect(tabButtons(w).map((b) => b.text())).toEqual([t('node.tabAll'), t(LABEL_A), t('files.dropBox.section')])
    expect(selected(w)).toEqual([t('node.tabAll')])
    expect(shown(w)).toEqual(['a', 'c'])
    expect(useLevelTab().levelTab.value).toBe('BETA')
    expect(localStorage.getItem(levelTabStorageKey()!)).toBe('BETA')
    // …and the level that has BETA shows it again
    w.unmount()
    expect(shown(mountLevel(TREE))).toEqual(['b'])
  })

  it('shows no tab bar with fewer than two sections that have a tab (and then no filtering, whatever is stored)', () => {
    setLevelTab('BETA')
    holder.reg = createRegistry()
    holder.reg.registerKind({ kind: 'ALPHA', icon: 'pi pi-a', shareable: false, section: { order: 10, component: A, tabLabel: LABEL_A } })
    const one = mountLevel(TREE)
    expect(one.find('[role="tablist"]').exists()).toBe(false)
    expect(shown(one)).toEqual(['a'])
    holder.reg = createRegistry()
    holder.reg.registerKind({ kind: 'ALPHA', icon: 'pi pi-a', shareable: false, section: { order: 10, component: A, tabLabel: LABEL_A } })
    holder.reg.registerKind({ kind: 'BETA', icon: 'pi pi-b', shareable: false, section: { order: 20, component: B } }) // no label: no tab
    const unlabelled = mountLevel(TREE)
    expect(unlabelled.find('[role="tablist"]').exists()).toBe(false)
    expect(shown(unlabelled)).toEqual(['a', 'b'])
  })

  it('moves between tabs with the arrow keys (roving tabindex) and selects with Enter', async () => {
    const w = mountLevel(TREE)
    const all = tabNamed(w, t('node.tabAll'))
    expect(all.attributes('tabindex')).toBe('0')
    expect(tabNamed(w, t(LABEL_A)).attributes('tabindex')).toBe('-1')
    await all.trigger('keydown', { code: 'ArrowRight', key: 'ArrowRight' })
    await tabNamed(w, t(LABEL_A)).trigger('keydown', { code: 'Enter', key: 'Enter' })
    expect(shown(w)).toEqual(['a'])
  })

  it('a guest gets the same bar and the same filter (share = member view)', async () => {
    const w = mountLevel(TREE, true)
    expect(tabButtons(w).map((b) => b.text())).toEqual([t('node.tabAll'), t(LABEL_A), t(LABEL_B)])
    await tabNamed(w, t(LABEL_A)).trigger('click')
    expect(shown(w)).toEqual(['a'])
    expect(folderCards(w)).toEqual(['Sub'])
  })

  it('the view switch offers what the tab shows: a tab of a section without views leaves the folders\' grid and list', async () => {
    holder.reg = createRegistry()
    const map = { mode: 'map' as const, icon: 'pi pi-map', label: 'node.viewGrid' }
    holder.reg.registerKind({ kind: 'ALPHA', icon: 'pi pi-a', shareable: false, section: { order: 10, component: A, tabLabel: LABEL_A, views: [map] } })
    holder.reg.registerKind({ kind: 'BETA', icon: 'pi pi-b', shareable: false, section: { order: 20, component: B, tabLabel: LABEL_B } })
    const w = mountLevel(TREE)
    expect(w.findAll('.view-mode-button')).toHaveLength(3)
    await tabNamed(w, t(LABEL_B)).trigger('click')
    expect(w.findAll('.view-mode-button')).toHaveLength(2)
  })
})
