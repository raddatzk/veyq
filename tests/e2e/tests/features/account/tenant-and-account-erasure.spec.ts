// Erasing an account and deleting a tenant, in a browser (ADR 0101,
// uc.account.erase-account, uc.admin.delete-tenant). Two accounts are born the only way accounts are born — a tenant
// invite each, as moderation.spec.ts births its deputy: A owns "E2E Erase", B owns "E2E Keep". Then:
//
//   1. A sets its tenant's mail to the run's relay and invites B, who accepts the mailed link; A makes B a co-owner and
//      a member again, and cannot step down as the last owner;
//   2. A erases its account in the settings, naming B the new owner: the sign-in page says the account is being erased,
//      and signing in as A is refused as any wrong password is;
//   3. the console lists the pending erasure and stops it — A signs in again (the positive control of 2's refusal) —, B
//      owns "E2E Erase" (no "No owner" marker), and the console's own door starts and stops the same erasure;
//   4. the Server Owner deletes "E2E Keep" with its typed domain: offline at once for B, B mailed, and a stop leaves it
//      offline until it is brought back online, when B reaches it again.
//
// The erase panel's German words are the catalogs' (src/web/'s i18n.test.ts: the same keys in both languages, every key
// used), not a browser's: no German pass here (ADR 0090 decision 9). It runs after instance-mail.spec.ts (the `instance`
// project: the instance mail the erasure and deletion mails go through is set up and confirmed there); the accounts and
// tenants it erases are its own, so it disturbs no other scenario. The labels are the web client's and admin-web's own
// (each package's i18n, en).
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

// The relay as an owner types it into the space's mail settings: the overlay's alias, plain SMTP on Mailpit's port,
// no STARTTLS, no login (instance-mail.spec.ts types the same into Server → Mail).
const RELAY = { host: 'mail.relay.test', port: '1025' }
const ERASURE_MAIL = /^veyq: your account is being erased \//
const DELETION_MAIL = /^veyq: a space you belong to is being deleted \//
const SIGN_IN_FAILED = 'Sign-in failed. Check your email and password.'

const stamp = randomBytes(3).toString('hex')
const eraseDomain = () => `erase.${stack().domain}`
const keepDomain = () => `keep.${stack().domain}`
// Per-tenant handles: declared plaintext (`pii`), so no canaries.
const handles = { a: `erase-owner-${stamp}`, b: `keep-owner-${stamp}`, bInErase: `keep-member-${stamp}` }

let admin: Device // the Server Owner's console
let alice: Device // A
let bob: Device // B
let accountA: Account
let accountB: Account

const escape = (text: string) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

/** The console, signed in — a context that already signed in opens it signed in; a new document asks first. */
async function openConsole(page: Page): Promise<void> {
  const { owner } = instance()
  await page.goto(stack().adminOrigin)
  const console = page.getByRole('heading', { name: 'Server administration', level: 1 })
  const email = page.getByLabel('Email', { exact: true })
  await expect(console.or(email)).toBeVisible({ timeout: 60_000 })
  if (await email.isVisible()) {
    await email.fill(owner.email)
    await passwordField(page).fill(recordTyped(owner.password))
    await page.getByRole('button', { name: 'Sign in' }).click()
  }
  await expect(console).toBeVisible({ timeout: 60_000 })
}

/** A tenant's panel in the console, from the tenant list. */
async function openTenant(page: Page, name: string): Promise<void> {
  await openConsole(page)
  await page.getByRole('button', { name }).click()
  await expect(page.getByRole('heading', { level: 2, name })).toBeVisible()
}

const tenantBadge = (page: Page) => page.locator('.settings-section-header .veyq-badge')

/**
 * A new tenant for a fresh account, accepted on its own domain by that account's device: the Server Owner creates it and
 * copies the invite link, the person opens it, registers, saves the kit and accepts — then signs in to its home.
 */
