// The statement of reasons and the objection, in a browser (cr-statement-of-reasons-and-objection phase 3's browser pass;
// uc.moderation.object-to-a-measure, ADR 0093 § 7, ADR 0099 § 5). The tenant owner keeps a Memory with a photo and
// shares it; a guest reports the photo and leaves an address; then
//
//   1. the Server Owner — a moderator — blocks the photo with the facts of its statement; the measure shows whom the
//      statement reached; the owner's address gets the statement by mail, without the facts, and the reporter the decision;
//   2. the owner (affected: the uploader and the space's owner) opens the photo and reads the statement under "Blocked by
//      the operator", the facts opened in the browser, and objects;
//   3. the Server Owner, who took the measure, sees the objection opened but may not decide it: the deputy appointed in
//      moderation.spec.ts holds the key;
//   4. the deputy opens `/moderation` on its own tenant's address and lifts the measure; the owner is mailed the answer
//      and gets the photo back.
//
// It runs after moderation.spec.ts (the deputy, `deputy.json`) and instance-mail.spec.ts (the instance mail). The facts,
// the objection and every name are canaries (`recordTyped`): lane L1's sweep proves they reached the server sealed. The
// labels are src/web/'s own (src/web/src/i18n/index.ts, en).
import { test, expect, type Locator, type Page } from '@playwright/test'
import { randomBytes } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { recordTyped } from '../../src/canaries'
import { deputy as deputyAccount, instance, newDevice, passwordField, signInOwner, tenantOrigin, type Device } from '../../src/devices'
import { awaitMails, mailsTo } from '../../src/mail'
import { stack } from '../../src/stack'
import { headerAction } from '../../src/header'

test.describe.configure({ mode: 'serial' })

const PHOTO = fileURLToPath(new URL('../../../../src/web/src/features/memories/__fixtures__/exif-sample.jpg', import.meta.url))

const stamp = randomBytes(3).toString('hex')
const memoryName = `Begründete Erinnerung ${stamp}`
const photoName = `Gesperrtes Bild ${stamp}.jpg`
const guestReason = `Das Bild zeigt eine fremde Adresse ${stamp}`
const facts = `Auf dem Bild ist eine Wohnadresse neben einer Drohung zu lesen ${stamp}`
// The provision is clear text by design (DSA Art. 17(3)(d)): the moderator's short reference, never a canary.
const provision = '§ 241 StGB'
const objection = `Das ist meine eigene Adresse, ich habe sie selbst geteilt ${stamp}`
const reporterAddress = () => `reporter-${stamp}@${stack().domain}`
const BLOCKED = 'Blocked by the operator'
const STATEMENT_MAIL = /^veyq: a moderation decision concerns you/
const DECISION_MAIL = /^veyq: the decision on your report/
const ANSWER_MAIL = /^veyq: your objection was decided/

let owner: Device
let guest: Device
let moderator: Device
let deputy: Device
let shareLink: string
let statementsBefore = 0

async function expectDecodedPhoto(page: Page): Promise<void> {
  const thumbnail: Locator = page.getByRole('button', { name: 'Open item 1' }).locator('img.media-tile-img')
  await expect(thumbnail).toBeVisible({ timeout: 60_000 })
  await expect(thumbnail).toHaveAttribute('src', /^blob:/)
}

/** `/moderation` on [origin] for [account]: sign in if it asks, then unlock with both secrets. */
async function openModeration(page: Page, origin: string, account: { email: string; password: string; secretKey: string }): Promise<void> {
  await expect(async () => {
    await page.goto(`${origin}/moderation`)
  }).toPass({ timeout: 60_000, intervals: [1_000] })
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

/** This scenario's case in the queue: the one single-item case of the run whose report names [state]. */
async function openThisCase(page: Page, row: Locator): Promise<void> {
  await expect(row).toHaveCount(1, { timeout: 60_000 })
  await row.getByRole('button', { name: 'Open', exact: true }).click()
  await expect(page.locator('.moderation-case')).toBeVisible()
}

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
  deputy = await newDevice(browser)
})

test.afterEach(() => {
  for (const device of [owner, guest, moderator, deputy]) device?.confinement.assertConfined()
})

test.afterAll(async () => {
  for (const device of [owner, guest, moderator, deputy]) await device?.context.close()
})

