// What every scenario of the profile driver records around the operation it profiles (cr-profile-where-the-time-goes):
// Chromium's CPU profile of the page's main thread and a performance trace of every thread (the Argon2id
// and listing workers are threads of their own, which only the trace sees), under the run's CPU throttle; the requests
// the page made to the instance's API in the window, for the server's statements per request; and the window's
// wall-clock edges, by which the driver cuts the server's JFR recording to the same moment.
//
// The files hold no heap: the trace's categories leave out every one that dumps memory or captures a frame (a heap
// snapshot or a screenshot of an opened space holds what was decrypted in it). They are still the files of a signed-in
// session — their URLs name the account's objects — so they stay in the run directory under `.run/profiles/`, which git
// ignores, and the driver checks each for the account's password and Secret Key before it accepts the run.
import { expect, type Browser, type BrowserContext, type CDPSession, type Page, type Worker as PwWorker } from '@playwright/test'
import { readdirSync, readFileSync, writeFileSync } from 'node:fs'

export const OUT = process.env.VEYQ_PROFILE_OUT ?? '/out'

/** What the driver hands one run (`profile-spec.json`): which run, under which throttle, the files' stem, and whether
 *  the main thread's CPU profile is taken (absent: it is). */
export interface Spec {
  scenario: string
  run: number
  throttle: number
  stem: string
  /**
   * `false` leaves CDP's `Profiler` out of the window (`profile_hot_paths.py --no-cpu-profile`, inbox #554): its 100 µs
   * sampling of the main thread is overhead a worker escapes, so a profiled total overstates a move off the main thread.
   * The trace still runs, and its `disabled-by-default-v8.cpu_profiler` category keeps V8's own sampler on every thread
   * (every 0.2 ms, measured), so "unprofiled" means without the main thread's 100 µs profiler, not without any sampling.
   */
  cpuProfile?: boolean
}

/** The tenant owner L1's bootstrap activated (`instance.json`, written by tests/e2e/tests/setup/first-run.setup.ts). */
export interface Instance {
  owner: { email: string; password: string; secretKey: string }
  tenantDomain: string
}

export const spec = (): Spec => JSON.parse(readFileSync(`${OUT}/profile-spec.json`, 'utf8')) as Spec
export const instance = (): Instance => JSON.parse(readFileSync(`${OUT}/instance.json`, 'utf8')) as Instance

// DevTools' Performance panel set, minus what holds content: no `disabled-by-default-memory-infra` (heap dumps), no
// `disabled-by-default-devtools.screenshot` (frames of the decrypted page), no sampling heap profiler. The trace's own
// memory counters (`UpdateCounters`: JS heap, nodes, listeners) come with `disabled-by-default-devtools.timeline`.
export const TRACE_CATEGORIES = [
  'devtools.timeline',
  'disabled-by-default-devtools.timeline',
  'disabled-by-default-devtools.timeline.frame',
  'disabled-by-default-devtools.timeline.stack',
  'disabled-by-default-v8.cpu_profiler',
  'v8.execute',
  'blink.user_timing',
  'loading',
  'latencyInfo',
  'toplevel',
]

/** One API request of the window, by path (no query: a query may carry a token), with Resource Timing's numbers. */
interface ApiRequest {
  path: string
  start: number
  duration: number
  transfer: number
}

/**
 * One request of the window to the object store, by origin and path: a presigned URL, told apart by its `X-Amz-` query,
 * which is dropped (it is the signature). The store is another origin without `Timing-Allow-Origin`, so Resource Timing
 * gives its start and duration, never its size: the wire's share of an upload is read from these intervals (#261). The
 * page's and its workers' alike: an upload's chunk PUTs are its frame worker's (inbox #220).
 */
interface StoreRequest {
  url: string
  start: number
  duration: number
}

export interface Window {
  scenario: string
  run: number
  throttle: number
  /** Whether the main thread's CPU profiler ran in the window (the spec's `cpuProfile`). */
  cpuProfile: boolean
  browserVersion: string
  /** Epoch milliseconds, for cutting the server's recording to the window. */
  startedAt: number
  endedAt: number
  /** The operation's time as the page's clock saw it, from the action that starts it to the state that ends it. */
  operationMs: number
  apiRequests: ApiRequest[]
  storeRequests: StoreRequest[]
  /**
   * Every request of the window that never got an answer (`requestfailed`), as `METHOD origin+path errorText` — a
   * `net::ERR_ABORTED` among them too. Recorded, not judged: `scripts/profile_hot_paths.py` (`request_failures`) decides
   * which of them refuse the run.
   */
  failedRequests: string[]
  /** Every answer of the window with a status of 400 or more, as `METHOD origin+path status` — judged by the driver too. */
  httpErrors: string[]
  /**
   * `Performance.getMetrics` right before the window opens and right after it closes (inbox #220): the cumulative
   * counters (`TaskDuration`, `ScriptDuration`, `LayoutDuration`, `RecalcStyleDuration`, in seconds) of the window are
   * `metrics` minus `metricsBefore`, so the sign-in before it no longer counts.
   */
  metricsBefore: Record<string, number>
  metrics: Record<string, number>
  /** The browser's resident memory around and in the window ({@link sampleMemory}, inbox #558); null where no `/proc`. */
  memory: MemoryReading | null
}

