// Moderators and the moderation key, in a browser (ADR 0099, uc.moderation.appoint-moderators, uc.moderation.serve-as-moderator). A second account is born the only way
// accounts are born — a tenant invite, here a second tenant whose owner it becomes — and then:
//
//   1. the Server Owner finds moderation on since first-run setup (epoch 1 sealed to their own account) and appoints it;
//   2. the appointee opens `/moderation` on its own tenant's address, reads what accepting means, accepts, types the
//      code the instance mail sent it, and unlocks with password and Secret Key — and waits for the key;
//   3. the Server Owner opens `/moderation` on the first tenant's address and unlocks: their page passes the key on;
//   4. the appointee unlocks again and opens the (empty) queue, which only a key proof reaches; the console's roster
//      says it holds the key.
//
// It runs after instance-mail.spec.ts (the instance mail it relies on is set up and confirmed there). The labels are the
// web client's and admin-web's own (each package's i18n, en).
import { test, expect, type Page } from '@playwright/test'
import { freshOwner, readKit } from '../../../src/accounts'
import { recordTyped } from '../../../src/canaries'
import { instance, newDevice, passwordField, saveDeputy, tenantOrigin, type Device } from '../../../src/devices'
import { awaitMails } from '../../../src/mail'
import { stack } from '../../../src/stack'

test.describe.configure({ mode: 'serial' })

const CODE_MAIL = /^Confirm your address for moderating veyq/
const QUEUE_EMPTY = 'Nothing has been reported.'

let admin: Device
let operator: Device // the Server Owner on the web client
let deputy: Device
let deputyAccount: { email: string; password: string; secretKey: string }
const deputyDomain = () => `deputy.${stack().domain}`

async function openModerationPanel(page: Page): Promise<void> {
  const { owner } = instance()
  await page.goto(stack().adminOrigin)
  const console = page.getByRole('heading', { name: 'Server administration', level: 1 })
  const email = page.getByLabel('Email', { exact: true })
  // A console this context already signed in to opens signed in; a new one asks first.
  await expect(console.or(email)).toBeVisible({ timeout: 60_000 })
  if (await email.isVisible()) {
    await email.fill(owner.email)
    await passwordField(page).fill(recordTyped(owner.password))
    await page.getByRole('button', { name: 'Sign in' }).click()
  }
  await expect(console).toBeVisible({ timeout: 60_000 })
  await page.getByRole('button', { name: 'Moderation', exact: true }).click()
  await expect(page.getByRole('heading', { level: 2, name: 'Moderation' })).toBeVisible()
}

/** `/moderation` on [origin], signed in by the page itself (never the member login, which would open a device wrap). */
async function openModeration(page: Page, origin: string, account: { email: string; password: string }): Promise<void> {
  await expect(async () => {
    await page.goto(`${origin}/moderation`)
  }).toPass({ timeout: 60_000, intervals: [1_000] })
  // The page reads the appointment of a session it holds before it shows anything; without one it asks to sign in.
  const signIn = page.getByRole('button', { name: 'Sign in', exact: true })
  const state = page.locator('.moderation-accept, .moderation-address, .moderation-unlock, .moderation-none')
  await expect(signIn.or(state).first()).toBeVisible({ timeout: 60_000 })
  if (await signIn.isVisible()) {
    await page.getByLabel('Email', { exact: true }).fill(account.email)
    await passwordField(page).fill(recordTyped(account.password))
    await signIn.click()
  }
}

async function unlock(page: Page, account: { password: string; secretKey: string }): Promise<void> {
  const form = page.locator('.moderation-unlock')
  await form.getByRole('textbox', { name: /^Password\b/ }).fill(recordTyped(account.password))
  await page.locator('#moderation-secret-key').fill(recordTyped(account.secretKey))
  await page.getByRole('button', { name: 'Open the moderation key' }).click()
}

test.beforeAll(async ({ browser }) => {
  admin = await newDevice(browser)
  operator = await newDevice(browser)
  deputy = await newDevice(browser)
})

test.afterEach(() => {
  for (const d of [admin, operator, deputy]) d?.confinement.assertConfined()
})

test.afterAll(async () => {
  for (const d of [admin, operator, deputy]) await d?.context.close()
})

test(
  'a second account is born through a second tenant\'s invite',
  { tag: ['@uc.admin.create-tenant:create-pending-tenant', '@uc.admin.create-tenant:invite-link-handoff'] },
  async () => {
    const { page } = admin
    const fresh = freshOwner(`e2e.${stack().domain}`)
    await openModerationPanel(page)
    await page.getByRole('button', { name: 'Create tenant' }).click()
    const dialog = page.getByRole('dialog', { name: 'Create tenant' })
    await dialog.getByLabel('Tenant name').fill('E2E Deputy')
    await dialog.getByLabel('Domain').fill(deputyDomain())
    await dialog.getByLabel('Who should own this tenant? (email)').fill(fresh.email)
    await dialog.getByRole('button', { name: 'Create' }).click()
    await expect(dialog.getByText('Tenant created.')).toBeVisible()
    await admin.context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: stack().adminOrigin })
    await dialog.getByRole('button', { name: 'Copy link' }).click()
    const inviteLink = await page.evaluate(() => navigator.clipboard.readText())
    await dialog.getByRole('button', { name: 'Done' }).click()

    const tab = deputy.page
    await expect(async () => {
      await tab.goto(inviteLink)
    }).toPass({ timeout: 60_000, intervals: [1_000] })
    await expect(tab.getByText(`You have been invited to own “E2E Deputy” (${deputyDomain()}).`)).toBeVisible()
    await tab.getByLabel('Username').fill(`deputy-${Date.now()}`)
    await tab.locator('#join-password').fill(recordTyped(fresh.password))
    await tab.getByRole('button', { name: 'Continue' }).click()
    const download = tab.waitForEvent('download')
    await tab.getByRole('button', { name: 'Download Emergency Kit (PDF)' }).click()
    const kit = await readKit(await download)
    await tab.getByRole('checkbox', { name: 'I have saved my Emergency Kit somewhere safe.' }).check()
    await tab.getByRole('button', { name: 'Accept & activate' }).click()
    await expect(tab.getByText('“E2E Deputy” is now active and yours.')).toBeVisible({ timeout: 60_000 })
    deputyAccount = { email: fresh.email, password: fresh.password, secretKey: kit.secretKey }
  },
)

