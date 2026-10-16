import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'

vi.mock('../../../api/client', () => ({ contentApi: vi.fn(), blobApi: vi.fn(), loginApi: vi.fn(), tokenApi: vi.fn(), sessionApi: vi.fn() }))
// The transport's reserve-and-pump is the platform's (`core/blob/transport.test.ts`, the wire characterisation): here it hands
// back the session id and records the plans it was given, so what is under test is what the cover adds to it.
vi.mock('../../blob/transport', async (orig) => ({ ...(await orig<object>()), reserveAndPump: vi.fn(async () => ['up-1']) }))

import { COVER_SPEC, COVER_UNREADABLE, CoverStaleError, MAX_COVER_SOURCE_BYTES, uploadCoverImage, writeNodeCover } from './coverWrite'
import { FULL_FRAME, uploadedCover } from './coverModel'
import { unsealNodeMeta } from '../nodeMeta'
import { MetaConflictError } from '../sealedMetaWrite'
import { reserveAndPump, readBlobMeta } from '../../blob/transport'
import { contentApi } from '../../../api/client'
import type { ContentApi } from '../../../api/generated/content'
import type { BlobPlan } from '../../blob/types'
import type { MediaDeriver } from '../../../shared/image/mediaDeriver'
import { fakeApi } from '../../../test/apiMock'

const SCOPE = '11111111-1111-4111-8111-111111111111'
const NODE = '22222222-2222-4222-8222-222222222222'
const key = v1.randomBytes(32)
const metaCtx = { purpose: 'node-meta', scopeId: SCOPE, nodeId: NODE } as const

const stubDeriver = (opts: { fails?: boolean } = {}) => {
  const deriveImage = vi.fn(async (_source: Blob, spec: { maxEdge: number }) => {
    if (opts.fails) throw new Error('undecodable')
    return { bytes: new Uint8Array([0xff, 0xd8, spec.maxEdge & 0xff, spec.maxEdge >> 8]), source: { w: 4000, h: 3000 } }
  })
  return { deriveImage, deriveVideoPoster: vi.fn() } as unknown as MediaDeriver & { deriveImage: typeof deriveImage }
}
const source = (type: string, bytes = [1, 2, 3]) => ({ blob: new Blob([new Uint8Array(bytes)], { type }), mediaType: type, name: 'IMG_0001.JPG' })
const planOfLastUpload = () => vi.mocked(reserveAndPump).mock.calls.at(-1)![0][0] as BlobPlan

beforeEach(() => vi.clearAllMocks())

describe('uploadCoverImage - the node\'s own COVER blob', () => {
  it('downscales an image to ONE cover-sized JPEG, owned by the node in the COVER role, its type the JPEG\'s', async () => {
    const deriver = stubDeriver()
    const up = await uploadCoverImage(SCOPE, NODE, key, source('image/png'), deriver)

    expect(deriver.deriveImage).toHaveBeenCalledOnce()
    expect(deriver.deriveImage).toHaveBeenCalledWith(expect.anything(), COVER_SPEC) // no THUMBNAIL/PREVIEW split: the cover spec
    const plan = planOfLastUpload()
    expect(plan).toMatchObject({ ownerId: NODE, role: 'COVER' })
    expect(up).toEqual({ uploadId: 'up-1', plan: { blobId: plan.blobId, chunkCount: plan.chunkCount, byteSize: plan.byteSize, wrappedBlobKey: plan.wrappedBlobKey, ownerId: NODE } })
    // the blob's key wrap and its sealed meta open under the NODE's key, bound to (blob, node, COVER): the type is the JPEG it
    // was derived into, not the PNG it came from (inbox #397), and the picked file's name is nowhere in it
    const meta = readBlobMeta(SCOPE, key, { ...plan, wrappedBlobMeta: plan.wrappedBlobMeta })
    expect(meta).toEqual({ v: 1, type: 'image/jpeg' })
    expect(readBlobMeta(SCOPE, v1.randomBytes(32), { ...plan }), 'another key opens nothing').toBeNull()
    expect(readBlobMeta(SCOPE, key, { ...plan, ownerId: '33333333-3333-4333-8333-333333333333' }), 'nor does another node').toBeNull()
  })

  it('strict (a folder\'s or drop box\'s pick) refuses what the browser cannot read: a non-image, an undecodable image', async () => {
    const notImage = stubDeriver()
    await expect(uploadCoverImage(SCOPE, NODE, key, source('application/pdf'), notImage, { strict: true })).rejects.toThrow(COVER_UNREADABLE)
    expect(notImage.deriveImage).not.toHaveBeenCalled()
    await expect(uploadCoverImage(SCOPE, NODE, key, source('image/heic'), stubDeriver({ fails: true }), { strict: true })).rejects.toThrow(COVER_UNREADABLE)
    expect(reserveAndPump, 'nothing was uploaded').not.toHaveBeenCalled()
  })

  it('not strict (a Memory\'s dialog, as ever) seals the pristine bytes of what it cannot read, and a non-image as it is', async () => {
    await uploadCoverImage(SCOPE, NODE, key, source('image/heic'), stubDeriver({ fails: true }))
    const first = planOfLastUpload()
    expect(first.byteSize).toBeGreaterThan(0)
    expect(readBlobMeta(SCOPE, key, first)).toEqual({ v: 1, type: 'image/heic' })
    const deriver = stubDeriver()
    await uploadCoverImage(SCOPE, NODE, key, source('application/octet-stream'), deriver)
    expect(deriver.deriveImage).not.toHaveBeenCalled()
  })

  it('states its limit: a source over 40 MB is the picker\'s to refuse', () => {
    expect(MAX_COVER_SOURCE_BYTES).toBe(40 * 1024 * 1024)
  })
})

