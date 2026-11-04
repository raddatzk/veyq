// The clicks inbox #372 still lists for Depot and the level view, in Chromium (lane L1) — what depot-files.spec.ts,
// depot-live.spec.ts and depot-styles.spec.ts do not reach. Written as lane L1's spec and NOT YET RUN: the session that
// wrote it ran no stack; it waits for the V1 heavy stage.
//
//  1. The per-file messages beside the link-full one (that one is guest-take-back.spec.ts's): a picked name a folder of
//     the level holds is not sent and the notice names the folder; a store that cannot be reached fails the upload after
//     its one automatic retry, the notice says so and the top bar's Retry delivers it; a file a second device replaced
//     after this one read it ends on `409 stale_file`, and "Replace anyway" replaces it. Not provoked here: a member's
//     `quota_exceeded` (needs the tenant's quota filled), `illegal_structure` (no screen places a file where the server
//     refuses one), the `slug_taken` race backstop, and the folder upload's three refusals.
//  2. Focus, keyboard and tab order across the level's parts: Tab runs toolbar → folder header → application tabs (one
//     stop) → the rows, each row's primary button first and its "More actions" last; Shift+Tab walks back; the arrow keys
//     move between the tabs; Enter opens a folder; Escape closes a menu and focus returns to its opener.
//  3. `FolderNameDialog`'s Escape: the dialog closes, nothing is created, focus returns to the "New" button.
//  4. The level's fallback rows: a file whose content has not arrived ("Not uploaded yet", its download disabled), and a
//     node of a kind this client does not know — the explicit unsupported row, not a button. No client this build ships
//     writes such a kind, so the page's own `fetch` answer is relabelled for one folder (an init script, below): the state
//     a newer client's node puts an older client in.
//  5. The top bar's status menu with Depot's batches: the badge, one list per destination under the folder's name, each
//     file's status, Escape, the list's Close.
//  6. The level's core `veyq-*` rules that Depot's rows and the folder header wear (the successors of the rules
//     cr-files-application moved out of `memory.css`), computed in light and dark. memories-styles.spec.ts reads
//     Memories' sheet and depot-styles.spec.ts the PDF text layer; neither reads these. A token's value is never spelled
//     here: a probe element given the same declaration gives the expected value.
//  7. A drop-box guest's page on reload: before the first file the name is not kept (a reload asks again); after it, the
//     stored session (localStorage) brings the page back as that guest, "Not you?" offered, and the next file lands in
//     the same folder — a name sent before the reload is refused there ("you already sent a file with this name"). A
//     switched-off link refuses the next file with its own message, and keeps what arrived.
//
// Left out, after the V1 queue (inbox #372): a real operating-system drag and drop (no drag source exists in a headless
// browser; depot-files.spec.ts dispatches the DOM drop), and Firefox/Safari.
//
// The store is held or cut off by routing its presigned `PUT`s (`X-Amz-Signature`) in the page, as depot-download.spec.ts
// cuts its `GET`s; a device's live poll is cut off by its `If-None-Match` (only the poll sends one, `treeStore.pollTree`;
// a route turns the browser's cache off, so the browser adds none of its own). Each such route is switched off, never
// removed (`switchableRoute`, below), because removing one races the confinement's own route.
//
// It starts from the tenant whose owner the round trip activated (the `depot` project) and switches nothing of the
// instance; what it creates is its own folder. Labels are src/web/'s own (src/web/src/i18n/index.ts and
// src/web/src/features/depot/i18n.ts, en).
import { test, expect, type Locator, type Page, type Request, type Route } from '@playwright/test'
import { randomBytes } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { recordTyped } from '../../../src/canaries'
import { createDropBoxLink, createFolder, downloadButton, pickFiles, sha256 } from '../../../src/depot'
import { newDevice, signInOwner, tenantOrigin, type Device } from '../../../src/devices'

test.describe.configure({ mode: 'serial' })

const stamp = randomBytes(3).toString('hex')
const folder = `Haushaltsunterlagen ${stamp}`
// A folder and a file name that fold onto one slug: the folder's `slugify` gives `quittungen-vom-umzug-<stamp>`, which
// is the file name's own `fileNameSlug` (lower case, kept as it is).
const blockingFolder = `Quittungen vom Umzug ${stamp}`
const blockedFile = `quittungen-vom-umzug-${stamp}`
const laterFolder = `Archiv für später ${stamp}`
const LATER_KIND = 'E2E_LATER_KIND'
const escapedFolder = `Nie angelegter Ordner ${stamp}`
const trayFiles = [
  { name: `Haushaltsbuch ${stamp}.bin`, bytes: new Uint8Array(randomBytes(90_000)) },
  { name: `Stromzähler Ablesung ${stamp}.bin`, bytes: new Uint8Array(randomBytes(95_000)) },
]
const failing = { name: `Kaputter Upload ${stamp}.bin`, bytes: new Uint8Array(randomBytes(80_000)) }
const slow = { name: `Langsame Übertragung ${stamp}.bin`, bytes: new Uint8Array(randomBytes(70_000)) }
// The replace race: the first device's content is replaced by the second's, then the first replaces anyway.
const peerContent = new Uint8Array(randomBytes(111_111))
const ownContent = new Uint8Array(randomBytes(99_000))
const dropBox = `Briefkasten für Belege ${stamp}`
const dropper = `Steuerberaterin Waltraud ${stamp}`
const dropped = [`Erster Beleg ${stamp}.bin`, `Zweiter Beleg ${stamp}.bin`, `Dritter Beleg ${stamp}.bin`]
// The level poll ticks every 4–6 s (useLevelLive.ts LIVE_POLL); a few ticks and the read are well inside this.
const LIVE = 30_000
const SESSION_PREFIX = 'veyq:share-contributor:'
// The init script's switch (sessionStorage: it survives a reload of this tab and nothing else).
const LATER_FLAG = '__veyqE2eLaterKind'

