// How the app tells the start watchdog (`src/boot/watchdog.ts`, ADR 0136) where its start is: the boot step it waits
// for, and whether it mounted or failed. Document attributes and one event — nothing stored, nothing sent (decision 3).
// The watchdog imports nothing, so it spells these names itself; `boot/watchdog.test.ts` holds both spellings equal.

/** The step `boot()` (or `main.ts` after it) is waiting for, on `<html>`: what the watchdog's slow panel names. */
export const START_STEP_ATTRIBUTE = 'data-veyq-start-step'
/** `mounted` or `failed`, on `<html>`: read by a watchdog that loads after the app signalled. */
export const START_STATE_ATTRIBUTE = 'data-veyq-start'
/** Dispatched on `document` with each state; the watchdog cancels it when it answers with a reload. */
export const START_EVENT = 'veyq:start'

/** The steps a start can wait for: `boot()`'s three, then the first route (`main.ts`). */
export type StartStep = 'status' | 'session' | 'vault' | 'route'
export type StartState = 'mounted' | 'failed'

function root(): HTMLElement | null {
  return typeof document === 'undefined' ? null : document.documentElement
}

/** Mark the step the start now waits for. A no-op without a document (the node test environment). */
export function markStartStep(step: StartStep): void {
  root()?.setAttribute(START_STEP_ATTRIBUTE, step)
}

/**
 * Signal the start's outcome. Returns whether the watchdog answered with a reload (it cancels the event then), so a
 * caller holding an error it would otherwise throw — a chunk that did not load — can suppress it only in that case.
 */
export function signalStart(state: StartState): boolean {
  const el = root()
  if (!el) return false
  el.setAttribute(START_STATE_ATTRIBUTE, state)
  return !document.dispatchEvent(new Event(START_EVENT, { cancelable: true }))
}

/**
 * Run the app's start and signal its outcome: `mounted` once [start] resolved (it mounts), `failed` when it rejected —
 * an unexpected throw in `boot()` or in the first route's resolution, which used to leave the page blank.
 */
export async function runStart(start: () => Promise<void>): Promise<void> {
  try {
    await start()
  } catch (e) {
    console.error('veyq: the app did not start', e)
    signalStart('failed')
    return
  }
  signalStart('mounted')
}

/**
 * A chunk that no longer loads (`vite:preloadError`, a server upgraded under an open tab) is a failed start (ADR 0136
 * decision 4): the watchdog reloads once per five minutes. Only when it does is the error suppressed; otherwise it
 * propagates as before, so the start's own failure path (or, after the mount, the navigation's) still sees it.
 */
export function reloadOnChunkFailure(target: Pick<Window, 'addEventListener'>): void {
  target.addEventListener('vite:preloadError', (event) => {
    if (signalStart('failed')) event.preventDefault()
  })
}
