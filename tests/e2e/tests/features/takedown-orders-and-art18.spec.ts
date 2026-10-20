// An authority's order and the report to an authority, in a browser (cr-authority-orders-and-art18-reports phase 4's
// browser pass; uc.moderation.authority-order, uc.moderation.report-to-an-authority; ADR 0093 §§ 12-13, ADR 0099 § 6).
// The tenant owner keeps two Memories, each shared by a link — the first one a contribute link, through which a guest
// names itself and adds a photo; then
//
//   1. the deputy — a moderator — enters a removal order on the moderation surface with the first link as the authority
//      sent it (key in its fragment): the guest's next read of the link is `404`, timed from the submit, and the Annex II
//      text comes out to copy with the reference as typed and the block time in UTC; the guest's own browser is told on
//      the link that what it uploaded was removed on an authority's order (TCO Art. 11), which nobody else is;
//   2. the Server Owner enters one in admin-web, without a key, on the second link: the same, timed the same way;
//   3. the deputy opens the case the first order opened and prepares the fields for an Art. 18 report: a text block with
//      the case id and the time zone stated — never a file — and records the report's handover in the log.
//
// It runs after moderation.spec.ts (the deputy, `deputy.json`) — files run in name order. The references, the pasted
// links (their fragment is the share's key, which must never reach the server) and every name are canaries
// (`recordTyped`): lane L1's sweep proves they reached the server sealed or not at all. The labels are src/web/'s and
// admin-web's own (each package's i18n, en).
import { test, expect, type Page } from '@playwright/test'
import { randomBytes } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { recordTyped } from '../../src/canaries'
import { deputy as deputyAccount, instance, newDevice, passwordField, signInOwner, tenantOrigin, type Device } from '../../src/devices'
import { stack } from '../../src/stack'

test.describe.configure({ mode: 'serial' })

const stamp = randomBytes(3).toString('hex')
const memories = [`Angeordnete Erinnerung ${stamp}`, `Zweite angeordnete Erinnerung ${stamp}`]
const moderatorReference = `BKA TCO-${stamp}-1`
const ownerReference = `BKA TCO-${stamp}-2`
const handoverReference = `BKA Vorgang ${stamp}`
const guestName = `Gast der Anordnung ${stamp}`
const PHOTO = fileURLToPath(new URL('../../../../src/web/src/features/memories/__fixtures__/exif-sample.jpg', import.meta.url))
const ORDER_NOTICE = /Something you uploaded through this link was removed on an order of an authority, received on /

let owner: Device
let guest: Device
let deputy: Device
let admin: Device
const links: string[] = []
let orderCase = ''

const shareIdOf = (link: string) => /\/s\/([A-Za-z0-9_-]{43})/.exec(link)![1]

/**
 * What the guest's browser reads for [link]'s share now — a same-origin fetch from the guest's page, so it goes the way
 * the browser goes (its name resolution, Traefik), as the share page's own read does.
 */
async function guestStatus(page: Page, link: string): Promise<number> {
  return page.evaluate(async (path) => (await fetch(path, { cache: 'no-store' })).status, `/api/shares/${shareIdOf(link)}`)
}

/** Poll the guest's read of [link] until it answers [status]; the milliseconds it took from [since]. */
async function guestAnswers(page: Page, link: string, status: number, since: number): Promise<number> {
  await expect.poll(() => guestStatus(page, link), { timeout: 60_000, intervals: [100, 250, 500] }).toBe(status)
  return Date.now() - since
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
  await expect(page.locator('.moderation-queue-list')).toBeVisible({ timeout: 60_000 })
}

test.beforeAll(async ({ browser }) => {
  owner = await newDevice(browser)
  guest = await newDevice(browser)
  deputy = await newDevice(browser)
  admin = await newDevice(browser)
})

test.afterEach(() => {
  for (const device of [owner, guest, deputy, admin]) device?.confinement.assertConfined()
})

test.afterAll(async () => {
  for (const device of [owner, guest, deputy, admin]) await device?.context.close()
})

