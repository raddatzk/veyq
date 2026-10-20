// @vitest-environment happy-dom
// The ROOT Server-Owner gate (ADR 0065) — the artifact this package had to reconstruct rather than lift.
//
// In `src/web/` there was nothing to lift: `probeServerRole()` ran at data-fetch time and decided whether to OFFER
// a settings entry, so a non-owner simply never saw a link and there was no refusal surface to test. Here `/`
// IS the admin surface, so it has to answer the question, and the answer has to be readable. That is what
// these cases pin — one per verdict the backend can hand back:
//
//   403 -> a refusal a person can read, and NO console behind it
//   401 -> back to sign-in, saying the session ended (it lapsed between boot and mount; `app/sessionEnd.ts`)
//   200 -> the console
//
// Deliberately asserted through `listTenants` rejecting, not through a stubbed verdict: the gate's whole claim
// is that it mirrors the BACKEND's authorization rather than inventing one, so the fixture has to be the
// backend's own status code.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'

vi.mock('../features/tenants/tenants', () => ({ listTenants: vi.fn() }))
vi.mock('../features/auth/login', () => ({ logout: vi.fn() }))
// Stubbed with the real component's PROPS/EMITS surface, not a bare div: the case below asserts what the
// parent does to `visible`, which a prop-less stub cannot carry.
vi.mock('../features/tenants/CreateTenantDialog.vue', () => ({
  default: {
    name: 'CreateTenantDialog',
    props: { visible: { type: Boolean, required: true }, storeBlocked: { type: Boolean, default: false } },
    emits: ['created', 'update:visible', 'openStore'],
    template: '<div class="stub-create" />',
  },
}))
// The real `storeReady` stays: the gate's rule is what is asserted. Only the network read is stubbed.
vi.mock('../features/instance/store', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../features/instance/store')>()),
  getInstanceStore: vi.fn(),
}))
vi.mock('../features/instance/StorePanel.vue', () => ({ default: { template: '<div class="stub-store" />' } }))
vi.mock('../features/tenants/TenantDetailPanel.vue', () => ({
  default: { name: 'TenantDetailPanel', emits: ['revoked', 'changed'], template: '<button class="stub-detail" @click="$emit(\'revoked\', \'tn-7\')" />' },
}))
vi.mock('../features/instance/ReleaseVersionPanel.vue', () => ({ default: { template: '<div class="stub-release" />' } }))
vi.mock('../features/instance/CapabilitiesPanel.vue', () => ({ default: { template: '<div class="stub-capabilities" />' } }))
vi.mock('../features/instance/ExternalServicesPanel.vue', () => ({ default: { template: '<div class="stub-services" />' } }))
vi.mock('../features/instance/ReleaseNoticesPanel.vue', () => ({ default: { template: '<div class="stub-notices" />' } }))
vi.mock('../features/instance/InstanceMailPanel.vue', () => ({ default: { template: '<div class="stub-mail" />' } }))
vi.mock('../features/instance/ModerationPanel.vue', () => ({
  default: { emits: ['openTenant'], template: '<button class="stub-moderation" @click="$emit(\'openTenant\', \'tn-9\')" />' },
}))
vi.mock('../features/instance/GuestWriteRecordPanel.vue', () => ({ default: { template: '<div class="stub-guest-write-record" />' } }))
vi.mock('../features/instance/BackupRetentionPanel.vue', () => ({ default: { template: '<div class="stub-backup-retention" />' } }))
vi.mock('../features/instance/OrderPanel.vue', () => ({ default: { template: '<div class="stub-orders" />' } }))
vi.mock('../features/instance/AccountsPanel.vue', () => ({
  default: { emits: ['changed'], template: '<button class="stub-accounts" @click="$emit(\'changed\')" />' },
}))

