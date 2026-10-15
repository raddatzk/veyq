// @vitest-environment happy-dom
// The settings hub (cr-0078, ADR 0022): the section nav is filtered by scope, deep links open a section,
// and the split view tracks index vs. detail. The three section panels are stubbed — this tests the shell, not
// the panels (they have their own tests).
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('../features/spaces/scopes', () => ({ listMyGroups: vi.fn(), listMyTenants: vi.fn() }))
vi.mock('../features/settings/AppearancePanel.vue', () => ({ default: { template: '<div class="stub-appearance" />' } }))
vi.mock('../features/account/sessions/SessionsPanel.vue', () => ({ default: { template: '<div class="stub-sessions" />' } }))
vi.mock('../features/spaces/TenantMembersPanel.vue', () => ({ default: { template: '<div class="stub-members" />' } }))
vi.mock('../features/spaces/GroupMembersPanel.vue', () => ({ default: { template: '<div class="stub-group-members" />' } }))
vi.mock('../features/tenant/TenantMailSettingsPanel.vue', () => ({ default: { template: '<div class="stub-mail" />' } }))
vi.mock('../features/tenant/TenantStoragePanel.vue', () => ({ default: { template: '<div class="stub-storage" />' } }))
vi.mock('../features/spaces/CreateGroupDialog.vue', () => ({ default: { template: '<div class="stub-create-group" />' } }))
vi.mock('../features/account/pairing/AddDevicePanel.vue', () => ({ default: { template: '<div class="stub-add-device" />' } }))
vi.mock('../features/account/trustReset/TrustResetPanel.vue', () => ({ default: { template: '<div class="stub-trust-reset" />' } }))
vi.mock('../features/account/trustReset/refusedDocuments', () => ({ refusedDocuments: vi.fn() }))

import SettingsHubView from './SettingsHubView.vue'
import { listMyGroups, listMyTenants } from '../features/spaces/scopes'
import { refusedDocuments } from '../features/account/trustReset/refusedDocuments'
import { activeSpace, clearActiveSpace } from '../app/activeSpace'
import { ScopeKind } from '../api/generated/scopes'
import { realI18n, t } from '../test/i18n'
import { capabilityState } from '../shared/state/capabilities'
import { CORE_DEVICE_LINKING } from '../features/platform/capabilities'

async function mountHub(path = '/settings') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/settings/:section?', name: 'settings', component: { template: '<div />' } }],
  })
  await router.replace(path)
  await router.isReady()
  const wrapper = mount(SettingsHubView, { global: { plugins: [router, realI18n()] } })
  await flushPromises()
  return wrapper
}

const navText = (w: Awaited<ReturnType<typeof mountHub>>) => w.get('.settings-nav').text()

/** Stub the viewport so the wide-only /settings→section redirect is deterministic (happy-dom's default is wide). */
function stubMatchMedia(matches: boolean) {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches, media: query, onchange: null,
    addEventListener: () => {}, removeEventListener: () => {}, addListener: () => {}, removeListener: () => {},
    dispatchEvent: () => false,
  }))
}

beforeEach(() => {
  vi.clearAllMocks()
  clearActiveSpace()
  vi.mocked(listMyGroups).mockResolvedValue([]) // clearAllMocks keeps impls; reset the defaults per test
  vi.mocked(listMyTenants).mockResolvedValue([{ tenantId: 't-1', role: 'MEMBER' } as unknown as Awaited<ReturnType<typeof listMyTenants>>[number]])
  vi.mocked(refusedDocuments).mockResolvedValue([])
  stubMatchMedia(false) // narrow by default → /settings stays the list; the wide-redirect case opts in
})
afterEach(() => {
  vi.unstubAllGlobals()
  capabilityState.ids = null
})

describe('SettingsHubView — starting a refused trust list over (ADR 0028 Decision 7)', () => {
  it('offers the section only while this device refuses a document, and opens its panel', async () => {
    vi.mocked(refusedDocuments).mockResolvedValue([{ document: 'pins', code: 'trust_state_unreadable' }])
    const w = await mountHub('/settings/trust-reset')
    expect(navText(w)).toContain(t('trustReset.navTitle'))
    expect(w.find('.stub-trust-reset').exists()).toBe(true)
  })

  it('control: nothing refused — no standing reset beside key rotation and the sessions', async () => {
    const w = await mountHub()
    expect(navText(w)).toContain(t('settings.security'))
    expect(navText(w)).not.toContain(t('trustReset.navTitle'))
  })
})

const pathOf = (w: Awaited<ReturnType<typeof mountHub>>) => w.vm.$router.currentRoute.value.path

