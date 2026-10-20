// The read scheduler (inbox #488): every byte read of a blob — a presign POST and each chunk's store GET — passes
// through here, so the client never has more requests in flight than the limits below, whoever reads (the gallery's
// thumbnails, a share guest's, the lightbox, a video's chunk provider, a Depot download, an export).
//
// Why it exists: opening a Chapter of 3,000 photos used to send one presign POST per thumbnail and every GET at once.
// Chromium refused the excess with `net::ERR_INSUFFICIENT_RESOURCES`, nothing retried, and 1,630 of 3,000 tiles never
// loaded (the local volume measurement, 2026-10-18: 1,000 at once were all served, 3,000 were not). The server served
// everything it was sent; the fix is only fewer requests at once.
//
// Operator-blind as before: the same endpoints, the same blob ids, the same presigned GETs — only batched (many ids in
// one `blob-downloads-batch`, as the contract allows) and spaced out. Nothing here reads a key or a byte.
//
// Two lanes: `now` (what the user is looking at: a lightbox preview, a video's chunk, a download, a cover) always
// goes before `bulk` (a grid's thumbnails), so a gallery still loading 3,000 tiles never queues the photo just opened
// behind them. Within a lane the order is the order of asking — a grid asks top-down, so the tiles on screen when it
// opens come first. Scroll-aware priority belongs with the grid's virtualization (inbox #489), not here.
import { httpStatus } from '../../shared/httpError'

export type ReadLane = 'now' | 'bulk'

/** How a read is scheduled: its lane, and a signal that drops it while it still waits (a gallery navigated away). */
export interface ReadOpts {
  lane?: ReadLane
  signal?: AbortSignal
}

/** The most blob ids one `blob-downloads-batch` call carries: the contract's `BlobDownloadsBatchRequest.items`
 *  `maxItems` (`contracts/api/blob.openapi.yaml`) — a bigger batch is the server's `400`. */
export const PRESIGN_BATCH_MAX = 256

/** Presign POSTs in flight at once. One batch answers up to [PRESIGN_BATCH_MAX] tiles, so two keep the next batch on
 *  the wire while the GETs of the last one run; more would only presign URLs that wait behind the GETs. */
export const PRESIGNS_IN_FLIGHT = 2

/** Store GETs in flight at once, across every reader in the page. Above the six connections per host a browser opens
 *  over HTTP/1.1 (the rest wait in the browser's own queue) and enough streams to keep one HTTP/2 connection busy,
 *  yet two orders of magnitude below the burst Chromium refused (1,000 at once were served, 3,000 were not). */
export const GETS_IN_FLIGHT = 16

/** Tries per request on a transient failure (no answer, a `5xx`, `408`, `429`) before it counts as failed. */
export const MAX_TRIES = 4
/** The first backoff; each next one doubles, capped at [BACKOFF_CAP_MS], and is jittered into its upper half. */
export const BACKOFF_BASE_MS = 250
export const BACKOFF_CAP_MS = 4000

/** Re-presign a little before the backend's 600 s TTL (`application.properties`): a URL older than this is not used. */
export const PRESIGN_REFRESH_MS = 9 * 60 * 1000

/** A presigned URL that aged past [PRESIGN_REFRESH_MS] while its GET waited for a slot: re-presign, never send it. */
export class PresignExpired extends Error {
  constructor() {
    super('presign_expired')
  }
}

const abortError = () => new DOMException('Read cancelled', 'AbortError')

/** Whether a failure is worth another try: no HTTP answer at all (the browser refused or the network dropped it — a
 *  `TypeError` from `fetch`, `ERR_INSUFFICIENT_RESOURCES` among them, or the generated clients' `FetchError` wrapping
 *  one; each generated package has its own class, so it is known by name), a `5xx`, or a "not now" `408` / `429`. A
 *  cancelled read and every other answer are final. */
export function isTransient(e: unknown): boolean {
  if (!(e instanceof Error) || e.name === 'AbortError') return false
  const m = /^chunk_get_failed:(\d+)$/.exec(e.message)
  const status = m ? Number(m[1]) : httpStatus(e)
  if (status === 0) return e instanceof TypeError || e.name === 'FetchError'
  return status >= 500 || status === 408 || status === 429
}

