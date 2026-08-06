// @vitest-environment happy-dom
// uc.admin.create-tenant (web, cr-0087) — the per-tenant detail page: it loads one tenant by id, renders a
// PENDING tenant's owner-invite controls (reassign / resend / revoke) + the fresh invite link, shows an ACTIVE
// tenant with no controls, confirm-gates revoke (delegating to the facade + routing back), and maps 403/401.
// Component acceptance for the invite-link-handoff + per-tenant admin, split out of the old TenantAdminPanel.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))

vi.mock('./tenants', () => ({
  listTenants: vi.fn(),
  resendInvite: vi.fn().mockResolvedValue({ bootstrapSecret: 'sek' }),
  reassignInvite: vi.fn().mockResolvedValue({ bootstrapSecret: 'sek' }),
  revokeTenant: vi.fn().mockResolvedValue(undefined),
  tenantError: vi.fn().mockResolvedValue({ status: 0, code: null }),
}))
vi.mock('../../../shared/inviteLink', () => ({
  buildInviteLink: vi.fn().mockReturnValue('https://acme.example/#invite=sek'),
  copyText: vi.fn().mockResolvedValue(true),
}))

import TenantDetailPanel from './TenantDetailPanel.vue'
import { listTenants, revokeTenant, resendInvite } from './tenants'

const i18n = createI18n({
  legacy: false, locale: 'en', missingWarn: false, fallbackWarn: false,
  messages: {
    en: {
      tenant: {
        loading: 'Loading', notAuthorized: 'Not authorized', empty: 'Gone', loadFailed: 'Failed',
        statusPending: 'Pending', statusActive: 'Active', ownerEmail: 'Owner email', resend: 'Resend',
        reassignSubmit: 'Reassign', revoke: 'Revoke', revokeConfirm: 'sure?', actionFailed: 'x',
        resent: 'resent', reassigned: 'reassigned', inviteReissued: 'reissued',
      },
      members: { copyLinkIntro: 'copy', copyLink: 'link', copied: 'copied' },
      common: { done: 'Done' },
    },
  },
})

const stubs = {
  InputText: { props: ['modelValue'], template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  Button: { props: ['label', 'icon', 'ariaLabel'], template: '<button :aria-label="ariaLabel" @click="$emit(\'click\', $event)">{{ label }}</button>' },
  Message: { template: '<div class="msg"><slot /></div>' },
}

const PENDING = { tenantId: 't-1', name: 'Acme', domain: 'acme.example', ownerEmail: 'owner@acme.example', status: 'PENDING' }
const ACTIVE = { tenantId: 't-2', name: 'Live', domain: 'live.example', ownerEmail: 'o@live.example', status: 'ACTIVE' }

const mountPanel = (tenantId = 't-1') => mount(TenantDetailPanel, { props: { tenantId }, global: { plugins: [i18n], stubs } })
const btn = (w: ReturnType<typeof mountPanel>, label: string) => w.findAll('button').find((b) => b.text() === label)!

describe('TenantDetailPanel (uc.admin.create-tenant, web)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listTenants).mockResolvedValue([PENDING as never])
  })

  it('renders a PENDING tenant with its invite controls (reassign / resend / revoke)', async () => {
    const w = mountPanel()
    await flushPromises()
    expect(w.text()).toContain('Acme')
    expect(w.find('.veyq-badge.warning').exists()).toBe(true)
    expect(btn(w, 'Resend')).toBeTruthy()
    expect(btn(w, 'Reassign')).toBeTruthy()
    expect(btn(w, 'Revoke')).toBeTruthy()
  })

  it('an ACTIVE tenant shows the status badge but no invite controls', async () => {
    vi.mocked(listTenants).mockResolvedValue([ACTIVE as never])
    const w = mountPanel('t-2')
    await flushPromises()
    expect(w.find('.veyq-badge.success').exists()).toBe(true)
    expect(w.findAll('button').find((b) => b.text() === 'Revoke')).toBeUndefined()
  })

  it('resend re-mints the invite and reveals the copyable link (invite-link-handoff)', async () => {
    const w = mountPanel()
    await flushPromises()
    await btn(w, 'Resend').trigger('click')
    await flushPromises()
    expect(resendInvite).toHaveBeenCalledWith('t-1')
    expect(w.find('input[readonly]').exists()).toBe(true) // the invite-link field
  })

  it('revoke is confirm-gated, delegates to the facade, and routes back to the hub', async () => {
    const w = mountPanel()
    await flushPromises()

    window.confirm = () => false
    await btn(w, 'Revoke').trigger('click')
    await flushPromises()
    expect(revokeTenant).not.toHaveBeenCalled()

    window.confirm = () => true
    await btn(w, 'Revoke').trigger('click')
    await flushPromises()
    expect(revokeTenant).toHaveBeenCalledWith('t-1')
    expect(push).toHaveBeenCalledWith('/settings')
  })

  it('shows a not-authorized state on a 403', async () => {
    // A real generated-client failure carries `.response.status`; the panel classifies it via `httpStatus`.
    vi.mocked(listTenants).mockRejectedValueOnce({ response: { status: 403 } } as never)
    const w = mountPanel()
    await flushPromises()
    expect(w.find('.msg').exists()).toBe(true)
    expect(w.text()).not.toContain('Acme')
  })

  it('redirects to login on a 401', async () => {
    vi.mocked(listTenants).mockRejectedValueOnce({ response: { status: 401 } } as never)
    mountPanel()
    await flushPromises()
    expect(push).toHaveBeenCalledWith({ name: 'login' })
  })
})
