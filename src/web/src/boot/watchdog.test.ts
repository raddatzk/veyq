// @vitest-environment happy-dom
// The start watchdog (ADR 0136 decision 2), loaded the way the page loads it: its module evaluated on its own, over the
// start screen of the real index.html, once per case (a fresh module and a fresh `#app`; the listeners a case's watchdog
// adds to `document` are removed after it, so no earlier case's watchdog answers a later case's signal).
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { START_EVENT, START_STATE_ATTRIBUTE, START_STEP_ATTRIBUTE, signalStart } from '../app/startSignals'
import { LOCALE_STORAGE_KEY } from '../i18n'
import INDEX_HTML from '../../index.html?raw'
import WATCHDOG_SOURCE from './watchdog.ts?raw'

const RELOAD_KEY = 'veyq:boot-reload-at'

let reload: ReturnType<typeof vi.fn>
let added: [string, EventListenerOrEventListenerObject][] = []

/** A Storage over a Map, or one whose every call throws (a private window, blocked site data). */
function storage(throwing = false): Storage {
  const map = new Map<string, string>()
  const fail = () => {
    throw new DOMException('denied', 'SecurityError')
  }
  return {
    getItem: (k: string) => (throwing ? fail() : (map.get(k) ?? null)),
    setItem: (k: string, v: string) => (throwing ? fail() : void map.set(k, v)),
    removeItem: (k: string) => void map.delete(k),
    clear: () => map.clear(),
    key: () => null,
    get length() {
      return map.size
    },
  } as Storage
}

/** The page's start: index.html's `#app`, the step the app marked, then the watchdog module evaluated. */
async function startPage(o: { step?: string; session?: Storage; local?: Storage; language?: string } = {}) {
  const parsed = new DOMParser().parseFromString(INDEX_HTML, 'text/html')
  document.body.replaceChildren(document.importNode(parsed.getElementById('app')!, true))
  const root = document.documentElement
  root.removeAttribute(START_STATE_ATTRIBUTE)
  if (o.step) root.setAttribute(START_STEP_ATTRIBUTE, o.step)
  else root.removeAttribute(START_STEP_ATTRIBUTE)
  vi.stubGlobal('sessionStorage', o.session ?? storage())
  vi.stubGlobal('localStorage', o.local ?? storage())
  vi.stubGlobal('navigator', { language: o.language ?? 'en-US' })
  vi.resetModules()
  await import('./watchdog')
}

const panel = () => document.getElementById('veyq-start-slow')!
const text = (id: string) => document.getElementById(id)!.textContent

beforeEach(() => {
  vi.useFakeTimers()
  reload = vi.fn()
  vi.stubGlobal('location', { reload })
  added = []
  const original = document.addEventListener.bind(document)
  vi.spyOn(document, 'addEventListener').mockImplementation((type: string, fn: EventListenerOrEventListenerObject, opts?: boolean | AddEventListenerOptions) => {
    added.push([type, fn])
    original(type, fn, opts)
  })
})

afterEach(() => {
  vi.restoreAllMocks()
  for (const [type, fn] of added) document.removeEventListener(type, fn)
  vi.clearAllTimers()
  vi.useRealTimers()
  vi.unstubAllGlobals()
  document.documentElement.removeAttribute(START_STATE_ATTRIBUTE)
  document.documentElement.removeAttribute(START_STEP_ATTRIBUTE)
})

