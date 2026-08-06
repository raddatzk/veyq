// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'

// The tenant list is a network call (onMounted) — hand it a single seeded tenant (a test overrides it for the
// multi-tenant overview).
vi.mock('../../features/spaces/scopes', () => ({
  listMyTenants: vi.fn().mockResolvedValue([
    { tenantId: 't-1', name: 'Familie Muster', domain: '127.0.0.1', role: 'OWNER', iconEpoch: 0 },
  ]),
  listMyGroups: vi.fn().mockResolvedValue([]),
  openMyTenantIcon: vi.fn().mockResolvedValue(null),
}))

import AppSidebar from './AppSidebar.vue'
import { listMyTenants } from '../../features/spaces/scopes'
import { activeSpace, clearActiveSpace } from '../../app/activeSpace'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages: {
    en: {
      shell: { spaces: 'Spaces', personal: 'Personal', shared: 'Shared', tenantSwitch: 'Switch space', favorites: 'Favorites', menu: 'Navigation', menuOpen: 'Open navigation' },
      members: { awaitingAccess: 'Awaiting access' },
    },
  },
})

// The overview lives in a teleporting PrimeVue Popover — stub it with a pass-through that always renders its
// slot (so the tenant list is assertable) and exposes the toggle/hide the trigger calls.
const PopoverStub = { template: '<div class="popover-stub"><slot /></div>', methods: { toggle() {}, hide() {} } }

// The mobile sheet's teleporting PrimeVue Dialog needs the PrimeVue plugin's config to render — stub it with a
// pass-through that renders its slot only when visible (also lets the mobile-sheet test drive it).
const DialogStub = { props: ['visible'], template: '<div v-if="visible" class="dialog-stub"><slot /></div>' }

function makeRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', name: 'home', component: { template: '<div />' } }],
  })
}

