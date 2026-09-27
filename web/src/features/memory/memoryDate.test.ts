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
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { epochMsToIsoDate } from '../../shared/isoDate'

const SCOPE_ID = '11111111-1111-1111-1111-111111111111'
const nodeKey = v1.randomBytes(32)
// Every seal binds its node / media (ADR 0072): the node a date blob belongs to, and the one it does not.
const NODE_ID = '5c09e000-0000-4000-8000-0000000000d1'
const OTHER_NODE_ID = '5c09e000-0000-4000-8000-0000000000d2'
const NODE = { scopeId: SCOPE_ID, nodeId: NODE_ID }
const metaCtx = (nodeId: string) => ({ purpose: 'node-meta', scopeId: SCOPE_ID, nodeId }) as const
const sealMetaJson = (json: string, nodeId = NODE_ID) =>
  b64std(v1.seal(metaCtx(nodeId), nodeKey, new TextEncoder().encode(json)))

beforeEach(() => vi.clearAllMocks())

/** Build a MediaItem whose listed-metadata tier is sealed under `nodeKey` (mirrors media.ts's `sealMetadata`). */
function itemWith(tier: Partial<MediaMeta> | null): MediaItem {
  const base: MediaItem = {
    mediaId: crypto.randomUUID(),
    original: { blobId: 'b', chunkCount: 1, byteSize: 1, wrappedBlobKey: 'x', ownerId: 'm', role: 'ORIGINAL' },
    derivatives: [],
  }
  base.original.ownerId = base.mediaId
  if (!tier) return base
  const bytes = new TextEncoder().encode(JSON.stringify({ v: 1, ...tier }))
  base.wrappedMeta = b64std(v1.seal({ purpose: 'media-meta', scopeId: SCOPE_ID, mediaId: base.mediaId }, nodeKey, bytes))
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
    const sealed = sealMemoryDate(nodeKey, NODE, date)
    expect(unsealMemoryDate(SCOPE_ID, nodeKey, { id: NODE_ID, wrappedMeta: sealed })).toEqual(date)
  })

  it('is operator-blind — the sealed blob carries no plaintext day', () => {
    const sealed = sealMemoryDate(nodeKey, NODE, { mode: 'single', from: '2026-06-15', to: '2026-06-15' })
    expect(new TextDecoder().decode(fromB64std(sealed))).not.toContain('2026-06-15')
  })

  it('returns null for an absent, wrong-key, another node\'s, or malformed blob (E3 → falls back to auto)', () => {
    expect(unsealMemoryDate(SCOPE_ID, nodeKey, { id: NODE_ID, wrappedMeta: null })).toBeNull()
    const sealed = sealMemoryDate(nodeKey, NODE, { mode: 'single', from: '2026-06-15', to: '2026-06-15' })
    expect(unsealMemoryDate(SCOPE_ID, v1.randomBytes(32), { id: NODE_ID, wrappedMeta: sealed })).toBeNull() // wrong key
    // the same blob served on another node's row does not open there (ADR 0072)
    expect(unsealMemoryDate(SCOPE_ID, nodeKey, { id: OTHER_NODE_ID, wrappedMeta: sealed })).toBeNull()
    expect(unsealMemoryDate(SCOPE_ID, nodeKey, { id: NODE_ID, wrappedMeta: 'not-base64!!' })).toBeNull()
    // an unrecognized mode is rejected as no manual date
    const badMode = sealMetaJson('{"v":1,"date":{"mode":"weird"}}')
    expect(unsealMemoryDate(SCOPE_ID, nodeKey, { id: NODE_ID, wrappedMeta: badMode })).toBeNull()
  })
})