async function birth(name: string, domain: string, handle: string, device: Device): Promise<Account> {
  const { page } = admin
  const fresh = freshOwner(`e2e.${stack().domain}`)
  await openConsole(page)
  await page.getByRole('button', { name: 'Create tenant' }).click()
  const dialog = page.getByRole('dialog', { name: 'Create tenant' })
  await dialog.getByLabel('Tenant name').fill(name)
  await dialog.getByLabel('Domain').fill(domain)
  await dialog.getByLabel('Who should own this tenant? (email)').fill(fresh.email)
  await dialog.getByRole('button', { name: 'Create' }).click()
  await expect(dialog.getByText('Tenant created.')).toBeVisible()
  await admin.context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: stack().adminOrigin })
  await dialog.getByRole('button', { name: 'Copy link' }).click()
  const inviteLink = await page.evaluate(() => navigator.clipboard.readText())
  await dialog.getByRole('button', { name: 'Done' }).click()

  const tab = device.page
  await expect(async () => {
    await tab.goto(inviteLink)
  }).toPass({ timeout: 60_000, intervals: [1_000] })
  await expect(tab.getByText(`You have been invited to own “${name}” (${domain}).`)).toBeVisible()
  await tab.getByLabel('Username').fill(handle)
  await tab.locator('#join-password').fill(recordTyped(fresh.password))
  await tab.getByRole('button', { name: 'Continue' }).click()
  const download = tab.waitForEvent('download')
  await tab.getByRole('button', { name: 'Download Emergency Kit (PDF)' }).click()
  const kit = await readKit(await download)
  expect(kit.email, 'the kit names the account it was minted for').toBe(fresh.email)
  await tab.getByRole('checkbox', { name: 'I have saved my Emergency Kit somewhere safe.' }).check()
  await tab.getByRole('button', { name: 'Accept & activate' }).click()
  await expect(tab.getByText(`“${name}” is now active and yours.`)).toBeVisible({ timeout: 60_000 })
  // "Sign in" opens the sign-in form of this new device: email, password and the Secret Key the kit just carried.
  await tab.getByRole('button', { name: 'Sign in', exact: true }).click()
  const account = { email: fresh.email, password: fresh.password, secretKey: kit.secretKey }
  await submitSignIn(tab, account)
  await expect(tab.getByRole('button', { name: 'New', exact: true })).toBeVisible({ timeout: 60_000 })
  return account
}

/** The sign-in form of a device that holds no key for the account: email, password and Secret Key. */
async function submitSignIn(page: Page, account: Account): Promise<void> {
  await page.getByLabel('Email', { exact: true }).fill(account.email)
  await passwordField(page).fill(recordTyped(account.password))
  await page.locator('#unlock-secret-key').fill(recordTyped(account.secretKey))
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
}

/**
 * The space's home on [origin], signing in first when this document asks for it — [ready] says the home is up. The
 * document must be the app's: a domain whose routers the projector has just put back answers the proxy's own 404 until
 * Traefik applies the file (see [expectOffline]), and a 404 is a loaded page, not a failed navigation, so it is retried
 * here rather than waited on below.
 */
async function reachHome(
  device: Device, origin: string, account: Account,
  ready: (page: Page) => Locator = (page) => page.getByRole('button', { name: 'New', exact: true }),
): Promise<void> {
  const { page } = device
  await expect(async () => {
    const response = await page.goto(origin)
    expect(response?.ok(), `${origin} serves the app`).toBe(true)
  }).toPass({ timeout: 60_000, intervals: [1_000] })
  const home = ready(page)
  const email = page.getByLabel('Email', { exact: true })
  await expect(home.or(email)).toBeVisible({ timeout: 60_000 })
  if (await email.isVisible()) await submitSignIn(page, account)
  await expect(home).toBeVisible({ timeout: 60_000 })
}

/** A section of Settings on [origin], reached as devices.ts's `openSettings` does — from the space's home. */
async function openSettingsOn(device: Device, origin: string, account: Account, section: string): Promise<void> {
  const { page } = device
  await reachHome(device, origin, account)
  await page.locator('.profile-button').click()
  await page.getByRole('menuitem', { name: 'Settings' }).click()
  await page.getByRole('navigation', { name: 'Settings' }).getByRole('button', { name: section, exact: true }).click()
}

/** A row of the members panel, by the per-tenant handle it shows (`@handle`). */
const memberRow = (page: Page, handle: string): Locator => page.locator('.member-row').filter({ hasText: `@${handle}` })

/**
 * [domain]'s tenant offline for [device]'s member, the two ways ADR 0066 and ADR 0101 decision 10 make it observable:
 *
 *   - **At the edge, its domain no longer reaches the app** — the ingress is withdrawn, so a document loaded afresh is
 *     the proxy's own 404. Polled, because Traefik applies a change of the projector's file at most once per
 *     `providersThrottleDuration` (2 s) and the projector's temp file, written into the watched directory before its
 *     rename, can spend that slot on the old content: for up to 2 s the withdrawn routers still serve, and in that
 *     window only, the app answers `/api/setup` with `403 tenant_suspended` and shows "This space is temporarily
 *     offline". This test once read that page on a fresh document: green while console clicks beat the window
 *     (delete, stop and reactivate inside 0.9 s), red the first time a loaded host applied the file at once.
 *   - **From the member's other space ([from]), the switcher marks it "Temporarily offline" and cannot enter it** —
 *     `my-tenants` carries the status the gate refuses by, which no routing delay touches.
 */
