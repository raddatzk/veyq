// The safety number's QR compare, scanned by a camera in a browser (inbox #491's last open proof; ADR 0028 Decision 3,
// ADR 0111 decision 14, uc.spaces.enforce-verified-identities main flow 0-3). The compare by digits was proven at the V1
// heavy stage; the scan needs a camera, and Chromium gives a page one from a file: `--use-fake-device-for-media-stream`
// with `--use-file-for-fake-video-capture=<file.y4m>` is the camera `getUserMedia` opens, and
// `--use-fake-ui-for-media-stream` answers its prompt. So one member's scanner is shown the QR another member's own
// profile renders — screenshotted off that page, never encoded by the test — and the app's own decode (KitScanner's
// jsQR) and compare (`compareScan`) decide:
//
//   1. a QR of a DIFFERENT identity — the scanning member's own, a real `veyqid:2` code of another key and head — is
//      refused with "These are different keys" and marks nothing: on another device of the same member the profile and
//      Who's who still read "Not verified";
//   2. the QR on the other member's own profile marks them verified: the scanner says so, the profile reads "Verified"
//      and "Verified by you.", and so does Who's who on the member's other device — read from the server, not this tab.
//
// The flags are browser-launch flags and the file must exist at launch, while the file is made during the test from what
// a page renders — so the scanning device is a browser of its own, launched per QR from the worker's `playwright.chromium` with
// the suite's own launch options (the resolver rules) plus the three flags; the suite's default browser stays a plain
// one. It is not traced (the suite's trace follows the default browser); its page is confined like every other.
//
// Everything happens in a tenant of its own ("E2E Safety"), born as join-profile-picture.spec.ts births "E2E Picture":
// the Server Owner creates it for a fresh owner, who points its mail at the run's relay and invites a fresh member by
// mail. The labels are src/web/'s and admin-web's own (each package's i18n, en). Every secret typed is a canary
// (recordTyped); the handles are declared plaintext.
import { expect, type Browser, type LaunchOptions, type Locator, type Page } from '@playwright/test'
import { test } from '../../../src/cspViolations'
import { randomBytes } from 'node:crypto'
import { writeFileSync } from 'node:fs'
import { freshOwner, readKit } from '../../../src/accounts'
import { recordTyped } from '../../../src/canaries'
import { instance, newDevice, passwordField, type Device } from '../../../src/devices'
import { awaitMails } from '../../../src/mail'
import { stack } from '../../../src/stack'
import { toast } from '../../../src/toast'

test.describe.configure({ mode: 'serial' })

interface Account {
  email: string
  password: string
  secretKey: string
}

// The relay as an owner types it into the space's mail settings (join-profile-picture.spec.ts types the same).
const RELAY = { host: 'mail.relay.test', port: '1025' }
const TENANT = 'E2E Safety'

const stamp = randomBytes(3).toString('hex')
const safetyDomain = () => `safety.${stack().domain}`
const safetyOrigin = () => `https://${safetyDomain()}`
// Per-tenant handles: declared plaintext (`pii`), so no canaries.
const handles = { owner: `safety-owner-${stamp}`, member: `safety-member-${stamp}` }

// The camera's picture: a square frame with the screenshotted QR in its middle, pixel for pixel, on white (a quiet zone
// around the plate's own). 1:1 on purpose: the screen's own pixels are what is proven scannable. A first version
// stretched the 276 px screenshot to 400 px by nearest neighbour; the uneven module widths and doubled grey edges of that
// non-integer stretch made jsQR miss the code, which it read from the same screenshot unscaled and at x2 and x3
// (measured 2026-10-29) — an artefact of the test's resampling, not of the page.
const FRAME = 480

let admin: Device // the Server Owner's console
let owner: Device // the tenant's owner, whose QR is scanned
let member: Device // the member who scans, on a browser with no camera: the device that reads the result back
let accountOwner: Account
let accountMember: Account
let ownerId: string
let memberId: string

const escape = (text: string) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

/** The console, signed in — a context that already signed in opens it signed in; a new document asks first. */
async function openConsole(page: Page): Promise<void> {
  const { owner: serverOwner } = instance()
  await page.goto(stack().adminOrigin)
  const home = page.getByRole('heading', { name: 'Server administration', level: 1 })
  const email = page.getByLabel('Email', { exact: true })
  await expect(home.or(email)).toBeVisible({ timeout: 60_000 })
  if (await email.isVisible()) {
    await email.fill(serverOwner.email)
    await passwordField(page).fill(recordTyped(serverOwner.password))
    await page.getByRole('button', { name: 'Sign in' }).click()
  }
  await expect(home).toBeVisible({ timeout: 60_000 })
}

