// Reviewing a case and taking a measure, in a browser (cr-moderation-review-and-measures phase 5's browser pass;
// uc.moderation.review-a-case, uc.moderation.take-a-measure, ADR 0093 §§ 3, 5-6, ADR 0099 § 6). The tenant owner keeps a
// Memory with a photo and shares it read-only; then
//
//   1. a guest of the link opens the photo and reports it;
//   2. the Server Owner — a moderator holding the key since first-run setup (moderation.spec.ts) — opens `/moderation` on
//      the tenant's address, unlocks, finds the case in the queue and reads the report, opened in the browser;
//   3. "Review" opens the share viewer in its moderation mode on the tenant's host (`/s/<caseId>#moderation`): the gate
//      again, then the held photo drawn from the case's own reads;
//   4. the moderator blocks the content: the owner and the guest both see "Blocked by the operator" in the photo's place;
//   5. the moderator lifts the measure: the photo is back for both.
//
// The reasons and names are canaries (`recordTyped`), so lane L1's sweep proves the report, and the photo the review
// renders, reached the server sealed. The labels are web/'s own (web/src/i18n/index.ts, en).
import { test, expect, type Locator, type Page } from '@playwright/test'
import { randomBytes } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { recordTyped } from '../../src/canaries'
import { instance, newDevice, passwordField, signInOwner, tenantOrigin, type Device } from '../../src/devices'

test.describe.configure({ mode: 'serial' })

// The JPEG the round trip uploads (1×1, decodable); the repository is mounted read-only in the browser container.
const PHOTO = fileURLToPath(new URL('../../../web/src/features/memory/__fixtures__/exif-sample.jpg', import.meta.url))
const PHOTO_TEXT = ['Marienplatz at golden hour', '(c) 2021 A. Photographer']

const stamp = randomBytes(3).toString('hex')
const memoryName = `Gemeldete Erinnerung ${stamp}`
const photoName = `Strittiges Bild ${stamp}.jpg`
const guestReason = `Dieses Bild verletzt die Regeln ${stamp}`
const BLOCKED = 'Blocked by the operator'
const BLOCK_CONTENT = 'Block the content'

let owner: Device
let guest: Device
let moderator: Device
let shareLink: string

/** The first photo of the open Memory, decrypted in this page: an `<img>` on a `blob:` URL that decoded. */
async function expectDecodedPhoto(page: Page): Promise<void> {
  const thumbnail: Locator = page.getByRole('button', { name: 'Open item 1' }).locator('img.media-tile-img')
  await expect(thumbnail).toBeVisible({ timeout: 60_000 })
  await expect(thumbnail).toHaveAttribute('src', /^blob:/)
  await expect.poll(() => thumbnail.evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth),
    { message: 'the photo decoded' }).toBeGreaterThan(0)
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

/** `/moderation` on the tenant's address, unlocked, with this scenario's case open. */
async function openCase(page: Page): Promise<void> {
  await expect(async () => {
    await page.goto(`${tenantOrigin()}/moderation`)
  }).toPass({ timeout: 60_000, intervals: [1_000] })
  await passGate(page)
  // The queue's clear fields: the medium's case is the one "single item" case of this run (report.spec.ts reported
  // content in a space), open, with its one report.
  const row = page
    .locator('.moderation-case-row')
    .filter({ hasText: 'A single item, such as a photo' })
    .filter({ has: page.locator('.veyq-badge', { hasText: /^Open$/ }) })
  await expect(row).toHaveCount(1, { timeout: 60_000 })
  await expect(row).toContainText('A breach of the terms')
  await row.getByRole('button', { name: 'Open', exact: true }).click()
  await expect(page.locator('.moderation-case')).toBeVisible()
}

/** Confirm the app's own dialog for a decision: its header and its accept button are the decision's label. */
async function confirmDecision(page: Page, label: string): Promise<void> {
  const dialog = page.getByRole('dialog', { name: label })
  await expect(dialog).toBeVisible()
  await dialog.getByRole('button', { name: label, exact: true }).click()
  await expect(dialog).toBeHidden()
}

test.beforeAll(async ({ browser }) => {
  owner = await newDevice(browser)
  guest = await newDevice(browser)
  moderator = await newDevice(browser)
})

test.afterEach(() => {
  for (const device of [owner, guest, moderator]) device?.confinement.assertConfined()
})

test.afterAll(async () => {
  for (const device of [owner, guest, moderator]) await device?.context.close()
})

