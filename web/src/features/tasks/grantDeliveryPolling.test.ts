// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

vi.mock('./grantDeliveryWorker', () => ({ runGrantDeliveryWorker: vi.fn().mockResolvedValue(0) }))

import { startGrantDeliveryPolling, stopGrantDeliveryPolling } from './grantDeliveryPolling'
import { runGrantDeliveryWorker } from './grantDeliveryWorker'

const KEY = new Uint8Array(32)
const setVisibility = (state: 'visible' | 'hidden') =>
  Object.defineProperty(document, 'visibilityState', { value: state, configurable: true })

beforeEach(() => {
  vi.clearAllMocks()
  vi.useFakeTimers()
  setVisibility('visible')
})
afterEach(() => {
  stopGrantDeliveryPolling()
  vi.useRealTimers()
})

describe('grant-delivery background polling (cr-0079)', () => {
  it('delivers immediately on start, then once per interval', () => {
    startGrantDeliveryPolling('s1', () => KEY)
    expect(runGrantDeliveryWorker).toHaveBeenCalledTimes(1)
    expect(runGrantDeliveryWorker).toHaveBeenLastCalledWith({ scopeId: 's1', key: KEY })
    vi.advanceTimersByTime(45_000)
    expect(runGrantDeliveryWorker).toHaveBeenCalledTimes(2)
    vi.advanceTimersByTime(45_000)
    expect(runGrantDeliveryWorker).toHaveBeenCalledTimes(3)
  })

  it('stops polling after stop()', () => {
    startGrantDeliveryPolling('s1', () => KEY)
    stopGrantDeliveryPolling()
    vi.advanceTimersByTime(90_000)
    expect(runGrantDeliveryWorker).toHaveBeenCalledTimes(1) // only the immediate one
  })

  it('skips interval ticks while the tab is hidden, and fires on becoming visible again', () => {
    startGrantDeliveryPolling('s1', () => KEY) // immediate → 1
    setVisibility('hidden')
    vi.advanceTimersByTime(135_000) // three intervals, all skipped while hidden
    expect(runGrantDeliveryWorker).toHaveBeenCalledTimes(1)
    setVisibility('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    expect(runGrantDeliveryWorker).toHaveBeenCalledTimes(2)
  })

  it('no-ops a tick when the scope key is gone (scope closed)', () => {
    startGrantDeliveryPolling('s1', () => null)
    expect(runGrantDeliveryWorker).not.toHaveBeenCalled()
    vi.advanceTimersByTime(45_000)
    expect(runGrantDeliveryWorker).not.toHaveBeenCalled()
  })

  it('restarts cleanly for a new scope (old timer dropped)', () => {
    startGrantDeliveryPolling('s1', () => KEY)
    startGrantDeliveryPolling('s2', () => KEY) // stops s1, immediate s2
    vi.advanceTimersByTime(45_000)
    // 1 (s1 immediate) + 1 (s2 immediate) + 1 (one s2 interval) = 3; no lingering s1 timer.
    expect(runGrantDeliveryWorker).toHaveBeenCalledTimes(3)
    expect(runGrantDeliveryWorker).toHaveBeenLastCalledWith({ scopeId: 's2', key: KEY })
  })
})
