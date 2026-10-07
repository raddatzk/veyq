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
import { setViewMode } from '../../shared/state/useViewMode'
import { realI18n, t } from '../../test/i18n'

/** A section stub: renders its name, what it was handed, and offers buttons for the three events. */
function sectionStub(name: string) {
  return defineComponent({
    name: `Section${name}`,
    props: {
      folder: { type: Object as PropType<TreeNode | null>, default: null },
      nodes: { type: Array as PropType<readonly TreeNode[]>, required: true },
      epochs: { type: Object, default: null },
      readonly: { type: Boolean, required: true },
    },
    emits: ['openNode', 'create', 'openTrash'],
    setup: (p, { emit }) => () =>
      h('section', { class: 'stub-section', 'data-name': name, 'data-readonly': String(p.readonly), 'data-folder': p.folder?.name ?? '-' }, [
        h('span', { class: 'stub-nodes' }, p.nodes.map((n) => n.name).join(',')),
        h('button', { class: 'stub-open', onClick: () => emit('openNode', p.nodes[0]) }),
        h('button', { class: 'stub-create', onClick: () => emit('create', `create-${name}`) }),
        h('button', { class: 'stub-trash', onClick: () => emit('openTrash') }),
      ]),
  })
}
const A = sectionStub('a')
const B = sectionStub('b')

function node(id: string, kind: NodeKind, parentId: string | null = null, orderIndex = 0): TreeNode {
  return { id, kind, name: id, parentId, orderIndex, epoch: 0, wrappedContentKey: 'eA==', wrappedMeta: null, metaVersion: 0, placementVersion: 0 }
}

const TRIPS = node('Trips', FOLDER_KIND)
function mountLevel(tree: TreeNode[], over: { folder?: TreeNode | null; readonly?: boolean; shareable?: boolean } = {}) {
  const folder = over.folder ?? null
  return mount(LevelView, {
    props: { folder, level: childrenOf(tree, folder?.id ?? null), epochs: null, readonly: over.readonly ?? false, shareable: over.shareable ?? false },
    global: { plugins: [realI18n()], directives: { tooltip: {} } },
  })
}

