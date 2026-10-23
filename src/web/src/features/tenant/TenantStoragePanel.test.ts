// @vitest-environment happy-dom
// uc.sharing.share-link-storage-limit (web) — the owner-gated Storage rubric: the owner reads the tenant's usage and
// saves the per-link limit, entered in GiB and sent in bytes; a 403 read renders the read-only notice, not the editor.
// uc.admin.tenant-storage-quota (web) — the Server Owner's quota shows beside the usage when set, and not at all when null.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { httpError } from '../../test/httpError'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('./tenantStorage', async (orig) => ({
  ...(await orig<typeof import('./tenantStorage')>()),
  getTenantStorage: vi.fn(),
  updateTenantStorage: vi.fn(),
}))
vi.mock('../../shared/state/useToast', () => ({
  useToast: () => ({ success: vi.fn(), showUndo: vi.fn(), dismissUndo: vi.fn() }),
}))

import TenantStoragePanel from './TenantStoragePanel.vue'
import { getTenantStorage, updateTenantStorage, GIB } from './tenantStorage'
import { activeSpace, clearActiveSpace } from '../../app/activeSpace'
import { ScopeKind } from '../../api/generated/scopes'
import { realI18n, t } from '../../test/i18n'

const stubs = {
  VeyqButton: { props: ['label', 'loading'], emits: ['click'], template: '<button :disabled="loading" @click="$emit(\'click\')">{{ label }}</button>' },
  VeyqMessage: { props: ['tone'], template: '<div class="msg"><slot /></div>' },
  VeyqInput: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
}

async function mountPanel() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'login', component: { template: '<div/>' } }] })
  const wrapper = mount(TenantStoragePanel, { global: { plugins: [router, realI18n()], stubs } })
  await flushPromises()
  return wrapper
}

const saveButton = (w: Awaited<ReturnType<typeof mountPanel>>) =>
  w.findAll('button').find((b) => b.text() === t('storage.save'))

describe('TenantStoragePanel (uc.sharing.share-link-storage-limit, web)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    clearActiveSpace()
    activeSpace.kind = ScopeKind.Tenant
    activeSpace.scopeId = 't-1'
    activeSpace.tenantId = 't-1'
  })

  it('shows the usage in a human unit and saves the limit typed in GiB as bytes', async () => {
    vi.mocked(getTenantStorage).mockResolvedValue({ usedBytes: 3 * 1024 ** 2, quotaBytes: undefined, shareLinkLimitBytes: 10 * GIB })
    vi.mocked(updateTenantStorage).mockResolvedValue({ usedBytes: 3 * 1024 ** 2, quotaBytes: undefined, shareLinkLimitBytes: 2.5 * GIB })
    const wrapper = await mountPanel()
    expect(wrapper.text()).toContain('3 MiB')
    const input = wrapper.find('input')
    expect((input.element as HTMLInputElement).value).toBe('10') // the stored bytes, shown in GiB

    await input.setValue('2,5') // the German decimal comma is a decimal too
    await saveButton(wrapper)!.trigger('click')
    await flushPromises()
    expect(updateTenantStorage).toHaveBeenCalledWith('t-1', 2_684_354_560) // 2.5 × 1024³, in bytes on the wire
  })

  it('shows a limit under 0.01 GiB as non-zero, and saves it unchanged as the stored bytes, not as 0', async () => {
    // 107 374 B rounded to two decimals was "0" — and an unchanged save then switched link uploads off (ADR 0073 §4).
    vi.mocked(getTenantStorage).mockResolvedValue({ usedBytes: 0, quotaBytes: undefined, shareLinkLimitBytes: 107_374 })
    vi.mocked(updateTenantStorage).mockResolvedValue({ usedBytes: 0, quotaBytes: undefined, shareLinkLimitBytes: 107_374 })
    const wrapper = await mountPanel()
    expect((wrapper.find('input').element as HTMLInputElement).value).toBe('0.0001')

    await saveButton(wrapper)!.trigger('click')
    await flushPromises()
    expect(updateTenantStorage).toHaveBeenCalledWith('t-1', 107_374)
  })

  it('saves a limit the display rounds unchanged as the stored bytes, so an untouched page never drifts', async () => {
    const stored = Math.round(1.234 * GIB)
    vi.mocked(getTenantStorage).mockResolvedValue({ usedBytes: 0, quotaBytes: undefined, shareLinkLimitBytes: stored })
    vi.mocked(updateTenantStorage).mockResolvedValue({ usedBytes: 0, quotaBytes: undefined, shareLinkLimitBytes: stored })
    const wrapper = await mountPanel()
    expect((wrapper.find('input').element as HTMLInputElement).value).toBe('1.23')

    await saveButton(wrapper)!.trigger('click')
    await flushPromises()
    expect(updateTenantStorage).toHaveBeenCalledWith('t-1', stored)
  })

  it('sends 0 as link uploads off, and refuses a limit that is not a non-negative number without calling', async () => {
    vi.mocked(getTenantStorage).mockResolvedValue({ usedBytes: 0, quotaBytes: undefined, shareLinkLimitBytes: 10 * GIB })
    vi.mocked(updateTenantStorage).mockResolvedValue({ usedBytes: 0, quotaBytes: undefined, shareLinkLimitBytes: 0 })
    const wrapper = await mountPanel()
    const input = wrapper.find('input')

    await input.setValue('-1')
    await saveButton(wrapper)!.trigger('click')
    await flushPromises()
    expect(updateTenantStorage).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain(t('storage.invalidLimit'))

    await input.setValue('0')
    await saveButton(wrapper)!.trigger('click')
    await flushPromises()
    expect(updateTenantStorage).toHaveBeenCalledWith('t-1', 0)
  })

  it('shows the quota beside the usage when one is set, read-only', async () => {
    vi.mocked(getTenantStorage).mockResolvedValue({ usedBytes: 3 * 1024 ** 2, quotaBytes: 5 * GIB, shareLinkLimitBytes: 10 * GIB })
    const wrapper = await mountPanel()
    expect(wrapper.text()).toContain(t('storage.quota'))
    expect(wrapper.text()).toContain('5 GiB') // the storage panel counts in powers of two, and says so
    expect(wrapper.findAll('input')).toHaveLength(1) // the per-link limit only: the owner cannot edit the quota
  })

  it('omits the quota when none is set', async () => {
    vi.mocked(getTenantStorage).mockResolvedValue({ usedBytes: 3 * 1024 ** 2, quotaBytes: undefined, shareLinkLimitBytes: 10 * GIB })
    const wrapper = await mountPanel()
    expect(wrapper.text()).toContain('3 MiB') // control: the panel rendered its figures
    expect(wrapper.text()).not.toContain(t('storage.quota'))
  })

  it('renders a read-only notice (not the editor) when the read is owner-gated 403', async () => {
    vi.mocked(getTenantStorage).mockRejectedValue(httpError(403, 'not_tenant_owner'))
    const wrapper = await mountPanel()
    expect(wrapper.text()).toContain(t('storage.notOwner'))
    expect(saveButton(wrapper)).toBeUndefined()
    expect(wrapper.find('input').exists()).toBe(false)
  })
})