let member: Device
let peer: Device | undefined
let guest: Device | undefined
let laterId: string
let dropLink: string

// --- helpers ---------------------------------------------------------------------------------------------------------

async function openFolder(page: Page): Promise<void> {
  await page.goto(tenantOrigin())
  await page.getByRole('button', { name: folder, exact: true }).click({ timeout: 60_000 })
  await expect(page.getByRole('heading', { name: folder })).toBeVisible()
}

/** A file's bytes, saved through the browser's download (the SW's route: no picker offered). */
async function downloaded(page: Page, name: string): Promise<{ name: string; sha256: string }> {
  await page.evaluate(() => void delete (window as unknown as { showSaveFilePicker?: unknown }).showSaveFilePicker)
  await expect(downloadButton(page, name)).toBeEnabled({ timeout: 60_000 })
  const started = page.waitForEvent('download')
  await downloadButton(page, name).click()
  const download = await started
  expect(await download.failure()).toBeNull()
  return { name: download.suggestedFilename(), sha256: sha256(readFileSync(await download.path())) }
}

const question = (page: Page, text: string) =>
  page.getByRole('alertdialog').or(page.getByRole('dialog')).filter({ hasText: text })

const notice = (page: Page, text: string) => page.locator('.veyq-level-notice').filter({ hasText: text })

const tray = (page: Page) => page.getByRole('button', { name: 'Uploads', exact: true })
const trayPanel = (page: Page) => page.locator('#uploads-panel')
const trayRow = (page: Page, name: string) => trayPanel(page).locator('.uploads-file').filter({ hasText: name })

/** A file's row (or tile) in the level list, found by its primary button. */
const fileItem = (page: Page, name: string) =>
  page.locator('li.veyq-level-item').filter({ has: page.getByRole('button', { name: `Open ${name}`, exact: true }) })

const isStoreUrl = (url: URL) => url.searchParams.has('X-Amz-Signature')

/**
 * A page route that is switched off, never removed: once `off()`, it hands every request on unchanged, and it lives until
 * the page closes. Removing a page route (`page.unroute*`) while one of its requests stands between this handler's
 * `fallback()` and the confinement's `context.route` (egress.ts) makes Playwright continue that request itself, as the
 * page's last route goes away — and the confinement's own `continue()` then fails the test with "Route is already
 * handled!" (lane L1, 2026-10-23: the uploads-tray test, its held `PUT` released by an `unrouteAll`). A switched-off
 * route keeps that race from ever starting.
 */
async function switchableRoute(
  page: Page,
  match: (url: URL) => boolean,
  handler: (route: Route) => Promise<void>,
): Promise<{ off: () => void }> {
  let on = true
  await page.route(match, (route) => (on ? handler(route) : route.fallback()))
  return { off: () => void (on = false) }
}

/** Holds every presigned `PUT` of the store until `release()` — an upload that has started and not arrived. */
async function holdStore(page: Page): Promise<{ release: () => void; held: () => number }> {
  let open: () => void = () => undefined
  const gate = new Promise<void>((resolve) => (open = resolve))
  let held = 0
  const route = await switchableRoute(page, isStoreUrl, async (route) => {
    if (route.request().method() !== 'PUT') return route.fallback()
    held += 1
    await gate
    return route.fallback()
  })
  return {
    held: () => held,
    release: () => {
      route.off()
      open()
    },
  }
}

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

/** One stop of the Tab order inside the level view: which part of the level holds it, and what it is called. */
interface Stop { zone: string; label: string; row: string | null; primary: boolean; role: string | null; selected: string | null }

/** Describe the focused element as stop `n` (marking it `data-e2e-stop`), or null when focus left the level view. */
const describeFocus = (page: Page, n: number) =>
  page.evaluate((n): Stop | null => {
    const el = document.activeElement as HTMLElement | null
    if (!el || el === document.body || !el.closest('.veyq-level-view')) return null
    el.setAttribute('data-e2e-stop', String(n))
    const zone = el.closest('.veyq-level-toolbar')
      ? 'toolbar'
      : el.closest('.veyq-node-header')
        ? 'header'
        : el.closest('.veyq-level-tabs')
          ? 'tabs'
          : el.closest('.veyq-level-item')
            ? 'list'
            : 'other'
    const item = el.closest('.veyq-level-item')
    return {
      zone,
      label: el.getAttribute('aria-label') ?? (el.textContent ?? '').trim(),
      row: item?.querySelector('.veyq-level-item-title')?.textContent?.trim() ?? null,
      primary: el.classList.contains('veyq-level-item-main'),
      role: el.getAttribute('role'),
      selected: el.getAttribute('aria-selected'),
    }
  }, n)