/** The level's top-level blocks in DOM order, each named by what it is. */
function blocks(w: ReturnType<typeof mountLevel>): string[] {
  return [...w.element.children].map((el) => {
    if (el.classList.contains('stub-section')) return `section:${el.getAttribute('data-name')}`
    if (el.classList.contains('veyq-level-fallback')) return `fallback:${el.querySelector('h2')?.textContent}`
    if (el.querySelector('.memory-folder-overview-card')) return 'overview'
    if (el.querySelector('h2')?.textContent === t('memory.folders')) return 'folders'
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

  it('hands each section the folder, the read-only flag, and forwards its openNode, create and openTrash', async () => {
    reg().registerKind({ kind: 'ALPHA', icon: 'pi pi-a', shareable: false, section: { order: 10, component: A } })
    const a1 = node('a1', 'ALPHA', 'Trips')
    const w = mountLevel([TRIPS, a1], { folder: TRIPS, readonly: true })
    expect(w.find('.stub-section').attributes('data-folder')).toBe('Trips')
    expect(w.find('.stub-section').attributes('data-readonly')).toBe('true')
    await w.find('.stub-open').trigger('click')
    await w.find('.stub-create').trigger('click')
    await w.find('.stub-trash').trigger('click')
    expect(w.emitted('openNode')).toEqual([[a1]])
    expect(w.emitted('create')).toEqual([['create-a']])
    expect(w.emitted('openTrash')).toHaveLength(1)
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
  it('lists the sub-folders, opens one, and offers the new-folder card that asks for create-folder', async () => {
    const rome = node('Rome', FOLDER_KIND, 'Trips')
    const w = mountLevel([TRIPS, rome], { folder: TRIPS })
    const card = w.findAll('button.memory-folder-card').find((b) => b.text() === 'Rome')!
    await card.trigger('click')
    expect(w.emitted('openNode')).toEqual([[rome]])
    await w.find('.memory-create-card').trigger('click')
    expect(w.emitted('create')).toEqual([[FOLDER_KIND]])
  })

  it('shows its empty state when the level has no folder', () => {
    expect(mountLevel([]).text()).toContain(t('memory.folderEmpty'))
  })

  it('steps aside in map mode (the map spans the subtree) and takes the list variant in list mode', () => {
    setViewMode('list')
    expect(mountLevel([]).find('.memory-card-grid').classes()).toContain('is-list')
    setViewMode('map')
    expect(blocks(mountLevel([]))).toEqual([])
  })

  it('read-only: no new-folder card, the folder cards stay', () => {
    const w = mountLevel([TRIPS, node('Rome', FOLDER_KIND, 'Trips')], { folder: TRIPS, readonly: true })
    expect(w.find('.memory-create-card').exists()).toBe(false)
    expect(w.text()).toContain('Rome')
  })
})

// cr-node-key-hierarchy 5b.3 (moved from `MemoryBrowse.test.ts`): the folder overview card carries the share button the
// host allows (FOLDER's descriptor AND the capability — `MemoryHomeView.test.ts` pins that half), with a Memory's icon
// and label.
describe('LevelView: the folder overview card', () => {
  const shareButton = (w: ReturnType<typeof mountLevel>) => w.find(`button[aria-label="${t('share.share')}"]`)

  it('shows no overview card at the scope root', () => {
    expect(blocks(mountLevel([]))).toEqual(['folders'])
  })

  it('shows the share button on a shareable folder and emits share', async () => {
    const w = mountLevel([TRIPS], { folder: TRIPS, shareable: true })
    expect(shareButton(w).exists()).toBe(true)
    expect(shareButton(w).find('.pi-share-alt').exists()).toBe(true)
    await shareButton(w).trigger('click')
    expect(w.emitted('share')).toHaveLength(1)
  })

  it('shows none when the host says the folder is not shareable', () => {
    const w = mountLevel([TRIPS], { folder: TRIPS, shareable: false })
    expect(shareButton(w).exists()).toBe(false)
    expect(w.find(`button[aria-label="${t('memory.rename')}"]`).exists(), 'control: the card actions render').toBe(true)
  })

  it('emits renameFolder and deleteFolder from its actions', async () => {
    const w = mountLevel([TRIPS], { folder: TRIPS })
    await w.find(`button[aria-label="${t('memory.rename')}"]`).trigger('click')
    await w.find(`button[aria-label="${t('memory.delete')}"]`).trigger('click')
    expect(w.emitted('renameFolder')).toHaveLength(1)
    expect(w.emitted('deleteFolder')).toHaveLength(1)
  })

  // Found in the browser, 2026-10-07: the three carried `memory-action-collapsible` / `memory-action-inline`, which
  // memory.css hid at 721–1120 px and ≤ 720 px — and this card has no overflow menu to offer them instead. jsdom has no
  // layout, so the contract pinned is the DOM one: no width-hiding class on any of them.
  it('keeps share, rename and delete free of any width-hiding class (the card has no overflow menu)', () => {
    const w = mountLevel([TRIPS], { folder: TRIPS, shareable: true })
    const actions = w.findAll('.memory-folder-overview-actions button')
    expect(actions.map((b) => b.attributes('aria-label'))).toEqual([t('share.share'), t('memory.rename'), t('memory.delete')])
    for (const b of actions) {
      expect(b.classes()).not.toContain('memory-action-collapsible')
      expect(b.classes()).not.toContain('memory-action-inline')
    }
  })

  it('keeps the share (a read of the links) but no rename/delete during a pending deletion', () => {
    const w = mountLevel([TRIPS], { folder: TRIPS, shareable: true, readonly: true })
    expect(shareButton(w).exists()).toBe(true)
    expect(w.find(`button[aria-label="${t('memory.rename')}"]`).exists()).toBe(false)
  })
})
