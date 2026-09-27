// uc.account.onboard-device (web, ADR 0059) — the half of BOTH receiving cards that is the same: the device being
// let in holds a single-use transport key, asks the *stay signed in* question, waits for the grant sealed to that
// key, opens it under the channel key, and runs the shared tail exactly once. `JoinDeviceCard` (add a device)
// and `SignInWithCodeCard` (sign in with a QR code) each keep only their direction's own half: how the channel
// is reached and what the answer carries.
import { computed, onUnmounted, ref, type Ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { mintTransportKey, openGrant, type PairingChannelKeys } from './pairing'
import { startGrantEnvelopePolling, stopGrantEnvelopePolling } from './grantEnvelopePolling'
import { runOnboardTail, OnboardTailError } from './onboardTail'
import { isRememberSupported } from '../rememberMe/deviceKeyWrap'
import { useCapabilities } from '../../../shared/state/useCapabilities'
import { ACCOUNT_DEVICE_TRUST } from '../../platform/capabilities'
import { isHttpStatus, HttpStatus } from '../../../shared/httpError'
import type * as v1 from '@veyq/crypto/v1'

/**
 * The receiving steps both cards share. `gone`, `occupied` and `failed` are all terminal, and none of them offers
 * a way back into the ceremony. Two of them cannot be retried at all — after the envelope arrives the grant token
 * is spent, and re-presenting it is ADR 0004's clone case, which revokes the family of the device standing right
 * here (see `onboardTail`).
 */
export type ReceiverStep = 'waiting' | 'finishing' | 'gone' | 'occupied' | 'failed'

/** `S` is the card's own steps before it waits for the grant. */
export function useGrantReceiver<S extends string>(initial: S) {
  const { t } = useI18n()
  const { hasCapability } = useCapabilities()

  const step = ref(initial) as Ref<S | ReceiverStep>
  const error = ref('')

  /** The single-use transport keypair. Minted once, here: its private half never leaves this page load, and the
   *  grant is unreadable without it. */
  const transportKey: v1.Identity = mintTransportKey()

  // The same gate the sign-in form puts on its own "remember me" (ADR 0032): without IndexedDB or with device
  // trust switched off instance-wide there is nowhere to enrol a wrap, so the question is not asked — and the
  // declined arm is then the only reachable tier (ADR 0005 §22).
  const staySupported = isRememberSupported() && hasCapability(ACCOUNT_DEVICE_TRUST)
  const stay = ref(false)
  /** ADR 0059 §2 / ADR 0004 § web refresh cookie lifetimes: answered HERE, before the session exists, because
   *  adoption fixes the lifetime and no later refresh can move it. It is also ADR 0005 §22's tier choice. */
  const staySignedIn = computed(() => staySupported && stay.value)

  /** The one state that locks the controls: the tail is running, the lifetime the checkbox chooses is already
   *  fixed at adoption, and there is nothing left to cancel. */
  const settling = computed(() => step.value === 'finishing')

  /** Set on unmount. The card's POSTs are in flight when the user cancels, so without this the loop would be
   *  started *after* the card is gone — and an orphaned loop does not merely leak a timer here: its next tick
   *  collects the envelope and spends the channel, leaving the real attempt with nothing. */
  let disposed = false

  /** Every terminal server state answers `404`, deliberately indistinguishably (E1) — so there is one message and
   *  it is never a retry. */
  function onGone() {
    stopGrantEnvelopePolling()
    step.value = 'gone'
  }

  function fail(message: string) {
    error.value = message
    step.value = 'failed'
  }

  /** The card's answer POST failed. A `404` is a channel already spent, expired or superseded — the ordinary
   *  terminal state; anything else is the card's own message. Terminal either way: the slot is write-once. */
  function onAnswerFailed(e: unknown, message: string) {
    if (disposed) return
    if (isHttpStatus(e, HttpStatus.notFound)) return onGone()
    fail(message)
  }

  /**
   * The grant arrived. From here the tail runs exactly once: a failure inside it is terminal, because its first
   * call spent the only token this device will ever be handed.
   *
   * An envelope whose MAC does not verify under the channel key is refused here, before anything is opened or
   * adopted (ADR 0059 §11): anyone can seal to this device's public key, and the relay read it off the answer. It
   * takes the path every unreadable envelope takes — `pairing.joinFailed`, and no tail.
   */
  async function onEnvelope(channelKey: Uint8Array, envelope: string) {
    step.value = 'finishing'
    try {
      const grant = openGrant(channelKey, transportKey.priv, envelope)
      await runOnboardTail(grant, staySignedIn.value)
      return true
    } catch (e) {
      if (e instanceof OnboardTailError && e.reason === 'session_present') {
        step.value = 'occupied'
        return false
      }
      if (e instanceof OnboardTailError) {
        step.value = 'gone'
        return false
      }
      fail(t('pairing.joinFailed'))
      return false
    }
  }

  /** The answer is posted: wait for the grant on `channel`, and call `onDone` once the tail has completed. */
  function awaitGrant(channel: PairingChannelKeys, onDone: () => void) {
    if (disposed) return
    step.value = 'waiting'
    startGrantEnvelopePolling(
      channel.handle,
      (envelope) => void onEnvelope(channel.key, envelope).then((done) => done && onDone()),
      onGone,
    )
  }

  onUnmounted(() => {
    disposed = true
    stopGrantEnvelopePolling()
  })

  return {
    step,
    error,
    transportKey,
    staySupported,
    stay,
    settling,
    isDisposed: () => disposed,
    onGone,
    fail,
    onAnswerFailed,
    awaitGrant,
  }
}
