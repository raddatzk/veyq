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
import { MEMORY_KIND, CHAPTER_KIND } from '../features/memory/kinds'
import { capabilityState } from '../shared/state/capabilities'
import { SHARING_PUBLIC_LINKS } from '../features/platform/capabilities'
import { realI18n, t } from '../test/i18n'

const Detail = defineComponent({ props: ['node', 'shareable'], setup: (p) => () => h('div', { class: 'detail' }, `${p.node.name}|${p.shareable}`) })
const Level = defineComponent({ props: ['level'], setup: (p) => () => h('div', { class: 'level' }, `memories=${(p.level.byKind.get(MEMORY_KIND) ?? []).length}`) })

function node(over: Partial<TreeNode> & Pick<TreeNode, 'id' | 'kind'>): TreeNode {
  return { parentId: null, orderIndex: 0, epoch: 0, name: over.id, wrappedContentKey: 'eA==', wrappedMeta: null, metaVersion: 0, placementVersion: 0, ...over }
}

/** The registry a well-formed application would leave: both kinds with a detail, the level view claimed. */
function fullRegistry(): Registry {
  const r = createRegistry()
  r.registerKind({ kind: MEMORY_KIND, detail: Detail, icon: 'pi pi-images', shareable: true })
  r.registerKind({ kind: CHAPTER_KIND, detail: Detail, icon: 'pi pi-images', shareable: false })
  r.claimSlot('levelView', Level)
  return r
}

async function mountAt(hash: string) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'home', component: { template: '<div />' } }] })
  await router.replace({ name: 'home', hash })
  await router.isReady()
  const wrapper = mount(MemoryHomeView, { global: { plugins: [router, realI18n()] } })
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
  it('control: with everything registered, a chapter opens its detail and the root shows the level view', async () => {
    expect((await mountAt('#~me/mem/chap')).find('.detail').text()).toBe('Chap|false')
    expect((await mountAt('#~me')).find('.level').text()).toBe('memories=1')
  })

  it('a kind registered WITHOUT a detail shows the explicit unsupported state, not the level view', async () => {
    const r = createRegistry()
    r.registerKind({ kind: MEMORY_KIND, detail: Detail, icon: 'x', shareable: true })
    r.registerKind({ kind: CHAPTER_KIND, icon: 'x', shareable: false }) // no detail
    r.claimSlot('levelView', Level)
    holder.reg = r
    const w = await mountAt('#~me/mem/chap')
    expect(w.text()).toContain(t('home.unsupportedKind'))
    expect(w.find('.detail').exists()).toBe(false)
    expect(w.find('.level').exists()).toBe(false)
  })

  it('a kind nobody registered shows the unsupported state too', async () => {
    const r = createRegistry()
    r.claimSlot('levelView', Level)
    holder.reg = r
    const w = await mountAt('#~me/mem')
    expect(w.text()).toContain(t('home.unsupportedKind'))
  })

  it('an unclaimed levelView never leaves a blank scope root: the unsupported state shows', async () => {
    const r = createRegistry()
    r.registerKind({ kind: MEMORY_KIND, detail: Detail, icon: 'x', shareable: true })
    holder.reg = r
    expect((await mountAt('#~me')).text()).toContain(t('home.unsupportedKind'))
  })

  it('shareable comes from the descriptor AND the capability: a descriptor that says no wins over the capability', async () => {
    const r = createRegistry()
    r.registerKind({ kind: MEMORY_KIND, detail: Detail, icon: 'x', shareable: false })
    r.claimSlot('levelView', Level)
    holder.reg = r
    expect((await mountAt('#~me/mem')).find('.detail').text()).toBe('Mem|false')
    holder.reg = fullRegistry()
    expect((await mountAt('#~me/mem')).find('.detail').text()).toBe('Mem|true')
  })
})
