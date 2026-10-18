// @vitest-environment happy-dom
// uc.admin.create-tenant + uc.admin.suspend-tenant (web) — the per-tenant detail page: it loads one tenant by
// id, renders a PENDING tenant's owner-invite controls (reassign / resend / revoke) + the fresh invite link,
// confirm-gates revoke (delegating to the facade + routing back), maps 403/401, and carries the availability
// lever: an ACTIVE tenant offers "take offline" (confirm-gated), a SUSPENDED one its own badge, its
// explain-what-this-means notice and "bring back online" — and, deliberately, no invite controls in either.
// uc.admin.tenant-storage-quota: the panel shows usage beside the quota, and sets (GiB in, bytes out) or clears it.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

const push = vi.fn()
const replace = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push, replace }) }))

// The real `GIB`, `parseQuotaGib` and `formatQuotaGib`: the conversion is what the quota cases assert, so it must not be stubbed away.
vi.mock('./tenants', async (importOriginal) => ({
  GIB: (await importOriginal<typeof import('./tenants')>()).GIB,
  parseQuotaGib: (await importOriginal<typeof import('./tenants')>()).parseQuotaGib,
  formatQuotaGib: (await importOriginal<typeof import('./tenants')>()).formatQuotaGib,
  setTenantStorageQuota: vi.fn(),
  listTenants: vi.fn(),
  resendInvite: vi.fn(),
  reassignInvite: vi.fn(),
  revokeTenant: vi.fn(),
  changeTenantDomain: vi.fn(),
  suspendTenant: vi.fn(),
  reactivateTenant: vi.fn(),
  tenantError: vi.fn(),
}))
vi.mock('../../shared/inviteLink', () => ({
  buildInviteLink: vi.fn().mockReturnValue('https://acme.example/#invite=sek'),
  copyText: vi.fn().mockResolvedValue(true),
}))
// Confirmation is the console's own dialog (cr-admin-web-own-confirm-dialog), mounted once in App.vue: the
// panel awaits `ask()` and this answers it. Stubbing `window.confirm` would now pass every one of the
// confirm-gating cases below by confirming nothing — which is why `no-alert` is not exempted for tests.
const { confirmAsk } = vi.hoisted(() => ({ confirmAsk: vi.fn() }))
vi.mock('../../shared/state/useConfirm', () => ({ useConfirm: () => ({ ask: confirmAsk }) }))
vi.mock('../instance/stepStatement', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../instance/stepStatement')>()),
  stepStatement: vi.fn(),
}))

import TenantDetailPanel from './TenantDetailPanel.vue'
import { listTenants, revokeTenant, resendInvite, reassignInvite, suspendTenant, reactivateTenant, changeTenantDomain, tenantError, setTenantStorageQuota } from './tenants'
import type { TenantInviteState, TenantSummary } from './tenants'
import { httpError } from '../../test/httpError'
import { stepStatement } from '../instance/stepStatement'
import { session, setTokens } from '../../app/session'

const i18n = createI18n({
  legacy: false, locale: 'en', missingWarn: false, fallbackWarn: false,
  messages: {
    en: {
      tenant: {
        loading: 'Loading', notAuthorized: 'Not authorized', empty: 'Gone', loadFailed: 'Failed',
        statusPending: 'Pending', statusActive: 'Active', ownerEmail: 'Owner email', resend: 'Resend',
        reassignSubmit: 'Reassign', revoke: 'Revoke', revokeConfirm: 'sure?', actionFailed: 'x',
        revokeConfirmTitle: 'Revoke this pending tenant',
        resent: 'resent', reassigned: 'reassigned', inviteReissued: 'reissued',
        statusSuspended: 'Suspended', suspend: 'Take offline', reactivate: 'Bring back online',
        suspendConfirm: 'offline?', suspendConfirmTitle: 'Take this tenant offline',
        suspended: 'offline', reactivated: 'online',
        suspendedHint: 'This tenant is offline.',
        changeDomainTitle: 'Move address', changeDomainSubmit: 'Move', changeDomainHint: 'hint',
        changeDomainConfirm: 'move to {domain}?', changeDomainConfirmTitle: 'Move this tenant to a new address',
        domainChanged: 'moved',
        domainTaken: 'taken', domainNotMovable: 'offline tenants cannot move', domainInvalid: 'not an address',
        storageUsed: 'Used', quota: 'Quota', quotaNone: 'none', quotaInput: 'Quota (GiB)', quotaHint: 'hint',
        quotaSave: 'Save quota', quotaClear: 'Remove quota', quotaSaved: 'saved', quotaCleared: 'cleared',
        quotaInvalid: 'not a quota',
      },
      members: { copyLinkIntro: 'copy', copyLink: 'link', copied: 'copied' },
      common: { done: 'Done', cancel: 'Cancel' },
    },
  },
})

