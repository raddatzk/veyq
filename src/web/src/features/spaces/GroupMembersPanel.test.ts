// @vitest-environment happy-dom
// uc.spaces.set-space-icon (web, GROUP slice) — the group settings panel's identity header: a group OWNER may
// set/replace/remove the group's icon; a non-owner sees no icon controls. The seal/peel crypto lives in
// scopes.test.ts; here we pin the owner-gated UI + that save/remove call setSpaceIcon/clearSpaceIcon and signal
// the sidebar (bumpSpaceIcon).
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('./members', () => ({
  listScopeMembers: vi.fn(),
  addGroupMember: vi.fn(),
  resolveGroupMember: vi.fn(),
  setMemberRole: vi.fn(),
  removeGroupMember: vi.fn(),
  rekeySpace: vi.fn(),
  memberError: vi.fn(),
  SetMemberRoleRequestRoleEnum: { Owner: 'OWNER', Member: 'MEMBER' },
}))
vi.mock('./scopes', () => ({
  rotateGroupForRemoval: vi.fn(),
  deleteGroup: vi.fn(),
  restoreGroup: vi.fn(),
  scopeLifecycle: vi.fn(),
  wrapGroupNameKeyForInvitee: vi.fn(),
  renameGroup: vi.fn(),
  setSpaceIcon: vi.fn(),
  clearSpaceIcon: vi.fn(),
  openGroupIcon: vi.fn(),
  requeueScopeRotation: vi.fn(),
  revokeScopeShares: vi.fn(),
}))
vi.mock('../account/identityPins', () => ({
  IdentityUnanchoredError: class extends Error {},
}))
vi.mock('../account/tenantTrust', () => ({ tenantBadges: vi.fn() }))
vi.mock('../account/profile/profile', () => ({ getMyProfile: vi.fn() }))
vi.mock('../tasks/scopeRotationWorker', () => ({ runScopeRotationWorker: vi.fn() }))
vi.mock('./spaceIconSignal', () => ({ bumpSpaceIcon: vi.fn() }))
// The node re-key after a rotation is rekeyScope.test.ts's; here only that it runs, and after what.
vi.mock('../../core/node/rekey/rekeyScope', async (orig) => ({ ...(await orig<typeof import('../../core/node/rekey/rekeyScope')>()), rekeyScope: vi.fn() }))
const { toastSuccess } = vi.hoisted(() => ({ toastSuccess: vi.fn() }))
vi.mock('../../shared/state/useToast', () => ({ useToast: () => ({ success: toastSuccess, error: vi.fn() }) }))
// Confirmation is the app's own dialog (cr-web-own-confirm-dialog): the panel awaits `ask()`, so the answer is
// given here. Stubbing `window.confirm` would now confirm nothing and pass anyway.
const { confirmAsk } = vi.hoisted(() => ({ confirmAsk: vi.fn() }))
vi.mock('../../shared/state/useConfirm', () => ({ useConfirm: () => ({ ask: confirmAsk }) }))

import GroupMembersPanel from './GroupMembersPanel.vue'
import { listScopeMembers, removeGroupMember, resolveGroupMember, addGroupMember, memberError, rekeySpace } from './members'
import { rekeyScope } from '../../core/node/rekey/rekeyScope'
import { httpError } from '../../test/httpError'
import { setSpaceIcon, clearSpaceIcon, openGroupIcon, rotateGroupForRemoval, wrapGroupNameKeyForInvitee, scopeLifecycle, requeueScopeRotation, revokeScopeShares } from './scopes'
import { IdentityUnanchoredError } from '../account/identityPins'
import { tenantBadges } from '../account/tenantTrust'
import { activeSpace } from '../../app/activeSpace'
import { getMyProfile } from '../account/profile/profile'
import { bumpSpaceIcon } from './spaceIconSignal'
import { realI18n, t } from '../../test/i18n'
import { i18n } from '../../i18n'
import type { ScopeMember } from '../../api/generated/scopes'
import { memberProfile } from '../../test/models'

