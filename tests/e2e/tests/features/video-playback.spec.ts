// A video played in the lightbox through the Service Worker's range layer, in a real browser (ADR 0047, inbox #104 —
// the check that was to ride on the e2e orchestrator and never ran). The tenant owner uploads a short video into a
// Memory, reloads, opens it and presses play: the `<video>` must play from the synthetic `/__media/<handle>` URL, whose
// every request the Service Worker answers `206 Partial Content` from the chunks the page decrypted — the demuxer's
// `Range`, mapped to ciphertext chunks and back, with the original's exact plaintext size in `Content-Range` — never
// from the Slice-1 fallback's blob URL. Then a seek to near the end: the element lands there and keeps playing. A file
// this small Chromium reads whole from its first open-ended request, so the seek is served from the element's buffer;
// the windows a seek in a long video would ask for are asked here directly, through the same worker and playback — a
// range across the first chunk boundary and a suffix range, each the original's exact bytes.
//
// The fixture is a generated test pattern, not footage (`tests/e2e/fixtures/testsrc2-6s-vp8.webm`, 6 s, 320×240, VP8, three
// 256 KiB chunks), made with
//   ffmpeg -f lavfi -i "testsrc2=size=320x240:rate=25:duration=6" -c:v libvpx -b:v 900k -minrate 900k -maxrate 900k \
//     -g 25 -keyint_min 25 -auto-alt-ref 0 -an -map_metadata -1 -fflags +bitexact -flags:v +bitexact out.webm
// VP8 in WebM because the suite's browser is Playwright's Chromium, which carries no H.264 decoder; a keyframe every
// second so a seek lands where it is asked to; a constant bitrate so the bytes of the last second sit in the last chunk.
//
// It starts from the tenant whose owner the round trip activated (the `features` project). Every value typed is a canary
// (recordTyped). Labels are src/web/'s own (src/web/src/i18n/index.ts, en).
import { test, expect, type Page, type Response } from '@playwright/test'
import { randomBytes } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { recordTyped } from '../../src/canaries'
import { newDevice, signInOwner, tenantOrigin, type Device } from '../../src/devices'

test.describe.configure({ mode: 'serial' })

const VIDEO = readFileSync(fileURLToPath(new URL('../../fixtures/testsrc2-6s-vp8.webm', import.meta.url)))
// The plaintext chunk the uploader seals and the Service Worker maps a range onto (ADR 0013 §3, src/web/src/sw/range.ts).
const CHUNK = 256 * 1024
const SEEK_TO = 4.5

const stamp = randomBytes(3).toString('hex')
const memoryName = `Drachensteigen auf dem Feldberg ${stamp}`
const videoName = `Der Drachen hebt ab ${stamp}.webm`

interface MediaResponse {
  status: number
  range: string | undefined
  contentRange: string | undefined
  fromServiceWorker: boolean
}

let member: Device
const media: MediaResponse[] = []

const onMedia = async (response: Response) => {
  if (!new URL(response.url()).pathname.startsWith('/__media/')) return
  media.push({
    status: response.status(),
    range: response.request().headers()['range'],
    contentRange: (await response.allHeaders())['content-range'],
    fromServiceWorker: response.fromServiceWorker(),
  })
}

const video = (page: Page) => page.locator('.media-lightbox video')
const state = (page: Page) => video(page).evaluate((v: HTMLVideoElement) => ({
  src: new URL(v.currentSrc, location.href).pathname,
  paused: v.paused,
  currentTime: v.currentTime,
  duration: v.duration,
}))

test.beforeAll(async ({ browser }) => {
  member = await newDevice(browser)
  member.page.on('response', (response) => void onMedia(response))
})

test.afterEach(() => member?.confinement.assertConfined())

test.afterAll(async () => {
  await member?.context.close()
})

test(
  'a video uploaded into a Memory plays in the lightbox after a reload, streamed by the Service Worker, and seeks',
  {
    tag: [
      '@uc.memory.gallery:lightbox-video-playback',
      '@uc.memory.gallery:sw-range-streaming',
    ],
  },
  async () => {
    const { page } = member
    await signInOwner(member)
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

    // A new document: the video's type and poster are what the server's listing carries, not this tab's upload.
    await page.goto(tenantOrigin())
    await page.getByRole('button', { name: memoryName, exact: true }).click({ timeout: 60_000 })
    await page.getByRole('button', { name: 'Open item 1' }).click({ timeout: 60_000 })
    // The first play registers the media Service Worker (src/web/src/sw/register.ts, lazily) and waits for it to claim the page.
    await page.getByRole('button', { name: 'Play video', exact: true }).click({ timeout: 60_000 })

    await expect.poll(async () => (await state(page)).currentTime, { message: 'playback started', timeout: 60_000 })
      .toBeGreaterThan(0.5)
    const playing = await state(page)
    expect(playing.src, 'the Service Worker path, not the blob-URL fallback').toMatch(/^\/__media\/[^/]+$/)
    expect(playing.duration, 'the demuxer read the whole stream’s length').toBeCloseTo(6, 0)
    expect(await page.evaluate(() => navigator.serviceWorker.controller?.scriptURL), 'the media Service Worker controls the page')
      .toBe(`${tenantOrigin()}/sw.js`)

    await video(page).evaluate((v: HTMLVideoElement, to: number) => new Promise<void>((resolve) => {
      v.addEventListener('seeked', () => resolve(), { once: true })
      v.currentTime = to
    }), SEEK_TO)
    await expect.poll(async () => (await state(page)).currentTime, { message: 'playback went on past the seek point' })
      .toBeGreaterThan(SEEK_TO + 0.3)

    // The demuxer's own requests: Chromium opens the stream with `Range: bytes=0-` and, the file being small, reads it
    // whole from that one answer — so the seek above was served from its buffer, and asked the worker for nothing new.
    expect(media.length, 'the element asked the Service Worker for the video').toBeGreaterThan(0)
    for (const r of media) {
      expect(r, 'every media answer is the Service Worker’s 206 for the range asked').toMatchObject({ status: 206, fromServiceWorker: true })
      expect(r.range).toMatch(/^bytes=\d*-\d*$/)
      // The plaintext total is the original's size to the byte: byteSize − 28·chunkCount, mapped back from the ciphertext.
      expect(r.contentRange).toMatch(new RegExp(`^bytes \\d+-\\d+/${VIDEO.length}$`))
    }

    // A window inside the file, through the same Service Worker and the same playback: a closed range across the
    // first chunk boundary, and a suffix range — each answered 206 with exactly the original's bytes there.
    const windows: { range: string; start: number; end: number }[] = [
      { range: `bytes=${CHUNK - 100}-${CHUNK + 199}`, start: CHUNK - 100, end: CHUNK + 199 },
      { range: 'bytes=-1000', start: VIDEO.length - 1000, end: VIDEO.length - 1 },
    ]
    for (const w of windows) {
      const answer = await page.evaluate(async ({ src, range }) => {
        const response = await fetch(src, { headers: { Range: range } })
        return {
          status: response.status,
          contentRange: response.headers.get('content-range'),
          bytes: Array.from(new Uint8Array(await response.arrayBuffer())),
        }
      }, { src: playing.src, range: w.range })
      expect(answer.status, `${w.range} is a 206`).toBe(206)
      expect(answer.contentRange).toBe(`bytes ${w.start}-${w.end}/${VIDEO.length}`)
      expect(Buffer.from(answer.bytes).equals(VIDEO.subarray(w.start, w.end + 1)), `${w.range} carries the original’s bytes`)
        .toBe(true)
    }
  },
)