/** The sign-in form of a device that holds no key for the account: email, password and Secret Key. */
async function submitSignIn(page: Page, account: Account): Promise<void> {
  await page.getByLabel('Email', { exact: true }).fill(account.email)
  await passwordField(page).fill(recordTyped(account.password))
  await page.locator('#unlock-secret-key').fill(recordTyped(account.secretKey))
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
}

const newButton = (page: Page) => page.getByRole('button', { name: 'New', exact: true })

/**
 * The space's home on the safety tenant, signing in first when this document asks for it. Retried while the domain
 * answers the proxy's own 404 (a fresh tenant's routers reach Traefik a moment late).
 */
async function reachHome(device: Device, account: Account): Promise<void> {
  const { page } = device
  await expect(async () => {
    const response = await page.goto(safetyOrigin())
    expect(response?.ok(), `${safetyOrigin()} serves the app`).toBe(true)
  }).toPass({ timeout: 60_000, intervals: [1_000] })
  const email = page.getByLabel('Email', { exact: true })
  await expect(newButton(page).or(email)).toBeVisible({ timeout: 60_000 })
  if (await email.isVisible()) await submitSignIn(page, account)
  await expect(newButton(page)).toBeVisible({ timeout: 60_000 })
}

/** A section of Settings, reached as devices.ts's `openSettings` does — from the space's home. */
async function openSettingsOn(device: Device, account: Account, section: string): Promise<void> {
  const { page } = device
  await reachHome(device, account)
  await page.locator('.profile-button').click()
  await page.getByRole('menuitem', { name: 'Settings' }).click()
  await page.getByRole('navigation', { name: 'Settings' }).getByRole('button', { name: section, exact: true }).click()
}

/** A row of Who's who, by the per-tenant handle it shows (`@handle`). */
const whoRow = (page: Page, handle: string): Locator => page.getByTestId('who-is-who').locator('.member-row').filter({ hasText: `@${handle}` })

/** Who's who on [device], and the profile of the member [handle] opened from their row; returns their user id. */
async function openProfileFromWhoIsWho(device: Device, account: Account, handle: string): Promise<string> {
  const { page } = device
  await openSettingsOn(device, account, 'Who’s who')
  await whoRow(page, handle).locator('a.who-name').click()
  await expect(page).toHaveURL(/#people\/[^/]+$/)
  await expect(page.getByTestId('person-safety')).toBeVisible({ timeout: 60_000 })
  return decodeURIComponent(new URL(page.url()).hash.replace(/^#people\//, ''))
}

/** One's own profile page (`#people/<own id>`, ADR 0083): the shell links to it from nowhere, so the address is typed. */
async function openOwnProfile(device: Device, account: Account, userId: string): Promise<Locator> {
  const { page } = device
  await reachHome(device, account)
  await page.goto(`${safetyOrigin()}/#people/${encodeURIComponent(userId)}`)
  const mine = page.getByTestId('my-safety-number')
  await expect(mine).toBeVisible({ timeout: 60_000 })
  const qr = mine.getByRole('img', { name: 'Your safety number as a code for another member to scan' })
  await expect(qr).toHaveAttribute('src', /^data:image\/png;base64,/, { timeout: 60_000 })
  await expect.poll(() => qr.evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth),
    { message: 'the QR decoded' }).toBeGreaterThan(0)
  return qr
}

/**
 * A camera's video file of the QR [qr] shows on its page: the element's screenshot, drawn 1:1 into the middle of a white
 * square frame and turned into its luma in that page's own canvas, written as a two-frame YUV4MPEG2 file (4:2:0, full
 * range — chroma flat grey, the QR is black on white) at [path], which Chromium's file-backed fake camera plays in a loop.
 */
async function cameraFileOf(qr: Locator, path: string): Promise<void> {
  const png = await qr.screenshot()
  const luma = await qr.page().evaluate(async ({ b64, frame }) => {
    const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0))
    const bitmap = await createImageBitmap(new Blob([bytes], { type: 'image/png' }))
    if (bitmap.width > frame || bitmap.height > frame) throw new Error(`a ${bitmap.width}x${bitmap.height} QR does not fit the frame`)
    const canvas = document.createElement('canvas')
    canvas.width = frame
    canvas.height = frame
    const g = canvas.getContext('2d')!
    g.fillStyle = '#ffffff'
    g.fillRect(0, 0, frame, frame)
    g.drawImage(bitmap, Math.floor((frame - bitmap.width) / 2), Math.floor((frame - bitmap.height) / 2))
    const { data } = g.getImageData(0, 0, frame, frame)
    let out = ''
    for (let i = 0; i < frame * frame; i++) {
      out += String.fromCharCode(Math.round(0.299 * data[4 * i]! + 0.587 * data[4 * i + 1]! + 0.114 * data[4 * i + 2]!))
    }
    return btoa(out)
  }, { b64: png.toString('base64'), frame: FRAME })
  const y = Buffer.from(luma, 'base64')
  expect(y.length, 'one luma byte per pixel').toBe(FRAME * FRAME)
  const chroma = Buffer.alloc((FRAME / 2) * (FRAME / 2) * 2, 128)
  const frameBytes = Buffer.concat([Buffer.from('FRAME\n'), y, chroma])
  writeFileSync(path, Buffer.concat([Buffer.from(`YUV4MPEG2 W${FRAME} H${FRAME} F10:1 Ip A1:1 C420jpeg\n`), frameBytes, frameBytes]))
}