/** Every Tab stop of the level view from the focused element on, in order, until focus leaves the view. */
async function tabStops(page: Page): Promise<Stop[]> {
  await page.evaluate(() => document.querySelectorAll('[data-e2e-stop]').forEach((e) => e.removeAttribute('data-e2e-stop')))
  const first = await describeFocus(page, 0)
  if (!first) throw new Error('the walk starts on an element of the level view')
  const stops = [first]
  for (let n = 1; n < 150; n++) {
    await page.keyboard.press('Tab')
    const stop = await describeFocus(page, n)
    if (!stop) return stops
    stops.push(stop)
  }
  throw new Error('150 Tab presses never left the level view')
}

/** Shift+Tab from stop `n`: the stop focus lands on (its `data-e2e-stop`), or null when it left the marked stops. */
async function shiftTabFrom(page: Page, n: number): Promise<number | null> {
  await page.locator(`[data-e2e-stop="${n}"]`).focus()
  await page.keyboard.press('Shift+Tab')
  const landed = await page.evaluate(() => document.activeElement?.getAttribute('data-e2e-stop') ?? null)
  return landed === null ? null : Number(landed)
}

/** Light or dark through the OS preference the theme follows by default ('system'), on a fresh document. */
async function scheme(page: Page, colorScheme: 'light' | 'dark'): Promise<void> {
  await page.emulateMedia({ colorScheme })
  await openFolder(page)
  await expect(page.locator('html')).toHaveClass(colorScheme === 'dark' ? /\bveyq-dark\b/ : /^(?!.*\bveyq-dark\b)/)
}

/** The drop-box guest sessions this browser keeps (core's one session per link, `guestIdentity.ts`). */
const storedSessions = (page: Page) =>
  page.evaluate((prefix) => Object.keys(localStorage).filter((k) => k.startsWith(prefix)), SESSION_PREFIX)

/** A drop-box guest picks one file through "Choose files". */
async function sendOnDropPage(page: Page, name: string, size: number): Promise<void> {
  const chooser = page.waitForEvent('filechooser')
  await page.getByRole('button', { name: 'Choose files', exact: true }).click()
  await (await chooser).setFiles({ name: recordTyped(name), mimeType: 'application/octet-stream', buffer: randomBytes(size) })
}

// --- lifecycle -------------------------------------------------------------------------------------------------------

test.beforeAll(async ({ browser }) => {
  member = await newDevice(browser)
  // The fallback row's stand-in (test 4): while the tab's sessionStorage names a node, every listing of the scope's tree
  // the page reads gives that node the kind named with it — what a newer client's node looks like to this one. Off
  // (no flag) it hands every answer through untouched.
  await member.page.addInitScript((flag) => {
    const original = window.fetch.bind(window)
    window.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
      const response = await original(input, init)
      let raw: string | null = null
      try {
        raw = sessionStorage.getItem(flag)
      } catch {
        return response
      }
      if (!raw) return response
      const href = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
      const method = (init?.method ?? (input instanceof Request ? input.method : 'GET')).toUpperCase()
      if (method !== 'GET' || response.status !== 200 || !/^\/api\/scopes\/[^/]+\/nodes$/.test(new URL(href, location.href).pathname)) {
        return response
      }
      const { id, kind } = JSON.parse(raw) as { id: string; kind: string }
      const rows = (await response.clone().json()) as { id: string; kind: string }[]
      const body = JSON.stringify(rows.map((r) => (r.id === id ? { ...r, kind } : r)))
      return new Response(body, { status: response.status, statusText: response.statusText, headers: response.headers })
    }
  }, LATER_FLAG)
})

test.afterEach(() => {
  for (const device of [member, peer, guest]) device?.confinement.assertConfined()
})

test.afterAll(async () => {
  for (const device of [member, peer, guest]) await device?.context.close()
})

// --- 1. the per-file messages ----------------------------------------------------------------------------------------

test(
  'a picked file whose name a folder here holds is not sent, and the notice names that folder',
  {
    tag: [
      '@uc.depot.member-files:refusals-end-without-a-repeat',
      '@uc.depot.member-files:a-file-name-is-folded-as-a-file-name',
    ],
  },
  async () => {
    const { page } = member
    await signInOwner(member)
    await createFolder(page, folder)
    await createFolder(page, blockingFolder)
    await page.goBack()
    await expect(page.getByRole('heading', { name: folder })).toBeVisible()

    await pickFiles(page, [{ name: blockedFile, bytes: new Uint8Array(randomBytes(20_000)) }])
    const refused = notice(page, `“${blockedFile}” was not uploaded.`)
    await expect(refused.locator('.veyq-level-notice-text')).toHaveText(
      `“${blockedFile}” was not uploaded. There is already a folder named “${blockingFolder}” here. Choose another name.`,
    )
    // Nothing was sent: no upload in the top bar, no row of that name.
    await expect(tray(page)).toHaveCount(0)
    await expect(page.getByRole('button', { name: `Open ${blockedFile}`, exact: true })).toHaveCount(0)
    await refused.getByRole('button', { name: 'Dismiss', exact: true }).click()
    await expect(refused).toHaveCount(0)
  },
)

// --- 5. the top bar's status menu ------------------------------------------------------------------------------------

