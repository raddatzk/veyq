// The global upload queue (cr-files-application B2): batches of jobs, each a `run` an application supplies, run one
// at a time. A module singleton so uploads survive navigation; the tray renders `useUploadQueue()`.
//
// It knows nothing about WHAT a job uploads or WHERE it lands: the enqueuing application closes over its own target
// (scope, node, key, transport) inside `run`, and keeps on each item and batch whatever it later reads back (`extra`).
// Operator-blind is entirely the runner's concern — the queue never sees a byte, a key or a plaintext name it did
// not get from the caller for display.
//
// Rules, carried over unchanged from the queue Memories had (cr-0058, cr-0105, cr-0111, ADR 0073 §6): SEQUENTIAL
// (one job at a time, batches in enqueue order), fraction-based progress (no speed/ETA), one automatic retry on a
// transient failure, explicit retry on a hard failure — except a refusal no retry can help (`quota_exceeded`), which
// fails at once and offers none, as does a refusal the job itself declares final (`isFinal`). An abort (the item's signal, or an `AbortError`) settles `cancelled`, never `failed`.
import { reactive, computed } from 'vue'

// `preparing` = picked up, but still in the runner's pre-network phase (for a media file: derive poster/dimensions,
// read the metadata — a video's on-device frame decode can take a visible moment), so it gets its own state instead of
// masquerading as a stuck 0% `uploading`. It flips to `uploading` the instant the runner reports its first progress
// (a transport reports `onProgress(0)` right before the first chunk PUT).
export type UploadStatus = 'queued' | 'preparing' | 'uploading' | 'done' | 'failed' | 'cancelled'

/** What a job's `run` gets: report progress as a fraction 0..1 of the job, and honour the abort. */
export type UploadJobContext = {
  onProgress(fraction: number): void
  signal: AbortSignal
}

/**
 * One unit of upload work. `name` and `sizeBytes` are what the tray shows; `run` does the work and resolves with the
 * job's result (kept on the item as `result`), or throws — an `Error` whose message is the failure code. `extra`
 * holds the enqueuing application's own fields: they are copied onto the item, and the queue never reads them.
 */
export type UploadJob<R = unknown, X extends object = object> = {
  name: string
  sizeBytes: number
  run(ctx: UploadJobContext): Promise<R>
  extra?: X
  /**
   * Called once each time a run of the job settles `failed` (its automatic retry spent, or a refusal no retry helps) or
   * `cancelled` — never between the automatic retry's attempts. The job undoes here what a run left half-done and no
   * one will finish (Files: a file node it created that holds no content, inbox #384), so that a later retry by the
   * user starts clean. Awaited before the batch settles; a throw is swallowed (best effort, never a second failure).
   */
  abandon?(): Promise<void> | void
  /**
   * Whether `error` is a refusal no retry of THIS job can change (Files: a taken name, an illegal structure, a file a
   * peer replaced). The queue then fails the item at once and the tray offers no retry — a retry would only fail
   * again. Core's own [QUOTA_EXCEEDED] needs no declaration here.
   */
  isFinal?(error: string): boolean
}

/** A job in the queue, with its state. `X` is the application's own fields from the job's `extra`. */
export type UploadItem<R = unknown, X extends object = object> = X & {
  id: string
  name: string
  sizeBytes: number
  status: UploadStatus
  progress: number // 0..1
  error?: string
  /** Set with `error` when the job declared the refusal final ({@link UploadJob.isFinal}): no retry is offered. */
  final?: boolean
  attempts: number
  controller: AbortController
  /** What `run` resolved with once the item settled `done`. Undefined until then. */
  result?: R
}

export type UploadBatch<R = unknown, X extends object = object, B extends object = object> = B & {
  id: string
  label: string
  /** The caller's grouping key (which view an in-flight item belongs to), if it gave one. */
  key?: string
  files: UploadItem<R, X>[]
  seq: number
  finishedAt: number | null
  acknowledged: boolean
  onComplete?: (batch: UploadBatch<R, X, B>) => void
  /** Per-item settle hook (cr-0111): fired the moment ONE item finishes `done`, before the batch's `onComplete`. */
  onItemDone?: (item: UploadItem<R, X>) => void
}

