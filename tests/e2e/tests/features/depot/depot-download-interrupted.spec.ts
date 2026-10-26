// A streamed Depot download that is interrupted, in a real browser (inbox #371's V1 leftovers): the four ways a download
// through the Service Worker's route (ADR 0047 § The download route) can be cut off or stretched that unit tests with fake
// ports cannot reach. depot-download.spec.ts proves the route itself; this file starts each download the same way (no
// save picker, so `src/web/src/shared/saveStream.ts` picks the SW's route) and then holds the store's presigned GETs from
// the seventh frame on (`page.route`), so the download stands still mid-file, its first frames already in the browser's
// download, at a moment the test chooses:
//
//  1. **The SW idle past 30 s.** Chromium may stop a worker that handled no event for 30 s, and a port message is no
//     event — so the page pings the worker every 10 s while a download runs (`sw/register.ts` `keepaliveMs`). The store is
//     held for 40 s, then let go. Shown: the download completes byte for byte after the pause, and the page sent its
//     keepalive meanwhile. NOT shown: that the keepalive is what saved it. Playwright attaches DevTools to every service
//     worker, and Chromium does not stop an idle worker while DevTools is attached (measured: in five runs on 2026-10-26
//     the closed tab's worker below was never stopped by itself in 40 s); and the page's own level poll goes through the
//     worker's fetch handler every few seconds, which counts as an event too.
//     **The one real 40 s wait this file keeps**, and why DevTools cannot replace it: its claim is that NOTHING stops a
//     download that stands still — DevTools can only stop the worker, and a stopped worker fails the download (3.). The
//     40 s also outlast the page's own 35 s start timer (`register.ts` `startTimeoutMs`), so a regression that left it
//     running past the first frame turns this red.
//  2. **A presign refresh past 10 min.** The store's presigned GETs live 600 s; the page re-presigns a blob whose URLs are
//     older than 9 min before it sends one (`core/blob/readScheduler.ts` `PRESIGN_REFRESH_MS`, `core/blob/transport.ts`
//     `makeChunkProvider`). A real 10-minute download would cost the lane over ten minutes per run, so the page's clock is
//     moved instead: while the download stands still, the page's `Date.now` jumps 11 minutes ahead (an evaluate on that one
//     document, no other timer touched), then the store is let go. Shown: the page presigns the blob a second time, sends
//     none of the first presign's URLs after the jump (one that it did send would be answered 403, as the store answers an
//     expired URL), and the file arrives byte for byte. Costs seconds. NOT shown: the store's own clock refusing a URL —
//     only the page's clock moved.
//  3. **A closed tab.** The tab that registered the download is closed while the download stands still. Nothing can send
//     the worker the rest of the file, and the worker closes the body only on the page's end with every byte counted
//     (`sw/downloadRoute.ts`), so the browser must never keep the file as whole. Shown: for 5 s after the close the
//     download has not completed (failed or still running — the run records which), no store GET is sent after the close,
//     and once the worker is stopped — through DevTools' `ServiceWorker.stopAllWorkers`, at the moment the test needs it,
//     as Chromium stops an idle one — the browser marks the download failed. Then the same context's worker serves a new
//     download in another tab byte for byte — the dead one left nothing in its way. The other tab is opened from the first
//     with `window.open` before the close, so it carries the first tab's session vault (`sessionStorage` is copied to a
//     window its opener opens) and needs no new sign-in. Why 5 s and no longer: the worker closes the body only on the
//     page's `veyq-dl-end` with every byte counted, and nothing can send one once the tab is gone, so there is no later
//     event to wait for; until 2026-10-26 it waited 40 s for Chromium to stop the worker by itself, which with DevTools
//     attached it never did (1.), so every run stopped it through DevTools after the wait anyway.
//  4. **Sign-out mid-download.** The tenant owner signs out from the profile menu while the download stands still. The
//     ending (`app/sessionEnd.ts`) fails the page's downloads and tells the worker to drop every one it streams, then
//     loads the sign-in page as a new document — the wipe of every key the page held (ADR 0070). Shown: the browser marks
//     the download failed (only the worker's drop can fail it: the page that would send the rest is gone), the tab is on
//     /login in a new document, and no store GET is sent after the sign-out. It runs last: the device is signed out.
//
// Left out (inbox #371, after the V1 queue): S0 (the real ceiling per browser), memory staying flat for a ~4 GB file, the
// native save picker and a file on the user's disk, no `.crswap` after an abort, Firefox, Safari, iOS and Android, and a
// network kill. Also left out: these interruptions through a picked file or the in-memory Blob — the other two save
// targets — and through a guest's link.
//
// It starts from the tenant whose owner the round trip activated (the `depot` project), uploads its own file, and
// leaves the instance as it found it: its device signs out, which ends only this device's session.
import { test, expect, type Page, type Response as PwResponse, type Route } from '@playwright/test'
import { randomBytes } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { createFolder, downloadButton, pickFiles, sha256 } from '../../../src/depot'
import { newDevice, signInOwner, tenantOrigin, type Device } from '../../../src/devices'

