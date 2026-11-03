// Expenses in a browser (cr-expenses-ledger's browser pass; uc.expenses.*, ADR 0108). A tenant of its own ("E2E Expenses",
// born through the console's invite: `birthTenant`): its owner (A) invites a second member (B) by mail, and the two share
// one group per pass. Then, all in English:
//
//   1. A creates a Pot in a folder — base currency EUR, three people in order — and the folder's row names its currency;
//   2. A types in the "trip" Pot of contracts/expenses/v1-vectors.json, its nine entries as the vector holds them (all
//      rest, items with a rest by shares, foreign currencies at a typed rate, items by percent and by shares, two payers,
//      exact amounts as one item each, a refund with two receivers and an item, a transfer): Balances, the settle-up and
//      the Overview show what the vector computes;
//   2a. A's device fetched the day's ECB table once (answered by the lane's fixture, `src/egress.ts`), with the date and
//      nothing else; a new entry in USD gets its rate filled from it, typeable, citing the ECB and the rows' date — no
//      second request;
//   3. B, in a browser of its own, saves a change to "Dinner" while A's editor is open on it; A's save is told the entry
//      changed (the one `409` the pass provokes), shows B's version, and saving again puts A's on top; the history names
//      A, B, A;
//   4. A shares the Pot READ_WRITE (viewing, or viewing and adding: no other role); the guest names itself, adds a person
//      and an entry, sees no Settings, manages no people and reads no writer's name; A's history names the guest;
//   4c. A, the tenant owner, switches the exchange rates off (Settings → Capabilities): B's and the guest's devices, which
//      hold no table yet, make no request and ask for the rate — the guest learns the switch from its Pot read, its
//      client holding only the instance's manifest; switched on again, the guest's device fetches and fills (control);
//   5. A deletes "Taxi" and restores it from Deleted entries: the balances leave and come back, no ledger notice;
//   6. A records the settle-up, deactivates and activates Cleo, deletes her (entries show "deleted people") and removes
//      the person the guest added, whom no entry names; neither is offered for a new entry;
//   7. A downloads the Pot and imports the ZIP into another folder, then copies it ("Copy to…", which names what does not
//      come along, and no longer the receipts): both new Pots show the original's balances, settle-up and overview, and
//      the receipts the entries hold — the ZIP holds each by its name, byte for byte, and not the one removed in 2b;
//   8. the guest reports "Dinner"; the moderator (the Server Owner, holding the key since first-run setup) opens the case
//      and reads the entry's three versions in plaintext, then dismisses it (the shared queue is left as found);
//   9. A removes B from the group, which rotates the group's key and re-keys the Pot: no re-key left running, every
//      figure unchanged, no ledger notice, and the Pot still opens for A and through the guest's link;
//  10. the Pot at 375 px: Entries, Balances, the editor with items, People — no horizontal scroll, every action in reach.
//
// Receipts (uc.expenses.attach-receipts), in the same pass:
//   2b. A keeps a JPEG, a PDF and a text that claims to be a PDF with "Museum" (a `.txt` is refused at the picker): the
//       JPEG is drawn as an image, the PDF by core's page renderer, the false PDF only offered for download; removing it
//       leaves the entry, and no figure moves;
//   4b. the guest keeps a JPEG with its own entry through the link and opens A's receipts;
//   9b. after the rotation every receipt still opens, for A and through the link.
// Not driven here: a receipt the operator blocked, the restore cut-off past the retention, a receipt reported on its own
// and the case view's receipts — a block needs a moderator's measure and a cut-off a retention the lane does not shorten
// (unit-tested: PotDetail.receipts.test.ts, PotEntryReview.test.ts, receiptRules.test.ts).
//
// Then once, short, in German (ADR 0090 decision 9), in the second group: a Pot made and the vector typed with decimal
// commas, a USD rate filled under the fixture's German date and typed with a comma, the balances and the settle-up in
// German format; the stale-version banner, the copy's toast, and the Pot at 375 px with its long German words.
//
// Two writers are two browser contexts, each its own cookie jar and storage: the lane serves each tenant on one host
// (`<name>.<domain>` through Traefik), so the CR's "second host" — which on the dev stack bought exactly that separation —
// is a second context here. No console error, and no failed request but the stale save's `409`, on any page of a pass (afterEach).
//
// Every name and text typed is a canary (recordTyped): lane L1's sweep then proves no Pot, entry, person, item, link guest
// or report reason reached the server readable. The handles are declared plaintext (`pii`).
import { test, expect, type Locator, type Page } from '@playwright/test'
import { randomBytes } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { freshOwner, readKit } from '../../../src/accounts'
import { recordTyped } from '../../../src/canaries'
import { birthTenant, type Account } from '../../../src/console'
import { instance, newDevice, tenantOrigin, watchConsole, watchFailedRequests, type ConsoleErrors, type Device } from '../../../src/devices'
import {
  LABELS, addPotNames, addReceipts, entryDialog, entryRow, expectReceiptOpens, ledgerNotice, minorOf, money, openTab, pick, potTab, readBalances,
  readOverview, receiptRow, squash, tripPot, typeEntry, typed, typedRate, type Labels, type Lang, type PickedFile,
} from '../../../src/expenses'
import { onePagePdf } from '../../../src/pdf'
import { headerAction } from '../../../src/header'
import { awaitMails } from '../../../src/mail'
import { stack } from '../../../src/stack'
import { toast } from '../../../src/toast'
import { readZip } from '../../../src/zip'

test.describe.configure({ mode: 'serial' })

const stamp = randomBytes(3).toString('hex')
const TENANT = 'E2E Expenses'
const potDomain = () => `expenses.${stack().domain}`
const potOrigin = () => `https://${potDomain()}`
// The relay as an owner types it into the space's mail settings (join-profile-picture.spec.ts types the same).
const RELAY = { host: 'mail.relay.test', port: '1025' }
const handles = { owner: `pot-owner-${stamp}`, member: `pot-member-${stamp}` }
const groups: Record<Lang, string> = { en: `Reisekasse EN ${stamp}`, de: `Reisekasse DE ${stamp}` }

const trip = tripPot()
// The vector's Anna, Ben and Cleo, spelled so the sweep can match them (an umlaut is outside the ciphertext alphabets).
const PEOPLE = ['Anna Müller', 'Ben Schäfer', 'Cleo Jäger']
const names = new Map(trip.participants.map((p, i) => [p.id, PEOPLE[i]!] as const))
const nameOf = (id: string) => names.get(id)!
const [ANNA, , CLEO] = trip.participants.map((p) => p.id)
const titleOf = (vectorTitle: string) => `${vectorTitle} ${stamp}`
const entryOf = (vectorTitle: string) => trip.entries.find((e) => e.body.title === vectorTitle)!

let admin: Device
let setupOwner: Device
let setupMember: Device
let accountA: Account
let accountB: Account

// A real photo's bytes for the image receipts (Memories' fixture, as depot-download.spec.ts reads it).
const PHOTO = readFileSync(fileURLToPath(new URL('../../../../../src/web/src/features/memories/__fixtures__/exif-sample.jpg', import.meta.url)))
const jpegFile = (name: string): PickedFile => ({ name: recordTyped(name), mimeType: 'image/jpeg', buffer: PHOTO })

const escape = (text: string) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
const memberRow = (page: Page, handle: string) => page.locator('.member-row').filter({ hasText: `@${handle}` })

/** The tenant's home on this device, signing in with both secrets when the document asks; retried while the fresh
 *  tenant's routers reach Traefik. */
async function signIn(page: Page, L: Labels, account: Account): Promise<void> {
  await expect(async () => {
    const response = await page.goto(potOrigin())
    expect(response?.ok(), `${potOrigin()} serves the app`).toBe(true)
  }).toPass({ timeout: 60_000, intervals: [1_000] })
  const home = page.getByRole('button', { name: L.new, exact: true })
  const email = page.getByLabel(L.email, { exact: true })
  await expect(home.or(email)).toBeVisible({ timeout: 60_000 })
  if (await email.isVisible()) {
    if (await email.isEditable()) await email.fill(account.email)
    await page.getByRole('textbox', { name: L.password }).fill(recordTyped(account.password))
    // A device that kept the session asks the password alone; a fresh one the Secret Key too.
    const secretKey = page.locator('#unlock-secret-key')
    if (await secretKey.isVisible()) await secretKey.fill(recordTyped(account.secretKey))
    await page.getByRole('button', { name: L.signIn, exact: true }).click()
  }
  await expect(home).toBeVisible({ timeout: 60_000 })
}

/** The group [name], entered through its link in the sidebar: its level, headed by its name. */
async function enterGroup(page: Page, name: string): Promise<void> {
  await page.locator('a.desktop-group-link').filter({ hasText: name }).click({ timeout: 60_000 })
  await expect(page.getByRole('heading', { level: 1, name })).toBeVisible({ timeout: 60_000 })
}

/** Settings from the space on screen (the profile menu's entry): an entered group opens on its own page. */
async function openGroupSettings(page: Page, L: Labels, group: string): Promise<void> {
  await enterGroup(page, group)
  await page.locator('.profile-button').click()
  await page.getByRole('menuitem', { name: L.settings, exact: true }).click()
  await expect(page.getByRole('heading', { level: 1, name: group })).toBeVisible({ timeout: 60_000 })
}

async function createFolder(page: Page, L: Labels, name: string): Promise<void> {
  await page.getByRole('button', { name: L.new, exact: true }).click()
  await page.getByRole('menuitem', { name: L.newFolder, exact: true }).click()
  const dialog = page.getByRole('dialog', { name: L.createFolder })
  await dialog.getByLabel(L.name, { exact: true }).fill(recordTyped(name))
  await dialog.getByRole('button', { name: L.create, exact: true }).click()
  await expect(dialog).toBeHidden()
}

async function openFolder(page: Page, name: string): Promise<void> {
  await page.getByRole('button', { name, exact: true }).click({ timeout: 60_000 })
  await expect(page.getByRole('heading', { level: 1, name })).toBeVisible({ timeout: 60_000 })
}

/** A Pot of the open level, opened: its page, read (the Entries panel drawn). */
async function openPot(page: Page, L: Labels, pot: string): Promise<void> {
  await page.getByRole('button', { name: L.pot.open(pot), exact: true }).click({ timeout: 60_000 })
  await expect(page.getByRole('heading', { level: 1, name: pot })).toBeVisible({ timeout: 60_000 })
  await expect(page.locator('.expenses-entries')).toBeVisible({ timeout: 60_000 })
}

