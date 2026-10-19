import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../api/client', () => ({
  contentApi: vi.fn(),
  loginApi: vi.fn(),
  tokenApi: vi.fn(),
  sessionApi: vi.fn(),
}))


import {
  normalizeCoverCrop,
  normalizeMemoryCover,
  unsealMemoryCover,
  coverImageStyle,
  FULL_FRAME,
  type MemoryCover,
} from './memoryCover'
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
  it('an older client\'s photo reference - `{ kind: \'media\' }` or the flat `{ mediaId }` - is no cover: the placeholder', () => {
    expect(normalizeMemoryCover({ source: { kind: 'media', mediaId: 'm1' }, crop: FULL_FRAME }, NODE_ID)).toBeNull()
    expect(normalizeMemoryCover({ mediaId: 'legacy', crop: FULL_FRAME }, NODE_ID)).toBeNull()
  })

  it('normalizes an upload source (a standalone cover blob ref); a missing crop is the full frame', () => {
    expect(normalizeMemoryCover({ source: { kind: 'upload', ref: UPLOAD_REF_RAW }, crop: FULL_FRAME }, NODE_ID)).toEqual({
      source: { kind: 'upload', ref: UPLOAD_REF },
      crop: FULL_FRAME,
    })
    expect(normalizeMemoryCover({ source: { kind: 'upload', ref: UPLOAD_REF_RAW } }, NODE_ID)?.crop).toEqual(FULL_FRAME)
  })

  it('takes an upload ref\'s owner + role from the addressed node, never from the sealed payload', () => {
    // a ref that claims another owner/role (e.g. copied from a media) is re-anchored to this node's COVER slot,
    // so its K_blob wrap — bound to where it was really sealed — refuses to open here instead of being trusted
    const claimed = { ...UPLOAD_REF_RAW, ownerId: OTHER_NODE_ID, role: 'ORIGINAL' }
    expect(normalizeMemoryCover({ source: { kind: 'upload', ref: claimed } }, NODE_ID)?.source).toEqual({ kind: 'upload', ref: UPLOAD_REF })
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
  it('round-trips an upload cover (standalone blob ref) sealed under K_node', () => {
    const cover: MemoryCover = { source: { kind: 'upload', ref: UPLOAD_REF }, crop: FULL_FRAME }
    const sealed = sealMeta({ v: 1, cover: { source: { kind: 'upload', ref: UPLOAD_REF_RAW }, crop: FULL_FRAME } })
    expect(unsealMemoryCover(SCOPE_ID, nodeKey, { id: NODE_ID, wrappedMeta: sealed })).toEqual(cover)
  })

  it('is best-effort null for absent / wrong-key / another node\'s / malformed meta (E3/E4)', () => {
    expect(unsealMemoryCover(SCOPE_ID, nodeKey, { id: NODE_ID, wrappedMeta: null })).toBeNull()
    const sealed = sealMeta({ v: 1, cover: { source: { kind: 'upload', ref: UPLOAD_REF_RAW }, crop: FULL_FRAME } })
    expect(unsealMemoryCover(SCOPE_ID, nodeKey, { id: NODE_ID, wrappedMeta: sealed })).not.toBeNull() // control
    expect(unsealMemoryCover(SCOPE_ID, v1.randomBytes(32), { id: NODE_ID, wrappedMeta: sealed })).toBeNull() // wrong key
    // the blob served on another node's row does not open there (ADR 0072)
    expect(unsealMemoryCover(SCOPE_ID, nodeKey, { id: OTHER_NODE_ID, wrappedMeta: sealed })).toBeNull()
    expect(unsealMemoryCover(SCOPE_ID, nodeKey, { id: NODE_ID, wrappedMeta: 'not-base64!!' })).toBeNull()
    expect(unsealMemoryCover(SCOPE_ID, nodeKey, { id: NODE_ID, wrappedMeta: sealMeta({ v: 1 }) })).toBeNull() // no cover sub-object
  })

  it('is operator-blind — the sealed blob carries no plaintext blob id', () => {
    const sealed = sealMeta({ v: 1, cover: { source: { kind: 'upload', ref: { ...UPLOAD_REF_RAW, blobId: 'secret-blob-id' } }, crop: FULL_FRAME } })
    expect(new TextDecoder().decode(fromB64std(sealed))).not.toContain('secret-blob-id')
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