// --- the one write ------------------------------------------------------------------------------------------------------

const sealMeta = (blob: object) => b64std(v1.sealField(metaCtx, key, new TextEncoder().encode(JSON.stringify(blob))))
const openMeta = (wrappedMeta: string) => unsealNodeMeta(key, { scopeId: SCOPE, nodeId: NODE }, wrappedMeta)
const plan = (blobId: string) => ({ blobId, chunkCount: 1, byteSize: 300, wrappedBlobKey: 'd3JhcA==', ownerId: NODE })
const uploadedMeta = (blobId: string, extra: object = {}) => sealMeta({ v: 1, ...extra, cover: uploadedCover(plan(blobId)) })
const stale = (body: object) => ({ response: new Response(JSON.stringify(body), { status: 409 }) })
const base = (wrappedMeta: string | null, metaRevision = 3) => ({ scopeId: SCOPE, nodeId: NODE, nodeKey: key, current: { wrappedMeta, metaRevision } })

type Doors = { setNodeCover: Mock<ContentApi['setNodeCover']>; removeNodeCover: Mock<ContentApi['removeNodeCover']>; updateNode: Mock<ContentApi['updateNode']> }
function api(over: Partial<Doors> = {}): Doors {
  const calls: Doors = {
    setNodeCover: vi.fn<ContentApi['setNodeCover']>(async () => undefined),
    removeNodeCover: vi.fn<ContentApi['removeNodeCover']>(async () => undefined),
    updateNode: vi.fn<ContentApi['updateNode']>(async () => undefined),
    ...over,
  }
  fakeApi(contentApi, calls)
  return calls
}