const stubs = {
  VeyqButton: { props: ['label', 'icon', 'ariaLabel'], emits: ['click'], template: '<button :aria-label="ariaLabel" @click="$emit(\'click\')">{{ label }}</button>' },
  VeyqMessage: { template: '<div class="msg"><slot /></div>' },
  VeyqInput: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  TenantAvatar: { props: ['name', 'icon'], template: '<span class="avatar" :data-icon="icon">{{ name }}</span>' },
  SpaceIconDialog: {
    emits: ['save', 'close'],
    setup(_: unknown, { emit }: { emit: (e: string, ...a: unknown[]) => void }) {
      return { doSave: () => emit('save', new Uint8Array([1, 2, 3])) }
    },
    template: '<div class="icon-dialog"><button class="dialog-save" @click="doSave">save</button></div>',
  },
  VeyqCheckbox: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input type="checkbox" class="revoke-links" :checked="modelValue" @change="$emit(\'update:modelValue\', $event.target.checked)" />',
  },
}

const OWNER_ROW: ScopeMember[] = [
  { userId: 'u-own', username: 'owner', avatarRevision: 0, role: 'OWNER', identityPublicKey: 'AA==', keyChainHead: 'AA==', pending: false, pendingConsent: false },
]

async function mountPanel(opts: { me: string; icon?: Uint8Array | null } = { me: 'owner' }) {
  vi.mocked(listScopeMembers).mockResolvedValue(OWNER_ROW)
  vi.mocked(getMyProfile).mockResolvedValue(memberProfile({ username: opts.me }))
  vi.mocked(openGroupIcon).mockResolvedValue(opts.icon ?? null)
  const wrapper = mount(GroupMembersPanel, { props: { scopeId: 'g-1', name: 'Familie' }, global: { plugins: [realI18n()], stubs } })
  await flushPromises()
  return wrapper
}

const button = (w: ReturnType<typeof mount>, label: string) => w.findAll('button').find((b) => b.text() === label)

beforeEach(() => {
  vi.mocked(rekeyScope).mockResolvedValue({ done: true, committed: 1 })
  vi.mocked(memberError).mockResolvedValue({ status: 0, code: null })
  vi.mocked(tenantBadges).mockResolvedValue({})
  vi.mocked(scopeLifecycle).mockResolvedValue(null)
  vi.mocked(setSpaceIcon).mockResolvedValue(undefined)
  vi.mocked(clearSpaceIcon).mockResolvedValue(undefined)
  vi.mocked(openGroupIcon).mockResolvedValue(null)
})

describe('GroupMembersPanel — space icon (GROUP slice)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    globalThis.URL.createObjectURL = vi.fn(() => 'blob:mock')
    globalThis.URL.revokeObjectURL = vi.fn()
    confirmAsk.mockResolvedValue(true)
  })

  it('shows the owner an "add icon" control when the group has no icon (and no remove)', async () => {
    const w = await mountPanel({ me: 'owner' })
    expect(button(w, t('spaceIcon.add'))).toBeTruthy()
    expect(button(w, t('spaceIcon.remove'))).toBeFalsy()
  })

  it('hides all icon controls from a non-owner member', async () => {
    const w = await mountPanel({ me: 'stranger' }) // not in the OWNER_ROW → isOwner false
    expect(button(w, t('spaceIcon.add'))).toBeFalsy()
    expect(button(w, t('spaceIcon.change'))).toBeFalsy()
    expect(button(w, t('spaceIcon.remove'))).toBeFalsy()
  })

  it('owner sets an icon: the dialog save seals via setSpaceIcon and signals the sidebar', async () => {
    const w = await mountPanel({ me: 'owner' })
    await button(w, t('spaceIcon.add'))!.trigger('click') // open the dialog
    expect(w.find('.icon-dialog').exists()).toBe(true)
    await w.find('.dialog-save').trigger('click') // the stub emits save(bytes)
    await flushPromises()
    expect(setSpaceIcon).toHaveBeenCalledWith('g-1', new Uint8Array([1, 2, 3]))
    expect(bumpSpaceIcon).toHaveBeenCalled()
  })

  it('owner with an existing icon sees change + remove; remove clears via clearSpaceIcon', async () => {
    const w = await mountPanel({ me: 'owner', icon: new Uint8Array([9, 9, 9]) })
    expect(button(w, t('spaceIcon.change'))).toBeTruthy()
    const remove = button(w, t('spaceIcon.remove'))
    expect(remove).toBeTruthy()
    await remove!.trigger('click')
    await flushPromises()
    expect(clearSpaceIcon).toHaveBeenCalledWith('g-1')
    expect(bumpSpaceIcon).toHaveBeenCalled()
  })
})

