// The operator section of the terms in a browser (ADR 0035 decision 6, cr-operator-section-of-the-terms), on the run's
// club, organisation or company instance, so the full variant with the guest-write record's information:
//
//   1. Anyone, without an account, opens **Terms of this instance** from the sign-in page and reads the section — the
//      draft banner first, and at the record's anchor its pointer to the privacy notice, which holds the record's
//      information and its right to object (DSA Art. 14(1); ADR 0035 decision 8, Q27 of cr-instance-privacy-notice).
//   2. The space's owner publishes terms of their own: the Terms panel shows the section read-only below them, and the
//      owner, like every member, is then asked to agree — to the space's text and the section after it.
//   3. A guest opening a link of the space meets the same two at the link's terms gate, and after accepting, the link.
//   4. The Server Owner switches to the private mode in the console: the owner is asked again, now with the reduced
//      section, and a German visitor reads the reduced section without the record's passage (the private preset
//      switched the record off), and the reduced privacy notice: no record passage, and a value left unset absent
//      rather than a gap. The mode goes back to club, organisation or company at the end.
//
// THIS FILE RUNS LAST ON PURPOSE. Publishing terms gates every later guest and every later sign-in of the space, and v1
// has no way to unpublish them (ADR 0035 decision 1), so it is named to sort after every other feature scenario
// (`workers: 1`, files in name order). A scenario added after it meets a gated space.
import { test, expect, type Page } from '@playwright/test'
import { randomBytes } from 'node:crypto'
import { recordTyped } from '../../src/canaries'
import { confineToInstance } from '../../src/egress'
import { instance, newDevice, openSettings, passwordField, signInOwner, tenantOrigin, type Device } from '../../src/devices'
import { stack } from '../../src/stack'

test.describe.configure({ mode: 'serial' })

const stamp = randomBytes(3).toString('hex')
const spaceRule = `Be kind to each other ${stamp}.`
const sharedFolder = `Fotos für alle ${stamp}`

let visitor: Device
let owner: Device
let guest: Device
let shareLink: string

test.beforeAll(async ({ browser }) => {
  visitor = await newDevice(browser)
  owner = await newDevice(browser)
  guest = await newDevice(browser)
})

test.afterEach(() => {
  for (const device of [visitor, owner, guest]) device?.confinement.assertConfined()
})

test.afterAll(async () => {
  for (const device of [visitor, owner, guest]) await device?.context.close().catch(() => undefined)
})

test(
  'anyone reads the terms of this instance from the sign-in page, without an account',
  { tag: '@uc.sharing.share-accept:the-section-is-public-without-an-account' },
  async () => {
    const { page, context } = visitor
    await page.goto(tenantOrigin())
    await expect(page.getByRole('button', { name: 'Sign in', exact: true })).toBeVisible({ timeout: 60_000 })
    const opened = context.waitForEvent('page')
    await page.getByRole('link', { name: 'Terms of this instance' }).click()
    const terms = await opened
    await expect(terms.getByText('Terms of this instance').first()).toBeVisible({ timeout: 60_000 })
    const section = terms.getByTestId('operator-section')
    await expect(section.getByText('Draft: this section has not yet been reviewed by a lawyer.')).toBeVisible()
    await expect(section.getByRole('heading', { name: 'On this instance: reports, measures and what is kept' })).toBeVisible()
    await expect(section.getByRole('heading', { name: 'Authorities and contact' })).toBeVisible() // the full variant
    await expect(terms.locator('#guest-write-record')).toContainText('privacy notice of this instance')
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
    await signInOwner(owner)
    // A folder and a link for the guest below, made before the terms exist, so the owner is not yet asked.
    await page.getByRole('button', { name: 'New', exact: true }).click()
    await page.getByRole('menuitem', { name: 'New folder' }).click()
    const dialog = page.getByRole('dialog', { name: 'Create a folder' })
    await dialog.getByLabel('Name').fill(recordTyped(sharedFolder))
    await dialog.getByRole('button', { name: 'Create', exact: true }).click()
    await expect(dialog).toBeHidden()
    await page.getByRole('button', { name: sharedFolder, exact: true }).click()
    await page.getByRole('button', { name: 'Share', exact: true }).click()
    await page.getByRole('dialog', { name: 'Share this folder' }).getByRole('button', { name: 'New share' }).click()
    await page.getByRole('dialog', { name: 'New share' }).getByRole('button', { name: 'Create link' }).click()
    const linkView = page.getByRole('dialog', { name: 'Share this folder' })
    shareLink = await linkView.getByRole('textbox', { name: 'Public link' }).inputValue()
    await linkView.getByRole('button', { name: 'Done' }).click()

    await openSettings(page, 'Terms')
    const panel = page.getByTestId('terms-operator-section')
    await expect(panel.getByTestId('operator-section')).toContainText('On this instance: reports, measures and what is kept')
    await expect(panel.getByRole('button')).toHaveCount(0) // nothing edits or removes it

    await page.getByRole('button', { name: 'Add English' }).click()
    await page.getByRole('button', { name: 'Edit', exact: true }).click()
    const edit = page.getByRole('dialog', { name: 'Edit English' })
    await edit.getByLabel('Markdown').fill(`## Our rules\n${spaceRule}`)
    await edit.getByRole('button', { name: 'Save', exact: true }).click()
    await page.getByRole('button', { name: 'Save terms' }).click()
    await expect(page.getByText('Revision 1', { exact: true })).toBeVisible()

    // Every member, the owner included, is asked now: the space's text first, the section after it.
    await page.goto(tenantOrigin())
    await expect(page.getByText('Please review the terms')).toBeVisible({ timeout: 60_000 })
    const body = page.locator('.terms-body')
    await expect(body).toContainText(spaceRule)
    await expect(body.getByTestId('operator-section')).toContainText('On this instance: reports, measures and what is kept')
    await page.getByRole('button', { name: 'I agree' }).click()
    await expect(page.getByRole('button', { name: 'New', exact: true })).toBeVisible({ timeout: 60_000 })
  },
)

