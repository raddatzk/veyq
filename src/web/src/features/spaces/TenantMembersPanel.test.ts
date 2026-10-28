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
  rekeyTenant: vi.fn(),
  setTenantMemberRole: vi.fn(),
  SetTenantMemberRoleRequestRoleEnum: { Owner: 'OWNER', Member: 'MEMBER' },
  memberError: vi.fn(),
  prepareInviteCode: vi.fn(),
}))
vi.mock('./scopes', () => ({
  listMyTenants: vi.fn(),
  setSpaceIcon: vi.fn(),
  clearSpaceIcon: vi.fn(),
  openMyTenantIcon: vi.fn(),
  revokeScopeShares: vi.fn(),
  scopeLifecycle: vi.fn(),
}))
// The tenant's badge read: {} (no badge) unless a test says otherwise - what the vault-less real one degraded to.
vi.mock('../account/tenantTrust', async (orig) => ({ ...(await orig<typeof import('../account/tenantTrust')>()), tenantBadges: vi.fn() }))
// Confirmation is the app's own dialog (cr-web-own-confirm-dialog): the panel awaits `ask()`, so the answer is
// given here. Stubbing `window.confirm` would now confirm nothing and pass anyway.
const { confirmAsk, confirmAskWithOptions } = vi.hoisted(() => ({ confirmAsk: vi.fn(), confirmAskWithOptions: vi.fn() }))
vi.mock('../../shared/state/useConfirm', () => ({ useConfirm: () => ({ ask: confirmAsk, askWithOptions: confirmAskWithOptions }) }))
// The node re-key after a rotation is rekeyScope.test.ts's; here only that it runs, and after what.
vi.mock('../../core/node/rekey/rekeyScope', async (orig) => ({ ...(await orig<typeof import('../../core/node/rekey/rekeyScope')>()), rekeyScope: vi.fn() }))
// The tenant keys the active space holds: none unless a test hands it some (then the names open on the device).
const held = vi.hoisted(() => ({ write: null as Uint8Array | null, epochs: null as EpochKeys | null }))
vi.mock('../../app/activeSpace', async (orig) => ({
  ...(await orig<typeof import('../../app/activeSpace')>()),
  activeScopeKey: () => held.write,
  activeEpochKeys: () => held.epochs,
  refreshActiveScope: vi.fn(),
}))

import TenantMembersPanel from './TenantMembersPanel.vue'
import { listScopeMembers, listMemberInvites, reissueMemberInvite, revokeMemberInvite, removeTenantMember, rekeyTenant, setTenantMemberRole, memberError, prepareInviteCode, type InvitePhase } from './members'
import { rekeyScope } from '../../core/node/rekey/rekeyScope'
import { listMyTenants, openMyTenantIcon, revokeScopeShares, scopeLifecycle } from './scopes'
import { tenantBadges } from '../account/tenantTrust'
import { account } from '../../app/account'
import { activeSpace, clearActiveSpace, refreshActiveScope } from '../../app/activeSpace'
import { ScopeKind, type ScopeMember } from '../../api/generated/scopes'
import type { PendingMemberInvite } from '../../api/generated/tenants'
import { realI18n, t } from '../../test/i18n'
import { i18n } from '../../i18n'
import { myTenant } from '../../test/models'
import { formatInviteCode, generateInviteCode, normalizeInviteCode } from '@veyq/crypto/invite'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import { deriveEpochKeys, epochLinkContext, type EpochKeys } from './epochs'
import { sealName } from '../account/profile/memberName'

const stubs = {
  VeyqButton: { props: ['label', 'icon', 'ariaLabel'], template: '<button :aria-label="ariaLabel">{{ label }}</button>' },
  VeyqMessage: { template: '<div class="msg"><slot /></div>' },
  VeyqInput: { props: ['modelValue'], template: '<input :value="modelValue" />' },
  InviteMemberDialog: true,
}

/** A code as `prepareInviteCode` mints it, its link key's derivation stood in for: `discard` is what zeroes it. */
const preparedCode = () => ({ mode: 'code' as const, code: generateInviteCode(), ahead: { take: vi.fn(() => null), discard: vi.fn() } })
/** The codes the panel prepared, in order. */
const prepared = () => vi.mocked(prepareInviteCode).mock.results.map((r) => r.value as ReturnType<typeof preparedCode>)

