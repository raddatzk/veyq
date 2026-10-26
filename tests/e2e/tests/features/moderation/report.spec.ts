// Reporting in a browser (cr-report-and-hold phase 6's browser pass; uc.moderation.report, ADR 0093 §§ 1-2, 4). The tenant
// owner keeps a folder with a file in it and shares it with a view-only link; then
//
//   1. the owner selects the folder and reports it with core's dialog — a threat to life, so the moderators are mailed at
//      once — leaving an address for the receipt;
//   2. a guest of the link opens it, selects the file and reports it;
//   3. the instance mailed every confirmed moderator (moderation.spec.ts confirmed one before this file runs) the category
//      and "new case", with no link; and the reporter its receipt.
//
// The reasons are canaries (`recordTyped`): lane L1's sweep then proves they reached no table, no bucket object, no log
// and no statement — the report arrived sealed. That the hold pinned the file's objects is read off the kept stack's
// database after the run (the CR's record). The labels are src/web/'s own (src/web/src/i18n/index.ts, en).
import { test, expect, type Page } from '@playwright/test'
import { randomBytes } from 'node:crypto'
import { recordTyped } from '../../../src/canaries'
import { awaitMails } from '../../../src/mail'
import { newDevice, signInOwner, tenantOrigin, type Device } from '../../../src/devices'
import { stack } from '../../../src/stack'
import { headerAction } from '../../../src/header'

test.describe.configure({ mode: 'serial' })

const stamp = randomBytes(3).toString('hex')
const folder = `Gemeldeter Ordner ${stamp}`
const fileName = `Flugblatt ${stamp}.bin`
const memberReason = `Darin wird jemandem gedroht ${stamp}`
const guestReason = `Das verstößt gegen die Regeln ${stamp}`
const reporterAddress = `reporter-${stamp}@example.com`
const URGENT = /^veyq: a new moderation case/
const RECEIPT = /^veyq: your report was received/

let member: Device
let guest: Device
let shareLink: string

/** Report what is selected (or the item the dialog was opened for): a category, a reason, the good-faith tick. */
async function fillReport(page: Page, category: string, reason: string, address?: string): Promise<void> {
  const dialog = page.getByRole('dialog', { name: 'Report' })
  await expect(dialog).toBeVisible()
  await dialog.locator('.veyq-select').click()
  await page.getByRole('option', { name: category }).click()
  await dialog.locator('#veyq-report-reason').fill(recordTyped(reason))
  await dialog.getByText('I confirm that what I say here is accurate and complete to the best of my knowledge.').click()
  if (address) await dialog.locator('#veyq-report-contact').fill(address)
  await dialog.getByRole('button', { name: 'Send report' }).click()
  await expect(dialog.getByText('Your report was received.')).toBeVisible({ timeout: 60_000 })
  await dialog.locator('.veyq-report-receipt').getByRole('button', { name: 'Close' }).click()
  await expect(dialog).toBeHidden()
}

/** Select a row in the level's Select mode the way a person does: the drawn box, which is the checkbox's label. */
async function tick(page: Page, title: string): Promise<void> {
  const box = page.getByRole('checkbox', { name: title, exact: true })
  await page.locator('label.veyq-level-item-check').filter({ has: box }).click()
  await expect(box).toBeChecked()
}

test.beforeAll(async ({ browser }) => {
  member = await newDevice(browser)
  guest = await newDevice(browser)
})

test.afterEach(() => {
  for (const device of [member, guest]) device?.confinement.assertConfined()
})

test.afterAll(async () => {
  for (const device of [member, guest]) await device?.context.close()
})

