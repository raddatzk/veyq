// Depot's live updates and drop box in real, separate browser contexts (inbox #396, #386, #372 — the pane reported
// its tab hidden, so its poll paused, and its guests shared the owner's cookie store). Each device here is a browser
// context of its own: its own cookies and storage, as another person's browser is.
//
//  1. An open folder level shows what another device changed without a reload (core's level poll, ADR 0081): the
//     owner's open folder shows a file a guest of the folder's contributor link uploaded, and the guest's open link
//     page shows a file the owner uploaded. "Without a reload" is checked, not assumed: a marker set on the document
//     before the change must still be there when the row appears.
//  2. A drop box: two guests in two contexts send a file each under the same display name. The owner's open folder
//     counts them live on the drop box's row (1 person, then 2 people), the drop box lists one folder per guest, the
//     second numbered `(2)`, and each folder holds that guest's file only — read-only for the owner.
//  3. The drop box's own page is live too (owner decision 2026-10-17; every opened node's page polls the tree, inbox
//     #503): with it open, a third guest's first file brings its folder onto the page and its second file counts there,
//     without a reload.
//
// What it cannot show: a real phone or Safari/Firefox backgrounding a tab (Playwright keeps every page visible).
//
// It starts from the tenant whose owner the round trip activated (the `depot` project). Labels are src/web/'s own.
import { test, expect, type Page } from '@playwright/test'
import { randomBytes } from 'node:crypto'
import { recordTyped } from '../../../src/canaries'
import { createFolder, downloadButton, pickFiles, shareOpenFolder } from '../../../src/depot'
import { newDevice, signInOwner, type Device } from '../../../src/devices'

test.describe.configure({ mode: 'serial' })

const stamp = randomBytes(3).toString('hex')
const folder = `Gemeinsamer Austausch ${stamp}`
const shareLabel = `Für die Wohngemeinschaft ${stamp}`
const guestName = `Mitbewohnerin Henriette ${stamp}`
const guestFile = `Putzplan für den Herbst ${stamp}.bin`
const memberFile = `Nebenkostenabrechnung ${stamp}.bin`
const dropBox = `Briefkasten für Fotos vom Fest ${stamp}`
const dropperName = `Cousine Mathilda ${stamp}`
const dropped = [`Erstes Foto vom Fest ${stamp}.bin`, `Zweites Foto vom Fest ${stamp}.bin`]
const lateName = `Nachbar Konstantin ${stamp}`
const lateFiles = [`Nachgereichtes Foto ${stamp}.bin`, `Noch ein Foto ${stamp}.bin`]
// The level poll ticks every 4–6 s (useLevelLive.ts LIVE_POLL); three ticks and the read are well inside this.
const LIVE = 30_000

let member: Device
let guest: Device
let droppers: Device[] = []
let shareLink: string
let dropLink: string

/** Set on the document now; a reload, or a navigation away and back, loses it. */
const markDocument = (page: Page) => page.evaluate(() => void ((window as unknown as { __veyqSameDocument: boolean }).__veyqSameDocument = true))
const sameDocument = (page: Page) =>
  page.evaluate(() => (window as unknown as { __veyqSameDocument?: boolean }).__veyqSameDocument === true)

const bytes = (n: number) => new Uint8Array(randomBytes(n))

test.beforeAll(async ({ browser }) => {
  member = await newDevice(browser)
  guest = await newDevice(browser)
})

test.afterEach(() => {
  for (const device of [member, guest, ...droppers]) device?.confinement.assertConfined()
})

test.afterAll(async () => {
  for (const device of [member, guest, ...droppers]) await device?.context.close()
})

test(
  'the owner\'s open folder shows a file a guest uploaded through the link, without a reload',
  { tag: '@uc.memory.browse:level-live-refresh' },
  async () => {
    await signInOwner(member)
    await createFolder(member.page, folder)
    shareLink = await shareOpenFolder(member.page, shareLabel, 'View + contribute')
    await expect(member.page.getByText(/^Nothing here yet\./)).toBeVisible()
    await markDocument(member.page)

    const { page } = guest
    await page.goto(shareLink)
    await expect(page.getByText('Shared with you · you can add and change things here')).toBeVisible({ timeout: 60_000 })
    await pickFiles(page, [{ name: guestFile, bytes: bytes(70_000) }])
    const naming = page.getByRole('dialog', { name: 'What is your name?' })
    await naming.getByLabel('Your name').fill(recordTyped(guestName))
    await naming.getByRole('button', { name: 'Continue' }).click()
    await expect(downloadButton(page, guestFile)).toBeEnabled({ timeout: 60_000 })

    await expect(downloadButton(member.page, guestFile)).toBeEnabled({ timeout: LIVE })
    expect(await sameDocument(member.page), 'the owner\'s page was not reloaded').toBe(true)
  },
)

test(
  'the guest\'s open link page shows a file the owner uploaded, without a reload',
  { tag: '@uc.memory.browse:level-live-refresh' },
  async () => {
    await markDocument(guest.page)
    await pickFiles(member.page, [{ name: memberFile, bytes: bytes(80_000) }])
    await expect(downloadButton(member.page, memberFile)).toBeEnabled({ timeout: 60_000 })

    await expect(downloadButton(guest.page, memberFile)).toBeEnabled({ timeout: LIVE })
    expect(await sameDocument(guest.page), 'the guest\'s page was not reloaded').toBe(true)
  },
)

