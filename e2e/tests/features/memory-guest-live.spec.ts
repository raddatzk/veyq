// A share guest's open gallery picks up a change as a member's does (inbox #497, share view = member view): in its own
// browser context, a visitor of a read-only link has the Memory open; the owner, on another device, uploads a second
// photo; the visitor's gallery shows it without a reload. "Without a reload" is checked, not assumed: a marker set on the
// document before the change must still be there when the tile appears. Before the change, the visitor's poll is
// answered `304` — the tag is a digest of what the link serves (ADR 0081 decision 4), and nothing it serves has moved.
//
// What it cannot show: a real phone or Safari/Firefox backgrounding a tab (Playwright keeps every page visible), and
// that a change elsewhere in the space stays a `304` — the slice suite pins that (`ShareMediaLivePollSliceTest`).
//
// It starts from the tenant whose owner the round trip activated (the `features` project). Labels are web/'s own.
import { test, expect, type Page } from '@playwright/test'
import { randomBytes } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { recordTyped } from '../../src/canaries'
import { newDevice, signInOwner, type Device } from '../../src/devices'

test.describe.configure({ mode: 'serial' })

const PHOTO = fileURLToPath(new URL('../../../web/src/features/memories/__fixtures__/exif-sample.jpg', import.meta.url))
const stamp = randomBytes(3).toString('hex')
const memoryName = `Wanderung über den Rennsteig ${stamp}`
const shareLabel = `Für die Wandergruppe ${stamp}`
const photos = [`Aufbruch am Morgen ${stamp}.jpg`, `Rast an der Hütte ${stamp}.jpg`]
// The gallery's poll ticks every 16–24 s (core/items/itemsLive.ts ITEMS_POLL); three ticks and the read fit inside this.
const LIVE = 90_000

let owner: Device
let visitor: Device
let shareLink: string

const markDocument = (page: Page) => page.evaluate(() => void ((window as unknown as { __veyqSameDocument: boolean }).__veyqSameDocument = true))
const sameDocument = (page: Page) =>
  page.evaluate(() => (window as unknown as { __veyqSameDocument?: boolean }).__veyqSameDocument === true)

async function upload(page: Page, name: string): Promise<void> {
  const chooser = page.waitForEvent('filechooser')
  await page.getByRole('button', { name: 'Upload', exact: true }).click()
  await (await chooser).setFiles({ name: recordTyped(name), mimeType: 'image/jpeg', buffer: readFileSync(PHOTO) })
}

test.beforeAll(async ({ browser }) => {
  owner = await newDevice(browser)
  visitor = await newDevice(browser)
})

test.afterEach(() => {
  for (const device of [owner, visitor]) device?.confinement.assertConfined()
})

test.afterAll(async () => {
  for (const device of [owner, visitor]) await device?.context.close()
})

test(
  "a read-only link's open gallery shows the owner's new photo without a reload, after polls that changed nothing",
  { tag: '@uc.memory.live-updates:guest-live-poll' },
  async () => {
    await signInOwner(owner)
    const { page } = owner
    await page.getByRole('button', { name: 'New', exact: true }).click()
    await page.getByRole('menuitem', { name: 'New Memory' }).click()
    const dialog = page.getByRole('dialog', { name: 'Create a Memory' })
    await dialog.getByLabel('Name').fill(recordTyped(memoryName))
    await dialog.getByRole('button', { name: 'Create', exact: true }).click()
    await expect(dialog).toBeHidden()
    await page.getByRole('button', { name: memoryName, exact: true }).click()
    await upload(page, photos[0])
    await expect(page.getByRole('button', { name: 'Open item 1' })).toBeVisible({ timeout: 60_000 })

    await page.getByRole('button', { name: 'Share', exact: true }).click()
    await page.getByRole('dialog', { name: 'Share this Memory' }).getByRole('button', { name: 'New share' }).click()
    const create = page.getByRole('dialog', { name: 'New share' })
    await create.getByLabel('Name').fill(recordTyped(shareLabel))
    await create.getByRole('button', { name: 'Create link' }).click()
    shareLink = await create.getByRole('textbox', { name: 'Public link' }).inputValue()
    await create.getByRole('button', { name: 'Done' }).click()

    // The visitor opens the link and keeps it open; every answer to its media polls is counted.
    const answers: number[] = []
    visitor.page.on('response', (r) => {
      if (/\/api\/shares\/[^/]+\/media\?/.test(r.url()) && r.request().headers()['if-none-match']) answers.push(r.status())
    })
    await visitor.page.goto(shareLink)
    await expect(visitor.page.getByText('Shared with you · read-only')).toBeVisible({ timeout: 60_000 })
    await expect(visitor.page.getByRole('button', { name: 'Open item 1' })).toBeVisible({ timeout: 60_000 })
    await expect(visitor.page.getByRole('button', { name: 'Open item 2' })).toHaveCount(0)
    await markDocument(visitor.page)
    await expect.poll(() => answers.includes(304), { message: 'an idle poll is a 304', timeout: LIVE }).toBe(true)

    await upload(page, photos[1])
    await expect(page.getByRole('button', { name: 'Open item 2' })).toBeVisible({ timeout: 60_000 })

    await expect(visitor.page.getByRole('button', { name: 'Open item 2' })).toBeVisible({ timeout: LIVE })
    expect(await sameDocument(visitor.page), 'the tile arrived in the same document — no reload').toBe(true)
    expect(answers, 'the change came as a 200 of the conditional poll').toContain(200)
  },
)
