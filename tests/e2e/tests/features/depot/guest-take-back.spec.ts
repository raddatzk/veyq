// A guest's take-back in a real browser (uc.depot.share-folder-files E5; inbox #468's hand pass, automated). The tenant
// owner shares a folder that holds an empty folder of its own with a link that lets guests add files, and sets the
// space's per-link limit below the size of one file. A guest names itself and uploads that file: the link refuses it,
// and the page takes back the empty file its create left (`abandonShareNode`) — erased, so neither the guest nor the
// member sees it after a reload. Then the same guest, by hand, asks the same endpoint to take back the MEMBER's empty
// folder: `409 not_a_placeholder`, and the folder stays. The first take-back is the second's positive control — same
// endpoint, same guest, same token — so the refusal is the rule's, never a broken call.
//
// It starts from the tenant whose owner the round trip activated (the `depot` project); the per-link limit it
// lowers is restored at its end (the next feature scenario uploads through a link). Labels are src/web/'s own
// (src/web/src/i18n/index.ts, en).
import { expect, type Page, type Request } from '@playwright/test'
import { test } from '../../../src/cspViolations'
import { randomBytes } from 'node:crypto'
import { recordTyped } from '../../../src/canaries'
import { newDevice, openSettings, signInOwner, tenantOrigin, type Device } from '../../../src/devices'
import { toast } from '../../../src/toast'
import { headerAction } from '../../../src/header'

test.describe.configure({ mode: 'serial' })

const stamp = randomBytes(3).toString('hex')
const sharedFolder = `Gemeinsame Ablage ${stamp}`
const memberFolder = `Leerer Ordner der Familie ${stamp}`
const fileName = `Kassenbuch vom Sommerfest ${stamp}.bin`
const guestName = `Tante Hildegard ${stamp}`
const shareLabel = `Für die Nachbarschaft ${stamp}`
// 0.0001 GiB is 107 374 bytes; the file is three times that, so its first byte past the limit is refused.
const LINK_LIMIT_GIB = '0.0001'
const FILE_BYTES = 3 * 107_374

let member: Device
let guest: Device
let shareLink: string
let memberFolderId: string
let restoreLimit: string | undefined

const storageLimit = (page: Page) => page.getByRole('textbox', { name: /^Limit per upload link \(GiB\)/ })

async function setLinkLimit(page: Page, gib: string): Promise<void> {
  await openSettings(page, 'Storage')
  await expect(storageLimit(page)).toBeVisible({ timeout: 60_000 })
  await storageLimit(page).fill(gib)
  await page.getByRole('button', { name: 'Save storage settings' }).click()
  await expect(toast(page, 'Storage settings saved.')).toBeVisible()
}

/** The member's view of the shared folder, read fresh from the server: a new document, opened from the space. */
async function openSharedFolder(page: Page): Promise<void> {
  await page.goto(tenantOrigin())
  await page.getByRole('button', { name: sharedFolder, exact: true }).click({ timeout: 60_000 })
  await expect(page.getByRole('heading', { name: sharedFolder })).toBeVisible()
}

const shareIdOf = (link: string) => new URL(link).pathname.split('/').pop()!

test.beforeAll(async ({ browser }) => {
  member = await newDevice(browser)
  guest = await newDevice(browser)
})

test.afterEach(() => {
  for (const device of [member, guest]) device?.confinement.assertConfined()
})

test.afterAll(async () => {
  // A red test must not leave the space's links refusing every upload for the scenarios after this one.
  if (restoreLimit !== undefined && member) await setLinkLimit(member.page, restoreLimit).catch(() => undefined)
  for (const device of [member, guest]) await device?.context.close()
})

