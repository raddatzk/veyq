// Every text and icon field seals under a key of its own (en.platform.field-key, ADR 0093 § 9) — read back in a real
// browser (cr-text-fields-seal-under-their-own-key's hand pass, automated). One write per field purpose, each through
// the screen a member uses: a folder's name renamed and moved (`node-name`, re-sealed under the destination), a
// Memory's date (`node-meta`), a sub-bucket (`bucket-name`), a share's label and note (`share-name`, `share-note`) and
// the Memory's name the link carries (`share-memory-name`), a guest's display name (`share-identity-name`), the member's
// own name and picture (`member-name`, `member-avatar`). Each value is read back after a reload — a new document, so
// what shows was fetched as ciphertext and opened again, never the page's memory of what it typed — and on a second
// device, which holds no key but what it derives from the password and the Secret Key. A write sealed under a key no
// reader can derive is a value that shows on the writing tab and nowhere else; this is the pass that sees it.
//
// It starts from the tenant whose owner the round trip activated (the `account` project). Every value typed is a
// canary (recordTyped): the orchestrator's sweep proves none of it is readable in the database, the bucket, a log or a
// statement. The labels are src/web/'s own (src/web/src/i18n/index.ts, en).
import { test, expect, type Page } from '@playwright/test'
import { randomBytes } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { recordTyped } from '../../../src/canaries'
import { addLanguage } from '../../../src/localeEditor'
import { newDevice, openSettings, signInOwner, tenantOrigin, type Device } from '../../../src/devices'
import { toast } from '../../../src/toast'
import { headerAction, renameInHeader } from '../../../src/header'

test.describe.configure({ mode: 'serial' })

// The round trip's photo (1×1 JPEG with EXIF): what the guest contributes, so its name has a bucket to show under.
const PHOTO = fileURLToPath(new URL('../../../../../src/web/src/features/memories/__fixtures__/exif-sample.jpg', import.meta.url))

const stamp = randomBytes(3).toString('hex')
const folderBefore = `Ordner Alpha vom Dachboden ${stamp}`
const folderAfter = `Ordner Gamma aus dem Keller ${stamp}`
// Its fragment path in the tenant space (token-less, src/web/src/shared/naming.ts `slugify`): the stamp is lowercase hex.
const folderAfterPath = `ordner-gamma-aus-dem-keller-${stamp}/`
const destination = `Zielordner Beta im Flur ${stamp}`
const memoryName = `Sommerfrische an der Mosel ${stamp}`
// Typed as the en screen writes a day; sealed in the Memory's meta as the ISO day — both are canaries.
const memoryDay = '07/14/2021'
const sealedDay = '2021-07-14'
const subBucket = `Bilder vom Weinfest ${stamp}`
const shareLabel = `Link für den Kegelclub ${stamp}`
const shareNote = `Bitte nur Fotos vom Weinfest hochladen, danke schön ${stamp}`
const guestName = `Onkel Friedhelm ${stamp}`
const firstName = `Waltraud ${stamp}`
const lastName = `Ährenfeld-Ostermann ${stamp}`

let writer: Device // the member who writes every field
let second: Device // the same member on a second device
let guest: Device
let shareLink: string

async function openMemory(page: Page): Promise<void> {
  await page.goto(tenantOrigin())
  await page.getByRole('button', { name: memoryName, exact: true }).click({ timeout: 60_000 })
  await expect(page.getByRole('heading', { level: 1, name: memoryName })).toBeVisible()
}

/** The Memory's Contributions picker, opened: one option per bucket, its name opened from its seal. */
async function bucketOptions(page: Page) {
  await page.getByRole('tab', { name: 'Contributions' }).click()
  await page.getByRole('combobox', { name: 'Contributions' }).click()
  return page.getByRole('listbox')
}

