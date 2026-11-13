// An application left switched off is erased on its day, in a browser (uc.platform.erase-switched-off-application; ADR
// 0079 decision 8, ADR 0088 decision 5). The lane's overlay shortens the window to thirty seconds and runs the purge
// every five seconds (`VEYQ_APPLICATION_OFF_RETENTION`, `VEYQ_APPLICATION_ERASURE_PURGE_EVERY`; the overlay says why those
// numbers), so the suite waits out one day. Everything happens in a tenant of its own ("E2E Erasure"), so what it erases
// no other spec reads:
//
//   1. the Server Owner creates the tenant for a fresh owner, who sets the space's mail to the run's relay and invites a
//      fresh member by mail with a code; the member joins holding the space's key (ADR 0111);
//   2. the owner keeps a Depot file in a folder of the shared space (beside a subfolder) and a Memory at its root; the
//      member keeps a Depot file in a folder of their personal space;
//   3. the owner switches Depot off in Settings → Capabilities: the confirmation shows the catalog's `whenOff`, the owner's
//      row names the day, the member's banner names it — in English, and once in German, a date the browser writes in
//      its locale (ADR 0090 decision 9) — the notice reaches the owner and the member through the instance mail (set up
//      by instance-mail.spec.ts in the `instance` project), and the file still downloads while the member is offered no
//      upload;
//   4. switched on before the day, the row's day and the banner are gone; switched off again, a fresh day and a fresh
//      notice;
//   5. left off past that day: both files are gone, for the member and in the personal space; the folders, the subfolder
//      and the Memory stay; still off, a fresh day starts and a fresh notice goes; switched on, it ends.
//
// Not driven here, each for its reason (ADR 0090 decision 9: a rule a slice test proves with an injected clock is not
// proven again here by waiting real time): that a switch-on before the day leaves everything in place once the day has
// passed (ApplicationErasureSliceTest.aTenantSwitchOffNamesTheDayAndASwitchOnBeforeItCancelsIt); the reminder (seven
// days before the day — a window short enough to wait out has none; ApplicationErasureSliceTest drives it with a
// clock); and a switch-on AFTER the day (the purge ticks every five seconds, so a switch-on between the day and the
// tick that erases is a race with the tick's phase, not a state a browser can hold; ApplicationErasureSweepTest drives
// it). The labels are src/web/'s own (src/web/src/i18n/index.ts, en and de), the entry's words the catalog's
// (contracts/platform/capabilities.json, which the image serves), the mail's ApplicationErasureMail's.
import { expect, type Locator, type Page } from '@playwright/test'
import { test } from '../../../src/cspViolations'
import { randomBytes } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { freshOwner, readKit } from '../../../src/accounts'
import { recordTyped } from '../../../src/canaries'
import { birthTenant, submitSignIn, type Account } from '../../../src/console'
import { createFolder, downloadButton, pickFiles, sha256 } from '../../../src/depot'
import { newDevice, type Device } from '../../../src/devices'
import { awaitMails } from '../../../src/mail'
import { stack } from '../../../src/stack'
import { toast } from '../../../src/toast'

test.describe.configure({ mode: 'serial' })

interface Entry {
  title: { en: string }
  whenOff: { en: string }
}

const CATALOG = fileURLToPath(new URL('../../../../../contracts/platform/capabilities.json', import.meta.url))
const DEPOT = (JSON.parse(readFileSync(CATALOG, 'utf8')) as { capabilities: Record<string, Entry> }).capabilities.depot!

// The relay as an owner types it into the space's mail settings (tenant-and-account-erasure.spec.ts types the same).
const RELAY = { host: 'mail.relay.test', port: '1025' }
const TENANT = 'E2E Erasure'
// ApplicationErasureMail's notice: one subject, EN / DE.
const NOTICE = /^veyq: Depot is switched off in a space you belong to \/ /
// The overlay's window and the purge's tick (`VEYQ_APPLICATION_OFF_RETENTION`, `VEYQ_APPLICATION_ERASURE_PURGE_EVERY`).
const WINDOW_MS = 30_000
const TICK_MS = 5_000

const LABELS = {
  en: { signIn: 'Sign in', email: 'Email', password: /^Password\b/, new: 'New' },
  de: { signIn: 'Anmelden', email: 'E-Mail', password: /^Passwort\b/, new: 'Neu' },
} as const
type Lang = keyof typeof LABELS

