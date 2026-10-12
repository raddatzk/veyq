// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'

// The tenant list is a network call (onMounted) — hand it a single seeded tenant (a test overrides it for the
// multi-tenant overview).
vi.mock('../../features/spaces/scopes', () => ({
  listMyTenants: vi.fn(),
  listMyGroups: vi.fn(),
  openMyTenantIcon: vi.fn(),
}))

// `onMounted` also asks for the caller's profile, and that one was missed when the tenant list above was
// stubbed: unmocked, every mount of this component sent a real `GET /api/account/profile` out of the suite —
// 14 of them per run, one per test, silently, because AppSidebar catches the failure and falls back (#149).
// The username is returned EMPTY on purpose: `personalScope('')` is `~me`, which is the hash these tests drive
// directly, so this stub restores today's behaviour rather than changing what the suite asserts.
vi.mock('../../features/account/profile/profile', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../features/account/profile/profile')>()),
  getMyProfile: vi.fn(),
}))

import AppSidebar from './AppSidebar.vue'
import { listMyTenants, listMyGroups, openMyTenantIcon, type GroupSpace } from '../../features/spaces/scopes'
import type { CompromiseRotation } from '../../api/generated/scopes'
import { getMyProfile } from '../../features/account/profile/profile'
import { activeSpace, clearActiveSpace } from '../../app/activeSpace'
import { treeStore } from '../../core/node/treeStore'
import type { TreeNode } from '../../core/node/tree'
import { favoritesStore } from '../../core/favorites/favoritesStore'
import { registry } from '../../core/applications/registry'
import { hashForNode } from '../../core/node/location'

import { realI18n, t } from '../../test/i18n'
import { memberProfile, myTenant, treeNode } from '../../test/models'

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
    global: { plugins: [router, realI18n()], stubs: { ProfileMenu: true, Popover: PopoverStub, Dialog: DialogStub } },
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.mocked(listMyTenants).mockResolvedValue([
    myTenant({ tenantId: 't-1', domain: '127.0.0.1' }),
  ])
  vi.mocked(listMyGroups).mockResolvedValue([])
  vi.mocked(openMyTenantIcon).mockResolvedValue(null)
  vi.mocked(getMyProfile).mockResolvedValue(memberProfile({ username: '' }))
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
    expect(personal.text()).toContain(t('shell.personal'))
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
    expect(awaiting.text()).toContain(t('members.awaitingAccess'))
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
      global: { plugins: [router, realI18n()], stubs: { ProfileMenu: true, Dialog: DialogStub, Popover: PopoverStub } },
    })
    await flushPromises()

    expect(wrapper.find('.mobile-nav-sheet').exists()).toBe(false) // closed initially
    await wrapper.get('.mobile-navigation-button').trigger('click')
    await flushPromises()

    const items = wrapper.findAll('.mobile-nav-item')
    const texts = items.map((i) => i.text())
    expect(texts.some((s) => s.includes('Familie Muster'))).toBe(true) // the active tenant row
    expect(texts.some((s) => s.includes(t('shell.personal')))).toBe(true)
    expect(texts.every((s) => !s.includes(t('shell.shared')))).toBe(true) // folded into the tenant row; no standalone entry

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
      myTenant({ tenantId: 't-1', domain: '127.0.0.1' }),
      myTenant({ tenantId: 't-2', name: 'Verein Beispiel', domain: 'verein.localhost', role: 'MEMBER' }),
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
      myTenant({ tenantId: 't-1', domain: '127.0.0.1' }),
      myTenant({ tenantId: 't-2', name: 'Verein Beispiel', domain: 'verein.localhost', role: 'MEMBER' }),
    ])
    const assign = vi.spyOn(window.location, 'assign').mockImplementation(() => {})
    const wrapper = await mountAt('')

    const rows = wrapper.findAll('.sidebar-tenant-overview-list .sidebar-tenant-link')
    await rows[1].trigger('click') // the inactive tenant
    expect(assign).toHaveBeenCalledWith('https://verein.localhost')

    assign.mockRestore()
  })
  // uc.spaces.scope-access A7: a suspended tenant stays listed — its initial (no grant, no icon), marked offline —
  // but is not enterable. The control is the same list's active row, which still carries its domain.
  it('lists a suspended tenant offline and not enterable', async () => {
    vi.mocked(listMyTenants).mockResolvedValueOnce([
      myTenant({ tenantId: 't-1', domain: '127.0.0.1' }),
      myTenant({ tenantId: 't-2', name: 'Verein Beispiel', domain: 'verein.localhost', role: 'MEMBER', status: 'SUSPENDED' }),
    ])
    const assign = vi.spyOn(window.location, 'assign').mockImplementation(() => {})
    const wrapper = await mountAt('')

    const rows = wrapper.findAll('.sidebar-tenant-overview-list .sidebar-tenant-link')
    expect(rows[1].find('.sidebar-tenant-offline').text()).toBe(t('shell.tenantOffline'))
    expect(rows[1].text()).not.toContain('verein.localhost')
    expect(rows[1].attributes('disabled')).toBeDefined()
    await rows[1].trigger('click')
    expect(assign).not.toHaveBeenCalled()
    // control: the active tenant is neither marked nor disabled
    expect(rows[0].find('.sidebar-tenant-offline').exists()).toBe(false)
    expect(rows[0].attributes('disabled')).toBeUndefined()

    assign.mockRestore()
  })
})