/** What the Balances tab must show for [balances] (base minor units by participant id), sorted by name. */
function balanceRows(L: Labels, lang: Lang, balances: Record<string, number>, settledToo: string[] = []): [string, string][] {
  const text = (b: number) =>
    b > 0 ? L.pot.owed(money(b, 'EUR', 2, lang)) : b < 0 ? L.pot.owes(money(-b, 'EUR', 2, lang)) : L.pot.settled
  return [
    ...Object.entries(balances).map(([id, b]) => [nameOf(id), squash(text(b))] as [string, string]),
    ...settledToo.map((n) => [n, L.pot.settled] as [string, string]),
  ].sort((x, y) => x[0].localeCompare(y[0]))
}

const transferRows = (L: Labels, lang: Lang) =>
  trip.expected.settleUp.map((t) => [L.pot.pays(nameOf(t.from), nameOf(t.to)), squash(money(t.amount, 'EUR', 2, lang))] as [string, string])

/** Balances and the settle-up, as the screen shows them, against what is expected ([transfers] left unread: null). */
async function expectBalances(page: Page, L: Labels, rows: [string, string][], transfers: [string, string][] | null): Promise<void> {
  await expect.poll(async () => {
    const read = await readBalances(page, L)
    return { rows: [...read.rows].sort((x, y) => x[0].localeCompare(y[0])), transfers: transfers === null ? null : read.transfers }
  }, { timeout: 30_000 }).toEqual({ rows, transfers })
}

/** The balances, the settle-up and the overview: what a copy, an import or a re-key must leave as it was. */
async function figures(page: Page, L: Labels) {
  const b = await readBalances(page, L)
  return { rows: [...b.rows].sort((x, y) => x[0].localeCompare(y[0])), transfers: b.transfers, settled: b.settled, overview: await readOverview(page, L) }
}

/** The Overview's two tables add up: per person, the paid column and the share column each sum to the costs. */
async function expectOverviewAddsUp(page: Page, L: Labels): Promise<number> {
  const [byCategory, byPerson] = await readOverview(page, L)
  expect(byCategory, 'the category table and its total').toBeDefined()
  const total = minorOf(byCategory!.at(-1)![1]!)
  const categories = byCategory!.slice(0, -1).reduce((s, r) => s + minorOf(r[1]!), 0)
  expect(categories, 'the categories sum to the total').toBe(total)
  expect(byPerson!.reduce((s, r) => s + minorOf(r[1]!), 0), 'what each person paid sums to the costs').toBe(total)
  expect(byPerson!.reduce((s, r) => s + minorOf(r[2]!), 0), 'every share sums to the costs').toBe(total)
  return total
}

/** The open dialog's history: each version's line and its byline, newest first. */
async function historyOf(page: Page, L: Labels, header: string): Promise<{ what: string; by: string }[]> {
  const dialog = entryDialog(page, header)
  await dialog.getByRole('button', { name: L.pot.history, exact: true }).click()
  const rows = dialog.locator('.expenses-history li')
  await expect(rows.first()).toBeVisible()
  return rows.evaluateAll((lis) =>
    lis.map((li) => ({
      what: (li.querySelector('.veyq-row-title')?.textContent ?? '').replace(/\s+/g, ' ').trim(),
      by: (li.querySelector('.veyq-row-subtitle')?.textContent ?? '').replace(/\s+/g, ' ').trim(),
    })),
  )
}

/** [what] (an action) is shown and lies inside the 375 px, scrolled to if it must be. */
async function inReach(what: Locator, label: string): Promise<void> {
  await what.scrollIntoViewIfNeeded()
  await expect(what, `${label} is shown`).toBeVisible()
  const box = await what.boundingBox()
  expect(box, `${label} has a box`).not.toBeNull()
  expect(box!.x, `${label} starts inside the screen`).toBeGreaterThanOrEqual(0)
  expect(box!.x + box!.width, `${label} ends inside the screen`).toBeLessThanOrEqual(375 + 0.5)
}

async function noHorizontalScroll(page: Page, where: string): Promise<void> {
  const [scroll, width] = await page.evaluate(() => [document.documentElement.scrollWidth, window.innerWidth])
  expect(scroll, `${where}: the page is no wider than the screen`).toBeLessThanOrEqual(width)
}

/** A new document on [link]: a `goto` to the URL already open, fragment and all, would only move to the fragment. */
async function openLink(page: Page, link: string): Promise<void> {
  await page.goto('about:blank')
  await page.goto(link)
}

/** The device's own date, `YYYY-MM-DD`, as the editor puts it in the rate table's address (the lane's browsers run in
 *  the host's time zone). */
