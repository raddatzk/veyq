// The applications' ending steps (uc.account.sign-out § The ending; ADR 0131 decision 5): every step runs, a failing one
// stops nobody, and the ending never waits past the budget - a sign-out is not held hostage by another origin's frame.
import { afterEach, describe, expect, it, vi } from 'vitest'
import { onSessionEnding, runSessionEndingSteps } from './sessionEndingSteps'

const removals: (() => void)[] = []
afterEach(() => {
  removals.splice(0).forEach((r) => r())
  vi.useRealTimers()
})
const register = (step: Parameters<typeof onSessionEnding>[0]) => removals.push(onSessionEnding(step))

describe('the session ending steps', () => {
  it('runs every registered step with who started the ending, and waits for them', async () => {
    const seen: boolean[] = []
    let finished = false
    register(async (ctx) => void seen.push(ctx.startedHere))
    register(async () => {
      await new Promise((r) => setTimeout(r, 5))
      finished = true
    })
    await runSessionEndingSteps({ startedHere: true })
    expect(seen).toEqual([true])
    expect(finished).toBe(true)
  })

  it('goes on past a step that throws', async () => {
    const ran = vi.fn(async () => {})
    register(() => Promise.reject(new Error('no')))
    register(() => {
      throw new Error('sync')
    })
    register(ran)
    await expect(runSessionEndingSteps({ startedHere: false })).resolves.toBeUndefined()
    expect(ran).toHaveBeenCalledOnce()
  })

  it('never waits past its budget for a step that hangs', async () => {
    vi.useFakeTimers()
    register(() => new Promise(() => {}))
    let done = false
    void runSessionEndingSteps({ startedHere: true }, 2000).then(() => (done = true))
    await vi.advanceTimersByTimeAsync(1999)
    expect(done).toBe(false)
    await vi.advanceTimersByTimeAsync(1)
    expect(done).toBe(true)
  })

  it('runs a removed step no more', async () => {
    const step = vi.fn(async () => {})
    onSessionEnding(step)()
    await runSessionEndingSteps({ startedHere: true })
    expect(step).not.toHaveBeenCalled()
  })
})
