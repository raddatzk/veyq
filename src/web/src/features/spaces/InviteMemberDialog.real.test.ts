// @vitest-environment happy-dom
// The invite dialog over the REAL dialog (VeyqDialog), held open by a host that refreshes behind it as TenantMembersPanel does
// (`@invited` → reload): `InviteMemberDialog.test.ts` stubs the Dialog, so it cannot see the success view being drawn
// (or not) in the teleported dialog. A DOM submit, then the confirmation must be on screen.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h, ref } from 'vue'

vi.mock('./members', () => ({ createMemberInviteLink: vi.fn(), inviteMember: vi.fn(), memberError: vi.fn(), prepareInviteCode: vi.fn() }))

import InviteMemberDialog from './InviteMemberDialog.vue'
import { createMemberInviteLink, inviteMember, prepareInviteCode } from './members'
import { realI18n, t } from '../../test/i18n'

let wrapper: VueWrapper | undefined
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
})

describe('InviteMemberDialog over the real Dialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(inviteMember).mockResolvedValue({ email: 'x@y.z', inviteStatus: 'ACTIVE', inviteExpiresAt: new Date(), delivery: 'SENT' })
    vi.mocked(prepareInviteCode).mockImplementation(() => ({ mode: 'code', code: '0123456789', ahead: { take: vi.fn(() => null), discard: vi.fn() } }))
  })

  it('draws the confirmation naming the address after a 201, and keeps it while the host refreshes behind it', async () => {
    const refreshing = ref(false)
    const Host = defineComponent({
      setup() {
        const open = ref(true)
        return () =>
          h('div', [
            h(InviteMemberDialog, {
              tenantId: 't-1',
              tenantName: 'Acme',
              visible: open.value,
              'onUpdate:visible': (v: boolean) => (open.value = v),
              onInvited: () => { refreshing.value = true },
            }),
          ])
      },
    })
    wrapper = mount(Host, { attachTo: document.body, global: { plugins: [realI18n()] } })
    await flushPromises()
    // The dialog opens on the link way (ADR 0129 decision 1): switch to the mail way through the real toggle.
    const mailWay = [...document.body.querySelectorAll('.veyq-toggle-group__item')].find((b) => b.textContent?.includes(t('members.wayMail'))) as HTMLElement
    mailWay.click()
    await flushPromises()

    const input = document.body.querySelector('input[type="email"]') as HTMLInputElement
    input.value = 'Partner@Example.com'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()
    ;(document.body.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await flushPromises()

    expect(inviteMember).toHaveBeenCalledWith('t-1', 'partner@example.com', expect.objectContaining({ mode: 'code', code: expect.any(String) }), expect.any(Function))
    expect(refreshing.value).toBe(true)
    expect(document.body.textContent).toContain(t('members.inviteSentTo', { email: 'partner@example.com' }))
    expect(document.body.querySelector('form')).toBeNull()
  })

  it('the link way draws its result in the real dialog: the message holding the link, and keeps it while the host refreshes', async () => {
    const LINK = 'https://acme.example/tenant/accept#tok.SEALED.ABCDE12345'
    vi.mocked(createMemberInviteLink).mockResolvedValue({ inviteId: 'i-1', link: LINK, expiresAt: new Date('2026-11-19T10:00:00Z') })
    const refreshing = ref(false)
    const Host = defineComponent({
      setup() {
        const open = ref(true)
        return () => h('div', [h(InviteMemberDialog, {
          tenantId: 't-1', tenantName: 'Acme', visible: open.value,
          'onUpdate:visible': (v: boolean) => (open.value = v), onInvited: () => { refreshing.value = true },
        })])
      },
    })
    wrapper = mount(Host, { attachTo: document.body, global: { plugins: [realI18n()] } })
    await flushPromises()
    ;(document.body.querySelector('[data-testid="invite-link-create"]') as HTMLElement).click()
    await flushPromises()

    expect(createMemberInviteLink).toHaveBeenCalledOnce()
    expect(inviteMember).not.toHaveBeenCalled()
    expect(refreshing.value).toBe(true)
    expect((document.body.querySelector('[data-testid="invite-link-message"]') as HTMLTextAreaElement).value).toContain(LINK)
  })
})
