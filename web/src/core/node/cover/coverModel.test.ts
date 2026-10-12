import { describe, it, expect } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import { COVER_ROLE, FULL_FRAME, coverImageStyle, normalizeCoverBlobRef, normalizeCoverCrop, readCover, uploadBlobIdOf, uploadedCover } from './coverModel'
import { unsealNodeMeta } from '../nodeMeta'

// A node's cover (inbox #411) in the shape a Memory's uploaded cover has ALWAYS been sealed in. These pin that the core
// reader opens every cover already stored, and that the core writer produces the very bytes the old Memories writer did.

const SCOPE = '11111111-1111-4111-8111-111111111111'
const NODE = '22222222-2222-4222-8222-222222222222'
const OTHER = '33333333-3333-4333-8333-333333333333'
const key = v1.randomBytes(32)

// The legacy cover exactly as `memoryCover.ts` sealed it before #411 (cr-0073): one `JSON.stringify` of this literal.
const LEGACY_REF = { blobId: 'b-1', chunkCount: 2, byteSize: 262_300, wrappedBlobKey: 'd3JhcA==', ownerId: NODE, role: 'COVER' }
const LEGACY_COVER = { source: { kind: 'upload', ref: LEGACY_REF }, crop: { x: 0.1, y: 0.2, width: 0.5, height: 0.28 } }
const sealMeta = (blob: object, nodeId = NODE) => b64std(v1.seal({ purpose: 'node-meta', scopeId: SCOPE, nodeId }, key, new TextEncoder().encode(JSON.stringify(blob))))

describe('a Memory cover stored before the cover became core\'s', () => {
  it('opens, from the meta a Memory sealed, as the node\'s uploaded cover with its crop', () => {
    const wrappedMeta = sealMeta({ v: 1, date: { mode: 'single', from: '2019-07-01', to: '2019-07-01' }, cover: LEGACY_COVER })
    const raw = unsealNodeMeta(key, { scopeId: SCOPE, nodeId: NODE }, wrappedMeta).cover
    expect(readCover(raw, NODE)).toEqual({ source: { kind: 'upload', ref: LEGACY_REF }, crop: LEGACY_COVER.crop })
    expect(uploadBlobIdOf(raw)).toBe('b-1')
  })

  it('is written byte for byte as it was: the same keys in the same order', () => {
    const written = uploadedCover({ blobId: 'b-1', chunkCount: 2, byteSize: 262_300, wrappedBlobKey: 'd3JhcA==', ownerId: NODE }, LEGACY_COVER.crop)
    expect(JSON.stringify(written)).toBe(JSON.stringify(LEGACY_COVER))
    expect(written.source.ref.role).toBe(COVER_ROLE)
  })

  it('the blob\'s owner is the node that names it, never what the sealed payload says (ADR 0072)', () => {
    const copied = { source: { kind: 'upload', ref: { ...LEGACY_REF, ownerId: OTHER } }, crop: FULL_FRAME }
    const read = readCover(copied, NODE)
    expect(read?.source.kind === 'upload' && read.source.ref.ownerId).toBe(NODE)
  })
})

describe('readCover', () => {
  it('a source core cannot open is carried as "other" - the kind\'s own reader opens it - with its crop', () => {
    expect(readCover({ source: { kind: 'media', mediaId: 'm' }, crop: { x: 0, y: 0, width: 0.5, height: 0.5 } }, NODE))
      .toEqual({ source: { kind: 'other' }, crop: { x: 0, y: 0, width: 0.5, height: 0.5 } })
    expect(readCover({ mediaId: 'm' }, NODE)?.source).toEqual({ kind: 'other' }) // the cr-0072 flat shape too
  })

  it('no cover, a non-object, or an upload whose ref is incomplete, is none - never a throw', () => {
    for (const raw of [undefined, null, 'x', 7, []]) expect(readCover(raw, NODE), String(raw)).toBeNull()
    expect(readCover({ source: { kind: 'upload', ref: { blobId: 'b' } }, crop: FULL_FRAME }, NODE)).toBeNull()
    expect(readCover({ source: { kind: 'upload' }, crop: FULL_FRAME }, NODE)).toBeNull()
  })

  it('a malformed crop is the full frame, an off-image one is pulled back on it', () => {
    expect(readCover({ source: { kind: 'other' }, crop: 'nope' }, NODE)?.crop).toEqual(FULL_FRAME)
    expect(normalizeCoverCrop({ x: 0.9, y: 0.5, width: 0.5, height: 0.5 })).toEqual({ x: 0.5, y: 0.5, width: 0.5, height: 0.5 })
  })
})

describe('uploadBlobIdOf', () => {
  it('names the compare value of a write: the blob an uploaded cover holds, else none', () => {
    expect(uploadBlobIdOf(LEGACY_COVER)).toBe('b-1')
    expect(uploadBlobIdOf({ source: { kind: 'media', mediaId: 'm' }, crop: FULL_FRAME })).toBeNull()
    expect(uploadBlobIdOf({ source: { kind: 'upload', ref: {} } })).toBeNull()
    expect(uploadBlobIdOf(null)).toBeNull()
  })
})

describe('normalizeCoverBlobRef / coverImageStyle', () => {
  it('refuses a ref with a field off', () => {
    expect(normalizeCoverBlobRef({ ...LEGACY_REF, wrappedBlobKey: '' }, NODE)).toBeNull()
    expect(normalizeCoverBlobRef({ ...LEGACY_REF, chunkCount: Number.NaN }, NODE)).toBeNull()
  })

  it('a full frame is a plain centre-crop, a crop an exact expansion - at any container size', () => {
    expect(coverImageStyle(FULL_FRAME).objectFit).toBe('cover')
    expect(coverImageStyle(undefined).objectFit).toBe('cover')
    expect(coverImageStyle({ x: 0.25, y: 0, width: 0.5, height: 1 })).toEqual({ width: '200%', height: '100%', left: '-50%', top: '0%', objectFit: 'fill' })
  })
})