test(
  "the top bar's status menu lists Depot's batch under the folder's name, each file's status, and closes the finished list",
  { tag: ['@uc.memory.upload:global-uploads-tray', '@uc.memory.upload:one-list-per-destination'] },
  async () => {
    const { page } = member
    await openFolder(page)
    await expect(tray(page), 'a fresh document has no uploads').toHaveCount(0)

    const store = await holdStore(page)
    await pickFiles(page, trayFiles)
    await expect(tray(page)).toBeVisible()
    await expect(page.locator('.uploads-badge')).toHaveText('2')
    await expect(tray(page)).toHaveAttribute('aria-expanded', 'false')

    await tray(page).click()
    await expect(tray(page)).toHaveAttribute('aria-expanded', 'true')
    const panel = trayPanel(page)
    await expect(panel.getByRole('heading', { name: 'Uploads' })).toBeVisible()
    await expect(panel.getByText('2 active', { exact: true })).toBeVisible()
    await expect(panel.getByRole('button', { name: 'Cancel all', exact: true })).toBeVisible()
    // One list for the destination, under the folder's name, its bar at nothing yet.
    await expect(panel.locator('.uploads-list')).toHaveCount(1)
    await expect(panel.locator('.uploads-list-head strong')).toHaveText(folder)
    await expect(panel.locator('.uploads-list-head span')).toHaveText('0%')
    await expect(panel.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '0')
    // The queue is sequential: the first file is on its way (its PUT held), the second waits.
    await expect.poll(store.held, { timeout: 30_000 }).toBeGreaterThan(0)
    await expect(trayRow(page, trayFiles[0].name).locator('.uploads-file-status')).toHaveText(/^(Preparing…|Uploading…)$/)
    await expect(trayRow(page, trayFiles[1].name).locator('.uploads-file-status')).toHaveText('Queued')
    for (const f of trayFiles) await expect(trayRow(page, f.name).getByRole('button', { name: 'Cancel', exact: true })).toBeVisible()

    // Escape closes the menu; the button opens it again.
    await page.keyboard.press('Escape')
    await expect(panel).toHaveCount(0)
    await expect(tray(page)).toHaveAttribute('aria-expanded', 'false')
    await tray(page).click()

    store.release()
    for (const f of trayFiles) await expect(trayRow(page, f.name).locator('.uploads-file-status')).toHaveText('Done', { timeout: 60_000 })
    await expect(panel.locator('.uploads-list-head span')).toHaveText('100%')
    await expect(panel.getByText(/ active$/)).toHaveCount(0)
    await expect(page.locator('.uploads-badge')).toHaveCount(0)
    for (const f of trayFiles) await expect(downloadButton(page, f.name)).toBeEnabled({ timeout: 60_000 })

    // The finished list's Close acknowledges it: no list, no button.
    await panel.getByRole('button', { name: 'Close', exact: true }).click()
    await expect(panel.locator('.uploads-list')).toHaveCount(0)
    await expect(panel.getByText('No uploads', { exact: true })).toBeVisible()
    await expect(tray(page)).toHaveCount(0)
    await page.keyboard.press('Escape')
    await expect(panel).toHaveCount(0)
  },
)

test(
  'an upload the store never takes fails after its one automatic retry, says so beside the list, and the top bar retries it',
  { tag: ['@uc.memory.upload:transient-retry', '@uc.depot.member-files:refusals-end-without-a-repeat'] },
  async () => {
    const { page } = member
    await openFolder(page)
    let cut = 0
    const cutOff = await switchableRoute(page, isStoreUrl, async (route) => {
      if (route.request().method() !== 'PUT') return route.fallback()
      cut += 1
      return route.abort('internetdisconnected')
    })
    await pickFiles(page, [failing])
    const failed = notice(page, `“${failing.name}” could not be uploaded.`)
    await expect(failed).toBeVisible({ timeout: 60_000 })
    expect(cut, 'the store was asked on the first attempt and on the automatic retry').toBeGreaterThanOrEqual(2)

    await tray(page).click()
    const row = trayRow(page, failing.name)
    await expect(row.locator('.uploads-file-status')).toHaveText('Failed')
    cutOff.off()
    await row.getByRole('button', { name: 'Retry', exact: true }).click()
    await expect(row.locator('.uploads-file-status')).toHaveText('Done', { timeout: 60_000 })
    // Delivered: nothing left to explain, and the file is there.
    await expect(failed).toHaveCount(0)
    await expect(downloadButton(page, failing.name)).toBeEnabled({ timeout: 60_000 })
    await trayPanel(page).getByRole('button', { name: 'Close', exact: true }).click()
    await page.keyboard.press('Escape')

    await openFolder(page)
    expect(await downloaded(page, failing.name)).toEqual({ name: failing.name, sha256: sha256(failing.bytes) })
  },
)

