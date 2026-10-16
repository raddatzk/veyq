// First run, as site/src/content/docs/run/first-run.mdx tells a Server Owner to do it and in its order: see the domain
// answer that it is not set up, create the Server Owner and save the Emergency Kit, sign in, connect the bundled store
// and check it, create the first tenant and copy its invite link — then open that link as the invitee would. The labels are admin-web's own (admin-web/src/i18n/index.ts, en), so a renamed button is
// a red run here and a stale Run page at the same time. It is the scenario, not a fixture (ADR 0090 answer 4): it
// runs once per instance, one browser context throughout, and hands what later scenarios need — the owner's
// credentials and Secret Key, the tenant's domain and invite link — to them in `instance.json` in the run directory,
// never in the repository.
import { test, expect, type BrowserContext, type Page } from '@playwright/test'
import { writeFileSync } from 'node:fs'
import { freshOwner, readKit } from '../../src/accounts'
import { confineToInstance, type Confinement } from '../../src/egress'
import { stack } from '../../src/stack'

test.describe.configure({ mode: 'serial' })

// PrimeVue's Password field adds its placeholder to the accessible name ("Password Enter a password"), so the label
// is matched as the name's start, not as the whole of it.
const password = (on: Page) => on.getByRole('textbox', { name: /^Password\b/ })

let context: BrowserContext
let page: Page
let confinement: Confinement
const handOff: Record<string, unknown> = {}

test.beforeAll(async ({ browser }) => {
  context = await browser.newContext()
  confinement = await confineToInstance(context)
  page = await context.newPage()
})

// Every test of the file, not only the last: a foreign request is red in the step that made it.
test.afterEach(() => confinement.assertConfined())

test.afterAll(async () => {
  await context?.close()
})

test(
  'a fresh instance answers on its domain, over a certificate from the run\'s ACME server, that it is not set up',
  { tag: '@uc.admin.setup-server:status-required-when-fresh' },
  async () => {
    // What a Server Owner sees first: their domain, through Traefik. A certificate that does not chain to the run's
    // Pebble — Traefik's default one when it could not write /acme — fails this navigation outright.
    await page.goto(stack().appOrigin)
    await expect(page.getByText('This instance isn’t set up yet', { exact: true })).toBeVisible()
  },
)

test(
  'a Server Owner sets the instance up, saves the Emergency Kit and signs in',
  { tag: '@uc.admin.setup-server:create-owner-and-complete' },
  async () => {
    const owner = freshOwner(`e2e.${stack().domain}`)

    await page.goto(stack().adminOrigin)
    await expect(page.getByText('Set up this instance', { exact: true })).toBeVisible()
    await page.getByLabel('Email', { exact: true }).fill(owner.email)
    await password(page).fill(owner.password)
    // Leaving the field runs the breach check: one range query, answered locally with "no match" (egress.ts).
    await password(page).blur()
    await expect.poll(() => confinement.breachQueries().length, { message: 'the breach check asked once' }).toBe(1)
    await expect(page.getByText('This password could not be checked against known data breaches.')).toHaveCount(0)
    await page.getByRole('button', { name: 'Continue' }).click()

    // Every key of the account is generated in this browser; nothing has been sent yet.
    await expect(page.getByText('Save your Emergency Kit', { exact: true })).toBeVisible({ timeout: 60_000 })
    const download = page.waitForEvent('download')
    await page.getByRole('button', { name: 'Download Emergency Kit (PDF)' }).click()
    const kit = await readKit(await download)
    expect(kit.email, 'the kit names the account it was minted for').toBe(owner.email)
    await page.getByRole('checkbox', { name: 'I have saved my Emergency Kit somewhere safe.' }).check()
    await page.getByRole('button', { name: 'Create the account and sign in' }).click()

    // Only now is the account created; the console asks for a sign-in.
    await expect(page.getByText('Sign in as the Server Owner to administer this instance.')).toBeVisible()
    await page.getByLabel('Email', { exact: true }).fill(owner.email)
    await password(page).fill(owner.password)
    await page.getByRole('button', { name: 'Sign in' }).click()
    await expect(page.getByRole('heading', { name: 'Server administration', level: 1 })).toBeVisible()

    handOff.owner = { ...owner, secretKey: kit.secretKey }
  },
)