/**
 * The member's scanning device: a browser of its own whose only camera plays [feed], a context confined to the
 * instance with the camera allowed, signed in as the member and on the owner's profile — opened from Who's who.
 */
async function scanningDevice(
  { playwright, launchOptions }: { playwright: { chromium: { launch: (o: LaunchOptions) => Promise<Browser> } }; launchOptions: LaunchOptions },
  feed: string,
): Promise<{ close: () => Promise<void>; device: Device }> {
  const browser = await playwright.chromium.launch({
    ...launchOptions,
    args: [
      ...(launchOptions.args ?? []),
      '--use-fake-device-for-media-stream',
      '--use-fake-ui-for-media-stream',
      `--use-file-for-fake-video-capture=${feed}`,
    ],
  })
  const device = await newDevice(browser, { locale: 'en-US', permissions: ['camera'] })
  return {
    device,
    close: async () => {
      device.confinement.assertConfined()
      await browser.close()
    },
  }
}

/** On the owner's profile: the safety section's state badge, and the outcome the last compare came to. */
const safetyState = (page: Page) => page.getByTestId('person-safety').getByTestId('person-safety-state')
const safetyOutcome = (page: Page) => page.getByTestId('person-safety').getByTestId('person-safety-outcome')

test.beforeAll(async ({ browser }) => {
  admin = await newDevice(browser)
  owner = await newDevice(browser)
  member = await newDevice(browser)
})

test.afterEach(() => {
  for (const d of [admin, owner, member]) d?.confinement.assertConfined()
})

test.afterAll(async () => {
  for (const d of [admin, owner, member]) await d?.context.close()
})

