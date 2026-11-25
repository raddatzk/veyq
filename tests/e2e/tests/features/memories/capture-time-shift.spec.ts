// A selection's capture time corrected through one of its photos, in a real browser (uc.memory.buckets § Correct a
// selection's capture time): three photos selected in the Contributions tab, the middle one's day moved on by one in the
// lightbox's editor, and "All 3, shifted by +1 d" chosen. After a reload — the server's sealed tiers, nothing this page
// held — each of the three is taken exactly one day later, reads as corrected, and the gallery keeps their order.
// Two photos from the Munich fixture (taken 15 July 2021) and one from the Lisbon fixture (3 May 2022), their EXIF.
//
// It starts from the tenant whose owner the round trip activated (the `memories` project). Every value typed is a canary
// (recordTyped); the day is stepped with the arrow key, so no time is typed. Labels are src/web/'s own
// (src/web/src/i18n/index.ts and src/web/src/features/memories/i18n.ts, en).
import { expect, type Locator, type Page } from '@playwright/test'
import { test } from '../../../src/cspViolations'
import { randomBytes } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { recordTyped } from '../../../src/canaries'
import { newDevice, signInOwner, type Device } from '../../../src/devices'
import { toast } from '../../../src/toast'

test.describe.configure({ mode: 'serial' })

const MUNICH = readFileSync(fileURLToPath(new URL('../../../../../src/web/src/features/memories/__fixtures__/exif-sample.jpg', import.meta.url)))
const LISBON = readFileSync(fileURLToPath(new URL('../../../fixtures/exif-gps-lisbon.jpg', import.meta.url)))

const stamp = randomBytes(3).toString('hex')
const memoryName = `Die Kamera ging falsch ${stamp}`
const photos = [
  { name: `Am Ammersee ${stamp}.jpg`, bytes: MUNICH },
  { name: `Kloster Andechs ${stamp}.jpg`, bytes: MUNICH },
  { name: `Alfama am Abend ${stamp}.jpg`, bytes: LISBON },
]
const DAY_MS = 24 * 3600_000

let member: Device

const exactly = (text: string) => new RegExp(`^${text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`)
const tiles = (scope: Page | Locator): Locator => scope.getByRole('button', { name: /^Open item \d+$/ })
/** The open lightbox's row `label`: its value. */
const rowOf = (lightbox: Locator, label: string): Locator =>
  lightbox.locator('dl.media-lightbox-metadata dt', { hasText: exactly(label) }).locator('xpath=following-sibling::dd[1]')

/** Every photo of the gallery in its order: its file name, when it was taken (as the panel says it) and its status. */
async function readGallery(page: Page): Promise<{ name: string; taken: string; corrected: boolean }[]> {
  const shown: { name: string; taken: string; corrected: boolean }[] = []
  const count = await tiles(page).count()
  for (let i = 0; i < count; i++) {
    await tiles(page).nth(i).click()
    const lightbox = page.getByRole('dialog', { name: memoryName })
    await expect(lightbox.getByText(`${i + 1} of ${count}`, { exact: true })).toBeVisible()
    await expect(rowOf(lightbox, 'File')).toHaveText(/\S/, { timeout: 60_000 })
    await expect(rowOf(lightbox, 'Taken')).toHaveText(/\S/)
    shown.push({
      name: ((await rowOf(lightbox, 'File').textContent()) ?? '').trim(),
      taken: ((await rowOf(lightbox, 'Taken').textContent()) ?? '').trim(),
      corrected: (await rowOf(lightbox, 'Status').count()) > 0 && ((await rowOf(lightbox, 'Status').textContent()) ?? '').trim() === 'Corrected',
    })
    await page.keyboard.press('Escape')
    await expect(lightbox).toBeHidden()
  }
  return shown
}

test.beforeAll(async ({ browser }) => {
  member = await newDevice(browser)
})

test.afterEach(() => member?.confinement.assertConfined())

test.afterAll(async () => {
  await member?.context.close()
})

