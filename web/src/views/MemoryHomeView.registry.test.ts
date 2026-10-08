// @vitest-environment happy-dom
// The host's fail-closed rules, proven against a STUBBED registry (a fresh `createRegistry()` per test behind a
// delegating `registry` export), so each test chooses exactly what the application "registered". The real
// registry + real adapters are covered in `MemoryHomeView.test.ts`. Every negative here has its live control: the
// same node with the component registered renders it.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { defineComponent, h } from 'vue'

const holder = vi.hoisted(() => ({ reg: null as null | import('../core/applications/registry').Registry }))
vi.mock('../core/applications/registry', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../core/applications/registry')>()
  // `src/test/setup.ts` registers the real applications into whatever this resolves to before the first test;
  // each test then swaps `holder.reg` for the registry it wants.
  const r = () => (holder.reg ??= actual.createRegistry())
  return {
    ...actual,
    // A Proxy, not a method list: a method the registry gains later is delegated too, instead of being silently
    // missing here (a list of four methods once made `registerLifecycle` "not a function" for the whole file).
    registry: new Proxy({} as import('../core/applications/registry').Registry, {
      get: (_t, name: string) => (r() as unknown as Record<string, (...a: unknown[]) => unknown>)[name].bind(r()),
    }),
  }
})
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
}))
vi.mock('../features/spaces/scopes', () => ({ listMyTenants: vi.fn() }))
vi.mock('../core/node/treeStore', async () => {
  const { reactive } = await import('vue')
  return { treeStore: reactive({ nodes: [], loaded: true, error: false }), loadTree: vi.fn(async () => {}) }
})
vi.mock('../core/node/tree', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../core/node/tree')>()),
  nodeContentKey: () => new Uint8Array(32).fill(9),
}))

import MemoryHomeView from './MemoryHomeView.vue'
import { createRegistry, type Registry } from '../core/applications/registry'
import { treeStore } from '../core/node/treeStore'
import { listMyTenants } from '../features/spaces/scopes'
import type { TreeNode } from '../core/node/tree'
import { FOLDER_KIND } from '../core/node/kind'
import { MEMORY_KIND, CHAPTER_KIND } from '../features/memory/kinds'
import { capabilityState } from '../shared/state/capabilities'
import { SHARING_PUBLIC_LINKS } from '../features/platform/capabilities'
import { realI18n, t } from '../test/i18n'

const Detail = defineComponent({ props: ['node', 'shareable'], setup: (p) => () => h('div', { class: 'detail' }, `${p.node.name}|${p.shareable}`) })
const Section = defineComponent({ props: ['nodes'], setup: (p) => () => h('section', { class: 'level' }, `memories=${p.nodes.length}`) })
const Share = defineComponent({ props: ['nodeId'], setup: (p) => () => h('div', { class: 'share' }, p.nodeId) })

function node(over: Partial<TreeNode> & Pick<TreeNode, 'id' | 'kind'>): TreeNode {
  return { parentId: null, orderIndex: 0, epoch: 0, name: over.id, wrappedContentKey: 'eA==', wrappedMeta: null, metaVersion: 0, placementVersion: 0, ...over }
}

/** The registry a well-formed application would leave: both kinds with a detail, shareable with a share dialog (as
 *  Memories registers them), both naming one level section. */
function fullRegistry(): Registry {
  const r = createRegistry()
  const section = { order: 10, component: Section }
  r.registerKind({ kind: MEMORY_KIND, detail: Detail, icon: 'pi pi-images', shareable: true, section, shareDialog: Share })
  r.registerKind({ kind: CHAPTER_KIND, detail: Detail, icon: 'pi pi-images', shareable: true, section, shareDialog: Share })
  return r
}

async function mountAt(hash: string) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'home', component: { template: '<div />' } }] })
  await router.replace({ name: 'home', hash })
  await router.isReady()
  const wrapper = mount(MemoryHomeView, { global: { plugins: [router, realI18n()], directives: { tooltip: {} } } })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.mocked(listMyTenants).mockResolvedValue([])
  holder.reg = fullRegistry()
  capabilityState.ids = [SHARING_PUBLIC_LINKS]
  treeStore.nodes = [
    node({ id: 'mem', kind: MEMORY_KIND, name: 'Mem' }),
    node({ id: 'chap', kind: CHAPTER_KIND, name: 'Chap', parentId: 'mem' }),
  ]
})

