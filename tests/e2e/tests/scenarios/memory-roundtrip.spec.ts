// The Memories application's round trip (ADR 0090 decision 7, ADR 0091 decision 3's write half and read half in one
// lane): the tenant owner the first-run setup invited accepts on the tenant's own domain, writes a Memory with a photo,
// shares it read-only, reads it back on a second device that knows only the password and the Secret Key, and an
// anonymous visitor opens the READ link. Every value typed — and the text inside the photo handed to the page — goes
// through `recordTyped`, so the orchestrator's sweep afterwards proves none of it is readable in the database, the
// bucket, a container log or a statement the database received (ADR 0053): the operator-blind invariant, end to end,
// on the shipped image.
//
// The labels are web/'s own (web/src/i18n/index.ts, en), so a renamed button is a red run here. One browser context
// per device, each confined to the instance (egress.ts); the tests run in order and hand state on in this file.
import { test, expect, type BrowserContext, type Locator, type Page } from '@playwright/test'
import { randomBytes } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { recordTyped } from '../../src/canaries'
import { confineToInstance, type Confinement } from '../../src/egress'
import { stack } from '../../src/stack'

test.describe.configure({ mode: 'serial' })

interface Instance {
  owner: { email: string; password: string; secretKey: string }
  tenantDomain: string
  inviteLink: string
}

// A JPEG web/'s own tests read EXIF from (1×1, grayscale, decodable): the bytes the page seals carry readable text, so
// a photo uploaded unsealed is a hit in the bucket. The repository is mounted read-only at /repo in the browser
// container (tests/e2e/stack/docker-compose.e2e.yml).
const PHOTO = fileURLToPath(new URL('../../../../web/src/features/memories/__fixtures__/exif-sample.jpg', import.meta.url))
// Text inside the fixture's EXIF and XMP (`strings` on the file): sealed with the photo (its bytes, wrappedExif).
const PHOTO_TEXT = ['Marienplatz at golden hour', '(c) 2021 A. Photographer']

// PrimeVue's Password field adds its placeholder to the accessible name, so the label is matched as the name's start.
const passwordField = (on: Page) => on.getByRole('textbox', { name: /^Password\b/ })

const stamp = randomBytes(3).toString('hex')
const memoryName = `Sommer am Ufer der Ahr ${stamp}`
const photoName = `Ahrufer im Abendlicht ${stamp}.jpg`
const shareLabel = `Für Oma und Opa ${stamp}`

let instance: Instance
let device: { context: BrowserContext; page: Page; confinement: Confinement }
let shareLink: string

async function newDevice(browser: import('@playwright/test').Browser) {
  const context = await browser.newContext()
  const confinement = await confineToInstance(context)
  return { context, page: await context.newPage(), confinement }
}

/** The thumbnail of the Memory's first item, decrypted in this page: an `<img>` on a `blob:` URL that decoded. */
async function expectDecodedThumbnail(page: Page): Promise<void> {
  const thumbnail: Locator = page.getByRole('button', { name: 'Open item 1' }).locator('img.media-tile-img')
  await expect(thumbnail).toBeVisible({ timeout: 60_000 })
  await expect(thumbnail).toHaveAttribute('src', /^blob:/)
  await expect.poll(() => thumbnail.evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth),
    { message: 'the thumbnail decoded' }).toBeGreaterThan(0)
}

test.beforeAll(async ({ browser }) => {
  instance = JSON.parse(readFileSync(`${stack().outDir}/instance.json`, 'utf8')) as Instance
  device = await newDevice(browser)
})

test.afterEach(() => device.confinement.assertConfined())

test.afterAll(async () => {
  await device?.context.close()
})

test(
  'the invited owner accepts the tenant on its own domain with password and Secret Key',
  { tag: '@uc.admin.create-tenant:activate-by-signin' },
  async () => {
    const { page } = device
    const { owner, tenantDomain } = instance
    // first-run.setup.ts opened the link once; the invitation is consumed only by accepting it.
    await expect(async () => {
      await page.goto(instance.inviteLink)
    }).toPass({ timeout: 60_000, intervals: [1_000] })
    await expect(page.getByText(`You have been invited to own “E2E Family” (${tenantDomain}).`)).toBeVisible()

    // The handle is declared plaintext (`pii`, tenants.openapi.yaml) — not a canary. The real name is sealed.
    await page.getByLabel('Username').fill(`owner-${stamp}`)
    await page.getByLabel('First name').fill(recordTyped(`Gisela ${stamp}`))
    await page.getByLabel('Last name').fill(recordTyped(`Lindenbäumer ${stamp}`))
    // A fresh browser holds no vault, so taking ownership asks for both secrets — neither ever leaves the page.
    await expect(page.getByText(`Sign in as ${owner.email} to take ownership.`)).toBeVisible()
    await page.locator('#signin-password').fill(recordTyped(owner.password))
    await page.locator('#signin-secret-key').fill(recordTyped(owner.secretKey))
    await page.getByRole('button', { name: 'Accept & activate' }).click()

    await expect(page.getByText('“E2E Family” is now active and yours.')).toBeVisible({ timeout: 60_000 })
    await page.getByRole('button', { name: 'Sign in', exact: true }).click()
    // The vault is still open in this tab, so the sign-in route goes straight home: the tenant's shared space.
    await expect(page.getByText(/^Nothing here yet\./)).toBeVisible({ timeout: 60_000 })
  },
)

