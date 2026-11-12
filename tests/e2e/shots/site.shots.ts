// Every screenshot a Use page places (`<Screenshot id=… shows=…>`, site/src/components/Screenshot.astro), taken of the
// web client: one scenario per id, reaching the state the page's `shows` text describes, in English and in German.
// scripts/site_capture.py starts this and copies what it wrote into site/src/assets/screenshots/ — and the project
// website's shots (`website/<id>`, the section "the website" below) into site/src/assets/website/.
//
// The catalogue test runs first and needs no stack: every placed id (a Use page's or the website's) must be a scenario
// here or a reasoned entry of left-out.json, and nothing here may be a shot no page places — so a new `<Screenshot>`
// cannot silently stay a placeholder. Then one test per language walks the steps in order: a `seed` makes data later shots read and stops the
// language when it fails; a `shot` takes one or more pictures and, when it fails, is reported while the next one runs.
// VEYQ_SHOTS_ONLY (comma-separated ids) takes only those shots; every seed still runs.
import { test, expect, type Locator, type Page, type Route } from '@playwright/test'
import { execFileSync } from 'node:child_process'
import { mkdirSync, readFileSync, utimesSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { mailsTo } from '../src/mail'
import { toast } from '../src/toast'
import { drawBadge, drawJpeg, photo, SCENES } from './images'
import {
  acceptAndSignIn, capture, createTenant, fillJoinForm, fillSignIn, inviteMember, launch, openLink, openSettings, remember,
  saveKit, setUpMail, shotsDir, signIn, union, VIEWPORT, WINDOWS, World, type Device, type Lang,
} from './world'

type Step = { kind: 'seed' | 'shot'; ids: string[]; run: (w: World) => Promise<void> }
const steps: Step[] = []
const seed = (name: string, run: Step['run']) => steps.push({ kind: 'seed', ids: [name], run })
const shot = (ids: string | string[], run: Step['run']) => steps.push({ kind: 'shot', ids: typeof ids === 'string' ? [ids] : ids, run })

const REPO = fileURLToPath(new URL('../../../', import.meta.url))
const LEFT_OUT = JSON.parse(readFileSync(new URL('./left-out.json', import.meta.url), 'utf8')) as Record<string, string>

// ------------------------------------------------------------------------------------------------------------- helpers

/** The moment every made-up file was last modified and every photo taken around: stable across runs. */
const MODIFIED = new Date('2026-10-12T09:30:00+02:00')

const newButton = (w: World, page: Page) => page.getByRole('button', { name: w.t('New', 'Neu'), exact: true })
const rowMore = (w: World, page: Page, name: string) =>
  page.getByRole('button', { name: w.t(`More actions for “${name}”`, `Weitere Aktionen für „${name}“`), exact: true })
const openRow = (w: World, page: Page, name: string) => page.getByRole('button', { name: w.t(`Open ${name}`, `${name} öffnen`), exact: true })

async function home(w: World, page: Page): Promise<void> {
  await page.goto(w.origin)
  await expect(newButton(w, page)).toBeVisible({ timeout: 60_000 })
}

/** Open the entry [name] of the open level and wait for its page. */
async function open(w: World, page: Page, name: string): Promise<void> {
  const button = openRow(w, page, name)
  // The level's rows arrive after its toolbar: wait for the entry before choosing how to open it.
  const title = page.getByRole('button', { name, exact: true })
  await expect(button.or(title).first()).toBeVisible({ timeout: 60_000 })
  await (await button.count() ? button : title).first().click()
  await expect(page.getByRole('heading', { level: 1, name })).toBeVisible({ timeout: 60_000 })
}

async function rowAction(w: World, page: Page, name: string, action: string): Promise<void> {
  await rowMore(w, page, name).click()
  await page.getByRole('menuitem', { name: action, exact: true }).click()
}

async function newEntry(w: World, page: Page, entry: string): Promise<void> {
  await newButton(w, page).click()
  await page.getByRole('menuitem', { name: entry, exact: true }).click()
}

async function createFolder(w: World, page: Page, name: string): Promise<void> {
  await newEntry(w, page, w.t('New folder', 'Neuer Ordner'))
  const dialog = page.getByRole('dialog', { name: w.t('Create a folder', 'Ordner anlegen') })
  await dialog.getByLabel('Name').fill(name)
  await dialog.getByRole('button', { name: w.t('Create', 'Erstellen'), exact: true }).click()
  await expect(dialog).toBeHidden()
}

async function createMemory(w: World, page: Page, name: string): Promise<void> {
  await newEntry(w, page, w.t('New Memory', 'Neue Erinnerung'))
  const dialog = page.getByRole('dialog', { name: w.t('Create a Memory', 'Erinnerung anlegen') })
  await dialog.getByLabel('Name').fill(name)
  await dialog.getByRole('button', { name: w.t('Create', 'Erstellen'), exact: true }).click()
  await expect(dialog).toBeHidden()
}

/** A made-up file on disk, last modified at [MODIFIED]: an upload takes its date from the file. */
function file(w: World, name: string, bytes: Uint8Array | string): string {
  const dir = `${shotsDir()}/files/${w.lang}`
  mkdirSync(dir, { recursive: true })
  const path = `${dir}/${name}`
  writeFileSync(path, bytes)
  utimesSync(path, MODIFIED, MODIFIED)
  return path
}

/** A PDF of a few lines, its page the size of a short letter so the facts below the viewer stay in reach. */
function letterPdf(lines: string[]): Uint8Array {
  const text = lines.map((l, i) => `${i === 0 ? '' : '0 -26 Td '}(${l}) Tj`).join(' ')
  const content = `BT /F1 15 Tf 48 300 Td ${text} ET`
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 520 360] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>',
    `<< /Length ${content.length} >>\nstream\n${content}\nendstream`,
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
  ]
  let body = '%PDF-1.4\n'
  const offsets: number[] = []
  objects.forEach((o, i) => {
    offsets.push(body.length)
    body += `${i + 1} 0 obj\n${o}\nendobj\n`
  })
  const xref = body.length
  body += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`
  for (const o of offsets) body += `${String(o).padStart(10, '0')} 00000 n \n`
  body += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`
  return new TextEncoder().encode(body)
}

/** Upload [paths] into the open level through New → Upload files, and wait until each is a row. */
async function upload(w: World, page: Page, paths: string[], wait = true): Promise<void> {
  const chooser = page.waitForEvent('filechooser')
  await newEntry(w, page, w.t('Upload files', 'Dateien hochladen'))
  await (await chooser).setFiles(paths)
  if (!wait) return
  for (const path of paths) {
    const name = path.split('/').pop()!
    await expect(page.getByRole('button', { name: w.t(`Download ${name}`, `${name} herunterladen`), exact: true }).first()).toBeEnabled({ timeout: 60_000 })
  }
}

async function listView(w: World, page: Page): Promise<void> {
  const list = page.getByRole('button', { name: w.t('List view', 'Listenansicht'), exact: true })
  if (await list.count()) await list.click()
  await expect(page.locator('.veyq-level-list.is-list').first()).toBeVisible()
}

/** The node header's action [label]: its button, or its entry of the ⋯ menu. */
async function headerAction(w: World, page: Page, label: string): Promise<void> {
  const header = page.locator('header.veyq-node-header')
  const button = header.getByRole('button', { name: label, exact: true })
  const more = header.getByRole('button', { name: w.t(/^More actions for /, /^Weitere Aktionen für /) })
  await expect(button.or(more).first()).toBeVisible()
  await expect.poll(() => header.evaluateAll((all) => all.every((h) => (h as HTMLElement).dataset.layoutWidth === String((h as HTMLElement).clientWidth)))).toBe(true)
  if (await button.isVisible()) return button.click()
  await more.click()
  await page.getByRole('menuitem', { name: label, exact: true }).click()
}

const dialog = (page: Page, name: string | RegExp) => page.getByRole('dialog', { name })
async function closeDialogs(page: Page): Promise<void> {
  for (let i = 0; i < 3 && (await page.getByRole('dialog').count()) > 0; i++) await page.keyboard.press('Escape')
}

/** Photos into the open Memory through Upload, each a drawn scene taken a little after the last. */
async function uploadPhotos(w: World, page: Page, first: number, count: number, day: string, at?: { lat: number; lng: number }): Promise<void> {
  const files = []
  for (let i = 0; i < count; i++) {
    const n = first + i
    const minute = String(10 + n * 7).padStart(2, '0')
    const where = at && { lat: at.lat + (n % 3) * 0.004, lng: at.lng + (n % 4) * 0.005 }
    files.push({ name: `IMG_${2040 + n}.jpg`, mimeType: 'image/jpeg', buffer: await photo(page, n, `${day}T${String(9 + (n % 8)).padStart(2, '0')}:${minute}:00`, where) })
  }
  const chooser = page.waitForEvent('filechooser')
  await page.getByRole('button', { name: w.t('Upload', 'Hochladen'), exact: true }).click()
  await (await chooser).setFiles(files)
}

async function thumbnailsDecoded(page: Page, count: number): Promise<void> {
  await expect.poll(() => page.locator('img.media-tile-img').evaluateAll((imgs) =>
    imgs.filter((i) => (i as HTMLImageElement).src.startsWith('blob:') && (i as HTMLImageElement).naturalWidth > 0).length),
  { timeout: 120_000 }).toBeGreaterThanOrEqual(count)
}