describe('MemoryHomeView fail-closed rules', () => {
  it('control: with everything registered, a chapter opens its detail and the root shows the application section', async () => {
    expect((await mountAt('#~me/mem/chap')).find('.detail').text()).toBe('Chap|true')
    expect((await mountAt('#~me')).find('.level').text()).toBe('memories=1')
  })

  it('a kind registered WITHOUT a detail shows the explicit unsupported state, not the level view', async () => {
    const r = createRegistry()
    r.registerKind({ kind: MEMORY_KIND, detail: Detail, icon: 'x', shareable: true })
    r.registerKind({ kind: CHAPTER_KIND, icon: 'x', shareable: false }) // no detail
    holder.reg = r
    const w = await mountAt('#~me/mem/chap')
    expect(w.text()).toContain(t('home.unsupportedKind'))
    expect(w.find('.detail').exists()).toBe(false)
    expect(w.find('.level').exists()).toBe(false)
  })

  it('a kind nobody registered shows the unsupported state too', async () => {
    const r = createRegistry()
    holder.reg = r
    const w = await mountAt('#~me/mem')
    expect(w.text()).toContain(t('home.unsupportedKind'))
  })

  // Since P4b the level view is core's: with no section registered, folders are still walkable, and the children of
  // a kind nobody sections (registered or not) show in core's fallback section, never as a blank level.
  it('no section registered: folders are still walkable, children of unsectioned kinds show the fallback', async () => {
    const r = createRegistry()
    r.registerKind({ kind: MEMORY_KIND, detail: Detail, icon: 'pi pi-images', shareable: true }) // no section
    holder.reg = r
    treeStore.nodes = [
      node({ id: 'trips', kind: FOLDER_KIND, name: 'Trips' }),
      node({ id: 'mem', kind: MEMORY_KIND, name: 'Mem', parentId: 'trips' }),
      node({ id: 'odd', kind: 'UNKNOWN_KIND', name: 'Odd', parentId: 'trips' }),
    ]
    const root = await mountAt('#~me')
    expect(root.find('.level').exists()).toBe(false)
    const folder = root.findAll('button.veyq-folder-card').find((b) => b.text() === 'Trips')
    expect(folder, 'the folder card renders without any application section').toBeDefined()
    await folder!.trigger('click')
    await flushPromises()

    const fallbacks = root.findAll('.veyq-level-fallback')
    expect(fallbacks.map((f) => f.find('h2').text())).toEqual([MEMORY_KIND, 'UNKNOWN_KIND'])
    expect(fallbacks.map((f) => f.find('strong').text())).toEqual(['Mem', 'Odd'])
    for (const f of fallbacks) expect(f.text()).toContain(t('home.unsupportedKind'))
    expect(fallbacks[0].find('.pi-images').exists(), 'a registered kind keeps its icon').toBe(true)
  })

  it('shareable comes from the descriptor AND the capability: a descriptor that says no wins over the capability', async () => {
    const r = createRegistry()
    r.registerKind({ kind: MEMORY_KIND, detail: Detail, icon: 'x', shareable: false })
    holder.reg = r
    expect((await mountAt('#~me/mem')).find('.detail').text()).toBe('Mem|false')
    holder.reg = fullRegistry()
    expect((await mountAt('#~me/mem')).find('.detail').text()).toBe('Mem|true')
  })

  // cr-files-application P4c: the share dialog is the kind's. A kind that says `shareable` but brings no `shareDialog`
  // offers no share — the host never shows a button whose dialog it cannot mount.
  it('a kind without a shareDialog offers no share, even when its descriptor says shareable', async () => {
    const r = createRegistry()
    r.registerKind({ kind: MEMORY_KIND, detail: Detail, icon: 'x', shareable: true }) // no shareDialog
    holder.reg = r
    expect((await mountAt('#~me/mem')).find('.detail').text()).toBe('Mem|false')
    holder.reg = fullRegistry() // control: the same kind with its dialog is shared, and the dialog is the kind's own
    const w = await mountAt('#~me/mem')
    expect(w.find('.detail').text()).toBe('Mem|true')
    w.findComponent(Detail).vm.$emit('share')
    await flushPromises()
    expect(w.find('.share').text()).toBe('mem')
  })
})