/** A PNG drawn in the page — the profile picture, cropped and sealed on the device that picks it. */
async function drawnPicture(page: Page): Promise<Buffer> {
  const dataUrl = await page.evaluate(() => {
    const canvas = document.createElement('canvas')
    canvas.width = 320
    canvas.height = 320
    const g = canvas.getContext('2d')!
    const fill = g.createLinearGradient(0, 0, 320, 320)
    fill.addColorStop(0, '#2d6a4f')
    fill.addColorStop(1, '#f4a261')
    g.fillStyle = fill
    g.fillRect(0, 0, 320, 320)
    return canvas.toDataURL('image/png')
  })
  return Buffer.from(dataUrl.split(',')[1]!, 'base64')
}

test.beforeAll(async ({ browser }) => {
  writer = await newDevice(browser)
  second = await newDevice(browser)
  guest = await newDevice(browser)
})

test.afterEach(() => {
  for (const device of [writer, second, guest]) device?.confinement.assertConfined()
})

test.afterAll(async () => {
  for (const device of [writer, second, guest]) await device?.context.close()
})

test(
  'a folder renamed and moved shows its new name in its new place after a reload and on a second device',
  {
    tag: [
      '@uc.memory.browse:rename-and-delete-subtree',
      '@uc.memory.move-node:a-move-keeps-the-subtree-readable',
    ],
  },
  async () => {
    const { page } = writer
    await signInOwner(writer)
    for (const name of [folderBefore, destination]) {
      await page.getByRole('button', { name: 'New', exact: true }).click()
      await page.getByRole('menuitem', { name: 'New folder' }).click()
      const dialog = page.getByRole('dialog', { name: 'Create a folder' })
      await dialog.getByLabel('Name').fill(recordTyped(name))
      await dialog.getByRole('button', { name: 'Create', exact: true }).click()
      await expect(dialog).toBeHidden()
    }

    await page.getByRole('button', { name: folderBefore, exact: true }).click()
    // The folder's name is renamed in place in its header (ADR 0110 decision 11): no dialog.
    await renameInHeader(page, folderBefore, recordTyped(folderAfter))
    // The reload re-opens what the URL names: the folder's NEW slug path (ADR 0015, a folder with its trailing slash).
    // Reloaded on the old one, the path resolves to nothing and the page stands on the space root (inbox #475).
    await expect(page).toHaveURL(new RegExp(`#${folderAfterPath}$`))
    await page.reload()
    await expect(page.getByRole('heading', { name: folderAfter })).toBeVisible({ timeout: 60_000 })

    // Moved into the other folder: its name is sealed again under the destination's key.
    await headerAction(page, 'Move to…')
    const move = page.getByRole('dialog', { name: `Move “${folderAfter}”` })
    await move.getByRole('button', { name: destination, exact: true }).click()
    await move.getByRole('button', { name: 'Move here' }).click()
    await expect(move).toBeHidden()

    for (const device of [writer, second]) {
      if (device === second) await signInOwner(second)
      const on = device.page
      await on.goto(tenantOrigin())
      await expect(on.getByRole('button', { name: destination, exact: true })).toBeVisible({ timeout: 60_000 })
      await expect(on.getByRole('button', { name: folderBefore, exact: true })).toHaveCount(0)
      await expect(on.getByRole('button', { name: folderAfter, exact: true })).toHaveCount(0)
      await on.getByRole('button', { name: destination, exact: true }).click()
      await on.getByRole('button', { name: folderAfter, exact: true }).click()
      await expect(on.getByRole('heading', { name: folderAfter })).toBeVisible()
    }
  },
)