describe('GroupMembersPanel — removing a member is one request (ADR 0024 Decision 4)', () => {
  const BOB: ScopeMember = { userId: 'u-bob', username: 'bob', avatarRevision: 0, role: 'MEMBER', identityPublicKey: 'Qk9C', keyChainHead: 'Qk9C', pending: false, pendingConsent: false }
  const PICKED = [...OWNER_ROW, BOB]

  beforeEach(() => {
    vi.clearAllMocks()
    globalThis.URL.createObjectURL = vi.fn(() => 'blob:mock')
    globalThis.URL.revokeObjectURL = vi.fn()
  })

  async function removeBob(tickRevokeLinks = false) {
    // The server lists bob before the removal — and keeps listing him after it.
    vi.mocked(listScopeMembers).mockResolvedValue(PICKED)
    vi.mocked(getMyProfile).mockResolvedValue(memberProfile({ username: 'owner' }))
    vi.mocked(openGroupIcon).mockResolvedValue(null)
    vi.mocked(rotateGroupForRemoval).mockResolvedValue(1)
    const w = mount(GroupMembersPanel, { props: { scopeId: 'g-1', name: 'Familie' }, global: { plugins: [realI18n()], stubs } })
    await flushPromises()
    await button(w, t('groups.remove'))!.trigger('click') // bob's row — the owner cannot remove themselves here
    await flushPromises()
    if (tickRevokeLinks) await w.find('input.revoke-links').setValue(true) // ADR 0075's opt-in
    await button(w, t('groups.removeConfirmYes'))!.trigger('click') // the inline confirm
    await flushPromises()
    return w
  }

  it('rotates once, naming the member it removes, over the list the owner picked from — and deletes nothing on its own', async () => {
    const w = await removeBob()
    expect(removeGroupMember).not.toHaveBeenCalled()
    expect(rotateGroupForRemoval).toHaveBeenCalledTimes(1)
    // the fourth argument is ADR 0075's "turn off every link" — unchecked unless the owner ticks it
    expect(rotateGroupForRemoval).toHaveBeenCalledWith('g-1', PICKED, 'u-bob', false)
    // A re-listing server does not bring the removed member back into view.
    expect(w.text()).not.toContain('@bob')
    expect(w.text()).toContain('@owner') // control: the remaining member is still listed
  })

  it('carries the ticked "turn off every link" opt-in into the same removal request (ADR 0075)', async () => {
    await removeBob(true)
    expect(rotateGroupForRemoval).toHaveBeenCalledTimes(1)
    expect(rotateGroupForRemoval).toHaveBeenCalledWith('g-1', PICKED, 'u-bob', true)
  })
})

// W3 (ADR 0075 point 5): a group owner turns off every link of the group at once, behind the confirmation dialog.
describe('GroupMembersPanel — turn off every link (ADR 0075)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    globalThis.URL.createObjectURL = vi.fn(() => 'blob:mock')
    globalThis.URL.revokeObjectURL = vi.fn()
  })

  it('offers it to the owner and, confirmed, sends it for this group', async () => {
    confirmAsk.mockResolvedValue(true)
    vi.mocked(revokeScopeShares).mockResolvedValue(3)
    const w = await mountPanel({ me: 'owner' })
    await button(w, t('spaceLinks.revokeAll'))!.trigger('click')
    await flushPromises()
    expect(confirmAsk).toHaveBeenCalledWith(expect.objectContaining({ message: t('spaceLinks.revokeAllConfirm') }))
    expect(revokeScopeShares).toHaveBeenCalledWith('g-1')
    // the result names the count, in the catalog's plural form
    expect(toastSuccess).toHaveBeenCalledWith(i18n.global.t('spaceLinks.revokedAll', { count: 3 }, 3))
    expect(i18n.global.t('spaceLinks.revokedAll', { count: 3 }, 3)).toContain('3')
  })

  it('sends nothing when the confirmation is declined', async () => {
    confirmAsk.mockResolvedValue(false)
    const w = await mountPanel({ me: 'owner' })
    await button(w, t('spaceLinks.revokeAll'))!.trigger('click')
    await flushPromises()
    expect(revokeScopeShares).not.toHaveBeenCalled()
  })

  it('control: a member who does not own the group is not offered it', async () => {
    const w = await mountPanel({ me: 'stranger' })
    expect(button(w, t('spaceLinks.revokeAll'))).toBeFalsy()
  })
})

