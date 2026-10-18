// @vitest-environment happy-dom
// uc.moderation.serve-as-moderator (web) — the moderation surface's states: signed out (its own sign-in, never the member
// login), not appointed, pending (the acceptance text names child sexual abuse material and the log), accepted with an
// unconfirmed address, locked (password AND Secret Key), and unlocked (the empty queue, or waiting for the key).
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { httpError } from '../test/httpError'

vi.mock('../features/moderation/appointment', () => ({
  myAppointment: vi.fn(),
  acceptAppointment: vi.fn(),
  sendAddressCode: vi.fn(),
  confirmAddress: vi.fn(),
  stepDown: vi.fn(),
}))
vi.mock('../features/moderation/unlockModeration', async (orig) => ({
  ...(await orig<object>()),
  unlockModeration: vi.fn(),
}))
vi.mock('../features/account/login/login', () => ({ login: vi.fn() }))
// The queue behind the gate reads the cases with a key proof; here it is empty (ModerationQueue.test.ts covers it).
vi.mock('../features/moderation/cases', () => ({ listCases: vi.fn() }))

import ModerationView from './ModerationView.vue'
import { listCases } from '../features/moderation/cases'
import { acceptAppointment, confirmAddress, myAppointment, stepDown } from '../features/moderation/appointment'
import { ModerationBundleMismatch, unlockModeration } from '../features/moderation/unlockModeration'
import { session } from '../app/session'
import { realI18n, t } from '../test/i18n'
import type { ModeratorAppointment } from '../api/generated/moderation'

const stubs = {
  Card: { template: '<div><slot name="title" /><slot name="content" /></div>' },
  Button: { props: ['label'], emits: ['click'], template: '<button :class="$attrs.class" @click="$emit(\'click\')">{{ label }}</button>' },
  Message: { template: '<div class="msg"><slot /></div>' },
  InputText: { props: ['modelValue'], emits: ['update:modelValue'], template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  Password: { props: ['modelValue'], emits: ['update:modelValue'], template: '<input type="password" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  SecretKeyField: { props: ['modelValue'], emits: ['update:modelValue'], template: '<input name="secret-key" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
}

const appointment = (over: Partial<ModeratorAppointment> = {}): ModeratorAppointment => ({
  state: 'ACCEPTED',
  appointedAt: new Date(),
  acceptedAt: new Date(),
  address: 'deputy@example.com',
  confirmation: 'CONFIRMED',
  awaitingKey: false,
  ...over,
})

async function mountView() {
  const w = mount(ModerationView, { global: { plugins: [realI18n()], stubs } })
  await flushPromises()
  return w
}

describe('ModerationView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    session.accessToken = 'access-token'
    vi.mocked(listCases).mockResolvedValue([])
  })

  it('signed out, it signs in itself and reads nothing before', async () => {
    session.accessToken = null
    const w = await mountView()
    expect(myAppointment).not.toHaveBeenCalled()
    expect(w.find('.moderation-sign-in').exists()).toBe(true)
  })

  it('with a session, it shows no sign-in form while the appointment is read', async () => {
    vi.mocked(myAppointment).mockReturnValue(new Promise(() => {}))
    const w = await mountView()
    expect(w.find('.moderation-sign-in-form').exists()).toBe(false)
    expect(w.text()).toContain(t('moderation.loading'))
  })

  it('an account that is not appointed is told so and offered nothing', async () => {
    vi.mocked(myAppointment).mockResolvedValue(appointment({ state: 'NONE', appointedAt: undefined, acceptedAt: undefined }))
    const w = await mountView()
    expect(w.text()).toContain(t('moderation.notAppointed'))
    expect(w.find('.moderation-unlock').exists()).toBe(false)
  })

  it('a pending appointment says what accepting means before it is accepted', async () => {
    vi.mocked(myAppointment).mockResolvedValue(appointment({ state: 'PENDING', acceptedAt: undefined }))
    vi.mocked(acceptAppointment).mockResolvedValue(appointment({ confirmation: 'CODE_SENT' }))
    const w = await mountView()
    expect(w.text()).toContain(t('moderation.acceptCsam'))
    expect(w.text()).toContain(t('moderation.acceptLogged'))
    await w.find('.moderation-accept-button').trigger('click')
    await flushPromises()
    expect(acceptAppointment).toHaveBeenCalled()
    expect(w.find('.moderation-address').exists()).toBe(true)
  })

  it('a wrong code is the code refusal, not a generic one', async () => {
    vi.mocked(myAppointment).mockResolvedValue(appointment({ confirmation: 'CODE_SENT' }))
    vi.mocked(confirmAddress).mockRejectedValue(httpError(409, 'mail_code_invalid'))
    const w = await mountView()
    await w.find('.moderation-code').setValue('12345678')
    await w.find('.moderation-address').trigger('submit')
    await flushPromises()
    expect(w.text()).toContain(t('moderation.codeInvalid'))
  })

  it('unlocks with the password and the Secret Key, then shows the empty queue', async () => {
    vi.mocked(myAppointment).mockResolvedValue(appointment())
    vi.mocked(unlockModeration).mockResolvedValue({ holdsCurrent: true, delivered: 1, declined: 0 })
    const w = await mountView()
    const [pw, sk] = w.find('.moderation-unlock').findAll('input')
    await pw.setValue('pw')
    await sk.setValue('sk')
    await w.find('.moderation-unlock').trigger('submit')
    await flushPromises()
    expect(unlockModeration).toHaveBeenCalledWith('deputy@example.com', 'pw', 'sk')
    expect(w.text()).toContain(t('moderation.queueEmpty'))
    expect(w.text()).toContain(t('moderation.delivered', { count: 1 }, 1))
    // The step-down is offered once the key is held, and its last-holder refusal is said as such.
    vi.mocked(stepDown).mockRejectedValue(httpError(409, 'moderation_last_holder'))
    await w.find('.moderation-step-down').trigger('click')
    await flushPromises()
    expect(w.text()).toContain(t('moderation.lastHolder'))
  })

  it('a published key that is not the opened one stops the unlock with its own warning', async () => {
    vi.mocked(myAppointment).mockResolvedValue(appointment())
    vi.mocked(unlockModeration).mockRejectedValue(new ModerationBundleMismatch(3))
    const w = await mountView()
    await w.find('.moderation-unlock').trigger('submit')
    await flushPromises()
    expect(w.text()).toContain(t('moderation.bundleMismatch', { epoch: 3 }))
    expect(w.text()).not.toContain(t('moderation.queueEmpty'))
  })

  it('a moderator still waiting for the key is told how it reaches them', async () => {
    vi.mocked(myAppointment).mockResolvedValue(appointment({ awaitingKey: true }))
    vi.mocked(unlockModeration).mockResolvedValue({ holdsCurrent: false, delivered: 0, declined: 0 })
    const w = await mountView()
    await w.find('.moderation-unlock').trigger('submit')
    await flushPromises()
    expect(w.text()).toContain(t('moderation.awaitingKey'))
  })
})
