// S2v upload of videos (inbox #786): S2 with 20 phone-like clips (1080p, H.264 High at 16 Mbit/s with AAC, 6 s, about
// 12.5 MB each) instead of 50 photos — rendered by `scripts/profile_hot_paths.py` (`render_clips`) into the run
// directory's `fixtures/s2v/` and handed to the picker by path. Per run a fresh Memory with one Chapter; the window runs
// from handing the 20 files to the picker to the state where the Chapter counts all 20 and no upload tile is left.
//
// Beside S2's readings, its window records the page's media players (`media`, CDP's `Media` domain): each `<video>` the
// client makes — the upload tile's poster, the upload's own poster, and a size probe where the file's header does not name
// its size (inbox #793) — is one player with its own
// demuxer and decoders, so the most alive at once is the number of decoders the pick opened. And `extra.tilePictures`:
// how many upload tiles the grid had mounted and how many of them showed a picture, each time that changed (sampled every
// 100 ms in the page), with the largest picture's edge in pixels — what a tile's poster costs to show.
import { test, expect } from '@playwright/test'
import { readdirSync } from 'node:fs'
import { OUT, instance, memoryWithChapter, chapterCard, profiled, raiseResourceBuffer, signIn, spec, uploadsSettled } from './profiling'

const CLIPS = `${OUT}/fixtures/s2v`
const COUNT = 20

type Sampler = { series: [number, number, number][]; maxEdge: number; stop: () => void }

test('S2v upload: 20 phone-like clips into one Chapter', async ({ browser }) => {
  test.setTimeout(45 * 60_000)
  const files = readdirSync(CLIPS).filter((f) => f.endsWith('.mp4')).sort().map((f) => `${CLIPS}/${f}`)
  expect(files, 'the driver rendered the clips').toHaveLength(COUNT)
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

  await profiled(
    browser,
    page,
    cdp,
    async () => {
      await page.evaluate(() => {
        const series: [number, number, number][] = []
        const s: Sampler = { series, maxEdge: 0, stop: () => clearInterval(timer) }
        ;(window as unknown as { __veyqTilePictures: Sampler }).__veyqTilePictures = s
        const t0 = performance.now()
        let last = ''
        const timer = setInterval(() => {
          const tiles = document.querySelectorAll('.gallery-tile-optimistic').length
          const pictures = document.querySelectorAll<HTMLImageElement>('.gallery-tile-optimistic img')
          for (const img of pictures) s.maxEdge = Math.max(s.maxEdge, img.naturalWidth, img.naturalHeight)
          const key = `${tiles}/${pictures.length}`
          if (key !== last) series.push([Math.round(performance.now() - t0), tiles, pictures.length])
          last = key
        }, 100)
      })
      await picker.setFiles(files)
      await uploadsSettled(page, COUNT, 30 * 60_000)
    },
    {
      media: true,
      extra: async () => ({
        tilePictures: await page.evaluate(() => {
          const s = (window as unknown as { __veyqTilePictures: Sampler }).__veyqTilePictures
          s.stop()
          return { series: s.series, maxEdge: s.maxEdge }
        }),
      }),
    },
  )
  await context.close()
})
