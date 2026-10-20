// @vitest-environment happy-dom
// uc.account.onboard-device § Main flow — add a new device, the half that runs on the device being let in.
//
// Real crypto, the real poll loop, the real card; only the wire and the tail are mocked. Three properties live
// here and nowhere else:
//   - **the number on screen belongs to the key that answered** — derived locally at mint time, so it is there
//     before the channel is touched and is still the right number when the answer is refused;
//   - **the *stay signed in* answer is taken BEFORE the first call and reaches adoption**, because the refresh
//     lifetime is fixed there and no later refresh can move it (ADR 0004 § web refresh cookie lifetimes);
//   - **the tail is entered at most once.** Its first call spends the only token this device is ever handed, so
//     every failure after the envelope arrives is terminal — a screen that offered a retry would revoke the
//     family of the device standing in front of the user.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { httpError } from '../../../test/httpError'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'

vi.mock('./pairingChannel', () => ({
  answerPairingChannel: vi.fn(),
  collectPairingChannel: vi.fn(),
}))
vi.mock('./onboardTail', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./onboardTail')>()),
  runOnboardTail: vi.fn(),
}))
vi.mock('../rememberMe/deviceKeyWrap', () => ({ isRememberSupported: vi.fn() }))
const capable = vi.fn((_id: string) => true)
vi.mock('../../../shared/state/useCapabilities', () => ({
  useCapabilities: () => ({ hasCapability: (id: string) => capable(id) }),
}))

import JoinDeviceCard from './JoinDeviceCard.vue'
import { answerPairingChannel, collectPairingChannel } from './pairingChannel'
import { runOnboardTail, OnboardTailError } from './onboardTail'
import { isRememberSupported } from '../rememberMe/deviceKeyWrap'
import { readTransportAnswer, checkNumber, sealGrant, channelKeys, mintPairingSecret, type PairingGrant } from './pairing'
import { realI18n, t } from '../../../test/i18n'
import { CORE_REMEMBER_DEVICE, CORE_REMEMBER_DEVICE_PASSWORDLESS } from '../../platform/capabilities'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'

/** What the relay reads off the card's answer — `lp(transport key) || lp(mac)`: the key, and nothing it could
 *  not see. */
const relayReadsTheKey = (posted: string): v1.Identity['pub'] => {
  const bytes = fromB64std(posted)
  const len = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength).getUint32(0, false)
  return v1.deserializeIdentityPublic(bytes.slice(4, 4 + len))
}

const answer = vi.mocked(answerPairingChannel)
const collect = vi.mocked(collectPairingChannel)
const tail = vi.mocked(runOnboardTail)
const rememberSupported = vi.mocked(isRememberSupported)

/** The secret the scanned code carried, and the channel it derives on this card and on the device that showed it. */
const SECRET = mintPairingSecret()
const CHANNEL = channelKeys(SECRET)
const MASTER_KEY = new Uint8Array(32).fill(9)
const GRANT: PairingGrant = {
  email: 'kevin@example.com',
  masterKey: MASTER_KEY,
  refreshToken: 'the-grant-token',
  label: 'Kevin’s iPad',
}


const stubs = {
  Message: { props: ['severity'], template: '<div class="msg"><slot /></div>' },
  Button: {
    props: ['label', 'icon', 'severity', 'disabled', 'text'],
    emits: ['click'],
    template: '<button :disabled="disabled" @click="$emit(\'click\')">{{ label }}</button>',
  },
  Checkbox: {
    props: ['modelValue', 'inputId', 'binary', 'disabled'],
    emits: ['update:modelValue'],
    template:
      '<input type="checkbox" :id="inputId" :checked="modelValue" :disabled="disabled" @change="$emit(\'update:modelValue\', $event.target.checked)" />',
  },
}

const buttonLabelled = (w: VueWrapper, label: string) => w.findAll('button').find((b) => b.text() === label)

const mountCard = () => mount(JoinDeviceCard, { props: { secret: SECRET }, global: { plugins: [realI18n()], stubs } })

/** Let the answer POST settle and the poll arm. */
const started = async (w: VueWrapper) => {
  await flushPromises()
  await vi.advanceTimersByTimeAsync(0)
  await flushPromises()
  await w.vm.$nextTick()
}

/** Seal a grant to whatever key this card actually answered with — the only way the envelope can be opened by
 *  the private half the card kept, and therefore the only honest fixture. */
const sealToTheCard = (grant: PairingGrant = GRANT) =>
  sealGrant(CHANNEL.key, readTransportAnswer(CHANNEL.key, answer.mock.calls[0][1]), grant)

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
  answer.mockResolvedValue(undefined)
  collect.mockResolvedValue({})
  tail.mockResolvedValue(undefined)
})

afterEach(() => vi.useRealTimers())

