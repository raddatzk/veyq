// A Memory's background music in a real browser (uc.memory.music, ADR 0131), on the lane's instance, whose deployment
// sets no music host: the playlists are stored and opened in the service's own app, and nothing plays in veyq. The music
// ships dark (ADR 0131 decision 8): the lane's overlay switches the TEMPORARY release flag VEYQ_MUSIC_RELEASED on first
// (tests/e2e/stack/docker-compose.e2e.yml, `app`), or none of this is drawn.
//
// The owner creates a Memory and, through the ⋯ menu's Background music:
//  - pastes an album link into the Spotify field: refused in the dialog, Save disabled (E1);
//  - pastes a Spotify playlist and an Apple Music playlist: the Memory's header offers "Open in Spotify" and "Open in
//    Apple Music" (the server does not play them here, A3) - never clicked here, as the link leaves the instance -, no
//    music bar below the footer, no iframe, and no request leaves the instance (the device's confinement: no music host,
//    no Spotify, no Apple);
//  - reloads: the sealed playlists open again from the node's meta;
//  - empties the Spotify field: only Apple Music is left (A5).
// The console is read for errors throughout. Every value typed is a canary (recordTyped). Labels are src/web/'s own (en).
import { expect, type Page } from '@playwright/test'
import { test } from '../../../src/cspViolations'
import { randomBytes } from 'node:crypto'
import { recordTyped } from '../../../src/canaries'
import { newDevice, signInOwner, watchConsole, type ConsoleErrors, type Device } from '../../../src/devices'
import { expectHeaderAction, headerAction } from '../../../src/header'

test.describe.configure({ mode: 'serial' })

const stamp = randomBytes(3).toString('hex')
const memoryName = `Roadtrip ${stamp}`
const SPOTIFY = 'https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M'
const APPLE = 'https://music.apple.com/de/playlist/sommer/pl.u-AbC123dEf456'

let member: Device
let pageConsole: ConsoleErrors

const bar = (page: Page) => page.getByTestId('music-bar')
const headerMusic = (page: Page, service: 'spotify' | 'apple-music') => page.locator(`[data-action="music-${service}"]`)
const musicDialog = (page: Page) => page.getByRole('dialog', { name: 'Background music' })

test.beforeAll(async ({ browser }) => {
  member = await newDevice(browser)
  pageConsole = watchConsole(member.page)
})

test.afterEach(() => {
  member?.confinement.assertConfined()
  expect(pageConsole.errors, 'the console is free of errors').toEqual([])
})

test.afterAll(async () => {
  await member?.context.close()
})

test('a link that is no playlist is refused in the dialog', { tag: ['@uc.memory.music:sealed-playlists-in-node-meta'] }, async () => {
  const { page } = member
  await signInOwner(member)
  pageConsole.clear()
  await page.getByRole('button', { name: 'New', exact: true }).click()
  await page.getByRole('menuitem', { name: 'New Memory' }).click()
  const create = page.getByRole('dialog', { name: 'Create a Memory' })
  await create.getByLabel('Name').fill(recordTyped(memoryName))
  await create.getByRole('button', { name: 'Create', exact: true }).click()
  await expect(create).toBeHidden()
  await page.getByRole('button', { name: memoryName, exact: true }).click()
  await expect(page.getByRole('heading', { level: 1, name: memoryName })).toBeVisible()
  await expect(headerMusic(page, 'spotify'), 'no playlist, no music button').toHaveCount(0)
  await expect(bar(page), 'no playlist, no bar').toHaveCount(0)

  await headerAction(page, 'Background music')
  const dialog = musicDialog(page)
  await dialog.getByLabel('Spotify playlist').fill('https://open.spotify.com/album/37i9dQZF1DXcBWIGoYBM5M')
  await expect(dialog.getByText('This is not a playlist link of this service.')).toBeVisible()
  await expect(dialog.getByRole('button', { name: 'Save', exact: true })).toBeDisabled()
  await dialog.getByRole('button', { name: 'Cancel', exact: true }).click()
  await expect(dialog).toBeHidden()
})

test('both playlists are stored and offered as links only where the server plays none here',
  { tag: ['@uc.memory.music:sealed-playlists-in-node-meta', '@uc.memory.music:opt-in-per-browser-and-service'] }, async () => {
    const { page } = member
    await headerAction(page, 'Background music')
    const dialog = musicDialog(page)
    await dialog.getByLabel('Spotify playlist').fill(SPOTIFY)
    await dialog.getByLabel('Apple Music playlist').fill(APPLE)
    await dialog.getByRole('button', { name: 'Save', exact: true }).click()
    await expect(dialog).toBeHidden()

    await expectHeaderAction(page, 'Open in Spotify')
    await expectHeaderAction(page, 'Open in Apple Music')
    await expect(page.getByRole('button', { name: 'Play the Spotify playlist' }), 'the lane sets no music host: nothing plays here').toHaveCount(0)
    await expect(bar(page), 'no music bar below the footer').toHaveCount(0)
    await expect(page.locator('iframe'), 'no frame of any origin').toHaveCount(0)

    await page.reload()
    await expectHeaderAction(page, 'Open in Spotify') // the sealed playlists open again
  })

test('an emptied field drops its playlist', { tag: ['@uc.memory.music:sealed-playlists-in-node-meta'] }, async () => {
  const { page } = member
  await headerAction(page, 'Background music')
  const dialog = musicDialog(page)
  await expect(dialog.getByLabel('Spotify playlist')).toHaveValue(SPOTIFY)
  await dialog.getByLabel('Spotify playlist').fill('')
  await dialog.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(dialog).toBeHidden()
  await expectHeaderAction(page, 'Open in Apple Music')
  await expect(headerMusic(page, 'spotify')).toHaveCount(0)
})