test(
  'a Memory\'s date shows after a reload and on a second device',
  { tag: '@uc.memory.date:four-mode-date-header-editor' },
  async () => {
    const { page } = writer
    await page.goto(tenantOrigin())
    await page.getByRole('button', { name: 'New', exact: true }).click({ timeout: 60_000 })
    await page.getByRole('menuitem', { name: 'New Memory' }).click()
    const create = page.getByRole('dialog', { name: 'Create a Memory' })
    await create.getByLabel('Name').fill(recordTyped(memoryName))
    await create.getByRole('button', { name: 'Create', exact: true }).click()
    await expect(create).toBeHidden()
    await openMemory(page)

    await page.getByRole('button', { name: 'Edit date' }).click()
    const editor = page.getByRole('dialog', { name: 'Date' })
    await editor.getByRole('button', { name: 'Single day' }).click()
    recordTyped(sealedDay)
    // The day is typed into the field's segments (ADR 0107: Reka's date field, one spinbutton per part, `mm/dd/yyyy` in
    // English), as a keyboard does: each segment moves on to the next once it is full.
    const day = editor.getByRole('group', { name: 'Day' })
    await day.getByRole('spinbutton', { name: 'Month' }).click()
    await page.keyboard.type(recordTyped(memoryDay).replace(/\D/g, ''))
    await expect(day.getByRole('spinbutton', { name: 'Year' })).toHaveText('2021')
    await page.keyboard.press('Tab')
    await editor.getByRole('button', { name: 'Save', exact: true }).click()
    await expect(editor).toBeHidden()

    for (const device of [writer, second]) {
      await openMemory(device.page)
      await expect(device.page.getByRole('button', { name: 'Edit date' })).toContainText(memoryDay)
    }
  },
)

test(
  'a sub-bucket\'s name shows after a reload and on a second device',
  {
    tag: ['@uc.memory.buckets:sub-bucket-endpoints', '@uc.memory.buckets:contributions-tab-primary-grouping'],
  },
  async () => {
    const { page } = writer
    await page.getByRole('tab', { name: 'Contributions' }).click()
    await page.getByRole('button', { name: 'New sub-bucket' }).click()
    const dialog = page.getByRole('dialog', { name: 'New sub-bucket' })
    await dialog.getByLabel('Name').fill(recordTyped(subBucket))
    // The button's name carries its icon's glyph before "Save", so it is matched as a part of the name.
    await dialog.getByRole('button', { name: 'Save' }).click()
    await expect(dialog).toBeHidden()

    for (const device of [writer, second]) {
      await openMemory(device.page)
      const options = await bucketOptions(device.page)
      await expect(options.getByRole('option', { name: subBucket })).toBeVisible()
      await device.page.keyboard.press('Escape')
    }
  },
)

