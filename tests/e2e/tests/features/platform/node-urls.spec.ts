// The node address grammar walked deliberately in a real browser (ADR 0015 as amended 2026-10-11, inbox #328; the Use
// page `spaces/views-and-favorites` § Addresses you can bookmark). The position lives in the `#fragment`, so the server
// never sees it and the page resolves it against the tree it decrypted. Each address here is opened twice: built by the
// app when a member clicks the node, and opened cold — typed into a new document, as a bookmark is — where the page has
// nothing but the address to find the node by.
//
// One folder of its own holds the cases (src/web/src/core/node/location.ts is the grammar's one home):
//   - a **folder** ends in a slash: `#adressbuch-…/`;
//   - a **Memory** carries its application's route slug: `#adressbuch-…/memories/rom-im-mai-…`;
//   - the route slug is **reserved**: a folder named "Memories ✦" (slug `memories`) is numbered `memories-2/`;
//   - folders and files are **numbered apart**: the files `Bauplan.v1 …` and `Bauplan_v1 …` are two files (a file's
//     name folds keeping punctuation) with one URL slug, numbered by creation — `bauplan-v1-…` and `bauplan-v1-…-2` —
//     while the folder `Bauplan v1 …` keeps `bauplan-v1-…/`, its slash telling it apart;
//   - **older address forms**: a folder's address without its slash still opens it when no file has that slug (the form
//     every folder address had before the slash); a Memory's old address without `memories/` is not kept — it opens the
//     space's root, the consequence the inbox row and ADR 0015 state;
//   - **the scope prefixes**: the personal space is `#~<username>` (the owner's own handle, read from Your profile, never
//     `~me` once it is known) and a folder in it `#~<username>/…/`; a group is `#@<its name's slug>` (unique among the
//     member's groups) and a folder in it `#@…/…/`;
//   - **a chapter** extends its Memory's address without repeating the route slug: `#adressbuch-…/memories/rom-im-mai-…/
//     erster-tag-…`.
//
// It starts from the tenant whose owner the round trip activated (the `platform` project). Every value typed is a canary
// (recordTyped). Labels are src/web/'s own (src/web/src/i18n/index.ts, en).
import { test, expect, type Page } from '@playwright/test'
import { randomBytes } from 'node:crypto'
import { recordTyped } from '../../../src/canaries'
import { createFolder, downloadButton, pickFiles } from '../../../src/depot'
import { newDevice, openSettings, signInOwner, tenantOrigin, type Device } from '../../../src/devices'

test.describe.configure({ mode: 'serial' })

const stamp = randomBytes(3).toString('hex')
const base = `Adressbuch der Familie ${stamp}`
const memory = `Rom im Mai ${stamp}`
const reserved = 'Memories ✦'
const fileFirst = `Bauplan.v1 ${stamp}`
const fileSecond = `Bauplan_v1 ${stamp}`
const folderTwin = `Bauplan v1 ${stamp}`
const personalFolder = `Steuern ${stamp}`
const group = `Familie Süd ${stamp}`
const groupFolder = `Grillfest ${stamp}`
const chapter = `Erster Tag ${stamp}`

// The addresses, spelled out rather than derived: what the grammar promises, not what slugify happens to return.
const baseSlug = `adressbuch-der-familie-${stamp}`
const twinSlug = `bauplan-v1-${stamp}`
const at = {
  base: `#${baseSlug}/`,
  memory: `#${baseSlug}/memories/rom-im-mai-${stamp}`,
  reserved: `#${baseSlug}/memories-2/`,
  folderTwin: `#${baseSlug}/${twinSlug}/`,
  fileFirst: `#${baseSlug}/${twinSlug}`,
  fileSecond: `#${baseSlug}/${twinSlug}-2`,
  chapter: `#${baseSlug}/memories/rom-im-mai-${stamp}/erster-tag-${stamp}`,
  group: `#@familie-sued-${stamp}`,
  groupFolder: `#@familie-sued-${stamp}/grillfest-${stamp}/`,
}

let member: Device

/** An address opened cold: a new document whose only clue is the URL (the session survives, as a bookmark's does). */
async function openAddress(page: Page, hash: string): Promise<void> {
  await page.goto('about:blank')
  await page.goto(`${tenantOrigin()}/${hash}`)
}

const heading = (page: Page, name: string) => page.getByRole('heading', { name, exact: true })

