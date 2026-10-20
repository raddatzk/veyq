// uc.account.onboard-device (web) — the onboarded device's wait for the grant to be sealed to it.
//
// This is `pairingAnswerPolling`'s **sibling, not a reuse of it**: both are instances of `createVisibilityPoller`
// with the same shape (fire once immediately, skip ticks while the tab is hidden, re-fire on return, a `404` ends
// it once and for all), and the opposite stop condition. That one stops on `answer` because reading the answer
// spends nothing; this one stops on `envelope`, which is the read that spends the channel. That inversion is the
// whole reason each keeps its own instance: one shared loop would be one place for *reading costs nothing* and
// *reading spends the channel* to be conflated, and the conflation is silent in exactly the direction that loses
// the grant. (It is NOT that the two ever run on one screen — in both directions the new device polls only the
// envelope and the trusted device only the answer, one loop per device.) This is also the only loop in the client
// that runs with neither a session nor a vault, which is why the poller knows nothing of either.
import { collectPairingChannel } from './pairingChannel'
import { isHttpStatus, HttpStatus } from '../../../shared/httpError'
import { createVisibilityPoller } from '../../../shared/state/visibilityPoller'

const POLL_INTERVAL_MS = 2_000

const poller = createVisibilityPoller(POLL_INTERVAL_MS)

/**
 * Watch `handle` until the trusted device seals a grant to this one.
 *
 * `onEnvelope` fires exactly once and the loop is stopped before it does — the collect that returns an envelope
 * marks the row `COLLECTED`, so there is nothing left to look at and a further tick would only produce the
 * `404` that means "gone". `onGone` fires on a `404`, which every terminal state answers with, deliberately
 * indistinguishably (uc.account.onboard-device E1) — including the ten-minute expiry that bounds this loop, so
 * no separate deadline is kept here. Any other failure is left alone: one dropped tick is not a dead channel.
 * A collect still in flight when the loop is stopped or restarted applies nothing. The signal is deliberately
 * not handed to the collect: it spends the channel server-side, so aborting it would only discard a grant
 * that has already been taken.
 */
export function startGrantEnvelopePolling(
  handle: string,
  onEnvelope: (envelope: string) => void,
  onGone: () => void,
): void {
  const tick = async (signal: AbortSignal) => {
    try {
      const channel = await collectPairingChannel(handle)
      if (signal.aborted || !channel.envelope) return
      poller.stop()
      onEnvelope(channel.envelope)
    } catch (e) {
      if (!signal.aborted && isHttpStatus(e, HttpStatus.notFound)) {
        poller.stop()
        onGone()
      }
    }
  }
  poller.start(tick, { immediate: true }) // the trusted device may already have sealed
}

/** Stop watching + detach the visibility listener. Safe to call when not running, and called on every exit
 *  path of the card — including the one where the ceremony fails after the envelope has arrived. */
export function stopGrantEnvelopePolling(): void {
  poller.stop()
}