import AdminConsoleView from './AdminConsoleView.vue'
import { listTenants } from '../features/tenants/tenants'
import { logout } from '../features/auth/login'
import { getInstanceStore } from '../features/instance/store'
import { resetServerRole } from '../app/serverRole'
import { httpError } from '../test/httpError'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  missingWarn: false,
  fallbackWarn: false,
  messages: {
    en: {
      admin: {
        title: 'Server administration', sub: 's', signOut: 'Sign out', tenants: 'Tenants', server: 'Server',
        forbidden: 'This account is not the Server Owner.', forbiddenSub: 'reason', unreachable: 'unreachable',
        noTenants: 'No tenants yet.', loading: 'Loading…',
        storageOfQuota: '{used} of {quota}', storageNoQuota: '{used}, no quota', ownerless: 'No owner',
      },
      accounts: { navTitle: 'Accounts' },
      tenant: { create: 'Create' },
      release: { navTitle: 'Release' },
      capabilities: { navTitle: 'Capabilities' },
      services: { navTitle: 'External services' },
      instanceMail: { navTitle: 'Mail' },
      moderation: { navTitle: 'Moderation' },
    },
  },
})

async function mountConsole() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'console', component: { template: '<div />' } },
      { path: '/login', name: 'login', component: { template: '<div />' } },
    ],
  })
  await router.replace('/')
  await router.isReady()
  const w = mount(AdminConsoleView, { global: { plugins: [router, i18n] } })
  await flushPromises()
  return { w, router }
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(logout).mockResolvedValue(undefined)
  vi.mocked(getInstanceStore).mockResolvedValue({ bundledAvailable: false, blobsStored: false, setting: undefined, lastCheck: { passed: true, checkedAt: new Date(), steps: [] }, pending: undefined })
  resetServerRole()
})

