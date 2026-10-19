// The browser leg (cr-volume-measurement-local): what a big list costs the device that opens it. It waits
// on STATES, never on durations — a wait ends when the state is reached or when nothing has moved for `stallSecs`,
// and a stall is reported as "k of N", never as a pass or a hang. TEMPORARY, see playwright.config.ts beside it.
//
// `scripts/measure_volume.py` writes the spec (/out/browser-spec.json) and reads the result (/out/browser-result.json).
// Every run is a fresh context (a new device): sign in with password and Secret Key at the tenant's root, then open
// the target by its URL fragment in the same document, so the clock starts at the open and not at Argon2id. Per run it
// records the long tasks (an observer the init script installs before any page script), the JS heap and DOM node
// count (CDP `Performance.getMetrics`), the requests by kind (Resource Timing, its buffer raised so thousands of
// thumbnail fetches are not dropped) and every failed request; and it runs unthrottled and under a CPU throttle.
//
// A gallery mounts only the tiles near its viewport (cr-gallery-virtualization, ADR 0105), so "the last thumbnail of
// all" is no longer a state the page reaches by itself. What it promises instead, and what this leg waits for:
//   - *viewport complete* — every mounted tile inside the viewport shows a decrypted image (or, for a medium without
//     derivatives, its icon), with at least one tile there;
//   - *end complete* — the same after a jump to the end of the grid's scroll container;
//   - the live DOM's node count at both moments, independent of the Chapter's size if the window holds;
//   - the long tasks up to *viewport complete*, after it, and during a scripted scroll through the whole Chapter.
// Which tiles are inside the viewport is an IntersectionObserver's answer, never a layout read per tile per tick: an
// unwindowed grid of 10,000 tiles would otherwise be measured under the harness's own layout thrash.
import { test, type Browser, type BrowserContext, type CDPSession, type Page } from '@playwright/test'
import { readFileSync, writeFileSync } from 'node:fs'

type Account = { email: string; password: string; secretKey: string }
type Spec = {
  label: string
  axis: 'photos' | 'bare' | 'tree' | 'deep' | 'members' | 'five'
  domain: string
  runs: number
  throttles: number[]
  stallSecs: number
  accounts: Account[]
  /** The URL fragment that opens the target (a Chapter, a group, or empty for the members page). */
  hash: string
  /** photos/bare/five: the media count; members: the member count; tree: unused. */
  items: number
  /** members: how many rows carry a picture; tree: the folder's name; deep: the Chapter's name. */
  avatars?: number
  text?: string
  /** The list call whose Resource Timing entry is "the list arrived" (a path fragment). */
  listPath?: string
  /** Keep the opened target on screen this long before closing — a member who leaves the tab open (its live poll runs).
   *  The server leg's drift bite opens a browser this way and must see the reading refused. */
  holdSecs?: number
}

const OUT = process.env.VEYQ_MEASURE_OUT ?? '/out'
const spec = JSON.parse(readFileSync(`${OUT}/browser-spec.json`, 'utf8')) as Spec
const origin = `https://${spec.domain}`

// Runs in every document before its own scripts: the long-task observer and a Resource Timing buffer big enough.
const INIT = () => {
  const w = window as unknown as { __vol: { longtasks: [number, number][]; marks: Record<string, number> } }
  w.__vol = { longtasks: [], marks: {} }
  performance.setResourceTimingBufferSize(200_000)
  try {
    // [start, duration]: a long task is counted toward the phase it STARTED in (up to viewport complete, after it, the
    // scroll-through).
    new PerformanceObserver((list) => { for (const e of list.getEntries()) w.__vol.longtasks.push([e.startTime, e.duration]) })
      .observe({ type: 'longtask', buffered: true })
  } catch { /* no long-task support: the count stays empty, and says so by being empty */ }
}

type Watch = { axis: Spec['axis']; items: number; avatars: number; text: string; listPath: string }
type State = {
  t0: number; now: number; listMs: number | null; listRequestMs: number | null; firstTileMs: number | null
  tiles: number; thumbs: number; doneMs: number | null; progressAt: number; rows: number; named: number; pictures: number
  textMs: number | null
  /** The gallery's phases: `open` until viewport complete, `end` after the jump until end complete, then `done`. */
  phase: 'open' | 'end' | 'done'
  /** Tiles inside the viewport now, and how many of them show their image (or icon). */
  inView: number; shownInView: number
  viewportCompleteMs: number | null; viewportCompleteAt: number | null; viewportDomNodes: number | null
  jumpAt: number | null; endCompleteMs: number | null; endDomNodes: number | null
}
type LongTasks = { count: number; totalMs: number; maxMs: number }