test(
  'a file another device replaced after this one read it is not overwritten; "Replace anyway" then replaces it',
  { tag: '@uc.depot.member-files:replace-is-a-compare-and-set-on-the-blob' },
  async ({ browser }) => {
    const target = trayFiles[0].name
    const { page } = member
    await openFolder(page)
    await expect(downloadButton(page, target)).toBeEnabled({ timeout: 60_000 })
    // This device stops noticing changes: its live poll (the only listing sent with `If-None-Match`) is cut off, so the
    // blob it read stays the one it would replace.
    const deaf = await switchableRoute(page, (url) => /^\/api\/scopes\/[^/]+\/nodes$/.test(url.pathname), async (route) => {
      const r = route.request()
      if (r.method() === 'GET' && (await r.headerValue('if-none-match')) !== null) return route.abort('internetdisconnected')
      return route.fallback()
    })

    // The owner's second device replaces the file.
    peer = await newDevice(browser)
    await signInOwner(peer)
    await openFolder(peer.page)
    await pickFiles(peer.page, [{ name: target, bytes: peerContent }])
    const peerAsk = question(peer.page, 'A file with this name is already here')
    await peerAsk.getByRole('button', { name: 'Replace', exact: true }).click()
    await expect(peerAsk).toBeHidden()
    await expect(fileItem(peer.page, target)).toContainText('111.1 kB', { timeout: 60_000 })

    // This device replaces the content it read: the question, then the server's refusal, said beside the list.
    await pickFiles(page, [{ name: target, bytes: ownContent }])
    const ask = question(page, 'A file with this name is already here')
    await ask.getByRole('button', { name: 'Replace', exact: true }).click()
    await expect(ask).toBeHidden()
    const stale = notice(page, `“${target}” was not uploaded: someone else replaced this file in the meantime.`)
    await expect(stale).toContainText('Replace it anyway, or look at theirs first.', { timeout: 60_000 })
    deaf.off()

    await stale.getByRole('button', { name: 'Replace anyway', exact: true }).click()
    await expect(stale).toHaveCount(0, { timeout: 60_000 })
    await expect(fileItem(page, target)).toContainText('99 kB', { timeout: 60_000 })

    // Read back on both devices, from the server: this device's content won.
    await openFolder(page)
    expect(await downloaded(page, target)).toEqual({ name: target, sha256: sha256(ownContent) })
    await openFolder(peer.page)
    expect(await downloaded(peer.page, target)).toEqual({ name: target, sha256: sha256(ownContent) })
  },
)

// --- 4. the level's fallback rows ------------------------------------------------------------------------------------

test(
  "the level's fallback rows: a file whose content has not arrived, and a node of a kind this client does not know",
  { tag: '@uc.memory.browse:row-details' },
  async () => {
    const { page } = member
    await openFolder(page)

    // The file node exists before its content (the upload creates it, then sends the bytes): another look at the level
    // (the live poll) shows it, without size, its download not yet possible.
    const store = await holdStore(page)
    await pickFiles(page, [slow])
    await expect.poll(store.held, { timeout: 30_000 }).toBeGreaterThan(0)
    const item = fileItem(page, slow.name)
    await expect(item.locator('.veyq-level-item-meta')).toHaveText('File · Not uploaded yet', { timeout: LIVE })
    await expect(downloadButton(page, slow.name)).toBeDisabled()
    store.release()
    await expect(downloadButton(page, slow.name)).toBeEnabled({ timeout: 60_000 })
    await expect(item.locator('.veyq-level-item-meta')).not.toContainText('Not uploaded yet')

    // A folder whose listed kind this client does not know: listed, never dropped, named, and not openable.
    await page.getByRole('button', { name: 'New', exact: true }).click()
    await page.getByRole('menuitem', { name: 'New folder' }).click()
    const dialog = page.getByRole('dialog', { name: 'Create a folder' })
    await dialog.getByLabel('Name').fill(recordTyped(laterFolder))
    const create = page.waitForRequest((r: Request) => r.method() === 'POST' && /\/api\/scopes\/[^/]+\/nodes$/.test(r.url()))
    await dialog.getByRole('button', { name: 'Create', exact: true }).click()
    laterId = ((await create).postDataJSON() as { nodeId: string }).nodeId
    await expect(dialog).toBeHidden()
    await expect(page.getByRole('button', { name: laterFolder, exact: true })).toBeVisible()

    await page.evaluate(([flag, id, kind]) => sessionStorage.setItem(flag, JSON.stringify({ id, kind })), [LATER_FLAG, laterId, LATER_KIND])
    try {
      await openFolder(page)
      const unsupported = page.locator('li.veyq-level-item.is-unsupported')
      await expect(unsupported).toHaveCount(1)
      await expect(unsupported).toHaveAttribute('data-kind', LATER_KIND)
      await expect(unsupported.locator('.veyq-level-item-title')).toHaveText(laterFolder)
      await expect(unsupported.locator('.veyq-level-item-meta')).toHaveText(`${LATER_KIND} · This client cannot open this kind of item.`)
      await expect(unsupported.getByRole('button')).toHaveCount(0)
      await expect(page.getByRole('button', { name: laterFolder, exact: true })).toHaveCount(0)
      // Its look: dimmed, the row's own frame kept (`ui.css` `.veyq-level-item.is-unsupported`).
      expect(await styleOf(unsupported, ['opacity', 'background-color'])).toEqual({
        opacity: '0.85',
        'background-color': await probe(page, 'background-color: var(--app-surface)', 'background-color'),
      })
    } finally {
      await page.evaluate((flag) => sessionStorage.removeItem(flag), LATER_FLAG)
    }
    await openFolder(page)
    await expect(page.getByRole('button', { name: laterFolder, exact: true })).toBeVisible()
    await expect(page.locator('li.veyq-level-item.is-unsupported')).toHaveCount(0)
  },
)

// --- 2. focus, keyboard and tab order --------------------------------------------------------------------------------

