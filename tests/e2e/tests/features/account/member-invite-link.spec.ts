// A member invite handed over as a link (ADR 0129, cr-member-invite-as-a-link phase 3), in a browser:
//
//   1. A tenant of its own ("E2E Link", born as safety-number-qr.spec.ts births "E2E Safety"): the Server Owner creates
//      it for a fresh owner, who points its mail at the run's relay — mail stays a precondition for inviting at all
//      (decision 3). The owner opens "Invite member", which opens on sharing a link, creates one and reads it out of the
//      message the dialog prefills: `…/tenant/accept#<token>.<sealed>.<code>` on the tenant's own domain — and no mail
//      went out for it. The pending list names it "Link invitation".
//   2. A second browser context opens the link: no code is asked for, the page offers an address field, and the newcomer
//      registers with an address it types; the done step says a confirmation mail is on its way, and the relay holds it
//      (decision 4). Signed in, the app carries the unconfirmed-address banner, which has no close (decision 5); the
//      link in the mail confirms the address and the banner is gone. The owner's member list has the newcomer.
//   3. A third context opens the same link: "already been used" (decision 6).
//
// The labels are src/web/'s and admin-web's own (each package's i18n, en). Every secret typed is a canary (recordTyped);
// the handles are declared plaintext, the addresses `pii` needles (freshOwner).
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

// The relay as an owner types it into the space's mail settings (join-profile-picture.spec.ts types the same).
const RELAY = { host: 'mail.relay.test', port: '1025' }
const TENANT = 'E2E Link'

const stamp = randomBytes(3).toString('hex')
const linkDomain = () => `link.${stack().domain}`
const linkOrigin = () => `https://${linkDomain()}`
// Per-tenant handles: declared plaintext (`pii`), so no canaries.
const handles = { owner: `link-owner-${stamp}`, member: `link-member-${stamp}` }

let admin: Device // the Server Owner's console
let owner: Device // the tenant's owner, who creates the link
let invitee: Device // whoever the owner hands the link to
let latecomer: Device // a second holder of the same link
let accountOwner: Account
let accountMember: Account
let inviteLink = ''

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

/** The space's home on the link tenant, signing in first when this document asks for it. Retried while the domain
 *  answers the proxy's own 404 (a fresh tenant's routers reach Traefik a moment late). */
async function reachHome(device: Device, account: Account): Promise<void> {
  const { page } = device
  await expect(async () => {
    const response = await page.goto(linkOrigin())
    expect(response?.ok(), `${linkOrigin()} serves the app`).toBe(true)
  }).toPass({ timeout: 60_000, intervals: [1_000] })
  const email = page.getByLabel('Email', { exact: true })
  await expect(newButton(page).or(email)).toBeVisible({ timeout: 60_000 })
  if (await email.isVisible()) await submitSignIn(page, account)
  await expect(newButton(page)).toBeVisible({ timeout: 60_000 })
}

/** A section of Settings, reached from the space's home. */
async function openSettingsOn(device: Device, account: Account, section: string): Promise<void> {
  const { page } = device
  await reachHome(device, account)
  await page.locator('.profile-button').click()
  await page.getByRole('menuitem', { name: 'Settings' }).click()
  await page.getByRole('navigation', { name: 'Settings' }).getByRole('button', { name: section, exact: true }).click()
}

/** A row of the members panel, by the per-tenant handle it shows (`@handle`). */
const memberRow = (page: Page, handle: string): Locator => page.locator('.member-row').filter({ hasText: `@${handle}` })

/** How many invitation mails for the link tenant the relay holds, to anyone (`mail.ts` reads by recipient; a link has none). */
async function invitationMails(): Promise<number> {
  const response = await fetch(`${stack().mailApi}/api/v1/messages?limit=200`)
  expect(response.ok, 'the mail relay answers').toBe(true)
  const { messages } = (await response.json()) as { messages: { Subject: string }[] }
  return messages.filter((m) => new RegExp(`^You're invited to ${escape(linkDomain())} `).test(m.Subject)).length
}

/** The unconfirmed-address banner (a region named by its label). */
const banner = (page: Page) => page.getByRole('region', { name: 'Email address not confirmed' })

test.beforeAll(async ({ browser }) => {
  admin = await newDevice(browser)
  owner = await newDevice(browser)
  invitee = await newDevice(browser)
  latecomer = await newDevice(browser)
})

test.afterEach(() => {
  for (const d of [admin, owner, invitee, latecomer]) d?.confinement.assertConfined()
})

