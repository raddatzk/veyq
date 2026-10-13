import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../api/client', () => ({
  contentApi: vi.fn(),
  loginApi: vi.fn(),
  tokenApi: vi.fn(),
  sessionApi: vi.fn(),
}))

import {
  MAX_PARTICIPANTS,
  mergeParticipants,
  normalizeParticipants,
  setMemoryParticipants,
  unsealMemoryParticipants,
} from './memoryParticipants'
import { unsealMemoryDate, type MemoryDate } from './memoryDate'
import { sealNodeMeta, unsealNodeMeta } from '../../core/node/nodeMeta'
import { contentApi } from '../../api/client'
import { fakeApi } from '../../test/apiMock'
import type { ContentApi } from '../../api/generated/content'
import * as v1 from '@veyq/crypto/v1'

const SCOPE_ID = '11111111-1111-1111-1111-111111111111'
const NODE_ID = '5c09e000-0000-4000-8000-0000000000e1'
const NODE = { scopeId: SCOPE_ID, nodeId: NODE_ID }
const nodeKey = v1.randomBytes(32)
const A = 'aaaaaaaa-0000-4000-8000-000000000001'
const B = 'bbbbbbbb-0000-4000-8000-000000000002'
const C = 'cccccccc-0000-4000-8000-000000000003'
const D = 'dddddddd-0000-4000-8000-000000000004'
const date: MemoryDate = { mode: 'single', from: '2026-05-01', to: '2026-05-01' }

beforeEach(() => vi.clearAllMocks())

const stale = (wrappedMeta: string | null, metaVersion: number) => ({
  response: new Response(JSON.stringify({ code: 'stale_version', wrappedMeta, metaVersion }), { status: 409 }),
})
const idsOf = (wrappedMeta: string | null) => unsealMemoryParticipants(SCOPE_ID, nodeKey, { id: NODE_ID, wrappedMeta })

describe('normalizeParticipants', () => {
  it('keeps unique non-empty string ids in order and drops everything else', () => {
    expect(normalizeParticipants({ userIds: [A, B, A, '', 7, null, C] })).toEqual([A, B, C])
  })
  it('is empty for anything that is not { userIds: [] }', () => {
    for (const raw of [undefined, null, 'x', 5, [], { userIds: 'a' }, { ids: [A] }]) expect(normalizeParticipants(raw)).toEqual([])
  })
  it('caps the list at MAX_PARTICIPANTS', () => {
    const many = Array.from({ length: MAX_PARTICIPANTS + 20 }, (_, i) => `u${i}`)
    expect(normalizeParticipants({ userIds: many })).toHaveLength(MAX_PARTICIPANTS)
  })
})

describe('mergeParticipants', () => {
  it('keeps a peer\'s addition and adds the editor\'s', () => {
    expect(mergeParticipants([A, B], [A, C], [A])).toEqual([A, B, C])
  })
  it('an id the editor removed stays removed even though a peer\'s list still holds it', () => {
    expect(mergeParticipants([A, B], [A], [A, B])).toEqual([A])
  })
  it('does not remove an id the editor never had (a peer\'s new one)', () => {
    expect(mergeParticipants([A, D], [], [A])).toEqual([D])
  })
  it('caps the merged list', () => {
    const stored = Array.from({ length: MAX_PARTICIPANTS }, (_, i) => `u${i}`)
    expect(mergeParticipants(stored, ['extra'], [])).toHaveLength(MAX_PARTICIPANTS)
  })
})