describe('AdminConsoleView — the root Server-Owner gate', () => {
  it('refuses a non-Server-Owner session in words, and renders no console behind the refusal', async () => {
    vi.mocked(listTenants).mockRejectedValue(httpError(403))
    const { w } = await mountConsole()
    expect(w.text()).toContain('This account is not the Server Owner.')
    // The point of the case: not a blank page, not a crash, and not a console that merely failed to load —
    // the nav is absent, so there is nothing to click at and nothing that looks like it should have worked.
    expect(w.find('.admin-shell__nav').exists()).toBe(false)
    expect(w.find('.stub-detail').exists()).toBe(false)
  })

  it('sends a lapsed session to sign-in rather than showing it a refusal it cannot act on', async () => {
    vi.mocked(listTenants).mockRejectedValue(httpError(401))
    const { w, router } = await mountConsole()
    expect(router.currentRoute.value.name).toBe('login')
    expect(w.text()).not.toContain('This account is not the Server Owner.')
  })

  it('says the instance is unreachable rather than blaming the account', async () => {
    vi.mocked(listTenants).mockRejectedValue(new Error('network'))
    const { w } = await mountConsole()
    expect(w.text()).toContain('unreachable')
    expect(w.text()).not.toContain('This account is not the Server Owner.')
  })

  it('renders the console for the Server Owner, listing the tenants it administers', async () => {
    vi.mocked(listTenants).mockResolvedValue([
      { tenantId: 'tn-7', name: 'Acme', domain: 'acme.example', ownerEmail: 'o@acme.example', status: 'PENDING', storageUsedBytes: 0, storageQuotaBytes: undefined, ownerless: false, deletionDueAt: undefined },
    ])
    const { w } = await mountConsole()
    expect(w.find('.admin-shell__nav').exists()).toBe(true)
    expect(w.text()).toContain('Acme')
    expect(w.text()).not.toContain('This account is not the Server Owner.')
  })

  // uc.admin.tenant-storage-quota: the list shows each tenant's usage beside its quota, or that it has none.
  it('shows each tenant’s storage usage beside its quota in the list', async () => {
    vi.mocked(listTenants).mockResolvedValue([
      { tenantId: 'tn-1', name: 'Capped', domain: 'c.example', ownerEmail: 'o@c.example', status: 'ACTIVE', storageUsedBytes: 3 * 1024 ** 2, storageQuotaBytes: 10 * 1024 ** 3, ownerless: false, deletionDueAt: undefined },
      { tenantId: 'tn-2', name: 'Open', domain: 'o.example', ownerEmail: 'o@o.example', status: 'ACTIVE', storageUsedBytes: 0, storageQuotaBytes: undefined, ownerless: false, deletionDueAt: undefined },
    ])
    const { w } = await mountConsole()
    const lines = w.findAll('.admin-shell__storage').map((s) => s.text())
    expect(lines).toEqual(['3 MB of 10 GB', '0 B, no quota'])
  })

  it('opens a tenant’s detail page from the list', async () => {
    vi.mocked(listTenants).mockResolvedValue([
      { tenantId: 'tn-7', name: 'Acme', domain: 'acme.example', ownerEmail: 'o@acme.example', status: 'PENDING', storageUsedBytes: 0, storageQuotaBytes: undefined, ownerless: false, deletionDueAt: undefined },
    ])
    const { w } = await mountConsole()
    const item = w.findAll('.admin-shell__nav button').find((b) => b.text().includes('Acme'))!
    await item.trigger('click')
    await flushPromises()
    expect(w.find('.stub-detail').exists()).toBe(true)
  })

  it('closes a revoked tenant’s panel and reads the list again', async () => {
    const acme = { tenantId: 'tn-7', name: 'Acme', domain: 'acme.example', ownerEmail: 'o@acme.example', status: 'PENDING' as const, storageUsedBytes: 0, storageQuotaBytes: undefined, ownerless: false, deletionDueAt: undefined }
    vi.mocked(listTenants).mockResolvedValue([acme])
    const { w } = await mountConsole()
    await w.findAll('.admin-shell__nav button').find((b) => b.text().includes('Acme'))!.trigger('click')
    await flushPromises()
    vi.mocked(listTenants).mockResolvedValue([])
    await w.find('.stub-detail').trigger('click') // the panel reports the revoke
    await flushPromises()
    expect(w.find('.stub-detail').exists()).toBe(false)
    expect(w.text()).not.toContain('Acme')
  })

  it('opens Server → Capabilities from the nav in place of the open tenant (uc.admin.switch-capabilities)', async () => {
    vi.mocked(listTenants).mockResolvedValue([
      { tenantId: 'tn-7', name: 'Acme', domain: 'acme.example', ownerEmail: 'o@acme.example', status: 'PENDING', storageUsedBytes: 0, storageQuotaBytes: undefined, ownerless: false, deletionDueAt: undefined },
    ])
    const { w } = await mountConsole()
    await w.findAll('.admin-shell__nav button').find((b) => b.text().includes('Acme'))!.trigger('click')
    await w.findAll('.admin-shell__nav button').find((b) => b.text().includes('Capabilities'))!.trigger('click')
    await flushPromises()
    expect(w.find('.stub-capabilities').exists()).toBe(true)
    expect(w.find('.stub-detail').exists()).toBe(false)
  })

  it('opens Server → External services from the nav, one rubric at a time (uc.admin.configure-external-services)', async () => {
    vi.mocked(listTenants).mockResolvedValue([])
    const { w } = await mountConsole()
    await w.findAll('.admin-shell__nav button').find((b) => b.text().includes('Capabilities'))!.trigger('click')
    await w.findAll('.admin-shell__nav button').find((b) => b.text().includes('External services'))!.trigger('click')
    await flushPromises()
    expect(w.find('.stub-services').exists()).toBe(true)
    expect(w.find('.stub-capabilities').exists()).toBe(false)
  })

  it('shows the release check under Server → Release, and opens Server → Mail (uc.admin.learn-of-new-releases)', async () => {
    vi.mocked(listTenants).mockResolvedValue([])
    const { w } = await mountConsole()
    await w.findAll('.admin-shell__nav button').find((b) => b.text().includes('Release'))!.trigger('click')
    await flushPromises()
    expect(w.find('.stub-release').exists()).toBe(true)
    expect(w.find('.stub-notices').exists()).toBe(true)
    await w.findAll('.admin-shell__nav button').find((b) => b.text().includes('Mail'))!.trigger('click')
    await flushPromises()
    expect(w.find('.stub-mail').exists()).toBe(true)
    expect(w.find('.stub-notices').exists()).toBe(false)
  })

  it('opens Server → Moderation from the nav, one rubric at a time (uc.moderation.appoint-moderators)', async () => {
    vi.mocked(listTenants).mockResolvedValue([])
    const { w } = await mountConsole()
    await w.findAll('.admin-shell__nav button').find((b) => b.text().includes('Mail'))!.trigger('click')
    await w.findAll('.admin-shell__nav button').find((b) => b.text().includes('Moderation'))!.trigger('click')
    await flushPromises()
    expect(w.find('.stub-moderation').exists()).toBe(true)
    expect(w.find('.stub-orders').exists()).toBe(true) // an authority's order, below the roster
    expect(w.find('.stub-guest-write-record').exists()).toBe(true) // the record's retention, below the roster
    expect(w.find('.stub-backup-retention').exists()).toBe(true) // B, beside it (ADR 0056 decision 8)
    expect(w.find('.stub-mail').exists()).toBe(false)
  })

  // uc.account.erase-account: the Server Owner's door to an erasure is its own rubric under Server.
  it('opens Server → Accounts from the nav, one rubric at a time, and reads the tenant list again on a change', async () => {
    vi.mocked(listTenants).mockResolvedValue([])
    const { w } = await mountConsole()
    await w.findAll('.admin-shell__nav button').find((b) => b.text().includes('Moderation'))!.trigger('click')
    await w.findAll('.admin-shell__nav button').find((b) => b.text().includes('Accounts'))!.trigger('click')
    await flushPromises()
    expect(w.find('.stub-accounts').exists()).toBe(true)
    expect(w.find('.stub-moderation').exists()).toBe(false)

    // an erasure left a last owner's tenant ownerless: the list is read again, and marks it
    vi.mocked(listTenants).mockResolvedValue([
      { tenantId: 'tn-1', name: 'Orphan', domain: 'o.example', ownerEmail: 'o@o.example', status: 'ACTIVE', storageUsedBytes: 0, storageQuotaBytes: undefined, ownerless: true, deletionDueAt: undefined },
    ])
    await w.find('.stub-accounts').trigger('click')
    await flushPromises()
    expect(w.find('.admin-shell__ownerless').exists()).toBe(true)
    expect(w.find('.stub-accounts').exists()).toBe(true) // the panel stays open
  })

  // ADR 0101 decision 9: an ownerless tenant is marked in the list, so the Server Owner finds it without opening each.
  it('marks an ownerless tenant in the list, and only that one', async () => {
    vi.mocked(listTenants).mockResolvedValue([
      { tenantId: 'tn-1', name: 'Orphan', domain: 'o.example', ownerEmail: 'o@o.example', status: 'ACTIVE', storageUsedBytes: 0, storageQuotaBytes: undefined, ownerless: true, deletionDueAt: undefined },
      { tenantId: 'tn-2', name: 'Owned', domain: 'w.example', ownerEmail: 'o@w.example', status: 'ACTIVE', storageUsedBytes: 0, storageQuotaBytes: undefined, ownerless: false, deletionDueAt: undefined },
    ])
    const { w } = await mountConsole()
    const item = (name: string) => w.findAll('.admin-shell__nav button').find((b) => b.text().includes(name))!
    expect(item('Orphan').find('.admin-shell__ownerless').text()).toBe('No owner')
    expect(item('Owned').find('.admin-shell__ownerless').exists()).toBe(false)
  })

  it('reads the tenant list again when the tenant page reports a change (a deletion, or its stop)', async () => {
    vi.mocked(listTenants).mockResolvedValue([
      { tenantId: 'tn-7', name: 'Acme', domain: 'acme.example', ownerEmail: 'o@acme.example', status: 'ACTIVE', storageUsedBytes: 0, storageQuotaBytes: undefined, ownerless: false, deletionDueAt: undefined },
    ])
    const { w } = await mountConsole()
    await w.findAll('.admin-shell__nav button').find((b) => b.text().includes('Acme'))!.trigger('click')
    await flushPromises()
    vi.mocked(listTenants).mockClear()
    w.findComponent({ name: 'TenantDetailPanel' }).vm.$emit('changed')
    await flushPromises()
    expect(listTenants).toHaveBeenCalledOnce()
    expect(w.find('.stub-detail').exists()).toBe(true) // the panel stays open
  })

  // uc.admin.suspend-tenant A4: a proposal to take a tenant offline is taken on that tenant's own page, where the
  // suspension already lives — the moderation panel asks, the console opens it in its place.
  it('opens the tenant a take-offline proposal names when the moderation panel asks for it', async () => {
    vi.mocked(listTenants).mockResolvedValue([])
    const { w } = await mountConsole()
    await w.findAll('.admin-shell__nav button').find((b) => b.text().includes('Moderation'))!.trigger('click')
    await flushPromises()
    await w.find('.stub-moderation').trigger('click') // the panel's "open the tenant"
    await flushPromises()
    expect(w.find('.stub-moderation').exists()).toBe(false)
    expect(w.find('.stub-detail').attributes('tenant-id')).toBe('tn-9')
  })
})

