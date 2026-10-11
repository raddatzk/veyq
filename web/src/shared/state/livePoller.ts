// The foreground poll loop of a list that others change (inbox #408: an open level view picks up a peer's upload).
// `createVisibilityPoller` is the fixed-interval loop of the pairing and media pollers; this one is for a poll that
// runs for as long as a page is open and so must be a good citizen of the server it shares with every other tab:
//
// - **Bounded, jittered interval.** The next tick is `intervalMs` ± `jitter` after the previous one SETTLED (not
//   after it started), so a slow request never overlaps its successor and a hundred tabs opened together drift apart.
// - **Backs off on errors.** A rejected tick doubles the delay (to `maxBackoffMs`), jittered; the first success is
//   back to the interval. A server that is down or overloaded is asked less often, not more.
// - **Only while the tab is visible.** A hidden tab asks nothing: its timer is cleared when it hides and nothing is
//   scheduled until it shows again. On the return to visible — and on window focus, which a tab switch inside one
//   window does not always pair with a visibility change — it ticks at once (a returning viewer catches up now, not
//   in 15 s), unless a tick ran within `minGapMs` (the two events arrive together; one tick, not two).
// - **One tick at a time, and a stop fences it.** A trigger while a tick is in flight is dropped. `stop()` aborts the
//   signal each tick was handed; a tick checks it after every await before applying anything. A stopped poller
//   schedules nothing, whatever a late settle says.
// - **`pause`-able by its owner.** `tick` may resolve `'skip'` (the owner is mid-write or has a dialog open): that is
//   not an error, not a success to count, and it asks again after the plain interval.
//
// A factory holding no module state: every call is an independent loop. It knows nothing of Vue; the level view's
// composable (`core/node/useLevelLive.ts`) owns the lifecycle.

export type LiveTick = (signal: AbortSignal) => Promise<void | 'skip'>

export interface LivePollerOptions {
  /** The base delay between ticks. */
  intervalMs: number
  /** The longest delay after repeated errors. */
  maxBackoffMs: number
  /** Relative spread: a delay `d` is drawn from `d * (1 ± jitter)`. 0 disables it. */
  jitter: number
  /** The least time between two tick STARTS caused by a visibility or focus trigger. */
  minGapMs: number
  /** Replaces `Math.random` (tests). */
  random?: () => number
}

export interface LivePoller {
  /** (Re)start with `tick`: no immediate tick (the caller has just loaded), the first one after the interval. */
  start(tick: LiveTick): void
  /** Stop for good: timer and listeners gone, an in-flight tick fenced. Safe when not running, and from inside a tick. */
  stop(): void
}

const documentVisible = () => typeof document === 'undefined' || document.visibilityState === 'visible'

export function createLivePoller(options: LivePollerOptions): LivePoller {
  const { intervalMs, maxBackoffMs, jitter, minGapMs } = options
  const random = options.random ?? Math.random
  let stopRun: (() => void) | null = null

  const stop = () => {
    stopRun?.()
    stopRun = null
  }

  const spread = (ms: number) => Math.max(0, Math.round(ms * (1 + jitter * (2 * random() - 1))))

  const start: LivePoller['start'] = (tick) => {
    stop()
    const run = new AbortController()
    let timer: ReturnType<typeof setTimeout> | null = null
    let inFlight = false
    let failures = 0
    let lastStart = Number.NEGATIVE_INFINITY

    const clear = () => {
      if (timer !== null) clearTimeout(timer)
      timer = null
    }
    const schedule = (delayMs: number) => {
      clear()
      if (run.signal.aborted || !documentVisible()) return // hidden: the visibility listener schedules again
      timer = setTimeout(fire, spread(delayMs))
    }
    const delayAfterFailure = () => Math.min(maxBackoffMs, intervalMs * 2 ** failures)

    function fire(): void {
      timer = null
      if (run.signal.aborted || inFlight || !documentVisible()) return
      inFlight = true
      lastStart = Date.now()
      let pending: Promise<void | 'skip'>
      try {
        pending = tick(run.signal)
      } catch {
        pending = Promise.reject(new Error('tick threw'))
      }
      void pending.then(
        (outcome) => {
          inFlight = false
          if (run.signal.aborted) return
          if (outcome !== 'skip') failures = 0
          schedule(intervalMs)
        },
        () => {
          inFlight = false
          if (run.signal.aborted) return
          failures += 1
          schedule(delayAfterFailure())
        },
      )
    }

    /** The tab became visible or focused: tick now, unless one just ran or is running. */
    const wake = () => {
      if (run.signal.aborted || inFlight || !documentVisible()) return
      if (Date.now() - lastStart < minGapMs) return
      clear()
      fire()
    }
    const onVisibility = () => {
      if (documentVisible()) wake()
      else clear() // hidden: nothing is asked until it shows again
    }

    if (typeof document !== 'undefined') document.addEventListener('visibilitychange', onVisibility)
    if (typeof window !== 'undefined') window.addEventListener('focus', wake)
    stopRun = () => {
      run.abort()
      clear()
      if (typeof document !== 'undefined') document.removeEventListener('visibilitychange', onVisibility)
      if (typeof window !== 'undefined') window.removeEventListener('focus', wake)
    }
    schedule(intervalMs)
  }

  return { start, stop }
}
