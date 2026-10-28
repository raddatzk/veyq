// @vitest-environment happy-dom
// Who's who (inbox #562, uc.spaces.enforce-verified-identities main flow 5). The tenant's read (`loadPeopleOverview` -
// the anchor, the counted verifications, the pins and the rotation) is mocked so each test says exactly what the overview
// holds; its order (`orderPeople`) and the catalog are REAL, and the links are the real router's, clicked as rendered.
// What the read decides is peopleOverview.test.ts'.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent, h } from 'vue'

vi.mock('../account/peopleOverview', async (orig) => ({
  ...(await orig<typeof import('../account/peopleOverview')>()),
  loadPeopleOverview: vi.fn(),
}))
vi.mock('../../core/people/directory', async (orig) => ({
  ...(await orig<typeof import('../../core/people/directory')>()),
  loadTenantPeople: vi.fn(),
}))

import PeopleOverviewPanel from './PeopleOverviewPanel.vue'
import { loadPeopleOverview, type OverviewPerson } from '../account/peopleOverview'
import { loadTenantPeople, type Person } from '../../core/people/directory'
import { TrustStateRefused } from '../account/trustMarks'
import { activeSpace } from '../../app/activeSpace'
import { account } from '../../app/account'
import { realI18n, t } from '../../test/i18n'

const TENANT = '7e000000-0000-4000-8000-0000000000f3'
const ME = 'eeeeeeee-0000-4000-8000-000000000009'
const ANNA = 'aaaaaaaa-0000-4000-8000-000000000001'
const BEN = 'bbbbbbbb-0000-4000-8000-000000000002'
const CARL = 'cccccccc-0000-4000-8000-000000000003'
const DORA = 'dddddddd-0000-4000-8000-000000000004'
const ZOE = 'ffffffff-0000-4000-8000-000000000005'

const person = (userId: string, displayName: string): Person => ({ userId, username: displayName.toLowerCase(), realName: displayName, displayName })
const who = (userId: string, o: Partial<OverviewPerson> = {}): OverviewPerson =>
  ({ userId, username: userId.slice(0, 4), state: 'unverified', isNew: false, verifiedBy: [], rotatedAt: null, replaced: false, ...o })

const Page = defineComponent({ setup: () => () => h('div') })

async function mountPanel() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: Page }, { path: '/settings/:section?', component: Page }] })
  await router.push('/settings/tenant-people')
  const w = mount(PeopleOverviewPanel, { attachTo: document.body, global: { plugins: [realI18n(), router], stubs: { PersonAvatar: true, TrustResetLink: true } } })
  await flushPromises()
  return { w, router }
}
type W = Awaited<ReturnType<typeof mountPanel>>['w']
const rowOf = (w: W, userId: string) => w.get(`[data-testid="who-${userId}"]`)
const order = (w: W) => w.findAll('[data-testid^="who-"][class*="member-row"]').map((r) => r.attributes('data-testid')!.slice(4))

beforeEach(() => {
  vi.clearAllMocks()
  activeSpace.tenantId = TENANT
  account.userId = ME
  vi.mocked(loadTenantPeople).mockResolvedValue(new Map([
    [ANNA, person(ANNA, 'Anna')], [BEN, person(BEN, 'Ben')], [CARL, person(CARL, 'Carl')], [DORA, person(DORA, 'Dora')], [ZOE, person(ZOE, 'Zoe')],
  ]))
})

