// The search in a real browser (uc.platform.search, ADR 0125): the top bar's field over every space of the tenant, driven
// by the tenant owner the round trip activated (the `platform` project). The search itself is client-only, so what a run
// can see of it is what the page draws: one list after *Filter by*, with no heading of its own (the group named
// "Results" for a screen reader); each chip a predicate over every document; each row saying where it lies; the part of a
// name the text matched in bold. The data:
//
//  - in the shared space, the Memory "Gletscherwanderung" with two photos (Munich, 15 July 2021; Lisbon, 3 May 2022 - the
//    fixtures' EXIF), so its date range spans both years, the owner a participant ("I was there", the create dialog's
//    default) and its place NAMED BY HAND: the member's map services are off (a new browser's default), so the place
//    editor is the name field, with the offer to turn them on where the map would stand (uc.memory.place A6) - no map, no
//    request to the tile host the instance names, which a hermetic run cannot reach (`src/egress.ts`);
//  - beside it "Gletscherwanderung ohne Begleitung", no photo, no place, nobody listed: what every chip must leave out;
//  - "Lissabonreise" with the Lisbon photo, in the shared space and, imported, in the personal space: the owner downloads
//    it as a ZIP, and the copy carries a place for the photo. A photo's place is otherwise only ever reverse-geocoded (the
//    lightbox, behind the map consent), which a hermetic run cannot reach either; the import restores a download's place
//    names (`exportImport.ts` `restoredFacts`), so the place is written into the download's manifest here - a place the
//    member's import sealed, not one typed into an editor. Two nodes of one name, one per space: two *In:* suggestions;
//  - two empty Memories "Kältekammer Nord" and "Kältekammer Süd", for the sticky highlight (the owner's Ken/Kevin example).
//    Not "Ken" and "Kevin": what is typed is a canary, and "Ke" is two characters of the base64 alphabet, which the
//    recorder refuses (`src/canaries.ts`); and the store sweep matches raw bytes, so a canary as short as "Kä" (three
//    UTF-8 bytes) would turn up in megabytes of ciphertext by chance. The query starts at "Kältek", seven bytes.
//
// A Memory of "another space" is searched for from the personal space, and a photo of the personal space from the shared
// one; choosing a result there switches the space. Every value typed is a canary (recordTyped): the names, the places,
// and every query - the search sends none of them anywhere, so the sweep must find none. The year and month typed travel
// inside a longer query, which is the canary. Labels are src/web/'s own (src/web/src/i18n/index.ts, the Memories catalog
// src/web/src/features/memories/i18n.ts, en).
//
// That a share page has no search field is driven with the gallery filter's guest (memories/gallery-filter.spec.ts),
// which opens one link for both.
import { expect, type Locator, type Page } from '@playwright/test'
import { test } from '../../../src/cspViolations'
import { randomBytes } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { recordTyped } from '../../../src/canaries'
import { newDevice, openSettings, signInOwner, tenantOrigin, type Device } from '../../../src/devices'
import { toast } from '../../../src/toast'
import { readZip, writeZip } from '../../../src/zip'

test.describe.configure({ mode: 'serial' })

// 48°8' N 11°34' E, taken 2021-07-15 18:04:05 / 38°42' N 9°8' W, taken 2022-05-03 10:20:30 (their EXIF; export-import.spec.ts)
const MUNICH = readFileSync(fileURLToPath(new URL('../../../../../src/web/src/features/memories/__fixtures__/exif-sample.jpg', import.meta.url)))
const LISBON = readFileSync(fileURLToPath(new URL('../../../fixtures/exif-gps-lisbon.jpg', import.meta.url)))

const stamp = randomBytes(3).toString('hex')
const glacier = `Gletscherwanderung ${stamp}`
const glacierAlone = `Gletscherwanderung ohne Begleitung ${stamp}`
const glacierPhotos = [
  { name: `Am Gletschertor im Morgenlicht ${stamp}.jpg`, bytes: MUNICH },
  { name: `Rückfahrt durch die Baixa ${stamp}.jpg`, bytes: LISBON },
]
const glacierPlace = `Konkordiaplatz am Aletschgletscher ${stamp}`
const lisbon = `Lissabonreise ${stamp}`
const tram = `Straßenbahn am Tejo ${stamp}.jpg`
const tramPlace = `Praça do Comércio ${stamp}`
const north = `Kältekammer Nord ${stamp}` // collates before ...
const south = `Kältekammer Süd ${stamp}` // ... this one; both start with "Kältekammer "

