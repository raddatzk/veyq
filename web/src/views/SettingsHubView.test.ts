// @vitest-environment happy-dom
// The settings hub (cr-0078, ADR 0022): the section nav is filtered by scope, deep links open a section,
// and the split view tracks index vs. detail. The three section panels are stubbed — this tests the shell, not
// the panels (they have their own tests).
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('../features/spaces/scopes', () => ({ listMyGroups: vi.fn().mockResolvedValue([]) }))
vi.mock('../features/settings/AppearancePanel.vue', () => ({ default: { template: '<div class="stub-appearance" />' } }))
vi.mock('../features/account/sessions/SessionsPanel.vue', () => ({ default: { template: '<div class="stub-sessions" />' } }))
vi.mock('../features/spaces/TenantMembersPanel.vue', () => ({ default: { template: '<div class="stub-members" />' } }))
vi.mock('../features/spaces/GroupMembersPanel.vue', () => ({ default: { template: '<div class="stub-group-members" />' } }))
vi.mock('../features/spaces/CreateGroupDialog.vue', () => ({ default: { template: '<div class="stub-create-group" />' } }))

import SettingsHubView from './SettingsHubView.vue'
import { listMyGroups } from '../features/spaces/scopes'
import { activeSpace, clearActiveSpace } from '../app/activeSpace'
import { ScopeKind } from '../api/generated/scopes'
import { realI18n, t } from '../test/i18n'

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
  stubMatchMedia(false) // narrow by default → /settings stays the list; the wide-redirect case opts in
})
afterEach(() => vi.unstubAllGlobals())

describe('SettingsHubView — section filtering by scope × role', () => {
  it('a member in the Tenant scope sees Personal + Shared space', async () => {
    activeSpace.tenantId = 't-1'
    activeSpace.kind = ScopeKind.Tenant
    const nav = navText(await mountHub())
    expect(nav).toContain(t('settings.personal'))
    expect(nav).toContain(t('members.title'))
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