// ADR 0025 § 1 (inbox #660): a group owner gives the group a new lock with nobody removed — the tenant panel's action,
// for a member's lost device. The rotation is the plain one (rekeySpace, members.test.ts) and the nodes follow (ADR 0109).
describe('GroupMembersPanel — a new lock for the group (ADR 0025 § 1)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    globalThis.URL.createObjectURL = vi.fn(() => 'blob:mock')
    globalThis.URL.revokeObjectURL = vi.fn()
    vi.mocked(rekeySpace).mockResolvedValue(2)
    vi.mocked(rekeyScope).mockResolvedValue({ done: true, committed: 1 })
  })
  const lockAction = (w: ReturnType<typeof mount>) => button(w, t('spaceLock.action'))

  it('offers the owner the new lock and, confirmed, rotates this group with nobody removed, then re-keys its nodes', async () => {
    confirmAsk.mockResolvedValue(true)
    const w = await mountPanel({ me: 'owner' })
    expect(w.text()).toContain(t('spaceLock.subGroup'))
    await lockAction(w)!.trigger('click')
    await flushPromises()
    expect(confirmAsk).toHaveBeenCalledWith(expect.objectContaining({ message: t('spaceLock.confirmGroup'), acceptLabel: t('spaceLock.action') }))
    expect(rekeySpace).toHaveBeenCalledWith('g-1')
    expect(rekeyScope).toHaveBeenCalledWith('g-1')
    const order = (f: unknown) => vi.mocked(f as () => void).mock.invocationCallOrder[0]
    expect(order(rekeySpace)).toBeLessThan(order(rekeyScope))
    // a plain rotation: no removal rides on it
    expect(rotateGroupForRemoval).not.toHaveBeenCalled()
    expect(toastSuccess).toHaveBeenCalledWith(t('spaceLock.done'))
  })

  it('control: declined, nothing rotates', async () => {
    confirmAsk.mockResolvedValue(false)
    const w = await mountPanel({ me: 'owner' })
    await lockAction(w)!.trigger('click')
    await flushPromises()
    expect(rekeySpace).not.toHaveBeenCalled()
    expect(rekeyScope).not.toHaveBeenCalled()
  })

  it('a member who does not own the group is not offered it, nor an owner while the group is being deleted', async () => {
    expect(lockAction(await mountPanel({ me: 'stranger' }))).toBeUndefined()
    vi.mocked(scopeLifecycle).mockResolvedValue({ deletedAt: new Date(), purgeAfter: new Date(Date.now() + 86_400_000), compromiseRotation: null, rekeying: false })
    const pending = await mountPanel({ me: 'owner' })
    expect(button(pending, t('groups.restore'))).toBeTruthy() // the control: the owner's pending-deletion view is on screen
    expect(lockAction(pending)).toBeUndefined()
  })

  it('a rotation that fails says nothing was changed and re-keys nothing', async () => {
    confirmAsk.mockResolvedValue(true)
    vi.mocked(rekeySpace).mockRejectedValue(httpError(409, 'rotation_incomplete'))
    const w = await mountPanel({ me: 'owner' })
    await lockAction(w)!.trigger('click')
    await flushPromises()
    expect(w.text()).toContain(t('spaceLock.failed'))
    expect(rekeyScope).not.toHaveBeenCalled()
    expect(toastSuccess).not.toHaveBeenCalled()
  })

  // The group's copy says what the lock protects and what it does not, in both languages, and that a removal needs none.
  it('the group\'s copy names what it protects and what it does not, in EN and DE', () => {
    const de = (i18n.global.getLocaleMessage('de') as { spaceLock: { confirmGroup: string; subGroup: string } }).spaceLock
    for (const copy of [t('spaceLock.confirmGroup'), de.confirmGroup]) {
      expect(copy).toMatch(/from now on|ab jetzt/)
      expect(copy).toMatch(/stays seen|bleibt gesehen/)
    }
    for (const sub of [t('spaceLock.subGroup'), de.subGroup]) expect(sub).toMatch(/Removing a member|Entfernen eines Mitglieds/)
  })
})

