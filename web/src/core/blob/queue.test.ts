// The core upload queue (cr-files-application B2) over a fake job runner: the six states, the sequential order, the
// one automatic retry that `quota_exceeded` never gets, abort → `cancelled`, the `preparing` → `uploading` flip on the
// first progress, and the cancel/retry/remove/acknowledge/clear controls. No application is imported: what a job
// uploads is the runner's business, and Memories' own wiring is pinned in `features/memory/uploadQueue.test.ts`.
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
