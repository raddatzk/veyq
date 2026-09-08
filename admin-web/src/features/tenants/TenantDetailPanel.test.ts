// @vitest-environment happy-dom
// uc.admin.create-tenant + uc.admin.suspend-tenant (web) — the per-tenant detail page: it loads one tenant by
// id, renders a PENDING tenant's owner-invite controls (reassign / resend / revoke) + the fresh invite link,
// confirm-gates revoke (delegating to the facade + routing back), maps 403/401, and carries the availability
// lever: an ACTIVE tenant offers "take offline" (confirm-gated), a SUSPENDED one its own badge, its
// explain-what-this-means notice and "bring back online" — and, deliberately, no invite controls in either.
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
  changeTenantDomain: vi.fn().mockResolvedValue(undefined),
  suspendTenant: vi.fn().mockResolvedValue(undefined),
  reactivateTenant: vi.fn().mockResolvedValue(undefined),
  tenantError: vi.fn().mockResolvedValue({ status: 0, code: null }),
}))
vi.mock('../../shared/inviteLink', () => ({
  buildInviteLink: vi.fn().mockReturnValue('https://acme.example/#invite=sek'),
  copyText: vi.fn().mockResolvedValue(true),
}))

import TenantDetailPanel from './TenantDetailPanel.vue'
import { listTenants, revokeTenant, resendInvite, suspendTenant, reactivateTenant, changeTenantDomain, tenantError } from './tenants'

