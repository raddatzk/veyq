// The docs site's screenshot capture (ADR 0087 §1): the world each language's shots are taken in, and how a shot is
// taken. Started by scripts/site_capture.py, never by hand: it boots lane L1's stack (scripts/run_stack_e2e.py, whose
// `setup` project creates the Server Owner), then runs this on the HOST in the installed Google Chrome — headless,
// through Playwright's `channel: 'chrome'`, so no browser is downloaded and the client renders in the fonts a reader's
// desktop has (the Linux image L1 runs in has none of the client's font stack).
//
// Each language gets a space of its own, with made-up people and content in that language: the English shots are of
// "Example Family" in an English client, the German ones of "Familie Beispiel" in a German one. Everything typed is
// fictional and every picture is drawn here (images.ts) — never a real person's data.
import { chromium, expect, type Browser, type BrowserContext, type Locator, type Page } from '@playwright/test'
import { X509Certificate, createHash, randomBytes } from 'node:crypto'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname } from 'node:path'
import tls from 'node:tls'
import { readKit } from '../src/accounts'
import { confineToInstance, type Confinement } from '../src/egress'
import { awaitMails } from '../src/mail'
import { stack } from '../src/stack'

export type Lang = 'en' | 'de'

/** A desktop wide enough for the sidebar and a two-column settings page, at one and a half times the pixels: sharp in the
 *  site's column, and about half the bytes of twice (measured 2026-11-04: 18 MiB for both languages at 2). */
export const VIEWPORT = { width: 1280, height: 860 }
const SCALE = 1.5
// A desktop Chrome's own user agent: the headless one says "HeadlessChrome", which the device list names "Safari".
const UA = (os: string) => `Mozilla/5.0 (${os}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36`
export const MAC = UA('Macintosh; Intel Mac OS X 10_15_7')
export const WINDOWS = UA('Windows NT 10.0; Win64; x64')

export interface Person {
  username: string
  first: string
  last: string
  email: string
  password: string
  secretKey?: string
}

/** The made-up names of one language's space. */
export function cast(lang: Lang) {
  const password = () => `shots ${randomBytes(18).toString('base64')} Gänsefüßchen`
  const en = lang === 'en'
  const domain = stack().domain
  const suffix = process.env.VEYQ_SHOTS_TENANT_SUFFIX ?? ''
  const mail = en ? 'family.example' : 'familie.example'
  return {
    tenant: en ? 'Example Family' : 'Familie Beispiel',
    tenantDomain: `${en ? 'example' : 'beispiel'}${suffix}.${domain}`,
    owner: { username: en ? 'alex' : 'lena', first: en ? 'Alex' : 'Lena', last: en ? 'Morgan' : 'Becker', email: `${en ? 'alex' : 'lena'}${suffix}@${mail}`, password: password() } as Person,
    member: { username: 'sam', first: 'Sam', last: en ? 'Taylor' : 'Weber', email: `sam${suffix}@${mail}`, password: password() } as Person,
    invitee: { username: 'robin', first: 'Robin', last: en ? 'Lee' : 'Krüger', email: `robin${suffix}@${mail}`, password: password() } as Person,
    guest: en ? 'Grandma Rose' : 'Oma Rosi',
  }
}
export type Cast = ReturnType<typeof cast>

/** A browser context of the capture: one language, one device, confined to the instance (src/egress.ts). */
export interface Device {
  context: BrowserContext
  page: Page
  confinement: Confinement
}

export class World {
  readonly t: <T>(en: T, de: T) => T
  readonly cast: Cast
  owner!: Device
  member!: Device
  inviteCode = ''
  private devices: Device[] = []

  constructor(readonly lang: Lang, readonly browser: Browser) {
    this.t = <T>(en: T, de: T) => (lang === 'en' ? en : de)
    this.cast = cast(lang)
  }

  get origin(): string {
    return `https://${this.cast.tenantDomain}`
  }

