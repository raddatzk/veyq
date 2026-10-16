// @vitest-environment happy-dom
// uc.memory.cover (web) — the Memory's cover DISPLAY slice. Since inbox #411 the editing (dialog, crop, upload, write, failure
// wording) is core's (`core/node/cover/useNodeCover.test.ts`, `components/CoverDialog.test.ts`); what stays here is how the page
// reads the cover it shows: the stored cover, which image that is (a photo's thumbnail, or the uploaded blob opened on demand), which
// photo the lightbox marks, and the photo the gallery hands to core's dialog.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { defineComponent, h, ref, type Ref } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import { useMemoryCover } from './useMemoryCover'
import type { MediaLoaders } from './source'
import type { TreeNode } from '../../core/node/tree'

const SCOPE = '5c09e000-0000-4000-8000-0000000000cc'
const NODE = '5c09e000-0000-4000-8000-0000000000c1'
const KEY = v1.randomBytes(32)
const REF = { blobId: 'b-1', chunkCount: 1, byteSize: 9, wrappedBlobKey: 'd3JhcA==', ownerId: NODE, role: 'COVER' }
const crop = { x: 0.1, y: 0.2, width: 0.5, height: 0.28125 }
const sealed = (cover: object | null) =>
  b64std(v1.sealField({ purpose: 'node-meta', scopeId: SCOPE, nodeId: NODE }, KEY, new TextEncoder().encode(JSON.stringify({ v: 1, ...(cover ? { cover } : {}) }))))

const coverBlob = vi.fn()

function harness(wrappedMeta: string | null, thumbs = new Map<string, string>(), key: Uint8Array | null = KEY) {
  const meta = ref(wrappedMeta)
  const thumbsRef: Ref<Map<string, string>> = ref(thumbs)
  let api!: ReturnType<typeof useMemoryCover>
  mount(
    defineComponent({
      setup() {
        api = useMemoryCover({
          scopeId: () => SCOPE,
          node: () => ({ id: NODE, wrappedMeta: meta.value }) as TreeNode,
          memoryKey: () => key,
          loaders: { coverBlob } as unknown as MediaLoaders,
          thumbs: thumbsRef,
        })
        return () => h('div')
      },
    }),
  )
  return { api, meta, thumbs: thumbsRef }
}

beforeEach(() => {
  vi.clearAllMocks()
  Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:uploaded'), revokeObjectURL: vi.fn() })
  coverBlob.mockResolvedValue(new Uint8Array([1, 2, 3]))
})

describe('useMemoryCover - the cover the page shows', () => {
  it('no cover: nothing stored, no image, no marked photo', () => {
    const { api } = harness(sealed(null))
    expect(api.storedCover.value).toBeNull()
    expect(api.coverSrc.value).toBeNull()
    expect(api.coverMediaId.value).toBeNull()
  })

  it('a photo cover shows that photo\'s already-decrypted thumbnail with its crop, and the lightbox marks it', () => {
    const { api } = harness(sealed({ source: { kind: 'media', mediaId: 'm-7' }, crop }), new Map([['m-7', 'blob:thumb-7']]))
    expect(api.storedCover.value).toEqual({ source: { kind: 'media', mediaId: 'm-7' }, crop })
    expect(api.coverSrc.value).toBe('blob:thumb-7')
    expect(api.coverMediaId.value).toBe('m-7')
    expect(coverBlob, 'a photo needs no blob of its own').not.toHaveBeenCalled()
  })

  it('a photo whose thumbnail is not there yet shows no image (the placeholder), still marked', () => {
    const { api } = harness(sealed({ source: { kind: 'media', mediaId: 'm-7' }, crop }))
    expect(api.coverSrc.value).toBeNull()
    expect(api.coverMediaId.value).toBe('m-7')
  })

  it('an uploaded cover opens its own blob on demand; no photo is marked', async () => {
    const { api } = harness(sealed({ source: { kind: 'upload', ref: REF }, crop }))
    await flushPromises()
    expect(coverBlob).toHaveBeenCalledWith(expect.objectContaining({ blobId: 'b-1' }), KEY)
    expect(api.coverSrc.value).toBe('blob:uploaded')
    expect(api.coverMediaId.value).toBeNull()
  })

  it('an uploaded cover that does not open is the placeholder - best effort, never a throw', async () => {
    coverBlob.mockRejectedValue(new Error('offline'))
    const { api } = harness(sealed({ source: { kind: 'upload', ref: REF }, crop }))
    await flushPromises()
    expect(api.coverSrc.value).toBeNull()
  })

  it('a replaced cover drops the previous object URL', async () => {
    const { api, meta } = harness(sealed({ source: { kind: 'upload', ref: REF }, crop }))
    await flushPromises()
    expect(api.coverSrc.value).toBe('blob:uploaded')
    meta.value = sealed(null)
    await flushPromises()
    expect(api.coverSrc.value).toBeNull()
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:uploaded')
  })

  it('locked keys show no cover', () => {
    const { api } = harness(sealed({ source: { kind: 'media', mediaId: 'm-7' }, crop }), new Map([['m-7', 'blob:thumb-7']]), null)
    expect(api.storedCover.value).toBeNull()
    expect(api.coverSrc.value).toBeNull()
  })
})

describe('useMemoryCover - the photo the gallery hands to core\'s dialog', () => {
  it('is the photo\'s decrypted thumbnail (the exact tier the cover renders from)', () => {
    const { api } = harness(sealed(null), new Map([['m-7', 'blob:thumb-7']]))
    expect(api.coverSourceOf('m-7')).toEqual({ mediaId: 'm-7', src: 'blob:thumb-7' })
  })

  it('a photo with no thumbnail yet has no source to crop (the host sets it full-frame)', () => {
    const { api } = harness(sealed(null))
    expect(api.coverSourceOf('m-9')).toEqual({ mediaId: 'm-9', src: null })
  })
})
