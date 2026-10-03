// @vitest-environment happy-dom
// uc.account.onboard-device § Main flow — sign in with a QR code, the half that runs on the device that wants
// in. Real crypto, the real poll loop, the real card; only the wire and the shared tail are mocked.
//
// Four properties live here and nowhere else:
//   - **the displayed code carries the channel's secret under THIS direction's marker, and never the transport key**
//     (D1) — the key is 1260 bytes and would put the code past the decoder bound `web/CLAUDE.md`'s jsQR
//     exception rests on, and it would buy nothing: in this direction the scan authenticates nothing;
//   - **the answer carries key AND proof as one value** (D2), because `seal` takes no code and never may;
//   - **there is exactly ONE send.** The server's answer slot is write-once, so a second post — including one
//     from a double click landing while the first is still in flight — burns the ceremony;
//   - **a refused proof is learned only as the channel going quiet.** There is no status to relay a refusal
//     through, so the terminal state here is the ordinary expiry, and the copy has to say *start again*
//     rather than *keep waiting* (E2).
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { httpError } from '../../../test/httpError'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'

vi.mock('./pairingChannel', () => ({
  openPairingChannel: vi.fn(),
  answerPairingChannel: vi.fn(),
  collectPairingChannel: vi.fn(),
}))
vi.mock('./onboardTail', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./onboardTail')>()),
  runOnboardTail: vi.fn(),
}))
vi.mock('../rememberMe/deviceKeyWrap', () => ({ isRememberSupported: vi.fn() }))
const capable = vi.fn(() => true)
vi.mock('../../../shared/state/useCapabilities', () => ({
  useCapabilities: () => ({ hasCapability: () => capable() }),
}))

import SignInWithCodeCard from './SignInWithCodeCard.vue'
import { openPairingChannel, answerPairingChannel, collectPairingChannel } from './pairingChannel'
import { runOnboardTail, OnboardTailError } from './onboardTail'
import { isRememberSupported } from '../rememberMe/deviceKeyWrap'
import {
  parseSignInPayload,
  parsePairingPayload,
  readSignInAnswer,
  sealGrant,
  channelKeys,
  PairingRefused,
  type PairingGrant,
} from './pairing'
import { realI18n, t } from '../../../test/i18n'

const open = vi.mocked(openPairingChannel)
const answer = vi.mocked(answerPairingChannel)
const collect = vi.mocked(collectPairingChannel)
const tail = vi.mocked(runOnboardTail)
const rememberSupported = vi.mocked(isRememberSupported)

const CODE = '481502'
const GRANT: PairingGrant = {
  email: 'kevin@example.com',
  masterKey: new Uint8Array(32).fill(9),
  refreshToken: 'the-grant-token',
  label: 'Kevin’s laptop',
}


