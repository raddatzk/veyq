// @vitest-environment happy-dom
// uc.spaces.add-member (web, cr-0077) — the member directory rubric: it lists the active tenant's members with
// a granted/awaiting-access badge, and shows the invite button only to the OWNER (admission is owner-gated).
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('./members', () => ({
  DeliveryResult: { Sent: 'SENT', FallbackCopylink: 'FALLBACK_COPYLINK', Failed: 'FAILED' },
  listScopeMembers: vi.fn(),
  listMemberInvites: vi.fn(),
  reissueMemberInvite: vi.fn(),
  revokeMemberInvite: vi.fn(),
  memberError: vi.fn().mockResolvedValue({ status: 0, code: null }),
}))
vi.mock('./scopes', () => ({
  listMyTenants: vi.fn(),
  setSpaceIcon: vi.fn(),
  clearSpaceIcon: vi.fn(),
  openMyTenantIcon: vi.fn().mockResolvedValue(null),
}))
vi.mock('../../shared/inviteLink', () => ({
  buildInviteLink: (secret: string) => `https://x.test/tenant/accept#${secret}`,
  copyText: vi.fn().mockResolvedValue(true),
}))
// Confirmation is the app's own dialog (cr-web-own-confirm-dialog): the panel awaits `ask()`, so the answer is
// given here. Stubbing `window.confirm` would now confirm nothing and pass anyway.
const { confirmAsk } = vi.hoisted(() => ({ confirmAsk: vi.fn() }))
vi.mock('../../shared/state/useConfirm', () => ({ useConfirm: () => ({ ask: confirmAsk }) }))

import TenantMembersPanel from './TenantMembersPanel.vue'
import { listScopeMembers, listMemberInvites, reissueMemberInvite, revokeMemberInvite } from './members'
import { listMyTenants } from './scopes'
import { activeSpace, clearActiveSpace } from '../../app/activeSpace'
import { ScopeKind } from '../../api/generated/scopes'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  missingWarn: false,
  fallbackWarn: false,
  messages: {
    en: {
      common: { done: 'Done' },
      members: {
        title: 'Members', sub: 'sub', invite: 'Invite member', loading: 'Loading…', empty: 'No members',
        loadFailed: 'Failed', notAuthorized: 'Not authorized', statusActive: 'Active', statusAwaiting: 'Awaiting access',
        pendingTitle: 'Pending invites', pendingSub: 'pending sub', statusInvited: 'Invited', reissue: 'Send again',
        revoke: 'Cancel invite', revokeConfirm: 'Cancel?', inviteRevoked: 'Invite cancelled.',
        actionFailed: 'Action failed', copyLink: 'Copy link', copied: 'Copied',
        inviteSentTo: 'Sent to {email}.', inviteRateLimited: 'Too many invites',
        mailUnavailable: 'This space cannot send email yet', mailSendFailed: 'The email could not be sent',
      },
    },
  },
})

const stubs = {
  Button: { props: ['label', 'icon', 'ariaLabel'], template: '<button :aria-label="ariaLabel">{{ label }}</button>' },
  Message: { template: '<div class="msg"><slot /></div>' },
  InputText: { props: ['modelValue'], template: '<input :value="modelValue" />' },
  InviteMemberDialog: true,
}

// sealedName is null here (no K_tenant in the test → names stay sealed) so the rows show the @username.
const MEMBERS = [
  { userId: 'u-own', username: 'owner', sealedName: null, email: 'owner@x.z', role: 'OWNER', identityPublicKey: 'AA==', hasGrant: true },
  { userId: 'u-pend', username: 'partner', sealedName: null, email: 'partner@x.z', role: 'MEMBER', identityPublicKey: 'BB==', hasGrant: false },
]

const PENDING = [{ inviteId: 'inv-1', email: 'newbie@x.z', inviteStatus: 'ACTIVE', inviteExpiresAt: new Date() }]

