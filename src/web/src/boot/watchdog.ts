// The start watchdog (ADR 0136 decision 2): the one script of the client outside the app's module graph. It is its own
// build entry, served unhashed at `/boot-watchdog.js` and loaded by `index.html` as an `async` module, so it runs
// whether or not the app's chunks load, and before them when they hang. It must import NOTHING — not a constant, not
// a type that survives compilation: an import would tie it to the very chunks whose hang it exists to answer.
// `pnpm run verify:boot-watchdog` reads the built file and is red on any import or export-from.
//
// It waits for the app's `mounted` signal (`app/startSignals.ts`). Without one after 15 s, or at once on `failed`:
//   - the first time within five minutes (a timestamp in sessionStorage, the only thing it writes) it reloads the page,
//     the reload a person does by hand today;
//   - otherwise it shows the start screen's slow panel: a heading, the step the start waits for (the step marker the
//     app puts on <html>) and a Reload button. Where sessionStorage throws there is no automatic reload, only the button,
//     so a server that hangs every request costs one reload, never a loop.
// After the mount it still answers `failed` (a chunk that no longer loads, decision 4) with that one reload; the start
// screen is gone by then, so there is no panel. It writes text and attributes only, never markup (Trusted Types,
// ADR 0117), and sends nothing (decision 3).
//
// The names below are the app's, spelled again because nothing may be imported; `watchdog.test.ts` holds them equal.

const STEP_ATTRIBUTE = 'data-veyq-start-step'
const STATE_ATTRIBUTE = 'data-veyq-start'
const START_EVENT = 'veyq:start'
const LOCALE_KEY = 'veyq:locale'
const RELOAD_KEY = 'veyq:boot-reload-at'
const DEADLINE_MS = 15_000
const RELOAD_WINDOW_MS = 5 * 60_000
/** The step named when the app never marked one: its code did not start (a chunk that never arrived). */
const NO_STEP = 'load'

/** For `watchdog.test.ts` alone: the app never imports this module (it would pull it into the app's graph). */
export const WATCHDOG_NAMES = { STEP_ATTRIBUTE, STATE_ATTRIBUTE, START_EVENT, LOCALE_KEY, RELOAD_KEY, DEADLINE_MS, RELOAD_WINDOW_MS }

const TEXT = {
  en: { heading: 'Taking longer than usual', step: 'Still waiting for step: ', reload: 'Reload' },
  de: { heading: 'Das dauert länger als üblich', step: 'Wartet noch auf Schritt: ', reload: 'Neu laden' },
} as const

/** The app's language rule (`i18n/index.ts`): the stored choice, else the OS language; anything not German is English. */
function language(): 'en' | 'de' {
  let stored: string | null = null
  try {
    stored = localStorage.getItem(LOCALE_KEY)
  } catch {
    stored = null
  }
  if (stored === 'en' || stored === 'de') return stored
  return (navigator.language || '').toLowerCase().startsWith('de') ? 'de' : 'en'
}

/** Whether this tab may reload now; records the reload when it may. Throwing storage never reloads. */
function mayReload(): boolean {
  try {
    const now = Date.now()
    const last = Number(sessionStorage.getItem(RELOAD_KEY))
    if (last > 0 && Math.abs(now - last) < RELOAD_WINDOW_MS) return false
    sessionStorage.setItem(RELOAD_KEY, String(now))
    return true
  } catch {
    return false
  }
}

function showSlowPanel(): void {
  const panel = document.getElementById('veyq-start-slow')
  if (!panel) return // the app mounted over the start screen: nothing to show
  const text = TEXT[language()]
  const set = (id: string, value: string) => {
    const el = document.getElementById(id)
    if (el) el.textContent = value
  }
  set('veyq-start-heading', text.heading)
  set('veyq-start-step', text.step + (document.documentElement.getAttribute(STEP_ATTRIBUTE) || NO_STEP))
  set('veyq-start-reload-label', text.reload)
  document.getElementById('veyq-start-reload')?.addEventListener('click', () => location.reload())
  panel.setAttribute('lang', language())
  document.getElementById('veyq-start-progress')?.setAttribute('hidden', '')
  panel.removeAttribute('hidden')
}

let settled = false
let mounted = false

/** The start hangs or failed: reload once per window, else (before the mount) the slow panel. */
function stall(): boolean {
  if (mayReload()) {
    location.reload()
    return true
  }
  if (!mounted && !settled) showSlowPanel()
  settled = true
  return false
}

const deadline = setTimeout(() => {
  if (!settled && !mounted) stall()
}, DEADLINE_MS)

/** Read the app's state; [event] is the signal's, cancelled when the answer is a reload. */
function onSignal(event?: Event): void {
  const state = document.documentElement.getAttribute(STATE_ATTRIBUTE)
  if (state === 'mounted') {
    mounted = true
    clearTimeout(deadline)
  } else if (state === 'failed') {
    clearTimeout(deadline)
    if (stall()) event?.preventDefault()
  }
}

document.addEventListener(START_EVENT, onSignal)
// The app may have signalled before this file arrived (it is `async`, so either may run first).
onSignal()
