// @vitest-environment happy-dom
// en.memory.derivatives (web, inbox #793) — a video's size probe makes no media player where the container's header
// names the size, and lets go of the one it makes where it does not. No <video> loads anything here: its metadata event
// is dispatched by hand, and what is checked is what the probe does with the element.
import { readFileSync } from 'node:fs'
import { fileURLToPath, URL as FileURL } from 'node:url' // happy-dom's URL knows no file: scheme
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createBrowserDeriver } from './mediaDeriver'

type Made = { video: HTMLVideoElement; load: ReturnType<typeof vi.fn> }

/** Every <video> the probe makes, its `load` observed, its intrinsic size set as a browser would once metadata is in. */
function watchVideos(): Made[] {
  const made: Made[] = []
  const create = document.createElement.bind(document)
  vi.spyOn(document, 'createElement').mockImplementation(((tag: string) => {
    const el = create(tag)
    if (tag === 'video') {
      const video = el as HTMLVideoElement
      Object.defineProperty(video, 'videoWidth', { value: 1080 })
      Object.defineProperty(video, 'videoHeight', { value: 1920 })
      const load = vi.fn()
      video.load = load
      made.push({ video, load })
    }
    return el
  }) as typeof document.createElement)
  return made
}

const revoked: string[] = []
function watchUrls() {
  let n = 0
  vi.spyOn(URL, 'createObjectURL').mockImplementation(() => `blob:probe-${++n}`)
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation((u: string) => void revoked.push(u))
}

afterEach(() => {
  vi.restoreAllMocks()
  revoked.length = 0
})

// bytes no header reader knows (an AVI, say): the browser's element is asked
const unknown = new Blob([new Uint8Array(32)], { type: 'video/x-msvideo' })

describe('probeDimensions for a video (inbox #793)', () => {
  it('reads the size from the container header: no <video>, no player', async () => {
    const made = watchVideos()
    const mp4 = new Blob([readFileSync(fileURLToPath(new FileURL('./__fixtures__/portrait-moov-at-end.mov', import.meta.url))) as BlobPart])
    await expect(createBrowserDeriver().probeDimensions!(mp4, 'video/quicktime')).resolves.toEqual({ w: 36, h: 64 })
    expect(made, 'a <video> is a media player of its own').toHaveLength(0)
  })

  it('a header it cannot read: the <video> answers, and lets go of its source once it has', async () => {
    const made = watchVideos()
    watchUrls()
    const size = createBrowserDeriver().probeDimensions!(unknown, 'video/x-msvideo')
    await vi.waitFor(() => expect(made).toHaveLength(1))
    const [{ video, load }] = made
    expect(video.getAttribute('src')).toBe('blob:probe-1')
    video.dispatchEvent(new Event('loadedmetadata'))
    await expect(size).resolves.toEqual({ w: 1080, h: 1920 })
    expect(video.getAttribute('src'), 'the element no longer holds the file').toBeNull()
    expect(load, 'reloaded without a source, so its player is let go now and not when it is collected').toHaveBeenCalledTimes(1)
    expect(revoked).toEqual(['blob:probe-1'])
  })

  it('a video the browser cannot read rejects, its player let go too', async () => {
    const made = watchVideos()
    watchUrls()
    const size = createBrowserDeriver().probeDimensions!(unknown, 'video/x-msvideo')
    await vi.waitFor(() => expect(made).toHaveLength(1))
    made[0].video.dispatchEvent(new Event('error'))
    await expect(size).rejects.toThrow('video_loadedmetadata_failed')
    expect(made[0].video.getAttribute('src')).toBeNull()
    expect(made[0].load).toHaveBeenCalledTimes(1)
    expect(revoked).toEqual(['blob:probe-1'])
  })
})