test(
  'the owner shares two Memories, and a guest opens both links',
  { tag: '@uc.moderation.authority-order:a-removal-order-blocks-by-id-at-once' },
  async () => {
    const { page } = owner
    await signInOwner(owner)
    for (const name of memories) {
      await page.getByRole('button', { name: 'New', exact: true }).click()
      await page.getByRole('menuitem', { name: 'New Memory' }).click()
      const create = page.getByRole('dialog', { name: 'Create a Memory' })
      await create.getByLabel('Name').fill(recordTyped(name))
      await create.getByRole('button', { name: 'Create', exact: true }).click()
      await expect(create).toBeHidden()
      await page.getByRole('button', { name, exact: true }).click()
      await expect(page.getByRole('heading', { level: 1, name })).toBeVisible()
      await page.getByRole('button', { name: 'Share', exact: true }).click()
      const manage = page.getByRole('dialog', { name: 'Share this Memory' })
      await manage.getByRole('button', { name: 'New share' }).click()
      const createShare = page.getByRole('dialog', { name: 'New share' })
      // The first link takes a guest's photo: the guest a removal order then tells (TCO Art. 11).
      if (links.length === 0) {
        await createShare.getByRole('button', { name: 'View + add photos' }).click()
        await expect(createShare.getByRole('button', { name: 'View + add photos' })).toHaveAttribute('aria-pressed', 'true')
      }
      await createShare.getByRole('button', { name: 'Create link' }).click()
      links.push(await createShare.getByRole('textbox', { name: 'Public link' }).inputValue())
      await createShare.getByRole('button', { name: 'Done' }).click()
      await page.goto(tenantOrigin())
      await expect(page.getByRole('button', { name: 'New', exact: true })).toBeVisible({ timeout: 60_000 })
    }
    for (const [i, link] of links.entries()) {
      await guest.page.goto(link)
      await expect(guest.page.getByRole('heading', { level: 1, name: memories[i] })).toBeVisible({ timeout: 60_000 })
      expect(await guestStatus(guest.page, link)).toBe(200)
    }
    // Through the first link the guest names itself and adds a photo; its device keeps the identity.
    const g = guest.page
    await g.goto(links[0])
    await expect(g.getByText('Shared with you · you can contribute')).toBeVisible({ timeout: 60_000 })
    await g.getByRole('button', { name: 'Upload', exact: true }).click()
    const naming = g.getByRole('dialog', { name: 'What is your name?' })
    await naming.getByLabel('Your name').fill(recordTyped(guestName))
    await naming.getByRole('button', { name: 'Continue' }).click()
    await expect(naming).toBeHidden()
    const chooser = g.waitForEvent('filechooser')
    await g.getByRole('button', { name: 'Upload', exact: true }).click()
    await (await chooser).setFiles({ name: recordTyped(`Angeordnetes Bild ${stamp}.jpg`), mimeType: 'image/jpeg', buffer: readFileSync(PHOTO) })
    await expect(g.getByRole('button', { name: 'Open item 1' })).toBeVisible({ timeout: 60_000 })
    await g.reload()
    await expect(g.getByText(`You are here as ${guestName}.`)).toBeVisible({ timeout: 60_000 })
    await expect(g.getByText(ORDER_NOTICE)).toHaveCount(0)
  },
)

test(
  'a moderator enters a removal order with the link as sent: the guest gets 404, and the Annex II text comes out',
  {
    tag: [
      '@uc.moderation.authority-order:a-removal-order-blocks-by-id-at-once',
      '@uc.moderation.authority-order:the-forms-come-out-to-copy',
    ],
  },
  async () => {
    const { page } = deputy
    const account = deputyAccount()
    await openModeration(page, `https://${account.tenantDomain}`, account)
    await page.locator('.moderation-orders-toggle').click()
    // The link as the authority sent it, key and all: a canary, so the sweep proves its fragment never reached the server.
    await page.locator('#moderation-order-share').fill(recordTyped(links[0]))
    await page.locator('#moderation-order-reference').fill(recordTyped(moderatorReference))
    await page.locator('.moderation-order-submit').click()
    const confirm = page.getByRole('dialog', { name: 'An authority’s order' })
    await expect(confirm).toBeVisible()
    const submitted = Date.now()
    await confirm.getByRole('button', { name: 'Enter the order', exact: true }).click()
    const took = await guestAnswers(guest.page, links[0], 404, submitted)
    test.info().annotations.push({ type: 'moderator: submit → guest 404 (ms)', description: String(took) })
    await expect(page.locator('.moderation-order-blocked')).toContainText(/Blocked at \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} UTC/, { timeout: 60_000 })
    const annex = page.locator('.moderation-order-text')
    await expect(annex).toHaveValue(/^DRAFT — not yet reviewed by a lawyer/)
    await expect(annex).toHaveValue(new RegExp(`Reference of the removal order: ${moderatorReference}`))
    await expect(annex).toHaveValue(new RegExp(`identifier ${shareIdOf(links[0])}`))
    // The order's case, for the report below.
    await page.locator('.moderation-order-case').click()
    await expect(page.locator('.moderation-case')).toBeVisible({ timeout: 60_000 })
    orderCase = await page.evaluate(() => document.querySelector('.moderation-case')?.getAttribute('data-case-id') ?? '')
    // The other link still serves: the order blocked by id, not more.
    expect(await guestStatus(guest.page, links[1])).toBe(200)
  },
)

