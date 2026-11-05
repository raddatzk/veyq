// A profile picture set at join, and the one-time "finish your profile" notice, in a browser (inbox #414's two open
// browser proofs; ADR 0084 decision 7, uc.spaces.set-profile-picture A2 and A3). field-seal.spec.ts sets a picture in
// Settings → Your profile; this file covers the two other places the use-case offers one:
//
//   1. At join, as a tenant OWNER (A2): the owner cold-births `K_tenant` at accept, so the join page's picker — pick,
//      frame in the cropper, use — seals the picture with the name and sends it with the accept. A new tenant, created
//      by the Server Owner, is accepted by a fresh account that way; signed in on a new document, its sidebar avatar is
//      the picture (fetched as ciphertext, opened on the device), its name reads back, and no notice asks for either.
//   2. At join, as a MEMBER (A3): the code opens the invite's grant, so the member holds `K_tenant` at accept and the
//      join page offers a name and a picture too (ADR 0111) — both optional, and this member skips them. That owner
//      sets its space's mail to the run's relay and invites a fresh account, which registers from the mailed link with
//      the code the owner was shown; its row is Active at once, no delivery waited for. A member who skipped both is
//      what the notice exists for. On the member's next document the notice shows — once: "Not now" stores
//      the dismissal for that user on that browser (`veyq.finishProfileDismissed:u:<userId>` in localStorage, a flag,
//      never an image), so a reload shows nothing; on a second browser of the same member it shows again (the flag is
//      per browser by design), and following its link to Your profile dismisses it there too. The member, now holding
//      the key, also sees the picture the owner set at join, on the owner's row of Members.
//
// Everything happens in a tenant of its own ("E2E Picture"), so the shared tenant every domain project starts from is
// untouched; the tenant stays, as moderation.spec.ts's and server-owner-statement.spec.ts's do. Left out: that a member
// holding no current key sees no notice — a new member holds it from its accept on, so that state has no window a
// browser could hold still; the notice's absence before the key is FinishProfilePrompt.test.ts's. The labels are src/web/'s and admin-web's own (each
// package's i18n, en). Every name and secret typed is a canary (recordTyped); the handles are declared plaintext.
import { expect, type Locator, type Page } from '@playwright/test'
import { test } from '../../../src/cspViolations'
import { randomBytes } from 'node:crypto'
import { freshOwner, readKit } from '../../../src/accounts'
import { recordTyped } from '../../../src/canaries'
import { instance, newDevice, passwordField, type Device } from '../../../src/devices'
import { awaitMails } from '../../../src/mail'
import { stack } from '../../../src/stack'
import { toast } from '../../../src/toast'

test.describe.configure({ mode: 'serial' })

interface Account {
  email: string
  password: string
  secretKey: string
}

// The relay as an owner types it into the space's mail settings (tenant-and-account-erasure.spec.ts types the same).
const RELAY = { host: 'mail.relay.test', port: '1025' }
const TENANT = 'E2E Picture'
// What FinishProfilePrompt stores on "Not now" or its link: `createScopedPreference`'s key for the signed-in account.
const DISMISSED_KEY_PREFIX = 'veyq.finishProfileDismissed:u:'

const stamp = randomBytes(3).toString('hex')
const pictureDomain = () => `picture.${stack().domain}`
const pictureOrigin = () => `https://${pictureDomain()}`
// Per-tenant handles: declared plaintext (`pii`), so no canaries.
const handles = { owner: `picture-owner-${stamp}`, member: `picture-member-${stamp}` }
const firstName = `Hildegard ${stamp}`
const lastName = `Großkopf-Wäscher ${stamp}`

let admin: Device // the Server Owner's console
let owner: Device // the tenant's owner, who picks a picture at join
let member: Device // the member it invites, first browser
let memberSecond: Device // the same member, a second browser
let accountOwner: Account
let accountMember: Account

const escape = (text: string) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

