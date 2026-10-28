// @vitest-environment happy-dom
// uc.spaces.add-member (web, cr-0077) — the member directory rubric: it lists the active tenant's members, each
// active, and shows the invite button only to the OWNER (inviting is owner-gated).
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { httpError } from '../../test/httpError'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('./members', () => ({
  DeliveryResult: { Sent: 'SENT', FallbackCopylink: 'FALLBACK_COPYLINK', Failed: 'FAILED' },
  listScopeMembers: vi.fn(),
  listMemberInvites: vi.fn(),
  reissueMemberInvite: vi.fn(),
  revokeMemberInvite: vi.fn(),
  removeTenantMember: vi.fn(),
  setTenantMemberRole: vi.fn(),
  SetTenantMemberRoleRequestRoleEnum: { Owner: 'OWNER', Member: 'MEMBER' },
  memberError: vi.fn(),
}))
vi.mock('./scopes', () => ({
  listMyTenants: vi.fn(),
  setSpaceIcon: vi.fn(),
  clearSpaceIcon: vi.fn(),
  openMyTenantIcon: vi.fn(),
  revokeScopeShares: vi.fn(),
  scopeLifecycle: vi.fn(),
}))
// The pin store's badge read: {} (no badge) unless a test says otherwise - what the vault-less real one degraded to.
vi.mock('../account/identityPins', async (orig) => ({ ...(await orig<typeof import('../account/identityPins')>()), trustStates: vi.fn() }))
// Confirmation is the app's own dialog (cr-web-own-confirm-dialog): the panel awaits `ask()`, so the answer is
// given here. Stubbing `window.confirm` would now confirm nothing and pass anyway.
const { confirmAsk, confirmAskWithOption } = vi.hoisted(() => ({ confirmAsk: vi.fn(), confirmAskWithOption: vi.fn() }))
vi.mock('../../shared/state/useConfirm', () => ({ useConfirm: () => ({ ask: confirmAsk, askWithOption: confirmAskWithOption }) }))

import TenantMembersPanel from './TenantMembersPanel.vue'
import { listScopeMembers, listMemberInvites, reissueMemberInvite, revokeMemberInvite, removeTenantMember, setTenantMemberRole, memberError } from './members'
import { listMyTenants, openMyTenantIcon, revokeScopeShares, scopeLifecycle } from './scopes'
import { trustStates } from '../account/identityPins'
import { activeSpace, clearActiveSpace } from '../../app/activeSpace'
import { ScopeKind, type ScopeMember } from '../../api/generated/scopes'
import type { PendingMemberInvite } from '../../api/generated/tenants'
import { realI18n, t } from '../../test/i18n'
import { i18n } from '../../i18n'
import { myTenant } from '../../test/models'
import { formatInviteCode, normalizeInviteCode } from '@veyq/crypto/invite'

const stubs = {
  VeyqButton: { props: ['label', 'icon', 'ariaLabel'], template: '<button :aria-label="ariaLabel">{{ label }}</button>' },
  VeyqMessage: { template: '<div class="msg"><slot /></div>' },
  VeyqInput: { props: ['modelValue'], template: '<input :value="modelValue" />' },
  InviteMemberDialog: true,
}

beforeEach(() => {
  vi.mocked(trustStates).mockResolvedValue({})
  vi.mocked(memberError).mockResolvedValue({ status: 0, code: null })
  vi.mocked(openMyTenantIcon).mockResolvedValue(null)
  // ADR 0109: the owner's row says whether the tenant's re-key is unfinished; nothing pending here
  vi.mocked(scopeLifecycle).mockResolvedValue(null)
})

// sealedName is null here (no K_tenant in the test → names stay sealed) so the rows show the @username.
const MEMBERS: ScopeMember[] = [
  { userId: 'u-own', username: 'owner', avatarRevision: 0, sealedName: null, email: 'owner@x.z', role: 'OWNER', identityPublicKey: 'AA==', keyChainHead: 'AA==', pending: false },
  { userId: 'u-pend', username: 'partner', avatarRevision: 0, sealedName: null, email: 'partner@x.z', role: 'MEMBER', identityPublicKey: 'BB==', keyChainHead: 'BB==', pending: false },
]

const PENDING: PendingMemberInvite[] = [{ inviteId: 'inv-1', email: 'newbie@x.z', inviteStatus: 'ACTIVE', inviteExpiresAt: new Date() }]