test(
  'a member selects a folder of the space and reports it with the dialog',
  { tag: ['@uc.moderation.report:a-member-reports-what-their-space-holds', '@uc.moderation.report:the-dialog-asks-what-the-dsa-names'] },
  async () => {
    const { page } = member
    await signInOwner(member)
    await page.getByRole('button', { name: 'New', exact: true }).click()
    await page.getByRole('menuitem', { name: 'New folder' }).click()
    const create = page.getByRole('dialog', { name: 'Create a folder' })
    await create.getByLabel('Name').fill(recordTyped(folder))
    await create.getByRole('button', { name: 'Create', exact: true }).click()
    await expect(create).toBeHidden()

    // A file in it: the object the hold pins.
    await page.getByRole('button', { name: folder, exact: true }).click()
    await expect(page.getByRole('heading', { name: folder })).toBeVisible()
    const chooser = page.waitForEvent('filechooser')
    await page.getByRole('button', { name: 'New', exact: true }).click()
    await page.getByRole('menuitem', { name: 'Upload files' }).click()
    await (await chooser).setFiles({ name: recordTyped(fileName), mimeType: 'application/octet-stream', buffer: randomBytes(4096) })
    await expect(page.getByRole('button', { name: `Open ${fileName}`, exact: true })).toBeVisible({ timeout: 60_000 })

    // A view-only link on the folder, for the guest.
    await headerAction(page, 'Share')
    const share = page.getByRole('dialog', { name: 'Share this folder' })
    await share.getByRole('button', { name: 'New share' }).click()
    const createShare = page.getByRole('dialog', { name: 'New share' })
    await createShare.getByRole('button', { name: 'Create link' }).click()
    shareLink = await page.getByRole('dialog', { name: 'Share this folder' }).getByRole('textbox', { name: 'Public link' }).inputValue()
    await page.getByRole('dialog', { name: 'Share this folder' }).getByRole('button', { name: 'Done' }).click()
    await page.keyboard.press('Escape')

    // Back at the space's top level: select the folder, Report.
    await page.goto(tenantOrigin())
    await expect(page.getByRole('button', { name: folder, exact: true })).toBeVisible({ timeout: 60_000 })
    await page.locator('.veyq-select-button').click()
    await tick(page, folder)
    await page.locator('.veyq-select-report').click()
    await fillReport(page, 'A threat to someone’s life or safety', memberReason, reporterAddress)
  },
)

test(
  'a guest of the link selects the file and reports it through the link',
  { tag: '@uc.moderation.report:a-guest-reports-only-inside-the-link' },
  async () => {
    const { page } = guest
    await page.goto(shareLink)
    await expect(page.getByRole('button', { name: `Open ${fileName}`, exact: true })).toBeVisible({ timeout: 60_000 })
    await page.locator('.veyq-select-button').click()
    await tick(page, fileName)
    await page.locator('.veyq-select-report').click()
    await fillReport(page, 'A breach of the terms', guestReason)
  },
)

test(
  'the moderators are mailed the category and "new case", with no link; the reporter gets a receipt',
  { tag: ['@uc.moderation.report:moderators-are-mailed-the-category-only', '@uc.moderation.report:the-reporter-gets-a-receipt'] },
  async () => {
    const [receipt] = await awaitMails(reporterAddress, RECEIPT)
    expect(receipt!.text).toMatch(/Reference: [0-9a-f]{8}/)
    // The new-case mail goes to each confirmed moderator; who they are is the moderation scenario's, so it is read off
    // the relay by its subject.
    const response = await fetch(`${stack().mailApi}/api/v1/messages?limit=200`)
    const { messages } = (await response.json()) as { messages: { ID: string; Subject: string }[] }
    const urgent = messages.filter((m) => URGENT.test(m.Subject))
    expect(urgent.length, 'a new-case mail went out').toBeGreaterThan(0)
    const text = ((await (await fetch(`${stack().mailApi}/api/v1/message/${urgent[0]!.ID}`)).json()) as { Text: string }).Text
    expect(text).toContain('a threat to life or safety')
    expect(text).not.toMatch(/https?:\/\//)
    expect(text).not.toContain(folder)
  },
)
