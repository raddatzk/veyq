// @vitest-environment happy-dom
// uc.spaces.add-member (web) — the invite-member dialog flow: it shapes the inviteMember call (email trimmed +
// lowercased), on success emits `invited` and switches to a confirmation naming the address, and surfaces
// already_member / not-owner / rate-limit / mail-unavailable distinctly. There is no copy-link view any more:
// the secret is minted for the invitee and never returned to the owner (ADR 0055 §2).
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('./members', () => ({
  DeliveryResult: { Sent: 'SENT', FallbackCopylink: 'FALLBACK_COPYLINK', Failed: 'FAILED' },
  createMemberInviteLink: vi.fn(),
  inviteMember: vi.fn(),
  memberError: vi.fn(),
  prepareInviteCode: vi.fn(),
}))

import InviteMemberDialog from './InviteMemberDialog.vue'
import { createMemberInviteLink, inviteMember, memberError, prepareInviteCode, type InvitePhase } from './members'
import { realI18n, t } from '../../test/i18n'
import { formatInviteCode, generateInviteCode, normalizeInviteCode } from '@veyq/crypto/invite'
import { MIN_PASSWORD_LENGTH } from '../../shared/passwordPolicy'

const stubs = {
  VeyqPassword: {
    props: ['modelValue'],
    template: '<input class="pw" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  VeyqDialog: { props: ['visible'], template: '<div v-if="visible"><slot /></div>' },
  VeyqInput: {
    props: ['modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  VeyqButton: {
    props: ['type', 'label', 'disabled'],
    emits: ['click'], // declare it so the parent's @click fires once (not native-fallthrough + emit = twice)
    template: '<button :type="type || \'button\'" :disabled="disabled" @click="$emit(\'click\', $event)">{{ label }}</button>',
  },
  VeyqMessage: { template: '<div class="msg"><slot /></div>' },
  VeyqToggleGroup: {
    props: ['modelValue', 'options'],
    emits: ['update:modelValue'],
    template: '<div class="way"><button v-for="o in options" :key="o.value" type="button" class="way-option" :data-way="o.value" @click="$emit(\'update:modelValue\', o.value)">{{ o.label }}</button></div>',
  },
}

/** The dialog as it opens: on the link way (ADR 0129 decision 1). */
const mountOpen = () =>
  mount(InviteMemberDialog, { props: { visible: true, tenantId: 't-1', tenantName: 'Acme' }, global: { plugins: [realI18n()], stubs } })

/** The dialog switched to the mail way, which every test below the link block drives. */
async function mountDialog() {
  const w = mountOpen()
  await w.get('[data-way="mail"]').trigger('click')
  return w
}

describe('InviteMemberDialog (uc.spaces.add-member, web)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // SENT is the only success the server can return on this path now — the other two are 503s.
    vi.mocked(inviteMember).mockResolvedValue({ email: 'x@y.z', inviteStatus: 'ACTIVE', inviteExpiresAt: new Date(), delivery: 'SENT' })
    vi.mocked(memberError).mockResolvedValue({ status: 0, code: null })
    // A real code; its link key's derivation stands in as a pair of spies (`memberInvite.test.ts` covers the real one).
    vi.mocked(prepareInviteCode).mockImplementation(() => ({ mode: 'code', code: generateInviteCode(), ahead: { take: vi.fn(() => null), discard: vi.fn() } }))
  })

  it('invites a trimmed + lowercased email, then emits invited and confirms who it went to (stays open)', async () => {
    const wrapper = await mountDialog()
    await wrapper.find('input').setValue('  Partner@Example.COM  ')
    await wrapper.find('form').trigger('submit.prevent')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    expect(inviteMember).toHaveBeenCalledWith('t-1', 'partner@example.com', expect.objectContaining({ mode: 'code', code: expect.any(String) }), expect.any(Function))
    expect(wrapper.emitted('invited')).toHaveLength(1)
    // The dialog stays open and confirms the address — and offers NO link, because it holds no secret to offer.
    expect(wrapper.emitted('update:visible')).toBeUndefined()
    expect(wrapper.text()).toContain(t('members.inviteSentTo', { email: 'partner@example.com' }))
    expect(wrapper.find('.invite-link-input').exists()).toBe(false)
  })

  it('seals the invite under a fresh code and shows exactly that code once, to pass on outside veyq (ADR 0111 decision 1)', async () => {
    const wrapper = await mountDialog()
    await wrapper.find('input').setValue('partner@example.com')
    await wrapper.find('form').trigger('submit.prevent')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    const secret = vi.mocked(inviteMember).mock.calls[0][2]
    expect(secret.mode).toBe('code')
    const code = (secret as { code: string }).code
    // A real code: ten Crockford characters, and the one the dialog shows (grouped) — not a second one.
    expect(normalizeInviteCode(code)).toBe(code)
    expect(wrapper.get('[data-testid="invite-code"]').text()).toBe(formatInviteCode(code))
    expect(wrapper.text()).toContain(t('members.inviteCodeNote'))
    // A second invite draws a new code: the code is per invite, never reused.
    await wrapper.setProps({ visible: false })
    await wrapper.setProps({ visible: true })
    await wrapper.get('[data-way="mail"]').trigger('click') // a reopened dialog starts on the link way again
    await wrapper.find('input').setValue('other@example.com')
    await wrapper.find('form').trigger('submit.prevent')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()
    expect((vi.mocked(inviteMember).mock.calls[1][2] as { code: string }).code).not.toBe(code)
  })

  it('takes a password the owner chooses instead — at least the policy floor — and then shows no code', async () => {
    const wrapper = await mountDialog()
    await wrapper.find('input').setValue('partner@example.com')
    await wrapper.findAll('button').find((b) => b.text() === t('members.inviteUsePassword'))!.trigger('click')
    const pw = wrapper.get('input.pw')
    await pw.setValue('x'.repeat(MIN_PASSWORD_LENGTH - 1))
    const submit = wrapper.findAll('button').find((b) => b.text() === t('members.inviteSubmit'))!
    expect(submit.attributes('disabled')).toBeDefined() // below the floor: nothing to send
    await pw.setValue('x'.repeat(MIN_PASSWORD_LENGTH))
    expect(submit.attributes('disabled')).toBeUndefined()
    await wrapper.find('form').trigger('submit.prevent')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    expect(inviteMember).toHaveBeenCalledWith('t-1', 'partner@example.com', { mode: 'password', password: 'x'.repeat(MIN_PASSWORD_LENGTH) }, expect.any(Function))
    expect(wrapper.find('[data-testid="invite-code"]').exists()).toBe(false)
    expect(wrapper.text()).toContain(t('members.inviteSentPasswordIntro'))
  })

  it('closes on Done from the confirmation view', async () => {
    const wrapper = await mountDialog()
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
    const wrapper = await mountDialog()
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
    const wrapper = await mountDialog()
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
    const wrapper = await mountDialog()
    await wrapper.find('input').setValue('spam@example.com')
    await wrapper.find('form').trigger('submit.prevent')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain(t('members.inviteRateLimited'))
    expect(wrapper.emitted('invited')).toBeUndefined()
  })

  describe('while the code is prepared and sent (inbox #566)', () => {
    /** A send held at each phase until the test lets it go on. */
    function heldSend() {
      const steps: Array<() => void> = []
      const next = () => new Promise<void>((resolve) => steps.push(resolve))
      vi.mocked(inviteMember).mockImplementationOnce(async (_t, _e, _s, onPhase) => {
        onPhase?.('securing')
        await next()
        onPhase?.('sending')
        await next()
        return { email: 'x@y.z', inviteStatus: 'ACTIVE', inviteExpiresAt: new Date(), delivery: 'SENT' }
      })
      return { go: async () => { steps.shift()!(); await flushPromises() } }
    }
    const progress = (w: Awaited<ReturnType<typeof mountDialog>>) => w.find('[data-testid="invite-progress"]')

    it('says the invite is being secured on this device, then sent — and the line goes when the code is shown', async () => {
      const send = heldSend()
      const wrapper = await mountDialog()
      expect(progress(wrapper).exists()).toBe(false) // nothing runs yet
      await wrapper.find('input').setValue('partner@example.com')
      await wrapper.find('form').trigger('submit.prevent')
      await flushPromises()

      expect(progress(wrapper).attributes('role')).toBe('status')
      expect(progress(wrapper).text()).toBe(t('members.inviteSecuring'))
      await send.go()
      expect(progress(wrapper).text()).toBe(t('members.inviteSending'))
      await send.go()
      expect(progress(wrapper).exists()).toBe(false)
      expect(wrapper.find('[data-testid="invite-code"]').exists()).toBe(true)
    })

    it('the line goes with a failed send, and the error stands alone', async () => {
      vi.mocked(inviteMember).mockImplementationOnce(async (_t, _e, _s, onPhase) => {
        onPhase?.('sending' as InvitePhase)
        throw new Error('503')
      })
      vi.mocked(memberError).mockResolvedValueOnce({ status: 503, code: 'mail_send_failed' })
      const wrapper = await mountDialog()
      await wrapper.find('input').setValue('partner@example.com')
      await wrapper.find('form').trigger('submit.prevent')
      await flushPromises()
      expect(progress(wrapper).exists()).toBe(false)
      expect(wrapper.text()).toContain(t('members.mailSendFailed'))
    })

    it('mints the code as the dialog opens and sends exactly that one, its derivation with it', async () => {
      const wrapper = await mountDialog()
      expect(prepareInviteCode).toHaveBeenCalledTimes(1) // before any address is typed
      const minted = vi.mocked(prepareInviteCode).mock.results[0].value as ReturnType<typeof prepareInviteCode>
      await wrapper.find('input').setValue('partner@example.com')
      await wrapper.find('form').trigger('submit.prevent')
      await flushPromises()
      expect(vi.mocked(inviteMember).mock.calls[0][2]).toBe(minted)
      expect(wrapper.get('[data-testid="invite-code"]').text()).toBe(formatInviteCode(minted.code))
    })

    it('a failed send discards its derivation and draws a new code for the next try; closing discards the unused one', async () => {
      vi.mocked(inviteMember).mockRejectedValueOnce(new Error('429'))
      vi.mocked(memberError).mockResolvedValueOnce({ status: 429, code: 'rate_limited' })
      const wrapper = await mountDialog()
      const first = vi.mocked(prepareInviteCode).mock.results[0].value as ReturnType<typeof prepareInviteCode>
      await wrapper.find('input').setValue('partner@example.com')
      await wrapper.find('form').trigger('submit.prevent')
      await flushPromises()
      expect(first.ahead.discard).toHaveBeenCalled()
      expect(prepareInviteCode).toHaveBeenCalledTimes(2)
      const second = vi.mocked(prepareInviteCode).mock.results[1].value as ReturnType<typeof prepareInviteCode>
      expect(second.code).not.toBe(first.code)

      await wrapper.setProps({ visible: false })
      expect(second.ahead.discard).toHaveBeenCalled()
    })
  })

  it('does not submit an empty email', async () => {
    const wrapper = await mountDialog()
    await wrapper.find('form').trigger('submit.prevent')
    await wrapper.vm.$nextTick()
    expect(inviteMember).not.toHaveBeenCalled()
    expect(wrapper.emitted('invited')).toBeUndefined()
  })

  it('surfaces already_member distinctly and stays open', async () => {
    vi.mocked(inviteMember).mockRejectedValueOnce(new Error('409'))
    vi.mocked(memberError).mockResolvedValueOnce({ status: 409, code: 'already_member' })
    const wrapper = await mountDialog()
    await wrapper.find('input').setValue('dup@example.com')
    await wrapper.find('form').trigger('submit.prevent')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain(t('members.alreadyMember'))
    expect(wrapper.emitted('invited')).toBeUndefined()
    expect(wrapper.emitted('update:visible')).toBeUndefined()
  })
})

