// S2 upload (cr-profile-where-the-time-goes): 50 phone-sized photos (about 4 MB each) uploaded into one
// Chapter. The photos are synthetic — `scripts/profile_hot_paths.py` renders them with ffmpeg into the run directory's
// `fixtures/s2/` before the first run — and are handed to the picker by path, so Playwright copies no bytes. Per run a
// fresh Memory with one Chapter is made before the window opens; the window runs from handing the 50 files to the
// picker to the state where the Chapter counts all 50 and no upload tile is left.
//
// The stage split is read afterwards, not timed here: decode and derivatives, sealing (AES-GCM) and
// hashing from the CPU profile and the trace (workers included); presign and the server's commit from the window's API
// requests; the wire (#261) from its store requests, the presigned PUTs. Attribute the profile's functions to their
// sources through a source map of the same build (`vite build --sourcemap` emits the same chunk hashes), never by the
// minified names that survive: the first reading counted only GHASH's `_updateBlock` (1.9 s) and missed `@noble`'s input
// copy (`Uint8Array.from`) under a two-letter name, so the main thread's frame AEAD was 12.4 s, not 1.9 s (#536).
// Since #536 the frames are sealed in core's frame worker: its thread is the one whose samples run `frames.worker-*.js`.
import { test, expect } from '@playwright/test'
import { readdirSync } from 'node:fs'
import { OUT, instance, memoryWithChapter, chapterCard, profiled, raiseResourceBuffer, signIn, spec, uploadsSettled } from './profiling'

const PHOTOS = `${OUT}/fixtures/s2`
const COUNT = 50

test('S2 upload: 50 phone-sized photos into one Chapter', async ({ browser }) => {
  // 200 MB sealed and sent; under a CPU throttle of 4 that is far past the config's ten minutes.
  test.setTimeout(45 * 60_000)
  const files = readdirSync(PHOTOS).filter((f) => f.endsWith('.jpg')).sort().map((f) => `${PHOTOS}/${f}`)
  expect(files, 'the driver rendered the photos').toHaveLength(COUNT)
  const context = await browser.newContext()
  await raiseResourceBuffer(context)
  const page = await context.newPage()
  const cdp = await context.newCDPSession(page)
  await signIn(page, instance())

  const { stem } = spec()
  const chapter = `Profile ${stem} chapter`
  await memoryWithChapter(page, `Profile ${stem} ${Date.now().toString(36)}`, chapter)
  await chapterCard(page, chapter).click()
  await expect(page.getByRole('heading', { level: 1, name: chapter })).toBeVisible()
  const chooser = page.waitForEvent('filechooser')
  await page.getByRole('button', { name: 'Upload', exact: true }).click()
  const picker = await chooser

  await profiled(browser, page, cdp, async () => {
    await picker.setFiles(files)
    await uploadsSettled(page, COUNT, 30 * 60_000)
  })
  // Its failed requests and error answers are the driver's to judge (`profile_hot_paths.py` `request_failures`).
  await context.close()
})
