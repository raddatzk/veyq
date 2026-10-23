// A name reported on its own, and everything from one uploader, in a browser (cr-report-a-name-and-an-uploader's browser
// pass; uc.moderation.report A8, uc.moderation.review-a-case A8, uc.moderation.take-a-measure A8; ADR 0093 §§ 1, 6, 9).
// Written, not yet run (the owner's test rule of 2026-10-19: lane L1 runs once V1 is through). The tenant owner keeps a
// folder with an offending name and two files in it; then
//
//   1. the owner selects the folder and reports **only its name** — the dialog's choice for one node;
//   2. the moderator — the Server Owner, holding the key since first-run setup (moderation.spec.ts) — finds the case as
//      "A name on its own" and reviews it: the review shows that name, and nothing of what the folder holds;
//   3. the owner reports one of the two files, whole;
//   4. the moderator opens that case, reads the owner listed as an uploader with the two items they uploaded to the
//      space, and blocks everything from this uploader: both files show "Blocked by the operator";
//   5. a file the owner uploads after the decision is not blocked.
//
// The name, the reasons and the facts are canaries (`recordTyped`), so lane L1's sweep proves they reached the server
// sealed. The labels are src/web/'s own (src/web/src/i18n/index.ts, en).
import { test, expect, type Page } from '@playwright/test'
import { randomBytes } from 'node:crypto'
import { recordTyped } from '../../src/canaries'
import { instance, newDevice, passwordField, signInOwner, tenantOrigin, type Device } from '../../src/devices'

test.describe.configure({ mode: 'serial' })

const stamp = randomBytes(3).toString('hex')
const folder = `Anstößiger Ordnername ${stamp}`
const fileOne = `Erste Datei ${stamp}.bin`
const fileTwo = `Zweite Datei ${stamp}.bin`
const fileLater = `Spätere Datei ${stamp}.bin`
const nameReason = `Der Name beleidigt jemanden ${stamp}`
const fileReason = `Diese Datei ist rechtswidrig ${stamp}`
const facts = `Alle Dateien dieser Person verstoßen gegen die Regeln ${stamp}`
const BLOCKED = 'Blocked by the operator'

let owner: Device
let moderator: Device

