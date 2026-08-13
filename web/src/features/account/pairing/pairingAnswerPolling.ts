// uc.account.onboard-device (web) — the trusted device's wait for the other device to answer.
//
// Structurally this is the fourth poll loop in the client and it follows the three that exist (fire once
// immediately, skip ticks while the tab is hidden, re-fire on `visibilitychange`, idempotent `start()`). It
// could reuse none of them: `grantDeliveryPolling`, `scopeRotationPolling` and `mediaRevisionPolling` all gate
// on an open vault and an active scope, and a pairing channel is neither — it is a foreground ceremony with a
// ten-minute ceiling.
//
// The interval is short because someone is watching the screen while it runs, and the read is cheap: a channel
// that holds only an answer costs nothing to look at.
import { collectPairingChannel } from './pairingChannel'
import { isHttpStatus, HttpStatus } from '../../../shared/httpError'

const POLL_INTERVAL_MS = 2_000

let timer: ReturnType<typeof setInterval> | null = null
let onVisible: (() => void) | null = null

const documentVisible = () => typeof document === 'undefined' || document.visibilityState === 'visible'

/**
 * Watch `handle` until the other device answers.
 *
 * `onAnswer` fires exactly once and the loop is stopped before it does. `onGone` fires on a `404` — which every
 * terminal state answers with, deliberately indistinguishably (uc.account.onboard-device E1), so it is one
 * message and never a retry. Any other failure is left alone: a dropped network on one tick is not a dead
 * channel, and the next tick catches up.
 */
export function startPairingAnswerPolling(
  handle: string,
  onAnswer: (answer: string) => void,
  onGone: () => void,
): void {
  stopPairingAnswerPolling()
  const tick = async () => {
    try {
      const channel = await collectPairingChannel(handle)
      if (!channel.answer) return
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
      stopPairingAnswerPolling()
      onAnswer(channel.answer)
    } catch (e) {
      if (isHttpStatus(e, HttpStatus.notFound)) {
        stopPairingAnswerPolling()
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
  void tick() // the other device may already have answered
}

/** Stop watching + detach the visibility listener. Safe to call when not running, and called on every exit
 *  path of the screen — including the one where the user declines the comparison. */
export function stopPairingAnswerPolling(): void {
  if (timer !== null) {
    clearInterval(timer)
    timer = null
  }
  if (onVisible && typeof document !== 'undefined') document.removeEventListener('visibilitychange', onVisible)
  onVisible = null
}
