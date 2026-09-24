// @vitest-environment happy-dom
// The one foreground poll loop. The load-bearing cases are the two properties the five hand-written copies
// lacked — skip-not-queue while a tick is in flight, and the fence on stop/restart — each held against a tick
// the test keeps open by hand, and each with its control on the same predicate: a released tick DOES let the next
// one start, and an un-fenced tick DOES see a live signal.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

import { createVisibilityPoller, type VisibilityPoller } from './visibilityPoller'

const INTERVAL_MS = 1_000

const setVisibility = (state: 'visible' | 'hidden') =>
  Object.defineProperty(document, 'visibilityState', { value: state, configurable: true })

/** A tick whose runs stay open until the test releases them; records the signal each run was handed. */
const heldTick = () => {
  const releases: Array<() => void> = []
  const signals: AbortSignal[] = []
  const tick = vi.fn((signal: AbortSignal) => {
    signals.push(signal)
    return new Promise<void>((resolve) => releases.push(resolve))
  })
  return { tick, signals, release: (i: number) => releases[i]() }
}

let poller: VisibilityPoller

beforeEach(() => {
  vi.useFakeTimers()
  setVisibility('visible')
  poller = createVisibilityPoller(INTERVAL_MS)
})

afterEach(() => {
  poller.stop()
  vi.useRealTimers()
})

describe('createVisibilityPoller', () => {
  it('ticks once per interval, and not at start unless asked', async () => {
    const tick = vi.fn()
    poller.start(tick)
    await vi.advanceTimersByTimeAsync(0)
    expect(tick).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(INTERVAL_MS)
    expect(tick).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(INTERVAL_MS)
    expect(tick).toHaveBeenCalledTimes(2)
  })

  it('ticks at once on start when asked to', async () => {
    const tick = vi.fn()
    poller.start(tick, { immediate: true })
    expect(tick).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(INTERVAL_MS)
    expect(tick).toHaveBeenCalledTimes(2)
  })

  it('does not tick while hidden, and ticks once on the return to visible (E3)', async () => {
    const tick = vi.fn()
    poller.start(tick)
    setVisibility('hidden')
    document.dispatchEvent(new Event('visibilitychange')) // going hidden is not a return
    await vi.advanceTimersByTimeAsync(5 * INTERVAL_MS)
    expect(tick).not.toHaveBeenCalled()

    setVisibility('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    expect(tick).toHaveBeenCalledTimes(1)
  })

  it('skips a tick while one is in flight — skipped, not queued', async () => {
    const { tick, release } = heldTick()
    poller.start(tick, { immediate: true })
    expect(tick).toHaveBeenCalledTimes(1)

    document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(3 * INTERVAL_MS)
    expect(tick).toHaveBeenCalledTimes(1)

    // Released, the skipped ticks are not replayed …
    release(0)
    await vi.advanceTimersByTimeAsync(0)
    expect(tick).toHaveBeenCalledTimes(1)
    // … and the control: the next tick starts again, so the guard is about the held tick, not a loop that died.
    await vi.advanceTimersByTimeAsync(INTERVAL_MS)
    expect(tick).toHaveBeenCalledTimes(2)
  })

  it('a rejected tick ends the in-flight window like a resolved one', async () => {
    const tick = vi.fn(() => Promise.reject(new Error('offline')))
    poller.start(tick, { immediate: true })
    await vi.advanceTimersByTimeAsync(INTERVAL_MS)
    expect(tick).toHaveBeenCalledTimes(2)
  })

  it('fences an in-flight tick on stop, and ticks no more', async () => {
    const { tick, signals } = heldTick()
    poller.start(tick, { immediate: true })
    expect(signals[0].aborted).toBe(false) // the control: a live run's signal is live

    poller.stop()
    expect(signals[0].aborted).toBe(true)

    await vi.advanceTimersByTimeAsync(5 * INTERVAL_MS)
    document.dispatchEvent(new Event('visibilitychange'))
    expect(tick).toHaveBeenCalledTimes(1)
  })

  it('fences the old run on restart, and the new run does not wait for the old run’s tick', async () => {
    const first = heldTick()
    const second = vi.fn()
    poller.start(first.tick, { immediate: true })
    poller.start(second, { immediate: true })

    expect(first.signals[0].aborted).toBe(true)
    expect(second).toHaveBeenCalledTimes(1) // not blocked by the old run's still-open tick
    expect(second.mock.calls[0][0].aborted).toBe(false)

    // The old run's timer and listener are gone: only the new one drives ticks.
    await vi.advanceTimersByTimeAsync(INTERVAL_MS)
    document.dispatchEvent(new Event('visibilitychange'))
    expect(first.tick).toHaveBeenCalledTimes(1)
    expect(second).toHaveBeenCalledTimes(3)

    // The old tick settling late does not free or block the new run.
    first.release(0)
    await vi.advanceTimersByTimeAsync(INTERVAL_MS)
    expect(second).toHaveBeenCalledTimes(4)
  })

  it('can be stopped from inside its own tick', async () => {
    const tick = vi.fn(() => poller.stop())
    poller.start(tick, { immediate: true })
    await vi.advanceTimersByTimeAsync(5 * INTERVAL_MS)
    expect(tick).toHaveBeenCalledTimes(1)
  })

  it('keeps one loop per instance — stopping one leaves another running', async () => {
    const other = createVisibilityPoller(INTERVAL_MS)
    const tick = vi.fn()
    const otherTick = vi.fn()
    poller.start(tick)
    other.start(otherTick)
    other.stop()
    await vi.advanceTimersByTimeAsync(INTERVAL_MS)
    expect(tick).toHaveBeenCalledTimes(1)
    expect(otherTick).not.toHaveBeenCalled()
  })
})
