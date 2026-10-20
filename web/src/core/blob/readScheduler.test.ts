// The read scheduler (inbox #488) on a fake clock: batches, caps, lanes, retries, expiry and cancellation. Each case
// names the property it holds; the gallery-level proof (a Chapter's tiles through the real transport) is in
// `features/memories/components/MemoryDetail.test.ts`.
import { describe, it, expect, vi, afterEach } from 'vitest'
import {
  BACKOFF_BASE_MS,
  GETS_IN_FLIGHT,
  Limiter,
  MAX_TRIES,
  PRESIGN_BATCH_MAX,
  PRESIGN_REFRESH_MS,
  PRESIGNS_IN_FLIGHT,
  PresignExpired,
  createReadScheduler,
  isTransient,
  type PresignMany,
} from './readScheduler'

afterEach(() => vi.unstubAllGlobals())

/** A scheduler whose backoff sleeps are recorded and return at once, on a clock the test moves. */
function harness() {
  const sleeps: number[] = []
  let clock = 1_000_000
  const sched = createReadScheduler({
    sleep: async (ms) => void sleeps.push(ms),
    random: () => 1, // the top of each jitter window, so a sleep is exactly its ceiling
    now: () => clock,
  })
  return { sched, sleeps, advance: (ms: number) => (clock += ms) }
}

const urlsOf = (id: string) => [`https://store.test/${id}/0`]

/** A presign source that answers every id with its URLs, records each call's ids and how many run at once. */
function recordingPresign(hold = false) {
  const calls: string[][] = []
  const running = { now: 0, max: 0 }
  const releases: (() => void)[] = []
  const presignMany = vi.fn<PresignMany>(async (ids) => {
    calls.push(ids)
    running.now += 1
    running.max = Math.max(running.max, running.now)
    if (hold) await new Promise<void>((resolve) => releases.push(resolve))
    running.now -= 1
    return ids.map(urlsOf)
  })
  return { presignMany, calls, running, releases }
}

/** Let every queued microtask and resolved promise run. */
const settle = () => new Promise<void>((resolve) => setTimeout(resolve, 0))

describe('presigns are batched by the contract bound', () => {
  it('600 ids asked at once leave as 256 + 256 + 88, each waiter getting its own URLs', async () => {
    const { sched } = harness()
    const { presignMany, calls } = recordingPresign()
    const presign = sched.batcher(presignMany, PRESIGN_BATCH_MAX)
    const ids = Array.from({ length: 600 }, (_, i) => `b${i}`)
    const got = await Promise.all(ids.map((id) => presign(id, { lane: 'bulk' })))
    expect(calls.map((c) => c.length)).toEqual([256, 256, 88])
    expect(got.map((p) => p.urls)).toEqual(ids.map(urlsOf))
    expect(PRESIGN_BATCH_MAX).toBe(256) // blob.openapi.yaml BlobDownloadsBatchRequest.items.maxItems
  })

  it('ids asked while every slot is busy still travel together once one frees', async () => {
    const { sched } = harness()
    const { presignMany, calls, releases } = recordingPresign(true)
    const presign = sched.batcher(presignMany, PRESIGN_BATCH_MAX)
    const first = [presign('a'), presign('b')] // one batch, held open
    await settle()
    const second = presign('c') // a second slot is free: it leaves alone
    await settle()
    expect(calls).toEqual([['a', 'b'], ['c']])
    const later = Array.from({ length: 300 }, (_, i) => presign(`l${i}`)) // both slots busy: these wait
    await settle()
    expect(calls).toHaveLength(2)
    releases.splice(0).forEach((r) => r())
    await settle()
    expect(calls[2]).toHaveLength(256) // composed when its slot came up, not one request per id
    for (let spins = 0; spins < 20 && !(calls.length === 4 && releases.length === 0); spins++) {
      releases.splice(0).forEach((r) => r())
      await settle()
    }
    expect(calls[3]).toHaveLength(44)
    await Promise.all([...first, second, ...later])
  })

  it('the same id asked twice before its batch leaves is presigned once', async () => {
    const { sched } = harness()
    const { presignMany, calls } = recordingPresign()
    const presign = sched.batcher(presignMany, PRESIGN_BATCH_MAX)
    const [x, y] = await Promise.all([presign('dup', { lane: 'bulk' }), presign('dup')])
    expect(calls).toEqual([['dup']])
    expect(x.urls).toEqual(y.urls)
  })
})