/** Every made-up name of a language's content. */
function names(w: World) {
  return {
    documents: w.t('Documents', 'Dokumente'),
    party: w.t('Summer party', 'Sommerfest'),
    garden: w.t('Garden', 'Garten'),
    holidays: w.t('Holidays', 'Urlaub'),
    plans: w.t('Plans', 'Pläne'),
    pdf: w.t('Rental agreement.pdf', 'Mietvertrag.pdf'),
    txt: w.t('Shopping list.txt', 'Einkaufsliste.txt'),
    png: w.t('Floor plan.png', 'Grundriss.png'),
    invitation: w.t('Invitation.pdf', 'Einladung.pdf'),
    seeds: w.t('Seed order.pdf', 'Saatgut-Bestellung.pdf'),
    rome: w.t('Rome 2026', 'Rom 2026'),
    lake: w.t('Lake weekend', 'Wochenende am See'),
    partyPhotos: w.t('Party photos', 'Fotos vom Fest'),
    gardenDiary: w.t('Garden diary', 'Gartentagebuch'),
    dropParty: w.t('Your photos of the party', 'Eure Fotos vom Fest'),
    dropGarden: w.t('Photos of the beds', 'Fotos der Beete'),
    bookClub: w.t('Book club', 'Lesekreis'),
    hiking: w.t('Hiking', 'Wandergruppe'),
    choir: w.t('Choir', 'Chor'),
    pot: w.t('Lisbon trip', 'Lissabon-Reise'),
  }
}

// ---------------------------------------------------------------------------------------------------------- the space

let kitPdf = ''

seed('space', async (w) => {
  const link = await createTenant(w)
  w.owner = await w.device()
  const { page } = w.owner
  await openLink(page, link)
  await fillJoinForm(w, page, w.cast.owner)
  await saveKit(w, page, w.cast.owner)
  await acceptAndSignIn(w, page, w.cast.owner)
  await setUpMail(w, page)
  const invite = await inviteMember(w, page, w.cast.member)
  w.member = await w.device()
  await openLink(w.member.page, invite.link)
  await fillJoinForm(w, w.member.page, w.cast.member, invite.code)
  await saveKit(w, w.member.page, w.cast.member)
  await acceptAndSignIn(w, w.member.page, w.cast.member)
  remember(w)
})

shot(['getting-started-accept-form', 'getting-started-accept-kit'], async (w) => {
  const invite = await inviteMember(w, w.owner.page, w.cast.invitee)
  const { page } = await w.device()
  await openLink(page, invite.link)
  await fillJoinForm(w, page, w.cast.invitee, invite.code)
  await capture(w, 'getting-started-accept-form', page.locator('.join-card'))
  kitPdf = await saveKit(w, page, w.cast.invitee)
  await capture(w, 'getting-started-accept-kit', page.locator('.join-card'))
})

shot('getting-started-sign-in', async (w) => {
  const { page } = await w.device()
  await page.goto(`${w.origin}/login`)
  await fillSignIn(w, page, w.cast.owner)
  await page.getByRole('checkbox', { name: w.t(/^Stay signed in on this device/, /^Auf diesem Gerät angemeldet bleiben/) }).check()
  await capture(w, 'getting-started-sign-in', page.locator('.login-card'))
})

shot('account-pairing-sign-in-code', async (w) => {
  const { page } = await w.device()
  await page.goto(`${w.origin}/login`)
  await page.getByRole('button', { name: w.t('Sign in with a QR code', 'Über QR-Code anmelden') }).click()
  await expect(page.locator('#sign-in-code')).toBeVisible()
  await expect(page.locator('.login-card img').first()).toBeVisible()
  await capture(w, 'account-pairing-sign-in-code', page.locator('.login-card'))
})