describe('setMemoryParticipants (read-modify-write, ADR 0077 + ADR 0083)', () => {
  it('stores only opaque ids, next to the date, which survives', async () => {
    const updateNode = vi.fn<ContentApi['updateNode']>().mockResolvedValue(undefined)
    fakeApi(contentApi, { updateNode })
    const withDate = sealNodeMeta(nodeKey, NODE, { v: 1, date })
    const state = await setMemoryParticipants(SCOPE_ID, NODE_ID, nodeKey, [A, B], { wrappedMeta: withDate, metaVersion: 3 })
    expect(idsOf(state.wrappedMeta)).toEqual([A, B])
    expect(unsealMemoryDate(SCOPE_ID, nodeKey, { id: NODE_ID, wrappedMeta: state.wrappedMeta })).toEqual(date)
    // the stored object carries user ids and nothing else (no name, no snapshot - ADR 0083 decision 4)
    expect(unsealNodeMeta(nodeKey, NODE, state.wrappedMeta).participants).toEqual({ userIds: [A, B] })
    expect(updateNode.mock.calls[0][0].updateNodeRequest.metaVersion).toBe(3)
    expect(state.metaVersion).toBe(4)
  })

  it('keeps an unknown sibling field inside the sub-object and in the blob', async () => {
    fakeApi(contentApi, { updateNode: vi.fn<ContentApi['updateNode']>().mockResolvedValue(undefined) })
    const base = sealNodeMeta(nodeKey, NODE, { v: 1, future: { x: 1 }, participants: { userIds: [A], flag: true } })
    const state = await setMemoryParticipants(SCOPE_ID, NODE_ID, nodeKey, [A, B], { wrappedMeta: base, metaVersion: 0 })
    const meta = unsealNodeMeta(nodeKey, NODE, state.wrappedMeta)
    expect(meta.future).toEqual({ x: 1 })
    expect(meta.participants).toEqual({ userIds: [A, B], flag: true })
  })

  it('an empty list drops the sub-object and keeps the date', async () => {
    fakeApi(contentApi, { updateNode: vi.fn<ContentApi['updateNode']>().mockResolvedValue(undefined) })
    const base = sealNodeMeta(nodeKey, NODE, { v: 1, date, participants: { userIds: [A] } })
    const state = await setMemoryParticipants(SCOPE_ID, NODE_ID, nodeKey, [], { wrappedMeta: base, metaVersion: 0 })
    expect('participants' in unsealNodeMeta(nodeKey, NODE, state.wrappedMeta)).toBe(false)
    expect(unsealMemoryDate(SCOPE_ID, nodeKey, { id: NODE_ID, wrappedMeta: state.wrappedMeta })).toEqual(date)
  })

  it('writes nothing when the list is already what is wanted', async () => {
    const updateNode = vi.fn<ContentApi['updateNode']>().mockResolvedValue(undefined)
    fakeApi(contentApi, { updateNode })
    const base = sealNodeMeta(nodeKey, NODE, { v: 1, participants: { userIds: [A, B] } })
    const state = await setMemoryParticipants(SCOPE_ID, NODE_ID, nodeKey, [A, B], { wrappedMeta: base, metaVersion: 5 })
    expect(updateNode).not.toHaveBeenCalled()
    expect(state).toEqual({ wrappedMeta: base, metaVersion: 5 })
  })

  it('on 409 stale_version MERGES: a peer\'s added participant survives, the editor\'s addition lands', async () => {
    const peers = sealNodeMeta(nodeKey, NODE, { v: 1, date, participants: { userIds: [A, D] } })
    const updateNode = vi.fn<ContentApi['updateNode']>().mockRejectedValueOnce(stale(peers, 8)).mockResolvedValueOnce(undefined)
    fakeApi(contentApi, { updateNode })
    const mine = sealNodeMeta(nodeKey, NODE, { v: 1, participants: { userIds: [A] } })
    const state = await setMemoryParticipants(SCOPE_ID, NODE_ID, nodeKey, [A, C], { wrappedMeta: mine, metaVersion: 7 })
    expect(updateNode).toHaveBeenCalledTimes(2)
    expect(updateNode.mock.calls[1][0].updateNodeRequest.metaVersion).toBe(8)
    expect(idsOf(state.wrappedMeta)).toEqual([A, D, C])
    expect(unsealMemoryDate(SCOPE_ID, nodeKey, { id: NODE_ID, wrappedMeta: state.wrappedMeta })).toEqual(date)
    expect(state.metaVersion).toBe(9)
  })

  it('on 409 the editor\'s own removal wins over the peer\'s list that still holds the id', async () => {
    const peers = sealNodeMeta(nodeKey, NODE, { v: 1, participants: { userIds: [A, B, D] } })
    const updateNode = vi.fn<ContentApi['updateNode']>().mockRejectedValueOnce(stale(peers, 2)).mockResolvedValueOnce(undefined)
    fakeApi(contentApi, { updateNode })
    const mine = sealNodeMeta(nodeKey, NODE, { v: 1, participants: { userIds: [A, B] } })
    const state = await setMemoryParticipants(SCOPE_ID, NODE_ID, nodeKey, [A], { wrappedMeta: mine, metaVersion: 1 })
    expect(idsOf(state.wrappedMeta)).toEqual([A, D]) // B removed by the editor, D added by the peer
  })
})