async function mountAt(hash: string) {
  const router = makeRouter()
  await router.replace({ name: 'home', hash })
  await router.isReady()
  const wrapper = mount(AppSidebar, {
    global: { plugins: [router, i18n], stubs: { ProfileMenu: true, Popover: PopoverStub, Dialog: DialogStub } },
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  clearActiveSpace()
  activeSpace.tenantId = 't-1' // as if the tenant space is the active one
})
afterEach(() => clearActiveSpace())

describe('AppSidebar space list', () => {
  it('lists the Tenant space (labelled with the tenant name) beside Personal', async () => {
    const wrapper = await mountAt('')
    const tenant = wrapper.get('.sidebar-tenant-space-link')
    const personal = wrapper.get('.sidebar-personal-link')
    expect(tenant.text()).toContain('Familie Muster')
    expect(personal.text()).toContain('Personal')
  })

  it('highlights the Tenant space on the token-less home', async () => {
    const wrapper = await mountAt('')
    expect(wrapper.get('.sidebar-tenant-space-link').classes()).toContain('router-link-active')
    expect(wrapper.get('.sidebar-personal-link').classes()).not.toContain('router-link-active')
  })

  it('highlights Personal on the `~me` fragment', async () => {
    const wrapper = await mountAt('#~me')
    expect(wrapper.get('.sidebar-personal-link').classes()).toContain('router-link-active')
    expect(wrapper.get('.sidebar-tenant-space-link').classes()).not.toContain('router-link-active')
  })

  it('surfaces "awaiting access" on the active tenant when the caller holds no grant yet (cr-0077)', async () => {
    activeSpace.tenantUnavailable = true // a grant-less member fell back to Personal
    const wrapper = await mountAt('')
    const awaiting = wrapper.find('.sidebar-tenant-awaiting')
    expect(awaiting.exists()).toBe(true)
    expect(awaiting.text()).toContain('Awaiting access')
  })

  it('shows the domain (no awaiting badge) once the grant is delivered', async () => {
    activeSpace.tenantUnavailable = false
    const wrapper = await mountAt('')
    expect(wrapper.find('.sidebar-tenant-awaiting').exists()).toBe(false)
  })

  // The mobile bottom-sheet nav (cr-0084 + cr-0108): the inline nav is CSS-hidden below the compact threshold,
  // so the hamburger opens a sheet with the full navigation. Post-merge the active-tenant row IS the shared-home
  // entry (no separate "Shared" item), with Personal below — mirroring the desktop merge.
  it('opens a mobile nav sheet where the active-tenant row enters the shared home (no separate Shared item)', async () => {
    const router = makeRouter()
    await router.replace({ name: 'home', hash: '' })
    await router.isReady()
    const wrapper = mount(AppSidebar, {
      global: { plugins: [router, i18n], stubs: { ProfileMenu: true, Dialog: DialogStub, Popover: PopoverStub } },
    })
    await flushPromises()

    expect(wrapper.find('.mobile-nav-sheet').exists()).toBe(false) // closed initially
    await wrapper.get('.mobile-navigation-button').trigger('click')
    await flushPromises()

    const items = wrapper.findAll('.mobile-nav-item')
    const texts = items.map((i) => i.text())
    expect(texts.some((t) => t.includes('Familie Muster'))).toBe(true) // the active tenant row
    expect(texts.some((t) => t.includes('Personal'))).toBe(true)
    expect(texts.every((t) => !t.includes('Shared'))).toBe(true) // folded into the tenant row; no standalone entry

    // The active-tenant row navigates into the shared Tenant home.
    const tenantRow = items.find((i) => i.text().includes('Familie Muster'))!
    expect(tenantRow.element.tagName).toBe('A')
    expect(tenantRow.attributes('href')).toBe('/')
  })
})

// The merged tenant switcher (cr-0108): one active-tenant row whose body enters the shared Tenant home, plus a
// chevron that opens an overview of all the member's tenants.
describe('AppSidebar tenant switcher', () => {
  it('enters the shared Tenant home from the active-tenant row', async () => {
    const wrapper = await mountAt('')
    const row = wrapper.get('.sidebar-tenant-space-link')
    expect(row.text()).toContain('Familie Muster')
    expect(row.attributes('href')).toBe('/') // token-less tenant home
    expect(row.classes()).toContain('router-link-active') // highlighted on the token-less home
  })

  it('hides the switch chevron when the member belongs to a single tenant', async () => {
    const wrapper = await mountAt('')
    expect(wrapper.find('.sidebar-tenant-switch').exists()).toBe(false)
  })

  it('shows a chevron and an overview of every tenant when there are several', async () => {
    vi.mocked(listMyTenants).mockResolvedValueOnce([
      { tenantId: 't-1', name: 'Familie Muster', domain: '127.0.0.1', role: 'OWNER', iconEpoch: 0 },
      { tenantId: 't-2', name: 'Verein Beispiel', domain: 'verein.localhost', role: 'MEMBER', iconEpoch: 0 },
    ])
    const wrapper = await mountAt('')

    expect(wrapper.find('.sidebar-tenant-switch').exists()).toBe(true)
    const overview = wrapper.findAll('.sidebar-tenant-overview-list .sidebar-tenant-link')
    expect(overview.map((b) => b.text())).toEqual([
      expect.stringContaining('Familie Muster'),
      expect.stringContaining('Verein Beispiel'),
    ])
    // The active tenant is marked (gradient avatar class) — the other is not.
    expect(overview[0].classes()).toContain('sidebar-tenant-link-active')
    expect(overview[1].classes()).not.toContain('sidebar-tenant-link-active')
  })

  it('switches to another tenant from the overview (full navigation to its domain)', async () => {
    vi.mocked(listMyTenants).mockResolvedValueOnce([
      { tenantId: 't-1', name: 'Familie Muster', domain: '127.0.0.1', role: 'OWNER', iconEpoch: 0 },
      { tenantId: 't-2', name: 'Verein Beispiel', domain: 'verein.localhost', role: 'MEMBER', iconEpoch: 0 },
    ])
    const assign = vi.spyOn(window.location, 'assign').mockImplementation(() => {})
    const wrapper = await mountAt('')

    const rows = wrapper.findAll('.sidebar-tenant-overview-list .sidebar-tenant-link')
    await rows[1].trigger('click') // the inactive tenant
    expect(assign).toHaveBeenCalledWith('https://verein.localhost')

    assign.mockRestore()
  })
})