describe('in flight never exceeds the caps', () => {
  it(`presign POSTs: at most ${PRESIGNS_IN_FLIGHT} at once over 3,000 ids, and every id served`, async () => {
    const { sched } = harness()
    const { presignMany, running, releases, calls } = recordingPresign(true)
    const presign = sched.batcher(presignMany, PRESIGN_BATCH_MAX)
    const all = Promise.all(Array.from({ length: 3000 }, (_, i) => presign(`b${i}`, { lane: 'bulk' })))
    for (let spins = 0; spins < 100 && calls.reduce((n, c) => n + c.length, 0) < 3000; spins++) {
      await settle()
      expect(running.now).toBeLessThanOrEqual(PRESIGNS_IN_FLIGHT)
      releases.splice(0).forEach((r) => r())
    }
    releases.splice(0).forEach((r) => r())
    expect((await all).length).toBe(3000)
    expect(running.max).toBe(PRESIGNS_IN_FLIGHT)
    expect(calls).toHaveLength(Math.ceil(3000 / PRESIGN_BATCH_MAX))
  })

  it(`store GETs: at most ${GETS_IN_FLIGHT} at once over 500 chunks, and every chunk served`, async () => {
    const { sched } = harness()
    const running = { now: 0, max: 0 }
    const releases: (() => void)[] = []
    vi.stubGlobal('fetch', vi.fn(async () => {
      running.now += 1
      running.max = Math.max(running.max, running.now)
      await new Promise<void>((resolve) => releases.push(resolve))
      running.now -= 1
      return new Response(new Uint8Array([7]))
    }))
    const all = Promise.all(Array.from({ length: 500 }, (_, i) => sched.getChunk(`https://store.test/${i}`, { lane: 'bulk' })))
    for (let spins = 0; spins < 200 && (running.now > 0 || releases.length > 0 || spins === 0); spins++) {
      await settle()
      expect(running.now).toBeLessThanOrEqual(GETS_IN_FLIGHT)
      releases.splice(0).forEach((r) => r())
    }
    expect((await all).every((f) => f[0] === 7)).toBe(true)
    expect(running.max).toBe(GETS_IN_FLIGHT)
  })
})

describe('lanes: what the user opened goes before a grid', () => {
  it('a `now` task waiting behind queued `bulk` ones starts first; within a lane the order of asking holds', async () => {
    const limiter = new Limiter(1)
    const order: string[] = []
    let release!: () => void
    const blocker = limiter.run(() => new Promise<void>((resolve) => (release = resolve)))
    const task = (name: string) => () => (order.push(name), Promise.resolve())
    const queued = [
      limiter.run(task('bulk-1'), { lane: 'bulk' }),
      limiter.run(task('bulk-2'), { lane: 'bulk' }),
      limiter.run(task('now-1'), { lane: 'now' }),
    ]
    release()
    await Promise.all([blocker, ...queued])
    expect(order).toEqual(['now-1', 'bulk-1', 'bulk-2'])
  })

  it('a `now` presign joins the next batch ahead of waiting `bulk` ids', async () => {
    const { sched } = harness()
    const { presignMany, calls, releases } = recordingPresign(true)
    const presign = sched.batcher(presignMany, 2)
    const held = [presign('h1'), presign('h2'), presign('h3'), presign('h4')] // both slots busy
    await settle()
    const bulk = [presign('b1', { lane: 'bulk' }), presign('b2', { lane: 'bulk' })]
    const now = presign('n1')
    releases.splice(0).forEach((r) => r())
    await settle()
    expect(calls[2]).toEqual(['n1', 'b1'])
    for (let i = 0; i < 5; i++) {
      releases.splice(0).forEach((r) => r())
      await settle()
    }
    await Promise.all([...held, ...bulk, now])
  })
})