test(
  'the guest who uploaded through the blocked link is told on it that an authority ordered the removal; nobody else is',
  { tag: '@uc.moderation.authority-order:a-guest-uploader-is-told-of-a-removal-order' },
  async ({ browser }) => {
    const g = guest.page
    // The guest's page is already on this link (the 404 poll read from it): the same address with its fragment is no
    // navigation, so load it afresh, as a guest coming back does.
    await g.goto(links[0])
    await g.reload()
    await expect(g.getByRole('heading', { level: 1, name: 'This link isn’t available' })).toBeVisible({ timeout: 60_000 })
    const notice = g.locator('[data-testid="order-notice"]')
    await expect(notice).toBeVisible({ timeout: 60_000 })
    await expect(notice).toContainText(ORDER_NOTICE)
    await expect(notice).toContainText(/For the reasons, or a copy of the order, (write to|ask the people who run this veyq instance)/)
    // A browser that holds no identity for the link — any other visitor — sees the unavailable page and nothing more.
    const stranger = await newDevice(browser)
    try {
      await stranger.page.goto(links[0])
      await expect(stranger.page.getByRole('heading', { level: 1, name: 'This link isn’t available' })).toBeVisible({ timeout: 60_000 })
      await expect(stranger.page.locator('[data-testid="order-notice"]')).toHaveCount(0)
      stranger.confinement.assertConfined()
    } finally {
      await stranger.context.close()
    }
  },
)

test(
  'the Server Owner enters one in admin-web, without a key: the guest of the second link gets 404',
  { tag: '@uc.moderation.authority-order:a-removal-order-blocks-by-id-at-once' },
  async () => {
    const { page } = admin
    const { owner: serverOwner } = instance()
    await page.goto(stack().adminOrigin)
    const consoleHeading = page.getByRole('heading', { name: 'Server administration', level: 1 })
    const email = page.getByLabel('Email', { exact: true })
    await expect(consoleHeading.or(email)).toBeVisible({ timeout: 60_000 })
    if (await email.isVisible()) {
      await email.fill(serverOwner.email)
      await passwordField(page).fill(recordTyped(serverOwner.password))
      await page.getByRole('button', { name: 'Sign in' }).click()
    }
    await expect(consoleHeading).toBeVisible({ timeout: 60_000 })
    await page.getByRole('button', { name: 'Moderation', exact: true }).click()
    await expect(page.getByRole('heading', { level: 2, name: 'An authority\'s order' })).toBeVisible()
    await page.locator('.order-share').fill(recordTyped(links[1]))
    await page.locator('.order-reference').fill(recordTyped(ownerReference))
    await page.locator('.order-submit').click()
    const confirm = page.getByRole('dialog', { name: 'Enter this order?' })
    await expect(confirm).toBeVisible()
    const submitted = Date.now()
    await confirm.getByRole('button', { name: 'Enter the order', exact: true }).click()
    const took = await guestAnswers(guest.page, links[1], 404, submitted)
    test.info().annotations.push({ type: 'Server Owner: submit → guest 404 (ms)', description: String(took) })
    await expect(page.locator('.order-blocked')).toContainText(/Blocked at \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} UTC/, { timeout: 60_000 })
    await expect(page.locator('textarea.order-form-text')).toHaveValue(
      new RegExp(`Reference of the removal order: ${ownerReference}`),
    )
  },
)

test(
  'the moderator prepares the Art. 18 fields as a text block, and records the handover in the log',
  {
    tag: [
      '@uc.moderation.report-to-an-authority:the-export-carries-clear-fields-only',
      '@uc.moderation.report-to-an-authority:the-handover-is-logged',
    ],
  },
  async () => {
    const { page } = deputy
    await expect(page.locator('.moderation-case')).toBeVisible()
    await page.locator('.moderation-export-prepare').click()
    const block = page.locator('.moderation-export-text')
    await expect(block).toHaveValue(/All times in: UTC/, { timeout: 60_000 })
    await expect(block).toHaveValue(/Category: Terrorist content/)
    if (orderCase) await expect(block).toHaveValue(new RegExp(`Case: ${orderCase}`))
    // A text block to copy — no download is offered.
    await expect(page.locator('a[download]')).toHaveCount(0)
    await page.locator('#moderation-handover-reference').fill(recordTyped(handoverReference))
    await page.locator('.moderation-handover-reported').click()
    const confirm = page.getByRole('dialog', { name: 'Record that it was reported to an authority' })
    await confirm.getByRole('button', { name: 'Record that it was reported to an authority', exact: true }).click()
    await expect(page.locator('.moderation-event').filter({ hasText: 'Reported to an authority' })).toHaveCount(1, { timeout: 60_000 })
    await expect(page.locator('.moderation-event').filter({ hasText: 'Reported to an authority' })).toContainText(handoverReference)
  },
)