let member: Device
/** The owner as the directory names them (their first and last name, Your profile) and their first name alone. */
let owner: { display: string; first: string }

// --- the field -----------------------------------------------------------------------------------------------------------

const exactly = (text: string) => new RegExp(`^${text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`)
const field = (page: Page): Locator => page.getByTestId('search-field').getByRole('combobox', { name: 'Search all spaces' })
/** The chip suggestions, under their heading. */
const filterBy = (page: Page): Locator => page.getByRole('group', { name: 'Filter by', exact: true })
/** The one list of results: no heading, its name for a screen reader only. */
const results = (page: Page): Locator => page.getByRole('group', { name: 'Results', exact: true })
/** A chip suggestion: its label and its kind's word ("Year", "Place", "In", ...). */
const suggestion = (page: Page, label: string, kind: string): Locator =>
  filterBy(page).getByRole('option')
    .filter({ has: page.locator('.veyq-search__option-label', { hasText: exactly(label) }) })
    .filter({ has: page.locator('.veyq-search__option-kind', { hasText: exactly(kind) }) })
/** A result row, by the name it draws. */
const result = (page: Page, name: string): Locator =>
  results(page).getByRole('option').filter({ has: page.locator('.veyq-search__result-name', { hasText: exactly(name) }) })
/** What a row says under its name: where it lies. */
const whereOf = (row: Locator): Locator => row.locator('.veyq-search__result-meta')
/** Where the imported photo lies, as its row says it: its space first, its Memory's path, then the day it was taken. */
const photoWhere = new RegExp(`^Personal · ${lisbon} · May 3, 2022$`)
/** The entry the list highlights - the one Enter takes. */
const highlighted = (page: Page): Locator => page.locator('.veyq-search__list [role="option"][data-highlighted]')

/** Type [text] into the field of a fresh document (a new document starts with no chip and no text). */
async function search(page: Page, text: string): Promise<void> {
  await field(page).click()
  await field(page).fill(recordTyped(text))
}

/** The field's chip, as it stands in the field once chosen. */
const chip = (page: Page, label: string): Locator =>
  page.getByTestId('search-field').locator('.veyq-tags-input__tag', { hasText: exactly(label) })

// --- the spaces ----------------------------------------------------------------------------------------------------------

/** The shared space's top level, a new document. */
async function openShared(page: Page): Promise<void> {
  await page.goto(tenantOrigin())
  await expect(page.getByRole('button', { name: 'New', exact: true })).toBeVisible({ timeout: 60_000 })
}

