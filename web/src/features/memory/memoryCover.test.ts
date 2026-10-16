import { describe, it, expect, vi, beforeEach } from 'vitest'
import { fakeApi, stubApi } from '../../test/apiMock'
import type { ContentApi } from '../../api/generated/content'
import { mediaItem } from '../../test/models'

vi.mock('../../api/client', () => ({
  contentApi: vi.fn(),
  loginApi: vi.fn(),
  tokenApi: vi.fn(),
  sessionApi: vi.fn(),
}))

// loadNodeCover delegates the actual decrypt to the media transport (covered end-to-end in media.test.ts);
// here we stub it to assert the source-branch routing (media → listMedia+openGrid, upload → openBlob).
vi.mock('./media', () => ({
  listMedia: vi.fn(),
  openGrid: vi.fn(),
  openBlob: vi.fn(),
  hasDerivatives: vi.fn(),
}))

import {
  normalizeCoverCrop,
  normalizeMemoryCover,
  unsealMemoryCover,
  memoryPhotoSource,
  coverImageStyle,
  FULL_FRAME,
  type MemoryCover,
} from './memoryCover'
import { loadMemoryPhotoCover } from './coverLoad'
import { writeNodeCover } from '../../core/node/cover/coverWrite'
import type { SealedMetaState } from '../../core/node/sealedMetaWrite'
import { contentApi } from '../../api/client'
import { MetaConflictError } from '../../core/node/sealedMetaWrite'
import { listMedia, openGrid, hasDerivatives } from './media'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'


const SCOPE_ID = '11111111-1111-1111-1111-111111111111'
const nodeKey = v1.randomBytes(32)
// The node every meta blob here belongs to — each seal is bound to it (ADR 0072).
const NODE_ID = '5c09e000-0000-4000-8000-0000000000c1'
const OTHER_NODE_ID = '5c09e000-0000-4000-8000-0000000000c2'
const metaCtx = (scopeId: string, nodeId: string) => ({ purpose: 'node-meta', scopeId, nodeId }) as const

/** Seal an arbitrary node-meta blob under `nodeKey` as the RMW envelope would (purpose `node-meta`, bound to the node). */
function sealMeta(blob: object, scopeId = SCOPE_ID, nodeId = NODE_ID): string {
  return b64std(v1.sealField(metaCtx(scopeId, nodeId), nodeKey, new TextEncoder().encode(JSON.stringify(blob))))
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(hasDerivatives).mockReturnValue(true)
})

describe('normalizeCoverCrop', () => {
  it('passes a valid on-image crop through unchanged', () => {
    const crop = { x: 0.1, y: 0.2, width: 0.5, height: 0.3 }
    expect(normalizeCoverCrop(crop)).toEqual(crop)
  })

  it('falls back to the full frame for a malformed / absent crop (E4, never a throw)', () => {
    expect(normalizeCoverCrop(null)).toEqual(FULL_FRAME)
    expect(normalizeCoverCrop('nope')).toEqual(FULL_FRAME)
    expect(normalizeCoverCrop({})).toEqual(FULL_FRAME)
    expect(normalizeCoverCrop({ x: NaN, y: 0.2, width: 0, height: -1 })).toEqual(FULL_FRAME)
  })

  it('clamps the extent to (0..1] and keeps the rect on-image (x ∈ [0, 1-width])', () => {
    // width>1 clamps to 1 (⇒ x pinned to 0); an over-far x is pulled back so x+width ≤ 1
    expect(normalizeCoverCrop({ x: 0.9, y: 0.9, width: 2, height: 2 })).toEqual(FULL_FRAME)
    expect(normalizeCoverCrop({ x: 0.9, y: 0.5, width: 0.5, height: 0.5 })).toEqual({ x: 0.5, y: 0.5, width: 0.5, height: 0.5 })
    // a negative origin clamps up to 0
    expect(normalizeCoverCrop({ x: -0.3, y: -0.1, width: 0.4, height: 0.4 })).toEqual({ x: 0, y: 0, width: 0.4, height: 0.4 })
  })
})

