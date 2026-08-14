// @vitest-environment happy-dom
// uc.account.onboard-device (web) — the onboarded device's wait for the grant.
//
// The load-bearing case is the **stop condition itself**, because it is the one thing that differs from the
// sibling loop and is therefore the one a copy-paste gets wrong: a channel carrying only an `answer` is this
// device's OWN answer echoed back, and stopping on it would run the tail with nothing to open. Its control is
// on the same predicate: an envelope on the very next tick does stop it, so "it kept polling" is not satisfied
// by a loop that can never stop at all.
import { describe, it, expect, vi, beforeEach, afterEach, type Mock } from 'vitest'

vi.mock('./pairingChannel', () => ({ collectPairingChannel: vi.fn() }))

import { startGrantEnvelopePolling, stopGrantEnvelopePolling } from './grantEnvelopePolling'
import { collectPairingChannel } from './pairingChannel'

const HANDLE = 'aGFuZGxl'
const ANSWER = 'YW5zd2Vy'
const ENVELOPE = 'ZW52ZWxvcGU='

const collect = vi.mocked(collectPairingChannel)
const httpError = (status: number) => Object.assign(new Error('http'), { response: { status } })

const setVisibility = (state: 'visible' | 'hidden') =>
  Object.defineProperty(document, 'visibilityState', { value: state, configurable: true })

let onEnvelope: Mock<(envelope: string) => void>
let onGone: Mock<() => void>

beforeEach(() => {
  vi.clearAllMocks()
  vi.useFakeTimers()
  setVisibility('visible')
  onEnvelope = vi.fn()
  onGone = vi.fn()
  collect.mockResolvedValue({})
})

afterEach(() => {
  stopGrantEnvelopePolling()
  vi.useRealTimers()
})

const start = () => startGrantEnvelopePolling(HANDLE, onEnvelope, onGone)

describe('the onboarded device’s wait for the grant (ADR 0059, phase 3)', () => {
  it('reads once immediately, then once per interval while nothing is sealed yet', async () => {
    start()
    await vi.advanceTimersByTimeAsync(0)
    expect(collect).toHaveBeenCalledTimes(1)
    expect(collect).toHaveBeenLastCalledWith(HANDLE)

    await vi.advanceTimersByTimeAsync(2_000)
    expect(collect).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(2_000)
    expect(collect).toHaveBeenCalledTimes(3)
    expect(onEnvelope).not.toHaveBeenCalled()
  })

  // The stop condition is the ENVELOPE, not the answer — the inverse of the trusted device's. A channel that
  // carries only this device's own echoed answer is not a grant, and stopping there would leave nothing to open.
  it('does not stop on an answer — only an envelope ends it', async () => {
    collect.mockResolvedValue({ answer: ANSWER })
    start()
    await vi.advanceTimersByTimeAsync(0)
    expect(onEnvelope).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(10_000)
    expect(collect.mock.calls.length).toBeGreaterThan(1) // still going
    expect(onEnvelope).not.toHaveBeenCalled()

    // The control on the same predicate: it CAN stop, so the assertion above is about the answer and not about
    // a loop with no exit at all.
    collect.mockResolvedValue({ answer: ANSWER, envelope: ENVELOPE })
    await vi.advanceTimersByTimeAsync(2_000)
    expect(onEnvelope).toHaveBeenCalledExactlyOnceWith(ENVELOPE)
  })

  it('stops the instant the envelope is there, and never reads the channel again', async () => {
    start()
    await vi.advanceTimersByTimeAsync(0)
    expect(collect.mock.calls.length).toBeGreaterThanOrEqual(1) // the control: it really was polling

    collect.mockResolvedValue({ envelope: ENVELOPE })
    await vi.advanceTimersByTimeAsync(2_000)

    expect(onEnvelope).toHaveBeenCalledExactlyOnceWith(ENVELOPE)
    const atTheEnvelope = collect.mock.calls.length

    // The collect that returned the envelope spent the channel; a further read would only answer 404.
    await vi.advanceTimersByTimeAsync(600_000)
    setVisibility('hidden')
    setVisibility('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(0)

    expect(collect).toHaveBeenCalledTimes(atTheEnvelope)
    expect(onEnvelope).toHaveBeenCalledTimes(1)
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

  // E1/D9: expired, superseded and already-collected are one answer on purpose. So is the ten-minute ceiling,
  // which is why this loop keeps no deadline of its own.
  it('ends on a 404 — one terminal answer, no retry', async () => {
    collect.mockRejectedValue(httpError(404))
    start()
    await vi.advanceTimersByTimeAsync(0)

    expect(onGone).toHaveBeenCalledTimes(1)
    const atTheEnd = collect.mock.calls.length
    await vi.advanceTimersByTimeAsync(600_000)
    expect(collect).toHaveBeenCalledTimes(atTheEnd)
    expect(onEnvelope).not.toHaveBeenCalled()
  })

  it('keeps waiting through a failure that is not a 404', async () => {
    collect.mockRejectedValue(httpError(503))
    start()
    await vi.advanceTimersByTimeAsync(0)
    expect(onGone).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(2_000)
    expect(collect).toHaveBeenCalledTimes(2)

    collect.mockResolvedValue({ envelope: ENVELOPE })
    await vi.advanceTimersByTimeAsync(2_000)
    expect(onEnvelope).toHaveBeenCalledExactlyOnceWith(ENVELOPE)
  })

  it('drops its timer and its listener on stop', async () => {
    start()
    await vi.advanceTimersByTimeAsync(0)
    stopGrantEnvelopePolling()

    await vi.advanceTimersByTimeAsync(60_000)
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(0)

    expect(collect).toHaveBeenCalledTimes(1) // the immediate one, and nothing else
  })

  // The two loops keep separate module state on purpose: in the QR-sign-in direction they will run on the same
  // screen, and one shared timer would have the second start silently cancel the first.
  it('is independent of the sibling loop’s state', async () => {
    start()
    await vi.advanceTimersByTimeAsync(0)
    const { stopPairingAnswerPolling } = await import('./pairingAnswerPolling')
    stopPairingAnswerPolling()

    await vi.advanceTimersByTimeAsync(2_000)
    expect(collect).toHaveBeenCalledTimes(2) // still running
  })
})