describe('setMemoryDate', () => {
  it('re-seals under K_mem and PATCHes wrappedMeta via updateNode', async () => {
    const updateNode = vi.fn().mockResolvedValue(undefined)
    vi.mocked(contentApi).mockReturnValue({ updateNode } as unknown as ReturnType<typeof contentApi>)

    const date: MemoryDate = { mode: 'range', from: '2026-05-01', to: '2026-05-09' }
    const sealed = await setMemoryDate(SCOPE_ID, NODE_ID, nodeKey, date, null)

    const req = updateNode.mock.calls[0][0]
    expect(req.scopeId).toBe(SCOPE_ID)
    expect(req.nodeId).toBe(NODE_ID)
    expect(req.updateNodeRequest.wrappedMeta).toBe(sealed)
    // what we sealed opens back to the date under K_mem (operator-blind round-trip), bound to the patched node
    expect(unsealMemoryDate(SCOPE_ID, nodeKey, { id: NODE_ID, wrappedMeta: sealed })).toEqual(date)
    expect(unsealMemoryDate(SCOPE_ID, nodeKey, { id: OTHER_NODE_ID, wrappedMeta: sealed })).toBeNull()
  })

  it('read-modify-write: setting a date preserves a sibling location in the same blob (cr-0069)', async () => {
    const updateNode = vi.fn().mockResolvedValue(undefined)
    vi.mocked(contentApi).mockReturnValue({ updateNode } as unknown as ReturnType<typeof contentApi>)

    // a blob that already carries a location (sealed as the RMW envelope would)
    const withLocation = sealMetaJson(
      JSON.stringify({ v: 1, location: { name: 'Teneriffa', nameSource: 'manual', derivedMode: 'fixed' } }),
    )
    const date: MemoryDate = { mode: 'single', from: '2026-05-01', to: '2026-05-01' }
    const sealed = await setMemoryDate(SCOPE_ID, NODE_ID, nodeKey, date, withLocation)

    // the date landed AND the location survived the re-seal
    expect(unsealMemoryDate(SCOPE_ID, nodeKey, { id: NODE_ID, wrappedMeta: sealed })).toEqual(date)
    const blob = JSON.parse(new TextDecoder().decode(v1.open(metaCtx(NODE_ID), nodeKey, fromB64std(sealed))))
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
    expect(computeAutoRange(SCOPE_ID, items, nodeKey)).toEqual({
      mode: 'auto',
      from: epochMsToIsoDate(day('2026-03-02')),
      to: epochMsToIsoDate(day('2026-03-20')),
    })
  })

  it('is empty auto when no trustworthy dated media (only file fallbacks / none)', () => {
    const items = [itemWith({ capturedAt: Date.now(), capturedAtSource: 'file' }), itemWith(null)]
    expect(computeAutoRange(SCOPE_ID, items, nodeKey)).toEqual({ mode: 'auto' })
  })

  it('ignores a capture time sealed for another media (a moved wrappedMeta contributes nothing)', () => {
    const day = (iso: string) => new Date(iso + 'T12:00:00').getTime()
    const own = itemWith({ capturedAt: day('2026-03-10'), capturedAtSource: 'exif' })
    const donor = itemWith({ capturedAt: day('2020-01-01'), capturedAtSource: 'exif' })
    const moved: MediaItem = { ...itemWith(null), wrappedMeta: donor.wrappedMeta }
    expect(computeAutoRange(SCOPE_ID, [own, moved], nodeKey)).toEqual({
      mode: 'auto',
      from: epochMsToIsoDate(day('2026-03-10')),
      to: epochMsToIsoDate(day('2026-03-10')),
    })
  })
})

describe('unsealNodeDate / sortNodesByDate (browse card grid, cr-0067)', () => {
  const scopeKey = v1.randomBytes(32)

  /** A top-level Memory node: a fresh K_mem wrapped under `scopeKey`, its date sealed under that K_mem — both bound
   *  to the node (a fresh UUID; the readable label rides in `name`). */
  function memoryNode(label: string, date: MemoryDate | null): TreeNode {
    const id = crypto.randomUUID()
    const memKey = v1.randomBytes(32)
    const wrappedContentKey = b64std(v1.seal({ purpose: 'content-key', scopeId: SCOPE_ID, nodeId: id, epoch: 0 }, scopeKey, memKey))
    return {
      id,
      parentId: null,
      kind: NodeKind.Memory,
      orderIndex: 0,
      epoch: 0,
      name: label,
      wrappedContentKey,
      wrappedMeta: date ? sealMemoryDate(memKey, { scopeId: SCOPE_ID, nodeId: id }, date) : null,
    }
  }

  it('unseals a node’s own date by opening its K_mem under the parent key', () => {
    const n = memoryNode('m', { mode: 'single', from: '2026-06-15', to: '2026-06-15' })
    expect(unsealNodeDate(SCOPE_ID, scopeKey, n)).toEqual({ mode: 'single', from: '2026-06-15', to: '2026-06-15' })
    expect(unsealNodeDate(SCOPE_ID, null, n)).toBeNull() // no key (locked) → no date
    expect(unsealNodeDate(SCOPE_ID, v1.randomBytes(32), n)).toBeNull() // wrong parent key → best-effort null, no throw
    // a K_mem wrap served on another node's row → best-effort null, never the other Memory's date
    expect(unsealNodeDate(SCOPE_ID, scopeKey, { ...n, id: crypto.randomUUID() })).toBeNull()
  })

  it('orders a mixed grid by start date — newest first, undated last (stable)', () => {
    const a = memoryNode('a', { mode: 'range', from: '2026-03-01', to: '2026-03-10' })
    const b = memoryNode('b', { mode: 'single', from: '2026-08-01', to: '2026-08-01' })
    const c = memoryNode('c', null) // undated
    const d = memoryNode('d', { mode: 'ongoing', from: '2026-05-01' })
    const e = memoryNode('e', null) // undated — keeps input order among undated
    const ordered = sortNodesByDate(SCOPE_ID, () => scopeKey, [a, b, c, d, e]).map((x) => x.node.name)
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