async function mountPanel(role: 'OWNER' | 'MEMBER', pending = PENDING, members = MEMBERS) {
  vi.mocked(listScopeMembers).mockResolvedValue(members)
  vi.mocked(listMemberInvites).mockResolvedValue(pending)
  vi.mocked(listMyTenants).mockResolvedValue([myTenant({ tenantId: 't-1', name: 'Fam', domain: '127.0.0.1', role })])
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'login', component: { template: '<div/>' } }] })
  const wrapper = mount(TenantMembersPanel, { global: { plugins: [router, realI18n()], stubs } })
  await flushPromises()
  return wrapper
}

describe('TenantMembersPanel (uc.spaces.add-member, web)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    clearActiveSpace()
    activeSpace.kind = ScopeKind.Tenant
    activeSpace.scopeId = 't-1'
    activeSpace.tenantId = 't-1'
  })

  it('lists every member as active — a member holds the tenant key from its join on (ADR 0111)', async () => {
    const wrapper = await mountPanel('OWNER')
    const rows = wrapper.findAll('.member-row')
    expect(rows).toHaveLength(2)
    expect(wrapper.text()).toContain('@owner')
    expect(wrapper.text()).toContain('@partner')
    // One status badge per row, and it is "Active": there is no awaiting-access state to show any more.
    for (const row of rows) expect(row.findAll('.veyq-badge').map((b) => b.text())).toEqual([t('members.statusActive')])
    expect(rows.some((r) => r.find('.veyq-badge.warning').exists())).toBe(false)
  })

  it('shows the invite button to the owner', async () => {
    const wrapper = await mountPanel('OWNER')
    expect(wrapper.text()).toContain(t('members.invite'))
  })

  it('hides the invite button from a non-owner member (inviting is owner-only)', async () => {
    const wrapper = await mountPanel('MEMBER')
    expect(wrapper.text()).not.toContain(t('members.invite'))
  })

  it('shows the owner the pending invites (invited-but-not-joined) with management actions', async () => {
    const wrapper = await mountPanel('OWNER')
    const rows = wrapper.findAll('.invite-row')
    expect(rows).toHaveLength(1)
    expect(wrapper.text()).toContain('newbie@x.z')
    expect(wrapper.text()).toContain(t('members.pendingTitle'))
    expect(wrapper.find(`[aria-label="${t('members.reissue')}"]`).exists()).toBe(true)
    expect(wrapper.find(`[aria-label="${t('members.revoke')}"]`).exists()).toBe(true)
  })

  it('never lists pending invites for a non-owner (inviting stays owner-only)', async () => {
    const wrapper = await mountPanel('MEMBER')
    expect(listMemberInvites).not.toHaveBeenCalled()
    expect(wrapper.find('.invite-row').exists()).toBe(false)
    expect(wrapper.text()).not.toContain(t('members.pendingTitle'))
  })

  it('re-issues an invite by mail as a new invite under a new code, shows that code once, and offers no link to hand on', async () => {
    // ADR 0055 §2: the fresh link goes to the invitee. The owner gets a confirmation naming the address and nothing to
    // copy but the code (ADR 0111 decision 1) — a link is exactly what this retired, so its absence is asserted too.
    vi.mocked(reissueMemberInvite).mockResolvedValue({
      email: 'newbie@x.z', inviteStatus: 'ACTIVE', inviteExpiresAt: new Date(), delivery: 'SENT',
    })
    const wrapper = await mountPanel('OWNER')
    await wrapper.find(`[aria-label="${t('members.reissue')}"]`).trigger('click')
    await flushPromises()
    expect(reissueMemberInvite).toHaveBeenCalledWith('t-1', 'inv-1', 'newbie@x.z', { mode: 'code', code: expect.any(String) })
    const { code } = vi.mocked(reissueMemberInvite).mock.calls[0][3] as { code: string }
    expect(normalizeInviteCode(code)).toBe(code)
    expect(wrapper.get('[data-testid="reissued-invite-code"]').text()).toBe(formatInviteCode(code))
    expect(wrapper.text()).toContain(t('members.inviteSentTo', { email: 'newbie@x.z' }))
    expect(wrapper.find('.invite-link-input').exists()).toBe(false)
  })

  it('control: a re-issue the server refuses shows no code — there is no invite it would open', async () => {
    vi.mocked(reissueMemberInvite).mockRejectedValue(httpError(503, 'mail_unavailable'))
    const wrapper = await mountPanel('OWNER')
    await wrapper.find(`[aria-label="${t('members.reissue')}"]`).trigger('click')
    await flushPromises()
    expect(reissueMemberInvite).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-testid="reissued-invite-code"]').exists()).toBe(false)
  })

  it('surfaces a tenant that cannot send mail distinctly, and leaves the invite alone', async () => {
    // The 503 arrives *before* the supersede, so the outstanding invite is untouched — the message has to say
    // "configure mail", not "try again", or the owner retries a thing that cannot succeed.
    vi.mocked(reissueMemberInvite).mockRejectedValue(httpError(503, 'mail_unavailable'))
    const wrapper = await mountPanel('OWNER')
    await wrapper.find(`[aria-label="${t('members.reissue')}"]`).trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain(t('members.mailUnavailable'))
    expect(wrapper.text()).not.toContain(t('members.actionFailed'))
  })

  it('revokes an invite after confirmation', async () => {
    confirmAsk.mockResolvedValue(true)
    vi.mocked(revokeMemberInvite).mockResolvedValue(undefined)
    const wrapper = await mountPanel('OWNER')
    await wrapper.find(`[aria-label="${t('members.revoke')}"]`).trigger('click')
    await flushPromises()
    expect(revokeMemberInvite).toHaveBeenCalledWith('t-1', 'inv-1')
  })

  it('does not revoke when the confirm is dismissed', async () => {
    confirmAsk.mockResolvedValue(false)
    const wrapper = await mountPanel('OWNER')
    await wrapper.find(`[aria-label="${t('members.revoke')}"]`).trigger('click')
    await flushPromises()
    expect(revokeMemberInvite).not.toHaveBeenCalled()
  })
})