async function expectOffline(device: Device, account: Account, domain: string, name: string, from: string): Promise<void> {
  const { page } = device
  await expect(async () => {
    const response = await page.goto(`https://${domain}`)
    expect(response?.status(), `${domain} no longer reaches the app`).toBe(404)
  }).toPass({ timeout: 60_000, intervals: [1_000] })

  const switchSpace = (p: Page) => p.getByRole('button', { name: 'Switch space' })
  await reachHome(device, from, account, switchSpace)
  await switchSpace(page).click()
  const entry = page.locator('.sidebar-tenant-overview .sidebar-tenant-link').filter({ hasText: name })
  await expect(entry).toContainText('Temporarily offline')
  await expect(entry).not.toContainText(domain)
  await expect(entry).toBeDisabled()
  await page.keyboard.press('Escape')
}

test.beforeAll(async ({ browser }) => {
  admin = await newDevice(browser)
  alice = await newDevice(browser)
  bob = await newDevice(browser)
})

test.afterEach(() => {
  for (const d of [admin, alice, bob]) d?.confinement.assertConfined()
})

test.afterAll(async () => {
  for (const d of [admin, alice, bob]) await d?.context.close()
})

test(
  'two accounts are born, each through its own tenant\'s invite',
  { tag: ['@uc.admin.create-tenant:create-pending-tenant', '@uc.admin.create-tenant:invite-link-handoff'] },
  async () => {
    accountA = await birth('E2E Erase', eraseDomain(), handles.a, alice)
    accountB = await birth('E2E Keep', keepDomain(), handles.b, bob)
  },
)

test(
  'B joins A\'s tenant by the mailed invite; A makes B a co-owner and a member again, and cannot step down as the last owner',
  { tag: '@uc.admin.delete-tenant:an-ownerless-tenant-has-two-ways-on' },
  async () => {
    const eraseOrigin = `https://${eraseDomain()}`
    const { page } = alice

    // The space's own mail, pointed at the run's relay: invitations go out by mail only once it is set up.
    await openSettingsOn(alice, eraseOrigin, accountA, 'Email')
    await expect(page.getByRole('heading', { level: 2, name: 'Email' })).toBeVisible()
    await page.getByRole('textbox', { name: /^SMTP host/ }).fill(RELAY.host)
    await page.getByRole('textbox', { name: /^Port/ }).fill(RELAY.port)
    await page.getByRole('textbox', { name: /^From address/ }).fill(`space-mail@${eraseDomain()}`)
    await page.getByRole('switch', { name: /^Use STARTTLS/ }).uncheck()
    await page.getByRole('switch', { name: /^Send invitations by email/ }).check()
    await page.getByRole('button', { name: 'Save email settings' }).click()
    await expect(toast(page, 'Email settings saved.')).toBeVisible()
    await expect(page.getByText('Ready to send email — new member invitations will be emailed from your server.')).toBeVisible()

    await page.getByRole('navigation', { name: 'Settings' }).getByRole('button', { name: 'Members', exact: true }).click()
    await page.getByRole('button', { name: 'Invite member' }).click()
    const invite = page.getByRole('dialog', { name: 'Invite member' })
    // The dialog opens on sharing a link (ADR 0129 decision 1); this member is invited by mail.
    await invite.getByRole('button', { name: 'Invite by email' }).click()
    await invite.getByLabel('Email address').fill(accountB.email)
    await invite.getByRole('button', { name: 'Send invite' }).click()
    await expect(invite.getByText(`Invitation sent to ${accountB.email}.`)).toBeVisible({ timeout: 30_000 })
    // The code is shown once, here, and travels outside veyq (ADR 0111 decision 1): never in the mail, never on the wire.
    const code = recordTyped((await invite.getByTestId('invite-code').textContent())!.trim())
    await invite.getByRole('button', { name: 'Done' }).click()

    // B opens the link from the mail on its own device: an existing account, so a sign-in on the invite page.
    const [mail] = await awaitMails(accountB.email, new RegExp(`^You're invited to ${escape(eraseDomain())} `))
    const link = /https:\/\/\S+/.exec(mail!.text)?.[0]
    expect(link, `an invite link in:\n${mail!.text}`).toBeDefined()
    expect(new URL(link!).origin, 'the invite opens on the tenant domain').toBe(eraseOrigin)
    const tab = bob.page
    await tab.goto(link!)
    await expect(tab.getByText(`You have been invited to join “E2E Erase” (${eraseDomain()}).`)).toBeVisible({ timeout: 60_000 })
    // The link alone opens nothing: the code opens its sealed part, and the client checks the invite before anything else.
    await tab.getByRole('textbox', { name: 'Code', exact: true }).fill(code)
    await tab.getByRole('button', { name: 'Continue' }).click()
    await tab.getByLabel('Username').fill(handles.bInErase)
    await tab.locator('#signin-password').fill(recordTyped(accountB.password))
    await tab.locator('#signin-secret-key').fill(recordTyped(accountB.secretKey))
    await tab.getByRole('button', { name: 'Accept & join' }).click()
    await expect(tab.getByText('You’ve joined “E2E Erase”.')).toBeVisible({ timeout: 60_000 })

    // A tenant owner makes a member an owner, and a member again; the last owner cannot be demoted.
    await openSettingsOn(alice, eraseOrigin, accountA, 'Members')
    const rowB = memberRow(page, handles.bInErase)
    const rowA = memberRow(page, handles.a)
    await expect(rowB).toContainText('MEMBER', { timeout: 30_000 })
    await rowB.getByRole('button', { name: 'Make co-owner' }).click()
    await expect(toast(page, 'They own this space now too.')).toBeVisible()
    await expect(rowB).toContainText('OWNER')
    await rowB.getByRole('button', { name: 'Remove co-owner' }).click()
    await expect(toast(page, 'They are a member of this space again.')).toBeVisible()
    await expect(rowB.getByRole('button', { name: 'Make co-owner' })).toBeVisible()
    await rowA.getByRole('button', { name: 'Remove co-owner' }).click()
    await expect(page.getByText('A shared space needs at least one owner.')).toBeVisible()
    await expect(rowA).toContainText('OWNER')
  },
)

