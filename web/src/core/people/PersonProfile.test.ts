// @vitest-environment happy-dom
// A member's profile page (ADR 0083 decision 6): core's frame - name by the display rule, letter avatar, former member -
// around the sections the applications claim. The section here is a prop recorder; Memories' own is PersonMemories.test.ts.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import PrimeVue from 'primevue/config'

vi.mock('./directory', async (orig) => ({ ...(await orig<typeof import('./directory')>()), loadTenantPeople: vi.fn() }))
vi.mock('../applications/registry', async (orig) => {
  const real = await orig<typeof import('../applications/registry')>()
  const Section = defineComponent({ props: ['userId', 'name'], setup: (p) => () => h('div', { class: 'section-stub' }, `${p.userId}|${p.name}`) })
  return { ...real, registry: { ...real.registry, personSections: () => [Section] } }
})

import PersonProfile from './PersonProfile.vue'
import { loadTenantPeople, type Person } from './directory'
import { realI18n } from '../../test/i18n'

const ANNA = 'aaaaaaaa-0000-4000-8000-000000000001'
const load = vi.mocked(loadTenantPeople)
const anna: Person = { userId: ANNA, username: 'anna', realName: 'Anna Meyer', displayName: 'Anna Meyer' }

async function mountPage(userId = ANNA) {
  const w = mount(PersonProfile, { props: { userId }, global: { plugins: [realI18n(), PrimeVue] } })
  await flushPromises()
  return w
}

beforeEach(() => vi.clearAllMocks())

describe('PersonProfile', () => {
  it('says it is loading until the directory is read', async () => {
    load.mockReturnValue(new Promise(() => {}))
    const w = await mountPage()
    expect(w.get('[role="status"]').text()).toBe('Loading…')
  })

  it('shows the real name, the username under it, a letter avatar and each claimed section with the id and the name', async () => {
    load.mockResolvedValue(new Map([[ANNA, anna]]))
    const w = await mountPage()
    expect(w.get('h1').text()).toBe('Anna Meyer')
    expect(w.get('.person-username').text()).toBe('@anna')
    expect(w.get('.person-avatar').text()).toBe('A')
    expect(w.get('.section-stub').text()).toBe(`${ANNA}|Anna Meyer`)
  })

  it('names a member without a readable real name by the username, with no second line', async () => {
    load.mockResolvedValue(new Map([[ANNA, { ...anna, realName: null, displayName: 'anna' }]]))
    const w = await mountPage()
    expect(w.get('h1').text()).toBe('anna')
    expect(w.find('.person-username').exists()).toBe(false)
  })

  it('an id the tenant does not list is a greyed former member - the sections still run, with no name', async () => {
    load.mockResolvedValue(new Map())
    const w = await mountPage()
    expect(w.get('h1').text()).toBe('Former member')
    expect(w.get('.person-head').classes()).toContain('is-former')
    expect(w.get('.person-username').text()).toBe('No longer in this space.')
    expect(w.get('.section-stub').text()).toBe(`${ANNA}|`)
  })

  it('shows an error with a retry that reads again', async () => {
    load.mockRejectedValueOnce(new Error('offline'))
    const w = await mountPage()
    expect(w.get('[role="alert"]').text()).toContain('Could not load this person')
    expect(w.find('.section-stub').exists()).toBe(false)
    load.mockResolvedValueOnce(new Map([[ANNA, anna]]))
    await w.get('.person-retry').trigger('click')
    await flushPromises()
    expect(w.get('h1').text()).toBe('Anna Meyer')
  })

  it('re-reads when the route moves to another person', async () => {
    load.mockResolvedValue(new Map([[ANNA, anna]]))
    const w = await mountPage()
    await w.setProps({ userId: 'other' })
    await flushPromises()
    expect(load).toHaveBeenCalledTimes(2)
    expect(w.get('h1').text()).toBe('Former member')
  })
})
