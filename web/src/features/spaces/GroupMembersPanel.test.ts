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
  acceptKeyChange: vi.fn(),
  trustStates: vi.fn(),
  IdentityChangedError: class extends Error {},
}))
vi.mock('../account/profile/profile', () => ({ getMyProfile: vi.fn() }))
vi.mock('../tasks/scopeRotationWorker', () => ({ runScopeRotationWorker: vi.fn() }))
vi.mock('./spaceIconSignal', () => ({ bumpSpaceIcon: vi.fn() }))
const { toastSuccess } = vi.hoisted(() => ({ toastSuccess: vi.fn() }))
vi.mock('../../shared/state/useToast', () => ({ useToast: () => ({ success: toastSuccess, error: vi.fn() }) }))
// Confirmation is the app's own dialog (cr-web-own-confirm-dialog): the panel awaits `ask()`, so the answer is
// given here. Stubbing `window.confirm` would now confirm nothing and pass anyway.
const { confirmAsk } = vi.hoisted(() => ({ confirmAsk: vi.fn() }))
vi.mock('../../shared/state/useConfirm', () => ({ useConfirm: () => ({ ask: confirmAsk }) }))

import GroupMembersPanel from './GroupMembersPanel.vue'
import { listScopeMembers, removeGroupMember, resolveGroupMember, addGroupMember, memberError } from './members'
import { setSpaceIcon, clearSpaceIcon, openGroupIcon, rotateGroupForRemoval, wrapGroupNameKeyForInvitee, scopeLifecycle, requeueScopeRotation, revokeScopeShares } from './scopes'
import { acceptKeyChange, IdentityChangedError, trustStates } from '../account/identityPins'
import { runScopeRotationWorker } from '../tasks/scopeRotationWorker'
import { getMyProfile } from '../account/profile/profile'
import { bumpSpaceIcon } from './spaceIconSignal'
import { realI18n, t } from '../../test/i18n'
import { i18n } from '../../i18n'
import type { ScopeMember } from '../../api/generated/scopes'
import { memberProfile } from '../../test/models'

const stubs = {
  Button: { props: ['label', 'icon', 'ariaLabel'], emits: ['click'], template: '<button :aria-label="ariaLabel" @click="$emit(\'click\')">{{ label }}</button>' },
  Message: { template: '<div class="msg"><slot /></div>' },
  InputText: {
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
  ChangedKeyReviewDialog: true,
  Checkbox: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input type="checkbox" class="revoke-links" :checked="modelValue" @change="$emit(\'update:modelValue\', $event.target.checked)" />',
  },
}

const OWNER_ROW: ScopeMember[] = [
  { userId: 'u-own', username: 'owner', avatarVersion: 0, role: 'OWNER', identityPublicKey: 'AA==', hasGrant: true, pendingConsent: false },
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
  vi.mocked(memberError).mockResolvedValue({ status: 0, code: null })
  vi.mocked(trustStates).mockResolvedValue({})
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
  const BOB: ScopeMember = { userId: 'u-bob', username: 'bob', avatarVersion: 0, role: 'MEMBER', identityPublicKey: 'Qk9C', hasGrant: true, pendingConsent: false }
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

describe('GroupMembersPanel — accepting an invitee\'s changed key drains the rotation worker first (inbox #233)', () => {
  beforeEach(() => vi.clearAllMocks())

  it('the worker pass resolves before the pin store is written, then the add is retried', async () => {
    const events: string[] = []
    vi.mocked(runScopeRotationWorker).mockImplementation(async () => { events.push('pass'); return 0 })
    vi.mocked(acceptKeyChange).mockImplementation(async () => { events.push('pin written') })
    vi.mocked(resolveGroupMember).mockResolvedValue({ userId: 'u-c', username: 'carol', identityPublicKey: 'new-key', requiresConsent: true })
    vi.mocked(wrapGroupNameKeyForInvitee)
      .mockRejectedValueOnce(new IdentityChangedError('u-c'))
      .mockResolvedValueOnce('c2VhbGVk')
    const w = await mountPanel({ me: 'owner' })

    await w.find('#group-add-username').setValue('carol')
    await w.find('form.group-add-form').trigger('submit')
    await flushPromises()
    await button(w, t('groups.acceptKeyChange'))!.trigger('click')
    await flushPromises()

    expect(events).toEqual(['pass', 'pin written'])
    expect(acceptKeyChange).toHaveBeenCalledWith('u-c', 'new-key')
    expect(addGroupMember).toHaveBeenCalledWith('g-1', 'carol', 'u-c', 'c2VhbGVk') // the retried add
  })
})

describe('GroupMembersPanel — a stuck compromise rotation, on an owner row (uc.spaces.complete-queued-rotation)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    globalThis.URL.createObjectURL = vi.fn(() => 'blob:mock')
    globalThis.URL.revokeObjectURL = vi.fn()
  })

  const withRow = (compromiseRotation: 'stalled' | 'failed' | null) =>
    vi.mocked(scopeLifecycle).mockResolvedValue({ deletedAt: null, purgeAfter: null, compromiseRotation })

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

describe('GroupMembersPanel - who is verified (ADR 0028 Decision 3)', () => {
  beforeEach(() => vi.clearAllMocks())

  it('shows "verified" beside the member, and the louder change on a verified contact', async () => {
    vi.mocked(trustStates).mockResolvedValueOnce({ 'u-own': { state: 'verified', pinVerified: true } })
    const w = await mountPanel({ me: 'owner' })
    expect(w.get('[data-testid="member-trust-verified"]').text()).toBe(t('safety.stateVerified'))

    vi.mocked(trustStates).mockResolvedValueOnce({ 'u-own': { state: 'changed', pinVerified: true } })
    const w2 = await mountPanel({ me: 'owner' })
    expect(w2.get('[data-testid="member-trust-changed"]').text()).toBe(t('safety.badgeVerifiedChanged'))
    expect(button(w2, t('verify.review'))).toBeDefined()
  })
})
