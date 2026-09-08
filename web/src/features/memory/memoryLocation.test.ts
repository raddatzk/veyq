import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../api/client', () => ({
  contentApi: vi.fn(),
  loginApi: vi.fn(),
  tokenApi: vi.fn(),
  sessionApi: vi.fn(),
}))

import {
  computeMedoid,
  isLocationPending,
  mediaGpsPoints,
  normalizeLocation,
  sameMemoryLocation,
  setMemoryLocation,
  unsealMemoryLocation,
  type MemoryLocation,
} from './memoryLocation'
import { unsealMemoryDate, type MemoryDate } from './memoryDate'
import { sealNodeMeta } from './nodeMeta'
import type { MediaItem, MediaMeta } from './media'
import { contentApi } from '../../api/client'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'

const SCOPE_ID = '11111111-1111-1111-1111-111111111111'
const nodeKey = v1.randomBytes(32)

beforeEach(() => vi.clearAllMocks())

/** Build a MediaItem whose listed tier (incl. GPS) is sealed under `nodeKey` (mirrors media.ts's `sealMetadata`). */
function itemWith(tier: Partial<MediaMeta> | null): MediaItem {
  const base: MediaItem = {
    mediaId: 'm-' + Math.random().toString(36).slice(2),
    original: { blobId: 'b', chunkCount: 1, byteSize: 1, wrappedBlobKey: 'x' },
    derivatives: [],
  }
  if (!tier) return base
  const bytes = new TextEncoder().encode(JSON.stringify({ v: 1, ...tier }))
  base.wrappedMeta = b64std(v1.symWrap(nodeKey, bytes, 'media-meta', v1.randomBytes(12)))
  return base
}

describe('computeMedoid (min summed Haversine)', () => {
  it('resists a sea outlier the mean would pull into the ocean', () => {
    const a = { lat: 28.27, lng: -16.61 } // Tenerife
    const b = { lat: 28.28, lng: -16.6 } // Tenerife, next to a
    const c = { lat: 30, lng: -40 } // mid-Atlantic outlier
    // the mean is lat≈28.85, lng≈-24.4 — open ocean; the medoid stays on a real land point
    const medoid = computeMedoid([a, b, c])
    expect([a, b]).toContainEqual(medoid)
    expect(medoid).not.toEqual(c)
  })

  it('a single point is its own medoid', () => {
    expect(computeMedoid([{ lat: 10, lng: 20 }])).toEqual({ lat: 10, lng: 20 })
  })

  it('an empty set has no medoid', () => {
    expect(computeMedoid([])).toBeNull()
  })

  it('a tie resolves to the lowest index', () => {
    const a = { lat: 0, lng: 0 }
    const b = { lat: 1, lng: 1 } // two points: summed distance is equal → index 0 wins
    expect(computeMedoid([a, b])).toEqual(a)
  })
})

describe('mediaGpsPoints', () => {
  it('collects GPS from the listed tier, skipping items without one', () => {
    const items = [
      itemWith({ gpsLat: 28.27, gpsLng: -16.61 }),
      itemWith({ capturedAt: 1 }), // no GPS
      itemWith(null), // no tier
      itemWith({ gpsLat: 48.85, gpsLng: 2.35 }),
    ]
    expect(mediaGpsPoints(items, nodeKey)).toEqual([
      { lat: 28.27, lng: -16.61 },
      { lat: 48.85, lng: 2.35 },
    ])
  })
})

describe('normalizeLocation', () => {
  it('requires a non-empty name (no name ⇒ no location) except a pending auto place', () => {
    expect(normalizeLocation({ nameSource: 'manual', derivedMode: 'fixed' })).toBeNull() // fixed needs a name
    expect(normalizeLocation({ name: '   ', nameSource: 'manual' })).toBeNull() // manual needs a name
    expect(normalizeLocation({ name: '', nameSource: 'manual', derivedMode: 'auto' })).toBeNull() // auto+manual needs a name
    expect(normalizeLocation(null)).toBeNull()
    // a pending auto place (auto + geocoded, name deferred) IS a valid armed location
    expect(normalizeLocation({ nameSource: 'geocoded', derivedMode: 'auto' })).toEqual({
      name: '', nameSource: 'geocoded', derivedMode: 'auto', derived: undefined, nameAnchor: undefined,
    })
  })

  it('isLocationPending flags an armed-but-unnamed auto place', () => {
    expect(isLocationPending({ name: '', nameSource: 'geocoded', derivedMode: 'auto' })).toBe(true)
    expect(isLocationPending({ name: 'Teneriffa', nameSource: 'geocoded', derivedMode: 'auto' })).toBe(false)
    expect(isLocationPending(null)).toBe(false)
  })

  it('defaults nameSource→manual and derivedMode→auto, drops malformed derived/anchor', () => {
    expect(normalizeLocation({ name: 'X', derived: { lat: 'no' }, nameAnchor: { lat: 1, lng: 2 } })).toEqual({
      name: 'X',
      nameSource: 'manual',
      derivedMode: 'auto',
      derived: undefined,
      nameAnchor: undefined, // missing level → dropped
    })
  })

  it('keeps a full geocoded location', () => {
    const loc = {
      name: 'Teneriffa',
      nameSource: 'geocoded',
      derived: { lat: 28.27, lng: -16.61 },
      derivedMode: 'auto',
      nameAnchor: { lat: 28.27, lng: -16.61, level: 'city' },
    }
    expect(normalizeLocation(loc)).toEqual(loc)
  })
})

