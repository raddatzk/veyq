// What Memories hands core's search (ADR 0125 decision 6): a Memory's facets from its sealed meta, and the open Memory's media
// as documents from the opened listing (their capture time and place as `openedMeta` holds them).
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('./media', async (orig) => ({ ...(await orig<typeof import('./media')>()), originalBlobMeta: vi.fn(), unsealMeta: vi.fn() }))

import * as v1 from '@veyq/crypto/v1'
import { sealNodeMeta } from '../../core/node/nodeMeta'
import { originalBlobMeta, unsealMeta } from './media'
import { closeListing } from './openedListing'
import { mediaDocuments, memorySearchFacets, monthsOfDate, PHOTO_DOCUMENT, VIDEO_DOCUMENT, yearsOfDate } from './search'
import { mediaItem, treeNode } from '../../test/models'

const SCOPE = '00000000-0000-4000-8000-000000000101'
const NODE = '00000000-0000-4000-8000-000000000011'

describe('a Memory\'s facets', () => {
  const key = v1.randomBytes(32)
  const facetsOf = (meta: Record<string, unknown>) =>
    memorySearchFacets({
      scopeId: SCOPE, nodeKey: key, parentKey: new Uint8Array(32),
      node: treeNode({ id: NODE, wrappedMeta: sealNodeMeta(key, { scopeId: SCOPE, nodeId: NODE }, { v: 1, ...meta }) }),
    })

  it('reads the date range as years and as months, the place\'s name and the participants\' ids from the sealed meta', () => {
    expect(facetsOf({
      date: { mode: 'range', from: '2023-12-30', to: '2024-01-02' },
      location: { name: ' Rom ', nameSource: 'manual', derivedMode: 'fixed' },
      participants: { userIds: ['aaaaaaaa-0000-4000-8000-000000000001'] },
    })).toEqual({
      years: { from: 2023, to: 2024 }, months: { from: '2023-12', to: '2024-01' }, places: ['Rom'], people: ['aaaaaaaa-0000-4000-8000-000000000001'],
    })
  })

  it('leaves out what the Memory does not have', () => {
    expect(facetsOf({})).toEqual({})
    expect(facetsOf({ date: { mode: 'auto' } })).toEqual({})
  })

  it('reads nothing under a key the meta was not sealed with', () => {
    expect(memorySearchFacets({
      scopeId: SCOPE, nodeKey: v1.randomBytes(32), parentKey: new Uint8Array(32),
      node: treeNode({ id: NODE, wrappedMeta: sealNodeMeta(key, { scopeId: SCOPE, nodeId: NODE }, { v: 1, date: { mode: 'single', from: '2024-05-01', to: '2024-05-01' } }) }),
    })).toEqual({})
  })
})

describe('yearsOfDate', () => {
  const now = new Date(2026, 10, 8)
  it('spans from the start day\'s year to the end day\'s', () => {
    expect(yearsOfDate({ mode: 'single', from: '2024-05-01', to: '2024-05-01' }, now)).toEqual({ from: 2024, to: 2024 })
    expect(yearsOfDate({ mode: 'range', from: '2019-07-01', to: '2021-01-01' }, now)).toEqual({ from: 2019, to: 2021 })
  })
  it('an ongoing Memory reaches this year', () => {
    expect(yearsOfDate({ mode: 'ongoing', from: '2022-03-01' }, now)).toEqual({ from: 2022, to: 2026 })
  })
  it('none without a start day', () => {
    expect(yearsOfDate({ mode: 'auto' }, now)).toBeNull()
    expect(yearsOfDate(null, now)).toBeNull()
  })
})

describe('monthsOfDate (the month chip, cr-search-polish)', () => {
  const now = new Date(2026, 10, 8)
  it('spans from the start day\'s month to the end day\'s, across a year\'s end', () => {
    expect(monthsOfDate({ mode: 'single', from: '2024-05-31', to: '2024-05-31' }, now)).toEqual({ from: '2024-05', to: '2024-05' })
    expect(monthsOfDate({ mode: 'range', from: '2023-12-30', to: '2024-01-02' }, now)).toEqual({ from: '2023-12', to: '2024-01' })
  })
  it('an ongoing Memory reaches this month', () => {
    expect(monthsOfDate({ mode: 'ongoing', from: '2022-03-01' }, now)).toEqual({ from: '2022-03', to: '2026-11' })
  })
  it('none without a start day: an undated Memory matches no month', () => {
    expect(monthsOfDate({ mode: 'auto' }, now)).toBeNull()
    expect(monthsOfDate(null, now)).toBeNull()
  })
})

describe('the open Memory\'s media as documents', () => {
  const KEY = new Uint8Array(32).fill(3)
  const taken = new Date(2021, 6, 14).getTime()
  beforeEach(() => {
    vi.clearAllMocks()
    closeListing(SCOPE, KEY)
    vi.mocked(unsealMeta).mockImplementation((_s, _k, m) => (m.mediaId === 'p1' ? { v: 1, capturedAt: taken, capturedAtOverride: taken + 1, placeName: 'Roma' } : null))
    vi.mocked(originalBlobMeta).mockImplementation((_s, _k, m) => (m.mediaId === 'p1' ? { v: 1, type: 'image/jpeg', name: 'IMG_1.jpg' } : { v: 1, type: 'video/mp4' }))
  })

  it('carries each one\'s name, capture time (the override first) and place from the opened listing', () => {
    const open = vi.fn()
    const docs = mediaDocuments({
      scopeId: SCOPE, key: KEY, open, unnamed: (video) => (video ? 'Video' : 'Photo'),
      items: [mediaItem({ mediaId: 'p1', wrappedMeta: 's1', metaRevision: 2 }), mediaItem({ mediaId: 'v1', wrappedMeta: 's2' })],
    })
    expect(docs.map(({ open: _o, ...d }) => d)).toEqual([
      { kind: PHOTO_DOCUMENT, id: 'p1', revision: '2:blob-p1', name: 'IMG_1.jpg', facets: { capturedAt: taken + 1, places: ['Roma'] }, icon: 'image' },
      { kind: VIDEO_DOCUMENT, id: 'v1', revision: '0:blob-v1', name: 'Video', facets: {}, icon: 'play' },
    ])
    docs[1].open()
    expect(open).toHaveBeenCalledWith('v1')
  })

  it('reads each seal once, as the gallery does (the listing\'s memo)', () => {
    const items = [mediaItem({ mediaId: 'p1', wrappedMeta: 's1' })]
    const input = { scopeId: SCOPE, key: KEY, open: () => {}, unnamed: () => 'Photo', items }
    mediaDocuments(input)
    mediaDocuments(input)
    expect(vi.mocked(unsealMeta)).toHaveBeenCalledTimes(1)
  })
})