test(
  'the owner shares a Memory with a photo; a guest reports the photo and leaves an address',
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
    const chooser = page.waitForEvent('filechooser')
    await page.getByRole('button', { name: 'Upload', exact: true }).click()
    await (await chooser).setFiles({ name: recordTyped(photoName), mimeType: 'image/jpeg', buffer: readFileSync(PHOTO) })
    await expectDecodedPhoto(page)

    await headerAction(page, 'Share')
    const manage = page.getByRole('dialog', { name: 'Share this Memory' })
    await manage.getByRole('button', { name: 'New share' }).click()
    const createShare = page.getByRole('dialog', { name: 'New share' })
    await createShare.getByRole('button', { name: 'Create link' }).click()
    shareLink = await createShare.getByRole('textbox', { name: 'Public link' }).inputValue()
    await createShare.getByRole('button', { name: 'Done' }).click()

    const g = guest.page
    await g.goto(shareLink)
    await expect(g.getByRole('heading', { level: 1, name: memoryName })).toBeVisible({ timeout: 60_000 })
    await expectDecodedPhoto(g)
    await g.getByRole('button', { name: 'Open item 1' }).click()
    await g.locator('.lightbox-report-button').click()
    const dialog = g.getByRole('dialog', { name: 'Report' })
    await expect(dialog).toBeVisible()
    await dialog.locator('.veyq-select').click()
    await g.getByRole('option', { name: 'Other illegal content' }).click()
    await dialog.locator('#veyq-report-reason').fill(recordTyped(guestReason))
    await dialog.locator('#veyq-report-contact').fill(reporterAddress())
    await dialog.getByText('I confirm that what I say here is accurate and complete to the best of my knowledge.').click()
    await dialog.getByRole('button', { name: 'Send report' }).click()
    await expect(dialog.getByText('Your report was received.')).toBeVisible({ timeout: 60_000 })
    await dialog.locator('.veyq-report-receipt').getByRole('button', { name: 'Close' }).click()
  },
)

test(
  'the moderator blocks the photo with the facts; the owner is mailed the statement without them, the reporter the decision',
  {
    tag: [
      '@uc.moderation.object-to-a-measure:the-statement-is-sent-with-the-measure',
      '@uc.moderation.object-to-a-measure:the-statement-has-one-form',
      '@uc.moderation.object-to-a-measure:the-reporter-is-told-the-decision',
      '@uc.moderation.object-to-a-measure:the-statement-names-the-provision-and-where-it-applies',
      '@uc.moderation.object-to-a-measure:the-reporter-is-told-their-redress',
    ],
  },
  async () => {
    const { page } = moderator
    statementsBefore = (await mailsTo(instance().owner.email, STATEMENT_MAIL)).length
    await openModeration(page, tenantOrigin(), instance().owner)
    await openThisCase(
      page,
      page
        .locator('.moderation-case-row')
        .filter({ hasText: 'A single item, such as a photo' })
        .filter({ hasText: 'Other illegal content' })
        .filter({ has: page.locator('.veyq-badge', { hasText: /^Open$/ }) }),
    )
    await expect(page.locator('.moderation-report')).toContainText(guestReason)
    // Every statement asks for the facts (one form on every instance): without them the block is not sent.
    await page.locator('.moderation-block-content').click()
    await expect(page.locator('.moderation-case-error')).toHaveText('Write what you found: a block’s statement must state the facts.')
    await expect(page.locator('#moderation-statement-ground')).toHaveValue('ILLEGAL_CONTENT')
    await page.locator('#moderation-statement-facts').fill(recordTyped(facts))
    // ... and the law or the clause the ground relies on (DSA Art. 17(3)(d), (e)): without it the block is not sent either.
    await page.locator('.moderation-block-content').click()
    await expect(page.locator('.moderation-case-error')).toHaveText('Name the law or the clause the block relies on: a block’s statement must name it.')
    await page.locator('#moderation-statement-provision').fill(provision)
    await page.locator('.moderation-block-content').click()
    await confirmDecision(page, 'Block the content')
    await expect(page.locator('.moderation-measure-statement')).toContainText('Statement of reasons sent', { timeout: 60_000 })
    await expect(page.locator('.moderation-measure-statement')).toContainText('to 1 member')
    await expect(page.locator('.moderation-measure-facts')).toContainText(facts)

    await expect.poll(async () => (await mailsTo(instance().owner.email, STATEMENT_MAIL)).length, { timeout: 60_000 })
      .toBe(statementsBefore + 1)
    const mail = (await mailsTo(instance().owner.email, STATEMENT_MAIL)).at(-1)!
    expect(mail.text).toContain('content was blocked')
    expect(mail.text).toContain('it is illegal under the law that applies')
    expect(mail.text).toContain('No automated means found or decided it')
    expect(mail.text).toContain(`It relies on ${provision}.`)
    expect(mail.text).toContain('It applies wherever this veyq instance is reached, and lasts until it is lifted.')
    expect(mail.text).toContain(`sign in at ${instance().tenantDomain}`)
    expect(mail.text, 'the facts stay out of the mail').not.toContain(stamp)
    const [decision] = await awaitMails(reporterAddress(), DECISION_MAIL)
    expect(decision!.text).toContain('took a measure')
    // The reporter's redress (DSA Art. 16(5), Art. 53), whole on every instance, the coordinator where the DSA applies.
    expect(decision!.text).toContain('If you have more information, you can report it again.')
    expect(decision!.text).toContain('You can also go to court.')
    expect(decision!.text).toContain('Where the Digital Services Act (DSA) applies to this instance, you can also complain to the Digital Services Coordinator of the Member State where you live or are established')
    expect(decision!.text).toContain('Koordinator für digitale Dienste')
    expect(decision!.text).not.toContain(stamp)
  },
)

