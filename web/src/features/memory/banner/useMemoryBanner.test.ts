// @vitest-environment happy-dom
// uc.memory.banner (web) - the banner's read side: which banner the page shows, its decrypted picture keyed on what the meta
// names (not on every meta write), a removed banner's picture dropped at once, a failed read never thrown, and a video's
// chunk source built over the BANNER ref only.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { defineComponent, h, ref } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import { useMemoryBanner } from './useMemoryBanner'
import type { MediaLoaders } from '../source'
import type { TreeNode } from '../../../core/node/tree'

const SCOPE = '5c09e000-0000-4000-8000-0000000000dd'
const NODE = '5c09e000-0000-4000-8000-0000000000d1'
const KEY = v1.randomBytes(32)
const ref4 = (blobId: string) => ({ blobId, chunkCount: 1, byteSize: 9, wrappedBlobKey: 'd3JhcA==' })
const sealed = (fields: object) =>
  b64std(v1.sealField({ purpose: 'node-meta', scopeId: SCOPE, nodeId: NODE }, KEY, new TextEncoder().encode(JSON.stringify({ v: 1, ...fields }))))
const image = (blobId: string) => ({ medium: 'image', blob: ref4(blobId), frame: 'fill', focal: { x: 0.3, y: 0.6 } })
const video = { medium: 'video', blob: ref4('v'), poster: ref4('p'), type: 'video/mp4', frame: 'fit', focal: { x: 0.5, y: 0.5 } }

const coverBlob = vi.fn()
const attachmentChunks = vi.fn()

function harness(wrappedMeta: string | null) {
  const meta = ref(wrappedMeta)
  let api!: ReturnType<typeof useMemoryBanner>
  const wrapper = mount(
    defineComponent({
      setup() {
        api = useMemoryBanner({
          scopeId: () => SCOPE,
          node: () => ({ id: NODE, wrappedMeta: meta.value }) as TreeNode,
          memoryKey: () => KEY,
          loaders: { coverBlob, attachmentChunks } as unknown as MediaLoaders,
        })
        return () => h('div')
      },
    }),
  )
  return { api, meta, wrapper }
}

let minted = 0
beforeEach(() => {
  vi.clearAllMocks()
  minted = 0
  Object.assign(URL, { createObjectURL: vi.fn(() => `blob:${++minted}`), revokeObjectURL: vi.fn() })
  coverBlob.mockResolvedValue(new Uint8Array([0xff, 0xd8]))
  attachmentChunks.mockReturnValue({ chunkCount: 1, getPlaintextChunk: vi.fn() })
})

describe('useMemoryBanner', () => {
  it('no banner: nothing to show, nothing read', async () => {
    const { api } = harness(sealed({ date: { mode: 'auto' } }))
    await flushPromises()
    expect(api.banner.value).toBeNull()
    expect(api.status.value).toBe('none')
    expect(coverBlob).not.toHaveBeenCalled()
  })

  it('an image banner reads its BANNER blob once and shows it; another field of the meta moving reads nothing again', async () => {
    const { api, meta } = harness(sealed({ banner: image('i1') }))
    await flushPromises()
    expect(coverBlob).toHaveBeenCalledWith(expect.objectContaining({ blobId: 'i1', role: 'BANNER', ownerId: NODE }), KEY)
    expect(api.pictureUrl.value).toBe('blob:1')
    expect(api.status.value).toBe('ready')
    meta.value = sealed({ banner: image('i1'), date: { mode: 'single', from: '2025-01-01' } })
    await flushPromises()
    expect(coverBlob, 'a date edit is no banner change').toHaveBeenCalledTimes(1)
  })

  it('a removed banner drops its picture at once, revoking its URL', async () => {
    const { api, meta } = harness(sealed({ banner: image('i1') }))
    await flushPromises()
    meta.value = sealed({})
    await flushPromises()
    expect(api.pictureUrl.value).toBeNull()
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:1')
    expect(api.status.value).toBe('none')
  })

  it('a video shows its poster, and its chunk source is built over the BANNER ref - never the poster, never a medium (bite 9)', async () => {
    const { api } = harness(sealed({ banner: video }))
    await flushPromises()
    expect(coverBlob).toHaveBeenCalledWith(expect.objectContaining({ blobId: 'p', role: 'BANNER_POSTER' }), KEY)
    api.videoChunks()
    expect(attachmentChunks).toHaveBeenCalledWith(expect.objectContaining({ blobId: 'v', role: 'BANNER', ownerId: NODE }), KEY)
  })

  it('a read that fails is "failed", never a throw; a read overtaken by a newer banner is dropped', async () => {
    coverBlob.mockRejectedValueOnce(new Error('offline'))
    const failed = harness(sealed({ banner: image('i1') }))
    await flushPromises()
    expect(failed.api.status.value).toBe('failed')
    expect(failed.api.pictureUrl.value).toBeNull()

    let release!: (b: Uint8Array) => void
    coverBlob.mockReturnValueOnce(new Promise<Uint8Array>((r) => (release = r)))
    const { api, meta } = harness(sealed({ banner: image('slow') }))
    meta.value = sealed({ banner: image('fast') })
    await flushPromises()
    release(new Uint8Array([1]))
    await flushPromises()
    expect(coverBlob).toHaveBeenLastCalledWith(expect.objectContaining({ blobId: 'fast' }), KEY)
    expect(URL.createObjectURL).toHaveBeenCalledTimes(1) // only the newer banner's picture was minted
    expect(api.status.value).toBe('ready')
  })
})
