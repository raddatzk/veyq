import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../api/client', () => ({
  contentApi: vi.fn(),
  loginApi: vi.fn(),
  tokenApi: vi.fn(),
  sessionApi: vi.fn(),
}))

import {
  sealMemoryDate,
  unsealMemoryDate,
  setMemoryDate,
  computeAutoRange,
  sameMemoryDate,
  unsealNodeDate,
  sortNodesByDate,
  type MemoryDate,
} from './memoryDate'
import type { MediaItem, MediaMeta } from './media'
import type { TreeNode } from './tree'
import { NodeKind } from '../../api/generated/content'
import { contentApi } from '../../api/client'
import * as v1 from '../../shared/crypto/v1'
import { b64std, fromB64std } from '../../shared/base64'
import { epochMsToIsoDate } from '../../shared/isoDate'

const SCOPE_ID = '11111111-1111-1111-1111-111111111111'
const nodeKey = v1.randomBytes(32)

beforeEach(() => vi.clearAllMocks())

/** Build a MediaItem whose listed-metadata tier is sealed under `nodeKey` (mirrors media.ts's `sealMetadata`). */
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

describe('sealMemoryDate / unsealMemoryDate', () => {
  const cases: MemoryDate[] = [
    { mode: 'auto', from: '2026-03-01', to: '2026-03-05' },
    { mode: 'single', from: '2026-06-15', to: '2026-06-15' },
    { mode: 'range', from: '2026-06-01', to: '2026-06-30' },
    { mode: 'ongoing', from: '2026-01-01' },
  ]

  it.each(cases)('round-trips %o under K_mem', (date) => {
    const sealed = sealMemoryDate(nodeKey, date)
    expect(unsealMemoryDate(nodeKey, { wrappedMeta: sealed })).toEqual(date)
  })

  it('is operator-blind — the sealed blob carries no plaintext day', () => {
    const sealed = sealMemoryDate(nodeKey, { mode: 'single', from: '2026-06-15', to: '2026-06-15' })
    expect(new TextDecoder().decode(fromB64std(sealed))).not.toContain('2026-06-15')
  })

  it('returns null for an absent, wrong-key, or malformed blob (E3 → falls back to auto)', () => {
    expect(unsealMemoryDate(nodeKey, { wrappedMeta: null })).toBeNull()
    const sealed = sealMemoryDate(nodeKey, { mode: 'single', from: '2026-06-15', to: '2026-06-15' })
    expect(unsealMemoryDate(v1.randomBytes(32), { wrappedMeta: sealed })).toBeNull() // wrong key
    expect(unsealMemoryDate(nodeKey, { wrappedMeta: 'not-base64!!' })).toBeNull()
    // an unrecognized mode is rejected as no manual date
    const badMode = b64std(v1.symWrap(nodeKey, new TextEncoder().encode('{"v":1,"date":{"mode":"weird"}}'), 'node-meta', v1.randomBytes(12)))
    expect(unsealMemoryDate(nodeKey, { wrappedMeta: badMode })).toBeNull()
  })
})

describe('setMemoryDate', () => {
  it('re-seals under K_mem and PATCHes wrappedMeta via updateNode', async () => {
    const updateNode = vi.fn().mockResolvedValue(undefined)
    vi.mocked(contentApi).mockReturnValue({ updateNode } as unknown as ReturnType<typeof contentApi>)

    const date: MemoryDate = { mode: 'range', from: '2026-05-01', to: '2026-05-09' }
    const sealed = await setMemoryDate(SCOPE_ID, 'node-9', nodeKey, date, null)

    const req = updateNode.mock.calls[0][0]
    expect(req.scopeId).toBe(SCOPE_ID)
    expect(req.nodeId).toBe('node-9')
    expect(req.updateNodeRequest.wrappedMeta).toBe(sealed)
    // what we sealed opens back to the date under K_mem (operator-blind round-trip)
    expect(unsealMemoryDate(nodeKey, { wrappedMeta: sealed })).toEqual(date)
  })

  it('read-modify-write: setting a date preserves a sibling location in the same blob (cr-0069)', async () => {
    const updateNode = vi.fn().mockResolvedValue(undefined)
    vi.mocked(contentApi).mockReturnValue({ updateNode } as unknown as ReturnType<typeof contentApi>)

    // a blob that already carries a location (sealed as the RMW envelope would)
    const withLocation = b64std(
      v1.symWrap(
        nodeKey,
        new TextEncoder().encode(JSON.stringify({ v: 1, location: { name: 'Teneriffa', nameSource: 'manual', derivedMode: 'fixed' } })),
        'node-meta',
        v1.randomBytes(12),
      ),
    )
    const date: MemoryDate = { mode: 'single', from: '2026-05-01', to: '2026-05-01' }
    const sealed = await setMemoryDate(SCOPE_ID, 'node-9', nodeKey, date, withLocation)

    // the date landed AND the location survived the re-seal
    expect(unsealMemoryDate(nodeKey, { wrappedMeta: sealed })).toEqual(date)
    const blob = JSON.parse(new TextDecoder().decode(v1.symUnwrap(nodeKey, fromB64std(sealed), 'node-meta')))
    expect(blob.location).toEqual({ name: 'Teneriffa', nameSource: 'manual', derivedMode: 'fixed' })
  })
})