beforeEach(() => {
  vi.mocked(prepareInviteCode).mockImplementation(preparedCode)
  vi.mocked(tenantBadges).mockResolvedValue({})
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
    expect(reissueMemberInvite).toHaveBeenCalledWith('t-1', 'inv-1', 'newbie@x.z', expect.objectContaining({ mode: 'code', code: expect.any(String) }), expect.any(Function))
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

// inbox #630: a re-issue builds and mails a whole new invite - the link key's Argon2id included - so it says what it is
// doing, as the invite dialog does (#566): its row names the phase, every re-issue button is disabled while it runs (a
// double click sends nothing twice), and its code is prepared as the owner sees the pending list, so the derivation has
// begun before the click.
describe('TenantMembersPanel - a re-issue says what it is doing (inbox #630)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    clearActiveSpace()
    activeSpace.kind = ScopeKind.Tenant
    activeSpace.scopeId = 't-1'
    activeSpace.tenantId = 't-1'
  })
  const SENT = { email: 'newbie@x.z', inviteStatus: 'ACTIVE', inviteExpiresAt: new Date(), delivery: 'SENT' } as const

  it('while it runs its row names the phase and every re-issue button is disabled; the line goes when it is done', async () => {
    let phase!: (p: InvitePhase) => void
    let finish!: () => void
    vi.mocked(reissueMemberInvite).mockImplementation((_t, _i, _e, _s, onPhase) => {
      phase = onPhase!
      onPhase!('securing')
      return new Promise((resolve) => (finish = () => resolve(SENT)))
    })
    const wrapper = await mountPanel('OWNER', [...PENDING, { ...PENDING[0], inviteId: 'inv-2', email: 'other@x.z' }])
    const buttons = () => wrapper.findAll(`[aria-label="${t('members.reissue')}"]`)
    const progress = () => wrapper.find('[data-testid="reissue-progress"]')
    expect(progress().exists()).toBe(false)
    await buttons()[0].trigger('click')
    await flushPromises()
    expect(progress().text()).toBe(t('members.inviteSecuring'))
    expect(wrapper.findAll('.invite-row')[0].find('[data-testid="reissue-progress"]').exists(), 'on the re-issued row').toBe(true)
    expect(buttons().map((b) => b.attributes('disabled') !== undefined)).toEqual([true, true])
    phase('sending')
    await flushPromises()
    expect(progress().text()).toBe(t('members.inviteSending'))
    await buttons()[0].trigger('click') // a double click: nothing more is sent
    await buttons()[1].trigger('click')
    expect(reissueMemberInvite).toHaveBeenCalledOnce()
    finish()
    await flushPromises()
    expect(progress().exists()).toBe(false)
    expect(buttons().map((b) => b.attributes('disabled') !== undefined)).toEqual([false, false])
  })

  it('its code is prepared as the owner sees the pending list - the re-issue sends that very one, and the next is armed', async () => {
    vi.mocked(reissueMemberInvite).mockResolvedValue(SENT)
    const wrapper = await mountPanel('OWNER')
    expect(prepareInviteCode, 'before any click').toHaveBeenCalledOnce()
    await wrapper.get(`[aria-label="${t('members.reissue')}"]`).trigger('click')
    await flushPromises()
    const [first] = prepared()
    expect(vi.mocked(reissueMemberInvite).mock.calls[0][3]).toBe(first)
    expect(wrapper.get('[data-testid="reissued-invite-code"]').text()).toBe(formatInviteCode(first.code))
    expect(prepareInviteCode, 'the next re-issue\'s code').toHaveBeenCalledTimes(2)
  })

  it('a failed re-issue zeroes what its code derived and the next one sends a fresh code', async () => {
    vi.mocked(reissueMemberInvite).mockRejectedValueOnce(httpError(503, 'mail_send_failed')).mockResolvedValue(SENT)
    const wrapper = await mountPanel('OWNER')
    await wrapper.get(`[aria-label="${t('members.reissue')}"]`).trigger('click')
    await flushPromises()
    const [failed, next] = prepared()
    expect(failed.ahead.discard).toHaveBeenCalled()
    await wrapper.get(`[aria-label="${t('members.reissue')}"]`).trigger('click')
    await flushPromises()
    expect(vi.mocked(reissueMemberInvite).mock.calls[1][3]).toBe(next)
    expect(next.code).not.toBe(failed.code)
  })

  it('nothing is prepared without a pending invite to re-issue, and an unused code is zeroed when the panel closes', async () => {
    await mountPanel('MEMBER')
    await mountPanel('OWNER', [])
    expect(prepareInviteCode).not.toHaveBeenCalled()
    const wrapper = await mountPanel('OWNER')
    const [unused] = prepared()
    wrapper.unmount()
    expect(unused.ahead.discard).toHaveBeenCalled()
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
    confirmAskWithOptions.mockResolvedValue({ options: [true, false] })
    await removePartner()
    expect(confirmAskWithOptions).toHaveBeenCalledWith(expect.objectContaining({
      options: [expect.objectContaining({ label: t('members.removeRevokeLinks') }), expect.objectContaining({ label: t('spaceLock.removeOption') })],
    }))
    expect(removeTenantMember).toHaveBeenCalledWith('t-1', 'u-pend', true)
    expect(rekeyTenant).not.toHaveBeenCalled()
  })

  // inbox #259: a removal cuts the app's access, not a link the member copied — that keeps opening its Memory
  // until it is renewed or turned off (uc.spaces.remove-tenant-member postconditions, ADR 0075 point 4). The
  // confirmation must not promise more, in either language.
  it('the confirmation says a copied link keeps working until renewed or turned off, in EN and DE', async () => {
    confirmAskWithOptions.mockResolvedValue(null)
    await removePartner()
    expect(confirmAskWithOptions).toHaveBeenCalledWith(expect.objectContaining({ message: t('members.removeConfirm') }))
    const de = (i18n.global.getLocaleMessage('de') as { members: { removeConfirm: string } }).members.removeConfirm
    for (const copy of [t('members.removeConfirm'), de]) {
      expect(copy).toMatch(/Link/i)
      expect(copy).toMatch(/renew|erneuert/)
      expect(copy).not.toMatch(/everything shared right away|alles Geteilte/)
    }
  })

  it('control: unticked, the same removal turns off no link', async () => {
    confirmAskWithOptions.mockResolvedValue({ options: [false, false] })
    await removePartner()
    expect(removeTenantMember).toHaveBeenCalledWith('t-1', 'u-pend', false)
  })

  it('a declined confirmation removes no one', async () => {
    confirmAskWithOptions.mockResolvedValue(null)
    await removePartner()
    expect(removeTenantMember).not.toHaveBeenCalled()
    expect(rekeyTenant).not.toHaveBeenCalled()
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

describe('TenantMembersPanel - who is verified in this tenant (ADR 0111 decision 15)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    clearActiveSpace()
    activeSpace.kind = ScopeKind.Tenant
    activeSpace.scopeId = 't-1'
    activeSpace.tenantId = 't-1'
  })

  const rowOf = (w: Awaited<ReturnType<typeof mountPanel>>, username: string) => w.findAll('.member-row').find((r) => r.text().includes('@' + username))!

  it('shows "verified" in the member list, and an anomaly with its note — and no review or accept anywhere', async () => {
    account.userId = 'u-me'
    vi.mocked(tenantBadges).mockResolvedValue({ 'u-own': 'verified', 'u-pend': 'anomaly' })
    const w = await mountPanel('MEMBER')
    // the active tenant's badges, without this account's own row
    expect(tenantBadges).toHaveBeenCalledWith('t-1', 'u-me')
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

  it('a member first seen under two weeks ago reads "New"', async () => {
    vi.mocked(tenantBadges).mockResolvedValue({ 'u-own': 'new' })
    const w = await mountPanel('MEMBER')
    expect(rowOf(w, 'owner').get('[data-testid="member-trust-new"]').text()).toBe(t('safety.stateNew'))
  })

  it('control: a member nobody verified carries no trust badge', async () => {
    vi.mocked(tenantBadges).mockResolvedValue({ 'u-own': 'unverified' })
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

describe('TenantMembersPanel - names across a tenant rotation (inbox #573)', () => {
  const T = '5c09e000-0000-4000-8000-0000000005a3'
  const OLGA = '5c09e000-0000-4000-8000-00000000b001'
  const PAUL = '5c09e000-0000-4000-8000-00000000b002'
  beforeEach(() => {
    vi.clearAllMocks()
    clearActiveSpace()
    activeSpace.kind = ScopeKind.Tenant
    activeSpace.scopeId = T
    activeSpace.tenantId = T
  })

  it('shows the real name a member saved under the epoch before the rotation, opened with the key peeled from the chain', async () => {
    const e0 = v1.randomBytes(32)
    const e1 = v1.randomBytes(32)
    const epochs = deriveEpochKeys(T, e1, 1, [{ epoch: 1, wrappedPrev: b64std(v1.seal(epochLinkContext(T, 1), e1, e0)) }])
    held.write = epochs.keys.get(1)!
    held.epochs = epochs
    try {
      const members: ScopeMember[] = [
        { ...MEMBERS[0], userId: OLGA, sealedName: sealName(e0, { scopeId: T, userId: OLGA }, { firstName: 'Olga', lastName: 'Owner' }) },
        { ...MEMBERS[1], userId: PAUL, sealedName: sealName(e1, { scopeId: T, userId: PAUL }, { firstName: 'Paul', lastName: 'Partner' }) },
      ]
      const wrapper = await mountPanel('MEMBER', [], members)
      const titles = wrapper.findAll('.member-row .veyq-row-title').map((x) => x.text())
      expect(titles).toEqual(['Olga Owner', 'Paul Partner'])
    } finally {
      held.write = null
      held.epochs = null
    }
  })
})

// ADR 0025 § 2: a tenant owner gives the space a new lock — after a removal, opted in, or on its own. A removal stays
// ACL-revoke unless asked; the rotation is the plain one (rekeyTenant, members.test.ts) and the nodes follow (ADR 0109).
describe('TenantMembersPanel - a new lock for the space (ADR 0025 § 2)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    clearActiveSpace()
    activeSpace.kind = ScopeKind.Tenant
    activeSpace.scopeId = 't-1'
    activeSpace.tenantId = 't-1'
    vi.mocked(removeTenantMember).mockResolvedValue(undefined)
    vi.mocked(rekeyTenant).mockResolvedValue(1)
    vi.mocked(rekeyScope).mockResolvedValue({ done: true, committed: 1 })
  })

  const removePartner = async () => {
    const wrapper = await mountPanel('OWNER')
    await wrapper.find(`[aria-label="${t('members.remove')}"]`).trigger('click')
    await flushPromises()
    return wrapper
  }
  const lockAction = (w: Awaited<ReturnType<typeof mountPanel>>) => w.findAll('button').find((b) => b.text() === t('spaceLock.action'))

  it('ticked, the removal lands first, then the rotation without the removed member, then the re-key of the nodes', async () => {
    confirmAskWithOptions.mockResolvedValue({ options: [false, true] })
    await removePartner()
    expect(removeTenantMember).toHaveBeenCalledWith('t-1', 'u-pend', false)
    expect(rekeyTenant).toHaveBeenCalledWith('t-1', 'u-pend')
    expect(rekeyScope).toHaveBeenCalledWith('t-1')
    const order = (f: unknown) => vi.mocked(f as () => void).mock.invocationCallOrder[0]
    expect(order(removeTenantMember)).toBeLessThan(order(rekeyTenant))
    expect(order(rekeyTenant)).toBeLessThan(order(rekeyScope))
    expect(refreshActiveScope).toHaveBeenCalled()
  })

  it('a removal the server refuses gives no new lock', async () => {
    confirmAskWithOptions.mockResolvedValue({ options: [false, true] })
    vi.mocked(removeTenantMember).mockRejectedValue(httpError(409, 'last_owner'))
    const wrapper = await removePartner()
    expect(rekeyTenant).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain(t('members.actionFailed'))
  })

  it('a rotation that fails after the removal says the member is gone but the lock is not in', async () => {
    confirmAskWithOptions.mockResolvedValue({ options: [false, true] })
    vi.mocked(rekeyTenant).mockRejectedValue(httpError(409, 'rotation_incomplete'))
    const wrapper = await removePartner()
    expect(removeTenantMember).toHaveBeenCalled()
    expect(rekeyScope).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain(t('spaceLock.removedNotRekeyed'))
  })

  it('offers the owner the new lock on its own, and gives it with nobody removed on confirm', async () => {
    confirmAsk.mockResolvedValue(true)
    const wrapper = await mountPanel('OWNER')
    await lockAction(wrapper)!.trigger('click')
    await flushPromises()
    expect(confirmAsk).toHaveBeenCalledWith(expect.objectContaining({ message: t('spaceLock.confirm') }))
    expect(rekeyTenant).toHaveBeenCalledWith('t-1', undefined)
    expect(rekeyScope).toHaveBeenCalledWith('t-1')
    expect(removeTenantMember).not.toHaveBeenCalled()
  })

  it('control: declined, nothing rotates; a member who does not own the space is not offered it', async () => {
    confirmAsk.mockResolvedValue(false)
    const owner = await mountPanel('OWNER')
    await lockAction(owner)!.trigger('click')
    await flushPromises()
    expect(rekeyTenant).not.toHaveBeenCalled()

    const member = await mountPanel('MEMBER')
    expect(lockAction(member)).toBeUndefined()
  })

  it('a standalone rotation that fails says nothing was changed', async () => {
    confirmAsk.mockResolvedValue(true)
    vi.mocked(rekeyTenant).mockRejectedValue(httpError(409, 'stale_epoch'))
    const wrapper = await mountPanel('OWNER')
    await lockAction(wrapper)!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain(t('spaceLock.failed'))
  })

  // The copy says what the lock protects and what it does not, in both languages: what is added from now on is closed to
  // the old key; what was already seen stays seen, and links made before keep working.
  it('the confirmation names what it protects and what it does not, in EN and DE', () => {
    const de = (i18n.global.getLocaleMessage('de') as { spaceLock: { confirm: string } }).spaceLock.confirm
    for (const copy of [t('spaceLock.confirm'), de]) {
      expect(copy).toMatch(/from now on|ab jetzt/)
      expect(copy).toMatch(/already saw stays seen|schon gesehen wurde, bleibt gesehen/)
      expect(copy).toMatch(/links made before keep working|vorher erstellte Links funktionieren weiter/i)
    }
  })
})
