// @vitest-environment happy-dom
// uc.spaces.add-member (web) — the invite-member dialog flow: it shapes the inviteMember call (email trimmed +
// lowercased), on success emits `invited` and switches to a confirmation naming the address, and surfaces
// already_member / not-owner / rate-limit / mail-unavailable distinctly. There is no copy-link view any more:
// the secret is minted for the invitee and never returned to the owner (ADR 0055 §2).
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'

vi.mock('./members', () => ({
  DeliveryResult: { Sent: 'SENT', FallbackCopylink: 'FALLBACK_COPYLINK', Failed: 'FAILED' },
  // SENT is the only success the server can return on this path now — the other two are 503s.
  inviteMember: vi.fn().mockResolvedValue({ email: 'x@y.z', inviteStatus: 'ACTIVE', inviteExpiresAt: new Date(), delivery: 'SENT' }),
  memberError: vi.fn().mockResolvedValue({ status: 0, code: null }),
}))

import InviteMemberDialog from './InviteMemberDialog.vue'
import { inviteMember, memberError } from './members'
import { realI18n, t } from '../../test/i18n'

const stubs = {
  Dialog: { props: ['visible'], template: '<div v-if="visible"><slot /></div>' },
  InputText: {
    props: ['modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  Button: {
    props: ['type', 'label', 'disabled'],
    emits: ['click'], // declare it so the parent's @click fires once (not native-fallthrough + emit = twice)
    template: '<button :type="type || \'button\'" :disabled="disabled" @click="$emit(\'click\', $event)">{{ label }}</button>',
  },
  Message: { template: '<div class="msg"><slot /></div>' },
}

const mountDialog = () =>
  mount(InviteMemberDialog, { props: { visible: true, tenantId: 't-1' }, global: { plugins: [realI18n()], stubs } })

describe('InviteMemberDialog (uc.spaces.add-member, web)', () => {
  beforeEach(() => vi.clearAllMocks())

  it('invites a trimmed + lowercased email, then emits invited and confirms who it went to (stays open)', async () => {
    const wrapper = mountDialog()
    await wrapper.find('input').setValue('  Partner@Example.COM  ')
    await wrapper.find('form').trigger('submit.prevent')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    expect(inviteMember).toHaveBeenCalledWith('t-1', 'partner@example.com')
    expect(wrapper.emitted('invited')).toHaveLength(1)
    // The dialog stays open and confirms the address — and offers NO link, because it holds no secret to offer.
    expect(wrapper.emitted('update:visible')).toBeUndefined()
    expect(wrapper.text()).toContain(t('members.inviteSentTo', { email: 'partner@example.com' }))
    expect(wrapper.find('.invite-link-input').exists()).toBe(false)
  })

  it('closes on Done from the confirmation view', async () => {
    const wrapper = mountDialog()
    await wrapper.find('input').setValue('partner@example.com')
    await wrapper.find('form').trigger('submit.prevent')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()
    const done = wrapper.findAll('button').find((b) => b.text() === t('common.done'))!
    await done.trigger('click')
    expect(wrapper.emitted('update:visible')?.at(-1)).toEqual([false])
  })

  it('surfaces a tenant that cannot send mail as its own message, not a generic failure', async () => {
    // ADR 0055 §2: the owner has to learn that the fix is configuring mail, not retrying — and no invitation
    // exists, so there is nothing to hand over in the meantime.
    vi.mocked(inviteMember).mockRejectedValueOnce(new Error('503'))
    vi.mocked(memberError).mockResolvedValueOnce({ status: 503, code: 'mail_unavailable' })
    const wrapper = mountDialog()
    await wrapper.find('input').setValue('partner@example.com')
    await wrapper.find('form').trigger('submit.prevent')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain(t('members.mailUnavailable'))
    expect(wrapper.text()).not.toContain(t('members.inviteFailed')) // not the generic fallback
    expect(wrapper.emitted('invited')).toBeUndefined()
    expect(wrapper.find('.invite-link-input').exists()).toBe(false)
  })

  it('surfaces a failed send as retryable, since the invite was revoked with it', async () => {
    vi.mocked(inviteMember).mockRejectedValueOnce(new Error('503'))
    vi.mocked(memberError).mockResolvedValueOnce({ status: 503, code: 'mail_send_failed' })
    const wrapper = mountDialog()
    await wrapper.find('input').setValue('partner@example.com')
    await wrapper.find('form').trigger('submit.prevent')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain(t('members.mailSendFailed'))
    expect(wrapper.emitted('invited')).toBeUndefined()
  })

  it('surfaces a rate-limit (429) distinctly and stays open', async () => {
    vi.mocked(inviteMember).mockRejectedValueOnce(new Error('429'))
    vi.mocked(memberError).mockResolvedValueOnce({ status: 429, code: 'rate_limited' })
    const wrapper = mountDialog()
    await wrapper.find('input').setValue('spam@example.com')
    await wrapper.find('form').trigger('submit.prevent')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain(t('members.inviteRateLimited'))
    expect(wrapper.emitted('invited')).toBeUndefined()
  })

  it('does not submit an empty email', async () => {
    const wrapper = mountDialog()
    await wrapper.find('form').trigger('submit.prevent')
    await wrapper.vm.$nextTick()
    expect(inviteMember).not.toHaveBeenCalled()
    expect(wrapper.emitted('invited')).toBeUndefined()
  })

  it('surfaces already_member distinctly and stays open', async () => {
    vi.mocked(inviteMember).mockRejectedValueOnce(new Error('409'))
    vi.mocked(memberError).mockResolvedValueOnce({ status: 409, code: 'already_member' })
    const wrapper = mountDialog()
    await wrapper.find('input').setValue('dup@example.com')
    await wrapper.find('form').trigger('submit.prevent')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain(t('members.alreadyMember'))
    expect(wrapper.emitted('invited')).toBeUndefined()
    expect(wrapper.emitted('update:visible')).toBeUndefined()
  })
})