const stubs = {
  Message: { props: ['severity'], template: '<div class="msg"><slot /></div>' },
  Button: {
    props: ['label', 'icon', 'severity', 'loading', 'disabled', 'text'],
    emits: ['click'],
    template: '<button :disabled="loading || disabled" @click="$emit(\'click\')">{{ label }}</button>',
  },
  Checkbox: {
    props: ['modelValue', 'inputId', 'binary', 'disabled'],
    emits: ['update:modelValue'],
    template:
      '<input type="checkbox" :id="inputId" :checked="modelValue" :disabled="disabled" @change="$emit(\'update:modelValue\', $event.target.checked)" />',
  },
  InputText: {
    props: ['modelValue', 'fluid', 'maxlength', 'inputmode', 'autocomplete', 'spellcheck'],
    emits: ['update:modelValue'],
    template: '<input :id="$attrs.id" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  // The QR encoder is loaded lazily and draws nothing a test can read; WHICH payload it is handed is the point.
  PairingCode: { props: ['payload'], template: '<div class="qr">{{ payload }}</div>' },
}

const buttonLabelled = (w: VueWrapper, label: string) => w.findAll('button').find((b) => b.text() === label)

const mountCard = () => mount(SignInWithCodeCard, { global: { plugins: [realI18n()], stubs } })

/** The secret the card's code showed — read off the screen the way the scanning device reads it. */
let shownSecret = ''
/** The channel that secret derives, on the card and on the device that scanned it. */
const shownChannel = () => channelKeys(shownSecret)

/** Let the channel open and the card reach its transcription step. */
const opened = async (w: VueWrapper) => {
  await flushPromises()
  await vi.advanceTimersByTimeAsync(0)
  await flushPromises()
  await w.vm.$nextTick()
  const qr = w.find('.qr')
  if (qr.exists()) shownSecret = parseSignInPayload(qr.text()) ?? ''
}

/** Type the code and confirm it — the one send. */
const transcribe = async (w: VueWrapper, code = CODE) => {
  await w.find('#sign-in-code').setValue(code)
  await buttonLabelled(w, t('pairing.signInSend'))!.trigger('click')
  await flushPromises()
  await w.vm.$nextTick()
}

/** Seal a grant to whatever key this card actually answered with — the only key whose private half it kept,
 *  and therefore the only honest fixture. */
const sealToTheCard = (grant: PairingGrant = GRANT) =>
  sealGrant(shownChannel().key, readSignInAnswer(shownChannel().key, CODE, answer.mock.calls[0][1]), grant)

const grantArrives = async (w: VueWrapper, envelope = sealToTheCard()) => {
  collect.mockResolvedValue({ envelope })
  await vi.advanceTimersByTimeAsync(2_000)
  await flushPromises()
  await w.vm.$nextTick()
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.useFakeTimers()
  rememberSupported.mockReturnValue(true)
  capable.mockReturnValue(true)
  open.mockResolvedValue(undefined)
  answer.mockResolvedValue(undefined)
  collect.mockResolvedValue({})
  tail.mockResolvedValue(undefined)
})

afterEach(() => vi.useRealTimers())