test.describe.configure({ mode: 'serial' })

const FRAME = 256 * 1024
// 24 whole frames and a short last one, as depot-download.spec.ts: the hold below lands well inside the file.
const FILE_BYTES = 24 * FRAME + 4_321
const CHUNKS = Math.ceil(FILE_BYTES / FRAME)
/** The first store GET held: six frames reach the download first, the pump's window (four) is then full and waits. */
const HELD_FROM = 7
/** Past the 30 s Chromium lets a service worker sit idle. */
const IDLE_MS = 40_000
/** How long a closed tab's download is watched before its worker is stopped: no later event could complete it (3.). */
const CLOSED_WATCH_MS = 5_000
/** Past the store's 600 s TTL and the page's 9-minute re-presign. */
const SKEW_MS = 11 * 60 * 1000
/** How long a download the worker dropped may take to show as failed. */
const FAIL_WITHIN_MS = 30_000

const stamp = randomBytes(3).toString('hex')
const folder = `Ablage für Kontoauszüge ${stamp}`
const fileName = `Kontoauszüge des Quartals ${stamp}.bin`
const content = new Uint8Array(randomBytes(FILE_BYTES))
const expected = { size: FILE_BYTES, sha256: sha256(content) }

let member: Device

const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms))
const STILL_RUNNING = 'still running'
/** A download's failure, or [STILL_RUNNING] when it neither completed nor failed within `ms`. Null means it completed. */
const failureWithin = (failure: Promise<string | null>, ms: number) =>
  Promise.race([failure, sleep(ms).then(() => STILL_RUNNING)])

/** The page offers no save picker: the next sink is the Service Worker's route. */
const withoutPicker = (page: Page) =>
  page.evaluate(() => void delete (window as unknown as { showSaveFilePicker?: unknown }).showSaveFilePicker)

/** A presigned GET of the store: the page fetches every frame itself, straight from the store. */
const isStoreGet = (url: URL) => url.searchParams.has('X-Amz-Signature')

/** The folder, read fresh: a new document opened from the space, with no save picker. */
async function openFolder(page: Page): Promise<void> {
  await page.goto(tenantOrigin())
  await page.getByRole('button', { name: folder, exact: true }).click({ timeout: 60_000 })
  await expect(page.getByRole('heading', { name: folder })).toBeVisible()
  await expect(downloadButton(page, fileName)).toBeEnabled({ timeout: 60_000 })
  await withoutPicker(page)
}

interface Stall {
  /** Store GETs the page sent so far. */
  sent: () => number
  /** Those that stood at the gate. */
  held: () => number
  /** Let the held GETs go; every later one is answered by the stall's `after`. */
  release: () => void
}

/** Store GETs from the [from]th on wait at a gate until [Stall.release]; the ones after that go to `after`. */
async function stallStore(page: Page, from: number, after: (route: Route) => Promise<void> = (r) => r.fallback()): Promise<Stall> {
  let sent = 0
  let held = 0
  let open = false
  let openGate!: () => void
  const gate = new Promise<void>((resolve) => (openGate = resolve))
  await page.route((url) => isStoreGet(url), async (route) => {
    sent += 1
    if (open) return after(route)
    if (sent < from) return route.fallback()
    held += 1
    await gate
    // The page that sent it may be gone by now (a closed tab, a signed-out document): then there is nothing to answer.
    await route.fallback().catch(() => {})
  })
  return {
    sent: () => sent,
    held: () => held,
    release: () => {
      open = true
      openGate()
    },
  }
}

/** The download stands still: GETs are held, and the page sent no new one for a second and a half — its window is full. */
async function standsStill(stall: Stall): Promise<void> {
  await expect.poll(stall.held, { message: 'the store was reached, then held', timeout: 60_000 }).toBeGreaterThan(0)
  for (let last = -1; stall.sent() !== last; ) {
    last = stall.sent()
    await sleep(1_500)
  }
}

/** Click the file's download and wait for the browser's download: the SW route's attachment. */
async function startDownload(page: Page) {
  const started = page.waitForEvent('download')
  await downloadButton(page, fileName).click()
  const download = await started
  expect(new URL(download.url()).pathname, 'the browser saves the SW route\'s attachment').toMatch(/^\/__download\/.+/)
  return download
}

test.beforeAll(async ({ browser }) => {
  member = await newDevice(browser)
})

test.afterEach(() => member?.confinement.assertConfined())

