// @vitest-environment happy-dom
// uc.account.onboard-device § Main flow — add a new device (web). The ceremony end to end on the trusted side,
// with the real poll loop, the real slide and the real crypto underneath; only the wire is mocked.
//
// Three properties live here and nowhere else:
//   - **E2 abandons, it does not retry.** Declining the comparison must reach NEITHER `delegate` nor `seal` —
//     and the restart must mint a different handle, because offering "try again" on the same channel is an
//     invitation to grant past the one signal that something else answered.
//   - **The order is delegate → seal**, asserted as a sequence rather than as two independent calls: two
//     `toHaveBeenCalled` lines are satisfied just as well by the wrong order, and the wrong order cannot work
//     (the token being sealed is what `delegate` returns).
//   - **The channel is not read again after the seal** (D7). A collect after the seal hands this device its own
//     grant and leaves the other one waiting forever, with both screens still looking correct.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'

vi.mock('./pairingChannel', () => ({
  openPairingChannel: vi.fn(),
  collectPairingChannel: vi.fn(),
  delegatePairingSession: vi.fn(),
  sealPairingChannel: vi.fn(),
}))

const MASTER_KEY = new Uint8Array(32).fill(7)
vi.mock('../../../app/vault', () => ({ masterKey: () => MASTER_KEY }))

import AddDevicePanel from './AddDevicePanel.vue'
import SlideToConfirm from '../../../shared/ui/SlideToConfirm.vue'
import {
  openPairingChannel,
  collectPairingChannel,
  delegatePairingSession,
  sealPairingChannel,
} from './pairingChannel'
import { mintTransportKey, transportAnswer, checkNumber, openGrant, parsePairingPayload } from './pairing'
import { account } from '../../../app/account'
import { i18n } from '../../../i18n'

const open = vi.mocked(openPairingChannel)
const collect = vi.mocked(collectPairingChannel)
const delegate = vi.mocked(delegatePairingSession)
const seal = vi.mocked(sealPairingChannel)

const REFRESH_TOKEN = 'ZGVsZWdhdGVkLXJlZnJlc2gtdG9rZW4'
const transport = mintTransportKey()
const ANSWER = transportAnswer(transport.pub)

const stubs = {
  Message: { props: ['severity'], template: '<div class="msg"><slot /></div>' },
  InputText: {
    props: ['modelValue', 'placeholder', 'fluid'],
    emits: ['update:modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  Button: {
    props: ['label', 'icon', 'severity', 'loading', 'disabled', 'outlined', 'text'],
    emits: ['click'],
    template: '<button :disabled="loading || disabled" @click="$emit(\'click\')">{{ label }}</button>',
  },
  // The QR encoder is loaded lazily from a dynamic import and draws nothing a test can read; what matters here
  // is WHICH payload it is handed, so the stub renders it.
  PairingCode: { props: ['payload'], template: '<div class="qr">{{ payload }}</div>' },
}

const t = (key: string, named?: Record<string, unknown>) => i18n.global.t(key, named ?? {})
const buttonLabelled = (w: VueWrapper, label: string) => w.findAll('button').find((b) => b.text() === label)

const mountPanel = () => mount(AddDevicePanel, { global: { plugins: [i18n], stubs } })

/** Advance the real poll loop by one interval and let its promise chain settle. */
const pollOnce = async () => {
  await vi.advanceTimersByTimeAsync(2_000)
  await flushPromises()
}

const showCode = async (w: VueWrapper) => {
  await buttonLabelled(w, t('pairing.start'))!.trigger('click')
  await flushPromises()
  await vi.advanceTimersByTimeAsync(0)
  await flushPromises()
}

const answerArrives = async (w: VueWrapper) => {
  collect.mockResolvedValue({ answer: ANSWER })
  await pollOnce()
  await w.vm.$nextTick()
}

const nameAndSlide = async (w: VueWrapper, label = 'Küchen-Tablet') => {
  await w.find('input').setValue(label)
  const slide = w.findComponent(SlideToConfirm)
  for (let i = 0; i < 10; i++) await slide.trigger('keydown', { key: 'ArrowRight' })
  await flushPromises()
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.useFakeTimers()
  i18n.global.locale.value = 'en'
  account.email = 'someone@example.org'
  open.mockResolvedValue(undefined)
  collect.mockResolvedValue({})
  delegate.mockResolvedValue(REFRESH_TOKEN)
  seal.mockResolvedValue(undefined)
})

afterEach(() => vi.useRealTimers())