/** A cover blob ref as it sits sealed in the meta (the opaque read-back shape)… */
const UPLOAD_REF_RAW = { blobId: 'b-1', chunkCount: 1, byteSize: 4096, wrappedBlobKey: 'd3JhcA==' }
/** …and as a reader addressing NODE_ID resolves it: owned by that node, in the COVER role (ADR 0072). */
const UPLOAD_REF = { ...UPLOAD_REF_RAW, ownerId: NODE_ID, role: 'COVER' as const }

describe('normalizeMemoryCover', () => {
  it('normalizes a media source + crop', () => {
    expect(normalizeMemoryCover({ source: { kind: 'media', mediaId: 'm1' }, crop: { x: 0.1, y: 0.1, width: 0.5, height: 0.5 } }, NODE_ID)).toEqual({
      source: { kind: 'media', mediaId: 'm1' },
      crop: { x: 0.1, y: 0.1, width: 0.5, height: 0.5 },
    })
    // missing/blank crop → full frame
    expect(normalizeMemoryCover({ source: { kind: 'media', mediaId: 'm1' } }, NODE_ID)).toEqual({ source: { kind: 'media', mediaId: 'm1' }, crop: FULL_FRAME })
  })

  it('normalizes an upload source (a standalone cover blob ref)', () => {
    expect(normalizeMemoryCover({ source: { kind: 'upload', ref: UPLOAD_REF_RAW }, crop: FULL_FRAME }, NODE_ID)).toEqual({
      source: { kind: 'upload', ref: UPLOAD_REF },
      crop: FULL_FRAME,
    })
  })

  it('takes an upload ref\'s owner + role from the addressed node, never from the sealed payload', () => {
    // a ref that claims another owner/role (e.g. copied from a media) is re-anchored to this node's COVER slot,
    // so its K_blob wrap — bound to where it was really sealed — refuses to open here instead of being trusted
    const claimed = { ...UPLOAD_REF_RAW, ownerId: OTHER_NODE_ID, role: 'ORIGINAL' }
    expect(normalizeMemoryCover({ source: { kind: 'upload', ref: claimed } }, NODE_ID)?.source).toEqual({ kind: 'upload', ref: UPLOAD_REF })
  })

  it('is backward-compatible: a cr-0072 flat { mediaId, crop } seal opens as a media source', () => {
    expect(normalizeMemoryCover({ mediaId: 'legacy', crop: { x: 0.2, y: 0, width: 0.6, height: 0.6 } }, NODE_ID)).toEqual({
      source: { kind: 'media', mediaId: 'legacy' },
      crop: { x: 0.2, y: 0, width: 0.6, height: 0.6 },
    })
  })

  it('returns null for the "no cover" / malformed-source shapes', () => {
    expect(normalizeMemoryCover(null, NODE_ID)).toBeNull()
    expect(normalizeMemoryCover({}, NODE_ID)).toBeNull()
    expect(normalizeMemoryCover({ mediaId: '' }, NODE_ID)).toBeNull()
    expect(normalizeMemoryCover({ mediaId: 42 }, NODE_ID)).toBeNull()
    expect(normalizeMemoryCover({ source: { kind: 'media' } }, NODE_ID)).toBeNull() // media without a mediaId
    expect(normalizeMemoryCover({ source: { kind: 'upload' } }, NODE_ID)).toBeNull() // upload without a ref
    expect(normalizeMemoryCover({ source: { kind: 'upload', ref: { blobId: 'b' } } }, NODE_ID)).toBeNull() // ref missing fields
    expect(normalizeMemoryCover({ source: { kind: 'bogus', mediaId: 'x' } }, NODE_ID)).toBeNull()
  })
})