test(
  'a new tenant\'s owner invites a member, each finds the other in Who\'s who, and the owner\'s own profile shows the QR',
  { tag: '@uc.spaces.enforce-verified-identities:show-my-safety-number' },
  async () => {
    // The Server Owner creates the tenant for a fresh account and copies its invite link.
    const fresh = freshOwner(`e2e.${stack().domain}`)
    const consolePage = admin.page
    await openConsole(consolePage)
    await consolePage.getByRole('button', { name: 'Create tenant' }).click()
    const create = consolePage.getByRole('dialog', { name: 'Create tenant' })
    await create.getByLabel('Tenant name').fill(TENANT)
    await create.getByLabel('Domain').fill(safetyDomain())
    await create.getByLabel('Who should own this tenant? (email)').fill(fresh.email)
    await create.getByRole('button', { name: 'Create' }).click()
    await expect(create.getByText('Tenant created.')).toBeVisible()
    await admin.context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: stack().adminOrigin })
    await create.getByRole('button', { name: 'Copy link' }).click()
    const inviteLink = await consolePage.evaluate(() => navigator.clipboard.readText())
    await create.getByRole('button', { name: 'Done' }).click()

    const { page } = owner
    await expect(async () => {
      await page.goto(inviteLink)
    }).toPass({ timeout: 60_000, intervals: [1_000] })
    await expect(page.getByText(`You have been invited to own “${TENANT}” (${safetyDomain()}).`)).toBeVisible()
    await page.getByLabel('Username').fill(handles.owner)
    await page.locator('#join-password').fill(recordTyped(fresh.password))
    await page.getByRole('button', { name: 'Continue' }).click()
    const ownerKit = page.waitForEvent('download')
    await page.getByRole('button', { name: 'Download Emergency Kit (PDF)' }).click()
    const kit = await readKit(await ownerKit)
    await page.getByRole('checkbox', { name: 'I have saved my Emergency Kit somewhere safe.' }).check()
    await page.getByRole('button', { name: 'Accept & activate' }).click()
    await expect(page.getByText(`“${TENANT}” is now active and yours.`)).toBeVisible({ timeout: 60_000 })
    accountOwner = { email: fresh.email, password: fresh.password, secretKey: kit.secretKey }

    // The space's own mail, pointed at the run's relay: member invitations go out by mail only.
    await page.getByRole('button', { name: 'Sign in', exact: true }).click()
    await submitSignIn(page, accountOwner)
    await expect(newButton(page)).toBeVisible({ timeout: 60_000 })
    await openSettingsOn(owner, accountOwner, 'Email')
    await expect(page.getByRole('heading', { level: 2, name: 'Email' })).toBeVisible()
    await page.getByRole('textbox', { name: /^SMTP host/ }).fill(RELAY.host)
    await page.getByRole('textbox', { name: /^Port/ }).fill(RELAY.port)
    await page.getByRole('textbox', { name: /^From address/ }).fill(`space-mail@${safetyDomain()}`)
    await page.getByRole('switch', { name: /^Use STARTTLS/ }).uncheck()
    await page.getByRole('switch', { name: /^Send invitations by email/ }).check()
    await page.getByRole('button', { name: 'Save email settings' }).click()
    await expect(toast(page, 'Email settings saved.')).toBeVisible()

    const invitee = freshOwner(`e2e.${stack().domain}`)
    await page.getByRole('navigation', { name: 'Settings' }).getByRole('button', { name: 'Members', exact: true }).click()
    await page.getByRole('button', { name: 'Invite member' }).click()
    const invite = page.getByRole('dialog', { name: 'Invite member' })
    // The dialog opens on sharing a link (ADR 0129 decision 1); this member is invited by mail.
    await invite.getByRole('button', { name: 'Invite by email' }).click()
    await invite.getByLabel('Email address').fill(invitee.email)
    await invite.getByRole('button', { name: 'Send invite' }).click()
    await expect(invite.getByText(`Invitation sent to ${invitee.email}.`)).toBeVisible({ timeout: 30_000 })
    const code = recordTyped((await invite.getByTestId('invite-code').textContent())!.trim())
    await invite.getByRole('button', { name: 'Done' }).click()

    const [mail] = await awaitMails(invitee.email, new RegExp(`^You're invited to ${escape(safetyDomain())} `))
    const link = /https:\/\/\S+/.exec(mail!.text)?.[0]
    expect(link, `an invite link in:\n${mail!.text}`).toBeDefined()

    const tab = member.page
    await tab.goto(link!)
    await expect(tab.getByText(`You have been invited to join “${TENANT}” (${safetyDomain()}).`)).toBeVisible({ timeout: 60_000 })
    await tab.getByRole('textbox', { name: 'Code', exact: true }).fill(code)
    await tab.getByRole('button', { name: 'Continue' }).click()
    await tab.getByLabel('Username').fill(handles.member)
    await tab.locator('#join-password').fill(recordTyped(invitee.password))
    await tab.getByRole('button', { name: 'Continue' }).click()
    const memberKit = tab.waitForEvent('download')
    await tab.getByRole('button', { name: 'Download Emergency Kit (PDF)' }).click()
    const kitMember = await readKit(await memberKit)
    await tab.getByRole('checkbox', { name: 'I have saved my Emergency Kit somewhere safe.' }).check()
    await tab.getByRole('button', { name: 'Accept & join' }).click()
    await expect(tab.getByText(`You’ve joined “${TENANT}”.`)).toBeVisible({ timeout: 60_000 })
    accountMember = { email: invitee.email, password: invitee.password, secretKey: kitMember.secretKey }
    await tab.getByRole('button', { name: 'Sign in', exact: true }).click()
    await submitSignIn(tab, accountMember)
    await expect(newButton(tab)).toBeVisible({ timeout: 60_000 })

    // Each finds the other in Who's who, not verified yet, and opens their profile from the row: the ids the own
    // profiles are reached by.
    memberId = await openProfileFromWhoIsWho(owner, accountOwner, handles.member)
    ownerId = await openProfileFromWhoIsWho(member, accountMember, handles.owner)
    expect(ownerId).not.toBe(memberId)
    await expect(safetyState(member.page)).toHaveText('Not verified')
    await expect(member.page.getByTestId('person-safety-scan')).toBeVisible()

    // The owner's own profile: their safety number, their chain code, and the QR of both the member will scan.
    await openOwnProfile(owner, accountOwner, ownerId)
    const mine = owner.page.getByTestId('my-safety-number')
    await expect(mine.getByTestId('my-chain-code')).toBeVisible()
    await expect(owner.page.getByTestId('person-safety')).toHaveCount(0)
  },
)