const stamp = randomBytes(3).toString('hex')
const domain = () => `erasure.${stack().domain}`
const origin = () => `https://${domain()}`
// Per-tenant handles: declared plaintext (`pii`), so no canaries.
const handles = { owner: `erasure-owner-${stamp}`, member: `erasure-member-${stamp}` }
// Core folders (they organise only, so they stay), a Memory (another application, so it stays) and two Depot files.
const sharedFolder = `Steuerunterlagen ${stamp}`
const subfolder = `Archiv der Belege ${stamp}`
const personalFolder = `Meine Ablage ${stamp}`
const memory = `Sommerfest am See ${stamp}`
const sharedFile = { name: `Kontoauszug März ${stamp}.bin`, bytes: new Uint8Array(randomBytes(200_000)) }
const personalFile = { name: `Gehaltsabrechnung Mai ${stamp}.bin`, bytes: new Uint8Array(randomBytes(150_000)) }

let admin: Device // the Server Owner's console
let owner: Device // the tenant's owner, in English
let member: Device // the member, in English
let memberDe: Device // the same member, a second browser in German
let accountOwner: Account
let accountMember: Account
/** The day of the first switch-off, cancelled before it came, and of the second, left to come. */
let cancelledDay: Date
let erasureDay: Date

const escape = (text: string) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

/** The space's home on this device, signing in when the document asks (a kept session asks the password alone); retried
 *  while the fresh tenant's routers reach Traefik. */
async function reachHome(device: Device, account: Account, lang: Lang = 'en'): Promise<void> {
  const { page } = device
  const L = LABELS[lang]
  await expect(async () => {
    const response = await page.goto(origin())
    expect(response?.ok(), `${origin()} serves the app`).toBe(true)
  }).toPass({ timeout: 60_000, intervals: [1_000] })
  const home = page.getByRole('button', { name: L.new, exact: true })
  const email = page.getByLabel(L.email, { exact: true })
  await expect(home.or(email)).toBeVisible({ timeout: 60_000 })
  if (await email.isVisible()) {
    if (await email.isEditable()) await email.fill(account.email)
    await page.getByRole('textbox', { name: L.password }).fill(recordTyped(account.password))
    const secretKey = page.locator('#unlock-secret-key')
    if (await secretKey.isVisible()) await secretKey.fill(recordTyped(account.secretKey))
    await page.getByRole('button', { name: L.signIn, exact: true }).click()
  }
  await expect(home).toBeVisible({ timeout: 60_000 })
}

/** A section of the owner's Settings, from the space's home (devices.ts's `openSettings`, on this tenant). */
async function openSettings(section: string): Promise<void> {
  const { page } = owner
  await reachHome(owner, accountOwner)
  await page.locator('.profile-button').click()
  await page.getByRole('menuitem', { name: 'Settings' }).click()
  await page.getByRole('navigation', { name: 'Settings' }).getByRole('button', { name: section, exact: true }).click()
}

const depotRow = (page: Page): Locator => page.locator('li.capability[data-id="depot"]')
const erasureLine = (page: Page): Locator => depotRow(page).locator('.capability__erasure')
const banner = (page: Page): Locator => page.locator('.application-off-banner')

interface TenantSet {
  capabilities: { id: string }[]
  applicationErasures: { id: string; dueAt: string }[]
}

/** The open tenant's set as the member's next document reads it at boot (`getTenantCapabilities`): registered before
 *  the navigation, so an absence asserted after it is the client's answer to that set, not to a set not yet read. */
const tenantSet = (page: Page): Promise<TenantSet> =>
  page.waitForResponse((r) => r.request().method() === 'GET' && r.ok()
    && /^\/api\/tenants\/[^/]+\/capabilities$/.test(new URL(r.url()).pathname), { timeout: 60_000 })
    .then((r) => r.json() as Promise<TenantSet>)

/** The owner's Capabilities, with the switch-list the panel reads on opening: Depot's `erasureDueAt`, if any. */
async function openCapabilities(): Promise<Date | null> {
  const listed = owner.page.waitForResponse((r) =>
    r.request().method() === 'GET' && new URL(r.url()).pathname.endsWith('/capability-switches') && r.ok())
  await openSettings('Capabilities')
  const entries = (await (await listed).json()) as { id: string; erasureDueAt?: string | null }[]
  await expect(depotRow(owner.page)).toBeVisible()
  const due = entries.find((e) => e.id === 'depot')?.erasureDueAt
  return due ? new Date(due) : null
}

