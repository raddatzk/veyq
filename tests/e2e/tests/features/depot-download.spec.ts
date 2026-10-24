// A Depot download streams in a real browser (inbox #371, #386, #396 — the pane could not show it). A file of 25 frames
// (the transport's frame is 256 KiB, the pump keeps four in flight) is uploaded by the tenant owner and downloaded
// through each save target `src/web/src/shared/saveStream.ts` picks, in its order:
//
//  1. **A picked file** (File System Access). Headless Chromium opens no native save dialog, so `showSaveFilePicker` is
//     answered by a file of the origin-private file system — the page's own `FileSystemWritableFileStream`, the
//     interface a user-picked file hands it. Shown: the bytes arrive frame by frame (one write per frame, none larger)
//     and match; no Blob of the file's size is built. Not shown: the native dialog, a file on the user's disk, the
//     browser's swap file after an abort.
//  2. **The Service Worker's download route** (no picker). Shown: the page is controlled by `/sw.js`, the download is a
//     navigation to `/__download/<handle>` the browser saves under the file's name, its bytes match, and no Blob of the
//     file's size is built — the file never sits in the tab whole. And a store that stops answering mid-download fails
//     the download (the browser marks it failed, never a truncated file saved as whole) and the page says so.
//  3. **The in-memory Blob** (no picker, Service Workers blocked), through a guest's read-only folder link: the
//     fallback a browser without either gets — the bytes match and it holds the file as one Blob, as designed.
//
// Plus the Memories original (inbox #371 S4): the lightbox's "Download original" saves the photo under its own name
// through the same route. What this cannot show: memory staying flat for a multi-gigabyte file (the tab's own memory is
// not readable here, only the Blobs the page builds), Firefox, Safari and phones, the SW's keepalive past 30 s idle, a
// presign refresh past 10 min, sign-out or a closed tab mid-download (inbox #371 keeps them).
//
// It starts from the tenant whose owner the round trip activated (the `features` project).
import { test, expect, type BrowserContext, type Page } from '@playwright/test'
import { randomBytes } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { recordTyped } from '../../src/canaries'
import {
  createFolder, downloadButton, forgetBlobs, largestBlob, pickFiles, recordBlobs, sha256, shareOpenFolder,
} from '../../src/depot'
import { newDevice, signInOwner, tenantOrigin, type Device } from '../../src/devices'
import { confineToInstance } from '../../src/egress'
import { toast } from '../../src/toast'

test.describe.configure({ mode: 'serial' })

const FRAME = 256 * 1024
// 24 whole frames and a short last one: past the pump's four in flight many times over, and not frame-aligned.
const FILE_BYTES = 24 * FRAME + 4_321
const PHOTO = fileURLToPath(new URL('../../../../src/web/src/features/memories/__fixtures__/exif-sample.jpg', import.meta.url))

const stamp = randomBytes(3).toString('hex')
const folder = `Ablage für Rechnungen ${stamp}`
const fileName = `Steuerunterlagen des Jahres ${stamp}.bin`
const content = new Uint8Array(randomBytes(FILE_BYTES))
const shareLabel = `Für die Steuerberaterin ${stamp}`
const memoryName = `Wanderung über den Rennsteig ${stamp}`
const photoName = `Gipfelkreuz im Morgennebel ${stamp}.jpg`

let member: Device
let shareLink: string

/** The page offers no save picker: the next sink is the Service Worker's route, then the Blob. */
const withoutPicker = (page: Page) =>
  page.evaluate(() => void delete (window as unknown as { showSaveFilePicker?: unknown }).showSaveFilePicker)

/** The member's folder, read fresh: a new document opened from the space. */
async function openFolder(page: Page): Promise<void> {
  await page.goto(tenantOrigin())
  await page.getByRole('button', { name: folder, exact: true }).click({ timeout: 60_000 })
  await expect(page.getByRole('heading', { name: folder })).toBeVisible()
  await expect(downloadButton(page, fileName)).toBeEnabled({ timeout: 60_000 })
}

test.beforeAll(async ({ browser }) => {
  member = await newDevice(browser)
  await recordBlobs(member.context)
})

test.afterEach(() => member?.confinement.assertConfined())

test.afterAll(async () => {
  await member?.context.close()
})

