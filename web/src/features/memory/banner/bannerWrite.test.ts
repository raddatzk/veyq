import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'

vi.mock('../../../api/client', async (orig) => ({ ...(await orig<object>()), contentApi: vi.fn() }))
vi.mock('../api', () => ({ memoryBannerApi: vi.fn() }))
// The transport's reserve-and-pump is the platform's (`core/blob/transport.test.ts`): here it hands back one session id per
// plan and records the plans, so what is under test is what the banner adds to it.
vi.mock('../../../core/blob/transport', async (orig) => ({
  ...(await orig<object>()),
  reserveAndPump: vi.fn(async (plans: unknown[]) => plans.map((_p, i) => `up-${i}`)),
}))

import {
  BANNER_IMAGE_SPEC,
  BANNER_TOO_LARGE,
  BANNER_UNREADABLE,
  BannerStaleError,
  MAX_BANNER_IMAGE_BYTES,
  prepareImage,
  prepareVideo,
  uploadBanner,
  writeMemoryBanner,
} from './bannerWrite'
import { CENTRE, sealedBanner } from './bannerModel'
import { unsealNodeMeta } from '../../../core/node/nodeMeta'
import { MetaConflictError } from '../../../core/node/sealedMetaWrite'
import { readBlobMeta, reserveAndPump } from '../../../core/blob/transport'
import { contentApi } from '../../../api/client'
import { memoryBannerApi } from '../api'
import type { ContentApi } from '../../../api/generated/content'
import type { MemoryBannerApi } from '../../../api/generated/memory-banner'
import type { BlobPlan } from '../../../core/blob/types'
import type { MediaDeriver } from '../../../shared/image/mediaDeriver'
import { fakeApi } from '../../../test/apiMock'

const SCOPE = '11111111-1111-4111-8111-111111111111'
const NODE = '22222222-2222-4222-8222-222222222222'
const key = v1.randomBytes(32)
const metaCtx = { purpose: 'node-meta', scopeId: SCOPE, nodeId: NODE } as const

beforeEach(() => vi.clearAllMocks())

const deriver = (fails = false) =>
  ({
    deriveImage: vi.fn(async () => {
      if (fails) throw new Error('undecodable')
      return { bytes: new Uint8Array([0xff, 0xd8, 1]), source: { w: 4000, h: 3000 } }
    }),
    deriveVideoPoster: vi.fn(),
  }) as unknown as MediaDeriver & { deriveImage: Mock }

describe('preparing the bytes', () => {
  it('an image is ONE JPEG derived with the cover\'s spec; an undecodable one is refused, a too large one before any decode', async () => {
    const d = deriver()
    const p = await prepareImage(new Blob([new Uint8Array(10)], { type: 'image/png' }), d)
    expect(d.deriveImage).toHaveBeenCalledWith(expect.anything(), BANNER_IMAGE_SPEC)
    expect(p.medium).toBe('image')
    await expect(prepareImage(new Blob([new Uint8Array(10)]), deriver(true))).rejects.toThrow(BANNER_UNREADABLE)
    const huge = { size: MAX_BANNER_IMAGE_BYTES + 1 } as Blob
    const untouched = deriver()
    await expect(prepareImage(huge, untouched)).rejects.toThrow(BANNER_TOO_LARGE)
    expect(untouched.deriveImage).not.toHaveBeenCalled()
  })

  it('a video is taken as it is with its first frame; a frame that does not decode refuses it (Q5: never a banner nothing draws)', async () => {
    const video = new Blob([new Uint8Array(100)], { type: 'video/quicktime' })
    const p = await prepareVideo(video, 'video/quicktime', async () => new Uint8Array([0xff, 0xd8]))
    expect(p).toMatchObject({ medium: 'video', video, type: 'video/quicktime' })
    await expect(prepareVideo(video, 'video/mp4', async () => { throw new Error('no frame') })).rejects.toThrow(BANNER_UNREADABLE)
    await expect(prepareVideo(video, 'video/mp4', async () => new Uint8Array())).rejects.toThrow(BANNER_UNREADABLE)
  })
})

