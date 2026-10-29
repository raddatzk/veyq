// The core upload queue (cr-files-application B2) over a fake job runner: the six states, the sequential order, the
// one automatic retry that `quota_exceeded` never gets, abort → `cancelled`, the `preparing` → `uploading` flip on the
// first progress, and the cancel/retry/remove/acknowledge/clear controls. No application is imported: what a job
// uploads is the runner's business, and Memories' own wiring is pinned in `features/memories/uploadQueue.test.ts`.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import {
  enqueue,
  cancelFile,
  cancelBatch,
  cancelAll,
  retryFile,
  removeFile,
  acknowledgeBatch,
  clearUploads,
  useUploadQueue,
  isRetryable,
  APPLICATION_OFF,
  QUOTA_EXCEEDED,
  type UploadJob,
  type UploadJobContext,
} from './queue'

type Run = (ctx: UploadJobContext) => Promise<string>
const job = (name: string, run: Run, extra?: { tag: string }): UploadJob<string, { tag: string }> => ({ name, sizeBytes: 3, run, extra })

/** A runner that stays in flight until aborted; `progress` reports one tick first (→ `uploading`). */
const untilAborted = (progress: boolean): Run => ({ onProgress, signal }) =>
  new Promise((_resolve, reject) => {
    if (progress) onProgress(0)
    const abort = () => reject(new DOMException('cancelled', 'AbortError'))
    if (signal.aborted) return abort()
    signal.addEventListener('abort', abort)
  })

beforeEach(() => {
  clearUploads()
})

describe('core upload queue — the runner', () => {
  it('runs a job, records its progress and its result, and settles it done', async () => {
    const seen: number[] = []
    const { batches } = useUploadQueue()
    const id = enqueue([job('a.bin', async ({ onProgress }) => {
      onProgress(0.5)
      seen.push(batches.value[0].files[0].progress)
      onProgress(1)
      return 'result-a'
    })], { label: '1 file', key: 'k' })
    const batch = batches.value[0]
    const item = batch.files[0]

    expect(batch.id).toBe(id)
    expect(batch.label).toBe('1 file')
    expect(batch.key).toBe('k')
    await vi.waitFor(() => expect(item.status).toBe('done'))
    expect(seen).toEqual([0.5])
    expect(item.progress).toBe(1)
    expect(item.result).toBe('result-a')
    expect(item.attempts).toBe(1)
  })

  it('returns null and enqueues nothing for no jobs; labels a batch by its count when no label is given', () => {
    const { batches } = useUploadQueue()
    expect(enqueue([], { label: 'x' })).toBeNull()
    expect(batches.value).toHaveLength(0)
    enqueue([job('a', untilAborted(false)), job('b', untilAborted(false))], { label: '' })
    expect(batches.value[0].label).toBe('2')
  })

  it("copies the job's and the batch's extra fields onto the item and the batch, untouched", () => {
    const { batches } = useUploadQueue()
    enqueue([job('a', untilAborted(false), { tag: 'mine' })], { label: '1', extra: { target: 'node-1' } })
    expect((batches.value[0] as unknown as { target: string }).target).toBe('node-1')
    expect((batches.value[0].files[0] as unknown as { tag: string }).tag).toBe('mine')
  })

  it('stays preparing until the first progress, then flips to uploading', async () => {
    let report!: (p: number) => void
    const { batches } = useUploadQueue()
    enqueue([job('a', ({ onProgress }) => {
      report = onProgress
      return new Promise(() => {})
    })], { label: '1' })
    const item = batches.value[0].files[0]

    await vi.waitFor(() => expect(item.status).toBe('preparing'))
    expect(item.progress).toBe(0)
    report(0)
    expect(item.status).toBe('uploading')
    report(0.25)
    expect(item.progress).toBe(0.25)
  })

  it('runs jobs one at a time, in enqueue order across batches', async () => {
    const order: string[] = []
    let inFlight = 0
    let maxInFlight = 0
    const tracked = (name: string): Run => async () => {
      inFlight += 1
      maxInFlight = Math.max(maxInFlight, inFlight)
      order.push(name)
      await new Promise((r) => setTimeout(r, 1))
      inFlight -= 1
      return name
    }
    const { batches } = useUploadQueue()
    enqueue([job('a', tracked('a')), job('b', tracked('b'))], { label: '2' })
    enqueue([job('c', tracked('c'))], { label: '1' })

    await vi.waitFor(() => expect(batches.value.every((b) => b.files.every((f) => f.status === 'done'))).toBe(true))
    expect(order).toEqual(['a', 'b', 'c'])
    expect(maxInFlight).toBe(1)
  })

  it('fires onItemDone per item as it settles, before the batch onComplete', async () => {
    const done: string[] = []
    let completeSawCount = -1
    const complete = vi.fn(() => { completeSawCount = done.length })
    enqueue([job('a', async () => 'ra'), job('b', async () => 'rb')], {
      label: '2',
      onItemDone: (item) => { done.push(item.result!) },
      onComplete: complete,
    })

    await vi.waitFor(() => expect(complete).toHaveBeenCalledOnce())
    expect(done).toEqual(['ra', 'rb'])
    expect(completeSawCount).toBe(2)
  })
})