describe('SettingsHubView — a section the caller may not see leads back to /settings', () => {
  beforeEach(() => {
    activeSpace.tenantId = 't-1'
    activeSpace.kind = ScopeKind.Tenant
  })

  it('a member who opens the owner-only mail or storage address lands on /settings; an owner stays', async () => {
    const m = await mountHub('/settings/mail')
    expect(pathOf(m)).toBe('/settings')
    expect(m.find('.stub-mail').exists()).toBe(false) // the page was never mounted: it would have asked the server
    expect(pathOf(await mountHub('/settings/storage'))).toBe('/settings')
    vi.mocked(listMyTenants).mockResolvedValue([{ tenantId: 't-1', role: 'OWNER' } as unknown as Awaited<ReturnType<typeof listMyTenants>>[number]])
    expect(pathOf(await mountHub('/settings/mail'))).toBe('/settings/mail')
    expect(pathOf(await mountHub('/settings/storage'))).toBe('/settings/storage')
  })

  it('judges nothing before the tenant role is in: an owner is not bounced while the role is still being read', async () => {
    let answer: (v: never[]) => void = () => {}
    vi.mocked(listMyTenants).mockReturnValue(new Promise((r) => { answer = r as typeof answer }) as unknown as ReturnType<typeof listMyTenants>)
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/settings/:section?', name: 'settings', component: { template: '<div />' } }] })
    await router.replace('/settings/storage')
    await router.isReady()
    mount(SettingsHubView, { global: { plugins: [router, realI18n()] } })
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/settings/storage') // still waiting
    answer([{ tenantId: 't-1', role: 'OWNER' }] as never[])
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/settings/storage') // an owner: stays
  })

  it('a group page of a group the caller does not own leads back; an owned one stays', async () => {
    vi.mocked(listMyGroups).mockResolvedValue([
      { scopeId: 'g-1', tenantId: 't-1', name: 'Mine', role: 'OWNER', deletedAt: null, purgeAfter: null, icon: null, compromiseRotation: null },
    ])
    expect(pathOf(await mountHub('/settings/group-g-9'))).toBe('/settings')
    expect(pathOf(await mountHub('/settings/group-g-1'))).toBe('/settings/group-g-1')
  })

  it('adding a device needs core.device-linking: its entry and its address are there with the leaf, gone without it', async () => {
    capabilityState.ids = [CORE_DEVICE_LINKING]
    const on = await mountHub('/settings/pairing')
    expect(navText(on)).toContain(t('pairing.navTitle'))
    expect(pathOf(on)).toBe('/settings/pairing')
    expect(on.find('.stub-add-device').exists()).toBe(true)

    capabilityState.ids = [] // the control on the same predicate: the instance serves other ids, not this one
    const off = await mountHub('/settings/pairing')
    expect(navText(off)).not.toContain(t('pairing.navTitle'))
    expect(pathOf(off)).toBe('/settings')
    expect(off.find('.stub-add-device').exists()).toBe(false)
  })

  it('the trust reset page leads back unless this device refuses a document', async () => {
    expect(pathOf(await mountHub('/settings/trust-reset'))).toBe('/settings')
    vi.mocked(refusedDocuments).mockResolvedValue([{ document: 'pins', code: 'trust_state_unreadable' }])
    expect(pathOf(await mountHub('/settings/trust-reset'))).toBe('/settings/trust-reset')
  })
})