test(
  "Tab walks the level's header, toolbar, tabs and rows in order, Shift+Tab walks back, and the keys work each part",
  { tag: ['@uc.memory.browse:application-tabs', '@uc.memory.browse:view-modes'] },
  async () => {
    const { page } = member
    await openFolder(page)
    const newButton = page.getByRole('button', { name: 'New', exact: true })
    // The head is one row, the name first (owner 2026-11-01): its first stop is the folder header's first action.
    await page.locator('.veyq-level-head .veyq-node-header button').first().focus()
    const stops = await tabStops(page)

    // Every stop belongs to a part of the level, and the parts come in the component's order.
    const rank: Record<string, number> = { header: 0, toolbar: 1, tabs: 2, list: 3 }
    expect(stops.filter((s) => !(s.zone in rank)), 'no stop outside the four parts').toEqual([])
    const ranks = stops.map((s) => rank[s.zone])
    expect(ranks, 'header, then toolbar, then tabs, then rows').toEqual([...ranks].sort((a, b) => a - b))

    const toolbar = stops.filter((s) => s.zone === 'toolbar').map((s) => s.label)
    expect(toolbar.slice(0, 3)).toEqual(['New', 'Trash', 'Select'])
    expect(toolbar).toEqual(expect.arrayContaining(['Grid view', 'List view']))
    expect(stops.filter((s) => s.zone === 'header').length, 'the folder header has its actions').toBeGreaterThan(0)
    // The tab bar is ONE stop: the selected tab (the arrow keys move inside it).
    expect(stops.filter((s) => s.zone === 'tabs').map(({ label, role, selected }) => ({ label, role, selected }))).toEqual([
      { label: 'All', role: 'tab', selected: 'true' },
    ])

    // Each row is one run of stops: its primary button first, its "More actions" last; folders before files.
    const listStops = stops.map((s, i) => ({ ...s, i })).filter((s) => s.zone === 'list')
    const rows = [...new Set(listStops.map((s) => s.row))]
    for (const row of rows) {
      const own = listStops.filter((s) => s.row === row)
      expect(own.map((s) => s.i), `the stops of “${row}” are one run`).toEqual(own.map((_, k) => own[0].i + k))
      expect(own[0].primary, `“${row}” starts with its primary button`).toBe(true)
      expect(own[own.length - 1].label).toBe(`More actions for “${row}”`)
    }
    const fileRow = listStops.filter((s) => s.row === trayFiles[0].name)
    expect(fileRow[0].label).toBe(`Open ${trayFiles[0].name}`)
    expect(fileRow.map((s) => s.label)).toContain(`Download ${trayFiles[0].name}`)
    expect(rows.indexOf(blockingFolder), 'a folder comes before the files').toBeLessThan(rows.indexOf(trayFiles[0].name))
    expect(rows.indexOf(blockingFolder)).toBeGreaterThanOrEqual(0)

    // Shift+Tab walks back one stop: out of the first row into the tab bar, out of the tab bar into the toolbar.
    const firstTab = stops.findIndex((s) => s.zone === 'tabs')
    const firstRow = listStops[0].i
    expect(await shiftTabFrom(page, firstRow)).toBe(firstTab)
    expect(await shiftTabFrom(page, firstTab)).toBe(firstTab - 1)
    expect(stops[firstTab - 1].zone).toBe('toolbar')

    // The arrow keys move between the tabs, and a focused tab is the shown one; back to All.
    await page.locator(`[data-e2e-stop="${firstTab}"]`).focus()
    await page.keyboard.press('ArrowRight')
    const next = page.locator('.veyq-level-tabs [role="tab"]:focus')
    await expect(next).toHaveCount(1)
    await expect(next).not.toHaveText('All')
    await expect(next).toHaveAttribute('aria-selected', 'true')
    await page.keyboard.press('ArrowLeft')
    const all = page.getByRole('tab', { name: 'All', exact: true })
    await expect(all).toBeFocused()
    await expect(all).toHaveAttribute('aria-selected', 'true')
    await expect(page.getByRole('button', { name: `Open ${trayFiles[0].name}`, exact: true })).toBeVisible()

    // A menu opens from the keyboard and Escape gives focus back to its opener: the "New" menu, a row's "More actions".
    await newButton.focus()
    await page.keyboard.press('Enter')
    await expect(page.getByRole('menu')).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(page.getByRole('menu')).toHaveCount(0)
    await expect(newButton).toBeFocused()
    const more = page.getByRole('button', { name: `More actions for “${trayFiles[0].name}”`, exact: true })
    await more.focus()
    await page.keyboard.press('Enter')
    await expect(page.getByRole('menuitem', { name: 'Rename', exact: true })).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(page.getByRole('menu')).toHaveCount(0)
    await expect(more).toBeFocused()

    // Enter on a folder's row opens it.
    await page.getByRole('button', { name: blockingFolder, exact: true }).focus()
    await page.keyboard.press('Enter')
    await expect(page.getByRole('heading', { name: blockingFolder })).toBeVisible()
  },
)

// --- 3. FolderNameDialog's Escape ------------------------------------------------------------------------------------

