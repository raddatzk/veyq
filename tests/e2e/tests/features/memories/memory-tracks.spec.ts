// A Memory's GPX tracks in a real browser (uc.memory.tracks, ADR 0130): a track is a medium whose original is its GPX file,
// listed on the Memory's Map tab and never in its gallery.
//
// The owner creates a Memory with one photo, then:
//  - adds a GPX file on the Map tab: the upload is a medium of the Memory (one more original on the wire), the gallery still
//    counts one item, and the track is listed by its own name with its distance - with the map services off (their default),
//    so the tab offers them and asks no tile service anything;
//  - refuses a file that is no GPX track, naming it;
//  - names the track and gives it an activity through its Edit (ADR 0130 decision 9): the row shows both;
//  - opens the same Memory through a READ link: the Map tab lists the track by that name with its activity, with no add,
//    no Edit and no removal;
//  - removes the track: it leaves the list and stands in the media trash.
// The map itself (tiles from a third party) is not drawn here: the lane's stack reaches no tile service, and the consent's
// states are the unit tests' (`MemoryTracks.test.ts`). The console is read for errors throughout. Every value typed is a
// canary (recordTyped). Labels are src/web/'s own (the Memories catalog, en).
import { expect, type Page } from '@playwright/test'
import { test } from '../../../src/cspViolations'
import { randomBytes } from 'node:crypto'
import { recordTyped } from '../../../src/canaries'
import { newDevice, signInOwner, watchConsole, type ConsoleErrors, type Device } from '../../../src/devices'
import { headerAction } from '../../../src/header'

test.describe.configure({ mode: 'serial' })

const stamp = randomBytes(3).toString('hex')
const memoryName = `Bergtour ${stamp}`
const trackName = `Gipfelweg ${stamp}`
const editedName = `Kammweg ${stamp}`
const gpx = (name: string) => Buffer.from(
  `<?xml version="1.0" encoding="UTF-8"?><gpx version="1.1" creator="e2e" xmlns="http://www.topografix.com/GPX/1/1">` +
  `<trk><name>${name}</name><trkseg>` +
  `<trkpt lat="47.4210" lon="10.9850"><ele>1000</ele><time>2026-07-01T08:00:00Z</time></trkpt>` +
  `<trkpt lat="47.4300" lon="10.9950"><ele>1200</ele><time>2026-07-01T09:00:00Z</time></trkpt>` +
  `<trkpt lat="47.4400" lon="11.0050"><ele>1400</ele><time>2026-07-01T10:30:00Z</time></trkpt>` +
  `</trkseg></trk></gpx>`,
)

let member: Device
let guest: Device
let shareLink = ''
let pageConsole: ConsoleErrors

const mapTab = (page: Page) => page.getByRole('tab', { name: /^Map/ })
const trackRows = (page: Page) => page.getByTestId('track-row')