test.afterAll(async () => {
  await member?.context.close()
})

test(
  'the owner uploads a file of many frames into a folder',
  { tag: '@uc.depot.member-files:upload-attaches-one-sealed-blob' },
  async () => {
    const { page } = member
    await signInOwner(member)
    await createFolder(page, folder)
    await pickFiles(page, [{ name: fileName, bytes: content }])
    await expect(downloadButton(page, fileName)).toBeEnabled({ timeout: 60_000 })
  },
)

test(
  'a download that stands still past the Service Worker\'s 30 s idle completes byte for byte',
  { tag: '@uc.depot.member-files:download-returns-the-original-bytes' },
  async () => {
    test.setTimeout(180_000)
    const { page } = member
    await openFolder(page)
    // Every keepalive the page posts to the worker, counted from here on (`sw/register.ts` posts it to the controller).
    await page.evaluate(() => {
      const w = window as unknown as { __veyqKeepalives: number }
      w.__veyqKeepalives = 0
      const post = ServiceWorker.prototype.postMessage as (this: ServiceWorker, ...args: unknown[]) => void
      ServiceWorker.prototype.postMessage = function (this: ServiceWorker, ...args: unknown[]) {
        if ((args[0] as { type?: string } | null)?.type === 'veyq-download-keepalive') w.__veyqKeepalives += 1
        return post.apply(this, args)
      } as typeof ServiceWorker.prototype.postMessage
    })
    const stall = await stallStore(page, HELD_FROM)
    const download = await startDownload(page)
    await standsStill(stall)
    const stoodStill = Date.now()
    await sleep(IDLE_MS)
    expect(await failureWithin(download.failure(), 1_000), 'the download still runs after the pause').toBe(STILL_RUNNING)
    stall.release()

    expect(await download.failure()).toBeNull()
    const bytes = readFileSync(await download.path())
    expect({ size: bytes.length, sha256: sha256(bytes) }).toEqual(expected)
    expect(Date.now() - stoodStill, 'it stood still past the idle timeout').toBeGreaterThanOrEqual(IDLE_MS)
    expect(await page.evaluate(() => (window as unknown as { __veyqKeepalives: number }).__veyqKeepalives),
      'the page kept the worker alive every 10 s').toBeGreaterThanOrEqual(3)
    expect(await page.evaluate(() => navigator.serviceWorker.controller?.scriptURL ?? null)).toBe(`${tenantOrigin()}/sw.js`)
    await page.unrouteAll({ behavior: 'ignoreErrors' })
  },
)

test(
  'a download whose presigned URLs age past 10 minutes re-presigns and completes byte for byte',
  { tag: '@uc.depot.member-files:download-returns-the-original-bytes' },
  async () => {
    const { page } = member
    await openFolder(page)

    // Every presign of this file's chunks, as the X-Amz-Signature of each URL it handed the page.
    const presigned: Set<string>[] = []
    const parsing: Promise<void>[] = []
    type PresignBody = { results?: { presignedGets?: { url: string }[] | null }[] }
    const onResponse = (response: PwResponse) => {
      const url = new URL(response.url())
      if (response.request().method() !== 'POST' || !url.pathname.endsWith('/blob-downloads-batch')) return
      parsing.push(response.json().then((body: PresignBody) => {
        for (const item of body.results ?? []) {
          const gets = item.presignedGets ?? []
          if (gets.length !== CHUNKS) continue
          presigned.push(new Set(gets.map((g) => new URL(g.url).searchParams.get('X-Amz-Signature') ?? '')))
        }
      }).catch(() => {}))
    }
    page.on('response', onResponse)

    // After the jump, a URL of the first presign is one the store would refuse as expired: answered so, and recorded.
    const stale: string[] = []
    const stall = await stallStore(page, HELD_FROM, async (route) => {
      const signature = new URL(route.request().url()).searchParams.get('X-Amz-Signature') ?? ''
      if (!presigned[0]?.has(signature)) return route.fallback()
      stale.push(route.request().url())
      return route.fulfill({
        status: 403,
        contentType: 'application/xml',
        headers: { 'access-control-allow-origin': tenantOrigin() },
        body: '<?xml version="1.0" encoding="UTF-8"?><Error><Code>AccessDenied</Code><Message>Request has expired</Message></Error>',
      })
    })
    const download = await startDownload(page)
    await standsStill(stall)
    await Promise.all(parsing)
    expect(presigned, 'the download presigned the file once before it stood still').toHaveLength(1)

    // The page's clock only, on this document: every later `Date.now()` reads 11 minutes on.
    await page.evaluate((skew) => {
      const real = Date.now.bind(Date)
      Date.now = () => real() + skew
    }, SKEW_MS)
    stall.release()

    expect(await download.failure()).toBeNull()
    const bytes = readFileSync(await download.path())
    expect({ size: bytes.length, sha256: sha256(bytes) }).toEqual(expected)
    await Promise.all(parsing)
    expect(presigned, 'the aged URLs were re-presigned once').toHaveLength(2)
    expect(stale, 'no URL of the first presign was sent after it aged').toEqual([])
    page.off('response', onResponse)
    await page.unrouteAll({ behavior: 'ignoreErrors' })
  },
)

