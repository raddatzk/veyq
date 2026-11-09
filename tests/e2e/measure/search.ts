// The search at a household's volume (cr-search-at-volume): what the top bar's field costs the device that uses it, read
// along the CR's judging rule. TEMPORARY, see search.config.ts beside it.
//
// `scripts/measure_volume.py search` writes the spec (/out/search-spec.json) and reads the result (/out/search-result.json).
// Every run is a fresh device: sign in at the tenant's root (the active space; `activeHash` enters another one in the same
// document), wait until the page is quiet, then
//   1. **the first focus** - the click into the field builds the index: every other space's tree is fetched and opened.
//      Read: the long tasks from the focus until no space is loading any more (bound 2), the build's wall time, and the JS
//      heap after a forced collection before the focus and after the build (the index's memory, quantity 4);
//   2. **typing** - "K", "Ke", "Kev", Enter on the highlighted entry (the place chip the seeded Memories carry), Backspace
//      (the chip goes again). Per step: the long tasks from the keystroke until the list stopped changing, and the Event
//      Timing entries of the keystroke (bound 1);
//   3. **the second focus** - blur, then the field again with nothing changed. Read: every API response and its status, and
//      how many strings the page decoded (quantity 3).
// "Decoded" is `TextDecoder.prototype.decode`, counted by the init script: a node's name is opened as
// `decoder.decode(v1.openField(...))` (`core/node/tree.ts`), so a tree that is opened decodes once per node, and a `304`
// opens nothing. The first focus is the counter's own bite: it must count at least one decode per node of every space the
// search opened (the driver checks that, then judges the second focus by the same counter).
import { test, type Browser, type CDPSession, type Page } from '@playwright/test'
import { readFileSync, writeFileSync } from 'node:fs'

type Account = { email: string; password: string; secretKey: string }
type Spec = {
  label: string
  domain: string
  runs: number
  throttles: number[]
  account: Account
  /** The spaces the search must open itself (every one but the active space), by id. */
  otherScopes: string[]
  /** The spaces whose media listing the search reads (every one, the active space included: ADR 0125 decision 8a). */
  mediaScopes: string[]
  /** The active space while measuring, by id: the tenant's (where sign-in lands), or another entered by `activeHash`. */
  activeScope: string
  /** The fragment that enters the active space after sign-in; empty: stay in the tenant's. */
  activeHash: string
  /** A name the active space's root lists: the page is drawn. */
  rootText: string
  /** The typed sequence; `Enter` takes the highlighted entry, which must be the chip `chip`. */
  keys: string[]
  chip: string
  /** How long the list must not change for a step to count as settled (ms, the page's clock). */
  settleMs: number
  /** Diagnosis only: a CPU profile of each typing step, its heaviest frames with the bundle's code around them. */
  profile?: boolean
}

const OUT = process.env.VEYQ_MEASURE_OUT ?? '/out'
const spec = JSON.parse(readFileSync(`${OUT}/search-spec.json`, 'utf8')) as Spec
const origin = `https://${spec.domain}`

type Probe = {
  longtasks: [number, number][]
  loafs: { start: number; duration: number; blocking: number; scripts: { invoker: string; source: string; fn: string; duration: number }[] }[]
  events: { name: string; start: number; duration: number; processing: number; interactionId: number }[]
  decodes: number
  /** While set, what each decode returned (cut to 40 characters): which reads decoded, by what they opened. */
  keep: boolean
  decoded: string[]
  mutationAt: number
  loadingSeen: number | null
  loadingGone: number | null
}

