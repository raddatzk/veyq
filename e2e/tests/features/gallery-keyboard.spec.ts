// The windowed gallery in a real browser (ADR 0105, inbox #489): a Memory of more photos than three viewports hold mounts
// only the tiles near what is on screen, and is walked by keyboard alone — one tab stop, the arrows, Home and End, Enter
// into the lightbox and Escape back onto the tile of the photo it showed last. What a screen reader is told is read
// from the accessibility attributes the grid carries (`role="grid"`, the whole list's row count, each row's index);
// the reading itself by VoiceOver or NVDA is a hand pass and not part of this suite.
//
// The photos are the 1×1 fixture web/'s own tests read (`exif-sample.jpg`), uploaded under 120 names, so the tiles lay
// out square and the viewport is made small enough that 120 of them are several screens. It starts from the tenant whose
// owner the round trip activated (the `features` project). Every value typed is a canary (recordTyped). Labels are
// web/'s own (web/src/i18n/index.ts, en).
import { test, expect, type Locator, type Page } from '@playwright/test'
import { randomBytes } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { recordTyped } from '../../src/canaries'
import { newDevice, signInOwner, tenantOrigin, type Device } from '../../src/devices'

test.describe.configure({ mode: 'serial' })

const PHOTO = readFileSync(fileURLToPath(new URL('../../../web/src/features/memories/__fixtures__/exif-sample.jpg', import.meta.url)))
const COUNT = 120

const stamp = randomBytes(3).toString('hex')
const memoryName = `Herbstwanderung im Pfälzerwald ${stamp}`

let member: Device

const grid = (page: Page): Locator => page.getByRole('grid', { name: 'Memory gallery' })
const tile = (page: Page, n: number): Locator => page.getByRole('button', { name: `Open item ${n}`, exact: true })
const press = (page: Page, key: string) => page.keyboard.press(key)

test.beforeAll(async ({ browser }) => {
  member = await newDevice(browser)
  await member.page.setViewportSize({ width: 900, height: 600 })
})

test.afterEach(() => member?.confinement.assertConfined())

test.afterAll(async () => {
  await member?.context.close()
})

test(
  'a Memory of 120 photos mounts only the tiles near the viewport, and tells a screen reader the whole grid',
  { tag: ['@uc.memory.gallery:the-grid-mounts-only-the-window'] },
  async () => {
    const { page } = member
    await signInOwner(member)
    await page.getByRole('button', { name: 'New', exact: true }).click()
    await page.getByRole('menuitem', { name: 'New Memory' }).click()
    const dialog = page.getByRole('dialog', { name: 'Create a Memory' })
    await dialog.getByLabel('Name').fill(recordTyped(memoryName))
    await dialog.getByRole('button', { name: 'Create', exact: true }).click()
    await expect(dialog).toBeHidden()
    await page.getByRole('button', { name: memoryName, exact: true }).click()

    const chooser = page.waitForEvent('filechooser')
    await page.getByRole('button', { name: 'Upload', exact: true }).click()
    await (await chooser).setFiles(
      Array.from({ length: COUNT }, (_, i) => ({ name: recordTyped(`Weg ${stamp} ${i + 1}.jpg`), mimeType: 'image/jpeg', buffer: PHOTO })),
    )
    await expect(page.getByText(`${COUNT} items`, { exact: true })).toBeVisible({ timeout: 5 * 60_000 })

    // A new document: the grid is what the listing serves, not this tab's upload.
    await page.goto(tenantOrigin())
    await page.getByRole('button', { name: memoryName, exact: true }).click({ timeout: 60_000 })
    await expect(tile(page, 1).locator('img.media-tile-img')).toHaveAttribute('src', /^blob:/, { timeout: 60_000 })

    const rows = Number(await grid(page).getAttribute('aria-rowcount'))
    expect(rows, 'the row count of the WHOLE list').toBeGreaterThan(5)
    const mounted = await grid(page).getByRole('row').count()
    expect(mounted, 'only the rows near the viewport are in the DOM').toBeLessThan(rows)
    await expect(grid(page).getByRole('row').first()).toHaveAttribute('aria-rowindex', '1')
    expect(await page.locator('.media-tile').count(), 'fewer tiles than photos').toBeLessThan(COUNT)
  },
)

test(
  'the grid is one tab stop: the arrows, Home and End move through all 120 photos, and Tab leaves after one tile',
  { tag: ['@uc.memory.gallery:the-grid-is-one-tab-stop-with-arrow-keys'] },
  async () => {
    const { page } = member
    await tile(page, 1).focus()
    await press(page, 'ArrowRight')
    await expect(tile(page, 2)).toBeFocused()
    await press(page, 'ArrowLeft')
    await expect(tile(page, 1)).toBeFocused()
    await press(page, 'ArrowDown')
    await expect(grid(page).locator(':focus')).toHaveCount(1)
    await expect(tile(page, 1)).not.toBeFocused() // the nearest tile of the next row

    await press(page, 'End')
    await expect(tile(page, COUNT)).toBeFocused() // scrolled in and mounted, although it was not in the DOM before
    await expect(tile(page, COUNT)).toBeInViewport()
    await expect(tile(page, COUNT).locator('img.media-tile-img')).toHaveAttribute('src', /^blob:/, { timeout: 60_000 })
    await expect(tile(page, 1)).toHaveCount(0) // the top of the grid left the DOM
    await press(page, 'PageUp')
    await expect(tile(page, COUNT)).not.toBeFocused()
    await press(page, 'Home')
    await expect(tile(page, 1)).toBeFocused()

    // Tab walks the one tile's own controls (its open button, its mark) and then leaves the grid.
    let left = false
    for (let i = 0; i < 3 && !left; i++) {
      await press(page, 'Tab')
      left = (await grid(page).locator(':focus').count()) === 0
    }
    expect(left, 'Tab leaves the grid within the tile’s own controls').toBe(true)
    await press(page, 'Shift+Tab')
    await expect(grid(page).locator(':focus')).toHaveCount(1) // and Shift+Tab comes back to that one tile
  },
)

test(
  'Enter opens the focused photo, the lightbox takes the keys, and Escape returns to the tile of the photo it showed last',
  { tag: ['@uc.memory.gallery:focus-returns-from-the-lightbox'] },
  async () => {
    const { page } = member
    await tile(page, 1).focus()
    await press(page, 'ArrowRight')
    await press(page, 'ArrowRight')
    await expect(tile(page, 3)).toBeFocused()
    await press(page, 'Enter')
    const lightbox = page.getByRole('dialog', { name: memoryName })
    await expect(lightbox).toBeVisible()
    await expect(lightbox).toBeFocused()
    await expect(lightbox.getByText(`3 of ${COUNT}`)).toBeVisible()
    await press(page, 'ArrowRight') // the lightbox's: the next photo, not the grid's next tile
    await expect(lightbox.getByText(`4 of ${COUNT}`)).toBeVisible()
    await press(page, 'Escape')
    await expect(lightbox).toBeHidden()
    await expect(tile(page, 4)).toBeFocused()
  },
)
