// uc.memory.banner - what the dialog's save does: a gallery medium is copied from what its upload sealed (an image from its
// ORIGINAL, derived; a video's original by frame and its upload's poster), a picked file is prepared on the device, a
// re-frame keeps the bytes, a removal frees them - each through the source's banner door, and the tree learns the new meta.
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../media', async (orig) => ({ ...(await orig<object>()), hasDerivatives: vi.fn(), plaintextSize: vi.fn(), mediaTypeOf: vi.fn() }))

import { hasDerivatives, mediaTypeOf, plaintextSize, type MediaItem } from '../media'
import { MetaConflictError } from '../../../core/node/sealedMetaWrite'
import type { MemorySource } from '../source'
import type { TreeNode } from '../../../core/node/tree'
import type { MediaDeriver } from '../../../shared/image/mediaDeriver'
import { CENTRE } from './bannerModel'
import { BANNER_UNREADABLE } from './bannerWrite'
import { useBannerEditor } from './useBannerEditor'

const KEY = new Uint8Array(32).fill(7)
const node = { id: 'n1', wrappedMeta: 'w', metaRevision: 4 } as unknown as TreeNode
const item = { mediaId: 'm1', original: { byteSize: 50, chunkCount: 1 } } as unknown as MediaItem
const framing = { frame: 'fill' as const, focal: { x: 0.25, y: 0.5 }, alt: 'Bay' }
const ref = (blobId: string) => ({ blobId, chunkCount: 1, byteSize: 9, wrappedBlobKey: 'k' })

function setup(raw: unknown = null) {
  const chunks = { chunkCount: 1, byteSize: 50, getPlaintextChunk: vi.fn(async () => new Uint8Array([1, 2, 3, 4, 5])) }
  const loaders = {
    blobMeta: vi.fn(() => ({ v: 1, type: 'x' })),
    chunk: vi.fn(() => chunks),
    preview: vi.fn(async () => new Uint8Array([0xff, 0xd8, 9])),
    original: vi.fn(async () => new Uint8Array([1, 2, 3])),
  }
  const banner = {
    upload: vi.fn(async (_n: string, _k: Uint8Array, prepared: { medium: 'image' | 'video' }) =>
      prepared.medium === 'image'
        ? { medium: 'image' as const, uploadId: 'u', blob: ref('b') }
        : { medium: 'video' as const, uploadId: 'u', posterUploadId: 'pu', blob: ref('v'), poster: ref('p'), type: 'video/mp4' }),
    write: vi.fn(async (_args: Record<string, unknown>) => ({ wrappedMeta: 'w2', metaRevision: 5 })),
  }
  const source = { loaders, banner } as unknown as MemorySource
  const onMetaUpdated = vi.fn()
  const deriver = { deriveImage: vi.fn(async () => ({ bytes: new Uint8Array([0xff, 0xd8]), source: null })), deriveVideoPoster: vi.fn() } as unknown as MediaDeriver
  const editor = useBannerEditor({
    scopeId: () => 's', node: () => node, memoryKey: () => KEY, source, items: () => [item], raw: () => raw, onMetaUpdated, deriver,
  })
  return { editor, loaders, banner, onMetaUpdated, deriver, chunks }
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(hasDerivatives).mockReturnValue(true)
  vi.mocked(plaintextSize).mockReturnValue(5)
})

