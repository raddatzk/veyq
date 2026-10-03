// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

vi.mock('./grantDeliveryWorker', () => ({ runGrantDeliveryWorker: vi.fn() }))

import { startGrantDeliveryPolling, stopGrantDeliveryPolling } from './grantDeliveryPolling'
import { runGrantDeliveryWorker } from './grantDeliveryWorker'

const KEY = new Uint8Array(32)
// The write key with the epoch it is — the worker seals the grant bound to it and states it (ADR 0072).
const HELD = { key: KEY, epoch: 3 }
const setVisibility = (state: 'visible' | 'hidden') =>
  Object.defineProperty(document, 'visibilityState', { value: state, configurable: true })

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(runGrantDeliveryWorker).mockResolvedValue(0)
  vi.useFakeTimers()
  setVisibility('visible')
})
afterEach(() => {
  stopGrantDeliveryPolling()
  vi.useRealTimers()
})

describe('grant-delivery background polling (cr-0079)', () => {
  it('delivers immediately on start, then once per interval', async () => {
    startGrantDeliveryPolling('s1', 'GROUP', () => HELD)
    expect(runGrantDeliveryWorker).toHaveBeenCalledTimes(1)
    expect(runGrantDeliveryWorker).toHaveBeenLastCalledWith({ scopeId: 's1', kind: 'GROUP', key: KEY, epoch: 3 })
    await vi.advanceTimersByTimeAsync(45_000)
    expect(runGrantDeliveryWorker).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(45_000)
    expect(runGrantDeliveryWorker).toHaveBeenCalledTimes(3)
  })

  it('stops polling after stop()', async () => {
    startGrantDeliveryPolling('s1', 'GROUP', () => HELD)
    stopGrantDeliveryPolling()
    await vi.advanceTimersByTimeAsync(90_000)
    expect(runGrantDeliveryWorker).toHaveBeenCalledTimes(1) // only the immediate one
  })

  it('skips interval ticks while the tab is hidden, and fires on becoming visible again', async () => {
    startGrantDeliveryPolling('s1', 'GROUP', () => HELD) // immediate → 1
    setVisibility('hidden')
    await vi.advanceTimersByTimeAsync(135_000) // three intervals, all skipped while hidden
    expect(runGrantDeliveryWorker).toHaveBeenCalledTimes(1)
    setVisibility('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    expect(runGrantDeliveryWorker).toHaveBeenCalledTimes(2)
  })

  it('no-ops a tick when the scope key is gone (scope closed)', async () => {
    startGrantDeliveryPolling('s1', 'GROUP', () => null)
    expect(runGrantDeliveryWorker).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(45_000)
    expect(runGrantDeliveryWorker).not.toHaveBeenCalled()
  })

  it('restarts cleanly for a new scope (old timer dropped)', async () => {
    startGrantDeliveryPolling('s1', 'GROUP', () => HELD)
    startGrantDeliveryPolling('s2', 'GROUP', () => HELD) // stops s1, immediate s2
    await vi.advanceTimersByTimeAsync(45_000)
    // 1 (s1 immediate) + 1 (s2 immediate) + 1 (one s2 interval) = 3; no lingering s1 timer.
    expect(runGrantDeliveryWorker).toHaveBeenCalledTimes(3)
    expect(runGrantDeliveryWorker).toHaveBeenLastCalledWith({ scopeId: 's2', kind: 'GROUP', key: KEY, epoch: 3 })
  })

  // A delivery lands on the server, so the fence cannot undo it — the in-flight guard is what keeps one tab from
  // running the worker twice at once. The control: once the held run settles, the next tick delivers again.
  it('starts no second delivery run while one is still running', async () => {
    let release!: () => void
    vi.mocked(runGrantDeliveryWorker).mockImplementationOnce(() => new Promise((r) => (release = () => r(0))))
    startGrantDeliveryPolling('s1', 'GROUP', () => HELD)
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(135_000)
    expect(runGrantDeliveryWorker).toHaveBeenCalledTimes(1)

    release()
    await vi.advanceTimersByTimeAsync(45_000)
    expect(runGrantDeliveryWorker).toHaveBeenCalledTimes(2)
  })
})