test(
  "Escape closes the folder name dialog: nothing is created, and focus is back on the New button",
  { tag: '@uc.memory.browse:create-folder-and-memory' },
  async () => {
    const { page } = member
    await openFolder(page)
    const newButton = page.getByRole('button', { name: 'New', exact: true })
    await newButton.focus()
    await page.keyboard.press('Enter')
    const entry = page.getByRole('menuitem', { name: 'New folder' })
    await entry.focus()
    await page.keyboard.press('Enter')

    const dialog = page.getByRole('dialog', { name: 'Create a folder' })
    await expect(dialog).toBeVisible()
    const name = dialog.getByLabel('Name')
    await expect(name, 'the name field has focus as the dialog opens').toBeFocused()
    await name.fill(recordTyped(escapedFolder))
    await expect(dialog.getByRole('button', { name: 'Create', exact: true })).toBeEnabled()
    await page.keyboard.press('Escape')
    await expect(dialog).toHaveCount(0)
    // VeyqDialog: focus returns to what had it before — the menu gave it back to its opener, the New button.
    await expect(newButton).toBeFocused()
    await expect(page.getByRole('button', { name: escapedFolder, exact: true })).toHaveCount(0)

    // Read back from the server: no such folder.
    await openFolder(page)
    await expect(page.getByRole('button', { name: blockingFolder, exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: escapedFolder, exact: true })).toHaveCount(0)
  },
)

// --- 6. the level's core rules in light and dark ---------------------------------------------------------------------

test(
  "the level's core rules that Depot's rows and the folder header wear hold in light and dark",
  { tag: ['@uc.memory.browse:row-details', '@uc.memory.browse:view-modes'] },
  async () => {
    const { page } = member
    const surfaces: string[] = []
    for (const colorScheme of ['light', 'dark'] as const) {
      await scheme(page, colorScheme)
      const tokens = async (pairs: Record<string, string>) =>
        Object.fromEntries(await Promise.all(Object.entries(pairs).map(async ([p, d]) => [p, await probe(page, d, p)] as const)))

      // The list: a grid with no list indent (inbox #382 (c)).
      expect(await styleOf(page.locator('.veyq-level-list'), ['display', 'list-style-type', 'padding-left', 'margin-top'])).toEqual({
        display: 'grid', 'list-style-type': 'none', 'padding-left': '0px', 'margin-top': '0px',
      })
      // The toolbar: one right-aligned row.
      expect(await styleOf(page.locator('.veyq-level-toolbar'), ['display', 'justify-content'])).toEqual({
        display: 'flex', 'justify-content': 'flex-end',
      })

      // A file's tile: the item's surface, corner and shadow; its visual the accent tile; its title and meta line.
      const file = fileItem(page, trayFiles[0].name)
      const fileStyle = await styleOf(file, ['background-color', 'color', 'border-top-left-radius', 'box-shadow'])
      expect(fileStyle).toEqual({
        ...(await tokens({ 'background-color': 'background-color: var(--app-surface)', color: 'color: var(--app-text)' })),
        'border-top-left-radius': '12px',
        'box-shadow': await probe(page, 'box-shadow: var(--app-shadow-card)', 'box-shadow'),
      })
      surfaces.push(fileStyle['background-color'])
      expect(await styleOf(file.locator('.veyq-level-item-visual'), ['background-color', 'color', 'aspect-ratio'])).toEqual({
        ...(await tokens({ 'background-color': 'background-color: var(--app-accent-soft)', color: 'color: var(--app-accent)' })),
        'aspect-ratio': '16 / 9',
      })
      expect(await styleOf(file.locator('.veyq-level-item-title'), ['font-weight', 'color', 'white-space'])).toEqual({
        'font-weight': '700', color: await probe(page, 'color: var(--app-text)', 'color'), 'white-space': 'nowrap',
      })
      expect(await styleOf(file.locator('.veyq-level-item-meta'), ['color', 'font-size'])).toEqual(
        await tokens({ color: 'color: var(--app-muted)', 'font-size': 'font-size: 0.85rem' }),
      )

      // A folder's tile: the info tile instead of the accent one.
      const folderItem = page.locator('li.veyq-level-item.is-folder').filter({
        has: page.getByRole('button', { name: blockingFolder, exact: true }),
      })
      expect(await styleOf(folderItem.locator('.veyq-level-item-visual'), ['background-color', 'color'])).toEqual(
        await tokens({ 'background-color': 'background-color: var(--app-info-soft)', color: 'color: var(--app-info-text)' }),
      )

      // The folder header's empty cover frame (a folder takes a cover; its dark twin in `dark.css` says the same tokens): 16:9,
      // 64 px wide on a compact header, else as tall as the folder's one name row (ADR 0110 decision 11).
      const frame = page.locator('.veyq-level-head .veyq-node-header .veyq-node-header-cover.is-empty')
      expect(await styleOf(frame, ['background-color', 'color'])).toEqual(
        await tokens({ 'background-color': 'background-color: var(--app-info-soft)', color: 'color: var(--app-info-text)' }),
      )
      const compact = await page.locator('.veyq-level-head .veyq-node-header').evaluate((h) => h.classList.contains('is-compact'))
      const box = await styleOf(frame, ['width', 'height'])
      expect(Number.parseFloat(box.width), 'its width').toBeCloseTo(compact ? 64 : (42 * 16) / 9, 1)
      expect(Number.parseFloat(box.height), 'its height').toBeCloseTo(compact ? 36 : 42, 1)

      // The list mode: one column, the visual a 6.5 × 4 rem tile at this width; then back to the grid.
      const toolbar = page.locator('.veyq-level-toolbar')
      await toolbar.getByRole('button', { name: 'List view', exact: true }).click()
      await expect(page.locator('.veyq-level-list')).toHaveClass(/\bis-list\b/)
      expect(await styleOf(file.locator('.veyq-level-item-visual'), ['width', 'height'])).toEqual(
        await tokens({ width: 'width: 6.5rem', height: 'height: 4rem' }),
      )
      await toolbar.getByRole('button', { name: 'Grid view', exact: true }).click()
      await expect(page.locator('.veyq-level-list')).not.toHaveClass(/\bis-list\b/)
    }
    expect(surfaces[1], 'the dark theme gives the item another surface').not.toBe(surfaces[0])
    await page.emulateMedia({ colorScheme: 'light' })
  },
)