/** Started in the page right before the target is opened: samples the DOM every 100 ms and keeps the timestamps. */
const WATCH = (p: Watch) => {
  const w = window as unknown as {
    __vol: { state: State; longtasks: [number, number][]; jump?: () => void; scrollThrough?: () => Promise<unknown> }
  }
  const t0 = performance.now()
  const s: State = {
    t0, now: t0, listMs: null, listRequestMs: null, firstTileMs: null, tiles: 0, thumbs: 0, doneMs: null,
    progressAt: t0, rows: 0, named: 0, pictures: 0, textMs: null, phase: 'open', inView: 0, shownInView: 0,
    viewportCompleteMs: null, viewportCompleteAt: null, viewportDomNodes: null, jumpAt: null, endCompleteMs: null,
    endDomNodes: null,
  }
  w.__vol.state = s
  // The live DOM's nodes, every type (what CDP's `Nodes` counts, minus nodes detached and not yet collected).
  const domNodes = () => {
    const walker = document.createTreeWalker(document, NodeFilter.SHOW_ALL)
    let n = 0
    while (walker.nextNode()) n++
    return n
  }
  // The grid's scroll container: its nearest ancestor that scrolls, else the document.
  const scroller = (): Element => {
    for (let n = document.querySelector('.media-grid')?.parentElement; n; n = n.parentElement) {
      const o = getComputedStyle(n).overflowY
      if ((o === 'auto' || o === 'scroll') && n.scrollHeight > n.clientHeight) return n
    }
    return document.scrollingElement ?? document.documentElement
  }
  // Which tiles intersect the viewport: an IntersectionObserver's answer, kept as a set (see the header).
  const inView = new Set<Element>()
  const observed = new WeakSet<Element>()
  // When the observer last reported: after the jump, the tiles in view count only once it has reported anew — before
  // that the set still holds the tiles the jump scrolled away from, and would read as complete.
  let lastReport = 0
  let newestObserveAt = 0
  const io = typeof IntersectionObserver === 'undefined'
    ? null
    : new IntersectionObserver((entries) => {
      lastReport = performance.now()
      for (const e of entries) if (e.isIntersecting) inView.add(e.target); else inView.delete(e.target)
    })
  const gallery = p.axis === 'photos' || p.axis === 'bare' || p.axis === 'five'
  const tick = () => {
    const now = performance.now()
    s.now = now
    if (s.listMs === null && p.listPath) {
      const e = performance.getEntriesByType('resource').find((r) => r.name.includes(p.listPath) && r.startTime >= t0 - 1) as PerformanceResourceTiming | undefined
      if (e && e.responseEnd > 0) { s.listMs = e.responseEnd - t0; s.listRequestMs = e.responseEnd - e.requestStart }
    }
    let progress = 0
    if (gallery) {
      const all = document.querySelectorAll('.media-tile')
      for (const t of all) if (!observed.has(t)) { observed.add(t); io?.observe(t); newestObserveAt = now }
      const tiles = all.length
      const thumbs = document.querySelectorAll('.media-tile-img[src^="blob:"]').length
      let view = 0
      let shown = 0
      for (const t of inView) {
        if (!t.isConnected) { inView.delete(t); continue }
        view++
        if (t.querySelector('.media-tile-img[src^="blob:"], .media-grid-icon')) shown++
      }
      if (tiles > 0 && s.firstTileMs === null) s.firstTileMs = now - t0
      progress = tiles + thumbs + view + shown - s.tiles - s.thumbs - s.inView - s.shownInView
      s.tiles = tiles
      s.thumbs = thumbs
      s.inView = view
      s.shownInView = shown
      // Complete only once the observer has reported on every tile observed so far: a tile mounted since its last report
      // is not in the set yet, and the ones that are could all be showing while it is not.
      const complete = view > 0 && shown === view && lastReport > newestObserveAt
      if (complete && s.phase === 'open') {
        s.viewportCompleteMs = now - t0
        s.viewportCompleteAt = now
        s.viewportDomNodes = domNodes()
        s.doneMs = s.viewportCompleteMs
        s.phase = 'done'
      } else if (complete && s.phase === 'end' && s.jumpAt !== null && now > s.jumpAt) {
        s.endCompleteMs = now - s.jumpAt
        s.endDomNodes = domNodes()
        s.phase = 'done'
      }
    } else if (p.axis === 'members') {
      const rows = [...document.querySelectorAll('.member-row')]
      const named = rows.filter((r) => !(r.querySelector('.veyq-row-title')?.textContent ?? '@').startsWith('@')).length
      const pictures = document.querySelectorAll('.member-row [data-avatar-image]').length
      progress = rows.length + named + pictures - s.rows - s.named - s.pictures
      s.rows = rows.length
      s.named = named
      s.pictures = pictures
      if (s.firstTileMs === null && rows.length > 0) s.firstTileMs = now - t0
      if (rows.length >= p.items && named >= p.items && pictures >= p.avatars && s.doneMs === null) s.doneMs = now - t0
    } else {
      // tree: the seeded folder listed at the space's root (the sidebar lists spaces, not folders: the root level view
      // lists them); deep: the Chapter's own heading.
      const found = p.axis === 'deep'
        ? [...document.querySelectorAll('h1')].some((h) => h.textContent?.trim() === p.text)
        : !!document.querySelector('main')?.textContent?.includes(p.text)
      if (found && s.textMs === null) { s.textMs = now - t0; s.doneMs = s.textMs; progress = 1 }
    }
    if (progress > 0) s.progressAt = now
    if (gallery ? s.phase !== 'done' : s.doneMs === null) setTimeout(tick, 100)
  }
  tick()
  if (!gallery) return
  /** Jump to the end of the grid's scroll container; the tick ends the `end` phase at *end complete*. A grid too short to
   *  scroll moves nothing, so the observer never reports and the wait ends as a stall (every measured Chapter scrolls). */
  w.__vol.jump = () => {
    const el = scroller()
    s.jumpAt = null
    const armed = performance.now()
    el.scrollTop = el.scrollHeight
    const wait = () => {
      if (lastReport > armed) {
        s.jumpAt = armed
        s.phase = 'end'
        s.progressAt = performance.now()
        tick()
      } else setTimeout(wait, 16)
    }
    wait()
  }
  /** Scroll from the top to the end of the grid one viewport per step, each step on its own frame and 50 ms apart, and
   *  report the wall time, the steps and the long tasks that started during it. Nothing waits for a thumbnail. */
  w.__vol.scrollThrough = async () => {
    const el = scroller()
    el.scrollTop = 0
    await new Promise((r) => setTimeout(r, 1000))
    const started = performance.now()
    let steps = 0
    while (el.scrollTop + el.clientHeight < el.scrollHeight - 1) {
      const before = el.scrollTop
      el.scrollTop = before + el.clientHeight
      steps++
      await new Promise((r) => requestAnimationFrame(() => setTimeout(r, 50)))
      if (el.scrollTop === before) break // the container stopped growing: the end
    }
    const ended = performance.now()
    const lts = w.__vol.longtasks.filter(([start]) => start >= started && start < ended).map(([, d]) => d)
    return {
      ms: Math.round(ended - started), steps,
      longtasks: { count: lts.length, totalMs: Math.round(lts.reduce((a, b) => a + b, 0)), maxMs: Math.round(Math.max(0, ...lts)) },
    }
  }
}

