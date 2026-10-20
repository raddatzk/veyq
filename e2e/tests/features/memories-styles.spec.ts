// The web's stylesheets after the core/application split (inbox #513, ADR 0079), read in a real browser — written as lane
// L1's spec and NOT YET RUN (the implementing session ran no stack; it waits for the V1 heavy stage, where it is the
// visual proof of the move).
//
// `styles/memory.css` went: Memories' rules to the application's own `features/memories/memories.css` (imported by the
// application, so it now loads BEFORE core's `style.css`), the rendered Markdown to core's `styles/markdown.css`, and the
// image cropper's global copy away (its classes renamed `veyq-cropper*`, its light style the component's scoped block,
// the zoom label's dark twin in `dark.css`). What a unit test cannot see is the cascade: happy-dom applies no stylesheet.
// So this reads computed styles of the moved rules on the real pages, light and dark — above all the two places where a
// Memories rule used to win against a core rule of equal specificity by coming later and now wins by compounding with
// it (`.veyq-card.memory-card`, `.veyq-create-card.memory-create-card-memory`). A token's value is never spelled here:
// a probe element is given the same declaration and its computed value is the expected one, so a retuned token cannot
// turn this red, a lost rule does.
//
// It starts from the tenant whose owner the round trip activated (the `features` project). Every value typed is a canary
// (recordTyped). Labels are web/'s own (web/src/i18n/index.ts and web/src/features/memories/i18n.ts, en).
import { test, expect, type Locator, type Page } from '@playwright/test'
import { randomBytes } from 'node:crypto'
import { recordTyped } from '../../src/canaries'
import { newDevice, signInOwner, tenantOrigin, type Device } from '../../src/devices'

test.describe.configure({ mode: 'serial' })

const stamp = randomBytes(3).toString('hex')
const memoryName = `Stilprobe ${stamp}`
const chapterName = `Erstes Kapitel ${stamp}`

let member: Device
const consoleErrors: string[] = []

/** The computed value of `property` on a throwaway element given `declarations` — what a rule saying the same resolves to. */
async function probe(page: Page, declarations: string, property: string): Promise<string> {
  return page.evaluate(({ declarations, property }) => {
    const el = document.createElement('div')
    el.style.cssText = declarations
    document.body.append(el)
    const value = getComputedStyle(el).getPropertyValue(property)
    el.remove()
    return value
  }, { declarations, property })
}

/** The computed values of `properties` on the element `locator` names (the first match). */
async function styleOf(locator: Locator, properties: string[]): Promise<Record<string, string>> {
  return locator.first().evaluate((el, props) => {
    const s = getComputedStyle(el)
    return Object.fromEntries(props.map((p) => [p, s.getPropertyValue(p)]))
  }, properties)
}

async function openMemory(page: Page): Promise<void> {
  await page.goto(tenantOrigin())
  await page.getByRole('button', { name: memoryName, exact: true }).click({ timeout: 60_000 })
  await expect(page.locator('.memory-route-surface.memory-detail')).toBeVisible({ timeout: 60_000 })
}

/** Light or dark through the OS preference the theme follows by default ('system'), on a fresh document. */
async function scheme(page: Page, colorScheme: 'light' | 'dark'): Promise<void> {
  await page.emulateMedia({ colorScheme })
  await openMemory(page)
  await expect(page.locator('html')).toHaveClass(colorScheme === 'dark' ? /\bveyq-dark\b/ : /^(?!.*\bveyq-dark\b)/)
}

test.beforeAll(async ({ browser }) => {
  member = await newDevice(browser)
  member.page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()) })
  member.page.on('pageerror', (e) => consoleErrors.push(String(e)))
})

test.afterEach(() => {
  member?.confinement.assertConfined()
  expect(consoleErrors, 'the console is free of errors').toEqual([])
})

test.afterAll(async () => {
  await member?.context.close()
})