// The Favorites section is CORE's (inbox #409): every pinned node of the scope, each with its KIND's icon from the registry and
// its name, opened through `hashForNode`. A pinned id that no longer resolves (purged, trashed) or whose kind this client does
// not draw is skipped - never a broken row.
describe('AppSidebar favorites — every kind of pinned node, with its kind icon', () => {
  const at = (id: string, kind: string, name: string, parentId: string | null = null): TreeNode =>
    treeNode({
      id, name, kind, parentId, orderIndex: 0, epoch: 0, wrappedContentKey: 'k', wrappedMeta: null, metaVersion: 0, placementVersion: 0,
    })

  beforeEach(() => {
    treeStore.nodes = [
      at('f1', 'FOLDER', 'Reisen'),
      at('m1', 'MEMORY', 'Rom 2026', 'f1'),
      at('d1', 'FILE', 'Plan.pdf', 'f1'),
      at('b1', 'FILES_DROPBOX', 'Einreichungen'),
      at('x1', 'FUTURE_KIND', 'Unbekannt'),
    ]
  })
  afterEach(() => {
    treeStore.nodes = []
    favoritesStore.ids = new Set()
  })

  it('lists a folder, a Memory, a file and a drop box, each with its registry icon and name', async () => {
    favoritesStore.ids = new Set(['f1', 'm1', 'd1', 'b1'])
    const wrapper = await mountAt('')
    const rows = wrapper.findAll('.sidebar-favorite-link')
    expect(rows.map((r) => r.text())).toEqual(['Reisen', 'Rom 2026', 'Plan.pdf', 'Einreichungen'])
    expect(rows.map((r) => r.attributes('data-kind'))).toEqual(['FOLDER', 'MEMORY', 'FILE', 'FILES_DROPBOX'])
    for (const [i, kind] of ['FOLDER', 'MEMORY', 'FILE', 'FILES_DROPBOX'].entries()) {
      const info = registry.kind(kind)
      expect(info.supported, kind).toBe(true)
      if (info.supported) expect(rows[i].find('.sidebar-favorite-cover span').classes().join(' '), kind).toBe(info.icon)
    }
  })

  it('opens the node by its readable fragment path (hashForNode), not a hand-built one', async () => {
    favoritesStore.ids = new Set(['m1'])
    const wrapper = await mountAt('')
    const href = wrapper.get('.sidebar-favorite-link').attributes('href')
    expect(href).toContain(hashForNode(treeStore.nodes, 'm1', ''))
  })

  it('skips a pinned id that does not resolve and a kind this client does not draw', async () => {
    favoritesStore.ids = new Set(['gone', 'x1', 'm1'])
    const wrapper = await mountAt('')
    expect(wrapper.findAll('.sidebar-favorite-link').map((r) => r.text())).toEqual(['Rom 2026'])
  })

  it('renders no section at all when nothing resolves', async () => {
    favoritesStore.ids = new Set(['gone'])
    const wrapper = await mountAt('')
    expect(wrapper.find('.sidebar-favorites-section').exists()).toBe(false)
  })

  it('the mobile row carries the kind tile and the class its stylesheet has been waiting for', async () => {
    favoritesStore.ids = new Set(['d1'])
    const wrapper = await mountAt('')
    await wrapper.get('.mobile-navigation-button').trigger('click')
    await flushPromises()

    const row = wrapper.findAll('.mobile-nav-item').find((i) => i.text().includes('Plan.pdf'))!
    expect(row.classes()).toContain('mobile-nav-favorite')
    expect(row.get('.sidebar-favorite-cover span').classes()).toContain('pi')
  })
})

describe('AppSidebar — a space whose compromise rotation needs an owner is marked (uc.spaces.complete-queued-rotation)', () => {
  it('marks a group on stalled and on failed, and no other', async () => {
    const group = (scopeId: string, compromiseRotation: CompromiseRotation | null): GroupSpace =>
      ({ scopeId, tenantId: 't-1', name: scopeId, icon: null, role: 'OWNER', deletedAt: null, purgeAfter: null, compromiseRotation })
    vi.mocked(listMyGroups).mockResolvedValueOnce([group('g-failed', 'failed'), group('g-stalled', 'stalled'), group('g-fine', null)])
    const wrapper = await mountAt('')
    const link = (name: string) => wrapper.findAll('.desktop-group-link').find((a) => a.text().includes(name))!
    expect(link('g-failed').text()).toContain(t('members.compromiseRotationMark'))
    expect(link('g-stalled').text()).toContain(t('members.compromiseRotationMark'))
    expect(link('g-fine').text()).not.toContain(t('members.compromiseRotationMark'))
  })

  it('marks the active tenant row when its root needs an owner', async () => {
    vi.mocked(listMyTenants).mockResolvedValueOnce([
      myTenant({ tenantId: 't-1', domain: '127.0.0.1', compromiseRotation: 'failed' }),
    ])
    const wrapper = await mountAt('')
    expect(wrapper.get('.sidebar-tenant-space-link').text()).toContain(t('members.compromiseRotationMark'))
  })
})