async function signIn(page: Page, a: Account): Promise<number> {
  const started = Date.now()
  await page.goto(`${origin}/`)
  await page.getByLabel('Email', { exact: true }).fill(a.email)
  await page.getByRole('textbox', { name: /^Password\b/ }).fill(a.password)
  await page.locator('#unlock-secret-key').fill(a.secretKey)
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  // The shared space's toolbar: signed in, unlocked, home rendered.
  await page.getByRole('button', { name: 'New', exact: true }).waitFor({ timeout: 5 * 60_000 })
  return Date.now() - started
}

type Run = Record<string, unknown>
type Device = { context: BrowserContext; page: Page; failed: string[]; cdp: CDPSession; signInMs: number; throttle: number; email: string }

/** A new device, signed in and unlocked, its CPU throttled as asked; nothing opened yet. */
async function device(browser: Browser, a: Account, throttle: number): Promise<Device> {
  const context = await browser.newContext()
  await context.addInitScript(INIT)
  const page = await context.newPage()
  const failed: string[] = []
  page.on('requestfailed', (r) => failed.push(`${r.method()} ${new URL(r.url()).pathname} ${r.failure()?.errorText}`))
  page.on('response', (r) => { if (r.status() >= 400) failed.push(`${r.request().method()} ${new URL(r.url()).pathname} ${r.status()}`) })
  const cdp = await context.newCDPSession(page)
  await cdp.send('Performance.enable')
  if (throttle > 1) await cdp.send('Emulation.setCPUThrottlingRate', { rate: throttle })
  const signInMs = await signIn(page, a)
  return { context, page, failed, cdp, signInMs, throttle, email: a.email }
}

