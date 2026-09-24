// @vitest-environment happy-dom
// uc.spaces.consent-group-add (web) — the invitation row says who proposed the group. `invitations.addedBy` once
// read `Invited by @{user}`: a bare `@` opens a linked message, so vue-i18n could not compile it and rendered the
// source text, placeholder and all, in both languages. The catalog's compile test (`i18n/setLocale.test.ts`) proves
// the message parses; only a rendered row proves it says what we meant — the inviter's name, after a literal `@`.
import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('../features/spaces/members', () => ({
  listMyGroupInvitations: vi.fn(async () => [
    { scopeId: 's1', addedBy: 'alice', sealedNameKey: 'k', encryptedName: 'n' },
  ]),
  acceptGroupInvitation: vi.fn(),
  declineGroupInvitation: vi.fn(),
}))
vi.mock('../features/spaces/invitations', () => ({ refreshMyInvitations: vi.fn() }))
vi.mock('../features/spaces/inviteName', () => ({ openInviteeName: () => 'Family' }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))

import GroupInvitationsView from './GroupInvitationsView.vue'
import { i18n, setLocalePreference } from '../i18n'

const stubs = { Button: { props: ['label'], template: '<button>{{ label }}</button>' }, Message: { template: '<div><slot /></div>' } }

afterEach(() => setLocalePreference('en'))

describe('GroupInvitationsView — who invited you', () => {
  it.each([
    ['en', 'Invited by @alice'],
    ['de', 'Eingeladen von @alice'],
  ] as const)('names the inviter after a literal @ (%s)', async (locale, expected) => {
    setLocalePreference(locale)
    const wrapper = mount(GroupInvitationsView, { global: { plugins: [i18n], stubs } })
    await flushPromises()

    expect(wrapper.find('.veyq-row-title').text()).toBe('Family') // the row rendered, so the subtitle below is its own
    expect(wrapper.find('.veyq-row-subtitle').text()).toBe(expected)
  })
})