test(
  'a tab closed mid-download never leaves a whole file, and the worker serves the next download byte for byte',
  { tag: '@uc.depot.member-files:download-returns-the-original-bytes' },
  async () => {
    test.setTimeout(240_000)
    const { context, page: tab } = member
    await openFolder(tab)
    // The next tab, opened by this one so it carries this tab's session vault; it stays blank — no client of the worker
    // that could keep it busy — until the closed tab's download is settled.
    const opened = context.waitForEvent('page')
    await tab.evaluate(() => void window.open('about:blank', '_blank'))
    const next = await opened
    member.page = next

    const stall = await stallStore(tab, HELD_FROM)
    const download = await startDownload(tab)
    await standsStill(stall)

    let closed = false
    let fetchedAfterClose = 0
    const onRequest = (request: { url: () => string }) => {
      if (closed && isStoreGet(new URL(request.url()))) fetchedAfterClose += 1
    }
    context.on('request', onRequest)
    await tab.close()
    closed = true
    stall.release()

    const alone = await failureWithin(download.failure(), CLOSED_WATCH_MS)
    test.info().annotations.push({
      type: 'worker after the tab closed',
      description: alone === STILL_RUNNING
        ? `still running ${CLOSED_WATCH_MS / 1000} s after the close — stopped through ServiceWorker.stopAllWorkers`
        : `failed within ${CLOSED_WATCH_MS / 1000} s of the close, with "${alone}"`,
    })
    expect(alone, 'the closed tab\'s download did not complete').not.toBeNull()
    if (alone === STILL_RUNNING) {
      const cdp = await context.newCDPSession(next)
      await cdp.send('ServiceWorker.enable')
      await cdp.send('ServiceWorker.stopAllWorkers')
      await cdp.detach()
      const failed = await failureWithin(download.failure(), FAIL_WITHIN_MS)
      expect(failed, 'a stopped worker fails the download, never completes it').not.toBeNull()
      expect(failed, 'a stopped worker fails the download').not.toBe(STILL_RUNNING)
    }
    context.off('request', onRequest)
    expect(fetchedAfterClose, 'no frame was fetched after the tab closed').toBe(0)

    await openFolder(next)
    const again = await startDownload(next)
    expect(await again.failure()).toBeNull()
    const bytes = readFileSync(await again.path())
    expect({ size: bytes.length, sha256: sha256(bytes) }).toEqual(expected)
    expect(await next.evaluate(() => navigator.serviceWorker.controller?.scriptURL ?? null)).toBe(`${tenantOrigin()}/sw.js`)
  },
)

test(
  'a sign-out mid-download fails the download and leaves the tab on the sign-in page in a new document',
  { tag: '@uc.depot.member-files:download-returns-the-original-bytes' },
  async () => {
    const { page } = member
    await openFolder(page)
    const stall = await stallStore(page, HELD_FROM)
    const download = await startDownload(page)
    await standsStill(stall)
    // Gone with the document the ending replaces.
    await page.evaluate(() => void ((window as unknown as { __veyqOldDocument: boolean }).__veyqOldDocument = true))
    const sentBefore = stall.sent()

    await page.locator('.profile-button').click()
    await page.getByRole('menuitem', { name: 'Sign out', exact: true }).click()
    await expect(page).toHaveURL(/\/login(?:[?#]|$)/,{ timeout: 60_000 })
    await expect(page.getByRole('button', { name: 'Sign in', exact: true })).toBeVisible({ timeout: 60_000 })
    // Held until the old document is gone, so no frame could reach the download before the ending dropped it.
    stall.release()

    const failed = await failureWithin(download.failure(), FAIL_WITHIN_MS)
    expect(failed, 'the worker dropped the download: the browser marks it failed').not.toBeNull()
    expect(failed, 'the worker dropped the download: the browser marks it failed').not.toBe(STILL_RUNNING)
    expect(await page.evaluate(() => (window as unknown as { __veyqOldDocument?: boolean }).__veyqOldDocument ?? false),
      'the sign-in page is a new document').toBe(false)
    await sleep(2_000)
    expect(stall.sent(), 'no frame was fetched after the sign-out').toBe(sentBefore)
    await page.unrouteAll({ behavior: 'ignoreErrors' })
  },
)