describe('GroupMembersPanel — an add carries the group key to the resolved identity (ADR 0111 decision 4)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(memberError).mockResolvedValue({ status: 0, code: null })
    vi.mocked(tenantBadges).mockResolvedValue({})
  })

  async function addDave(w: ReturnType<typeof mount>) {
    await w.find('#group-add-username').setValue('Dave')
    await w.find('form.group-add-form').trigger('submit')
    await flushPromises()
  }

  it('a direct add hands addGroupMember the identity key and head the resolve served, and no name key', async () => {
    vi.mocked(resolveGroupMember).mockResolvedValue({ userId: 'u-d', username: 'dave', identityPublicKey: 'ZGF2ZQ==', keyChainHead: 'aGVhZA==', requiresConsent: false })
    const w = await mountPanel({ me: 'owner' })
    await addDave(w)
    expect(addGroupMember).toHaveBeenCalledTimes(1)
    expect(addGroupMember).toHaveBeenCalledWith('g-1', 'dave', 'u-d', expect.objectContaining({ identityPublicKey: 'ZGF2ZQ==', keyChainHead: 'aGVhZA==' }))
    expect(wrapGroupNameKeyForInvitee).not.toHaveBeenCalled()
  })

  it('a consent add hands it the same key beside the wrapped name key', async () => {
    vi.mocked(resolveGroupMember).mockResolvedValue({ userId: 'u-d', username: 'dave', identityPublicKey: 'ZGF2ZQ==', keyChainHead: 'aGVhZA==', requiresConsent: true })
    vi.mocked(wrapGroupNameKeyForInvitee).mockResolvedValue('bmFtZWtleQ==')
    const w = await mountPanel({ me: 'owner' })
    await addDave(w)
    expect(wrapGroupNameKeyForInvitee).toHaveBeenCalledWith('g-1', 'u-d', expect.objectContaining({ identityPublicKey: 'ZGF2ZQ==', keyChainHead: 'aGVhZA==' }))
    expect(addGroupMember).toHaveBeenCalledWith('g-1', 'dave', 'u-d', expect.objectContaining({ identityPublicKey: 'ZGF2ZQ==', keyChainHead: 'aGVhZA==' }), 'bmFtZWtleQ==')
  })

  it('a key the chain does not reach is said as such - with no accept - not as a generic failure', async () => {
    vi.mocked(resolveGroupMember).mockResolvedValue({ userId: 'u-d', username: 'dave', identityPublicKey: 'ZGF2ZQ==', keyChainHead: 'aGVhZA==', requiresConsent: false })
    vi.mocked(addGroupMember).mockRejectedValueOnce(new IdentityUnanchoredError('u-d'))
    const w = await mountPanel({ me: 'owner' })
    await addDave(w)
    expect(w.text()).toContain(t('trust.anomalyNote'))
    expect(w.text()).not.toContain(t('groups.addMemberFailed'))
    expect(w.findAll('form.group-add-form button').map((b) => b.text())).toEqual([t('groups.addMember')]) // no accept
  })

  it('control: any other refusal of the add is the generic failure', async () => {
    vi.mocked(resolveGroupMember).mockResolvedValue({ userId: 'u-d', username: 'dave', identityPublicKey: 'ZGF2ZQ==', keyChainHead: 'aGVhZA==', requiresConsent: false })
    vi.mocked(addGroupMember).mockRejectedValueOnce(new Error('boom'))
    const w = await mountPanel({ me: 'owner' })
    await addDave(w)
    expect(w.text()).toContain(t('groups.addMemberFailed'))
    expect(w.text()).not.toContain(t('trust.anomalyNote'))
  })
})