test(
  'the Server Owner connects the bundled store and it passes its check',
  { tag: '@uc.admin.configure-object-store:server-owner-sets-and-checks-the-store' },
  async () => {
    // ADR 0089: the environment only offers the bundled store; choosing and checking it is the owner's act.
    await page.getByRole('button', { name: 'Object store' }).click()
    await page.getByRole('combobox', { name: 'Store' }).click()
    await page.getByRole('option', { name: 'Use the bundled store' }).click()
    await page.getByRole('button', { name: 'Save' }).click()
    await expect(page.getByText('This setting is saved but not in use yet', { exact: false })).toBeVisible()
    await page.getByRole('button', { name: 'Check store' }).click()
    // The probe uploads through the public origin the backend signs for — through Traefik and its ACME certificate.
    await expect(page.getByText('The store passed its check. Tenants can be created.')).toBeVisible({ timeout: 60_000 })
  },
)

test(
  'the Server Owner creates the first tenant and copies its invite link',
  { tag: ['@uc.admin.create-tenant:create-pending-tenant', '@uc.admin.create-tenant:invite-link-handoff'] },
  async () => {
    const tenantDomain = `family.${stack().domain}`
    await page.getByRole('button', { name: 'Create tenant' }).click()
    const dialog = page.getByRole('dialog', { name: 'Create tenant' })
    await dialog.getByLabel('Tenant name').fill('E2E Family')
    await dialog.getByLabel('Domain').fill(tenantDomain)
    await dialog.getByLabel('Who should own this tenant? (email)').fill((handOff.owner as { email: string }).email)
    await dialog.getByRole('button', { name: 'Create' }).click()
    await expect(dialog.getByText('Tenant created.')).toBeVisible()

    // A tenant has no mail until its owner sets it up, so the first invite is a link the Server Owner passes on.
    await context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: stack().adminOrigin })
    await dialog.getByRole('button', { name: 'Copy link' }).click()
    const inviteLink = await page.evaluate(() => navigator.clipboard.readText())
    const invite = new URL(inviteLink)
    expect(invite.origin, 'the invite opens on the tenant domain').toBe(`https://${tenantDomain}`)
    expect(invite.pathname).toBe('/tenant/accept')
    expect(invite.hash.length, 'the invitation secret travels in the fragment').toBeGreaterThan(1)
    await dialog.getByRole('button', { name: 'Done' }).click()
    await expect(page.getByRole('button', { name: 'E2E Family' })).toBeVisible()

    handOff.tenantDomain = tenantDomain
    handOff.inviteLink = inviteLink
    writeFileSync(`${stack().outDir}/instance.json`, JSON.stringify(handOff, null, 2))
  },
)

test(
  'the invite link opens on the new tenant domain, over a certificate from the run\'s ACME server',
  { tag: '@uc.admin.tenant-reachability:domain-actually-routes' },
  async ({ browser }) => {
    // The invited owner's browser, not the Server Owner's: a context of its own, confined the same way. Only the
    // preview is opened — accepting is the Memory round trip's (cr-stack-e2e phase 3).
    const invitee = await browser.newContext()
    const confined = await confineToInstance(invitee)
    const tab = await invitee.newPage()
    const { inviteLink, tenantDomain } = handOff as { inviteLink: string; tenantDomain: string }
    // Creating the tenant regenerated Traefik's routers (ADR 0063); its certificate for the new domain follows from
    // the run's Pebble a moment later, and until then Traefik serves its default one — which this browser does not
    // trust, so the navigation is retried only until that certificate arrives, and at the deadline it is red with
    // the browser's own error (ERR_CERT_AUTHORITY_INVALID for a certificate not from Pebble).
    await expect(async () => {
      await tab.goto(inviteLink)
    }).toPass({ timeout: 60_000, intervals: [1_000] })
    await expect(tab.getByText(`You have been invited to own “E2E Family” (${tenantDomain}).`)).toBeVisible()
    confined.assertConfined()
    await invitee.close()
  },
)
