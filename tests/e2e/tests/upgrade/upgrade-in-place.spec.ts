// The browser half of lanes L2 and L3 (ADR 0091 decision 6, amended 2026-11-26): an instance that a published release's
// own API seeder wrote into (`upgrade.a-seed.e2e.test.ts` of its tree) now runs the candidate — in lane L2
// (`scripts/run_stack_upgrade.py` stage `browser`) upgraded in place by the starting release's own `veyqctl upgrade`, in
// lane L3 (`scripts/run_stack_restore.py` stage `browser`) restored from that release's archived backup into a fresh
// install of the candidate by the candidate's `veyqctl restore` — and the candidate's API reader has asserted every
// item. This opens what was seeded the way its owner does after the upgrade: a new device, email, password and Secret
// Key from the record (the person's, and nothing else, `src/web/src/test/upgradeRecord.ts`), the personal space, the
// Memory with its place and its chapter, and the Depot folder with each of its files — on the candidate's shipped web
// client, which the API reader never loads.
//
// It runs only in lanes L2 and L3, never in an L1 leg: its project `upgrade` has no dependency on the first-run `setup`
// (the instance was set up by the seeder) and no leg names it (tests/e2e/shards.test.mjs holds that). The record is the
// run directory's, named in `stack.json` as `upgradeRecord`.
import { expect } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { test } from '../../src/cspViolations'
import { recordTyped } from '../../src/canaries'
import { newDevice, passwordField, type Device } from '../../src/devices'
import { stack } from '../../src/stack'

/** What this spec reads of the upgrade record (version 1): the person's sign-in, and the names to find. */
interface Record {
  owner: { email: string; password: string; secretKey: string }
  memory: { name: string; place: string; chapter: { name: string } }
  files: { name: string; files: { name: string }[] }
}

let device: Device
let record: Record

test.beforeAll(async ({ browser }) => {
  const path = stack().upgradeRecord
  if (!path) throw new Error('stack.json names no upgradeRecord: this spec runs only in lanes L2 and L3 (scripts/run_stack_upgrade.py, scripts/run_stack_restore.py)')
  record = JSON.parse(readFileSync(path, 'utf8')) as Record
  device = await newDevice(browser)
})

test.afterEach(() => device.confinement.assertConfined())

test.afterAll(async () => {
  await device?.context.close()
})

test(
  'after an upgrade the seeded owner signs in on a new device and opens the seeded Memory and Depot files',
  {
    tag: [
      '@uc.admin.install-and-upgrade-instance:an-upgrade-in-place-keeps-every-item',
      '@uc.admin.back-up-and-restore-instance:a-predecessors-backup-upgrades-with-its-content-intact',
    ],
  },
  async () => {
    const { page } = device
    const origin = `https://${stack().domain}`
    await page.goto(origin)
    await page.getByLabel('Email', { exact: true }).fill(record.owner.email)
    await passwordField(page).fill(recordTyped(record.owner.password))
    await page.locator('#unlock-secret-key').fill(recordTyped(record.owner.secretKey))
    await page.getByRole('button', { name: 'Sign in', exact: true }).click()
    await expect(page.getByRole('button', { name: 'New', exact: true })).toBeVisible({ timeout: 60_000 })

    // The personal space (`#~me`, ADR 0015), where the seeder wrote everything.
    await page.goto('about:blank')
    await page.goto(`${origin}/#~me`)
    await page.getByRole('button', { name: record.memory.name, exact: true }).click({ timeout: 60_000 })
    await expect(page.getByRole('heading', { level: 1, name: record.memory.name })).toBeVisible({ timeout: 60_000 })
    // Its sealed place, and its chapter on the Chapters tab.
    await expect(page.getByRole('button', { name: 'Edit location' })).toHaveText(record.memory.place, { timeout: 60_000 })
    await page.getByRole('tab', { name: /^Chapters\b/ }).click()
    await expect(page.getByText(record.memory.chapter.name, { exact: true }).first()).toBeVisible({ timeout: 60_000 })

    await page.goto('about:blank')
    await page.goto(`${origin}/#~me`)
    await page.getByRole('button', { name: record.files.name, exact: true }).click({ timeout: 60_000 })
    await expect(page.getByRole('heading', { name: record.files.name, exact: true })).toBeVisible({ timeout: 60_000 })
    for (const file of record.files.files) {
      await expect(page.getByRole('button', { name: `Open ${file.name}`, exact: true })).toBeVisible({ timeout: 60_000 })
    }
  },
)
