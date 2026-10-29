// @vitest-environment happy-dom
// uc.memory.upload (web, inbox #640) — the optimistic tiles' list changes only when what places a tile does. Each chunk
// writes its queue item's `progress`; the tile list (the gallery's layout input, and the drop page's) must stay the SAME
// value through it and through a stage change, while a tile that appears, gets its probe's answer or leaves still makes
// a new one. The gallery-level proof (the justified layout counted) is MemoryDetail.optimistic.test.ts; this one holds
// the composable both callers share, with a probe that answers.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

const hoisted = vi.hoisted(() => ({ batches: null as unknown as unknown[] }))
vi.mock('./uploadQueue', async (orig) => {
  const actual = await orig<typeof import('./uploadQueue')>()
  const { reactive, computed } = await import('vue')
  const batches = reactive<unknown[]>([])
  hoisted.batches = batches
  return { ...actual, useUploadQueue: () => ({ batches: computed(() => batches), activeFileCount: computed(() => 0) }) }
})
vi.mock('./media', async (orig) => ({ ...(await orig<typeof import('./media')>()), readCaptureTime: vi.fn(), createBrowserDeriver: vi.fn() }))

import { mount, flushPromises } from '@vue/test-utils'
import { computed, defineComponent, h, watch, type ComputedRef } from 'vue'
import { useOptimisticTiles, type OptimisticTileVM } from './useOptimisticTiles'
import { galleryDisplayAspectRatio } from './mediaGridLayout'
import { captureTimeOf, createBrowserDeriver, readCaptureTime } from './media'

/** Releases the pending dimension probe. It answers a portrait (the fallback is landscape), so its answer is a placement
 *  change. */
const probe = { release: null as null | (() => void) }

const KEY = 'node-1'
type File$ = { id: string; status: string; progress: number; mediaId?: string }

function uploadFile(id: string): File$ & Record<string, unknown> {
  return {
    id, name: `${id}.jpg`, sizeBytes: 3, mediaType: 'image/jpeg', status: 'queued', progress: 0, attempts: 0,
    controller: new AbortController(), file: new File([new Uint8Array([1])], `${id}.jpg`, { type: 'image/jpeg', lastModified: 1 }),
  }
}

/** Mount the composable; count how often its tile list became a new value. */
function harness() {
  const seen: OptimisticTileVM[][] = []
  let tiles!: ComputedRef<OptimisticTileVM[]>
  const C = defineComponent({
    setup() {
      tiles = useOptimisticTiles(computed(() => KEY), computed(() => new Set<string>())).optimisticTiles
      watch(tiles, (v) => seen.push(v), { flush: 'sync' })
      return () => h('div')
    },
  })
  return { wrapper: mount(C), seen, tiles: () => tiles.value }
}

const fileAt = (i: number) => (hoisted.batches[0] as { files: File$[] }).files[i]

beforeEach(() => {
  hoisted.batches.length = 0
  probe.release = null
  vi.mocked(readCaptureTime).mockImplementation(async (_f, _t, lastModified) => captureTimeOf(undefined, lastModified))
  vi.mocked(createBrowserDeriver).mockImplementation(() => ({
    deriveImage: vi.fn(),
    deriveVideoPoster: vi.fn(),
    probeDimensions: () => new Promise((resolve) => (probe.release = () => resolve({ w: 300, h: 400 }))),
  }))
})

describe('useOptimisticTiles — a chunk is not a placement change (inbox #640)', () => {
  it('keeps the same tile list through N progress ticks and a stage change; the probe and a new tile replace it', async () => {
    const { seen, tiles } = harness()
    hoisted.batches.push({ id: 'b0', label: '1', target: { scopeId: 's', nodeId: KEY }, files: [uploadFile('u1')], seq: 0, finishedAt: null, acknowledged: false })
    await flushPromises()
    expect(tiles()).toHaveLength(1)
    const placed = seen.length

    fileAt(0).status = 'preparing'
    fileAt(0).status = 'uploading'
    for (let i = 1; i <= 20; i++) {
      fileAt(0).progress = i / 20
      await flushPromises()
    }
    expect(seen.length - placed, 'a chunk or a stage change made a new tile list').toBe(0)
    // the tile still reads the live stage and progress through its item
    expect(tiles()[0].upload.status).toBe('uploading')
    expect(tiles()[0].upload.progress).toBe(1)

    // the control: the probe's aspect ratio IS a placement change, and so is a tile that appears
    probe.release!()
    await flushPromises()
    expect(seen.length - placed, 'the probe\'s answer reached the layout input').toBe(1)
    expect(tiles()[0].aspectRatio).toBe(galleryDisplayAspectRatio(300, 400))
    ;(hoisted.batches[0] as { files: unknown[] }).files.push(uploadFile('u2'))
    await flushPromises()
    expect(tiles().map((t) => t.fileId)).toEqual(['u1', 'u2'])
  })
})

