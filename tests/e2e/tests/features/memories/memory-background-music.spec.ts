// A Memory's background music in a real browser (uc.memory.background-music, ADR 0135): two audio files added on the
// Memory's Music tab are media of the Memory - no gallery tile - and core's player plays them shuffled, crossfading, in the
// bar below the footer.
//
// The owner creates a Memory, then:
//  - adds two short generated tones (WAV) on the Music tab: each is listed by its file name without the extension, with
//    its length, and the gallery holds none of them;
//  - presses the header's Play music: the bar shows a track's title, and once that track reached its trimmed end the bar
//    shows the other one - the crossfade's hand-over, which the order guarantees (a round never starts with the track that
//    ended the one before);
//  - closes the bar: the music ends and the header offers Play music again.
// What the ear hears is the unit tests' (`core/audio/player.test.ts`) and the CR's browser pass's (an AnalyserNode read
// non-zero samples); this lane proves the path: upload, listing, the stream through the service worker, the hand-over.
// The console is read for errors throughout. Every value typed is a canary (recordTyped). Labels are src/web/'s own (en).
import { expect, type Page } from '@playwright/test'
import { test } from '../../../src/cspViolations'
import { randomBytes } from 'node:crypto'
import { recordTyped } from '../../../src/canaries'
import { newDevice, signInOwner, watchConsole, type ConsoleErrors, type Device } from '../../../src/devices'
import { headerAction } from '../../../src/header'

test.describe.configure({ mode: 'serial' })

const stamp = randomBytes(3).toString('hex')
const memoryName = `Sommerfest ${stamp}`
const titles = [`Morgenlied ${stamp}`, `Abendlied ${stamp}`]

/** A WAV of `seconds` of a tone at `hz`, with a quarter second of silence before and after. */
function tone(hz: number, seconds = 3): Buffer {
  const rate = 8000
  const pad = rate / 4
  const frames = Math.round(seconds * rate) + 2 * pad
  const out = Buffer.alloc(44 + frames * 2)
  out.write('RIFF', 0); out.writeUInt32LE(36 + frames * 2, 4); out.write('WAVE', 8); out.write('fmt ', 12)
  out.writeUInt32LE(16, 16); out.writeUInt16LE(1, 20); out.writeUInt16LE(1, 22); out.writeUInt32LE(rate, 24)
  out.writeUInt32LE(rate * 2, 28); out.writeUInt16LE(2, 32); out.writeUInt16LE(16, 34); out.write('data', 36); out.writeUInt32LE(frames * 2, 40)
  for (let i = pad; i < frames - pad; i++) out.writeInt16LE(Math.round(Math.sin((2 * Math.PI * hz * i) / rate) * 12000), 44 + i * 2)
  return out
}

let member: Device
let pageConsole: ConsoleErrors

const musicTab = (page: Page) => page.getByRole('tab', { name: /^Music/ })
const barTitle = (page: Page) => page.getByTestId('audio-title')

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

test(
  'two audio files added on the Music tab are listed with their titles and lengths, and are no gallery tile',
  { tag: ['@uc.memory.background-music:a-track-is-a-medium', '@uc.memory.background-music:facts-sealed-at-birth', '@uc.memory.background-music:who-adds-who-listens'] },
  async () => {
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

    // the Music tab shows where music may be added, before there is any
    await musicTab(page).click()
    const chooser = page.waitForEvent('filechooser')
    await page.getByTestId('music-add').click()
    await (await chooser).setFiles(titles.map((title, i) => ({ name: `${recordTyped(title)}.wav`, mimeType: 'audio/wav', buffer: tone(440 + 220 * i) })))
    await expect(page.getByTestId('music-row')).toHaveCount(2, { timeout: 90_000 })
    await expect(page.getByTestId('music-title')).toHaveText([...titles].sort())
    await expect(page.getByTestId('music-length')).toHaveText(['0:04', '0:04'])

    await page.getByRole('tab', { name: 'Gallery' }).click()
    await expect(page.locator('.media-tile')).toHaveCount(0)
    await expect(page.getByText('No items', { exact: true })).toBeVisible()
  },
)

test(
  'the header\'s Play music starts the music in the bar, and one track hands over to the other at its end',
  { tag: ['@uc.memory.background-music:player-in-core-plays-across-pages', '@uc.memory.background-music:shuffled-endless-order', '@uc.memory.background-music:crossfade-at-trimmed-edges'] },
  async () => {
    const { page } = member
    await headerAction(page, 'Play music')
    await expect(barTitle(page)).toBeVisible({ timeout: 30_000 })
    const first = (await barTitle(page).textContent())!.split(' · ')[0]
    expect(titles).toContain(first)
    const other = titles.find((t) => t !== first)!
    await expect(barTitle(page)).toContainText(other, { timeout: 30_000 })

    await page.getByTestId('audio-close').click()
    await expect(page.getByTestId('audio-bar')).toBeHidden()
    await expect(page.locator('.veyq-node-header-action[data-action="music"]')).toHaveAttribute('aria-label', 'Play music')
  },
)