test(
  'the owner shares a folder that holds an empty folder of its own with a link guests may add files through',
  { tag: '@uc.memory.browse:create-folder-and-memory' },
  async () => {
    const { page } = member
    await signInOwner(member)
    await page.getByRole('button', { name: 'New', exact: true }).click()
    await page.getByRole('menuitem', { name: 'New folder' }).click()
    let dialog = page.getByRole('dialog', { name: 'Create a folder' })
    await dialog.getByLabel('Name').fill(recordTyped(sharedFolder))
    await dialog.getByRole('button', { name: 'Create', exact: true }).click()
    await expect(dialog).toBeHidden()
    await page.getByRole('button', { name: sharedFolder, exact: true }).click()
    await expect(page.getByRole('heading', { name: sharedFolder })).toBeVisible()

    // The member's empty folder: its node id is client-minted, read off the create the page sends.
    await page.getByRole('button', { name: 'New', exact: true }).click()
    await page.getByRole('menuitem', { name: 'New folder' }).click()
    dialog = page.getByRole('dialog', { name: 'Create a folder' })
    await dialog.getByLabel('Name').fill(recordTyped(memberFolder))
    const create = page.waitForRequest((r: Request) => r.method() === 'POST' && /\/api\/scopes\/[^/]+\/nodes$/.test(r.url()))
    await dialog.getByRole('button', { name: 'Create', exact: true }).click()
    memberFolderId = ((await create).postDataJSON() as { nodeId: string }).nodeId
    await expect(dialog).toBeHidden()
    await expect(page.getByRole('button', { name: memberFolder, exact: true })).toBeVisible()

    await headerAction(page, 'Share')
    const share = page.getByRole('dialog', { name: 'Share this folder' })
    await share.getByRole('button', { name: 'New share' }).click()
    const createShare = page.getByRole('dialog', { name: 'New share' })
    await createShare.getByLabel('Name').fill(recordTyped(shareLabel))
    await createShare.getByRole('button', { name: 'View + add files' }).click()
    await expect(createShare.getByRole('button', { name: 'View + add files' })).toHaveAttribute('aria-pressed', 'true')
    await createShare.getByRole('button', { name: 'Create link' }).click()
    const linkView = page.getByRole('dialog', { name: 'Share this folder' })
    shareLink = await linkView.getByRole('textbox', { name: 'Public link' }).inputValue()
    expect(new URL(shareLink).hash, 'the link key travels in the fragment').toMatch(/^#k=/)
    await linkView.getByRole('button', { name: 'Done' }).click()
    await page.keyboard.press('Escape')
    await expect(linkView).toBeHidden()

    // The space's per-link limit, below the file the guest will send; restored at the end.
    await openSettings(page, 'Storage')
    await expect(storageLimit(page)).toBeVisible({ timeout: 60_000 })
    restoreLimit = await storageLimit(page).inputValue()
    await setLinkLimit(page, LINK_LIMIT_GIB)
  },
)

test(
  'a guest\'s upload the full link refuses is taken back: its empty file is erased, for the guest and the member',
  {
    tag: [
      '@uc.depot.share-folder-files:a-refused-upload-leaves-no-file-behind',
      '@uc.depot.share-folder-files:guest-files-count-against-the-link-limit',
    ],
  },
  async () => {
    const { page } = guest
    await page.goto(shareLink)
    await expect(page.getByText('Shared with you · you can add and change things here')).toBeVisible({ timeout: 60_000 })
    await expect(page.getByRole('button', { name: memberFolder, exact: true })).toBeVisible()

    const chooser = page.waitForEvent('filechooser')
    await page.getByRole('button', { name: 'New', exact: true }).click()
    await page.getByRole('menuitem', { name: 'Upload files' }).click()
    await (await chooser).setFiles({
      name: recordTyped(fileName), mimeType: 'application/octet-stream', buffer: randomBytes(FILE_BYTES),
    })

    // The guest names itself at its first write; the name is sealed, a canary.
    const naming = page.getByRole('dialog', { name: 'What is your name?' })
    await naming.getByLabel('Your name').fill(recordTyped(guestName))
    const created = page.waitForRequest((r: Request) =>
      r.method() === 'POST' && /\/api\/shares\/[^/]+\/nodes$/.test(new URL(r.url()).pathname))
    await naming.getByRole('button', { name: 'Continue' }).click()
    const placeholderId = ((await created).postDataJSON() as { nodeId: string }).nodeId

    // The link refuses the bytes; the page takes back the node its create made — 204, erased.
    const takeBack = await page.waitForResponse((r) =>
      r.request().method() === 'DELETE' && new URL(r.url()).pathname.endsWith(`/nodes/${placeholderId}`))
    expect(takeBack.status(), 'the placeholder was taken back').toBe(204)
    await expect(page.getByText(`“${fileName}” was not uploaded: this link takes no more uploads right now.`))
      .toBeVisible()

    // A new document reads the tree from the server: the member's folder, and no trace of the file.
    await page.reload()
    await expect(page.getByText(`You are here as ${guestName}.`)).toBeVisible({ timeout: 60_000 })
    await expect(page.getByRole('button', { name: memberFolder, exact: true })).toBeVisible()
    await expect(page.getByText(fileName)).toHaveCount(0)

    await openSharedFolder(member.page)
    await expect(member.page.getByRole('button', { name: memberFolder, exact: true })).toBeVisible()
    await expect(member.page.getByText(fileName)).toHaveCount(0)
  },
)

test(
  'the same guest asking to take back the member\'s empty folder is refused with not_a_placeholder, and it stays',
  { tag: '@uc.depot.share-folder-files:a-guest-takes-back-only-its-own-placeholder' },
  async () => {
    const { page } = guest
    const shareId = shareIdOf(shareLink)
    // Hand-built, as a guest could send it: its own contributor token, the member's folder in the same shared tree.
    const answer = await page.evaluate(async ({ shareId, nodeId }) => {
      const session = JSON.parse(localStorage.getItem(`veyq:share-contributor:${shareId}`) ?? 'null') as
        { contributorToken: string } | null
      if (!session) return { status: 0, body: 'this tab holds no guest session' as unknown }
      const response = await fetch(`/api/shares/${shareId}/nodes/${nodeId}`, {
        method: 'DELETE', headers: { 'X-Contributor-Token': session.contributorToken },
      })
      // A 204 has no body: read it as text, so an erase is red on the status the server gave, not on a parse.
      const text = await response.text()
      return { status: response.status, body: (text ? JSON.parse(text) : null) as unknown }
    }, { shareId, nodeId: memberFolderId })
    expect(answer).toEqual({ status: 409, body: expect.objectContaining({ code: 'not_a_placeholder' }) })

    await page.reload()
    await expect(page.getByRole('button', { name: memberFolder, exact: true })).toBeVisible({ timeout: 60_000 })
    await openSharedFolder(member.page)
    await expect(member.page.getByRole('button', { name: memberFolder, exact: true })).toBeVisible()

    await setLinkLimit(member.page, restoreLimit!)
    restoreLimit = undefined
  },
)
