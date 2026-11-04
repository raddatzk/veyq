// Depot through a member's and a guest's screens, the steps several feature scenarios share: a folder made, a file
// uploaded into the open level, a folder link made, and what a download leaves behind measured from inside the page.
// The labels are src/web/'s own (src/web/src/i18n/index.ts, en), so a renamed button is red in every scenario that uses them.
import { expect, type BrowserContext, type Page } from '@playwright/test'
import { createHash } from 'node:crypto'
import { recordTyped } from './canaries'
import { headerAction } from './header'

export const sha256 = (bytes: Uint8Array) => createHash('sha256').update(bytes).digest('hex')

/** A folder made in the open level and opened: its name is sealed, a canary. */
export async function createFolder(page: Page, name: string): Promise<void> {
  await page.getByRole('button', { name: 'New', exact: true }).click()
  await page.getByRole('menuitem', { name: 'New folder' }).click()
  const dialog = page.getByRole('dialog', { name: 'Create a folder' })
  await dialog.getByLabel('Name').fill(recordTyped(name))
  await dialog.getByRole('button', { name: 'Create', exact: true }).click()
  await expect(dialog).toBeHidden()
  await page.getByRole('button', { name, exact: true }).click()
  await expect(page.getByRole('heading', { name })).toBeVisible()
}

/** Files uploaded into the open level through New → Upload files (a member's, or a contributor's after naming). */
export async function pickFiles(page: Page, files: { name: string; bytes: Uint8Array }[]): Promise<void> {
  const chooser = page.waitForEvent('filechooser')
  await page.getByRole('button', { name: 'New', exact: true }).click()
  await page.getByRole('menuitem', { name: 'Upload files' }).click()
  await (await chooser).setFiles(files.map((f) => ({
    name: recordTyped(f.name), mimeType: 'application/octet-stream', buffer: Buffer.from(f.bytes),
  })))
}

/** A file's row action, enabled once its blob is listed — the row is usable, its upload attached. */
export const downloadButton = (page: Page, name: string) => page.getByRole('button', { name: `Download ${name}`, exact: true })

/** A link on the open folder: `View only` or `View + add files`. Returns the link the dialog shows. */
export async function shareOpenFolder(page: Page, label: string, role: 'View only' | 'View + add files'): Promise<string> {
  await headerAction(page, 'Share')
  const share = page.getByRole('dialog', { name: 'Share this folder' })
  await share.getByRole('button', { name: 'New share' }).click()
  const create = page.getByRole('dialog', { name: 'New share' })
  await create.getByLabel('Name').fill(recordTyped(label))
  await create.getByRole('button', { name: role }).click()
  await expect(create.getByRole('button', { name: role })).toHaveAttribute('aria-pressed', 'true')
  await create.getByRole('button', { name: 'Create link' }).click()
  const linkView = page.getByRole('dialog', { name: 'Share this folder' })
  const link = await linkView.getByRole('textbox', { name: 'Public link' }).inputValue()
  expect(new URL(link).hash, 'the link key travels in the fragment').toMatch(/^#k=/)
  await linkView.getByRole('button', { name: 'Done' }).click()
  await page.keyboard.press('Escape')
  await expect(linkView).toBeHidden()
  return link
}

/**
 * A drop box in the open folder - or at the space's top level - with its first link, in one flow (owner 2026-11-04): the
 * header's Share, a new link "Add files only", named `name` (the drop box's name). Returns the link the dialog shows; the
 * drop box is then a row of the level, its links its own dialog's.
 */
export async function createDropBoxLink(page: Page, name: string): Promise<string> {
  await headerAction(page, 'Share')
  // A folder's dialog opens on its links; the top level's has none and opens on the form.
  const list = page.getByRole('dialog', { name: 'Share this folder' })
  const create = page.getByRole('dialog', { name: 'New share' })
  await expect(list.or(create)).toBeVisible()
  if (await list.isVisible()) await list.getByRole('button', { name: 'New share' }).click()
  await create.getByRole('button', { name: 'Add files only' }).click()
  await expect(create.getByRole('button', { name: 'Add files only' })).toHaveAttribute('aria-pressed', 'true')
  await create.getByLabel('Name').fill(recordTyped(name))
  await create.getByRole('button', { name: 'Create link' }).click()
  const linkView = page.getByRole('dialog', { name: /^(Share this folder|New share)$/ })
  const link = await linkView.getByRole('textbox', { name: 'Public link' }).inputValue()
  expect(new URL(link).hash, 'the link key travels in the fragment').toMatch(/^#k=/)
  await linkView.getByRole('button', { name: 'Done' }).click()
  // Done shows a folder's links again (Escape closes them); it closes the top level's dialog.
  if (await list.isVisible()) await page.keyboard.press('Escape')
  await expect(list.or(create)).toBeHidden()
  await expect(page.getByRole('button', { name: `Open ${name}`, exact: true })).toBeVisible()
  return link
}

/**
 * Every `Blob` the page constructs, by size, recorded from the document's first script on (`window.__veyqBlobSizes`).
 * The constructor is wrapped in a Proxy, never subclassed, so `instanceof Blob` still holds for a `File` the page was
 * handed. A download that holds the whole file in tab memory builds one Blob of its size (`saveStream.ts` `blobSink`);
 * one that streams builds none that large. It sees the page's main world only — a Blob a worker built is not counted.
 */
export async function recordBlobs(context: BrowserContext): Promise<void> {
  await context.addInitScript(() => {
    const sizes: number[] = []
    const w = window as unknown as { __veyqBlobSizes: number[]; Blob: typeof Blob }
    w.__veyqBlobSizes = sizes
    w.Blob = new Proxy(Blob, {
      construct(target, args, newTarget) {
        const blob = Reflect.construct(target, args, newTarget) as Blob
        sizes.push(blob.size)
        return blob
      },
    })
  })
}

export const largestBlob = (page: Page) =>
  page.evaluate(() => Math.max(0, ...(window as unknown as { __veyqBlobSizes: number[] }).__veyqBlobSizes))

export const forgetBlobs = (page: Page) =>
  page.evaluate(() => void ((window as unknown as { __veyqBlobSizes: number[] }).__veyqBlobSizes.length = 0))