describe('useOptimisticTiles — a photo is previewed tile-sized, near the viewport, one at a time (inbox #785)', () => {
  /** Every object URL minted and not revoked, with the blob it shows. */
  const minted = new Map<string, Blob>()
  /** The derivations asked of the deriver, each settled by the test. */
  const asked: { file: Blob; maxEdge: number; release: () => void; fail: () => void }[] = []
  const deriveImage = vi.fn((file: Blob, { maxEdge }: { maxEdge: number; quality: number }) =>
    new Promise<{ bytes: Uint8Array; source: null }>((resolve, reject) => {
      asked.push({ file, maxEdge, release: () => resolve({ bytes: new Uint8Array([0xff, 0xd8, 1]), source: null }), fail: () => reject(new Error('source')) })
    }))

  beforeEach(() => {
    minted.clear()
    asked.length = 0
    deriveImage.mockClear()
    let n = 0
    vi.spyOn(URL, 'createObjectURL').mockImplementation((b) => {
      const url = `blob:test/${++n}`
      minted.set(url, b as Blob)
      return url
    })
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation((url) => void minted.delete(url))
    vi.mocked(createBrowserDeriver).mockImplementation(() => ({ deriveImage, deriveVideoPoster: vi.fn(), probeDimensions: async () => null }))
  })
  afterEach(() => vi.restoreAllMocks())

  /** Three photos picked; the composable mounted. */
  function picked() {
    let api!: ReturnType<typeof useOptimisticTiles>
    const C = defineComponent({
      setup() {
        api = useOptimisticTiles(computed(() => KEY), computed(() => new Set<string>()))
        return () => h('div')
      },
    })
    const files = ['u1', 'u2', 'u3'].map(uploadFile)
    hoisted.batches.push({ id: 'b0', label: '3', target: { scopeId: 's', nodeId: KEY }, files, seq: 0, finishedAt: null, acknowledged: false })
    const wrapper = mount(C)
    const url = (id: string) => api.optimisticTiles.value.find((t) => t.fileId === id)!.url
    return { wrapper, want: (ids: string[]) => api.wantPreviews('gallery', ids), url }
  }

  it('shows nothing of a photo before its tile is near the viewport, then a tile-sized JPEG the deriver drew - never the file', async () => {
    const { want, url } = picked()
    await flushPromises()
    expect(['u1', 'u2', 'u3'].map(url), 'no tile announced: no preview').toEqual([null, null, null])
    expect(deriveImage).not.toHaveBeenCalled()
    expect([...minted.values()].filter((b) => b instanceof File), 'no picked file is shown whole').toEqual([])

    want(['upload:u2', 'a-listed-media-id'])
    await flushPromises()
    expect(asked.map((a) => [(a.file as File).name, a.maxEdge])).toEqual([['u2.jpg', 512]])
    asked[0].release()
    await flushPromises()
    const shown = minted.get(url('u2')!)!
    expect(shown.type).toBe('image/jpeg')
    expect(new Uint8Array(await shown.arrayBuffer())).toEqual(new Uint8Array([0xff, 0xd8, 1]))
    expect([url('u1'), url('u3')]).toEqual([null, null])
  })

  it('draws one preview at a time, in the order the grid wants them, and none it no longer wants', async () => {
    const { want } = picked()
    await flushPromises()
    want(['upload:u3', 'upload:u1', 'upload:u2'])
    await flushPromises()
    expect(asked.map((a) => (a.file as File).name), 'one decode at a time').toEqual(['u3.jpg'])
    want(['upload:u1']) // scrolled: u2 left the window
    await flushPromises()
    expect(asked, 'a new window starts nothing while one is drawn').toHaveLength(1)
    asked[0].release()
    await flushPromises()
    expect(asked.map((a) => (a.file as File).name)).toEqual(['u3.jpg', 'u1.jpg'])
    asked[1].release()
    await flushPromises()
    expect(asked, 'u2 is no longer wanted').toHaveLength(2)
  })

  it('a photo the deriver cannot read is shown as its file, as before', async () => {
    const { want, url } = picked()
    await flushPromises()
    want(['upload:u1'])
    await flushPromises()
    asked[0].fail()
    await flushPromises()
    const shown = minted.get(url('u1')!)
    expect(shown instanceof File && shown.name).toBe('u1.jpg')
  })

  it('a preview drawn after its view left is revoked, never kept; the ones it showed are revoked on unmount', async () => {
    const { want, wrapper, url } = picked()
    await flushPromises()
    want(['upload:u1', 'upload:u2'])
    await flushPromises()
    asked[0].release()
    await flushPromises()
    expect(minted.has(url('u1')!)).toBe(true)
    wrapper.unmount()
    asked[1].release()
    await flushPromises()
    expect(minted.size, 'nothing minted is left unrevoked').toBe(0)
  })
})

