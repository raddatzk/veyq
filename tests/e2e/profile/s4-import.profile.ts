// S4 import (inbox #557): the download `s4-seed.profile.ts` saved — a Memory of 50 phone-sized photos, 5.2 MB each —
// imported into a fresh folder at the space's top level, a new one per run (ADR 0082, uc.platform.export-import). The ZIP
// is handed to the picker by path, so Playwright copies no bytes; the dialog's dry run (the manifest, the caps, each
// payload present at its size) is read before the window opens. The window runs from the click on "Import" to the toast
// that says the Memory was imported: every payload's check (its SHA-256 against the manifest, the ZIP entry's CRC) and
// each original's upload — the tiers derived, sealed and sent — one after another, as the import runs them.
//
// What it is read for: where the main thread spends the import (the trace's own sampler; `@noble`'s SHA-256 and the CRC
// as their sources name them through a source map of the same build), the workers beside it, and the window's length.
import { test, expect } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { OUT, instance, profiled, raiseResourceBuffer, signIn, spec } from './profiling'

const ZIP = `${OUT}/fixtures/s4/import.zip`

test('S4 import: a download of a Memory of 50 photos into a folder', async ({ browser }) => {
  test.setTimeout(45 * 60_000)
  const { memory } = JSON.parse(readFileSync(`${OUT}/s4-seed.json`, 'utf8')) as { memory: string }
  const context = await browser.newContext()
  await raiseResourceBuffer(context)
  const page = await context.newPage()
  const cdp = await context.newCDPSession(page)
  await signIn(page, instance())

  const { stem } = spec()
  const folder = `Profile ${stem} ${Date.now().toString(36)}`
  await page.getByRole('button', { name: 'New', exact: true }).click()
  await page.getByRole('menuitem', { name: 'New folder' }).click()
  const create = page.getByRole('dialog', { name: 'Create a folder' })
  await create.getByLabel('Name').fill(folder)
  await create.getByRole('button', { name: 'Create', exact: true }).click()
  await expect(create).toBeHidden()
  await page.getByRole('button', { name: folder, exact: true }).click()
  await expect(page.getByRole('heading', { level: 1, name: folder })).toBeVisible()

  const chooser = page.waitForEvent('filechooser')
  await page.getByRole('button', { name: 'New', exact: true }).click()
  await page.getByRole('menuitem', { name: 'Upload files' }).click()
  await (await chooser).setFiles(ZIP)
  const dialog = page.getByRole('dialog', { name: 'Import', exact: true })
  await expect(dialog.getByTestId('import-summary')).toBeVisible({ timeout: 5 * 60_000 })
  const submit = dialog.getByTestId('import-submit')
  await expect(submit).toBeEnabled()

  await profiled(browser, page, cdp, async () => {
    await submit.click()
    // Polled in the page every 250 ms, so the wait costs the page no protocol round trip; an error the dialog shows ends it.
    await page.waitForFunction((name) => {
      const error = document.querySelector('[data-testid="import-error"]')
      if (error) throw new Error(`the import failed: ${error.textContent ?? '?'}`)
      return [...document.querySelectorAll('[role="region"]')].some((r) => r.textContent?.includes(`Imported “${name}”`))
    }, memory, { polling: 250, timeout: 30 * 60_000 })
  })
  // Its failed requests and error answers are the driver's to judge (`profile_hot_paths.py` `request_failures`).
  await context.close()
})