describe('GroupMembersPanel — a member is active or a pending consent proposal, nothing else (ADR 0111)', () => {
  beforeEach(() => vi.clearAllMocks())

  it('badges each row by its consent state, and a member a rotation declared pending as waiting', async () => {
    const ROWS: ScopeMember[] = [
      ...OWNER_ROW,
      { userId: 'u-p', username: 'pia', avatarRevision: 0, role: 'MEMBER', identityPublicKey: 'cGlh', keyChainHead: 'cGlh', pending: false, pendingConsent: true },
      { userId: 'u-q', username: 'quinn', avatarRevision: 0, role: 'MEMBER', identityPublicKey: 'cXVp', keyChainHead: 'cXVp', pending: true, pendingConsent: false },
    ]
    vi.mocked(listScopeMembers).mockResolvedValue(ROWS)
    vi.mocked(getMyProfile).mockResolvedValue(memberProfile({ username: 'owner' }))
    vi.mocked(openGroupIcon).mockResolvedValue(null)
    vi.mocked(tenantBadges).mockResolvedValue({})
    const w = mount(GroupMembersPanel, { props: { scopeId: 'g-1', name: 'Familie' }, global: { plugins: [realI18n()], stubs } })
    await flushPromises()
    const badges = (who: string) =>
      w.findAll('.member-row').find((r) => r.text().includes(`@${who}`))!.findAll('.veyq-row-meta .veyq-badge').map((b) => b.text())
    expect(badges('owner')).toEqual([t('members.statusActive')])
    expect(badges('pia')).toEqual([t('members.statusPendingConsent')])
    expect(badges('quinn')).toEqual([t('members.statusActive'), t('members.pending')])
  })
})

describe('GroupMembersPanel — a stuck compromise rotation, on an owner row (uc.spaces.complete-queued-rotation)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    globalThis.URL.createObjectURL = vi.fn(() => 'blob:mock')
    globalThis.URL.revokeObjectURL = vi.fn()
  })

  const withRow = (compromiseRotation: 'stalled' | 'failed' | null) =>
    vi.mocked(scopeLifecycle).mockResolvedValue({ deletedAt: null, purgeAfter: null, compromiseRotation, rekeying: false })

  it('on failed: says the old key still reads what is new, and re-queues it', async () => {
    withRow('failed')
    vi.mocked(requeueScopeRotation).mockResolvedValue(null)
    const w = await mountPanel({ me: 'owner' })
    expect(w.text()).toContain(t('members.compromiseRotation.failed'))
    await button(w, t('members.compromiseRotationRequeue'))!.trigger('click')
    await flushPromises()
    expect(requeueScopeRotation).toHaveBeenCalledWith('g-1')
    expect(w.text()).not.toContain(t('members.compromiseRotation.failed')) // the answered value: nothing stuck now
  })

  it('on stalled: the notice with the promote hint, and no re-queue — promoting a co-owner is the remedy', async () => {
    withRow('stalled')
    const w = await mountPanel({ me: 'owner' })
    expect(w.text()).toContain(t('members.compromiseRotation.stalled'))
    expect(button(w, t('members.compromiseRotationRequeue'))).toBeUndefined()
  })

  it('shows nothing when nothing is stuck', async () => {
    withRow(null)
    const w = await mountPanel({ me: 'owner' })
    expect(w.text()).not.toContain(t('members.compromiseRotation.stalled'))
    expect(w.text()).not.toContain(t('members.compromiseRotation.failed'))
  })
})

describe('GroupMembersPanel - who is verified in its tenant (ADR 0111 decision 15)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    activeSpace.tenantId = 'tenant-1'
  })

  it('shows "verified" beside the member, and an anomaly with its note and no review', async () => {
    vi.mocked(tenantBadges).mockResolvedValueOnce({ 'u-own': 'verified' })
    const w = await mountPanel({ me: 'owner' })
    // a group's list reads the badges of the tenant it lives in - the active one
    expect(vi.mocked(tenantBadges).mock.calls[0][0]).toBe('tenant-1')
    expect(w.get('[data-testid="member-trust-verified"]').text()).toBe(t('safety.stateVerified'))

    vi.mocked(tenantBadges).mockResolvedValueOnce({ 'u-own': 'anomaly' })
    const w2 = await mountPanel({ me: 'owner' })
    expect(w2.get('[data-testid="member-trust-anomaly"]').text()).toBe(t('trust.anomaly'))
    expect(w2.get('.member-row').text()).toContain(t('trust.anomalyNote'))
  })
})