export type EnqueueOptions<R, X extends object, B extends object> = {
  label: string
  key?: string
  onComplete?: (batch: UploadBatch<R, X, B>) => void
  onItemDone?: (item: UploadItem<R, X>) => void
  /** The application's own batch fields; copied onto the batch, never read by the queue. */
  extra?: B
}

type AnyItem = UploadItem<unknown, object>
type AnyBatch = UploadBatch<unknown, object, object>

const MAX_ATTEMPTS = 2 // one automatic retry on a transient failure

/**
 * The server refused the upload because the tenant or the link has no room left (ADR 0073 §6). No retry can help —
 * the room is still gone a moment later — so the item fails on its first attempt and no retry is offered. A
 * member's upload and a link visitor's both carry this code; each surface words it for its own reader.
 */
export const QUOTA_EXCEEDED = 'quota_exceeded'

/** Whether a failed item is one a retry could help — every failure but [QUOTA_EXCEEDED] and a refusal its job declared
 *  final ({@link UploadJob.isFinal}). */
export const isRetryable = (f: { error?: string; final?: boolean }): boolean => f.error !== QUOTA_EXCEEDED && !f.final

const batches = reactive<AnyBatch[]>([])
/** The job behind each item, by item id — kept off the reactive item so the queue's state stays plain data. */
const jobs = new Map<string, UploadJob<unknown, object>>()
let seq = 0
let running = false

function nextQueued(): AnyItem | null {
  for (const batch of batches.slice().sort((a, b) => a.seq - b.seq)) {
    const item = batch.files.find((f) => f.status === 'queued')
    if (item) return item
  }
  return null
}

function finalize(batch: AnyBatch): void {
  const settled = (f: AnyItem) => f.status === 'done' || f.status === 'failed' || f.status === 'cancelled'
  if (batch.finishedAt === null && batch.files.every(settled)) {
    batch.finishedAt = Date.now()
    batch.onComplete?.(batch)
  }
}

async function runOne(item: AnyItem): Promise<void> {
  const batch = batches.find((b) => b.files.includes(item))
  const job = jobs.get(item.id)
  if (!batch || !job) return
  item.status = 'preparing' // pre-network phase; flips to 'uploading' on the first progress
  item.progress = 0
  item.attempts += 1
  try {
    const result = await job.run({
      onProgress: (p) => {
        if (item.status === 'preparing') item.status = 'uploading' // first progress → the network phase began
        item.progress = p
      },
      signal: item.controller.signal,
    })
    item.result = result
    item.status = 'done'
    item.progress = 1
    batch.onItemDone?.(item) // cr-0111: settle THIS item's view now, not only at the batch's onComplete
  } catch (e) {
    const error = (e as Error)?.message ?? 'upload_failed'
    if (item.controller.signal.aborted || (e as { name?: string })?.name === 'AbortError') {
      item.status = 'cancelled'
    } else {
      const final = job.isFinal?.(error) === true
      if (item.attempts < MAX_ATTEMPTS && isRetryable({ error, final })) {
        item.status = 'queued' // one automatic retry on a transient failure
      } else {
        item.status = 'failed'
        item.error = error
        if (final) item.final = true
      }
    }
    if (item.status !== 'queued') await abandon(job)
  }
  finalize(batch)
}

async function abandon(job: UploadJob<unknown, object>): Promise<void> {
  try {
    await job.abandon?.()
  } catch {
    /* best effort: the job's own leftovers stay as they are */
  }
}

function scheduleRunner(): void {
  if (running) return
  running = true
  void (async () => {
    try {
      let item: AnyItem | null
      while ((item = nextQueued())) await runOne(item)
    } finally {
      running = false
      if (nextQueued()) scheduleRunner() // a retry re-queued after the loop ended
    }
  })()
}