/** The console, signed in — a context that already signed in opens it signed in; a new document asks first. */
async function openConsole(page: Page): Promise<void> {
  const { owner: serverOwner } = instance()
  await page.goto(stack().adminOrigin)
  const home = page.getByRole('heading', { name: 'Server administration', level: 1 })
  const email = page.getByLabel('Email', { exact: true })
  await expect(home.or(email)).toBeVisible({ timeout: 60_000 })
  if (await email.isVisible()) {
    await email.fill(serverOwner.email)
    await passwordField(page).fill(recordTyped(serverOwner.password))
    await page.getByRole('button', { name: 'Sign in' }).click()
  }
  await expect(home).toBeVisible({ timeout: 60_000 })
}

/** The sign-in form of a device that holds no key for the account: email, password and Secret Key. */
async function submitSignIn(page: Page, account: Account): Promise<void> {
  await page.getByLabel('Email', { exact: true }).fill(account.email)
  await passwordField(page).fill(recordTyped(account.password))
  await page.locator('#unlock-secret-key').fill(recordTyped(account.secretKey))
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
}

const newButton = (page: Page) => page.getByRole('button', { name: 'New', exact: true })

/**
 * The space's home on the picture tenant, signing in first when this document asks for it — [ready] says the home is
 * up. Retried while the domain answers the proxy's own 404 (a fresh tenant's routers reach Traefik a moment late).
 */
async function reachHome(device: Device, account: Account, ready: (page: Page) => Locator = newButton): Promise<void> {
  const { page } = device
  await expect(async () => {
    const response = await page.goto(pictureOrigin())
    expect(response?.ok(), `${pictureOrigin()} serves the app`).toBe(true)
  }).toPass({ timeout: 60_000, intervals: [1_000] })
  const home = ready(page)
  const email = page.getByLabel('Email', { exact: true })
  await expect(home.or(email)).toBeVisible({ timeout: 60_000 })
  if (await email.isVisible()) await submitSignIn(page, account)
  await expect(home).toBeVisible({ timeout: 60_000 })
}

/** A section of Settings, reached as devices.ts's `openSettings` does — from the space's home. */
async function openSettingsOn(device: Device, account: Account, section: string): Promise<void> {
  const { page } = device
  await reachHome(device, account)
  await page.locator('.profile-button').click()
  await page.getByRole('menuitem', { name: 'Settings' }).click()
  await page.getByRole('navigation', { name: 'Settings' }).getByRole('button', { name: section, exact: true }).click()
}

/** A row of the members panel, by the per-tenant handle it shows (`@handle`). */
const memberRow = (page: Page, handle: string): Locator => page.locator('.member-row').filter({ hasText: `@${handle}` })

/** The one-time notice (FinishProfilePrompt.vue: a region named by its title). */
const finishNotice = (page: Page) => page.getByRole('region', { name: 'Finish your profile' })

/** The values this browser stores under the dismissal's key — one per account that dismissed the notice here. */
const dismissals = (page: Page) =>
  page.evaluate((prefix) => Object.keys(localStorage).filter((k) => k.startsWith(prefix)).map((k) => localStorage.getItem(k)),
    DISMISSED_KEY_PREFIX)

/**
 * The notice stays away on this document. Its absence is a race against its own check (two reads once the space is
 * ready), so the home's toolbar comes first — the space is open, the check has had its trigger — and the absence is
 * then held for a few seconds, longer than those two reads take. The positive control is the second browser's test,
 * where the same check on the same account shows it.
 */
async function noticeStaysAway(page: Page): Promise<void> {
  await expect(newButton(page)).toBeVisible({ timeout: 60_000 })
  await page.waitForTimeout(3_000)
  await expect(finishNotice(page)).toHaveCount(0)
}

/** An avatar's picture, decoded: a blob URL (opened on this device from its ciphertext) whose image has pixels. */
async function expectPicture(avatar: Locator): Promise<void> {
  await expect(avatar).toHaveAttribute('src', /^blob:/, { timeout: 60_000 })
  await expect.poll(() => avatar.evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth),
    { message: 'the profile picture decoded' }).toBeGreaterThan(0)
}

