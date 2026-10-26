// A Server Owner's own step over content carries a statement of reasons, in a browser (cr-statement-legal-gaps' browser
// pass; uc.moderation.object-to-a-measure A7, ADR 0093 § 7, DSA Art. 17(1)(d)). A member is born the only way accounts
// are born — a tenant invite — keeps a Memory with a photo there and shares it; then
//
//   1. a guest reports the photo;
//   2. the Server Owner, a moderator, opens the case on `/moderation` and proposes blocking the uploader's sign-in;
//   3. the Server Owner takes the proposal in admin-web with its statement — the ground, the clause it relies on and what
//      they found — and the member's address is mailed the statement WITH those words (a blocked member cannot sign in to
//      read it; owner decision Q24), the objection going to the instance contact;
//   4. the block's answer says the mail reached the member (ADR 0093 § 7, `statementDelivery` REACHED), so the console
//      shows no standing report and the fields warned of nothing (the instance mail is set up);
//   5. the block is lifted again, so nothing of this run outlives it.
//
// It runs after moderation.spec.ts and instance-mail.spec.ts (the instance mail is set up there). What the Server Owner
// found is deliberately NOT a canary: it travels in the clear to the server and in the mail by design (a `pii` field),
// and the backend's ServerOwnerStatementSliceTest proves the database holds it only sealed. The labels are src/web/'s and
// admin-web's own (each package's i18n, en).
import { test, expect, type Page } from '@playwright/test'
import { randomBytes } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { freshOwner, readKit } from '../../../src/accounts'
import { recordTyped } from '../../../src/canaries'
import { instance, newDevice, passwordField, tenantOrigin, type Device } from '../../../src/devices'
import { awaitMails } from '../../../src/mail'
import { stack } from '../../../src/stack'
import { headerAction } from '../../../src/header'

test.describe.configure({ mode: 'serial' })

const PHOTO = fileURLToPath(new URL('../../../../../src/web/src/features/memories/__fixtures__/exif-sample.jpg', import.meta.url))
const STATEMENT_MAIL = /^veyq: a moderation decision concerns you/

const stamp = randomBytes(3).toString('hex')
const memberDomain = () => `statement.${stack().domain}`
const memoryName = `Erinnerung des Mitglieds ${stamp}`
const photoName = `Bild des Mitglieds ${stamp}.jpg`
const guestReason = `Dieses Bild verstößt gegen die Regeln ${stamp}`
const facts = `Das Mitglied hat dasselbe rechtswidrige Bild nach zwei Hinweisen erneut geteilt ${stamp}`
const provision = 'Space terms, clause 4'

let admin: Device
let member: Device
let guest: Device
let moderator: Device
let memberAccount: { email: string; password: string; secretKey: string }
let shareLink: string

async function openConsoleModeration(page: Page): Promise<void> {
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
  await page.getByRole('button', { name: 'Moderation', exact: true }).click()
  await expect(page.getByRole('heading', { level: 2, name: 'Moderation' })).toBeVisible()
}

async function expectDecodedPhoto(page: Page): Promise<void> {
  const thumbnail = page.getByRole('button', { name: 'Open item 1' }).locator('img.media-tile-img')
  await expect(thumbnail).toBeVisible({ timeout: 60_000 })
  await expect(thumbnail).toHaveAttribute('src', /^blob:/)
}

test.beforeAll(async ({ browser }) => {
  admin = await newDevice(browser)
  member = await newDevice(browser)
  guest = await newDevice(browser)
  moderator = await newDevice(browser)
})

test.afterEach(() => {
  for (const d of [admin, member, guest, moderator]) d?.confinement.assertConfined()
})

test.afterAll(async () => {
  for (const d of [admin, member, guest, moderator]) await d?.context.close()
})