describe('core upload queue — failure and retry', () => {
  it('retries once on a transient failure, then succeeds', async () => {
    let calls = 0
    const { batches } = useUploadQueue()
    enqueue([job('a', async () => {
      calls += 1
      if (calls === 1) throw new Error('chunk_put_failed:500')
      return 'ok'
    })], { label: '1' })
    const item = batches.value[0].files[0]

    await vi.waitFor(() => expect(item.status).toBe('done'))
    expect(calls).toBe(2)
    expect(item.attempts).toBe(2)
  })

  // uc.admin.tenant-storage-quota / ADR 0073 §6: no room left is not transient — a retry cannot help, so none is made.
  // The test above is this one's control: the same queue still retries every other failure once.
  it('fails a quota_exceeded job on its first attempt, with its reason, and makes no retry', async () => {
    const run = vi.fn<Run>().mockRejectedValue(new Error(QUOTA_EXCEEDED))
    const { batches } = useUploadQueue()
    enqueue([job('a', run)], { label: '1' })
    const item = batches.value[0].files[0]

    await vi.waitFor(() => expect(item.status).toBe('failed'))
    expect(item.error).toBe('quota_exceeded')
    expect(item.attempts).toBe(1)
    expect(run).toHaveBeenCalledOnce()
    expect(isRetryable(item)).toBe(false)
    expect(isRetryable({ error: 'chunk_put_failed:500' })).toBe(true)
  })

  // uc.platform.switch-application-off: an application switched off stays off a moment later too — no retry is made.
  it('fails a capability_disabled job on its first attempt and makes no retry', async () => {
    const run = vi.fn<Run>().mockRejectedValue(new Error(APPLICATION_OFF))
    const { batches } = useUploadQueue()
    enqueue([job('a', run)], { label: '1' })
    const item = batches.value[0].files[0]

    await vi.waitFor(() => expect(item.status).toBe('failed'))
    expect(item.error).toBe('capability_disabled')
    expect(run).toHaveBeenCalledOnce()
    expect(isRetryable(item)).toBe(false)
  })

  it('fails a refusal its job declares final on the first attempt and marks the item not retryable', async () => {
    const run = vi.fn<Run>().mockRejectedValue(new Error('stale_file'))
    const { batches } = useUploadQueue()
    enqueue([{ ...job('a', run), isFinal: (e) => e === 'stale_file' }], { label: '1' })
    const item = batches.value[0].files[0]

    await vi.waitFor(() => expect(item.status).toBe('failed'))
    expect(run).toHaveBeenCalledOnce()
    expect(isRetryable(item)).toBe(false)
    expect(item.final).toBe(true)
  })

  // Owner decision 2026-10-14 (inbox #426): one file's hard failure does not stop the rest of its batch — 36 good
  // photos are not held back by one refused one; each failure is the file's own, with its own retry.
  it('a hard failure of one job leaves the rest of its batch running to completion', async () => {
    const bad = vi.fn<Run>().mockRejectedValue(new Error('stale_file'))
    const good = vi.fn<Run>().mockResolvedValue('ok')
    const { batches } = useUploadQueue()
    enqueue([job('a', good), { ...job('b', bad), isFinal: (e) => e === 'stale_file' }, job('c', good), job('d', good)], { label: '4' })
    const [a, b, c, d] = batches.value[0].files

    await vi.waitFor(() => expect(d.status).toBe('done'))
    expect(b.status).toBe('failed')
    expect([a.status, c.status, d.status]).toEqual(['done', 'done', 'done'])
    expect(good).toHaveBeenCalledTimes(3)
  })

  it('marks a job failed after the retry is exhausted; retryFile re-runs it with fresh attempts', async () => {
    const run = vi.fn<Run>().mockRejectedValue(new Error('chunk_put_failed:500'))
    const complete = vi.fn()
    const { batches } = useUploadQueue()
    enqueue([job('a', run)], { label: '1', onComplete: complete })
    const item = batches.value[0].files[0]

    await vi.waitFor(() => expect(item.status).toBe('failed'))
    expect(run).toHaveBeenCalledTimes(2)
    expect(item.error).toContain('chunk_put_failed')
    expect(batches.value[0].finishedAt).not.toBeNull()
    expect(complete).toHaveBeenCalledOnce()

    run.mockResolvedValue('ok')
    retryFile(item.id)
    expect(item.error).toBeUndefined()
    expect(batches.value[0].finishedAt).toBeNull()
    await vi.waitFor(() => expect(item.status).toBe('done'))
    expect(item.attempts).toBe(1)
    expect(complete).toHaveBeenCalledTimes(2)
  })

  it('abandons a job once it settles failed or cancelled — never between the automatic retry\'s attempts (inbox #384)', async () => {
    const abandon = vi.fn()
    let attemptsAtAbandon = -1
    const run = vi.fn<Run>().mockRejectedValue(new Error('chunk_put_failed:500'))
    const { batches } = useUploadQueue()
    enqueue([{ ...job('a', run), abandon: () => { attemptsAtAbandon = run.mock.calls.length; abandon() } }], { label: '1' })
    const item = batches.value[0].files[0]
    await vi.waitFor(() => expect(item.status).toBe('failed'))
    expect(abandon).toHaveBeenCalledOnce()
    expect(attemptsAtAbandon).toBe(2) // after the retry, not after the first attempt

    // a cancelled job, in flight or still queued, is abandoned too; a throwing abandon fails nothing
    const thrown = vi.fn(() => { throw new Error('boom') })
    const queued = vi.fn()
    enqueue([{ ...job('b', untilAborted(true)), abandon: thrown }, { ...job('c', untilAborted(true)), abandon: queued }], { label: '2' })
    const [b, c] = batches.value[1].files
    await vi.waitFor(() => expect(b.status).toBe('uploading'))
    cancelFile(c.id)
    expect(queued).toHaveBeenCalledOnce()
    cancelFile(b.id)
    await vi.waitFor(() => expect(b.status).toBe('cancelled'))
    expect(thrown).toHaveBeenCalledOnce()
    expect(batches.value[1].finishedAt).not.toBeNull()
  })

  it('does not abandon a job that succeeds', async () => {
    const abandon = vi.fn()
    const run = vi.fn<Run>().mockRejectedValueOnce(new Error('chunk_put_failed:500')).mockResolvedValue('ok')
    const { batches } = useUploadQueue()
    enqueue([{ ...job('a', run), abandon }], { label: '1' })
    await vi.waitFor(() => expect(batches.value[0].files[0].status).toBe('done'))
    expect(abandon).not.toHaveBeenCalled()
  })

  it('retryFile leaves a job that is not failed alone', async () => {
    const run = vi.fn<Run>().mockResolvedValue('ok')
    const { batches } = useUploadQueue()
    enqueue([job('a', run)], { label: '1' })
    const item = batches.value[0].files[0]
    await vi.waitFor(() => expect(item.status).toBe('done'))

    retryFile(item.id)
    expect(item.status).toBe('done')
    expect(run).toHaveBeenCalledOnce()
  })
})