describe('AdminConsoleView — creating a tenant', () => {
  it('refreshes the list WITHOUT closing the dialog, so the invite link survives', async () => {
    // Regression, found by driving the real container: a PENDING tenant has no mail settings by construction,
    // so the copy-link the dialog switches to on success is the ONLY way its invite reaches the owner
    // (ADR 0055 §3). An `@created` handler that closes the dialog refreshes the list and throws that away.
    vi.mocked(listTenants).mockResolvedValue([])
    const { w } = await mountConsole()

    const create = w.findAll('.admin-shell__nav button').find((b) => b.text().includes('Create'))!
    await create.trigger('click')
    expect(w.findComponent({ name: 'CreateTenantDialog' }).props('visible')).toBe(true)

    vi.mocked(listTenants).mockResolvedValue([
      { tenantId: 'tn-9', name: 'Fresh', domain: 'fresh.example', ownerEmail: 'o@fresh.example', status: 'PENDING', storageUsedBytes: 0, storageQuotaBytes: undefined, ownerless: false, deletionDueAt: undefined },
    ])
    w.findComponent({ name: 'CreateTenantDialog' }).vm.$emit('created')
    await flushPromises()

    expect(w.text()).toContain('Fresh')                                                   // the list refreshed
    expect(w.findComponent({ name: 'CreateTenantDialog' }).props('visible')).toBe(true)   // and stayed open
  })
})