test(
  'the owner uploads a file of many frames into a folder',
  { tag: '@uc.depot.member-files:upload-attaches-one-sealed-blob' },
  async () => {
    const { page } = member
    await signInOwner(member)
    await createFolder(page, folder)
    await pickFiles(page, [{ name: fileName, bytes: content }])
    await expect(downloadButton(page, fileName)).toBeEnabled({ timeout: 60_000 })
  },
)

test(
  'a download into a picked file arrives frame by frame, never as one Blob, byte for byte',
  { tag: '@uc.depot.member-files:download-returns-the-original-bytes' },
  async () => {
    const { page } = member
    await openFolder(page)
    // The picker answered with a file of the origin-private file system; its writable is the browser's own.
    await page.evaluate(() => {
      const log = { suggested: '', writes: [] as number[], closed: false, aborted: false }
      const w = window as unknown as { __veyqPicked: typeof log; showSaveFilePicker: unknown }
      w.__veyqPicked = log
      w.showSaveFilePicker = async (options?: { suggestedName?: string }) => {
        log.suggested = options?.suggestedName ?? ''
        const root = await navigator.storage.getDirectory()
        const handle = await root.getFileHandle('veyq-picked', { create: true })
        return {
          createWritable: async () => {
            const writable = await handle.createWritable()
            return {
              write: (bytes: Uint8Array) => {
                log.writes.push(bytes.byteLength)
                return writable.write(bytes as unknown as ArrayBuffer)
              },
              close: async () => {
                await writable.close()
                log.closed = true
              },
              abort: async (reason?: unknown) => {
                log.aborted = true
                await writable.abort(reason)
              },
            }
          },
        }
      }
    })
    await forgetBlobs(page)
    await downloadButton(page, fileName).click()

    const picked = () => page.evaluate(() => (window as unknown as { __veyqPicked: { closed: boolean } }).__veyqPicked)
    await expect.poll(async () => (await picked()).closed, { message: 'the picked file was closed', timeout: 60_000 }).toBe(true)
    const log = await page.evaluate(() =>
      (window as unknown as { __veyqPicked: { suggested: string; writes: number[]; aborted: boolean } }).__veyqPicked)
    expect(log.suggested, 'the picker suggests the file name').toBe(fileName)
    expect(log.aborted).toBe(false)
    expect(log.writes, 'one write per frame').toHaveLength(Math.ceil(FILE_BYTES / FRAME))
    expect(Math.max(...log.writes), 'no write larger than a frame').toBeLessThanOrEqual(FRAME)
    expect(await largestBlob(page), 'no Blob holds the file').toBeLessThan(FILE_BYTES)

    const saved = await page.evaluate(async () => {
      const root = await navigator.storage.getDirectory()
      const file = await (await root.getFileHandle('veyq-picked')).getFile()
      const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', await file.arrayBuffer()))
      await root.removeEntry('veyq-picked')
      return { size: file.size, sha256: [...digest].map((b) => b.toString(16).padStart(2, '0')).join('') }
    })
    expect(saved).toEqual({ size: FILE_BYTES, sha256: sha256(content) })
  },
)

test(
  'with no picker the download streams through the Service Worker\'s route, never as one Blob, byte for byte',
  { tag: '@uc.depot.member-files:download-returns-the-original-bytes' },
  async () => {
    const { page } = member
    await openFolder(page)
    await withoutPicker(page)
    await forgetBlobs(page)
    const started = page.waitForEvent('download')
    await downloadButton(page, fileName).click()
    const download = await started

    expect(new URL(download.url()).pathname, 'the browser saved the SW route\'s attachment').toMatch(/^\/__download\/.+/)
    expect(download.suggestedFilename()).toBe(fileName)
    expect(await download.failure()).toBeNull()
    const bytes = readFileSync(await download.path())
    expect({ size: bytes.length, sha256: sha256(bytes) }).toEqual({ size: FILE_BYTES, sha256: sha256(content) })
    expect(await page.evaluate(() => navigator.serviceWorker.controller?.scriptURL ?? null), 'the page is controlled by the app\'s worker')
      .toBe(`${tenantOrigin()}/sw.js`)
    expect(await largestBlob(page), 'no Blob holds the file').toBeLessThan(FILE_BYTES)
  },
)