test(
  'the owner reads the statement under "Blocked by the operator", the facts opened in the browser, and objects once',
  {
    tag: [
      '@uc.moderation.object-to-a-measure:the-affected-read-it-in-veyq-and-are-mailed',
      '@uc.moderation.object-to-a-measure:one-objection-per-measure-reopens-the-case',
    ],
  },
  async () => {
    const { page } = owner
    await page.reload()
    const tile = page.getByRole('button', { name: 'Open item 1' })
    await expect(tile).toContainText(BLOCKED, { timeout: 60_000 })
    await tile.click()
    const notice = page.locator('.media-lightbox-statement')
    await expect(notice).toBeVisible({ timeout: 60_000 })
    await expect(notice.locator('.statement-title')).toHaveText('Why it was blocked')
    await expect(notice).toContainText('content was blocked')
    await expect(notice).toContainText('You are told as the person who uploaded it and an owner of the space it lies in.')
    await expect(notice.locator('.statement-facts')).toHaveText(facts)
    await expect(notice).toContainText(`It relies on ${provision}.`)
    await expect(notice).toContainText('It applies wherever this veyq instance is reached')
    await expect(notice).toContainText('You can also go to court')
    await notice.locator('.statement-objection-text').fill(recordTyped(objection))
    await notice.getByRole('button', { name: 'Send the objection' }).click()
    await expect(notice).toContainText('Your objection was sent.', { timeout: 60_000 })
    await expect(notice.locator('.statement-objection-state')).toContainText('A moderator is reviewing it.')
    await expect(notice.locator('.statement-objection')).toHaveCount(0)
  },
)

test(
  'the moderator who took the measure sees the objection opened but may not decide it while the deputy holds the key',
  { tag: '@uc.moderation.object-to-a-measure:another-moderator-decides-the-objection' },
  async () => {
    const { page } = moderator
    await page.reload()
    await openModeration(page, tenantOrigin(), instance().owner)
    await openThisCase(page, page.locator('.moderation-case-row').filter({ has: page.locator('.moderation-objection-badge') }))
    await expect(page.locator('.moderation-objection-text')).toHaveText(objection, { timeout: 60_000 })
    await expect(page.locator('.moderation-objection-another')).toHaveText('Another moderator must decide this objection: you took the measure.')
    await expect(page.locator('.moderation-objection-uphold')).toHaveCount(0)
    await expect(page.locator('.moderation-objection-lift')).toHaveCount(0)
  },
)

test(
  'the deputy lifts the measure on the objection; the owner is told the answer and gets the photo back',
  { tag: '@uc.moderation.object-to-a-measure:another-moderator-decides-the-objection' },
  async () => {
    const { page } = deputy
    const account = deputyAccount()
    await openModeration(page, `https://${account.tenantDomain}`, account)
    await openThisCase(page, page.locator('.moderation-case-row').filter({ has: page.locator('.moderation-objection-badge') }))
    await expect(page.locator('.moderation-objection-text')).toHaveText(objection, { timeout: 60_000 })
    await page.locator('.moderation-objection-lift').click()
    await confirmDecision(page, 'Lift the measure')
    await expect(page.locator('.moderation-objection')).toContainText('Measure lifted', { timeout: 60_000 })
    await expect(page.locator('.moderation-objection')).not.toContainText('Reviewed by the moderator who took the measure')
    await expect(page.locator('.moderation-event').filter({ hasText: 'Objection decided by another moderator' })).toHaveCount(1)

    const [answer] = await awaitMails(instance().owner.email, ANSWER_MAIL)
    expect(answer!.text).not.toContain(stamp)
    const o = owner.page
    await o.reload()
    await expectDecodedPhoto(o)
    await expect(o.getByRole('button', { name: 'Open item 1' })).not.toContainText(BLOCKED)
  },
)
