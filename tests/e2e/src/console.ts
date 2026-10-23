// The Server Owner's console (admin-web) as the scenarios reach it, and the one way an account is born there: a tenant
// the Server Owner creates for a fresh address, its invite accepted on the tenant's own domain. A scenario that needs
// an uploader, a moderator's case or a count of its own makes its tenant this way, so what it counts is its own and the
// shared tenant the `features` project starts from is left as it was found (moderation.spec.ts, server-owner-statement.spec.ts
// and tenant-and-account-erasure.spec.ts birth theirs the same way). The labels are admin-web's and src/web/'s own
// (each package's i18n, en).
import { expect, type Page } from '@playwright/test'
import { freshOwner, readKit } from './accounts'
import { recordTyped } from './canaries'
import { instance, passwordField, type Device } from './devices'
import { stack } from './stack'

export interface Account {
  email: string
  password: string
  secretKey: string
}

/**
 * The console in a new document, signed in. admin-web revives its session from the refresh cookie on a reload
 * (`src/admin-web/src/app/boot.ts`), so a context that signed in once opens it signed in, and a new context asks first:
 * both are taken here, never assumed.
 */
export async function openConsole(page: Page): Promise<void> {
  const { owner } = instance()
  await page.goto(stack().adminOrigin)
  const home = page.getByRole('heading', { name: 'Server administration', level: 1 })
  const email = page.getByLabel('Email', { exact: true })
  await expect(home.or(email)).toBeVisible({ timeout: 60_000 })
  if (await email.isVisible()) {
    await email.fill(owner.email)
    await passwordField(page).fill(recordTyped(owner.password))
    await page.getByRole('button', { name: 'Sign in' }).click()
  }
  await expect(home).toBeVisible({ timeout: 60_000 })
}

/** The sign-in form of a device that holds no key for the account: email, password and Secret Key. */
export async function submitSignIn(page: Page, account: Account): Promise<void> {
  await page.getByLabel('Email', { exact: true }).fill(account.email)
  await passwordField(page).fill(recordTyped(account.password))
  await page.locator('#unlock-secret-key').fill(recordTyped(account.secretKey))
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
}

/**
 * A new tenant [name] on [domain] (a subdomain of the run's domain, so the resolver and the run's ACME server know it),
 * owned by a fresh account that [device] births: the Server Owner creates it on [admin] and copies the invite link, the
 * person opens it, registers as [handle], saves the kit and accepts — then signs in to the tenant's home. [handle] is a
 * per-tenant username, declared plaintext (`pii`), so it is no canary.
 */
export async function birthTenant(
  admin: Device, tenant: { name: string; domain: string; handle: string }, device: Device,
): Promise<Account> {
  const { page } = admin
  const fresh = freshOwner(`e2e.${stack().domain}`)
  await openConsole(page)
  await page.getByRole('button', { name: 'Create tenant' }).click()
  const dialog = page.getByRole('dialog', { name: 'Create tenant' })
  await dialog.getByLabel('Tenant name').fill(tenant.name)
  await dialog.getByLabel('Domain').fill(tenant.domain)
  await dialog.getByLabel('Who should own this tenant? (email)').fill(fresh.email)
  await dialog.getByRole('button', { name: 'Create' }).click()
  await expect(dialog.getByText('Tenant created.')).toBeVisible()
  await admin.context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: stack().adminOrigin })
  await dialog.getByRole('button', { name: 'Copy link' }).click()
  const inviteLink = await page.evaluate(() => navigator.clipboard.readText())
  await dialog.getByRole('button', { name: 'Done' }).click()

  const tab = device.page
  // The tenant's domain answers once Traefik has its router and certificate: a navigation that fails is retried.
  await expect(async () => {
    await tab.goto(inviteLink)
  }).toPass({ timeout: 60_000, intervals: [1_000] })
  await expect(tab.getByText(`You have been invited to own “${tenant.name}” (${tenant.domain}).`)).toBeVisible({ timeout: 60_000 })
  await tab.getByLabel('Username').fill(tenant.handle)
  await tab.locator('#join-password').fill(recordTyped(fresh.password))
  await tab.getByRole('button', { name: 'Continue' }).click()
  const download = tab.waitForEvent('download')
  await tab.getByRole('button', { name: 'Download Emergency Kit (PDF)' }).click()
  const kit = await readKit(await download)
  expect(kit.email, 'the kit names the account it was minted for').toBe(fresh.email)
  await tab.getByRole('checkbox', { name: 'I have saved my Emergency Kit somewhere safe.' }).check()
  await tab.getByRole('button', { name: 'Accept & activate' }).click()
  await expect(tab.getByText(`“${tenant.name}” is now active and yours.`)).toBeVisible({ timeout: 60_000 })
  // "Sign in" opens the sign-in form of this new device: email, password and the Secret Key the kit just carried.
  await tab.getByRole('button', { name: 'Sign in', exact: true }).click()
  const account = { email: fresh.email, password: fresh.password, secretKey: kit.secretKey }
  await submitSignIn(tab, account)
  await expect(tab.getByRole('button', { name: 'New', exact: true })).toBeVisible({ timeout: 60_000 })
  return account
}