const i18n = createI18n({
  legacy: false, locale: 'en', missingWarn: false, fallbackWarn: false,
  messages: {
    en: {
      tenant: {
        loading: 'Loading', notAuthorized: 'Not authorized', empty: 'Gone', loadFailed: 'Failed',
        statusPending: 'Pending', statusActive: 'Active', ownerEmail: 'Owner email', resend: 'Resend',
        reassignSubmit: 'Reassign', revoke: 'Revoke', revokeConfirm: 'sure?', actionFailed: 'x',
        resent: 'resent', reassigned: 'reassigned', inviteReissued: 'reissued',
        statusSuspended: 'Suspended', suspend: 'Take offline', reactivate: 'Bring back online',
        suspendConfirm: 'offline?', suspended: 'offline', reactivated: 'online',
        suspendedHint: 'This tenant is offline.',
        changeDomainTitle: 'Move address', changeDomainSubmit: 'Move', changeDomainHint: 'hint',
        changeDomainConfirm: 'move to {domain}?', domainChanged: 'moved',
        domainTaken: 'taken', domainNotMovable: 'offline tenants cannot move', domainInvalid: 'not an address',
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
const SUSPENDED = { tenantId: 't-3', name: 'Offline', domain: 'off.example', ownerEmail: 'o@off.example', status: 'SUSPENDED' }

const mountPanel = (tenantId = 't-1') => mount(TenantDetailPanel, { props: { tenantId }, global: { plugins: [i18n], stubs } })
const btn = (w: ReturnType<typeof mountPanel>, label: string) => w.findAll('button').find((b) => b.text() === label)!
// The move form submits (so Enter works), and the stubbed Button emits only a click — so the test drives the
// form's own submit rather than the button, which is the event the component actually listens for.
const submitMove = (w: ReturnType<typeof mountPanel>) => w.find('form.change-domain').trigger('submit')

describe('TenantDetailPanel (uc.admin.create-tenant + uc.admin.suspend-tenant, web)', () => {
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

  it('take-offline is confirm-gated and delegates to the facade, then reloads in place', async () => {
    vi.mocked(listTenants).mockResolvedValue([ACTIVE as never])
    const w = mountPanel('t-2')
    await flushPromises()

    window.confirm = () => false
    await btn(w, 'Take offline').trigger('click')
    await flushPromises()
    expect(suspendTenant).not.toHaveBeenCalled()

    window.confirm = () => true
    vi.mocked(listTenants).mockResolvedValue([{ ...ACTIVE, status: 'SUSPENDED' } as never])
    await btn(w, 'Take offline').trigger('click')
    await flushPromises()
    expect(suspendTenant).toHaveBeenCalledWith('t-2')
    // Stays on the panel (unlike revoke, which routes away) and re-renders in the new state.
    expect(push).not.toHaveBeenCalledWith('/settings')
    expect(w.text()).toContain('Suspended')
  })

  it('a SUSPENDED tenant shows its own badge + notice and offers only bring-back-online', async () => {
    vi.mocked(listTenants).mockResolvedValue([SUSPENDED as never])
    const w = mountPanel('t-3')
    await flushPromises()
    expect(w.find('.veyq-badge.danger').exists()).toBe(true)
    expect(w.text()).toContain('This tenant is offline.')
    expect(btn(w, 'Bring back online')).toBeTruthy()
    expect(w.findAll('button').find((b) => b.text() === 'Take offline')).toBeUndefined()
    expect(w.findAll('button').find((b) => b.text() === 'Revoke')).toBeUndefined()
  })

  it('bring-back-online needs no confirm and delegates to the facade', async () => {
    vi.mocked(listTenants).mockResolvedValue([SUSPENDED as never])
    const w = mountPanel('t-3')
    await flushPromises()
    // No window.confirm stub on purpose: reactivating only ever restores access, so it must not be gated —
    // if the component asked, happy-dom's unstubbed confirm would answer false and the call would not happen.
    await btn(w, 'Bring back online').trigger('click')
    await flushPromises()
    expect(reactivateTenant).toHaveBeenCalledWith('t-3')
  })

  it('a PENDING tenant offers no availability lever — it is withdrawn with revoke, not suspended', async () => {
    const w = mountPanel()
    await flushPromises()
    expect(w.findAll('button').find((b) => b.text() === 'Take offline')).toBeUndefined()
    expect(w.findAll('button').find((b) => b.text() === 'Bring back online')).toBeUndefined()
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

  it('moves an ACTIVE tenant to a new address, confirm-gated, and reloads in place', async () => {
    vi.mocked(listTenants).mockResolvedValue([ACTIVE as never])
    const w = mountPanel('t-2')
    await flushPromises()
    // Prefilled with where the tenant is now, so the field reads as "the address" and not as an empty command.
    expect((w.findAll('input')[0].element as HTMLInputElement).value).toBe('live.example')

    await w.findAll('input')[0].setValue('moved.example')
    window.confirm = () => false
    await submitMove(w)
    await flushPromises()
    expect(changeTenantDomain).not.toHaveBeenCalled()

    window.confirm = () => true
    vi.mocked(listTenants).mockResolvedValue([{ ...ACTIVE, domain: 'moved.example' } as never])
    await submitMove(w)
    await flushPromises()
    expect(changeTenantDomain).toHaveBeenCalledWith('t-2', 'moved.example')
    expect(w.text()).toContain('moved.example')
  })

  it('offers the move on a PENDING tenant too — unlike the invite controls', async () => {
    // The one tenant-admin write that is not PENDING-only, asserted from the side that would otherwise be
    // assumed: a pending tenant carries BOTH the move form and its invite controls.
    const w = mountPanel()
    await flushPromises()
    expect(btn(w, 'Move')).toBeTruthy()
    expect(btn(w, 'Resend')).toBeTruthy()
  })

  it('offers no move on a SUSPENDED tenant — the backend refuses it, so the UI does not ask', async () => {
    vi.mocked(listTenants).mockResolvedValue([SUSPENDED as never])
    const w = mountPanel('t-3')
    await flushPromises()
    expect(w.findAll('button').find((b) => b.text() === 'Move')).toBeUndefined()
    expect(btn(w, 'Bring back online')).toBeTruthy() // control: the panel rendered, it just omits this one action
  })

  it('maps the move failures apart instead of showing one generic error', async () => {
    // Three different fixes for the operator — pick another address / bring it back online / correct the form —
    // so a single "that failed" would be the wrong answer to two of them.
    vi.mocked(listTenants).mockResolvedValue([ACTIVE as never])
    const w = mountPanel('t-2')
    await flushPromises()
    window.confirm = () => true

    for (const [failure, expected] of [
      [{ status: 409, code: 'domain_taken' }, 'taken'],
      [{ status: 409, code: 'tenant_not_pending_or_active' }, 'offline tenants cannot move'],
      [{ status: 400, code: 'invalid_request' }, 'not an address'],
    ] as const) {
      await w.findAll('input')[0].setValue(`x-${expected.length}.example`)
      vi.mocked(changeTenantDomain).mockRejectedValueOnce({ response: { status: failure.status } } as never)
      vi.mocked(tenantError).mockResolvedValueOnce(failure as never)
      await submitMove(w)
      await flushPromises()
      expect(w.text()).toContain(expected)
    }
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
