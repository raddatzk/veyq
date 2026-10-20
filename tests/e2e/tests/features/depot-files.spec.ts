// Depot's file section clicked in a real browser (inbox #372 — every UI slice of cr-files-application reported "not
// verified in a browser"; the pane's walk of 2026-10-08 could not reach a real drop or the routed app's re-click). The
// tenant owner drops three files on a folder at once, uploads one whose name folds onto a file already there (it asks
// to replace and does), renames a file and downloads it under the new name, and deletes a file into the trash and
// takes it back with the toast's Undo. Each download is checked byte for byte; each listing after a reload, so what
// shows was read back from the server.
//
// The drop is the browser's own `drop` event on the level with a `DataTransfer` the page built — the DOM path a real drop
// takes, never an operating-system drag (no drag source exists in a headless browser). Downloads go through the Service
// Worker's route (the picker removed), which depot-download.spec.ts proves streams.
//
// It starts from the tenant whose owner the round trip activated (the `features` project). Labels are src/web/'s own
// (src/web/src/i18n/index.ts, en).
import { test, expect, type Page } from '@playwright/test'
import { randomBytes } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { recordTyped } from '../../src/canaries'
import { createFolder, downloadButton, pickFiles, sha256 } from '../../src/depot'
import { newDevice, signInOwner, tenantOrigin, type Device } from '../../src/devices'

test.describe.configure({ mode: 'serial' })

const stamp = randomBytes(3).toString('hex')
const folder = `Unterlagen vom Umzug ${stamp}`
const dropped = [1, 2, 3].map((n) => ({
  name: `Übergabeprotokoll Teil ${n} vom ${stamp}.bin`, bytes: new Uint8Array(randomBytes(300_000 + n * 1_000)),
}))
// The same name in another case: a file's slug folds by fileNameSlug (lower case), so it is the same file.
const replacement = { name: dropped[0].name.toUpperCase(), bytes: new Uint8Array(randomBytes(123_456)) }
const renamed = `Schlüsselübergabe unterschrieben ${stamp}.bin`

let member: Device

async function openFolder(page: Page): Promise<void> {
  await page.goto(tenantOrigin())
  await page.getByRole('button', { name: folder, exact: true }).click({ timeout: 60_000 })
  await expect(page.getByRole('heading', { name: folder })).toBeVisible()
}

/** A file's bytes, saved through the browser's download (the SW's route: no picker offered). */
async function downloaded(page: Page, name: string): Promise<{ name: string; sha256: string }> {
  await page.evaluate(() => void delete (window as unknown as { showSaveFilePicker?: unknown }).showSaveFilePicker)
  await expect(downloadButton(page, name)).toBeEnabled({ timeout: 60_000 })
  const started = page.waitForEvent('download')
  await downloadButton(page, name).click()
  const download = await started
  expect(await download.failure()).toBeNull()
  return { name: download.suggestedFilename(), sha256: sha256(readFileSync(await download.path())) }
}

/** A row's action behind its "More actions" menu. */
async function rowAction(page: Page, name: string, action: string): Promise<void> {
  await page.getByRole('button', { name: `More actions for “${name}”`, exact: true }).click()
  await page.getByRole('menuitem', { name: `${action} ${name}`, exact: true }).click()
}

const question = (page: Page, text: string) =>
  page.getByRole('alertdialog').or(page.getByRole('dialog')).filter({ hasText: text })

test.beforeAll(async ({ browser }) => {
  member = await newDevice(browser)
})

test.afterEach(() => member?.confinement.assertConfined())

test.afterAll(async () => {
  await member?.context.close()
})

test(
  'three files dropped on a folder at once are each uploaded into it',
  { tag: '@uc.depot.member-files:upload-attaches-one-sealed-blob' },
  async () => {
    const { page } = member
    await signInOwner(member)
    await createFolder(page, folder)
    for (const f of dropped) recordTyped(f.name)

    const transfer = await page.evaluateHandle((files) => {
      const data = new DataTransfer()
      for (const f of files) data.items.add(new File([Uint8Array.from(atob(f.base64), (c) => c.charCodeAt(0))], f.name))
      return data
    }, dropped.map((f) => ({ name: f.name, base64: Buffer.from(f.bytes).toString('base64') })))
    await page.locator('.veyq-level-view').dispatchEvent('drop', { dataTransfer: transfer })

    for (const f of dropped) await expect(downloadButton(page, f.name)).toBeEnabled({ timeout: 60_000 })
    await openFolder(page)
    for (const f of dropped) expect(await downloaded(page, f.name)).toEqual({ name: f.name, sha256: sha256(f.bytes) })
  },
)

test(
  'a picked file whose name folds onto one already there asks to replace it, and replaces its content',
  {
    tag: [
      '@uc.depot.member-files:a-file-name-is-folded-as-a-file-name',
      '@uc.depot.member-files:replace-is-a-compare-and-set-on-the-blob',
    ],
  },
  async () => {
    const { page } = member
    await openFolder(page)
    await pickFiles(page, [replacement])
    const ask = question(page, 'A file with this name is already here')
    await expect(ask).toBeVisible()
    await ask.getByRole('button', { name: 'Replace', exact: true }).click()
    await expect(ask).toBeHidden()
    // The replacement's size on the row: its upload completed (a reload before that would cancel it).
    await expect(page.getByText(/^File · 123\.5 kB · /)).toBeVisible({ timeout: 60_000 })

    // A new document: one file under its first name, holding the picked content.
    await openFolder(page)
    expect(await downloaded(page, dropped[0].name)).toEqual({ name: dropped[0].name, sha256: sha256(replacement.bytes) })
    await expect(page.getByRole('button', { name: `Download ${replacement.name}`, exact: true })).toHaveCount(0)
  },
)

test(
  'a renamed file downloads under its new name',
  { tag: '@uc.depot.member-files:metadata-is-the-blobs-own' },
  async () => {
    const { page } = member
    await openFolder(page)
    await rowAction(page, dropped[1].name, 'Rename')
    const dialog = page.getByRole('dialog', { name: 'Rename' })
    await expect(dialog.getByLabel('Name')).toBeFocused()
    await dialog.getByLabel('Name').fill(recordTyped(renamed))
    await dialog.getByRole('button', { name: 'Save', exact: true }).click()
    await expect(dialog).toBeHidden()

    await openFolder(page)
    // The node name wins over the name sealed in the blob's meta at upload.
    expect(await downloaded(page, renamed)).toEqual({ name: renamed, sha256: sha256(dropped[1].bytes) })
  },
)

test(
  'a deleted file goes to the trash after the question, and Undo brings it back',
  { tag: '@uc.depot.member-files:delete-goes-to-the-trash' },
  async () => {
    const { page } = member
    const { name, bytes } = dropped[2]
    await openFolder(page)
    await rowAction(page, name, 'Delete')
    const ask = question(page, 'moves to the trash')
    await ask.getByRole('button', { name: 'Delete', exact: true }).click()
    await expect(downloadButton(page, name)).toHaveCount(0)
    await expect(page.getByText(`“${name}” moved to the trash.`)).toBeVisible()
    await page.getByRole('button', { name: 'Undo', exact: true }).click()
    await expect(downloadButton(page, name)).toBeVisible({ timeout: 60_000 })

    await openFolder(page)
    expect(await downloaded(page, name)).toEqual({ name, sha256: sha256(bytes) })
  },
)