// ADR 0022 decision 3 (inbox #814): a member who belongs to the group but does not own it reaches this page in the
// settings hub and reads the directory - no owner action is rendered (not hidden by CSS). The gate is the page's own
// `isOwner`, read from the directory: false for a MEMBER row, for a caller the directory does not list, and on a failed read.
describe('GroupMembersPanel — read-only for a member who does not own the group (ADR 0022 decision 3)', () => {
  const ROWS: ScopeMember[] = [
    ...OWNER_ROW,
    { userId: 'u-bob', username: 'bob', avatarRevision: 0, role: 'MEMBER', identityPublicKey: 'Qk9C', keyChainHead: 'Qk9C', pending: false, pendingConsent: false },
    { userId: 'u-pia', username: 'pia', avatarRevision: 0, role: 'MEMBER', identityPublicKey: 'cGlh', keyChainHead: 'cGlh', pending: false, pendingConsent: true },
  ]
  // Every owner action the page has, by its label: rename, icon, add, roles, remove, rescind, links, lock, delete.
  const OWNER_LABELS = [
    'groups.rename', 'spaceIcon.add', 'groups.addMember', 'groups.promote', 'groups.demote', 'groups.remove',
    'groups.rescind', 'spaceLinks.revokeAll', 'spaceLock.action', 'groups.deleteGroup',
  ]
  // Every owner-only state the page can show beside them: a queued rotation and a re-keying lock.
  const withOwnerState = () =>
    vi.mocked(scopeLifecycle).mockResolvedValue({ deletedAt: null, purgeAfter: null, compromiseRotation: 'failed', rekeying: true })

  async function mountAs(me: string) {
    vi.mocked(listScopeMembers).mockResolvedValue(ROWS)
    vi.mocked(getMyProfile).mockResolvedValue(memberProfile({ username: me }))
    const w = mount(GroupMembersPanel, { props: { scopeId: 'g-1', name: 'Reise-Crew' }, global: { plugins: [realI18n()], stubs } })
    await flushPromises()
    return w
  }
  const labelsShown = (w: ReturnType<typeof mount>) => OWNER_LABELS.filter((k) => button(w, t(k)))

  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(tenantBadges).mockResolvedValue({})
    vi.mocked(openGroupIcon).mockResolvedValue(null)
    withOwnerState()
  })

  it('a member sees the whole directory, the note, and no owner control', async () => {
    const w = await mountAs('bob')
    expect(w.findAll('.member-row').map((r) => r.get('.veyq-row-title').text())).toEqual(['@owner', '@bob', '@pia'])
    expect(w.get('[data-testid="group-readonly"]').text()).toBe(t('groups.readonlyNote'))
    expect(labelsShown(w)).toEqual([])
    expect(w.find('form').exists()).toBe(false)
    expect(w.find('input').exists()).toBe(false) // no username field, no remove opt-in
    expect(w.findAll('.management-actions')).toHaveLength(0)
    expect(w.text()).not.toContain(t('members.compromiseRotation.failed'))
    expect(w.text()).not.toContain(t('rotation.statusHint.rekeying'))
    expect(w.text()).toContain('Reise-Crew') // the group's own name heads the page
  })

  it('control: the owner of the same group sees every owner control and no note', async () => {
    const w = await mountAs('owner')
    expect(labelsShown(w)).toEqual(OWNER_LABELS) // demote on the owner's own row (the last-owner guard is the server's)
    expect(w.find('[data-testid="group-readonly"]').exists()).toBe(false)
    expect(w.text()).toContain(t('members.compromiseRotation.failed'))
    expect(w.text()).toContain(t('rotation.statusHint.rekeying'))
  })

  it('a directory that cannot be read offers nothing and says it failed, not that it is read-only', async () => {
    vi.mocked(listScopeMembers).mockRejectedValue(new Error('offline'))
    vi.mocked(getMyProfile).mockResolvedValue(memberProfile({ username: 'owner' }))
    const w = mount(GroupMembersPanel, { props: { scopeId: 'g-1', name: 'Reise-Crew' }, global: { plugins: [realI18n()], stubs } })
    await flushPromises()
    expect(labelsShown(w)).toEqual([])
    expect(w.find('[data-testid="group-readonly"]').exists()).toBe(false)
    expect(w.text()).toContain(t('members.loadFailed'))
  })

  it('the note is in EN and DE', () => {
    expect(i18n.global.t('groups.readonlyNote', {}, { locale: 'en' })).toBe('Only an owner of this group can change its members and settings.')
    expect(i18n.global.t('groups.readonlyNote', {}, { locale: 'de' })).toBe('Nur wer die Gruppe besitzt, kann ihre Mitglieder und Einstellungen ändern.')
  })
})
