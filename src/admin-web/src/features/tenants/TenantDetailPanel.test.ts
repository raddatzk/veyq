// @vitest-environment happy-dom
// uc.admin.create-tenant + uc.admin.suspend-tenant (web) — the per-tenant detail page: it loads one tenant by
// id, renders a PENDING tenant's owner-invite controls (reassign / resend / revoke) + the fresh invite link,
// confirm-gates revoke (delegating to the facade + routing back), maps 403/401, and carries the availability
// lever: an ACTIVE tenant offers "take offline" (confirm-gated), a SUSPENDED one its own badge, its
// explain-what-this-means notice and "bring back online" — and, deliberately, no invite controls in either.
// uc.admin.tenant-storage-quota: the panel shows usage beside the quota, and sets (GiB in, bytes out) or clears it.
// uc.admin.delete-tenant: an ACTIVE or SUSPENDED tenant is deleted only once its domain is typed; a PENDING_DELETION one
// shows its due date and is stopped; an ownerless one says so and invites one of its members to own it.
// Both measures ask the over-content question once, above both; a moderator's open proposal ticks and holds it.
// ADR 0093 § 7: a step over content whose statement did not plainly reach every owner leaves a standing report under the
// header with the words as sent (only a plain success is a toast), and the fields warn before the step without mail.
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
  // the delete confirmation's rule stays real: the gate is what the delete cases assert
  domainConfirmed: (await importOriginal<typeof import('./tenants')>()).domainConfirmed,
  deleteTenant: vi.fn(),
  stopTenantDeletion: vi.fn(),
  inviteTenantOwner: vi.fn(),
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
// The real `ProposalStep`: which proposal the panel picks is what the proposal cases assert. The facade is mocked
// whole — its key minting pulls in `@veyq/crypto`, which none of these cases reach.
vi.mock('../instance/moderation', () => ({
  ProposalStep: { MemberSignIn: 'MEMBER_SIGN_IN', TenantOffline: 'TENANT_OFFLINE' },
  getModerationSteps: vi.fn(),
}))
vi.mock('../instance/stepStatement', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../instance/stepStatement')>()),
  stepStatement: vi.fn(),
}))
vi.mock('../instance/instanceMail', () => ({ statementMailNotSetUp: vi.fn() }))
const { toastSuccess } = vi.hoisted(() => ({ toastSuccess: vi.fn() }))
vi.mock('../../shared/state/useToast', () => ({ useToast: () => ({ success: toastSuccess }) }))

