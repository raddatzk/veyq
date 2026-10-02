// @vitest-environment happy-dom
// uc.spaces.add-member (web, cr-0077) — the member directory rubric: it lists the active tenant's members with
// a granted/awaiting-access badge, and shows the invite button only to the OWNER (admission is owner-gated).
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
  memberError: vi.fn().mockResolvedValue({ status: 0, code: null }),
}))
vi.mock('../memory/share', () => ({ revokeScopeShares: vi.fn() }))
vi.mock('./scopes', () => ({
  listMyTenants: vi.fn(),
  setSpaceIcon: vi.fn(),
  clearSpaceIcon: vi.fn(),
  openMyTenantIcon: vi.fn().mockResolvedValue(null),
}))
// Confirmation is the app's own dialog (cr-web-own-confirm-dialog): the panel awaits `ask()`, so the answer is
// given here. Stubbing `window.confirm` would now confirm nothing and pass anyway.
const { confirmAsk, confirmAskWithOption } = vi.hoisted(() => ({ confirmAsk: vi.fn(), confirmAskWithOption: vi.fn() }))
vi.mock('../../shared/state/useConfirm', () => ({ useConfirm: () => ({ ask: confirmAsk, askWithOption: confirmAskWithOption }) }))

import TenantMembersPanel from './TenantMembersPanel.vue'
import { listScopeMembers, listMemberInvites, reissueMemberInvite, revokeMemberInvite, removeTenantMember } from './members'
import { revokeScopeShares } from '../memory/share'
import { listMyTenants } from './scopes'
import { activeSpace, clearActiveSpace } from '../../app/activeSpace'
import { ScopeKind, type ScopeMember } from '../../api/generated/scopes'
import type { PendingMemberInvite } from '../../api/generated/tenants'
import { realI18n, t } from '../../test/i18n'
import { myTenant } from '../../test/models'

const stubs = {
  Button: { props: ['label', 'icon', 'ariaLabel'], template: '<button :aria-label="ariaLabel">{{ label }}</button>' },
  Message: { template: '<div class="msg"><slot /></div>' },
  InputText: { props: ['modelValue'], template: '<input :value="modelValue" />' },
  InviteMemberDialog: true,
}

// sealedName is null here (no K_tenant in the test → names stay sealed) so the rows show the @username.
const MEMBERS: ScopeMember[] = [
  { userId: 'u-own', username: 'owner', sealedName: null, email: 'owner@x.z', role: 'OWNER', identityPublicKey: 'AA==', hasGrant: true },
  { userId: 'u-pend', username: 'partner', sealedName: null, email: 'partner@x.z', role: 'MEMBER', identityPublicKey: 'BB==', hasGrant: false },
]

const PENDING: PendingMemberInvite[] = [{ inviteId: 'inv-1', email: 'newbie@x.z', inviteStatus: 'ACTIVE', inviteExpiresAt: new Date() }]

async function mountPanel(role: 'OWNER' | 'MEMBER', pending = PENDING) {
  vi.mocked(listScopeMembers).mockResolvedValue(MEMBERS)
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

  it('lists members with a granted vs awaiting-access badge', async () => {
    const wrapper = await mountPanel('OWNER')
    const rows = wrapper.findAll('.member-row')
    expect(rows).toHaveLength(2)
    expect(wrapper.text()).toContain('@owner')
    expect(wrapper.text()).toContain('@partner')
    // The pending member shows "Awaiting access"; the granted one shows "Active".
    const badges = wrapper.findAll('.veyq-badge').map((b) => b.text())
    expect(badges).toContain(t('members.statusActive'))
    expect(badges).toContain(t('members.statusAwaiting'))
    expect(wrapper.find('.veyq-badge.warning').text()).toBe(t('members.statusAwaiting'))
  })

  it('shows the invite button to the owner', async () => {
    const wrapper = await mountPanel('OWNER')
    expect(wrapper.text()).toContain(t('members.invite'))
  })

  it('hides the invite button from a non-owner member (admission is owner-only)', async () => {
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

  it('never lists pending invites for a non-owner (admission stays owner-only)', async () => {
    const wrapper = await mountPanel('MEMBER')
    expect(listMemberInvites).not.toHaveBeenCalled()
    expect(wrapper.find('.invite-row').exists()).toBe(false)
    expect(wrapper.text()).not.toContain(t('members.pendingTitle'))
  })

  it('re-issues an invite by mail and offers no link to hand on', async () => {
    // ADR 0055 §2: the fresh secret goes to the invitee. The owner gets a confirmation naming the address and
    // nothing to copy — the absence is the assertion, since a copy-link is exactly what this retired.
    vi.mocked(reissueMemberInvite).mockResolvedValue({
      email: 'newbie@x.z', inviteStatus: 'ACTIVE', inviteExpiresAt: new Date(), delivery: 'SENT',
    })
    const wrapper = await mountPanel('OWNER')
    await wrapper.find(`[aria-label="${t('members.reissue')}"]`).trigger('click')
    await flushPromises()
    expect(reissueMemberInvite).toHaveBeenCalledWith('t-1', 'inv-1')
    expect(wrapper.text()).toContain(t('members.inviteSentTo', { email: 'newbie@x.z' }))
    expect(wrapper.find('.invite-link-input').exists()).toBe(false)
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