describe('AdminConsoleView — a tenant waits for a checked store (ADR 0089 decision 6)', () => {
  const dialog = (w: Awaited<ReturnType<typeof mountConsole>>['w']) => w.findComponent({ name: 'CreateTenantDialog' })

  it('closes tenant creation while the store has not passed its check, and opens it once it has', async () => {
    vi.mocked(listTenants).mockResolvedValue([])
    vi.mocked(getInstanceStore).mockResolvedValue({ bundledAvailable: true, blobsStored: false, setting: undefined, lastCheck: undefined, pending: undefined })
    const blocked = await mountConsole()
    expect(dialog(blocked.w).props('storeBlocked')).toBe(true)

    // the paired case, same predicate: a passed check is the one thing that opens it
    resetServerRole()
    vi.mocked(getInstanceStore).mockResolvedValue({ bundledAvailable: true, blobsStored: false, setting: undefined, lastCheck: { passed: true, checkedAt: new Date(), steps: [] }, pending: undefined })
    const open = await mountConsole()
    expect(dialog(open.w).props('storeBlocked')).toBe(false)
  })

  it('does not block on a failed READ of the store — the server is the authority and refuses on its own', async () => {
    vi.mocked(listTenants).mockResolvedValue([])
    vi.mocked(getInstanceStore).mockRejectedValue(new Error('network'))
    const { w } = await mountConsole()
    expect(dialog(w).props('storeBlocked')).toBe(false)
  })

  it('takes the owner to the store page when the dialog asks for it', async () => {
    vi.mocked(listTenants).mockResolvedValue([])
    vi.mocked(getInstanceStore).mockResolvedValue({ bundledAvailable: true, blobsStored: false, setting: undefined, lastCheck: undefined, pending: undefined })
    const { w } = await mountConsole()
    await w.findAll('.admin-shell__nav button').find((b) => b.text().includes('Create'))!.trigger('click')
    dialog(w).vm.$emit('openStore')
    await flushPromises()
    expect(w.find('.stub-store').exists()).toBe(true)
  })
})
