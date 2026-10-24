// A node and everything under it downloads as a plaintext ZIP and imports back (ADR 0082, uc.platform.export-import) —
// in a real browser for the first time (inbox #379: built and unit-tested, never seen in a browser). The tenant owner:
//
//  1. **A Memory** with two GPS-tagged photos and an uploaded cover is downloaded through its row's "Download". The ZIP,
//     opened here in Node, holds the Memory's directory with each original byte for byte (the GPS sits in the file's own
//     EXIF), the manifest's copy of each coordinate and the cover as `cover.jpg`. Picked for an upload into another
//     folder, the ZIP is recognised by its content and imported; the imported Memory shows the cover in its header, both
//     photos, each photo's Location row in the lightbox at the coordinate the original showed (the product's promise: an
//     import reads the coordinate again from the file's EXIF, `features/memories/exportImport.ts`), and saves each
//     original byte for byte.
//  2. **A Files (Depot) folder tree** — three folders deep, four files, one of 12 frames — is downloaded and imported
//     the same way; downloading the imported tree again yields the same entries with the same bytes (sha256), and the
//     deepest file saves byte for byte through its own Download. While the ZIP is written and while the import runs,
//     each dialog's progress bar (`role="progressbar"`, `aria-valuenow`) is recorded from inside the page: it shows a
//     value between 0 and 100 and never goes back, beside its status line.
//  3. **The failure messages** a browser can provoke, each read in the import dialog: a manifest that is not JSON, one
//     of a newer version, a payload whose size the list misstates, a payload missing, a path that leaves its node — each
//     refused before anything is written, with no Import button — and a payload whose bytes no longer match its SHA-256
//     (its ZIP CRC recomputed, so only the manifest's checksum can tell), which stops the import part-way, says "N of M"
//     and moves what it created to the trash. And a ZIP that is no veyq download (the "wrong file") is no import at all:
//     it is uploaded as a file, as a download is when its dialog's "Upload as a file" is chosen.
//
// The ZIPs are saved through the Service Worker's download route (the save picker removed, as depot-files.spec.ts does);
// the tampered ones are re-written here in Node as plain stored ZIPs, which is all the reader accepts.
//
// The second photo is a committed fixture, the first one's bytes with another position and capture time:
//   exiftool -o tests/e2e/fixtures/exif-gps-lisbon.jpg -GPSLatitude=38.7139 -GPSLatitudeRef=N -GPSLongitude=9.1394 \
//     -GPSLongitudeRef=W -GPSAltitude=12 -DateTimeOriginal="2022:05:03 10:20:30" \
//     src/web/src/features/memories/__fixtures__/exif-sample.jpg
//
// Left out, and why: a multi-GB export (a run of this lane cannot afford one; the ZIP64 branches are unit-tested in
// zip.test.ts); the OS directory picker and a dropped directory — `showDirectoryPicker` and a real OS drag cannot be
// driven headless, so the unpacked-download input stays with importHardening.test.ts; the quota refusal ("not enough
// storage space"), which needs a tenant quota this instance does not set; and Firefox, Safari and phones (Chromium only).
//
// It starts from the tenant whose owner the round trip activated (the `features` project); every name is new (a random
// stamp), and what it creates stays in its own folders. The labels are src/web/'s own (src/web/src/i18n/index.ts and
// src/web/src/features/memories/i18n.ts, en).
import { test, expect, type Locator, type Page } from '@playwright/test'
import { randomBytes } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { recordTyped } from '../../src/canaries'
import { createFolder, downloadButton, pickFiles, sha256 } from '../../src/depot'
import { newDevice, signInOwner, tenantOrigin, type Device } from '../../src/devices'

test.describe.configure({ mode: 'serial' })

const MUNICH = readFileSync(fileURLToPath(new URL('../../../../src/web/src/features/memories/__fixtures__/exif-sample.jpg', import.meta.url)))
const LISBON = readFileSync(fileURLToPath(new URL('../../fixtures/exif-gps-lisbon.jpg', import.meta.url)))
const FRAME = 256 * 1024

const stamp = randomBytes(3).toString('hex')
const memoryName = `Städtereise mit Großtante Hildegard ${stamp}`
const photos = [
  // 48°8'13" N, 11°34'30" E — the lightbox prints six decimals (MediaLightbox.vue `gpsLabel`)
  { name: `Am Marienplatz im Regen ${stamp}.jpg`, bytes: MUNICH, lat: 48.136944, lon: 11.575, shown: '48.136944, 11.575000' },
  { name: `Straßenbahn am Tejo-Ufer ${stamp}.jpg`, bytes: LISBON, lat: 38.7139, lon: -9.1394, shown: '38.713900, -9.139400' },
]
const memoryTarget = `Zurückgeholte Erinnerungen ${stamp}`

