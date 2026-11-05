// Drag and drop on the level (inbox #421, owner 2026-11-04), dragged in a real browser: Chromium's own HTML5 drag, which
// Playwright's `dragTo` starts with the mouse (press, move, release) - no event this spec builds. The tenant owner drops a
// file on a folder (the level's upload, depot-files.spec.ts' drop), drags its row onto a sub-folder of the same level, and
// then from the sub-folder onto the parent's crumb in the breadcrumb. Each move is said in the move dialog's own toast, and
// each listing is read after a reload, so what shows was read back from the server.
//
// It starts from the tenant whose owner the round trip activated (the `depot` project). Labels are src/web/'s own
// (src/web/src/i18n/index.ts, en).
import { expect, type Page } from '@playwright/test'
import { test } from '../../../src/cspViolations'
import { randomBytes } from 'node:crypto'
import { recordTyped } from '../../../src/canaries'
import { createFolder, downloadButton } from '../../../src/depot'
import { newDevice, signInOwner, type Device } from '../../../src/devices'
import { toast } from '../../../src/toast'

test.describe.configure({ mode: 'serial' })

const stamp = randomBytes(3).toString('hex')
const parent = `Umzugskartons ${stamp}`
const sub = `Keller ${stamp}`
const file = { name: `Inventarliste ${stamp}.bin`, bytes: new Uint8Array(randomBytes(40_000)) }

let member: Device

/** A folder's row or tile of the level (its button is named by the folder), and the file's (by its download). */
const folderItem = (page: Page, name: string) => page.locator('li.veyq-level-item').filter({ has: page.getByRole('button', { name, exact: true }) })
const fileItem = (page: Page) => page.locator('li.veyq-level-item').filter({ has: downloadButton(page, file.name) })
/** The crumb of `name` in the shell's breadcrumb. */
const crumb = (page: Page, name: string) => page.locator('.workspace-topbar').getByRole('link', { name, exact: true })

test.beforeAll(async ({ browser }) => {
  member = await newDevice(browser)
})

test.afterEach(() => member?.confinement.assertConfined())

test.afterAll(async () => {
  await member?.context.close()
})

test(
  'a file dragged onto a sub-folder moves into it, and dragged onto an ancestor in the breadcrumb moves back up',
  { tag: '@uc.memory.move-node:drag-and-drop-moves-as-the-dialog-does' },
  async () => {
    const { page } = member
    await signInOwner(member)
    await createFolder(page, parent)
    await createFolder(page, sub)
    await crumb(page, parent).click()
    await expect(page.getByRole('heading', { name: parent })).toBeVisible()

    recordTyped(file.name)
    const transfer = await page.evaluateHandle((f) => {
      const data = new DataTransfer()
      data.items.add(new File([Uint8Array.from(atob(f.base64), (c) => c.charCodeAt(0))], f.name))
      return data
    }, { name: file.name, base64: Buffer.from(file.bytes).toString('base64') })
    await page.locator('.veyq-level-view').dispatchEvent('drop', { dataTransfer: transfer })
    await expect(downloadButton(page, file.name)).toBeEnabled({ timeout: 60_000 })

    // Only what the viewer may move is draggable: the file and the sub-folder are.
    await expect(fileItem(page)).toHaveAttribute('draggable', 'true')
    await expect(folderItem(page, sub)).toHaveAttribute('draggable', 'true')

    // Down: onto the sub-folder's row.
    await fileItem(page).dragTo(folderItem(page, sub))
    await expect(toast(page, `“${file.name}” moved to “${sub}”.`)).toBeVisible({ timeout: 60_000 })
    await expect(downloadButton(page, file.name)).toHaveCount(0)
    await page.reload()
    await expect(folderItem(page, sub)).toBeVisible({ timeout: 60_000 })
    await expect(downloadButton(page, file.name)).toHaveCount(0)
    await page.getByRole('button', { name: sub, exact: true }).click()
    await expect(downloadButton(page, file.name)).toBeEnabled({ timeout: 60_000 })

    // Up: onto the parent's crumb.
    await fileItem(page).dragTo(crumb(page, parent))
    await expect(toast(page, `“${file.name}” moved to “${parent}”.`)).toBeVisible({ timeout: 60_000 })
    await expect(downloadButton(page, file.name)).toHaveCount(0)
    await page.reload()
    await expect(page.getByRole('heading', { name: sub })).toBeVisible({ timeout: 60_000 })
    await expect(downloadButton(page, file.name)).toHaveCount(0)
    await crumb(page, parent).click()
    await expect(downloadButton(page, file.name)).toBeEnabled({ timeout: 60_000 })
  },
)