test(
  'a member is born through a tenant invite, shares a photo, and a guest reports it',
  { tag: '@uc.moderation.report:a-guest-reports-only-inside-the-link' },
  async () => {
  const fresh = freshOwner(`e2e.${stack().domain}`)
  const { page } = admin
  await openConsoleModeration(page)
  await page.getByRole('button', { name: 'Create tenant' }).click()
  const dialog = page.getByRole('dialog', { name: 'Create tenant' })
  await dialog.getByLabel('Tenant name').fill('E2E Statement')
  await dialog.getByLabel('Domain').fill(memberDomain())
  await dialog.getByLabel('Who should own this tenant? (email)').fill(fresh.email)
  await dialog.getByRole('button', { name: 'Create' }).click()
  await expect(dialog.getByText('Tenant created.')).toBeVisible()
  await admin.context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: stack().adminOrigin })
  await dialog.getByRole('button', { name: 'Copy link' }).click()
  const inviteLink = await page.evaluate(() => navigator.clipboard.readText())
  await dialog.getByRole('button', { name: 'Done' }).click()

  const m = member.page
  await expect(async () => {
    await m.goto(inviteLink)
  }).toPass({ timeout: 60_000, intervals: [1_000] })
  await expect(m.getByText(`You have been invited to own “E2E Statement” (${memberDomain()}).`)).toBeVisible({ timeout: 60_000 })
  await m.getByLabel('Username').fill(`member-${stamp}`)
  await m.locator('#join-password').fill(recordTyped(fresh.password))
  await m.getByRole('button', { name: 'Continue' }).click()
  const download = m.waitForEvent('download')
  await m.getByRole('button', { name: 'Download Emergency Kit (PDF)' }).click()
  const kit = await readKit(await download)
  await m.getByRole('checkbox', { name: 'I have saved my Emergency Kit somewhere safe.' }).check()
  await m.getByRole('button', { name: 'Accept & activate' }).click()
  await expect(m.getByText('“E2E Statement” is now active and yours.')).toBeVisible({ timeout: 60_000 })
  memberAccount = { email: fresh.email, password: fresh.password, secretKey: kit.secretKey }

  // The member's own space: sign in if the activation left none, then a Memory with a photo, shared by a link.
  await expect(async () => {
    await m.goto(`https://${memberDomain()}`)
  }).toPass({ timeout: 60_000, intervals: [1_000] })
  const newButton = m.getByRole('button', { name: 'New', exact: true })
  const signInEmail = m.getByLabel('Email', { exact: true })
  await expect(newButton.or(signInEmail)).toBeVisible({ timeout: 60_000 })
  if (await signInEmail.isVisible()) {
    await signInEmail.fill(memberAccount.email)
    await passwordField(m).fill(recordTyped(memberAccount.password))
    await m.locator('#unlock-secret-key').fill(recordTyped(memberAccount.secretKey))
    await m.getByRole('button', { name: 'Sign in', exact: true }).click()
  }
  await expect(newButton).toBeVisible({ timeout: 60_000 })
  await newButton.click()
  await m.getByRole('menuitem', { name: 'New Memory' }).click()
  const create = m.getByRole('dialog', { name: 'Create a Memory' })
  await create.getByLabel('Name').fill(recordTyped(memoryName))
  await create.getByRole('button', { name: 'Create', exact: true }).click()
  await expect(create).toBeHidden()
  await m.getByRole('button', { name: memoryName, exact: true }).click()
  await expect(m.getByRole('heading', { level: 1, name: memoryName })).toBeVisible()
  const chooser = m.waitForEvent('filechooser')
  await m.getByRole('button', { name: 'Upload', exact: true }).click()
  await (await chooser).setFiles({ name: recordTyped(photoName), mimeType: 'image/jpeg', buffer: readFileSync(PHOTO) })
  await expectDecodedPhoto(m)
  await headerAction(m, 'Share')
  const manage = m.getByRole('dialog', { name: 'Share this Memory' })
  await manage.getByRole('button', { name: 'New share' }).click()
  const createShare = m.getByRole('dialog', { name: 'New share' })
  await createShare.getByRole('button', { name: 'Create link' }).click()
  shareLink = await createShare.getByRole('textbox', { name: 'Public link' }).inputValue()
  await createShare.getByRole('button', { name: 'Done' }).click()

  const g = guest.page
  await g.goto(shareLink)
  await expectDecodedPhoto(g)
  await g.getByRole('button', { name: 'Open item 1' }).click()
  await g.locator('.lightbox-report-button').click()
  const report = g.getByRole('dialog', { name: 'Report' })
  await expect(report).toBeVisible()
  await report.locator('.veyq-select').click()
  await g.getByRole('option', { name: 'A breach of the terms' }).click()
  await report.locator('#veyq-report-reason').fill(recordTyped(guestReason))
  await report.getByText('I confirm that what I say here is accurate and complete to the best of my knowledge.').click()
  await report.getByRole('button', { name: 'Send report' }).click()
  await expect(report.getByText('Your report was received.')).toBeVisible({ timeout: 60_000 })
  },
)