const tree = `Umzugskartons aus dem Keller ${stamp}`
const sub = `Kiste Nummer sieben ${stamp}`
const subsub = `Briefe von Oma Gertrud ${stamp}`
const treeFiles = {
  // 12 whole frames and a short 13th: the progress bar moves while it is written
  big: { name: `Inventarliste komplett ${stamp}.bin`, bytes: new Uint8Array(randomBytes(12 * FRAME + 777)) },
  small: { name: `Notizzettel vom Möbelpacker ${stamp}.bin`, bytes: new Uint8Array(randomBytes(4_321)) },
  inSub: { name: `Quittung Sperrmüll ${stamp}.bin`, bytes: new Uint8Array(randomBytes(70_001)) },
  deepest: { name: `Brief vom Juli neunzehnhundertachtzig ${stamp}.bin`, bytes: new Uint8Array(randomBytes(123_457)) },
}
const treeTarget = `Wiederhergestellte Ablage ${stamp}`
const failuresFolder = `Kaputte Downloads zum Ausprobieren ${stamp}`

let member: Device
let memoryZip: Buffer
let treeZip: Buffer

// --- the ZIP, read and re-written in Node -------------------------------------------------------------------------------
// The archive is store-only (src/web/src/core/export/zip.ts): an entry's bytes sit after its local header unchanged. The
// central directory gives each entry's offset and size; the export's data descriptors are not needed to read it. A rewrite
// writes every entry plain (no descriptor, no ZIP64), which the browser's reader accepts as it accepts its own.

type ZipEntries = Map<string, Buffer>

const CRC_TABLE = Array.from({ length: 256 }, (_, n) => {
  let c = n
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
  return c >>> 0
})
function crc32(bytes: Uint8Array): number {
  let c = 0xffffffff
  for (const b of bytes) c = CRC_TABLE[(c ^ b) & 0xff] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}

function readZip(zip: Buffer): ZipEntries {
  let eocd = -1
  for (let i = zip.length - 22; i >= 0; i--) {
    if (zip.readUInt32LE(i) === 0x06054b50) {
      eocd = i
      break
    }
  }
  if (eocd < 0) throw new Error('no end-of-central-directory record')
  const count = zip.readUInt16LE(eocd + 10)
  let p = zip.readUInt32LE(eocd + 16)
  const out: ZipEntries = new Map()
  for (let n = 0; n < count; n++) {
    if (zip.readUInt32LE(p) !== 0x02014b50) throw new Error('bad central directory entry')
    expect(zip.readUInt16LE(p + 10), 'a stored entry').toBe(0)
    const size = zip.readUInt32LE(p + 24)
    const nameLength = zip.readUInt16LE(p + 28)
    const next = p + 46 + nameLength + zip.readUInt16LE(p + 30) + zip.readUInt16LE(p + 32)
    const name = zip.subarray(p + 46, p + 46 + nameLength).toString('utf8')
    const offset = zip.readUInt32LE(p + 42)
    const dataAt = offset + 30 + zip.readUInt16LE(offset + 26) + zip.readUInt16LE(offset + 28)
    out.set(name, zip.subarray(dataAt, dataAt + size))
    p = next
  }
  return out
}

function writeZip(entries: ZipEntries): Buffer {
  const locals: Buffer[] = []
  const centrals: Buffer[] = []
  let offset = 0
  for (const [name, bytes] of entries) {
    const n = Buffer.from(name, 'utf8')
    const crc = crc32(bytes)
    const local = Buffer.alloc(30)
    local.writeUInt32LE(0x04034b50, 0)
    local.writeUInt16LE(20, 4)
    local.writeUInt16LE(0x0800, 6) // UTF-8 names
    local.writeUInt16LE(0x21, 12) // 1980-01-01
    local.writeUInt32LE(crc, 14)
    local.writeUInt32LE(bytes.length, 18)
    local.writeUInt32LE(bytes.length, 22)
    local.writeUInt16LE(n.length, 26)
    const central = Buffer.alloc(46)
    central.writeUInt32LE(0x02014b50, 0)
    central.writeUInt16LE(20, 4)
    central.writeUInt16LE(20, 6)
    central.writeUInt16LE(0x0800, 8)
    central.writeUInt16LE(0x21, 14)
    central.writeUInt32LE(crc, 16)
    central.writeUInt32LE(bytes.length, 20)
    central.writeUInt32LE(bytes.length, 24)
    central.writeUInt16LE(n.length, 28)
    central.writeUInt32LE(offset, 42)
    locals.push(local, n, bytes)
    centrals.push(central, n)
    offset += 30 + n.length + bytes.length
  }
  const directory = Buffer.concat(centrals)
  const end = Buffer.alloc(22)
  end.writeUInt32LE(0x06054b50, 0)
  end.writeUInt16LE(entries.size, 8)
  end.writeUInt16LE(entries.size, 10)
  end.writeUInt32LE(directory.length, 12)
  end.writeUInt32LE(offset, 16)
  return Buffer.concat([...locals, directory, end])
}