test(
  'a share\'s label and note show after a reload and on a second device',
  { tag: '@uc.sharing.share-note:curator-sets-note-at-creation' },
  async () => {
    const { page } = writer
    await openMemory(page)
    await headerAction(page, 'Share')
    await page.getByRole('dialog', { name: 'Share this Memory' }).getByRole('button', { name: 'New share' }).click()
    const create = page.getByRole('dialog', { name: 'New share' })
    await create.getByLabel('Name').fill(recordTyped(shareLabel))
    await create.getByRole('button', { name: 'View + add photos' }).click()
    await expect(create.getByRole('button', { name: 'View + add photos' })).toHaveAttribute('aria-pressed', 'true')
    await create.getByRole('switch', { name: 'Note' }).click()
    await addLanguage(page, create, 'English', recordTyped(shareNote))
    await create.getByRole('button', { name: 'Create link' }).click()
    shareLink = await create.getByRole('textbox', { name: 'Public link' }).inputValue()
    expect(new URL(shareLink).hash, 'the link key travels in the fragment').toMatch(/^#k=/)
    await create.getByRole('button', { name: 'Done' }).click()
    await page.keyboard.press('Escape')

    for (const device of [writer, second]) {
      await openMemory(device.page)
      await headerAction(device.page, 'Share')
      const list = device.page.getByRole('dialog', { name: 'Share this Memory' })
      await expect(list.getByText(shareLabel, { exact: true })).toBeVisible({ timeout: 60_000 })
      await list.getByRole('button', { name: 'Edit', exact: true }).first().click()
      const edit = device.page.getByRole('dialog', { name: 'Edit share' })
      await expect(edit.getByLabel('Name')).toHaveValue(shareLabel)
      // The note's English text, opened from its seal into the editor it was written in.
      await edit.getByRole('button', { name: 'Edit', exact: true }).click()
      await expect(device.page.getByRole('dialog', { name: 'Edit English' }).getByRole('textbox', { name: 'Markdown' }))
        .toHaveValue(shareNote)
    }
  },
)

test(
  'a guest reads the note and the Memory\'s name, names itself, and its name reaches the members, on both devices',
  {
    tag: [
      '@uc.sharing.share-note:note-shown-and-rendered-on-share-page',
      '@uc.sharing.share-public-viewer:anonymous-resolve-decrypt',
      '@uc.sharing.share-contributor:sealed-name-anonymous-identity',
      '@uc.memory.buckets:guest-names-for-members-only',
    ],
  },
  async () => {
    const { page } = guest
    await page.goto(shareLink)
    await expect(page.getByText('Shared with you · you can contribute')).toBeVisible({ timeout: 60_000 })
    await expect(page.getByRole('heading', { level: 1, name: memoryName })).toBeVisible()
    await expect(page.getByText(shareNote)).toBeVisible()

    // Asked at the first write: the name, then the picker.
    await page.getByRole('button', { name: 'Upload', exact: true }).click()
    const naming = page.getByRole('dialog', { name: 'What is your name?' })
    await naming.getByLabel('Your name').fill(recordTyped(guestName))
    await naming.getByRole('button', { name: 'Continue' }).click()
    await expect(naming).toBeHidden()
    const chooser = page.waitForEvent('filechooser')
    await page.getByRole('button', { name: 'Upload', exact: true }).click()
    await (await chooser).setFiles({
      name: recordTyped(`Weinprobe im Gewölbekeller ${stamp}.jpg`), mimeType: 'image/jpeg', buffer: readFileSync(PHOTO),
    })
    await expect(page.getByRole('button', { name: 'Open item 1' })).toBeVisible({ timeout: 60_000 })

    await page.reload()
    await expect(page.getByText(`You are here as ${guestName}.`)).toBeVisible({ timeout: 60_000 })
    await expect(page.getByText(shareNote)).toBeVisible()

    for (const device of [writer, second]) {
      await openMemory(device.page)
      const options = await bucketOptions(device.page)
      await expect(options.getByRole('option', { name: `${guestName} (guest)` })).toBeVisible({ timeout: 60_000 })
      await device.page.keyboard.press('Escape')
    }
  },
)

test(
  'the member\'s own name and picture show after a reload and on a second device',
  {
    tag: [
      '@uc.spaces.edit-profile:name-sealed-on-device-at-its-address',
      '@uc.spaces.set-profile-picture:picture-sealed-on-device-constant-length',
    ],
  },
  async () => {
    const { page } = writer
    await openSettings(page, 'Your profile')
    await expect(page.getByRole('heading', { level: 2, name: 'Your profile' })).toBeVisible({ timeout: 60_000 })
    await page.getByLabel('First name').fill(recordTyped(firstName))
    await page.getByLabel('Last name').fill(recordTyped(lastName))
    const picture = await drawnPicture(page)
    await page.getByRole('button', { name: /^(Choose|Change) picture$|^Choose a picture$/ }).first().click()
    const dialog = page.getByRole('dialog', { name: 'Profile picture' })
    await dialog.getByTestId('profile-picture-file').setInputFiles({ name: 'portrait.png', mimeType: 'image/png', buffer: picture })
    await dialog.getByTestId('profile-picture-use').click()
    await expect(page.getByText('New picture — saved with your profile.')).toBeVisible()
    await page.getByRole('button', { name: 'Save profile' }).click()
    await expect(toast(page, 'Profile updated.')).toBeVisible()

    for (const device of [writer, second]) {
      const on = device.page
      await openSettings(on, 'Your profile')
      await expect(on.getByLabel('First name')).toHaveValue(firstName, { timeout: 60_000 })
      await expect(on.getByLabel('Last name')).toHaveValue(lastName)
      // The sidebar's avatar: the picture, fetched as ciphertext and opened on this device.
      const avatar = on.locator('.profile-button [data-avatar-image] img')
      await expect(avatar).toHaveAttribute('src', /^blob:/, { timeout: 60_000 })
      await expect.poll(() => avatar.evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth),
        { message: 'the profile picture decoded' }).toBeGreaterThan(0)
    }
  },
)
