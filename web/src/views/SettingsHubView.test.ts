// @vitest-environment happy-dom
// The settings hub (cr-0078, ADR 0022): the section nav is filtered by scope + role, deep links open a section,
// and the split view tracks index vs. detail. The three section panels are stubbed — this tests the shell, not
// the panels (they have their own tests).
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { reactive } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'

vi.mock('../app/serverRole', () => ({
  serverRole: reactive({ canManage: false, probed: true }),
  probeServerRole: vi.fn(),
}))
vi.mock('../features/spaces/scopes', () => ({ listMyGroups: vi.fn().mockResolvedValue([]) }))
vi.mock('../features/settings/AppearancePanel.vue', () => ({ default: { template: '<div class="stub-appearance" />' } }))
vi.mock('../features/account/trustDevice/DeviceTrustPanel.vue', () => ({ default: { template: '<div class="stub-device" />' } }))
vi.mock('../features/account/sessions/SessionsPanel.vue', () => ({ default: { template: '<div class="stub-sessions" />' } }))
vi.mock('../features/spaces/TenantMembersPanel.vue', () => ({ default: { template: '<div class="stub-members" />' } }))
vi.mock('../features/spaces/GroupMembersPanel.vue', () => ({ default: { template: '<div class="stub-group-members" />' } }))
vi.mock('../features/settings/ServerAdminPanel.vue', () => ({ default: { template: '<div class="stub-server-admin" />' } }))
vi.mock('../features/spaces/CreateGroupDialog.vue', () => ({ default: { template: '<div class="stub-create-group" />' } }))

import SettingsHubView from './SettingsHubView.vue'
import { serverRole } from '../app/serverRole'
import { listMyGroups } from '../features/spaces/scopes'
import { activeSpace, clearActiveSpace } from '../app/activeSpace'
import { ScopeKind } from '../api/generated/scopes'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  missingWarn: false,
  fallbackWarn: false,
  messages: {
    en: {
      members: { title: 'Members' },
      groups: { members: 'Members', noneOwned: 'No groups yet' },
      profile: { title: 'Profile' },
      shell: { groups: 'Groups', groupCreate: 'New group' },
      serverAdmin: { navTitle: 'Server administration', title: 'Server administration', sub: 's', where: 'w', whyMoved: 'y' },
      settings: {
        title: 'Settings', back: 'Back', personal: 'Personal', tenant: 'Shared space', group: 'Group', server: 'Server',
        appearance: 'Appearance', appearanceTitle: 'A', appearanceSub: 's', device: 'This device', sessions: 'Sessions',
      },
    },
  },
})

async function mountHub(path = '/settings') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/settings/:section?', name: 'settings', component: { template: '<div />' } }],
  })
  await router.replace(path)
  await router.isReady()
  const wrapper = mount(SettingsHubView, { global: { plugins: [router, i18n] } })
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
  serverRole.canManage = false
  vi.mocked(listMyGroups).mockResolvedValue([]) // clearAllMocks keeps impls; reset the defaults per test
  stubMatchMedia(false) // narrow by default → /settings stays the list; the wide-redirect case opts in
})
afterEach(() => vi.unstubAllGlobals())

describe('SettingsHubView — section filtering by scope × role', () => {
  it('a Server Owner in the Tenant scope sees Personal + Shared space + Server', async () => {
    activeSpace.tenantId = 't-1'
    activeSpace.kind = ScopeKind.Tenant
    serverRole.canManage = true
    const nav = navText(await mountHub())
    expect(nav).toContain('Personal')
    expect(nav).toContain('Members')
    expect(nav).toContain('Server administration')
  })

  it('a plain member in the Tenant scope sees Personal + Shared space, no Server', async () => {
    activeSpace.tenantId = 't-1'
    activeSpace.kind = ScopeKind.Tenant
    serverRole.canManage = false
    const nav = navText(await mountHub())
    expect(nav).toContain('Personal')
    expect(nav).toContain('Members')
    expect(nav).not.toContain('Server administration')
  })

  it('a Group scope: entering the hub opens that group’s detail page, and the Gruppen collection lists owned groups by name', async () => {
    vi.mocked(listMyGroups).mockResolvedValue([
      { scopeId: 'g-1', tenantId: 't-1', name: 'Familie Raddatz', role: 'OWNER', deletedAt: null, purgeAfter: null, icon: null },
      { scopeId: 'g-2', tenantId: 't-1', name: 'Reise-Crew', role: 'OWNER', deletedAt: null, purgeAfter: null, icon: null },
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
      { scopeId: 'g-1', tenantId: 't-1', name: 'Familie Raddatz', role: 'OWNER', deletedAt: null, purgeAfter: null, icon: null },
      { scopeId: 'g-9', tenantId: 't-1', name: 'Reise-Crew', role: 'OWNER', deletedAt: null, purgeAfter: null, icon: null },
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

  it('with no tenant context and no server role, only Personal shows', async () => {
    activeSpace.tenantId = null
    serverRole.canManage = false
    const nav = navText(await mountHub())
    expect(nav).toContain('Personal')
    expect(nav).not.toContain('Members')
    expect(nav).not.toContain('Server administration')
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

  it('Sessions is a Personal section reachable with no scope at all (uc.account.sessions)', async () => {
    // Account-wide, not scope-bound: it gates on a session, not on a space, so an account that has entered no
    // tenant must still reach it. The unresolved-id fallback is the appearance panel, so mounting the sessions
    // stub — rather than that one — is what says the route actually resolved.
    clearActiveSpace()
    serverRole.canManage = false
    const w = await mountHub('/settings/sessions')
    expect(navText(w)).toContain('Sessions')
    expect(w.find('.stub-sessions').exists()).toBe(true)
    expect(w.find('.stub-appearance').exists()).toBe(false)
  })

  it('the Server signpost opens the panel that says administration moved to its own surface', async () => {
    // The tenant fan-out this case used to drive is not a section of this app any more (ADR 0065): it is
    // `admin-web`. What replaced it is one entry, and what matters is that a Server Owner can still REACH an
    // explanation from here — a gated nav entry that resolves to nothing would be worse than no entry.
    activeSpace.tenantId = 't-1'
    activeSpace.kind = ScopeKind.Tenant
    serverRole.canManage = true
    const w = await mountHub('/settings')
    const item = w.findAll('.settings-nav-item').find((b) => b.text().includes('Server administration'))!
    await item.trigger('click')
    await flushPromises()
    expect(w.vm.$route.params.section).toBe('server-admin')
    expect(w.find('.stub-server-admin').exists()).toBe(true)
  })
})