describe('SignInWithCodeCard — the device that wants in (uc.account.onboard-device, ADR 0059)', () => {
  it('shows a code that carries the channel’s secret under its own marker, and no key', async () => {
    const w = mountCard()

    await opened(w)

    const shown = w.find('.qr').text()
    expect(shown).toBe(`veyqsignin:${shownSecret}`)
    // Only the handle the secret derives reaches the server — never the secret, never the channel key (§11).
    expect(open).toHaveBeenCalledExactlyOnceWith(shownChannel().handle)
    expect(open.mock.calls[0][0]).not.toBe(shownSecret)
    // §4, as a property of the string rather than of the intent behind it.
    expect(shown).not.toContain('://')
    // D1's reason for the second marker: read as the OTHER direction's code this would mount the join card and
    // burn the write-once answer slot. It must not parse there.
    expect(parsePairingPayload(shown)).toBeNull()
    // The transport bundle is 1260 bytes; a payload carrying it would be ~1700 chars and a version-28 QR.
    expect(shown.length).toBeLessThan(80)
  })

  it('sends the transcribed code as key AND proof in the channel’s one answer', async () => {
    const w = mountCard()
    await opened(w)
    expect(answer).not.toHaveBeenCalled() // nothing is posted before the code is confirmed

    await transcribe(w)

    expect(answer).toHaveBeenCalledTimes(1)
    expect(answer.mock.calls[0][0]).toBe(open.mock.calls[0][0])
    // The proof holds for the code that was typed, under the channel key — which is what binds the two (§6, §11).
    const posted = answer.mock.calls[0][1]
    expect(readSignInAnswer(shownChannel().key, CODE, posted)).toBeTruthy()
    expect(() => readSignInAnswer(shownChannel().key, '481503', posted)).toThrow(new PairingRefused('answer_refused'))
  })

  it('will not send until the transcription is six digits', async () => {
    const w = mountCard()
    await opened(w)

    for (const partial of ['', '4815', '48150a', '4815022']) {
      await w.find('#sign-in-code').setValue(partial)
      expect(buttonLabelled(w, t('pairing.signInSend'))!.attributes('disabled')).toBeDefined()
    }
    // The control on the same predicate: the real thing is accepted, so the guard is about the shape and not
    // about a button that never enables.
    await w.find('#sign-in-code').setValue(CODE)
    expect(buttonLabelled(w, t('pairing.signInSend'))!.attributes('disabled')).toBeUndefined()
  })

  it('posts exactly once, even for clicks landing inside one tick — the `disabled` attribute is not the guard', async () => {
    // The server's answer slot is write-once: a second post is refused, and the ceremony is burned on whichever
    // one lost. `:disabled` cannot carry this — Vue patches the attribute on the next tick, so two clicks inside
    // one tick both reach the handler in a real browser. The flag set synchronously before the await is what
    // holds, and driving the clicks WITHOUT awaiting between them is the only way to reach it: awaiting each
    // trigger flushes the patch first and the test then passes with no guard at all.
    answer.mockReturnValue(new Promise(() => {}))
    const w = mountCard()
    await opened(w)

    await w.find('#sign-in-code').setValue(CODE)
    const confirm = buttonLabelled(w, t('pairing.signInSend'))!
    const inOneTick = [confirm.trigger('click'), confirm.trigger('click'), confirm.trigger('click')]
    await Promise.all(inOneTick)
    await flushPromises()

    // The control on the same predicate: one post DID happen, so this is about the second and third and not
    // about a confirm button that never fires.
    expect(answer).toHaveBeenCalledTimes(1)
  })

  it('opens the grant with the private half it kept, and runs the tail with it exactly once', async () => {
    const w = mountCard()
    await opened(w)
    await transcribe(w)
    expect(tail).not.toHaveBeenCalled()

    await grantArrives(w)

    expect(tail).toHaveBeenCalledTimes(1)
    // Equality of the whole grant is the proof that the envelope was opened with THIS card's key and not with
    // some other key it happened to hold.
    expect(tail.mock.calls[0][0]).toEqual(GRANT)
    expect(w.emitted('done')).toHaveLength(1)
  })

  it('takes the “stay signed in” answer before any call, and hands it to the tail — both ways', async () => {
    for (const stay of [false, true]) {
      vi.clearAllMocks()
      open.mockResolvedValue(undefined)
      answer.mockResolvedValue(undefined)
      collect.mockResolvedValue({})
      tail.mockResolvedValue(undefined)

      const w = mountCard()
      await opened(w)
      // Answered while the code is still being typed: adoption has not happened, and after it the lifetime is
      // fixed for good (ADR 0004 § web refresh cookie lifetimes).
      if (stay) await w.find('#sign-in-stay').setValue(true)
      await transcribe(w)
      expect(tail).not.toHaveBeenCalled()

      await grantArrives(w)
      expect(tail.mock.calls[0][1]).toBe(stay)
      w.unmount()
    }
  })

  it('does not ask where it cannot honour the answer, and then takes the only reachable tier', async () => {
    // ADR 0005 §22 / the sign-in form's own gate: with no IndexedDB or device trust switched off there is
    // nowhere to enrol a wrap, so the question is not put and the declined arm is the only tier there is.
    rememberSupported.mockReturnValue(false)
    const w = mountCard()
    await opened(w)

    expect(w.find('#sign-in-stay').exists()).toBe(false)
    await transcribe(w)
    await grantArrives(w)
    expect(tail.mock.calls[0][1]).toBe(false)
  })

  // E2, and the one the whole direction turns on: a WRONG code is refused on the trusted device, which seals
  // nothing. This side is never told — it sees the channel go quiet and then expire. The accepted case is the
  // live control on the same predicate, because "the tail never ran" is satisfied by a card that never works.
  it('a refused proof ends in the ordinary expiry, told to start again — against an accepted one as control', async () => {
    const refused = mountCard()
    await opened(refused)
    await transcribe(refused)

    // Nothing is sealed, so the channel simply runs out its ten minutes.
    collect.mockRejectedValue(httpError(404))
    await vi.advanceTimersByTimeAsync(2_000)
    await flushPromises()
    await refused.vm.$nextTick()

    expect(refused.find('.msg').text()).toBe(t('pairing.gone'))
    // The copy must send the person back to the start rather than leave them waiting at a dead screen: there is
    // no refusal to relay, so "keep waiting" would be waiting for something that can never arrive.
    expect(refused.text()).toContain(t('pairing.signInGoneHint'))
    expect(tail).not.toHaveBeenCalled()
    // Terminal is not a retry: this channel's answer slot is spent, and the way out is a fresh start.
    expect(buttonLabelled(refused, t('pairing.signInSend'))).toBeUndefined()
    await vi.advanceTimersByTimeAsync(600_000)
    expect(answer).toHaveBeenCalledTimes(1)
    refused.unmount()

    vi.clearAllMocks()
    open.mockResolvedValue(undefined)
    answer.mockResolvedValue(undefined)
    collect.mockResolvedValue({})
    tail.mockResolvedValue(undefined)

    const accepted = mountCard()
    await opened(accepted)
    await transcribe(accepted)
    await grantArrives(accepted)

    expect(accepted.text()).toContain(t('pairing.joinFinishing'))
    expect(tail).toHaveBeenCalledTimes(1)
  })

  it('shows the same terminal message when the answer is refused outright, and never posts again', async () => {
    answer.mockRejectedValue(httpError(404))
    const w = mountCard()
    await opened(w)
    await transcribe(w)

    expect(w.find('.msg').text()).toBe(t('pairing.gone'))
    await vi.advanceTimersByTimeAsync(600_000)
    expect(answer).toHaveBeenCalledTimes(1)
    expect(collect).not.toHaveBeenCalled()
    expect(tail).not.toHaveBeenCalled()

    await buttonLabelled(w, t('pairing.joinBack'))!.trigger('click')
    expect(w.emitted('cancel')).toHaveLength(1)
  })

  it('tells adoption’s two failures apart — and enters the tail exactly once for either', async () => {
    for (const [reason, message] of [
      ['gone', t('pairing.gone')],
      ['session_present', t('pairing.joinOccupied')],
    ] as const) {
      vi.clearAllMocks()
      open.mockResolvedValue(undefined)
      answer.mockResolvedValue(undefined)
      collect.mockResolvedValue({})
      tail.mockRejectedValue(new OnboardTailError(reason))

      const w = mountCard()
      await opened(w)
      await transcribe(w)
      await grantArrives(w)

      expect(w.find('.msg').text()).toBe(message)
      // The grant token is spent by now. Nothing on this screen may lead back into the tail — a second adoption
      // is ADR 0004's clone case and revokes this device's own family.
      await vi.advanceTimersByTimeAsync(600_000)
      await flushPromises()
      expect(tail).toHaveBeenCalledTimes(1)
      expect(w.emitted('done')).toBeUndefined()
      w.unmount()
    }
  })

  it('stops polling when it is dismissed, so an abandoned card cannot eat the envelope', async () => {
    const w = mountCard()
    await opened(w)
    await transcribe(w)
    const atDismissal = collect.mock.calls.length
    expect(atDismissal).toBeGreaterThanOrEqual(1) // the control: it really was polling

    w.unmount()
    await vi.advanceTimersByTimeAsync(600_000)
    await flushPromises()

    // A collect that returns the envelope spends the channel whoever asked, so an orphaned loop does not leak a
    // timer — it takes the grant the real attempt is waiting for.
    expect(collect).toHaveBeenCalledTimes(atDismissal)
  })
})