describe('a transient failure is retried with a bounded backoff', () => {
  it('a GET the browser refused twice is served on the third try, after two growing waits', async () => {
    const { sched, sleeps } = harness()
    const fetchStub = vi.fn()
      .mockRejectedValueOnce(new TypeError('Failed to fetch')) // net::ERR_INSUFFICIENT_RESOURCES surfaces like this
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValue(new Response(new Uint8Array([1])))
    vi.stubGlobal('fetch', fetchStub)
    expect(Array.from(await sched.getChunk('https://store.test/x'))).toEqual([1])
    expect(fetchStub).toHaveBeenCalledTimes(3)
    expect(sleeps).toEqual([BACKOFF_BASE_MS, 2 * BACKOFF_BASE_MS])
  })

  it(`gives up after ${MAX_TRIES} tries, and a 5xx counts as transient`, async () => {
    const { sched, sleeps } = harness()
    const fetchStub = vi.fn(async () => new Response(null, { status: 503 }))
    vi.stubGlobal('fetch', fetchStub)
    await expect(sched.getChunk('https://store.test/x')).rejects.toThrow('chunk_get_failed:503')
    expect(fetchStub).toHaveBeenCalledTimes(MAX_TRIES)
    expect(sleeps).toHaveLength(MAX_TRIES - 1)
  })

  it('a final answer (404) is not retried', async () => {
    const { sched, sleeps } = harness()
    const fetchStub = vi.fn(async () => new Response(null, { status: 404 }))
    vi.stubGlobal('fetch', fetchStub)
    await expect(sched.getChunk('https://store.test/x')).rejects.toThrow('chunk_get_failed:404')
    expect(fetchStub).toHaveBeenCalledTimes(1)
    expect(sleeps).toEqual([])
  })

  it('a presign batch the network dropped is sent again whole; a per-id error fails only that id', async () => {
    const { sched, sleeps } = harness()
    const fetchError = Object.assign(new Error('The request failed'), { name: 'FetchError', cause: new TypeError('Failed to fetch') })
    const presignMany = vi.fn<PresignMany>()
      .mockRejectedValueOnce(fetchError)
      .mockImplementation(async (ids) => ids.map((id) => (id === 'gone' ? new Error('upload_not_found') : urlsOf(id))))
    const presign = sched.batcher(presignMany, PRESIGN_BATCH_MAX)
    const ok = presign('a')
    const gone = presign('gone')
    expect((await ok).urls).toEqual(urlsOf('a'))
    await expect(gone).rejects.toThrow('upload_not_found')
    expect(presignMany.mock.calls).toEqual([[['a', 'gone']], [['a', 'gone']]])
    expect(sleeps).toEqual([BACKOFF_BASE_MS])
  })

  it('classifies: no answer, 5xx, 408 and 429 are transient; 4xx answers and a cancel are not', () => {
    const status = (s: number) => Object.assign(new Error('x'), { response: new Response(null, { status: s }) })
    expect([new TypeError('Failed to fetch'), status(500), status(408), status(429), new Error('chunk_get_failed:502')].map(isTransient))
      .toEqual([true, true, true, true, true])
    expect([status(400), status(403), status(404), new Error('chunk_get_failed:403'), new DOMException('x', 'AbortError'), new PresignExpired()].map(isTransient))
      .toEqual([false, false, false, false, false, false])
  })
})

describe('an expired presign is never sent', () => {
  it(`a URL older than ${PRESIGN_REFRESH_MS / 60_000} min when its slot comes up throws PresignExpired without a request`, async () => {
    const { sched, advance } = harness()
    const fetchStub = vi.fn(async () => new Response(new Uint8Array([1])))
    vi.stubGlobal('fetch', fetchStub)
    const issuedAt = 1_000_000
    advance(PRESIGN_REFRESH_MS + 1)
    await expect(sched.getChunk('https://store.test/x', { issuedAt })).rejects.toBeInstanceOf(PresignExpired)
    expect(fetchStub).not.toHaveBeenCalled()
  })
})

describe('a read nobody wants any more is dropped while it waits', () => {
  it('an aborted presign never joins a batch', async () => {
    const { sched } = harness()
    const { presignMany, calls } = recordingPresign()
    const presign = sched.batcher(presignMany, PRESIGN_BATCH_MAX)
    const gallery = new AbortController()
    const dropped = presign('left', { lane: 'bulk', signal: gallery.signal })
    const kept = presign('kept', { lane: 'bulk' })
    gallery.abort()
    await expect(dropped).rejects.toMatchObject({ name: 'AbortError' })
    await kept
    expect(calls).toEqual([['kept']])
  })

  it('an aborted GET still queued never reaches the store', async () => {
    const { sched } = harness()
    const releases: (() => void)[] = []
    const fetchStub = vi.fn(async () => {
      await new Promise<void>((resolve) => releases.push(resolve))
      return new Response(new Uint8Array([1]))
    })
    vi.stubGlobal('fetch', fetchStub)
    const busy = Array.from({ length: GETS_IN_FLIGHT }, (_, i) => sched.getChunk(`https://store.test/busy-${i}`))
    const gallery = new AbortController()
    const queued = sched.getChunk('https://store.test/queued', { lane: 'bulk', signal: gallery.signal })
    await settle()
    gallery.abort()
    await expect(queued).rejects.toMatchObject({ name: 'AbortError' })
    releases.splice(0).forEach((r) => r())
    await Promise.all(busy)
    expect(fetchStub).toHaveBeenCalledTimes(GETS_IN_FLIGHT)
  })
})
