// The heavy stage's browser passes that need the volume instance (passes.config.ts beside it says why). One pass per
// run: `scripts/measure_volume.py pass <name>` writes the spec (/out/pass-spec.json), prepares what the pass needs —
// the seed, a server-side corruption — runs this file, reads the result (/out/pass-result.json) and checks the database
// afterwards. A pass waits on states and asserts; its result says what it saw, failed or not.
//
//   marks-cap    (inbox #500) the owner's mark set for the 'Volume marks' Memory holds the cap, 12,284 marks
//                (`seed-volume.mts marks`); one device in English and one in German each mark the one photo past it.
//                The client refuses before any write: its toast says so in that language, the heart stays unpressed,
//                and no PUT of the mark set leaves the page.
//   trust-reset  (inbox #491) the driver has flipped one byte of the owner's sealed pin-store head in the database, so
//                a fresh device refuses the store ("could not be opened"); Settings offers *Start a list over*, the
//                pass starts the list over, and the device reads the new list. One PUT, replacing the whole set with
//                nothing; a second fresh device then reads the store and is offered nothing to start over. That the
//                store holds no entry afterwards is the driver's read of the database, after this.
//
// Every label is src/web/'s own (src/web/src/i18n/index.ts, src/web/src/features/memories/i18n.ts). Sign-in is by the
// form's own field ids, so a German device signs in the same way. TEMPORARY, with the rest of tests/e2e/measure/.
import { test, expect, type Browser, type Page } from '@playwright/test'
import { readFileSync, writeFileSync } from 'node:fs'
import { toast } from '../src/toast'

type Lang = 'en' | 'de'
type Account = { email: string; password: string; secretKey: string }
type Spec =
  | { pass: 'marks-cap'; domain: string; account: Account; hash: string; pastCap: string; marked: number }
  | { pass: 'trust-reset'; domain: string; account: Account }

const OUT = process.env.VEYQ_MEASURE_OUT ?? '/out'
const spec = JSON.parse(readFileSync(`${OUT}/pass-spec.json`, 'utf8')) as Spec
const origin = `https://${spec.domain}`

const TEXT = {
  new: { en: 'New', de: 'Neu' },
  mark: { en: 'Mark this photo — everyone in this space sees it', de: 'Dieses Foto markieren — alle in diesem Bereich sehen es' },
  tooMany: {
    en: 'You have marked as many photos in this Memory as one list of marks can hold (12,284). Remove a mark to add another.',
    de: 'Du hast in dieser Erinnerung so viele Fotos markiert, wie eine Liste von Markierungen fassen kann (12.284). Entferne eine Markierung, um eine weitere zu setzen.',
  },
} as const

/** What the pass saw, written whether it passed or not. */
const seen: Record<string, unknown> = { pass: spec.pass }
const record = () => writeFileSync(`${OUT}/pass-result.json`, JSON.stringify(seen, null, 2))

/** A new device in `lang`, signed in and unlocked with password and Secret Key at the tenant's root. */
async function device(browser: Browser, a: Account, lang: Lang): Promise<Page> {
  const context = await browser.newContext({ locale: lang === 'de' ? 'de-DE' : 'en-US' })
  const page = await context.newPage()
  await page.goto(`${origin}/`)
  await page.locator('input[autocomplete="username"]').fill(a.email)
  await page.locator('#login-password').fill(a.password)
  await page.locator('#unlock-secret-key').fill(a.secretKey)
  await page.locator('form.veyq-form button[type="submit"]').click()
  await expect(page.getByRole('button', { name: TEXT.new[lang], exact: true })).toBeVisible({ timeout: 5 * 60_000 })
  return page
}

/** Settings, reached the one way the shell offers (the profile menu), on the space's home. */
async function openSettings(page: Page): Promise<void> {
  await page.locator('.profile-button').click()
  await page.getByRole('menuitem', { name: 'Settings' }).click()
  await expect(page.getByRole('navigation', { name: 'Settings' })).toBeVisible({ timeout: 60_000 })
}
const settingsEntry = (page: Page, name: string) =>
  page.getByRole('navigation', { name: 'Settings' }).getByRole('button', { name, exact: true })