test(
  "a Memory's detail, its chapter cards and its create card wear Memories' own stylesheet, light and dark",
  { tag: ['@uc.memory.browse:view-modes'] },
  async () => {
    const { page } = member
    await signInOwner(member)
    await page.getByRole('button', { name: 'New', exact: true }).click()
    await page.getByRole('menuitem', { name: 'New Memory' }).click()
    const create = page.getByRole('dialog', { name: 'Create a Memory' })
    await create.getByLabel('Name').fill(recordTyped(memoryName))
    await create.getByRole('button', { name: 'Create', exact: true }).click()
    await expect(create).toBeHidden()

    await scheme(page, 'light')
    // The detail's surface and its tab row (`.memory-route-surface`, `.memory-tabs …`): the sheet loaded at all.
    expect(await styleOf(page.locator('.memory-route-surface.memory-detail'), ['display', 'row-gap'])).toEqual({
      display: 'grid',
      'row-gap': await probe(page, 'row-gap: 1.45rem', 'row-gap'),
    })
    expect(await styleOf(page.locator('.memory-tabs .p-tablist-tab-list'), ['overflow-x'])).toEqual({ 'overflow-x': 'auto' })
    const activeTab = page.locator('.memory-tabs .p-tab[data-p-active="true"]')
    expect(await styleOf(activeTab, ['font-weight', 'color'])).toEqual({
      'font-weight': '800',
      color: await probe(page, 'color: var(--app-success)', 'color'),
    })

    // A chapter, so the chapters' grid holds a card next to the create card.
    await page.getByRole('tab', { name: 'Chapters' }).click()
    await page.getByRole('button', { name: 'Add chapter' }).click()
    const chapterDialog = page.getByRole('dialog', { name: 'Create a chapter' })
    await chapterDialog.getByLabel('Name').fill(recordTyped(chapterName))
    await chapterDialog.getByRole('button', { name: 'Create', exact: true }).click()
    await expect(chapterDialog).toBeHidden()

    const grid = page.locator('.memory-card-grid')
    const card = grid.locator('.veyq-card.memory-card')
    const createCard = grid.locator('.veyq-create-card.memory-create-card-memory')
    await expect(card).toHaveCount(1, { timeout: 60_000 })
    expect(await styleOf(grid, ['display'])).toEqual({ display: 'grid' })

    // The two ties the move had to keep: the chapter card is Memories' (12 px corners, its own shadow and fill), not
    // core's `.veyq-card`; the create card is Memories' centred column, not core's `.veyq-create-card` row.
    expect(await styleOf(card, ['border-top-left-radius', 'box-shadow', 'background-color'])).toEqual({
      'border-top-left-radius': '12px',
      'box-shadow': await probe(page, 'box-shadow: var(--app-shadow-card)', 'box-shadow'),
      'background-color': await probe(page, 'background-color: var(--app-on-strong)', 'background-color'),
    })
    expect(await styleOf(createCard, ['text-align', 'justify-items'])).toEqual({ 'text-align': 'center', 'justify-items': 'center' })
    expect(await styleOf(card.locator('.memory-card-cover'), ['aspect-ratio'])).toEqual({ 'aspect-ratio': '16 / 9' })

    // The list variant: one column, the cover a 40 px square.
    const toolbar = page.locator('.chapters-toolbar')
    await toolbar.getByRole('button', { name: 'List view' }).click()
    await expect(grid).toHaveClass(/\bis-list\b/)
    expect(await styleOf(card.locator('.memory-card-cover'), ['width', 'height'])).toEqual({ width: '40px', height: '40px' })
    await toolbar.getByRole('button', { name: 'Grid view' }).click()
    await expect(grid).not.toHaveClass(/\bis-list\b/)

    // Dark: the twins moved with the light rules into Memories' sheet and still win over them.
    await scheme(page, 'dark')
    await page.getByRole('tab', { name: 'Chapters' }).click()
    await expect(card).toHaveCount(1, { timeout: 60_000 })
    expect(await styleOf(card, ['background-color', 'color'])).toEqual({
      'background-color': await probe(page, 'background-color: var(--app-surface)', 'background-color'),
      color: await probe(page, 'color: var(--app-text)', 'color'),
    })
    expect(await styleOf(card.locator('.memory-card-cover'), ['background-color'])).toEqual({
      'background-color': await probe(page, 'background-color: var(--app-accent-soft)', 'background-color'),
    })
    await page.emulateMedia({ colorScheme: 'light' })
  },
)

test(
  "the cover dialog's cropper wears core's own classes, the zoom label's dark twin included",
  { tag: ['@uc.platform.node-cover:dialog-crops-and-saves-one-write'] },
  async () => {
    const { page } = member
    for (const colorScheme of ['light', 'dark'] as const) {
      await scheme(page, colorScheme)
      await page.getByRole('button', { name: 'Set cover', exact: true }).first().click()
      const dialog = page.getByRole('dialog', { name: 'Cover' })
      const chooser = page.waitForEvent('filechooser')
      await dialog.getByRole('button', { name: 'Choose an image' }).click()
      await (await chooser).setFiles({ name: recordTyped(`Cover ${stamp}.png`), mimeType: 'image/png', buffer: await drawnImage(page) })

      const frame = dialog.locator('.veyq-cropper .veyq-cropper-frame')
      await expect(frame.locator('img')).toBeVisible({ timeout: 30_000 })
      expect(await styleOf(frame, ['cursor', 'overflow', 'touch-action'])).toEqual({ cursor: 'grab', overflow: 'hidden', 'touch-action': 'none' })
      expect(await styleOf(frame.locator('img'), ['user-select', 'position'])).toEqual({ 'user-select': 'none', position: 'absolute' })
      // The zoom label's dark twin (`dark.css`) ties with the component's scoped rule and wins by coming later, which holds
      // while the cropper's scoped block loads before the global sheets (it did under `memory-cover-zoom`; the rename
      // changed no load order). Red here in dark mode means that order flipped, not that the rename lost a rule.
      expect(await styleOf(dialog.locator('.veyq-cropper-zoom'), ['display', 'color'])).toEqual({
        display: 'grid',
        color: await probe(page, colorScheme === 'dark' ? 'color: var(--app-muted)' : 'color: var(--app-control-text)', 'color'),
      })
      await dialog.getByRole('button', { name: 'Cancel', exact: true }).click()
      await expect(dialog).toBeHidden()
    }
    await page.emulateMedia({ colorScheme: 'light' })
  },
)

/** A 640×360 PNG drawn on a canvas in the page — a cover-shaped image with no fixture behind it. */
async function drawnImage(page: Page): Promise<Buffer> {
  const data = await page.evaluate(() => {
    const canvas = document.createElement('canvas')
    canvas.width = 640
    canvas.height = 360
    const g = canvas.getContext('2d')!
    g.fillStyle = '#1d4ed8'
    g.fillRect(0, 0, 640, 360)
    g.fillStyle = '#f59e0b'
    g.fillRect(400, 80, 120, 120)
    return canvas.toDataURL('image/png').split(',')[1]
  })
  return Buffer.from(data, 'base64')
}