  /** A new context in this world's language. */
  async device(options: { userAgent?: string; locale?: string } = {}): Promise<Device> {
    const context = await this.browser.newContext({
      locale: options.locale ?? (this.lang === 'en' ? 'en-US' : 'de-DE'),
      timezoneId: 'Europe/Berlin',
      viewport: VIEWPORT,
      deviceScaleFactor: SCALE,
      userAgent: options.userAgent ?? MAC,
      colorScheme: 'light',
      reducedMotion: 'reduce',
    })
    const confinement = await confineToInstance(context)
    // A page left with unsaved edits asks before it goes; the capture leaves them on purpose (a filled form is the shot).
    context.on('page', (p) => p.on('dialog', (d) => void d.accept().catch(() => undefined)))
    const device = { context, page: await context.newPage(), confinement }
    this.devices.push(device)
    return device
  }

  /** What every context of the world asked outside the instance (a finding to report, not a reason to stop). */
  foreignRequests(): string[] {
    const found: string[] = []
    for (const d of this.devices) {
      try {
        d.confinement.assertConfined()
      } catch (e) {
        found.push(String(e).split('\n').slice(0, 6).join(' '))
      }
    }
    return found
  }

  async close(): Promise<void> {
    for (const d of this.devices) await d.context.close().catch(() => undefined)
  }
}

// ---------------------------------------------------------------------------------------------------------------- browser

function servedChain(host: string): Promise<Buffer[]> {
  return new Promise((resolve, reject) => {
    const socket = tls.connect({ host: '127.0.0.1', port: 443, servername: host, rejectUnauthorized: false }, () => {
      const chain: Buffer[] = []
      const seen = new Set<string>()
      let cert = socket.getPeerCertificate(true)
      while (cert?.raw && !seen.has(cert.fingerprint256)) {
        seen.add(cert.fingerprint256)
        chain.push(cert.raw)
        cert = cert.issuerCertificate
      }
      socket.end()
      resolve(chain)
    })
    socket.on('error', reject)
  })
}

/**
 * The installed Google Chrome, headless, which resolves the run's domain and every subdomain to the host's Traefik and
 * nothing else, and accepts certificates issued by the run's Pebble — by the public key of its intermediate, which signs
 * every certificate of the run. Not `ignoreHTTPSErrors`: a page with a certificate error may register no service worker,
 * so the client would not be the one a reader runs.
 */
export async function launch(): Promise<Browser> {
  const { domain } = stack()
  const chain = await servedChain(domain)
  const issuers = chain.slice(1).map((raw) =>
    createHash('sha256').update(new X509Certificate(raw).publicKey.export({ type: 'spki', format: 'der' })).digest('base64'))
  if (issuers.length === 0) throw new Error(`Traefik serves ${domain} no certificate chain: is the run's stack up?`)
  return chromium.launch({
    channel: 'chrome',
    headless: true,
    args: [
      `--host-resolver-rules=MAP ${domain} 127.0.0.1, MAP *.${domain} 127.0.0.1, EXCLUDE localhost, MAP * ~NOTFOUND`,
      `--ignore-certificate-errors-spki-list=${issuers.join(',')}`,
      '--font-render-hinting=none',
    ],
  })
}

// ---------------------------------------------------------------------------------------------------------------- capture

export const shotsDir = () => `${stack().outDir}/shots`

async function settle(page: Page): Promise<void> {
  await page.evaluate(() => document.fonts.ready.then(() => undefined))
  // Bounded: a held upload (depot-upload-menu) keeps the network busy for good.
  await page.waitForLoadState('networkidle', { timeout: 3_000 }).catch(() => undefined)
  // Two frames: whatever a ResizeObserver or a transition still draws lands before the picture.
  await page.evaluate(() => new Promise<void>((r) => requestAnimationFrame(() => requestAnimationFrame(() => r()))))
}

/** The pointer into the window's bottom-right corner, whatever size the window has now. */
async function parkPointer(page: Page): Promise<void> {
  const size = page.viewportSize() ?? VIEWPORT
  await page.mouse.move(size.width - 2, size.height - 2)
}

/**
 * The shot `<id>.<lang>.png`: [target] is an element (with [pad] pixels of its surroundings), or a page with a region
 * of it. An element taller than the window is taken in a window grown to hold it — scrolled into view instead, it would
 * slide under the shell's sticky top bar. The pointer is parked in a corner and the focus dropped first, so no hover
 * state or focus ring is in the picture, unless [keepPointer] / [keepFocus].
 */