const stubs = {
  InputText: { props: ['modelValue'], template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  // `emits` is load-bearing: without it the parent's @click ALSO arrives as a native fallthrough listener, so
  // one trigger runs the handler twice. That was invisible while confirmation was `window.confirm` (both runs
  // got the same answer) and is not once a declined `ask()` is answered only ONCE — the second run would then
  // read the default "yes" and perform the very mutation the case asserts was refused.
  Button: { props: ['label', 'icon', 'ariaLabel'], emits: ['click'], template: '<button :aria-label="ariaLabel" @click="$emit(\'click\', $event)">{{ label }}</button>' },
  Message: { template: '<div class="msg"><slot /></div>' },
}

const NO_QUOTA = { storageUsedBytes: 0, storageQuotaBytes: null }
const PENDING: TenantSummary = { tenantId: 't-1', name: 'Acme', domain: 'acme.example', ownerEmail: 'owner@acme.example', status: 'PENDING', ...NO_QUOTA }
const ACTIVE: TenantSummary = { tenantId: 't-2', name: 'Live', domain: 'live.example', ownerEmail: 'o@live.example', status: 'ACTIVE', ...NO_QUOTA }
const SUSPENDED: TenantSummary = { tenantId: 't-3', name: 'Offline', domain: 'off.example', ownerEmail: 'o@off.example', status: 'SUSPENDED', ...NO_QUOTA }
const GiB = 1024 ** 3
const INVITE: TenantInviteState = {
  ownerEmail: 'owner@acme.example', inviteStatus: 'ACTIVE', inviteExpiresAt: new Date('2026-10-10T12:00:00.000Z'),
  bootstrapSecret: 'sek', delivery: 'FALLBACK_COPYLINK',
}

const mountPanel = (tenantId = 't-1') => mount(TenantDetailPanel, { props: { tenantId }, global: { plugins: [i18n], stubs } })
const btn = (w: ReturnType<typeof mountPanel>, label: string) => w.findAll('button').find((b) => b.text() === label)!
// The move form submits (so Enter works), and the stubbed Button emits only a click — so the test drives the
// form's own submit rather than the button, which is the event the component actually listens for.
const submitMove = (w: ReturnType<typeof mountPanel>) => w.find('form.change-domain').trigger('submit')

describe('TenantDetailPanel (uc.admin.create-tenant + uc.admin.suspend-tenant, web)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    confirmAsk.mockResolvedValue(true)
    vi.mocked(listTenants).mockResolvedValue([PENDING])
    vi.mocked(setTenantStorageQuota).mockResolvedValue(undefined)
    vi.mocked(resendInvite).mockResolvedValue(INVITE)
    vi.mocked(reassignInvite).mockResolvedValue(INVITE)
    vi.mocked(revokeTenant).mockResolvedValue(undefined)
    vi.mocked(changeTenantDomain).mockResolvedValue(undefined)
    vi.mocked(suspendTenant).mockResolvedValue(undefined)
    vi.mocked(reactivateTenant).mockResolvedValue(undefined)
    vi.mocked(tenantError).mockResolvedValue({ status: 0, code: null })
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
    vi.mocked(listTenants).mockResolvedValue([ACTIVE])
    const w = mountPanel('t-2')
    await flushPromises()
    expect(w.find('.veyq-badge.success').exists()).toBe(true)
    expect(w.findAll('button').find((b) => b.text() === 'Revoke')).toBeUndefined()
  })

  it('take-offline is confirm-gated and delegates to the facade, then reloads in place', async () => {
    vi.mocked(listTenants).mockResolvedValue([ACTIVE])
    const w = mountPanel('t-2')
    await flushPromises()

    confirmAsk.mockResolvedValueOnce(false)
    await btn(w, 'Take offline').trigger('click')
    await flushPromises()
    expect(suspendTenant).not.toHaveBeenCalled()
    // The dialog is asked with the console's own copy — an imperative header over the question-voice body
    // that spells the consequence out (styleguide § Confirming a destructive action).
    expect(confirmAsk.mock.calls[0][0]).toMatchObject({ header: 'Take this tenant offline', message: 'offline?' })

    vi.mocked(listTenants).mockResolvedValue([{ ...ACTIVE, status: 'SUSPENDED' }])
    await btn(w, 'Take offline').trigger('click')
    await flushPromises()
    expect(suspendTenant).toHaveBeenCalledWith('t-2', null)
    // Stays on the panel (unlike revoke, which hands back to the console) and re-renders in the new state.
    expect(w.emitted('revoked')).toBeUndefined()
    expect(w.text()).toContain('Suspended')
  })

  it('take-offline over content carries its statement of reasons to the tenant’s owners (ADR 0093 § 7)', async () => {
    vi.mocked(listTenants).mockResolvedValue([ACTIVE])
    const statement = { statementId: 's-1', ground: 'ILLEGAL_CONTENT' as const, provision: '§ 130 StGB', facts: 'what was found', factsForLog: { epoch: 3, sealedFacts: 'c2VhbGVk' } }
    vi.mocked(stepStatement).mockResolvedValue(statement)
    const w = mountPanel('t-2')
    await flushPromises()
    // Not over content: no fields, and the suspension carries none (the control).
    expect(w.find('.step-statement').exists()).toBe(false)
    await w.find('.tenant-over-content__toggle').setValue(true)
    expect(w.find('.step-statement').exists()).toBe(true)
    await w.find('.step-statement__provision').setValue('§ 130 StGB')
    await w.find('.step-statement__facts').setValue('what was found')
    confirmAsk.mockResolvedValueOnce(true)
    await w.find('.tenant-suspend').trigger('click')
    await flushPromises()
    expect(stepStatement).toHaveBeenCalledWith(expect.objectContaining({ provision: '§ 130 StGB', facts: 'what was found' }))
    expect(suspendTenant).toHaveBeenCalledWith('t-2', statement)
  })

  it('a suspension the server finds over content asks for its statement', async () => {
    vi.mocked(listTenants).mockResolvedValue([ACTIVE])
    vi.mocked(suspendTenant).mockRejectedValueOnce(httpError(400, 'statement_required'))
    const w = mountPanel('t-2')
    await flushPromises()
    confirmAsk.mockResolvedValueOnce(true)
    await w.find('.tenant-suspend').trigger('click')
    await flushPromises()
    expect(w.find('.step-statement').exists()).toBe(true)
    expect(w.text()).toContain('moderation.steps.errors.statementRequired')
  })

  it('a SUSPENDED tenant shows its own badge + notice and offers only bring-back-online', async () => {
    vi.mocked(listTenants).mockResolvedValue([SUSPENDED])
    const w = mountPanel('t-3')
    await flushPromises()
    expect(w.find('.veyq-badge.danger').exists()).toBe(true)
    expect(w.text()).toContain('This tenant is offline.')
    expect(btn(w, 'Bring back online')).toBeTruthy()
    expect(w.findAll('button').find((b) => b.text() === 'Take offline')).toBeUndefined()
    expect(w.findAll('button').find((b) => b.text() === 'Revoke')).toBeUndefined()
  })

  it('bring-back-online needs no confirm and delegates to the facade', async () => {
    vi.mocked(listTenants).mockResolvedValue([SUSPENDED])
    const w = mountPanel('t-3')
    await flushPromises()
    await btn(w, 'Bring back online').trigger('click')
    await flushPromises()
    expect(reactivateTenant).toHaveBeenCalledWith('t-3')
    // Asserted from the mock rather than from a refused answer: reactivating only ever restores access, so it
    // must not ask at all. `ask` defaults to `true` here, so a gate that crept in would otherwise pass.
    expect(confirmAsk).not.toHaveBeenCalled()
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

  it('revoke is confirm-gated, delegates to the facade, and hands the gone tenant back to the console', async () => {
    const w = mountPanel()
    await flushPromises()

    confirmAsk.mockResolvedValueOnce(false)
    await btn(w, 'Revoke').trigger('click')
    await flushPromises()
    expect(revokeTenant).not.toHaveBeenCalled()

    await btn(w, 'Revoke').trigger('click')
    await flushPromises()
    expect(revokeTenant).toHaveBeenCalledWith('t-1')
    expect(w.emitted('revoked')).toEqual([['t-1']])
    // The bite: the old panel pushed `/settings`, a route admin-web does not have.
    expect(push).not.toHaveBeenCalled()
  })

  it('moves an ACTIVE tenant to a new address, confirm-gated, and reloads in place', async () => {
    vi.mocked(listTenants).mockResolvedValue([ACTIVE])
    const w = mountPanel('t-2')
    await flushPromises()
    // Prefilled with where the tenant is now, so the field reads as "the address" and not as an empty command.
    expect((w.find('form.change-domain input').element as HTMLInputElement).value).toBe('live.example')

    await w.find('form.change-domain input').setValue('moved.example')
    confirmAsk.mockResolvedValueOnce(false)
    await submitMove(w)
    await flushPromises()
    expect(changeTenantDomain).not.toHaveBeenCalled()
    // The one confirm whose body interpolates: the address it is about has to be IN the sentence.
    expect(confirmAsk.mock.calls[0][0]).toMatchObject({ message: 'move to moved.example?' })

    vi.mocked(listTenants).mockResolvedValue([{ ...ACTIVE, domain: 'moved.example' }])
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
    vi.mocked(listTenants).mockResolvedValue([SUSPENDED])
    const w = mountPanel('t-3')
    await flushPromises()
    expect(w.findAll('button').find((b) => b.text() === 'Move')).toBeUndefined()
    expect(btn(w, 'Bring back online')).toBeTruthy() // control: the panel rendered, it just omits this one action
  })

  it('maps the move failures apart instead of showing one generic error', async () => {
    // Three different fixes for the operator — pick another address / bring it back online / correct the form —
    // so a single "that failed" would be the wrong answer to two of them.
    vi.mocked(listTenants).mockResolvedValue([ACTIVE])
    const w = mountPanel('t-2')
    await flushPromises()

    for (const [failure, expected] of [
      [{ status: 409, code: 'domain_taken' }, 'taken'],
      [{ status: 409, code: 'tenant_not_pending_or_active' }, 'offline tenants cannot move'],
      [{ status: 400, code: 'invalid_request' }, 'not an address'],
    ] as const) {
      await w.find('form.change-domain input').setValue(`x-${expected.length}.example`)
      vi.mocked(changeTenantDomain).mockRejectedValueOnce(httpError(failure.status))
      vi.mocked(tenantError).mockResolvedValueOnce(failure)
      await submitMove(w)
      await flushPromises()
      expect(w.text()).toContain(expected)
    }
  })

  // --- the storage quota (uc.admin.tenant-storage-quota) ---
  const submitQuota = (w: ReturnType<typeof mountPanel>) => w.find('form.tenant-storage').trigger('submit')
  const quotaInput = (w: ReturnType<typeof mountPanel>) => w.find('form.tenant-storage input')

  it('shows the usage beside the quota, and "none" with no Remove when no quota is set', async () => {
    vi.mocked(listTenants).mockResolvedValue([{ ...ACTIVE, storageUsedBytes: 3 * 1024 ** 2 }])
    const w = mountPanel('t-2')
    await flushPromises()
    expect(w.find('.tenant-storage__figures').text()).toContain('3 MB')
    expect(w.find('.tenant-storage__figures').text()).toContain('none')
    expect(w.findAll('button').find((b) => b.text() === 'Remove quota')).toBeUndefined()
  })

  it('sets the quota typed in GiB as bytes, and reloads in place', async () => {
    vi.mocked(listTenants).mockResolvedValue([ACTIVE])
    const w = mountPanel('t-2')
    await flushPromises()
    await quotaInput(w).setValue('2,5') // the German decimal comma is a decimal too
    vi.mocked(listTenants).mockResolvedValue([{ ...ACTIVE, storageQuotaBytes: 2.5 * GiB }])
    await submitQuota(w)
    await flushPromises()
    expect(setTenantStorageQuota).toHaveBeenCalledWith('t-2', 2_684_354_560) // 2.5 × 1024³
    expect(w.find('.tenant-storage__figures').text()).toContain('2.5 GB')
  })

  it('clears a set quota with null, and prefills the input with the current one in GiB', async () => {
    vi.mocked(listTenants).mockResolvedValue([{ ...SUSPENDED, storageQuotaBytes: 10 * GiB }]) // a suspended tenant keeps its quota
    const w = mountPanel('t-3')
    await flushPromises()
    expect((quotaInput(w).element as HTMLInputElement).value).toBe('10')
    await btn(w, 'Remove quota').trigger('click')
    await flushPromises()
    expect(setTenantStorageQuota).toHaveBeenCalledWith('t-3', null)
  })

  it('shows a quota under 0.01 GiB as non-zero, and saves it unchanged as the stored bytes, not as 0', async () => {
    // 107 374 B rounded to two decimals was "0" — and 0 is a real quota: an unchanged save stopped every upload.
    vi.mocked(listTenants).mockResolvedValue([{ ...ACTIVE, storageQuotaBytes: 107_374 }])
    const w = mountPanel('t-2')
    await flushPromises()
    expect((quotaInput(w).element as HTMLInputElement).value).toBe('0.0001')
    await submitQuota(w)
    await flushPromises()
    expect(setTenantStorageQuota).toHaveBeenCalledWith('t-2', 107_374)
  })

  it('saves a quota the display rounds unchanged as the stored bytes, so an untouched field never drifts', async () => {
    const stored = Math.round(1.234 * GiB)
    vi.mocked(listTenants).mockResolvedValue([{ ...ACTIVE, storageQuotaBytes: stored }])
    const w = mountPanel('t-2')
    await flushPromises()
    expect((quotaInput(w).element as HTMLInputElement).value).toBe('1.23')
    await submitQuota(w)
    await flushPromises()
    expect(setTenantStorageQuota).toHaveBeenCalledWith('t-2', stored)
  })

  it('refuses a quota that is not a number of GiB without calling', async () => {
    vi.mocked(listTenants).mockResolvedValue([ACTIVE])
    const w = mountPanel('t-2')
    await flushPromises()
    for (const bad of ['-1', 'lots', '']) {
      await quotaInput(w).setValue(bad)
      await submitQuota(w)
      await flushPromises()
    }
    expect(setTenantStorageQuota).not.toHaveBeenCalled()
    expect(w.text()).toContain('not a quota')
    await quotaInput(w).setValue('0') // control: 0 is a real quota — nothing more may be uploaded
    await submitQuota(w)
    await flushPromises()
    expect(setTenantStorageQuota).toHaveBeenCalledWith('t-2', 0)
  })

  it('shows a not-authorized state on a 403', async () => {
    // A real generated-client failure carries `.response.status`; the panel classifies it via `httpStatus`.
    vi.mocked(listTenants).mockRejectedValueOnce(httpError(403))
    const w = mountPanel()
    await flushPromises()
    expect(w.find('.msg').exists()).toBe(true)
    expect(w.text()).not.toContain('Acme')
  })

  it('ends the session on a 401 — tokens dropped BEFORE the sign-in, or the router bounces it (inbox #449)', async () => {
    setTokens('expired-access', 'dead-refresh', 'family-1')
    vi.mocked(listTenants).mockRejectedValueOnce(httpError(401))
    mountPanel()
    await flushPromises()
    expect(replace).toHaveBeenCalledWith({ name: 'login' })
    expect(session.accessToken).toBeNull()
  })
})