describe('uploadBanner - the node\'s own BANNER (and BANNER_POSTER) blobs', () => {
  const plans = () => vi.mocked(reserveAndPump).mock.calls.at(-1)![0] as BlobPlan[]

  it('an image: one blob, owned by the node in the BANNER role, sealed as a JPEG under the node\'s key', async () => {
    const up = await uploadBanner(SCOPE, NODE, key, { medium: 'image', image: new Blob([new Uint8Array([1, 2, 3])]) })
    const [plan] = plans()
    expect(plans()).toHaveLength(1)
    expect(plan).toMatchObject({ ownerId: NODE, role: 'BANNER' })
    expect(up).toEqual({
      medium: 'image', uploadId: 'up-0',
      blob: { blobId: plan.blobId, chunkCount: plan.chunkCount, byteSize: plan.byteSize, wrappedBlobKey: plan.wrappedBlobKey },
    })
    expect(readBlobMeta(SCOPE, key, plan)).toEqual({ v: 1, type: 'image/jpeg' })
    expect(readBlobMeta(SCOPE, key, { ...plan, role: 'COVER' }), 'bound to its role').toBeNull()
  })

  it('a video: the poster and the video in ONE start, each in its own role, the video\'s type its own', async () => {
    const video = new Blob([new Uint8Array(500)])
    const up = await uploadBanner(SCOPE, NODE, key, { medium: 'video', video, type: 'video/webm', poster: new Blob([new Uint8Array([9])]) })
    const [poster, vid] = plans()
    expect(poster).toMatchObject({ ownerId: NODE, role: 'BANNER_POSTER' })
    expect(vid).toMatchObject({ ownerId: NODE, role: 'BANNER', source: video }) // the original as it is, read by the pump frame by frame
    expect(readBlobMeta(SCOPE, key, vid)).toEqual({ v: 1, type: 'video/webm' })
    expect(up).toMatchObject({ medium: 'video', uploadId: 'up-1', posterUploadId: 'up-0', type: 'video/webm' })
    expect(up.poster?.blobId).toBe(poster.blobId)
  })
})

// --- the one write ------------------------------------------------------------------------------------------------------

const sealMeta = (blob: object) => b64std(v1.sealField(metaCtx, key, new TextEncoder().encode(JSON.stringify(blob))))
const openMeta = (wrappedMeta: string) => unsealNodeMeta(key, { scopeId: SCOPE, nodeId: NODE }, wrappedMeta)
const ref = (blobId: string) => ({ blobId, chunkCount: 1, byteSize: 300, wrappedBlobKey: 'd3JhcA==' })
const image = (blobId: string) => sealedBanner({ medium: 'image', blob: ref(blobId), framing: { frame: 'fill', focal: CENTRE } })
const video = (blobId: string, posterId: string) =>
  sealedBanner({ medium: 'video', blob: ref(blobId), poster: ref(posterId), type: 'video/mp4', framing: { frame: 'fit', focal: CENTRE } })
const base = (wrappedMeta: string | null, metaRevision = 3) => ({ scopeId: SCOPE, nodeId: NODE, nodeKey: key, current: { wrappedMeta, metaRevision } })
const conflict = (body: object) => ({ response: new Response(JSON.stringify(body), { status: 409 }) })

type Doors = {
  setMemoryBanner: Mock<MemoryBannerApi['setMemoryBanner']>
  removeMemoryBanner: Mock<MemoryBannerApi['removeMemoryBanner']>
  updateNode: Mock<ContentApi['updateNode']>
}
function api(over: Partial<Doors> = {}): Doors {
  const calls: Doors = {
    setMemoryBanner: vi.fn<MemoryBannerApi['setMemoryBanner']>(async () => undefined),
    removeMemoryBanner: vi.fn<MemoryBannerApi['removeMemoryBanner']>(async () => undefined),
    updateNode: vi.fn<ContentApi['updateNode']>(async () => undefined),
    ...over,
  }
  fakeApi(memoryBannerApi, { setMemoryBanner: calls.setMemoryBanner, removeMemoryBanner: calls.removeMemoryBanner })
  fakeApi(contentApi, { updateNode: calls.updateNode })
  return calls
}