test.afterAll(async () => {
  for (const d of [admin, owner, invitee, latecomer]) await d?.context.close()
})

test(
  'an owner creates an invite link — the code in it, nothing mailed — and its holder registers with an address it types',
  { tag: '@uc.spaces.add-member:member-invite-and-onboard' },
  async () => {
    // The Server Owner creates the tenant for a fresh account and copies its invite link.
    const fresh = freshOwner(`e2e.${stack().domain}`)
    const consolePage = admin.page
    await openConsole(consolePage)
    await consolePage.getByRole('button', { name: 'Create tenant' }).click()
    const create = consolePage.getByRole('dialog', { name: 'Create tenant' })
    await create.getByLabel('Tenant name').fill(TENANT)
    await create.getByLabel('Domain').fill(linkDomain())
    await create.getByLabel('Who should own this tenant? (email)').fill(fresh.email)
    await create.getByRole('button', { name: 'Create' }).click()
    await expect(create.getByText('Tenant created.')).toBeVisible()
    await admin.context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: stack().adminOrigin })
    await create.getByRole('button', { name: 'Copy link' }).click()
    const ownerInvite = await consolePage.evaluate(() => navigator.clipboard.readText())
    await create.getByRole('button', { name: 'Done' }).click()

    const { page } = owner
    await expect(async () => {
      await page.goto(ownerInvite)
    }).toPass({ timeout: 60_000, intervals: [1_000] })
    await expect(page.getByText(`You have been invited to own “${TENANT}” (${linkDomain()}).`)).toBeVisible()
    await page.getByLabel('Username').fill(handles.owner)
    await page.locator('#join-password').fill(recordTyped(fresh.password))
    await page.getByRole('button', { name: 'Continue' }).click()
    const ownerKit = page.waitForEvent('download')
    await page.getByRole('button', { name: 'Download Emergency Kit (PDF)' }).click()
    const kit = await readKit(await ownerKit)
    await page.getByRole('checkbox', { name: 'I have saved my Emergency Kit somewhere safe.' }).check()
    await page.getByRole('button', { name: 'Accept & activate' }).click()
    await expect(page.getByText(`“${TENANT}” is now active and yours.`)).toBeVisible({ timeout: 60_000 })
    accountOwner = { email: fresh.email, password: fresh.password, secretKey: kit.secretKey }

    // Mail is a precondition for inviting either way (ADR 0129 decision 3): the space's own, pointed at the run's relay.
    await page.getByRole('button', { name: 'Sign in', exact: true }).click()
    await submitSignIn(page, accountOwner)
    await expect(newButton(page)).toBeVisible({ timeout: 60_000 })
    await openSettingsOn(owner, accountOwner, 'Email')
    await expect(page.getByRole('heading', { level: 2, name: 'Email' })).toBeVisible()
    await page.getByRole('textbox', { name: /^SMTP host/ }).fill(RELAY.host)
    await page.getByRole('textbox', { name: /^Port/ }).fill(RELAY.port)
    await page.getByRole('textbox', { name: /^From address/ }).fill(`space-mail@${linkDomain()}`)
    await page.getByRole('switch', { name: /^Use STARTTLS/ }).uncheck()
    await page.getByRole('switch', { name: /^Send invitations by email/ }).check()
    await page.getByRole('button', { name: 'Save email settings' }).click()
    await expect(toast(page, 'Email settings saved.')).toBeVisible()

    // The dialog opens on sharing a link; one button creates it, and the message it prefills holds the link.
    await page.getByRole('navigation', { name: 'Settings' }).getByRole('button', { name: 'Members', exact: true }).click()
    await page.getByRole('button', { name: 'Invite member' }).click()
    const invite = page.getByRole('dialog', { name: 'Invite member' })
    await expect(invite.getByRole('button', { name: 'Share a link' })).toHaveAttribute('aria-pressed', 'true')
    await expect(invite.getByLabel('Email address'), 'a link is bound to nobody: no address to type').toHaveCount(0)
    await invite.getByRole('button', { name: 'Create link' }).click()
    const message = invite.getByTestId('invite-link-message')
    await expect(message).toBeVisible({ timeout: 30_000 })
    const text = await message.inputValue()
    expect(text, 'the message names the space').toContain(TENANT)
    inviteLink = /https:\/\/\S+/.exec(text)?.[0] ?? ''
    const url = new URL(inviteLink)
    expect(url.origin, 'the link opens on the tenant domain').toBe(linkOrigin())
    expect(url.pathname).toBe('/tenant/accept')
    expect(url.hash.slice(1).split('.'), 'token, sealed part and code').toHaveLength(3)
    recordTyped(url.hash.slice(1)) // the whole fragment — token, sealed part and code — never reaches the server readable
    await expect(invite.getByText('Whoever has this link can join. Give it only to the person you invite.')).toBeVisible()
    await invite.getByRole('button', { name: 'Done' }).click()
    await expect(page.locator('.invite-row').filter({ hasText: 'Link invitation' })).toHaveCount(1)
    expect(await invitationMails(), 'the link was handed over, never mailed').toBe(0)

    // The holder opens it in a browser of its own: the code rides in the link, so none is asked for.
    const tab = invitee.page
    const newcomer = freshOwner(`e2e.${stack().domain}`)
    await tab.goto(inviteLink)
    await expect(tab.getByText(`You have been invited to join “${TENANT}” (${linkDomain()}).`)).toBeVisible({ timeout: 60_000 })
    await expect(tab.getByRole('textbox', { name: 'Code', exact: true })).toHaveCount(0)
    expect(new URL(tab.url()).hash, 'the page strips the fragment once it read it').toBe('')
    await tab.getByLabel('Username').fill(handles.member)
    await tab.getByLabel('Email', { exact: true }).fill(newcomer.email)
    await tab.locator('#join-password').fill(recordTyped(newcomer.password))
    await tab.getByRole('button', { name: 'Continue' }).click()
    const memberKit = tab.waitForEvent('download')
    await tab.getByRole('button', { name: 'Download Emergency Kit (PDF)' }).click()
    const kitMember = await readKit(await memberKit)
    expect(kitMember.email, 'the kit names the address typed').toBe(newcomer.email)
    await tab.getByRole('checkbox', { name: 'I have saved my Emergency Kit somewhere safe.' }).check()
    await tab.getByRole('button', { name: 'Accept & join' }).click()
    await expect(tab.getByText(`You’ve joined “${TENANT}”.`)).toBeVisible({ timeout: 60_000 })
    // A link proves no address (decision 4): the new account asked for its confirmation mail, once.
    await expect(tab.getByText(`A confirmation is on its way to ${newcomer.email}`)).toBeVisible()
    const [confirmation] = await awaitMails(newcomer.email, new RegExp(`^Confirm your email address on ${escape(linkDomain())} `))
    accountMember = { email: newcomer.email, password: newcomer.password, secretKey: kitMember.secretKey }

    // Signed in, the app says the address is unconfirmed, and offers no way to put that away (decision 5).
    await tab.getByRole('button', { name: 'Sign in', exact: true }).click()
    await submitSignIn(tab, accountMember)
    await expect(newButton(tab)).toBeVisible({ timeout: 60_000 })
    await expect(banner(tab)).toContainText(newcomer.email)
    await expect(banner(tab).getByRole('button')).toHaveText(['Send confirmation mail'])

    // The mailed link confirms it, and the banner is gone.
    const confirmLink = /https:\/\/\S+\/confirm-email#\S+/.exec(confirmation!.text)?.[0]
    expect(confirmLink, `a confirmation link in:\n${confirmation!.text}`).toBeDefined()
    await tab.goto(confirmLink!)
    await expect(tab.getByText('Your email address is confirmed.')).toBeVisible({ timeout: 60_000 })
    await reachHome(invitee, accountMember)
    await expect(newButton(tab)).toBeVisible()
    await expect(banner(tab)).toHaveCount(0)

    // The owner's member list has the newcomer, and the link invite is no longer pending.
    await openSettingsOn(owner, accountOwner, 'Members')
    await expect(memberRow(page, handles.member)).toBeVisible({ timeout: 60_000 })
    await expect(page.locator('.invite-row').filter({ hasText: 'Link invitation' })).toHaveCount(0)
  },
)

test(
  'whoever opens the same link after it was used is told so, to tell whoever invited them',
  { tag: '@uc.spaces.add-member:member-invite-and-onboard' },
  async () => {
    const tab = latecomer.page
    await tab.goto(inviteLink)
    await expect(tab.getByText('This invitation link has already been used. Tell whoever invited you — they can send you a new one.'))
      .toBeVisible({ timeout: 60_000 })
    await expect(tab.getByLabel('Username')).toHaveCount(0)
  },
)