test(
  'two guests of one name in two browsers each get a folder of their own in a drop box, counted live on the owner\'s level',
  {
    tag: [
      '@uc.depot.drop-box:one-folder-per-guest-identity',
      '@uc.depot.drop-box:members-open-every-guest-folder',
      '@uc.depot.drop-box:members-place-nothing-and-only-delete-or-move-out',
      '@uc.memory.browse:level-live-refresh',
    ],
  },
  async ({ browser }) => {
    const { page } = member
    await page.getByRole('button', { name: 'New', exact: true }).click()
    await page.getByRole('menuitem', { name: 'New drop box' }).click()
    const create = page.getByRole('dialog', { name: 'New drop box' })
    await create.getByLabel('Name').fill(recordTyped(dropBox))
    await create.getByRole('button', { name: 'Create', exact: true }).click()
    await expect(create).toBeHidden()

    await page.getByRole('button', { name: `Open ${dropBox}`, exact: true }).click()
    await expect(page.getByRole('heading', { name: dropBox })).toBeVisible()
    await page.getByRole('button', { name: 'Share link', exact: true }).click()
    const links = page.getByRole('dialog', { name: 'Drop box links' })
    await links.getByRole('button', { name: 'Create link', exact: true }).click()
    dropLink = await links.getByRole('textbox', { name: 'Copy link' }).inputValue()
    expect(new URL(dropLink).hash, 'the link key travels in the fragment').toMatch(/^#k=/)
    await links.getByRole('button', { name: 'Close', exact: true }).first().click()
    await expect(links).toBeHidden()

    // Back on the folder level that holds the drop box — the level that polls.
    await page.goBack()
    await expect(page.getByRole('heading', { name: folder })).toBeVisible()
    await expect(page.getByText('Nobody yet', { exact: true })).toBeVisible()
    await markDocument(page)

    droppers = [await newDevice(browser), await newDevice(browser)]
    for (const [i, dropper] of droppers.entries()) {
      const tab = dropper.page
      await tab.goto(dropLink)
      await expect(tab.getByRole('heading', { name: 'Send files' })).toBeVisible({ timeout: 60_000 })
      await tab.getByLabel('Your name').fill(recordTyped(dropperName))
      await tab.getByRole('button', { name: 'Continue', exact: true }).click()
      const chooser = tab.waitForEvent('filechooser')
      await tab.getByRole('button', { name: 'Choose files', exact: true }).click()
      await (await chooser).setFiles({
        name: recordTyped(dropped[i]), mimeType: 'application/octet-stream', buffer: Buffer.from(bytes(60_000 + i)),
      })
      await expect(tab.getByText(/^Sent · /)).toBeVisible({ timeout: 60_000 })
      await expect(page.getByText(i === 0 ? '1 person' : '2 people', { exact: true })).toBeVisible({ timeout: LIVE })
    }
    expect(await sameDocument(page), 'the owner\'s page was not reloaded').toBe(true)

    await page.getByRole('button', { name: `Open ${dropBox}`, exact: true }).click()
    await expect(page.getByRole('heading', { name: dropBox })).toBeVisible()
    for (const [i, label] of [dropperName, `${dropperName} (2)`].entries()) {
      await page.getByRole('button', { name: `Open ${label}`, exact: true }).click()
      await expect(page.getByRole('heading', { name: label, exact: true })).toBeVisible()
      await expect(downloadButton(page, dropped[i])).toBeEnabled({ timeout: 60_000 })
      await expect(downloadButton(page, dropped[1 - i])).toHaveCount(0)
      // Nothing is placed in a guest's folder: no New, no upload.
      await expect(page.getByRole('button', { name: 'New', exact: true })).toHaveCount(0)
      await page.goBack()
      await expect(page.getByRole('heading', { name: dropBox })).toBeVisible()
    }
  },
)

test(
  'the owner\'s open drop box page shows a new guest\'s folder and its next file, without a reload',
  { tag: ['@uc.memory.browse:level-live-refresh', '@uc.depot.drop-box:members-open-every-guest-folder'] },
  async ({ browser }) => {
    // Left on the drop box's own page by the test before: its two guests' folders, nothing of the third yet.
    const { page } = member
    await expect(page.getByRole('heading', { name: dropBox })).toBeVisible()
    await expect(page.getByRole('button', { name: `Open ${dropperName} (2)`, exact: true })).toBeVisible()
    const late = page.getByRole('button', { name: `Open ${lateName}`, exact: true })
    await expect(late).toHaveCount(0)
    await markDocument(page)

    const latecomer = await newDevice(browser)
    droppers.push(latecomer)
    const tab = latecomer.page
    await tab.goto(dropLink)
    await expect(tab.getByRole('heading', { name: 'Send files' })).toBeVisible({ timeout: 60_000 })
    await tab.getByLabel('Your name').fill(recordTyped(lateName))
    await tab.getByRole('button', { name: 'Continue', exact: true }).click()
    for (const [i, name] of lateFiles.entries()) {
      const chooser = tab.waitForEvent('filechooser')
      await tab.getByRole('button', { name: 'Choose files', exact: true }).click()
      await (await chooser).setFiles({ name: recordTyped(name), mimeType: 'application/octet-stream', buffer: Buffer.from(bytes(50_000 + i)) })
      await expect(tab.getByText(/^Sent · /)).toHaveCount(i + 1, { timeout: 60_000 })
      // Its first file brings the guest's folder onto the open page, its second counts there.
      await expect(late).toContainText(i === 0 ? '1 file' : '2 files', { timeout: LIVE })
    }
    expect(await sameDocument(page), 'the owner\'s page was not reloaded').toBe(true)
  },
)