/** A PNG drawn in the page — the profile picture, cropped and sealed on the device that picks it (field-seal.spec.ts's). */
async function drawnPicture(page: Page): Promise<Buffer> {
  const dataUrl = await page.evaluate(() => {
    const canvas = document.createElement('canvas')
    canvas.width = 320
    canvas.height = 320
    const g = canvas.getContext('2d')!
    const fill = g.createLinearGradient(0, 0, 320, 320)
    fill.addColorStop(0, '#1d3557')
    fill.addColorStop(1, '#e9c46a')
    g.fillStyle = fill
    g.fillRect(0, 0, 320, 320)
    return canvas.toDataURL('image/png')
  })
  return Buffer.from(dataUrl.split(',')[1]!, 'base64')
}

test.beforeAll(async ({ browser }) => {
  admin = await newDevice(browser)
  owner = await newDevice(browser)
  member = await newDevice(browser)
  memberSecond = await newDevice(browser)
})

test.afterEach(() => {
  for (const d of [admin, owner, member, memberSecond]) d?.confinement.assertConfined()
})

test.afterAll(async () => {
  for (const d of [admin, owner, member, memberSecond]) await d?.context.close()
})

test(
  'a new tenant\'s owner picks and frames a picture on the join page; signed in, it is their avatar',
  {
    tag: [
      '@uc.spaces.set-profile-picture:set-at-join-or-after-first-unlock',
      '@uc.spaces.set-profile-picture:picture-sealed-on-device-constant-length',
    ],
  },
  async () => {
    // The Server Owner creates the tenant for a fresh account and copies its invite link (tenant-and-account-erasure.spec.ts's birth).
    const fresh = freshOwner(`e2e.${stack().domain}`)
    const consolePage = admin.page
    await openConsole(consolePage)
    await consolePage.getByRole('button', { name: 'Create tenant' }).click()
    const create = consolePage.getByRole('dialog', { name: 'Create tenant' })
    await create.getByLabel('Tenant name').fill(TENANT)
    await create.getByLabel('Domain').fill(pictureDomain())
    await create.getByLabel('Who should own this tenant? (email)').fill(fresh.email)
    await create.getByRole('button', { name: 'Create' }).click()
    await expect(create.getByText('Tenant created.')).toBeVisible()
    await admin.context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: stack().adminOrigin })
    await create.getByRole('button', { name: 'Copy link' }).click()
    const inviteLink = await consolePage.evaluate(() => navigator.clipboard.readText())
    await create.getByRole('button', { name: 'Done' }).click()

    const { page } = owner
    await expect(async () => {
      await page.goto(inviteLink)
    }).toPass({ timeout: 60_000, intervals: [1_000] })
    await expect(page.getByText(`You have been invited to own “${TENANT}” (${pictureDomain()}).`)).toBeVisible()
    await page.getByLabel('Username').fill(handles.owner)
    await page.getByLabel('First name').fill(recordTyped(firstName))
    await page.getByLabel('Last name').fill(recordTyped(lastName))

    // The join page's picker: the profile-picture dialog, a file picked, framed 1:1 and used; the preview shows it.
    const picker = page.getByTestId('join-picture')
    await picker.getByRole('button', { name: 'Choose a picture' }).click()
    const dialog = page.getByRole('dialog', { name: 'Profile picture' })
    await dialog.getByTestId('profile-picture-file')
      .setInputFiles({ name: 'portrait.png', mimeType: 'image/png', buffer: await drawnPicture(page) })
    await dialog.getByTestId('profile-picture-use').click()
    await expect(dialog).toBeHidden()
    await expect(picker.getByRole('button', { name: 'Change picture' })).toBeVisible()
    await expectPicture(picker.locator('[data-avatar-image] img'))

    await page.locator('#join-password').fill(recordTyped(fresh.password))
    await page.getByRole('button', { name: 'Continue' }).click()
    const download = page.waitForEvent('download')
    await page.getByRole('button', { name: 'Download Emergency Kit (PDF)' }).click()
    const kit = await readKit(await download)
    expect(kit.email, 'the kit names the account it was minted for').toBe(fresh.email)
    await page.getByRole('checkbox', { name: 'I have saved my Emergency Kit somewhere safe.' }).check()
    await page.getByRole('button', { name: 'Accept & activate' }).click()
    await expect(page.getByText(`“${TENANT}” is now active and yours.`)).toBeVisible({ timeout: 60_000 })
    accountOwner = { email: fresh.email, password: fresh.password, secretKey: kit.secretKey }

    // A new document, signed in with both secrets: what shows was fetched as ciphertext and opened on this device.
    await page.getByRole('button', { name: 'Sign in', exact: true }).click()
    await submitSignIn(page, accountOwner)
    await expect(newButton(page)).toBeVisible({ timeout: 60_000 })
    await expectPicture(page.locator('.profile-button [data-avatar-image] img'))
    // A name and a picture are set, so the notice has nothing to ask for.
    await noticeStaysAway(page)

    await openSettingsOn(owner, accountOwner, 'Your profile')
    await expect(page.getByRole('heading', { level: 2, name: 'Your profile' })).toBeVisible({ timeout: 60_000 })
    await expect(page.getByLabel('First name')).toHaveValue(firstName, { timeout: 60_000 })
    await expect(page.getByLabel('Last name')).toHaveValue(lastName)
  },
)