import TenantDetailPanel from './TenantDetailPanel.vue'
import {
  listTenants, revokeTenant, resendInvite, reassignInvite, suspendTenant, reactivateTenant, changeTenantDomain, tenantError,
  setTenantStorageQuota, deleteTenant, stopTenantDeletion, inviteTenantOwner,
} from './tenants'
import type { TenantInviteState, TenantSummary } from './tenants'
import { httpError } from '../../test/httpError'
import { stepStatement } from '../instance/stepStatement'
import { getModerationSteps, type OpenModerationProposal } from '../instance/moderation'
import { statementMailNotSetUp } from '../instance/instanceMail'
import { copyText } from '../../shared/inviteLink'
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
        statusPendingDeletion: 'Being deleted', pendingDeletionHint: 'Erased on {date}.', stopDeletion: 'Stop deletion',
        deletionStopped: 'stopped', deleteTitle: 'Delete this tenant', deleteHint: 'gone after 30 days',
        deleteConfirmLabel: 'Type {domain}', delete: 'Delete tenant', deletionStarted: 'deleting, erased on {date}',
        deleteDomainMismatch: 'wrong domain', notDeletable: 'not deletable',
        ownerlessHint: 'This tenant has no owner.', ownerlessSuspendedHint: 'Bring it back online to invite an owner.',
        inviteOwnerTitle: 'Invite an owner', inviteOwnerEmail: 'Member address', inviteOwner: 'Invite owner',
        ownerInviteMailed: 'mailed to {email}', ownerInviteCopy: 'copy the link for {email}',
        notTenantMember: 'not a member', ownerInviteNotActive: 'not online',
        measuresTitle: 'Take offline or delete', suspendSend: 'Take offline and send', deleteSend: 'Delete and send',
        proposalOpen: 'Proposed: {category}, case {caseId}',
      },
      moderation: {
        steps: {
          categories: { ILLEGAL_CONTENT: 'Illegal content', TERMS_BREACH: 'A breach of the terms' },
          statement: { ground: 'Ground', provision: 'Provision', facts: 'Facts', noMail: 'Mail is not set up' },
          delivery: { notReached: 'Not mailed to {addresses}', unknown: 'Cannot say', nobodyToTell: 'Nobody to tell', copy: 'Copy', copied: 'Copied' },
        },
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
  // `disabled` is carried so the delete gate's disarmed state is observable, not only its refused submit.
  Button: { props: ['label', 'icon', 'ariaLabel', 'disabled'], emits: ['click'], template: '<button :aria-label="ariaLabel" :disabled="disabled" @click="$emit(\'click\', $event)">{{ label }}</button>' },
  // `severity` and the close button are carried so the standing statement report's tone and dismissal are observable.
  Message: {
    props: ['severity', 'closable'],
    emits: ['close'],
    template: '<div class="msg" :data-severity="severity"><slot /><button v-if="closable" class="msg-close" @click="$emit(\'close\')">x</button></div>',
  },
  // a native checkbox, so `setValue` and `disabled` read as on the page; the class falls through onto it
  Checkbox: {
    props: ['modelValue', 'disabled', 'inputId'],
    emits: ['update:modelValue'],
    template: '<input type="checkbox" :id="inputId" :checked="modelValue" :disabled="disabled" @change="$emit(\'update:modelValue\', $event.target.checked)" />',
  },
}