describe('core upload queue — cancel', () => {
  it('cancelling an uploading job aborts its signal and settles it cancelled, not failed', async () => {
    const { batches } = useUploadQueue()
    enqueue([job('a', untilAborted(true))], { label: '1' })
    const item = batches.value[0].files[0]

    await vi.waitFor(() => expect(item.status).toBe('uploading'))
    cancelFile(item.id)
    await vi.waitFor(() => expect(item.status).toBe('cancelled'))
    expect(item.controller.signal.aborted).toBe(true)
    expect(item.error).toBeUndefined()
    expect(item.attempts).toBe(1) // an abort is never retried
  })

  it('cancelling a still-preparing job (no progress yet) also aborts it → cancelled', async () => {
    const { batches } = useUploadQueue()
    enqueue([job('a', untilAborted(false))], { label: '1' })
    const item = batches.value[0].files[0]

    await vi.waitFor(() => expect(item.status).toBe('preparing'))
    cancelFile(item.id)
    await vi.waitFor(() => expect(item.status).toBe('cancelled'))
  })

  it('a runner that throws AbortError on its own settles cancelled', async () => {
    const { batches } = useUploadQueue()
    enqueue([job('a', async () => { throw new DOMException('gone', 'AbortError') })], { label: '1' })
    await vi.waitFor(() => expect(batches.value[0].files[0].status).toBe('cancelled'))
  })

  it('cancels a queued job without running it; cancelBatch and cancelAll reach every job', async () => {
    const later = vi.fn<Run>().mockResolvedValue('never')
    const { batches, activeFileCount } = useUploadQueue()
    enqueue([job('a', untilAborted(true)), job('b', later)], { label: '2' })
    enqueue([job('c', later)], { label: '1' })
    const [a, b] = batches.value[0].files
    const c = batches.value[1].files[0]
    await vi.waitFor(() => expect(a.status).toBe('uploading'))
    expect(activeFileCount.value).toBe(3)

    cancelFile(b.id) // queued → cancelled at once
    expect(b.status).toBe('cancelled')
    cancelBatch(batches.value[1].id) // the whole second batch, still queued
    expect(c.status).toBe('cancelled')
    cancelAll() // and the one in flight
    await vi.waitFor(() => expect(a.status).toBe('cancelled'))
    expect(later).not.toHaveBeenCalled()
    expect(activeFileCount.value).toBe(0)
    expect(batches.value.every((x) => x.finishedAt !== null)).toBe(true)
  })
})

