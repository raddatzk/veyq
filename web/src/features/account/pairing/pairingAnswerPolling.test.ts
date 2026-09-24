// @vitest-environment happy-dom
// uc.account.onboard-device (web) — the trusted device's wait, and the one rule it must never break.
//
// The load-bearing case is `stops the instant it has an answer`. It is not a tidiness assertion: after the seal
// a collect from ANY caller returns the envelope and marks the row COLLECTED, so a loop that outlives the
// answer eats the grant the other device came for — and every visible thing on this side still looks right.
// Its control is on the same predicate: before the answer it DID poll, or "it stopped" is satisfied by a loop
// that never ran.
import { describe, it, expect, vi, beforeEach, afterEach, type Mock } from 'vitest'

vi.mock('./pairingChannel', () => ({ collectPairingChannel: vi.fn() }))

import { startPairingAnswerPolling, stopPairingAnswerPolling } from './pairingAnswerPolling'
import { collectPairingChannel, type PairingChannel } from './pairingChannel'

const HANDLE = 'aGFuZGxl'
const ANSWER = 'YW5zd2Vy'

const collect = vi.mocked(collectPairingChannel)
const httpError = (status: number) => Object.assign(new Error('http'), { response: { status } })

const setVisibility = (state: 'visible' | 'hidden') =>
  Object.defineProperty(document, 'visibilityState', { value: state, configurable: true })

let onAnswer: Mock<(answer: string) => void>
let onGone: Mock<() => void>

beforeEach(() => {
  vi.clearAllMocks()
  vi.useFakeTimers()
  setVisibility('visible')
  onAnswer = vi.fn()
  onGone = vi.fn()
  collect.mockResolvedValue({})
})

afterEach(() => {
  stopPairingAnswerPolling()
  vi.useRealTimers()
})

const start = () => startPairingAnswerPolling(HANDLE, onAnswer, onGone)

/** The next collect stays open until the test resolves it by hand — a collect slower than the interval. */
const holdNextCollect = () => {
  let release!: (channel: PairingChannel) => void
  collect.mockImplementationOnce(() => new Promise((resolve) => (release = resolve)))
  return (channel: PairingChannel) => release(channel)
}

describe('the trusted device’s wait for an answer (ADR 0059, D7)', () => {
  it('reads once immediately, then once per interval while the channel is still empty', async () => {
    start()
    await vi.advanceTimersByTimeAsync(0)
    expect(collect).toHaveBeenCalledTimes(1)
    expect(collect).toHaveBeenLastCalledWith(HANDLE)

    await vi.advanceTimersByTimeAsync(2_000)
    expect(collect).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(2_000)
    expect(collect).toHaveBeenCalledTimes(3)
    expect(onAnswer).not.toHaveBeenCalled()
  })

  it('stops the instant it has an answer, and never reads the channel again', async () => {
    start()
    await vi.advanceTimersByTimeAsync(0)
    const beforeTheAnswer = collect.mock.calls.length
    expect(beforeTheAnswer).toBeGreaterThanOrEqual(1) // the control: it really was polling

    collect.mockResolvedValue({ answer: ANSWER })
    await vi.advanceTimersByTimeAsync(2_000)

    expect(onAnswer).toHaveBeenCalledExactlyOnceWith(ANSWER)
    const atTheAnswer = collect.mock.calls.length

    // Everything after this point stands in for the seal and the minutes around it. Not one more read.
    await vi.advanceTimersByTimeAsync(600_000)
    setVisibility('hidden')
    setVisibility('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(0)

    expect(collect).toHaveBeenCalledTimes(atTheAnswer)
    expect(onAnswer).toHaveBeenCalledTimes(1)
  })

  it('skips ticks while the tab is hidden and catches up when it returns', async () => {
    start()
    await vi.advanceTimersByTimeAsync(0)
    setVisibility('hidden')
    await vi.advanceTimersByTimeAsync(10_000)
    expect(collect).toHaveBeenCalledTimes(1)

    setVisibility('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(0)
    expect(collect).toHaveBeenCalledTimes(2)
  })

  // E1/D9: every terminal state answers 404, deliberately indistinguishably — so this is one message and
  // never a retry, and the loop is over.
  it('ends on a 404 — one terminal answer, no retry', async () => {
    collect.mockRejectedValue(httpError(404))
    start()
    await vi.advanceTimersByTimeAsync(0)

    expect(onGone).toHaveBeenCalledTimes(1)
    const atTheEnd = collect.mock.calls.length
    await vi.advanceTimersByTimeAsync(60_000)
    expect(collect).toHaveBeenCalledTimes(atTheEnd)
    expect(onAnswer).not.toHaveBeenCalled()
  })

  // The control on the same predicate as the 404: a transport failure is NOT a dead channel. Without this,
  // "it ends on a failure" would be satisfied by a loop that gives up on anything at all.
  it('keeps waiting through a failure that is not a 404', async () => {
    collect.mockRejectedValue(httpError(503))
    start()
    await vi.advanceTimersByTimeAsync(0)
    expect(onGone).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(2_000)
    expect(collect).toHaveBeenCalledTimes(2)

    collect.mockResolvedValue({ answer: ANSWER })
    await vi.advanceTimersByTimeAsync(2_000)
    expect(onAnswer).toHaveBeenCalledExactlyOnceWith(ANSWER)
  })

  // The collect endpoint is shared with the onboarded device's loop, and so is its overlap: a tick during a held
  // collect is skipped, and nothing from a collect that outlived its run is applied.
  it('starts no second collect while one is still in flight', async () => {
    const release = holdNextCollect()
    start()
    await vi.advanceTimersByTimeAsync(0)
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(2_000)
    expect(collect).toHaveBeenCalledTimes(1)

    // The control: the held collect is a real one — released with an answer, it ends the wait exactly once.
    release({ answer: ANSWER })
    await vi.advanceTimersByTimeAsync(0)
    expect(onAnswer).toHaveBeenCalledExactlyOnceWith(ANSWER)
  })

  it('applies nothing from a collect that was still in flight when it was stopped', async () => {
    const release = holdNextCollect()
    start()
    await vi.advanceTimersByTimeAsync(0)
    expect(collect).toHaveBeenCalledTimes(1) // the control: the held collect really started

    stopPairingAnswerPolling()
    release({ answer: ANSWER })
    await vi.advanceTimersByTimeAsync(0)

    expect(onAnswer).not.toHaveBeenCalled()
  })

  it('drops its timer and its listener on stop', async () => {
    start()
    await vi.advanceTimersByTimeAsync(0)
    stopPairingAnswerPolling()

    await vi.advanceTimersByTimeAsync(60_000)
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(0)

    expect(collect).toHaveBeenCalledTimes(1) // the immediate one, and nothing else
  })
})