describe('writeNodeCover - the blob step and the meta that names it, one request', () => {
  it('an uploaded cover onto a node with none: ONE setNodeCover - the open upload, no blob to replace, the sealed meta at its revision', async () => {
    const c = api()
    const cover = uploadedCover(plan('new-blob'))
    const done = await writeNodeCover({ ...base(sealMeta({ v: 1, date: { mode: 'single' } })), cover, uploadId: 'up-1' })

    expect(c.updateNode).not.toHaveBeenCalled()
    expect(c.removeNodeCover).not.toHaveBeenCalled()
    const req = c.setNodeCover.mock.calls[0][0]
    expect(req).toMatchObject({ scopeId: SCOPE, nodeId: NODE })
    expect(req.setNodeCoverRequest).toMatchObject({ uploadId: 'up-1', expectedBlobId: null, metaRevision: 3 })
    // the meta it carries names exactly the blob that was uploaded, and keeps the sibling a Memory sealed beside it
    const sent = openMeta(req.setNodeCoverRequest.wrappedMeta)
    expect(sent.cover).toEqual(cover)
    expect(sent.date).toEqual({ mode: 'single' })
    expect(done).toMatchObject({ metaRevision: 4, replaced: null })
    expect(done.wrappedMeta).toBe(req.setNodeCoverRequest.wrappedMeta)
  })

  it('a replacing upload compares against the blob the meta being replaced names', async () => {
    const c = api()
    const done = await writeNodeCover({ ...base(uploadedMeta('old-blob')), cover: uploadedCover(plan('new-blob')), uploadId: 'up-2' })
    expect(c.setNodeCover.mock.calls[0][0].setNodeCoverRequest.expectedBlobId).toBe('old-blob')
    expect(uploadBlobOf(done.replaced)).toBe('old-blob')
  })

  it('a removal of an uploaded cover is ONE removeNodeCover naming the blob it frees, the meta without the cover', async () => {
    const c = api()
    const done = await writeNodeCover({ ...base(uploadedMeta('old-blob', { location: { name: 'Rom' } })), cover: null })
    expect(c.setNodeCover).not.toHaveBeenCalled()
    expect(c.updateNode).not.toHaveBeenCalled()
    const req = c.removeNodeCover.mock.calls[0][0].removeNodeCoverRequest
    expect(req).toMatchObject({ blobId: 'old-blob', metaRevision: 3 })
    const sent = openMeta(req.wrappedMeta)
    expect('cover' in sent).toBe(false)
    expect(sent.location).toEqual({ name: 'Rom' }) // a sibling survives
    expect(done.metaRevision).toBe(4)
  })

  it('a switch from an uploaded cover to another source (a Memory\'s photo) frees the blob in the same request', async () => {
    const c = api()
    const photo = { source: { kind: 'media', mediaId: 'm1' }, crop: FULL_FRAME }
    await writeNodeCover({ ...base(uploadedMeta('old-blob')), cover: photo })
    expect(c.removeNodeCover.mock.calls[0][0].removeNodeCoverRequest.blobId).toBe('old-blob')
    expect(openMeta(c.removeNodeCover.mock.calls[0][0].removeNodeCoverRequest.wrappedMeta).cover).toEqual(photo)
  })

  it('a cover with no blob step - a moved crop, a photo over a photo, a first photo - is a plain meta write', async () => {
    const c = api()
    const moved = { ...uploadedCover(plan('old-blob')), crop: { x: 0.1, y: 0.1, width: 0.5, height: 0.5 } }
    await writeNodeCover({ ...base(uploadedMeta('old-blob')), cover: moved }) // the same blob: it stays
    await writeNodeCover({ ...base(sealMeta({ v: 1, cover: { source: { kind: 'media', mediaId: 'a' }, crop: FULL_FRAME } })), cover: { source: { kind: 'media', mediaId: 'b' }, crop: FULL_FRAME } })
    await writeNodeCover({ ...base(null), cover: { source: { kind: 'media', mediaId: 'b' }, crop: FULL_FRAME } })
    expect(c.updateNode).toHaveBeenCalledTimes(3)
    expect(c.setNodeCover).not.toHaveBeenCalled()
    expect(c.removeNodeCover).not.toHaveBeenCalled()
    expect(c.updateNode.mock.calls[0][0].updateNodeRequest).toMatchObject({ metaRevision: 3 })
  })

  it('a 409 stale_revision re-runs on the blob the server holds: the compare is a peer\'s cover now, not the copy the write was built on', async () => {
    const c = api({
      setNodeCover: vi.fn<ContentApi['setNodeCover']>()
        .mockRejectedValueOnce(stale({ code: 'stale_revision', wrappedMeta: uploadedMeta('peer-blob', { location: { name: 'Wien' } }), metaRevision: 7 }))
        .mockResolvedValue(undefined),
    })
    const done = await writeNodeCover({ ...base(uploadedMeta('old-blob')), cover: uploadedCover(plan('new-blob')), uploadId: 'up-1', surfaceConflict: false })

    expect(c.setNodeCover).toHaveBeenCalledTimes(2)
    const [first, second] = c.setNodeCover.mock.calls.map((x) => x[0].setNodeCoverRequest)
    expect(first).toMatchObject({ expectedBlobId: 'old-blob', metaRevision: 3, uploadId: 'up-1' })
    expect(second).toMatchObject({ expectedBlobId: 'peer-blob', metaRevision: 7, uploadId: 'up-1' }) // the SAME upload, re-based
    expect(openMeta(second.wrappedMeta).location).toEqual({ name: 'Wien' }) // the peer's place survives
    expect(uploadBlobOf(done.replaced)).toBe('peer-blob')
  })

  it('a cover another writer changed is surfaced to an editor, and replaced by a picker that asked for it', async () => {
    const peers = stale({ code: 'stale_revision', wrappedMeta: uploadedMeta('peer-blob'), metaRevision: 7 })
    api({ setNodeCover: vi.fn<ContentApi['setNodeCover']>().mockRejectedValueOnce(peers).mockResolvedValue(undefined) })
    await expect(writeNodeCover({ ...base(null), cover: uploadedCover(plan('mine')), uploadId: 'up-1' })).rejects.toBeInstanceOf(MetaConflictError)
  })

  it('a slot a peer changed (409 cover_stale) is a CoverStaleError - nothing was written, the old cover is intact', async () => {
    api({ setNodeCover: vi.fn<ContentApi['setNodeCover']>().mockRejectedValue(stale({ code: 'cover_stale', currentBlobId: 'peer-blob' })) })
    await expect(writeNodeCover({ ...base(null), cover: uploadedCover(plan('mine')), uploadId: 'up-1', surfaceConflict: false })).rejects.toBeInstanceOf(CoverStaleError)
    api({ removeNodeCover: vi.fn<ContentApi['removeNodeCover']>().mockRejectedValue(stale({ code: 'cover_stale', currentBlobId: null })) })
    await expect(writeNodeCover({ ...base(uploadedMeta('old-blob')), cover: null })).rejects.toBeInstanceOf(CoverStaleError)
  })

  it('any other failure is the caller\'s, unchanged (a 409 with another code included)', async () => {
    api({ setNodeCover: vi.fn<ContentApi['setNodeCover']>().mockRejectedValue(stale({ code: 'scope_deleting' })) })
    await expect(writeNodeCover({ ...base(null), cover: uploadedCover(plan('mine')), uploadId: 'up-1' })).rejects.toMatchObject({ response: expect.anything() })
  })
})

const uploadBlobOf = (cover: unknown): string | null => (cover as { source?: { ref?: { blobId?: string } } } | null)?.source?.ref?.blobId ?? null