/** Chromium's processes by their `--type`: the page's renderer (its workers are its threads), the GPU process, the rest. */
type ProcessClass = 'renderer' | 'gpu' | 'browser' | 'other'

/**
 * The resident set (`VmRSS`, kB) of the browser's processes, summed per {@link ProcessClass} and over all: right before the
 * window (`before`), its largest value inside it and when (`peak`, window ms from t0), and every sample (`series`: window
 * ms, renderer kB, all kB). Read from `/proc` by the test's own process, which runs in the same container as the browser,
 * so the page is asked nothing and spends nothing on it. RSS is what the operating system holds for the process — a decoded
 * bitmap, which the JS heap counters never see, included; a page freed by the allocator but kept may stay in it, so the
 * peak is what to read, not the end.
 */
export interface MemoryReading {
  intervalMs: number
  samples: number
  before: Record<ProcessClass | 'all', number>
  peak: Record<ProcessClass | 'all', { kB: number; atMs: number }>
  series: [number, number, number][]
}

const MEMORY_INTERVAL_MS = 20
const CLASSES: ProcessClass[] = ['renderer', 'gpu', 'browser', 'other']

/** A process's class from its `/proc/<pid>/cmdline`: NUL-separated arguments, or — for a child the zygote forked, which
 *  rewrote its title — one string with the arguments separated by spaces. */
function processClass(cmdline: string): ProcessClass | null {
  const args = cmdline.split(/[\0 ]/)
  if (!/chrom|headless_shell/i.test(args[0] ?? '')) return null
  const type = args.find((a) => a.startsWith('--type='))?.slice('--type='.length)
  if (type === undefined) return 'browser'
  if (type === 'renderer') return 'renderer'
  if (type === 'gpu-process') return 'gpu'
  return 'other'
}

/** One reading of every browser process's `VmRSS` (kB) by class, or null where `/proc` cannot be read. */
function residentNow(): Record<ProcessClass | 'all', number> | null {
  let pids: string[]
  try {
    pids = readdirSync('/proc').filter((p) => /^\d+$/.test(p))
  } catch {
    return null
  }
  const out = { renderer: 0, gpu: 0, browser: 0, other: 0, all: 0 }
  for (const pid of pids) {
    try {
      const cls = processClass(readFileSync(`/proc/${pid}/cmdline`, 'utf8'))
      if (!cls) continue
      const kB = Number(/^VmRSS:\s+(\d+)\s+kB/m.exec(readFileSync(`/proc/${pid}/status`, 'utf8'))?.[1] ?? 0)
      out[cls] += kB
      out.all += kB
    } catch {
      // a process that ended between the listing and the read
    }
  }
  return out
}

/**
 * Starts sampling the browser's resident memory every {@link MEMORY_INTERVAL_MS}; the returned function stops it and
 * returns the reading with times relative to `t0` (epoch ms), or null where `/proc` cannot be read (not Linux).
 */
export function sampleMemory(): { start: (t0: number) => void; stop: () => MemoryReading | null } {
  const before = residentNow()
  let t0 = Date.now()
  const series: [number, number, number][] = []
  const peak = Object.fromEntries([...CLASSES, 'all'].map((c) => [c, { kB: 0, atMs: 0 }])) as MemoryReading['peak']
  let timer: NodeJS.Timeout | undefined
  const tick = () => {
    const now = residentNow()
    if (!now) return
    const at = Date.now() - t0
    series.push([at, now.renderer, now.all])
    for (const c of [...CLASSES, 'all'] as const) if (now[c] > peak[c].kB) peak[c] = { kB: now[c], atMs: at }
  }
  return {
    start(windowStart: number) {
      t0 = windowStart
      if (before) timer = setInterval(tick, MEMORY_INTERVAL_MS)
    },
    stop() {
      if (timer) clearInterval(timer)
      if (!before) return null
      tick()
      return { intervalMs: MEMORY_INTERVAL_MS, samples: series.length, before, peak, series }
    },
  }
}

