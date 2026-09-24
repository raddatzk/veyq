// uc.account.onboard-device (web) — the trusted device's wait for the other device to answer.
//
// An instance of `createVisibilityPoller` (fire once immediately, skip ticks while the tab is hidden, re-fire on
// return), kept apart from `grantEnvelopePolling`'s for the reason that file's header records: the two loops
// have opposite stop conditions, and only one of them spends the channel.
//
// The interval is short because someone is watching the screen while it runs, and the read is cheap: a channel
// that holds only an answer costs nothing to look at.
import { collectPairingChannel } from './pairingChannel'
import { isHttpStatus, HttpStatus } from '../../../shared/httpError'
import { createVisibilityPoller } from '../../../shared/state/visibilityPoller'

const POLL_INTERVAL_MS = 2_000

const poller = createVisibilityPoller(POLL_INTERVAL_MS)

/**
 * Watch `handle` until the other device answers.
 *
 * `onAnswer` fires exactly once and the loop is stopped before it does. `onGone` fires on a `404` — which every
 * terminal state answers with, deliberately indistinguishably (uc.account.onboard-device E1), so it is one
 * message and never a retry. Any other failure is left alone: a dropped network on one tick is not a dead
 * channel, and the next tick catches up. A collect still in flight when the loop is stopped or restarted
 * applies nothing.
 */
export function startPairingAnswerPolling(
  handle: string,
  onAnswer: (answer: string) => void,
  onGone: () => void,
): void {
  const tick = async (signal: AbortSignal) => {
    try {
      const channel = await collectPairingChannel(handle)
      if (signal.aborted || !channel.answer) return
      // ┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
      // │ THE TRUSTED DEVICE STOPS HERE, AND NEVER POLLS THIS CHANNEL AGAIN. After the seal, a collect from │
      // │ ANY caller returns the envelope and marks the row COLLECTED — so a poll that outlives the answer  │
      // │ consumes this device's own grant before the onboarded device ever arrives, and the ceremony fails │
      // │ with both screens looking correct.                                                               │
      // │                                                                                                  │
      // │ The cost is deliberate: this device therefore CANNOT show "the new device arrived", and its       │
      // │ terminal state is "sealed — finish on the new device". A later "did it work?" poll re-opens the   │
      // │ defect exactly. There is no server signal that is free to read here; do not add one.              │
      // └─────────────────────────────────────────────────────────────────────────────────────────────────┘
      poller.stop()
      onAnswer(channel.answer)
    } catch (e) {
      if (!signal.aborted && isHttpStatus(e, HttpStatus.notFound)) {
        poller.stop()
        onGone()
      }
    }
  }
  poller.start(tick, { immediate: true }) // the other device may already have answered
}

/** Stop watching + detach the visibility listener. Safe to call when not running, and called on every exit
 *  path of the screen — including the one where the user declines the comparison. */
export function stopPairingAnswerPolling(): void {
  poller.stop()
}
