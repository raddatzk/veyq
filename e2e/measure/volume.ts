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
  const w = window as unknown as { __vol: { longtasks: number[]; marks: Record<string, number> } }
  w.__vol = { longtasks: [], marks: {} }
  performance.setResourceTimingBufferSize(200_000)
  try {
    new PerformanceObserver((list) => { for (const e of list.getEntries()) w.__vol.longtasks.push(e.duration) })
      .observe({ type: 'longtask', buffered: true })
  } catch { /* no long-task support: the count stays empty, and says so by being empty */ }
}

type Watch = { axis: Spec['axis']; items: number; avatars: number; text: string; listPath: string }
type State = {
  t0: number; now: number; listMs: number | null; listRequestMs: number | null; firstTileMs: number | null
  tiles: number; thumbs: number; doneMs: number | null; progressAt: number; rows: number; named: number; pictures: number
  textMs: number | null
}

/** Started in the page right before the target is opened: samples the DOM every 100 ms and keeps the timestamps. */
const WATCH = (p: Watch) => {
  const w = window as unknown as { __vol: { state: State } }
  const t0 = performance.now()
  const s: State = {
    t0, now: t0, listMs: null, listRequestMs: null, firstTileMs: null, tiles: 0, thumbs: 0, doneMs: null,
    progressAt: t0, rows: 0, named: 0, pictures: 0, textMs: null,
  }
  w.__vol.state = s
  const tick = () => {
    const now = performance.now()
    s.now = now
    if (s.listMs === null && p.listPath) {
      const e = performance.getEntriesByType('resource').find((r) => r.name.includes(p.listPath) && r.startTime >= t0 - 1) as PerformanceResourceTiming | undefined
      if (e && e.responseEnd > 0) { s.listMs = e.responseEnd - t0; s.listRequestMs = e.responseEnd - e.requestStart }
    }
    let progress = 0
    if (p.axis === 'photos' || p.axis === 'bare' || p.axis === 'five') {
      const tiles = document.querySelectorAll('.media-tile').length
      const thumbs = document.querySelectorAll('.media-tile-img[src^="blob:"]').length
      if (tiles > 0 && s.firstTileMs === null) s.firstTileMs = now - t0
      progress = tiles + thumbs - s.tiles - s.thumbs
      s.tiles = tiles
      s.thumbs = thumbs
      if (thumbs >= p.items && s.doneMs === null) s.doneMs = now - t0
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
    if (s.doneMs === null) setTimeout(tick, 100)
  }
  tick()
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
  // 10,000 Chapter under a 4x throttle held one for over 30 minutes).
  let stopped: string | null = null
  let state: State | null = null
  for (;;) {
    const s = await within(stateOf(page), spec.stallSecs * 1000)
    if (s === null) {
      stopped = `the page did not answer for ${spec.stallSecs}s` + (state ? ` (last seen: ${state.thumbs} of ${spec.items} thumbnails, ${state.tiles} tiles)` : '')
      break
    }
    state = s
    if (s.doneMs !== null) break
    if ((s.now - s.progressAt) / 1000 > spec.stallSecs) {
      stopped = spec.axis === 'members'
        ? `${s.rows} rows, ${s.named} named, ${s.pictures} pictures of ${spec.items} / ${spec.avatars}; nothing moved for ${spec.stallSecs}s`
        : spec.axis === 'tree' || spec.axis === 'deep'
          ? `'${spec.text}' not shown; nothing moved for ${spec.stallSecs}s`
          : `${s.thumbs} of ${spec.items} thumbnails decrypted (${s.tiles} tiles); nothing moved for ${spec.stallSecs}s`
      break
    }
    await page.waitForTimeout(500)
  }
  if (!stopped) state = (await within(stateOf(page), spec.stallSecs * 1000)) ?? state
  if (spec.holdSecs) {
    console.log(JSON.stringify({ holding: spec.holdSecs }))
    await page.waitForTimeout(spec.holdSecs * 1000)
  }
  const got = await within(cdp.send('Performance.getMetrics'), spec.stallSecs * 1000)
  const metrics: Record<string, number> = got ? Object.fromEntries(got.metrics.map((m) => [m.name, m.value])) : {}
  const seen = await within(page.evaluate(() => {
    const w = window as unknown as { __vol: { longtasks: number[] } }
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
    const lt = w.__vol.longtasks
    return { requests: kinds, longtasks: { count: lt.length, totalMs: Math.round(lt.reduce((a, b) => a + b, 0)), maxMs: Math.round(Math.max(0, ...lt)) } }
  }), spec.stallSecs * 1000) ?? { requests: {}, longtasks: null }
  await d.context.close().catch(() => {})
  const gallery = spec.axis === 'photos' || spec.axis === 'five' || spec.axis === 'bare'
  return {
    run, throttle: d.throttle, signInMs: d.signInMs,
    listMs: state?.listMs ?? null, listRequestMs: state?.listRequestMs ?? null, firstTileMs: state?.firstTileMs ?? null,
    lastThumbnailMs: gallery ? state?.doneMs ?? null : null,
    doneMs: state?.doneMs ?? null, tiles: state?.tiles ?? 0, thumbs: state?.thumbs ?? 0, rows: state?.rows ?? 0,
    named: state?.named ?? 0, pictures: state?.pictures ?? 0, stopped, failedRequests: d.failed,
    jsHeapUsedBytes: metrics.JSHeapUsedSize ?? null, domNodes: metrics.Nodes ?? null, ...seen,
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
        console.log(JSON.stringify({ throttle, run, listMs: r.listMs, firstTileMs: r.firstTileMs, doneMs: r.doneMs, stopped: r.stopped }))
        if (r.stopped) break
      }
    }
  }
  writeFileSync(`${OUT}/browser-result.json`, JSON.stringify({ label: spec.label, axis: spec.axis, items: spec.items, runs }, null, 2))
})