/**
 * The two marks that tie the trace's clock to the window (inbox #220): `t0` and `t1` are these marks' `startTime`, and
 * the trace carries each as a `blink.user_timing` event at the same instant on its own clock. Fixed names, nothing of
 * the content. `scripts/profile_attribute.py` reads them by these names.
 */
export const WINDOW_MARKS = { start: 'veyq-profile-window-start', end: 'veyq-profile-window-end' } as const

/**
 * Runs `operation` (the action that starts what is profiled, and the wait for the state that ends it) under the
 * profiler, the trace and the throttle, and writes `<stem>.cpuprofile`, `<stem>.trace.json` and `<stem>.window.json`.
 * With the spec's `cpuProfile: false` the profiler is left out and no `.cpuprofile` is written; the trace and the throttle
 * are the same. Everything the operation needs typed is typed BEFORE this is called, so no keystroke is inside the window.
 */
export async function profiled(browser: Browser, page: Page, cdp: CDPSession, operation: () => Promise<void>): Promise<Window> {
  const { scenario, run, throttle, stem, cpuProfile = true } = spec()
  const failed: string[] = []
  const httpErrors: string[] = []
  page.on('requestfailed', (r) => {
    const url = new URL(r.url())
    failed.push(`${r.method()} ${url.origin}${url.pathname} ${r.failure()?.errorText ?? ''}`)
  })
  page.on('response', (r) => {
    if (r.status() < 400) return
    const url = new URL(r.url())
    httpErrors.push(`${r.request().method()} ${url.origin}${url.pathname} ${r.status()}`)
  })

  // A worker keeps its own Resource Timing, with the same 250-entry default as a document, and an init script never
  // reaches it: its buffer is raised as it appears (core's frame worker sends an upload's chunks, inbox #220).
  const raiseWorker = (w: PwWorker) => void w.evaluate(() => performance.setResourceTimingBufferSize(200_000)).catch(() => undefined)
  page.workers().forEach(raiseWorker)
  page.on('worker', raiseWorker)

  await cdp.send('Performance.enable')
  if (cpuProfile) {
    await cdp.send('Profiler.enable')
    // 100 µs: DevTools' own default; fine enough to see a function of a few milliseconds in three runs.
    await cdp.send('Profiler.setSamplingInterval', { interval: 100 })
  }
  // Whether the throttle reaches the workers too is read from the readings (a worker's time at 4 against 1), never assumed.
  await cdp.send('Emulation.setCPUThrottlingRate', { rate: throttle })
  await browser.startTracing(page, { path: `${OUT}/${stem}.trace.json`, categories: TRACE_CATEGORIES, screenshots: false })
  if (cpuProfile) await cdp.send('Profiler.start')

  const metricsBefore = (await cdp.send('Performance.getMetrics')).metrics
  const resident = sampleMemory()
  const startedAt = Date.now()
  resident.start(startedAt)
  const t0 = await page.evaluate((name) => performance.mark(name).startTime, WINDOW_MARKS.start)
  await operation()
  const t1 = await page.evaluate((name) => performance.mark(name).startTime, WINDOW_MARKS.end)
  const endedAt = Date.now()
  const memory = resident.stop()
  const { metrics } = await cdp.send('Performance.getMetrics')

  const profile = cpuProfile ? (await cdp.send('Profiler.stop')).profile : null
  await browser.stopTracing()
  await cdp.send('Emulation.setCPUThrottlingRate', { rate: 1 })
  if (profile) writeFileSync(`${OUT}/${stem}.cpuprofile`, JSON.stringify(profile))

  const { apiRequests, storeRequests: pageStore, timeOrigin } = await page.evaluate(([from, to]) => {
    const inWindow = (performance.getEntriesByType('resource') as PerformanceResourceTiming[])
      .filter((e) => e.startTime >= from && e.startTime <= to)
    const api = inWindow.filter((e) => new URL(e.name).origin === location.origin && new URL(e.name).pathname.startsWith('/api/'))
    const store = inWindow.filter((e) => /[?&]X-Amz-/i.test(new URL(e.name).search))
    return {
      apiRequests: api.map((e) => ({ path: new URL(e.name).pathname, start: e.startTime - from, duration: e.duration, transfer: e.transferSize })),
      storeRequests: store.map((e) => ({ url: `${new URL(e.name).origin}${new URL(e.name).pathname}`, start: e.startTime - from, duration: e.duration })),
      timeOrigin: performance.timeOrigin,
    }
  }, [t0, t1] as const)
  // Each worker's store requests, moved onto the page's clock by the two time origins (a worker's clock starts with it).
  const workerStore = (await Promise.all(page.workers().map((w) => w.evaluate(([from, to, pageOrigin]) => {
    const shift = performance.timeOrigin - pageOrigin
    return (performance.getEntriesByType('resource') as PerformanceResourceTiming[])
      .filter((e) => /[?&]X-Amz-/i.test(new URL(e.name).search) && e.startTime + shift >= from && e.startTime + shift <= to)
      .map((e) => ({ url: `${new URL(e.name).origin}${new URL(e.name).pathname}`, start: e.startTime + shift - from, duration: e.duration }))
  }, [t0, t1, timeOrigin] as const).catch(() => [])))).flat()
  const storeRequests = [...pageStore, ...workerStore].sort((a, b) => a.start - b.start)

  const window: Window = {
    scenario, run, throttle, cpuProfile,
    browserVersion: browser.version(),
    startedAt, endedAt,
    operationMs: t1 - t0,
    apiRequests,
    storeRequests,
    failedRequests: failed,
    httpErrors,
    metricsBefore: Object.fromEntries(metricsBefore.map((m) => [m.name, m.value])),
    metrics: Object.fromEntries(metrics.map((m) => [m.name, m.value])),
    memory,
  }
  writeFileSync(`${OUT}/${stem}.window.json`, JSON.stringify(window, null, 2))
  return window
}