describe('JoinDeviceCard — the device being let in (uc.account.onboard-device, ADR 0059)', () => {
  it('shows the check number before it touches the channel, and answers with that same key', async () => {
    answer.mockReturnValue(new Promise(() => {})) // the POST never settles
    const w = mountCard()
    await w.vm.$nextTick()

    const shown = w.find('[data-testid="join-check-number"]').text()
    expect(shown).toMatch(/^\d{5}$/)
    // The number is local, so a channel that never answered — or one an attacker answered first — cannot move
    // it. That is the whole reason the comparison means anything.
    expect(answer).toHaveBeenCalledTimes(1)
    // Only the handle reaches the server; the answer is MACed under the key the same secret derives (§11).
    expect(answer.mock.calls[0][0]).toBe(CHANNEL.handle)
    expect(checkNumber(readTransportAnswer(CHANNEL.key, answer.mock.calls[0][1]))).toBe(shown)
  })

  it('opens the grant with the private half it kept, and runs the tail with it', async () => {
    const w = mountCard()
    await started(w)
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
      answer.mockResolvedValue(undefined)
      collect.mockResolvedValue({})
      tail.mockResolvedValue(undefined)

      const w = mountCard()
      // Answered while the card is still waiting: adoption has not happened, and after it the lifetime is fixed.
      if (stay) await w.find('#join-stay').setValue(true)
      await started(w)
      expect(tail).not.toHaveBeenCalled()

      await grantArrives(w)
      expect(tail.mock.calls[0][1]).toBe(stay)
      w.unmount()
    }
  })

  it('asks only where core.remember-device.passwordless is on — its parent leaf alone does not offer a DEVICE_KEY wrap (ADR 0088 decision 11)', async () => {
    rememberSupported.mockReturnValue(true)
    capable.mockImplementation((id) => id !== CORE_REMEMBER_DEVICE_PASSWORDLESS)
    const off = mountCard()
    expect(off.find('#join-stay').exists()).toBe(false)
    await started(off)
    await grantArrives(off)
    expect(tail.mock.calls[0][1]).toBe(false)
    off.unmount()

    // the control on the same predicate: switch the OTHER leaf off instead and the question is put
    vi.clearAllMocks()
    capable.mockImplementation((id) => id !== CORE_REMEMBER_DEVICE)
    const on = mountCard()
    expect(on.find('#join-stay').exists()).toBe(true)
    await started(on)
  })

  it('does not ask where it cannot honour the answer, and then takes the only reachable tier', async () => {
    // ADR 0005 §22 / the sign-in form's own gate: with no IndexedDB or device trust switched off there is
    // nowhere to enrol a wrap, so the question is not put and the declined arm is the only tier there is.
    rememberSupported.mockReturnValue(false)
    const w = mountCard()
    await started(w)

    expect(w.find('#join-stay').exists()).toBe(false)
    await grantArrives(w)
    expect(tail.mock.calls[0][1]).toBe(false)
  })

  it('shows one terminal message when the channel is already gone, and never answers twice', async () => {
    answer.mockRejectedValue(httpError(404))
    const w = mountCard()
    await started(w)

    expect(w.find('.msg').text()).toBe(t('pairing.gone'))
    expect(w.text()).toContain(t('pairing.joinGoneHint'))
    await vi.advanceTimersByTimeAsync(600_000)
    expect(answer).toHaveBeenCalledTimes(1)
    expect(collect).not.toHaveBeenCalled()
    expect(tail).not.toHaveBeenCalled()

    // Terminal is not a dead end: the way out is back to the sign-in form, where a fresh scan starts a fresh
    // ceremony. What is NOT offered is a retry of this channel — E2's whole point.
    await buttonLabelled(w, t('pairing.joinBack'))!.trigger('click')
    expect(w.emitted('cancel')).toHaveLength(1)
    expect(buttonLabelled(w, t('common.cancel'))).toBeUndefined()
  })

  it('shows the same terminal message when the channel dies while waiting', async () => {
    const w = mountCard()
    await started(w)
    collect.mockRejectedValue(httpError(404))
    await vi.advanceTimersByTimeAsync(2_000)
    await flushPromises()
    await w.vm.$nextTick()

    expect(w.find('.msg').text()).toBe(t('pairing.gone'))
    expect(tail).not.toHaveBeenCalled()
  })

  it('tells adoption’s two failures apart — and enters the tail exactly once for either', async () => {
    for (const [reason, message] of [
      ['gone', t('pairing.gone')],
      ['session_present', t('pairing.joinOccupied')],
    ] as const) {
      vi.clearAllMocks()
      answer.mockResolvedValue(undefined)
      collect.mockResolvedValue({})
      tail.mockRejectedValue(new OnboardTailError(reason))

      const w = mountCard()
      await started(w)
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

  // The unclassified tail failure (`pairing.joinFailed`, no retry) is pinned once for both cards, in
  // `useGrantReceiver.test.ts` — both run that one function.

  // ADR 0059 §11 — anyone can seal to the new device's public key, and the relay has read it off the answer. An
  // envelope it made itself would hand this device a session and a Master Key of the relay's choosing. The
  // honest envelope above (`opens the grant with the private half it kept`) is the live control.
  // Two relays: a bare seal (the envelope as it was before §11 — refused as `envelope_unreadable`), and a seal
  // with a MAC under the one derived value the relay does hold, the handle (refused as `envelope_refused`; the
  // named refusals are pinned in `pairing.test.ts`). Either would hand this device the relay's session and key.
  it.each(['bare seal', 'seal MACed under the handle'] as const)(
    'RELAY — refuses an envelope the relay sealed to this device itself (%s), and runs no tail',
    async (variant) => {
      const w = mountCard()
      await started(w)

      const relayGrant = { v: 1, email: 'kevin@example.com', masterKey: b64std(new Uint8Array(32).fill(6)), refreshToken: 'the-relays-token', label: 'x' }
      const pub = relayReadsTheKey(answer.mock.calls[0][1])
      const sealed = v1.sealTo({ purpose: 'pairing-grant' }, pub.kem, v1.utf8(JSON.stringify(relayGrant)))
      const tag = v1.pairingMac(fromB64std(CHANNEL.handle), { purpose: 'pairing-envelope', envelope: sealed })
      const envelope = variant === 'bare seal' ? b64std(sealed) : b64std(v1.concat(v1.lp(sealed), v1.lp(tag)))
      await grantArrives(w, envelope)

      expect(tail).not.toHaveBeenCalled()
      expect(w.find('.msg').text()).toBe(t('pairing.joinFailed'))
      expect(w.emitted('done')).toBeUndefined()
    },
  )

  it('stops polling when it is dismissed, so an abandoned card cannot eat the envelope', async () => {
    const w = mountCard()
    await started(w)
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
