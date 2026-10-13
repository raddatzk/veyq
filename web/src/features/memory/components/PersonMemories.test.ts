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
import { realI18n } from '../../../test/i18n'

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
  const w = mount(PersonMemories, { props: { userId, name }, global: { plugins: [realI18n(), PrimeVue, router] } })
  await flushPromises()
  return { w, router }
}

beforeEach(() => vi.clearAllMocks())

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
})