/** A small PNG drawn on a canvas in the page, so the Memory holds one photo. */
async function drawnImage(page: Page): Promise<Buffer> {
  const data = await page.evaluate(() => {
    const canvas = document.createElement('canvas')
    canvas.width = 640
    canvas.height = 480
    const g = canvas.getContext('2d')!
    g.fillStyle = '#0f9d6e'
    g.fillRect(0, 0, 640, 480)
    return canvas.toDataURL('image/png').split(',')[1]
  })
  return Buffer.from(data, 'base64')
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

test('a Memory with one photo', { tag: ['@uc.memory.tracks:no-photo'] }, async () => {
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
  const chooser = page.waitForEvent('filechooser')
  await page.getByRole('button', { name: 'Upload', exact: true }).click()
  await (await chooser).setFiles({ name: recordTyped(`Gipfel ${stamp}.png`), mimeType: 'image/png', buffer: await drawnImage(page) })
  await expect(page.getByText('1 item', { exact: true })).toBeVisible({ timeout: 90_000 })
})

test(
  'a GPX file added on the Map tab is a medium of the Memory, listed by its name with its numbers, and no gallery tile',
  { tag: ['@uc.memory.tracks:a-track-is-a-medium', '@uc.memory.tracks:map-tab-lists-and-draws', '@uc.memory.tracks:the-one-map-consent', '@uc.memory.tracks:no-photo'] },
  async () => {
    const { page } = member
    await mapTab(page).click()
    // the map services are off by default: the tab offers them, and no tile is asked for
    await expect(page.getByRole('button', { name: 'Turn map services on' })).toBeVisible()
    await expect(page.locator('.leaflet-container')).toHaveCount(0)

    const completes: string[] = []
    page.on('request', (r) => {
      if (r.method() === 'POST' && new URL(r.url()).pathname.endsWith('/media-batch')) completes.push(r.url())
    })
    const chooser = page.waitForEvent('filechooser')
    await page.getByTestId('track-add').click()
    await (await chooser).setFiles({ name: recordTyped(`tour-${stamp}.gpx`), mimeType: 'application/gpx+xml', buffer: gpx(recordTyped(trackName)) })
    await expect(trackRows(page)).toHaveCount(1, { timeout: 90_000 })
    await expect(trackRows(page).first()).toContainText(trackName)
    await expect(trackRows(page).first()).toContainText('km', { timeout: 30_000 })
    expect(completes.length, 'the track was completed as a medium').toBeGreaterThan(0)

    await page.getByRole('tab', { name: 'Gallery' }).click()
    await expect(page.getByText('1 item', { exact: true }), 'the gallery still counts the photo alone').toBeVisible()
  },
)

test('a file that is no GPX track is refused by name, nothing uploaded', { tag: ['@uc.memory.tracks:parsed-on-the-device-within-bounds'] }, async () => {
  const { page } = member
  await mapTab(page).click()
  const chooser = page.waitForEvent('filechooser')
  await page.getByTestId('track-add').click()
  const name = recordTyped(`notes-${stamp}.gpx`)
  await (await chooser).setFiles({ name, mimeType: 'application/gpx+xml', buffer: Buffer.from('<html><body>no track</body></html>') })
  await expect(page.getByText(`${name} is no GPX track.`)).toBeVisible()
  await expect(trackRows(page)).toHaveCount(1)
})

test('the track is named and given an activity through its Edit', { tag: ['@uc.memory.tracks:name-and-activity'] }, async () => {
  const { page } = member
  await mapTab(page).click()
  await page.getByTestId('track-more').click()
  await page.getByRole('menuitem', { name: 'Edit' }).click()
  const dialog = page.getByRole('dialog', { name: 'Edit track' })
  await expect(dialog.getByTestId('track-edit-name')).toHaveAttribute('placeholder', trackName)
  await dialog.getByTestId('track-edit-name').fill(recordTyped(editedName))
  await dialog.getByTestId('track-edit-activity').click()
  await page.getByRole('option', { name: 'Cycling', exact: true }).click()
  await dialog.getByTestId('track-edit-save').click()
  await expect(dialog).toBeHidden()
  await expect(trackRows(page).first().locator('.memory-track-name')).toHaveText(editedName)
  await expect(trackRows(page).first().getByTestId('track-activity')).toHaveText('Cycling')
})

test('the same Memory through a READ link lists the track, with no add and no removal', { tag: ['@uc.memory.tracks:a-mediums-rights-and-lifecycle', '@uc.memory.tracks:name-and-activity'] }, async () => {
  const { page } = member
  await headerAction(page, 'Share')
  const manage = page.getByRole('dialog', { name: 'Share this Memory' })
  await manage.getByRole('button', { name: 'New share' }).click()
  const createShare = page.getByRole('dialog', { name: 'New share' })
  await createShare.getByRole('button', { name: 'Create link' }).click()
  shareLink = await createShare.getByRole('textbox', { name: 'Public link' }).inputValue()
  await createShare.getByRole('button', { name: 'Done' }).click()
  await expect(manage).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(manage).toBeHidden()

  const g = guest.page
  await g.goto(shareLink)
  await expect(g.getByRole('heading', { level: 1, name: memoryName })).toBeVisible({ timeout: 60_000 })
  await mapTab(g).click()
  await expect(trackRows(g)).toHaveCount(1, { timeout: 60_000 })
  await expect(trackRows(g).first().locator('.memory-track-name')).toHaveText(editedName)
  await expect(trackRows(g).first().getByTestId('track-activity')).toHaveText('Cycling')
  await expect(g.getByTestId('track-add')).toHaveCount(0)
  // a READ guest may download: the row's menu holds that and nothing that writes
  await g.getByTestId('track-more').click()
  await expect(g.getByRole('menuitem', { name: 'Download GPX' })).toBeVisible()
  await expect(g.getByRole('menuitem', { name: 'Remove' })).toHaveCount(0)
  await expect(g.getByRole('menuitem', { name: 'Edit' })).toHaveCount(0)
  await g.keyboard.press('Escape')
})

test('removing the track moves it to the media trash', { tag: ['@uc.memory.tracks:a-mediums-rights-and-lifecycle'] }, async () => {
  const { page } = member
  await mapTab(page).click()
  await page.getByTestId('track-more').click()
  await page.getByRole('menuitem', { name: 'Remove' }).click()
  await expect(trackRows(page)).toHaveCount(0, { timeout: 30_000 })
  await page.getByRole('tab', { name: 'Trash' }).click()
  await expect(page.locator('.memory-trash-tile')).toHaveCount(1, { timeout: 60_000 })
})
