// The two epoch refusals (ADR 0109 point 6) and the device floor, decoded and retried once in one place: a pending node
// is waited for and the write re-run (re-reading from the second attempt on), until the waits run out; a stale epoch is
// never waited for by `retryWhileRekeying` and re-run exactly once by `writeAtNodeEpoch`.
import { describe, it, expect, vi, afterEach } from 'vitest'
import { retryWhileRekeying, writeAtNodeEpoch, isRekeyPending, NodeRekeyPendingError } from './rekeyRetry'
import { NodeKeyBelowFloorError } from '../core/node/rekey/floor'

afterEach(() => vi.useRealTimers())

/** A thrown generated-client error carrying `409 { code }`, the shape `isCodedError` reads. */
const refusal = (code: string) => Object.assign(new Error(code), {
  response: new Response(JSON.stringify({ code }), { status: 409, headers: { 'Content-Type': 'application/json' } }),
})

const NO_WAIT = [0, 0, 0]

describe('retryWhileRekeying', () => {
  it('re-runs a write refused node_rekey_pending after the wait, re-reading from the second attempt, until it lands', async () => {
    vi.useFakeTimers()
    const attempts: number[] = []
    const run = retryWhileRekeying(async (n) => {
      attempts.push(n)
      if (n < 2) throw refusal('node_rekey_pending')
      return 'written'
    }, { delaysMs: [2_000, 5_000, 10_000] })
    await vi.advanceTimersByTimeAsync(1_999)
    expect(attempts, 'nothing re-sent before the first wait is over').toEqual([0])
    await vi.advanceTimersByTimeAsync(1 + 5_000)
    await expect(run).resolves.toBe('written')
    expect(attempts).toEqual([0, 1, 2])
  })

  it('gives up once the waits are used up and throws the coded refusal as it came', async () => {
    let calls = 0
    const err = await retryWhileRekeying(async () => {
      calls++
      throw refusal('node_rekey_pending')
    }, { delaysMs: NO_WAIT }).catch((e: unknown) => e)
    expect(calls, 'the first attempt and one per wait').toBe(4)
    expect(await isRekeyPending(err)).toBe(true)
  })

  it('never waits for stale_epoch: it is thrown at once, for the caller to re-read', async () => {
    let calls = 0
    const err = await retryWhileRekeying(async () => {
      calls++
      throw refusal('stale_epoch')
    }, { delaysMs: NO_WAIT }).catch((e: unknown) => e)
    expect(calls).toBe(1)
    expect((err as Error).message).toBe('stale_epoch')
  })

  it('waits for the device floor\'s own refusal as for node_rekey_pending', async () => {
    const attempts: number[] = []
    await expect(retryWhileRekeying(async (n) => {
      attempts.push(n)
      if (n === 0) throw new NodeKeyBelowFloorError('n', 1, 2)
      return 'written'
    }, { delaysMs: NO_WAIT })).resolves.toBe('written')
    expect(attempts).toEqual([0, 1])
  })
})

describe('retryWhileRekeying with a member\'s pull (ADR 0111 decision 11)', () => {
  it('pulls once on the first refusal and retries at once, spending no wait', async () => {
    const pull = vi.fn(async () => true)
    const attempts: number[] = []
    await expect(retryWhileRekeying(async (n) => {
      attempts.push(n)
      if (n < 2) throw refusal('node_rekey_pending')
      return 'written'
    }, { delaysMs: [0], pull })).resolves.toBe('written')
    expect(pull, 'once per write: the second refusal waits').toHaveBeenCalledTimes(1)
    expect(attempts).toEqual([0, 1, 2])
  })

  it('a pull that cannot run (false, or a throw) falls back to the waits, which still end in the refusal', async () => {
    for (const pull of [vi.fn(async () => false), vi.fn(async () => { throw new Error('offline') })]) {
      let calls = 0
      const err = await retryWhileRekeying(async () => {
        calls++
        throw refusal('node_rekey_pending')
      }, { delaysMs: [0, 0], pull }).catch((e: unknown) => e)
      expect(await isRekeyPending(err)).toBe(true)
      expect(calls, 'the first attempt and one per wait: the failed pull bought no attempt').toBe(3)
      expect(pull).toHaveBeenCalledTimes(1)
    }
  })

  it('reads a per-item node_rekey_pending (NodeRekeyPendingError) as the HTTP refusal', async () => {
    expect(await isRekeyPending(new NodeRekeyPendingError())).toBe(true)
    expect(await isRekeyPending(new Error('node_rekey_pending')), 'a bare message is not it').toBe(false)
  })
})

describe('writeAtNodeEpoch', () => {
  it('re-reads and re-runs a stale_epoch write once; a second stale is thrown', async () => {
    const rereads: boolean[] = []
    const err = await writeAtNodeEpoch(async (reread) => {
      rereads.push(reread)
      throw refusal('stale_epoch')
    }, { delaysMs: NO_WAIT }).catch((e: unknown) => e)
    expect(rereads).toEqual([false, true])
    expect((err as Error).message).toBe('stale_epoch')
  })

  it('re-reads after a pending wait and after a stale alike, and lands', async () => {
    const rereads: boolean[] = []
    await expect(writeAtNodeEpoch(async (reread) => {
      rereads.push(reread)
      if (rereads.length === 1) throw refusal('node_rekey_pending')
      if (rereads.length === 2) throw refusal('stale_epoch')
      return 'written'
    }, { delaysMs: NO_WAIT })).resolves.toBe('written')
    expect(rereads).toEqual([false, true, true])
  })

  it('throws any other refusal at once, without a re-read', async () => {
    const rereads: boolean[] = []
    const err = await writeAtNodeEpoch(async (reread) => {
      rereads.push(reread)
      throw refusal('slug_taken')
    }, { delaysMs: NO_WAIT }).catch((e: unknown) => e)
    expect(rereads).toEqual([false])
    expect((err as Error).message).toBe('slug_taken')
  })
})
