import { describe, it, expect } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { planBlob } from '../../../core/blob/transport'
import { sealNodeMeta, unsealNodeMeta } from '../../../core/node/nodeMeta'
import { uploadedCover } from '../../../core/node/cover/coverModel'
import { MAX_PARTICIPANTS } from '../memoryParticipants'
import { MAX_PLACE_NAME, normalizeLocation } from '../memoryLocation'
import {
  BANNER_POSTER_ROLE,
  BANNER_ROLE,
  CENTRE,
  DEFAULT_VIDEO_TYPE,
  bannerBlobIds,
  bannerStyle,
  defaultFrame,
  isPortrait,
  normalizeBanner,
  normalizeFocal,
  reframedBanner,
  sealedBanner,
} from './bannerModel'

const NODE = '4a6e8b2c-1f2d-3e4a-5b6c-7c3f0e2a9b1d'
const ref = (blobId: string) => ({ blobId, chunkCount: 2, byteSize: 1234, wrappedBlobKey: 'd3JhcA==' })
/** A SHA-256 as `sha256` carries it (base64url, 43 characters): the digest of 'abc'. */
const DIGEST = 'ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0'

describe('normalizeBanner', () => {
  it('reads an image banner, its ref opening on the node in the BANNER role', () => {
    const b = normalizeBanner({ medium: 'image', blob: ref('b1'), frame: 'fill', focal: { x: 0.25, y: 0.75 }, alt: '  Sunset  ' }, NODE)
    expect(b).toEqual({
      medium: 'image',
      blob: { ...ref('b1'), ownerId: NODE, role: BANNER_ROLE },
      frame: 'fill',
      focal: { x: 0.25, y: 0.75 },
    }) // an older client's description is read by nobody
  })

  it('reads a video banner with its poster in the BANNER_POSTER role, its type and its file\'s digest', () => {
    const b = normalizeBanner(
      { medium: 'video', blob: ref('v'), poster: ref('p'), type: 'video/quicktime', sha256: DIGEST, origin: { mediaId: 'm1' }, frame: 'fit', focal: CENTRE },
      NODE,
    )
    expect(b?.poster).toEqual({ ...ref('p'), ownerId: NODE, role: BANNER_POSTER_ROLE })
    expect(b?.type).toBe('video/quicktime')
    expect(b?.sha256).toBe(DIGEST)
    expect(b).not.toHaveProperty('origin') // an older client's origin is read by nobody
    expect(b?.frame).toBe('fit')
  })

  it('a malformed digest is none', () => {
    for (const sha256 of ['abc', DIGEST + 'A', DIGEST.slice(0, 42) + 'B', 42]) {
      expect(normalizeBanner({ medium: 'video', blob: ref('v'), sha256 }, NODE)?.sha256).toBeUndefined()
    }
  })

  it('takes owner and role from where it is read, never from the payload (ADR 0072)', () => {
    const b = normalizeBanner({ medium: 'image', blob: { ...ref('b1'), ownerId: 'other', role: 'ORIGINAL' } }, NODE)
    expect(b?.blob.ownerId).toBe(NODE)
    expect(b?.blob.role).toBe(BANNER_ROLE)
  })

  it('degrades, never throws: a malformed focal point is the centre, an unknown frame fills, a bad type is mp4', () => {
    const b = normalizeBanner({ medium: 'video', blob: ref('v'), frame: 'stretch', focal: { x: 2, y: 'a' }, type: 'text/html' }, NODE)
    expect(b?.focal).toEqual(CENTRE)
    expect(b?.frame).toBe('fill')
    expect(b?.type).toBe(DEFAULT_VIDEO_TYPE)
    expect(b?.poster).toBeUndefined() // a video whose poster is gone still plays
  })

  it('an unknown shape is no banner', () => {
    for (const raw of [null, 'x', [], {}, { medium: 'audio', blob: ref('a') }, { medium: 'image' }, { medium: 'image', blob: { blobId: 'x' } }]) {
      expect(normalizeBanner(raw, NODE)).toBeNull()
    }
  })

  it('an image never reads a poster', () => {
    const b = normalizeBanner({ medium: 'image', blob: ref('b'), poster: ref('p') }, NODE)
    expect(b?.poster).toBeUndefined()
    expect(b?.type).toBeUndefined()
  })
})

describe('focal point and frame', () => {
  it('normalizeFocal keeps a point on the medium', () => {
    expect(normalizeFocal({ x: 0, y: 1 })).toEqual({ x: 0, y: 1 })
    expect(normalizeFocal({ x: -0.1, y: 0.5 })).toEqual(CENTRE)
    expect(normalizeFocal(undefined)).toEqual(CENTRE)
  })

  // Bite 6: swap x and y in `bannerStyle` and the asymmetric point's object-position goes red.
  it('bannerStyle puts the focal point first across, then down', () => {
    expect(bannerStyle({ x: 0.2, y: 0.85 })).toEqual({ objectPosition: '20% 85%' })
    expect(bannerStyle({ x: 1 / 3, y: 0 })).toEqual({ objectPosition: '33.33% 0%' })
  })

  it('a portrait medium fits by default, a landscape or square one fills', () => {
    expect(defaultFrame(1080, 1920)).toBe('fit')
    expect(defaultFrame(1920, 1080)).toBe('fill')
    expect(defaultFrame(1000, 1000)).toBe('fill')
    expect(isPortrait(1000, 1000)).toBe(false)
  })
})

