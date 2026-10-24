// A Memory's banner in a real browser (uc.memory.banner, ADR 0102): the browser pass cr-memory-banner's phase 4 names,
// written as lane L1's spec and NOT YET RUN (the implementing sessions ran no stack; it waits for the V1 heavy stage).
// cr-cover-banner-upload-only-and-dedup edited it (not run either): the banner is set only by a file picked in its dialog,
// has no description, and a file equal to a gallery medium shares that medium's stored object (ADR 0013 § 5).
//
// The owner creates a Memory and adds a landscape image, a portrait image and a video to it, then:
//  - sets a landscape image as the banner from a file, moves the point that stays in view, and reads the frame at a wide
//    and at a phone width (3:1 and 16:9, never taller than 35 % of the viewport); scrolls, and the tab row sticks under the
//    top bar while the banner scrolls away;
//  - uploads the gallery's portrait photo again as the banner: nothing is uploaded (it shares the photo's PREVIEW), it
//    fits, its sides blurred, and switches to fill;
//  - uploads the gallery's video again as the banner: nothing is uploaded (it shares the video's ORIGINAL and PREVIEW),
//    its poster and a play button, no <video> and no read of the banner's video before the click; after it, the video
//    plays from the media Service Worker and the presigns it asks for name the banner's own blob row, never the gallery's;
//  - opens the same Memory through a READ link: the banner is there, no banner action, and the tab row sticks at the top
//    of a page with no top bar;
//  - removes the banner.
// The images are drawn in the browser (a canvas, so no fixture of a size the frame needs), the video is the suite's test
// pattern (`fixtures/testsrc2-6s-vp8.webm`; landscape - a portrait video is not among the fixtures). The console is read
// for errors throughout. Every value typed is a canary (recordTyped). Labels are src/web/'s own (src/web/src/i18n/index.ts, en).
import { test, expect, type Page, type Request } from '@playwright/test'
import { randomBytes } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { recordTyped } from '../../src/canaries'
import { newDevice, signInOwner, tenantOrigin, watchConsole, type ConsoleErrors, type Device } from '../../src/devices'

test.describe.configure({ mode: 'serial' })

const VIDEO = readFileSync(fileURLToPath(new URL('../../fixtures/testsrc2-6s-vp8.webm', import.meta.url)))
const stamp = randomBytes(3).toString('hex')
const memoryName = `Sommer am See ${stamp}`

/** The gallery's files, kept to upload the same bytes into the banner dialog again. */
let portrait: { name: string; mimeType: string; buffer: Buffer }
let member: Device
let guest: Device
let shareLink = ''
let pageConsole: ConsoleErrors

/** A PNG of `w`×`h` drawn on a canvas in the page: a gradient with a marker, so a frame shows where it is cut. */
async function drawnImage(page: Page, w: number, h: number): Promise<Buffer> {
  const data = await page.evaluate(({ w, h }) => {
    const canvas = document.createElement('canvas')
    canvas.width = w
    canvas.height = h
    const g = canvas.getContext('2d')!
    const grad = g.createLinearGradient(0, 0, w, h)
    grad.addColorStop(0, '#1d4ed8')
    grad.addColorStop(1, '#f59e0b')
    g.fillStyle = grad
    g.fillRect(0, 0, w, h)
    g.fillStyle = '#ffffff'
    g.fillRect(w * 0.8, h * 0.2, w * 0.05, h * 0.05)
    return canvas.toDataURL('image/png').split(',')[1]
  }, { w, h })
  return Buffer.from(data, 'base64')
}

const banner = (page: Page) => page.getByTestId('memory-banner')
const tabRow = (page: Page) => page.locator('.memory-tabs .veyq-tabs__bar')
const bannerDialog = (page: Page) => page.getByRole('dialog', { name: /banner/i })

/**
 * Scroll the page 1,200 px down with the tab row's block tall enough to stick in. Three items fill one gallery row, so
 * the page scrolls about 18 px of its own and the row would never reach its sticky offset: the row's containing block
 * (`.memory-tabs`, the row's parent) is made three viewports tall for the read, and the returned function puts its
 * `min-height` back and scrolls to the top, so the next test meets the page as the product draws it.
 *
 * Through the CSSOM (`element.style`), never a `<style>` element: the instance's CSP has no `'unsafe-inline'` in
 * `style-src`, so an injected `<style>` (`page.addStyleTag`) is refused and the row never had a block to stick in; the
 * CSSOM is not governed by `style-src` (`src/web/src/shared/security/csp.ts`, which Vue's `:style` relies on too). The
 * scroll is asserted, so a page that did not move cannot pass as a row that stuck.
 */
