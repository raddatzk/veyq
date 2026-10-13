// @vitest-environment happy-dom
// A person's profile section from Memories (ADR 0083 decision 6): loading, error, empty, the list in core's row item, the
// honest "partial" note, and the click that opens the Memory in ITS scope (a group's under its `@<scope>` token).
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import PrimeVue from 'primevue/config'

vi.mock('../participantIndex', async (orig) => ({ ...(await orig<typeof import('../participantIndex')>()), loadParticipantIndex: vi.fn() }))
vi.mock('../../../core/node/cover/coverStore', async (orig) => ({
  ...(await orig<typeof import('../../../core/node/cover/coverStore')>()),
  createCoverStore: vi.fn(() => ({ covers: { value: new Map() }, declared: new Set(), ensure: vi.fn(), reset: vi.fn() })),
}))

import PersonMemories from './PersonMemories.vue'
import { loadParticipantIndex, type ParticipantIndex, type ParticipatedMemory } from '../participantIndex'
import { treeNode } from '../../../test/models'
import { MEMORY_KIND } from '../kinds'
import { realI18n, t } from '../../../test/i18n'
import { setViewMode, viewModeStorageKey } from '../../../shared/state/useViewMode'
import { setAccountUserId } from '../../../app/account'

const ANNA = 'aaaaaaaa-0000-4000-8000-000000000001'
const BEN = 'bbbbbbbb-0000-4000-8000-000000000002'

function memory(over: Partial<ParticipatedMemory> & { name: string; id: string }): ParticipatedMemory {
  const node = treeNode({ id: over.id, name: over.name, kind: MEMORY_KIND, parentId: null })
  return {
    ref: `s/${over.id}`, scopeId: 's', scopeToken: '', scopeLabel: '', node, tree: [node], key: new Uint8Array(32),
    date: null, placeName: '', userIds: [ANNA], ...over,
  }
}
function indexOf(memories: ParticipatedMemory[], partial = false): ParticipantIndex {
  const byUser = new Map<string, Set<string>>()
  for (const m of memories) for (const u of m.userIds) byUser.set(u, new Set([...(byUser.get(u) ?? []), m.ref]))
  return { byUser, memories: new Map(memories.map((m) => [m.ref, m])), partial }
}
const load = vi.mocked(loadParticipantIndex)

async function mountSection(userId = ANNA, name = 'Anna') {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { template: '<div />' } }] })
  await router.push('/')
  const w = mount(PersonMemories, { props: { userId, name }, global: { plugins: [realI18n(), PrimeVue, router], directives: { tooltip: {} } } })
  await flushPromises()
  return { w, router }
}

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  setAccountUserId('user-1')
  setViewMode('grid')
})