/** Depot switched off on the owner's open Capabilities: the confirmation shows the catalog's `whenOff` word for word,
 *  and the day is the one the switch's answer names. */
async function switchDepotOff(): Promise<Date> {
  const { page } = owner
  const answered = page.waitForResponse((r) =>
    r.request().method() === 'PUT' && new URL(r.url()).pathname.endsWith('/capability-switches/depot'))
  await depotRow(page).getByRole('button', { name: 'Switch off', exact: true }).click()
  const confirm = page.getByRole('dialog', { name: `Switch off “${DEPOT.title.en}”?` })
  await expect(confirm.getByText(DEPOT.whenOff.en, { exact: true })).toBeVisible()
  await confirm.getByRole('button', { name: 'Switch off', exact: true }).click()
  await expect(confirm).toBeHidden()
  const answer = await answered
  expect(answer.ok(), 'the switch-off is taken').toBe(true)
  const due = ((await answer.json()) as { erasureDueAt?: string | null }).erasureDueAt
  expect(due, 'the switch-off names the day').toBeTruthy()
  await expect(depotRow(page).getByRole('button', { name: 'Switch on', exact: true })).toBeVisible()
  return new Date(due!)
}

async function switchDepotOn(): Promise<void> {
  const { page } = owner
  await depotRow(page).getByRole('button', { name: 'Switch on', exact: true }).click()
  await expect(depotRow(page).getByRole('button', { name: 'Switch off', exact: true })).toBeVisible()
}

/** [day] as the web client writes it in [lang] (`toLocaleDateString` with the app's locale), read in the page. */
const onScreen = (page: Page, day: Date, lang: Lang) =>
  page.evaluate(([iso, locale]) => new Date(iso).toLocaleDateString(locale), [day.toISOString(), lang] as const)
/** [day] as the mails write it: `yyyy-MM-dd`, UTC. */
const inMail = (day: Date) => day.toISOString().slice(0, 10)

/** Wait until [day] plus [ticks] of the purge has passed: the purge has had its turn at that day, so what is read
 *  afterwards is what it left (and is still polled, for a tick that ran late). */
async function passDay(page: Page, day: Date, ticks: number): Promise<void> {
  const wait = day.getTime() + ticks * TICK_MS - Date.now()
  if (wait > 0) await page.waitForTimeout(wait)
}

/** The shared space's folder, opened from its root on the member's device. */
async function openSharedFolder(): Promise<void> {
  const { page } = member
  await reachHome(member, accountMember)
  await page.getByRole('button', { name: sharedFolder, exact: true }).click({ timeout: 60_000 })
  await expect(page.getByRole('heading', { name: sharedFolder })).toBeVisible()
}

/** The member's personal space, its folder opened. */
async function openPersonalFolder(): Promise<void> {
  const { page } = member
  await reachHome(member, accountMember)
  await page.getByRole('navigation', { name: 'Spaces' }).getByRole('link', { name: 'Personal', exact: true }).click()
  await page.getByRole('button', { name: personalFolder, exact: true }).click({ timeout: 60_000 })
  await expect(page.getByRole('heading', { name: personalFolder })).toBeVisible()
}

/** A file's bytes, saved through the browser's download (the SW's route: no picker offered), as depot-files.spec.ts does. */
async function downloaded(page: Page, name: string): Promise<string> {
  await page.evaluate(() => void delete (window as unknown as { showSaveFilePicker?: unknown }).showSaveFilePicker)
  await expect(downloadButton(page, name)).toBeEnabled({ timeout: 60_000 })
  const started = page.waitForEvent('download')
  await downloadButton(page, name).click()
  const download = await started
  expect(await download.failure()).toBeNull()
  return sha256(readFileSync(await download.path()))
}