describe('useBannerEditor', () => {
  it('a gallery video: its original read by frame (never whole), its upload\'s poster, the origin sealed, the tree told', async () => {
    vi.mocked(mediaTypeOf).mockReturnValue('video/mp4')
    const { editor, loaders, banner, onMetaUpdated, chunks } = setup()
    await editor.save({ kind: 'medium', mediaId: 'm1', framing })
    expect(loaders.original, 'a video is never held whole').not.toHaveBeenCalled()
    expect(loaders.preview).toHaveBeenCalledWith(item, KEY)
    const prepared = banner.upload.mock.calls[0][2] as unknown as { medium: string; video: Blob; type: string; poster: Blob }
    expect(prepared).toMatchObject({ medium: 'video', type: 'video/mp4' })
    expect(prepared.video.size).toBe(5)
    expect(new Uint8Array(await prepared.video.slice(0, 5).arrayBuffer())).toEqual(new Uint8Array([1, 2, 3, 4, 5]))
    expect(chunks.getPlaintextChunk).toHaveBeenCalledWith(0)
    expect(banner.write).toHaveBeenCalledWith(expect.objectContaining({
      nodeId: 'n1', nodeKey: KEY, current: { wrappedMeta: 'w', metaRevision: 4 },
      upload: { uploadId: 'u', posterUploadId: 'pu' },
      banner: expect.objectContaining({ medium: 'video', origin: { mediaId: 'm1' }, frame: 'fill', focal: { x: 0.25, y: 0.5 }, alt: 'Bay' }),
    }))
    expect(onMetaUpdated).toHaveBeenCalledWith({ nodeId: 'n1', wrappedMeta: 'w2', metaRevision: 5 })
  })

  it('a gallery image is derived from its ORIGINAL, never from its preview', async () => {
    vi.mocked(mediaTypeOf).mockReturnValue('image/jpeg')
    const { editor, loaders, deriver } = setup()
    await editor.save({ kind: 'medium', mediaId: 'm1', framing })
    expect(loaders.original).toHaveBeenCalledWith(item, KEY)
    expect(loaders.preview).not.toHaveBeenCalled()
    expect(deriver.deriveImage).toHaveBeenCalledOnce()
  })

  it('a gallery video whose upload made no poster is refused: no frame to show before it plays', async () => {
    vi.mocked(mediaTypeOf).mockReturnValue('video/mp4')
    vi.mocked(hasDerivatives).mockReturnValueOnce(false)
    const { editor, banner } = setup()
    await expect(editor.save({ kind: 'medium', mediaId: 'm1', framing })).rejects.toThrow(BANNER_UNREADABLE)
    expect(banner.upload).not.toHaveBeenCalled()
  })

  it('a picked video takes the poster the dialog decoded; a re-frame keeps the bytes; a removal frees them', async () => {
    const { editor, banner, deriver } = setup({ medium: 'image', blob: ref('b'), frame: 'fill', focal: CENTRE, alt: 'old' })
    const file = new File([new Uint8Array(4)], 'clip.mov')
    await editor.save({ kind: 'file', file, type: 'video/quicktime', poster: new Uint8Array([0xff]), framing })
    expect(deriver.deriveVideoPoster).not.toHaveBeenCalled()
    expect(banner.upload.mock.calls[0][2]).toMatchObject({ medium: 'video', video: file, type: 'video/quicktime' })

    await editor.save({ kind: 'keep', framing: { frame: 'fit', focal: { x: 0, y: 1 } } })
    expect(banner.write).toHaveBeenLastCalledWith(expect.objectContaining({
      banner: { medium: 'image', blob: ref('b'), frame: 'fit', focal: { x: 0, y: 1 } },
    }))
    expect(banner.write.mock.lastCall![0]).not.toHaveProperty('upload')

    await editor.save({ kind: 'remove' })
    expect(banner.write).toHaveBeenLastCalledWith(expect.objectContaining({ banner: null }))
  })

  it('a peer\'s banner change patches the tree with what the server holds, and is rethrown for the dialog', async () => {
    const { editor, banner, onMetaUpdated } = setup()
    banner.write.mockRejectedValueOnce(new MetaConflictError({ wrappedMeta: 'peer', metaRevision: 9 }))
    await expect(editor.save({ kind: 'remove' })).rejects.toBeInstanceOf(MetaConflictError)
    expect(onMetaUpdated).toHaveBeenCalledWith({ nodeId: 'n1', wrappedMeta: 'peer', metaRevision: 9 })
  })

  it('a session without the banner door writes nothing', async () => {
    const { editor } = setup()
    const without = useBannerEditor({
      scopeId: () => 's', node: () => node, memoryKey: () => KEY, source: { loaders: {} } as unknown as MemorySource,
      items: () => [], raw: () => null, onMetaUpdated: vi.fn(),
    })
    await expect(without.save({ kind: 'remove' })).rejects.toThrow('banner_not_writable')
    expect(editor).toBeTruthy()
  })
})