/** A promise and the two hands that settle it. */
function deferred<T = void>() {
  let resolve!: (v: T) => void
  let reject!: (e: unknown) => void
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

/** A job whose `prepare` records its calls (by name) and settles when `gate` does, and whose `run` is given. */
function preparedJob(name: string, run: Run, calls: string[], gate: Promise<void> = Promise.resolve()):
UploadJob<string, { tag: string }> & { prepare: (ctx: { signal: AbortSignal }) => Promise<void> } {
  return {
    ...job(name, run),
    prepare: vi.fn(async ({ signal }: { signal: AbortSignal }) => {
      calls.push(name)
      await gate
      if (signal.aborted) throw new DOMException('cancelled', 'AbortError')
    }),
  }
}

// Inbox #220: the next job's on-device stage runs while the current job is on the wire, one job ahead. The queue only
// CALLS a job's optional `prepare`; the job keeps what it prepared (Memories' memo, `features/memories/uploadQueue.ts`).
describe('core upload queue — the next job prepared ahead (inbox #220)', () => {
  it("overlaps: B's prepare runs while A is still on the wire, gated on both sides", async () => {
    const calls: string[] = []
    const bPrepared = deferred()
    const aReported = deferred()
    const bPrepareStarted = deferred()
    // A reports its first progress, then resolves only once B's prepare has started; B's prepare resolves only once A
    // reported progress. A queue that prepared B only after A settled would leave A waiting forever.
    const a: Run = async ({ onProgress }) => {
      onProgress(0)
      aReported.resolve()
      await bPrepareStarted.promise
      return 'a'
    }
    const b = preparedJob('b', async () => 'b', calls, bPrepared.promise)
    const prepare = b.prepare
    b.prepare = async (ctx) => {
      bPrepareStarted.resolve()
      await aReported.promise
      bPrepared.resolve()
      return prepare(ctx)
    }
    const { batches } = useUploadQueue()
    enqueue([job('a', a), b], { label: '2' })
    const [ia, ib] = batches.value[0].files

    await vi.waitFor(() => expect(ib.status).toBe('done'))
    expect(ia.status).toBe('done')
    expect(calls).toEqual(['b'])
  })

  it("does not prepare the next job before the running one's first progress, and shows it preparing from then on", async () => {
    const calls: string[] = []
    let report!: (p: number) => void
    const { batches } = useUploadQueue()
    enqueue([
      job('a', ({ onProgress }) => { report = onProgress; return new Promise(() => {}) }),
      preparedJob('b', untilAborted(true), calls, new Promise(() => {})),
    ], { label: '2' })
    const [a, b] = batches.value[0].files

    await vi.waitFor(() => expect(a.status).toBe('preparing'))
    await Promise.resolve()
    expect(calls).toEqual([]) // A is still in its own on-device stage
    expect(b.status).toBe('queued')
    report(0)
    expect(calls).toEqual(['b'])
    expect(b.status).toBe('preparing') // never back to queued
    report(0.5) // later progress prepares nothing more
    expect(calls).toEqual(['b'])
  })

  it('prepares one job ahead: C waits for B to be on the wire', async () => {
    const calls: string[] = []
    const reports: Record<string, (p: number) => void> = {}
    const finish: Record<string, () => void> = {}
    const held = (name: string): Run => ({ onProgress }) => new Promise((resolve) => {
      reports[name] = onProgress
      finish[name] = () => resolve(name)
    })
    const { batches } = useUploadQueue()
    enqueue([preparedJob('a', held('a'), calls), preparedJob('b', held('b'), calls), preparedJob('c', held('c'), calls)], { label: '3' })
    const [, , c] = batches.value[0].files

    await vi.waitFor(() => expect(reports.a).toBeDefined())
    reports.a(0)
    expect(calls).toEqual(['b'])
    expect(c.status).toBe('queued')
    finish.a()
    await vi.waitFor(() => expect(reports.b).toBeDefined())
    expect(calls).toEqual(['b']) // B runs on what it prepared; C is not prepared before B is on the wire
    reports.b(0)
    expect(calls).toEqual(['b', 'c'])
  })

  it('still runs one job at a time, with prepares in the mix', async () => {
    const calls: string[] = []
    let inFlight = 0
    let maxInFlight = 0
    const tracked: Run = async ({ onProgress }) => {
      inFlight += 1
      maxInFlight = Math.max(maxInFlight, inFlight)
      onProgress(0)
      await new Promise((r) => setTimeout(r, 1))
      inFlight -= 1
      return 'x'
    }
    const { batches } = useUploadQueue()
    enqueue([preparedJob('a', tracked, calls), preparedJob('b', tracked, calls), preparedJob('c', tracked, calls)], { label: '3' })
    await vi.waitFor(() => expect(batches.value[0].files.every((f) => f.status === 'done')).toBe(true))
    expect(maxInFlight).toBe(1)
    expect(calls).toEqual(['b', 'c'])
  })

  it('cancelling a job prepared ahead settles it cancelled at once, aborts its signal and abandons it once', async () => {
    const calls: string[] = []
    const abandon = vi.fn()
    const later = vi.fn<Run>().mockResolvedValue('never')
    const { batches } = useUploadQueue()
    enqueue([job('a', untilAborted(true)), { ...preparedJob('b', later, calls, new Promise(() => {})), abandon }], { label: '2' })
    const [a, b] = batches.value[0].files
    await vi.waitFor(() => expect(b.status).toBe('preparing'))

    cancelFile(b.id)
    expect(b.status).toBe('cancelled') // at once: not left waiting for A to finish
    expect(b.controller.signal.aborted).toBe(true)
    expect(abandon).toHaveBeenCalledOnce()
    expect(a.status).toBe('uploading')
    cancelFile(a.id)
    await vi.waitFor(() => expect(a.status).toBe('cancelled'))
    expect(later).not.toHaveBeenCalled()
    expect(batches.value[0].finishedAt).not.toBeNull()
  })

  it('a job prepared ahead and left alone runs, and its prepare ran once in total', async () => {
    const calls: string[] = []
    const b = preparedJob('b', async () => 'b', calls)
    const { batches } = useUploadQueue()
    enqueue([job('a', async ({ onProgress }) => { onProgress(0); return 'a' }), b], { label: '2' })
    await vi.waitFor(() => expect(batches.value[0].files[1].status).toBe('done'))
    expect(b.prepare).toHaveBeenCalledOnce()
    expect(batches.value[0].files[1].controller.signal.aborted).toBe(false)
  })

  it("a failure of the running job leaves the prepared one waiting: A's retry first, then B", async () => {
    const calls: string[] = []
    const order: string[] = []
    let attempts = 0
    const a: Run = async ({ onProgress }) => {
      attempts += 1
      order.push(`a${attempts}`)
      onProgress(0)
      if (attempts === 1) throw new Error('chunk_put_failed:500')
      return 'a'
    }
    let preparedWhenBRan: string[] = []
    const { batches } = useUploadQueue()
    enqueue([
      job('a', a),
      preparedJob('b', async ({ onProgress }) => {
        order.push('b')
        preparedWhenBRan = [...calls]
        onProgress(0)
        return 'b'
      }, calls),
      preparedJob('c', async () => 'c', calls),
    ], { label: '3' })
    await vi.waitFor(() => expect(batches.value[0].files[2].status).toBe('done'))
    expect(order).toEqual(['a1', 'a2', 'b'])
    // A's retry reached the wire with B already ahead, so nothing more was prepared; C only once B was on the wire
    expect(preparedWhenBRan).toEqual(['b'])
    expect(calls).toEqual(['b', 'c'])
  })

  it('a hard failure of the running job leaves the prepared one to run next', async () => {
    const calls: string[] = []
    const b = preparedJob('b', async () => 'b', calls)
    const { batches } = useUploadQueue()
    enqueue([
      { ...job('a', async ({ onProgress }) => { onProgress(0); throw new Error('stale_file') }), isFinal: () => true },
      b,
    ], { label: '2' })
    const [a, ib] = batches.value[0].files
    await vi.waitFor(() => expect(ib.status).toBe('done'))
    expect(a.status).toBe('failed')
    expect(b.prepare).toHaveBeenCalledOnce()
  })

  it('skips a job without prepare for the next one that has it', async () => {
    const calls: string[] = []
    let report!: (p: number) => void
    const plain = vi.fn<Run>().mockResolvedValue('plain')
    const { batches } = useUploadQueue()
    enqueue([
      job('a', ({ onProgress }) => { report = onProgress; return new Promise(() => {}) }),
      job('plain', plain),
      preparedJob('c', async () => 'c', calls, new Promise(() => {})),
    ], { label: '3' })
    const [, p, c] = batches.value[0].files
    await vi.waitFor(() => expect(report).toBeDefined())
    report(0)
    expect(calls).toEqual(['c'])
    expect(p.status).toBe('queued') // the job with no prepare is never prepared ahead
    expect(c.status).toBe('preparing')
  })
})

describe('core upload queue — remove, acknowledge, clear', () => {
  it('removeFile drops a settled job and empties an exhausted batch', async () => {
    const { batches } = useUploadQueue()
    enqueue([job('a.png', async () => 'a'), job('b.png', async () => 'b')], { label: '2' })
    const [a, b] = batches.value[0].files
    await vi.waitFor(() => expect(b.status).toBe('done'))

    removeFile(a.id)
    expect(batches.value[0].files.map((f) => f.name)).toEqual(['b.png'])
    removeFile(b.id)
    expect(batches.value).toHaveLength(0)
  })

  it('acknowledgeBatch hides a batch from the view; clearUploads empties the queue and aborts what runs', async () => {
    const { batches } = useUploadQueue()
    enqueue([job('a', async () => 'a')], { label: '1' })
    enqueue([job('b', untilAborted(true))], { label: '1' })
    const first = batches.value[0]
    const running = batches.value[1].files[0]
    await vi.waitFor(() => expect(running.status).toBe('uploading'))

    acknowledgeBatch(first.id)
    expect(batches.value.map((x) => x.id)).not.toContain(first.id)
    expect(batches.value).toHaveLength(1)

    clearUploads()
    expect(batches.value).toHaveLength(0)
    expect(running.controller.signal.aborted).toBe(true)
  })
})