export async function capture(
  w: World,
  id: string,
  target: Locator | { page: Page; clip?: { x: number; y: number; width: number; height: number } },
  options: { pad?: number; keepPointer?: boolean; keepFocus?: boolean } = {},
): Promise<void> {
  // An id may name a sub-folder (`website/folder`): the capture copies those images elsewhere.
  const path = `${shotsDir()}/${id}.${w.lang}.png`
  mkdirSync(dirname(path), { recursive: true })
  if ('page' in target && !('locator' in target)) {
    if (!options.keepPointer) await parkPointer(target.page)
    if (!options.keepFocus) await target.page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur?.())
    await settle(target.page)
    await target.page.screenshot({ path, clip: target.clip, animations: 'disabled', caret: 'hide' })
    return
  }
  const element = target as Locator
  const page = element.page()
  await expect(element).toBeVisible()
  if (!options.keepPointer) await parkPointer(page)
  // A dialog focuses its first control (often its close button), whose ring would be in the picture.
  if (!options.keepFocus) await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur?.())
  // A dialog is taken as it is, scrolled to its top (a filled form leaves it scrolled to the last field); anything else
  // with a little of the page around it, so its border is not the picture's edge.
  const isDialog = await element.evaluate((e) => ['dialog', 'alertdialog'].includes(e.getAttribute('role') ?? ''))
  if (isDialog) await element.evaluate((e) => { for (const x of [e, ...e.querySelectorAll('*')]) if (x.scrollTop) x.scrollTop = 0 })
  const size = page.viewportSize() ?? VIEWPORT
  let box = await element.boundingBox()
  const grown = box !== null && box.y + box.height > size.height - 8
  if (grown) {
    await page.setViewportSize({ width: size.width, height: Math.ceil(Math.max(box!.y, 0) + box!.height + 48) })
    await settle(page)
    box = await element.boundingBox()
  }
  // Below the fold even so (the page lays out its column anew in the taller window): bring it into view.
  if (box && (box.y < 0 || box.y + box.height > (page.viewportSize() ?? size).height)) {
    await element.scrollIntoViewIfNeeded()
    await settle(page)
    box = await element.boundingBox()
  }
  try {
    await settle(page)
    const pad = options.pad ?? (isDialog ? 0 : 12)
    if (pad === 0 || !box) await element.screenshot({ path, animations: 'disabled', caret: 'hide' })
    else {
      const x = Math.max(0, box.x - pad)
      const y = Math.max(0, box.y - pad)
      const now = page.viewportSize() ?? size
      await page.screenshot({
        path, animations: 'disabled', caret: 'hide',
        clip: { x, y, width: Math.min(now.width, box.x + box.width + pad) - x, height: Math.min(now.height, box.y + box.height + pad) - y },
      })
    }
  } finally {
    if (grown) await page.setViewportSize(size)
  }
}

/** The union of [elements]' boxes, with [pad] pixels around, clamped to the viewport. */
export async function union(elements: Locator[], pad = 12): Promise<{ x: number; y: number; width: number; height: number }> {
  const boxes = []
  for (const e of elements) {
    const box = await e.boundingBox()
    if (!box) throw new Error(`no box for ${e}`)
    boxes.push(box)
  }
  const x = Math.max(0, Math.min(...boxes.map((b) => b.x)) - pad)
  const y = Math.max(0, Math.min(...boxes.map((b) => b.y)) - pad)
  const right = Math.min(VIEWPORT.width, Math.max(...boxes.map((b) => b.x + b.width)) + pad)
  const bottom = Math.max(...boxes.map((b) => b.y + b.height)) + pad
  return { x, y, width: right - x, height: bottom - y }
}

// ------------------------------------------------------------------------------------------------------------- the space

const adminOrigin = () => stack().adminOrigin

interface ServerOwner {
  email: string
  password: string
}

/** The Server Owner the L1 `setup` project created (instance.json in the run directory). */
function serverOwner(): ServerOwner {
  return (JSON.parse(readFileSync(`${stack().outDir}/instance.json`, 'utf8')) as { owner: ServerOwner }).owner
}

/** The Server Owner creates the world's space in the admin console and hands back its invite link. Admin-web speaks
 *  English here: the console is the operator's, not part of any shot. */