shot('account-recover-key', async (w) => {
  const { page } = await w.device()
  await page.goto(`${w.origin}/login`)
  await page.getByRole('button', { name: w.t('Forgot your password?', 'Passwort vergessen?') }).or(
    page.getByRole('link', { name: w.t('Forgot your password?', 'Passwort vergessen?') })).first().click()
  await page.getByLabel(w.t('Your email address', 'Deine E-Mail-Adresse')).fill(w.cast.owner.email)
  await page.getByRole('button', { name: w.t('Send me a link', 'Link schicken') }).click()
  let link = ''
  await expect.poll(async () => {
    const mails = await mailsTo(w.cast.owner.email, /./)
    link = mails.map((m) => /https:\/\/\S+\/recover#\S+/.exec(m.text)?.[0] ?? '').find(Boolean) ?? ''
    return link
  }, { timeout: 60_000 }).not.toBe('')
  await page.goto(link)
  await expect(page.locator('input[type="password"], #recover-secret-key').first()).toBeVisible({ timeout: 60_000 })
  await capture(w, 'account-recover-key', page.locator('.login-card'))
})

// --------------------------------------------------------------------------------------------------------- account

shot('account-appearance', async (w) => {
  const { page } = w.owner
  await openSettings(w, page, w.t('Appearance and language', 'Darstellung und Sprache'))
  await capture(w, 'account-appearance', page.locator('.settings-panel'))
})

shot('account-profile', async (w) => {
  const { page } = w.owner
  await openSettings(w, page, w.t('Your profile', 'Dein Profil'))
  const picture = await drawBadge(page, w.t('AM', 'LB'), ['#f2a65a', '#c9573a'])
  await page.getByRole('button', { name: /^(Choose|Change) picture$|^Choose a picture$|^Bild (wählen|ändern)$/ }).first().click()
  const pick = dialog(page, w.t('Profile picture', 'Profilbild'))
  await pick.getByTestId('profile-picture-file').setInputFiles({ name: 'portrait.png', mimeType: 'image/png', buffer: picture })
  await pick.getByTestId('profile-picture-use').click()
  await page.getByRole('button', { name: w.t('Save profile', 'Profil speichern') }).click()
  await expect(toast(page, w.t('Profile updated.', 'Profil aktualisiert.'), w.lang)).toBeVisible().catch(() => undefined)
  await page.reload()
  await expect(page.locator('.settings-panel')).toBeVisible({ timeout: 60_000 })
  await capture(w, 'account-profile', page.locator('.settings-panel'))
})

shot('account-change-password', async (w) => {
  const { page } = w.owner
  await openSettings(w, page, w.t('Change password', 'Passwort ändern'))
  const panel = page.locator('.settings-panel')
  await panel.locator('input[autocomplete="current-password"]').fill(w.cast.owner.password)
  await page.locator('#change-password-new').fill('a new and long passphrase for the example')
  await page.locator('#change-password-confirm').fill('a new and long passphrase for the example')
  await panel.locator('label.settings-field').last().locator('input').first().fill(w.cast.owner.secretKey!)
  await page.locator('#change-password-confirm').blur()
  await capture(w, 'account-change-password', panel)
})

shot('account-change-email', async (w) => {
  const { page } = w.owner
  await openSettings(w, page, w.t('Secure your account', 'Konto absichern'))
  await page.getByTestId('change-email-open').click()
  const section = page.locator('.settings-panel > section.settings-form').first()
  await section.getByLabel(w.t('New email address', 'Neue E-Mail-Adresse')).fill(w.t('alex.morgan@family.example', 'lena.becker@familie.example'))
  await section.getByRole('textbox', { name: w.t(/^Password\b/, /^Passwort\b/) }).fill(w.cast.owner.password)
  await section.locator('input[autocomplete="off"], input').last().focus()
  const secret = section.getByLabel('Secret Key', { exact: false })
  await secret.last().fill(w.cast.owner.secretKey!)
  await capture(w, 'account-change-email', section)
})

shot('account-replace-keys', async (w) => {
  const { page } = w.owner
  await openSettings(w, page, w.t('Secure your account', 'Konto absichern'))
  const section = page.locator('section.settings-form').filter({ has: page.getByRole('heading', { level: 2, name: w.t('Secure your account', 'Konto absichern') }) })
  await page.locator('#rotation-password').fill(w.cast.owner.password)
  await page.locator('#rotation-secret-key').fill(w.cast.owner.secretKey!)
  await expect(page.getByRole('checkbox', { name: w.t('Also choose a new password', 'Auch ein neues Passwort wählen') })).toBeChecked()
  await capture(w, 'account-replace-keys', section)
})

shot('account-sessions-list', async (w) => {
  const laptop = await w.device({ userAgent: WINDOWS })
  await signIn(w, laptop.page, w.cast.owner)
  const { page } = w.owner
  await openSettings(w, page, w.t('Devices and sessions', 'Geräte und Sitzungen'))
  const section = page.locator('.settings-panel > section.settings-form').first()
  await expect(section.getByRole('button', { name: w.t('Rename this device', 'Dieses Gerät umbenennen') })).toHaveCount(2, { timeout: 30_000 })
  await capture(w, 'account-sessions-list', section)
})

shot('account-safety-number', async (w) => {
  const { page } = w.owner
  await openSettings(w, page, w.t('Who’s who', 'Wer ist wer'))
  await page.getByTestId('who-is-who').locator('.member-row').filter({ hasText: `@${w.cast.member.username}` }).locator('a.who-name').click()
  const safety = page.getByTestId('person-safety')
  // The digits are derived in the page after the block appears: wait for its buttons, which come with them.
  await expect(safety.getByRole('button', { name: w.t('Compare digits', 'Ziffern vergleichen') })).toBeVisible({ timeout: 60_000 })
  await capture(w, 'account-safety-number', safety)
})

// --------------------------------------------------------------------------------------------------------- owners

shot('owners-invite', async (w) => {
  const { page } = w.owner
  await openSettings(w, page, w.t('Members', 'Mitglieder'))
  await page.getByRole('button', { name: w.t('Invite member', 'Mitglied einladen') }).click()
  const d = dialog(page, w.t('Invite member', 'Mitglied einladen'))
  await d.getByLabel(w.t('Email address', 'E-Mail-Adresse')).fill(w.t('jordan@family.example', 'jonas@familie.example'))
  await capture(w, 'owners-invite', d)
  await closeDialogs(page)
})

shot('owners-email', async (w) => {
  const { page } = w.owner
  await openSettings(w, page, w.t('Email', 'E-Mail'))
  await page.setViewportSize({ width: VIEWPORT.width, height: 1600 })
  try {
    await capture(w, 'owners-email', page.locator('.settings-panel'))
  } finally {
    await page.setViewportSize(VIEWPORT)
  }
})

shot('owners-storage', async (w) => {
  const { page } = w.owner
  await openSettings(w, page, w.t('Storage', 'Speicher'))
  await capture(w, 'owners-storage', page.locator('.settings-panel'))
})

shot('owners-terms', async (w) => {
  const { page } = w.owner
  await openSettings(w, page, w.t('Terms', 'Nutzungsbedingungen'))
  const panel = page.locator('.settings-panel')
  await panel.getByRole('button', { name: w.t('Add language', 'Sprache hinzufügen'), exact: true }).first().click()
  const add = dialog(page, w.t('Add a language', 'Sprache hinzufügen'))
  await add.getByRole('combobox', { name: w.t('Language', 'Sprache'), exact: true }).click()
  await page.getByRole('option', { name: 'Deutsch', exact: true }).click()
  await add.getByRole('textbox', { name: 'Markdown' }).fill(w.t('Be kind to each other.', 'Geht freundlich miteinander um.'))
  await add.getByRole('button', { name: w.t('Save', 'Speichern'), exact: true }).click()
  await expect(add).toBeHidden()
  const insert = panel.getByRole('button', { name: w.t('Insert template', 'Vorlage einfügen') }).first()
  await insert.click()
  const confirm = page.getByRole('alertdialog').or(page.getByRole('dialog')).last()
  await confirm.getByRole('button', { name: w.t('Insert template', 'Vorlage einfügen') }).click()
  await expect(panel.locator('.terms-preview').first()).toBeVisible()
  await page.setViewportSize({ width: VIEWPORT.width, height: 1800 })
  try {
    const clip = await union([panel.getByRole('heading', { level: 2 }).first(), panel.locator('.terms-preview').first()], 16)
    clip.height = Math.min(clip.height, 900) // the head of the preview is enough to show it is one
    await capture(w, 'owners-terms', { page, clip })
  } finally {
    await page.setViewportSize(VIEWPORT)
  }
})

// ---------------------------------------------------------------------------------------------- spaces and Depot

seed('folders', async (w) => {
  const { page } = w.owner
  const n = names(w)
  await home(w, page)
  for (const folder of [n.documents, n.party, n.garden, n.holidays]) await createFolder(w, page, folder)
  await open(w, page, n.documents)
  await upload(w, page, [
    file(w, n.pdf, letterPdf(w.t(
      ['Rental agreement', 'Flat 3, Example Street 12', 'Start: 1 November 2026', 'Signed by both parties.'],
      ['Mietvertrag', 'Wohnung 3, Beispielstraße 12', 'Beginn: 1. November 2026', 'Von beiden Seiten unterschrieben.']))),
    file(w, n.txt, w.t('Milk\nBread\nApples\nCoffee\n', 'Milch\nBrot\nÄpfel\nKaffee\n')),
    file(w, n.png, await drawBadge(page, '▦', ['#dfe9f3', '#9db7d1'])),
  ])
  // Garden: a sub-folder, a Memory, a file and a drop box, side by side.
  await home(w, page)
  await open(w, page, n.garden)
  await createFolder(w, page, n.plans)
  // The space's favourite: a folder one level down, so no tile of the space's home wears the heart.
  await page.locator('.veyq-level-list [data-action="favorite"]').first().click()
  await createMemory(w, page, n.gardenDiary)
  await upload(w, page, [file(w, n.seeds, letterPdf([w.t('Seed order', 'Saatgut-Bestellung')]))])
  await createDropBox(w, page, n.dropGarden)
  // The summer party: a Memory, a file and a drop box in one folder.
  await home(w, page)
  await open(w, page, n.party)
  await createMemory(w, page, n.partyPhotos)
  await upload(w, page, [file(w, n.invitation, letterPdf([w.t('You are invited to our summer party!', 'Ihr seid zu unserem Sommerfest eingeladen!')]))])
  await createDropBox(w, page, n.dropParty)
})

async function createDropBox(w: World, page: Page, name: string): Promise<void> {
  await headerAction(w, page, w.t('Share', 'Teilen'))
  const list = dialog(page, w.t('Share this folder', 'Diesen Ordner teilen'))
  const create = dialog(page, w.t('New share', 'Neuer Link'))
  await expect(list.or(create)).toBeVisible()
  if (await list.isVisible()) await list.getByRole('button', { name: w.t('New share', 'Neuer Link') }).click()
  await create.getByRole('button', { name: w.t('Add files only', 'Nur Dateien hinzufügen') }).click()
  await create.getByLabel('Name').fill(name)
  await create.getByRole('button', { name: w.t('Create link', 'Link erstellen') }).click()
  const view = page.getByRole('dialog').filter({ has: page.getByRole('textbox', { name: w.t('Public link', 'Öffentlicher Link') }) })
  await expect(view).toBeVisible({ timeout: 30_000 })
  await view.getByRole('button', { name: w.t('Done', 'Fertig') }).click()
  await closeDialogs(page)
  await expect(openRow(w, page, name)).toBeVisible()
}

seed('groups', async (w) => {
  const { page } = w.owner
  const n = names(w)
  await home(w, page)
  for (const group of [n.bookClub, n.hiking]) {
    await page.getByRole('button', { name: w.t('New group', 'Neue Gruppe') }).click()
    const d = dialog(page, w.t('New group', 'Neue Gruppe'))
    await d.getByLabel(w.t('Group name', 'Gruppenname')).fill(group)
    await d.getByRole('button', { name: w.t('Create', 'Erstellen'), exact: true }).click()
    await expect(d).toBeHidden({ timeout: 60_000 })
    await home(w, page)
  }
})

shot('getting-started-sidebar', async (w) => {
  const { page } = w.owner
  await home(w, page)
  await capture(w, 'getting-started-sidebar', { page })
})

shot('spaces-sidebar', async (w) => {
  const { page } = w.owner
  await home(w, page)
  await capture(w, 'spaces-sidebar', page.locator('aside.tenant-sidebar'))
})

shot('spaces-new-group', async (w) => {
  const { page } = w.owner
  await home(w, page)
  await page.getByRole('button', { name: w.t('New group', 'Neue Gruppe') }).click()
  const d = dialog(page, w.t('New group', 'Neue Gruppe'))
  await d.getByLabel(w.t('Group name', 'Gruppenname')).fill(w.t('Neighbours', 'Nachbarschaft'))
  await capture(w, 'spaces-new-group', d)
  await closeDialogs(page)
})

shot('spaces-new-menu', async (w) => {
  const { page } = w.owner
  await home(w, page)
  await open(w, page, names(w).holidays)
  await newButton(w, page).click()
  const menu = page.getByRole('menu')
  await expect(menu.getByRole('menuitem').first()).toBeVisible()
  const clip = await union([page.locator('.veyq-level-head'), menu], 16)
  await capture(w, 'spaces-new-menu', { page, clip }, { keepFocus: true })
  await page.keyboard.press('Escape')
})

shot('depot-folder-with-files', async (w) => {
  const { page } = w.owner
  await home(w, page)
  await open(w, page, names(w).documents)
  await listView(w, page)
  await capture(w, 'depot-folder-with-files', page.locator('.veyq-level-view'))
})

shot('depot-rename-dialog', async (w) => {
  const { page } = w.owner
  const n = names(w)
  await home(w, page)
  await open(w, page, n.documents)
  await rowAction(w, page, n.pdf, w.t('Rename', 'Umbenennen'))
  const d = dialog(page, w.t('Rename', 'Umbenennen'))
  await expect(d.getByLabel('Name')).toBeFocused()
  await capture(w, 'depot-rename-dialog', d, { keepFocus: true })
  await closeDialogs(page)
})

shot('depot-replace-confirm', async (w) => {
  const { page } = w.owner
  const n = names(w)
  await home(w, page)
  await open(w, page, n.documents)
  await upload(w, page, [file(w, n.txt, w.t('Milk\nBread\nApples\nCoffee\nButter\n', 'Milch\nBrot\nÄpfel\nKaffee\nButter\n'))], false)
  const d = dialog(page, w.t('Replace file', 'Datei ersetzen'))
  await capture(w, 'depot-replace-confirm', d)
  await d.getByRole('button', { name: w.t('Cancel', 'Abbrechen') }).click()
})

shot('depot-select-bar', async (w) => {
  const { page } = w.owner
  const n = names(w)
  await home(w, page)
  await open(w, page, n.documents)
  await listView(w, page)
  await page.getByRole('button', { name: w.t('Select', 'Auswählen'), exact: true }).click()
  for (const name of [n.pdf, n.txt, n.png]) await page.getByRole('checkbox', { name, exact: true }).check({ force: true })
  await expect(page.getByRole('group', { name: w.t('Selection', 'Auswahl') })).toBeVisible()
  await capture(w, 'depot-select-bar', page.locator('.veyq-level-view'))
  await page.keyboard.press('Escape')
})

shot('depot-file-page', async (w) => {
  const { page } = w.owner
  const n = names(w)
  await home(w, page)
  await open(w, page, n.documents)
  await openRow(w, page, n.pdf).click()
  await expect(page.locator('.pdf-page canvas').first()).toBeVisible({ timeout: 60_000 })
  await expect(page.locator('.pdf-page .pdf-text-layer').first()).toBeAttached({ timeout: 60_000 })
  await capture(w, 'depot-file-page', page.locator('.veyq-node-page'))
})

shot('getting-started-kit-pdf', async (w) => {
  if (!kitPdf) throw new Error('no Emergency Kit was saved (getting-started-accept-kit runs first)')
  const { page } = w.owner
  await home(w, page)
  await page.locator('aside.tenant-sidebar').getByRole('link', { name: w.t('Personal', 'Persönlich') }).first().click()
  await expect(newButton(w, page)).toBeVisible({ timeout: 60_000 })
  const name = w.t('Emergency Kit.pdf', 'Notfall-Kit.pdf')
  await upload(w, page, [file(w, name, readFileSync(kitPdf))])
  await openRow(w, page, name).click()
  const sheet = page.locator('.pdf-page').first()
  await expect(sheet.locator('canvas')).toBeVisible({ timeout: 60_000 })
  await capture(w, 'getting-started-kit-pdf', sheet)
})

shot('depot-copy-dialog', async (w) => {
  const { page } = w.owner
  const n = names(w)
  await home(w, page)
  await rowAction(w, page, n.documents, w.t('Copy to…', 'Kopieren nach …'))
  const d = dialog(page, w.t(`Copy “${n.documents}”`, `„${n.documents}“ kopieren`))
  await expect(d.getByText(w.t(/will be copied to/, /(wird|werden) nach/)).first()).toBeVisible({ timeout: 60_000 })
  await capture(w, 'depot-copy-dialog', d)
  await closeDialogs(page)
})

shot('depot-move-dialog', async (w) => {
  const { page } = w.owner
  const n = names(w)
  await home(w, page)
  await open(w, page, n.garden)
  await rowAction(w, page, n.seeds, w.t('Move to…', 'Verschieben nach …'))
  const d = dialog(page, w.t(`Move “${n.seeds}”`, `„${n.seeds}“ verschieben`))
  await expect(d.getByRole('button', { name: w.t('Move here', 'Hierher verschieben') })).toBeVisible()
  await capture(w, 'depot-move-dialog', d)
  await closeDialogs(page)
})

shot('depot-level-list', async (w) => {
  const { page } = w.owner
  await home(w, page)
  await open(w, page, names(w).garden)
  await listView(w, page)
  await capture(w, 'depot-level-list', page.locator('.veyq-level-view'))
})

shot('applications-one-folder', async (w) => {
  const { page } = w.owner
  await home(w, page)
  await open(w, page, names(w).party)
  await listView(w, page)
  await capture(w, 'applications-one-folder', page.locator('.veyq-level-view'))
})

shot('depot-dropbox-links', async (w) => {
  const { page } = w.owner
  const n = names(w)
  await home(w, page)
  await open(w, page, n.party)
  await open(w, page, n.dropParty)
  await headerAction(w, page, w.t('Share link', 'Link teilen'))
  const d = dialog(page, w.t('Drop box links', 'Links zum Briefkasten'))
  await capture(w, 'depot-dropbox-links', d)
  await closeDialogs(page)
})

shot('depot-cover-dialog', async (w) => {
  const { page } = w.owner
  const n = names(w)
  await home(w, page)
  await rowAction(w, page, n.party, w.t('Set cover', 'Cover festlegen'))
  let d = dialog(page, 'Cover')
  await d.locator('input.veyq-cover-file').setInputFiles({ name: 'party.jpg', mimeType: 'image/jpeg', buffer: await drawJpeg(page, SCENES[5]!) })
  await d.getByRole('button', { name: w.t('Save cover', 'Cover speichern') }).click()
  await expect(d).toBeHidden({ timeout: 60_000 })
  await rowAction(w, page, n.party, w.t('Change cover', 'Cover ändern'))
  d = dialog(page, 'Cover')
  await expect(d.locator('.veyq-cropper img')).toBeVisible({ timeout: 60_000 })
  await capture(w, 'depot-cover-dialog', d)
  await closeDialogs(page)
})

shot('depot-delete-undo', async (w) => {
  const { page } = w.owner
  const name = w.t('Old notes.txt', 'Alte Notizen.txt')
  await home(w, page)
  await open(w, page, names(w).holidays)
  await upload(w, page, [file(w, name, w.t('Nothing important.\n', 'Nichts Wichtiges.\n'))])
  await rowAction(w, page, name, w.t('Delete', 'Löschen'))
  await dialog(page, w.t('Delete file', 'Datei löschen')).getByRole('button', { name: w.t('Delete', 'Löschen'), exact: true }).click()
  const undo = page.locator('.veyq-undo-toast')
  await expect(undo).toBeVisible()
  await undo.hover()
  await page.waitForTimeout(1_200) // the live region's copy of the text goes after about a second
  await capture(w, 'depot-delete-undo', undo, { keepPointer: true })
  await undo.getByRole('button', { name: w.t('Undo', 'Rückgängig') }).click()
})

shot('spaces-trash', async (w) => {
  const { page } = w.owner
  const gone = [w.t('Packing list.txt', 'Packliste.txt'), w.t('Ferry times.pdf', 'Fährzeiten.pdf')]
  await home(w, page)
  await open(w, page, names(w).holidays)
  await upload(w, page, [file(w, gone[0]!, w.t('Sun cream\nHats\n', 'Sonnencreme\nHüte\n')), file(w, gone[1]!, letterPdf([w.t('Ferry times', 'Fährzeiten')]))])
  for (const name of gone) {
    await rowAction(w, page, name, w.t('Delete', 'Löschen'))
    await dialog(page, w.t('Delete file', 'Datei löschen')).getByRole('button', { name: w.t('Delete', 'Löschen'), exact: true }).click()
    await expect(openRow(w, page, name)).toHaveCount(0, { timeout: 30_000 })
  }
  await page.getByRole('button', { name: w.t('Trash', 'Papierkorb'), exact: true }).click()
  const d = dialog(page, w.t('Trash', 'Papierkorb'))
  await expect(d.getByText(gone[1]!)).toBeVisible({ timeout: 30_000 })
  await capture(w, 'spaces-trash', d)
  await closeDialogs(page)
})

shot('depot-upload-menu', async (w) => {
  const { page } = w.owner
  await home(w, page)
  await open(w, page, names(w).holidays)
  let release: () => void = () => undefined
  const gate = new Promise<void>((resolve) => (release = resolve))
  let held = 0
  let on = true
  await page.route((url) => url.searchParams.has('X-Amz-Signature'), async (route) => {
    if (!on || route.request().method() !== 'PUT') return route.fallback()
    held += 1
    await gate
    return route.fallback()
  })
  try {
    const big = Buffer.alloc(3 * 1024 * 1024, 7)
    await upload(w, page, [file(w, w.t('Holiday video.mov', 'Urlaubsvideo.mov'), big)], false)
    await expect.poll(() => held, { timeout: 60_000 }).toBeGreaterThan(0)
    await page.getByRole('button', { name: 'Uploads' }).click()
    await expect(page.locator('#uploads-panel')).toBeVisible()
    await newButton(w, page).dispatchEvent('click')
    const menu = page.getByRole('menu')
    await expect(menu.getByRole('menuitem').first()).toBeVisible()
    await expect(page.locator('#uploads-panel')).toBeVisible()
    const clip = await union([page.locator('.veyq-level-head'), menu, page.locator('#uploads-panel'), page.getByRole('button', { name: 'Uploads' })], 16)
    await capture(w, 'depot-upload-menu', { page, clip }, { keepFocus: true })
  } finally {
    on = false
    release()
    await page.keyboard.press('Escape')
  }
})

shot('spaces-group-members', async (w) => {
  const { page } = w.owner
  await openGroupSettings(w, page, names(w).bookClub)
  const field = page.locator('#group-add-username')
  await field.fill(w.cast.member.username)
  await page.getByRole('button', { name: w.t('Add', 'Hinzufügen'), exact: true }).click()
  await expect(page.locator('.member-row').filter({ hasText: `@${w.cast.member.username}` })).toBeVisible({ timeout: 60_000 })
  await capture(w, 'spaces-group-members', page.locator('section.settings-panel'))
})

shot('spaces-group-icon', async (w) => {
  const { page } = w.owner
  await openGroupSettings(w, page, names(w).hiking)
  await page.getByRole('button', { name: w.t('Add icon', 'Icon hinzufügen') }).click()
  const d = dialog(page, w.t('Space icon', 'Bereichs-Icon'))
  await d.locator('input.space-icon-file').setInputFiles({ name: 'hiking.png', mimeType: 'image/png', buffer: await drawBadge(page, '⛰', ['#9fd3a8', '#3f7d5a']) })
  await expect(d.locator('img').first()).toBeVisible({ timeout: 30_000 })
  await capture(w, 'spaces-group-icon', d)
  await closeDialogs(page)
})

async function openGroupSettings(w: World, page: Page, group: string): Promise<void> {
  await home(w, page)
  await page.locator('a.desktop-group-link').filter({ hasText: group }).click()
  await expect(newButton(w, page)).toBeVisible({ timeout: 60_000 })
  await page.locator('.profile-button').click()
  await page.getByRole('menuitem', { name: w.t('Settings', 'Einstellungen') }).click()
  await expect(page.locator('#group-add-username')).toBeVisible({ timeout: 60_000 })
}

shot('spaces-group-invitations', async (w) => {
  const { page } = w.owner
  const n = names(w)
  // The owner asks to be asked: a group adds them only once they accept.
  await openSettings(w, page, w.t('Your profile', 'Dein Profil'))
  const consent = page.locator('#require-group-add-consent')
  if (!(await consent.isChecked())) await consent.check()
  await page.getByRole('button', { name: w.t('Save profile', 'Profil speichern') }).click()
  await page.waitForTimeout(1_500)
  // Sam makes a group and adds the owner.
  const sam = w.member.page
  await home(w, sam)
  await sam.getByRole('button', { name: w.t('New group', 'Neue Gruppe') }).click()
  const d = dialog(sam, w.t('New group', 'Neue Gruppe'))
  await d.getByLabel(w.t('Group name', 'Gruppenname')).fill(n.choir)
  await d.getByRole('button', { name: w.t('Create', 'Erstellen'), exact: true }).click()
  await expect(d).toBeHidden({ timeout: 60_000 })
  await openGroupSettings(w, sam, n.choir)
  await sam.locator('#group-add-username').fill(w.cast.owner.username)
  await sam.getByRole('button', { name: w.t('Add', 'Hinzufügen'), exact: true }).click()
  await page.waitForTimeout(2_000)
  await home(w, page)
  await page.getByRole('button', { name: w.t('Group invitations', 'Gruppeneinladungen') }).or(
    page.getByRole('link', { name: w.t('Group invitations', 'Gruppeneinladungen') })).first().click()
  const view = page.locator('.invitations-view')
  await expect(view.getByText(`@${w.cast.member.username}`)).toBeVisible({ timeout: 60_000 })
  await capture(w, 'spaces-group-invitations', view)
})

// ------------------------------------------------------------------------------------------------------------ Memories

seed('rome', async (w) => {
  const { page } = w.owner
  const n = names(w)
  await home(w, page)
  await createMemory(w, page, n.rome)
  await open(w, page, n.rome)
  await uploadPhotos(w, page, 0, 6, '2026-04-18', ROME)
  await thumbnailsDecoded(page, 6)
})

shot('memories-map-services-off', async (w) => {
  const { page } = w.owner
  await home(w, page)
  await open(w, page, names(w).rome)
  await headerAction(w, page, w.t('Set location', 'Ort festlegen'))
  // The map services are off (a new browser's default): the place editor is the name field, with the offer to turn them
  // on where the map would stand (uc.memory.place A6).
  const place = dialog(page, w.t('Location', 'Ort'))
  await expect(place.getByRole('button', { name: w.t('Turn map services on', 'Kartendienste einschalten'), exact: true })).toBeVisible()
  await capture(w, 'memories-map-services-off', place)
  // The map's tiles and place names are answered here, never fetched: the offer works, and nothing leaves the run.
  await page.context().route('https://tile.openstreetmap.org/**', async (route) => route.fulfill({ contentType: 'image/png', body: await paleTile(page) }))
  await page.context().route('https://nominatim.openstreetmap.org/**', (route) =>
    route.fulfill({ contentType: 'application/json', headers: { 'access-control-allow-origin': '*' }, body: '{}' }))
  // Name the place, so the Memory's page shows it (memories-memory-page), then turn the map services on in the editor: the
  // typed name stays, and the photos carry where they were taken.
  try {
    const name = place.getByRole('textbox', { name: 'Name' })
    await name.fill(w.t('Rome', 'Rom'))
    await place.getByRole('button', { name: w.t('Turn map services on', 'Kartendienste einschalten'), exact: true }).click()
    await expect(place.locator('.leaflet-container')).toBeVisible({ timeout: 10_000 })
    await place.getByRole('button', { name: w.t('Save', 'Speichern'), exact: true }).click({ timeout: 10_000 })
    await expect(place).toBeHidden({ timeout: 30_000 })
  } catch (error) {
    console.log(`[${w.lang}] the Memory's place was not set: ${String(error).split('\n')[0]}`)
    await closeDialogs(page)
  }
})

const ROME = { lat: 41.8902, lng: 12.4922 }
let tile: Buffer | undefined

/** One pale map tile, drawn once. */
async function paleTile(page: Page): Promise<Buffer> {
  if (!tile) {
    const url = await page.evaluate(() => {
      const c = document.createElement('canvas')
      c.width = c.height = 256
      const g = c.getContext('2d')!
      g.fillStyle = '#eef0ea'
      g.fillRect(0, 0, 256, 256)
      return c.toDataURL('image/png')
    })
    tile = Buffer.from(url.split(',')[1]!, 'base64')
  }
  return tile
}

shot('memories-create-dialog', async (w) => {
  const { page } = w.owner
  const n = names(w)
  await home(w, page)
  await newEntry(w, page, w.t('New Memory', 'Neue Erinnerung'))
  const d = dialog(page, w.t('Create a Memory', 'Erinnerung anlegen'))
  await d.getByLabel('Name').fill(n.lake)
  await capture(w, 'memories-create-dialog', d)
  await closeDialogs(page)
})

seed('lake', async (w) => {
  const { page } = w.owner
  await home(w, page)
  await createMemory(w, page, names(w).lake)
})

shot('memories-cover-dialog', async (w) => {
  const { page } = w.owner
  await home(w, page)
  await open(w, page, names(w).rome)
  await headerAction(w, page, w.t('Set cover', 'Cover festlegen'))
  let d = dialog(page, 'Cover')
  const chooser = page.waitForEvent('filechooser')
  await d.getByRole('button', { name: w.t('Choose an image', 'Bild auswählen') }).click()
  await (await chooser).setFiles({ name: 'rome.jpg', mimeType: 'image/jpeg', buffer: await drawJpeg(page, SCENES[2]!) })
  await expect(d.locator('.veyq-cropper img')).toBeVisible({ timeout: 60_000 })
  await d.getByRole('button', { name: w.t('Save cover', 'Cover speichern') }).click()
  await expect(d).toBeHidden({ timeout: 60_000 })
  // The cover is sealed and uploaded after the dialog closes; its tile then offers to change it.
  const change = page.locator('header.veyq-node-header').getByRole('button', { name: w.t('Change cover', 'Cover ändern'), exact: true })
  await expect(change).toBeVisible({ timeout: 60_000 })
  await change.click()
  d = dialog(page, 'Cover')
  await expect(d.locator('.veyq-cropper img')).toBeVisible({ timeout: 60_000 })
  await capture(w, 'memories-cover-dialog', d)
  await closeDialogs(page)
})

async function typeDate(w: World, group: Locator, day: string): Promise<void> {
  const [y, m, d] = day.split('-')
  await group.getByRole('spinbutton').first().focus()
  await group.page().keyboard.type(w.lang === 'en' ? `${m}${d}${y}` : `${d}${m}${y}`)
}

shot('memories-date-dialog', async (w) => {
  const { page } = w.owner
  await home(w, page)
  await open(w, page, names(w).rome)
  await headerAction(w, page, w.t('Edit date', 'Datum bearbeiten'))
  let d = dialog(page, w.t('Date', 'Datum'))
  await d.getByRole('button', { name: w.t('Range', 'Zeitraum'), exact: true }).click()
  await typeDate(w, d.getByRole('group', { name: w.t('From', 'Von') }), '2026-04-17')
  await typeDate(w, d.getByRole('group', { name: w.t('To', 'Bis') }), '2026-04-21')
  await page.keyboard.press('Tab')
  await d.getByRole('button', { name: w.t('Save', 'Speichern'), exact: true }).click()
  await expect(d).toBeHidden({ timeout: 30_000 })
  await headerAction(w, page, w.t('Edit date', 'Datum bearbeiten'))
  d = dialog(page, w.t('Date', 'Datum'))
  await expect(page.locator('.veyq-date-panel')).toHaveCount(0)
  await capture(w, 'memories-date-dialog', d)
  await closeDialogs(page)
})

shot('memories-chapters-tab', async (w) => {
  const { page } = w.owner
  await home(w, page)
  await open(w, page, names(w).rome)
  await page.getByRole('tab', { name: w.t(/^Chapters/, /^Kapitel/) }).click()
  for (const chapter of w.t(['Colosseum', 'Trastevere'], ['Kolosseum', 'Trastevere'])) {
    await page.getByRole('button', { name: w.t('Add chapter', 'Kapitel hinzufügen') }).click()
    const d = dialog(page, w.t('Create a chapter', 'Kapitel anlegen'))
    await d.getByLabel('Name').fill(chapter)
    await d.getByRole('button', { name: w.t('Create', 'Erstellen'), exact: true }).click()
    await expect(d).toBeHidden({ timeout: 30_000 })
  }
  await capture(w, 'memories-chapters-tab', page.getByRole('tabpanel'))
})

seed('participants', async (w) => {
  const { page } = w.owner
  await home(w, page)
  await open(w, page, names(w).rome)
  const add = page.getByRole('button', { name: w.t(/^(Add|Edit) participants$/, /^Beteiligte (hinzufügen|bearbeiten)$/) }).first()
  await add.click()
  const d = dialog(page, w.t('Who was there?', 'Wer war dabei?'))
  await d.getByRole('checkbox', { name: new RegExp(w.cast.member.first) }).check()
  await d.getByRole('button', { name: w.t('Save', 'Speichern'), exact: true }).click()
  await expect(d).toBeHidden({ timeout: 30_000 })
})

shot('memories-memory-page', async (w) => {
  const { page } = w.owner
  await home(w, page)
  await open(w, page, names(w).rome)
  await page.getByRole('tab', { name: w.t(/^(Photos|Gallery|All)/, /^(Fotos|Galerie|Alle)/) }).first().click().catch(() => undefined)
  await thumbnailsDecoded(page, 6)
  await capture(w, 'memories-memory-page', page.locator('main.content'))
})

shot('memories-photo-open', async (w) => {
  const { page } = w.owner
  const n = names(w)
  await home(w, page)
  await open(w, page, n.rome)
  await thumbnailsDecoded(page, 6)
  await page.getByRole('button', { name: w.t('Open item 2', 'Objekt 2 öffnen') }).click()
  const d = dialog(page, n.rome)
  await expect(d.locator('img').first()).toBeVisible({ timeout: 60_000 })
  await page.waitForTimeout(1_000)
  await capture(w, 'memories-photo-open', d)
  await closeDialogs(page)
})

shot('memories-marked-photos', async (w) => {
  const n = names(w)
  for (const [device, tiles] of [[w.owner, [0, 2]], [w.member, [0]]] as [Device, number[]][]) {
    const { page } = device
    await home(w, page)
    await open(w, page, n.rome)
    await thumbnailsDecoded(page, 6)
    for (const i of tiles) {
      await page.locator('.media-tile').nth(i).hover()
      await page.locator('.media-tile').nth(i).getByRole('button', { name: /markieren|Mark this photo/ }).click()
      await page.waitForTimeout(500)
    }
  }
  const { page } = w.owner
  await page.reload()
  await thumbnailsDecoded(page, 6)
  await expect(page.getByRole('group', { name: w.t('Show marked photos', 'Markierte Fotos anzeigen') })).toBeVisible()
  await capture(w, 'memories-marked-photos', page.getByRole('tabpanel'))
})

shot('memories-contributions-tab', async (w) => {
  const n = names(w)
  // Sam adds two photos from their own device.
  const sam = w.member.page
  await home(w, sam)
  await open(w, sam, n.rome)
  await uploadPhotos(w, sam, 6, 2, '2026-04-19', ROME)
  await thumbnailsDecoded(sam, 8)
  // A guest adds one through a link that lets people add photos.
  const { page } = w.owner
  await home(w, page)
  await open(w, page, n.rome)
  const link = await memoryLink(w, page, w.t('Family chat', 'Familienchat'), w.t('View + add photos', 'Ansehen + Fotos hinzufügen'))
  const guest = await w.device()
  await guest.page.goto(link)
  await guest.page.getByRole('button', { name: w.t('Upload', 'Hochladen'), exact: true }).click()
  const naming = dialog(guest.page, w.t('What is your name?', 'Wie heißt du?'))
  await naming.getByLabel(w.t('Your name', 'Dein Name')).fill(w.cast.guest)
  const chooser = guest.page.waitForEvent('filechooser')
  await naming.getByRole('button', { name: w.t('Continue', 'Weiter') }).click()
  const pick = await chooser.catch(async () => {
    const again = guest.page.waitForEvent('filechooser')
    await guest.page.getByRole('button', { name: w.t('Upload', 'Hochladen'), exact: true }).click()
    return again
  })
  await pick.setFiles({ name: 'IMG_3001.jpg', mimeType: 'image/jpeg', buffer: await photo(guest.page, 8, '2026-04-20T18:05:00') })
  await thumbnailsDecoded(guest.page, 9)
  await page.reload()
  await page.getByRole('tab', { name: w.t(/^Contributions/, /^Beiträge/) }).click()
  const panel = await page.getByRole('tablist').first().boundingBox()
  await page.getByRole('combobox', { name: w.t('Contributions', 'Beiträge') }).click()
  const listbox = page.getByRole('listbox')
  await expect(listbox.getByRole('option')).toHaveCount(3, { timeout: 30_000 })
  const list = await listbox.boundingBox()
  const top = Math.min(panel!.y, list!.y) - 12
  const clip = { x: panel!.x - 12, y: top, width: panel!.width + 24, height: list!.y + list!.height + 120 - top }
  await capture(w, 'memories-contributions-tab', { page, clip }, { keepFocus: true })
  await page.keyboard.press('Escape')
})

/** A new link on the open Memory with [role]; returns it. */
async function memoryLink(w: World, page: Page, name: string, role: string): Promise<string> {
  await headerAction(w, page, w.t('Share', 'Teilen'))
  const manage = dialog(page, w.t('Share this Memory', 'Diese Erinnerung teilen'))
  await manage.getByRole('button', { name: w.t('New share', 'Neuer Link') }).click()
  const create = dialog(page, w.t('New share', 'Neuer Link'))
  await create.getByLabel('Name').fill(name)
  await create.getByRole('button', { name: role }).click()
  await create.getByRole('button', { name: w.t('Create link', 'Link erstellen') }).click()
  const link = await page.getByRole('textbox', { name: w.t('Public link', 'Öffentlicher Link') }).inputValue()
  await page.getByRole('button', { name: w.t('Done', 'Fertig') }).click()
  await closeDialogs(page)
  return link
}

shot('memories-upload-progress', async (w) => {
  const { page } = w.owner
  const held: Route[] = []
  await home(w, page)
  await open(w, page, names(w).lake)
  await page.route('**/api/scopes/*/media-batch', (route) => void held.push(route))
  try {
    await uploadPhotos(w, page, 3, 3, '2026-07-04')
    const labels = page.locator('.upload-tile-status-label')
    await expect(labels).toHaveCount(3, { timeout: 60_000 })
    await expect.poll(async () => (await labels.allTextContents()).map((t) => t.trim()).sort().join('|'), { timeout: 60_000 })
      .toBe(w.t(['Preparing…', 'Queued', 'Uploading…'], ['In Warteschlange', 'Lädt hoch …', 'Wird vorbereitet …']).sort().join('|'))
    await capture(w, 'memories-upload-progress', page.getByRole('grid', { name: w.t('Memory gallery', 'Erinnerungs-Galerie') }))
  } finally {
    await page.unroute('**/api/scopes/*/media-batch')
    for (const route of held) await route.continue().catch(() => undefined)
  }
  await thumbnailsDecoded(page, 3)
})

shot('memories-photo-trash', async (w) => {
  const n = names(w)
  for (const device of [w.owner, w.member]) {
    const { page } = device
    await home(w, page)
    await open(w, page, n.lake)
    await thumbnailsDecoded(page, 1)
    await page.getByRole('button', { name: w.t('Open item 1', 'Objekt 1 öffnen') }).click()
    await page.getByRole('button', { name: w.t('Move to trash', 'In den Papierkorb') }).click()
    await page.waitForTimeout(1_000)
    await closeDialogs(page)
  }
  const { page } = w.owner
  await page.reload()
  await page.getByRole('tab', { name: w.t(/^Trash/, /^Papierkorb/) }).click()
  const panel = page.getByRole('tabpanel')
  await expect(panel.locator('img').nth(1)).toBeVisible({ timeout: 60_000 })
  await panel.getByRole('checkbox').first().check({ force: true })
  await capture(w, 'memories-photo-trash', panel)
})

shot(['sharing-create-link', 'sharing-request-upload'], async (w) => {
  const { page } = w.owner
  await home(w, page)
  await open(w, page, names(w).rome)
  await headerAction(w, page, w.t('Share', 'Teilen'))
  await dialog(page, w.t('Share this Memory', 'Diese Erinnerung teilen')).getByRole('button', { name: w.t('New share', 'Neuer Link') }).click()
  const create = dialog(page, w.t('New share', 'Neuer Link'))
  await create.getByLabel('Name').fill(w.t('For the grandparents', 'Für Oma und Opa'))
  await page.setViewportSize({ width: VIEWPORT.width, height: 1400 })
  try {
    await capture(w, 'sharing-create-link', create)
    await create.getByRole('button', { name: w.t('Add photos only', 'Nur Fotos hinzufügen') }).click()
    await expect(create.getByRole('button', { name: w.t('Add photos only', 'Nur Fotos hinzufügen') })).toHaveAttribute('aria-pressed', 'true')
    await capture(w, 'sharing-request-upload', create)
  } finally {
    await page.setViewportSize(VIEWPORT)
    await closeDialogs(page)
  }
})

let viewLink = ''

shot('sharing-link-list', async (w) => {
  const { page } = w.owner
  await home(w, page)
  await open(w, page, names(w).rome)
  viewLink = await memoryLink(w, page, w.t('Neighbours', 'Nachbarn'), w.t('View only', 'Nur ansehen'))
  const old = await memoryLink(w, page, w.t('Old link', 'Alter Link'), w.t('View only', 'Nur ansehen'))
  void old
  await headerAction(w, page, w.t('Share', 'Teilen'))
  let manage = dialog(page, w.t('Share this Memory', 'Diese Erinnerung teilen'))
  const row = manage.locator('li, .share-list-item, .veyq-row').filter({ hasText: w.t('Old link', 'Alter Link') }).first()
  await row.getByRole('button', { name: w.t('Revoke', 'Widerrufen') }).click()
  await page.waitForTimeout(1_000)
  await closeDialogs(page)
  // Only the links still on, and the switched-off ones folded: the family-chat link and one neighbours' link stay on.
  await headerAction(w, page, w.t('Share', 'Teilen'))
  manage = dialog(page, w.t('Share this Memory', 'Diese Erinnerung teilen'))
  await expect(manage.getByText(w.t('One switched-off link', 'Ein abgeschalteter Link'))).toBeVisible({ timeout: 30_000 })
  await capture(w, 'sharing-link-list', manage)
  await closeDialogs(page)
})

shot('sharing-guest-view', async (w) => {
  if (!viewLink) throw new Error('no view-only link (sharing-link-list runs first)')
  const { page } = await w.device()
  await page.goto(viewLink)
  await expect(page.getByText(w.t('Shared with you · read-only', 'Mit dir geteilt · schreibgeschützt'))).toBeVisible({ timeout: 60_000 })
  await thumbnailsDecoded(page, 6)
  await capture(w, 'sharing-guest-view', { page })
})

// ------------------------------------------------------------------------------------------------------------ Expenses

const PEOPLE = ['Anna', 'Ben', 'Cleo']

seed('pot', async (w) => {
  const { page } = w.owner
  const n = names(w)
  await home(w, page)
  await newEntry(w, page, w.t('New Pot', 'Neue Kasse'))
  const d = dialog(page, w.t('New Pot', 'Neue Kasse'))
  await d.getByRole('textbox', { name: 'Name', exact: true }).fill(n.pot)
  await d.getByRole('combobox', { name: w.t(/^Base currency/, /^Basiswährung/) }).click()
  await page.getByRole('option', { name: /^EUR\b/ }).first().click()
  const people = d.getByRole('combobox', { name: w.t('People', 'Personen'), exact: true })
  for (const person of PEOPLE) {
    await people.fill(person)
    await page.getByRole('option', { name: w.t(`Add “${person}” as a name`, `„${person}“ als Namen hinzufügen`), exact: true }).click()
  }
  await d.getByRole('button', { name: w.t('Create', 'Erstellen'), exact: true }).click()
  await expect(d).toBeHidden({ timeout: 30_000 })
  await open(w, page, n.pot)
  const entries: [string, string, string, string, string][] = w.t(
    [['Hotel', 'Leisure', 'Ben', '360.00', '2026-10-09'], ['Dinner at the harbour', 'Food & drink', 'Anna', '90.00', '2026-10-10'], ['Taxi to the airport', 'Transport', 'Cleo', '24.00', '2026-10-12'], ['Museum tickets', 'Leisure', 'Ben', '45.00', '2026-10-11']],
    [['Hotel', 'Freizeit', 'Ben', '360,00', '2026-10-09'], ['Abendessen am Hafen', 'Essen & Trinken', 'Anna', '90,00', '2026-10-10'], ['Taxi zum Flughafen', 'Transport', 'Cleo', '24,00', '2026-10-12'], ['Museumseintritt', 'Freizeit', 'Ben', '45,00', '2026-10-11']])
  for (const [title, category, payer, amount, day] of entries) {
    await page.locator('.expenses-entries').getByRole('button', { name: w.t('Add entry', 'Eintrag hinzufügen'), exact: true }).click()
    const editor = dialog(page, w.t('Add entry', 'Eintrag hinzufügen'))
    await editor.getByLabel(w.t('Title', 'Titel'), { exact: true }).fill(title)
    await typeDate(w, editor.getByRole('group').filter({ has: page.getByRole('spinbutton') }).first(), day).catch(() => undefined)
    await pickOption(page, editor.getByRole('combobox', { name: w.t(/^Category/, /^Kategorie/) }), category)
    await pickOption(page, editor.getByRole('combobox', { name: w.t('Person', 'Person'), exact: true }).first(), payer)
    await editor.getByRole('textbox', { name: w.t(`Amount paid by ${payer}`, `Betrag, den ${payer} bezahlt hat`), exact: true }).fill(amount)
    await editor.getByRole('button', { name: w.t('Save', 'Speichern'), exact: true }).click()
    await expect(editor).toBeHidden({ timeout: 30_000 })
  }
})

async function pickOption(page: Page, trigger: Locator, option: string): Promise<void> {
  await trigger.click()
  await page.getByRole('option', { name: option, exact: true }).click()
  await expect(page.getByRole('listbox')).toHaveCount(0)
}

async function openPot(w: World, page: Page): Promise<void> {
  await home(w, page)
  await open(w, page, names(w).pot)
}

shot('expenses-pot-entries', async (w) => {
  const { page } = w.owner
  await openPot(w, page)
  await capture(w, 'expenses-pot-entries', page.locator('.veyq-node-page'))
})

shot('expenses-balances-settle-up', async (w) => {
  const { page } = w.owner
  await openPot(w, page)
  await page.getByRole('tab', { name: w.t('Overview', 'Übersicht'), exact: true }).click()
  const top = page.locator('.expenses-overview')
  const transfers = page.locator('ul.expenses-settle-up')
  await expect(transfers).toBeVisible({ timeout: 30_000 })
  const a = await top.boundingBox()
  const b = await transfers.boundingBox()
  await capture(w, 'expenses-balances-settle-up', { page, clip: { x: a!.x - 12, y: a!.y - 12, width: a!.width + 24, height: b!.y + b!.height - a!.y + 24 } })
})

shot('expenses-entry-editor', async (w) => {
  const { page } = w.owner
  await openPot(w, page)
  await page.locator('.expenses-entries').getByRole('button', { name: w.t('Add entry', 'Eintrag hinzufügen'), exact: true }).click()
  const editor = dialog(page, w.t('Add entry', 'Eintrag hinzufügen'))
  await editor.getByLabel(w.t('Title', 'Titel'), { exact: true }).fill(w.t('Drinks at the bar', 'Getränke an der Bar'))
  await typeDate(w, editor.getByRole('group').filter({ has: page.getByRole('spinbutton') }).first(), '2026-10-11').catch(() => undefined)
  await pickOption(page, editor.getByRole('combobox', { name: w.t(/^Category/, /^Kategorie/) }), w.t('Food & drink', 'Essen & Trinken'))
  await pickOption(page, editor.getByRole('combobox', { name: w.t('Person', 'Person'), exact: true }).first(), 'Cleo')
  await editor.getByRole('textbox', { name: w.t('Amount paid by Cleo', 'Betrag, den Cleo bezahlt hat'), exact: true }).fill(w.t('25.00', '25,00'))
  const items: [string, string, string[]][] = [[w.t('Beer', 'Bier'), w.t('10.00', '10,00'), ['Anna', 'Ben']], [w.t('Juice', 'Saft'), w.t('10.00', '10,00'), ['Cleo']]]
  for (const [i, [label, amount, on]] of items.entries()) {
    await editor.getByRole('button', { name: w.t('Add item', 'Posten hinzufügen'), exact: true }).click()
    const row = editor.locator('.expenses-item:not(.expenses-rest)').nth(i)
    await row.getByRole('textbox', { name: w.t('Item', 'Posten'), exact: true }).fill(label)
    await row.getByRole('textbox', { name: w.t('Item amount', 'Betrag des Postens'), exact: true }).fill(amount)
    await row.getByRole('button', { name: w.t('Adjust', 'Anpassen'), exact: true }).click()
    for (const person of PEOPLE.filter((p) => !on.includes(p))) {
      await row.getByRole('button', { name: w.t(`Take ${person} off the split`, `${person} aus der Aufteilung nehmen`), exact: true }).click()
    }
  }
  await page.setViewportSize({ width: VIEWPORT.width, height: 1300 })
  try {
    await capture(w, 'expenses-entry-editor', editor)
  } finally {
    await page.setViewportSize(VIEWPORT)
    await closeDialogs(page)
    const discard = page.getByRole('button', { name: /^(Discard|Verwerfen)/ })
    if (await discard.count()) await discard.first().click()
  }
})

shot('expenses-share-dialog', async (w) => {
  const { page } = w.owner
  await openPot(w, page)
  await headerAction(w, page, w.t('Share', 'Teilen'))
  const list = dialog(page, w.t('Share this Pot', 'Diese Kasse teilen'))
  const create = dialog(page, w.t('New share', 'Neuer Link'))
  await expect(list.or(create)).toBeVisible()
  if (await list.isVisible()) await list.getByRole('button', { name: w.t('New share', 'Neuer Link') }).click()
  await create.getByLabel('Name').fill(w.t('Travel group', 'Reisegruppe'))
  await capture(w, 'expenses-share-dialog', create)
  await closeDialogs(page)
})

// ------------------------------------------------------------------------------------------------------------ the website

// The project website's shots — the hero's `shotId` and each application's `shot` in
// site/src/components/website/strings.ts, which README.md shows as well. Each is the whole window of the web client at a
// laptop's size, as the website's browser frame (site/src/components/website/Shot.astro) expects. Their ids carry the
// `website/` prefix: the capture copies them into site/src/assets/website/, not beside the Use pages' shots. They come
// last, so every seed above has filled the space by then.

const WEBSITE_VIEWPORT = { width: 1440, height: 900 }

function websiteShot(id: string, reach: (w: World, page: Page) => Promise<void>): void {
  shot(`website/${id}`, async (w) => {
    const { page } = w.owner
    await page.setViewportSize(WEBSITE_VIEWPORT)
    try {
      await reach(w, page)
      await capture(w, `website/${id}`, { page })
    } finally {
      await page.setViewportSize(VIEWPORT)
    }
  })
}

// A little more in the two folders the website shows than the Use pages need: the party's Memory gets photos and a
// cover, so its tile wears a picture rather than a placeholder, the party a seating plan, and the documents a few more
// letters, so the Depot shot reads as somebody's papers. Nothing above reads these.
seed('website', async (w) => {
  const { page } = w.owner
  const n = names(w)
  await home(w, page)
  await open(w, page, n.party)
  await upload(w, page, [file(w, w.t('Seating plan.pdf', 'Sitzplan.pdf'), letterPdf([w.t('Seating plan', 'Sitzplan')]))])
  await open(w, page, n.partyPhotos)
  await uploadPhotos(w, page, 3, 4, '2026-07-18')
  await thumbnailsDecoded(page, 4)
  await headerAction(w, page, w.t('Set cover', 'Cover festlegen'))
  const d = dialog(page, 'Cover')
  const chooser = page.waitForEvent('filechooser')
  await d.getByRole('button', { name: w.t('Choose an image', 'Bild auswählen') }).click()
  await (await chooser).setFiles({ name: 'party.jpg', mimeType: 'image/jpeg', buffer: await drawJpeg(page, SCENES[3]!) })
  await expect(d.locator('.veyq-cropper img')).toBeVisible({ timeout: 60_000 })
  await d.getByRole('button', { name: w.t('Save cover', 'Cover speichern') }).click()
  await expect(d).toBeHidden({ timeout: 60_000 })
  await expect(page.locator('header.veyq-node-header').getByRole('button', { name: w.t('Change cover', 'Cover ändern'), exact: true }))
    .toBeVisible({ timeout: 60_000 })
  await home(w, page)
  await open(w, page, n.documents)
  const letters: [string, string[]][] = w.t(
    [['Car insurance 2026.pdf', ['Car insurance 2026']], ['Tax return 2025.pdf', ['Tax return 2025']], ['Warranty – washing machine.pdf', ['Warranty']]],
    [['Kfz-Versicherung 2026.pdf', ['Kfz-Versicherung 2026']], ['Steuererklärung 2025.pdf', ['Steuererklärung 2025']], ['Garantie – Waschmaschine.pdf', ['Garantie']]])
  await upload(w, page, letters.map(([name, lines]) => file(w, name, letterPdf(lines))))
  // The project's donation banner is the signed-in app's, but not what the website's pictures are about.
  const dismiss = page.getByTestId('donation-banner-dismiss')
  if (await dismiss.count()) await dismiss.click()
  await expect(page.getByTestId('donation-banner')).toHaveCount(0)
})

websiteShot('folder', async (w, page) => {
  await home(w, page)
  await open(w, page, names(w).party)
  const grid = page.getByRole('button', { name: w.t('Grid view', 'Rasteransicht'), exact: true })
  if (await grid.count()) await grid.click()
  await expect(page.locator('.veyq-level-list:not(.is-list)').first()).toBeVisible()
  await expect.poll(() => page.locator('.veyq-level-view img').evaluateAll((imgs) =>
    imgs.filter((i) => (i as HTMLImageElement).naturalWidth > 0).length), { timeout: 60_000 }).toBeGreaterThanOrEqual(1)
})

websiteShot('gallery', async (w, page) => {
  await home(w, page)
  await open(w, page, names(w).rome)
  await page.getByRole('tab', { name: w.t(/^(Photos|Gallery|All)/, /^(Fotos|Galerie|Alle)/) }).first().click().catch(() => undefined)
  // The seed's six; a whole run has the two a member added too (memories-contributions-tab, a shot `--only` skips).
  await thumbnailsDecoded(page, 6)
})

websiteShot('depot', async (w, page) => {
  await home(w, page)
  await open(w, page, names(w).documents)
  await listView(w, page)
})

websiteShot('pot', async (w, page) => {
  await openPot(w, page)
  await page.getByRole('tab', { name: w.t('Overview', 'Übersicht'), exact: true }).click()
  await expect(page.locator('ul.expenses-settle-up')).toBeVisible({ timeout: 30_000 })
})

// -------------------------------------------------------------------------------------------------------------- run

/** Every id a Use page places, read the way the to-do report reads them (scripts/site_shots.py). */
function placedIds(): string[] {
  return execFileSync('python3', [`${REPO}scripts/site_shots.py`, '--ids'], { encoding: 'utf8' }).split('\n').filter(Boolean)
}

/**
 * Every id the project website places, as `website/<id>`: the hero's `shotId: '…'`, and an application's `shot: '…'` —
 * the one followed by its `shotShows` (the hero's own `shot` is the text of its picture, not an id).
 */
function websiteIds(): string[] {
  const strings = readFileSync(`${REPO}site/src/components/website/strings.ts`, 'utf8')
  const ids = [...strings.matchAll(/\bshotId: '([^']+)'|\bshot: '([^']+)',\s*shotShows:/g)].map((m) => m[1] ?? m[2])
  return [...new Set(ids.map((id) => `website/${id}`))]
}

export const SCENARIO_IDS = steps.filter((s) => s.kind === 'shot').flatMap((s) => s.ids)

test('every placed screenshot has a scenario or a reason it is left out', () => {
  expect(websiteIds(), 'the website places no shot: its strings changed shape').not.toEqual([])
  const placed = new Set([...placedIds(), ...websiteIds()])
  const left = Object.keys(LEFT_OUT).filter((k) => !k.startsWith('//'))
  const scenarios = new Set(SCENARIO_IDS)
  expect(SCENARIO_IDS.length, 'an id has two scenarios').toBe(scenarios.size)
  expect([...placed].filter((id) => !scenarios.has(id) && !left.includes(id)), 'placed, with no scenario and no reason in left-out.json').toEqual([])
  expect([...scenarios, ...left].filter((id) => !placed.has(id)), 'a scenario or left-out entry no page places').toEqual([])
  expect(left.filter((id) => scenarios.has(id)), 'both a scenario and left out').toEqual([])
})

const LANGS = (process.env.VEYQ_SHOTS_LANGS ?? 'en,de').split(',').filter(Boolean) as Lang[]
const ONLY = new Set((process.env.VEYQ_SHOTS_ONLY ?? '').split(',').filter(Boolean))

if (process.env.VEYQ_STACK_FILE) {
  for (const lang of LANGS) {
    test(`shots in ${lang}`, async () => {
      const browser = await launch()
      const w = new World(lang, browser)
      const results: Record<string, string> = {}
      try {
        for (const step of steps) {
          if (step.kind === 'shot' && ONLY.size > 0 && !step.ids.some((id) => ONLY.has(id))) continue
          const label = `${step.kind} ${step.ids.join(' + ')}`
          const started = Date.now()
          try {
            await test.step(label, () => step.run(w))
            if (step.kind === 'shot') for (const id of step.ids) results[id] = 'ok'
            console.log(`[${lang}] ok    ${label} (${((Date.now() - started) / 1000).toFixed(1)}s)`)
          } catch (error) {
            const reason = String(error instanceof Error ? error.message : error).split('\n').filter(Boolean).slice(0, 4).join(' | ')
            console.log(`[${lang}] FAIL  ${label}: ${reason}`)
            for (const [who, d] of [['owner', w.owner], ['member', w.member]] as const) {
              await d?.page.screenshot({ path: `${shotsDir()}/failed-${step.ids[0].replace('/', '-')}.${lang}.${who}.png` }).catch(() => undefined)
            }
            if (step.kind === 'seed') throw error
            for (const id of step.ids) results[id] = reason
            for (const d of [w.owner, w.member]) await d?.page.keyboard.press('Escape').catch(() => undefined)
          }
        }
      } finally {
        mkdirSync(shotsDir(), { recursive: true })
        writeFileSync(`${shotsDir()}/results-${lang}.json`, JSON.stringify({ results, foreign: w.foreignRequests() }, null, 2))
        await w.close()
        await browser.close()
      }
      const failed = Object.entries(results).filter(([, r]) => r !== 'ok')
      expect(failed, 'shots that failed').toEqual([])
    })
  }
}