function deviceDay(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/** The day the lane's fixture rows are of (fixtures/ecb-rates-2026-10-23.json), as the editor names it per language. */
const FIXTURE_DAY = { en: 'Oct 23, 2026', de: '23.10.2026' } as const
/** One US dollar in euros from the fixture's table: 1 / 1.1193, rounded half away from zero at 12 places. */
const USD_IN_EUR = '0.893415527562'

/** Each rate-table request [device] made carried the date and nothing else: the seeded ECB address (the egress route
 *  answers no other), a plain GET with no header of the page's own, no Referer and no credentials. */
function expectBareRateQueries(device: Device): void {
  for (const q of device.confinement.rateQueries()) {
    expect(q.href, 'the device\'s date, nothing else').toBe(`https://api.frankfurter.dev/v2/providers/ecb/rates?date=${deviceDay()}`)
    expect(q.method).toBe('GET')
    expect(q.headers.origin, 'the instance\'s Origin').toBe(potOrigin())
    for (const name of Object.keys(q.headers)) expect(name, 'a header of the page\'s own').not.toMatch(/^(x-|authorization$|cookie$|referer$|content-type$)/)
  }
}

/** A new entry opened on [page], its currency set to [currency]: the dialog, and its rate field. */
async function foreignEntry(page: Page, L: Labels, currency: string): Promise<{ dialog: Locator; rate: Locator }> {
  await openTab(page, L.pot.tabs.entries)
  await page.locator('.expenses-entries').getByRole('button', { name: L.pot.addEntry, exact: true }).click()
  const dialog = entryDialog(page, L.pot.addEntry)
  await expect(dialog).toBeVisible()
  await pick(page, dialog.getByRole('combobox', { name: L.pot.currency }), currency)
  const rate = dialog.getByLabel(new RegExp(`^${escape(L.pot.rate(currency, 'EUR'))}`))
  await expect(rate).toBeVisible()
  return { dialog, rate }
}

/** Settings → Capabilities of the Expenses tenant, as its owner: the exchange rates switched [off] or on. Off asks first. */
async function switchExchangeRates(page: Page, L: Labels, off: boolean): Promise<void> {
  await signIn(page, L, accountA)
  await page.locator('.profile-button').click()
  await page.getByRole('menuitem', { name: L.settings, exact: true }).click()
  await page.getByRole('navigation', { name: L.settings }).getByRole('button', { name: L.capabilities, exact: true }).click()
  const row = page.locator('li.capability[data-id="expenses.exchange-rates"]')
  await row.getByRole('button', { name: off ? L.switchOff : L.switchOn, exact: true }).click({ timeout: 60_000 })
  if (off) {
    const confirm = page.getByRole('dialog', { name: L.switchOffTitle(L.exchangeRates) })
    await confirm.getByRole('button', { name: L.switchOff, exact: true }).click()
    await expect(confirm).toBeHidden()
  }
  await expect(row.getByRole('button', { name: off ? L.switchOn : L.switchOff, exact: true })).toBeVisible({ timeout: 30_000 })
}

/** The moderation gate on this page, in [L]'s language: sign in if it asks, then unlock with both secrets. */
async function passGate(page: Page, L: Labels): Promise<void> {
  const account = instance().owner
  const signInButton = page.getByRole('button', { name: L.moderationSignIn, exact: true })
  const unlockForm = page.locator('.moderation-unlock')
  await expect(signInButton.or(unlockForm).first()).toBeVisible({ timeout: 60_000 })
  if (await signInButton.isVisible()) {
    await page.getByLabel(L.email, { exact: true }).fill(account.email)
    await page.getByRole('textbox', { name: L.password }).fill(recordTyped(account.password))
    await signInButton.click()
  }
  await expect(unlockForm).toBeVisible({ timeout: 60_000 })
  await unlockForm.getByRole('textbox', { name: L.password }).fill(recordTyped(account.password))
  await page.locator('#moderation-secret-key').fill(recordTyped(account.secretKey))
  await page.getByRole('button', { name: L.unlock, exact: true }).click()
}

test.beforeAll(async ({ browser }) => {
  admin = await newDevice(browser)
  setupOwner = await newDevice(browser)
  setupMember = await newDevice(browser)
})

test.afterAll(async () => {
  for (const d of [admin, setupOwner, setupMember]) await d?.context.close()
})

test(
  'a tenant of its own: its owner invites a second member by mail, and the two share one group per language',
  { tag: ['@uc.spaces.add-member:member-invite-and-onboard', '@uc.spaces.add-group-member:auto-deliver-group-grant'] },
  async () => {
    test.setTimeout(480_000)
    const L = LABELS.en
    accountA = await birthTenant(admin, { name: TENANT, domain: potDomain(), handle: handles.owner }, setupOwner)
    const { page } = setupOwner

    // The space's own mail, pointed at the run's relay: member invitations go out by mail only.
    await page.locator('.profile-button').click()
    await page.getByRole('menuitem', { name: 'Settings', exact: true }).click()
    await page.getByRole('navigation', { name: 'Settings' }).getByRole('button', { name: 'Email', exact: true }).click()
    await expect(page.getByRole('heading', { level: 2, name: 'Email' })).toBeVisible()
    await page.getByRole('textbox', { name: /^SMTP host/ }).fill(RELAY.host)
    await page.getByRole('textbox', { name: /^Port/ }).fill(RELAY.port)
    await page.getByRole('textbox', { name: /^From address/ }).fill(`space-mail@${potDomain()}`)
    await page.getByRole('switch', { name: /^Use STARTTLS/ }).uncheck()
    await page.getByRole('switch', { name: /^Send invitations by email/ }).check()
    await page.getByRole('button', { name: 'Save email settings' }).click()
    await expect(toast(page, 'Email settings saved.')).toBeVisible()

    const fresh = freshOwner(`e2e.${stack().domain}`)
    await page.getByRole('navigation', { name: 'Settings' }).getByRole('button', { name: 'Members', exact: true }).click()
    await page.getByRole('button', { name: 'Invite member' }).click()
    const invite = page.getByRole('dialog', { name: 'Invite member' })
    await invite.getByLabel('Email address').fill(fresh.email)
    await invite.getByRole('button', { name: 'Send invite' }).click()
    await expect(invite.getByText(`Invitation sent to ${fresh.email}.`)).toBeVisible({ timeout: 30_000 })
    // The code is shown once, here, and travels outside veyq (ADR 0111 decision 1): never in the mail, never on the wire.
    const code = recordTyped((await invite.getByTestId('invite-code').textContent())!.trim())
    await invite.getByRole('button', { name: 'Done' }).click()
    const [mail] = await awaitMails(fresh.email, new RegExp(`^You're invited to ${escape(potDomain())} `))
    const link = /https:\/\/\S+/.exec(mail!.text)?.[0]
    expect(link, `an invite link in:\n${mail!.text}`).toBeDefined()

    const tab = setupMember.page
    await tab.goto(link!)
    await expect(tab.getByText(`You have been invited to join “${TENANT}” (${potDomain()}).`)).toBeVisible({ timeout: 60_000 })
    // The link alone opens nothing: the code opens its sealed part, and the client checks the invite before anything else.
    await tab.getByRole('textbox', { name: 'Code', exact: true }).fill(code)
    await tab.getByRole('button', { name: 'Continue' }).click()
    await tab.getByLabel('Username').fill(handles.member)
    await tab.locator('#join-password').fill(recordTyped(fresh.password))
    await tab.getByRole('button', { name: 'Continue' }).click()
    const download = tab.waitForEvent('download')
    await tab.getByRole('button', { name: 'Download Emergency Kit (PDF)' }).click()
    const kit = await readKit(await download)
    await tab.getByRole('checkbox', { name: 'I have saved my Emergency Kit somewhere safe.' }).check()
    await tab.getByRole('button', { name: 'Accept & join' }).click()
    await expect(tab.getByText(`You’ve joined “${TENANT}”.`)).toBeVisible({ timeout: 60_000 })
    accountB = { email: fresh.email, password: fresh.password, secretKey: kit.secretKey }

    // The member granted itself the space's key at its accept (ADR 0111): Active at once, no delivery waited for.
    await signIn(page, L, accountA)
    await page.locator('.profile-button').click()
    await page.getByRole('menuitem', { name: 'Settings', exact: true }).click()
    await page.getByRole('navigation', { name: 'Settings' }).getByRole('button', { name: 'Members', exact: true }).click()
    await expect(memberRow(page, handles.member).locator('.veyq-badge').first()).toHaveText('Active', { timeout: 30_000 })

    // One group per language, each with the member added by username; the add carries the group's key (ADR 0111).
    for (const group of Object.values(groups)) {
      await signIn(page, L, accountA)
      await page.getByRole('button', { name: 'New group', exact: true }).click()
      const create = page.getByRole('dialog', { name: 'New group' })
      await create.getByLabel('Group name').fill(recordTyped(group))
      await create.getByRole('button', { name: 'Create', exact: true }).click()
      await expect(create).toBeHidden({ timeout: 30_000 })
      await openGroupSettings(page, L, group)
      await page.locator('#group-add-username').fill(handles.member)
      await page.getByRole('button', { name: 'Add', exact: true }).click()
      await expect(memberRow(page, handles.member).locator('.veyq-badge').first()).toHaveText('Active', { timeout: 30_000 })
    }

    // The member's sidebar lists both groups at once: their keys open on its side with nobody else online.
    await signIn(tab, L, accountB)
    for (const group of Object.values(groups)) {
      await expect(tab.locator('a.desktop-group-link').filter({ hasText: group })).toBeVisible({ timeout: 30_000 })
    }
  },
)

// The whole flow, in English (ADR 0090 decision 9: lane L1 runs every flow in English; the German pass below proves
// what only a browser shows in German).
test.describe('in English', () => {
  const lang = 'en' as const
  const L = LABELS[lang]
  const locale = 'en-US'
  const group = groups[lang]
  const folder = `Trip folder ${stamp}`
  const importFolder = `Imported here ${stamp}`
  const pot = `Trip to the sea ${stamp}`
  const guestName = `Guest Fritz ${stamp}`
  const dora = 'Dora Ölmann'
  const snacks = titleOf('Snacks for Anna')
  const dinner = titleOf('Dinner')
  const dinnerB = titleOf('Dinner at the port')
  const dinnerA = titleOf('Dinner for three')
  const museum = titleOf('Museum')
  const receiptJpeg = `Museum ticket ${stamp}.jpg`
  const receiptPdf = `Admission ${stamp}.pdf`
  const falsePdf = `Note ${stamp}.pdf`
  const guestJpeg = `Kiosk receipt ${stamp}.jpg`
  const reason = `This entry insults someone ${stamp}`
  const vectorRows = () => balanceRows(L, lang, trip.expected.balances)
  const eur = (minor: number) => squash(money(minor, 'EUR', 2, lang))
  // The PDF receipt's bytes: its one line is a canary (the sweep proves it never reached the server readable).
  const admissionPdf = () => Buffer.from(onePagePdf(recordTyped(`Eintritt ${stamp}`)))

  /** The receipts an import or a copy carried ([where]): Museum's JPEG and PDF and the guest's JPEG with their entries,
   *  each opening as it did, and not the one step 2b removed. */
  async function expectCarriedReceipts(page: Page, where: string): Promise<void> {
    await openTab(page, L.pot.tabs.entries)
    await entryRow(page, museum).click()
    const museumDialog = entryDialog(page, L.pot.editEntry)
    await expect(receiptRow(museumDialog, receiptJpeg), `${where}: ${receiptJpeg}`).toHaveCount(1, { timeout: 30_000 })
    await expect(receiptRow(museumDialog, receiptPdf), `${where}: ${receiptPdf}`).toHaveCount(1)
    await expect(receiptRow(museumDialog, falsePdf), `${where}: the removed receipt is not carried`).toHaveCount(0)
    await expectReceiptOpens(museumDialog, L, receiptJpeg, 'image')
    await expectReceiptOpens(museumDialog, L, receiptPdf, 'pdf')
    await museumDialog.getByRole('button', { name: L.cancel, exact: true }).click()
    await expect(museumDialog).toBeHidden()
    await entryRow(page, snacks).click()
    const snacksDialog = entryDialog(page, L.pot.editEntry)
    await expect(receiptRow(snacksDialog, guestJpeg), `${where}: ${guestJpeg}`).toHaveCount(1, { timeout: 30_000 })
    await expectReceiptOpens(snacksDialog, L, guestJpeg, 'image')
    await snacksDialog.getByRole('button', { name: L.cancel, exact: true }).click()
    await expect(snacksDialog).toBeHidden()
  }

  let a: Device
  let b: Device
  let guest: Device
  let moderator: Device
  let consoleErrors: ConsoleErrors
  let failures: ConsoleErrors
  let shareLink = ''
  let before: Awaited<ReturnType<typeof figures>>

  test.beforeAll(async ({ browser }) => {
    a = await newDevice(browser, { locale })
    b = await newDevice(browser, { locale })
    guest = await newDevice(browser, { locale })
    moderator = await newDevice(browser, { locale })
    const pages = [a.page, b.page, guest.page, moderator.page]
    consoleErrors = watchConsole(...pages)
    failures = watchFailedRequests(...pages)
  })

  test.afterEach(() => {
    for (const d of [a, b, guest, moderator]) d?.confinement.assertConfined()
    expect(consoleErrors.errors, 'console errors').toEqual([])
    expect(failures.errors, 'failed requests').toEqual([])
  })

  test.afterAll(async () => {
    await a?.page.setViewportSize({ width: 1280, height: 720 })
    for (const d of [a, b, guest, moderator]) await d?.context.close()
  })

  test(
    'step 1: a member creates a Pot in a folder with its base currency and three people; the row names the currency',
    { tag: '@uc.expenses.create-pot:member-creates-a-pot' },
    async () => {
      const { page } = a
      await signIn(page, L, accountA)
      consoleErrors.clear()
      failures.clear()
      await enterGroup(page, group)
      await createFolder(page, L, folder)
      await openFolder(page, folder)

      await page.getByRole('button', { name: L.new, exact: true }).click()
      await page.getByRole('menuitem', { name: L.pot.newPot, exact: true }).click()
      const dialog = page.getByRole('dialog', { name: L.pot.newPot, exact: true })
      // The Pot's own name is the dialog's first field; its people are chips in the people field.
      await dialog.getByRole('textbox', { name: L.name, exact: true }).fill(recordTyped(pot))
      await pick(page, dialog.getByRole('combobox', { name: L.pot.baseCurrency }), 'EUR')
      await addPotNames(page, L, dialog, PEOPLE)
      await dialog.getByRole('button', { name: L.create, exact: true }).click()
      await expect(dialog).toBeHidden({ timeout: 30_000 })

      const row = page.locator('li.veyq-level-item[data-kind="EXPENSES_POT"]').filter({ hasText: pot })
      await expect(row.locator('.veyq-level-item-detail[data-detail="currency"]')).toHaveText('EUR', { timeout: 30_000 })
      await expect(row.locator('.veyq-level-item-meta')).toContainText(L.pot.kindPot)

      await openPot(page, L, pot)
      await expect(potTab(page, L.pot.tabs.entries)).toHaveAttribute('aria-selected', 'true')
      await openTab(page, L.pot.tabs.people)
      await expect(page.locator('.expenses-people li .veyq-row-title')).toHaveText(PEOPLE)
      await openTab(page, L.pot.tabs.entries)
    },
  )

  test(
    'step 2: the "trip" vector typed in — balances, settle-up and overview as the vector computes them',
    {
      tag: [
        '@uc.expenses.record-entries:allocation-and-rounding-are-exact-and-equal-everywhere',
        '@uc.expenses.balances:balances-from-the-live-entries',
        '@uc.expenses.balances:settle-up-is-greedy-and-deterministic',
        '@uc.expenses.balances:overviews-by-category-and-person',
      ],
    },
    async () => {
      test.setTimeout(360_000)
      const { page } = a
      for (const entry of trip.entries) await typeEntry(page, L, lang, entry, names, titleOf(entry.body.title))
      await expect(page.locator('.expenses-entries .expenses-row')).toHaveCount(trip.entries.length)
      await expect(page.locator('.expenses-entries').getByText(L.pot.invalid, { exact: true }), 'no entry is marked invalid').toHaveCount(0)
      await expectBalances(page, L, vectorRows(), transferRows(L, lang))
      await expectOverviewAddsUp(page, L)
      await expect(ledgerNotice(page)).toHaveCount(0)
    },
  )

  test(
    'step 2a: the device fetched the day\'s ECB table once, with the date alone; a USD entry gets its rate filled from it',
    {
      tag: [
        '@uc.expenses.exchange-rates:the-days-table-straight-from-the-device',
        '@uc.expenses.exchange-rates:the-rate-is-sealed-and-never-moves',
      ],
    },
    async () => {
      const { page } = a
      // Step 2's JPY entry asked once; its typed rate stood (the vector's balances above prove it).
      expect(a.confinement.rateQueries(), 'one request for the day, for every entry of step 2').toHaveLength(1)
      const { dialog, rate } = await foreignEntry(page, L, 'USD')
      await expect(rate).toHaveValue(USD_IN_EUR, { timeout: 30_000 })
      await expect(dialog.getByText(L.pot.rateFilled(FIXTURE_DAY[lang]), { exact: true })).toBeVisible()
      // typeable: what is typed stands, and the ECB's note goes with the filled rate
      await rate.fill(typedRate('0.9', lang))
      await expect(dialog.getByText(L.pot.rateFilled(FIXTURE_DAY[lang]), { exact: true })).toHaveCount(0)
      await dialog.getByRole('button', { name: L.cancel, exact: true }).click()
      await expect(dialog).toBeHidden()
      expect(a.confinement.rateQueries(), 'the device\'s table, no second request').toHaveLength(1)
      expectBareRateQueries(a)
    },
  )

  test(
    'step 2b: receipts kept with an entry — an image and a PDF drawn, anything else only for download, one removed again',
    {
      tag: [
        '@uc.expenses.attach-receipts:receipts-are-blobs-of-the-entry',
        '@uc.expenses.attach-receipts:only-images-and-pdfs-render',
        '@uc.expenses.attach-receipts:a-dropped-receipt-goes-after-the-retention',
      ],
    },
    async () => {
      test.setTimeout(240_000)
      const { page } = a
      const R = L.pot.receipts
      await openTab(page, L.pot.tabs.entries)
      await entryRow(page, museum).click()
      const editor = entryDialog(page, L.pot.editEntry)
      // A `.txt` is no receipt: refused at the picker, before anything is uploaded.
      const note = `Notes ${stamp}.txt`
      await addReceipts(editor, [{ name: recordTyped(note), mimeType: 'text/plain', buffer: Buffer.from(recordTyped(`plain ${stamp}`)) }])
      await expect(editor.getByText(R.notReceipt(note), { exact: true })).toBeVisible()
      await addReceipts(editor, [
        jpegFile(receiptJpeg),
        { name: recordTyped(receiptPdf), mimeType: 'application/pdf', buffer: admissionPdf() },
        // claims to be a PDF; its bytes are a text: the bytes decide
        { name: recordTyped(falsePdf), mimeType: 'application/pdf', buffer: Buffer.from(recordTyped(`not a PDF ${stamp}`)) },
      ])
      await editor.getByRole('button', { name: L.save, exact: true }).click()
      await expect(editor).toBeHidden({ timeout: 60_000 })

      await entryRow(page, museum).click()
      const saved = entryDialog(page, L.pot.editEntry)
      for (const name of [receiptJpeg, receiptPdf, falsePdf]) await expect(receiptRow(saved, name)).toHaveCount(1, { timeout: 30_000 })
      await expectReceiptOpens(saved, L, receiptJpeg, 'image')
      await expectReceiptOpens(saved, L, receiptPdf, 'pdf')
      await expectReceiptOpens(saved, L, falsePdf, 'other')
      await receiptRow(saved, falsePdf).getByRole('button', { name: R.remove(falsePdf), exact: true }).click()
      await saved.getByRole('button', { name: L.save, exact: true }).click()
      await expect(saved).toBeHidden({ timeout: 60_000 })

      await entryRow(page, museum).click()
      const after = entryDialog(page, L.pot.editEntry)
      await expect(receiptRow(after, receiptJpeg)).toHaveCount(1, { timeout: 30_000 })
      await expect(receiptRow(after, receiptPdf)).toHaveCount(1)
      await expect(receiptRow(after, falsePdf), 'a removed receipt leaves the entry').toHaveCount(0)
      await after.getByRole('button', { name: L.cancel, exact: true }).click()
      // Receipts move no figure.
      await expectBalances(page, L, vectorRows(), transferRows(L, lang))
      await expect(ledgerNotice(page)).toHaveCount(0)
    },
  )

  test(
    'step 3: a save on an entry another member changed meanwhile shows their version; saving again goes on top',
    {
      tag: [
        '@uc.expenses.record-entries:revision-n-plus-one-or-stale',
        '@uc.expenses.entry-history:history-shows-every-revision',
        '@uc.expenses.entry-history:writer-names-for-members-only',
      ],
    },
    async () => {
      test.setTimeout(240_000)
      await openTab(a.page, L.pot.tabs.entries)
      await entryRow(a.page, dinner).click()
      const editorA = entryDialog(a.page, L.pot.editEntry)
      await expect(editorA.getByLabel(L.pot.title, { exact: true })).toHaveValue(dinner)

      // B, in a browser of its own, saves first.
      const pb = b.page
      await signIn(pb, L, accountB)
      await enterGroup(pb, group)
      await openFolder(pb, folder)
      await openPot(pb, L, pot)
      await entryRow(pb, dinner).click()
      const editorB = entryDialog(pb, L.pot.editEntry)
      await editorB.getByLabel(L.pot.title, { exact: true }).fill(recordTyped(dinnerB))
      await editorB.getByRole('button', { name: L.save, exact: true }).click()
      await expect(editorB).toBeHidden({ timeout: 30_000 })
      // B's part in the Pot pauses here; it comes back in step 4c, and its page stays on the Pot from there into step 9.
      await pb.goto('about:blank')

      // A saves on the version its editor shows: told, and shown B's.
      await editorA.getByLabel(L.pot.title, { exact: true }).fill(recordTyped(dinnerA))
      await editorA.getByRole('button', { name: L.save, exact: true }).click()
      await expect(editorA.getByText(L.pot.stale, { exact: true })).toBeVisible({ timeout: 30_000 })
      await expect(editorA.getByLabel(L.pot.title, { exact: true })).toHaveValue(dinnerB)
      // Saving again puts A's on top.
      await editorA.getByLabel(L.pot.title, { exact: true }).fill(dinner)
      await editorA.getByRole('button', { name: L.save, exact: true }).click()
      await expect(editorA).toBeHidden({ timeout: 30_000 })

      await entryRow(a.page, dinner).click()
      const history = await historyOf(a.page, L, L.pot.editEntry)
      const hundred = eur(10_000)
      expect(history.map((h) => h.what)).toEqual([`${dinner} · ${hundred}`, `${dinnerB} · ${hundred}`, `${dinner} · ${hundred}`])
      expect(history.map((h) => h.by.startsWith(L.pot.byAt(handles.owner)) ? 'A' : h.by.startsWith(L.pot.byAt(handles.member)) ? 'B' : h.by))
        .toEqual(['A', 'B', 'A'])
      await entryDialog(a.page, L.pot.editEntry).getByRole('button', { name: L.cancel, exact: true }).click()
      await expectBalances(a.page, L, vectorRows(), transferRows(L, lang))

      // The one failed request the pass provokes: A's stale append, answered 409 — and Chromium's console line for it.
      const append = /^POST 409 \/api\/scopes\/[^/]+\/pots\/[^/]+\/revisions$/
      expect(failures.errors.filter((e) => append.test(e)), 'exactly one stale append').toHaveLength(1)
      failures.errors.splice(failures.errors.findIndex((e) => append.test(e)), 1)
      const logged = (e: string) => /status of 409\b/.test(e) && /\/pots\/[^/]+\/revisions\b/.test(e)
      expect(consoleErrors.errors.filter(logged), 'its console line').toHaveLength(1)
      consoleErrors.errors.splice(consoleErrors.errors.findIndex(logged), 1)
    },
  )

  test(
    'step 4: a READ_WRITE guest adds a person and an entry in the member\'s view, without Settings, people management or writer names',
    {
      tag: [
        '@uc.expenses.share-pot:read-and-read-write-never-write-only',
        '@uc.expenses.share-pot:the-guest-sees-the-members-view',
        '@uc.expenses.share-pot:a-contributor-writes-like-a-member',
        '@uc.expenses.share-pot:what-stays-a-members',
        '@uc.expenses.entry-history:writer-names-for-members-only',
        '@uc.expenses.manage-participants:writers-add-and-rename',
      ],
    },
    async () => {
      test.setTimeout(240_000)
      const { page } = a
      await headerAction(page, L.share)
      const share = page.getByRole('dialog', { name: L.shareTitle, exact: true })
      await share.getByRole('button', { name: L.newShare, exact: true }).click()
      const create = page.getByRole('dialog', { name: L.newShare, exact: true })
      // Viewing, or viewing and adding: no upload-only link on a Pot.
      await expect(create.locator('.veyq-toggle-group').getByRole('button')).toHaveText([L.viewOnly, L.contribute])
      await create.getByRole('button', { name: L.contribute, exact: true }).click()
      await expect(create.getByRole('button', { name: L.contribute, exact: true })).toHaveAttribute('aria-pressed', 'true')
      await create.getByRole('button', { name: L.createLink, exact: true }).click()
      const linkView = page.getByRole('dialog', { name: L.shareTitle, exact: true })
      shareLink = await linkView.getByRole('textbox', { name: L.publicLink }).inputValue()
      expect(new URL(shareLink).hash, 'the link key travels in the fragment').toMatch(/^#k=/)
      await linkView.getByRole('button', { name: L.done, exact: true }).click()
      await page.keyboard.press('Escape')
      await expect(linkView).toBeHidden()

      const g = guest.page
      await g.goto(shareLink)
      const heading = g.getByRole('heading', { level: 1, name: pot })
      const terms = g.getByRole('button', { name: L.termsAccept, exact: true })
      await expect(heading.or(terms)).toBeVisible({ timeout: 60_000 })
      if (await terms.isVisible()) await terms.click()
      await expect(heading).toBeVisible({ timeout: 60_000 })
      await expect(g.locator('.expenses-entries')).toBeVisible({ timeout: 60_000 })
      // The member's page with functions removed: no Settings tab.
      await expect(g.getByRole('tablist', { name: pot }).getByRole('tab'))
        .toHaveText([L.pot.tabs.entries, L.pot.tabs.balances, L.pot.tabs.overview, L.pot.tabs.people])

      // People: add and rename, never deactivate or delete. The first write asks the guest's name.
      await openTab(g, L.pot.tabs.people)
      const people = g.locator('.expenses-people')
      await expect(people.locator('li')).toHaveCount(3)
      await expect(people.getByRole('button', { name: L.pot.deactivate, exact: true })).toHaveCount(0)
      await expect(people.locator('[data-action]')).toHaveCount(0)
      await people.locator('form').getByRole('textbox').fill(recordTyped(dora))
      await people.locator('form').getByRole('button', { name: L.pot.addPerson, exact: true }).click()
      const naming = g.getByRole('dialog', { name: L.guestNameTitle, exact: true })
      await naming.getByLabel(L.yourName, { exact: true }).fill(recordTyped(guestName))
      await naming.getByRole('button', { name: L.continue, exact: true }).click()
      await expect(people.locator('li .veyq-row-title')).toHaveText([...PEOPLE, dora], { timeout: 30_000 })

      // An entry that moves no balance: Anna pays it and shares it alone.
      await openTab(g, L.pot.tabs.entries)
      await g.locator('.expenses-entries').getByRole('button', { name: L.pot.addEntry, exact: true }).click()
      const editor = entryDialog(g, L.pot.addEntry)
      await editor.getByLabel(L.pot.title, { exact: true }).fill(recordTyped(snacks))
      await pick(g, editor.getByRole('combobox', { name: L.pot.person, exact: true }), nameOf(ANNA!))
      await editor.getByRole('textbox', { name: L.pot.payerAmount(nameOf(ANNA!)), exact: true }).fill(typed(1234, 2, lang))
      await editor.getByRole('button', { name: L.pot.adjust, exact: true }).click()
      for (const n of [...PEOPLE, dora]) if (n !== nameOf(ANNA!)) await editor.getByRole('button', { name: L.pot.offSplit(n), exact: true }).click()
      await expect(editor.getByRole('combobox', { name: L.pot.splitPerson, exact: true })).toHaveCount(1)
      await editor.getByRole('button', { name: L.save, exact: true }).click()
      await expect(editor).toBeHidden({ timeout: 30_000 })
      await expect(entryRow(g, snacks)).toHaveCount(1)

      // No writer's name in any history the guest reads.
      for (const title of [snacks, dinner]) {
        await entryRow(g, title).click()
        const lines = await historyOf(g, L, L.pot.editEntry)
        for (const line of lines) {
          for (const writer of [handles.owner, handles.member, guestName]) expect(line.by, `${title}'s history`).not.toContain(writer)
        }
        await entryDialog(g, L.pot.editEntry).getByRole('button', { name: L.cancel, exact: true }).click()
      }

      // The member's history names the guest by the name it gave.
      await page.reload()
      await expect(page.locator('.expenses-entries')).toBeVisible({ timeout: 60_000 })
      await expect(entryRow(page, snacks)).toHaveCount(1, { timeout: 60_000 })
      await entryRow(page, snacks).click()
      expect((await historyOf(page, L, L.pot.editEntry)).map((h) => h.by.startsWith(L.pot.byAt(L.guestName(guestName))))).toEqual([true])
      await entryDialog(page, L.pot.editEntry).getByRole('button', { name: L.cancel, exact: true }).click()
      await expectBalances(page, L, balanceRows(L, lang, trip.expected.balances, [dora]), transferRows(L, lang))
    },
  )

  test(
    'step 4b: the guest keeps a receipt with its entry through the link and opens the member\'s',
    { tag: ['@uc.expenses.attach-receipts:a-contributor-adds-and-removes-receipts', '@uc.expenses.attach-receipts:only-images-and-pdfs-render'] },
    async () => {
      test.setTimeout(240_000)
      const g = guest.page
      await openTab(g, L.pot.tabs.entries)
      await entryRow(g, snacks).click()
      const editor = entryDialog(g, L.pot.editEntry)
      await addReceipts(editor, [jpegFile(guestJpeg)])
      await editor.getByRole('button', { name: L.save, exact: true }).click()
      await expect(editor).toBeHidden({ timeout: 60_000 })
      await entryRow(g, snacks).click()
      const saved = entryDialog(g, L.pot.editEntry)
      await expectReceiptOpens(saved, L, guestJpeg, 'image')
      await saved.getByRole('button', { name: L.cancel, exact: true }).click()
      // A's receipts open through the link too.
      await entryRow(g, museum).click()
      const museumG = entryDialog(g, L.pot.editEntry)
      await expectReceiptOpens(museumG, L, receiptJpeg, 'image')
      await expectReceiptOpens(museumG, L, receiptPdf, 'pdf')
      await museumG.getByRole('button', { name: L.cancel, exact: true }).click()
    },
  )

  test(
    'step 4c: switched off by the tenant owner, a member\'s and a guest\'s device ask nothing and the rate is typed',
    { tag: '@uc.expenses.exchange-rates:switched-off-or-unreachable-asks-for-the-rate' },
    async () => {
      test.setTimeout(240_000)
      expect(b.confinement.rateQueries(), 'B has written no foreign entry yet').toEqual([])
      expect(guest.confinement.rateQueries(), 'nor has the guest').toEqual([])
      await switchExchangeRates(a.page, L, true)
      try {
        // B, a member: its tenant's set, read anew, lacks the entry.
        await signIn(b.page, L, accountB)
        await enterGroup(b.page, group)
        await openFolder(b.page, folder)
        await openPot(b.page, L, pot)
        const member = await foreignEntry(b.page, L, 'USD')
        await member.dialog.getByLabel(L.pot.title, { exact: true }).fill(recordTyped(titleOf('Off')))
        await expect(member.rate).toHaveValue('')
        await member.dialog.getByRole('button', { name: L.cancel, exact: true }).click()
        await expect(member.dialog).toBeHidden()
        expect(b.confinement.rateQueries(), 'a member\'s device asks nothing while the tenant has the rates off').toEqual([])
        // B's page stays open on the Pot: step 9 removes B from the group while it is (inbox #546).

        // The guest: its client holds the instance's manifest alone, which still has them on; its Pot read says off.
        await openLink(guest.page, shareLink)
        await expect(guest.page.locator('.expenses-entries')).toBeVisible({ timeout: 60_000 })
        const off = await foreignEntry(guest.page, L, 'USD')
        await off.dialog.getByLabel(L.pot.title, { exact: true }).fill(recordTyped(titleOf('Off guest')))
        await expect(off.rate).toHaveValue('')
        await off.dialog.getByRole('button', { name: L.cancel, exact: true }).click()
        await expect(off.dialog).toBeHidden()
        expect(guest.confinement.rateQueries(), 'a guest\'s device asks nothing while the tenant has the rates off').toEqual([])
      } finally {
        await switchExchangeRates(a.page, L, false)
      }

      // Control: switched on, the same guest's device fetches once and fills the rate.
      await openLink(guest.page, shareLink)
      await expect(guest.page.locator('.expenses-entries')).toBeVisible({ timeout: 60_000 })
      const on = await foreignEntry(guest.page, L, 'USD')
      await expect(on.rate).toHaveValue(USD_IN_EUR, { timeout: 30_000 })
      await on.dialog.getByRole('button', { name: L.cancel, exact: true }).click()
      await expect(on.dialog).toBeHidden()
      expect(guest.confinement.rateQueries()).toHaveLength(1)
      expectBareRateQueries(guest)

      // A back on the Pot, where step 5 goes on.
      await signIn(a.page, L, accountA)
      await enterGroup(a.page, group)
      await openFolder(a.page, folder)
      await openPot(a.page, L, pot)
    },
  )

  test(
    'step 4d: who am I — a member and a guest each mark their own row, kept on the device over a reload, nothing sent',
    { tag: '@uc.expenses.balances:who-am-i-stays-on-the-device' },
    async () => {
      test.setTimeout(180_000)
      const marked = (page: Page) => page.locator('.expenses-balances li').filter({ has: page.locator('.expenses-you') })
      /** Choose [name] in the Balances tab: no request but reads while choosing, and the row is marked. */
      async function choose(page: Page, name: string): Promise<void> {
        await openTab(page, L.pot.tabs.balances)
        await expect(marked(page)).toHaveCount(0)
        const sent: string[] = []
        const watch = (r: { method(): string; url(): string }) => {
          if (r.method() !== 'GET') sent.push(`${r.method()} ${new URL(r.url()).pathname}`)
        }
        page.on('request', watch)
        await pick(page, page.getByRole('combobox', { name: L.pot.whoAmI }), name)
        await expect(marked(page).locator('.veyq-row-title')).toHaveText([name])
        await expect(marked(page).locator('.expenses-you')).toHaveText([L.pot.you])
        page.off('request', watch)
        expect(sent, 'choosing who one is sends nothing').toEqual([])
      }
      await choose(a.page, PEOPLE[0]!)
      const g = guest.page
      await choose(g, PEOPLE[1]!)
      // Each device keeps its own choice: a reload, the member's and the guest's.
      await a.page.reload()
      await expect(a.page.getByRole('heading', { level: 1, name: pot })).toBeVisible({ timeout: 60_000 })
      await expect(a.page.locator('.expenses-entries')).toBeVisible({ timeout: 60_000 })
      await openTab(a.page, L.pot.tabs.balances)
      await expect(marked(a.page).locator('.veyq-row-title')).toHaveText([PEOPLE[0]!])
      await openLink(g, shareLink)
      await expect(g.locator('.expenses-entries')).toBeVisible({ timeout: 60_000 })
      await openTab(g, L.pot.tabs.balances)
      await expect(marked(g).locator('.veyq-row-title')).toHaveText([PEOPLE[1]!])
      // The overview marks the same row.
      await openTab(g, L.pot.tabs.overview)
      await expect(g.locator('.expenses-overview tr').filter({ has: g.locator('.expenses-you') }).locator('th')).toContainText([PEOPLE[1]!])
      // Back to "not chosen", so the later steps read the rows as they were.
      for (const page of [a.page, g]) {
        await openTab(page, L.pot.tabs.balances)
        await pick(page, page.getByRole('combobox', { name: L.pot.whoAmI }), L.pot.nobody)
        await expect(marked(page)).toHaveCount(0)
      }
      await openTab(a.page, L.pot.tabs.entries)
      await openTab(g, L.pot.tabs.entries)
    },
  )

  test(
    'step 5: a deleted entry leaves the balances and its restore brings them back, with no ledger notice',
    {
      tag: [
        '@uc.expenses.record-entries:delete-is-a-tombstone-revision',
        '@uc.expenses.entry-history:deleted-entries-are-restorable',
        '@uc.expenses.verify-ledger:every-revision-names-the-heads',
      ],
    },
    async () => {
      const { page } = a
      const taxi = entryOf('Taxi')
      const taxiTitle = titleOf('Taxi')
      await openTab(page, L.pot.tabs.entries)
      await entryRow(page, taxiTitle).click()
      await entryDialog(page, L.pot.editEntry).getByRole('button', { name: L.pot.delete, exact: true }).click()
      await expect(entryDialog(page, L.pot.editEntry)).toBeHidden({ timeout: 30_000 })
      await expect(page.getByRole('heading', { name: L.pot.deletedEntries, exact: true })).toBeVisible()
      await expect(entryRow(page, taxiTitle).locator('.veyq-badge')).toHaveText(L.pot.deletedBadge)

      // Without the taxi: what it paid and what it shared, taken out of the vector's balances.
      const effect = trip.expected.entries[taxi.id]!
      const without = Object.fromEntries(Object.entries(trip.expected.balances).map(([id, v]) =>
        [id, v - (effect.paid?.[id] ?? 0) + (effect.shares?.[id] ?? 0)]))
      await expectBalances(page, L, balanceRows(L, lang, without, [dora]), null)
      expect(Object.values(without), 'the taxi moved a balance').not.toEqual(Object.values(trip.expected.balances))

      await openTab(page, L.pot.tabs.entries)
      await entryRow(page, taxiTitle).click()
      await entryDialog(page, L.pot.viewEntry).getByRole('button', { name: L.pot.restore, exact: true }).click()
      await expect(entryDialog(page, L.pot.viewEntry)).toBeHidden({ timeout: 30_000 })
      await expect(page.getByRole('heading', { name: L.pot.deletedEntries, exact: true })).toHaveCount(0)
      await expectBalances(page, L, balanceRows(L, lang, trip.expected.balances, [dora]), transferRows(L, lang))
      await page.reload()
      await expect(page.locator('.expenses-entries')).toBeVisible({ timeout: 60_000 })
      await expect(ledgerNotice(page)).toHaveCount(0)
    },
  )

  test(
    'step 6: the settle-up recorded; a settled person deactivated, activated and deleted, an unnamed one removed',
    {
      tag: [
        '@uc.expenses.balances:settle-up-is-greedy-and-deterministic',
        '@uc.expenses.manage-participants:member-deactivates-a-settled-participant',
        '@uc.expenses.manage-participants:member-deletes-and-the-name-is-erased',
        '@uc.expenses.manage-participants:member-removes-an-unnamed-participant',
      ],
    },
    async () => {
      const { page } = a
      await openTab(page, L.pot.tabs.balances)
      await page.locator('.expenses-balances').getByRole('button', { name: L.pot.recordAll, exact: true }).click()
      await expect(page.locator('.expenses-balances').getByText(L.pot.allSettled, { exact: true })).toBeVisible({ timeout: 30_000 })

      await openTab(page, L.pot.tabs.people)
      const people = page.locator('.expenses-people')
      const row = (name: string) => people.locator('li').filter({ has: page.locator('.veyq-row-title').getByText(name, { exact: true }) })
      const cleo = nameOf(CLEO!)
      await row(cleo).getByRole('button', { name: L.pot.deactivate, exact: true }).click()
      await expect(row(cleo).getByText(L.pot.inactive, { exact: true })).toBeVisible({ timeout: 30_000 })
      await row(cleo).getByRole('button', { name: L.pot.activate, exact: true }).click()
      await expect(row(cleo).getByText(L.pot.inactive, { exact: true })).toHaveCount(0, { timeout: 30_000 })

      await row(cleo).getByRole('button', { name: L.pot.deletePerson(cleo), exact: true }).click()
      const confirmDelete = page.getByRole('dialog', { name: L.pot.deletePerson(cleo), exact: true })
      await confirmDelete.getByRole('button', { name: L.pot.deleteAccept, exact: true }).click()
      await expect(row(cleo)).toHaveCount(0, { timeout: 30_000 })
      await expect(people.getByText(L.pot.oneDeleted, { exact: true })).toBeVisible()

      await row(dora).getByRole('button', { name: L.pot.removePerson(dora), exact: true }).click()
      const confirmRemove = page.getByRole('dialog', { name: L.pot.removePerson(dora), exact: true })
      await confirmRemove.getByRole('button', { name: L.pot.removeAccept, exact: true }).click()
      await expect(row(dora)).toHaveCount(0, { timeout: 30_000 })
      await expect(people.locator('li .veyq-row-title')).toHaveText(PEOPLE.slice(0, 2))
      await expect(people.getByText(L.pot.oneDeleted, { exact: true })).toBeVisible()

      // Cleo's entries name "deleted people"; the removed person appears nowhere.
      await openTab(page, L.pot.tabs.entries)
      await expect(entryRow(page, titleOf('Museum')).locator('.veyq-row-subtitle')).toContainText(L.pot.paidBy(L.pot.deletedPeople))
      for (const tab of [L.pot.tabs.entries, L.pot.tabs.balances, L.pot.tabs.overview, L.pot.tabs.people]) {
        await openTab(page, tab)
        await expect(page.locator('.expenses-pot').getByText(dora), `${tab} names no removed person`).toHaveCount(0)
      }

      // Neither is offered for a new entry.
      await openTab(page, L.pot.tabs.entries)
      await page.locator('.expenses-entries').getByRole('button', { name: L.pot.addEntry, exact: true }).click()
      const editor = entryDialog(page, L.pot.addEntry)
      await expect(editor.getByText(L.pot.everyone(2), { exact: true })).toBeVisible()
      await editor.getByRole('button', { name: L.pot.adjust, exact: true }).click()
      const sharing = editor.getByRole('combobox', { name: L.pot.splitPerson, exact: true })
      await expect(sharing).toHaveCount(2)
      for (const [i, n] of PEOPLE.slice(0, 2).entries()) await expect(sharing.nth(i)).toHaveText(n)
      await editor.getByRole('combobox', { name: L.pot.person, exact: true }).click()
      await expect(page.getByRole('option')).toHaveText(PEOPLE.slice(0, 2))
      await page.keyboard.press('Escape')
      await editor.getByRole('button', { name: L.cancel, exact: true }).click()
      await expect(editor).toBeHidden()
    },
  )

  test(
    'step 7: the Pot downloaded and imported into another folder, then copied: both show its balances, overview and receipts',
    {
      tag: [
        '@uc.platform.export-import:expenses-content',
        '@uc.platform.copy-node:a-pot-copies-its-live-entries',
        '@uc.expenses.attach-receipts:receipts-are-blobs-of-the-entry',
      ],
    },
    async () => {
      test.setTimeout(300_000)
      const { page } = a
      before = await figures(page, L)
      expect(before.settled, 'everyone is settled after step 6').toBe(true)
      const total = await expectOverviewAddsUp(page, L)
      expect(total, 'the overview still counts the costs').toBeGreaterThan(0)

      // Download: the Pot's header action, saved as a ZIP.
      await page.evaluate(() => void delete (window as unknown as { showSaveFilePicker?: unknown }).showSaveFilePicker)
      await headerAction(page, L.download)
      const exportDialog = page.getByRole('dialog', { name: L.exportTitle(pot), exact: true })
      await expect(exportDialog.getByTestId('export-summary')).toBeVisible({ timeout: 60_000 })
      const started = page.waitForEvent('download')
      await exportDialog.getByRole('button', { name: L.saveZip, exact: true }).click()
      const download = await started
      expect(download.suggestedFilename()).toBe(`${pot}.zip`)
      const zip = readFileSync(await download.path())
      await expect(toast(page, L.downloadSaved, lang)).toBeVisible({ timeout: 60_000 })
      // The receipts the entries hold sit beside the Pot's content under their own names, byte for byte; the one
      // removed in step 2b is not carried.
      const archived = readZip(zip)
      for (const [name, bytes] of [[receiptJpeg, PHOTO], [receiptPdf, admissionPdf()], [guestJpeg, PHOTO]] as const) {
        const held = archived.get(`${pot}/${name}`)
        expect(held, `the ZIP holds ${name}`).toBeDefined()
        expect(held!.equals(bytes), `${name} byte for byte`).toBe(true)
      }
      expect([...archived.keys()].filter((path) => path.includes(falsePdf)), 'the removed receipt is not in the ZIP').toEqual([])

      // Import: the ZIP picked for an upload in another folder of the group.
      await signIn(page, L, accountA)
      await enterGroup(page, group)
      await createFolder(page, L, importFolder)
      await openFolder(page, importFolder)
      const chooser = page.waitForEvent('filechooser')
      await page.getByRole('button', { name: L.new, exact: true }).click()
      await page.getByRole('menuitem', { name: L.uploadFiles, exact: true }).click()
      await (await chooser).setFiles({ name: recordTyped(`${pot}.zip`), mimeType: 'application/zip', buffer: zip })
      const importDialog = page.getByRole('dialog', { name: L.importTitle, exact: true })
      await importDialog.getByRole('button', { name: L.importSubmit, exact: true }).click({ timeout: 60_000 })
      await expect(toast(page, L.imported(pot), lang)).toBeVisible({ timeout: 90_000 })
      await openPot(page, L, pot)
      expect(await figures(page, L), 'the imported Pot').toEqual(before)
      await expectCarriedReceipts(page, 'the imported Pot')

      // Copy: "Copy to…" from the Pot's page, into its own folder (the copy is named apart there).
      await signIn(page, L, accountA)
      await enterGroup(page, group)
      await openFolder(page, folder)
      await openPot(page, L, pot)
      await headerAction(page, L.copyTo)
      const copy = page.getByRole('dialog', { name: L.copyTitle(pot), exact: true })
      await expect(copy.getByTestId('copy-losses')).toContainText(L.pot.copyLoss, { timeout: 60_000 })
      await expect(copy.getByTestId('copy-losses'), 'the receipts come along: no loss names them').not.toContainText(L.pot.receipts.word)
      await copy.getByTestId('copy-submit').click()
      // Beside its original the copy is named apart (core's `Name (2)`), and the toast names it so.
      const copyName = `${pot} (2)`
      await expect(toast(page, L.copied(copyName, folder), lang)).toBeVisible({ timeout: 90_000 })
      await signIn(page, L, accountA)
      await enterGroup(page, group)
      await openFolder(page, folder)
      await expect(page.locator('li.veyq-level-item[data-kind="EXPENSES_POT"]')).toHaveCount(2, { timeout: 60_000 })
      await openPot(page, L, copyName)
      expect(await figures(page, L), 'the copied Pot').toEqual(before)
      await expectCarriedReceipts(page, 'the copied Pot')
    },
  )

  test(
    'step 8: the guest reports an entry; the moderator reads every version of it in plaintext',
    { tag: '@uc.moderation.report:an-expenses-entry-is-reported-whole' },
    async () => {
      test.setTimeout(240_000)
      const g = guest.page
      await openLink(g, shareLink)
      await expect(g.locator('.expenses-entries')).toBeVisible({ timeout: 60_000 })
      // The guest's page, read anew, names neither the deleted nor the removed person.
      await openTab(g, L.pot.tabs.people)
      await expect(g.locator('.expenses-people li .veyq-row-title')).toHaveText(PEOPLE.slice(0, 2))
      await openTab(g, L.pot.tabs.entries)
      await entryRow(g, dinner).click()
      await entryDialog(g, L.pot.editEntry).getByRole('button', { name: L.report, exact: true }).click()
      const dialog = g.getByRole('dialog', { name: L.report, exact: true })
      await expect(dialog).toBeVisible()
      await dialog.locator('.veyq-select').click()
      await g.getByRole('option', { name: L.reportCategory, exact: true }).click()
      await dialog.locator('#veyq-report-reason').fill(recordTyped(reason))
      await dialog.getByText(L.goodFaith, { exact: true }).click()
      await dialog.getByRole('button', { name: L.reportSend, exact: true }).click()
      await expect(dialog.getByText(L.reportSent, { exact: true })).toBeVisible({ timeout: 60_000 })
      await dialog.locator('.veyq-report-receipt').getByRole('button', { name: L.close, exact: true }).click()
      await expect(dialog).toBeHidden()
      // The report dialog opened above the entry dialog, which stayed open beneath it (inbox #545): every click above
      // reached the report dialog, as Playwright's hit test asks, and the entry dialog is back on top.
      const entry = entryDialog(g, L.pot.editEntry)
      await expect(entry, 'the entry dialog stayed open beneath the report').toBeVisible()
      await entry.getByRole('button', { name: L.cancel, exact: true }).click()
      await expect(entry).toBeHidden()

      // The moderator works where the Server Owner is a member, the shared tenant; the case row names this tenant.
      const m = moderator.page
      await expect(async () => {
        await m.goto(`${tenantOrigin()}/moderation`)
      }).toPass({ timeout: 60_000, intervals: [1_000] })
      await passGate(m, L)
      const caseRow = m
        .locator('.moderation-case-row')
        .filter({ hasText: potDomain() })
        .filter({ hasText: L.itemKind })
        .filter({ hasText: L.reportCategory })
        .filter({ has: m.locator('.veyq-badge', { hasText: new RegExp(`^${L.caseOpen}$`) }) })
      await expect(caseRow).toHaveCount(1, { timeout: 60_000 })
      await caseRow.getByRole('button', { name: L.openCase, exact: true }).click()
      await expect(m.locator('.moderation-report')).toContainText(reason)
      await m.getByRole('button', { name: L.review, exact: true }).click()
      await expect(m).toHaveURL(new RegExp(`^${escape(potOrigin())}/s/[0-9a-f-]{36}#moderation$`), { timeout: 60_000 })
      await passGate(m, L)
      const review = m.locator('.expenses-review')
      await expect(review.getByRole('heading', { name: L.pot.reviewTitle })).toBeVisible({ timeout: 60_000 })
      const hundred = squash(money(10_000, 'EUR', 2, lang))
      const versions = review.locator('li .veyq-row-title')
      await expect(versions).toHaveCount(3)
      const read = (await versions.allTextContents()).map(squash)
      for (const [i, title] of [dinner, dinnerB, dinner].entries()) {
        expect(read[i], `version ${3 - i}`).toContain(title)
        expect(read[i], `version ${3 - i}`).toContain(hundred)
      }
      // The case read names no writer.
      for (const writer of [handles.owner, handles.member]) await expect(review).not.toContainText(writer)

      // The case is dismissed again: the shared queue is left as this file found it (moderation.spec.ts, which runs
      // later, starts from "Nothing has been reported.", and the other moderation scenarios each count their own case).
      await expect(async () => {
        await m.goto(`${tenantOrigin()}/moderation`)
      }).toPass({ timeout: 60_000, intervals: [1_000] })
      await passGate(m, L)
      await expect(caseRow).toHaveCount(1, { timeout: 60_000 })
      await caseRow.getByRole('button', { name: L.openCase, exact: true }).click()
      await m.locator('.moderation-dismiss').click()
      const confirm = m.getByRole('dialog', { name: L.dismissCase, exact: true })
      await confirm.getByRole('button', { name: L.dismissCase, exact: true }).click()
      await expect(confirm).toBeHidden()
      await expect(m.locator('.moderation-case .veyq-badge', { hasText: new RegExp(`^${L.caseOpen}$`) })).toHaveCount(0, { timeout: 30_000 })
      await m.goto(`${tenantOrigin()}/moderation`)
      await passGate(m, L)
      await expect(m.locator('.moderation-case-row').filter({ hasText: potDomain() })).toHaveCount(0, { timeout: 60_000 })
    },
  )

  test(
    'step 9: a member removed from the group rotates its key and re-keys the Pot; every figure and the link stay',
    {
      tag: [
        '@uc.spaces.rekey-after-rotation:pot-revisions-rewrapped-bodies-unchanged',
        '@uc.spaces.manage-group-members:remove-rotates-forward',
        '@uc.expenses.verify-ledger:every-revision-names-the-heads',
      ],
    },
    async () => {
      test.setTimeout(300_000)
      const { page } = a
      await signIn(page, L, accountA)
      await openGroupSettings(page, L, group)
      const row = memberRow(page, handles.member)
      await row.getByRole('button', { name: L.remove, exact: true }).click()
      await expect(row).toContainText(L.removeConfirm)
      await row.getByRole('button', { name: L.remove, exact: true }).click()
      await expect(row).toHaveCount(0, { timeout: 180_000 })
      // The re-key ran to its end: the group no longer reads as having its new lock fitted.
      await expect(async () => {
        await signIn(page, L, accountA)
        await openGroupSettings(page, L, group)
        await expect(memberRow(page, handles.owner)).toBeVisible({ timeout: 15_000 })
        await expect(page.getByText(L.rekeying, { exact: true })).toHaveCount(0)
      }).toPass({ timeout: 120_000, intervals: [3_000] })

      await signIn(page, L, accountA)
      await enterGroup(page, group)
      await openFolder(page, folder)
      await openPot(page, L, pot)
      await expect(ledgerNotice(page)).toHaveCount(0)
      expect(await figures(page, L), 'the Pot after the re-key').toEqual(before)
      await openTab(page, L.pot.tabs.entries)
      await expect(page.locator('.expenses-entries').getByText(L.pot.invalid, { exact: true })).toHaveCount(0)

      // Through the guest's link, as before.
      const g = guest.page
      await openLink(g, shareLink)
      await expect(g.getByRole('heading', { level: 1, name: pot })).toBeVisible({ timeout: 60_000 })
      await expect(g.locator('.expenses-entries')).toBeVisible({ timeout: 60_000 })
      await expect(ledgerNotice(g)).toHaveCount(0)
      expect(await figures(g, L), 'the Pot through the link after the re-key').toEqual(before)
      // B's page, left open on the Pot since step 4c (inbox #546): the scope's reads are answered 404 now, and the page
      // says the access ended and asks no more — instead of polling on, every read answered 404.
      await expect(b.page.getByRole('heading', { name: L.accessEnded })).toBeVisible({ timeout: 120_000 })
      await expect(b.page.locator('.expenses-pot')).toHaveCount(0)
      const gone = /^GET 404 \/api\/scopes\/[^/]+\//
      const asked = failures.errors.filter((e) => gone.test(e)).length
      expect(asked, 'the page learnt it from a 404 of its own scope').toBeGreaterThan(0)
      await b.page.waitForTimeout(30_000) // more than the tree's and the Pot's poll intervals, their backoff included
      expect(failures.errors.filter((e) => gone.test(e)), 'and asked nothing of the scope since').toHaveLength(asked)
      for (let i = failures.errors.length - 1; i >= 0; i--) if (gone.test(failures.errors[i]!)) failures.errors.splice(i, 1)
      for (let i = consoleErrors.errors.length - 1; i >= 0; i--) if (/status of 404\b/.test(consoleErrors.errors[i]!)) consoleErrors.errors.splice(i, 1)
      // B, removed, no longer lists the group.
      await signIn(b.page, L, accountB)
      await expect(b.page.locator('a.desktop-group-link').filter({ hasText: group })).toHaveCount(0)
    },
  )

  test(
    'step 9b: after the rotation every receipt still opens, for the member and through the link',
    { tag: ['@uc.expenses.attach-receipts:receipts-are-blobs-of-the-entry'] },
    async () => {
      test.setTimeout(240_000)
      const { page } = a
      await openTab(page, L.pot.tabs.entries)
      await entryRow(page, museum).click()
      const museumA = entryDialog(page, L.pot.editEntry)
      await expectReceiptOpens(museumA, L, receiptJpeg, 'image')
      await expectReceiptOpens(museumA, L, receiptPdf, 'pdf')
      await museumA.getByRole('button', { name: L.cancel, exact: true }).click()
      await entryRow(page, snacks).click()
      const snacksA = entryDialog(page, L.pot.editEntry)
      await expectReceiptOpens(snacksA, L, guestJpeg, 'image')
      await snacksA.getByRole('button', { name: L.cancel, exact: true }).click()

      const g = guest.page
      await openTab(g, L.pot.tabs.entries)
      await entryRow(g, snacks).click()
      const snacksG = entryDialog(g, L.pot.editEntry)
      await expectReceiptOpens(snacksG, L, guestJpeg, 'image')
      await snacksG.getByRole('button', { name: L.cancel, exact: true }).click()
      await entryRow(g, museum).click()
      const museumG = entryDialog(g, L.pot.editEntry)
      await expectReceiptOpens(museumG, L, receiptPdf, 'pdf')
      await museumG.getByRole('button', { name: L.cancel, exact: true }).click()
    },
  )

  test(
    'step 10: the Pot at 375 px — Entries, Balances, the editor with items, People — scrolls only down',
    {
      tag: [
        '@uc.expenses.balances:balances-from-the-live-entries',
        '@uc.expenses.record-entries:allocation-and-rounding-are-exact-and-equal-everywhere',
      ],
    },
    async () => {
      const { page } = a
      await page.setViewportSize({ width: 375, height: 812 })
      await page.reload()
      await expect(page.locator('.expenses-entries')).toBeVisible({ timeout: 60_000 })
      for (const tab of [L.pot.tabs.entries, L.pot.tabs.balances, L.pot.tabs.overview, L.pot.tabs.people]) {
        await inReach(potTab(page, tab), `the ${tab} tab`)
      }

      await openTab(page, L.pot.tabs.entries)
      await noHorizontalScroll(page, L.pot.tabs.entries)
      await inReach(page.locator('.expenses-entries').getByRole('button', { name: L.pot.addEntry, exact: true }), L.pot.addEntry)

      const read = await readBalances(page, L)
      await noHorizontalScroll(page, L.pot.tabs.balances)
      expect(read.settled, 'the settled Pot reads as settled').toBe(true)
      expect(read.rows.map((r) => r[1]), 'every shown person settled').toEqual(read.rows.map(() => L.pot.settled))

      await openTab(page, L.pot.tabs.people)
      await noHorizontalScroll(page, L.pot.tabs.people)
      for (const name of PEOPLE.slice(0, 2)) {
        await inReach(page.locator('.expenses-people li').filter({ hasText: name }).getByRole('button', { name: L.pot.deactivate, exact: true }), `${L.pot.deactivate} ${name}`)
      }
      await inReach(page.locator('.expenses-people form').getByRole('button', { name: L.pot.addPerson, exact: true }), L.pot.addPerson)

      // The editor with items and a rest: the trattoria.
      await openTab(page, L.pot.tabs.entries)
      await entryRow(page, titleOf('Trattoria')).click()
      const editor = entryDialog(page, L.pot.editEntry)
      await expect(editor.locator('.expenses-item:not(.expenses-rest)')).toHaveCount(3)
      await expect(editor.locator('.expenses-rest')).toHaveCount(1)
      await noHorizontalScroll(page, L.pot.editEntry)
      const dialogFits = await editor.evaluate((el) => el.scrollWidth <= el.clientWidth)
      expect(dialogFits, 'the editor scrolls only down').toBe(true)
      for (const [label, button] of [
        [L.pot.addItem, editor.getByRole('button', { name: L.pot.addItem, exact: true })],
        [L.pot.history, editor.getByRole('button', { name: L.pot.history, exact: true })],
        [L.pot.delete, editor.getByRole('button', { name: L.pot.delete, exact: true })],
        [L.save, editor.getByRole('button', { name: L.save, exact: true })],
        [L.cancel, editor.getByRole('button', { name: L.cancel, exact: true })],
      ] as const) {
        await inReach(button, label)
      }
      await editor.getByRole('button', { name: L.cancel, exact: true }).click()
      await expect(editor).toBeHidden()
      await page.setViewportSize({ width: 1280, height: 720 })
    },
  )
})


// The German pass (ADR 0090 decision 9): only what a browser alone shows in German — amounts typed with a decimal
// comma, a foreign currency's rate filled from the day's table under a German date and typed with a comma, the
// balances and the settle-up in German number format, the editor's stale-version banner and a toast with German
// quotes, and the Pot at 375 px with its long German words. In a browser context of that locale: the app follows
// `navigator.language`, so every label is the catalog's German one. That the catalogs carry the same keys in both
// languages, every key used, is src/web/'s i18n.test.ts; the flow itself ran in English above.
test.describe('auf Deutsch', () => {
  const lang = 'de' as const
  const L = LABELS[lang]
  const group = groups[lang]
  const folder = `Reiseordner ${stamp}`
  const pot = `Reise ans Meer ${stamp}`
  const dinner = titleOf('Dinner')
  const dinnerB = titleOf('Abendessen am Hafen')
  const dinnerA = titleOf('Abendessen zu dritt')

  let a: Device
  let b: Device
  let consoleErrors: ConsoleErrors
  let failures: ConsoleErrors

  test.beforeAll(async ({ browser }) => {
    a = await newDevice(browser, { locale: 'de-DE' })
    b = await newDevice(browser, { locale: 'de-DE' })
    consoleErrors = watchConsole(a.page, b.page)
    failures = watchFailedRequests(a.page, b.page)
  })

  test.afterEach(() => {
    for (const d of [a, b]) d?.confinement.assertConfined()
    expect(consoleErrors.errors, 'console errors').toEqual([])
    expect(failures.errors, 'failed requests').toEqual([])
  })

  test.afterAll(async () => {
    for (const d of [a, b]) await d?.context.close()
  })

  test(
    'a Pot made and the "trip" vector typed in German: decimal commas, a foreign currency, balances and settle-up in German format',
    {
      tag: [
        '@uc.expenses.create-pot:member-creates-a-pot',
        '@uc.expenses.record-entries:allocation-and-rounding-are-exact-and-equal-everywhere',
        '@uc.expenses.balances:balances-from-the-live-entries',
        '@uc.expenses.balances:settle-up-is-greedy-and-deterministic',
        '@uc.expenses.exchange-rates:the-days-table-straight-from-the-device',
      ],
    },
    async () => {
      test.setTimeout(360_000)
      const { page } = a
      await signIn(page, L, accountA)
      consoleErrors.clear()
      failures.clear()
      await enterGroup(page, group)
      await createFolder(page, L, folder)
      await openFolder(page, folder)

      await page.getByRole('button', { name: L.new, exact: true }).click()
      await page.getByRole('menuitem', { name: L.pot.newPot, exact: true }).click()
      const dialog = page.getByRole('dialog', { name: L.pot.newPot, exact: true })
      await dialog.getByRole('textbox', { name: L.name, exact: true }).fill(recordTyped(pot))
      await pick(page, dialog.getByRole('combobox', { name: L.pot.baseCurrency }), 'EUR')
      await addPotNames(page, L, dialog, PEOPLE)
      await dialog.getByRole('button', { name: L.create, exact: true }).click()
      await expect(dialog).toBeHidden({ timeout: 30_000 })
      const row = page.locator('li.veyq-level-item[data-kind="EXPENSES_POT"]').filter({ hasText: pot })
      await expect(row.locator('.veyq-level-item-meta')).toContainText(L.pot.kindPot, { timeout: 30_000 })
      await openPot(page, L, pot)

      // Every amount typed the German way (`typed`: a decimal comma), the JPY entry at its typed rate among them.
      for (const entry of trip.entries) await typeEntry(page, L, lang, entry, names, titleOf(entry.body.title))
      await expect(page.locator('.expenses-entries .expenses-row')).toHaveCount(trip.entries.length)
      await expect(page.locator('.expenses-entries').getByText(L.pot.invalid, { exact: true }), 'no entry is marked invalid').toHaveCount(0)
      await expectBalances(page, L, balanceRows(L, lang, trip.expected.balances), transferRows(L, lang))
      await expectOverviewAddsUp(page, L)

      // A USD entry: its rate filled from the day's table, the table's day written the German way; typed, it stands.
      expect(a.confinement.rateQueries(), 'one request for the day').toHaveLength(1)
      const { dialog: editor, rate } = await foreignEntry(page, L, 'USD')
      await expect(rate).toHaveValue(USD_IN_EUR, { timeout: 30_000 })
      await expect(editor.getByText(L.pot.rateFilled(FIXTURE_DAY[lang]), { exact: true })).toBeVisible()
      await rate.fill(typedRate('0.9', lang))
      await expect(editor.getByText(L.pot.rateFilled(FIXTURE_DAY[lang]), { exact: true })).toHaveCount(0)
      await editor.getByRole('button', { name: L.cancel, exact: true }).click()
      await expect(editor).toBeHidden()
      expectBareRateQueries(a)
    },
  )

  test(
    'in German: the stale-version banner, the copy\'s toast, and the Pot at 375 px with its long words',
    {
      tag: [
        '@uc.expenses.record-entries:revision-n-plus-one-or-stale',
        '@uc.platform.copy-node:a-pot-copies-its-live-entries',
        '@uc.expenses.balances:balances-from-the-live-entries',
      ],
    },
    async () => {
      test.setTimeout(300_000)
      const { page } = a
      // The banner: B saves "Dinner" while A's editor is open on it; A's save is told, in German, and shown B's version.
      await openTab(page, L.pot.tabs.entries)
      await entryRow(page, dinner).click()
      const editorA = entryDialog(page, L.pot.editEntry)
      await expect(editorA.getByLabel(L.pot.title, { exact: true })).toHaveValue(dinner)
      const pb = b.page
      await signIn(pb, L, accountB)
      await enterGroup(pb, group)
      await openFolder(pb, folder)
      await openPot(pb, L, pot)
      await entryRow(pb, dinner).click()
      const editorB = entryDialog(pb, L.pot.editEntry)
      await editorB.getByLabel(L.pot.title, { exact: true }).fill(recordTyped(dinnerB))
      await editorB.getByRole('button', { name: L.save, exact: true }).click()
      await expect(editorB).toBeHidden({ timeout: 30_000 })
      await pb.goto('about:blank')
      await editorA.getByLabel(L.pot.title, { exact: true }).fill(recordTyped(dinnerA))
      await editorA.getByRole('button', { name: L.save, exact: true }).click()
      await expect(editorA.getByText(L.pot.stale, { exact: true })).toBeVisible({ timeout: 30_000 })
      await expect(editorA.getByLabel(L.pot.title, { exact: true })).toHaveValue(dinnerB)
      await editorA.getByLabel(L.pot.title, { exact: true }).fill(dinner)
      await editorA.getByRole('button', { name: L.save, exact: true }).click()
      await expect(editorA).toBeHidden({ timeout: 30_000 })
      // The one failed request the banner provokes: A's stale append, answered 409, and Chromium's console line for it.
      const append = /^POST 409 \/api\/scopes\/[^/]+\/pots\/[^/]+\/revisions$/
      expect(failures.errors.filter((e) => append.test(e)), 'exactly one stale append').toHaveLength(1)
      failures.errors.splice(failures.errors.findIndex((e) => append.test(e)), 1)
      const logged = (e: string) => /status of 409\b/.test(e) && /\/pots\/[^/]+\/revisions\b/.test(e)
      expect(consoleErrors.errors.filter(logged), 'its console line').toHaveLength(1)
      consoleErrors.errors.splice(consoleErrors.errors.findIndex(logged), 1)

      // The toast: "Kopieren nach …" into the Pot's own folder; the toast names the copy in German quotes, and the copy
      // shows the original's figures.
      const original = await figures(page, L)
      await headerAction(page, L.copyTo)
      const copy = page.getByRole('dialog', { name: L.copyTitle(pot), exact: true })
      await expect(copy.getByTestId('copy-losses')).toContainText(L.pot.copyLoss, { timeout: 60_000 })
      await copy.getByTestId('copy-submit').click()
      const copyName = `${pot} (2)`
      await expect(toast(page, L.copied(copyName, folder), lang)).toBeVisible({ timeout: 90_000 })
      await signIn(page, L, accountA)
      await enterGroup(page, group)
      await openFolder(page, folder)
      await openPot(page, L, copyName)
      expect(await figures(page, L), 'the copied Pot').toEqual(original)

      // 375 px: the German tab names, the balances' long lines and the editor with items scroll only down.
      await page.setViewportSize({ width: 375, height: 812 })
      await page.reload()
      await expect(page.locator('.expenses-entries')).toBeVisible({ timeout: 60_000 })
      for (const tab of [L.pot.tabs.entries, L.pot.tabs.balances, L.pot.tabs.overview, L.pot.tabs.people]) {
        await inReach(potTab(page, tab), `the ${tab} tab`)
      }
      await openTab(page, L.pot.tabs.entries)
      await noHorizontalScroll(page, L.pot.tabs.entries)
      await expectBalances(page, L, balanceRows(L, lang, trip.expected.balances), transferRows(L, lang))
      await noHorizontalScroll(page, L.pot.tabs.balances)
      await inReach(page.locator('.expenses-balances').getByRole('button', { name: L.pot.recordAll, exact: true }), L.pot.recordAll)
      await openTab(page, L.pot.tabs.overview)
      await noHorizontalScroll(page, L.pot.tabs.overview)
      await openTab(page, L.pot.tabs.entries)
      await entryRow(page, titleOf('Trattoria')).click()
      const editor = entryDialog(page, L.pot.editEntry)
      await expect(editor.locator('.expenses-item:not(.expenses-rest)')).toHaveCount(3)
      await expect(editor.locator('.expenses-rest')).toHaveCount(1)
      await noHorizontalScroll(page, L.pot.editEntry)
      expect(await editor.evaluate((el) => el.scrollWidth <= el.clientWidth), 'the editor scrolls only down').toBe(true)
      for (const name of [L.pot.addItem, L.pot.history, L.save, L.cancel]) {
        await inReach(editor.getByRole('button', { name, exact: true }), name)
      }
      await editor.getByRole('button', { name: L.cancel, exact: true }).click()
      await expect(editor).toBeHidden()
      await page.setViewportSize({ width: 1280, height: 720 })
    },
  )
})
