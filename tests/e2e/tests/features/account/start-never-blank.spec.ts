// The web client never starts blank (ADR 0136, uc.account.remember-this-device E5), in the release candidate's built shell
// under its served policy (every test here fails on a CSP or Trusted Types violation, `cspViolations.ts`).
//
//   1. A start whose setup-status request (`GET /api/setup`, `boot()`'s first step) never answers shows the start screen,
//      reloads itself once after 15 s, and when the start hangs again shows the slow panel — in German for a German
//      browser — naming the step, `status`; its Reload, with the server answering again, lands on sign-in.
//   2. A hang that the one reload clears lands on sign-in with no panel, and that document signs in: the reloaded start
//      is a working app, not just a painted one. (Its typed password and Secret Key are this file's canaries, which the
//      stack lane's sweep requires of every spec file that ran, `scripts/run_stack_e2e.py` `sweep`.)
//   3. A normal start mounts over the start screen: no panel, the start signalled `mounted`.
//
// The cause on iOS Safari (a discarded tab whose start hangs, ADR 0136 § Context) cannot be reproduced here; a request
// held by `page.route` (never fulfilled) is the hang it stands for. Each test opens a device of its own: the reload
// timestamp lives in that tab's sessionStorage.
import { expect, type Page } from '@playwright/test'
import { test } from '../../../src/cspViolations'
import { recordTyped } from '../../../src/canaries'
import { instance, newDevice, passwordField, tenantOrigin, type Device } from '../../../src/devices'

const SETUP_STATUS = '**/api/setup'
const WATCHDOG_DEADLINE_MS = 15_000
const HANG_TIMEOUT = { timeout: WATCHDOG_DEADLINE_MS + 15_000 }

const devices: Device[] = []
test.afterEach(async () => {
  for (const device of devices.splice(0)) {
    device.confinement.assertConfined()
    await device.context.close().catch(() => undefined)
  }
})

/**
 * Hold every setup-status request [page] sends — never answered — until the returned release is called; from then on
 * new ones are answered. A request held before the release stays held for good: unrouting would hand it to the network,
 * the stalled start would finish on its own and mount over the slow panel the test is about to click (it did, on CI).
 */
async function holdSetupStatus(page: Page): Promise<() => void> {
  let holding = true
  await page.route(SETUP_STATUS, (route) => {
    if (!holding) return route.continue()
  })
  return () => {
    holding = false
  }
}

/** Count the documents [page] loads, so a test can wait for the watchdog's one reload. */
function countLoads(page: Page): { readonly count: number } {
  const loads = { count: 0 }
  page.on('load', () => void loads.count++)
  return loads
}

const signIn = (page: Page) => page.locator('#unlock-secret-key')

test(
  'a start that hangs twice shows the start screen, reloads once, then names the step and offers Reload',
  { tag: ['@uc.account.remember-this-device:a-hung-start-is-never-blank'] },
  async ({ browser }) => {
    test.setTimeout(150_000)
    const device = await newDevice(browser, { locale: 'de-DE' })
    devices.push(device)
    const { page } = device
    const release = await holdSetupStatus(page)
    const loads = countLoads(page)

    await page.goto(tenantOrigin())
    await expect(page.locator('.veyq-start')).toBeVisible()
    await expect(page.locator('#veyq-start-slow')).toBeHidden()

    // The watchdog's one reload, after 15 s without a mount.
    await expect.poll(() => loads.count, HANG_TIMEOUT).toBe(2)
    await expect(page.locator('.veyq-start')).toBeVisible()

    // The second hang within five minutes: no reload, the slow panel, in German, naming the step.
    const slow = page.locator('#veyq-start-slow')
    await expect(slow).toBeVisible(HANG_TIMEOUT)
    await expect(slow.getByRole('heading', { name: 'Das dauert länger als üblich' })).toBeVisible()
    await expect(slow).toContainText('Wartet noch auf Schritt: status')
    expect(loads.count).toBe(2)

    release()
    await slow.getByRole('button', { name: 'Neu laden' }).click()
    await expect(signIn(page)).toBeVisible({ timeout: 30_000 })
    await expect(page.locator('#veyq-start-slow')).toHaveCount(0)
  },
)

test(
  'a hang the one reload clears lands on sign-in, with no panel, and that document signs in',
  { tag: ['@uc.account.remember-this-device:a-hung-start-is-never-blank'] },
  async ({ browser }) => {
    test.setTimeout(90_000)
    const device = await newDevice(browser)
    devices.push(device)
    const { page } = device
    // Only the first document's setup status hangs; the reloaded document's is answered.
    let seen = 0
    await page.route(SETUP_STATUS, (route) => {
      if (seen++ > 0) return route.continue()
    })
    const loads = countLoads(page)

    await page.goto(tenantOrigin())
    await expect(page.locator('.veyq-start')).toBeVisible()
    await expect(signIn(page)).toBeVisible({ timeout: WATCHDOG_DEADLINE_MS + 30_000 })
    expect(loads.count).toBe(2)
    await expect(page.locator('#veyq-start-slow')).toHaveCount(0)

    // The same document, no navigation of the test's own: it signs in and reaches the space.
    const { owner } = instance()
    await page.getByLabel('Email', { exact: true }).fill(owner.email)
    await passwordField(page).fill(recordTyped(owner.password))
    await page.locator('#unlock-secret-key').fill(recordTyped(owner.secretKey))
    await page.getByRole('button', { name: 'Sign in', exact: true }).click()
    await expect(page.getByRole('button', { name: 'New', exact: true })).toBeVisible({ timeout: 60_000 })
    expect(loads.count).toBe(2)
  },
)

test(
  'a normal start mounts over the start screen and signals it',
  { tag: ['@uc.account.remember-this-device:a-hung-start-is-never-blank'] },
  async ({ browser }) => {
    const device = await newDevice(browser)
    devices.push(device)
    const { page } = device
    await page.goto(tenantOrigin())
    await expect(signIn(page)).toBeVisible({ timeout: 30_000 })
    await expect(page.locator('.veyq-start')).toHaveCount(0)
    await expect(page.locator('html')).toHaveAttribute('data-veyq-start', 'mounted')
  },
)
