// The operator section of the terms in a browser (ADR 0035 decision 6, cr-operator-section-of-the-terms), in its one
// form on every instance (ADR 0093 § 14), with the pointer to the guest-write record's information:
//
//   1. Anyone, without an account, opens **Terms of this server** from the sign-in page and reads the section and,
//      at the record's anchor, its pointer to the privacy notice, which holds the record's
//      information and its right to object (DSA Art. 14(1); ADR 0035 decision 8).
//   2. The space's owner publishes terms of their own: the Terms panel shows the section read-only below them, and the
//      owner, like every member, is then asked to agree — to the space's text and the section after it.
//   3. A guest opening a link of the space meets the same two at the link's terms gate, and after accepting, the link.
//   4. A German visitor reads the same one form — the authorities, the record's pointer — and the privacy notice: the
//      record's passage, and the recipient's country left unset shown as a gap.
//
// IN A TENANT OF ITS OWN ("E2E Terms", born through the console's invite: `birthTenant`). Publishing terms gates every
// later guest and every later sign-in of the space, and v1 has no way to unpublish them (ADR 0035 decision 1). Until
// 2026-10-26 it ran in the round trip's tenant and was named to sort after the other feature scenarios; once the specs
// moved into domain folders (ADR 0090 decision 7) a whole run put it first, and every later guest of that tenant met
// the gate. A space of its own leaves the shared one ungated whatever runs after it.
import { expect } from '@playwright/test'
import { test } from '../../../src/cspViolations'
import { randomBytes } from 'node:crypto'
import { recordTyped } from '../../../src/canaries'
import { addLanguage } from '../../../src/localeEditor'
import { birthTenant } from '../../../src/console'
import { confineToInstance } from '../../../src/egress'
import { newDevice, openSettings, type Device } from '../../../src/devices'
import { headerAction } from '../../../src/header'
import { stack } from '../../../src/stack'

test.describe.configure({ mode: 'serial' })

const stamp = randomBytes(3).toString('hex')
const TENANT = 'E2E Terms'
const domain = () => `terms.${stack().domain}`
const tenantOrigin = () => `https://${domain()}`
// A per-tenant handle: declared plaintext (`pii`), so no canary.
const handle = `terms-owner-${stamp}`
const spaceRule = `Be kind to each other ${stamp}.`
const sharedFolder = `Fotos für alle ${stamp}`

let admin: Device
let visitor: Device
let owner: Device
let guest: Device
let shareLink: string

test.beforeAll(async ({ browser }) => {
  admin = await newDevice(browser)
  visitor = await newDevice(browser)
  owner = await newDevice(browser)
  guest = await newDevice(browser)
  // The space this file gates: born here, its owner signed in on `owner`'s device.
  await birthTenant(admin, { name: TENANT, domain: domain(), handle }, owner)
})

test.afterEach(() => {
  for (const device of [admin, visitor, owner, guest]) device?.confinement.assertConfined()
})

test.afterAll(async () => {
  for (const device of [admin, visitor, owner, guest]) await device?.context.close().catch(() => undefined)
})

test(
  'anyone reads the terms of this instance from the sign-in page, without an account',
  { tag: '@uc.sharing.share-accept:the-section-is-public-without-an-account' },
  async () => {
    const { page, context } = visitor
    await page.goto(tenantOrigin())
    await expect(page.getByRole('button', { name: 'Sign in', exact: true })).toBeVisible({ timeout: 60_000 })
    const opened = context.waitForEvent('page')
    await page.getByRole('link', { name: 'Terms of this server' }).click()
    const terms = await opened
    await expect(terms.getByText('Terms of this server').first()).toBeVisible({ timeout: 60_000 })
    const section = terms.getByTestId('operator-section')
    await expect(section.getByRole('heading', { name: 'On this server: reports, measures and what is kept' })).toBeVisible()
    await expect(section.getByRole('heading', { name: 'Authorities and contact' })).toBeVisible() // the one form
    await expect(terms.locator('#guest-write-record')).toContainText('privacy notice of this server')
    await expect(terms.getByRole('link', { name: 'Read it in the privacy notice' })).toHaveAttribute('href', '/privacy#guest-write-record')
    await expect(terms.locator('aside')).toHaveCount(0) // the right to object is the notice's now
    await terms.close()
  },
)

