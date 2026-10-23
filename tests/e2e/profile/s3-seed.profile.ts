// S3's seed (cr-profile-where-the-time-goes, decision 1): a Chapter of 1,000 photos, uploaded once per profile run
// before S3's first run and never profiled. Through the browser like any member's upload, so the seed comes from the
// e2e bootstrap's own instance and owner and not from the volume harness (the CR's Q2: `cr-volume-measurement-cax`
// deletes that harness). The photos are synthetic, rendered by `scripts/profile_hot_paths.py` into `fixtures/s3/`
// smaller than S2's, since what S3 reads is the grid and its thumbnails, not the originals. The names it made go to
// `s3-seed.json`, where S3 reads which Chapter to open.
import { test, expect } from '@playwright/test'
import { readdirSync, writeFileSync } from 'node:fs'
import { OUT, instance, memoryWithChapter, chapterCard, signIn, uploadsSettled } from './profiling'

const PHOTOS = `${OUT}/fixtures/s3`
const COUNT = 1000

test('S3 seed: a Chapter of 1,000 photos', async ({ browser }) => {
  test.setTimeout(3 * 60 * 60_000)
  const files = readdirSync(PHOTOS).filter((f) => f.endsWith('.jpg')).sort().map((f) => `${PHOTOS}/${f}`)
  expect(files, 'the driver rendered the photos').toHaveLength(COUNT)
  const context = await browser.newContext()
  const page = await context.newPage()
  await signIn(page, instance())

  const stamp = Date.now().toString(36)
  const seed = { memory: `Profile S3 ${stamp}`, chapter: `Profile S3 ${stamp} chapter` }
  await memoryWithChapter(page, seed.memory, seed.chapter)
  await chapterCard(page, seed.chapter).click()
  await expect(page.getByRole('heading', { level: 1, name: seed.chapter })).toBeVisible()
  const chooser = page.waitForEvent('filechooser')
  await page.getByRole('button', { name: 'Upload', exact: true }).click()
  await (await chooser).setFiles(files)
  await uploadsSettled(page, COUNT, 3 * 60 * 60_000)

  writeFileSync(`${OUT}/s3-seed.json`, JSON.stringify(seed, null, 2))
  await context.close()
})
