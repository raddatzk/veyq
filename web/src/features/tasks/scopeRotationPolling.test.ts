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
  it('drains immediately on start, then once per interval', async () => {
    startScopeRotationPolling(() => true)
    expect(runScopeRotationWorker).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(45_000)
    expect(runScopeRotationWorker).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(45_000)
    expect(runScopeRotationWorker).toHaveBeenCalledTimes(3)
  })

  it('stops polling after stop()', async () => {
    startScopeRotationPolling(() => true)
    stopScopeRotationPolling()
    await vi.advanceTimersByTimeAsync(90_000)
    expect(runScopeRotationWorker).toHaveBeenCalledTimes(1) // only the immediate one
  })

  it('no-ops every tick while the vault is locked', async () => {
    startScopeRotationPolling(() => false)
    expect(runScopeRotationWorker).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(45_000)
    expect(runScopeRotationWorker).not.toHaveBeenCalled()
  })

  it('skips interval ticks while the tab is hidden, and fires on becoming visible again', async () => {
    startScopeRotationPolling(() => true) // immediate → 1
    setVisibility('hidden')
    await vi.advanceTimersByTimeAsync(135_000) // three intervals, all skipped while hidden
    expect(runScopeRotationWorker).toHaveBeenCalledTimes(1)
    setVisibility('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    expect(runScopeRotationWorker).toHaveBeenCalledTimes(2)
  })

  it('reads unlocked freshly each tick (locks mid-session → no more work)', async () => {
    let unlocked = true
    startScopeRotationPolling(() => unlocked) // immediate → 1
    expect(runScopeRotationWorker).toHaveBeenCalledTimes(1)
    unlocked = false // re-lock before clearActiveSpace stops the poll
    await vi.advanceTimersByTimeAsync(90_000)
    expect(runScopeRotationWorker).toHaveBeenCalledTimes(1)
  })

  // A rotation lands on the server, so the fence cannot undo it — the in-flight guard is what keeps one tab from
  // draining twice at once. The control: once the held drain settles, the next tick drains again.
  it('starts no second drain while one is still running', async () => {
    let release!: () => void
    vi.mocked(runScopeRotationWorker).mockImplementationOnce(() => new Promise((r) => (release = () => r(0))))
    startScopeRotationPolling(() => true)
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(135_000)
    expect(runScopeRotationWorker).toHaveBeenCalledTimes(1)

    release()
    await vi.advanceTimersByTimeAsync(45_000)
    expect(runScopeRotationWorker).toHaveBeenCalledTimes(2)
  })
})