describe('InviteMemberDialog — the link way (ADR 0129 decision 1)', () => {
  const LINK = 'https://acme.example/tenant/accept#tok.SEALED.ABCDE12345'
  const EXPIRES = new Date('2026-11-19T10:00:00Z')

  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(memberError).mockResolvedValue({ status: 0, code: null })
    vi.mocked(prepareInviteCode).mockImplementation(() => ({ mode: 'code', code: generateInviteCode(), ahead: { take: vi.fn(() => null), discard: vi.fn() } }))
    vi.mocked(createMemberInviteLink).mockResolvedValue({ inviteId: 'i-1', link: LINK, expiresAt: EXPIRES })
  })

  const create = async (w: ReturnType<typeof mountOpen>) => {
    await w.get('[data-testid="invite-link-create"]').trigger('click')
    await flushPromises()
  }

  it('opens on the link way, first of the two, with no address to type', () => {
    const w = mountOpen()
    expect(w.findAll('.way-option').map((b) => b.attributes('data-way'))).toEqual(['link', 'mail'])
    expect(w.text()).toContain(t('members.linkIntro'))
    expect(w.find('input[type="email"], form').exists()).toBe(false)
    expect(w.find('[data-testid="invite-link-create"]').exists()).toBe(true)
  })

  it('mints a link invite under the code prepared as it opened — never the mailed operation — and shows the link once in an editable message', async () => {
    const w = mountOpen()
    const prepared = vi.mocked(prepareInviteCode).mock.results[0].value as ReturnType<typeof prepareInviteCode>
    await create(w)
    expect(createMemberInviteLink).toHaveBeenCalledWith('t-1', prepared, expect.any(Function))
    expect(inviteMember).not.toHaveBeenCalled()
    expect(w.emitted('invited')).toEqual([['link']])
    const message = w.get<HTMLTextAreaElement>('[data-testid="invite-link-message"]').element.value
    expect(message).toContain(LINK) // the link with its code
    expect(message).toContain('Acme') // the space's name
    expect(message).toContain('2026') // its expiry
    expect(w.text()).toContain(t('members.linkWarning'))
    expect(w.find('[data-testid="invite-way"]').exists()).toBe(false) // the result replaces the switch
  })

  it('copy, the mail program and the share sheet take the message as edited', async () => {
    const writeText = vi.fn(async () => undefined)
    const share = vi.fn(async () => undefined)
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
    Object.defineProperty(navigator, 'share', { configurable: true, value: share })
    try {
      const w = mountOpen()
      await create(w)
      await w.get('[data-testid="invite-link-message"]').setValue(`Hi!\n${LINK}`)
      await w.get('[data-testid="invite-link-copy"]').trigger('click')
      await flushPromises()
      expect(writeText).toHaveBeenCalledWith(`Hi!\n${LINK}`)
      const href = w.get('[data-testid="invite-link-mail"]').attributes('href')!
      expect(href.startsWith('mailto:?subject=')).toBe(true)
      expect(decodeURIComponent(href.slice(href.indexOf('&body=') + 6))).toBe(`Hi!\n${LINK}`)
      await w.get('[data-testid="invite-link-share"]').trigger('click')
      expect(share).toHaveBeenCalledWith({ title: t('members.linkMailSubject', { space: 'Acme' }), text: `Hi!\n${LINK}` })
    } finally {
      Reflect.deleteProperty(navigator, 'share')
      Reflect.deleteProperty(navigator, 'clipboard')
    }
  })

  it('offers no share button where the browser has no share sheet (control: copy and mail are there)', async () => {
    const w = mountOpen()
    await create(w)
    expect(w.find('[data-testid="invite-link-copy"]').exists()).toBe(true)
    expect(w.find('[data-testid="invite-link-mail"]').exists()).toBe(true)
    expect(w.find('[data-testid="invite-link-share"]').exists()).toBe(false)
  })

  it('a tenant that cannot send is refused here too, with its own message, and nothing is shown to hand over', async () => {
    vi.mocked(createMemberInviteLink).mockRejectedValueOnce(new Error('503'))
    vi.mocked(memberError).mockResolvedValueOnce({ status: 503, code: 'mail_unavailable' })
    const w = mountOpen()
    const first = vi.mocked(prepareInviteCode).mock.results[0].value as ReturnType<typeof prepareInviteCode>
    await create(w)
    expect(w.text()).toContain(t('members.mailUnavailable'))
    expect(w.find('[data-testid="invite-link-message"]').exists()).toBe(false)
    expect(w.emitted('invited')).toBeUndefined()
    expect(first.ahead.discard).toHaveBeenCalled() // its code is never reused
    expect(prepareInviteCode).toHaveBeenCalledTimes(2)
  })

  it('a reopened dialog starts on the link way again, the last link gone', async () => {
    const w = mountOpen()
    await create(w)
    await w.setProps({ visible: false })
    await w.setProps({ visible: true })
    expect(w.find('[data-testid="invite-link-message"]').exists()).toBe(false)
    expect(w.find('[data-testid="invite-link-create"]').exists()).toBe(true)
  })
})