async function scrollPastTheBanner(page: Page): Promise<() => Promise<void>> {
  const tabs = page.locator('.memory-tabs')
  const before = await tabs.evaluate((e: HTMLElement) => {
    const was = e.style.minHeight
    e.style.minHeight = '300vh'
    return was
  })
  await page.evaluate(() => window.scrollTo(0, 1200))
  expect(await page.evaluate(() => window.scrollY), 'the page scrolled past the banner').toBeGreaterThan(1000)
  return async () => {
    await page.evaluate(() => window.scrollTo(0, 0))
    await tabs.evaluate((e: HTMLElement, was: string) => void (e.style.minHeight = was), before)
  }
}

test.beforeAll(async ({ browser }) => {
  member = await newDevice(browser)
  guest = await newDevice(browser)
  pageConsole = watchConsole(member.page, guest.page)
})

test.afterEach(() => {
  member?.confinement.assertConfined()
  expect(pageConsole.errors, 'the console is free of errors').toEqual([])
})

test.afterAll(async () => {
  await member?.context.close()
  await guest?.context.close()
})

test('a Memory with a landscape photo, a portrait photo and a video', { tag: ['@uc.memory.banner:not-in-the-gallery'] }, async () => {
  const { page } = member
  await signInOwner(member)
  pageConsole.clear()
  await page.getByRole('button', { name: 'New', exact: true }).click()
  await page.getByRole('menuitem', { name: 'New Memory' }).click()
  const dialog = page.getByRole('dialog', { name: 'Create a Memory' })
  await dialog.getByLabel('Name').fill(recordTyped(memoryName))
  await dialog.getByRole('button', { name: 'Create', exact: true }).click()
  await expect(dialog).toBeHidden()
  await page.getByRole('button', { name: memoryName, exact: true }).click()
  await expect(page.getByRole('heading', { level: 1, name: memoryName })).toBeVisible()

  portrait = { name: recordTyped(`Turm ${stamp}.png`), mimeType: 'image/png', buffer: await drawnImage(page, 900, 1600) }
  const files = [
    { name: recordTyped(`Ufer ${stamp}.png`), mimeType: 'image/png', buffer: await drawnImage(page, 2400, 900) },
    portrait,
    { name: recordTyped(`Wellen ${stamp}.webm`), mimeType: 'video/webm', buffer: VIDEO },
  ]
  const chooser = page.waitForEvent('filechooser')
  await page.getByRole('button', { name: 'Upload', exact: true }).click()
  await (await chooser).setFiles(files)
  await expect(page.getByText('3 items', { exact: true })).toBeVisible({ timeout: 90_000 })
})

test(
  'an uploaded landscape image fills the frame around its point at a wide and a phone width, and the tab row sticks',
  { tag: ['@uc.memory.banner:frame-and-focal-point', '@uc.memory.banner:sealed-banner-in-node-meta'] },
  async () => {
    const { page } = member
    await page.setViewportSize({ width: 1400, height: 900 })
    await page.getByRole('button', { name: 'Add banner', exact: true }).click()
    const dialog = bannerDialog(page)
    const chooser = page.waitForEvent('filechooser')
    await dialog.getByRole('button', { name: 'Choose an image or a video' }).click()
    await (await chooser).setFiles({ name: recordTyped(`Steg ${stamp}.png`), mimeType: 'image/png', buffer: await drawnImage(page, 3000, 1000) })
    const stage = dialog.locator('.banner-dialog-stage')
    await expect(stage).toBeVisible()
    const box = (await stage.boundingBox())!
    await stage.click({ position: { x: box.width * 0.8, y: box.height * 0.2 } }) // the marker: the point that stays in view
    await expect(dialog.getByLabel('Description'), 'the banner has no description').toHaveCount(0)
    await dialog.getByRole('button', { name: 'Save banner' }).click()
    await expect(dialog).toBeHidden({ timeout: 60_000 })

    await expect(banner(page).locator('img.banner-frame-media')).toBeVisible({ timeout: 60_000 })
    await expect(banner(page).locator('img.banner-frame-media'), 'decorative: the title follows it').toHaveAttribute('alt', '')
    const style = await banner(page).locator('img.banner-frame-media').getAttribute('style')
    expect(style).toMatch(/object-position: 8\d(\.\d+)?% 2\d(\.\d+)?%/)
    const wide = (await banner(page).boundingBox())!
    expect(wide.width / wide.height, 'about 3:1 on a wide column').toBeCloseTo(3, 0)
    expect(wide.height).toBeLessThanOrEqual(900 * 0.35 + 1)

    // the frame on a phone: 16:9, the same stored point
    await page.setViewportSize({ width: 390, height: 844 })
    const phone = (await banner(page).boundingBox())!
    expect(phone.width / phone.height, '16:9 on a narrow column').toBeCloseTo(16 / 9, 1)
    expect(await banner(page).locator('img.banner-frame-media').getAttribute('style')).toBe(style)

    // the tab row sticks below the compact header while the banner scrolls away
    const back = await scrollPastTheBanner(page)
    const stuck = (await tabRow(page).boundingBox())!
    const header = (await page.locator('.app-compact-header').boundingBox())!
    expect(Math.abs(stuck.y - (header.y + header.height)), 'the tab row sits on the compact header').toBeLessThan(3)
    expect((await banner(page).boundingBox())!.y + phone.height, 'the banner scrolled away').toBeLessThan(header.y + header.height)
    await back()
    await page.setViewportSize({ width: 1400, height: 900 })
    const backWide = await scrollPastTheBanner(page)
    const stuckWide = (await tabRow(page).boundingBox())!
    const topbar = (await page.locator('.workspace-topbar').boundingBox())!
    expect(Math.abs(stuckWide.y - (topbar.y + topbar.height)), 'the tab row sits on the top bar').toBeLessThan(3)
    await backWide()
  },
)