async function marksCap(browser: Browser, s: Extract<Spec, { pass: 'marks-cap' }>): Promise<void> {
  for (const lang of ['en', 'de'] as const) {
    const page = await device(browser, s.account, lang)
    const puts: string[] = []
    page.on('request', (r) => {
      if (r.method() === 'PUT' && new URL(r.url()).pathname.endsWith('/item-favorites')) puts.push(new URL(r.url()).pathname)
    })
    // The Memory, opened in the same document (the URL fragment), newest first: the photo past the cap is its first tile.
    await page.evaluate((hash) => { location.hash = hash }, s.hash)
    // The owner's set has loaded once a tile in view shows its heart pressed (every photo but one is marked): before
    // that, a heart does nothing at all (`itemFavoritesStore.ts`), which would read as no refusal.
    await expect(page.locator('.media-tile-mark[aria-pressed="true"]').first()).toBeVisible({ timeout: 5 * 60_000 })
    const heart = page.locator(`[data-media-id="${s.pastCap}"] .media-tile-mark`)
    await expect(heart).toHaveAttribute('aria-pressed', 'false')
    await expect(heart).toHaveAccessibleName(TEXT.mark[lang])
    await heart.click()
    const refusal = toast(page, TEXT.tooMany[lang], lang)
    await expect(refusal, `the refusal, in ${lang}`).toBeVisible({ timeout: 60_000 })
    await expect(heart, 'the heart is put back').toHaveAttribute('aria-pressed', 'false')
    seen[lang] = { toast: await refusal.textContent(), heartPressedAfter: await heart.getAttribute('aria-pressed'), markPuts: [...puts] }
    expect(puts, 'no write of the mark set left the page').toEqual([])
    await page.context().close()
  }
}

type PinCall = { method: string; path: string; status: number; body?: { revision: unknown; replaceAll: unknown; put: number; remove: number } }

async function trustReset(browser: Browser, s: Extract<Spec, { pass: 'trust-reset' }>): Promise<void> {
  const calls: PinCall[] = []
  const watch = (page: Page, who: string) => page.on('response', (r) => {
    const path = new URL(r.url()).pathname
    if (!path.startsWith('/api/account/identity-pins')) return
    const call: PinCall = { method: r.request().method(), path: `${who} ${path}`, status: r.status() }
    if (call.method === 'PUT') {
      const b = JSON.parse(r.request().postData() ?? '{}') as { revision?: unknown; replaceAll?: unknown; put?: unknown[]; remove?: unknown[] }
      call.body = { revision: b.revision, replaceAll: b.replaceAll, put: b.put?.length ?? -1, remove: b.remove?.length ?? -1 }
    }
    calls.push(call)
  })
  seen.calls = calls

  // 1 — a fresh device refuses the store the server now serves, and Settings offers to start it over.
  const page = await device(browser, s.account, 'en')
  watch(page, 'first')
  await openSettings(page)
  await expect(settingsEntry(page, 'Start a list over'), 'the device refuses its pin store').toBeVisible({ timeout: 60_000 })
  await settingsEntry(page, 'Start a list over').click()
  await expect(page.getByRole('heading', { level: 2, name: 'Start a list over' })).toBeVisible()
  await expect(page.getByText('The copy the server returned could not be opened.')).toBeVisible()

  // 2 — started over.
  await page.locator('[data-test="trust-reset-open-pins"]').click()
  const dialog = page.getByRole('dialog', { name: 'Start the keys you trust over?' })
  await dialog.getByRole('checkbox', { name: 'I understand what starting over costs.' }).click()
  await dialog.getByRole('button', { name: 'Start over', exact: true }).click()
  await expect(page.getByText('Started over. This device reads the new, empty list from now on.', { exact: true })).toBeVisible({ timeout: 60_000 })
  await expect(settingsEntry(page, 'Start a list over'), 'the device reads the new list').toHaveCount(0, { timeout: 60_000 })
  const puts = calls.filter((c) => c.method === 'PUT')
  seen.firstDevice = 'started over'
  expect(puts.map((c) => c.status), 'one write').toEqual([200])
  expect(puts[0].body, 'the whole set replaced by nothing').toMatchObject({ replaceAll: true, put: 0, remove: 0 })
  await page.context().close()

  // 3 — another fresh device reads the new store: nothing to start over.
  const second = await device(browser, s.account, 'en')
  watch(second, 'second')
  // The hub probes the stores once, on mount (`refusedDocuments`): the entry's absence means something only after the
  // probe read the head, and an absence can only be watched over a while — three seconds after that read.
  const probed = second.waitForResponse((r) => r.request().method() === 'GET' && new URL(r.url()).pathname === '/api/account/identity-pins', { timeout: 60_000 })
  await openSettings(second)
  expect((await probed).status(), 'the hub read the pin store').toBe(200)
  await expect(settingsEntry(second, 'Appearance and language')).toBeVisible()
  await second.waitForTimeout(3_000)
  await expect(settingsEntry(second, 'Start a list over'), 'a fresh device reads the store').toHaveCount(0)
  seen.secondDevice = 'reads the store'
  expect(calls.filter((c) => c.method === 'PUT').length, 'nothing wrote the store after the reset').toBe(1)
  await second.context().close()
}

test(`pass: ${spec.pass}`, async ({ browser }) => {
  try {
    if (spec.pass === 'marks-cap') await marksCap(browser, spec)
    else await trustReset(browser, spec)
    seen.ok = true
  } catch (e) {
    seen.ok = false
    seen.error = String((e as Error)?.message ?? e).slice(0, 4000)
    throw e
  } finally {
    record()
  }
})