const MANIFEST = '.veyq/manifest.json'

type Payload = { path: string; size: number; mediaType: string; sha256?: string; modifiedAt?: number }
type Medium = { file: Payload; name?: string; location?: { lat?: number; lon?: number; place?: string } }
type ManifestNode = {
  id: string
  kind: string
  name: string
  path: string
  content?: { type: string; file?: Payload; media?: Medium[]; cover?: { file: Payload; crop: unknown } }
  children?: ManifestNode[]
}
type Manifest = { format: string; version: number; applications: { id: string; contentSchema: number }[]; root: ManifestNode }

const manifestOf = (entries: ZipEntries): Manifest => JSON.parse(entries.get(MANIFEST)!.toString('utf8')) as Manifest

/** The ZIP with its manifest changed by `edit` (or replaced by raw bytes). */
function withManifest(zip: Buffer, edit: ((m: Manifest) => void) | Buffer): Buffer {
  const entries = new Map(readZip(zip))
  if (Buffer.isBuffer(edit)) entries.set(MANIFEST, edit)
  else {
    const m = manifestOf(entries)
    edit(m)
    entries.set(MANIFEST, Buffer.from(JSON.stringify(m)))
  }
  return writeZip(entries)
}

/** Every node, parents before children, in the order the import creates them (`importSubtree.ts` `walk`). */
const preorder = (n: ManifestNode): ManifestNode[] => [n, ...(n.children ?? []).flatMap(preorder)]

/** The tree as a comparable shape: kinds, names and paths, each file's size, type, time and checksum. */
const shapeOf = (n: ManifestNode): unknown => ({
  kind: n.kind, name: n.name, path: n.path, file: n.content?.file ?? null, children: (n.children ?? []).map(shapeOf),
})

/** Every entry but the manifest, as name → sha256: the directories and the files' bytes. */
const entryDigests = (entries: ZipEntries) =>
  Object.fromEntries([...entries].filter(([name]) => name !== MANIFEST).map(([name, bytes]) => [name, sha256(bytes)]))

// --- the browser ---------------------------------------------------------------------------------------------------------

/** The page offers no save picker: a download goes through the Service Worker's route (or the in-memory Blob). */
const withoutPicker = (page: Page) =>
  page.evaluate(() => void delete (window as unknown as { showSaveFilePicker?: unknown }).showSaveFilePicker)

/** Back to the space's top level, where the scenario's folders and its Memory are rows. */
async function openSpace(page: Page): Promise<void> {
  await page.goto(tenantOrigin())
  await expect(page.getByRole('button', { name: 'New', exact: true })).toBeVisible({ timeout: 60_000 })
}

/**
 * A row of the open level, opened by its name, and the heading it opens on: a node's own page (a Memory) names it at
 * `h1`, a folder's header inside the level view at `h2` (`NodeHeader`'s `level`).
 */
async function openRow(page: Page, name: string, opens: 'memory' | 'folder'): Promise<void> {
  await page.getByRole('button', { name, exact: true }).click({ timeout: 60_000 })
  await expect(page.getByRole('heading', { level: opens === 'memory' ? 1 : 2, name })).toBeVisible()
}

/** A folder of the space's top level, read fresh: a new document. */
async function openTopFolder(page: Page, name: string): Promise<void> {
  await openSpace(page)
  await openRow(page, name, 'folder')
}

/**
 * Every progress bar and status line of the export and import dialogs, as the page draws them, from now until the next
 * navigation (`window.__veyqProgress`): one entry per change, so a bar that moves leaves its values in order.
 */
async function recordProgress(page: Page): Promise<void> {
  await page.evaluate(() => {
    type Seen = { dialog: string; value: number | null; status: string }
    const log: Seen[] = []
    ;(window as unknown as { __veyqProgress: Seen[] }).__veyqProgress = log
    const look = () => {
      for (const dialog of document.querySelectorAll('[data-testid="export-dialog"], [data-testid="import-dialog"]')) {
        const raw = dialog.querySelector('[role="progressbar"]')?.getAttribute('aria-valuenow')
        const seen: Seen = {
          dialog: dialog.getAttribute('data-testid') ?? '',
          value: raw === null || raw === undefined ? null : Number(raw),
          status: dialog.querySelector('[role="status"]')?.textContent?.trim() ?? '',
        }
        const last = log[log.length - 1]
        if (!last || last.dialog !== seen.dialog || last.value !== seen.value || last.status !== seen.status) log.push(seen)
      }
    }
    new MutationObserver(look).observe(document.body, { subtree: true, childList: true, attributes: true, characterData: true })
  })
}