describe('unsealMemoryCover', () => {
  it('round-trips a media cover sealed under K_node', () => {
    const cover: MemoryCover = { source: { kind: 'media', mediaId: 'm-7' }, crop: { x: 0.2, y: 0.1, width: 0.6, height: 0.34 } }
    const sealed = sealMeta({ v: 1, cover })
    expect(unsealMemoryCover(SCOPE_ID, nodeKey, { id: NODE_ID, wrappedMeta: sealed })).toEqual(cover)
  })

  it('round-trips an upload cover (standalone blob ref) sealed under K_node', () => {
    const cover: MemoryCover = { source: { kind: 'upload', ref: UPLOAD_REF }, crop: FULL_FRAME }
    const sealed = sealMeta({ v: 1, cover: { source: { kind: 'upload', ref: UPLOAD_REF_RAW }, crop: FULL_FRAME } })
    expect(unsealMemoryCover(SCOPE_ID, nodeKey, { id: NODE_ID, wrappedMeta: sealed })).toEqual(cover)
  })

  it('opens an already-sealed cr-0072 { mediaId, crop } cover as a media source (back-compat)', () => {
    const sealed = sealMeta({ v: 1, cover: { mediaId: 'old-seal', crop: { x: 0, y: 0.1, width: 0.5, height: 0.5 } } })
    expect(unsealMemoryCover(SCOPE_ID, nodeKey, { id: NODE_ID, wrappedMeta: sealed })).toEqual({
      source: { kind: 'media', mediaId: 'old-seal' },
      crop: { x: 0, y: 0.1, width: 0.5, height: 0.5 },
    })
  })

  it('is best-effort null for absent / wrong-key / another node\'s / malformed meta (E3/E4)', () => {
    expect(unsealMemoryCover(SCOPE_ID, nodeKey, { id: NODE_ID, wrappedMeta: null })).toBeNull()
    const sealed = sealMeta({ v: 1, cover: { source: { kind: 'media', mediaId: 'm-7' }, crop: FULL_FRAME } })
    expect(unsealMemoryCover(SCOPE_ID, v1.randomBytes(32), { id: NODE_ID, wrappedMeta: sealed })).toBeNull() // wrong key
    // the blob served on another node's row does not open there (ADR 0072)
    expect(unsealMemoryCover(SCOPE_ID, nodeKey, { id: OTHER_NODE_ID, wrappedMeta: sealed })).toBeNull()
    expect(unsealMemoryCover(SCOPE_ID, nodeKey, { id: NODE_ID, wrappedMeta: 'not-base64!!' })).toBeNull()
    expect(unsealMemoryCover(SCOPE_ID, nodeKey, { id: NODE_ID, wrappedMeta: sealMeta({ v: 1 }) })).toBeNull() // no cover sub-object
  })

  it('is operator-blind — the sealed blob carries no plaintext mediaId', () => {
    const sealed = sealMeta({ v: 1, cover: { source: { kind: 'media', mediaId: 'secret-media-id' }, crop: FULL_FRAME } })
    expect(new TextDecoder().decode(fromB64std(sealed))).not.toContain('secret-media-id')
  })
})

// A Memory cover's meta write is core's `writeNodeCover` since inbox #411 (the one writer for every kind); what is pinned here is
// that the Memory's stored shapes (a photo, an upload, the siblings beside them) go through it unchanged, byte for byte.
const setMemoryCover = async (
  scopeId: string, nodeId: string, key: Uint8Array, cover: MemoryCover | null, current: SealedMetaState, surfaceConflict = true,
) => {
  const { replaced, ...state } = await writeNodeCover({ scopeId, nodeId, nodeKey: key, current, cover, surfaceConflict })
  return { ...state, replaced: normalizeMemoryCover(replaced, nodeId) }
}

