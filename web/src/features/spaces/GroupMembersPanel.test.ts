// @vitest-environment happy-dom
// uc.spaces.set-space-icon (web, GROUP slice) — the group settings panel's identity header: a group OWNER may
// set/replace/remove the group's icon; a non-owner sees no icon controls. The seal/peel crypto lives in
// scopes.test.ts; here we pin the owner-gated UI + that save/remove call setSpaceIcon/clearSpaceIcon and signal
// the sidebar (bumpSpaceIcon).
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

vi.mock('./members', () => ({
  listScopeMembers: vi.fn(),
  addGroupMember: vi.fn(),
  resolveGroupMember: vi.fn(),
  setMemberRole: vi.fn(),
  removeGroupMember: vi.fn(),
  memberError: vi.fn().mockResolvedValue({ status: 0, code: null }),
  SetMemberRoleRequestRoleEnum: { Owner: 'OWNER', Member: 'MEMBER' },
}))
vi.mock('./scopes', () => ({
  rotateGroupForRemoval: vi.fn(),
  deleteGroup: vi.fn(),
  restoreGroup: vi.fn(),
  scopeLifecycle: vi.fn().mockResolvedValue(null),
  wrapGroupNameKeyForInvitee: vi.fn(),
  renameGroup: vi.fn(),
  setSpaceIcon: vi.fn().mockResolvedValue(undefined),
  clearSpaceIcon: vi.fn().mockResolvedValue(undefined),
  openGroupIcon: vi.fn().mockResolvedValue(null),
}))
vi.mock('../account/identityPins', () => ({
  acceptKeyChange: vi.fn(),
  IdentityChangedError: class extends Error {},
}))
vi.mock('../account/profile/profile', () => ({ getMyProfile: vi.fn() }))
vi.mock('./spaceIconSignal', () => ({ bumpSpaceIcon: vi.fn() }))
vi.mock('../../shared/state/useToast', () => ({ useToast: () => ({ success: vi.fn(), error: vi.fn() }) }))

import GroupMembersPanel from './GroupMembersPanel.vue'
import { listScopeMembers } from './members'
import { setSpaceIcon, clearSpaceIcon, openGroupIcon } from './scopes'
import { getMyProfile } from '../account/profile/profile'
import { bumpSpaceIcon } from './spaceIconSignal'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  missingWarn: false,
  fallbackWarn: false,
  messages: {
    en: {
      spaceIcon: { add: 'Add icon', change: 'Change icon', remove: 'Remove icon', saved: 'Saved', removed: 'Removed', removeConfirm: 'Remove?', stale: 'Stale', failed: 'Failed' },
      groups: { rename: 'Rename', untitled: 'Untitled', members: 'Members', membersSub: 'sub', dangerZone: 'Danger', deleteSub: 's', deleteGroup: 'Delete', addMember: 'Add', addMemberIntro: 'i', usernamePlaceholder: 'u' },
      members: { loading: 'Loading', empty: 'No members', statusActive: 'Active', statusAwaiting: 'Awaiting', statusPendingConsent: 'Pending' },
      common: { save: 'Save', cancel: 'Cancel' },
    },
  },
})

const stubs = {
  Button: { props: ['label', 'icon', 'ariaLabel'], template: '<button :aria-label="ariaLabel" @click="$emit(\'click\')">{{ label }}</button>' },
  Message: { template: '<div class="msg"><slot /></div>' },
  InputText: { props: ['modelValue'], template: '<input :value="modelValue" />' },
  TenantAvatar: { props: ['name', 'icon'], template: '<span class="avatar" :data-icon="icon">{{ name }}</span>' },
  SpaceIconDialog: {
    emits: ['save', 'close'],
    setup(_: unknown, { emit }: { emit: (e: string, ...a: unknown[]) => void }) {
      return { doSave: () => emit('save', new Uint8Array([1, 2, 3])) }
    },
    template: '<div class="icon-dialog"><button class="dialog-save" @click="doSave">save</button></div>',
  },
  ChangedKeyReviewDialog: true,
}

const OWNER_ROW = [{ userId: 'u-own', username: 'owner', role: 'OWNER', hasGrant: true, pendingConsent: false }]

async function mountPanel(opts: { me: string; icon?: Uint8Array | null } = { me: 'owner' }) {
  vi.mocked(listScopeMembers).mockResolvedValue(OWNER_ROW as never)
  vi.mocked(getMyProfile).mockResolvedValue({ username: opts.me } as never)
  vi.mocked(openGroupIcon).mockResolvedValue(opts.icon ?? null)
  const wrapper = mount(GroupMembersPanel, { props: { scopeId: 'g-1', name: 'Familie' }, global: { plugins: [i18n], stubs } })
  await flushPromises()
  return wrapper
}

const button = (w: ReturnType<typeof mount>, label: string) => w.findAll('button').find((b) => b.text() === label)

describe('GroupMembersPanel — space icon (GROUP slice)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    globalThis.URL.createObjectURL = vi.fn(() => 'blob:mock')
    globalThis.URL.revokeObjectURL = vi.fn()
    window.confirm = vi.fn().mockReturnValue(true)
  })

  it('shows the owner an "add icon" control when the group has no icon (and no remove)', async () => {
    const w = await mountPanel({ me: 'owner' })
    expect(button(w, 'Add icon')).toBeTruthy()
    expect(button(w, 'Remove icon')).toBeFalsy()
  })

  it('hides all icon controls from a non-owner member', async () => {
    const w = await mountPanel({ me: 'stranger' }) // not in the OWNER_ROW → isOwner false
    expect(button(w, 'Add icon')).toBeFalsy()
    expect(button(w, 'Change icon')).toBeFalsy()
    expect(button(w, 'Remove icon')).toBeFalsy()
  })

  it('owner sets an icon: the dialog save seals via setSpaceIcon and signals the sidebar', async () => {
    const w = await mountPanel({ me: 'owner' })
    await button(w, 'Add icon')!.trigger('click') // open the dialog
    expect(w.find('.icon-dialog').exists()).toBe(true)
    await w.find('.dialog-save').trigger('click') // the stub emits save(bytes)
    await flushPromises()
    expect(setSpaceIcon).toHaveBeenCalledWith('g-1', new Uint8Array([1, 2, 3]))
    expect(bumpSpaceIcon).toHaveBeenCalled()
  })

  it('owner with an existing icon sees change + remove; remove clears via clearSpaceIcon', async () => {
    const w = await mountPanel({ me: 'owner', icon: new Uint8Array([9, 9, 9]) })
    expect(button(w, 'Change icon')).toBeTruthy()
    const remove = button(w, 'Remove icon')
    expect(remove).toBeTruthy()
    await remove!.trigger('click')
    await flushPromises()
    expect(clearSpaceIcon).toHaveBeenCalledWith('g-1')
    expect(bumpSpaceIcon).toHaveBeenCalled()
  })
})