test(
  'the owner writes a Memory and uploads a photo into it',
  { tag: ['@uc.memory.browse:create-folder-and-memory', '@uc.memory.upload:seal-and-upload-operator-blind'] },
  async () => {
    const { page } = device
    await page.getByRole('button', { name: 'New', exact: true }).click()
    await page.getByRole('menuitem', { name: 'New Memory' }).click()
    const dialog = page.getByRole('dialog', { name: 'Create a Memory' })
    await dialog.getByLabel('Name').fill(recordTyped(memoryName))
    await dialog.getByRole('button', { name: 'Create', exact: true }).click()
    await expect(dialog).toBeHidden()

    await page.getByRole('button', { name: memoryName, exact: true }).click()
    await expect(page.getByRole('heading', { level: 1, name: memoryName })).toBeVisible()
    await expect(page.getByText('No photos in this Memory yet.')).toBeVisible()

    for (const text of PHOTO_TEXT) recordTyped(text)
    const chooser = page.waitForEvent('filechooser')
    await page.getByRole('button', { name: 'Upload', exact: true }).click()
    // Derived in this page (thumbnail, preview), sealed under a fresh blob key, PUT through Traefik to Garage.
    await (await chooser).setFiles({ name: recordTyped(photoName), mimeType: 'image/jpeg', buffer: readFileSync(PHOTO) })
    await expect(page.getByText('1 item', { exact: true })).toBeVisible({ timeout: 60_000 })
    await expect(page.getByRole('button', { name: 'Open item 1' })).toBeVisible({ timeout: 60_000 })
  },
)

test(
  'the owner shares the Memory with a read-only link',
  { tag: '@uc.sharing.share-public-viewer:create-public-share' },
  async () => {
    const { page } = device
    await page.getByRole('button', { name: 'Share', exact: true }).click()
    const manage = page.getByRole('dialog', { name: 'Share this Memory' })
    await manage.getByRole('button', { name: 'New share' }).click()
    const create = page.getByRole('dialog', { name: 'New share' })
    // The label is sealed under the link's key; only the curator ever reads it.
    await create.getByLabel('Name').fill(recordTyped(shareLabel))
    await expect(create.getByRole('button', { name: 'View only' })).toHaveAttribute('aria-pressed', 'true')
    await create.getByRole('button', { name: 'Create link' }).click()
    shareLink = await create.getByRole('textbox', { name: 'Public link' }).inputValue()
    const link = new URL(shareLink)
    expect(link.origin, 'the link opens on the tenant domain').toBe(`https://${instance.tenantDomain}`)
    expect(link.hash, 'the link key travels in the fragment, never to the server').toMatch(/^#k=/)
    await create.getByRole('button', { name: 'Done' }).click()
  },
)

test(
  'a second device signs in with password and Secret Key and reads the Memory and its photo',
  { tag: ['@uc.account.unlock:unlock-opens-master-key', '@uc.memory.gallery:thumbnail-grid'] },
  async ({ browser }) => {
    // A fresh context is a new device: no session, no device wrap — it must derive everything from the two secrets.
    const second = await newDevice(browser)
    const { page } = second
    const { owner, tenantDomain } = instance
    await page.goto(`https://${tenantDomain}/`)
    await page.getByLabel('Email', { exact: true }).fill(owner.email)
    await passwordField(page).fill(recordTyped(owner.password))
    await page.locator('#unlock-secret-key').fill(recordTyped(owner.secretKey))
    await page.getByRole('button', { name: 'Sign in', exact: true }).click()

    await page.getByRole('button', { name: memoryName, exact: true }).click({ timeout: 60_000 })
    await expect(page.getByRole('heading', { level: 1, name: memoryName })).toBeVisible()
    // Fetched as ciphertext and decrypted here: this tab never held the uploader's local preview.
    await expectDecodedThumbnail(page)
    second.confinement.assertConfined()
    await second.context.close()
  },
)

test(
  'an anonymous visitor opens the read-only link and sees the Memory as a member does, minus its functions',
  {
    tag: [
      '@uc.sharing.share-public-viewer:anonymous-resolve-decrypt',
      '@uc.sharing.share-public-viewer:guest-sees-the-member-view',
    ],
  },
  async ({ browser }) => {
    const visitor = await newDevice(browser)
    const { page } = visitor
    await page.goto(shareLink)
    await expect(page.getByText('Shared with you · read-only')).toBeVisible({ timeout: 60_000 })
    await expect(page.getByRole('heading', { level: 1, name: memoryName })).toBeVisible()
    await expectDecodedThumbnail(page)
    // inbox #401: the member's page with the rights of the link — nothing to write with.
    await expect(page.getByRole('button', { name: 'Upload', exact: true })).toHaveCount(0)
    visitor.confinement.assertConfined()
    await visitor.context.close()
  },
)
