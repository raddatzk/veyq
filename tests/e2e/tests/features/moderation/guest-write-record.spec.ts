// The guest-write record through the release's own Traefik (ADR 0093 § 10, ADR 0094 § For the moderation CR). The record has no switch, so a link guests may add
// through records with the default N = 7. The tenant owner shares a folder with a link guests may add through, and
// signs out of the browser; then a guest opens the link and creates a folder at its first write.
//
//   1. Before that first write, the naming dialog says what is kept: the IP address, the source port and the time, for
//      the 7 days the resolve served, with the right to object as a sentence of its own.
//   2. Right after the write, the spec reads its OWN container's TCP table (`/proc/net/tcp`: Chromium runs in this
//      container, so its sockets are listed here) for the established connections to Traefik's :443 — the only browser
//      context left open is the guest's — and writes them, with the created folder's id, to `guest-write-record.json`
//      in the run directory. `scripts/run_stack_e2e.py`'s `guest-write record` stage then reads the app's record off
//      the instance's database and holds it to them: the recorded address is the browser's own, and the recorded port
//      is the port of one of the browser's own sockets — the one Traefik accepted. The lane's `client port` stage reads
//      an echo; this reads the app's record.
import { test, expect, type Request } from '@playwright/test'
import { randomBytes } from 'node:crypto'
import { lookup } from 'node:dns/promises'
import { readFileSync, writeFileSync } from 'node:fs'
import { recordTyped } from '../../../src/canaries'
import { newDevice, signInOwner, type Device } from '../../../src/devices'
import { stack } from '../../../src/stack'
import { headerAction } from '../../../src/header'

test.describe.configure({ mode: 'serial' })

const stamp = randomBytes(3).toString('hex')
const sharedFolder = `Fotos vom Vereinsfest ${stamp}`
const guestFolder = `Beiträge von Gästen ${stamp}`
const guestName = `Onkel Bertram ${stamp}`
const shareLabel = `Für die Gäste ${stamp}`

let member: Device
let guest: Device
let shareLink: string

/** One established IPv4 connection of this container: its local address and port, and the remote ones. */
interface Socket { localIp: string; localPort: number; remoteIp: string; remotePort: number }

/** `/proc/net/tcp`'s address field: a little-endian IPv4 in hex, a colon, a big-endian port in hex. */
function address(field: string): [string, number] {
  const [hex, port] = field.split(':')
  const ip = [3, 2, 1, 0].map((i) => parseInt(hex.slice(i * 2, i * 2 + 2), 16)).join('.')
  return [ip, parseInt(port, 16)]
}

/** The container's ESTABLISHED (state 01) IPv4 connections. */
function established(): Socket[] {
  return readFileSync('/proc/net/tcp', 'utf8').split('\n').slice(1).map((l) => l.trim().split(/\s+/))
    .filter((f) => f.length > 3 && f[3] === '01')
    .map((f) => {
      const [localIp, localPort] = address(f[1])
      const [remoteIp, remotePort] = address(f[2])
      return { localIp, localPort, remoteIp, remotePort }
    })
}

test.beforeAll(async ({ browser }) => {
  member = await newDevice(browser)
  guest = await newDevice(browser)
})

test.afterEach(() => {
  for (const device of [member, guest]) device?.confinement.assertConfined()
})

test.afterAll(async () => {
  for (const device of [member, guest]) await device?.context.close().catch(() => undefined)
})

test(
  'the owner shares a folder with a link guests may add through',
  { tag: '@uc.sharing.share-contributor:create-contributor-share' },
  async () => {
    const { page } = member
    await signInOwner(member)
    await page.getByRole('button', { name: 'New', exact: true }).click()
    await page.getByRole('menuitem', { name: 'New folder' }).click()
    const dialog = page.getByRole('dialog', { name: 'Create a folder' })
    await dialog.getByLabel('Name').fill(recordTyped(sharedFolder))
    await dialog.getByRole('button', { name: 'Create', exact: true }).click()
    await expect(dialog).toBeHidden()
    await page.getByRole('button', { name: sharedFolder, exact: true }).click()
    await expect(page.getByRole('heading', { name: sharedFolder })).toBeVisible()

    await headerAction(page, 'Share')
    const share = page.getByRole('dialog', { name: 'Share this folder' })
    await share.getByRole('button', { name: 'New share' }).click()
    const createShare = page.getByRole('dialog', { name: 'New share' })
    await createShare.getByLabel('Name').fill(recordTyped(shareLabel))
    await createShare.getByRole('button', { name: 'View + contribute' }).click()
    await createShare.getByRole('button', { name: 'Create link' }).click()
    const linkView = page.getByRole('dialog', { name: 'Share this folder' })
    shareLink = await linkView.getByRole('textbox', { name: 'Public link' }).inputValue()
    await linkView.getByRole('button', { name: 'Done' }).click()
    // Only the guest's browser context stays open, so every socket to Traefik below is the guest's.
    await member.context.close()
  },
)

test(
  'a guest is told before its first write, and the write records the socket Traefik accepted',
  {
    tag: [
      '@uc.sharing.share-contributor:a-writing-guest-is-told-before-its-first-write',
      '@uc.sharing.share-contributor:a-guests-write-records-the-address-the-network-vouches-for',
    ],
  },
  async () => {
    const { page } = guest
    await page.goto(shareLink)
    await expect(page.getByText('Shared with you · you can add and change things here')).toBeVisible({ timeout: 60_000 })

    await page.getByRole('button', { name: 'New', exact: true }).click()
    await page.getByRole('menuitem', { name: 'New folder' }).click()
    const naming = page.getByRole('dialog', { name: 'What is your name?' })
    await expect(naming.getByText(
      'When you add something here, your IP address, your source port and the time are kept for 7 days, longer only if a report or an authority’s order concerns what you added.',
    )).toBeVisible()
    await expect(naming.getByText('You can object to this at any time on grounds relating to your particular situation', { exact: false })).toBeVisible()
    await naming.getByLabel('Your name').fill(recordTyped(guestName))
    await naming.getByRole('button', { name: 'Continue' }).click()

    const create = page.getByRole('dialog', { name: 'Create a folder' })
    await create.getByLabel('Name').fill(recordTyped(guestFolder))
    const sent = page.waitForRequest((r: Request) =>
      r.method() === 'POST' && /\/api\/shares\/[^/]+\/nodes$/.test(new URL(r.url()).pathname))
    const answered = page.waitForResponse((r) =>
      r.request().method() === 'POST' && /\/api\/shares\/[^/]+\/nodes$/.test(new URL(r.url()).pathname))
    await create.getByRole('button', { name: 'Create', exact: true }).click()
    const nodeId = ((await sent).postDataJSON() as { nodeId: string }).nodeId
    expect((await answered).status(), 'the guest create stood').toBe(201)

    // The sockets the write went over: this container's established connections to Traefik's :443, read while the
    // page's connection is still open (keep-alive).
    const traefik = (await lookup(stack().traefikHost, { family: 4 })).address
    const sockets = established().filter((s) => s.remoteIp === traefik && s.remotePort === 443)
    expect(sockets.length, `the browser holds a connection to Traefik (${traefik}:443)`).toBeGreaterThan(0)
    writeFileSync(`${stack().outDir}/guest-write-record.json`, JSON.stringify({ objectId: nodeId, traefik, sockets }, null, 2), 'utf8')
    await expect(page.getByRole('button', { name: guestFolder, exact: true })).toBeVisible()
  },
)
