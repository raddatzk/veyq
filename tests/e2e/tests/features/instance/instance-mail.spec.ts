// The Server Owner's release check and instance mail in admin-web (ADR 0097, uc.admin.learn-of-new-releases; inbox
// #464's browser pass). Server → Release: the daily check pointed at a mirror and switched off, both kept after a
// reload, then restored. Server → Mail: settings saved against the run's mail relay (the overlay's Mailpit), the
// confirmation code read out of the mail the instance sent — to the Server Owner and nobody else — a wrong code refused,
// the mailed one confirming the address, and the confirmation kept after a reload. Then the notices: the check pointed
// at the run's stub release mirror, the console showing each newer release and the unscheduled major as urgent, each
// notice mailed once to the confirmed address, the planned major's with its date as an `.ics`, and the check switched
// off.
//
// The notices need a release-stamped instance (`veyq.release.version`; a `-SNAPSHOT` or unstamped build has no release
// line and makes no request), and the lane's candidate is stamped `<major>.<minor>` (checks.yml's `version` job) without
// any published release around it. So the run stamps its own instance and serves releases newer than the stamp — a test double like Pebble, decided by the owner
// (inbox #464, option (a)): tests/e2e/stack/docker-compose.e2e.yml's `release-mirror` and `app`, the files written by the
// publish's own producer (scripts/run_stack_e2e.py, stage `release mirror`), named for the suite in `stack.json`.
//
// The labels and the mail's words are admin-web's own (src/admin-web/src/i18n/index.ts, en) and the backend's
// (InstanceMailCopy). It starts from the instance the setup project left (the Server Owner exists); the first test
// restores what it switches, and the last leaves the check switched off, as an offline run's must be (ADR 0097).
import { expect, type Page } from '@playwright/test'
import { test } from '../../../src/cspViolations'
import { openConsole } from '../../../src/console'
import { instance, newDevice, type Device } from '../../../src/devices'
import { attachmentText, awaitMails, mailsTo, type Mail } from '../../../src/mail'
import { stack } from '../../../src/stack'

test.describe.configure({ mode: 'serial' })

// The relay as a Server Owner types it: the overlay's alias (a dotted name — the settings refuse a bare one), plain
// SMTP on Mailpit's port, no STARTTLS.
const RELAY = { host: 'mail.relay.test', port: '1025' }
const FROM = 'veyq-notices@e2e.veyq.test'
const MIRROR = 'https://releases.mirror.example/veyq'
const CODE_MAIL = /^Confirm your veyq server's mail/

let admin: Device

/**
 * The console in a new document, signed in, at one Server section — what is read after it was fetched from the server.
 * The first document of the run's context asks for the Server Owner's sign-in; every later one opens signed in, since
 * admin-web revives its session from the refresh cookie on a reload (`openConsole`).
 */
async function openServerSection(page: Page, section: 'Release' | 'Mail'): Promise<void> {
  await openConsole(page)
  await page.getByRole('button', { name: section, exact: true }).click()
}

/** The console's Server → Release read again from the server: away to another section and back, which remounts it. */
async function rereadRelease(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Mail', exact: true }).click()
  await expect(page.getByRole('heading', { level: 2, name: 'Mail' })).toBeVisible()
  await page.getByRole('button', { name: 'Release', exact: true }).click()
  await expect(page.getByRole('heading', { level: 2, name: 'New releases' })).toBeVisible()
}

const exactly = (text: string) => new RegExp(`^${text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`)

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

