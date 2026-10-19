// First run, as site/src/content/docs/run/first-run.mdx tells a Server Owner to do it and in its order: see the domain
// answer that it is not set up, create the Server Owner, save the Emergency Kit and choose the operating mode, sign in,
// connect the bundled store and check it, publish who runs the instance (the warnings per mode on the way), create the
// first tenant and copy its invite link — then open that link as the invitee would, and see the provider
// identification on the tenant's sign-in page. The labels are admin-web's own (admin-web/src/i18n/index.ts, en), so a renamed button is
// a red run here and a stale Run page at the same time. It is the scenario, not a fixture (ADR 0090 answer 4): it
// runs once per instance, one browser context throughout, and hands what later scenarios need — the owner's
// credentials and Secret Key, the tenant's domain and invite link — to them in `instance.json` in the run directory,
// never in the repository. What it types that the product promises never to send — the password — is recorded for the
// orchestrator's sweep (src/canaries.ts); the email and the tenant's name are declared plaintext and are not.
import { test, expect, type BrowserContext, type Page } from '@playwright/test'
import { writeFileSync } from 'node:fs'
import { freshOwner, readKit } from '../../src/accounts'
import { recordTyped } from '../../src/canaries'
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
  { tag: ['@uc.admin.setup-server:create-owner-and-complete', '@uc.admin.setup-server:setup-stores-the-operating-mode'] },
  async () => {
    const owner = freshOwner(`e2e.${stack().domain}`)

    await page.goto(stack().adminOrigin)
    await expect(page.getByText('Set up this instance', { exact: true })).toBeVisible()
    await page.getByLabel('Email', { exact: true }).fill(owner.email)
    // The password never leaves the page (Model B: only what Argon2id derives from it does) — a canary for the sweep.
    await password(page).fill(recordTyped(owner.password))
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
    await page.getByRole('button', { name: 'Continue' }).click()

    // Step 4: the operating mode, after its notice and before anything is posted (ADR 0093 § 14). Private needs the
    // tick; the run chooses club, organisation or company, the mode an unchosen instance runs as.
    await expect(page.getByText('How is this instance run?', { exact: true })).toBeVisible()
    await expect(page.getByText('This is your own assessment, not veyq’s, and not legal advice.', { exact: false })).toBeVisible()
    const create = page.getByRole('button', { name: 'Create the account and sign in' })
    await expect(create, 'no mode is preselected').toBeDisabled()
    await page.getByRole('button', { name: 'Private (family, circle of friends)' }).click()
    await expect(create, 'private waits for the tick').toBeDisabled()
    await expect(page.getByRole('checkbox', { name: 'I have read this and choose Private on my own responsibility.' })).toBeVisible()
    await page.getByRole('button', { name: 'Club, organisation or company' }).click()
    await expect(page.getByRole('checkbox', { name: 'I have read this and choose Private on my own responsibility.' })).toHaveCount(0)
    await create.click()

    // Only now is the account created; the console asks for a sign-in.
    await expect(page.getByText('Sign in as the Server Owner to administer this instance.')).toBeVisible()
    await page.getByLabel('Email', { exact: true }).fill(owner.email)
    await password(page).fill(recordTyped(owner.password))
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
  'the Server Owner publishes who runs the instance; the console warns in the organisation mode only',
  {
    tag: [
      '@uc.admin.operator-details:admin-web-warns-in-the-organisation-mode-only',
      '@uc.admin.operator-details:the-notice-comes-before-the-mode',
      '@uc.admin.operator-details:the-privacy-notice-values-are-set-and-never-published-unsaved',
    ],
  },
  async () => {
    const warnContact = page.getByText('Public links are on and no instance contact is set.', { exact: false })
    const warnIdentification = page.getByText('The provider identification is incomplete.', { exact: false })
    const warnPrivacy = page.getByText('The privacy notice names no recipient or no supervisory authority', { exact: false })
    const operatorPanel = () => page.getByRole('button', { name: 'Operator', exact: true }).click()
    const modePanel = () => page.getByRole('button', { name: 'Operating mode', exact: true }).click()

    // Organisation (chosen at setup), nothing published, public links on: every warning, never a refusal.
    await operatorPanel()
    await expect(warnContact).toBeVisible()
    await expect(warnIdentification).toBeVisible()
    await expect(warnPrivacy).toBeVisible()

    // Private, on the operator's own assessment: the notice, the tick, and then neither warning, every value empty.
    await modePanel()
    await expect(page.getByText('In effect: Club, organisation or company.')).toBeVisible()
    await page.getByRole('button', { name: 'Private (family, circle of friends)' }).click()
    const setMode = page.getByRole('button', { name: 'Set operating mode' })
    await expect(setMode).toBeDisabled()
    await page.getByRole('checkbox', { name: 'I have read this and choose Private on my own responsibility.' }).check()
    await setMode.click()
    await expect(page.getByText('In effect: Private (family, circle of friends).')).toBeVisible()
    await operatorPanel()
    await expect(page.getByRole('heading', { name: 'Operator', level: 2 })).toBeVisible()
    await expect(warnContact).toHaveCount(0)
    await expect(warnIdentification).toHaveCount(0)
    await expect(warnPrivacy).toHaveCount(0)

    // Back to organisation: the warnings return until the contact and the identification are published.
    await modePanel()
    await page.getByRole('button', { name: 'Club, organisation or company' }).click()
    await page.getByRole('button', { name: 'Set operating mode' }).click()
    await expect(page.getByText('In effect: Club, organisation or company.')).toBeVisible()
    await operatorPanel()
    await expect(warnContact).toBeVisible()
    await page.getByLabel('Contact address').fill(`contact@${stack().domain}`)
    await page.getByLabel('Languages it answers in').fill('en, de')
    await page.getByLabel('Name', { exact: true }).fill('E2E Operator e. V.')
    await page.getByLabel('Address for service').fill('Teststraße 1\n12345 Teststadt')
    await page.getByLabel('Email', { exact: true }).fill(`info@${stack().domain}`)
    // The privacy notice's values (ADR 0035 decision 8): the authority, and one recipient whose country is left open,
    // so the full notice shows that gap and the reduced one leaves it out (terms-operator-section.spec.ts reads both).
    await page.getByLabel('Data-protection supervisory authority').fill('E2E Datenschutzaufsicht')
    await page.getByRole('button', { name: 'Add a recipient' }).click()
    const recipient = page.getByTestId('operator-recipient')
    await recipient.getByLabel('Name').fill('E2E Hosting GmbH')
    await recipient.locator('.operator-recipient__kind').click() // what they do: a kind from the list
    await page.getByRole('option', { name: 'Hosts the server' }).click()
    await page.getByRole('button', { name: 'Save', exact: true }).click()
    await expect(warnContact).toHaveCount(0)
    await expect(warnIdentification).toHaveCount(0)
    await expect(warnPrivacy).toHaveCount(0)
    await expect(recipient.getByLabel('Name')).toHaveValue('E2E Hosting GmbH') // the server's answer, re-read
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
  {
    tag: [
      '@uc.admin.tenant-reachability:domain-actually-routes',
      '@uc.admin.operator-details:the-identification-appears-on-the-login-and-share-pages',
      '@uc.admin.privacy-notice:public-without-signing-in',
    ],
  },
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

    // GDPR Art. 13 applies where the account is born: the invite links the privacy notice's account passage, in a tab
    // of its own (the invitation's secret rides this page's fragment), readable with no account at all.
    const opened = invitee.waitForEvent('page')
    await tab.getByRole('link', { name: 'What this instance keeps about your account, and why' }).click()
    const notice = await opened
    expect(new URL(notice.url()).pathname + new URL(notice.url()).hash).toBe('/privacy#account')
    await expect(notice.locator('#account')).toContainText('the server keeps your email address', { timeout: 60_000 })
    await notice.close()

    // The provider identification the Server Owner published, on the tenant's sign-in page (ADR 0035 decision 7):
    // read before sign-in, the same on every host, behind a footer link.
    await tab.goto(`https://${tenantDomain}/login`)
    await tab.getByRole('button', { name: 'Legal notice' }).click()
    await expect(tab.getByText('E2E Operator e. V.')).toBeVisible()
    await expect(tab.getByRole('link', { name: `info@${stack().domain}` })).toHaveAttribute('href', `mailto:info@${stack().domain}`)
    confined.assertConfined()
    await invitee.close()
  },
)
