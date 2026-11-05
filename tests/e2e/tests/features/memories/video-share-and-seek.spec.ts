// The share viewer's video path, and a seek the demuxer must fetch for itself (inbox #104's two Chromium items, the
// rest of ADR 0047's browser proof next to `video-playback.spec.ts`). The tenant owner uploads a long video into a Memory
// and shares the Memory by a read-only link; a guest opens the link in a browser context of its own — no account, no
// session — opens the video in the lightbox and presses play. The `<video>` must play from the synthetic
// `/__media/<handle>` URL of the media Service Worker the share page registered (`/sw.js`, the same worker a member's
// page uses), every answer that worker's `206` with the original's exact plaintext size in `Content-Range`, and the
// chunks must come through the link's gated presign (`resolveShareBlob`, `GET /api/shares/{shareId}/blobs/{blobId}`) —
// the share-scoped chunk provider behind the same worker (`sw-share-viewer`). Then a seek far past what Chromium has
// read: the element asks the worker for a NEW range starting near the seek point, gets the original's bytes there, and
// plays on past it.
//
// Why this fixture is long. `video-playback.spec.ts`'s 6 s file is read whole from Chromium's first `Range: bytes=0-`, so
// its seek is served from the element's buffer. Chromium's media loader (MultiBufferDataSource) reads ahead of playback
// only so far — about max(2 MiB, 10 s of the stream's bitrate) plus 1 MiB of high water, then it stops pulling the open
// answer — and a seek beyond that, with no reader within a few 32 KiB blocks of the target, opens a new request there.
// Measured locally on 2026-10-23 with Google Chrome 156.0.8078.47 (the build line of this suite's Playwright 1.64.1
// Chromium, 156.0.8078.4), playing through a stand-in Service Worker that streams 256 KiB chunks: half a second into
// playback the element held ~3.2–3.5 MB; a 30 s / 2.9 MB file was buffered whole, a 40 s / 3.9 MB one to 35.6 s, and
// this 48 s / 4.7 MB one to 24–35 s, its seek to 45.5 s asking `bytes=4390912-` (the 32 KiB block under the 45 s
// keyframe). So 4.7 MB is about the smallest file that leaves the seek target a megabyte past the read-ahead whatever
// the stack's speed (a slower stack reads less, never more). Seeking at `loadedmetadata` on a smaller file was weighed
// and rejected: whether the first answer has already streamed past the target is then a race against the stack's
// throughput, and a fast stack reads a few MB in a few hundred ms. The test asserts the precondition instead of
// trusting it: at the seek, the element's buffered ranges must not cover the target.
//
// The fixture is a generated test pattern (`tests/e2e/fixtures/testsrc2-48s-vp8.webm`, 48 s, 320×240, VP8, 4 703 451
// bytes, regenerated bit-identical on 2026-10-23), made with
//   ffmpeg -f lavfi -i "testsrc2=size=320x240:rate=25:duration=48" -c:v libvpx -b:v 800k -minrate 800k -maxrate 800k \
//     -g 25 -keyint_min 25 -auto-alt-ref 0 -an -cues_to_front 1 -map_metadata -1 -fflags +bitexact -flags:v +bitexact out.webm
// VP8 in WebM for Playwright's Chromium, which has no H.264; a keyframe every second so a seek lands where it is asked
// to; a constant bitrate so a second's bytes sit where its share of the file says (the bounds below); 800 kbit/s keeps
// ten seconds of it under the 2 MiB floor, so the read-ahead does not grow with the bitrate; and the index (Cues) at the
// front, read with the first answer, so the seek's new request is for the cluster — FFmpeg's demuxer reads a trailing
// index lazily, at the first seek, which would put a request for the file's tail beside it.
//
// What it leaves out: WebKit and Gecko (inbox #104 keeps them for the browser passes after the V1 queue), and the member
// path's seek — the worker, the range layer and the demuxer are the ones a member's page uses; only the chunk provider
// differs, and this one is the guest's.
//
// It starts from the tenant whose owner the round trip activated (the `memories` project) and switches nothing back
// or forth. Every value typed is a canary (recordTyped). Labels are src/web/'s own (src/web/src/i18n/index.ts and
// src/web/src/features/memories/i18n.ts, en).
import { expect, type Page, type Response } from '@playwright/test'
import { test } from '../../../src/cspViolations'
import { createHash, randomBytes } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { recordTyped } from '../../../src/canaries'
import { newDevice, signInOwner, type Device } from '../../../src/devices'
import { headerAction } from '../../../src/header'

test.describe.configure({ mode: 'serial' })

const VIDEO = readFileSync(fileURLToPath(new URL('../../../fixtures/testsrc2-48s-vp8.webm', import.meta.url)))
const DURATION = 48
// Between keyframes, so the element decodes from the one before it; 2.5 s from the end, room to play on past it.
const SEEK_TO = 45.5
/** Where second [t] of the constant-bitrate stream sits in the file, near enough for a bound. */
const byteAt = (t: number) => Math.floor((VIDEO.length * t) / DURATION)