/** The URL the app wrote, its fragment exactly [hash]. */
async function expectAddress(page: Page, hash: string): Promise<void> {
  await expect.poll(() => new URL(page.url()).hash, { message: `the address reads ${hash}` }).toBe(encodeURI(hash))
}

test.beforeAll(async ({ browser }) => {
  member = await newDevice(browser)
})

test.afterEach(() => member?.confinement.assertConfined())

test.afterAll(async () => {
  await member?.context.close()
})

test(
  'clicking a folder, a Memory, a reserved name and two files of one slug writes the grammar’s addresses',
  { tag: '@uc.spaces.switch-spaces:switch-space-by-fragment' },
  async () => {
    const { page } = member
    await signInOwner(member)
    await createFolder(page, base)
    await expectAddress(page, at.base)

    await page.getByRole('button', { name: 'New', exact: true }).click()
    await page.getByRole('menuitem', { name: 'New Memory' }).click()
    const dialog = page.getByRole('dialog', { name: 'Create a Memory' })
    await dialog.getByLabel('Name').fill(recordTyped(memory))
    await dialog.getByRole('button', { name: 'Create', exact: true }).click()
    await expect(dialog).toBeHidden()

    // One at a time, so the creation order — which numbers the two files — is the order written here.
    for (const name of [fileFirst, fileSecond]) {
      await pickFiles(page, [{ name, bytes: new Uint8Array(randomBytes(2_048)) }])
      await expect(downloadButton(page, name)).toBeEnabled({ timeout: 60_000 })
    }
    for (const name of [reserved, folderTwin]) {
      await createFolder(page, name)
      await expectAddress(page, `#${baseSlug}/${name === reserved ? 'memories-2' : twinSlug}/`)
      await page.goto(`${tenantOrigin()}/${at.base}`)
      await expect(heading(page, base)).toBeVisible({ timeout: 60_000 })
    }

    await page.getByRole('button', { name: memory, exact: true }).click()
    await expect(page.getByRole('heading', { level: 1, name: memory })).toBeVisible()
    await expectAddress(page, at.memory)

    await page.goto(`${tenantOrigin()}/${at.base}`)
    await page.getByRole('button', { name: `Open ${fileSecond}`, exact: true }).click({ timeout: 60_000 })
    await expect(heading(page, fileSecond)).toBeVisible()
    await expectAddress(page, at.fileSecond)
  },
)

test(
  'each address opened cold finds its node: the Memory, the reserved folder, the folder and both files of one slug',
  { tag: '@uc.spaces.switch-spaces:switch-space-by-fragment' },
  async () => {
    const { page } = member
    const cases: [string, string][] = [
      [at.memory, memory],
      [at.reserved, reserved],
      [at.folderTwin, folderTwin],
      // Without the slash the last segment means the file when one has the slug — the first-created keeps it.
      [at.fileFirst, fileFirst],
      [at.fileSecond, fileSecond],
    ]
    for (const [hash, name] of cases) {
      await openAddress(page, hash)
      await expect(heading(page, name), `${hash} opens “${name}”`).toBeVisible({ timeout: 60_000 })
      // And it stays there: the page did not rewrite the address to somewhere else once it resolved.
      await expectAddress(page, hash)
    }
    // A reload is one more cold open of the same address.
    await page.reload()
    await expect(heading(page, fileSecond)).toBeVisible({ timeout: 60_000 })
  },
)

test(
  'an old folder address without its slash still opens the folder; an old Memory address without memories/ opens the root',
  { tag: '@uc.spaces.switch-spaces:switch-space-by-fragment' },
  async () => {
    const { page } = member
    await openAddress(page, `#${baseSlug}`)
    await expect(heading(page, base)).toBeVisible({ timeout: 60_000 })
    await expect(page.getByRole('button', { name: memory, exact: true })).toBeVisible()

    await openAddress(page, `#${baseSlug}/rom-im-mai-${stamp}`)
    // The space's root: the folder listed as a child, neither it nor the Memory open.
    await expect(page.getByRole('button', { name: base, exact: true })).toBeVisible({ timeout: 60_000 })
    await expect(heading(page, base)).toHaveCount(0)
    await expect(page.getByRole('heading', { level: 1, name: memory })).toHaveCount(0)
  },
)

