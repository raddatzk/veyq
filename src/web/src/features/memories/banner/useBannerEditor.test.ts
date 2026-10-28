// uc.memory.banner - what the dialog's save does: a picked file is prepared on the device - or, where the gallery already
// stores that very file, the banner shares the stored tiers (ADR 0013 § 5): an image banner the medium's PREVIEW, a video
// banner its ORIGINAL and its PREVIEW as the poster. A re-frame keeps the bytes, a removal frees them - each through the
// source's banner door, and the tree learns the new meta.
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../media', async (orig) => ({ ...(await orig<object>()), originalBlobMeta: vi.fn() }))

import { originalBlobMeta, type MediaItem } from '../media'
import { contentDigest, sealedByteSize } from '../../../core/blob/sharedBlob'
import { MetaConflictError } from '../../../core/node/sealedMetaWrite'
import type { MemorySource } from '../source'
import type { TreeNode } from '../../../core/node/tree'
import type { MediaDeriver } from '../../../shared/image/mediaDeriver'
import { CENTRE } from './bannerModel'
import { useBannerEditor } from './useBannerEditor'

const KEY = new Uint8Array(32).fill(7)
const node = { id: 'n1', wrappedMeta: 'w', metaRevision: 4 } as unknown as TreeNode
const framing = { frame: 'fill' as const, focal: { x: 0.25, y: 0.5 } }
const ref = (blobId: string) => ({ blobId, chunkCount: 1, byteSize: 9, wrappedBlobKey: 'k' })
const FILE_BYTES = new Uint8Array([1, 2, 3, 4])
const file = (name: string) => new File([FILE_BYTES], name)

/** A gallery medium whose original is `size` sealed bytes, with a PREVIEW unless `preview` is false. */
function medium(size: number, preview = true): MediaItem {
  const r = (blobId: string, role: string) => ({ ...ref(blobId), byteSize: size, ownerId: 'm1', role })
  return {
    mediaId: 'm1',
    original: r('orig', 'ORIGINAL'),
    derivatives: preview ? [{ kind: 'PREVIEW', ref: r('prev', 'PREVIEW') }] : [],
  } as unknown as MediaItem
}

function setup(opts: { raw?: unknown; items?: MediaItem[] } = {}) {
  const banner = {
    upload: vi.fn(async (_n: string, _k: Uint8Array, prepared: { medium: 'image' | 'video' }) =>
      prepared.medium === 'image'
        ? { medium: 'image' as const, uploadId: 'u', blob: ref('b') }
        : { medium: 'video' as const, uploadId: 'u', posterUploadId: 'pu', blob: ref('v'), poster: ref('p'), type: 'video/mp4' }),
    write: vi.fn(async (_args: Record<string, unknown>) => ({ wrappedMeta: 'w2', metaRevision: 5 })),
    // one attempt at the key it is handed (the pull and rebuild are `useBannerEditor.pull.test.ts`'s)
    under: vi.fn((_n: string, at: { key: Uint8Array; epoch: number }, save: (at: { key: Uint8Array; epoch: number }, reread: null) => Promise<unknown>) => save(at, null)),
  }
  const source = { loaders: {}, banner } as unknown as MemorySource
  const onMetaUpdated = vi.fn()
  const deriver = {
    deriveImage: vi.fn(async () => ({ bytes: new Uint8Array([0xff, 0xd8]), source: null })),
    deriveVideoPoster: vi.fn(async () => new Uint8Array([0xff, 0xd9])),
  } as unknown as MediaDeriver
  const editor = useBannerEditor({
    scopeId: () => 's', node: () => node, memoryKey: () => KEY, source, items: () => opts.items ?? [], raw: () => opts.raw ?? null,
    onMetaUpdated, deriver,
  })
  return { editor, banner, onMetaUpdated, deriver }
}

/** The prepared banner handed to the door's upload. */
const preparedOf = (banner: ReturnType<typeof setup>['banner']) => banner.upload.mock.calls[0][2] as unknown as Record<string, unknown>

let digest: string
beforeEach(async () => {
  vi.clearAllMocks()
  digest = await contentDigest(new Blob([FILE_BYTES]))
  vi.mocked(originalBlobMeta).mockReturnValue({ v: 1, type: 'x', sha256: digest })
})