test(
  'A erases its account in the settings, naming B the owner of its tenant; the sign-in page says so and refuses A',
  {
    tag: [
      '@uc.account.erase-account:both-doors-start-the-same-erasure',
      '@uc.account.erase-account:the-account-stops-at-the-request',
      '@uc.account.erase-account:the-last-owner-is-handled',
    ],
  },
  async () => {
    const eraseOrigin = `https://${eraseDomain()}`
    const { page } = alice
    await openSettingsOn(alice, eraseOrigin, accountA, 'Erase account')
    await expect(page.getByRole('heading', { level: 2, name: 'Erase your account' })).toBeVisible()
    await expect(page.getByText('Spaces you are the last owner of')).toBeVisible({ timeout: 30_000 })

    // The last owner's choice, per tenant with other members: delete it along, or one of its members as the owner. The
    // panel's own button — the Settings rail's entry carries the same name.
    const erase = page.locator('.erasure-action').getByRole('button', { name: 'Erase account', exact: true })
    await page.locator('#erasure-password').fill(recordTyped(accountA.password))
    await expect(erase, 'no erasure before the choice is made').toBeDisabled()
    await page.locator('[data-choice-tenant]').getByRole('combobox').click()
    await expect(page.getByRole('option', { name: 'Delete the space' })).toBeVisible()
    await page.getByRole('option', { name: /^Make .+ the owner$/ }).click()
    await erase.click()

    const confirm = page.getByRole('alertdialog').or(page.getByRole('dialog')).filter({ hasText: 'Erase your account?' })
    await confirm.getByRole('button', { name: 'Erase account', exact: true }).click()

    // Every session ended with the request: the tab is on the sign-in page, which says why.
    await expect(page.getByText(
      'Your account is being erased, and you are signed out. You cannot sign in again; until the erasure completes, only the Server Owner can stop it.',
    )).toBeVisible({ timeout: 60_000 })
    const [mail] = await awaitMails(accountA.email, ERASURE_MAIL)
    expect(mail!.to, 'the erasure is mailed to the account alone').toEqual([accountA.email])

    // Both secrets right, and still refused — as any wrong password is (the Server Owner's stop is the control).
    await submitSignIn(page, accountA)
    await expect(page.getByText(SIGN_IN_FAILED)).toBeVisible({ timeout: 60_000 })
  },
)

