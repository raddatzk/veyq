// The capability off states in a real browser (uc.admin.switch-capabilities; inbox #447's hand pass of 2026-10-15,
// automated). The Server Owner switches four entries off, one at a time, in admin-web's Server → Capabilities — each
// confirmation showing the entry's served `whenOff` word for word — and the member's web client is read after each:
// the affordance gone, a trusted and a remembered device asking for the password and the Secret Key with the
// held-back hint, no wrap of either device deleted or replaced while its switch is off; then everything back on.
//
// It starts from the instance the round trip left: the tenant's owner is active (memory-roundtrip.spec.ts accepts the
// invitation), so this file is the `features` project, after `scenarios`. One browser context per device, each
// confined to the instance (egress.ts). web/ reads the capability manifest once per document (`GET /api/setup` at
// boot), so every check after a switch opens a new document — a reload, or a new page, which a device needs anyway to
// show its sign-in screen (a reload of a signed-in tab restores the tab's session vault). The labels are web/'s and
// admin-web's own (their `src/i18n/index.ts`, en); the entries' words are the catalog's
// (`contracts/platform/capabilities.json`, which the image serves).
import { test, expect, type Browser, type BrowserContext, type Page, type Request } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { recordTyped } from '../../src/canaries'
import { confineToInstance, type Confinement } from '../../src/egress'
import { stack } from '../../src/stack'

test.describe.configure({ mode: 'serial' })

interface Instance {
  owner: { email: string; password: string; secretKey: string }
  tenantDomain: string
}

interface Entry {
  title: { en: string }
  whenOff: { en: string }
}

const CATALOG = fileURLToPath(new URL('../../../contracts/platform/capabilities.json', import.meta.url))
const entries = (JSON.parse(readFileSync(CATALOG, 'utf8')) as { capabilities: Record<string, Entry> }).capabilities

const DROP_BOX = 'core.public-shares.drop-box'
const DEVICE_LINKING = 'core.device-linking'
const REMEMBER = 'core.remember-device'
const PASSWORDLESS = 'core.remember-device.passwordless'
const SWITCHED = [DROP_BOX, DEVICE_LINKING, PASSWORDLESS, REMEMBER]


const HELD_BACK = 'This server does not allow remembered devices right now, so this device needs your Secret Key from '
  + 'your Emergency Kit. Nothing on it was removed: once the server allows it again, it unlocks as before.'

// PrimeVue's Password field adds its placeholder to the accessible name, so the label is matched as the name's start.
const passwordField = (on: Page) => on.getByRole('textbox', { name: /^Password\b/ })
const secretKeyField = (on: Page) => on.locator('#unlock-secret-key')
const stayField = (on: Page) => on.getByRole('checkbox', { name: /^Stay signed in on this device/ })

interface Device {
  context: BrowserContext
  page: Page
  confinement: Confinement
  /** Every POST or DELETE this device sent to `/api/account/wraps` — the only way a wrap is added or removed. */
  wrapWrites: string[]
}

let instance: Instance
let admin: Device
let fresh: Device // no account record at all: what a new device's sign-in screen offers
let trusted: Device // signed in without "Stay signed in": a DEVICE wrap, unlocks with the password alone
let remembered: Device // signed in with "Stay signed in": a DEVICE_KEY wrap, opens without a prompt

async function newDevice(browser: Browser): Promise<Device> {
  const context = await browser.newContext()
  const confinement = await confineToInstance(context)
  const wrapWrites: string[] = []
  context.on('request', (request: Request) => {
    const url = new URL(request.url())
    if (url.pathname.startsWith('/api/account/wraps') && ['POST', 'DELETE'].includes(request.method())) {
      wrapWrites.push(`${request.method()} ${url.pathname}`)
    }
  })
  return { context, page: await context.newPage(), confinement, wrapWrites }
}

const tenantOrigin = () => `https://${instance.tenantDomain}`

/** A new tab of the device on the tenant's domain: a new document, and no session vault (that is per tab). */
async function newTab(device: Device): Promise<Page> {
  await device.page.close()
  device.page = await device.context.newPage()
  await device.page.goto(tenantOrigin())
  return device.page
}

/** The shell, signed in and unlocked: the space's toolbar offers New. */
async function expectShell(page: Page): Promise<void> {
  await expect(page.getByRole('button', { name: 'New', exact: true })).toBeVisible({ timeout: 60_000 })
  expect(new URL(page.url()).pathname).not.toBe('/login')
}