describe('computeAutoRange', () => {
  it('min→from, max→to (day-truncated) over trustworthy media only, excluding file fallbacks', () => {
    const day = (iso: string) => new Date(iso + 'T12:00:00').getTime()
    const items = [
      itemWith({ capturedAt: day('2026-03-10'), capturedAtSource: 'exif' }), // trustworthy
      itemWith({ capturedAt: day('2026-03-02'), capturedAtSource: 'exif' }), // trustworthy — the earliest
      itemWith({ capturedAt: day('2020-01-01'), capturedAtSource: 'file' }), // file fallback — EXCLUDED
      itemWith({ capturedAt: day('2030-01-01') }), // legacy, no source — EXCLUDED
      itemWith({ capturedAt: day('2019-01-01'), capturedAtSource: 'file', capturedAtOverride: day('2026-03-20') }), // override → trustworthy, the latest
      itemWith(null), // no tier
    ]
    expect(computeAutoRange(items, nodeKey)).toEqual({
      mode: 'auto',
      from: epochMsToIsoDate(day('2026-03-02')),
      to: epochMsToIsoDate(day('2026-03-20')),
    })
  })

  it('is empty auto when no trustworthy dated media (only file fallbacks / none)', () => {
    const items = [itemWith({ capturedAt: Date.now(), capturedAtSource: 'file' }), itemWith(null)]
    expect(computeAutoRange(items, nodeKey)).toEqual({ mode: 'auto' })
  })
})

describe('unsealNodeDate / sortNodesByDate (browse card grid, cr-0067)', () => {
  const scopeKey = v1.randomBytes(32)

  /** A top-level Memory node: a fresh K_mem wrapped under `scopeKey`, its date sealed under that K_mem. */
  function memoryNode(id: string, date: MemoryDate | null): TreeNode {
    const memKey = v1.randomBytes(32)
    const wrappedContentKey = b64std(v1.symWrap(scopeKey, memKey, 'content-key', v1.randomBytes(12)))
    return {
      id,
      parentId: null,
      kind: NodeKind.Memory,
      orderIndex: 0,
      epoch: 0,
      name: id,
      wrappedContentKey,
      wrappedMeta: date ? sealMemoryDate(memKey, date) : null,
    }
  }

  it('unseals a node’s own date by opening its K_mem under the parent key', () => {
    const n = memoryNode('m', { mode: 'single', from: '2026-06-15', to: '2026-06-15' })
    expect(unsealNodeDate(scopeKey, n)).toEqual({ mode: 'single', from: '2026-06-15', to: '2026-06-15' })
    expect(unsealNodeDate(null, n)).toBeNull() // no key (locked) → no date
    expect(unsealNodeDate(v1.randomBytes(32), n)).toBeNull() // wrong parent key → best-effort null, no throw
  })

  it('orders a mixed grid by start date — newest first, undated last (stable)', () => {
    const a = memoryNode('a', { mode: 'range', from: '2026-03-01', to: '2026-03-10' })
    const b = memoryNode('b', { mode: 'single', from: '2026-08-01', to: '2026-08-01' })
    const c = memoryNode('c', null) // undated
    const d = memoryNode('d', { mode: 'ongoing', from: '2026-05-01' })
    const e = memoryNode('e', null) // undated — keeps input order among undated
    const ordered = sortNodesByDate(() => scopeKey, [a, b, c, d, e]).map((x) => x.node.id)
    expect(ordered).toEqual(['b', 'd', 'a', 'c', 'e'])
  })
})

describe('sameMemoryDate', () => {
  it('compares mode + both ends', () => {
    expect(sameMemoryDate({ mode: 'auto', from: 'a', to: 'b' }, { mode: 'auto', from: 'a', to: 'b' })).toBe(true)
    expect(sameMemoryDate({ mode: 'auto', from: 'a' }, { mode: 'auto', from: 'a', to: 'b' })).toBe(false)
    expect(sameMemoryDate(null, null)).toBe(true)
    expect(sameMemoryDate(null, { mode: 'auto' })).toBe(false)
  })
})