async function progressOf(page: Page, dialog: 'export-dialog' | 'import-dialog') {
  const log = await page.evaluate(
    () => (window as unknown as { __veyqProgress: { dialog: string; value: number | null; status: string }[] }).__veyqProgress,
  )
  const mine = log.filter((s) => s.dialog === dialog)
  return {
    values: mine.map((s) => s.value).filter((v): v is number => v !== null),
    statuses: [...new Set(mine.map((s) => s.status).filter(Boolean))],
  }
}

/** The bar was drawn, showed a value strictly between 0 and 100, and never went back. */
function expectProgressMoved(values: number[], what: string): void {
  expect(values.length, `${what}: a progress bar was drawn`).toBeGreaterThan(0)
  expect(values.some((v) => v > 0 && v < 100), `${what}: a value between 0 and 100 was shown (${values.join(', ')})`).toBe(true)
  expect(values, `${what}: the bar never goes back`).toEqual([...values].sort((a, b) => a - b))
}

/** "Download" from a row's "More actions" menu of the open level: the export dialog, summarised, saved as a ZIP. */
async function downloadAsZip(page: Page, name: string, summary: RegExp): Promise<Buffer> {
  await page.getByRole('button', { name: `More actions for “${name}”`, exact: true }).click({ timeout: 60_000 })
  await page.getByRole('menuitem', { name: 'Download', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: `Download “${name}”` })
  await expect(dialog.getByTestId('export-summary')).toHaveText(summary, { timeout: 60_000 })
  await withoutPicker(page)
  await recordProgress(page)
  const started = page.waitForEvent('download')
  await dialog.getByRole('button', { name: 'Save as ZIP…', exact: true }).click()
  const download = await started
  expect(download.suggestedFilename(), 'the saved file is named after the node').toBe(`${name}.zip`)
  expect(await download.failure()).toBeNull()
  const zip = readFileSync(await download.path())
  await expect(page.getByText('Download saved', { exact: true })).toBeVisible({ timeout: 60_000 })
  await expect(dialog).toBeHidden()
  return zip
}

/** A ZIP picked through the open folder's New → Upload files, as a member picks any file. */
async function pickZip(page: Page, name: string, zip: Buffer): Promise<void> {
  const chooser = page.waitForEvent('filechooser')
  await page.getByRole('button', { name: 'New', exact: true }).click()
  await page.getByRole('menuitem', { name: 'Upload files' }).click()
  await (await chooser).setFiles({ name: recordTyped(name), mimeType: 'application/zip', buffer: zip })
}

const importDialog = (page: Page) => page.getByRole('dialog', { name: 'Import', exact: true })

/** The lightbox's details, term → description, once the file's name and its location have both been read. */
async function lightboxDetails(lightbox: Locator): Promise<Record<string, string>> {
  const read = () =>
    lightbox.locator('dl.media-lightbox-metadata').evaluate((dl) => {
      const out: Record<string, string> = {}
      let term = ''
      for (const child of Array.from(dl.children)) {
        if (child.tagName === 'DT') term = child.textContent?.trim() ?? ''
        else if (child.tagName === 'DD') out[term] = child.textContent?.trim() ?? ''
      }
      return out
    })
  await expect.poll(async () => {
    const d = await read()
    return !!d.File && !!d.Location
  }, { message: 'the lightbox shows the file and its location', timeout: 60_000 }).toBe(true)
  return read()
}

/** Each photo of the open Memory as its lightbox shows it: file name → location, and the original it saves. */
async function photosAsShown(page: Page): Promise<Map<string, { location: string; sha256: string }>> {
  await withoutPicker(page)
  const lightbox = page.getByRole('dialog', { name: memoryName })
  const out = new Map<string, { location: string; sha256: string }>()
  for (const index of [1, 2]) {
    await page.getByRole('button', { name: `Open item ${index}`, exact: true }).click({ timeout: 60_000 })
    await expect(lightbox).toBeVisible()
    const details = await lightboxDetails(lightbox)
    const save = lightbox.getByRole('button', { name: 'Download original', exact: true })
    await expect(save).toBeEnabled({ timeout: 60_000 })
    const started = page.waitForEvent('download')
    await save.click()
    const download = await started
    expect(await download.failure()).toBeNull()
    out.set(details.File, { location: details.Location, sha256: sha256(readFileSync(await download.path())) })
    await lightbox.getByRole('button', { name: 'Close', exact: true }).click()
    await expect(lightbox).toBeHidden()
  }
  return out
}

