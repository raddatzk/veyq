// @vitest-environment happy-dom
// A person's profile section from Memories (ADR 0083 decision 6): loading, error, empty, the list in core's row item, the
// honest "partial" note, the click that opens the Memory in ITS scope (a group's under its `@<scope>` token), and the
// level's own map over the person's Memories with a place (inbox #417).
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

// SpaceMap wraps Leaflet, which a happy-dom suite cannot run: the module is stubbed out (the map's own canvas is never
// reached here - the real component is mounted only where it draws its consent prompt or its empty state).
vi.mock('leaflet', () => ({ default: {} }))
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
import { useMapConfig } from '../../../shared/state/useMapConfig'
import type { MemoryLocation } from '../memoryLocation'
import type { LocatedPin } from '../spaceMap'

const ANNA = 'aaaaaaaa-0000-4000-8000-000000000001'
const BEN = 'bbbbbbbb-0000-4000-8000-000000000002'

function memory(over: Partial<ParticipatedMemory> & { name: string; id: string }): ParticipatedMemory {
  const node = treeNode({ id: over.id, name: over.name, kind: MEMORY_KIND, parentId: null })
  return {
    ref: `s/${over.id}`, scopeId: 's', scopeToken: '', scopeLabel: '', node, tree: [node], key: new Uint8Array(32),
    date: null, location: null, placeName: '', userIds: [ANNA], ...over,
  }
}
/** A place with a position (a pin), as the index hands it over after unsealing. */
const placed = (name: string, lat: number, lng: number): MemoryLocation => ({ name, nameSource: 'manual', derivedMode: 'fixed', derived: { lat, lng } })
/** A pending automatic place: a name may come, no position yet - no pin (the level's rule). */
const pending: MemoryLocation = { name: '', nameSource: 'geocoded', derivedMode: 'auto' }

// A stand-in for the map (the level's own `SpaceMap`): it records the pins it is handed and re-emits `open` on demand.
const SpaceMapStub = {
  name: 'SpaceMap',
  props: ['pins', 'covers'],
  emits: ['open'],
  template: `<div class="space-map-stub">{{ pins.length }}</div>`,
}
function indexOf(memories: ParticipatedMemory[], partial = false): ParticipantIndex {
  const byUser = new Map<string, Set<string>>()
  for (const m of memories) for (const u of m.userIds) byUser.set(u, new Set([...(byUser.get(u) ?? []), m.ref]))
  return { byUser, memories: new Map(memories.map((m) => [m.ref, m])), partial }
}
const load = vi.mocked(loadParticipantIndex)

