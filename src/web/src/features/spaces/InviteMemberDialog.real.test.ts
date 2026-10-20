// @vitest-environment happy-dom
// The invite dialog over the REAL PrimeVue Dialog, held open by a host that refreshes behind it as TenantMembersPanel does
// (`@invited` → reload): `InviteMemberDialog.test.ts` stubs the Dialog, so it cannot see the success view being drawn
// (or not) in the teleported dialog. A DOM submit, then the confirmation must be on screen.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import { defineComponent, h, ref } from 'vue'

vi.mock('./members', () => ({ inviteMember: vi.fn(), memberError: vi.fn() }))

import InviteMemberDialog from './InviteMemberDialog.vue'
import { inviteMember } from './members'
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
              visible: open.value,
              'onUpdate:visible': (v: boolean) => (open.value = v),
              onInvited: () => { refreshing.value = true },
            }),
          ])
      },
    })
    wrapper = mount(Host, { attachTo: document.body, global: { plugins: [realI18n(), PrimeVue] } })
    await flushPromises()

    const input = document.body.querySelector('input[type="email"]') as HTMLInputElement
    input.value = 'Partner@Example.com'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()
    ;(document.body.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await flushPromises()

    expect(inviteMember).toHaveBeenCalledWith('t-1', 'partner@example.com')
    expect(refreshing.value).toBe(true)
    expect(document.body.textContent).toContain(t('members.inviteSentTo', { email: 'partner@example.com' }))
    expect(document.body.querySelector('form')).toBeNull()
  })
})