/** Report what the dialog was opened for: a category, a reason, the good-faith tick; [nameOnly] picks "Only its name". */
async function fillReport(page: Page, category: string, reason: string, nameOnly: boolean): Promise<void> {
  const dialog = page.getByRole('dialog', { name: 'Report' })
  await expect(dialog).toBeVisible()
  // One node: the dialog asks what is reported.
  await expect(dialog.getByText('What do you report?')).toBeVisible()
  if (nameOnly) {
    await dialog.getByRole('button', { name: 'Only its name' }).click()
    await expect(dialog.getByText('The moderators can open its name and nothing else')).toBeVisible()
  }
  await dialog.locator('.veyq-select').click()
  await page.getByRole('option', { name: category }).click()
  await dialog.locator('#veyq-report-reason').fill(recordTyped(reason))
  await dialog.getByText('I confirm that what I say here is accurate and complete to the best of my knowledge.').click()
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

async function upload(page: Page, name: string): Promise<void> {
  const chooser = page.waitForEvent('filechooser')
  await page.getByRole('button', { name: 'New', exact: true }).click()
  await page.getByRole('menuitem', { name: 'Upload files' }).click()
  await (await chooser).setFiles({ name: recordTyped(name), mimeType: 'application/octet-stream', buffer: randomBytes(4096) })
  await expect(page.getByRole('button', { name: `Open ${name}`, exact: true })).toBeVisible({ timeout: 60_000 })
}

/** The moderation gate on this page: sign in if it asks (never the member login), then unlock with both secrets. */
async function passGate(page: Page): Promise<void> {
  const account = instance().owner
  const signIn = page.getByRole('button', { name: 'Sign in', exact: true })
  const unlockForm = page.locator('.moderation-unlock')
  await expect(signIn.or(unlockForm).first()).toBeVisible({ timeout: 60_000 })
  if (await signIn.isVisible()) {
    await page.getByLabel('Email', { exact: true }).fill(account.email)
    await passwordField(page).fill(recordTyped(account.password))
    await signIn.click()
  }
  await expect(unlockForm).toBeVisible({ timeout: 60_000 })
  await unlockForm.getByRole('textbox', { name: /^Password\b/ }).fill(recordTyped(account.password))
  await page.locator('#moderation-secret-key').fill(recordTyped(account.secretKey))
  await page.getByRole('button', { name: 'Open the moderation key' }).click()
}

/** `/moderation`, unlocked, with the open case of [kind] and [category] open — this scenario's only such one. */
async function openCase(page: Page, kind: string, category: string): Promise<void> {
  await expect(async () => {
    await page.goto(`${tenantOrigin()}/moderation`)
  }).toPass({ timeout: 60_000, intervals: [1_000] })
  await passGate(page)
  const row = page
    .locator('.moderation-case-row')
    .filter({ hasText: kind })
    .filter({ hasText: category })
    .filter({ has: page.locator('.veyq-badge', { hasText: /^Open$/ }) })
  await expect(row).toHaveCount(1, { timeout: 60_000 })
  await row.getByRole('button', { name: 'Open', exact: true }).click()
  await expect(page.locator('.moderation-case')).toBeVisible()
}

/** The open folder's level, from the space's top. */
async function openFolder(page: Page): Promise<void> {
  await page.goto(tenantOrigin())
  await page.getByRole('button', { name: folder, exact: true }).click()
  await expect(page.getByRole('heading', { name: folder })).toBeVisible({ timeout: 60_000 })
}

test.beforeAll(async ({ browser }) => {
  owner = await newDevice(browser)
  moderator = await newDevice(browser)
})

test.afterEach(() => {
  for (const device of [owner, moderator]) device?.confinement.assertConfined()
})

test.afterAll(async () => {
  for (const device of [owner, moderator]) await device?.context.close()
})

test(
  'the owner reports only the name of a folder that holds two files',
  { tag: '@uc.moderation.report:a-name-is-reported-on-its-own' },
  async () => {
    const { page } = owner
    await signInOwner(owner)
    await page.getByRole('button', { name: 'New', exact: true }).click()
    await page.getByRole('menuitem', { name: 'New folder' }).click()
    const create = page.getByRole('dialog', { name: 'Create a folder' })
    await create.getByLabel('Name').fill(recordTyped(folder))
    await create.getByRole('button', { name: 'Create', exact: true }).click()
    await expect(create).toBeHidden()
    await openFolder(page)
    await upload(page, fileOne)
    await upload(page, fileTwo)

    await page.goto(tenantOrigin())
    await expect(page.getByRole('button', { name: folder, exact: true })).toBeVisible({ timeout: 60_000 })
    await page.locator('.veyq-select-button').click()
    await tick(page, folder)
    await page.locator('.veyq-select-report').click()
    await fillReport(page, 'A breach of the terms', nameReason, true)
  },
)

test(
  'the moderator reviews the name alone: its text, nothing of what the folder holds',
  { tag: '@uc.moderation.review-a-case:a-reported-name-is-reviewed-alone' },
  async () => {
    const { page } = moderator
    await openCase(page, 'A name on its own', 'A breach of the terms')
    await expect(page.locator('.moderation-report')).toContainText(nameReason)
    await page.getByRole('button', { name: 'Review what was reported' }).click()
    await expect(page).toHaveURL(new RegExp(`^${tenantOrigin()}/s/[0-9a-f-]{36}#moderation$`), { timeout: 60_000 })
    await passGate(page)
    await expect(page.locator('.moderation-review-name')).toContainText(folder, { timeout: 60_000 })
    await expect(page.locator('.moderation-review-name')).toContainText('The name that was reported, on its own')
    // Nothing below the name: no level, no file of the folder.
    await expect(page.getByRole('button', { name: `Open ${fileOne}`, exact: true })).toHaveCount(0)
    await expect(page.getByRole('button', { name: `Open ${fileTwo}`, exact: true })).toHaveCount(0)
  },
)

test(
  'the owner reports one of the two files, whole',
  { tag: '@uc.moderation.report:a-member-reports-what-their-space-holds' },
  async () => {
    const { page } = owner
    await openFolder(page)
    await page.locator('.veyq-select-button').click()
    await tick(page, fileOne)
    await page.locator('.veyq-select-report').click()
    await fillReport(page, 'Other illegal content', fileReason, false)
  },
)

test(
  'the moderator blocks everything from the uploader: both files show "Blocked by the operator"',
  { tag: '@uc.moderation.take-a-measure:everything-from-one-uploader' },
  async () => {
    const { page } = moderator
    await openCase(page, 'Content in a space', 'Other illegal content')
    const uploader = page.locator('.moderation-uploader').filter({ hasText: 'Member' })
    await expect(uploader).toHaveCount(1)
    await expect(uploader.locator('.moderation-uploader-items')).toHaveText('2 items uploaded to this space')
    // Every statement asks for the facts and the clause (one form on every instance).
    await page.locator('#moderation-statement-facts').fill(recordTyped(facts))
    await page.locator('#moderation-statement-provision').fill('Space terms, clause 4')
    const label = 'Block everything from this uploader (2)'
    await uploader.getByRole('button', { name: label }).click()
    const confirm = page.getByRole('dialog', { name: label })
    await expect(confirm).toBeVisible()
    await confirm.getByRole('button', { name: label, exact: true }).click()
    await expect(confirm).toBeHidden()
    await expect(page.locator('.moderation-measure')).toContainText('Everything from one uploader blocked', { timeout: 60_000 })
    await expect(page.locator('.moderation-measure')).toContainText('2 items')

    const o = owner.page
    await openFolder(o)
    for (const name of [fileOne, fileTwo]) {
      await expect(o.locator('.veyq-level-item').filter({ hasText: name })).toContainText(BLOCKED, { timeout: 60_000 })
    }
  },
)

test(
  'a file the uploader adds after the decision is not blocked',
  { tag: '@uc.moderation.take-a-measure:everything-from-one-uploader' },
  async () => {
    const { page } = owner
    await openFolder(page)
    await upload(page, fileLater)
    await expect(page.locator('.veyq-level-item').filter({ hasText: fileLater })).not.toContainText(BLOCKED)
    await expect(page.locator('.veyq-level-item').filter({ hasText: fileOne })).toContainText(BLOCKED)
  },
)
