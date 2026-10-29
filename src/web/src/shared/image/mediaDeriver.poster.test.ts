// @vitest-environment happy-dom
// en.memory.derivatives (web, inbox #786) — the video poster's size is the caller's: the upload's poster, stored as both
// tiers, is drawn at the PREVIEW tier's 2048 px when nothing is named, and an upload tile asks for the THUMBNAIL's 512 px.
// The real frame decode is a browser's (no <video> decodes here); the <video>'s events are dispatched by hand and the
// encode is the pixel stage's, observed at its entry.
import { afterEach, describe, expect, it, vi } from 'vitest'

const drawEncoded = vi.hoisted(() => vi.fn(async () => new Uint8Array([0xff, 0xd8])))
vi.mock('./mediaPixels', async (orig) => ({ ...(await orig<typeof import('./mediaPixels')>()), drawEncoded }))

import { createBrowserDeriver, TIER_SPECS } from './mediaDeriver'

afterEach(() => {
  vi.restoreAllMocks()
  drawEncoded.mockClear()
})

/** Runs `deriveVideoPoster` against a <video> whose metadata and seek are announced by hand; returns the encode's size. */
async function posterEdge(opts?: { maxEdge: number; quality: number }): Promise<[number, number]> {
  const create = document.createElement.bind(document)
  let video: HTMLVideoElement | undefined
  vi.spyOn(document, 'createElement').mockImplementation(((tag: string) => {
    const el = create(tag)
    if (tag === 'video') {
      video = el as HTMLVideoElement
      Object.defineProperty(video, 'videoWidth', { value: 1920 })
      Object.defineProperty(video, 'videoHeight', { value: 1080 })
      Object.defineProperty(video, 'duration', { value: 6 })
      video.play = () => Promise.resolve()
    }
    return el
  }) as typeof document.createElement)
  const done = createBrowserDeriver().deriveVideoPoster(new Blob([new Uint8Array([0])], { type: 'video/mp4' }), opts)
  await vi.waitFor(() => expect(video).toBeDefined())
  video!.dispatchEvent(new Event('loadedmetadata'))
  await vi.waitFor(() => expect(video!.currentTime).toBeGreaterThan(0))
  video!.dispatchEvent(new Event('seeked'))
  await done
  const [, , , , maxEdge, quality] = drawEncoded.mock.calls[0] as unknown as [unknown, unknown, number, number, number, number]
  return [maxEdge, quality]
}

describe("deriveVideoPoster — drawn at the size its caller names (inbox #786)", () => {
  it("the upload's poster, nothing named, is the PREVIEW tier's", async () => {
    expect(await posterEdge()).toEqual([TIER_SPECS.PREVIEW.maxEdge, TIER_SPECS.PREVIEW.quality])
  })

  it("an upload tile's, named, is the THUMBNAIL tier's", async () => {
    expect(await posterEdge(TIER_SPECS.THUMBNAIL)).toEqual([512, TIER_SPECS.THUMBNAIL.quality])
  })
})