const stamp = randomBytes(3).toString('hex')
const memoryName = `Segelregatta auf dem Bodensee ${stamp}`
const videoName = `Start der Drachenklasse vor Lindau ${stamp}.webm`
const shareLabel = `Für die Crew der Möwe ${stamp}`

interface MediaResponse {
  status: number
  range: string | undefined
  /** Read after the event (the full header set is async); undefined until then. */
  contentRange: string | undefined
  fromServiceWorker: boolean
}

let owner: Device
let guest: Device
let shareLink: string
/** The guest's `/__media/` answers, in the order the browser reported them. */
const media: MediaResponse[] = []
/** The guest's answers of the link's gated presign, `resolveShareBlob`, in order. */
const shareBlobAnswers: number[] = []

// The entry is pushed when the event fires, so the list's order is the browser's; only the header set is filled later.
const onGuestResponse = (response: Response) => {
  const path = new URL(response.url()).pathname
  if (/^\/api\/shares\/[^/]+\/blobs\/[^/]+$/.test(path)) shareBlobAnswers.push(response.status())
  if (!path.startsWith('/__media/')) return
  const entry: MediaResponse = {
    status: response.status(),
    range: response.request().headers()['range'],
    contentRange: undefined,
    fromServiceWorker: response.fromServiceWorker(),
  }
  media.push(entry)
  void response.allHeaders().then((headers) => {
    entry.contentRange = headers['content-range']
  })
}

/** The start of an open-ended or closed `Range: bytes=N-…` the element sent, or null for any other form. */
const rangeStart = (r: MediaResponse) => {
  const m = /^bytes=(\d+)-\d*$/.exec(r.range ?? '')
  return m ? Number(m[1]) : null
}

const video = (page: Page) => page.locator('.media-lightbox video')
const state = (page: Page) => video(page).evaluate((v: HTMLVideoElement) => ({
  src: new URL(v.currentSrc, location.href).pathname,
  paused: v.paused,
  currentTime: v.currentTime,
  duration: v.duration,
}))

test.beforeAll(async ({ browser }) => {
  owner = await newDevice(browser)
  guest = await newDevice(browser)
  guest.page.on('response', onGuestResponse)
})

test.afterEach(() => {
  for (const device of [owner, guest]) device?.confinement.assertConfined()
})

test.afterAll(async () => {
  for (const device of [owner, guest]) await device?.context.close()
})

test(
  'the owner uploads a long video into a Memory and shares the Memory by a read-only link',
  { tag: '@uc.memory.gallery:sw-share-viewer' },
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
    await (await chooser).setFiles({ name: recordTyped(videoName), mimeType: 'video/webm', buffer: VIDEO })
    await expect(page.getByText('1 item', { exact: true })).toBeVisible({ timeout: 60_000 })
    await expect(page.getByRole('button', { name: 'Open item 1' })).toBeVisible({ timeout: 60_000 })

    await headerAction(page, 'Share')
    await page.getByRole('dialog', { name: 'Share this Memory' }).getByRole('button', { name: 'New share' }).click()
    const create = page.getByRole('dialog', { name: 'New share' })
    await create.getByLabel('Name').fill(recordTyped(shareLabel))
    await create.getByRole('button', { name: 'Create link' }).click()
    shareLink = await create.getByRole('textbox', { name: 'Public link' }).inputValue()
    expect(new URL(shareLink).hash, 'the link key travels in the fragment').toMatch(/^#k=/)
    await create.getByRole('button', { name: 'Done' }).click()
  },
)