/** Both notices so far reached the owner and the member — [count] each, the newest naming [day]. */
async function expectNotices(count: number, day: Date): Promise<void> {
  for (const to of [accountOwner.email, accountMember.email]) {
    const mails = await awaitMails(to, NOTICE, count)
    const text = mails[mails.length - 1]!.text.replace(/\s+/g, ' ')
    expect(text).toContain(`The owner of the space switched Depot off in the space "${TENANT}".`)
    expect(text).toContain(`until ${inMail(day)}; then it is erased, unless Depot is switched on again before.`)
    expect(text).toContain(`bis zum ${inMail(day)} öffnen und herunterladen; dann wird es gelöscht`)
  }
}

test.beforeAll(async ({ browser }) => {
  admin = await newDevice(browser)
  owner = await newDevice(browser)
  member = await newDevice(browser)
  memberDe = await newDevice(browser, { locale: 'de-DE' })
})

test.afterEach(() => {
  for (const d of [admin, owner, member, memberDe]) d?.confinement.assertConfined()
})

test.afterAll(async () => {
  for (const d of [admin, owner, member, memberDe]) await d?.context.close()
})

test(
  'a tenant of its own: its owner, and a member invited by mail who is given the space\'s key',
  {
    tag: [
      '@uc.admin.create-tenant:create-pending-tenant',
      '@uc.admin.create-tenant:invite-link-handoff',
      '@uc.spaces.add-member:member-invite-and-onboard',
    ],
  },
  async () => {
    accountOwner = await birthTenant(admin, { name: TENANT, domain: domain(), handle: handles.owner }, owner)
    const { page } = owner

    // The space's own mail, pointed at the run's relay: member invitations go out by mail only.
    await openSettings('Email')
    await expect(page.getByRole('heading', { level: 2, name: 'Email' })).toBeVisible()
    await page.getByRole('textbox', { name: /^SMTP host/ }).fill(RELAY.host)
    await page.getByRole('textbox', { name: /^Port/ }).fill(RELAY.port)
    await page.getByRole('textbox', { name: /^From address/ }).fill(`space-mail@${domain()}`)
    await page.getByRole('switch', { name: /^Use STARTTLS/ }).uncheck()
    await page.getByRole('switch', { name: /^Send invitations by email/ }).check()
    await page.getByRole('button', { name: 'Save email settings' }).click()
    await expect(toast(page, 'Email settings saved.')).toBeVisible()

    const fresh = freshOwner(`e2e.${stack().domain}`)
    await page.getByRole('navigation', { name: 'Settings' }).getByRole('button', { name: 'Members', exact: true }).click()
    await page.getByRole('button', { name: 'Invite member' }).click()
    const invite = page.getByRole('dialog', { name: 'Invite member' })
    // The dialog opens on sharing a link (ADR 0129 decision 1); this member is invited by mail.
    await invite.getByRole('button', { name: 'Invite by email' }).click()
    await invite.getByLabel('Email address').fill(fresh.email)
    await invite.getByRole('button', { name: 'Send invite' }).click()
    await expect(invite.getByText(`Invitation sent to ${fresh.email}.`)).toBeVisible({ timeout: 30_000 })
    // The code is shown once, here, and travels outside veyq (ADR 0111 decision 1): never in the mail, never on the wire.
    const code = recordTyped((await invite.getByTestId('invite-code').textContent())!.trim())
    await invite.getByRole('button', { name: 'Done' }).click()

    const [mail] = await awaitMails(fresh.email, new RegExp(`^You're invited to ${escape(domain())} `))
    const link = /https:\/\/\S+/.exec(mail!.text)?.[0]
    expect(link, `an invite link in:\n${mail!.text}`).toBeDefined()
    const tab = member.page
    await tab.goto(link!)
    await expect(tab.getByText(`You have been invited to join “${TENANT}” (${domain()}).`)).toBeVisible({ timeout: 60_000 })
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
    accountMember = { email: fresh.email, password: fresh.password, secretKey: kit.secretKey }
    await tab.getByRole('button', { name: 'Sign in', exact: true }).click()
    await submitSignIn(tab, accountMember)
    await expect(tab.locator('.profile-button')).toBeVisible({ timeout: 60_000 })

    // The member granted itself the space's key at its accept (ADR 0111): its row is Active at once, no delivery waited for.
    await openSettings('Members')
    await expect(page.locator('.member-row').filter({ hasText: `@${handles.member}` }).locator('.veyq-badge').first())
      .toHaveText('Active', { timeout: 30_000 })
  },
)