test(
  'three photos selected and shifted through the middle one: after a reload each is a day later, corrected, in its order',
  { tag: '@uc.memory.buckets:selection-capture-time-shift' },
  async () => {
    test.setTimeout(240_000)
    const { page } = member
    await signInOwner(member)
    await page.getByRole('button', { name: 'New', exact: true }).click()
    await page.getByRole('menuitem', { name: 'New Memory' }).click()
    const dialog = page.getByRole('dialog', { name: 'Create a Memory' })
    await dialog.getByLabel('Name').fill(recordTyped(memoryName))
    await dialog.getByRole('button', { name: 'Create', exact: true }).click()
    await expect(dialog).toBeHidden()
    await page.getByRole('button', { name: memoryName, exact: true }).click()
    await expect(page.getByRole('heading', { level: 1, name: memoryName })).toBeVisible()

    const chooser = page.waitForEvent('filechooser')
    await page.getByRole('button', { name: 'Upload', exact: true }).click()
    await (await chooser).setFiles(photos.map((p) => ({ name: recordTyped(p.name), mimeType: 'image/jpeg', buffer: p.bytes })))
    await expect(page.getByText('3 items', { exact: true })).toBeVisible({ timeout: 60_000 })
    await page.reload()
    await expect(page.getByRole('heading', { level: 1, name: memoryName })).toBeVisible({ timeout: 60_000 })
    await expect(tiles(page)).toHaveCount(3, { timeout: 60_000 })
    const before = await readGallery(page)
    expect(before.map((p) => p.corrected), 'none corrected yet').toEqual([false, false, false])

    // The Contributions tab: all three are the owner's own, in its primary bucket. Select them, open the middle one.
    await page.getByRole('tab', { name: 'Contributions' }).click()
    const panel = page.locator('.veyq-bucket-organizer')
    for (const n of [1, 2, 3]) await panel.getByRole('checkbox', { name: `Open item ${n}`, exact: true }).check()
    await expect(panel.getByText('3 of 3 selected', { exact: true })).toBeVisible()
    await panel.getByRole('button', { name: 'Open item 2', exact: true }).click()
    const lightbox = page.getByRole('dialog', { name: memoryName })
    await expect(lightbox).toBeVisible()
    await expect(rowOf(lightbox, 'Taken')).toHaveText(/\S/, { timeout: 60_000 })

    // One day on, by the day segment's arrow key — the camera's clock was a day behind.
    await lightbox.getByRole('button', { name: 'Edit capture time' }).click()
    await lightbox.getByRole('spinbutton', { name: 'Day' }).focus()
    await page.keyboard.press('ArrowUp')
    await lightbox.getByRole('button', { name: 'Save', exact: true }).click()
    await expect(lightbox.getByText('3 items are selected.', { exact: true })).toBeVisible()
    await lightbox.getByRole('button', { name: 'All 3, shifted by +1 d', exact: true }).click()
    await expect(lightbox.locator('#captured-at-input')).toHaveCount(0, { timeout: 60_000 }) // the editor closed: every write settled
    await expect(rowOf(lightbox, 'Status')).toHaveText('Corrected')
    await expect(toast(page, 'Capture time shifted for 3 items')).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(lightbox).toBeHidden()

    // A new document: what the server holds, opened afresh.
    await page.reload()
    await expect(page.getByRole('heading', { level: 1, name: memoryName })).toBeVisible({ timeout: 60_000 })
    await expect(tiles(page)).toHaveCount(3, { timeout: 60_000 })
    const after = await readGallery(page)
    expect(after.map((p) => p.name), 'the gallery keeps their order').toEqual(before.map((p) => p.name))
    expect(after.map((p) => p.corrected), 'all three corrected').toEqual([true, true, true])
    for (const [i, p] of after.entries()) {
      // the panel's date as the browser formats it, read back in the same runtime and zone
      const at = (taken: string) => Date.parse(taken.replace(/\u202f/g, ' ')) // ICU's narrow space before AM/PM
      expect(at(p.taken), `${p.name}: a date`).not.toBeNaN()
      expect(at(p.taken) - at(before[i].taken), `${p.name}: one day later`).toBe(DAY_MS)
    }
  },
)