// W2 (ADR 0075 point 5): the removal confirmation offers "also turn off every link", and its answer rides on the one
// removal request. That the unticked request carries no parameter at all is asserted on the wire in members.test.ts.
describe('TenantMembersPanel — removal and the links of the space (ADR 0075)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    clearActiveSpace()
    activeSpace.kind = ScopeKind.Tenant
    activeSpace.scopeId = 't-1'
    activeSpace.tenantId = 't-1'
    vi.mocked(removeTenantMember).mockResolvedValue(undefined)
  })

  const removePartner = async () => {
    const wrapper = await mountPanel('OWNER')
    await wrapper.find(`[aria-label="${t('members.remove')}"]`).trigger('click')
    await flushPromises()
    return wrapper
  }

  it('asks with the opt-in and sends it ticked when ticked', async () => {
    confirmAskWithOption.mockResolvedValue({ option: true })
    await removePartner()
    expect(confirmAskWithOption).toHaveBeenCalledWith(expect.objectContaining({ option: expect.objectContaining({ label: t('members.removeRevokeLinks') }) }))
    expect(removeTenantMember).toHaveBeenCalledWith('t-1', 'u-pend', true)
  })

  // inbox #259: a removal cuts the app's access, not a link the member copied — that keeps opening its Memory
  // until it is renewed or turned off (uc.spaces.remove-tenant-member postconditions, ADR 0075 point 4). The
  // confirmation must not promise more, in either language.
  it('the confirmation says a copied link keeps working until renewed or turned off, in EN and DE', async () => {
    confirmAskWithOption.mockResolvedValue(null)
    await removePartner()
    expect(confirmAskWithOption).toHaveBeenCalledWith(expect.objectContaining({ message: t('members.removeConfirm') }))
    const de = (i18n.global.getLocaleMessage('de') as { members: { removeConfirm: string } }).members.removeConfirm
    for (const copy of [t('members.removeConfirm'), de]) {
      expect(copy).toMatch(/Link/i)
      expect(copy).toMatch(/renew|erneuert/)
      expect(copy).not.toMatch(/everything shared right away|alles Geteilte/)
    }
  })

  it('control: unticked, the same removal turns off no link', async () => {
    confirmAskWithOption.mockResolvedValue({ option: false })
    await removePartner()
    expect(removeTenantMember).toHaveBeenCalledWith('t-1', 'u-pend', false)
  })

  it('a declined confirmation removes no one', async () => {
    confirmAskWithOption.mockResolvedValue(null)
    await removePartner()
    expect(removeTenantMember).not.toHaveBeenCalled()
  })

  it('offers the owner "turn off every link" and sends it for the tenant space on confirm', async () => {
    confirmAsk.mockResolvedValue(true)
    vi.mocked(revokeScopeShares).mockResolvedValue(2)
    const wrapper = await mountPanel('OWNER')
    const action = wrapper.findAll('button').find((b) => b.text() === t('spaceLinks.revokeAll'))
    expect(action).toBeDefined()
    await action!.trigger('click')
    await flushPromises()
    expect(revokeScopeShares).toHaveBeenCalledWith('t-1')
  })

  it('control: a member who does not own the space is not offered it', async () => {
    const wrapper = await mountPanel('MEMBER')
    expect(wrapper.findAll('button').some((b) => b.text() === t('spaceLinks.revokeAll'))).toBe(false)
  })
})

