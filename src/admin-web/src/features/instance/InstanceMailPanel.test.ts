// @vitest-environment happy-dom
// uc.admin.learn-of-new-releases (admin-web, ADR 0097 decisions 3 and 5) — Server → Mail. Each case is one observable:
//   - the password is write-only: sent only when typed, cleared from the field after the save, never pre-filled;
//   - a save answered CODE_SENT offers the code entry, and the code confirms (main flow 3);
//   - a wrong code is refused in words, and the entry stays (A3);
//   - a key that cannot open a stored password is said, for a relay that logs in (A4).
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('./instanceMail', async () => {
  const actual = await vi.importActual<typeof import('./instanceMail')>('./instanceMail')
  return { ...actual, getInstanceMail: vi.fn(), updateInstanceMail: vi.fn(), confirmInstanceMail: vi.fn() }
})

import InstanceMailPanel from './InstanceMailPanel.vue'
import { confirmInstanceMail, getInstanceMail, updateInstanceMail, type InstanceMailSettings } from './instanceMail'
import { i18n } from '../../i18n'
import { httpError } from '../../test/httpError'

const stubs = {
  VeyqMessage: { props: ['tone'], template: '<div class="msg" :data-severity="tone"><slot /></div>' },
  VeyqInput: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  VeyqSwitch: { props: ['modelValue'], template: '<input type="checkbox" :checked="modelValue" />' },
  VeyqButton: {
    props: ['label', 'disabled', 'type'],
    emits: ['click'],
    template: '<button :type="type ?? \'button\'" :disabled="disabled" @click="$emit(\'click\', $event)">{{ label }}</button>',
  },
}

const ADDRESS = 'owner@example.com'

function settings(over: Partial<InstanceMailSettings> = {}): InstanceMailSettings {
  return {
    host: undefined, port: undefined, from: undefined, username: undefined, tls: true, passwordSet: false, configSecretState: 'OK',
    address: ADDRESS, confirmation: 'NOT_CONFIGURED', confirmedAt: undefined, ...over,
  }
}

const configured = settings({ host: 'smtp.example.com', from: 'veyq@example.com', username: 'veyq', passwordSet: true })

async function mountPanel() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'login', component: { template: '<div/>' } }] })
  const wrapper = mount(InstanceMailPanel, { global: { plugins: [router, i18n], stubs } })
  await flushPromises()
  return wrapper
}

describe('InstanceMailPanel (uc.admin.learn-of-new-releases, admin-web)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    i18n.global.locale.value = 'en'
  })

  it('names the address the mail would go to, and sends a typed password once, then forgets it', async () => {
    vi.mocked(getInstanceMail).mockResolvedValue(settings())
    vi.mocked(updateInstanceMail).mockResolvedValue({ ...configured, confirmation: 'CODE_SENT' })
    const w = await mountPanel()
    expect(w.find('.mail-confirmation').text()).toContain(ADDRESS)
    await w.find('.mail-host').setValue('smtp.example.com')
    await w.find('.mail-from').setValue('veyq@example.com')
    await w.find('.mail-password').setValue('relay-secret')
    await w.find('.mail-save').trigger('click')
    await flushPromises()
    expect(updateInstanceMail).toHaveBeenCalledWith(expect.objectContaining({ host: 'smtp.example.com', from: 'veyq@example.com', password: 'relay-secret' }))
    expect((w.find('.mail-password').element as HTMLInputElement).value).toBe('')

    // A second save with the field left blank keeps the stored password: the field is not sent at all.
    await w.find('.mail-save').trigger('click')
    await flushPromises()
    expect(vi.mocked(updateInstanceMail).mock.calls[1][0]).not.toHaveProperty('password')
  })

  it('offers the code entry after a save, and the code confirms', async () => {
    vi.mocked(getInstanceMail).mockResolvedValue({ ...configured, confirmation: 'CODE_SENT' })
    vi.mocked(confirmInstanceMail).mockResolvedValue({ ...configured, confirmation: 'CONFIRMED', confirmedAt: new Date('2026-10-16T12:00:00Z') })
    const w = await mountPanel()
    expect(w.find('.mail-confirmation').attributes('data-state')).toBe('CODE_SENT')
    await w.find('.mail-code__input').setValue('48213075')
    await w.find('.mail-code').trigger('submit')
    await flushPromises()
    expect(confirmInstanceMail).toHaveBeenCalledWith('48213075')
    expect(w.find('.mail-confirmation').attributes('data-state')).toBe('CONFIRMED')
    expect(w.find('.mail-code').exists()).toBe(false) // nothing left to enter
  })

  it('refuses a wrong code in words and keeps the entry open', async () => {
    vi.mocked(getInstanceMail).mockResolvedValue({ ...configured, confirmation: 'CODE_SENT' })
    vi.mocked(confirmInstanceMail).mockRejectedValue(httpError(409, 'mail_code_invalid'))
    const w = await mountPanel()
    await w.find('.mail-code__input').setValue('00000000')
    await w.find('.mail-code').trigger('submit')
    await flushPromises()
    expect(w.text()).toContain(i18n.global.t('instanceMail.codeInvalid'))
    expect(w.find('.mail-code').exists()).toBe(true)
    expect(w.find('.mail-confirmation').attributes('data-state')).toBe('CODE_SENT')
  })

  it('says when the config-secret key cannot open the stored password, for a relay that logs in', async () => {
    vi.mocked(getInstanceMail).mockResolvedValue({ ...configured, configSecretState: 'MISMATCH', confirmation: 'CONFIRMED', confirmedAt: new Date() })
    const w = await mountPanel()
    expect(w.find('.mail-key').text()).toBe(i18n.global.t('instanceMail.keyMismatch'))
  })

  it('says nothing about the key for a relay that does not log in', async () => {
    // Control for the case above: the same state, no username — the key is not asked for, so it is not a problem.
    vi.mocked(getInstanceMail).mockResolvedValue({ ...configured, username: undefined, configSecretState: 'MISMATCH' })
    const w = await mountPanel()
    expect(w.find('.mail-key').exists()).toBe(false)
  })
})