/** The Memory's header cover: the button that IS the cover, holding the decrypted image (not the placeholder). */
async function expectCoverShown(page: Page): Promise<void> {
  const cover = page.getByRole('button', { name: 'Change cover', exact: true }).locator('img').first()
  await expect(cover).toHaveAttribute('alt', memoryName, { timeout: 60_000 })
  await expect.poll(() => cover.evaluate((img) => (img as HTMLImageElement).complete && (img as HTMLImageElement).naturalWidth > 0), {
    message: 'the cover image decoded',
    timeout: 60_000,
  }).toBe(true)
}

/** A 640×360 PNG drawn on a canvas in the page — a cover-shaped image with no fixture behind it (memories-styles.spec.ts). */
async function drawnImage(page: Page): Promise<Buffer> {
  const data = await page.evaluate(() => {
    const canvas = document.createElement('canvas')
    canvas.width = 640
    canvas.height = 360
    const g = canvas.getContext('2d')!
    g.fillStyle = '#0f766e'
    g.fillRect(0, 0, 640, 360)
    g.fillStyle = '#facc15'
    g.fillRect(80, 60, 160, 160)
    return canvas.toDataURL('image/png').split(',')[1]
  })
  return Buffer.from(data, 'base64')
}

test.beforeAll(async ({ browser }) => {
  member = await newDevice(browser)
})

test.afterEach(() => member?.confinement.assertConfined())

test.afterAll(async () => {
  await member?.context.close()
})

test(
  'a Memory with an uploaded cover and two GPS-tagged photos downloads as a ZIP of its originals, their coordinates and the cover',
  { tag: ['@uc.platform.export-import:memories-content', '@uc.platform.export-import:archive-reads-as-the-tree'] },
  async () => {
    const { page } = member
    await signInOwner(member)
    await page.getByRole('button', { name: 'New', exact: true }).click()
    await page.getByRole('menuitem', { name: 'New Memory' }).click()
    const create = page.getByRole('dialog', { name: 'Create a Memory' })
    await create.getByLabel('Name').fill(recordTyped(memoryName))
    await create.getByRole('button', { name: 'Create', exact: true }).click()
    await expect(create).toBeHidden()
    await openRow(page, memoryName, 'memory')

    const chooser = page.waitForEvent('filechooser')
    await page.getByRole('button', { name: 'Upload', exact: true }).click()
    await (await chooser).setFiles(photos.map((p) => ({ name: recordTyped(p.name), mimeType: 'image/jpeg', buffer: p.bytes })))
    await expect(page.getByText('2 items', { exact: true })).toBeVisible({ timeout: 60_000 })

    await page.getByRole('button', { name: 'Set cover', exact: true }).first().click()
    const coverDialog = page.getByRole('dialog', { name: 'Cover' })
    const imageChooser = page.waitForEvent('filechooser')
    await coverDialog.getByRole('button', { name: 'Choose an image' }).click()
    await (await imageChooser).setFiles({ name: recordTyped(`Titelbild der Städtereise ${stamp}.png`), mimeType: 'image/png', buffer: await drawnImage(page) })
    await expect(coverDialog.locator('.veyq-cropper img')).toBeVisible({ timeout: 30_000 })
    await coverDialog.getByRole('button', { name: 'Save cover', exact: true }).click()
    await expect(coverDialog).toBeHidden()
    await expectCoverShown(page)

    // A new document: the photos as the server serves them — the coordinate read from each upload's EXIF.
    await page.reload()
    await expect(page.getByRole('heading', { level: 1, name: memoryName })).toBeVisible({ timeout: 60_000 })
    await expectCoverShown(page)
    const before = await photosAsShown(page)
    for (const p of photos) expect(before.get(p.name), `${p.name} before the download`).toEqual({ location: p.shown, sha256: sha256(p.bytes) })

    await openSpace(page)
    memoryZip = await downloadAsZip(page, memoryName, /^1 items, 3 files, /)

    const entries = readZip(memoryZip)
    const manifest = manifestOf(entries)
    expect(manifest).toMatchObject({ format: 'veyq-subtree', version: 2 })
    expect(manifest.applications).toContainEqual({ id: 'memories', contentSchema: 1 })
    const root = manifest.root
    expect(root).toMatchObject({ kind: 'MEMORY', name: memoryName, path: memoryName })
    expect(root.content?.type).toBe('memories.memory')
    expect(entries.has(`${memoryName}/`), 'the Memory is a directory of the archive').toBe(true)
    const media = root.content?.media ?? []
    expect(media).toHaveLength(2)
    for (const p of photos) {
      const medium = media.find((m) => m.name === p.name)
      expect(medium, `${p.name} is in the manifest`).toBeDefined()
      expect(medium!.file.path).toBe(`${memoryName}/${p.name}`)
      // the original as it was uploaded — its EXIF, the GPS included, inside it
      expect(sha256(entries.get(medium!.file.path)!), `${p.name}: the original, byte for byte`).toBe(sha256(p.bytes))
      expect(medium!.file.sha256).toBe(sha256(p.bytes))
      expect(medium!.location?.lat, `${p.name}: the manifest's copy of the latitude`).toBeCloseTo(p.lat, 5)
      expect(medium!.location?.lon, `${p.name}: the manifest's copy of the longitude`).toBeCloseTo(p.lon, 5)
    }
    const cover = root.content?.cover
    expect(cover?.file.path).toBe(`${memoryName}/cover.jpg`)
    expect(cover?.crop, 'the cover travels with its crop').toBeTruthy()
    const coverBytes = entries.get(`${memoryName}/cover.jpg`)!
    expect([...coverBytes.subarray(0, 3)], 'the cover is a JPEG').toEqual([0xff, 0xd8, 0xff])
    expect(coverBytes.length).toBe(cover!.file.size)
  },
)