/**
 * Resource Timing's buffer raised in every document of the context before its own scripts: the default keeps 250
 * entries, and an upload of 50 photos or a Chapter of 1,000 thumbnails makes far more requests than that — the ones past
 * the buffer would be missing from the window without a trace of it.
 */
export const raiseResourceBuffer = (context: BrowserContext) =>
  context.addInitScript(() => performance.setResourceTimingBufferSize(200_000))

/** A new device signs in at the tenant's root with both secrets and waits for the space's toolbar (outside any window). */
export async function signIn(page: Page, { owner, tenantDomain }: Instance): Promise<void> {
  await page.goto(`https://${tenantDomain}/`)
  await page.getByLabel('Email', { exact: true }).fill(owner.email)
  await page.getByRole('textbox', { name: /^Password\b/ }).fill(owner.password)
  await page.locator('#unlock-secret-key').fill(owner.secretKey)
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await expect(page.getByRole('button', { name: 'New', exact: true })).toBeVisible({ timeout: 5 * 60_000 })
}

/**
 * A new Memory at the space's root with one Chapter in it, the Memory's Chapters tab left open. Labels are src/web's
 * own (en): the level's New menu, `memory.addChapter`, the node dialog's `node.nameLabel` and `common.create`.
 */
export async function memoryWithChapter(page: Page, memory: string, chapter: string): Promise<void> {
  await page.getByRole('button', { name: 'New', exact: true }).click()
  await page.getByRole('menuitem', { name: 'New Memory' }).click()
  const createMemory = page.getByRole('dialog', { name: 'Create a Memory' })
  await createMemory.getByLabel('Name').fill(memory)
  await createMemory.getByRole('button', { name: 'Create', exact: true }).click()
  await expect(createMemory).toBeHidden()
  await page.getByRole('button', { name: memory, exact: true }).click()
  await expect(page.getByRole('heading', { level: 1, name: memory })).toBeVisible()
  await page.getByRole('tab', { name: /^Chapters/ }).click()
  await page.getByRole('button', { name: 'Add chapter' }).click()
  const createChapter = page.getByRole('dialog', { name: 'Create a chapter' })
  await createChapter.getByLabel('Name').fill(chapter)
  await createChapter.getByRole('button', { name: 'Create', exact: true }).click()
  await expect(createChapter).toBeHidden()
  await expect(chapterCard(page, chapter)).toBeVisible()
}

/** A Chapter's card in its Memory's Chapters tab (`MemoryCard`'s main button carries the name among its text). */
export const chapterCard = (page: Page, chapter: string) => page.locator('.memory-card-main', { hasText: chapter })

/**
 * Every upload into the open gallery settled: the header counts `count` items and no upload tile is left (a member's
 * tile flips to the listed thumbnail once its file is done, `MemoryDetail.vue` `onFileDone`). A failed tile ends the
 * wait at once, naming the file. Polled every 250 ms in the page, so the wait costs the page no protocol round trip.
 */
export async function uploadsSettled(page: Page, count: number, timeout: number): Promise<void> {
  await page.waitForFunction((n) => {
    const failed = document.querySelector('.gallery-tile-optimistic.is-failed')
    if (failed) throw new Error(`an upload failed: ${failed.getAttribute('aria-label') ?? '?'}`)
    const header = document.querySelector('.veyq-node-header-subtitle')?.textContent?.replace(/[,.\s]/g, '')
    return header === `${n}items` && document.querySelectorAll('.gallery-tile-optimistic:not(.is-done)').length === 0
  }, count, { polling: 250, timeout })
}