describe('PersonMemories', () => {
  it('says it is loading while the index is built', async () => {
    load.mockReturnValue(new Promise(() => {}))
    const { w } = await mountSection()
    expect(w.get('[role="status"]').text()).toContain('Looking through your Memories')
    expect(w.find('[data-testid="person-memories-empty"]').exists()).toBe(false)
  })

  it('lists the person\'s Memories newest first with date and place, and opens one in its own scope', async () => {
    const old = memory({ id: 'o', name: 'Rome', date: { mode: 'single', from: '2025-05-01', to: '2025-05-01' }, placeName: 'Roma' })
    const recent = memory({ id: 'r', name: 'Camping', date: { mode: 'single', from: '2026-08-01', to: '2026-08-01' }, scopeToken: '@grp', scopeLabel: 'Family' })
    const other = memory({ id: 'x', name: 'Not Anna', userIds: [BEN] })
    load.mockResolvedValue(indexOf([old, other, recent]))
    const { w, router } = await mountSection()
    expect(w.findAll('.veyq-level-item-title').map((t) => t.text())).toEqual(['Camping', 'Rome'])
    expect(w.text()).toContain('Roma')
    expect(w.text()).toContain('Family')
    expect(w.get('h2').text()).toBe('Memories with Anna')
    await w.findAll('button.veyq-level-item-main, .veyq-level-item-main').find((b) => b.text().includes('Camping'))!.trigger('click')
    await flushPromises()
    expect(decodeURIComponent(router.currentRoute.value.hash)).toBe('#@grp/memories/camping')
  })

  it('says so when nobody the viewer can open lists the person', async () => {
    load.mockResolvedValue(indexOf([memory({ id: 'x', name: 'Not Anna', userIds: [BEN] })]))
    const { w } = await mountSection()
    expect(w.get('[data-testid="person-memories-empty"]').text()).toBe('No Memories you can open list this person.')
    expect(w.find('[data-testid="person-memories-partial"]').exists()).toBe(false)
  })

  it('says the list may be incomplete when the walk was capped or a scope failed - with the rows it has', async () => {
    load.mockResolvedValue(indexOf([memory({ id: 'o', name: 'Rome' })], true))
    const { w } = await mountSection()
    expect(w.findAll('.veyq-level-item-title')).toHaveLength(1)
    expect(w.get('[data-testid="person-memories-partial"]').text()).toContain('may be incomplete')
  })

  it('shows an error with a retry that tries again', async () => {
    load.mockRejectedValueOnce(new Error('offline'))
    const { w } = await mountSection()
    expect(w.get('[role="alert"]').text()).toContain('Could not look through your Memories')
    load.mockResolvedValueOnce(indexOf([memory({ id: 'o', name: 'Rome' })]))
    await w.get('.person-memories-retry').trigger('click')
    await flushPromises()
    expect(w.findAll('.veyq-level-item-title').map((t) => t.text())).toEqual(['Rome'])
  })

  it('a former member (no name) still gets the list under a plain heading', async () => {
    load.mockResolvedValue(indexOf([memory({ id: 'o', name: 'Rome' })]))
    const { w } = await mountSection(ANNA, '')
    expect(w.get('h2').text()).toBe('Memories with this person')
    expect(w.findAll('.veyq-level-item-title')).toHaveLength(1)
  })

  describe('details', () => {
    it('shows the date and the place as the row\'s details (the level\'s builder), with the group\'s name after them', async () => {
      const m = memory({ id: 'o', name: 'Rome', date: { mode: 'single', from: '2025-05-01', to: '2025-05-01' }, placeName: 'Roma', scopeToken: '@grp', scopeLabel: 'Family' })
      load.mockResolvedValue(indexOf([m]))
      const { w } = await mountSection()
      expect(w.findAll('[data-detail]').map((d) => d.attributes('data-detail'))).toEqual(['date', 'place', 'scope'])
      expect(w.get('.veyq-level-item-meta').text()).toBe('Memory · 05/01/2025 · Roma · Family')
    })

    it('omits what the Memory does not have - no placeholder', async () => {
      load.mockResolvedValue(indexOf([memory({ id: 'o', name: 'Rome' })]))
      const { w } = await mountSection()
      expect(w.find('[data-detail]').exists()).toBe(false)
      expect(w.get('.veyq-level-item-meta').text()).toBe('Memory')
    })
  })

  describe('the rows/tiles switch (core\'s ViewModeToggle over the per-user preference)', () => {
    const buttons = (w: ReturnType<typeof mount>) => w.findAll('.view-mode-button')

    it('offers the two layouts core draws - rows and tiles, no map - once there is something to list', async () => {
      load.mockResolvedValue(indexOf([memory({ id: 'o', name: 'Rome' })]))
      const { w } = await mountSection()
      expect(buttons(w).map((b) => b.attributes('aria-label'))).toEqual([t('node.viewGrid'), t('node.viewList')])
    })

    it('has no switch while loading, on an error, or with nothing to list', async () => {
      load.mockReturnValue(new Promise(() => {}))
      expect((await mountSection()).w.find('.view-mode-toggle').exists()).toBe(false)
      load.mockRejectedValueOnce(new Error('offline'))
      expect((await mountSection()).w.find('.view-mode-toggle').exists()).toBe(false)
      load.mockResolvedValue(indexOf([]))
      expect((await mountSection()).w.find('.view-mode-toggle').exists()).toBe(false)
    })

    it('draws tiles by default and rows after the switch, as the same core items', async () => {
      load.mockResolvedValue(indexOf([memory({ id: 'o', name: 'Rome' })]))
      const { w } = await mountSection()
      expect(w.get('[data-testid="person-memories-list"]').classes()).not.toContain('is-list')
      expect(w.get('li.veyq-level-item').classes()).toContain('is-grid')
      await buttons(w)[1].trigger('click')
      expect(w.get('[data-testid="person-memories-list"]').classes()).toContain('is-list')
      expect(w.get('li.veyq-level-item').classes()).toContain('is-list')
      await buttons(w)[0].trigger('click')
      expect(w.get('li.veyq-level-item').classes()).toContain('is-grid')
    })

    it('stores the choice per user - the folder view\'s very preference - and a page opened later starts from it', async () => {
      load.mockResolvedValue(indexOf([memory({ id: 'o', name: 'Rome' })]))
      const first = await mountSection()
      await buttons(first.w)[1].trigger('click')
      expect(localStorage.getItem(viewModeStorageKey()!)).toBe('list')
      expect(viewModeStorageKey()).toBe('veyq:view-mode:u:user-1')
      first.w.unmount()
      const again = await mountSection()
      expect(again.w.get('li.veyq-level-item').classes()).toContain('is-list')
      expect(buttons(again.w)[1].attributes('aria-pressed')).toBe('true')
    })

    it('another user of the browser keeps their own choice', async () => {
      load.mockResolvedValue(indexOf([memory({ id: 'o', name: 'Rome' })]))
      const mine = await mountSection()
      await buttons(mine.w)[1].trigger('click')
      mine.w.unmount()
      setAccountUserId('user-2')
      const theirs = await mountSection()
      expect(theirs.w.get('li.veyq-level-item').classes()).toContain('is-grid')
    })

    it('shows tiles (without rewriting the stored mode) when the stored one is the map, which this page does not offer', async () => {
      setViewMode('map')
      load.mockResolvedValue(indexOf([memory({ id: 'o', name: 'Rome' })]))
      const { w } = await mountSection()
      expect(w.get('li.veyq-level-item').classes()).toContain('is-grid')
      expect(buttons(w)[0].attributes('aria-pressed')).toBe('true')
      expect(localStorage.getItem(viewModeStorageKey()!)).toBe('map')
    })
  })
})