const NO_QUOTA = { storageUsedBytes: 0, storageQuotaBytes: undefined, ownerless: false, deletionDueAt: undefined }
const PENDING: TenantSummary = { tenantId: 't-1', name: 'Acme', domain: 'acme.example', ownerEmail: 'owner@acme.example', status: 'PENDING', ...NO_QUOTA }
const ACTIVE: TenantSummary = { tenantId: 't-2', name: 'Live', domain: 'live.example', ownerEmail: 'o@live.example', status: 'ACTIVE', ...NO_QUOTA }
const SUSPENDED: TenantSummary = { tenantId: 't-3', name: 'Offline', domain: 'off.example', ownerEmail: 'o@off.example', status: 'SUSPENDED', ...NO_QUOTA }
const DUE = new Date('2026-11-17T12:00:00.000Z')
const DELETING: TenantSummary = {
  tenantId: 't-4', name: 'Going', domain: 'going.example', ownerEmail: 'o@going.example', status: 'PENDING_DELETION', ...NO_QUOTA, deletionDueAt: DUE,
}
// The stored owner address of an ownerless tenant is its first owner's — possibly the very account erased.
const OWNERLESS: TenantSummary = { ...ACTIVE, tenantId: 't-5', ownerEmail: 'erased@live.example', ownerless: true }
const GiB = 1024 ** 3
const PROPOSAL: OpenModerationProposal = {
  id: 'p-1', caseId: 'c-1', category: 'ILLEGAL_CONTENT', step: 'TENANT_OFFLINE', proposedAt: new Date('2026-10-20T12:00:00.000Z'),
  tenantId: 't-2', tenantDomain: 'live.example',
}
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
    vi.mocked(suspendTenant).mockResolvedValue({})
    vi.mocked(reactivateTenant).mockResolvedValue(undefined)
    vi.mocked(tenantError).mockResolvedValue({ status: 0, code: null })
    vi.mocked(deleteTenant).mockResolvedValue({ dueAt: DUE })
    vi.mocked(stopTenantDeletion).mockResolvedValue(undefined)
    vi.mocked(inviteTenantOwner).mockResolvedValue({ ...INVITE, ownerEmail: 'member@live.example' })
    vi.mocked(getModerationSteps).mockResolvedValue({ proposals: [], signInBlocks: [] })
    vi.mocked(statementMailNotSetUp).mockResolvedValue(false)
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

  // --- delete (uc.admin.delete-tenant) ---
  const deleteInput = (w: ReturnType<typeof mountPanel>) => w.find('form.tenant-delete input')
  const submitDelete = (w: ReturnType<typeof mountPanel>) => w.find('form.tenant-delete').trigger('submit')

  it('refuses to delete until the tenant’s own domain is typed, then deletes with it and reloads in place', async () => {
    vi.mocked(listTenants).mockResolvedValue([ACTIVE])
    const w = mountPanel('t-2')
    await flushPromises()
    expect(w.text()).toContain('Type live.example') // the label names what to type
    expect(btn(w, 'Delete tenant').attributes('disabled')).toBeDefined()

    for (const wrong of ['', 'live.exampl', 'other.example']) {
      await deleteInput(w).setValue(wrong)
      await submitDelete(w)
    }
    await flushPromises()
    expect(deleteTenant).not.toHaveBeenCalled()
    expect(btn(w, 'Delete tenant').attributes('disabled')).toBeDefined()
    // The typed domain is the confirmation: no second yes/no dialog stands in for it.
    expect(confirmAsk).not.toHaveBeenCalled()

    // the positive control, same path: the domain (case aside) arms it, and is what is sent
    await deleteInput(w).setValue('Live.Example')
    expect(btn(w, 'Delete tenant').attributes('disabled')).toBeUndefined()
    vi.mocked(listTenants).mockResolvedValue([{ ...ACTIVE, status: 'PENDING_DELETION', deletionDueAt: DUE }])
    await submitDelete(w)
    await flushPromises()
    expect(deleteTenant).toHaveBeenCalledWith('t-2', 'Live.Example', null)
    expect(w.text()).toContain('Being deleted')
    expect(w.emitted('changed')).toHaveLength(1)
    expect(w.emitted('revoked')).toBeUndefined() // still there, pending — not gone
  })

  it('a deletion over content carries its statement of reasons to the tenant’s owners (ADR 0101 decision 10)', async () => {
    vi.mocked(listTenants).mockResolvedValue([ACTIVE])
    const statement = { statementId: 's-2', ground: 'TERMS_BREACH' as const, provision: 'Space terms, clause 4', facts: 'what was found', factsForLog: { epoch: 3, sealedFacts: 'c2VhbGVk' } }
    vi.mocked(stepStatement).mockResolvedValue(statement)
    vi.mocked(deleteTenant).mockResolvedValue({ dueAt: DUE })
    const w = mountPanel('t-2')
    await flushPromises()
    expect(w.find('.step-statement').exists()).toBe(false)
    await w.find('.tenant-over-content__toggle').setValue(true)
    expect(w.find('.step-statement').exists()).toBe(true)
    await w.find('.step-statement__provision').setValue('Space terms, clause 4')
    await w.find('.step-statement__facts').setValue('what was found')
    await deleteInput(w).setValue('live.example')
    expect(btn(w, 'Delete and send')).toBeTruthy()
    await submitDelete(w)
    await flushPromises()
    expect(stepStatement).toHaveBeenCalledWith(expect.objectContaining({ provision: 'Space terms, clause 4', facts: 'what was found' }))
    expect(deleteTenant).toHaveBeenCalledWith('t-2', 'live.example', statement)
  })

  it('a deletion the server finds over content asks for its statement', async () => {
    vi.mocked(listTenants).mockResolvedValue([ACTIVE])
    vi.mocked(deleteTenant).mockRejectedValueOnce(httpError(400, 'statement_required'))
    vi.mocked(tenantError).mockResolvedValueOnce({ status: 400, code: 'statement_required' })
    const w = mountPanel('t-2')
    await flushPromises()
    await deleteInput(w).setValue('live.example')
    await submitDelete(w)
    await flushPromises()
    expect(deleteTenant).toHaveBeenCalledWith('t-2', 'live.example', null)
    expect(w.find('.tenant-measures .step-statement').exists()).toBe(true)
    expect((w.find('.tenant-over-content__toggle').element as HTMLInputElement).checked).toBe(true)
  })

  it('names the delete refusals apart', async () => {
    vi.mocked(listTenants).mockResolvedValue([SUSPENDED]) // a suspended tenant is deletable too
    const w = mountPanel('t-3')
    await flushPromises()
    for (const [code, expected] of [['tenant_domain_mismatch', 'wrong domain'], ['tenant_not_deletable', 'not deletable']] as const) {
      await deleteInput(w).setValue('off.example')
      vi.mocked(deleteTenant).mockRejectedValueOnce(httpError(409, code))
      vi.mocked(tenantError).mockResolvedValueOnce({ status: 409, code })
      await submitDelete(w)
      await flushPromises()
      expect(w.text()).toContain(expected)
    }
  })

  // --- the one over-content question (ADR 0093 § 7, ADR 0101 decision 10) ---

  it('asks over content once for both measures, and both buttons say a statement goes out', async () => {
    vi.mocked(listTenants).mockResolvedValue([ACTIVE])
    const w = mountPanel('t-2')
    await flushPromises()
    // The bite: the page asked it twice, once per measure, under one and the same label.
    expect(w.findAll('.tenant-over-content__toggle')).toHaveLength(1)
    expect(w.findAll('.step-statement')).toHaveLength(0)
    expect(btn(w, 'Take offline')).toBeTruthy()
    expect(btn(w, 'Delete tenant')).toBeTruthy()
    await w.find('.tenant-over-content__toggle').setValue(true)
    expect(w.findAll('.step-statement')).toHaveLength(1)
    expect(btn(w, 'Take offline and send')).toBeTruthy()
    expect(btn(w, 'Delete and send')).toBeTruthy()
  })

  it('a SUSPENDED tenant brings it back online above, outside the measures, which hold only Delete', async () => {
    vi.mocked(listTenants).mockResolvedValue([SUSPENDED])
    const w = mountPanel('t-3')
    await flushPromises()
    const measures = w.find('.tenant-measures')
    expect(measures.find('h3').text()).toBe('Delete this tenant')
    expect(measures.findAll('button').map((b) => b.text())).toEqual(['Delete tenant'])
    expect(btn(w, 'Bring back online')).toBeTruthy() // control: it is on the page, just not among the measures
  })

  it('a moderator’s open proposal to take the tenant offline is shown, and holds the checkbox ticked', async () => {
    vi.mocked(listTenants).mockResolvedValue([ACTIVE])
    vi.mocked(getModerationSteps).mockResolvedValue({
      // another tenant's proposal and a sign-in block's must not count
      proposals: [{ ...PROPOSAL, id: 'p-0', tenantId: 't-9' }, { ...PROPOSAL, id: 'p-2', step: 'MEMBER_SIGN_IN', tenantId: 't-2' }, PROPOSAL],
      signInBlocks: [],
    })
    const statement = { statementId: 's-3', ground: 'ILLEGAL_CONTENT' as const, provision: '', facts: '', factsForLog: undefined }
    vi.mocked(stepStatement).mockResolvedValue(statement)
    const w = mountPanel('t-2')
    await flushPromises()
    expect(w.find('.tenant-proposal').text()).toBe('Proposed: Illegal content, case c-1')
    const toggle = w.find('.tenant-over-content__toggle').element as HTMLInputElement
    expect(toggle.checked).toBe(true)
    expect(toggle.disabled).toBe(true)
    expect(w.find('.step-statement').exists()).toBe(true)
    await w.find('.tenant-suspend').trigger('click')
    await flushPromises()
    expect(suspendTenant).toHaveBeenCalledWith('t-2', statement)
  })

  it('without an open proposal for this tenant the checkbox is the Server Owner’s, also when the read fails', async () => {
    vi.mocked(listTenants).mockResolvedValue([ACTIVE])
    vi.mocked(getModerationSteps).mockResolvedValue({ proposals: [{ ...PROPOSAL, tenantId: 't-9' }], signInBlocks: [] })
    let w = mountPanel('t-2')
    await flushPromises()
    expect(w.find('.tenant-proposal').exists()).toBe(false)
    expect((w.find('.tenant-over-content__toggle').element as HTMLInputElement).disabled).toBe(false)

    vi.mocked(getModerationSteps).mockRejectedValueOnce(httpError(500))
    w = mountPanel('t-2')
    await flushPromises()
    expect(w.text()).toContain('Live') // the tenant still loaded
    expect(w.find('.tenant-proposal').exists()).toBe(false)
    expect((w.find('.tenant-over-content__toggle').element as HTMLInputElement).checked).toBe(false)
  })

  it('offers no delete on a PENDING tenant — it is revoked instead', async () => {
    const w = mountPanel()
    await flushPromises()
    expect(w.find('form.tenant-delete').exists()).toBe(false)
    expect(btn(w, 'Revoke')).toBeTruthy() // control: the panel rendered
  })

  it('a PENDING_DELETION tenant shows its badge and due date, and stops the deletion without asking', async () => {
    vi.mocked(listTenants).mockResolvedValue([DELETING])
    const w = mountPanel('t-4')
    await flushPromises()
    expect(w.find('.veyq-badge.danger').text()).toBe('Being deleted')
    expect(w.text()).toContain(`Erased on ${DUE.toLocaleString('en')}.`)
    // offline like SUSPENDED, but none of a live tenant's levers: no take-offline, no move, no second delete
    for (const absent of ['Take offline', 'Bring back online', 'Move', 'Delete tenant']) {
      expect(w.findAll('button').find((b) => b.text() === absent)).toBeUndefined()
    }

    vi.mocked(listTenants).mockResolvedValue([{ ...DELETING, status: 'SUSPENDED', deletionDueAt: undefined }])
    await btn(w, 'Stop deletion').trigger('click')
    await flushPromises()
    expect(stopTenantDeletion).toHaveBeenCalledWith('t-4')
    expect(confirmAsk).not.toHaveBeenCalled()
    expect(w.emitted('changed')).toHaveLength(1)
    expect(btn(w, 'Bring back online')).toBeTruthy() // back SUSPENDED, reactivated as usual
  })

  // --- an ownerless tenant (ADR 0101 decision 9) ---
  const ownerInput = (w: ReturnType<typeof mountPanel>) => w.find('form.tenant-owner-invite input')
  const submitOwnerInvite = (w: ReturnType<typeof mountPanel>) => w.find('form.tenant-owner-invite').trigger('submit')

  it('an ownerless tenant says so, names no stale owner address, and offers both ways on', async () => {
    vi.mocked(listTenants).mockResolvedValue([OWNERLESS])
    const w = mountPanel('t-5')
    await flushPromises()
    expect(w.text()).toContain('This tenant has no owner.')
    expect(w.text()).not.toContain('erased@live.example')
    expect(btn(w, 'Invite owner')).toBeTruthy()
    expect(btn(w, 'Delete tenant')).toBeTruthy()
  })

  it('an owned tenant shows no ownerless notice and no owner invite', async () => {
    vi.mocked(listTenants).mockResolvedValue([ACTIVE])
    const w = mountPanel('t-2')
    await flushPromises()
    expect(w.text()).not.toContain('This tenant has no owner.')
    expect(w.find('form.tenant-owner-invite').exists()).toBe(false)
    expect(w.text()).toContain('o@live.example') // control: an owned tenant's header names its owner
  })

  it('invites a member to own it and shows the copy-link when no mail went out', async () => {
    vi.mocked(listTenants).mockResolvedValue([OWNERLESS])
    const w = mountPanel('t-5')
    await flushPromises()
    await ownerInput(w).setValue(' member@live.example ')
    await submitOwnerInvite(w)
    await flushPromises()
    expect(inviteTenantOwner).toHaveBeenCalledWith('t-5', 'member@live.example')
    expect(w.text()).toContain('copy the link for member@live.example')
    expect((w.find('input[readonly]').element as HTMLInputElement).value).toBe('https://acme.example/#invite=sek')
  })

  it('says the owner invite was mailed, and shows no link, when it was', async () => {
    vi.mocked(listTenants).mockResolvedValue([OWNERLESS])
    vi.mocked(inviteTenantOwner).mockResolvedValueOnce({ ...INVITE, ownerEmail: 'member@live.example', delivery: 'SENT' })
    const w = mountPanel('t-5')
    await flushPromises()
    await ownerInput(w).setValue('member@live.example')
    await submitOwnerInvite(w)
    await flushPromises()
    expect(w.text()).toContain('mailed to member@live.example')
    expect(w.find('input[readonly]').exists()).toBe(false)
  })

  it('names an owner invite to a non-member as such', async () => {
    vi.mocked(listTenants).mockResolvedValue([OWNERLESS])
    vi.mocked(inviteTenantOwner).mockRejectedValueOnce(httpError(409, 'not_tenant_member'))
    vi.mocked(tenantError).mockResolvedValueOnce({ status: 409, code: 'not_tenant_member' })
    const w = mountPanel('t-5')
    await flushPromises()
    await ownerInput(w).setValue('stranger@example.com')
    await submitOwnerInvite(w)
    await flushPromises()
    expect(w.text()).toContain('not a member')
    expect(w.find('input[readonly]').exists()).toBe(false)
  })

  it('an ownerless SUSPENDED tenant says to bring it back online first and offers no invite, but Delete', async () => {
    vi.mocked(listTenants).mockResolvedValue([{ ...OWNERLESS, status: 'SUSPENDED' }])
    const w = mountPanel('t-5')
    await flushPromises()
    expect(w.text()).toContain('Bring it back online to invite an owner.')
    expect(w.find('form.tenant-owner-invite').exists()).toBe(false)
    expect(btn(w, 'Delete tenant')).toBeTruthy()
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

  // ADR 0093 § 7 — what the step's answer says its statement's mail reached.
  const STATEMENT = { statementId: 's-9', ground: 'TERMS_BREACH' as const, provision: 'Space terms, clause 4', facts: 'what was found', factsForLog: { epoch: 3, sealedFacts: 'c2VhbGVk' } }
  async function suspendOverContent(answer: Awaited<ReturnType<typeof suspendTenant>>) {
    vi.mocked(listTenants).mockResolvedValue([ACTIVE])
    vi.mocked(stepStatement).mockResolvedValue(STATEMENT)
    vi.mocked(suspendTenant).mockResolvedValue(answer)
    const w = mountPanel('t-2')
    await flushPromises()
    await w.find('.tenant-over-content__toggle').setValue(true)
    await w.find('.step-statement__provision').setValue('Space terms, clause 4')
    await w.find('.step-statement__facts').setValue('what was found')
    vi.mocked(listTenants).mockResolvedValue([{ ...ACTIVE, status: 'SUSPENDED' }])
    await w.find('.tenant-suspend').trigger('click')
    await flushPromises()
    return w
  }

  it('a suspension over content whose mail reached every owner is only the toast', async () => {
    const w = await suspendOverContent({ statementDelivery: { outcome: 'REACHED', notReached: [] } })
    expect(toastSuccess).toHaveBeenCalledWith('offline')
    expect(w.find('.statement-delivery').exists()).toBe(false)
  })

  it('a suspension whose mail did not reach every owner names them and keeps the words in view, not a toast', async () => {
    const w = await suspendOverContent({ statementDelivery: { outcome: 'NOT_ALL_REACHED', notReached: ['a@live.example', 'b@live.example'] } })
    const report = w.find('.statement-delivery')
    expect(report.exists()).toBe(true)
    expect(report.attributes('data-severity')).toBe('warn')
    expect(report.find('.statement-delivery__what').text()).toBe('Not mailed to a@live.example, b@live.example')
    expect(report.find('.statement-delivery__ground').text()).toBe('A breach of the terms')
    expect(report.find('.statement-delivery__provision').text()).toBe('Space terms, clause 4')
    expect(report.find('.statement-delivery__facts').text()).toBe('what was found')
    expect(toastSuccess).not.toHaveBeenCalled()
    // It survives the reload that follows the step; the editable draft does not (a following delete starts empty).
    expect(w.text()).toContain('Suspended')
    await w.find('.tenant-over-content__toggle').setValue(true)
    expect((w.find('.step-statement__facts').element as HTMLTextAreaElement).value).toBe('')
    // The words go to the clipboard as entered, to be passed on through the instance contact.
    await report.find('.statement-delivery__copy').trigger('click')
    await flushPromises()
    expect(copyText).toHaveBeenCalledWith('Ground: A breach of the terms\nProvision: Space terms, clause 4\nFacts: what was found')
    // Dismissed, it is gone.
    await report.find('.msg-close').trigger('click')
    expect(w.find('.statement-delivery').exists()).toBe(false)
  })

  it('the report goes when the panel loads another tenant', async () => {
    const w = await suspendOverContent({ statementDelivery: { outcome: 'NOT_ALL_REACHED', notReached: ['a@live.example'] } })
    expect(w.find('.statement-delivery').exists()).toBe(true)
    vi.mocked(listTenants).mockResolvedValue([SUSPENDED])
    await w.setProps({ tenantId: 't-3' })
    await flushPromises()
    expect(w.find('.statement-delivery').exists()).toBe(false)
  })

  it('a deletion whose delivery the server cannot say, or that told nobody, says so with the words', async () => {
    vi.mocked(listTenants).mockResolvedValue([ACTIVE])
    vi.mocked(stepStatement).mockResolvedValue(STATEMENT)
    vi.mocked(deleteTenant).mockResolvedValue({ dueAt: DUE, statementDelivery: { outcome: 'UNKNOWN', notReached: [] } })
    const w = mountPanel('t-2')
    await flushPromises()
    await w.find('.tenant-over-content__toggle').setValue(true)
    await deleteInput(w).setValue('live.example')
    await submitDelete(w)
    await flushPromises()
    let report = w.find('.statement-delivery')
    expect(report.attributes('data-severity')).toBe('warn')
    expect(report.find('.statement-delivery__what').text()).toBe('Cannot say')
    expect(report.find('.statement-delivery__facts').text()).toBe('what was found')
    expect(toastSuccess).not.toHaveBeenCalled()

    vi.mocked(listTenants).mockResolvedValue([ACTIVE])
    vi.mocked(deleteTenant).mockResolvedValue({ dueAt: DUE, statementDelivery: { outcome: 'NOBODY_TO_TELL', notReached: [] } })
    vi.mocked(stepStatement).mockResolvedValue({ ...STATEMENT, statementId: 's-10' })
    const second = mountPanel('t-2')
    await flushPromises()
    await second.find('.tenant-over-content__toggle').setValue(true)
    await deleteInput(second).setValue('live.example')
    await submitDelete(second)
    await flushPromises()
    report = second.find('.statement-delivery')
    expect(report.attributes('data-severity')).toBe('info')
    expect(report.find('.statement-delivery__what').text()).toBe('Nobody to tell')
  })

  it('a deletion without a statement is the toast, as before — control', async () => {
    vi.mocked(listTenants).mockResolvedValue([ACTIVE])
    const w = mountPanel('t-2')
    await flushPromises()
    await deleteInput(w).setValue('live.example')
    await submitDelete(w)
    await flushPromises()
    expect(toastSuccess).toHaveBeenCalledWith(expect.stringContaining('deleting'))
    expect(w.find('.statement-delivery').exists()).toBe(false)
  })

  it('warns before the step that the statement cannot be mailed while the instance mail is not set up', async () => {
    vi.mocked(listTenants).mockResolvedValue([ACTIVE])
    vi.mocked(statementMailNotSetUp).mockResolvedValue(true)
    const w = mountPanel('t-2')
    await flushPromises()
    await w.find('.tenant-over-content__toggle').setValue(true)
    await flushPromises()
    expect(w.find('.step-statement__no-mail').text()).toBe('Mail is not set up')
  })

  it('says nothing of mail before the step when the instance mail is set up — control', async () => {
    vi.mocked(listTenants).mockResolvedValue([ACTIVE])
    const w = mountPanel('t-2')
    await flushPromises()
    await w.find('.tenant-over-content__toggle').setValue(true)
    await flushPromises()
    expect(statementMailNotSetUp).toHaveBeenCalled()
    expect(w.find('.step-statement__no-mail').exists()).toBe(false)
  })
})