/** A FIFO limiter with two lanes: at most `max` tasks run at once, a waiting `now` task starts before any `bulk` one. */
export class Limiter {
  private running = 0
  private readonly queues: Record<ReadLane, (() => void)[]> = { now: [], bulk: [] }
  constructor(readonly max: number) {}

  /** Tasks running now — for a test that asserts the cap. */
  get active(): number {
    return this.running
  }

  run<T>(task: () => Promise<T>, opts: ReadOpts = {}): Promise<T> {
    return new Promise<T>((resolve, reject) => {
      const { signal } = opts
      if (signal?.aborted) return reject(abortError())
      const queue = this.queues[opts.lane ?? 'now']
      // Aborted while it waits: out of the queue, so it never takes a slot. Once started it runs to its end.
      const onAbort = () => {
        queue.splice(queue.indexOf(admit), 1)
        reject(abortError())
      }
      const admit = () => {
        signal?.removeEventListener('abort', onAbort)
        this.running += 1
        task().then(resolve, reject).finally(() => this.release())
      }
      signal?.addEventListener('abort', onAbort, { once: true })
      queue.push(admit)
      this.pump()
    })
  }

  private release() {
    this.running -= 1
    this.pump()
  }

  private pump() {
    while (this.running < this.max) {
      const next = this.queues.now.shift() ?? this.queues.bulk.shift()
      if (!next) return
      next()
    }
  }
}

/** What a scheduler needs from its world — injected so a test runs it on a fake clock. */
export interface SchedulerDeps {
  sleep: (ms: number) => Promise<void>
  random: () => number
  now: () => number
}

const realDeps: SchedulerDeps = {
  sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
  random: () => Math.random(),
  now: () => Date.now(),
}

/** Presign `blobIds` in ONE request: per id, its GET URLs in chunk order or the error the server gave for it. */
export type PresignMany = (blobIds: string[]) => Promise<(string[] | Error)[]>

/** A presign as one reader asks it: the URLs in chunk order and when they were issued (for the expiry check). */
export interface Presigned {
  urls: string[]
  issuedAt: number
}

