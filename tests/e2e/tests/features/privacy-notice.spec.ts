// The instance privacy notice in a browser (cr-instance-privacy-notice phase 3's browser pass; ADR 0035 decision 8,
// uc.admin.privacy-notice), in its one form on every instance (ADR 0093 § 14), with the guest-write record's passage
// and the values first-run setup published: the provider, the authority and one recipient whose country was left open.
//
//   1. Anyone, without an account, opens **Privacy** from the sign-in page's footer and reads the notice — the draft
//      banner first, the controller and the recipient the Server Owner named, the record's passage with N, each right
//      to object apart, the breached-password check naming the service the instance calls; and in German.
//   2. A member's report dialog links the notice at the passage on the reporter's contact, beside the field.
//   3. A guest of a link it may add through is told at its first write, with a link to the record's passage; after the
//      write, its session line shows its guest id, which copies — what it names in an objection (A3).
//   4. The exchange rates' passage (`expenses.exchange-rates`, ADR 0108 decision 8) names Frankfurter and the ECB while the
//      rates are on for the host's tenant (step 1), and is gone while its owner has them off — the breached-password
//      check's passage beside it stays (control); switched on again, it is back.
//
// The invite acceptance's link is first-run setup's (`first-run.setup.ts`), where the one invite of the run is opened;
// the German notice with its gap is `terms-operator-section.spec.ts`'s.
import { test, expect, type BrowserContext, type Page } from '@playwright/test'
import { randomBytes } from 'node:crypto'
import { recordTyped } from '../../src/canaries'
import { confineToInstance } from '../../src/egress'
import { instance, newDevice, signInOwner, tenantOrigin, type Device } from '../../src/devices'
import { headerAction } from '../../src/header'

test.describe.configure({ mode: 'serial' })

const stamp = randomBytes(3).toString('hex')
const folder = `Datenschutz-Probe ${stamp}`
const guestName = `Tante Ilse ${stamp}`
const guestFolder = `Von Ilse ${stamp}`

let visitor: Device
let member: Device
let guest: Device
let shareLink: string

test.beforeAll(async ({ browser }) => {
  visitor = await newDevice(browser)
  member = await newDevice(browser)
  guest = await newDevice(browser)
})

test.afterEach(() => {
  for (const device of [visitor, member, guest]) device?.confinement.assertConfined()
})

test.afterAll(async () => {
  for (const device of [visitor, member, guest]) await device?.context.close().catch(() => undefined)
})

/** Click [link] on [page] and return the tab it opens — every link to the notice opens one, so a share keeps its key. */
async function opens(context: BrowserContext, page: Page, link: string): Promise<Page> {
  const opened = context.waitForEvent('page')
  await page.getByRole('link', { name: link }).click()
  const tab = await opened
  await tab.waitForLoadState()
  return tab
}

test(
  'anyone reads the privacy notice from the sign-in page, without an account, filled with this instance’s values',
  {
    tag: [
      '@uc.admin.privacy-notice:public-without-signing-in',
      '@uc.admin.privacy-notice:fixed-text-with-instance-values',
      '@uc.admin.privacy-notice:a-missing-value-shows-as-not-stated',
    ],
  },
  async () => {
    const { page, context } = visitor
    await page.goto(tenantOrigin())
    await expect(page.getByRole('button', { name: 'Sign in', exact: true })).toBeVisible({ timeout: 60_000 })
    const notice = await opens(context, page, 'Privacy')
    expect(new URL(notice.url()).pathname).toBe('/privacy')
    const body = notice.getByTestId('privacy-notice')
    await expect(body.getByText('Draft: this privacy notice has not yet been reviewed by a lawyer.')).toBeVisible({ timeout: 60_000 })
    await expect(notice.locator('#controller')).toContainText('E2E Operator e. V.')
    // The recipient first-run named, its open country a visible gap.
    await expect(notice.locator('#recipients')).toContainText('E2E Hosting GmbH: hosts the server (not stated by this operator)')
    await expect(notice.locator('#rights')).toContainText('E2E Datenschutzaufsicht')
    // The record's passage, on every instance, with the N the instance holds now.
    await expect(notice.locator('#guest-write-record')).toContainText('Each record is deleted 7 days after the write.')
    await expect(notice.locator('aside#guest-write-record-objection')).toContainText('give your guest id')
    await expect(notice.locator('aside#right-to-object')).toContainText('Art. 21 GDPR')
    await expect(notice.locator('#breached-password-check')).toContainText('Have I Been Pwned (api.pwnedpasswords.com)')
    // The day's exchange rates, on by default: the service the browser contacts, and whose rates they are.
    await expect(notice.locator('#exchange-rates')).toContainText(
      "fetches the day's euro reference rates of the European Central Bank (ECB) from Frankfurter (api.frankfurter.dev), at most once a day.",
    )
    await expect(notice.locator('#storage')).toContainText("on this instance's own server")
    await notice.close()
  },
)