/** The personal space's top level, entered from the sidebar of a new document. */
async function openPersonal(page: Page): Promise<void> {
  await openShared(page)
  await page.getByRole('navigation', { name: 'Spaces' }).getByRole('link', { name: 'Personal', exact: true }).click()
  await expect.poll(() => new URL(page.url()).hash, { message: 'the personal space is open' }).toMatch(/^#~/)
  await expect(page.getByRole('button', { name: 'New', exact: true })).toBeVisible({ timeout: 60_000 })
}

/** A Memory at the open level; [wasThere] false unticks the create dialog's "I was there" (ticked by default). */
async function createMemory(page: Page, name: string, wasThere = true): Promise<void> {
  await page.getByRole('button', { name: 'New', exact: true }).click()
  await page.getByRole('menuitem', { name: 'New Memory' }).click()
  const dialog = page.getByRole('dialog', { name: 'Create a Memory' })
  await dialog.getByLabel('Name').fill(recordTyped(name))
  const there = dialog.locator('#was-there')
  await expect(there).toHaveAttribute('aria-checked', 'true')
  if (!wasThere) {
    await there.click()
    await expect(there).toHaveAttribute('aria-checked', 'false')
  }
  await dialog.getByRole('button', { name: 'Create', exact: true }).click()
  await expect(dialog).toBeHidden()
}

/** A row of the open level opened, and the page's one heading its name. */
async function openRow(page: Page, name: string): Promise<void> {
  await page.getByRole('button', { name, exact: true }).click({ timeout: 60_000 })
  await expect(page.getByRole('heading', { level: 1, name })).toBeVisible()
}

/** Photos uploaded into the open Memory, every one listed once the upload is through. */
async function upload(page: Page, photos: { name: string; bytes: Buffer }[]): Promise<void> {
  const chooser = page.waitForEvent('filechooser')
  await page.getByRole('button', { name: 'Upload', exact: true }).click()
  await (await chooser).setFiles(photos.map((p) => ({ name: recordTyped(p.name), mimeType: 'image/jpeg', buffer: p.bytes })))
  const count = photos.length === 1 ? '1 item' : `${photos.length} items`
  await expect(page.getByText(count, { exact: true })).toBeVisible({ timeout: 60_000 })
}

// --- a download, rewritten ----------------------------------------------------------------------------------------------

const MANIFEST = '.veyq/manifest.json'
type Medium = { name?: string; location?: { lat?: number; lon?: number; place?: string } }
type Manifest = { root: { kind: string; name: string; content?: { media?: Medium[] } } }

/** "Download" from a row's ⋯ menu of the open level, saved through the Service Worker's route (no save picker). */
async function downloadAsZip(page: Page, name: string): Promise<Buffer> {
  await page.getByRole('button', { name: `More actions for “${name}”`, exact: true }).click({ timeout: 60_000 })
  await page.getByRole('menuitem', { name: 'Download', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: `Download “${name}”` })
  await expect(dialog.getByTestId('export-summary')).toHaveText(/^1 items, 1 files, /, { timeout: 60_000 })
  await page.evaluate(() => void delete (window as unknown as { showSaveFilePicker?: unknown }).showSaveFilePicker)
  const started = page.waitForEvent('download')
  await dialog.getByRole('button', { name: 'Save as ZIP…', exact: true }).click()
  const download = await started
  expect(await download.failure()).toBeNull()
  const zip = readFileSync(await download.path())
  await expect(dialog).toBeHidden({ timeout: 60_000 })
  return zip
}

/** The download with one medium given a place, as a download of a geocoded photo carries it (`location.place`). */
function withPhotoPlace(zip: Buffer, medium: string, place: string): Buffer {
  const entries = new Map(readZip(zip))
  const manifest = JSON.parse(entries.get(MANIFEST)!.toString('utf8')) as Manifest
  expect(manifest.root).toMatchObject({ kind: 'MEMORY', name: lisbon })
  const target = manifest.root.content?.media?.find((m) => m.name === medium)
  expect(target, `${medium} is in the download`).toBeDefined()
  target!.location = { ...target!.location, place: recordTyped(place) }
  entries.set(MANIFEST, Buffer.from(JSON.stringify(manifest)))
  return writeZip(entries)
}

test.beforeAll(async ({ browser }) => {
  member = await newDevice(browser)
})

test.afterEach(() => member?.confinement.assertConfined())

test.afterAll(async () => {
  await member?.context.close()
})

test(
  'a Memory of another space is found by its name, the match drawn bold, and opens in its own space',
  {
    tag: [
      '@uc.platform.search:every-space-of-the-tenant',
      '@uc.platform.search:chips-and-text',
      '@uc.platform.search:one-list-ordered-by-match-and-place',
      '@uc.platform.search:open-a-result',
    ],
  },
  async () => {
    test.setTimeout(240_000)
    const { page } = member
    await signInOwner(member)
    await createMemory(page, glacier)
    await openRow(page, glacier)
    await upload(page, glacierPhotos)
    await openShared(page)
    await createMemory(page, glacierAlone, false)
    await expect(page.getByRole('button', { name: glacierAlone, exact: true })).toBeVisible()

    // Searched from the personal space: both Memories lie in another space, and each row says which (its space first).
    await openPersonal(page)
    await search(page, 'Gletscherwanderung')
    for (const name of [glacier, glacierAlone]) {
      await expect(result(page, name)).toBeVisible({ timeout: 60_000 })
      await expect(whereOf(result(page, name))).toHaveText(/\S/)
      await expect(whereOf(result(page, name))).not.toHaveText(/^Personal\b/)
    }
    // The suggestions stand under their heading - here the In chip of the Memory that holds photos - and the one list
    // after them has none: Filter by is the list's only heading.
    await expect(suggestion(page, glacier, 'In')).toBeVisible({ timeout: 60_000 })
    await expect(page.locator('.veyq-search__list .veyq-tags-input__group-label')).toHaveText(['Filter by'])
    // The part of the name the text matched, bold: a strong element over the name's own characters.
    await expect(result(page, glacier).locator('strong.veyq-search__match')).toHaveText('Gletscherwanderung')

    await result(page, glacier).click()
    await expect(page.getByRole('heading', { level: 1, name: glacier })).toBeVisible({ timeout: 60_000 })
    expect(new URL(page.url()).hash, 'the Memory opened in the shared space, not the personal one').not.toMatch(/^#~/)
    await expect(page.getByRole('button', { name: 'Open item 2', exact: true })).toBeVisible({ timeout: 60_000 })
  },
)

test(
  'a year chip, a month chip and a person chip find the Memory of another space, each a predicate its twin does not meet',
  { tag: ['@uc.platform.search:chips-and-text', '@uc.platform.search:a-chip-is-a-predicate-over-every-document'] },
  async () => {
    const { page } = member
    // The owner as the directory names them: Your profile's first and last name.
    await openSettings(page, 'Your profile')
    const first = page.getByLabel('First name', { exact: true })
    await expect(first).not.toHaveValue('', { timeout: 60_000 })
    const firstName = (await first.inputValue()).trim()
    const lastName = (await page.getByLabel('Last name', { exact: true }).inputValue()).trim()
    owner = { display: `${firstName} ${lastName}`.trim(), first: firstName }

    // A year: the Memory's range (July 2021 - May 2022) overlaps it; the twin has no date at all.
    await openPersonal(page)
    await search(page, 'Gletscherwanderung 2021')
    await suggestion(page, '2021', 'Year').click({ timeout: 60_000 })
    await expect(chip(page, '2021')).toBeVisible()
    await expect(field(page)).toHaveValue(/^Gletscherwanderung\s*$/)
    await expect(result(page, glacier)).toBeVisible({ timeout: 60_000 })
    await expect(result(page, glacierAlone)).toHaveCount(0)
    // a person has no year: the year chip lists nobody (a predicate over every document, people included)
    await expect(results(page).locator('.veyq-search__result-avatar')).toHaveCount(0)

    // A month with its year: May 2022 is inside the range.
    await openPersonal(page)
    await search(page, 'Gletscherwanderung May 2022')
    await suggestion(page, 'May 2022', 'Month').click({ timeout: 60_000 })
    await expect(chip(page, 'May 2022')).toBeVisible()
    await expect(result(page, glacier)).toBeVisible({ timeout: 60_000 })
    await expect(result(page, glacier).locator('.veyq-search__result-chip')).toHaveText(['May 2022'])
    await expect(result(page, glacierAlone)).toHaveCount(0)

    // A person: the owner was there; the twin lists nobody. The chip means what they take part in - never their profile.
    await openPersonal(page)
    await search(page, owner.first)
    await suggestion(page, owner.display, 'Person').click({ timeout: 60_000 })
    await expect(chip(page, owner.display)).toBeVisible()
    await field(page).fill(recordTyped('Gletscherwanderung'))
    await expect(result(page, glacier)).toBeVisible({ timeout: 60_000 })
    await expect(result(page, glacierAlone)).toHaveCount(0)
    await expect(results(page).locator('.veyq-search__result-avatar')).toHaveCount(0)
  },
)

test(
  'a place named by hand with the map services off is a place chip that finds the Memory from another space',
  { tag: ['@uc.platform.search:chips-and-text', '@uc.platform.search:memories-contributes-through-the-registry'] },
  async () => {
    const { page } = member
    // The member's map services are off (this browser never turned them on): the place editor opens at once as the name
    // field, the offer to turn them on where the map would stand - no map, so no tile is asked for (uc.memory.place A6).
    await openShared(page)
    await openRow(page, glacier)
    await page.getByRole('button', { name: 'Set location', exact: true }).click({ timeout: 60_000 })
    const editor = page.getByRole('dialog', { name: 'Location' })
    await expect(editor.getByRole('textbox', { name: 'Name' })).toBeVisible()
    await expect(editor.getByRole('button', { name: 'Turn map services on', exact: true })).toBeVisible()
    await expect(editor.locator('.leaflet-container')).toHaveCount(0)
    await editor.getByRole('textbox', { name: 'Name' }).fill(recordTyped(glacierPlace))
    await editor.getByRole('button', { name: 'Save', exact: true }).click()
    await expect(editor).toBeHidden()
    await expect(page.getByRole('button', { name: 'Edit location', exact: true })).toBeVisible({ timeout: 60_000 })
    await expect(page.locator('header.veyq-node-header')).toContainText(glacierPlace)

    await openPersonal(page)
    await search(page, 'Konkordiaplatz')
    await suggestion(page, glacierPlace, 'Place').click({ timeout: 60_000 })
    await expect(chip(page, glacierPlace)).toBeVisible()
    await expect(result(page, glacier)).toBeVisible({ timeout: 60_000 })
    await expect(result(page, glacier).locator('.veyq-search__result-chip')).toHaveText([glacierPlace])
    await expect(result(page, glacierAlone)).toHaveCount(0)
  },
)

test(
  'a photo of the personal space is found by its place and opens in its Memory’s lightbox; two Memories of one name are two In chips',
  {
    tag: [
      '@uc.platform.search:photos-of-every-space',
      '@uc.platform.search:in-a-node',
      '@uc.platform.search:open-a-result',
      '@uc.platform.search:one-list-ordered-by-match-and-place',
    ],
  },
  async () => {
    test.setTimeout(240_000)
    const { page } = member
    // "Lissabonreise" in the shared space, downloaded, and imported into the personal space with the photo's place.
    await openShared(page)
    await createMemory(page, lisbon)
    await openRow(page, lisbon)
    await upload(page, [{ name: tram, bytes: LISBON }])
    await openShared(page)
    const zip = withPhotoPlace(await downloadAsZip(page, lisbon), tram, tramPlace)
    await openPersonal(page)
    const chooser = page.waitForEvent('filechooser')
    await page.getByRole('button', { name: 'New', exact: true }).click()
    await page.getByRole('menuitem', { name: 'Upload files' }).click()
    await (await chooser).setFiles({ name: recordTyped(`${lisbon}.zip`), mimeType: 'application/zip', buffer: zip })
    const importing = page.getByRole('dialog', { name: 'Import', exact: true })
    await importing.getByRole('button', { name: 'Import', exact: true }).click({ timeout: 60_000 })
    await expect(toast(page, `Imported “${lisbon}”`)).toBeVisible({ timeout: 90_000 })

    // From the shared space: one In suggestion per node of that name, each saying where it lies.
    await openShared(page)
    await search(page, 'Lissabonreise')
    const ins = filterBy(page).getByRole('option')
      .filter({ has: page.locator('.veyq-search__option-label', { hasText: exactly(lisbon) }) })
      .filter({ has: page.locator('.veyq-search__option-kind', { hasText: exactly('In') }) })
    await expect(ins).toHaveCount(2, { timeout: 60_000 })
    const personalIn = ins.filter({ has: page.locator('.veyq-search__result-meta', { hasText: /^Personal\b/ }) })
    await expect(personalIn).toHaveCount(1)
    // The personal one narrows to ITS photo: the shared Memory's photo of the same name is not below it.
    await personalIn.click()
    await expect(result(page, tram)).toHaveCount(1, { timeout: 60_000 })
    await expect(whereOf(result(page, tram))).toHaveText(photoWhere)

    // The place the import sealed: a place chip, the one photo carrying it, opened in its Memory's lightbox.
    await openShared(page)
    await search(page, 'Praça')
    await suggestion(page, tramPlace, 'Place').click({ timeout: 60_000 })
    await expect(result(page, tram)).toHaveCount(1, { timeout: 60_000 })
    await expect(whereOf(result(page, tram))).toHaveText(photoWhere)
    await result(page, tram).click()
    const lightbox = page.getByRole('dialog', { name: lisbon })
    await expect(lightbox).toBeVisible({ timeout: 60_000 })
    const file = lightbox.locator('dl.media-lightbox-metadata dt', { hasText: exactly('File') }).locator('xpath=following-sibling::dd[1]')
    await expect(file).toHaveText(tram, { timeout: 60_000 })
    expect(new URL(page.url()).hash, 'the photo opened in the personal space').toMatch(/^#~/)
  },
)

test(
  'a member is a result of the one list and opens their profile, while the same word offers them as a person chip',
  { tag: '@uc.platform.search:people-are-results' },
  async () => {
    const { page } = member
    await openShared(page)
    await search(page, owner.first)
    await expect(suggestion(page, owner.display, 'Person')).toBeVisible({ timeout: 60_000 })
    const person = result(page, owner.display).filter({ has: page.locator('.veyq-search__result-avatar') })
    await expect(person).toHaveCount(1, { timeout: 60_000 })
    await expect(whereOf(person)).toHaveText(/^~/)
    await person.click()
    await expect.poll(() => new URL(page.url()).hash, { message: 'the profile page' }).toMatch(/^#people\//)
    await expect(page.getByRole('heading', { level: 1, name: owner.display })).toBeVisible({ timeout: 60_000 })
  },
)

test(
  'a settings section is a result, found by a word of its own, and opens on the settings page',
  { tag: '@uc.platform.search:settings-are-the-rails-sections' },
  async () => {
    const { page } = member
    await openShared(page)
    // "dark mode" is one of the appearance section's own words (settings.words.appearance): matched from a word's start
    // and from the third character, and drawn after the row's heading as the reason it is listed
    // (cr-search-visible-reasons). (Not "mail server", the mail section's: a canary that generic could sit in a relay's
    // log line.)
    await search(page, 'dark mode')
    const section = result(page, 'Appearance and language')
    await expect(section).toBeVisible({ timeout: 60_000 })
    await expect(whereOf(section)).toHaveText('Personal · dark mode')
    await section.click()
    await expect(page).toHaveURL(/\/settings\/appearance$/)
    const rail = page.getByRole('navigation', { name: 'Settings' })
    await expect(rail.getByRole('button', { name: 'Appearance and language', exact: true })).toHaveClass(/is-active/)
  },
)

test(
  'the highlight the member moved stays on its entry while they type on, falls back when it drops out, and comes back with it',
  { tag: '@uc.platform.search:chips-and-text' },
  async () => {
    const { page } = member
    await openShared(page)
    await createMemory(page, north, false)
    await expect(page.getByRole('button', { name: north, exact: true })).toBeVisible()
    await createMemory(page, south, false)
    await expect(page.getByRole('button', { name: south, exact: true })).toBeVisible()

    await openShared(page)
    await field(page).click()
    // What the field holds along the way, each a canary (every longer one contains "Kältek", the first).
    for (const typed of ['Kältek', 'Kälteka', 'Kältekammer ', 'Kältekammer N']) recordTyped(typed)
    await field(page).fill('Kältek')
    await expect(result(page, north)).toBeVisible({ timeout: 60_000 })
    await expect(result(page, south)).toBeVisible()
    await expect(highlighted(page)).toHaveCount(1)

    // ↓ until the highlight is on "Kältekammer Süd", whatever else the tenant holds.
    for (let i = 0; i < 20 && (await result(page, south).getAttribute('data-highlighted')) === null; i++) {
      await page.keyboard.press('ArrowDown')
    }
    await expect(result(page, south)).toHaveAttribute('data-highlighted', '')

    // Typing on, key by key: both are still listed, "Nord" before "Süd" - and the highlight stays where the member put it.
    // Without the remembered entry the layer puts it back on the list's first (the control: that first entry is another).
    await page.keyboard.type('a')
    await expect(field(page)).toHaveValue('Kälteka')
    await expect(result(page, north)).toBeVisible()
    await expect(result(page, south)).toHaveAttribute('data-highlighted', '')
    await expect(page.locator('.veyq-search__list [role="option"]').first()).not.toHaveAttribute('data-highlighted', '')
    const order = await results(page).locator('.veyq-search__result-name').allTextContents()
    expect(order.indexOf(north), '"Nord" is listed before "Süd"').toBeGreaterThanOrEqual(0)
    expect(order.indexOf(north), '"Nord" is listed before "Süd"').toBeLessThan(order.indexOf(south))

    // It drops out ("Kältekammer N"): the first entry of its area - the results - is highlighted.
    await page.keyboard.type('mmer N')
    await expect(field(page)).toHaveValue('Kältekammer N')
    await expect(result(page, south)).toHaveCount(0)
    await expect(results(page).getByRole('option').first()).toHaveAttribute('data-highlighted', '')

    // Listed again: highlighted again.
    await page.keyboard.press('Backspace')
    await expect(field(page)).toHaveValue('Kältekammer ')
    await expect(result(page, south)).toHaveAttribute('data-highlighted', '')
  },
)