async function signIn(page: Page, { secretKey, stay }: { secretKey: boolean; stay?: boolean }): Promise<void> {
  const { owner } = instance
  await expect(page.getByRole('button', { name: 'Sign in', exact: true })).toBeVisible({ timeout: 60_000 })
  await page.getByLabel('Email', { exact: true }).fill(owner.email)
  await passwordField(page).fill(recordTyped(owner.password))
  if (secretKey) {
    await expect(secretKeyField(page)).toBeVisible()
    await secretKeyField(page).fill(recordTyped(owner.secretKey))
  } else {
    await expect(secretKeyField(page), 'this device unlocks with the password alone').toHaveCount(0)
  }
  if (stay) await stayField(page).check()
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await expectShell(page)
}

/** The device's sign-in screen asks for the Secret Key and says why: its wrap is held back, nothing was removed. */
async function expectHeldBack(page: Page): Promise<void> {
  await expect(page.getByRole('button', { name: 'Sign in', exact: true })).toBeVisible({ timeout: 60_000 })
  // The email is filled in from the device's own record (inbox #450), so the hint shows before anything is typed.
  await expect(page.getByLabel('Email', { exact: true })).toHaveValue(instance.owner.email)
  await expect(secretKeyField(page)).toBeVisible()
  await expect(page.getByText(HELD_BACK, { exact: true })).toBeVisible()
}

function row(id: string) {
  return admin.page.locator(`li.capability[data-id="${id}"]`)
}

/** Server → Capabilities: off asks first, showing the entry's served `whenOff`; on asks nothing. */
async function switchEntry(id: string, off: boolean): Promise<void> {
  const entry = entries[id]
  if (!entry) throw new Error(`${id} is not in the catalog`)
  if (off) {
    await row(id).getByRole('button', { name: 'Switch off' }).click()
    const confirm = admin.page.getByRole('dialog', { name: `Switch off “${entry.title.en}”?` })
    await expect(confirm.getByText(entry.whenOff.en, { exact: true })).toBeVisible()
    await confirm.getByRole('button', { name: 'Switch off' }).click()
    await expect(confirm).toBeHidden()
  } else {
    await row(id).getByRole('button', { name: 'Switch on' }).click()
  }
  await expect(row(id).locator('.capability__state')).toHaveText(off ? 'Off' : 'On')
}

/** The instance's manifest as the page reads it — the one `GET /api/setup` every document boots from. */
async function advertised(page: Page): Promise<string[]> {
  return page.evaluate(async () => {
    const setup = (await (await fetch('/api/setup')).json()) as { capabilities: { id: string }[] }
    return setup.capabilities.map((c) => c.id)
  })
}

async function expectDropBoxOffered(page: Page, offered: boolean): Promise<void> {
  await page.getByRole('button', { name: 'New', exact: true }).click()
  // The menu's other entry is the control that the menu opened at all.
  await expect(page.getByRole('menuitem', { name: 'New Memory' })).toBeVisible()
  await expect(page.getByRole('menuitem', { name: 'New drop box' })).toHaveCount(offered ? 1 : 0)
  await page.keyboard.press('Escape')
  await expect(page.getByRole('tab', { name: 'Drop boxes' })).toHaveCount(offered ? 1 : 0)
}

async function expectAddADevice(page: Page, offered: boolean): Promise<void> {
  await page.goto(`${tenantOrigin()}/settings/pairing`)
  await expect(page.getByRole('heading', { level: 1, name: 'Settings' })).toBeVisible({ timeout: 60_000 })
  const nav = page.getByRole('navigation', { name: 'Settings' })
  // The rail's other entry is the control that the rail rendered at all.
  await expect(nav.getByRole('button', { name: 'Devices and sessions' })).toBeVisible()
  await expect(nav.getByRole('button', { name: 'Add a device' })).toHaveCount(offered ? 1 : 0)
  if (offered) {
    await expect(page.getByRole('heading', { level: 2, name: 'Add a device' })).toBeVisible()
  } else {
    // Its address leads back to Settings.
    await expect.poll(() => new URL(page.url()).pathname).not.toBe('/settings/pairing')
    await expect(page.getByRole('heading', { level: 2, name: 'Add a device' })).toHaveCount(0)
  }
}

