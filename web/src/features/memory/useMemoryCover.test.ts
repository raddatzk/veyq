// @vitest-environment happy-dom
// uc.memory.cover (web) — the Memory's cover DISPLAY slice. Since inbox #411 the editing (dialog, crop, upload, write, failure
// wording) is core's (`core/node/cover/useNodeCover.test.ts`, `components/CoverDialog.test.ts`); what stays here is how the page
// reads the cover it shows: the stored cover and its image, the uploaded blob opened on demand. A cover is set only by an upload,
// so an older client's photo reference is no cover.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { defineComponent, h, ref } from 'vue'
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

function harness(wrappedMeta: string | null, key: Uint8Array | null = KEY) {
  const meta = ref(wrappedMeta)
  let api!: ReturnType<typeof useMemoryCover>
  mount(
    defineComponent({
      setup() {
        api = useMemoryCover({
          scopeId: () => SCOPE,
          node: () => ({ id: NODE, wrappedMeta: meta.value }) as TreeNode,
          memoryKey: () => key,
          loaders: { coverBlob } as unknown as MediaLoaders,
        })
        return () => h('div')
      },
    }),
  )
  return { api, meta }
}

beforeEach(() => {
  vi.clearAllMocks()
  Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:uploaded'), revokeObjectURL: vi.fn() })
  coverBlob.mockResolvedValue(new Uint8Array([1, 2, 3]))
})

describe('useMemoryCover - the cover the page shows', () => {
  it('no cover: nothing stored, no image', () => {
    const { api } = harness(sealed(null))
    expect(api.storedCover.value).toBeNull()
    expect(api.coverSrc.value).toBeNull()
  })

  it('an older client\'s photo reference is no cover: the placeholder, nothing read', async () => {
    const { api } = harness(sealed({ source: { kind: 'media', mediaId: 'm-7' }, crop }))
    await flushPromises()
    expect(api.storedCover.value).toBeNull()
    expect(api.coverSrc.value).toBeNull()
    expect(coverBlob).not.toHaveBeenCalled()
  })

  it('an uploaded cover opens its own blob on demand', async () => {
    const { api } = harness(sealed({ source: { kind: 'upload', ref: REF }, crop }))
    await flushPromises()
    expect(coverBlob).toHaveBeenCalledWith(expect.objectContaining({ blobId: 'b-1' }), KEY)
    expect(api.storedCover.value?.crop).toEqual(crop)
    expect(api.coverSrc.value).toBe('blob:uploaded')
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

  it('locked keys show no cover', async () => {
    const { api } = harness(sealed({ source: { kind: 'upload', ref: REF }, crop }), null)
    await flushPromises()
    expect(api.storedCover.value).toBeNull()
    expect(api.coverSrc.value).toBeNull()
  })
})