export function createReadScheduler(deps: SchedulerDeps = realDeps) {
  const presigns = new Limiter(PRESIGNS_IN_FLIGHT)
  const gets = new Limiter(GETS_IN_FLIGHT)

  /** Run `attempt` until it succeeds, fails for good, or [MAX_TRIES] are spent; between tries a jittered exponential
   *  backoff. A cancelled signal stops before the next try. */
  async function retry<T>(attempt: () => Promise<T>, signal?: AbortSignal): Promise<T> {
    for (let tries = 1; ; tries++) {
      try {
        return await attempt()
      } catch (e) {
        if (tries >= MAX_TRIES || !isTransient(e)) throw e
        const ceiling = Math.min(BACKOFF_CAP_MS, BACKOFF_BASE_MS * 2 ** (tries - 1))
        await deps.sleep(ceiling / 2 + deps.random() * (ceiling / 2))
        if (signal?.aborted) throw abortError()
      }
    }
  }

  /**
   * A presign source that coalesces: every id asked while no batch has been composed yet joins the next one, up to
   * `maxBatch` ids per request, at most [PRESIGNS_IN_FLIGHT] requests (across all sources) at once, each retried as a
   * whole on a transient failure. A batch is composed when its slot comes up, not when it is queued, so ids that
   * arrive while slots are busy still travel together. The same id asked twice before its batch leaves is presigned
   * once. A per-id error is the server's answer and is final.
   */
  function batcher(presignMany: PresignMany, maxBatch: number) {
    type Waiter = { resolve: (p: Presigned) => void; reject: (e: unknown) => void; signal?: AbortSignal; onAbort?: () => void }
    type Entry = { blobId: string; lane: ReadLane; waiters: Waiter[] }
    const pending: Record<ReadLane, Map<string, Entry>> = { now: new Map(), bulk: new Map() }
    let slotsAsked = 0 // limiter tasks queued or running that will still take ids

    const size = () => pending.now.size + pending.bulk.size

    function take(): Entry[] {
      const batch: Entry[] = []
      for (const lane of ['now', 'bulk'] as const) {
        for (const [id, entry] of pending[lane]) {
          if (batch.length >= maxBatch) return batch
          pending[lane].delete(id)
          batch.push(entry)
        }
      }
      return batch
    }

    function settle(entry: Entry, result: string[] | Error | undefined, error?: unknown) {
      for (const w of entry.waiters) {
        if (w.onAbort) w.signal?.removeEventListener('abort', w.onAbort)
        if (result instanceof Error || result === undefined) w.reject(result ?? error ?? new Error('store_unavailable'))
        else w.resolve({ urls: result, issuedAt: deps.now() })
      }
    }

    async function send(): Promise<void> {
      slotsAsked -= 1
      const batch = take()
      if (batch.length === 0) return
      const lane = batch.some((e) => e.lane === 'now') ? 'now' : 'bulk'
      if (size() > slotsAsked * maxBatch) ask(lane)
      try {
        const results = await retry(() => presignMany(batch.map((e) => e.blobId)))
        batch.forEach((entry, i) => settle(entry, results.length === batch.length ? results[i] : new Error('store_unavailable')))
      } catch (e) {
        for (const entry of batch) settle(entry, undefined, e)
      }
    }

    function ask(lane: ReadLane) {
      slotsAsked += 1
      // `send` is started on a later microtask (Limiter.run → task), so every id asked in the same synchronous run —
      // a grid's whole list — is pending by the time the batch is composed.
      void presigns.run(() => Promise.resolve().then(send), { lane })
    }

    return (blobId: string, opts: ReadOpts = {}): Promise<Presigned> =>
      new Promise<Presigned>((resolve, reject) => {
        const { signal } = opts
        if (signal?.aborted) return reject(abortError())
        const lane = opts.lane ?? 'now'
        let entry = pending.now.get(blobId) ?? pending.bulk.get(blobId)
        if (entry && lane === 'now' && entry.lane === 'bulk') {
          pending.bulk.delete(blobId) // asked again by someone looking at it: it moves to the front lane
          entry.lane = 'now'
          pending.now.set(blobId, entry)
        }
        if (!entry) {
          entry = { blobId, lane, waiters: [] }
          pending[lane].set(blobId, entry)
        }
        const waiter: Waiter = { resolve, reject, signal }
        if (signal) {
          const owner = entry
          waiter.onAbort = () => {
            owner.waiters = owner.waiters.filter((w) => w !== waiter)
            if (owner.waiters.length === 0) pending[owner.lane].delete(owner.blobId) // nobody wants it: never sent
            reject(abortError())
          }
          signal.addEventListener('abort', waiter.onAbort, { once: true })
        }
        entry.waiters.push(waiter)
        if (size() > slotsAsked * maxBatch) ask(lane) // more ids waiting than the slots already asked will take
      })
  }

  /**
   * GET one presigned chunk through the shared GET limiter, retried on a transient failure. A URL issued longer than
   * [PRESIGN_REFRESH_MS] ago when its slot comes up throws [PresignExpired] without being sent; a store answer other
   * than 2xx throws `chunk_get_failed:<status>` (a `403` is an expired URL to its caller).
   */
  function getChunk(url: string, opts: ReadOpts & { issuedAt?: number } = {}): Promise<Uint8Array> {
    return retry(
      () =>
        gets.run(async () => {
          if (opts.issuedAt !== undefined && deps.now() - opts.issuedAt > PRESIGN_REFRESH_MS) throw new PresignExpired()
          const res = await fetch(url, { method: 'GET' })
          if (!res.ok) throw new Error(`chunk_get_failed:${res.status}`)
          return new Uint8Array(await res.arrayBuffer())
        }, opts),
      opts.signal,
    )
  }

  return { presigns, gets, retry, batcher, getChunk, now: deps.now }
}

export type ReadScheduler = ReturnType<typeof createReadScheduler>

/** The page's one scheduler: the browser's limits are per page, so every reader shares these slots. */
export const readScheduler: ReadScheduler = createReadScheduler()