test.beforeAll(async ({ browser }) => {
  instance = JSON.parse(readFileSync(`${stack().outDir}/instance.json`, 'utf8')) as Instance
  admin = await newDevice(browser)
  fresh = await newDevice(browser)
  trusted = await newDevice(browser)
  remembered = await newDevice(browser)
})

test.afterEach(() => {
  for (const device of [admin, fresh, trusted, remembered]) device?.confinement.assertConfined()
})

test.afterAll(async () => {
  for (const device of [admin, fresh, trusted, remembered]) await device?.context.close()
})

test(
  'with every entry on, the member is offered each affordance and two devices are enrolled, one of each tier',
  {
    tag: ['@uc.admin.switch-capabilities:off-state-in-a-browser', '@uc.admin.switch-capabilities:device-switch-acts-at-once'],
  },
  async () => {
    // The Server Owner, in the console: every entry this file switches is on.
    const { page } = admin
    await page.goto(stack().adminOrigin)
    await page.getByLabel('Email', { exact: true }).fill(instance.owner.email)
    await passwordField(page).fill(recordTyped(instance.owner.password))
    await page.getByRole('button', { name: 'Sign in' }).click()
    await expect(page.getByRole('heading', { name: 'Server administration', level: 1 })).toBeVisible()
    await page.getByRole('button', { name: 'Capabilities' }).click()
    await expect(page.getByRole('heading', { level: 2, name: 'Capabilities' })).toBeVisible()
    for (const id of SWITCHED) await expect(row(id).locator('.capability__state')).toHaveText('On')

    // A new device's sign-in screen offers both ways a device can be added and kept.
    await fresh.page.goto(tenantOrigin())
    await expect(fresh.page.getByRole('button', { name: 'Sign in with a QR code' })).toBeVisible({ timeout: 60_000 })
    await expect(stayField(fresh.page)).toBeVisible()

    // The trusted device: signed in without "Stay signed in" — it uploads a DEVICE wrap.
    await trusted.page.goto(tenantOrigin())
    await signIn(trusted.page, { secretKey: true })
    await expect.poll(() => trusted.wrapWrites, { message: 'the trusted device uploaded its wrap' })
      .toContain('POST /api/account/wraps')
    expect(await advertised(trusted.page)).toEqual(expect.arrayContaining(SWITCHED))
    await expectDropBoxOffered(trusted.page, true)
    await expectAddADevice(trusted.page, true)

    // The remembered device: signed in with "Stay signed in" — it uploads a DEVICE_KEY wrap.
    await remembered.page.goto(tenantOrigin())
    await signIn(remembered.page, { secretKey: true, stay: true })
    await expect.poll(() => remembered.wrapWrites, { message: 'the remembered device uploaded its wrap' })
      .toContain('POST /api/account/wraps')

    // The two tiers, as they are meant to work: the trusted device asks for the password alone, the remembered one
    // opens without a prompt.
    await signIn(await newTab(trusted), { secretKey: false })
    await expectShell(await newTab(remembered))
  },
)

test(
  'drop boxes switched off: no "New drop box" and no drop-box tab, until switched back on',
  { tag: ['@uc.admin.switch-capabilities:off-state-in-a-browser', '@uc.admin.switch-capabilities:switch-without-restart'] },
  async () => {
    const { page } = trusted
    await switchEntry(DROP_BOX, true)
    await page.reload()
    await expectShell(page)
    expect(await advertised(page)).not.toContain(DROP_BOX)
    await expectDropBoxOffered(page, false)

    await switchEntry(DROP_BOX, false)
    await page.reload()
    await expectShell(page)
    expect(await advertised(page)).toContain(DROP_BOX)
    await expectDropBoxOffered(page, true)
  },
)

test(
  'adding a device from another one switched off: no "Sign in with a QR code", no "Add a device", until back on',
  { tag: ['@uc.admin.switch-capabilities:off-state-in-a-browser', '@uc.admin.switch-capabilities:switch-without-restart'] },
  async () => {
    await switchEntry(DEVICE_LINKING, true)
    await fresh.page.reload()
    await expect(fresh.page.getByRole('button', { name: 'Sign in', exact: true })).toBeVisible({ timeout: 60_000 })
    await expect(fresh.page.getByRole('button', { name: 'Sign in with a QR code' })).toHaveCount(0)
    await expectAddADevice(trusted.page, false)

    await switchEntry(DEVICE_LINKING, false)
    await fresh.page.reload()
    await expect(fresh.page.getByRole('button', { name: 'Sign in with a QR code' })).toBeVisible({ timeout: 60_000 })
    await expectAddADevice(trusted.page, true)
  },
)

