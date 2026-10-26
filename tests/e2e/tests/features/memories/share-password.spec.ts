// A password-protected link opened by a guest in a real browser, and the Argon2 work it costs done in the Argon2 worker
// (ADR 0037, inbox #268). The tenant owner writes a Memory with a photo and shares it with a link that carries a
// password; an anonymous visitor opening the link sees the password prompt and nothing of the Memory, a wrong password
// is refused, and the right one opens the Memory, its photo decrypted on that device.
//
// Both ends pay an Argon2id derivation — the curator's page derives the verifier it stores, the guest's page the token it
// presents — and both go through `@veyq/crypto/argonWorker`, which runs them in a module worker and falls back to the
// page thread, saying so once on the console (`[veyq] Argon2 worker unavailable — deriving on the page thread`), when the
// worker cannot run. So each page here is watched from its first script: every Worker it constructs, every request it
// posts to the Argon2 worker chunk (`assets/argon.worker-*.js`, the name packages/crypto's `verify:argon-worker` pins in
// the build) and every answer the worker sends back, and the console for that warning. The proof is the round trip
// (the request posted, the worker's own answer, the unlock that used it), not the worker's construction alone. This
// instruments the test's browser, not the product: nothing here is a hook src/web/ carries.
//
// It starts from the tenant whose owner the round trip activated (the `memories` project). Every value typed is a canary
// (recordTyped) — the password above all, which the page never sends (it sends Argon2id of it). Labels are src/web/'s own
// (src/web/src/i18n/index.ts, en).
import { test, expect, type Page } from '@playwright/test'
import { randomBytes } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { recordTyped } from '../../../src/canaries'
import { newDevice, signInOwner, type Device } from '../../../src/devices'
import { headerAction } from '../../../src/header'

test.describe.configure({ mode: 'serial' })

// The round trip's photo (1×1 JPEG with EXIF): something for the guest to decrypt once the password let it in.
const PHOTO = fileURLToPath(new URL('../../../../../src/web/src/features/memories/__fixtures__/exif-sample.jpg', import.meta.url))

const stamp = randomBytes(3).toString('hex')
const memoryName = `Hochzeit im Gartenhaus ${stamp}`
const photoName = `Brautstrauß unter der Linde ${stamp}.jpg`
const shareLabel = `Nur für die Trauzeugen ${stamp}`
// Ten characters at least (the dialog's minimum); a space and an umlaut keep both clear of the ciphertext alphabet.
const password = `Schlüssel zum Gartenhaus ${stamp}`
const wrongPassword = `Schlüssel zum Kellerraum ${stamp}`

const FALLBACK_WARNING = '[veyq] Argon2 worker unavailable'

/** What one page did with the Argon2 worker, read from the page (`window.__veyqArgon`, the init script below). */
interface ArgonLog {
  /** The script URL of every Worker the page constructed. */
  workers: string[]
  /** The `op` of every request posted to the Argon2 worker, in order. */
  posted: string[]
  /** How many answers the Argon2 worker sent back with `ok: true` — its own result, not the page thread's fallback. */
  answered: number
}

interface Watched extends Device {
  warnings: string[]
}

/**
 * Watch every page of [context] from its first script: wrap the `Worker` constructor (a Proxy, so `instanceof Worker`
 * still holds) and, for the Argon2 chunk, its `postMessage` and its answers. Only the request's `op` is kept — never
 * the password it carries.
 */
async function watchArgon(device: Device): Promise<Watched> {
  const warnings: string[] = []
  device.page.on('console', (message) => {
    if (message.text().includes(FALLBACK_WARNING)) warnings.push(message.text())
  })
  await device.context.addInitScript(() => {
    const log = { workers: [] as string[], posted: [] as string[], answered: 0 }
    const w = window as unknown as { __veyqArgon: typeof log; Worker: typeof Worker }
    w.__veyqArgon = log
    w.Worker = new Proxy(Worker, {
      construct(target, args, newTarget) {
        const worker = Reflect.construct(target, args, newTarget) as Worker
        const url = new URL(String(args[0]), location.href)
        log.workers.push(url.href)
        if (/\/argon\.worker-[^/]+\.js$/.test(url.pathname)) {
          const post = worker.postMessage.bind(worker) as (message: unknown, options?: unknown) => void
          worker.postMessage = ((message: { op?: string }, options?: unknown) => {
            log.posted.push(String(message?.op))
            post(message, options)
          }) as Worker['postMessage']
          worker.addEventListener('message', (event: MessageEvent) => {
            if ((event.data as { ok?: boolean })?.ok === true) log.answered++
          })
        }
        return worker
      },
    })
  })
  return { ...device, warnings }
}

const argonLog = (page: Page) => page.evaluate(() => (window as unknown as { __veyqArgon: ArgonLog }).__veyqArgon)

/** The page derived [op] in the Argon2 worker: the chunk was constructed, every request it got was answered by it, and
 *  the page never fell back to its own thread. */