test(
  'the Memory imported into a folder comes back with its cover, its photos byte for byte and their coordinates',
  {
    tag: [
      '@uc.platform.export-import:memories-content',
      '@uc.platform.export-import:recognised-on-upload',
      '@uc.platform.export-import:round-trip-by-hooks',
    ],
  },
  async () => {
    const { page } = member
    await openSpace(page)
    await createFolder(page, memoryTarget)
    await pickZip(page, `${memoryName}.zip`, memoryZip)

    const dialog = importDialog(page)
    await expect(dialog.getByTestId('import-summary')).toHaveText(importSummary(memoryName, 1, memoryTarget), { timeout: 60_000 })
    await expect(dialog.getByRole('button', { name: 'Upload as a file', exact: true })).toBeVisible()
    await expect(dialog.getByText('You become the owner of everything imported.', { exact: false })).toBeVisible()
    await dialog.getByRole('button', { name: 'Import', exact: true }).click()
    await expect(page.getByText(`Imported “${memoryName}”`, { exact: true })).toBeVisible({ timeout: 90_000 })
    await expect(dialog).toBeHidden()

    // A new document: the imported Memory as the server serves it.
    await openTopFolder(page, memoryTarget)
    await openRow(page, memoryName, 'memory')
    await expect(page.getByText('2 items', { exact: true })).toBeVisible({ timeout: 60_000 })
    await expectCoverShown(page)
    const after = await photosAsShown(page)
    for (const p of photos) {
      expect(after.get(p.name), `${p.name} after the import: its location and its original`).toEqual({ location: p.shown, sha256: sha256(p.bytes) })
    }
  },
)

test(
  'a Files folder tree downloads as a ZIP of its directories and files, the progress bar moving while it is written',
  { tag: ['@uc.platform.export-import:archive-reads-as-the-tree', '@uc.platform.export-import:dialogs'] },
  async () => {
    const { page } = member
    await openSpace(page)
    // Each level's uploads attached (a file's own Download is enabled once its blob is listed) before the next folder.
    for (const [folder, files] of [
      [tree, [treeFiles.big, treeFiles.small]], [sub, [treeFiles.inSub]], [subsub, [treeFiles.deepest]],
    ] as const) {
      await createFolder(page, folder)
      await pickFiles(page, [...files])
      for (const f of files) await expect(downloadButton(page, f.name)).toBeEnabled({ timeout: 60_000 })
    }

    await openSpace(page)
    // 3 folders and 4 files; the size in the reader's locale
    treeZip = await downloadAsZip(page, tree, /^7 items, 4 files, /)
    const { values, statuses } = await progressOf(page, 'export-dialog')
    expectProgressMoved(values, 'the download')
    expect(statuses).toContain('Writing the file…')

    const entries = readZip(treeZip)
    const expected: Record<string, string> = {
      [`${tree}/`]: sha256(new Uint8Array(0)),
      [`${tree}/${sub}/`]: sha256(new Uint8Array(0)),
      [`${tree}/${sub}/${subsub}/`]: sha256(new Uint8Array(0)),
      [`${tree}/${treeFiles.big.name}`]: sha256(treeFiles.big.bytes),
      [`${tree}/${treeFiles.small.name}`]: sha256(treeFiles.small.bytes),
      [`${tree}/${sub}/${treeFiles.inSub.name}`]: sha256(treeFiles.inSub.bytes),
      [`${tree}/${sub}/${subsub}/${treeFiles.deepest.name}`]: sha256(treeFiles.deepest.bytes),
    }
    expect(entryDigests(entries), 'the archive reads as the tree: a directory per folder, each file under its name').toEqual(expected)
    const root = manifestOf(entries).root
    expect(preorder(root).map((n) => [n.kind, n.path])).toEqual(
      expect.arrayContaining([
        ['FOLDER', tree], ['FOLDER', `${tree}/${sub}`], ['FOLDER', `${tree}/${sub}/${subsub}`],
        ['FILE', `${tree}/${treeFiles.big.name}`], ['FILE', `${tree}/${sub}/${subsub}/${treeFiles.deepest.name}`],
      ]),
    )
  },
)