test(
  'a QR of a different identity — the scanner\'s own — is refused as different keys and marks nothing',
  { tag: '@uc.spaces.enforce-verified-identities:safety-number-verify' },
  async ({ playwright, launchOptions }) => {
    // The member's OWN code: a well-formed veyqid:2 code, of another key and another head than the owner's.
    const qr = await openOwnProfile(member, accountMember, memberId)
    const feed = test.info().outputPath('member-own-qr.y4m')
    await cameraFileOf(qr, feed)

    const scanner = await scanningDevice({ playwright, launchOptions }, feed)
    try {
      const { page } = scanner.device
      await openProfileFromWhoIsWho(scanner.device, accountMember, handles.owner)
      await expect(safetyState(page)).toHaveText('Not verified')
      await page.getByTestId('person-safety-scan').click()
      // The outcome needs a decode: the scanner closes and speaks only once jsQR read a code off the camera.
      await expect(safetyOutcome(page)).toHaveText(/^These are different keys\. Do not mark /, { timeout: 30_000 })
      await expect(safetyState(page)).toHaveText('Not verified')
    } finally {
      await scanner.close()
    }

    // Nothing was written: the member's other device reads the owner's profile and Who's who from the server.
    await openProfileFromWhoIsWho(member, accountMember, handles.owner)
    await expect(safetyState(member.page)).toHaveText('Not verified')
    await expect(member.page.getByTestId('person-safety-verified-by')).toHaveCount(0)
    await openSettingsOn(member, accountMember, 'Who’s who')
    await expect(whoRow(member.page, handles.owner).getByTestId('who-state')).toHaveText('Not verified', { timeout: 30_000 })
  },
)

test(
  'the QR on the owner\'s own profile, scanned by the member, marks the owner verified for the member\'s every device',
  {
    tag: [
      '@uc.spaces.enforce-verified-identities:safety-number-verify',
      '@uc.spaces.enforce-verified-identities:verified-by-counts-for-every-member',
    ],
  },
  async ({ playwright, launchOptions }) => {
    const qr = await openOwnProfile(owner, accountOwner, ownerId)
    const feed = test.info().outputPath('owner-own-qr.y4m')
    await cameraFileOf(qr, feed)

    const scanner = await scanningDevice({ playwright, launchOptions }, feed)
    try {
      const { page } = scanner.device
      await openProfileFromWhoIsWho(scanner.device, accountMember, handles.owner)
      await expect(safetyState(page)).toHaveText('Not verified')
      await page.getByTestId('person-safety-scan').click()
      await expect(safetyOutcome(page)).toHaveText(/ is verified\. Everyone in this space sees that you verified them\.$/,
        { timeout: 30_000 })
      await expect(safetyState(page)).toHaveText('Verified')
      await expect(page.getByTestId('person-safety-verified-by')).toHaveText('Verified by you.')
    } finally {
      await scanner.close()
    }

    // Written, and read back from the server on the member's other device: the profile and Who's who.
    await openProfileFromWhoIsWho(member, accountMember, handles.owner)
    await expect(safetyState(member.page)).toHaveText('Verified', { timeout: 30_000 })
    await expect(member.page.getByTestId('person-safety-verified-by')).toHaveText('Verified by you.')
    await openSettingsOn(member, accountMember, 'Who’s who')
    await expect(whoRow(member.page, handles.owner).getByTestId('who-state')).toHaveText('Verified', { timeout: 30_000 })
  },
)