test(
  'the moderator proposes blocking the uploader\'s sign-in',
  { tag: '@uc.moderation.take-a-measure:server-owner-steps-are-proposals-on-the-admin-channel' },
  async () => {
  const { page } = moderator
  const account = instance().owner
  await expect(async () => {
    await page.goto(`${tenantOrigin()}/moderation`)
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
  const row = page.locator('.moderation-case-row')
    .filter({ hasText: 'A breach of the terms' })
    .filter({ hasText: memberDomain() })
    .filter({ has: page.locator('.veyq-badge', { hasText: /^Open$/ }) })
  await expect(row).toHaveCount(1, { timeout: 60_000 })
  await row.getByRole('button', { name: 'Open', exact: true }).click()
  await expect(page.locator('.moderation-report')).toContainText(guestReason, { timeout: 60_000 })
  await page.locator('.moderation-propose-sign-in').click()
  const dialog = page.getByRole('dialog', { name: /^Propose: block sign-in of member/ })
  await expect(dialog).toBeVisible()
  await dialog.getByRole('button', { name: /^Propose: block sign-in of member/ }).click()
  await expect(dialog).toBeHidden()
  },
)

test(
  'the Server Owner takes the proposal with its statement; the member is mailed it with the facts, and the block is lifted again',
  { tag: '@uc.moderation.object-to-a-measure:a-server-owners-step-over-content-carries-a-statement-by-mail' },
  async () => {
    const { page } = admin
    await openConsoleModeration(page)
    const proposal = page.locator('.mod-proposal').filter({ hasText: memberAccount.email })
    await expect(proposal).toHaveCount(1, { timeout: 60_000 })
    await proposal.locator('.mod-proposal__block').click()
    // A proposal is over content: its statement — the ground starts at the case's category.
    await expect(proposal.locator('.step-statement__ground')).toHaveValue('TERMS_BREACH')
    await proposal.locator('.step-statement__provision').fill(provision)
    await proposal.locator('.step-statement__facts').fill(facts)
    // The instance mail is set up (instance-mail.spec.ts), so the fields warn of nothing.
    await expect(proposal.locator('.step-statement__no-mail')).toHaveCount(0)
    await proposal.locator('.mod-proposal__send').click()
    const confirm = page.getByRole('dialog', { name: `Block the sign-in of ${memberAccount.email}?` })
    await expect(confirm).toBeVisible()
    const answer = page.waitForResponse(
      (r) => r.request().method() === 'POST' && new URL(r.url()).pathname === '/api/instance/moderation/sign-in-blocks',
    )
    await confirm.getByRole('button', { name: 'Block sign-in', exact: true }).click()
    const block = page.locator(`.mod-block[data-block="${memberAccount.email}"]`)
    await expect(block).toBeVisible({ timeout: 60_000 })
    // The block's answer reports the one mail as gone out, and the console keeps no report standing beside the steps.
    const blocked = await answer
    expect(blocked.status()).toBe(200)
    expect((await blocked.json()).statementDelivery).toEqual({ outcome: 'REACHED', notReached: [] })
    await expect(page.locator('.statement-delivery')).toHaveCount(0)

    const [mail] = await awaitMails(memberAccount.email, STATEMENT_MAIL)
    expect(mail!.text).toContain('your account was blocked from signing in')
    expect(mail!.text).toContain('You are told as the holder of the account.')
    expect(mail!.text, 'the Server Owner\'s own words reach the member, who cannot sign in to read them').toContain(`What they found: ${facts}`)
    expect(mail!.text).toContain(`It relies on ${provision}.`)
    expect(mail!.text).toContain('It applies wherever this veyq instance is reached')
    expect(mail!.text).not.toContain('you can object once, in veyq')
    expect(mail!.text).toContain('Was sie festgestellt haben:')

    // Nothing of this run outlives it: the block is lifted.
    await block.locator('.mod-block__lift').click()
    await expect(block).toHaveCount(0, { timeout: 60_000 })
  },
)