describe('writeMemoryBanner - the slots and the meta that names them, one request', () => {
  it('a first video banner: ONE setMemoryBanner with both uploads, no blob to replace, the meta at its revision, siblings kept', async () => {
    const c = api()
    const banner = video('v', 'p')
    const done = await writeMemoryBanner({ ...base(sealMeta({ v: 1, date: { mode: 'single' } })), banner, upload: { uploadId: 'u', posterUploadId: 'pu' } })
    expect(c.updateNode).not.toHaveBeenCalled()
    const req = c.setMemoryBanner.mock.calls[0][0].setMemoryBannerRequest
    expect(req).toMatchObject({ uploadId: 'u', posterUploadId: 'pu', expectedBlobId: null, expectedPosterBlobId: null, metaRevision: 3 })
    const sent = openMeta(req.wrappedMeta)
    expect(sent.banner).toEqual(banner)
    expect(sent.date).toEqual({ mode: 'single' })
    expect(done).toEqual({ wrappedMeta: req.wrappedMeta, metaRevision: 4 })
  })

  it('an image replacing a video states BOTH slots it read: the server frees the poster with the video', async () => {
    const c = api()
    await writeMemoryBanner({ ...base(sealMeta({ v: 1, banner: video('v', 'p') })), banner: image('i'), upload: { uploadId: 'u' } })
    expect(c.setMemoryBanner.mock.calls[0][0].setMemoryBannerRequest).toMatchObject({
      uploadId: 'u', posterUploadId: null, expectedBlobId: 'v', expectedPosterBlobId: 'p',
    })
  })

  it('a removal frees both slots the meta named and writes the meta without the banner', async () => {
    const c = api()
    await writeMemoryBanner({ ...base(sealMeta({ v: 1, banner: video('v', 'p'), cover: { x: 1 } })), banner: null })
    const req = c.removeMemoryBanner.mock.calls[0][0].removeMemoryBannerRequest
    expect(req).toMatchObject({ blobId: 'v', posterBlobId: 'p', metaRevision: 3 })
    expect(openMeta(req.wrappedMeta).banner).toBeUndefined()
    expect(openMeta(req.wrappedMeta).cover).toEqual({ x: 1 })
    expect(c.setMemoryBanner).not.toHaveBeenCalled()
  })

  it('a re-frame is a plain meta write: no slot is touched', async () => {
    const c = api()
    const reframed = { ...image('i'), frame: 'fit', focal: { x: 0.1, y: 0.9 } }
    await writeMemoryBanner({ ...base(sealMeta({ v: 1, banner: image('i') })), banner: reframed })
    expect(c.setMemoryBanner).not.toHaveBeenCalled()
    expect(c.removeMemoryBanner).not.toHaveBeenCalled()
    expect(openMeta(c.updateNode.mock.calls[0][0].updateNodeRequest.wrappedMeta!).banner).toEqual(reframed)
  })

  it('a stale meta re-runs the change on the fresh meta and compares against what IT names; the same uploads go again', async () => {
    const fresh = sealMeta({ v: 1, banner: image('peer'), date: { mode: 'auto' } })
    const set = vi.fn<MemoryBannerApi['setMemoryBanner']>()
      .mockRejectedValueOnce(conflict({ code: 'stale_revision', wrappedMeta: fresh, metaRevision: 7 }))
      .mockResolvedValueOnce(undefined)
    api({ setMemoryBanner: set })
    await writeMemoryBanner({ ...base(null), banner: image('mine'), upload: { uploadId: 'u' }, surfaceConflict: false })
    expect(set).toHaveBeenCalledTimes(2)
    expect(set.mock.calls[1][0].setMemoryBannerRequest).toMatchObject({ uploadId: 'u', expectedBlobId: 'peer', metaRevision: 7 })
    expect(openMeta(set.mock.calls[1][0].setMemoryBannerRequest.wrappedMeta).date).toEqual({ mode: 'auto' })
  })

  it('a peer\'s banner change surfaces to the dialog; a stale slot is a BannerStaleError', async () => {
    const fresh = sealMeta({ v: 1, banner: image('peer') })
    api({ setMemoryBanner: vi.fn<MemoryBannerApi['setMemoryBanner']>().mockRejectedValueOnce(conflict({ code: 'stale_revision', wrappedMeta: fresh, metaRevision: 7 })) })
    await expect(writeMemoryBanner({ ...base(null), banner: image('mine'), upload: { uploadId: 'u' } })).rejects.toBeInstanceOf(MetaConflictError)
    api({ setMemoryBanner: vi.fn<MemoryBannerApi['setMemoryBanner']>().mockRejectedValueOnce(conflict({ code: 'banner_stale', currentBlobId: 'x' })) })
    await expect(writeMemoryBanner({ ...base(null), banner: image('mine'), upload: { uploadId: 'u' } })).rejects.toBeInstanceOf(BannerStaleError)
  })
})