test(
  'unlocking without a password switched off: no "Stay signed in", and the remembered device asks for the Secret Key',
  {
    tag: ['@uc.admin.switch-capabilities:off-state-in-a-browser', '@uc.admin.switch-capabilities:device-switch-acts-at-once'],
  },
  async () => {
    trusted.wrapWrites.length = 0
    remembered.wrapWrites.length = 0
    await switchEntry(PASSWORDLESS, true)

    await fresh.page.reload()
    await expect(fresh.page.getByRole('button', { name: 'Sign in', exact: true })).toBeVisible({ timeout: 60_000 })
    await expect(stayField(fresh.page)).toHaveCount(0)

    // The remembered device no longer opens straight in: it asks for both secrets, and unlocks with them.
    const page = await newTab(remembered)
    await expectHeldBack(page)
    await signIn(page, { secretKey: true })
    // The trusted device is not this switch's: it still asks for the password alone (not submitted — a sign-in there
    // re-trusts, which replaces its wrap by design).
    const other = await newTab(trusted)
    await expect(passwordField(other)).toBeVisible({ timeout: 60_000 })
    await expect(other.getByLabel('Email', { exact: true })).toHaveValue(instance.owner.email)
    await expect(secretKeyField(other)).toHaveCount(0)
    expect(remembered.wrapWrites, 'no wrap removed or replaced while the switch is off').toEqual([])
    expect(trusted.wrapWrites, 'no wrap removed or replaced while the switch is off').toEqual([])

    // Back on: the remembered device opens without a prompt again, through the wrap the server only held back.
    await switchEntry(PASSWORDLESS, false)
    await expectShell(await newTab(remembered))
    expect(remembered.wrapWrites, 'restored, not re-enrolled').toEqual([])
    await fresh.page.reload()
    await expect(stayField(fresh.page)).toBeVisible({ timeout: 60_000 })
  },
)

test(
  'remembered devices switched off: both devices ask for the Secret Key, the child is off through it, until back on',
  {
    tag: ['@uc.admin.switch-capabilities:off-state-in-a-browser', '@uc.admin.switch-capabilities:device-switch-acts-at-once'],
  },
  async () => {
    trusted.wrapWrites.length = 0
    remembered.wrapWrites.length = 0
    await switchEntry(REMEMBER, true)
    // Its child's own switch is on, and it is off through its parent.
    await expect(row(PASSWORDLESS).locator('.capability__state'))
      .toHaveText(`Off through “${entries[REMEMBER]!.title.en}”`)
    await expect(row(PASSWORDLESS).getByRole('button', { name: 'Switch off' })).toBeVisible()

    await fresh.page.reload()
    await expect(fresh.page.getByRole('button', { name: 'Sign in', exact: true })).toBeVisible({ timeout: 60_000 })
    await expect(stayField(fresh.page)).toHaveCount(0)

    for (const device of [trusted, remembered]) {
      const page = await newTab(device)
      await expectHeldBack(page)
      await signIn(page, { secretKey: true })
    }
    expect(trusted.wrapWrites, 'no wrap removed or replaced while the switch is off').toEqual([])
    expect(remembered.wrapWrites, 'no wrap removed or replaced while the switch is off').toEqual([])

    // Back on: the child follows its own switch again, the remembered device opens without a prompt and the trusted
    // one unlocks with the password alone — both through the wraps the server only held back.
    await switchEntry(REMEMBER, false)
    await expect(row(PASSWORDLESS).locator('.capability__state')).toHaveText('On')
    await expectShell(await newTab(remembered))
    expect(remembered.wrapWrites, 'restored, not re-enrolled').toEqual([])
    await signIn(await newTab(trusted), { secretKey: false })
    await fresh.page.reload()
    await expect(stayField(fresh.page)).toBeVisible({ timeout: 60_000 })
    for (const id of SWITCHED) await expect(row(id).locator('.capability__state')).toHaveText('On')
  },
)