describe('SettingsHubView — section filtering by scope × role', () => {
  it('the owner-only sections (mail, storage) are in an owner\'s rail and in nobody else\'s', async () => {
    activeSpace.tenantId = 't-1'
    activeSpace.kind = ScopeKind.Tenant
    const asMember = navText(await mountHub())
    expect(asMember).toContain(t('terms.navTitle')) // terms are member-readable
    expect(asMember).not.toContain(t('mail.navTitle'))
    expect(asMember).not.toContain(t('storage.navTitle'))

    vi.mocked(listMyTenants).mockResolvedValue([{ tenantId: 't-1', role: 'OWNER' } as unknown as Awaited<ReturnType<typeof listMyTenants>>[number]])
    const asOwner = navText(await mountHub())
    expect(asOwner).toContain(t('mail.navTitle'))
    expect(asOwner).toContain(t('storage.navTitle'))
  })

  it('a tenant role that cannot be read is no owner: the owner-only sections stay out of the rail', async () => {
    activeSpace.tenantId = 't-1'
    activeSpace.kind = ScopeKind.Tenant
    vi.mocked(listMyTenants).mockRejectedValue(new Error('offline'))
    const nav = navText(await mountHub())
    expect(nav).not.toContain(t('mail.navTitle'))
    expect(nav).not.toContain(t('storage.navTitle'))
  })

  it('a member in the Tenant scope sees Personal + Shared space', async () => {
    activeSpace.tenantId = 't-1'
    activeSpace.kind = ScopeKind.Tenant
    const nav = navText(await mountHub())
    expect(nav).toContain(t('settings.personal'))
    expect(nav).toContain(t('members.title'))
  })

  it('a Group scope: entering the hub opens that group’s detail page, and the Gruppen collection lists owned groups by name', async () => {
    vi.mocked(listMyGroups).mockResolvedValue([
      { scopeId: 'g-1', tenantId: 't-1', name: 'Familie Raddatz', role: 'OWNER', deletedAt: null, purgeAfter: null, icon: null, compromiseRotation: null },
      { scopeId: 'g-2', tenantId: 't-1', name: 'Reise-Crew', role: 'OWNER', deletedAt: null, purgeAfter: null, icon: null, compromiseRotation: null },
    ])
    activeSpace.tenantId = 't-1'
    activeSpace.kind = ScopeKind.Group
    activeSpace.scopeId = 'g-1'
    const w = await mountHub()
    expect(navText(w)).toContain('Familie Raddatz') // the Gruppen collection lists owned groups by name
    expect(navText(w)).toContain('Reise-Crew')
    expect(w.find('.stub-group-members').exists()).toBe(true) // no param → the active group's own detail page
    expect(w.find('.stub-members').exists()).toBe(false) // the Tenant panel is not shown in a group
  })

  it('clicking a group in the collection routes to its per-entity detail page', async () => {
    vi.mocked(listMyGroups).mockResolvedValue([
      { scopeId: 'g-1', tenantId: 't-1', name: 'Familie Raddatz', role: 'OWNER', deletedAt: null, purgeAfter: null, icon: null, compromiseRotation: null },
      { scopeId: 'g-9', tenantId: 't-1', name: 'Reise-Crew', role: 'OWNER', deletedAt: null, purgeAfter: null, icon: null, compromiseRotation: null },
    ])
    activeSpace.tenantId = 't-1'
    activeSpace.kind = ScopeKind.Group
    activeSpace.scopeId = 'g-1' // the collection is expanded (holds the active group), so both items render
    const w = await mountHub()
    const item = w.findAll('.settings-nav-item').find((b) => b.text().includes('Reise-Crew'))!
    await item.trigger('click')
    await flushPromises()
    expect(w.vm.$route.params.section).toBe('group-g-9')
    expect(w.find('.stub-group-members').exists()).toBe(true)
  })

  it('with no tenant context, only Personal shows', async () => {
    activeSpace.tenantId = null
    const nav = navText(await mountHub())
    expect(nav).toContain(t('settings.personal'))
    expect(nav).not.toContain(t('members.title'))
  })
})

describe('SettingsHubView — split view index vs detail', () => {
  it('wide/no param redirects to the current scope section (tenant members) as an explicit detail URL', async () => {
    activeSpace.tenantId = 't-1'
    activeSpace.kind = ScopeKind.Tenant
    stubMatchMedia(true) // wide → the default section becomes the explicit /settings/<section> URL
    const w = await mountHub('/settings')
    await flushPromises()
    expect(w.find('.stub-members').exists()).toBe(true)
    expect(w.get('.settings-hub').classes()).toContain('is-detail') // redirected to /settings/tenant-members
  })

  it('narrow/no param stays the drill-down list (no redirect)', async () => {
    activeSpace.tenantId = 't-1'
    activeSpace.kind = ScopeKind.Tenant
    stubMatchMedia(false) // narrow → /settings is the list, not auto-navigated into a section
    const w = await mountHub('/settings')
    await flushPromises()
    expect(w.get('.settings-hub').classes()).toContain('is-index')
  })

  it('a deep link opens that section and marks the hub as detail', async () => {
    activeSpace.tenantId = 't-1'
    activeSpace.kind = ScopeKind.Tenant
    const w = await mountHub('/settings/appearance')
    expect(w.find('.stub-appearance').exists()).toBe(true)
    expect(w.get('.settings-hub').classes()).toContain('is-detail')
  })

  it('Devices and sessions is a Personal section reachable with no scope at all (uc.account.sessions)', async () => {
    // Account-wide, not scope-bound: it gates on a session, not on a space, so an account that has entered no
    // tenant must still reach it. The unresolved-id fallback is the appearance panel, so mounting the sessions
    // stub — rather than that one — is what says the route actually resolved.
    clearActiveSpace()
    const w = await mountHub('/settings/sessions')
    expect(navText(w)).toContain(t('settings.sessions'))
    expect(w.find('.stub-sessions').exists()).toBe(true)
    expect(w.find('.stub-appearance').exists()).toBe(false)
  })

  it('has no separate "This device" section — it was replaced, not redirected (ADR 0022)', async () => {
    // One rubric now answers both questions about a device. The old route is not kept alive as a redirect: it
    // falls through to the unresolved-id default, which is the appearance panel.
    clearActiveSpace()
    const w = await mountHub('/settings/device')
    expect(navText(w)).not.toContain(t('sessions.thisDevice'))
    expect(w.find('.stub-sessions').exists()).toBe(false)
    expect(w.find('.stub-appearance').exists()).toBe(true)
  })
})
