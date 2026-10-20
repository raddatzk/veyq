// @vitest-environment happy-dom
// The live poll loop (inbox #408). Each property is held against a tick the test keeps open or fails by hand, and each
// has its control on the same predicate (a visible tab DOES tick, a settled tick DOES allow the next).
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { createLivePoller, type LivePoller } from './livePoller'

const OPTIONS = { intervalMs: 1_000, maxBackoffMs: 8_000, jitter: 0, minGapMs: 500 }

const setVisibility = (state: 'visible' | 'hidden') => {
  Object.defineProperty(document, 'visibilityState', { value: state, configurable: true })
  document.dispatchEvent(new Event('visibilitychange'))
}

let poller: LivePoller

beforeEach(() => {
  vi.useFakeTimers()
  Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true })
  poller = createLivePoller(OPTIONS)
})
afterEach(() => {
  poller.stop()
  vi.useRealTimers()
})

describe('createLivePoller', () => {
  it('ticks after each interval, not at start', async () => {
    const tick = vi.fn(async () => {})
    poller.start(tick)
    await vi.advanceTimersByTimeAsync(999)
    expect(tick).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1)
    expect(tick).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1_000)
    expect(tick).toHaveBeenCalledTimes(2)
  })

  it('asks nothing while hidden, and ticks at once when the tab shows again', async () => {
    const tick = vi.fn(async () => {})
    poller.start(tick)
    await vi.advanceTimersByTimeAsync(1_000)
    expect(tick).toHaveBeenCalledTimes(1)
    setVisibility('hidden')
    await vi.advanceTimersByTimeAsync(60_000)
    expect(tick).toHaveBeenCalledTimes(1)
    setVisibility('visible')
    expect(tick).toHaveBeenCalledTimes(2) // at once, not at the next interval
    await vi.advanceTimersByTimeAsync(1_000)
    expect(tick).toHaveBeenCalledTimes(3) // and the loop runs on
  })

  it('does not start a tick while hidden even when the timer was already due', async () => {
    const tick = vi.fn(async () => {})
    poller.start(tick)
    await vi.advanceTimersByTimeAsync(900)
    setVisibility('hidden')
    await vi.advanceTimersByTimeAsync(5_000)
    expect(tick).not.toHaveBeenCalled()
  })

  it('ticks on window focus, but one tick for a visibility change and a focus together', async () => {
    const tick = vi.fn(async () => {})
    poller.start(tick)
    await vi.advanceTimersByTimeAsync(5_000)
    await vi.advanceTimersByTimeAsync(600) // idle past the min gap since the last tick
    const before = tick.mock.calls.length
    setVisibility('hidden')
    setVisibility('visible')
    window.dispatchEvent(new Event('focus'))
    expect(tick.mock.calls.length - before).toBe(1)
  })

  it('backs off on errors (doubling to the cap) and returns to the interval after a success', async () => {
    let fail = true
    const tick = vi.fn(async () => {
      if (fail) throw new Error('down')
    })
    poller.start(tick)
    await vi.advanceTimersByTimeAsync(1_000) // 1st (fails) -> next in 2 s
    expect(tick).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1_999)
    expect(tick).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1) // 2nd (fails) -> next in 4 s
    expect(tick).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(3_999)
    expect(tick).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(1) // 3rd (fails) -> next in 8 s (the cap)
    expect(tick).toHaveBeenCalledTimes(3)
    await vi.advanceTimersByTimeAsync(8_000) // 4th (fails) -> still 8 s, not 16
    expect(tick).toHaveBeenCalledTimes(4)
    fail = false
    await vi.advanceTimersByTimeAsync(8_000) // 5th succeeds -> back to 1 s
    expect(tick).toHaveBeenCalledTimes(5)
    await vi.advanceTimersByTimeAsync(1_000)
    expect(tick).toHaveBeenCalledTimes(6)
  })

  it('a skipped tick is neither an error nor a success: the plain interval again', async () => {
    const tick = vi.fn(async () => 'skip' as const)
    poller.start(tick)
    await vi.advanceTimersByTimeAsync(3_000)
    expect(tick).toHaveBeenCalledTimes(3)
  })

  it('never runs two ticks at once: a trigger during a slow tick is dropped', async () => {
    const releases: Array<() => void> = []
    const tick = vi.fn(() => new Promise<void>((resolve) => releases.push(resolve)))
    poller.start(tick)
    await vi.advanceTimersByTimeAsync(1_000)
    expect(tick).toHaveBeenCalledTimes(1)
    setVisibility('hidden')
    setVisibility('visible')
    window.dispatchEvent(new Event('focus'))
    await vi.advanceTimersByTimeAsync(10_000)
    expect(tick).toHaveBeenCalledTimes(1) // control below: it settles, the loop goes on
    releases[0]()
    await vi.advanceTimersByTimeAsync(1_000)
    expect(tick).toHaveBeenCalledTimes(2)
  })

  it('jitters the delay within the spread', async () => {
    poller.stop()
    poller = createLivePoller({ ...OPTIONS, jitter: 0.2, random: () => 0 }) // the lowest draw: 0.8 x
    const tick = vi.fn(async () => {})
    poller.start(tick)
    await vi.advanceTimersByTimeAsync(799)
    expect(tick).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1)
    expect(tick).toHaveBeenCalledTimes(1)
  })

  it('stop() ends the loop, detaches the listeners and fences an in-flight tick', async () => {
    const signals: AbortSignal[] = []
    let release: () => void = () => {}
    const tick = vi.fn((signal: AbortSignal) => {
      signals.push(signal)
      return new Promise<void>((resolve) => (release = resolve))
    })
    poller.start(tick)
    await vi.advanceTimersByTimeAsync(1_000)
    expect(signals[0].aborted).toBe(false)
    poller.stop()
    expect(signals[0].aborted).toBe(true)
    release()
    await vi.advanceTimersByTimeAsync(60_000)
    setVisibility('hidden')
    setVisibility('visible')
    window.dispatchEvent(new Event('focus'))
    expect(tick).toHaveBeenCalledTimes(1)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('a restart fences the earlier run', async () => {
    const first = vi.fn(async () => {})
    const second = vi.fn(async () => {})
    poller.start(first)
    poller.start(second)
    await vi.advanceTimersByTimeAsync(1_000)
    expect(first).not.toHaveBeenCalled()
    expect(second).toHaveBeenCalledTimes(1)
  })
})
