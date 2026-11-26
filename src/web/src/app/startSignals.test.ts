// @vitest-environment happy-dom
// The app's half of ADR 0136: `boot()` marks the step it waits for (decision 3), the start signals `mounted` or `failed`,
// and a chunk that no longer loads is a failed start (decision 4). The watchdog's half is `boot/watchdog.test.ts`.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setupStatus } from '../test/models'

vi.mock('../features/account/instance/instance', () => ({ getSetupStatus: vi.fn() }))
vi.mock('./sessionRefresh', () => ({ refreshSession: vi.fn(), isSessionRefusal: vi.fn(async () => false) }))
vi.mock('../features/account/unlock/unlock', () => ({ restoreVaultFromCache: vi.fn() }))
vi.mock('./devAutoUnlock', () => ({ maybeDevAutoUnlock: vi.fn() }))

import { boot } from './boot'
import { getSetupStatus } from '../features/account/instance/instance'
import { refreshSession } from './sessionRefresh'
import { restoreVaultFromCache } from '../features/account/unlock/unlock'
import { START_EVENT, START_STATE_ATTRIBUTE, START_STEP_ATTRIBUTE, reloadOnChunkFailure, runStart } from './startSignals'

const step = () => document.documentElement.getAttribute(START_STEP_ATTRIBUTE)
const state = () => document.documentElement.getAttribute(START_STATE_ATTRIBUTE)

/** Record each signal as the watchdog would see it; [reloads] makes it answer as a watchdog that reloads. */
function watchSignals(reloads = false) {
  const seen: (string | null)[] = []
  const on = (e: Event) => {
    seen.push(state())
    if (reloads) e.preventDefault()
  }
  document.addEventListener(START_EVENT, on)
  return { seen, stop: () => document.removeEventListener(START_EVENT, on) }
}

beforeEach(() => {
  vi.clearAllMocks()
  document.documentElement.removeAttribute(START_STEP_ATTRIBUTE)
  document.documentElement.removeAttribute(START_STATE_ATTRIBUTE)
})
afterEach(() => vi.restoreAllMocks())

describe('boot() marks the step it waits for', () => {
  it('moves through status, session and vault, each marked before its await', async () => {
    const steps: (string | null)[] = []
    vi.mocked(getSetupStatus).mockImplementation(async () => {
      steps.push(step())
      return setupStatus({ setupRequired: false })
    })
    vi.mocked(refreshSession).mockImplementation(async () => {
      steps.push(step())
    })
    vi.mocked(restoreVaultFromCache).mockImplementation(async () => {
      steps.push(step())
      return false
    })
    await boot()
    expect(steps).toEqual(['status', 'session', 'vault'])
  })

  it('a hang in the setup status leaves `status` marked', async () => {
    vi.mocked(getSetupStatus).mockReturnValue(new Promise(() => {}))
    void boot()
    await Promise.resolve()
    expect(step()).toBe('status')
    expect(refreshSession).not.toHaveBeenCalled()
  })
})

describe('the start signals its outcome', () => {
  it('`mounted` once the start resolved', async () => {
    const { seen, stop } = watchSignals()
    await runStart(async () => {})
    stop()
    expect(seen).toEqual(['mounted'])
  })

  it('`failed` when boot() rejects — never a silent blank page', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    vi.mocked(getSetupStatus).mockResolvedValue(setupStatus({ setupRequired: false }))
    // A throw boot() does not catch (the refresh's rejection it does: a synchronous throw it does not).
    vi.mocked(refreshSession).mockImplementation(() => {
      throw new TypeError('unexpected')
    })
    const { seen, stop } = watchSignals()
    await runStart(async () => {
      await boot()
    })
    stop()
    expect(seen).toEqual(['failed'])
    expect(step()).toBe('session')
  })
})

describe('a chunk that no longer loads', () => {
  it('is a failed start, and its error is suppressed only when the watchdog reloads', () => {
    const target = new EventTarget()
    reloadOnChunkFailure(target as unknown as Window)
    const watchdog = watchSignals(true)
    const event = new Event('vite:preloadError', { cancelable: true })
    target.dispatchEvent(event)
    watchdog.stop()
    expect(watchdog.seen).toEqual(['failed'])
    expect(event.defaultPrevented).toBe(true)

    const noReload = watchSignals(false)
    const passed = new Event('vite:preloadError', { cancelable: true })
    target.dispatchEvent(passed)
    noReload.stop()
    expect(noReload.seen).toEqual(['failed'])
    expect(passed.defaultPrevented).toBe(false)
  })
})