test(
  'pointed at the mirror, the check shows each newer release, the unscheduled major as urgent, and mails each once',
  {
    tag: [
      '@uc.admin.learn-of-new-releases:a-newer-release-is-noticed-in-the-console',
      '@uc.admin.learn-of-new-releases:majors-are-told-apart-and-an-unscheduled-one-is-urgent',
      '@uc.admin.learn-of-new-releases:each-notice-is-mailed-once-with-a-calendar-date',
      '@uc.admin.learn-of-new-releases:the-check-can-be-switched-off-and-pointed-elsewhere',
    ],
  },
  async () => {
    const { page } = admin
    const { owner } = instance()
    const releases = stack().releases
    const major = (release: string) => release.split('.')[0]
    // ADR 0085 decision 5 (ReleaseNoticePolicy.windowEnd): the running major leaves the window on 1 May, two years
    // after the year of the planned major that made it non-current.
    const windowEnd = `${Number(releases.plannedDate.slice(0, 4)) + 2}-05-01`
    const lines = {
      newer: `veyq ${releases.newer} is available.`,
      planned: `veyq ${major(releases.planned)} is out (first release ${releases.planned}). The major this server runs ` +
        `leaves the apps’ window on ${windowEnd} — upgrade before then.`,
      unscheduled: `veyq ${major(releases.unscheduled)} (release ${releases.unscheduled}) is an unscheduled major: a ` +
        'security fix that needed a removal. Upgrade now.',
    }
    const subjects = {
      newer: exactly(`veyq ${releases.newer} is available / ist verfügbar`),
      planned: exactly(`veyq ${major(releases.planned)} is out / ist erschienen`),
      unscheduled: exactly(`URGENT: veyq ${major(releases.unscheduled)} — upgrade now / DRINGEND: jetzt aktualisieren`),
    }
    const anyNotice = new RegExp(Object.values(subjects).map((r) => `(?:${r.source})`).join('|'))

    // The stamp took: the console names the release the run installed, and the check — reading veyq's GitHub
    // Releases, which the run cannot reach — has found nothing and mailed nothing.
    await openServerSection(page, 'Release')
    await expect(page.getByText(releases.running, { exact: true })).toBeVisible()
    await expect(page.getByText('Nothing newer has been found.')).toBeVisible()
    expect(await mailsTo(owner.email, anyNotice), 'no notice mailed before the check found one').toEqual([])

    await checkSource(page).fill(releases.source)
    await page.getByRole('button', { name: 'Save', exact: true }).click()
    await expect(checkSource(page)).toHaveValue(releases.source)

    // The next check (every 5 s in the run) reads the mirror; the console, read again, shows what it found.
    await expect(async () => {
      await rereadRelease(page)
      await expect(page.getByRole('listitem').filter({ hasText: lines.newer })).toBeVisible({ timeout: 1_000 })
    }).toPass({ timeout: 60_000, intervals: [2_000] })
    await expect(page.getByRole('listitem').filter({ hasText: lines.planned })).toBeVisible()
    // The unscheduled major is not one row among the others: it stands apart, as an alert marked urgent.
    const urgent = page.getByRole('alert').filter({ hasText: lines.unscheduled })
    await expect(urgent).toBeVisible()
    await expect(urgent).toContainText('Urgent:')
    await expect(page.getByRole('listitem').filter({ hasText: lines.unscheduled })).toHaveCount(0)
    // The running release and older ones are no notice.
    await expect(page.getByRole('listitem').filter({ hasText: `veyq ${releases.running} is available.` })).toHaveCount(0)

    // One mail per notice, to the confirmed Server Owner and nobody else.
    const [newer] = await awaitMails(owner.email, subjects.newer)
    const [planned] = await awaitMails(owner.email, subjects.planned)
    const [unscheduled] = await awaitMails(owner.email, subjects.unscheduled)
    for (const mail of [newer!, planned!, unscheduled!]) {
      expect(mail.to, `${mail.subject}: to the Server Owner alone`).toEqual([owner.email])
    }
    // The planned major's date, written out and attached as a calendar entry; an undated notice carries none.
    expect(planned!.text).toContain(`stays in the apps' window until ${windowEnd}; upgrade before then.`)
    expect(planned!.text).toContain('The date is attached as a calendar entry.')
    expect(planned!.attachments.map((a) => a.fileName)).toEqual([`veyq-${releases.planned}.ics`])
    const [ics] = planned!.attachments
    expect(ics!.contentType).toMatch(/^text\/calendar\b/)
    const calendar = await attachmentText(planned!, ics!.partId)
    expect(calendar).toContain('BEGIN:VEVENT')
    expect(calendar).toContain(`DTSTART;VALUE=DATE:${windowEnd.replaceAll('-', '')}`)
    expect(calendar).toContain(`SUMMARY:Upgrade veyq to ${major(releases.planned)} or later`)
    expect(newer!.attachments, 'a release notice is undated').toEqual([])
    expect(unscheduled!.attachments, 'an unscheduled major is undated').toEqual([])
    expect(unscheduled!.text).toContain('URGENT:')

    // Mailed once: two more checks run after the mails arrived — each reads the mirror again and finds the same
    // notices — and nothing more is mailed. The console's "Last read successfully" time, read again, counts them.
    // The panel was last read when the notices showed, and that read can fall between the check's two writes — the
    // notices stored, its success not yet (`ReleaseCheck.check`: `recordFound`, then `recordSuccess`, each its own
    // transaction): then it says the source "has not been read successfully yet" until it is read again (seen
    // 2026-10-26: the statement log had the panel's read between the notices' inserts and the success's update). So it is
    // read again until it names a successful read before that time is taken as the one the next two checks must move.
    const lastRead = page.getByText(/^Last read successfully:/)
    await expect(async () => {
      await rereadRelease(page)
      await expect(lastRead).toBeVisible({ timeout: 1_000 })
    }).toPass({ timeout: 30_000, intervals: [2_000] })
    let seen = await lastRead.textContent()
    for (let check = 0; check < 2; check++) {
      await expect(async () => {
        await rereadRelease(page)
        expect(await lastRead.textContent()).not.toEqual(seen)
      }).toPass({ timeout: 30_000, intervals: [2_000] })
      seen = await lastRead.textContent()
    }
    const mailed: Mail[] = await mailsTo(owner.email, anyNotice)
    expect(mailed.map((m) => m.subject).sort(), 'each notice mailed exactly once').toEqual(
      [newer!, planned!, unscheduled!].map((m) => m.subject).sort())

    // Switched off: no request, nothing new — and the panel says so after a reload.
    await checkEnabled(page).uncheck()
    await page.getByRole('button', { name: 'Save', exact: true }).click()
    await expect(page.getByText('Switched off: this server makes no request and finds nothing new.')).toBeVisible()
    await openServerSection(page, 'Release')
    await expect(checkEnabled(page)).not.toBeChecked()
    await expect(page.getByText('Switched off: this server makes no request and finds nothing new.')).toBeVisible()
  },
)