test(
  'a member invited by mail opens the invite with its code, skips the name and picture offered, and signs in',
  { tag: '@uc.spaces.set-profile-picture:set-at-join-or-after-first-unlock' },
  async () => {
    const { page } = owner
    // The space's own mail, pointed at the run's relay: member invitations go out by mail only.
    await openSettingsOn(owner, accountOwner, 'Email')
    await expect(page.getByRole('heading', { level: 2, name: 'Email' })).toBeVisible()
    await page.getByRole('textbox', { name: /^SMTP host/ }).fill(RELAY.host)
    await page.getByRole('textbox', { name: /^Port/ }).fill(RELAY.port)
    await page.getByRole('textbox', { name: /^From address/ }).fill(`space-mail@${pictureDomain()}`)
    await page.getByRole('switch', { name: /^Use STARTTLS/ }).uncheck()
    await page.getByRole('switch', { name: /^Send invitations by email/ }).check()
    await page.getByRole('button', { name: 'Save email settings' }).click()
    await expect(toast(page, 'Email settings saved.')).toBeVisible()
    await expect(page.getByText('Ready to send email — new member invitations will be emailed from your server.')).toBeVisible()

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

    const [mail] = await awaitMails(fresh.email, new RegExp(`^You're invited to ${escape(pictureDomain())} `))
    const link = /https:\/\/\S+/.exec(mail!.text)?.[0]
    expect(link, `an invite link in:\n${mail!.text}`).toBeDefined()
    expect(new URL(link!).origin, 'the invite opens on the tenant domain').toBe(pictureOrigin())

    // The link alone opens nothing: the code opens its sealed part, the client checks the invite, and the member then
    // holds the tenant key at accept — so the join page offers a name and a picture, as it does an owner. Both skipped.
    const tab = member.page
    await tab.goto(link!)
    await expect(tab.getByText(`You have been invited to join “${TENANT}” (${pictureDomain()}).`)).toBeVisible({ timeout: 60_000 })
    await tab.getByRole('textbox', { name: 'Code', exact: true }).fill(code)
    await tab.getByRole('button', { name: 'Continue' }).click()
    await expect(tab.getByLabel('Username')).toBeVisible()
    await expect(tab.getByTestId('join-picture'), 'a picture is offered to a member at join').toHaveCount(1)
    await expect(tab.getByLabel('First name'), 'and a name').toHaveCount(1)
    await tab.getByLabel('Username').fill(handles.member)
    await tab.locator('#join-password').fill(recordTyped(fresh.password))
    await tab.getByRole('button', { name: 'Continue' }).click()
    const download = tab.waitForEvent('download')
    await tab.getByRole('button', { name: 'Download Emergency Kit (PDF)' }).click()
    const kit = await readKit(await download)
    expect(kit.email, 'the kit names the account it was minted for').toBe(fresh.email)
    await tab.getByRole('checkbox', { name: 'I have saved my Emergency Kit somewhere safe.' }).check()
    await tab.getByRole('button', { name: 'Accept & join' }).click()
    await expect(tab.getByText(`You’ve joined “${TENANT}”.`)).toBeVisible({ timeout: 60_000 })
    accountMember = { email: fresh.email, password: fresh.password, secretKey: kit.secretKey }

    await tab.getByRole('button', { name: 'Sign in', exact: true }).click()
    await submitSignIn(tab, accountMember)
    await expect(tab.locator('.profile-button')).toBeVisible({ timeout: 60_000 })
  },
)

