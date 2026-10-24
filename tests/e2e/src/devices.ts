// A device of the scenarios: one browser context, confined to the instance (egress.ts), and the tenant owner the
// round trip activated (instance.json in the run directory) signing in on it with both secrets, as a new device must.
import { expect, type Browser, type BrowserContext, type Page } from '@playwright/test'
import { readFileSync, writeFileSync } from 'node:fs'
import { recordTyped } from './canaries'
import { confineToInstance, type Confinement } from './egress'
import { stack } from './stack'

export interface Instance {
  owner: { email: string; password: string; secretKey: string }
  tenantDomain: string
}

export interface Device {
  context: BrowserContext
  page: Page
  confinement: Confinement
}

export function instance(): Instance {
  return JSON.parse(readFileSync(`${stack().outDir}/instance.json`, 'utf8')) as Instance
}

export const tenantOrigin = () => `https://${instance().tenantDomain}`

/** A second moderator: the account moderation.spec.ts appointed, which holds the key from then on (`deputy.json`). */
export interface Deputy {
  email: string
  password: string
  secretKey: string
  tenantDomain: string
}

export function deputy(): Deputy {
  return JSON.parse(readFileSync(`${stack().outDir}/deputy.json`, 'utf8')) as Deputy
}

export function saveDeputy(d: Deputy): void {
  writeFileSync(`${stack().outDir}/deputy.json`, JSON.stringify(d, null, 2))
}

export async function newDevice(browser: Browser): Promise<Device> {
  const context = await browser.newContext()
  const confinement = await confineToInstance(context)
  return { context, page: await context.newPage(), confinement }
}

// The password field's accessible name is its label's text (the UI layer names the input by it, so its reveal toggle
// adds nothing); the label is matched as the name's start, because a label may carry a hint after the word.
export const passwordField = (on: Page) => on.getByRole('textbox', { name: /^Password\b/ })

/**
 * The one answer of 400 or more that is no error (`signedOutBoot.json`, which scripts/profile_hot_paths.py reads too): a
 * document with no session asks `POST /api/token/refresh` once at its boot and is told 401 — the signed-out state.
 */
export const SIGNED_OUT_BOOT = JSON.parse(readFileSync(new URL('./signedOutBoot.json', import.meta.url), 'utf8')) as {
  method: string
  path: string
  statuses: number[]
}

/** Whether a failed resource Chromium logged (its text, its URL) is the signed-out boot's refresh. */
function isSignedOutBoot(text: string, url: string): boolean {
  const status = /^Failed to load resource: the server responded with a status of (\d{3})\b/.exec(text)?.[1]
  let path = ''
  try {
    path = new URL(url).pathname
  } catch {
    return false
  }
  return path === SIGNED_OUT_BOOT.path && SIGNED_OUT_BOOT.statuses.includes(Number(status))
}

/**
 * The console errors of [pages], for a scenario that asserts its pages log none. One answer is not an error and is left
 * out: the signed-out boot's refresh ([SIGNED_OUT_BOOT]; `src/web/src/app/sessionRefresh.ts` `rotateSession`), which
 * Chromium logs as a failed resource. A
 * scenario calls `clear()` after its sign-in too, so what it asserts is the console of the pages it reads, not of the
 * sign-in in front of them (lane L1, 2026-10-23: that answer alone, then a 400, made memories-styles, memory-banner and depot-styles red).
 */
export interface ConsoleErrors {
  readonly errors: string[]
  clear: () => void
}

export function watchConsole(...pages: Page[]): ConsoleErrors {
  const errors: string[] = []
  for (const page of pages) {
    page.on('console', (m) => {
      if (m.type() !== 'error') return
      const from = m.location().url
      if (isSignedOutBoot(m.text(), from)) return
      errors.push(from ? `${m.text()} (${from})` : m.text())
    })
    page.on('pageerror', (e) => errors.push(String(e)))
  }
  return { errors, clear: () => void (errors.length = 0) }
}

/** A new device of the tenant owner: email, password and Secret Key, then the shared space's toolbar. */
export async function signInOwner(device: Device): Promise<void> {
  const { owner } = instance()
  const { page } = device
  await page.goto(tenantOrigin())
  await page.getByLabel('Email', { exact: true }).fill(owner.email)
  await passwordField(page).fill(recordTyped(owner.password))
  await page.locator('#unlock-secret-key').fill(recordTyped(owner.secretKey))
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await expect(page.getByRole('button', { name: 'New', exact: true })).toBeVisible({ timeout: 60_000 })
}

/**
 * A section of Settings, reached the one way the shell offers (the profile menu's "Settings"): from the space's home,
 * so the shared space is the active one — the space's own sections (Your profile, Storage) are listed only then, and an
 * address typed straight into a new document opens Settings with no active space.
 */
export async function openSettings(page: Page, section: string): Promise<void> {
  await page.goto(tenantOrigin())
  await expect(page.getByRole('button', { name: 'New', exact: true })).toBeVisible({ timeout: 60_000 })
  await page.locator('.profile-button').click()
  await page.getByRole('menuitem', { name: 'Settings' }).click()
  await page.getByRole('navigation', { name: 'Settings' }).getByRole('button', { name: section, exact: true }).click()
}