describe('setMemoryCover', () => {
  it('re-seals under K_node and PATCHes wrappedMeta via updateNode', async () => {
    const { updateNode } = stubApi(contentApi, { updateNode: undefined })

    const cover: MemoryCover = { source: { kind: 'media', mediaId: 'm-3' }, crop: { x: 0.1, y: 0.2, width: 0.5, height: 0.28 } }
    const { wrappedMeta: sealed } = await setMemoryCover(SCOPE_ID, NODE_ID, nodeKey, cover, { wrappedMeta: null, metaRevision: 0 })

    const req = updateNode.mock.calls[0][0]
    expect(req.scopeId).toBe(SCOPE_ID)
    expect(req.nodeId).toBe(NODE_ID)
    expect(req.updateNodeRequest.wrappedMeta).toBe(sealed)
    expect(unsealMemoryCover(SCOPE_ID, nodeKey, { id: NODE_ID, wrappedMeta: sealed })).toEqual(cover)
  })

  it('read-modify-write: setting a cover preserves a sibling date + location', async () => {
    stubApi(contentApi, { updateNode: undefined })

    const date = { mode: 'single', from: '2026-05-01', to: '2026-05-01' }
    const location = { name: 'Teneriffa', nameSource: 'manual', derivedMode: 'fixed' }
    const withSiblings = sealMeta({ v: 1, date, location })

    const cover: MemoryCover = { source: { kind: 'media', mediaId: 'm-3' }, crop: FULL_FRAME }
    const sealed = (await setMemoryCover(SCOPE_ID, NODE_ID, nodeKey, cover, { wrappedMeta: withSiblings, metaRevision: 0 })).wrappedMeta!

    const blob = JSON.parse(new TextDecoder().decode(v1.openField(metaCtx(SCOPE_ID, NODE_ID), nodeKey, fromB64std(sealed))))
    expect(blob.cover).toEqual(cover)
    expect(blob.date).toEqual(date)
    expect(blob.location).toEqual(location)
  })

  it('read-modify-write: removing a cover (null) drops it but keeps the date + location', async () => {
    stubApi(contentApi, { updateNode: undefined })

    const date = { mode: 'single', from: '2026-05-01', to: '2026-05-01' }
    const location = { name: 'Teneriffa', nameSource: 'manual', derivedMode: 'fixed' }
    const withCover = sealMeta({ v: 1, date, location, cover: { source: { kind: 'media', mediaId: 'm-3' }, crop: FULL_FRAME } })

    const sealed = (await setMemoryCover(SCOPE_ID, NODE_ID, nodeKey, null, { wrappedMeta: withCover, metaRevision: 0 })).wrappedMeta!

    const blob = JSON.parse(new TextDecoder().decode(v1.openField(metaCtx(SCOPE_ID, NODE_ID), nodeKey, fromB64std(sealed))))
    expect(blob.cover).toBeUndefined()
    expect(unsealMemoryCover(SCOPE_ID, nodeKey, { id: NODE_ID, wrappedMeta: sealed })).toBeNull()
    expect(blob.date).toEqual(date)
    expect(blob.location).toEqual(location)
  })
  const staleWith = (wrappedMeta: string, metaRevision: number) => ({
    response: new Response(JSON.stringify({ code: 'stale_revision', wrappedMeta, metaRevision }), { status: 409 }),
  })

  /** W5 (ADR 0077): a cover save from a stale tree meets a 409 whose blob holds a peer's place — the retry keeps it. */
  it('W5: on 409 stale_revision re-applies only the cover on the current blob — a peer\'s place survives', async () => {
    const location = { name: 'Lisbon', nameSource: 'manual', derivedMode: 'fixed' }
    const updateNode = vi.fn<ContentApi['updateNode']>().mockRejectedValueOnce(staleWith(sealMeta({ v: 1, location }), 2)).mockResolvedValueOnce(undefined)
    fakeApi(contentApi, { updateNode })
    const cover: MemoryCover = { source: { kind: 'media', mediaId: 'm-3' }, crop: FULL_FRAME }

    const state = await setMemoryCover(SCOPE_ID, NODE_ID, nodeKey, cover, { wrappedMeta: null, metaRevision: 1 })

    expect(updateNode.mock.calls[1][0].updateNodeRequest.metaRevision).toBe(2)
    const blob = JSON.parse(new TextDecoder().decode(v1.openField(metaCtx(SCOPE_ID, NODE_ID), nodeKey, fromB64std(state.wrappedMeta!))))
    expect(blob.cover).toEqual(cover)
    expect(blob.location).toEqual(location)
  })

  /** W10 (D9): a peer changed the COVER — a plain save re-opens (conflict), a save that moved the blob re-applies. */
  it('W10: a same-field conflict surfaces by default, and is re-applied when told the cover blob already moved', async () => {
    const peersCover = { source: { kind: 'media', mediaId: 'peer' }, crop: FULL_FRAME }
    const peers = sealMeta({ v: 1, cover: peersCover })
    const mine: MemoryCover = { source: { kind: 'media', mediaId: 'mine' }, crop: FULL_FRAME }

    const surfaced = vi.fn<ContentApi['updateNode']>().mockRejectedValueOnce(staleWith(peers, 2)).mockResolvedValue(undefined)
    fakeApi(contentApi, { updateNode: surfaced })
    const err = await setMemoryCover(SCOPE_ID, NODE_ID, nodeKey, mine, { wrappedMeta: null, metaRevision: 1 }).catch((e) => e)
    expect(err).toBeInstanceOf(MetaConflictError)
    expect(surfaced).toHaveBeenCalledTimes(1)

    const reapplied = vi.fn<ContentApi['updateNode']>().mockRejectedValueOnce(staleWith(peers, 2)).mockResolvedValue(undefined)
    fakeApi(contentApi, { updateNode: reapplied })
    const state = await setMemoryCover(SCOPE_ID, NODE_ID, nodeKey, mine, { wrappedMeta: null, metaRevision: 1 }, false)
    expect(reapplied).toHaveBeenCalledTimes(2)
    expect(unsealMemoryCover(SCOPE_ID, nodeKey, { id: NODE_ID, wrappedMeta: state.wrappedMeta })).toEqual(mine)
  })

  /** inbox #284: `replaced` is the cover the LANDED write replaced — the 409's current blob, not the caller's copy. */
  it('reports the cover it replaced from the blob the write landed on, not from the copy it was handed', async () => {
    const peersUpload = { source: { kind: 'upload', ref: { blobId: 'peer-blob', chunkCount: 1, byteSize: 9, wrappedBlobKey: 'a2V5' } }, crop: FULL_FRAME }
    const mineOld = sealMeta({ v: 1, cover: { source: { kind: 'media', mediaId: 'mine-old' }, crop: FULL_FRAME } })
    const updateNode = vi.fn<ContentApi['updateNode']>()
      .mockRejectedValueOnce(staleWith(sealMeta({ v: 1, cover: peersUpload }), 2))
      .mockResolvedValue(undefined)
    const removeNodeCover = vi.fn<ContentApi['removeNodeCover']>().mockResolvedValue(undefined)
    fakeApi(contentApi, { updateNode, removeNodeCover })
    const mine: MemoryCover = { source: { kind: 'media', mediaId: 'mine' }, crop: FULL_FRAME }

    const state = await setMemoryCover(SCOPE_ID, NODE_ID, nodeKey, mine, { wrappedMeta: mineOld, metaRevision: 1 }, false)

    expect(state.replaced).toEqual(normalizeMemoryCover(peersUpload, NODE_ID))
    expect(state.replaced?.source.kind).toBe('upload')
    // inbox #411: the peer's uploaded blob is freed in the SAME write that replaces it, decided from the blob the 409 handed
    // back (the first attempt, built on a copy that named no upload, was a plain meta write)
    expect(updateNode).toHaveBeenCalledTimes(1)
    expect(removeNodeCover.mock.calls[0][0].removeNodeCoverRequest).toMatchObject({ blobId: 'peer-blob', metaRevision: 2 })

    stubApi(contentApi, { updateNode: undefined, removeNodeCover: undefined })
    const plain = await setMemoryCover(SCOPE_ID, NODE_ID, nodeKey, null, { wrappedMeta: mineOld, metaRevision: 1 })
    expect(plain.replaced).toEqual({ source: { kind: 'media', mediaId: 'mine-old' }, crop: FULL_FRAME })
  })
})

