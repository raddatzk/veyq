// The German pass of Memories and Depot (ADR 0090 decision 9): lane L1 runs every flow in English, and a second
// language is the catalogs' and the component tests' (src/web/'s i18n.test.ts: the same keys in both languages, every
// key used; selected statements in both). What only a browser shows in German is proven here, once per application,
// in a tenant of its own ("E2E Deutsch", born through the console's invite: `birthTenant`) — not the round trip's: every
// device the shared owner signs in on keeps a device wrap, and an account holds at most 20 per method
// (KeyMaterialHandler.MAX_WRAPS_PER_METHOD), which the suite's devices of that owner already come close to (lane L1,
// 2026-10-26: one more device here, and privacy-notice.spec.ts's was refused its wrap, 409, and asked for the email):
//
//   1. the switch itself: the owner, in an English browser, picks Deutsch in Settings → Appearance and language; the
//      page re-renders in German in the same document, and after a reload it is still German (kept on the device);
//   2. Depot: a folder and a file with long German words, the file's row in German — its size with a decimal comma
//      (`123,5 kB`), its date and time written the German way — and the folder at 375 px with no horizontal scroll;
//   3. Memories: a Memory's day typed into the date field in the German order (day, month, year), shown as `dd.mm.yyyy`
//      after a reload, and the Memory at 375 px with its long name and no horizontal scroll.
//
// Every value typed is a canary (recordTyped). The labels are src/web/'s own German ones (src/web/src/i18n/index.ts,
// src/web/src/features/depot/i18n.ts, src/web/src/features/memories/i18n.ts, de); the English sign-in before the switch
// is devices.ts's.
import { test, expect, type Page } from '@playwright/test'
import { randomBytes } from 'node:crypto'
import { recordTyped } from '../../src/canaries'
import { birthTenant } from '../../src/console'
import { newDevice, watchConsole, type ConsoleErrors, type Device } from '../../src/devices'
import { stack } from '../../src/stack'

test.describe.configure({ mode: 'serial' })

const stamp = randomBytes(3).toString('hex')
const TENANT = 'E2E Deutsch'
const domain = () => `deutsch.${stack().domain}`
const tenantOrigin = () => `https://${domain()}`
// A per-tenant handle: declared plaintext (`pii`), so no canary.
const handle = `deutsch-owner-${stamp}`
// Long German words, as a German member names things: the layout must carry them at 375 px.
const folder = `Grundstücksverkehrsgenehmigungsunterlagen ${stamp}`
const file = { name: `Rechtsschutzversicherungsgesellschaftsbescheid ${stamp}.bin`, bytes: new Uint8Array(randomBytes(123_456)) }
const memoryName = `Donaudampfschifffahrtsausflug mit Familie ${stamp}`
// Typed in the German order, shown as the German screen writes a day; sealed in the Memory's meta as the ISO day — all
// canaries.
const shownDay = '03.09.2019'
const sealedDay = '2019-09-03'

let admin: Device
let owner: Device
let consoleErrors: ConsoleErrors

const markDocument = (page: Page) => page.evaluate(() => void ((window as unknown as { __veyqSameDocument: boolean }).__veyqSameDocument = true))
const sameDocument = (page: Page) =>
  page.evaluate(() => (window as unknown as { __veyqSameDocument?: boolean }).__veyqSameDocument === true)

async function noHorizontalScroll(page: Page, where: string): Promise<void> {
  const [scroll, width] = await page.evaluate(() => [document.documentElement.scrollWidth, window.innerWidth])
  expect(scroll, `${where}: the page is no wider than the screen`).toBeLessThanOrEqual(width)
}

/** The tenant's home, in German (the device kept its session). */
async function home(page: Page): Promise<void> {
  await page.goto(tenantOrigin())
  await expect(page.getByRole('button', { name: 'Neu', exact: true })).toBeVisible({ timeout: 60_000 })
}

test.beforeAll(async ({ browser }) => {
  admin = await newDevice(browser)
  // An English browser: German comes from the switch, not from `navigator.language`.
  owner = await newDevice(browser, { locale: 'en-US' })
  consoleErrors = watchConsole(owner.page)
})

test.afterEach(() => {
  for (const d of [admin, owner]) d?.confinement.assertConfined()
  expect(consoleErrors.errors, 'console errors').toEqual([])
})

test.afterAll(async () => {
  for (const d of [admin, owner]) await d?.context.close()
})

test(
  'Deutsch picked in Settings re-renders the page in German at once and is kept over a reload',
  { tag: '@uc.settings.appearance-language:language-switch-live' },
  async () => {
    const { page } = owner
    await birthTenant(admin, { name: TENANT, domain: domain(), handle }, owner)
    consoleErrors.clear()
    await page.locator('.profile-button').click()
    await page.getByRole('menuitem', { name: 'Settings' }).click()
    await page.getByRole('navigation', { name: 'Settings' }).getByRole('button', { name: 'Appearance and language', exact: true }).click()
    await markDocument(page)
    await page.getByRole('group', { name: 'Language' }).getByRole('button', { name: 'Deutsch', exact: true }).click()

    // Live: the same document, every visible string German, the document's language with it.
    const settings = page.getByRole('navigation', { name: 'Einstellungen' })
    await expect(settings.getByRole('button', { name: 'Darstellung und Sprache', exact: true })).toBeVisible()
    await expect(page.getByRole('group', { name: 'Sprache' }).getByRole('button', { name: 'Deutsch', exact: true }))
      .toHaveAttribute('aria-pressed', 'true')
    await expect(page.locator('html')).toHaveAttribute('lang', 'de')
    expect(await sameDocument(page), 'the switch re-rendered the page, no reload').toBe(true)

    // Kept: a new document opens in German.
    await page.reload()
    await expect(page.getByRole('navigation', { name: 'Einstellungen' })).toBeVisible({ timeout: 60_000 })
    await home(page)
  },
)