test(
  'Depot files in a folder of the shared space and of the member\'s personal space, a Memory beside them',
  { tag: '@uc.depot.member-files:upload-attaches-one-sealed-blob' },
  async () => {
    const { page } = owner
    await reachHome(owner, accountOwner)
    await page.getByRole('button', { name: 'New', exact: true }).click()
    await page.getByRole('menuitem', { name: 'New Memory' }).click()
    const dialog = page.getByRole('dialog', { name: 'Create a Memory' })
    await dialog.getByLabel('Name').fill(recordTyped(memory))
    await dialog.getByRole('button', { name: 'Create', exact: true }).click()
    await expect(dialog).toBeHidden()

    await reachHome(owner, accountOwner)
    await createFolder(page, sharedFolder)
    await createFolder(page, subfolder)
    await reachHome(owner, accountOwner)
    await page.getByRole('button', { name: sharedFolder, exact: true }).click({ timeout: 60_000 })
    await expect(page.getByRole('heading', { name: sharedFolder })).toBeVisible()
    await pickFiles(page, [sharedFile])
    await expect(downloadButton(page, sharedFile.name)).toBeEnabled({ timeout: 60_000 })

    // The member's own space, which only the member's devices hold the key to.
    const m = member.page
    await reachHome(member, accountMember)
    await m.getByRole('navigation', { name: 'Spaces' }).getByRole('link', { name: 'Personal', exact: true }).click()
    await createFolder(m, personalFolder)
    await pickFiles(m, [personalFile])
    await expect(downloadButton(m, personalFile.name)).toBeEnabled({ timeout: 60_000 })

    // The member reads the shared folder: the subfolder and the file. The German browser signs in now, so the switch-off
    // below only reloads it.
    await openSharedFolder()
    await expect(m.getByRole('button', { name: subfolder, exact: true })).toBeVisible()
    await expect(downloadButton(m, sharedFile.name)).toBeEnabled({ timeout: 60_000 })
    await reachHome(memberDe, accountMember, 'de')
  },
)

test(
  'switched off, the owner\'s row and the member\'s banner name the day in English and German, both are mailed it, and the file still downloads',
  {
    tag: [
      '@uc.platform.erase-switched-off-application:the-day-is-set-once',
      '@uc.platform.erase-switched-off-application:every-member-is-told-the-day',
      '@uc.platform.erase-switched-off-application:the-day-is-served',
      '@uc.platform.switch-application-off:the-client-removes-every-write',
      '@uc.platform.switch-application-off:reads-downloads-and-removals-stay',
    ],
  },
  async () => {
    const { page } = owner
    expect(await openCapabilities(), 'Depot is on: no day').toBeNull()
    await expect(erasureLine(page)).toHaveCount(0)
    cancelledDay = await switchDepotOff()
    const windowMs = cancelledDay.getTime() - Date.now()
    expect(windowMs, 'the day is the overlay\'s window away').toBeGreaterThan(WINDOW_MS / 2)
    expect(windowMs).toBeLessThanOrEqual(WINDOW_MS)
    await expect(erasureLine(page)).toHaveText(`Erased on ${await onScreen(page, cancelledDay, 'en')} unless switched on again`)

    // The member's banner, in English, at the top of the folder that holds the file, which still downloads — and the
    // folder's New menu offers a folder and no upload.
    const m = member.page
    const served = tenantSet(m)
    await openSharedFolder()
    expect((await served).applicationErasures.map((e) => ({ id: e.id, dueAt: new Date(e.dueAt).getTime() })),
      'the member is served the day').toEqual([{ id: 'depot', dueAt: cancelledDay.getTime() }])
    await expect(banner(m)).toHaveText(
      `Depot is switched off here. You can open and download what’s here until ${await onScreen(m, cancelledDay, 'en')}; `
        + 'then it is erased. Save what you want to keep.')
    expect(await downloaded(m, sharedFile.name), 'the file downloads as it was uploaded').toBe(sha256(sharedFile.bytes))
    await m.getByRole('button', { name: 'New', exact: true }).click()
    await expect(m.getByRole('menuitem', { name: 'New folder' })).toBeVisible()
    await expect(m.getByRole('menuitem', { name: 'Upload files' })).toHaveCount(0)
    await m.keyboard.press('Escape')

    // …and in German: the day as a German browser writes it.
    const de = memberDe.page
    await reachHome(memberDe, accountMember, 'de')
    await expect(banner(de)).toHaveText(
      `Depot ist hier abgeschaltet. Was da ist, kannst du bis zum ${await onScreen(de, cancelledDay, 'de')} öffnen und `
        + 'herunterladen; dann wird es gelöscht. Sichere, was du behalten willst.')

    // The notice, within a tick, to the owner and the member alike.
    await expectNotices(1, cancelledDay)
  },
)