test(
  'a German visitor reads the notice in German',
  { tag: '@uc.admin.privacy-notice:fixed-text-with-instance-values' },
  async ({ browser }) => {
    const context = await browser.newContext({ locale: 'de-DE' })
    const confinement = await confineToInstance(context)
    try {
      const page = await context.newPage()
      await page.goto(`${tenantOrigin()}/privacy`)
      await expect(page.getByText('Entwurf: Diese Datenschutzerklärung ist noch nicht anwaltlich geprüft.')).toBeVisible({ timeout: 60_000 })
      await expect(page.getByRole('heading', { name: 'Wer verantwortlich ist' })).toBeVisible()
      // the kind in the page's own language: the German notice carries no English
      await expect(page.locator('#recipients')).toContainText('E2E Hosting GmbH: hostet den Server (von der Betreiberseite nicht angegeben)')
      await expect(page.locator('#guest-write-record')).toContainText('7 Tage nach dem Schreibvorgang')
      confinement.assertConfined()
    } finally {
      await context.close()
    }
  },
)

test(
  'a member’s report dialog links the notice at the passage on the reporter’s contact',
  { tag: '@uc.admin.privacy-notice:public-without-signing-in' },
  async () => {
    const { page, context } = member
    await signInOwner(member)
    await page.getByRole('button', { name: 'New', exact: true }).click()
    await page.getByRole('menuitem', { name: 'New folder' }).click()
    const create = page.getByRole('dialog', { name: 'Create a folder' })
    await create.getByLabel('Name').fill(recordTyped(folder))
    await create.getByRole('button', { name: 'Create', exact: true }).click()
    await expect(create).toBeHidden()

    await page.locator('.veyq-select-button').click()
    const box = page.getByRole('checkbox', { name: folder, exact: true })
    await page.locator('label.veyq-level-item-check').filter({ has: box }).click()
    await page.locator('.veyq-select-report').click()
    const dialog = page.getByRole('dialog', { name: 'Report' })
    await expect(dialog).toBeVisible()
    const notice = await opens(context, page, 'How your contact is kept, and for how long')
    expect(new URL(notice.url()).hash).toBe('#reporter-contact')
    await expect(notice.locator('#reporter-contact')).toContainText('deleted six months after you were told', { timeout: 60_000 })
    await notice.close()
    // Nothing was sent: the dialog is still open, and closing it reports nothing.
    await dialog.getByRole('button', { name: 'Cancel' }).click()
    await expect(dialog).toBeHidden()
  },
)