async function mountSection(userId = ANNA, name = 'Anna', { realMap = false } = {}) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { template: '<div />' } }] })
  await router.push('/')
  const stubs: Record<string, typeof SpaceMapStub> = realMap ? {} : { SpaceMap: SpaceMapStub }
  const w = mount(PersonMemories, { props: { userId, name }, global: { plugins: [realI18n(), PrimeVue, router], directives: { tooltip: {} }, stubs } })
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

  describe('the view switch (core\'s ViewModeToggle over the per-user preference)', () => {
    const buttons = (w: ReturnType<typeof mount>) => w.findAll('.view-mode-button')

    it('offers what a level of Memories offers - tiles, rows and the map - once there is something to list', async () => {
      load.mockResolvedValue(indexOf([memory({ id: 'o', name: 'Rome', location: placed('Roma', 41.9, 12.5) })]))
      const { w } = await mountSection()
      expect(buttons(w).map((b) => b.attributes('aria-label'))).toEqual([t('node.viewGrid'), t('node.viewList'), t('memory.viewMap')])
    })

    it('offers the map even when none of the person\'s Memories has a place, as the level does', async () => {
      load.mockResolvedValue(indexOf([memory({ id: 'o', name: 'Rome' })]))
      const { w } = await mountSection()
      expect(buttons(w).map((b) => b.attributes('aria-label'))).toContain(t('memory.viewMap'))
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

    it('a stored map shows the map here too, pressed, and the stored mode stays the map', async () => {
      setViewMode('map')
      load.mockResolvedValue(indexOf([memory({ id: 'o', name: 'Rome', location: placed('Roma', 41.9, 12.5) })]))
      const { w } = await mountSection()
      expect(w.findComponent(SpaceMapStub).exists()).toBe(true)
      expect(w.find('[data-testid="person-memories-list"]').exists()).toBe(false)
      expect(buttons(w)[2].attributes('aria-pressed')).toBe('true')
      expect(localStorage.getItem(viewModeStorageKey()!)).toBe('map')
    })

    it('a stored map is not shown where the page offers nothing - the person has no Memories: the empty text, not a map', async () => {
      setViewMode('map')
      load.mockResolvedValue(indexOf([]))
      const { w } = await mountSection()
      expect(w.find('[data-testid="person-memories-empty"]').exists()).toBe(true)
      expect(w.findComponent(SpaceMapStub).exists()).toBe(false)
      expect(localStorage.getItem(viewModeStorageKey()!)).toBe('map')
    })
  })

  describe('the map (the level\'s own SpaceMap, inbox #417)', () => {
    const pinNames = (w: ReturnType<typeof mount>) => (w.findComponent(SpaceMapStub).props('pins') as LocatedPin[]).map((p) => p.node.name)

    it('the switch shows the map in place of the list, and back', async () => {
      load.mockResolvedValue(indexOf([memory({ id: 'o', name: 'Rome', location: placed('Roma', 41.9, 12.5) })]))
      const { w } = await mountSection()
      await w.findAll('.view-mode-button')[2].trigger('click')
      expect(w.findComponent(SpaceMapStub).exists()).toBe(true)
      expect(w.find('[data-testid="person-memories-list"]').exists()).toBe(false)
      expect(localStorage.getItem(viewModeStorageKey()!)).toBe('map')
      await w.findAll('.view-mode-button')[0].trigger('click')
      expect(w.findComponent(SpaceMapStub).exists()).toBe(false)
      expect(w.get('li.veyq-level-item').classes()).toContain('is-grid')
    })

    it('pins exactly the person\'s Memories that have a place - none without one, none of another person', async () => {
      setViewMode('map')
      load.mockResolvedValue(indexOf([
        memory({ id: 'r', name: 'Rome', location: placed('Roma', 41.9, 12.5), date: { mode: 'single', from: '2025-05-01', to: '2025-05-01' } }),
        memory({ id: 'c', name: 'Camping', scopeToken: '@grp', location: placed('Lake', 47.5, 9.7) }),
        memory({ id: 'n', name: 'No place' }),
        memory({ id: 'p', name: 'Pending place', location: pending }),
        memory({ id: 'b', name: 'Ben\'s trip', location: placed('Oslo', 59.9, 10.7), userIds: [BEN] }),
      ]))
      const { w } = await mountSection()
      expect(pinNames(w).sort()).toEqual(['Camping', 'Rome'])
      const rome = (w.findComponent(SpaceMapStub).props('pins') as LocatedPin[]).find((p) => p.node.name === 'Rome')!
      expect(rome.location.derived).toEqual({ lat: 41.9, lng: 12.5 })
      expect(rome.date?.from).toBe('2025-05-01')
    })

    it('a pin opens its Memory in its own scope, as its row does', async () => {
      setViewMode('map')
      load.mockResolvedValue(indexOf([memory({ id: 'c', name: 'Camping', scopeToken: '@grp', location: placed('Lake', 47.5, 9.7) })]))
      const { w, router } = await mountSection()
      w.findComponent(SpaceMapStub).vm.$emit('open', 'c')
      await flushPromises()
      expect(decodeURIComponent(router.currentRoute.value.hash)).toBe('#@grp/memories/camping')
    })

    describe('with the real map component', () => {
      afterEach(() => useMapConfig().setMapsConsent(false))

      it('asks for the map consent first, exactly as the level\'s map does', async () => {
        useMapConfig().setMapsConsent(false)
        setViewMode('map')
        load.mockResolvedValue(indexOf([memory({ id: 'o', name: 'Rome', location: placed('Roma', 41.9, 12.5) })]))
        const { w } = await mountSection(ANNA, 'Anna', { realMap: true })
        expect(w.get('.space-map').text()).toContain(t('memory.place.consentTitle'))
        expect(w.find('.space-map-canvas').exists()).toBe(false)
      })

      it('says there are no places yet when none of the person\'s Memories has one, as the level\'s map does', async () => {
        useMapConfig().setMapsConsent(true)
        setViewMode('map')
        load.mockResolvedValue(indexOf([memory({ id: 'o', name: 'Rome' }), memory({ id: 'p', name: 'Pending', location: pending })]))
        const { w } = await mountSection(ANNA, 'Anna', { realMap: true })
        expect(w.get('.space-map').text()).toContain(t('memory.map.empty'))
        expect(w.find('.space-map-canvas').exists()).toBe(false)
      })
    })
  })
})
