// S1 unlock (cr-profile-where-the-time-goes, decision 1): a new device signs in with email, password and Secret Key at
// the tenant's root and opens the shared space. The window runs from the click on "Sign in" to the space's toolbar;
// the three values are typed before it opens. Expected: Argon2id dominates by design (ADR 0017's parameters) — the
// question is what else costs anything.
import { test, expect } from '@playwright/test'
import { instance, profiled } from './profiling'

test('S1 unlock: a new device signs in and opens the space', async ({ browser }) => {
  const { owner, tenantDomain } = instance()
  // A fresh context is a new device: no session, no device wrap — it derives everything from the two secrets.
  const context = await browser.newContext()
  const page = await context.newPage()
  const cdp = await context.newCDPSession(page)

  await page.goto(`https://${tenantDomain}/`)
  await page.getByLabel('Email', { exact: true }).fill(owner.email)
  await page.getByRole('textbox', { name: /^Password\b/ }).fill(owner.password)
  await page.locator('#unlock-secret-key').fill(owner.secretKey)

  await profiled(browser, page, cdp, async () => {
    await page.getByRole('button', { name: 'Sign in', exact: true }).click()
    await expect(page.getByRole('button', { name: 'New', exact: true })).toBeVisible({ timeout: 5 * 60_000 })
  })
  // Its failed requests and error answers are the driver's to judge (`profile_hot_paths.py` `request_failures`).
  await context.close()
})
