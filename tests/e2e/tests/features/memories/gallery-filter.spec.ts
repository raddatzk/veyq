// A Memory's gallery narrowed by facts in a real browser (uc.memory.gallery step 8, cr-gallery-filter-by-facts): the
// gallery's own filter field with the search's chip grammar, a member's only. Three photos - two from the Munich fixture
// (taken 15 July 2021) and one from the Lisbon fixture (taken 3 May 2022), their EXIF - so the Memory spans two years and
// a day's chip names its year ("July 15, 2021", the app's locale). A day chip narrows the grid to the two of that day,
// the lightbox pages through those two alone, and removing the chip brings the third back. A read-only link's guest gets
// the member's page with no filter field - and, as every share page, no search field (uc.platform.search E3); the member's
// own page shows both first, so their absence on the guest's is not a renamed label.
//
// It starts from the tenant whose owner the round trip activated (the `memories` project). Every value typed is a canary
// (recordTyped). Labels are src/web/'s own (src/web/src/i18n/index.ts and src/web/src/features/memories/i18n.ts, en).
import { expect, type Locator, type Page } from '@playwright/test'
import { test } from '../../../src/cspViolations'
import { randomBytes } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { recordTyped } from '../../../src/canaries'
import { newDevice, signInOwner, type Device } from '../../../src/devices'
import { headerAction } from '../../../src/header'

test.describe.configure({ mode: 'serial' })

const MUNICH = readFileSync(fileURLToPath(new URL('../../../../../src/web/src/features/memories/__fixtures__/exif-sample.jpg', import.meta.url)))
const LISBON = readFileSync(fileURLToPath(new URL('../../../fixtures/exif-gps-lisbon.jpg', import.meta.url)))

const stamp = randomBytes(3).toString('hex')
const memoryName = `Sommerferien in Oberbayern ${stamp}`
const ofTheDay = [`Am Seeufer bei Herrsching ${stamp}.jpg`, `Biergarten am Kloster Andechs ${stamp}.jpg`]
const otherDay = `Straßenbahn in der Alfama ${stamp}.jpg`
const DAY = 'July 15, 2021'
const shareLabel = `Für die Wandergruppe ${stamp}`

let member: Device
let shareLink = ''

const exactly = (text: string) => new RegExp(`^${text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`)
const filterField = (page: Page): Locator => page.getByRole('combobox', { name: 'Filter the photos of this Memory' })
const searchField = (page: Page): Locator => page.getByRole('combobox', { name: 'Search all spaces' })
const tiles = (page: Page): Locator => page.getByRole('button', { name: /^Open item \d+$/ })
/** The open lightbox's File row: the original's name. */
const fileOf = (lightbox: Locator): Locator =>
  lightbox.locator('dl.media-lightbox-metadata dt', { hasText: exactly('File') }).locator('xpath=following-sibling::dd[1]')

test.beforeAll(async ({ browser }) => {
  member = await newDevice(browser)
})

test.afterEach(() => member?.confinement.assertConfined())

test.afterAll(async () => {
  await member?.context.close()
})

test(
  'a day chip narrows the grid to that day’s photos, the lightbox pages through them alone, and removing it brings the rest back',
  { tag: '@uc.memory.gallery:gallery-narrows-by-facts' },
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
    await (await chooser).setFiles([
      ...ofTheDay.map((name) => ({ name: recordTyped(name), mimeType: 'image/jpeg', buffer: MUNICH })),
      { name: recordTyped(otherDay), mimeType: 'image/jpeg', buffer: LISBON },
    ])
    await expect(page.getByText('3 items', { exact: true })).toBeVisible({ timeout: 60_000 })
    // A new document: the facts are the opened listing's, as the server serves it.
    await page.reload()
    await expect(page.getByRole('heading', { level: 1, name: memoryName })).toBeVisible({ timeout: 60_000 })
    await expect(tiles(page)).toHaveCount(3, { timeout: 60_000 })

    await filterField(page).click()
    await filterField(page).fill(recordTyped('July 15'))
    const day = page.getByRole('option').filter({ has: page.locator('.veyq-search__option-label', { hasText: exactly(DAY) }) })
    await expect(day.locator('.veyq-search__option-kind')).toHaveText('Day', { timeout: 60_000 })
    await day.click()
    await expect(page.locator('.gallery-fact-filter .veyq-tags-input__tag', { hasText: exactly(DAY) })).toBeVisible()
    await expect(tiles(page)).toHaveCount(2)

    // The lightbox pages through the narrowed set: two, both of that day, never the third.
    await page.keyboard.press('Escape') // the suggestions closed, the chip stays
    await tiles(page).first().click()
    const lightbox = page.getByRole('dialog', { name: memoryName })
    await expect(lightbox).toBeVisible()
    await expect(lightbox.getByText('1 of 2', { exact: true })).toBeVisible()
    await expect(fileOf(lightbox)).toHaveText(/\S/, { timeout: 60_000 })
    const first = ((await fileOf(lightbox).textContent()) ?? '').trim()
    await page.keyboard.press('ArrowRight')
    await expect(lightbox.getByText('2 of 2', { exact: true })).toBeVisible()
    await expect(fileOf(lightbox)).not.toHaveText(first, { timeout: 60_000 })
    await expect(fileOf(lightbox)).toHaveText(/\S/)
    const shown = [first, ((await fileOf(lightbox).textContent()) ?? '').trim()]
    expect(shown.sort(), 'the lightbox showed the two photos of that day').toEqual([...ofTheDay].sort())
    await page.keyboard.press('Escape')
    await expect(lightbox).toBeHidden()

    await page.getByRole('button', { name: `Remove ${DAY}`, exact: true }).click()
    await expect(tiles(page)).toHaveCount(3)
  },
)

test(
  'a read-only link’s guest gets the member’s page with no filter field and no search field',
  { tag: ['@uc.memory.gallery:gallery-narrows-by-facts', '@uc.platform.search:nothing-leaves-or-persists'] },
  async ({ browser }) => {
    const { page } = member
    // The member's page: both fields are there (the positive control for the guest's absences below).
    await expect(filterField(page)).toBeVisible()
    await expect(searchField(page)).toBeVisible()

    await headerAction(page, 'Share')
    const manage = page.getByRole('dialog', { name: 'Share this Memory' })
    await manage.getByRole('button', { name: 'New share' }).click()
    const create = page.getByRole('dialog', { name: 'New share' })
    await create.getByLabel('Name').fill(recordTyped(shareLabel))
    await expect(create.getByRole('button', { name: 'View only' })).toHaveAttribute('aria-pressed', 'true')
    await create.getByRole('button', { name: 'Create link' }).click()
    shareLink = await create.getByRole('textbox', { name: 'Public link' }).inputValue()
    await create.getByRole('button', { name: 'Done' }).click()

    const guest = await newDevice(browser)
    try {
      await guest.page.goto(shareLink)
      await expect(guest.page.getByText('Shared with you · read-only')).toBeVisible({ timeout: 60_000 })
      await expect(guest.page.getByRole('heading', { level: 1, name: memoryName })).toBeVisible()
      await expect(tiles(guest.page)).toHaveCount(3, { timeout: 60_000 })
      await expect(filterField(guest.page)).toHaveCount(0)
      await expect(searchField(guest.page)).toHaveCount(0)
      await expect(guest.page.getByTestId('search-field')).toHaveCount(0)
      guest.confinement.assertConfined()
    } finally {
      await guest.context.close()
    }
  },
)
