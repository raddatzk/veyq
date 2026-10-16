// The Server Owner's release check and instance mail in admin-web (ADR 0097, uc.admin.learn-of-new-releases; inbox
// #464's browser pass, automated as far as the lane's candidate allows). Server → Release: the daily check pointed at a
// mirror and switched off, both kept after a reload, then restored. Server → Mail: settings saved against the run's
// mail relay (the overlay's Mailpit), the confirmation code read out of the mail the instance sent — to the Server
// Owner and nobody else — a wrong code refused, the mailed one confirming the address, and the confirmation kept after a
// reload.
//
// Not here, and why: a notice in the console and its one mail with an `.ics`. The check reads releases only on a build
// stamped with a release (`veyq.release.version`, ReleaseCheck: a `-SNAPSHOT` or unstamped build has no release line
// and makes no request), and the lane's candidate on a push or a pull request is a `-SNAPSHOT` by design (ci.yml's
// `version` job: an image that was never released never carries a release's name).
//
// The labels are admin-web's own (admin-web/src/i18n/index.ts, en). It starts from the instance the setup project left
// (the Server Owner exists) and restores what it switches.
import { test, expect, type Page } from '@playwright/test'
import { recordTyped } from '../../src/canaries'
import { instance, newDevice, passwordField, type Device } from '../../src/devices'
import { awaitMails, mailsTo } from '../../src/mail'
import { stack } from '../../src/stack'

test.describe.configure({ mode: 'serial' })

// The relay as a Server Owner types it: the overlay's alias (a dotted name — the settings refuse a bare one), plain
// SMTP on Mailpit's port, no STARTTLS.
const RELAY = { host: 'mail.relay.test', port: '1025' }
const FROM = 'veyq-notices@e2e.veyq.test'
const MIRROR = 'https://releases.mirror.example/veyq'
const CODE_MAIL = /^Confirm your veyq instance mail/

let admin: Device

/**
 * The console in a new document, signed in, at one Server section. admin-web keeps no session across a reload, so every
 * new document asks for the Server Owner's sign-in again — what is read after it was fetched from the server.
 */
async function openServerSection(page: Page, section: 'Release' | 'Mail'): Promise<void> {
  const { owner } = instance()
  await page.goto(stack().adminOrigin)
  await page.getByLabel('Email', { exact: true }).fill(owner.email)
  await passwordField(page).fill(recordTyped(owner.password))
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('heading', { name: 'Server administration', level: 1 })).toBeVisible({ timeout: 60_000 })
  await page.getByRole('button', { name: section, exact: true }).click()
}

const checkEnabled = (page: Page) => page.getByRole('checkbox', { name: 'Look for new releases once a day' })
const checkSource = (page: Page) => page.getByRole('textbox', { name: /^Source/ })

test.beforeAll(async ({ browser }) => {
  admin = await newDevice(browser)
})

test.afterEach(() => admin?.confinement.assertConfined())

test.afterAll(async () => {
  await admin?.context.close()
})

test(
  'the Server Owner points the release check at a mirror and switches it off; both hold after a reload',
  { tag: '@uc.admin.learn-of-new-releases:the-check-can-be-switched-off-and-pointed-elsewhere' },
  async () => {
    const { page } = admin
    await openServerSection(page, 'Release')
    await expect(page.getByRole('heading', { level: 2, name: 'New releases' })).toBeVisible()
    // On by default, reading the published GitHub Releases (an empty source).
    await expect(checkEnabled(page)).toBeChecked()
    await expect(checkSource(page)).toHaveValue('')

    await checkEnabled(page).uncheck()
    await checkSource(page).fill(MIRROR)
    await page.getByRole('button', { name: 'Save', exact: true }).click()
    await expect(page.getByText('Switched off: this server makes no request and finds nothing new.')).toBeVisible()

    await openServerSection(page, 'Release')
    await expect(checkEnabled(page)).not.toBeChecked()
    await expect(checkSource(page)).toHaveValue(MIRROR)
    await expect(page.getByText('Switched off: this server makes no request and finds nothing new.')).toBeVisible()

    // Restored: on, and the published releases again.
    await checkEnabled(page).check()
    await checkSource(page).fill('')
    await page.getByRole('button', { name: 'Save', exact: true }).click()
    await expect(page.getByText('Switched off: this server makes no request and finds nothing new.')).toHaveCount(0)
    await openServerSection(page, 'Release')
    await expect(checkEnabled(page)).toBeChecked()
    await expect(checkSource(page)).toHaveValue('')
  },
)

test(
  'instance mail: a save mails a code to the Server Owner alone, a wrong code is refused, the mailed one confirms',
  {
    tag: [
      '@uc.admin.learn-of-new-releases:the-address-is-confirmed-before-any-notice',
      '@uc.admin.learn-of-new-releases:instance-mail-reaches-only-the-server-owner',
    ],
  },
  async () => {
    const { page } = admin
    const { owner } = instance()
    await openServerSection(page, 'Mail')
    await expect(page.getByRole('heading', { level: 2, name: 'Mail' })).toBeVisible()
    expect(await mailsTo(owner.email, CODE_MAIL), 'nothing mailed before a save').toEqual([])

    await page.getByRole('textbox', { name: /^SMTP host/ }).fill(RELAY.host)
    await page.getByRole('textbox', { name: /^Port/ }).fill(RELAY.port)
    await page.getByRole('textbox', { name: /^From address/ }).fill(FROM)
    await page.getByRole('switch', { name: 'Use STARTTLS' }).uncheck()
    await page.getByRole('button', { name: 'Save and send test mail' }).click()
    await expect(page.getByText(`A code was mailed to ${owner.email}.`, { exact: false })).toBeVisible({ timeout: 30_000 })

    // The test mail, read where it arrived: one, addressed to the Server Owner and to nobody else.
    const [mail] = await awaitMails(owner.email, CODE_MAIL)
    expect(mail!.to, 'the instance mails the Server Owner and nobody else').toEqual([owner.email])
    const code = /^\s+(\d{8})\s*$/m.exec(mail!.text)?.[1]
    expect(code, `an 8-digit code on a line of its own in:\n${mail!.text}`).toBeDefined()

    // A code that is not the one mailed is refused (the mailed code is the positive control below).
    const wrong = code === '00000000' ? '11111111' : '00000000'
    await page.getByRole('textbox', { name: 'Code from the test mail' }).fill(wrong)
    await page.getByRole('button', { name: 'Confirm', exact: true }).click()
    await expect(page.getByText('That code is not valid any more, or not the one mailed. Save again for a new one.'))
      .toBeVisible()

    await page.getByRole('textbox', { name: 'Code from the test mail' }).fill(code!)
    await page.getByRole('button', { name: 'Confirm', exact: true }).click()
    await expect(page.getByText(`Confirmed for ${owner.email} since`, { exact: false })).toBeVisible()

    await openServerSection(page, 'Mail')
    await expect(page.getByText(`Confirmed for ${owner.email} since`, { exact: false })).toBeVisible()
    await expect(page.getByRole('textbox', { name: /^SMTP host/ })).toHaveValue(RELAY.host)
    // Exactly one mail: the confirmation sent nothing further.
    expect(await mailsTo(owner.email, CODE_MAIL)).toHaveLength(1)
  },
)