test(
  'the tree imported into another folder comes back entry for entry and byte for byte, its progress bar counting the items',
  { tag: ['@uc.platform.export-import:round-trip-by-hooks', '@uc.platform.export-import:dialogs'] },
  async () => {
    const { page } = member
    await openSpace(page)
    await createFolder(page, treeTarget)
    await pickZip(page, `${tree}.zip`, treeZip)
    const dialog = importDialog(page)
    await expect(dialog.getByTestId('import-summary')).toHaveText(importSummary(tree, 7, treeTarget), { timeout: 60_000 })
    await recordProgress(page)
    await dialog.getByRole('button', { name: 'Import', exact: true }).click()
    await expect(page.getByText(`Imported “${tree}”`, { exact: true })).toBeVisible({ timeout: 90_000 })
    await expect(dialog).toBeHidden()
    const { values, statuses } = await progressOf(page, 'import-dialog')
    expectProgressMoved(values, 'the import')
    expect(statuses.some((s) => /^Importing… [1-6] of 7 items$/.test(s)), `the status counts the items (${statuses.join(' | ')})`).toBe(true)

    // The imported tree, read back by downloading it again: the same entries, the same bytes, the same manifest tree.
    await openTopFolder(page, treeTarget)
    await openRow(page, tree, 'folder')
    await openRow(page, sub, 'folder')
    await openRow(page, subsub, 'folder')
    await expect(downloadButton(page, treeFiles.deepest.name)).toBeEnabled({ timeout: 60_000 })
    await withoutPicker(page)
    const started = page.waitForEvent('download')
    await downloadButton(page, treeFiles.deepest.name).click()
    const deepest = await started
    expect(sha256(readFileSync(await deepest.path())), 'the deepest file, through its own Download').toBe(sha256(treeFiles.deepest.bytes))

    await openTopFolder(page, treeTarget)
    const again = readZip(await downloadAsZip(page, tree, /^7 items, 4 files, /))
    const original = readZip(treeZip)
    expect(entryDigests(again), 'the same directories and files, byte for byte').toEqual(entryDigests(original))
    expect(shapeOf(manifestOf(again).root), 'the same tree: kinds, names, paths, each file\'s size, type, time and checksum')
      .toEqual(shapeOf(manifestOf(original).root))
  },
)

test(
  'a damaged or altered download is refused before anything is written, each with its reason and no Import button',
  { tag: '@uc.platform.export-import:manifest-is-trusted-for-nothing' },
  async () => {
    const { page } = member
    await openSpace(page)
    await createFolder(page, failuresFolder)
    const smallPath = `${tree}/${treeFiles.small.name}`
    const smallNode = (m: Manifest) => preorder(m.root).find((n) => n.path === smallPath)!
    const cases: { label: string; zip: Buffer; message: string }[] = [
      {
        label: 'not JSON',
        zip: withManifest(treeZip, Buffer.from('{ "format": "veyq-subtree", ')),
        message: 'The list of contents in this download is not in the expected form. Nothing was imported.',
      },
      {
        label: 'newer version',
        zip: withManifest(treeZip, (m) => void (m.version = 3)),
        message: 'This download was made by a newer version of veyq. Update veyq, then try again.',
      },
      {
        label: 'size misstated',
        zip: withManifest(treeZip, (m) => void (smallNode(m).content!.file!.size += 1)),
        message: `A file in this download is not the size its list says. Nothing was imported. (${smallPath})`,
      },
      {
        label: 'payload missing',
        zip: (() => {
          const entries = new Map(readZip(treeZip))
          entries.delete(smallPath)
          return writeZip(entries)
        })(),
        message: `A file this download lists is missing. Nothing was imported. (${smallPath})`,
      },
      {
        label: 'path leaves its node',
        zip: withManifest(treeZip, (m) => void (smallNode(m).content!.file!.path = `${tree}/../${treeFiles.small.name}`)),
        message: `This download names a file at a place it may not be. Nothing was imported. (${tree}/../${treeFiles.small.name})`,
      },
    ]
    for (const c of cases) {
      await pickZip(page, `Beschädigter Download ${c.label} ${stamp}.zip`, c.zip)
      const dialog = importDialog(page)
      await expect(dialog.getByTestId('import-error'), c.label).toHaveText(c.message, { timeout: 60_000 })
      await expect(dialog.getByRole('button', { name: 'Import', exact: true }), `${c.label}: nothing to import`).toHaveCount(0)
      await dialog.getByRole('button', { name: 'Cancel', exact: true }).click()
      await expect(dialog).toBeHidden()
    }
    // Nothing was written: the folder is still empty after a re-read.
    await openTopFolder(page, failuresFolder)
    await expect(page.getByRole('button', { name: tree, exact: true })).toHaveCount(0)
  },
)