test(
  'a guest is pointed to the record’s passage before its first write, and then shown its guest id to copy',
  { tag: ['@uc.admin.privacy-notice:public-without-signing-in', '@uc.admin.privacy-notice:a-guest-names-its-id-to-object'] },
  async () => {
    // The member shares the folder with a link guests may add through.
    {
      const { page } = member
      await page.getByRole('button', { name: folder, exact: true }).click()
      await expect(page.getByRole('heading', { name: folder })).toBeVisible()
      await headerAction(page, 'Share')
      await page.getByRole('dialog', { name: 'Share this folder' }).getByRole('button', { name: 'New share' }).click()
      const createShare = page.getByRole('dialog', { name: 'New share' })
      await createShare.getByRole('button', { name: 'View + contribute' }).click()
      await createShare.getByRole('button', { name: 'Create link' }).click()
      const linkView = page.getByRole('dialog', { name: 'Share this folder' })
      shareLink = await linkView.getByRole('textbox', { name: 'Public link' }).inputValue()
      await linkView.getByRole('button', { name: 'Done' }).click()
    }

    const { page, context } = guest
    await page.goto(shareLink)
    await expect(page.getByText('Shared with you · you can add and change things here')).toBeVisible({ timeout: 60_000 })
    await page.getByRole('button', { name: 'New', exact: true }).click()
    await page.getByRole('menuitem', { name: 'New folder' }).click()
    const naming = page.getByRole('dialog', { name: 'What is your name?' })
    const notice = await opens(context, page, 'Who keeps it, why, and your rights')
    expect(new URL(notice.url()).pathname + new URL(notice.url()).hash).toBe('/privacy#guest-write-record')
    await expect(notice.locator('#guest-write-record')).toContainText('the server records that write', { timeout: 60_000 })
    await notice.close()
    expect(new URL(page.url()).hash, 'the share page kept its key').toContain('k=')

    await naming.getByLabel('Your name').fill(recordTyped(guestName))
    await naming.getByRole('button', { name: 'Continue' }).click()
    const create = page.getByRole('dialog', { name: 'Create a folder' })
    await create.getByLabel('Name').fill(recordTyped(guestFolder))
    await create.getByRole('button', { name: 'Create', exact: true }).click()
    await expect(page.getByRole('button', { name: guestFolder, exact: true })).toBeVisible({ timeout: 60_000 })

    // The session line now holds the identity: its id, to name in an objection, copies as it is shown.
    const line = page.getByTestId('guest-naming-guest-id')
    await expect(line).toContainText('Your guest id, to name if you object:')
    const id = (await line.locator('code').textContent())!.trim()
    expect(id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/)
    await context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: tenantOrigin() })
    await line.getByRole('button', { name: 'Copy' }).click()
    await expect(line.getByRole('button', { name: 'Copied' })).toBeVisible()
    expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(id)
  },
)

/** Settings → Capabilities of the shared tenant, as its owner: the exchange rates switched [off] or on. Off asks first.
 *  A new document on a device that kept the session asks the password again; it is given, and nothing else. */
async function switchExchangeRates(page: Page, off: boolean): Promise<void> {
  await page.goto(tenantOrigin())
  const home = page.getByRole('button', { name: 'New', exact: true })
  const password = page.getByRole('textbox', { name: /^Password\b/ })
  await expect(home.or(password).first()).toBeVisible({ timeout: 60_000 })
  if (await password.isVisible()) {
    await password.fill(recordTyped(instance().owner.password))
    const secretKey = page.locator('#unlock-secret-key')
    if (await secretKey.isVisible()) await secretKey.fill(recordTyped(instance().owner.secretKey))
    await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  }
  await expect(home).toBeVisible({ timeout: 60_000 })
  // Settings as `openSettings` opens it, without its own new document (which would ask the password once more).
  await page.locator('.profile-button').click()
  await page.getByRole('menuitem', { name: 'Settings' }).click()
  await page.getByRole('navigation', { name: 'Settings' }).getByRole('button', { name: 'Capabilities', exact: true }).click()
  const row = page.locator('li.capability[data-id="expenses.exchange-rates"]')
  await row.getByRole('button', { name: off ? 'Switch off' : 'Switch on', exact: true }).click({ timeout: 60_000 })
  if (off) {
    const confirm = page.getByRole('dialog', { name: 'Switch off “Exchange rates”?' })
    await confirm.getByRole('button', { name: 'Switch off', exact: true }).click()
    await expect(confirm).toBeHidden()
  }
  await expect(row.getByRole('button', { name: off ? 'Switch on' : 'Switch off', exact: true })).toBeVisible({ timeout: 30_000 })
}

test(
  'the exchange rates’ passage is there while the tenant has them on, and gone while its owner switched them off',
  { tag: ['@uc.expenses.exchange-rates:csp-and-notice-name-the-provider', '@uc.admin.privacy-notice:fixed-text-with-instance-values'] },
  async () => {
    const notice = visitor.page
    const passage = notice.locator('#exchange-rates')
    await switchExchangeRates(member.page, true)
    try {
      await notice.goto(`${tenantOrigin()}/privacy`)
      await expect(notice.locator('#breached-password-check')).toContainText('Have I Been Pwned', { timeout: 60_000 }) // control
      await expect(passage).toHaveCount(0)
      await expect(notice.getByTestId('privacy-notice')).not.toContainText('Frankfurter')
    } finally {
      await switchExchangeRates(member.page, false)
    }
    await notice.goto(`${tenantOrigin()}/privacy`)
    await expect(passage).toContainText('from Frankfurter (api.frankfurter.dev), at most once a day', { timeout: 60_000 })
  },
)
