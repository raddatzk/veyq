// The one foreground poll loop (uc.memory.live-updates E3, uc.account.onboard-device): tick on an interval, skip
// ticks while the tab is hidden, tick once the moment it becomes visible again. Every poller in the client is an
// instance of this — they used to hand-write the loop five times, and none of the copies had the two properties
// below, which are the reason it exists:
//
// - **A tick never starts while one is in flight.** It is skipped, not queued. A timer tick and a visibility tick,
//   or two timer ticks around a request slower than the interval, used to run at once — for the pairing collect
//   that is a real defect (the loser's 404 ends the ceremony while the winner's tail runs, or the tail runs twice).
// - **`stop()` and a restart fence every in-flight tick.** Each run hands its tick an `AbortSignal` and aborts it
//   on stop/restart; a tick checks `signal.aborted` after every await and before applying anything, so a result
//   that arrives after the stop is dropped. "Anything" includes calling the owner's `stop()`: a fenced tick that
//   stops the poller stops the run that REPLACED it. The fence only stops LOCAL effects: a tick whose side effect
//   already happened on the server (a delivered grant, a rotated scope, a collected envelope) is not undone by it
//   — there the in-flight guard is what matters. For the same reason a tick must not hand the signal to a request
//   that spends something server-side: aborting it would discard a response whose effect has already landed.
//
// A factory, never a shared timer, and it holds no module-level state: every call returns an independent loop.
// The two pairing loops are why that matters — one reads the answer, which spends nothing, the other takes the
// grant, which spends the channel, and one shared loop would conflate the two silently, in the direction that
// loses the grant. So what is shared is the mechanism; each poller keeps its own instance and its own stop
// condition. It knows nothing about sessions either: the onboarded device polls with neither a session nor a vault.

export type PollTick = (signal: AbortSignal) => void | Promise<unknown>

export interface VisibilityPoller {
  /** (Re)start the loop with `tick`, fencing any run already going. `immediate` ticks once at start, whatever the
   *  tab's visibility — the thing waited for may already be there. */
  start(tick: PollTick, options?: { immediate?: boolean }): void
  /** Stop the loop, detach the visibility listener, and fence an in-flight tick. Safe to call when not running,
   *  and from inside a tick. */
  stop(): void
}

const documentVisible = () => typeof document === 'undefined' || document.visibilityState === 'visible'

/** A foreground poll loop ticking every `intervalMs`. A tick owns its errors: a rejection is swallowed here only
 *  so that it ends the in-flight window, never reported.
 *
 *  Testing a poller whose tick returns a promise: drive the clock with `advanceTimersByTimeAsync`. The sync
 *  `advanceTimersByTime` never lets even an already-resolved promise settle, so every later tick reads as
 *  in flight and is skipped — which looks like a broken loop and is the guard working. */
export function createVisibilityPoller(intervalMs: number): VisibilityPoller {
  let stopRun: (() => void) | null = null

  const stop = () => {
    stopRun?.()
    stopRun = null
  }

  const start: VisibilityPoller['start'] = (tick, { immediate = false } = {}) => {
    stop()
    const run = new AbortController()
    let inFlight = false
    const fire = () => {
      if (inFlight || run.signal.aborted) return
      const pending = tick(run.signal)
      if (!pending) return
      inFlight = true
      const settle = () => {
        inFlight = false
      }
      void pending.then(settle, settle)
    }
    const timer = setInterval(() => {
      if (documentVisible()) fire()
    }, intervalMs)
    const onVisible = () => {
      if (documentVisible()) fire()
    }
    if (typeof document !== 'undefined') document.addEventListener('visibilitychange', onVisible)
    stopRun = () => {
      run.abort()
      clearInterval(timer)
      if (typeof document !== 'undefined') document.removeEventListener('visibilitychange', onVisible)
    }
    if (immediate) fire()
  }

  return { start, stop }
}
