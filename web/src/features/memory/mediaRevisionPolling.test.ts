// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

import { startMediaRevisionPolling, stopMediaRevisionPolling } from './mediaRevisionPolling'

const setVisibility = (state: 'visible' | 'hidden') =>
  Object.defineProperty(document, 'visibilityState', { value: state, configurable: true })

beforeEach(() => {
  vi.useFakeTimers()
  setVisibility('visible')
})
afterEach(() => {
  stopMediaRevisionPolling()
  vi.useRealTimers()
})

describe('live media-update polling (uc.memory.live-updates, cr-0107)', () => {
  it('does NOT tick on start (the initial load already ran), then ticks once per interval', () => {
    const tick = vi.fn()
    startMediaRevisionPolling(tick)
    expect(tick).not.toHaveBeenCalled() // unlike grant-delivery, the open already did a full load
    vi.advanceTimersByTime(20_000)
    expect(tick).toHaveBeenCalledTimes(1)
    vi.advanceTimersByTime(20_000)
    expect(tick).toHaveBeenCalledTimes(2)
  })

  it('stops polling after stop()', () => {
    const tick = vi.fn()
    startMediaRevisionPolling(tick)
    stopMediaRevisionPolling()
    vi.advanceTimersByTime(60_000)
    expect(tick).not.toHaveBeenCalled()
  })

  it('skips interval ticks while the tab is hidden, and fires once on becoming visible again (E3)', () => {
    const tick = vi.fn()
    startMediaRevisionPolling(tick)
    setVisibility('hidden')
    vi.advanceTimersByTime(60_000) // three intervals, all skipped while hidden
    expect(tick).not.toHaveBeenCalled()
    setVisibility('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    expect(tick).toHaveBeenCalledTimes(1)
  })

  it('does not fire on a visibilitychange to hidden (E3: only a return to the foreground catches up)', () => {
    const tick = vi.fn()
    startMediaRevisionPolling(tick)
    setVisibility('hidden')
    document.dispatchEvent(new Event('visibilitychange'))
    expect(tick).not.toHaveBeenCalled()
  })

  it('restarts cleanly for a new Memory — the old timer + visibility listener are dropped', () => {
    const first = vi.fn()
    const second = vi.fn()
    startMediaRevisionPolling(first)
    startMediaRevisionPolling(second) // stops the first
    vi.advanceTimersByTime(20_000)
    expect(first).not.toHaveBeenCalled()
    expect(second).toHaveBeenCalledTimes(1)
    // the first Memory's visibility listener is gone too — a foreground event drives only the second
    setVisibility('hidden')
    setVisibility('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    expect(first).not.toHaveBeenCalled()
    expect(second).toHaveBeenCalledTimes(2)
  })
})