/** The requests a save sends: any upload start, and the banner write's body. */
function watchSave(page: Page) {
  const uploads: string[] = []
  const writes: string[] = []
  const on = (r: Request) => {
    const path = new URL(r.url()).pathname
    if (r.method() === 'POST' && path.endsWith('/blob-uploads-batch')) uploads.push(r.postData() ?? '')
    if (r.method() === 'PUT' && path.endsWith('/banner')) writes.push(r.postData() ?? '')
  }
  page.on('request', on)
  return { uploads, writes, stop: () => page.off('request', on) }
}

test('the gallery\'s portrait photo, uploaded again, shares its preview, fits with blurred sides, and switches to fill', { tag: ['@uc.memory.banner:frame-and-focal-point', '@uc.memory.banner:upload-shares-an-equal-gallery-file'] }, async () => {
  const { page } = member
  await expect(page.getByRole('button', { name: 'Use as banner' }), 'the gallery offers no way in').toHaveCount(0)
  await page.getByRole('button', { name: 'Change banner', exact: true }).click()
  const dialog = bannerDialog(page)
  const chooser = page.waitForEvent('filechooser')
  await dialog.getByRole('button', { name: 'Choose an image or a video' }).click()
  await (await chooser).setFiles(portrait)
  await expect(dialog.getByRole('button', { name: 'Fit' })).toHaveAttribute('aria-pressed', 'true')
  const save = watchSave(page)
  await dialog.getByRole('button', { name: 'Save banner' }).click()
  await expect(dialog).toBeHidden({ timeout: 60_000 })
  save.stop()
  expect(save.uploads, 'nothing uploaded: the photo is already stored').toEqual([])
  expect(save.writes.join(' '), 'the banner is a row on the stored object').toContain('"shared"')
  await expect(banner(page).locator('.banner-frame-backdrop')).toHaveAttribute('aria-hidden', 'true')

  await page.getByRole('button', { name: 'Change banner', exact: true }).click()
  await bannerDialog(page).getByRole('button', { name: 'Fill' }).click()
  await bannerDialog(page).getByRole('button', { name: 'Save banner' }).click()
  await expect(bannerDialog(page)).toBeHidden({ timeout: 60_000 })
  await expect(banner(page).locator('.banner-frame-backdrop')).toHaveCount(0)
})