test(
  'moderation is on since first-run setup, the Server Owner holds epoch 1 and appoints the account; it stays pending',
  {
    tag: [
      '@uc.moderation.appoint-moderators:setup-mints-epoch-one-to-the-server-owner',
      '@uc.moderation.appoint-moderators:appoint-an-existing-account-by-address',
    ],
  },
  async () => {
    const { page } = admin
    await openModerationPanel(page)
    // Setup minted epoch 1 (ADR 0099 § 3): no "off" state, the Server Owner its one holder.
    await expect(page.getByText('Current moderation key: epoch 1.')).toBeVisible({ timeout: 60_000 })
    await expect(page.getByText('Moderation is off: there is no moderation key, so reporting is off.')).toHaveCount(0)
    await expect(page.locator(`.mod-row[data-address="${instance().owner.email}"]`)).toContainText('Moderator, holds the key')

    await page.locator('.mod-address').fill(deputyAccount.email)
    await page.getByRole('button', { name: 'Appoint', exact: true }).click()
    await expect(page.locator(`.mod-row[data-address="${deputyAccount.email}"]`)).toContainText('Pending')
  },
)

test(
  'the appointee accepts after the text that names what it will see, confirms its address and waits for the key',
  {
    tag: [
      '@uc.moderation.serve-as-moderator:accepting-grants-the-role-and-mails-a-code',
      '@uc.moderation.serve-as-moderator:the-address-is-confirmed-by-the-code',
    ],
  },
  async () => {
    const { page } = deputy
    await openModeration(page, `https://${deputyDomain()}`, deputyAccount)
    await expect(page.getByText('You will see what was reported. That can include child sexual abuse material.')).toBeVisible({ timeout: 60_000 })
    await expect(page.getByText(/the moderation log records it with your account/)).toBeVisible()
    await page.getByRole('button', { name: 'Accept and moderate' }).click()

    const [mail] = await awaitMails(deputyAccount.email, CODE_MAIL)
    expect(mail!.to, 'the code goes to the moderator alone').toEqual([deputyAccount.email])
    const code = /^\s+(\d{8})\s*$/m.exec(mail!.text)?.[1]
    expect(code, `an 8-digit code in:\n${mail!.text}`).toBeDefined()
    await page.locator('.moderation-code').fill(code!)
    await page.getByRole('button', { name: 'Confirm address' }).click()
    await expect(page.locator('.moderation-address')).toHaveCount(0)

    await unlock(page, deputyAccount)
    await expect(page.getByText(/You do not hold the moderation key yet/)).toBeVisible({ timeout: 60_000 })
  },
)

test(
  'the Server Owner unlocks the moderation page and passes the key on after the pin check',
  {
    tag: [
      '@uc.moderation.serve-as-moderator:a-holder-delivers-after-the-pin-check',
      '@uc.moderation.serve-as-moderator:unlock-compares-the-published-key-and-drops-the-vault',
    ],
  },
  async () => {
    const { page } = operator
    const { owner } = instance()
    await openModeration(page, tenantOrigin(), owner)
    await unlock(page, owner)
    await expect(page.getByText('You passed the key on to 1 new moderator.')).toBeVisible({ timeout: 60_000 })
    await expect(page.getByText(QUEUE_EMPTY)).toBeVisible()
  },
)

test(
  'the appointee now holds the key and opens the empty queue; the roster says so',
  { tag: '@uc.moderation.serve-as-moderator:every-key-proved-operation-needs-role-and-proof' },
  async () => {
    const { page } = deputy
    await openModeration(page, `https://${deputyDomain()}`, deputyAccount)
    await unlock(page, deputyAccount)
    await expect(page.getByText(QUEUE_EMPTY)).toBeVisible({ timeout: 60_000 })
    await expect(page.getByRole('button', { name: 'Step down as moderator' })).toBeVisible()

    await openModerationPanel(admin.page)
    await expect(admin.page.locator(`.mod-row[data-address="${deputyAccount.email}"]`)).toContainText('Moderator, holds the key')
    await expect(admin.page.locator(`.mod-row[data-address="${deputyAccount.email}"]`)).toContainText('Address confirmed')
    // The second moderator decides an objection to the Server Owner's measure later (statement-and-objection.spec.ts).
    saveDeputy({ ...deputyAccount, tenantDomain: deputyDomain() })
  },
)