export async function createTenant(w: World): Promise<string> {
  const context = await w.browser.newContext({ locale: 'en-US', viewport: VIEWPORT })
  try {
    await context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: adminOrigin() })
    const page = await context.newPage()
    const so = serverOwner()
    await page.goto(adminOrigin())
    await page.getByLabel('Email', { exact: true }).fill(so.email)
    await page.getByRole('textbox', { name: /^Password\b/ }).fill(so.password)
    await page.getByRole('button', { name: 'Sign in' }).click()
    await expect(page.getByRole('heading', { name: 'Server administration', level: 1 })).toBeVisible({ timeout: 60_000 })
    await page.getByRole('button', { name: 'Create tenant' }).click()
    const dialog = page.getByRole('dialog', { name: 'Create tenant' })
    await dialog.getByLabel('Tenant name').fill(w.cast.tenant)
    await dialog.getByLabel('Domain').fill(w.cast.tenantDomain)
    await dialog.getByLabel('Who should own this tenant? (email)').fill(w.cast.owner.email)
    await dialog.getByRole('button', { name: 'Create' }).click()
    await expect(dialog.getByText('Tenant created.')).toBeVisible()
    await dialog.getByRole('button', { name: 'Copy link' }).click()
    const link = await page.evaluate(() => navigator.clipboard.readText())
    await dialog.getByRole('button', { name: 'Done' }).click()
    return link
  } finally {
    await context.close()
  }
}

/** Open an invite link, waiting out the moment before Traefik holds the new domain's certificate. */
export async function openLink(page: Page, link: string): Promise<void> {
  await expect(async () => {
    await page.goto(link)
  }).toPass({ timeout: 90_000, intervals: [1_000] })
}

/** The register step of an invitation (owner or member): the code if asked, then username, names and password. Stops
 *  on the filled form, before Continue, so a shot can be taken of it. */
export async function fillJoinForm(w: World, page: Page, who: Person, code?: string): Promise<void> {
  const codeField = page.getByRole('textbox', { name: 'Code', exact: true })
  const username = page.locator('.join-card input[name="username"]')
  await expect(codeField.or(username)).toBeVisible({ timeout: 60_000 })
  if (await codeField.isVisible()) {
    if (!code) throw new Error('the invitation asks for a code and none was read')
    await codeField.fill(code)
    await page.getByRole('button', { name: w.t('Continue', 'Weiter'), exact: true }).click()
  }
  await username.fill(who.username)
  await page.getByLabel(w.t('First name', 'Vorname'), { exact: true }).fill(who.first)
  await page.getByLabel(w.t('Last name', 'Nachname'), { exact: true }).fill(who.last)
  await page.locator('#join-password').fill(who.password)
  await page.locator('#join-password').blur()
}

/** From the filled register form to the kit step with the kit saved and confirmed; returns the kit's PDF path. */
export async function saveKit(w: World, page: Page, who: Person): Promise<string> {
  await page.getByRole('button', { name: w.t('Continue', 'Weiter'), exact: true }).click()
  await expect(page.getByText(w.t('Save your Emergency Kit', 'Notfall-Kit sichern'), { exact: true })).toBeVisible({ timeout: 60_000 })
  const download = page.waitForEvent('download')
  await page.getByRole('button', { name: w.t('Download Emergency Kit (PDF)', 'Notfall-Kit herunterladen (PDF)') }).click()
  const kit = await download
  who.secretKey = (await readKit(kit)).secretKey
  await page.getByRole('checkbox', { name: w.t('I have saved my Emergency Kit somewhere safe.', 'Ich habe mein Notfall-Kit sicher gespeichert.') }).check()
  return (await kit.path())!
}

/** Accept & activate / Accept & join, then sign in on the same device. */
export async function acceptAndSignIn(w: World, page: Page, who: Person): Promise<void> {
  await page.getByRole('button', { name: /^(Accept & (activate|join)|Annehmen & (aktivieren|beitreten))$/ }).click()
  await page.getByRole('button', { name: w.t('Sign in', 'Anmelden'), exact: true }).click({ timeout: 60_000 })
  // The vault is still open in this tab, so the sign-in goes straight to the space — or asks for the password.
  const newButton = page.getByRole('button', { name: w.t('New', 'Neu'), exact: true })
  const password = page.getByRole('textbox', { name: w.t(/^Password\b/, /^Passwort\b/) })
  await expect(newButton.or(password)).toBeVisible({ timeout: 60_000 })
  if (!(await newButton.isVisible())) await signIn(w, page, who)
  await expect(newButton).toBeVisible({ timeout: 60_000 })
}