// Runs in every document before its own scripts: the observers and the decode counter.
const INIT = () => {
  const p: Probe = { longtasks: [], loafs: [], events: [], decodes: 0, keep: false, decoded: [], mutationAt: 0, loadingSeen: null, loadingGone: null }
  ;(window as unknown as { __srch: Probe }).__srch = p
  const decode = TextDecoder.prototype.decode
  TextDecoder.prototype.decode = function (this: TextDecoder, ...args: Parameters<TextDecoder['decode']>) {
    p.decodes++
    const out = decode.apply(this, args)
    if (p.keep) p.decoded.push(out.slice(0, 40))
    return out
  }
  const observe = (type: string, on: (e: PerformanceEntry) => void, extra: Record<string, unknown> = {}) => {
    try {
      new PerformanceObserver((list) => { for (const e of list.getEntries()) on(e) }).observe({ type, buffered: true, ...extra })
    } catch { /* not supported here: the list stays empty, and says so by being empty */ }
  }
  observe('longtask', (e) => p.longtasks.push([e.startTime, e.duration]))
  observe('long-animation-frame', (e) => {
    const f = e as PerformanceEntry & { blockingDuration: number; scripts: { invoker: string; sourceURL: string; sourceFunctionName: string; duration: number }[] }
    p.loafs.push({
      start: f.startTime, duration: f.duration, blocking: f.blockingDuration,
      scripts: (f.scripts ?? []).map((s) => ({ invoker: s.invoker, source: s.sourceURL.split('/').at(-1) ?? '', fn: s.sourceFunctionName, duration: Math.round(s.duration) })),
    })
  })
  observe('event', (e) => {
    const t = e as PerformanceEventTiming
    p.events.push({ name: t.name, start: t.startTime, duration: t.duration, processing: t.processingEnd - t.processingStart, interactionId: t.interactionId ?? 0 })
  }, { durationThreshold: 16 })
  // The list's changes and the "Searching N spaces…" line: when it first showed and when it last went.
  const watch = () => {
    new MutationObserver(() => {
      const now = performance.now()
      p.mutationAt = now
      const loading = !!document.querySelector('.veyq-search__loading')
      if (loading && p.loadingSeen === null) p.loadingSeen = now
      if (!loading && p.loadingSeen !== null && (p.loadingGone === null || p.loadingGone < p.loadingSeen)) p.loadingGone = now
      if (loading) p.loadingGone = null
    }).observe(document.documentElement, { childList: true, subtree: true, characterData: true, attributes: true })
  }
  if (document.documentElement) watch()
  else document.addEventListener('DOMContentLoaded', watch)
}

const probe = (page: Page) => page.evaluate(() => {
  const p = (window as unknown as { __srch: Probe }).__srch
  return { ...p, now: performance.now() }
})
const now = (page: Page) => page.evaluate(() => performance.now())

type Tasks = { count: number; totalMs: number; maxMs: number; all: number[] }
function tasksIn(p: Probe, from: number, to: number): Tasks {
  const d = p.longtasks.filter(([s]) => s >= from && s < to).map(([, x]) => Math.round(x))
  return { count: d.length, totalMs: d.reduce((a, b) => a + b, 0), maxMs: Math.max(0, ...d), all: d }
}
/** The long animation frames of a window, longest first, with the scripts that ran in each (attribution). */
function loafsIn(p: Probe, from: number, to: number, top = 5) {
  return p.loafs.filter((f) => f.start >= from && f.start < to && f.duration > 100).sort((a, b) => b.duration - a.duration).slice(0, top)
    .map((f) => ({ duration: Math.round(f.duration), blocking: Math.round(f.blocking), scripts: f.scripts.filter((s) => s.duration >= 20) }))
}

type Response = { at: number; method: string; path: string; status: number }

async function signIn(page: Page, a: Account): Promise<number> {
  const started = Date.now()
  await page.goto(`${origin}/`)
  await page.getByLabel('Email', { exact: true }).fill(a.email)
  await page.getByRole('textbox', { name: /^Password\b/ }).fill(a.password)
  await page.locator('#unlock-secret-key').fill(a.secretKey)
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await page.getByRole('button', { name: 'New', exact: true }).waitFor({ timeout: 10 * 60_000 })
  return Date.now() - started
}