test(
  'the personal space is ~username: its root and a folder in it, written by a click and opened cold',
  { tag: '@uc.spaces.switch-spaces:switch-space-by-fragment' },
  async () => {
    const { page } = member
    // The owner's handle, as Your profile shows it: the token the grammar promises, not the one the sidebar happens to build.
    await openSettings(page, 'Your profile')
    const handle = page.locator('input[name="username"]')
    await expect(handle).not.toHaveValue('', { timeout: 60_000 })
    const username = await handle.inputValue()
    const personal = `#~${username}`
    const folder = `${personal}/steuern-${stamp}/`

    await page.goto(tenantOrigin())
    const link = page.locator('a.sidebar-personal-link')
    // Until the profile is read the link falls back to `~me`; the click is made once it names the owner.
    await expect(link).toHaveAttribute('href', new RegExp(`#~${username}$`), { timeout: 60_000 })
    await link.click()
    await expectAddress(page, personal)
    await createFolder(page, personalFolder)
    await expectAddress(page, folder)

    await openAddress(page, personal)
    // The personal root: its folder listed, which the shared space does not hold.
    await expect(page.getByRole('button', { name: personalFolder, exact: true })).toBeVisible({ timeout: 60_000 })
    await expectAddress(page, personal)
    await openAddress(page, folder)
    await expect(heading(page, personalFolder)).toBeVisible({ timeout: 60_000 })
    await expectAddress(page, folder)
  },
)

// The folder's address is the step that was red on 2026-10-29: `buildHash` wrote the group's `@` as `%40`, and vue-router
// encoded that `%` again, so the address bar read `#%2540familie-sued-…/grillfest-…/` (core/node/location.ts says why).
test(
  'a group is @ and its name\'s slug: its root and a folder in it, written by a click and opened cold',
  { tag: '@uc.spaces.switch-spaces:switch-space-by-fragment' },
  async () => {
    const { page } = member
    await page.goto(tenantOrigin())
    await page.getByRole('button', { name: 'New group', exact: true }).click({ timeout: 60_000 })
    const create = page.getByRole('dialog', { name: 'New group' })
    await create.getByLabel('Group name').fill(recordTyped(group))
    await create.getByRole('button', { name: 'Create', exact: true }).click()
    await expect(create).toBeHidden({ timeout: 30_000 })
    // Creating a group enters it: the address is its token, the page headed by its name.
    await expect(page.getByRole('heading', { level: 1, name: group })).toBeVisible({ timeout: 60_000 })
    await expectAddress(page, at.group)
    await createFolder(page, groupFolder)
    await expectAddress(page, at.groupFolder)

    await openAddress(page, at.group)
    await expect(page.getByRole('heading', { level: 1, name: group })).toBeVisible({ timeout: 60_000 })
    await expect(page.getByRole('button', { name: groupFolder, exact: true })).toBeVisible({ timeout: 60_000 })
    await expectAddress(page, at.group)
    await openAddress(page, at.groupFolder)
    await expect(heading(page, groupFolder)).toBeVisible({ timeout: 60_000 })
    await expectAddress(page, at.groupFolder)
  },
)

test(
  'a chapter extends its Memory\'s address, written by a click on its card and opened cold',
  { tag: '@uc.spaces.switch-spaces:switch-space-by-fragment' },
  async () => {
    const { page } = member
    await openAddress(page, at.memory)
    await expect(page.getByRole('heading', { level: 1, name: memory })).toBeVisible({ timeout: 60_000 })
    await page.getByRole('tab', { name: 'Chapters' }).click()
    await page.getByRole('button', { name: 'Add chapter' }).click()
    const dialog = page.getByRole('dialog', { name: 'Create a chapter' })
    await dialog.getByLabel('Name').fill(recordTyped(chapter))
    await dialog.getByRole('button', { name: 'Create', exact: true }).click()
    await expect(dialog).toBeHidden()

    await page.locator('.memory-card-main').filter({ hasText: chapter }).click({ timeout: 60_000 })
    await expect(page.getByRole('heading', { level: 1, name: chapter })).toBeVisible({ timeout: 60_000 })
    await expectAddress(page, at.chapter)

    await openAddress(page, at.chapter)
    await expect(page.getByRole('heading', { level: 1, name: chapter })).toBeVisible({ timeout: 60_000 })
    await expectAddress(page, at.chapter)
  },
)