/** Enqueue a batch of jobs. Returns the batch id, or null when no job was given. */
export function enqueue<R, X extends object = object, B extends object = object>(
  newJobs: UploadJob<R, X>[],
  opts: EnqueueOptions<R, X, B>,
): string | null {
  if (newJobs.length === 0) return null
  const files = newJobs.map((job) => {
    const item = {
      ...job.extra,
      id: crypto.randomUUID(),
      name: job.name,
      sizeBytes: job.sizeBytes,
      status: 'queued',
      progress: 0,
      attempts: 0,
      controller: new AbortController(),
    } as AnyItem
    jobs.set(item.id, job as UploadJob<unknown, object>)
    return item
  })
  const batch = {
    ...opts.extra,
    id: crypto.randomUUID(),
    label: opts.label || `${files.length}`,
    key: opts.key,
    files,
    seq: seq++,
    finishedAt: null,
    acknowledged: false,
    onComplete: opts.onComplete,
    onItemDone: opts.onItemDone,
  } as AnyBatch
  batches.push(batch)
  scheduleRunner()
  return batch.id
}

export function cancelFile(fileId: string): void {
  for (const batch of batches) {
    const item = batch.files.find((f) => f.id === fileId)
    if (!item) continue
    if (item.status === 'queued') {
      item.status = 'cancelled'
      const job = jobs.get(item.id)
      if (job) void abandon(job) // a re-queued retry may have left something of its earlier run
    }
    // in-flight (preparing or uploading): abort — the runner's abort checkpoints map it → cancelled
    else if (item.status === 'preparing' || item.status === 'uploading') item.controller.abort()
    finalize(batch)
    return
  }
}

export function cancelBatch(batchId: string): void {
  const batch = batches.find((b) => b.id === batchId)
  batch?.files.forEach((f) => cancelFile(f.id))
}

export function cancelAll(): void {
  batches.slice().forEach((b) => cancelBatch(b.id))
}

/** Retry a hard-failed item (resets its attempts). */
export function retryFile(fileId: string): void {
  for (const batch of batches) {
    const item = batch.files.find((f) => f.id === fileId)
    if (!item || item.status !== 'failed') continue
    item.status = 'queued'
    item.attempts = 0
    item.error = undefined
    item.final = undefined
    batch.finishedAt = null
    scheduleRunner()
    return
  }
}

/**
 * Remove a settled item from the queue entirely (cr-0102 uploader delete-own) — drops its view and lets the
 * caller's preview lifecycle release what it held. Empties the batch if it was the last item. Only a settled item
 * should be removed; an in-flight one must be cancelled first.
 */
export function removeFile(fileId: string): void {
  for (const batch of batches) {
    const idx = batch.files.findIndex((f) => f.id === fileId)
    if (idx === -1) continue
    batch.files.splice(idx, 1)
    jobs.delete(fileId)
    if (batch.files.length === 0) {
      const bi = batches.indexOf(batch)
      if (bi !== -1) batches.splice(bi, 1)
    }
    return
  }
}

/** Hide a finished batch from the tray. */
export function acknowledgeBatch(batchId: string): void {
  const batch = batches.find((b) => b.id === batchId)
  if (batch) batch.acknowledged = true
}

/** Test/teardown: cancel everything and empty the queue. */
export function clearUploads(): void {
  cancelAll()
  batches.length = 0
  jobs.clear()
  running = false
}

/** Reactive view for the UI: the un-acknowledged batches + a count of still-active items. */
export function useUploadQueue() {
  const visibleBatches = computed(() => batches.filter((b) => !b.acknowledged))
  const activeFileCount = computed(() =>
    batches.reduce(
      (n, b) => n + b.files.filter((f) => f.status === 'queued' || f.status === 'preparing' || f.status === 'uploading').length,
      0,
    ),
  )
  return { batches: visibleBatches, activeFileCount }
}
