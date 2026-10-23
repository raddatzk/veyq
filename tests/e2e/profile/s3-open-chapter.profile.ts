// S3 open a large Chapter (cr-profile-where-the-time-goes, decision 1): a new device opens the 1,000-photo Chapter
// `s3-seed.profile.ts` made (ADR 0105's grid: the tiles near the viewport mounted, thumbnails read nearest first, every
// listed seal opened once in the listing worker). The sign-in and the way to the Memory's Chapters tab happen before
// the window; the window runs from the click on the Chapter's card to *viewport complete* — every tile inside the
// viewport shows its decrypted thumbnail, with at least one tile there — the same state `tests/e2e/measure/volume.ts`
// waits for, defined here on its own so nothing here depends on that harness. Main thread against the listing worker is
// read from the trace; the time per opened thumbnail from the window's store requests (the thumbnails' GETs) and the
// trace.
import { test, expect, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { OUT, chapterCard, instance, profiled, raiseResourceBuffer, signIn } from './profiling'

interface Seed {
  memory: string
  chapter: string
}

/**
 * Started in the page right before the click: resolves once every tile an IntersectionObserver reports inside the
 * viewport shows its thumbnail (`.media-tile-img` with a `blob:` source, or a medium's icon). The observer answers
 * which tiles are in view, so the wait reads no layout per tile per tick — the harness must not cost the page what a
 * member's page would not spend.
 */
const armViewportComplete = (page: Page) => page.evaluate(() => {
  const w = window as unknown as { __viewportComplete: Promise<number> }
  w.__viewportComplete = new Promise<number>((resolve) => {
    const inView = new Set<Element>()
    const observed = new WeakSet<Element>()
    let lastReport = 0
    let newestObserved = 0
    const io = new IntersectionObserver((entries) => {
      lastReport = performance.now()
      for (const e of entries) if (e.isIntersecting) inView.add(e.target); else inView.delete(e.target)
    })
    const tick = () => {
      for (const tile of document.querySelectorAll('.media-tile')) {
        if (!observed.has(tile)) { observed.add(tile); io.observe(tile); newestObserved = performance.now() }
      }
      let view = 0
      let shown = 0
      for (const tile of inView) {
        if (!tile.isConnected) { inView.delete(tile); continue }
        view++
        if (tile.querySelector('.media-tile-img[src^="blob:"], .media-grid-icon')) shown++
      }
      if (view > 0 && shown === view && lastReport > newestObserved) { io.disconnect(); resolve(performance.now()) }
      else setTimeout(tick, 100)
    }
    tick()
  })
})

test('S3 open a large Chapter: a new device opens 1,000 photos', async ({ browser }) => {
  const seed = JSON.parse(readFileSync(`${OUT}/s3-seed.json`, 'utf8')) as Seed
  const context = await browser.newContext()
  await raiseResourceBuffer(context)
  const page = await context.newPage()
  const cdp = await context.newCDPSession(page)
  await signIn(page, instance())

  await page.getByRole('button', { name: seed.memory, exact: true }).click()
  await expect(page.getByRole('heading', { level: 1, name: seed.memory })).toBeVisible()
  await page.getByRole('tab', { name: /^Chapters/ }).click()
  await expect(chapterCard(page, seed.chapter)).toBeVisible()

  const reading = await profiled(browser, page, cdp, async () => {
    await armViewportComplete(page)
    await chapterCard(page, seed.chapter).click()
    await page.waitForFunction(() => (window as unknown as { __viewportComplete: Promise<number> }).__viewportComplete,
      undefined, { polling: 1000, timeout: 5 * 60_000 })
  })
  expect(reading.failedRequests, 'every request of the window reached the instance').toEqual([])
  await expect(page.locator('.veyq-node-header-subtitle')).toHaveText(/^1,?000 items$/)
  await context.close()
})