test(
  'a guest plays the shared video in the lightbox, streamed by the Service Worker through the link’s gated presign',
  {
    tag: [
      '@uc.memory.gallery:sw-share-viewer',
      '@uc.memory.gallery:lightbox-video-playback',
    ],
  },
  async () => {
    const { page } = guest
    await page.goto(shareLink)
    // No terms gate: the space's terms are published in a tenant of its own (terms-operator-section.spec.ts).
    await expect(page.getByText('Shared with you · read-only')).toBeVisible({ timeout: 60_000 })
    await expect(page.getByRole('heading', { level: 1, name: memoryName })).toBeVisible({ timeout: 60_000 })
    await page.getByRole('button', { name: 'Open item 1' }).click({ timeout: 60_000 })

    const presignsBeforePlay = shareBlobAnswers.length
    // The first play registers the media Service Worker on the share page (src/web/src/sw/register.ts, lazily) and waits
    // for it to claim the page.
    await page.getByRole('button', { name: 'Play video', exact: true }).click({ timeout: 60_000 })

    await expect.poll(async () => (await state(page)).currentTime, { message: 'playback started', timeout: 60_000 })
      .toBeGreaterThan(0.5)
    const playing = await state(page)
    expect(playing.src, 'the Service Worker path, not the blob-URL fallback').toMatch(/^\/__media\/[^/]+$/)
    expect(playing.duration, 'the demuxer read the whole stream’s length').toBeCloseTo(DURATION, 0)
    expect(await page.evaluate(() => navigator.serviceWorker.controller?.scriptURL), 'the media Service Worker controls the share page')
      .toBe(`${new URL(shareLink).origin}/sw.js`)

    // The original's chunks came through the link: its gated presign answered after the play, for the original (the
    // poster and the thumbnail were presigned before it).
    expect(shareBlobAnswers.slice(presignsBeforePlay), 'the share-scoped presign served the original').toContain(200)
    expect(shareBlobAnswers.filter((s) => s !== 200), 'every gated presign was granted').toEqual([])

    expect(media.length, 'the element asked the Service Worker for the video').toBeGreaterThan(0)
    await expect.poll(() => media.every((r) => r.contentRange !== undefined), { message: 'every answer’s headers read' }).toBe(true)
    for (const r of media) {
      expect(r, 'every media answer is the Service Worker’s 206 for the range asked').toMatchObject({ status: 206, fromServiceWorker: true })
      expect(r.range).toMatch(/^bytes=\d*-\d*$/)
      // The plaintext total is the original's size to the byte: byteSize − 28·chunkCount, mapped back from the ciphertext.
      expect(r.contentRange).toMatch(new RegExp(`^bytes \\d+-\\d+/${VIDEO.length}$`))
    }
  },
)

test(
  'a seek past what Chromium has read makes the demuxer ask the Service Worker for a new range there, and playback goes on',
  { tag: '@uc.memory.gallery:sw-range-streaming' },
  async () => {
    const { page } = guest
    const { src } = await state(page)
    const before = media.length

    // The buffered ranges are read in the same task that seeks, so they are what the element held at the seek.
    const buffered = await video(page).evaluate((v: HTMLVideoElement, to: number) => {
      const ranges = Array.from({ length: v.buffered.length }, (_, i) => [v.buffered.start(i), v.buffered.end(i)])
      v.currentTime = to
      return ranges
    }, SEEK_TO)
    expect(
      buffered.filter(([start, end]) => start <= SEEK_TO && SEEK_TO <= end),
      `the element had not read up to ${SEEK_TO} s — a fixture it reads whole proves nothing here (buffered: ${JSON.stringify(buffered)})`,
    ).toEqual([])

    await expect.poll(async () => (await state(page)).currentTime, { message: 'playback went on past the seek point', timeout: 60_000 })
      .toBeGreaterThan(SEEK_TO + 0.3)

    // The demuxer's own request after the seek: one that starts in the cluster of the keyframe before the target, which
    // the first answer had not reached — never a continuation from where the read-ahead stopped (about 35 s at most).
    const afterSeek = media.slice(before)
    const seekRequest = afterSeek.find((r) => {
      const start = rangeStart(r)
      return start !== null && start >= byteAt(SEEK_TO - 2) && start <= byteAt(SEEK_TO)
    })
    expect(seekRequest, `a range near byte ${byteAt(SEEK_TO)} (asked after the seek: ${JSON.stringify(afterSeek.map((r) => r.range))})`)
      .toBeDefined()
    const start = rangeStart(seekRequest!)!
    await expect.poll(() => seekRequest!.contentRange, { message: 'the seek answer’s headers read' }).toBeDefined()
    expect(seekRequest, 'the seek’s range is the Service Worker’s 206').toMatchObject({ status: 206, fromServiceWorker: true })
    const end = /^bytes=\d+-(\d+)$/.exec(seekRequest!.range!)?.[1]
    const last = end === undefined ? VIDEO.length - 1 : Math.min(Number(end), VIDEO.length - 1)
    expect(seekRequest!.contentRange).toBe(`bytes ${start}-${last}/${VIDEO.length}`)

    // The same range again, through the same worker and playback: the original's exact bytes there (a digest, not
    // the bytes, crosses back — the window is a few hundred KB).
    const answer = await page.evaluate(async ({ src, range }) => {
      const response = await fetch(src, { headers: { Range: range } })
      const bytes = await response.arrayBuffer()
      const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes))
      return {
        status: response.status,
        contentRange: response.headers.get('content-range'),
        length: bytes.byteLength,
        sha256: Array.from(digest, (b) => b.toString(16).padStart(2, '0')).join(''),
      }
    }, { src, range: seekRequest!.range! })
    expect(answer.status).toBe(206)
    expect(answer.contentRange).toBe(`bytes ${start}-${last}/${VIDEO.length}`)
    expect(answer.length, 'the window’s length to the byte').toBe(last - start + 1)
    expect(answer.sha256, 'the window carries the original’s bytes')
      .toBe(createHash('sha256').update(VIDEO.subarray(start, last + 1)).digest('hex'))
  },
)