describe('who is who, per person', () => {
  it('anomalies first, then by name; each with its one state, and the lines decision 15 shows', async () => {
    const rotatedAt = new Date('2026-10-20T10:00:00Z')
    vi.mocked(loadPeopleOverview).mockResolvedValue({ available: true, people: [
      who(DORA, { state: 'verified', verifiedBy: [BEN, ME] }),
      who(ANNA, { state: 'unverified', isNew: true, rotatedAt }),
      who(ZOE, { state: 'anomaly' }),
      who(CARL, { state: 'unchecked' }),
      who(BEN, { state: 'verified', verifiedBy: [DORA], replaced: true }),
    ] })
    const { w } = await mountPanel()
    expect(vi.mocked(loadPeopleOverview)).toHaveBeenCalledWith(TENANT, ME)
    expect(order(w)).toEqual([ZOE, ANNA, BEN, CARL, DORA])

    const zoe = rowOf(w, ZOE)
    expect(zoe.get('[data-testid="who-state"]').text()).toBe(t('trust.anomaly'))
    expect(zoe.get('[data-testid="who-anomaly-note"]').text()).toBe(t('trust.anomalyNote'))

    const anna = rowOf(w, ANNA)
    expect(anna.get('[data-testid="who-state"]').text()).toBe(t('safety.stateUnverified'))
    expect(anna.find('[data-testid="who-new"]').exists()).toBe(true)
    expect(anna.get('[data-testid="who-rotated"]').text()).toBe(t('trust.rotated', { date: 'October 20, 2026' }))

    const dora = rowOf(w, DORA)
    expect(dora.get('[data-testid="who-state"]').text()).toBe(t('safety.stateVerified'))
    expect(dora.get('[data-testid="who-verified-by"]').text()).toBe(t('safety.verifiedBy', { names: `Ben, ${t('safety.verifiedByYou')}` }))
    expect(rowOf(w, BEN).find('[data-testid="who-replaced"]').exists()).toBe(true)

    // a failed check says nothing - no state, never the anomaly (control: the same row carries its name)
    expect(rowOf(w, CARL).find('[data-testid="who-state"]').exists()).toBe(false)
    expect(rowOf(w, CARL).text()).toContain('Carl')
  })

  it('an anomaly is never shown as verified, whatever the read hands over', async () => {
    vi.mocked(loadPeopleOverview).mockResolvedValue({ available: true, people: [
      who(ZOE, { state: 'anomaly', verifiedBy: [BEN] }),
      who(DORA, { state: 'verified', verifiedBy: [BEN] }), // control
    ] })
    const { w } = await mountPanel()
    expect(rowOf(w, ZOE).text()).not.toContain(t('safety.stateVerified'))
    expect(rowOf(w, ZOE).find('[data-testid="who-verified-by"]').exists()).toBe(false)
    expect(rowOf(w, DORA).find('[data-testid="who-verified-by"]').exists()).toBe(true)
  })

  it('the way into the compare: every row I have not verified links to the profile, and a click opens it', async () => {
    vi.mocked(loadPeopleOverview).mockResolvedValue({ available: true, people: [
      who(ANNA), who(ZOE, { state: 'anomaly' }), who(DORA, { state: 'verified', verifiedBy: [ME] }),
    ] })
    const { w, router } = await mountPanel()
    expect(rowOf(w, DORA).find('[data-testid="who-compare"]').exists()).toBe(false) // I verified Dora at her head
    expect(rowOf(w, ZOE).find('[data-testid="who-compare"]').exists()).toBe(true) // in person: the profile shows the digits
    const compare = rowOf(w, ANNA).get('[data-testid="who-compare"]')
    expect(compare.text()).toBe(t('whoIsWho.compare'))
    expect(compare.attributes('href')).toBe(`/#people/${ANNA}`)
    await compare.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe(`/#people/${ANNA}`)
  })
})

describe('when there is no list', () => {
  it('a refused pin store is said with the way to start over; a tenant this device cannot open is said; nobody else is said', async () => {
    vi.mocked(loadPeopleOverview).mockRejectedValue(new TrustStateRefused('trust_state_rolled_back', 'pins'))
    let { w } = await mountPanel()
    expect(w.text()).toContain(t('verify.trustRolledBack'))
    w.unmount()

    vi.mocked(loadPeopleOverview).mockResolvedValue({ available: false, people: [] })
    ;({ w } = await mountPanel())
    expect(w.text()).toContain(t('whoIsWho.unavailable'))
    w.unmount()

    vi.mocked(loadPeopleOverview).mockResolvedValue({ available: true, people: [] })
    ;({ w } = await mountPanel())
    expect(w.text()).toContain(t('whoIsWho.empty'))
  })

  it('a failed read offers to try again, and the retry reads again', async () => {
    vi.mocked(loadPeopleOverview).mockRejectedValueOnce(new Error('offline')).mockResolvedValue({ available: true, people: [who(ANNA)] })
    const { w } = await mountPanel()
    expect(w.text()).toContain(t('whoIsWho.loadFailed'))
    await w.get('[role="alert"] button').trigger('click')
    await flushPromises()
    expect(w.find(`[data-testid="who-${ANNA}"]`).exists()).toBe(true)
  })
})