describe('the start watchdog', () => {
  it('shows the start screen, and reloads once when no mount came within 15 s', async () => {
    const session = storage()
    await startPage({ step: 'status', session })
    expect(document.querySelector('.veyq-start')).not.toBeNull()
    vi.advanceTimersByTime(14_999)
    expect(reload).not.toHaveBeenCalled()
    vi.advanceTimersByTime(1)
    expect(reload).toHaveBeenCalledTimes(1)
    expect(Number(session.getItem(RELOAD_KEY))).toBeGreaterThan(0)
    expect(panel().hidden).toBe(true)
  })

  it('a second hang within five minutes does not reload: the slow panel names the step, its button reloads', async () => {
    const session = storage()
    session.setItem(RELOAD_KEY, String(Date.now() - 60_000))
    await startPage({ step: 'session', session })
    vi.advanceTimersByTime(15_000)
    expect(reload).not.toHaveBeenCalled()
    expect(panel().hidden).toBe(false)
    expect(document.getElementById('veyq-start-progress')!.hidden).toBe(true)
    expect(text('veyq-start-heading')).toBe('Taking longer than usual')
    expect(text('veyq-start-step')).toBe('Still waiting for step: session')
    expect(text('veyq-start-reload-label')).toBe('Reload')
    document.getElementById('veyq-start-reload')!.click()
    expect(reload).toHaveBeenCalledTimes(1)
  })

  it('reloads again once the five minutes are over', async () => {
    const session = storage()
    session.setItem(RELOAD_KEY, String(Date.now() - 5 * 60_000 - 1))
    await startPage({ step: 'vault', session })
    vi.advanceTimersByTime(15_000)
    expect(reload).toHaveBeenCalledTimes(1)
    expect(panel().hidden).toBe(true)
  })

  it('does nothing when the app mounts before 15 s — also when the mount came before the watchdog loaded', async () => {
    await startPage({ step: 'route' })
    vi.advanceTimersByTime(10_000)
    expect(signalStart('mounted')).toBe(false)
    vi.advanceTimersByTime(60_000)
    expect(reload).not.toHaveBeenCalled()
    expect(panel().hidden).toBe(true)

    document.documentElement.setAttribute(START_STATE_ATTRIBUTE, 'mounted')
    for (const [type, fn] of added) document.removeEventListener(type, fn)
    vi.resetModules()
    await import('./watchdog')
    vi.advanceTimersByTime(60_000)
    expect(reload).not.toHaveBeenCalled()
  })

  it('answers `failed` at once: a reload, which it reports by cancelling the signal', async () => {
    await startPage({ step: 'status' })
    expect(signalStart('failed')).toBe(true)
    expect(reload).toHaveBeenCalledTimes(1)
  })

  it('a `failed` within five minutes of the last reload shows the panel at once and lets the error through', async () => {
    const session = storage()
    session.setItem(RELOAD_KEY, String(Date.now()))
    await startPage({ step: 'status', session })
    expect(signalStart('failed')).toBe(false)
    expect(reload).not.toHaveBeenCalled()
    expect(panel().hidden).toBe(false)
    expect(text('veyq-start-step')).toBe('Still waiting for step: status')
  })

  it('a `failed` signalled before the watchdog loaded is answered when it loads', async () => {
    document.documentElement.setAttribute(START_STATE_ATTRIBUTE, 'failed')
    const parsed = new DOMParser().parseFromString(INDEX_HTML, 'text/html')
    document.body.replaceChildren(document.importNode(parsed.getElementById('app')!, true))
    vi.stubGlobal('sessionStorage', storage())
    vi.stubGlobal('localStorage', storage())
    vi.stubGlobal('navigator', { language: 'en-US' })
    vi.resetModules()
    await import('./watchdog')
    expect(reload).toHaveBeenCalledTimes(1)
  })

  it('after the mount, a `failed` (a chunk that no longer loads) still reloads once, and shows nothing', async () => {
    await startPage({ step: 'route' })
    signalStart('mounted')
    document.body.replaceChildren(document.createElement('main')) // the app replaced the start screen
    expect(signalStart('failed')).toBe(true)
    expect(reload).toHaveBeenCalledTimes(1)
    expect(signalStart('failed')).toBe(false)
    expect(reload).toHaveBeenCalledTimes(1)
  })

  it('where sessionStorage throws it never reloads by itself: only the panel and its button', async () => {
    await startPage({ step: 'status', session: storage(true) })
    vi.advanceTimersByTime(15_000)
    expect(reload).not.toHaveBeenCalled()
    expect(panel().hidden).toBe(false)
    expect(signalStart('failed')).toBe(false)
    expect(reload).not.toHaveBeenCalled()
    document.getElementById('veyq-start-reload')!.click()
    expect(reload).toHaveBeenCalledTimes(1)
  })

  it('names `load` when the app never marked a step (its code did not start)', async () => {
    const session = storage()
    session.setItem(RELOAD_KEY, String(Date.now()))
    await startPage({ session })
    vi.advanceTimersByTime(15_000)
    expect(text('veyq-start-step')).toBe('Still waiting for step: load')
  })

  it('speaks German from the stored choice, and from the OS where nothing is stored', async () => {
    const session = storage()
    session.setItem(RELOAD_KEY, String(Date.now()))
    const local = storage()
    local.setItem(LOCALE_STORAGE_KEY, 'de')
    await startPage({ step: 'vault', session, local, language: 'en-US' })
    vi.advanceTimersByTime(15_000)
    expect(text('veyq-start-heading')).toBe('Das dauert länger als üblich')
    expect(text('veyq-start-step')).toBe('Wartet noch auf Schritt: vault')
    expect(text('veyq-start-reload-label')).toBe('Neu laden')
    expect(panel().getAttribute('lang')).toBe('de')

    for (const [type, fn] of added) document.removeEventListener(type, fn)
    await startPage({ step: 'vault', session, language: 'de-AT' })
    vi.advanceTimersByTime(15_000)
    expect(text('veyq-start-heading')).toBe('Das dauert länger als üblich')

    for (const [type, fn] of added) document.removeEventListener(type, fn)
    const english = storage()
    english.setItem(LOCALE_STORAGE_KEY, 'en')
    await startPage({ step: 'vault', session, local: english, language: 'de-DE' })
    vi.advanceTimersByTime(15_000)
    expect(text('veyq-start-heading')).toBe('Taking longer than usual')
  })

  it('writes nothing but its one timestamp', async () => {
    const session = storage()
    const local = storage()
    await startPage({ step: 'status', session, local })
    vi.advanceTimersByTime(15_000)
    expect(session.length).toBe(1)
    expect(local.length).toBe(0)
  })
})

describe('the names the watchdog spells again', () => {
  it('equal the app’s: the step and state attributes, the event, the locale key', async () => {
    await startPage()
    const { WATCHDOG_NAMES } = await import('./watchdog')
    expect(WATCHDOG_NAMES.STEP_ATTRIBUTE).toBe(START_STEP_ATTRIBUTE)
    expect(WATCHDOG_NAMES.STATE_ATTRIBUTE).toBe(START_STATE_ATTRIBUTE)
    expect(WATCHDOG_NAMES.START_EVENT).toBe(START_EVENT)
    expect(WATCHDOG_NAMES.LOCALE_KEY).toBe(LOCALE_STORAGE_KEY)
    expect(WATCHDOG_NAMES.RELOAD_KEY).toBe(RELOAD_KEY)
    expect(WATCHDOG_NAMES.DEADLINE_MS).toBe(15_000)
    expect(WATCHDOG_NAMES.RELOAD_WINDOW_MS).toBe(5 * 60_000)
  })

  it('the ids it fills are the ones index.html carries', () => {
    const ids = [...WATCHDOG_SOURCE.matchAll(/'(veyq-start-[a-z-]+)'/g)].map((m) => m[1])
    expect(ids.length).toBeGreaterThanOrEqual(6)
    for (const id of ids) expect(INDEX_HTML).toContain(`id="${id}"`)
  })
})