test(
  'the owner publishes terms, sees the section below them read-only, and agrees to both',
  {
    tag: [
      '@uc.admin.tenant-terms:the-operator-section-is-not-the-owners',
      '@uc.account.accept-terms:the-operator-section-follows-the-space-terms',
    ],
  },
  async () => {
    const { page } = owner
    await page.goto(tenantOrigin())
    await expect(page.getByRole('button', { name: 'New', exact: true })).toBeVisible({ timeout: 60_000 })
    // A folder and a link for the guest below, made before the terms exist, so the owner is not yet asked.
    await page.getByRole('button', { name: 'New', exact: true }).click()
    await page.getByRole('menuitem', { name: 'New folder' }).click()
    const dialog = page.getByRole('dialog', { name: 'Create a folder' })
    await dialog.getByLabel('Name').fill(recordTyped(sharedFolder))
    await dialog.getByRole('button', { name: 'Create', exact: true }).click()
    await expect(dialog).toBeHidden()
    await page.getByRole('button', { name: sharedFolder, exact: true }).click()
    await headerAction(page, 'Share')
    await page.getByRole('dialog', { name: 'Share this folder' }).getByRole('button', { name: 'New share' }).click()
    await page.getByRole('dialog', { name: 'New share' }).getByRole('button', { name: 'Create link' }).click()
    const linkView = page.getByRole('dialog', { name: 'Share this folder' })
    shareLink = await linkView.getByRole('textbox', { name: 'Public link' }).inputValue()
    await linkView.getByRole('button', { name: 'Done' }).click()

    await openSettings(page, 'Terms', tenantOrigin())
    const panel = page.getByTestId('terms-operator-section')
    await expect(panel.getByTestId('operator-section')).toContainText('On this server: reports, measures and what is kept')
    await expect(panel.getByRole('button')).toHaveCount(0) // nothing edits or removes it

    await addLanguage(page, page, 'English', `## Our rules\n${spaceRule}`)
    await page.getByRole('button', { name: 'Save terms' }).click()
    await expect(page.getByText('Revision 1', { exact: true })).toBeVisible()

    // Every member, the owner included, is asked now: the space's text first, the section after it.
    await page.goto(tenantOrigin())
    await expect(page.getByText('Please review the terms')).toBeVisible({ timeout: 60_000 })
    const body = page.locator('.terms-body')
    await expect(body).toContainText(spaceRule)
    await expect(body.getByTestId('operator-section')).toContainText('On this server: reports, measures and what is kept')
    await page.getByRole('button', { name: 'I agree' }).click()
    await expect(page.getByRole('button', { name: 'New', exact: true })).toBeVisible({ timeout: 60_000 })
  },
)

test(
  'a guest meets the space’s terms and the section at the link, and enters after accepting',
  { tag: '@uc.sharing.share-accept:the-guest-accepts-the-pair' },
  async () => {
    const { page } = guest
    await page.goto(shareLink)
    await expect(page.getByText('Please accept the terms of use')).toBeVisible({ timeout: 60_000 })
    const body = page.locator('.share-terms-body')
    await expect(body).toContainText(spaceRule)
    await expect(body.getByTestId('operator-section')).toContainText('On this server: reports, measures and what is kept')
    await page.getByRole('button', { name: 'Accept and continue' }).click()
    await expect(page.getByText('Please accept the terms of use')).toBeHidden()
    await expect(page.getByText(sharedFolder).first()).toBeVisible({ timeout: 60_000 })
    // The acceptance stays on the device and holds the pair; a reload does not ask again.
    await page.reload()
    await expect(page.getByText(sharedFolder).first()).toBeVisible({ timeout: 60_000 })
    await expect(page.getByText('Please accept the terms of use')).toBeHidden()
  },
)

test(
  'a German visitor reads the one form of the section and of the privacy notice, an unset value as its gap',
  {
    tag: [
      '@uc.account.accept-terms:the-section-has-one-form',
      '@uc.admin.privacy-notice:a-missing-value-shows-as-not-stated',
    ],
  },
  async ({ browser }) => {
    const context = await browser.newContext({ locale: 'de-DE' })
    const confinement = await confineToInstance(context)
    try {
      const german = await context.newPage()
      await german.goto(`${tenantOrigin()}/operator-terms`)
      const section = german.getByTestId('operator-section')
      await expect(section.getByRole('heading', { name: 'Auf diesem Server: Meldungen, Maßnahmen und Aufbewahrung' })).toBeVisible({ timeout: 60_000 })
      await expect(section.getByRole('heading', { name: 'Inhalte melden' })).toBeVisible()
      await expect(section.getByRole('heading', { name: 'Behörden und Kontakt' })).toBeVisible()
      await expect(german.locator('#guest-write-record')).toContainText('Datenschutzerklärung dieses Servers')

      // The privacy notice: the record's passage on every instance, and the recipient's open country a visible gap.
      await german.goto(`${tenantOrigin()}/privacy`)
      await expect(german.locator('#recipients')).toContainText('E2E Hosting GmbH: hostet den Server', { timeout: 60_000 })
      await expect(german.locator('#recipients')).toContainText('von der Betreiberseite nicht angegeben')
      await expect(german.locator('#guest-write-record')).toBeVisible()
      await expect(german.locator('aside#right-to-object')).toBeVisible() // control: the page rendered, its general right too
      confinement.assertConfined()
    } finally {
      await context.close()
    }
  },
)