describe('useBannerEditor', () => {
  it('a picked image equal to a gallery original shares that medium\'s PREVIEW: nothing derived, the digest sealed', async () => {
    const { editor, banner, deriver, onMetaUpdated } = setup({ items: [medium(sealedByteSize(FILE_BYTES.length))] })
    await editor.save({ kind: 'file', file: file('a.jpg'), type: 'image/jpeg', poster: null, framing })
    expect(deriver.deriveImage).not.toHaveBeenCalled()
    expect(preparedOf(banner)).toMatchObject({ medium: 'image', image: { share: { key: KEY, ref: { blobId: 'prev' } } } })
    expect(banner.write).toHaveBeenCalledWith(expect.objectContaining({
      nodeId: 'n1', nodeKey: KEY, current: { wrappedMeta: 'w', metaRevision: 4 },
      banner: expect.objectContaining({ medium: 'image', sha256: digest, frame: 'fill', focal: { x: 0.25, y: 0.5 } }),
    }))
    expect(onMetaUpdated).toHaveBeenCalledWith({ nodeId: 'n1', wrappedMeta: 'w2', metaRevision: 5 })
  })

  it('a picked image of the same size and another digest is derived and uploaded', async () => {
    vi.mocked(originalBlobMeta).mockReturnValue({ v: 1, type: 'x', sha256: 'A'.repeat(43) })
    const { editor, banner, deriver } = setup({ items: [medium(sealedByteSize(FILE_BYTES.length))] })
    await editor.save({ kind: 'file', file: file('a.jpg'), type: 'image/jpeg', poster: null, framing })
    expect(deriver.deriveImage).toHaveBeenCalledOnce()
    expect((preparedOf(banner).image as Record<string, unknown>).upload).toBeInstanceOf(Blob)
  })

  it('a medium uploaded before the digest (none in its blob-meta) is never matched', async () => {
    vi.mocked(originalBlobMeta).mockReturnValue({ v: 1, type: 'x' })
    const { editor, banner } = setup({ items: [medium(sealedByteSize(FILE_BYTES.length))] })
    await editor.save({ kind: 'file', file: file('a.jpg'), type: 'image/jpeg', poster: null, framing })
    expect(preparedOf(banner).image).toHaveProperty('upload')
  })

  it('a picked video equal to a gallery original shares its ORIGINAL and, as the poster, its PREVIEW', async () => {
    const { editor, banner, deriver } = setup({ items: [medium(sealedByteSize(FILE_BYTES.length))] })
    await editor.save({ kind: 'file', file: file('clip.mov'), type: 'video/quicktime', poster: null, framing })
    expect(deriver.deriveVideoPoster).not.toHaveBeenCalled()
    expect(preparedOf(banner)).toMatchObject({
      medium: 'video', type: 'video/quicktime', sha256: digest,
      video: { share: { ref: { blobId: 'orig' } } }, poster: { share: { ref: { blobId: 'prev' } } },
    })
  })

  it('a matched video without a PREVIEW shares its ORIGINAL and uploads the poster the dialog decoded', async () => {
    const { editor, banner } = setup({ items: [medium(sealedByteSize(FILE_BYTES.length), false)] })
    await editor.save({ kind: 'file', file: file('clip.mov'), type: 'video/quicktime', poster: new Uint8Array([0xff]), framing })
    const prepared = preparedOf(banner)
    expect(prepared.video).toHaveProperty('share')
    expect(prepared.poster).toHaveProperty('upload')
  })

  it('an unmatched video uploads the file as it is with its digest; a re-frame keeps the bytes; a removal frees them', async () => {
    const { editor, banner, deriver } = setup({ raw: { medium: 'image', blob: ref('b'), frame: 'fill', focal: CENTRE, alt: 'old' } })
    const f = file('clip.mov')
    await editor.save({ kind: 'file', file: f, type: 'video/quicktime', poster: new Uint8Array([0xff]), framing })
    expect(deriver.deriveVideoPoster).not.toHaveBeenCalled()
    expect(preparedOf(banner)).toMatchObject({ medium: 'video', video: { upload: f }, type: 'video/quicktime', sha256: digest })
    expect(banner.write.mock.lastCall![0].upload).toEqual({ uploadId: 'u', posterUploadId: 'pu', shared: undefined, posterShared: undefined })

    await editor.save({ kind: 'keep', framing: { frame: 'fit', focal: { x: 0, y: 1 } } })
    expect(banner.write).toHaveBeenLastCalledWith(expect.objectContaining({
      banner: { medium: 'image', blob: ref('b'), frame: 'fit', focal: { x: 0, y: 1 }, alt: 'old' },
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
    const without = useBannerEditor({
      scopeId: () => 's', node: () => node, memoryKey: () => KEY, source: { loaders: {} } as unknown as MemorySource,
      items: () => [], raw: () => null, onMetaUpdated: vi.fn(),
    })
    await expect(without.save({ kind: 'remove' })).rejects.toThrow('banner_not_writable')
  })
})
