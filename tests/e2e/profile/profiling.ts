// What every scenario of the profile driver records around the operation it profiles (cr-profile-where-the-time-goes,
// decision 2): Chromium's CPU profile of the page's main thread and a performance trace of every thread (the Argon2id
// and listing workers are threads of their own, which only the trace sees), under the run's CPU throttle; the requests
// the page made to the instance's API in the window, for the server's statements per request; and the window's
// wall-clock edges, by which the driver cuts the server's JFR recording to the same moment.
//
// The files hold no heap: the trace's categories leave out every one that dumps memory or captures a frame (a heap
// snapshot or a screenshot of an opened space holds what was decrypted in it). They are still the files of a signed-in
// session — their URLs name the account's objects — so they stay in the run directory under `.run/profiles/`, which git
// ignores, and the driver checks each for the account's password and Secret Key before it accepts the run.
import type { Browser, CDPSession, Page } from '@playwright/test'
import { readFileSync, writeFileSync } from 'node:fs'

export const OUT = process.env.VEYQ_PROFILE_OUT ?? '/out'

/** What the driver hands one run (`profile-spec.json`): which run, under which throttle, and the files' stem. */
export interface Spec {
  scenario: string
  run: number
  throttle: number
  stem: string
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

export interface Window {
  scenario: string
  run: number
  throttle: number
  browserVersion: string
  /** Epoch milliseconds, for cutting the server's recording to the window. */
  startedAt: number
  endedAt: number
  /** The operation's time as the page's clock saw it, from the action that starts it to the state that ends it. */
  operationMs: number
  apiRequests: ApiRequest[]
  failedRequests: string[]
  metrics: Record<string, number>
}

/**
 * Runs `operation` (the action that starts what is profiled, and the wait for the state that ends it) under the
 * profiler, the trace and the throttle, and writes `<stem>.cpuprofile`, `<stem>.trace.json` and `<stem>.window.json`.
 * Everything the operation needs typed is typed BEFORE this is called, so no keystroke is inside the window.
 */
export async function profiled(browser: Browser, page: Page, cdp: CDPSession, operation: () => Promise<void>): Promise<Window> {
  const { scenario, run, throttle, stem } = spec()
  const failed: string[] = []
  page.on('requestfailed', (r) => {
    const url = new URL(r.url())
    failed.push(`${r.method()} ${url.origin}${url.pathname} ${r.failure()?.errorText ?? ''}`)
  })

  await cdp.send('Performance.enable')
  await cdp.send('Profiler.enable')
  // 100 µs: DevTools' own default; fine enough to see a function of a few milliseconds in three runs.
  await cdp.send('Profiler.setSamplingInterval', { interval: 100 })
  // Whether the throttle reaches the workers too is read from the readings (a worker's time at 4 against 1), never assumed.
  await cdp.send('Emulation.setCPUThrottlingRate', { rate: throttle })
  await browser.startTracing(page, { path: `${OUT}/${stem}.trace.json`, categories: TRACE_CATEGORIES, screenshots: false })
  await cdp.send('Profiler.start')

  const startedAt = Date.now()
  const t0 = await page.evaluate(() => performance.now())
  await operation()
  const t1 = await page.evaluate(() => performance.now())
  const endedAt = Date.now()

  const { profile } = await cdp.send('Profiler.stop')
  await browser.stopTracing()
  await cdp.send('Emulation.setCPUThrottlingRate', { rate: 1 })
  writeFileSync(`${OUT}/${stem}.cpuprofile`, JSON.stringify(profile))

  const { metrics } = await cdp.send('Performance.getMetrics')
  const apiRequests = await page.evaluate(([from, to]) =>
    (performance.getEntriesByType('resource') as PerformanceResourceTiming[])
      .filter((e) => e.startTime >= from && e.startTime <= to && new URL(e.name).pathname.startsWith('/api/'))
      .map((e) => ({ path: new URL(e.name).pathname, start: e.startTime - from, duration: e.duration, transfer: e.transferSize })),
  [t0, t1] as const)

  const window: Window = {
    scenario, run, throttle,
    browserVersion: browser.version(),
    startedAt, endedAt,
    operationMs: t1 - t0,
    apiRequests,
    failedRequests: failed,
    metrics: Object.fromEntries(metrics.map((m) => [m.name, m.value])),
  }
  writeFileSync(`${OUT}/${stem}.window.json`, JSON.stringify(window, null, 2))
  return window
}
