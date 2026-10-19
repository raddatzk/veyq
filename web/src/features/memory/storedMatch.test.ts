// ADR 0013 § 5 - what of a picked file the Memory already stores: a gallery medium whose original equals it (never a
// blocked or trashed one, never one without a digest), the tier a slot shares of it, the cover's seam, and the video banner
// a gallery upload of the same file shares (never an image banner: it holds a derived JPEG).
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('./media', async (orig) => ({ ...(await orig<object>()), listMedia: vi.fn(), originalBlobMeta: vi.fn() }))

import * as v1 from '@veyq/crypto/v1'
import { b64url } from '@veyq/crypto/base64'
import { listMedia, originalBlobMeta, type MediaItem } from './media'
import { bannerOriginalOf, findEqualMedium, findStoredCover, storedTier } from './storedMatch'
import { sealedByteSize } from '../../core/blob/sharedBlob'
import { sealNodeMeta } from '../../core/node/nodeMeta'
import { treeStore } from '../../core/node/treeStore'
import { treeNode } from '../../test/models'
import { MEMORY_KIND } from './kinds'
import { sealedBanner } from './banner/bannerModel'

const SCOPE = '11111111-1111-4111-8111-111111111111'
const NODE = '22222222-2222-4222-8222-222222222222'
const KEY = v1.randomBytes(32)
const BYTES = new Uint8Array([5, 6, 7])
const DIGEST = b64url(v1.sha256Digest(BYTES))
const SIZE = sealedByteSize(BYTES.length)
const ref = (blobId: string, role: string, byteSize = SIZE) => ({ blobId, chunkCount: 1, byteSize, wrappedBlobKey: 'k', ownerId: 'm', role })
const medium = (mediaId: string, over: Partial<MediaItem> = {}): MediaItem =>
  ({ mediaId, original: ref(`${mediaId}-o`, 'ORIGINAL'), derivatives: [{ kind: 'PREVIEW', ref: ref(`${mediaId}-p`, 'PREVIEW', 99) }], ...over }) as MediaItem

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(originalBlobMeta).mockReturnValue({ v: 1, type: 'image/jpeg', sha256: DIGEST })
})

describe('findEqualMedium', () => {
  it('finds the live, unblocked medium of the same size and digest; its tiers are what a slot shares', async () => {
    const items = [medium('blocked', { blocked: true }), medium('trashed', { deletedAt: new Date() }), medium('m1')]
    const hit = await findEqualMedium(SCOPE, KEY, items, new Blob([BYTES]))
    expect(hit?.item.mediaId).toBe('m1')
    expect(hit?.key).toBe(KEY) // a member's medium opens under the node's key
    expect(storedTier(hit!, 'PREVIEW')?.ref.blobId).toBe('m1-p')
    expect(storedTier(hit!, 'ORIGINAL')?.ref.blobId).toBe('m1-o')
    expect(storedTier({ ...hit!, item: medium('x', { derivatives: [] }) }, 'PREVIEW'), 'no tier: no match').toBeNull()
  })

  it('a digest handed in saves the read; another digest or size is no match', async () => {
    expect((await findEqualMedium(SCOPE, KEY, [medium('m1')], new Blob([BYTES]), undefined, DIGEST))?.item.mediaId).toBe('m1')
    expect(await findEqualMedium(SCOPE, KEY, [medium('m1')], new Blob([BYTES]), undefined, 'A'.repeat(43))).toBeNull()
    expect(await findEqualMedium(SCOPE, KEY, [medium('m1', { original: ref('o', 'ORIGINAL', SIZE + 1) })], new Blob([BYTES]))).toBeNull()
  })
})

describe('findStoredCover - the cover\'s seam', () => {
  const ctx = (file: File) => ({ scopeId: SCOPE, nodeId: NODE, nodeKey: KEY, file })

  it('an image equal to a gallery original: that medium\'s PREVIEW', async () => {
    vi.mocked(listMedia).mockResolvedValue([medium('m1')])
    const stored = await findStoredCover(ctx(new File([BYTES], 'a.jpg', { type: 'image/jpeg' })))
    expect(stored?.ref.blobId).toBe('m1-p')
    expect(listMedia).toHaveBeenCalledWith(SCOPE, NODE)
  })

  it('no image, or no equal medium: null (core uploads)', async () => {
    expect(await findStoredCover(ctx(new File([BYTES], 'a.pdf', { type: 'application/pdf' })))).toBeNull()
    expect(listMedia, 'nothing listed for a non-image').not.toHaveBeenCalled()
    vi.mocked(listMedia).mockResolvedValue([medium('m1')])
    expect(await findStoredCover(ctx(new File([new Uint8Array([9, 9, 9])], 'b.jpg', { type: 'image/jpeg' })))).toBeNull()
  })
})

describe('bannerOriginalOf - a gallery upload equal to the video banner', () => {
  const withBanner = (banner: Record<string, unknown>) => {
    const node = treeNode({ id: NODE, kind: MEMORY_KIND, name: 'Rom', parentId: null })
    treeStore.nodes = [{ ...node, wrappedMeta: sealNodeMeta(KEY, { scopeId: SCOPE, nodeId: NODE }, { v: 1, banner }) }]
  }
  const framing = { frame: 'fill' as const, focal: { x: 0.5, y: 0.5 } }
  const blob = { blobId: 'banner-blob', chunkCount: 1, byteSize: SIZE, wrappedBlobKey: 'k' }

  it('names the video banner\'s BANNER blob for the same digest and size, under the node\'s key', () => {
    withBanner(sealedBanner({ medium: 'video', blob, poster: { ...blob, blobId: 'p' }, type: 'video/mp4', sha256: DIGEST, framing }))
    const match = bannerOriginalOf(SCOPE, NODE, KEY)
    expect(match(DIGEST, SIZE)).toEqual({ key: KEY, ref: expect.objectContaining({ blobId: 'banner-blob', ownerId: NODE, role: 'BANNER' }) })
    expect(match(DIGEST, SIZE + 1), 'another size').toBeNull()
    expect(match('A'.repeat(43), SIZE), 'another digest').toBeNull()
  })

  it('an image banner holds a derived JPEG, never the file: no match; nor a node without a banner or a meta', () => {
    withBanner(sealedBanner({ medium: 'image', blob, sha256: DIGEST, framing }))
    expect(bannerOriginalOf(SCOPE, NODE, KEY)(DIGEST, SIZE)).toBeNull()
    treeStore.nodes = []
    expect(bannerOriginalOf(SCOPE, NODE, KEY)(DIGEST, SIZE)).toBeNull()
  })
})