test(
  'a store that stops answering mid-download fails the download instead of saving a truncated file',
  { tag: '@uc.depot.member-files:download-returns-the-original-bytes' },
  async () => {
    const { page } = member
    await openFolder(page)
    await withoutPicker(page)
    // Every presigned GET of the store after the sixth fails, as a network that drops does — retries included.
    let fetched = 0
    await page.route((url) => url.searchParams.has('X-Amz-Signature'), async (route) => {
      fetched += 1
      return fetched > 6 ? route.abort('internetdisconnected') : route.fallback()
    })
    const started = page.waitForEvent('download')
    await downloadButton(page, fileName).click()
    const download = await started
    expect(new URL(download.url()).pathname).toMatch(/^\/__download\/.+/)
    expect(await download.failure(), 'the browser marks the download failed').not.toBeNull()
    await expect(toast(page, 'The file could not be downloaded. Please try again.')).toBeVisible()
    expect(fetched, 'the store was reached, then cut off').toBeGreaterThan(6)
    await page.unrouteAll({ behavior: 'wait' })
  },
)

test(
  'a guest with neither a picker nor a Service Worker downloads through the read-only link into one Blob, byte for byte',
  { tag: '@uc.depot.share-folder-files:a-read-guest-lists-and-downloads-the-subtree' },
  async ({ browser }) => {
    await openFolder(member.page)
    shareLink = await shareOpenFolder(member.page, shareLabel, 'View only')

    const context: BrowserContext = await browser.newContext({ serviceWorkers: 'block' })
    const confinement = await confineToInstance(context)
    await recordBlobs(context)
    const page = await context.newPage()
    try {
      await page.goto(shareLink)
      await expect(page.getByText('Shared with you · read-only')).toBeVisible({ timeout: 60_000 })
      await expect(downloadButton(page, fileName)).toBeEnabled({ timeout: 60_000 })
      await withoutPicker(page)
      await forgetBlobs(page)
      const started = page.waitForEvent('download')
      await downloadButton(page, fileName).click()
      const download = await started
      expect(new URL(download.url()).protocol, 'saved from an in-memory Blob').toBe('blob:')
      expect(download.suggestedFilename()).toBe(fileName)
      const bytes = readFileSync(await download.path())
      expect({ size: bytes.length, sha256: sha256(bytes) }).toEqual({ size: FILE_BYTES, sha256: sha256(content) })
      expect(await largestBlob(page), 'the fallback holds the file as one Blob').toBe(FILE_BYTES)
      confinement.assertConfined()
    } finally {
      await context.close()
    }
  },
)

test(
  'the lightbox saves a Memory\'s original under its own name through the Service Worker\'s route',
  { tag: '@uc.memory.gallery:download-stable-name' },
  async () => {
    const { page } = member
    await page.goto(tenantOrigin())
    await page.getByRole('button', { name: 'New', exact: true }).click({ timeout: 60_000 })
    await page.getByRole('menuitem', { name: 'New Memory' }).click()
    const dialog = page.getByRole('dialog', { name: 'Create a Memory' })
    await dialog.getByLabel('Name').fill(recordTyped(memoryName))
    await dialog.getByRole('button', { name: 'Create', exact: true }).click()
    await expect(dialog).toBeHidden()
    await page.getByRole('button', { name: memoryName, exact: true }).click()
    await expect(page.getByRole('heading', { level: 1, name: memoryName })).toBeVisible()
    const chooser = page.waitForEvent('filechooser')
    await page.getByRole('button', { name: 'Upload', exact: true }).click()
    const photo = readFileSync(PHOTO)
    await (await chooser).setFiles({ name: recordTyped(photoName), mimeType: 'image/jpeg', buffer: photo })
    await expect(page.getByText('1 item', { exact: true })).toBeVisible({ timeout: 60_000 })

    // A new document: the name comes from what the server serves, never from the upload's memory.
    await page.reload()
    await page.getByRole('button', { name: 'Open item 1' }).click({ timeout: 60_000 })
    await withoutPicker(page)
    const save = page.getByRole('button', { name: 'Download original' })
    await expect(save).toBeEnabled({ timeout: 60_000 })
    const started = page.waitForEvent('download')
    await save.click()
    const download = await started
    expect(new URL(download.url()).pathname).toMatch(/^\/__download\/.+/)
    expect(download.suggestedFilename()).toBe(photoName)
    expect(sha256(readFileSync(await download.path())), 'the original, byte for byte').toBe(sha256(photo))
  },
)