/** `p`, or null when it has not settled within `ms` (the page's main thread may be too busy to answer). */
function within<T>(p: Promise<T>, ms: number): Promise<T | null> {
  let timer: ReturnType<typeof setTimeout> | undefined
  const late = new Promise<null>((resolve) => { timer = setTimeout(() => resolve(null), ms) })
  return Promise.race([p.catch(() => null), late]).finally(() => clearTimeout(timer))
}

const stateOf = (page: Page) => page.evaluate(() => (window as unknown as { __vol: { state: State } }).__vol.state)

/** Open the target on a signed-in device and wait for its end state, or for a stall; then read the device's numbers. */
async function open(d: Device, run: number): Promise<Run> {
  const { page, cdp } = d
  // What failed while signing in is the sign-in's (a fresh device's boot asks for a refresh with no cookie: a 400 by
  // design); the reading counts what fails once the target is opened.
  d.failed.length = 0
  await page.evaluate(WATCH, {
    axis: spec.axis, items: spec.items, avatars: spec.avatars ?? 0, text: spec.text ?? '', listPath: spec.listPath ?? '',
  } satisfies Watch)
  if (spec.axis === 'members') {
    // The members page is a settings panel reached in-app (a document load would drop the unlocked vault).
    // The way a person goes: the account menu (named by the signed-in address), Settings, then the hub's Members entry.
    await page.getByRole('button', { name: new RegExp(`^${d.email.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`) }).click()
    await page.getByRole('menuitem', { name: 'Settings' }).click()
    await page.getByText('Members', { exact: true }).first().click()
  } else {
    await page.evaluate((h) => { location.hash = h }, spec.hash)
  }
  // Two stalls end a wait: the page reports no progress for `stallSecs` (its own clock), or the page does not answer a
  // read for `stallSecs` of this process's clock — a main thread so busy it cannot run the read is a stall as well (the
  // 10,000 Chapter under a 4x throttle held one for over 30 minutes). A stall is a reading, "k of N", never a pass.
  let stopped = null as string | null
  let state = null as State | null
  const waitFor = async (reached: (s: State) => boolean, what: string): Promise<void> => {
    for (;;) {
      const s = await within(stateOf(page), spec.stallSecs * 1000)
      if (s === null) {
        stopped = `${what}: the page did not answer for ${spec.stallSecs}s` +
          (state ? ` (last seen: ${state.shownInView} of ${state.inView} tiles in view showing, ${state.tiles} mounted)` : '')
        return
      }
      state = s
      if (reached(s)) return
      if ((s.now - s.progressAt) / 1000 > spec.stallSecs) {
        stopped = `${what}: ` + (spec.axis === 'members'
          ? `${s.rows} rows, ${s.named} named, ${s.pictures} pictures of ${spec.items} / ${spec.avatars}; nothing moved for ${spec.stallSecs}s`
          : spec.axis === 'tree' || spec.axis === 'deep'
            ? `'${spec.text}' not shown; nothing moved for ${spec.stallSecs}s`
            : `${s.shownInView} of ${s.inView} tiles in view showing (${s.tiles} mounted, ${s.thumbs} images); nothing moved for ${spec.stallSecs}s`)
        return
      }
      await page.waitForTimeout(500)
    }
  }
  const gallery = spec.axis === 'photos' || spec.axis === 'five' || spec.axis === 'bare'
  let scrollThrough: unknown = null
  if (!gallery) await waitFor((s) => s.doneMs !== null, 'open')
  else {
    await waitFor((s) => s.viewportCompleteMs !== null, 'viewport complete')
    // Five members opening together measure the open; one device at a time goes on to the end and the scroll-through.
    if (!stopped && spec.axis !== 'five') {
      await within(page.evaluate(() => (window as unknown as { __vol: { jump: () => void } }).__vol.jump()), spec.stallSecs * 1000)
      await waitFor((s) => s.endCompleteMs !== null, 'end complete')
    }
    if (!stopped && spec.axis !== 'five') {
      scrollThrough = await within(
        page.evaluate(() => (window as unknown as { __vol: { scrollThrough: () => Promise<unknown> } }).__vol.scrollThrough()),
        Math.max(spec.stallSecs, spec.items / 20) * 1000,
      )
      if (scrollThrough === null) stopped = 'scroll-through: the page did not finish it'
    }
  }
  if (!stopped) state = (await within(stateOf(page), spec.stallSecs * 1000)) ?? state
  if (spec.holdSecs) {
    console.log(JSON.stringify({ holding: spec.holdSecs }))
    await page.waitForTimeout(spec.holdSecs * 1000)
  }
  const got = await within(cdp.send('Performance.getMetrics'), spec.stallSecs * 1000)
  const metrics: Record<string, number> = got ? Object.fromEntries(got.metrics.map((m) => [m.name, m.value])) : {}
  const seen = await within(page.evaluate((vcAt: number | null) => {
    const w = window as unknown as { __vol: { longtasks: [number, number][] } }
    const kinds: Record<string, { count: number; bytes: number }> = {}
    for (const e of performance.getEntriesByType('resource') as PerformanceResourceTiming[]) {
      const path = new URL(e.name).pathname
      // The API by its resource family with the ids left out (`api: scopes/nodes/media`; presigns are `api: blobs`), the
      // store's GETs (the bucket is on the same origin), the rest by initiator.
      const kind = path.startsWith('/api/')
        ? (path.includes('/blobs') ? 'api: blobs' : `api: ${path.split('/').filter((x) => x && x !== 'api' && !/^[0-9a-f-]{36}$/.test(x)).join('/')}`)
        : e.initiatorType === 'fetch' ? 'store' : e.initiatorType
      const k = (kinds[kind] ??= { count: 0, bytes: 0 })
      k.count++
      k.bytes += e.transferSize
    }
    const sum = (lt: number[]): LongTasks => ({
      count: lt.length, totalMs: Math.round(lt.reduce((a, b) => a + b, 0)), maxMs: Math.round(Math.max(0, ...lt)),
    })
    const all = w.__vol.longtasks
    return {
      requests: kinds,
      longtasks: sum(all.map(([, d]) => d)),
      // Up to viewport complete, and after it (the jump, the scroll-through, whatever the page still did): by start.
      longtasksToViewport: vcAt === null ? null : sum(all.filter(([t]) => t < vcAt).map(([, d]) => d)),
      longtasksAfterViewport: vcAt === null ? null : sum(all.filter(([t]) => t >= vcAt).map(([, d]) => d)),
    }
  }, state?.viewportCompleteAt ?? null), spec.stallSecs * 1000) ?? { requests: {}, longtasks: null, longtasksToViewport: null, longtasksAfterViewport: null }
  await d.context.close().catch(() => {})
  return {
    run, throttle: d.throttle, signInMs: d.signInMs,
    listMs: state?.listMs ?? null, listRequestMs: state?.listRequestMs ?? null, firstTileMs: state?.firstTileMs ?? null,
    // A windowed grid never mounts every tile, so "the last thumbnail of all" is retired for the gallery axes: these are
    // what it promises instead (the header).
    viewportCompleteMs: gallery ? state?.viewportCompleteMs ?? null : null,
    viewportDomNodes: gallery ? state?.viewportDomNodes ?? null : null,
    endCompleteMs: gallery ? state?.endCompleteMs ?? null : null,
    endDomNodes: gallery ? state?.endDomNodes ?? null : null,
    scrollThrough,
    doneMs: state?.doneMs ?? null, tiles: state?.tiles ?? 0, thumbs: state?.thumbs ?? 0, rows: state?.rows ?? 0,
    named: state?.named ?? 0, pictures: state?.pictures ?? 0, stopped, failedRequests: d.failed,
    jsHeapUsedBytes: metrics.JSHeapUsedSize ?? null, cdpNodes: metrics.Nodes ?? null, ...seen,
  }
}

test(`volume: ${spec.label}`, async ({ browser }) => {
  const runs: Run[] = []
  if (spec.axis === 'five') {
    // Five members open the same Chapter together, each its own device: all signed in first, then opened at once.
    const devices = await Promise.all(spec.accounts.slice(0, 5).map((a) => device(browser, a, 1)))
    const together = await Promise.all(devices.map((d, i) => open(d, i + 1)))
    runs.push(...together.map((r, i) => ({ ...r, member: i + 1 })))
  } else {
    for (const throttle of spec.throttles) {
      for (let run = 1; run <= spec.runs; run++) {
        const r = await open(await device(browser, spec.accounts[0], throttle), run)
        runs.push(r)
        console.log(JSON.stringify({ throttle, run, listMs: r.listMs, firstTileMs: r.firstTileMs, viewportCompleteMs: r.viewportCompleteMs, endCompleteMs: r.endCompleteMs, stopped: r.stopped }))
        if (r.stopped) break
      }
    }
  }
  writeFileSync(`${OUT}/browser-result.json`, JSON.stringify({ label: spec.label, axis: spec.axis, items: spec.items, runs }, null, 2))
})
