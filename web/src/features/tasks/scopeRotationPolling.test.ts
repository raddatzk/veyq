// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

vi.mock('./scopeRotationWorker', () => ({ runScopeRotationWorker: vi.fn().mockResolvedValue(0) }))

import { startScopeRotationPolling, stopScopeRotationPolling } from './scopeRotationPolling'
import { runScopeRotationWorker } from './scopeRotationWorker'

const setVisibility = (state: 'visible' | 'hidden') =>
  Object.defineProperty(document, 'visibilityState', { value: state, configurable: true })

beforeEach(() => {
  vi.clearAllMocks()
  vi.useFakeTimers()
  setVisibility('visible')
})
afterEach(() => {
  stopScopeRotationPolling()
  vi.useRealTimers()
})

describe('scope-rotation background polling (cr-0092)', () => {
  it('drains immediately on start, then once per interval', () => {
    startScopeRotationPolling(() => true)
    expect(runScopeRotationWorker).toHaveBeenCalledTimes(1)
    vi.advanceTimersByTime(45_000)
    expect(runScopeRotationWorker).toHaveBeenCalledTimes(2)
    vi.advanceTimersByTime(45_000)
    expect(runScopeRotationWorker).toHaveBeenCalledTimes(3)
  })

  it('stops polling after stop()', () => {
    startScopeRotationPolling(() => true)
    stopScopeRotationPolling()
    vi.advanceTimersByTime(90_000)
    expect(runScopeRotationWorker).toHaveBeenCalledTimes(1) // only the immediate one
  })

  it('no-ops every tick while the vault is locked', () => {
    startScopeRotationPolling(() => false)
    expect(runScopeRotationWorker).not.toHaveBeenCalled()
    vi.advanceTimersByTime(45_000)
    expect(runScopeRotationWorker).not.toHaveBeenCalled()
  })

  it('skips interval ticks while the tab is hidden, and fires on becoming visible again', () => {
    startScopeRotationPolling(() => true) // immediate → 1
    setVisibility('hidden')
    vi.advanceTimersByTime(135_000) // three intervals, all skipped while hidden
    expect(runScopeRotationWorker).toHaveBeenCalledTimes(1)
    setVisibility('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    expect(runScopeRotationWorker).toHaveBeenCalledTimes(2)
  })

  it('reads unlocked freshly each tick (locks mid-session → no more work)', () => {
    let unlocked = true
    startScopeRotationPolling(() => unlocked) // immediate → 1
    expect(runScopeRotationWorker).toHaveBeenCalledTimes(1)
    unlocked = false // re-lock before clearActiveSpace stops the poll
    vi.advanceTimersByTime(90_000)
    expect(runScopeRotationWorker).toHaveBeenCalledTimes(1)
  })
})