describe('unsealMemoryLocation', () => {
  it('round-trips a sealed location; best-effort null on wrong key / absent', () => {
    const loc: MemoryLocation = {
      name: 'Teneriffa',
      nameSource: 'geocoded',
      derived: { lat: 28.27, lng: -16.61 },
      derivedMode: 'auto',
      nameAnchor: { lat: 28.27, lng: -16.61, level: 'city' },
    }
    const sealed = sealNodeMeta(nodeKey, { v: 1, location: loc })
    expect(unsealMemoryLocation(nodeKey, { wrappedMeta: sealed })).toEqual(loc)
    expect(unsealMemoryLocation(v1.randomBytes(32), { wrappedMeta: sealed })).toBeNull() // wrong key
    expect(unsealMemoryLocation(nodeKey, { wrappedMeta: null })).toBeNull() // absent
  })

  it('is operator-blind — the sealed blob carries no plaintext name/coord', () => {
    const sealed = sealNodeMeta(nodeKey, {
      v: 1,
      location: { name: 'Teneriffa', nameSource: 'manual', derivedMode: 'fixed', derived: { lat: 28.27, lng: -16.61 } },
    })
    const plain = new TextDecoder().decode(fromB64std(sealed))
    expect(plain).not.toContain('Teneriffa')
    expect(plain).not.toContain('28.27')
  })
})

describe('setMemoryLocation — read-modify-write (preserves the date)', () => {
  const date: MemoryDate = { mode: 'single', from: '2026-05-01', to: '2026-05-01' }
  const loc: MemoryLocation = { name: 'Teneriffa', nameSource: 'manual', derivedMode: 'fixed', derived: { lat: 28.27, lng: -16.61 } }

  function mockUpdate() {
    const updateNode = vi.fn().mockResolvedValue(undefined)
    vi.mocked(contentApi).mockReturnValue({ updateNode } as unknown as ReturnType<typeof contentApi>)
    return updateNode
  }

  it('setting a location keeps the existing date', async () => {
    const updateNode = mockUpdate()
    const withDate = sealNodeMeta(nodeKey, { v: 1, date })
    const sealed = await setMemoryLocation(SCOPE_ID, 'n', nodeKey, loc, withDate)

    expect(updateNode.mock.calls[0][0].updateNodeRequest.wrappedMeta).toBe(sealed)
    expect(unsealMemoryLocation(nodeKey, { wrappedMeta: sealed })).toEqual(loc)
    expect(unsealMemoryDate(nodeKey, { wrappedMeta: sealed })).toEqual(date) // date survived
  })

  it('removing the location (null) keeps the date and drops only the place', async () => {
    mockUpdate()
    const withBoth = sealNodeMeta(nodeKey, { v: 1, date, location: loc })
    const sealed = await setMemoryLocation(SCOPE_ID, 'n', nodeKey, null, withBoth)

    expect(unsealMemoryLocation(nodeKey, { wrappedMeta: sealed })).toBeNull() // place gone
    expect(unsealMemoryDate(nodeKey, { wrappedMeta: sealed })).toEqual(date) // date intact
  })

  it('fixed → auto drops the frozen coord (re-medoid happens on the next open)', async () => {
    mockUpdate()
    const fixed: MemoryLocation = { name: 'Teneriffa', nameSource: 'geocoded', derivedMode: 'fixed', derived: { lat: 28.27, lng: -16.61 } }
    const withFixed = sealNodeMeta(nodeKey, { v: 1, location: fixed })
    // switching back to auto = writing the same name with derivedMode 'auto' and no frozen derived
    const asAuto: MemoryLocation = { name: fixed.name, nameSource: fixed.nameSource, derivedMode: 'auto' }
    const sealed = await setMemoryLocation(SCOPE_ID, 'n', nodeKey, asAuto, withFixed)

    const back = unsealMemoryLocation(nodeKey, { wrappedMeta: sealed })
    expect(back?.derivedMode).toBe('auto')
    expect(back?.derived).toBeUndefined() // frozen coord dropped
  })
})

describe('sameMemoryLocation', () => {
  const base: MemoryLocation = { name: 'X', nameSource: 'manual', derivedMode: 'auto', derived: { lat: 1, lng: 2 } }
  it('compares every field', () => {
    expect(sameMemoryLocation(base, { ...base })).toBe(true)
    expect(sameMemoryLocation(base, { ...base, derivedMode: 'fixed' })).toBe(false)
    expect(sameMemoryLocation(base, { ...base, derived: { lat: 1, lng: 3 } })).toBe(false)
    expect(sameMemoryLocation(null, null)).toBe(true)
    expect(sameMemoryLocation(base, null)).toBe(false)
  })
})