/** Wait until the page has had no long task for `quietMs` (its own clock), at most `maxMs`. */
async function quiet(page: Page, quietMs: number, maxMs: number): Promise<number> {
  const start = Date.now()
  for (;;) {
    const p = await probe(page)
    const last = p.longtasks.reduce((m, [s, d]) => Math.max(m, s + d), 0)
    if (p.now - last >= quietMs) return Date.now() - start
    if (Date.now() - start > maxMs) return -1
    await page.waitForTimeout(250)
  }
}

/** Wait until the page's DOM has not changed for `settleMs` and `ok` holds; returns the settle time (page clock). */
async function settled(page: Page, ok: () => Promise<boolean>, maxMs = 5 * 60_000): Promise<number | null> {
  const start = Date.now()
  for (;;) {
    const p = await probe(page)
    if (p.now - p.mutationAt >= spec.settleMs && (await ok())) return p.mutationAt
    if (Date.now() - start > maxMs) return null
    await page.waitForTimeout(100)
  }
}

async function heap(cdp: CDPSession): Promise<number> {
  await cdp.send('HeapProfiler.collectGarbage')
  const m = await cdp.send('Performance.getMetrics')
  return m.metrics.find((x) => x.name === 'JSHeapUsedSize')?.value ?? -1
}

type Profile = { nodes: { id: number; callFrame: { functionName: string; url: string; lineNumber: number; columnNumber: number }; children?: number[] }[]; samples?: number[]; timeDeltas?: number[] }

/** The profile's frames by self time, heaviest first, each with the minified bundle's code at its position (and its
 *  callers' names), so a frame of a build without source maps can still be read. */
async function heaviestFrames(page: Page, profile: Profile, top = 12) {
  const self = new Map<number, number>()
  ;(profile.samples ?? []).forEach((id, i) => self.set(id, (self.get(id) ?? 0) + (profile.timeDeltas?.[i] ?? 0) / 1000))
  const parent = new Map<number, number>()
  for (const n of profile.nodes) for (const c of n.children ?? []) parent.set(c, n.id)
  const byId = new Map(profile.nodes.map((n) => [n.id, n]))
  const key = (n: Profile['nodes'][number]) => `${n.callFrame.url}:${n.callFrame.lineNumber}:${n.callFrame.columnNumber}`
  const total = new Map<string, { ms: number; node: Profile['nodes'][number]; callers: Set<string> }>()
  for (const [id, ms] of self) {
    const n = byId.get(id)!
    if (!n.callFrame.url) continue
    const e = total.get(key(n)) ?? { ms: 0, node: n, callers: new Set<string>() }
    e.ms += ms
    let up = parent.get(id)
    for (let i = 0; up !== undefined && i < 4; i++, up = parent.get(up)) {
      const u = byId.get(up)!
      if (u.callFrame.url) e.callers.add(`${u.callFrame.functionName || '?'}@${u.callFrame.lineNumber}:${u.callFrame.columnNumber}`)
    }
    total.set(key(n), e)
  }
  // Inclusive time: each sample counted once for every distinct frame on its stack.
  const inclusive = new Map<string, number>()
  ;(profile.samples ?? []).forEach((id, i) => {
    const ms = (profile.timeDeltas?.[i] ?? 0) / 1000
    const seen = new Set<string>()
    for (let up: number | undefined = id; up !== undefined; up = parent.get(up)) {
      const f = byId.get(up)!.callFrame
      const k = `${f.functionName || '?'}@${f.lineNumber}:${f.columnNumber}`
      if (f.url && !seen.has(k)) { seen.add(k); inclusive.set(k, (inclusive.get(k) ?? 0) + ms) }
    }
  })
  console.log(JSON.stringify({ inclusive: [...inclusive].sort((a, b) => b[1] - a[1]).slice(0, 25).map(([k, ms]) => `${k} ${ms.toFixed(1)}`) }))
  const sorted = [...total.values()].sort((a, b) => b.ms - a.ms).slice(0, top)
  const sources = new Map<string, string>()
  for (const e of sorted) {
    const url = e.node.callFrame.url
    if (!sources.has(url)) sources.set(url, await page.evaluate((u) => fetch(u).then((r) => r.text()).catch(() => ''), url))
  }
  return sorted.map((e) => {
    const lines = (sources.get(e.node.callFrame.url) ?? '').split('\n')
    const line = lines[e.node.callFrame.lineNumber] ?? ''
    const col = e.node.callFrame.columnNumber
    return {
      ms: Math.round(e.ms * 10) / 10, fn: e.node.callFrame.functionName, file: e.node.callFrame.url.split('/').at(-1),
      at: `${e.node.callFrame.lineNumber}:${col}`, code: line.slice(Math.max(0, col - 60), col + 160), callers: [...e.callers].slice(0, 6),
    }
  })
}