describe('TenantMembersPanel - who is verified (ADR 0028 Decision 3)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    clearActiveSpace()
    activeSpace.kind = ScopeKind.Tenant
    activeSpace.scopeId = 't-1'
    activeSpace.tenantId = 't-1'
  })

  const rowOf = (w: Awaited<ReturnType<typeof mountPanel>>, username: string) => w.findAll('.member-row').find((r) => r.text().includes('@' + username))!

  it('shows "verified" in the member list, and an anomaly with its note — and no review or accept anywhere', async () => {
    vi.mocked(trustStates).mockResolvedValue({ 'u-own': { state: 'verified' }, 'u-pend': { state: 'anomaly' } })
    const w = await mountPanel('MEMBER')
    expect(rowOf(w, 'owner').get('[data-testid="member-trust-verified"]').text()).toBe(t('safety.stateVerified'))
    expect(rowOf(w, 'partner').get('[data-testid="member-trust-anomaly"]').text()).toBe(t('trust.anomaly'))
    expect(rowOf(w, 'partner').text()).toContain(t('trust.anomalyNote'))
    expect(rowOf(w, 'owner').find('[data-testid="member-trust-anomaly"]').exists()).toBe(false)
  })

  it('a member a rotation declared pending reads "waiting for someone who can confirm their key"', async () => {
    const w = await mountPanel('MEMBER', PENDING, [MEMBERS[0], { ...MEMBERS[1], pending: true }])
    expect(rowOf(w, 'partner').get('[data-testid="member-pending"]').text()).toBe(t('members.pending'))
    expect(rowOf(w, 'owner').find('[data-testid="member-pending"]').exists()).toBe(false)
  })

  it('control: a pinned member carries no trust badge', async () => {
    vi.mocked(trustStates).mockResolvedValue({ 'u-own': { state: 'pinned' } })
    const w = await mountPanel('MEMBER')
    expect(rowOf(w, 'owner').find('[data-testid^="member-trust-"]').exists()).toBe(false)
  })
})

// ADR 0101 decision 9: a tenant may have several owners, so its last one can hand it on before erasing their account.
describe('TenantMembersPanel - make owner (ADR 0101 decision 9)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    clearActiveSpace()
    activeSpace.kind = ScopeKind.Tenant
    activeSpace.scopeId = 't-1'
    activeSpace.tenantId = 't-1'
    vi.mocked(setTenantMemberRole).mockResolvedValue(undefined)
  })

  it('offers the owner "Make co-owner" on a MEMBER row only, and makes them an owner', async () => {
    const w = await mountPanel('OWNER')
    expect(w.find('[data-promote="u-own"]').exists()).toBe(false)
    const promote = w.get('[data-promote="u-pend"]')
    expect(promote.text()).toBe(t('members.promote'))
    vi.mocked(listScopeMembers).mockClear()
    await promote.trigger('click')
    await flushPromises()
    expect(setTenantMemberRole).toHaveBeenCalledWith('t-1', 'u-pend', 'OWNER')
    expect(listScopeMembers).toHaveBeenCalled() // the directory re-read shows the new role
  })

  it('control: a member is offered no role change at all', async () => {
    const w = await mountPanel('MEMBER')
    expect(w.find('[data-promote]').exists()).toBe(false)
    expect(w.find('[data-demote]').exists()).toBe(false)
  })

  it('an owner steps back to member; the last owner is refused in words', async () => {
    vi.mocked(setTenantMemberRole).mockRejectedValue(httpError(409, 'last_owner'))
    vi.mocked(memberError).mockResolvedValue({ status: 409, code: 'last_owner' })
    const w = await mountPanel('OWNER')
    await w.get('[data-demote="u-own"]').trigger('click')
    await flushPromises()
    expect(setTenantMemberRole).toHaveBeenCalledWith('t-1', 'u-own', 'MEMBER')
    expect(w.find('.msg').text()).toBe(t('members.lastOwner'))
  })
})