async function mountPanel(role: 'OWNER' | 'MEMBER', pending = PENDING) {
  vi.mocked(listScopeMembers).mockResolvedValue(MEMBERS as never)
  vi.mocked(listMemberInvites).mockResolvedValue(pending as never)
  vi.mocked(listMyTenants).mockResolvedValue([{ tenantId: 't-1', name: 'Fam', domain: '127.0.0.1', role }] as never)
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'login', component: { template: '<div/>' } }] })
  const wrapper = mount(TenantMembersPanel, { global: { plugins: [router, i18n], stubs } })
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
    expect(badges).toContain('Active')
    expect(badges).toContain('Awaiting access')
    expect(wrapper.find('.veyq-badge.warning').text()).toBe('Awaiting access')
  })

  it('shows the invite button to the owner', async () => {
    const wrapper = await mountPanel('OWNER')
    expect(wrapper.text()).toContain('Invite member')
  })

  it('hides the invite button from a non-owner member (admission is owner-only)', async () => {
    const wrapper = await mountPanel('MEMBER')
    expect(wrapper.text()).not.toContain('Invite member')
  })

  it('shows the owner the pending invites (invited-but-not-joined) with management actions', async () => {
    const wrapper = await mountPanel('OWNER')
    const rows = wrapper.findAll('.invite-row')
    expect(rows).toHaveLength(1)
    expect(wrapper.text()).toContain('newbie@x.z')
    expect(wrapper.text()).toContain('Pending invites')
    expect(wrapper.find('[aria-label="Send again"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="Cancel invite"]').exists()).toBe(true)
  })

  it('never lists pending invites for a non-owner (admission stays owner-only)', async () => {
    const wrapper = await mountPanel('MEMBER')
    expect(listMemberInvites).not.toHaveBeenCalled()
    expect(wrapper.find('.invite-row').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Pending invites')
  })

  it('re-issues an invite by mail and offers no link to hand on', async () => {
    // ADR 0055 §2: the fresh secret goes to the invitee. The owner gets a confirmation naming the address and
    // nothing to copy — the absence is the assertion, since a copy-link is exactly what this retired.
    vi.mocked(reissueMemberInvite).mockResolvedValue({ delivery: 'SENT' } as never)
    const wrapper = await mountPanel('OWNER')
    await wrapper.find('[aria-label="Send again"]').trigger('click')
    await flushPromises()
    expect(reissueMemberInvite).toHaveBeenCalledWith('t-1', 'inv-1')
    expect(wrapper.text()).toContain('Sent to newbie@x.z.')
    expect(wrapper.find('.invite-link-input').exists()).toBe(false)
    expect(wrapper.findAll('button').some((b) => b.text() === 'Copy link')).toBe(false)
  })

  it('surfaces a tenant that cannot send mail distinctly, and leaves the invite alone', async () => {
    // The 503 arrives *before* the supersede, so the outstanding invite is untouched — the message has to say
    // "configure mail", not "try again", or the owner retries a thing that cannot succeed.
    vi.mocked(reissueMemberInvite).mockRejectedValue(
      Object.assign(new Error('503'), { response: new Response(JSON.stringify({ code: 'mail_unavailable' }), { status: 503 }) }),
    )
    const wrapper = await mountPanel('OWNER')
    await wrapper.find('[aria-label="Send again"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('This space cannot send email yet')
    expect(wrapper.text()).not.toContain('Action failed')
  })

  it('revokes an invite after confirmation', async () => {
    confirmAsk.mockResolvedValue(true)
    vi.mocked(revokeMemberInvite).mockResolvedValue(undefined)
    const wrapper = await mountPanel('OWNER')
    await wrapper.find('[aria-label="Cancel invite"]').trigger('click')
    await flushPromises()
    expect(revokeMemberInvite).toHaveBeenCalledWith('t-1', 'inv-1')
  })

  it('does not revoke when the confirm is dismissed', async () => {
    confirmAsk.mockResolvedValue(false)
    const wrapper = await mountPanel('OWNER')
    await wrapper.find('[aria-label="Cancel invite"]').trigger('click')
    await flushPromises()
    expect(revokeMemberInvite).not.toHaveBeenCalled()
  })
})