const nodesScope = (path: string) => /^\/api\/scopes\/([0-9a-f-]{36})\/nodes$/.exec(path)?.[1] ?? null
const mediaScope = (path: string) => /^\/api\/scopes\/([0-9a-f-]{36})\/media$/.exec(path)?.[1] ?? null
/** Every tree the search opens itself and every space's media listing answered since `wall`. */
const answered = (responses: Response[], wall: number) => {
  const since = responses.filter((r) => r.at >= wall)
  const trees = new Set(since.map((r) => nodesScope(r.path)))
  const media = new Set(since.map((r) => mediaScope(r.path)))
  return spec.otherScopes.every((s) => trees.has(s)) && (spec.mediaScopes ?? []).every((s) => media.has(s))
}

async function measure(browser: Browser, throttle: number, run: number): Promise<Record<string, unknown>> {
  const context = await browser.newContext()
  await context.addInitScript(INIT)
  const page = await context.newPage()
  const responses: Response[] = []
  const failed: string[] = []
  page.on('response', (r) => {
    const u = new URL(r.url())
    if (u.pathname.startsWith('/api/')) responses.push({ at: Date.now(), method: r.request().method(), path: u.pathname, status: r.status() })
  })
  page.on('requestfailed', (r) => failed.push(`${r.method()} ${new URL(r.url()).pathname} ${r.failure()?.errorText}`))
  const cdp = await context.newCDPSession(page)
  await cdp.send('Performance.enable')
  if (throttle > 1) await cdp.send('Emulation.setCPUThrottlingRate', { rate: throttle })
  const signInMs = await signIn(page, spec.account)
  if (spec.activeHash) await page.evaluate((h) => { location.hash = h }, spec.activeHash)
  await page.getByText(spec.rootText, { exact: false }).first().waitFor({ timeout: 10 * 60_000 })
  const quietBefore = await quiet(page, 3000, 5 * 60_000)
  const field = page.locator('[data-testid="search-field"] input.veyq-tags-input__input')

  // 1. the first focus: the build
  const heapBefore = await heap(cdp)
  const decodesBefore = (await probe(page)).decodes
  const firstWall = Date.now()
  const firstAt = await now(page)
  await field.click()
  const buildAt = await settled(page, async () => {
    const p = await probe(page)
    return answered(responses, firstWall) && p.loadingSeen !== null && p.loadingGone !== null
  })
  let p = await probe(page)
  const buildDoneAt = p.loadingGone ?? buildAt ?? p.now
  const first = {
    buildMs: buildAt === null ? null : Math.round(buildDoneAt - firstAt),
    stopped: buildAt === null ? 'the build did not settle within 5 min' : null,
    longtasks: tasksIn(p, firstAt, buildDoneAt + 1),
    loafs: loafsIn(p, firstAt, buildDoneAt + 1),
    decodes: p.decodes - decodesBefore,
    responses: responses.filter((r) => r.at >= firstWall).map(({ method, path, status }) => ({ method, path, status })),
  }
  await page.waitForTimeout(1000)
  const heapAfter = await heap(cdp)

  // 2. typing
  await quiet(page, 2000, 2 * 60_000)
  const steps: Record<string, unknown>[] = []
  let typed = ''
  for (const key of spec.keys) {
    if (spec.profile) {
      await cdp.send('Profiler.enable')
      await cdp.send('Profiler.setSamplingInterval', { interval: 100 })
      await cdp.send('Profiler.start')
    }
    const before = await probe(page)
    const at = before.now
    await page.keyboard.press(key)
    if (key.length === 1) typed += key
    const ok = key === 'Enter'
      ? async () => (await page.locator('.veyq-tags-input__tag', { hasText: spec.chip }).count()) === 1
      : key === 'Backspace'
        ? async () => (await page.locator('.veyq-tags-input__tag').count()) === 0
        : async () => (await field.inputValue()) === typed && (await page.locator('.veyq-tags-input__option').count()) > 0
    const doneAt = await settled(page, ok, 2 * 60_000)
    const heaviest = spec.profile ? await heaviestFrames(page, (await cdp.send('Profiler.stop')).profile) : undefined
    p = await probe(page)
    const end = doneAt ?? p.now
    const events = p.events.filter((e) => e.start >= at - 1 && e.start < end)
    steps.push({
      key, text: await field.inputValue(), chips: await page.locator('.veyq-tags-input__tag').allTextContents(),
      options: await page.locator('.veyq-tags-input__option').count(),
      settled: doneAt !== null, toSettledMs: Math.round(end - at),
      longtasks: tasksIn(p, at, end + 1), loafs: loafsIn(p, at, end + 1, 3),
      eventMaxMs: Math.round(Math.max(0, ...events.map((e) => e.duration))),
      events: events.map((e) => ({ name: e.name, duration: Math.round(e.duration), processing: Math.round(e.processing) })),
      ...(heaviest ? { heaviest } : {}),
    })
    await quiet(page, 500, 60_000)
  }

  // 3. the second focus, nothing changed
  await page.keyboard.press('Escape')
  await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur())
  await quiet(page, 2000, 60_000)
  const decodesBefore2 = (await probe(page)).decodes
  await page.evaluate(() => { (window as unknown as { __srch: Probe }).__srch.keep = true })
  const secondWall = Date.now()
  const secondAt = await now(page)
  await field.click()
  const secondDone = await settled(page, async () => {
    return answered(responses, secondWall) && !(await page.locator('.veyq-search__loading').count())
  }, 2 * 60_000)
  p = await probe(page)
  const secondEnd = secondDone ?? p.now
  const second = {
    settled: secondDone !== null, toSettledMs: Math.round(secondEnd - secondAt),
    longtasks: tasksIn(p, secondAt, secondEnd + 1),
    decodes: p.decodes - decodesBefore2,
    decoded: p.decoded,
    responses: responses.filter((r) => r.at >= secondWall).map(({ method, path, status }) => ({ method, path, status })),
  }
  await context.close().catch(() => {})
  return {
    throttle, run, signInMs, quietBeforeMs: quietBefore, first, heap: { beforeBytes: heapBefore, afterBytes: heapAfter, deltaBytes: heapAfter - heapBefore },
    steps, second, failedRequests: failed,
  }
}

test(`search: ${spec.label}`, async ({ browser }) => {
  const runs: Record<string, unknown>[] = []
  for (const throttle of spec.throttles) {
    for (let run = 1; run <= spec.runs; run++) {
      const r = await measure(browser, throttle, run)
      runs.push(r)
      const f = r.first as { buildMs: number | null; longtasks: Tasks }
      console.log(JSON.stringify({ throttle, run, buildMs: f.buildMs, buildMaxTask: f.longtasks.maxMs,
        typingMaxTask: Math.max(...(r.steps as { longtasks: Tasks }[]).map((s) => s.longtasks.maxMs)) }))
      writeFileSync(`${OUT}/search-result.json`, JSON.stringify({ label: spec.label, runs }, null, 2))
    }
  }
  writeFileSync(`${OUT}/search-result.json`, JSON.stringify({ label: spec.label, runs }, null, 2))
})