test(
  'Depot in German: a file\'s row gives its size with a decimal comma and its date the German way; long words fit 375 px',
  { tag: '@uc.depot.member-files:upload-attaches-one-sealed-blob' },
  async () => {
    const { page } = owner
    await home(page)
    await page.getByRole('button', { name: 'Neu', exact: true }).click()
    await page.getByRole('menuitem', { name: 'Neuer Ordner', exact: true }).click()
    const create = page.getByRole('dialog', { name: 'Ordner anlegen' })
    await create.getByLabel('Name').fill(recordTyped(folder))
    await create.getByRole('button', { name: 'Erstellen', exact: true }).click()
    await expect(create).toBeHidden()
    await page.getByRole('button', { name: folder, exact: true }).click({ timeout: 60_000 })
    await expect(page.getByRole('heading', { level: 1, name: folder })).toBeVisible()

    const chooser = page.waitForEvent('filechooser')
    await page.getByRole('button', { name: 'Neu', exact: true }).click()
    await page.getByRole('menuitem', { name: 'Dateien hochladen', exact: true }).click()
    await (await chooser).setFiles({ name: recordTyped(file.name), mimeType: 'application/octet-stream', buffer: Buffer.from(file.bytes) })
    const download = page.getByRole('button', { name: `${file.name} herunterladen`, exact: true })
    await expect(download).toBeEnabled({ timeout: 60_000 })

    // Read back in a new document: the size with a decimal comma, the date and time as a German screen writes them.
    await page.reload()
    await expect(page.getByRole('heading', { level: 1, name: folder })).toBeVisible({ timeout: 60_000 })
    await expect(page.getByText(/^Datei · 123,5 kB · Geändert \d{2}\.\d{2}\.\d{4}, \d{2}:\d{2}$/)).toBeVisible({ timeout: 60_000 })

    await page.setViewportSize({ width: 375, height: 812 })
    await page.reload()
    await expect(page.getByRole('heading', { level: 1, name: folder })).toBeVisible({ timeout: 60_000 })
    await expect(page.getByText(/^Datei · 123,5 kB · /)).toBeVisible({ timeout: 60_000 })
    await noHorizontalScroll(page, 'the folder at 375 px')
    await page.setViewportSize({ width: 1280, height: 720 })
  },
)

test(
  'Memories in German: a day typed in the German order is shown as dd.mm.yyyy; the long name fits 375 px',
  { tag: '@uc.memory.date:four-mode-date-header-editor' },
  async () => {
    const { page } = owner
    await home(page)
    await page.getByRole('button', { name: 'Neu', exact: true }).click()
    await page.getByRole('menuitem', { name: 'Neue Erinnerung', exact: true }).click()
    const create = page.getByRole('dialog', { name: 'Erinnerung anlegen' })
    await create.getByLabel('Name').fill(recordTyped(memoryName))
    await create.getByRole('button', { name: 'Erstellen', exact: true }).click()
    await expect(create).toBeHidden()
    await page.getByRole('button', { name: memoryName, exact: true }).click({ timeout: 60_000 })
    await expect(page.getByRole('heading', { level: 1, name: memoryName })).toBeVisible()

    await page.getByRole('button', { name: 'Datum bearbeiten' }).click()
    const editor = page.getByRole('dialog', { name: 'Datum' })
    await editor.getByRole('button', { name: 'Einzelner Tag' }).click()
    recordTyped(sealedDay)
    // The field's segments in the German order — day, month, year (Reka's date field follows the app's locale) — typed
    // as a keyboard does: each segment moves on to the next once it is full.
    const day = editor.getByRole('group', { name: 'Tag', exact: true })
    await day.getByRole('spinbutton', { name: 'Tag', exact: true }).click()
    await page.keyboard.type(recordTyped(shownDay).replace(/\D/g, ''))
    await expect(day.getByRole('spinbutton', { name: 'Monat', exact: true })).toHaveText('09')
    await expect(day.getByRole('spinbutton', { name: 'Jahr', exact: true })).toHaveText('2019')
    await page.keyboard.press('Tab')
    await editor.getByRole('button', { name: 'Speichern', exact: true }).click()
    await expect(editor).toBeHidden()

    await page.reload()
    await expect(page.getByRole('heading', { level: 1, name: memoryName })).toBeVisible({ timeout: 60_000 })
    await expect(page.getByRole('button', { name: 'Datum bearbeiten' })).toContainText(shownDay, { timeout: 60_000 })

    await page.setViewportSize({ width: 375, height: 812 })
    await page.reload()
    await expect(page.getByRole('heading', { level: 1, name: memoryName })).toBeVisible({ timeout: 60_000 })
    await noHorizontalScroll(page, 'the Memory at 375 px')
    await page.setViewportSize({ width: 1280, height: 720 })
  },
)