async function expectDerivedInWorker(device: Watched, op: string, times: number): Promise<void> {
  await expect.poll(async () => {
    const log = await argonLog(device.page)
    return { posted: log.posted.filter((o) => o === op).length, answeredAll: log.answered === log.posted.length }
  }, { message: `${op} posted to the Argon2 worker and answered by it` }).toEqual({ posted: times, answeredAll: true })
  const log = await argonLog(device.page)
  expect(log.workers.filter((u) => /\/assets\/argon\.worker-[^/]+\.js$/.test(u)), 'one Argon2 worker, from the build').toHaveLength(1)
  expect(device.warnings, 'the page-thread fallback never ran').toEqual([])
}

let owner: Watched
let guest: Watched
let shareLink: string

/** The thumbnail of the Memory's first item, decrypted in this page: an `<img>` on a `blob:` URL that decoded. */
async function expectDecodedThumbnail(page: Page): Promise<void> {
  const thumbnail = page.getByRole('button', { name: 'Open item 1' }).locator('img.media-tile-img')
  await expect(thumbnail).toBeVisible({ timeout: 60_000 })
  await expect(thumbnail).toHaveAttribute('src', /^blob:/)
  await expect.poll(() => thumbnail.evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth),
    { message: 'the thumbnail decoded' }).toBeGreaterThan(0)
}

test.beforeAll(async ({ browser }) => {
  owner = await watchArgon(await newDevice(browser))
  guest = await watchArgon(await newDevice(browser))
})

test.afterEach(() => {
  for (const device of [owner, guest]) device?.confinement.assertConfined()
})

test.afterAll(async () => {
  for (const device of [owner, guest]) await device?.context.close()
})

test(
  'the owner shares a Memory with a password, deriving its verifier in the Argon2 worker',
  { tag: '@uc.sharing.share-password:create-and-locked-resolve' },
  async () => {
    const { page } = owner
    await signInOwner(owner)
    await page.getByRole('button', { name: 'New', exact: true }).click()
    await page.getByRole('menuitem', { name: 'New Memory' }).click()
    const dialog = page.getByRole('dialog', { name: 'Create a Memory' })
    await dialog.getByLabel('Name').fill(recordTyped(memoryName))
    await dialog.getByRole('button', { name: 'Create', exact: true }).click()
    await expect(dialog).toBeHidden()
    await page.getByRole('button', { name: memoryName, exact: true }).click()
    await expect(page.getByRole('heading', { level: 1, name: memoryName })).toBeVisible()

    const chooser = page.waitForEvent('filechooser')
    await page.getByRole('button', { name: 'Upload', exact: true }).click()
    await (await chooser).setFiles({ name: recordTyped(photoName), mimeType: 'image/jpeg', buffer: readFileSync(PHOTO) })
    await expect(page.getByRole('button', { name: 'Open item 1' })).toBeVisible({ timeout: 60_000 })

    await headerAction(page, 'Share')
    await page.getByRole('dialog', { name: 'Share this Memory' }).getByRole('button', { name: 'New share' }).click()
    const create = page.getByRole('dialog', { name: 'New share' })
    await create.getByLabel('Name').fill(recordTyped(shareLabel))
    await create.getByRole('switch', { name: 'Password protection' }).click()
    await create.locator('#share-create-password').fill(recordTyped(password))
    await create.getByRole('button', { name: 'Create link' }).click()
    shareLink = await create.getByRole('textbox', { name: 'Public link' }).inputValue()
    expect(new URL(shareLink).hash, 'the link key travels in the fragment').toMatch(/^#k=/)
    await create.getByRole('button', { name: 'Done' }).click()

    await expectDerivedInWorker(owner, 'sharesPwToken', 1)
  },
)

test(
  'a guest sees only the password prompt, a wrong password is refused, and the right one opens the Memory',
  {
    tag: [
      '@uc.sharing.share-password:create-and-locked-resolve',
      '@uc.sharing.share-password:unlock-and-gate-endpoints',
    ],
  },
  async () => {
    const { page } = guest
    await page.goto(shareLink)
    await expect(page.getByRole('heading', { name: 'This share is password-protected' })).toBeVisible({ timeout: 60_000 })
    // The locked resolve carries the salt and nothing else: no name, no item.
    await expect(page.getByRole('heading', { name: memoryName })).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Open item 1' })).toHaveCount(0)

    const field = page.locator('#share-password-input')
    await field.fill(recordTyped(wrongPassword))
    await page.getByRole('button', { name: 'Unlock', exact: true }).click()
    await expect(page.getByText('Wrong password — please try again.')).toBeVisible({ timeout: 60_000 })
    await expect(page.getByRole('heading', { name: memoryName })).toHaveCount(0)

    await field.fill(recordTyped(password))
    await page.getByRole('button', { name: 'Unlock', exact: true }).click()
    await expect(page.getByRole('heading', { level: 1, name: memoryName })).toBeVisible({ timeout: 60_000 })
    await expectDecodedThumbnail(page)

    // Both tries derived their token in the worker — the wrong one too: the refusal was the server's, on a real token.
    await expectDerivedInWorker(guest, 'sharesPwToken', 2)
  },
)
