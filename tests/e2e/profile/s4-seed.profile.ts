// S4's seed (inbox #557): the download S4 imports, made once per profile run before S4's first run and never profiled. A
// Memory of 50 phone-sized photos — the synthetic ones `scripts/profile_hot_paths.py` renders into `fixtures/s4/`, S2's
// size and quality — uploaded through the browser like any member's, then saved as a ZIP through its row's "Download",
// the way a member takes a Memory to another instance (ADR 0082). The ZIP goes to `fixtures/s4/import.zip` and the
// Memory's name to `s4-seed.json`, where S4 reads which toast ends its window.
import { test, expect } from '@playwright/test'
import { readdirSync, writeFileSync } from 'node:fs'
import { OUT, instance, signIn, uploadsSettled } from './profiling'
import { toast } from '../src/toast'

const PHOTOS = `${OUT}/fixtures/s4`
const COUNT = 50

test('S4 seed: a Memory of 50 photos, saved as a download', async ({ browser }) => {
  test.setTimeout(60 * 60_000)
  const files = readdirSync(PHOTOS).filter((f) => f.endsWith('.jpg')).sort().map((f) => `${PHOTOS}/${f}`)
  expect(files, 'the driver rendered the photos').toHaveLength(COUNT)
  const context = await browser.newContext({ acceptDownloads: true })
  const page = await context.newPage()
  const { tenantDomain } = instance()
  await signIn(page, instance())

  const memory = `Profile S4 ${Date.now().toString(36)}`
  await page.getByRole('button', { name: 'New', exact: true }).click()
  await page.getByRole('menuitem', { name: 'New Memory' }).click()
  const create = page.getByRole('dialog', { name: 'Create a Memory' })
  await create.getByLabel('Name').fill(memory)
  await create.getByRole('button', { name: 'Create', exact: true }).click()
  await expect(create).toBeHidden()
  await page.getByRole('button', { name: memory, exact: true }).click()
  await expect(page.getByRole('heading', { level: 1, name: memory })).toBeVisible()
  const chooser = page.waitForEvent('filechooser')
  await page.getByRole('button', { name: 'Upload', exact: true }).click()
  await (await chooser).setFiles(files)
  await uploadsSettled(page, COUNT, 30 * 60_000)

  // Back at the space's top level, where the Memory is a row with its "More actions" menu.
  await page.goto(`https://${tenantDomain}/`)
  await page.getByRole('button', { name: `More actions for “${memory}”`, exact: true }).click({ timeout: 60_000 })
  await page.getByRole('menuitem', { name: 'Download', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: `Download “${memory}”` })
  await expect(dialog.getByTestId('export-summary')).toHaveText(/^1 items, 50 files, /, { timeout: 60_000 })
  // No save picker: the download goes through the Service Worker's route, which Playwright receives as a download.
  await page.evaluate(() => void delete (window as unknown as { showSaveFilePicker?: unknown }).showSaveFilePicker)
  const started = page.waitForEvent('download', { timeout: 30 * 60_000 })
  await dialog.getByRole('button', { name: 'Save as ZIP…', exact: true }).click()
  const download = await started
  expect(await download.failure()).toBeNull()
  await download.saveAs(`${PHOTOS}/import.zip`)
  await expect(toast(page, 'Download saved')).toBeVisible({ timeout: 30 * 60_000 })

  writeFileSync(`${OUT}/s4-seed.json`, JSON.stringify({ memory }, null, 2))
  await context.close()
})
