// @vitest-environment happy-dom
// The profile route of the shell host (ADR 0083 decision 5): `#people/<userId>` shows core's profile page INSTEAD of a
// level, in the tenant scope; a folder called "People" is numbered away so no node ever lands on it, and the host never
// polls the tree for a page that is no level. The page itself is PersonProfile.test.ts's.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('leaflet', () => ({ default: {} }))
vi.mock('../app/activeSpace', async () => {
  const { reactive } = await import('vue')
  return {
    activeSpace: reactive({
      status: 'ready', scopeId: 'scope-1', tenantId: 't-1', kind: 'TENANT', name: null, epoch: 0,
      deletedAt: null, purgeAfter: null, trustRefusal: null,
    }),
    enterScope: vi.fn(async () => {}),
    activeEpochKeys: () => ({ current: 0, keys: new Map([[0, new Uint8Array(32).fill(1)]]) }),
  }
})
vi.mock('../core/favorites/favoritesStore', () => ({
  favoritesStore: { loaded: true, ids: new Set<string>() },
  loadFavoritesFor: vi.fn(),
  isFavorite: vi.fn(),
  toggleFavorite: vi.fn(),
}))
vi.mock('../features/files/api', () => ({ filesApi: vi.fn() }))
vi.mock('../features/spaces/scopes', () => ({ listMyTenants: vi.fn() }))
vi.mock('../core/node/treeStore', async () => {
  const { reactive } = await import('vue')
  return { treeStore: reactive({ nodes: [], loaded: true, error: false }), loadTree: vi.fn(async () => {}), patchNodeMeta: vi.fn(), pollTree: vi.fn(async () => false) }
})
vi.mock('../core/people/directory', () => ({ loadTenantPeople: vi.fn(async () => new Map()), personNow: vi.fn(() => undefined) }))
vi.mock('../core/people/PersonProfile.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return { default: defineComponent({ name: 'PersonProfileStub', props: ['userId'], setup: (p) => () => h('div', { class: 'profile-stub' }, p.userId) }) }
})

import MemoryHomeView from './MemoryHomeView.vue'
import { personNow } from '../core/people/directory'
import { treeStore } from '../core/node/treeStore'
import { enterScope } from '../app/activeSpace'
import { breadcrumb } from '../app/breadcrumb'
import { listMyTenants } from '../features/spaces/scopes'
import { FOLDER_KIND } from '../core/node/kind'
import { MEMORY_KIND } from '../features/memory/kinds'
import { treeNode } from '../test/models'
import { titlesOf } from '../test/levelRows'
import { realI18n } from '../test/i18n'
import PrimeVue from 'primevue/config'

const UID = '3f2c9e1a-0000-4000-8000-00000000abcd'
const mounted: { unmount(): void }[] = []
async function mountAt(hash: string) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'home', component: { template: '<div />' } }] })
  await router.replace({ name: 'home', hash })
  await router.isReady()
  const wrapper = mount(MemoryHomeView, { global: { plugins: [router, realI18n(), PrimeVue], directives: { tooltip: {} } } })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}
afterEach(() => { mounted.splice(0).forEach((w) => w.unmount()) })
beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(listMyTenants).mockResolvedValue([])
  treeStore.nodes = [
    treeNode({ id: 'people', kind: FOLDER_KIND, name: 'People', parentId: null }),
    treeNode({ id: 'rome', kind: MEMORY_KIND, name: 'Rome', parentId: null }),
  ]
})

describe('MemoryHomeView: #people/<userId>', () => {
  it('shows the profile page of the person instead of a level, in the tenant scope', async () => {
    const w = await mountAt(`#people/${UID}`)
    expect(w.get('.profile-stub').text()).toBe(UID)
    expect(titlesOf(w)).toEqual([]) // no level on screen
    expect(vi.mocked(enterScope)).toHaveBeenCalledWith('')
    expect(breadcrumb.trail.map((c) => c.name)).toEqual(['Shared', 'Profile'])
    expect(decodeURIComponent(breadcrumb.trail[1].hash)).toBe(`#people/${UID}`)
  })

  it('names the page by the person\'s ~handle from the roster already on the device', async () => {
    // The roster read is mocked below; the crumb answers from what `personNow` knows, with no wait on the page.
    vi.mocked(personNow).mockReturnValue({ userId: UID, username: 'anna', realName: null, displayName: 'anna' })
    await mountAt(`#people/${UID}`)
    expect(breadcrumb.trail.map((c) => c.name)).toEqual(['Shared', 'Profile', '~anna'])
  })

  it('a folder called "People" is not the route: it opens as people-2, and the profile page stays away', async () => {
    const w = await mountAt('#people-2/')
    expect(w.find('.profile-stub').exists()).toBe(false)
    expect(w.get('.veyq-node-header-title').text()).toBe('People')
  })

  it('a path under the personal scope that spells the same words is no profile', async () => {
    const w = await mountAt(`#~me/people/${UID}`)
    expect(w.find('.profile-stub').exists()).toBe(false)
  })
})