describe('sealedBanner / reframedBanner / bannerBlobIds', () => {
  it('seals only the four fields of a ref, a poster and type only for a video, the digest only when well-formed', () => {
    const plan = { ...ref('b'), ownerId: NODE, role: BANNER_ROLE, wrappedBlobMeta: 'bWV0YQ==' }
    expect(sealedBanner({ medium: 'image', blob: plan, poster: ref('p'), framing: { frame: 'fill', focal: { x: 0.123456, y: 1 } } })).toEqual({
      medium: 'image',
      blob: ref('b'),
      frame: 'fill',
      focal: { x: 0.1235, y: 1 },
    })
    expect(
      sealedBanner({ medium: 'video', blob: ref('v'), poster: ref('p'), type: 'video/webm', sha256: DIGEST, framing: { frame: 'fit', focal: CENTRE } }),
    ).toEqual({ medium: 'video', blob: ref('v'), poster: ref('p'), type: 'video/webm', sha256: DIGEST, frame: 'fit', focal: CENTRE })
    expect(sealedBanner({ medium: 'image', blob: ref('b'), sha256: 'not-a-digest', framing: { frame: 'fill', focal: CENTRE } })).not.toHaveProperty('sha256')
  })

  it('a re-frame keeps the bytes and every other field as stored (an older description and origin included)', () => {
    const stored = { medium: 'image', blob: ref('b'), sha256: DIGEST, frame: 'fill', focal: CENTRE, alt: 'old', origin: { mediaId: 'm' }, future: 1 }
    expect(reframedBanner(stored, { frame: 'fit', focal: { x: 0.1, y: 0.2 } })).toEqual({
      medium: 'image', blob: ref('b'), sha256: DIGEST, frame: 'fit', focal: { x: 0.1, y: 0.2 }, alt: 'old', origin: { mediaId: 'm' }, future: 1,
    })
    expect(reframedBanner(null, { frame: 'fill', focal: CENTRE })).toBeNull()
  })

  it('bannerBlobIds reads the compare values from the raw value', () => {
    expect(bannerBlobIds({ blob: { blobId: 'b' }, poster: { blobId: 'p' } })).toEqual({ blobId: 'b', posterBlobId: 'p' })
    expect(bannerBlobIds(undefined)).toEqual({ blobId: null, posterBlobId: null })
  })
})

/**
 * The meta budget is measured, not assumed. The node's `wrappedMeta` is one seal capped at 8192 base64 characters
 * (`content.openapi.yaml`, and `memory-banner.openapi.yaml` the same), shared with everything else a Memory seals there.
 * The worst case: 100 participants, an uploaded cover, a place with every field and its name at its bound in 4-byte
 * characters (the one field a member types without another bound - `MAX_PLACE_NAME`), a range date, and a video banner
 * with its poster, a type and its file's digest. Bite: lift the place name's bound (`boundPlaceName`) to 1000 and the
 * clamp below, then the seal, go red.
 */
describe('the meta budget', () => {
  it('a place name is cut to its bound in code points', () => {
    const name = '😀'.repeat(MAX_PLACE_NAME + 30)
    expect(Array.from(normalizeLocation({ name, nameSource: 'manual' })!.name)).toHaveLength(MAX_PLACE_NAME)
  })

  it('the worst case seals under maxLength 8192', () => {
    const key = v1.randomBytes(32)
    const address = { scopeId: crypto.randomUUID(), nodeId: crypto.randomUUID() }
    const plan = (role: string) => planBlob(address.scopeId, { ownerId: address.nodeId, role }, key, new Blob([new Uint8Array(10)]))
    const participants = Array.from({ length: MAX_PARTICIPANTS }, () => crypto.randomUUID())
    const meta = {
      v: 1,
      date: { mode: 'range', from: '2024-07-01', to: '2024-07-31' },
      location: {
        name: normalizeLocation({ name: '😀'.repeat(MAX_PLACE_NAME * 5), nameSource: 'manual' })!.name,
        nameSource: 'geocoded',
        derived: { lat: 52.51234567891234, lng: 13.43219876543219 },
        derivedMode: 'auto',
        nameAnchor: { lat: 52.51234567891234, lng: 13.43219876543219, level: 'road' },
      },
      participants: { userIds: participants },
      cover: uploadedCover(plan('COVER'), { x: 0.123456789, y: 0.2, width: 0.5, height: 0.28125 }),
      banner: sealedBanner({
        medium: 'video',
        blob: plan(BANNER_ROLE),
        poster: plan(BANNER_POSTER_ROLE),
        type: 'video/quicktime',
        sha256: DIGEST,
        framing: { frame: 'fit', focal: { x: 0.33333, y: 0.66666 } },
      }),
    }
    const sealed = sealNodeMeta(key, address, meta)
    expect(sealed.length).toBeLessThanOrEqual(8192)
    expect(unsealNodeMeta(key, address, sealed).banner).toEqual(meta.banner) // and it opens back whole
  })
})