describe("useOptimisticTiles — a video's poster joins the photos' queue: near the viewport, one at a time, tile-sized (inbox #786)", () => {
  /** Every object URL minted and not revoked, with the blob it shows. */
  const minted = new Map<string, Blob>()
  /** What was asked of the deriver, in order — photos and videos in one list — each settled by the test. */
  const asked: { kind: 'image' | 'poster'; name: string; maxEdge: number | undefined; release: () => void; fail: () => void }[] = []
  const POSTER = new Uint8Array([0xff, 0xd8, 7])
  const deriveImage = vi.fn((file: Blob, { maxEdge }: { maxEdge: number; quality: number }) =>
    new Promise<{ bytes: Uint8Array; source: null }>((resolve, reject) => {
      asked.push({ kind: 'image', name: (file as File).name, maxEdge, release: () => resolve({ bytes: new Uint8Array([0xff, 0xd8, 1]), source: null }), fail: () => reject(new Error('source')) })
    }))
  const deriveVideoPoster = vi.fn((file: Blob, opts?: { maxEdge: number; quality: number }) =>
    new Promise<Uint8Array>((resolve, reject) => {
      asked.push({ kind: 'poster', name: (file as File).name, maxEdge: opts?.maxEdge, release: () => resolve(POSTER), fail: () => reject(new Error('video_frame_timeout')) })
    }))
  const probeDimensions = vi.fn(async () => ({ w: 1920, h: 1080 }))

  beforeEach(() => {
    minted.clear()
    asked.length = 0
    deriveImage.mockClear()
    deriveVideoPoster.mockClear()
    probeDimensions.mockClear()
    let n = 0
    vi.spyOn(URL, 'createObjectURL').mockImplementation((b) => {
      const url = `blob:test/${++n}`
      minted.set(url, b as Blob)
      return url
    })
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation((url) => void minted.delete(url))
    vi.mocked(createBrowserDeriver).mockImplementation(() => ({ deriveImage, deriveVideoPoster, probeDimensions }))
  })
  afterEach(() => vi.restoreAllMocks())

  function videoFile(id: string) {
    return {
      ...uploadFile(id), name: `${id}.mp4`, mediaType: 'video/mp4',
      file: new File([new Uint8Array([0, 0, 0, 0x18])], `${id}.mp4`, { type: 'video/mp4', lastModified: 1 }),
    }
  }

  /** Three videos and one photo picked; the composable mounted. */
  function picked() {
    let api!: ReturnType<typeof useOptimisticTiles>
    const C = defineComponent({
      setup() {
        api = useOptimisticTiles(computed(() => KEY), computed(() => new Set<string>()))
        return () => h('div')
      },
    })
    const files = [videoFile('v1'), videoFile('v2'), videoFile('v3'), uploadFile('p1')]
    hoisted.batches.push({ id: 'b0', label: '4', target: { scopeId: 's', nodeId: KEY }, files, seq: 0, finishedAt: null, acknowledged: false })
    const wrapper = mount(C)
    const tile = (id: string) => api.optimisticTiles.value.find((t) => t.fileId === id)
    return { wrapper, want: (ids: string[]) => api.wantPreviews('gallery', ids), url: (id: string) => tile(id)!.url, tile }
  }

  it('a pick decodes no video before its tile is near the viewport, then its first frame at the thumbnail\'s size', async () => {
    const { want, url, tile } = picked()
    await flushPromises()
    expect(deriveVideoPoster, 'no tile announced: no decoder opened').not.toHaveBeenCalled()
    // the probe's own work is unchanged: every picked video's size is read at once, for its tile's place
    expect(probeDimensions).toHaveBeenCalledTimes(4)
    expect(tile('v1')!.aspectRatio).toBe(galleryDisplayAspectRatio(1920, 1080))
    expect(['v1', 'v2', 'v3'].map(url)).toEqual([null, null, null])

    want(['upload:v2'])
    await flushPromises()
    expect(asked.map((a) => [a.kind, a.name, a.maxEdge])).toEqual([['poster', 'v2.mp4', 512]])
    asked[0].release()
    await flushPromises()
    const shown = minted.get(url('v2')!)!
    expect(shown.type).toBe('image/jpeg')
    expect(new Uint8Array(await shown.arrayBuffer())).toEqual(POSTER)
    expect([url('v1'), url('v3')]).toEqual([null, null])
  })

  it('photos and videos are drawn one at a time, in the order the grid wants them', async () => {
    const { want } = picked()
    await flushPromises()
    want(['upload:v3', 'upload:p1', 'upload:v1'])
    await flushPromises()
    expect(asked.map((a) => a.name), 'one decode at a time, a photo waits for the video before it').toEqual(['v3.mp4'])
    want(['upload:p1', 'upload:v1', 'upload:v3']) // the grid scrolls and announces again while v3's poster is drawn
    await flushPromises()
    expect(asked.map((a) => a.name), 'a new window opens no second decoder').toEqual(['v3.mp4'])
    want(['upload:v3', 'upload:p1', 'upload:v1'])
    await flushPromises()
    asked[0].release()
    await flushPromises()
    expect(asked.map((a) => [a.kind, a.name])).toEqual([['poster', 'v3.mp4'], ['image', 'p1.jpg']])
    asked[1].release()
    await flushPromises()
    expect(asked.map((a) => a.name)).toEqual(['v3.mp4', 'p1.jpg', 'v1.mp4'])
    asked[2].release()
    await flushPromises()
    expect(asked, 'v2 was never wanted').toHaveLength(3)
  })

  it('a video the browser cannot decode stays icon-only - never the file - and the queue goes on', async () => {
    const { want, url } = picked()
    await flushPromises()
    want(['upload:v1', 'upload:v2'])
    await flushPromises()
    asked[0].fail()
    await flushPromises()
    expect(url('v1'), 'no picture: the tile keeps its icon').toBeNull()
    expect([...minted.values()].filter((b) => b instanceof File), 'no picked file is shown').toEqual([])
    expect(asked.map((a) => a.name), 'the failure released the queue').toEqual(['v1.mp4', 'v2.mp4'])
  })

  it("a poster is freed when its tile leaves: one finished after it left is revoked, one shown is revoked as it goes", async () => {
    const { want, url } = picked()
    await flushPromises()
    want(['upload:v1', 'upload:v2'])
    await flushPromises()
    asked[0].release()
    await flushPromises()
    const shownV1 = url('v1')!
    expect(minted.has(shownV1)).toBe(true)
    // v1 is cancelled while v2's poster is being drawn: both tiles leave the grid
    fileAt(0).status = 'cancelled' // through the queue's reactive item, as the queue writes it
    fileAt(1).status = 'cancelled'
    await flushPromises()
    expect(minted.has(shownV1), 'the shown poster is revoked with its tile').toBe(false)
    asked[1].release()
    await flushPromises()
    expect(minted.size, 'the poster finished after its tile left is not kept').toBe(0)
  })
})