test(
  'the console lists the erasure and stops it, A signs in again, B owns the tenant; the console\'s own door erases and stops too',
  {
    tag: [
      '@uc.account.erase-account:both-doors-start-the-same-erasure',
      '@uc.account.erase-account:the-last-owner-is-handled',
    ],
  },
  async () => {
    const { page } = admin
    await openConsole(page)
    await page.getByRole('button', { name: 'Accounts', exact: true }).click()
    await expect(page.getByRole('heading', { level: 2, name: 'Erase an account' })).toBeVisible()
    await expect(page.getByText('Pending erasures', { exact: true })).toBeVisible()
    const pendingA = page.locator('.account-erasures__row').filter({ hasText: accountA.email })
    await expect(pendingA).toContainText('Requested by the account itself on')
    await pendingA.getByRole('button', { name: 'Stop erasure' }).click()
    await expect(page.getByText(`The erasure of ${accountA.email} is stopped. The account can sign in again.`)).toBeVisible()
    await expect(pendingA).toHaveCount(0)

    // The positive control of the refused sign-in: the same secrets on the same page now unlock the account.
    const tab = alice.page
    await tab.reload()
    await submitSignIn(tab, accountA)
    await expect(tab.locator('#login-password')).toHaveCount(0, { timeout: 60_000 })
    await expect(tab.getByText(SIGN_IN_FAILED)).toHaveCount(0)

    // The handover took: the tenant has an owner (B; A's membership ended with the request), so no "No owner" marker.
    await openTenant(page, 'E2E Erase')
    await expect(page.getByRole('button', { name: 'E2E Erase' })).not.toContainText('No owner')
    await expect(page.locator('.tenant-ownerless')).toHaveCount(0)
    await expect(tenantBadge(page)).toHaveText('Active')

    // The Server Owner's door: the address typed twice arms it; the same pending erasure, listed as theirs; stopped.
    await page.getByRole('button', { name: 'Accounts', exact: true }).click()
    const eraseByOwner = page.getByRole('button', { name: 'Erase account', exact: true })
    await page.getByRole('textbox', { name: 'Account address', exact: true }).fill(accountA.email)
    await expect(eraseByOwner, 'armed only by the address typed twice').toBeDisabled()
    await page.getByRole('textbox', { name: 'Type the address again', exact: true }).fill(accountA.email)
    await eraseByOwner.click()
    await expect(page.getByText(`${accountA.email} will be erased on`, { exact: false })).toBeVisible()
    await expect(pendingA).toContainText('Requested by you on')
    await pendingA.getByRole('button', { name: 'Stop erasure' }).click()
    await expect(page.getByText(`The erasure of ${accountA.email} is stopped. The account can sign in again.`)).toBeVisible()
    await expect(pendingA).toHaveCount(0)
  },
)

test(
  'the Server Owner deletes B\'s tenant by its typed domain: offline at once, B mailed; stopped, it stays offline until brought back',
  {
    tag: [
      '@uc.admin.delete-tenant:an-active-tenant-goes-offline-at-once',
      '@uc.admin.delete-tenant:members-keep-their-accounts',
    ],
  },
  async () => {
    const keepOrigin = `https://${keepDomain()}`
    const eraseOrigin = `https://${eraseDomain()}` // B's other space (it joined A's tenant above)
    const { page } = admin
    await openTenant(page, 'E2E Keep')
    await expect(tenantBadge(page)).toHaveText('Active')

    // The typed domain is the confirmation: another tenant's domain leaves the button disabled.
    const typed = page.getByRole('textbox', { name: /^Type the tenant.s domain/ })
    const remove = page.getByRole('button', { name: 'Delete tenant', exact: true })
    await typed.fill(eraseDomain())
    await expect(remove).toBeDisabled()
    await typed.fill(keepDomain())
    await expect(remove).toBeEnabled()
    await remove.click()
    await expect(tenantBadge(page)).toHaveText('Being deleted')
    await expect(page.getByRole('button', { name: 'Stop deletion' })).toBeVisible()

    // Offline at once for its member, exactly as a suspension; the member is told by mail, and their account stays.
    await expectOffline(bob, accountB, keepDomain(), 'E2E Keep', eraseOrigin)
    const [mail] = await awaitMails(accountB.email, DELETION_MAIL)
    expect(mail!.text).toContain('Your account')
    expect(mail!.text).toContain('stays')

    // A stop returns it offline (suspended), not online: the lever brings it back.
    await page.getByRole('button', { name: 'Stop deletion' }).click()
    await expect(tenantBadge(page)).toHaveText('Suspended')
    await expectOffline(bob, accountB, keepDomain(), 'E2E Keep', eraseOrigin)
    await page.getByRole('button', { name: 'Bring back online' }).click()
    await expect(tenantBadge(page)).toHaveText('Active')

    // The account kept: B signs in and reaches its space again.
    await reachHome(bob, keepOrigin, accountB)
  },
)
