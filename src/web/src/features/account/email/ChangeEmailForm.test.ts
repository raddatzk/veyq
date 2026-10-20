// @vitest-environment happy-dom
// uc.account.change-email (web) — "Change sign-in address": closed → the form → "check your mailbox". The request
// itself is emailChange.test.ts's; this holds what the screen does with its answers, and that it refuses the address
// the account already signs in with before anything is derived.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { httpError } from '../../../test/httpError'

vi.mock('./emailChange', async (orig) => ({
  ...(await orig<typeof import('./emailChange')>()),
  requestEmailChange: vi.fn(),
}))

import ChangeEmailForm from './ChangeEmailForm.vue'
import { requestEmailChange, EmailChangeRefused } from './emailChange'
import { realI18n, t } from '../../../test/i18n'

const CURRENT = 'me@example.com'
const NEW = 'me.new@example.com'

const stubs = {
  Button: {
    props: ['label', 'loading', 'disabled', 'type'],
    emits: ['click'],
    template: '<button :type="type || \'button\'" :disabled="disabled" @click="$emit(\'click\')">{{ label }}</button>',
  },
  Message: { props: ['severity'], template: '<div class="msg" :data-severity="severity"><slot /></div>' },
  InputText: { props: ['modelValue'], emits: ['update:modelValue'], template: '<input name="new-email" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  Password: { props: ['modelValue'], emits: ['update:modelValue'], template: '<input name="password" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  SecretKeyField: { props: ['modelValue'], emits: ['update:modelValue'], template: '<input name="secret-key" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
}

function mountForm() {
  return mount(ChangeEmailForm, { props: { currentEmail: CURRENT }, global: { plugins: [realI18n()], stubs } })
}

async function fill(w: ReturnType<typeof mountForm>, email = NEW) {
  await w.find('[data-testid="change-email-open"]').trigger('click')
  await w.find('input[name="new-email"]').setValue(email)
  await w.find('input[name="password"]').setValue('the password')
  await w.find('input[name="secret-key"]').setValue('c2VjcmV0')
  await w.find('form').trigger('submit')
  await flushPromises()
}

describe('ChangeEmailForm', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(requestEmailChange).mockResolvedValue(undefined)
  })

  it('is one button until opened', () => {
    const w = mountForm()
    expect(w.find('[data-testid="change-email-form"]').exists()).toBe(false)
    expect(w.text()).toContain(t('emailChange.action'))
  })

  it('sends the new address, the password and the Secret Key, then says to check the new mailbox', async () => {
    const w = mountForm()
    await fill(w)
    expect(requestEmailChange).toHaveBeenCalledWith(NEW, 'the password', 'c2VjcmV0')
    expect(w.find('[data-testid="change-email-sent"]').text()).toBe(t('emailChange.sent', { email: NEW }))
    expect(w.find('[data-testid="change-email-form"]').exists()).toBe(false)
  })

  it('refuses the address it already signs in with, in any spelling, before anything is derived', async () => {
    const w = mountForm()
    await fill(w, `  ${CURRENT.toUpperCase()} `)
    expect(requestEmailChange).not.toHaveBeenCalled()
    expect(w.text()).toContain(t('emailChange.unchanged'))
    // Control: another address goes through from the same open form.
    await w.find('input[name="new-email"]').setValue(NEW)
    await w.find('form').trigger('submit')
    await flushPromises()
    expect(requestEmailChange).toHaveBeenCalledOnce()
  })

  it('stays on the form with the reason when the proof or the server refuses', async () => {
    vi.mocked(requestEmailChange).mockRejectedValueOnce(new EmailChangeRefused('credentialRejected'))
    const w = mountForm()
    await fill(w)
    expect(w.text()).toContain(t('emailChange.errorCredential'))
    expect(w.find('[data-testid="change-email-sent"]').exists()).toBe(false)

    vi.mocked(requestEmailChange).mockRejectedValueOnce(httpError(503, 'mail_unavailable'))
    await w.find('form').trigger('submit')
    await flushPromises()
    expect(w.text()).toContain(t('emailConfirm.mailUnavailable'))
  })
})