test(
  'switched on before the day, the day and the banner are gone; off again, a fresh day and a fresh notice',
  { tag: '@uc.platform.erase-switched-off-application:switched-on-before-the-day-nothing-is-lost' },
  async () => {
    const { page } = owner
    expect(cancelledDay.getTime() - Date.now(),
      'the switch-on comes before the day (a slower run needs a longer VEYQ_APPLICATION_OFF_RETENTION)').toBeGreaterThan(10_000)
    await switchDepotOn()
    await expect(erasureLine(page)).toHaveCount(0)
    // The member's next document is served Depot on and no day: the folder's New menu offers the upload again, the file
    // is there, and no banner shows.
    const m = member.page
    const served = tenantSet(m)
    await openSharedFolder()
    const set = await served
    expect(set.capabilities.map((c) => c.id)).toContain('depot')
    expect(set.applicationErasures).toEqual([])
    await expect(m.getByRole('button', { name: subfolder, exact: true })).toBeVisible()
    await expect(downloadButton(m, sharedFile.name)).toBeEnabled({ timeout: 60_000 })
    await m.getByRole('button', { name: 'New', exact: true }).click()
    await expect(m.getByRole('menuitem', { name: 'Upload files' })).toBeVisible()
    await m.keyboard.press('Escape')
    await expect(banner(m)).toHaveCount(0)

    // Off again: a fresh day, later than the cancelled one, and a fresh notice naming it.
    erasureDay = await switchDepotOff()
    expect(erasureDay.getTime()).toBeGreaterThan(cancelledDay.getTime())
    await expectNotices(2, erasureDay)
  },
)

test(
  'left off past its day, its files are gone in the shared and the personal space, the folders and the Memory stay, and a fresh day starts',
  {
    tag: [
      '@uc.platform.erase-switched-off-application:on-the-day-its-nodes-are-erased',
      '@uc.platform.erase-switched-off-application:switched-on-before-the-day-nothing-is-lost',
    ],
  },
  async () => {
    // Up to the day, at most its window, plus the wait for the purge's tick.
    test.setTimeout(150_000)
    const m = member.page
    await passDay(m, erasureDay, 1)

    // The shared folder, read afresh: its subfolder is there (the listing is read), the file is not.
    await expect(async () => {
      await openSharedFolder()
      await expect(m.getByRole('button', { name: subfolder, exact: true })).toBeVisible()
      await expect(m.getByText(sharedFile.name)).toHaveCount(0, { timeout: 2_000 })
    }).toPass({ timeout: 60_000, intervals: [2_000] })
    await expect(downloadButton(m, sharedFile.name)).toHaveCount(0)

    // The personal space's folder stays, its file is gone.
    await openPersonalFolder()
    await expect(m.getByText(personalFile.name)).toHaveCount(0)

    // Another application's content stays: the Memory beside the folder, opened.
    await reachHome(member, accountMember)
    await m.getByRole('button', { name: memory, exact: true }).click({ timeout: 60_000 })
    await expect(m.getByRole('heading', { level: 1, name: memory })).toBeVisible()

    // Still off once the erasure ended: a fresh day from then, on the owner's row and in a fresh notice.
    const { page } = owner
    const fresh: { day: Date | null } = { day: null }
    await expect(async () => {
      fresh.day = await openCapabilities()
      expect(fresh.day?.getTime() ?? 0).toBeGreaterThan(erasureDay.getTime())
    }).toPass({ timeout: 30_000, intervals: [2_000] })
    await expect(erasureLine(page)).toHaveText(`Erased on ${await onScreen(page, fresh.day!, 'en')} unless switched on again`)
    await expectNotices(3, fresh.day!)

    // Switched on before that day, it ends: the space is left with Depot on.
    await switchDepotOn()
    await expect(erasureLine(page)).toHaveCount(0)
  },
)