test(
  'a guest meets the space’s terms and the section at the link, and enters after accepting',
  { tag: '@uc.sharing.share-accept:the-guest-accepts-the-triple' },
  async () => {
    const { page } = guest
    await page.goto(shareLink)
    await expect(page.getByText('Please accept the terms of use')).toBeVisible({ timeout: 60_000 })
    const body = page.locator('.share-terms-body')
    await expect(body).toContainText(spaceRule)
    await expect(body.getByTestId('operator-section')).toContainText('On this instance: reports, measures and what is kept')
    await page.getByRole('button', { name: 'Accept and continue' }).click()
    await expect(page.getByText('Please accept the terms of use')).toBeHidden()
    await expect(page.getByText(sharedFolder).first()).toBeVisible({ timeout: 60_000 })
    // The acceptance stays on the device and holds the triple; a reload does not ask again.
    await page.reload()
    await expect(page.getByText(sharedFolder).first()).toBeVisible({ timeout: 60_000 })
    await expect(page.getByText('Please accept the terms of use')).toBeHidden()
  },
)

/** The console, signed in as the Server Owner, at Server → Operating mode; sets [mode] and waits for it to hold. */
async function setMode(page: Page, mode: 'Private (family, circle of friends)' | 'Club, organisation or company') {
  const { owner } = instance()
  await page.goto(stack().adminOrigin)
  await page.getByLabel('Email', { exact: true }).fill(owner.email)
  await passwordField(page).fill(recordTyped(owner.password))
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('heading', { name: 'Server administration', level: 1 })).toBeVisible({ timeout: 60_000 })
  await page.getByRole('button', { name: 'Operating mode', exact: true }).click()
  await page.getByRole('button', { name: mode }).click()
  const tick = page.getByRole('checkbox', { name: 'I have read this and choose Private on my own responsibility.' })
  if (mode.startsWith('Private')) await tick.check()
  await page.getByRole('button', { name: 'Set operating mode' }).click()
  await expect(page.getByText(`In effect: ${mode}.`)).toBeVisible()
}

test(
  'in the private mode the owner is asked again with the reduced section, and a German visitor reads it',
  {
    tag: [
      '@uc.account.accept-terms:the-gate-compares-the-triple',
      '@uc.account.accept-terms:the-variant-follows-the-operating-mode',
      '@uc.admin.privacy-notice:a-missing-value-follows-the-mode',
    ],
  },
  async ({ browser }) => {
    const console_ = await newDevice(browser)
    const context = await browser.newContext({ locale: 'de-DE' })
    const confinement = await confineToInstance(context)
    try {
      await setMode(console_.page, 'Private (family, circle of friends)')

      const { page } = owner
      await page.goto(tenantOrigin())
      await expect(page.getByText('Please review the terms')).toBeVisible({ timeout: 60_000 })
      const section = page.locator('.terms-body').getByTestId('operator-section')
      await expect(section).toContainText('On this instance: reports, measures and what is kept')
      await expect(section.getByRole('heading', { name: 'Authorities and contact' })).toHaveCount(0)
      await page.getByRole('button', { name: 'I agree' }).click()
      await expect(page.getByRole('button', { name: 'New', exact: true })).toBeVisible({ timeout: 60_000 })

      const german = await context.newPage()
      await german.goto(`${tenantOrigin()}/operator-terms`)
      const reduced = german.getByTestId('operator-section')
      await expect(reduced.getByText('Entwurf: Dieser Abschnitt ist noch nicht anwaltlich geprüft.')).toBeVisible({ timeout: 60_000 })
      await expect(reduced.getByRole('heading', { name: 'Auf dieser Instanz: Meldungen, Maßnahmen und Aufbewahrung' })).toBeVisible()
      await expect(reduced.getByRole('heading', { name: 'Inhalte melden' })).toBeVisible()
      await expect(reduced.getByRole('heading', { name: 'Behörden und Kontakt' })).toHaveCount(0)
      await expect(german.locator('#guest-write-record')).toHaveCount(0) // the private preset switched the record off

      // The reduced privacy notice: the record's passage gone with its switch, the recipient's open country simply
      // absent rather than a gap (owner Q26) — the full one shows "von der Betreiberseite nicht angegeben" there.
      await german.goto(`${tenantOrigin()}/privacy`)
      await expect(german.locator('#recipients')).toContainText('E2E Hosting GmbH: hosts the server', { timeout: 60_000 })
      await expect(german.locator('#recipients')).not.toContainText('nicht angegeben')
      await expect(german.locator('#guest-write-record')).toHaveCount(0)
      await expect(german.locator('aside#right-to-object')).toBeVisible() // control: the page rendered, its general right too
      confinement.assertConfined()
    } finally {
      await setMode(console_.page, 'Club, organisation or company')
      console_.confinement.assertConfined()
      await console_.context.close()
      await context.close()
    }
  },
)

