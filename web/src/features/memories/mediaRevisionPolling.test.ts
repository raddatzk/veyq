// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

import { startMediaRevisionPolling, stopMediaRevisionPolling } from './mediaRevisionPolling'

const setVisibility = (state: 'visible' | 'hidden') =>
  Object.defineProperty(document, 'visibilityState', { value: state, configurable: true })

beforeEach(() => {
  vi.useFakeTimers()
  vi.spyOn(Math, 'random').mockReturnValue(0.5) // the loop jitters its delay +-20 % (livePoller); 0.5 is the exact interval
  setVisibility('visible')
})
afterEach(() => {
  stopMediaRevisionPolling()
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('live media-update polling (uc.memory.live-updates, cr-0107)', () => {
  it('does NOT tick on start (the initial load already ran), then ticks once per interval', async () => {
    const tick = vi.fn(async () => {})
    startMediaRevisionPolling(tick)
    expect(tick).not.toHaveBeenCalled() // unlike grant-delivery, the open already did a full load
    await vi.advanceTimersByTimeAsync(20_000)
    expect(tick).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(20_000)
    expect(tick).toHaveBeenCalledTimes(2)
  })

  it('stops polling after stop()', async () => {
    const tick = vi.fn(async () => {})
    startMediaRevisionPolling(tick)
    stopMediaRevisionPolling()
    await vi.advanceTimersByTimeAsync(60_000)
    expect(tick).not.toHaveBeenCalled()
  })

  it('skips interval ticks while the tab is hidden, and fires once on becoming visible again (E3)', async () => {
    const tick = vi.fn(async () => {})
    startMediaRevisionPolling(tick)
    setVisibility('hidden')
    await vi.advanceTimersByTimeAsync(60_000) // three intervals, all skipped while hidden
    expect(tick).not.toHaveBeenCalled()
    setVisibility('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(0)
    expect(tick).toHaveBeenCalledTimes(1)
  })

  it('does not fire on a visibilitychange to hidden (E3: only a return to the foreground catches up)', async () => {
    const tick = vi.fn(async () => {})
    startMediaRevisionPolling(tick)
    setVisibility('hidden')
    document.dispatchEvent(new Event('visibilitychange'))
    expect(tick).not.toHaveBeenCalled()
  })

  it('restarts cleanly for a new Memory — the old timer + visibility listener are dropped', async () => {
    const first = vi.fn(async () => {})
    const second = vi.fn(async () => {})
    startMediaRevisionPolling(first)
    startMediaRevisionPolling(second) // stops the first
    await vi.advanceTimersByTimeAsync(20_000)
    expect(first).not.toHaveBeenCalled()
    expect(second).toHaveBeenCalledTimes(1)
    // the first Memory's visibility listener is gone too — a foreground event drives only the second
    await vi.advanceTimersByTimeAsync(2_000) // past the loop's minimum gap between a timer tick and a visibility tick
    setVisibility('hidden')
    setVisibility('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    expect(first).not.toHaveBeenCalled()
    expect(second).toHaveBeenCalledTimes(2)
  })
})