// The Memory's extra source in core's cover dialog (`KindCover.extraSource`, registered for MEMORY and CHAPTER).
describe('memoryPhotoSource - the Memory\'s second source for core\'s dialog', () => {
  it('seals a photo as the exact stored shape every Memory cover has had (key order included)', () => {
    const crop = { x: 0.1, y: 0.2, width: 0.5, height: 0.28125 }
    const sealed = memoryPhotoSource.seal('m-3', crop)
    expect(JSON.stringify(sealed)).toBe('{"source":{"kind":"media","mediaId":"m-3"},"crop":{"x":0.1,"y":0.2,"width":0.5,"height":0.28125}}')
    expect(normalizeMemoryCover(sealed, NODE_ID)).toEqual({ source: { kind: 'media', mediaId: 'm-3' }, crop })
  })

  it('names the photo a stored cover already is - the new shape, the cr-0072 flat one - and no other source', () => {
    expect(memoryPhotoSource.idOf({ source: { kind: 'media', mediaId: 'm-3' }, crop: FULL_FRAME })).toBe('m-3')
    expect(memoryPhotoSource.idOf({ mediaId: 'm-legacy', crop: FULL_FRAME })).toBe('m-legacy')
    expect(memoryPhotoSource.idOf({ source: { kind: 'upload', ref: UPLOAD_REF }, crop: FULL_FRAME })).toBeNull()
    expect(memoryPhotoSource.idOf(null)).toBeNull()
    expect(memoryPhotoSource.idOf('x')).toBeNull()
  })

  it('points at its hint in the Memory\'s own namespace, which core resolves without reading it', () => {
    expect(memoryPhotoSource.hintKey).toBe('memory.cover.galleryHint')
  })
})

