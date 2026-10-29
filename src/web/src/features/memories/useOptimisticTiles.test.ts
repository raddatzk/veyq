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