/** The sign-in form with both secrets; [submit] false leaves it filled for a shot. */
export async function fillSignIn(w: World, page: Page, who: Person): Promise<void> {
  await page.getByLabel(w.t('Email', 'E-Mail'), { exact: true }).fill(who.email)
  await page.getByRole('textbox', { name: w.t(/^Password\b/, /^Passwort\b/) }).fill(who.password)
  await page.locator('#unlock-secret-key').fill(who.secretKey!)
}

export async function signIn(w: World, page: Page, who: Person): Promise<void> {
  if (!page.url().startsWith(w.origin)) await page.goto(`${w.origin}/login`)
  await fillSignIn(w, page, who)
  await page.getByRole('button', { name: w.t('Sign in', 'Anmelden'), exact: true }).click()
  await expect(page.getByRole('button', { name: w.t('New', 'Neu'), exact: true })).toBeVisible({ timeout: 60_000 })
}

/** A section of Settings, through the profile menu, from the space's home (the space's own sections need it active). */
export async function openSettings(w: World, page: Page, section: string): Promise<void> {
  await page.goto(w.origin)
  await expect(page.getByRole('button', { name: w.t('New', 'Neu'), exact: true })).toBeVisible({ timeout: 60_000 })
  await page.locator('.profile-button').click()
  await page.getByRole('menuitem', { name: w.t('Settings', 'Einstellungen') }).click()
  await page.getByRole('navigation', { name: w.t('Settings', 'Einstellungen') }).getByRole('button', { name: section, exact: true }).click()
}

/** The space's mail through the run's relay, as a space owner types it: invitations then go out by mail. */
export async function setUpMail(w: World, page: Page): Promise<void> {
  await openSettings(w, page, w.t('Email', 'E-Mail'))
  await page.getByLabel(w.t(/^SMTP host/, /^SMTP-Host/)).fill('mail.relay.test')
  await page.getByLabel(/^Port/).fill('1025')
  await page.getByLabel(w.t(/^From address/, /^Absenderadresse/)).fill(`hello@${w.cast.tenantDomain}`)
  const starttls = page.getByRole('switch', { name: /^(Use STARTTLS|STARTTLS verwenden)/ })
  if (await starttls.isChecked()) await starttls.click()
  const byMail = page.getByRole('switch', { name: /^(Send invitations by email|Einladungen per E-Mail senden)/ })
  if (!(await byMail.isChecked())) await byMail.click()
  // Wait for the write itself: an invitation sent before it lands is refused as "this space can't send email yet".
  const saved = page.waitForResponse((r) => r.request().method() !== 'GET' && /mail/i.test(new URL(r.url()).pathname) && r.ok(), { timeout: 60_000 })
  await page.getByRole('button', { name: w.t('Save email settings', 'E-Mail-Einstellungen speichern') }).click()
  await saved
}

/** The owner invites [who] by mail; returns the invite link and code. Leaves Settings → Members open. */
export async function inviteMember(w: World, page: Page, who: Person): Promise<{ link: string; code: string }> {
  await openSettings(w, page, w.t('Members', 'Mitglieder'))
  await page.getByRole('button', { name: w.t('Invite member', 'Mitglied einladen') }).click()
  const dialog = page.getByRole('dialog', { name: w.t('Invite member', 'Mitglied einladen') })
  await dialog.getByLabel(w.t('Email address', 'E-Mail-Adresse')).fill(who.email)
  await dialog.getByRole('button', { name: w.t('Send invite', 'Einladung senden') }).click()
  const codeField = dialog.getByTestId('invite-code')
  await expect(codeField).toBeVisible({ timeout: 60_000 })
  const code = ((await codeField.textContent()) ?? '').trim()
  await dialog.getByRole('button', { name: w.t('Done', 'Fertig') }).click()
  const [mail] = await awaitMails(who.email, /./, 1)
  const link = /https:\/\/\S+/.exec(mail!.text)?.[0]
  if (!link) throw new Error(`the invitation to ${who.email} carries no link`)
  return { link, code }
}

/** Write what a later run of the same world needs (only for a reader of the run directory). */
export function remember(w: World): void {
  mkdirSync(shotsDir(), { recursive: true })
  writeFileSync(`${shotsDir()}/world-${w.lang}.json`, JSON.stringify({ cast: w.cast }, null, 2))
}