test(
  'the new member holds the space\'s key from its accept on',
  { tag: '@uc.spaces.set-profile-picture:set-at-join-or-after-first-unlock' },
  async () => {
    const { page } = owner
    // The member granted itself the key at its accept (ADR 0111): its row is Active at once, no delivery waited for.
    await openSettingsOn(owner, accountOwner, 'Members')
    await expect(memberRow(page, handles.member).locator('.veyq-badge').first()).toHaveText('Active', { timeout: 30_000 })
  },
)

test(
  'the member\'s next document shows the notice once; "Not now" keeps it away, and the member sees the owner\'s picture',
  { tag: '@uc.spaces.set-profile-picture:set-at-join-or-after-first-unlock' },
  async () => {
    const { page } = member
    // The key is opened on entry, and the notice decided once per document: a new document shows it.
    await expect(async () => {
      await reachHome(member, accountMember, (p) => p.locator('.profile-button'))
      await expect(finishNotice(page)).toBeVisible({ timeout: 15_000 })
    }).toPass({ timeout: 90_000, intervals: [2_000] })
    const notice = finishNotice(page)
    await expect(notice).toContainText('Add your name and a picture so the others recognize you.')
    expect(await dismissals(page), 'nothing stored before it is dismissed').toEqual([])

    await notice.getByRole('button', { name: 'Not now' }).click()
    await expect(notice).toBeHidden()
    expect(await dismissals(page), 'the dismissal is a flag for this account on this browser').toEqual(['1'])

    // Shown once: a new document of the same browser asks no more.
    await reachHome(member, accountMember)
    await noticeStaysAway(page)

    // The picture the owner sealed at join, read by another member of the space.
    await page.locator('.profile-button').click()
    await page.getByRole('menuitem', { name: 'Settings' }).click()
    await page.getByRole('navigation', { name: 'Settings' }).getByRole('button', { name: 'Members', exact: true }).click()
    await expectPicture(memberRow(page, handles.owner).locator('[data-avatar-image] img'))
  },
)

test(
  'on a second browser the notice shows again, and its link opens Your profile and keeps it away there too',
  { tag: '@uc.spaces.set-profile-picture:set-at-join-or-after-first-unlock' },
  async () => {
    const { page } = memberSecond
    // The dismissal is the first browser's: here, the same account with the key held is asked again.
    await reachHome(memberSecond, accountMember)
    await expect(finishNotice(page)).toBeVisible({ timeout: 60_000 })
    expect(await dismissals(page)).toEqual([])

    await finishNotice(page).getByRole('button', { name: 'Open profile settings' }).click()
    await expect(page.getByRole('heading', { level: 2, name: 'Your profile' })).toBeVisible({ timeout: 60_000 })
    await expect(finishNotice(page)).toHaveCount(0)
    expect(await dismissals(page), 'following the link dismisses it as "Not now" does').toEqual(['1'])

    await reachHome(memberSecond, accountMember)
    await noticeStaysAway(page)
  },
)