// --- 7. a drop-box guest's page on reload ----------------------------------------------------------------------------

test(
  "a drop-box guest's page on reload: the name is kept from the first file on, and the next file lands in the same folder",
  { tag: ['@uc.depot.drop-box:one-folder-per-guest-identity', '@uc.depot.drop-box:revoking-the-link-keeps-everything'] },
  async ({ browser }) => {
    const { page } = member
    await openFolder(page)
    // The folder's share, "Add files only": the drop box and its first link in one flow (owner 2026-11-04).
    dropLink = await createDropBoxLink(page, dropBox)

    guest = await newDevice(browser)
    const tab = guest.page
    const heading = tab.getByRole('heading', { name: 'Send files' })
    const nameField = tab.getByLabel('Your name')
    const named = tab.getByText(`You are here as ${dropper}.`, { exact: true })
    await tab.goto(dropLink)
    await expect(heading).toBeVisible({ timeout: 60_000 })

    // Named, nothing sent: nothing is kept, so a reload asks again.
    await nameField.fill(recordTyped(dropper))
    await tab.getByRole('button', { name: 'Continue', exact: true }).click()
    await expect(named).toBeVisible()
    expect(await storedSessions(tab), 'no session before the first file').toEqual([])
    await tab.reload()
    await expect(heading).toBeVisible({ timeout: 60_000 })
    await expect(nameField).toBeVisible()
    await expect(named).toHaveCount(0)

    // The first file makes the identity and its folder; the session is kept on this device from then on.
    await nameField.fill(recordTyped(dropper))
    await tab.getByRole('button', { name: 'Continue', exact: true }).click()
    await sendOnDropPage(tab, dropped[0], 40_000)
    await expect(tab.getByText(/^Sent · /)).toHaveCount(1, { timeout: 60_000 })
    expect(await storedSessions(tab)).toHaveLength(1)

    // Reloaded: the stored session, not the form; the session's list was this page's own and is gone.
    await tab.reload()
    await expect(heading).toBeVisible({ timeout: 60_000 })
    await expect(named).toBeVisible()
    await expect(tab.getByRole('button', { name: 'Not you?', exact: true })).toBeVisible()
    await expect(nameField).toHaveCount(0)
    await expect(tab.getByRole('heading', { name: 'Your files in this session' })).toHaveCount(0)

    // The same folder: a name sent before the reload is refused there, with no retry; a new one arrives.
    await sendOnDropPage(tab, dropped[0], 41_000)
    await expect(tab.getByText('Not sent: you already sent a file with this name.', { exact: true })).toBeVisible({ timeout: 60_000 })
    await expect(tab.getByRole('button', { name: `Send “${dropped[0]}” again`, exact: true })).toHaveCount(0)
    await sendOnDropPage(tab, dropped[1], 42_000)
    await expect(tab.getByText(/^Sent · /)).toHaveCount(1, { timeout: 60_000 })

    // The owner: one folder for the guest, holding both files.
    await openFolder(page)
    await page.getByRole('button', { name: `Open ${dropBox}`, exact: true }).click()
    await expect(page.getByRole('heading', { name: dropBox })).toBeVisible()
    await expect(page.getByRole('button', { name: `Open ${dropper}`, exact: true })).toBeVisible({ timeout: 60_000 })
    await expect(page.getByRole('button', { name: `Open ${dropper} (2)`, exact: true })).toHaveCount(0)

    // The owner switches the link off - on the drop box's own links: the guest's next file is refused with the page's own message.
    await page.getByRole('button', { name: 'Share link', exact: true }).click()
    const links = page.getByRole('dialog', { name: 'Drop box links' })
    await links.getByRole('button', { name: 'Switch off', exact: true }).click()
    const off = question(page, 'Switch this link off?')
    await off.getByRole('button', { name: 'Switch off', exact: true }).click()
    await expect(off).toBeHidden()
    await expect(links.getByRole('button', { name: 'Switch off', exact: true })).toHaveCount(0)
    await links.getByRole('button', { name: 'Close', exact: true }).first().click()
    await expect(links).toBeHidden()

    await sendOnDropPage(tab, dropped[2], 43_000)
    await expect(tab.getByText('Not sent: this link no longer takes files from you.', { exact: true })).toBeVisible({ timeout: 60_000 })
    await expect(tab.getByText('This link no longer takes files from you. Files marked as sent have arrived.', { exact: true })).toBeVisible()

    // What arrived stays: the guest's folder holds its two files, not the refused one.
    await page.getByRole('button', { name: `Open ${dropper}`, exact: true }).click()
    await expect(page.getByRole('heading', { name: dropper, exact: true })).toBeVisible()
    for (const name of dropped.slice(0, 2)) await expect(downloadButton(page, name)).toBeEnabled({ timeout: 60_000 })
    await expect(downloadButton(page, dropped[2])).toHaveCount(0)
  },
)