describe('AddDevicePanel — the trusted half (uc.account.onboard-device, ADR 0059)', () => {
  it('shows a code that carries the opened channel’s handle and nothing else', async () => {
    const w = mountPanel()

    await showCode(w)

    const handle = open.mock.calls[0][0]
    expect(w.find('.qr').text()).toBe(`veyqpair:${handle}`)
    expect(parsePairingPayload(w.find('.qr').text())).toBe(handle)
  })

  it('shows the same check number the answering device derives, once the answer lands', async () => {
    const w = mountPanel()
    await showCode(w)
    expect(w.find('[data-testid="check-number"]').exists()).toBe(false)

    await answerArrives(w)

    expect(w.find('[data-testid="check-number"]').text()).toBe(checkNumber(transport.pub))
  })

  // D8 row 1, the positive control: the numbers match, the confirmation is carried out, and BOTH calls happen —
  // in the only order that can work, since the token being sealed is what `delegate` hands back.
  it('delegates and then seals when the confirmation is carried out', async () => {
    const w = mountPanel()
    await showCode(w)
    await answerArrives(w)

    await nameAndSlide(w)

    const order = [delegate, seal].map((fn) => fn.mock.invocationCallOrder[0])
    expect(delegate).toHaveBeenCalledExactlyOnceWith(open.mock.calls[0][0])
    expect(seal).toHaveBeenCalledTimes(1)
    expect(order[0]).toBeLessThan(order[1])
    expect(w.find('.msg').text()).toBe(t('pairing.sealed'))
  })

  it('seals the account’s Master Key, the delegated token and the typed name to the answering key', async () => {
    const w = mountPanel()
    await showCode(w)
    await answerArrives(w)

    await nameAndSlide(w, 'Arbeitslaptop')

    const [handle, envelope] = seal.mock.calls[0]
    expect(handle).toBe(open.mock.calls[0][0])
    // Opened with the private half the answering device kept — the only proof that the envelope went to the
    // key whose number was on screen, rather than to some other key the panel happened to hold.
    expect(openGrant(transport.priv, envelope)).toEqual({
      email: 'someone@example.org',
      masterKey: MASTER_KEY,
      refreshToken: REFRESH_TOKEN,
      label: 'Arbeitslaptop',
    })
  })

  // D8 row 1, the negative: declining the comparison must not reach the wire at all. Anchored on the two calls
  // themselves — "an error was shown" is produced equally well by a broken form.
  it('grants nothing when the numbers do not match', async () => {
    const w = mountPanel()
    await showCode(w)
    await answerArrives(w)

    await buttonLabelled(w, t('pairing.mismatch'))!.trigger('click')
    await flushPromises()

    expect(delegate).not.toHaveBeenCalled()
    expect(seal).not.toHaveBeenCalled()
    expect(w.find('[data-testid="check-number"]').exists()).toBe(false)
    expect(buttonLabelled(w, t('pairing.start'))).toBeTruthy() // back at the start, not at a retry
  })

  // D8 row 2: E2 abandons the channel rather than retrying it in place. The declined handle must not come back.
  it('mints a NEW handle after a declined comparison, and that ceremony completes normally', async () => {
    const w = mountPanel()
    await showCode(w)
    await answerArrives(w)
    const abandoned = open.mock.calls[0][0]

    await buttonLabelled(w, t('pairing.mismatch'))!.trigger('click')
    await flushPromises()
    await showCode(w)

    const restarted = open.mock.calls[1][0]
    expect(restarted).not.toBe(abandoned)

    // The control on the same predicate: the restart is a working ceremony, not merely a different string.
    await answerArrives(w)
    await nameAndSlide(w)
    expect(delegate).toHaveBeenCalledExactlyOnceWith(restarted)
    expect(seal.mock.calls[0][0]).toBe(restarted)
  })

  // D7 / D8 row 4, at the altitude that matters: the whole screen, after a real seal.
  it('never reads the channel again once it has the answer — the seal is not followed by a collect', async () => {
    const w = mountPanel()
    await showCode(w)
    const beforeTheAnswer = collect.mock.calls.length
    expect(beforeTheAnswer).toBeGreaterThanOrEqual(1) // the control: it was polling before

    await answerArrives(w)
    await nameAndSlide(w)
    expect(seal).toHaveBeenCalledTimes(1)
    const atTheSeal = collect.mock.calls.length

    await vi.advanceTimersByTimeAsync(600_000)
    await flushPromises()

    expect(collect).toHaveBeenCalledTimes(atTheSeal)
  })

  // D9: every terminal state answers 404 and the client cannot tell them apart, so it says one thing and
  // offers a fresh start rather than a retry of a channel that is gone.
  it('shows one terminal message on a 404 and stops polling', async () => {
    const w = mountPanel()
    await showCode(w)

    collect.mockRejectedValue(Object.assign(new Error('http'), { response: { status: 404 } }))
    await pollOnce()
    await w.vm.$nextTick()

    expect(w.find('.msg').text()).toBe(t('pairing.gone'))
    const atTheEnd = collect.mock.calls.length
    await vi.advanceTimersByTimeAsync(60_000)
    expect(collect).toHaveBeenCalledTimes(atTheEnd)
  })

  it('cannot be confirmed before the device is named', async () => {
    const w = mountPanel()
    await showCode(w)
    await answerArrives(w)

    const slide = w.findComponent(SlideToConfirm)
    for (let i = 0; i < 10; i++) await slide.trigger('keydown', { key: 'ArrowRight' })
    await flushPromises()

    expect(delegate).not.toHaveBeenCalled()
    expect(seal).not.toHaveBeenCalled()

    // The control: the same slide, once a name is there.
    await nameAndSlide(w)
    expect(seal).toHaveBeenCalledTimes(1)
  })
})