test(
  'the owner keeps a Memory with a photo and shares it read-only; a guest of the link reports the photo',
  { tag: '@uc.moderation.report:a-guest-reports-only-inside-the-link' },
  async () => {
    const { page } = owner
    await signInOwner(owner)
    await page.getByRole('button', { name: 'New', exact: true }).click()
    await page.getByRole('menuitem', { name: 'New Memory' }).click()
    const create = page.getByRole('dialog', { name: 'Create a Memory' })
    await create.getByLabel('Name').fill(recordTyped(memoryName))
    await create.getByRole('button', { name: 'Create', exact: true }).click()
    await expect(create).toBeHidden()
    await page.getByRole('button', { name: memoryName, exact: true }).click()
    await expect(page.getByRole('heading', { level: 1, name: memoryName })).toBeVisible()

    for (const text of PHOTO_TEXT) recordTyped(text)
    const chooser = page.waitForEvent('filechooser')
    await page.getByRole('button', { name: 'Upload', exact: true }).click()
    await (await chooser).setFiles({ name: recordTyped(photoName), mimeType: 'image/jpeg', buffer: readFileSync(PHOTO) })
    await expectDecodedPhoto(page)

    await page.getByRole('button', { name: 'Share', exact: true }).click()
    const manage = page.getByRole('dialog', { name: 'Share this Memory' })
    await manage.getByRole('button', { name: 'New share' }).click()
    const createShare = page.getByRole('dialog', { name: 'New share' })
    await createShare.getByRole('button', { name: 'Create link' }).click()
    shareLink = await createShare.getByRole('textbox', { name: 'Public link' }).inputValue()
    await createShare.getByRole('button', { name: 'Done' }).click()

    // The guest opens the photo and reports it from the lightbox.
    const g = guest.page
    await g.goto(shareLink)
    await expect(g.getByRole('heading', { level: 1, name: memoryName })).toBeVisible({ timeout: 60_000 })
    await expectDecodedPhoto(g)
    await g.getByRole('button', { name: 'Open item 1' }).click()
    await g.locator('.lightbox-report-button').click()
    const dialog = g.getByRole('dialog', { name: 'Report' })
    await expect(dialog).toBeVisible()
    await dialog.locator('.p-select').click()
    await g.getByRole('option', { name: 'A breach of the terms' }).click()
    await dialog.locator('#veyq-report-reason').fill(recordTyped(guestReason))
    await dialog.getByText('I confirm that what I say here is accurate and complete to the best of my knowledge.').click()
    await dialog.getByRole('button', { name: 'Send report' }).click()
    await expect(dialog.getByText('Your report was received.')).toBeVisible({ timeout: 60_000 })
    await dialog.locator('.veyq-report-receipt').getByRole('button', { name: 'Close' }).click()
  },
)

test(
  'the moderator finds the case in the queue and reads the report, opened in the browser',
  {
    tag: [
      '@uc.moderation.review-a-case:the-queue-lists-cases-in-clear-fields',
      '@uc.moderation.review-a-case:a-case-shows-its-reports-and-log',
    ],
  },
  async () => {
    const { page } = moderator
    await openCase(page)
    await expect(page.locator('.moderation-report')).toHaveCount(1)
    await expect(page.locator('.moderation-report')).toContainText(guestReason)
  },
)

test(
  "the review opens on the tenant's host in the share viewer's moderation mode and shows the held photo",
  { tag: '@uc.moderation.review-a-case:moderation-mode-renders-the-guest-viewer-without-guest-calls' },
  async () => {
    const { page } = moderator
    // Every request the review makes, in order: none may be a link's (`/api/shares/…`) or a member's scope read.
    const paths: string[] = []
    page.on('request', (r) => {
      const url = new URL(r.url())
      if (url.pathname.startsWith('/api/')) paths.push(url.pathname)
    })
    await page.getByRole('button', { name: 'Review what was reported' }).click()
    await expect(page).toHaveURL(new RegExp(`^${tenantOrigin()}/s/[0-9a-f-]{36}#moderation$`), { timeout: 60_000 })
    const caseId = new URL(page.url()).pathname.split('/').pop()!
    await passGate(page)

    await expect(page.locator('.moderation-review-reason')).toHaveText(guestReason, { timeout: 60_000 })
    const stage = page.locator('.medium-review-stage img')
    await expect(stage).toHaveAttribute('src', /^blob:/, { timeout: 60_000 })
    await expect.poll(() => stage.evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth),
      { message: 'the held photo decoded' }).toBeGreaterThan(0)
    await expect(page.locator('.medium-review-name')).toHaveText(photoName)

    const review = paths.filter((p) => !p.startsWith('/api/auth/') && !p.startsWith('/api/moderation/me') && !p.startsWith('/api/session'))
    expect(review.some((p) => p === `/api/moderation/cases/${caseId}/review`), 'the review read').toBe(true)
    expect(review.filter((p) => p.startsWith('/api/shares/') || p.startsWith('/api/scopes/')), 'no link read, no scope read').toEqual([])
  },
)

test(
  'blocking the content puts "Blocked by the operator" in its place for the owner and the guest',
  {
    tag: [
      '@uc.moderation.take-a-measure:blocked-content-is-refused-and-marked',
      '@uc.moderation.take-a-measure:placeholder-for-members-and-guests',
    ],
  },
  async () => {
    const { page } = moderator
    await openCase(page)
    await page.locator('.moderation-block-content').click()
    await confirmDecision(page, BLOCK_CONTENT)
    await expect(page.locator('.moderation-lift')).toHaveCount(1, { timeout: 60_000 })

    for (const device of [owner, guest]) {
      const p = device.page
      await p.reload()
      const tile = p.getByRole('button', { name: 'Open item 1' })
      await expect(tile).toContainText(BLOCKED, { timeout: 60_000 })
      await expect(tile.locator('img.media-tile-img')).toHaveCount(0)
    }
  },
)

test(
  'lifting the measure gives the photo back to both',
  { tag: '@uc.moderation.take-a-measure:every-measure-is-lifted-and-logged' },
  async () => {
    const { page } = moderator
    await page.locator('.moderation-lift').click()
    await confirmDecision(page, 'Lift')
    await expect(page.locator('.moderation-lift')).toHaveCount(0, { timeout: 60_000 })

    for (const device of [owner, guest]) {
      const p = device.page
      await p.reload()
      await expectDecodedPhoto(p)
      await expect(p.getByRole('button', { name: 'Open item 1' })).not.toContainText(BLOCKED)
    }
  },
)