test(
  'a payload that no longer matches its checksum stops the import part-way, says how far it got and trashes what it made',
  { tag: ['@uc.platform.export-import:manifest-is-trusted-for-nothing', '@uc.platform.export-import:round-trip-by-hooks'] },
  async () => {
    const { page } = member
    await openTopFolder(page, failuresFolder)
    // The first file the import meets, one byte flipped; the ZIP's own CRC is written for the flipped bytes, so only the
    // manifest's SHA-256 can tell. Every node before it is created; the import then stops.
    const entries = new Map(readZip(treeZip))
    const order = preorder(manifestOf(entries).root)
    const first = order.findIndex((n) => n.kind === 'FILE')
    const path = order[first].content!.file!.path
    const flipped = Buffer.from(entries.get(path)!)
    flipped[flipped.length >> 1] ^= 0xff
    entries.set(path, flipped)

    await pickZip(page, `Veränderter Download ${stamp}.zip`, writeZip(entries))
    const dialog = importDialog(page)
    await expect(dialog.getByTestId('import-summary')).toContainText('7 items', { timeout: 60_000 })
    await dialog.getByRole('button', { name: 'Import', exact: true }).click()
    await expect(dialog.getByTestId('import-error')).toHaveText(
      'A file in the download does not match its checksum; the download is damaged. '
        + `The import stopped after ${first} of ${order.length} items. What was created has been moved to the trash.`,
      { timeout: 90_000 },
    )
    await dialog.getByRole('button', { name: 'Cancel', exact: true }).click()
    await expect(dialog).toBeHidden()
    await openTopFolder(page, failuresFolder)
    await expect(page.getByRole('button', { name: tree, exact: true }), 'what the import created is gone from the folder').toHaveCount(0)
  },
)

test(
  'a ZIP that is no veyq download uploads as a file, and so does a download when "Upload as a file" is chosen',
  { tag: '@uc.platform.export-import:recognised-on-upload' },
  async () => {
    const { page } = member
    await openTopFolder(page, failuresFolder)
    const foreignName = `Fotos vom Flohmarkt ohne Liste ${stamp}.zip`
    const foreign = writeZip(new Map([[`Einkaufszettel ${stamp}.txt`, Buffer.from(recordTyped(`Zwei Kerzenständer und eine Kuckucksuhr ${stamp}`))]]))
    await pickZip(page, foreignName, foreign)
    await expect(downloadButton(page, foreignName)).toBeEnabled({ timeout: 60_000 })
    await expect(importDialog(page), 'no import is offered for a ZIP without a veyq manifest').toHaveCount(0)

    const asFileName = `Ablage als Datei behalten ${stamp}.zip`
    await pickZip(page, asFileName, treeZip)
    const dialog = importDialog(page)
    await expect(dialog.getByTestId('import-summary')).toContainText('7 items', { timeout: 60_000 })
    await dialog.getByRole('button', { name: 'Upload as a file', exact: true }).click()
    await expect(dialog).toBeHidden()
    await expect(downloadButton(page, asFileName)).toBeEnabled({ timeout: 60_000 })

    // A new document: both are files of the folder, each saved byte for byte; no tree was imported.
    await openTopFolder(page, failuresFolder)
    await withoutPicker(page)
    for (const [name, bytes] of [[foreignName, foreign], [asFileName, treeZip]] as const) {
      await expect(downloadButton(page, name)).toBeEnabled({ timeout: 60_000 })
      const started = page.waitForEvent('download')
      await downloadButton(page, name).click()
      const download = await started
      expect(sha256(readFileSync(await download.path())), `${name}, byte for byte`).toBe(sha256(bytes))
    }
    await expect(page.getByRole('button', { name: tree, exact: true })).toHaveCount(0)
  },
)

/** `text` as a literal inside a regular expression. */
const literal = (text: string) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

/** The import dialog's dry run: the download's name, then what would be created where (the size in the reader's locale). */
const importSummary = (name: string, nodes: number, target: string) =>
  new RegExp(`^\\s*${literal(name)}\\s*${nodes} items, .+, will be added to “${literal(target)}”\\.\\s*$`)