test(
  'the gallery\'s video, uploaded again, shares its stored object: its poster, nothing played before the click, then the banner\'s own row',
  { tag: ['@uc.memory.banner:video-poster-no-autoplay', '@uc.memory.banner:upload-shares-an-equal-gallery-file'] },
  async () => {
    const { page } = member
    // the original's blob id, as the gallery's listing names it
    const listing = page.waitForResponse((r) => /\/nodes\/[^/]+\/media$/.test(new URL(r.url()).pathname) && r.status() === 200)
    await page.goto(tenantOrigin())
    await page.getByRole('button', { name: memoryName, exact: true }).click({ timeout: 60_000 })
    const media = ((await (await listing).json()) as { media: { blobId: string; derivatives: unknown[] }[] }).media
    const originals = new Set(media.map((m) => m.blobId))

    await page.getByRole('button', { name: 'Change banner', exact: true }).click({ timeout: 60_000 })
    const chooser = page.waitForEvent('filechooser')
    await bannerDialog(page).getByRole('button', { name: 'Choose an image or a video' }).click()
    await (await chooser).setFiles({ name: `Wellen ${stamp}.webm`, mimeType: 'video/webm', buffer: VIDEO })
    const save = watchSave(page)
    await bannerDialog(page).getByRole('button', { name: 'Save banner' }).click()
    await expect(bannerDialog(page)).toBeHidden({ timeout: 90_000 })
    save.stop()
    expect(save.uploads, 'nothing uploaded: the video and its poster are already stored').toEqual([])
    expect(save.writes.join(' ')).toContain('"posterShared"')

    await page.goto(tenantOrigin())
    const presigns: string[] = []
    page.on('request', (r: Request) => {
      if (r.method() === 'POST' && new URL(r.url()).pathname.endsWith('/blob-downloads-batch')) presigns.push(r.postData() ?? '')
    })
    await page.getByRole('button', { name: memoryName, exact: true }).click({ timeout: 60_000 })
    const play = banner(page).getByRole('button', { name: 'Play the banner video' })
    await expect(play).toBeVisible({ timeout: 60_000 })
    await expect(banner(page).locator('video'), 'no video before the click').toHaveCount(0)
    await page.waitForTimeout(2_000)
    expect(await page.evaluate(() => [...document.querySelectorAll('video')].some((v) => !v.paused)), 'nothing plays on its own').toBe(false)

    const before = presigns.length
    await play.click()
    const v = banner(page).locator('video')
    await expect.poll(() => v.evaluate((e: HTMLVideoElement) => e.currentTime), { timeout: 60_000 }).toBeGreaterThan(0.5)
    expect(await v.evaluate((e: HTMLVideoElement) => new URL(e.currentSrc).pathname)).toMatch(/^\/__media\//)
    const asked = presigns.slice(before).join(' ')
    expect(asked.length, 'the play presigned the banner\'s chunks').toBeGreaterThan(0)
    for (const id of originals) expect(asked, 'never a gallery original').not.toContain(id)
  },
)

test(
  'the same Memory through a READ link: the banner is there, no action, and the tab row sticks at the top',
  { tag: ['@uc.memory.banner:members-only-write-guests-see'] },
  async () => {
    const { page } = member
    await page.getByRole('button', { name: 'Share', exact: true }).click()
    const manage = page.getByRole('dialog', { name: 'Share this Memory' })
    await manage.getByRole('button', { name: 'New share' }).click()
    const createShare = page.getByRole('dialog', { name: 'New share' })
    await createShare.getByRole('button', { name: 'Create link' }).click()
    shareLink = await createShare.getByRole('textbox', { name: 'Public link' }).inputValue()
    await createShare.getByRole('button', { name: 'Done' }).click()
    // Done goes back to the Memory's list of links, a modal of its own: closed, so the next test reaches the header.
    await expect(manage).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(manage).toBeHidden()

    const g = guest.page
    await g.goto(shareLink)
    await expect(g.getByRole('heading', { level: 1, name: memoryName })).toBeVisible({ timeout: 60_000 })
    await expect(banner(g).getByRole('button', { name: 'Play the banner video' })).toBeVisible({ timeout: 60_000 })
    await expect(g.getByRole('button', { name: /banner/i }).filter({ hasNotText: 'Play' })).toHaveCount(0)
    const back = await scrollPastTheBanner(g)
    expect(Math.round((await tabRow(g).boundingBox())!.y), 'stuck at the top of a page without a top bar').toBe(0)
    await back()
  },
)

test('the banner is removed', { tag: ['@uc.memory.banner:one-write-for-slots-and-meta'] }, async () => {
  const { page } = member
  await page.getByRole('button', { name: 'Change banner', exact: true }).click()
  await bannerDialog(page).getByRole('button', { name: 'Remove banner' }).click()
  await expect(bannerDialog(page)).toBeHidden({ timeout: 60_000 })
  await expect(banner(page)).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Add banner', exact: true })).toBeVisible()
})