// A Memory's cover chosen from its own photos is read here (`coverLoad.ts`, the kinds' `cover.load`); an UPLOADED cover is
// core's to open (`core/node/cover/coverStore.test.ts`) - inbox #411.
describe('loadMemoryPhotoCover', () => {
  const SCOPE = '22222222-2222-2222-2222-222222222222'
  const coverBytes = async (blob: Blob) => new Uint8Array(await blob.arrayBuffer())
  const ctx = (cover: unknown) => ({ scopeId: SCOPE, nodeKey, node: { id: NODE_ID, wrappedMeta: null }, cover, guest: null })

  it('a media-source cover resolves the chosen media’s thumbnail tier (listMedia + openGrid)', async () => {
    vi.mocked(listMedia).mockResolvedValue([mediaItem({ mediaId: 'm1' })])
    vi.mocked(openGrid).mockResolvedValue(new Uint8Array([9]))
    const loaded = await loadMemoryPhotoCover(ctx({ source: { kind: 'media', mediaId: 'm1' }, crop: FULL_FRAME }))
    expect(loaded?.crop).toEqual(FULL_FRAME)
    expect(await coverBytes(loaded!.blob)).toEqual(new Uint8Array([9])) // decrypted bytes, not yet an object URL
    expect(openGrid).toHaveBeenCalled()
  })

  it('reads no uploaded cover: that source is core’s, never this reader’s', async () => {
    expect(await loadMemoryPhotoCover(ctx({ source: { kind: 'upload', ref: UPLOAD_REF_RAW }, crop: FULL_FRAME }))).toBeNull()
    expect(listMedia).not.toHaveBeenCalled()
  })

  it('a dangling media source (the chosen media is gone) falls back to null (E2, never a broken tile)', async () => {
    vi.mocked(listMedia).mockResolvedValue([mediaItem({ mediaId: 'other' })])
    expect(await loadMemoryPhotoCover(ctx({ source: { kind: 'media', mediaId: 'm1' }, crop: FULL_FRAME }))).toBeNull()
  })

  it('a failing read is null too, and a node with no cover touches no transport', async () => {
    vi.mocked(listMedia).mockRejectedValue(new Error('offline'))
    expect(await loadMemoryPhotoCover(ctx({ source: { kind: 'media', mediaId: 'm1' }, crop: FULL_FRAME }))).toBeNull()
    vi.mocked(listMedia).mockClear()
    expect(await loadMemoryPhotoCover(ctx(undefined))).toBeNull()
    expect(listMedia).not.toHaveBeenCalled()
  })
})

describe('coverImageStyle', () => {
  it('no crop ⇒ a plain full-frame object-fit cover', () => {
    expect(coverImageStyle(null)).toEqual({ width: '100%', height: '100%', left: '0', top: '0', objectFit: 'cover' })
  })

  it('a full-frame crop ⇒ object-fit cover (centre-crop, never distorted) — same as no crop', () => {
    expect(coverImageStyle(FULL_FRAME)).toEqual({ width: '100%', height: '100%', left: '0', top: '0', objectFit: 'cover' })
  })

  it('a real crop ⇒ the inverse-scale + offset formula', () => {
    // show the 0.8×0.6 region at (0.1, 0.2): image is 125%×166.6…%, shifted by -12.5%×-33.3…%
    expect(coverImageStyle({ x: 0.1, y: 0.2, width: 0.8, height: 0.6 })).toEqual({
      width: `${100 / 0.8}%`,
      height: `${100 / 0.6}%`,
      left: `${(-0.1 / 0.8) * 100}%`,
      top: `${(-0.2 / 0.6) * 100}%`,
      objectFit: 'fill',
    })
  })
})
