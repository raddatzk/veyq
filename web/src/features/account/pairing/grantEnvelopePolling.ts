// uc.account.onboard-device (web) — the onboarded device's wait for the grant to be sealed to it.
//
// This is `pairingAnswerPolling`'s **sibling, not a reuse of it**: same structure (fire once immediately, skip
// ticks while the tab is hidden, re-fire on `visibilitychange`, idempotent `start()`, a `404` ends it once and
// for all), opposite stop condition. That one stops on `answer` because reading the answer spends nothing; this
// one stops on `envelope`, which is the read that spends the channel. That inversion is the whole reason for the
// split: one shared module-level timer would be one place for *reading costs nothing* and *reading spends the
// channel* to be conflated, and the conflation is silent in exactly the direction that loses the grant. (It is
// NOT that the two ever run on one screen — in both directions the new device polls only the envelope and the
// trusted device only the answer, one loop per device.) So each keeps its own state.
//
// The three older loops (`grantDeliveryPolling`, `scopeRotationPolling`, `mediaRevisionPolling`) remain
// unusable here for the reason `pairingAnswerPolling` already records, and harder: this is the only loop in the
// client that runs with neither a session nor a vault.
import { collectPairingChannel } from './pairingChannel'
import { isHttpStatus, HttpStatus } from '../../../shared/httpError'

const POLL_INTERVAL_MS = 2_000

let timer: ReturnType<typeof setInterval> | null = null
let onVisible: (() => void) | null = null

const documentVisible = () => typeof document === 'undefined' || document.visibilityState === 'visible'

/**
 * Watch `handle` until the trusted device seals a grant to this one.
 *
 * `onEnvelope` fires exactly once and the loop is stopped before it does — the collect that returns an envelope
 * marks the row `COLLECTED`, so there is nothing left to look at and a further tick would only produce the
 * `404` that means "gone". `onGone` fires on a `404`, which every terminal state answers with, deliberately
 * indistinguishably (uc.account.onboard-device E1) — including the ten-minute expiry that bounds this loop, so
 * no separate deadline is kept here. Any other failure is left alone: one dropped tick is not a dead channel.
 */
export function startGrantEnvelopePolling(
  handle: string,
  onEnvelope: (envelope: string) => void,
  onGone: () => void,
): void {
  stopGrantEnvelopePolling()
  const tick = async () => {
    try {
      const channel = await collectPairingChannel(handle)
      if (!channel.envelope) return
      stopGrantEnvelopePolling()
      onEnvelope(channel.envelope)
    } catch (e) {
      if (isHttpStatus(e, HttpStatus.notFound)) {
        stopGrantEnvelopePolling()
        onGone()
      }
    }
  }
  timer = setInterval(() => {
    if (documentVisible()) void tick()
  }, POLL_INTERVAL_MS)
  onVisible = () => {
    if (documentVisible()) void tick()
  }
  if (typeof document !== 'undefined') document.addEventListener('visibilitychange', onVisible)
  void tick() // the trusted device may already have sealed
}

/** Stop watching + detach the visibility listener. Safe to call when not running, and called on every exit
 *  path of the card — including the one where the ceremony fails after the envelope has arrived. */
export function stopGrantEnvelopePolling(): void {
  if (timer !== null) {
    clearInterval(timer)
    timer = null
  }
  if (onVisible && typeof document !== 'undefined') document.removeEventListener('visibilitychange', onVisible)
  onVisible = null
}
