import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../spaces/scopes', () => ({ listMyScopes: vi.fn(), openScopeById: vi.fn() }))
vi.mock('../../core/node/tree', async (orig) => ({ ...(await orig<typeof import('../../core/node/tree')>()), fetchTree: vi.fn() }))

import * as v1 from '@veyq/crypto/v1'
import { loadParticipantIndex, resetParticipantIndex, isAbandoned, MAX_MEMORIES_PER_SCOPE } from './participantIndex'
import { listMyScopes, openScopeById } from '../spaces/scopes'
import { fetchTree, sealName, wrapContentKey } from '../../core/node/tree'
import { sealNodeMeta } from '../../core/node/nodeMeta'
import { MEMORY_KIND } from './kinds'
import { FOLDER_KIND } from '../../core/node/kind'
import type { NodeSummary } from '../../api/generated/content'
import { ScopeKind } from '../../api/generated/scopes'
import { openedScope, scopeSummary } from '../../test/models'

const id = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`
const T1 = id(101)
const G1 = id(102)
const P1 = id(103)
const FOLDER = id(1)
const M1 = id(11)
const M2 = id(12)
const M3 = id(13)
const ANNA = 'aaaaaaaa-0000-4000-8000-000000000001'
const BEN = 'bbbbbbbb-0000-4000-8000-000000000002'

/** One scope's sealed rows: a root folder `Trips` holding the given Memories (name, participants, date). */
function scopeRows(scopeId: string, epochKey: Uint8Array, memories: { id: string; name: string; users?: string[]; date?: unknown; place?: string }[]) {
  const addr = (nodeId: string) => ({ scopeId, nodeId, epoch: 0, placementVersion: 1 })
  const folderKey = v1.randomBytes(32)
  const base = { createdAt: new Date(0), placementVersion: 1, epoch: 0, slugToken: 's', orderIndex: 0, metaVersion: 0 }
  const rows: NodeSummary[] = [{
    ...base, id: FOLDER, parentId: null, kind: FOLDER_KIND,
    encryptedName: sealName(epochKey, addr(FOLDER), 'Trips'), wrappedContentKey: wrapContentKey(epochKey, addr(FOLDER), folderKey),
  }]
  const keys = new Map<string, Uint8Array>()
  for (const m of memories) {
    const key = v1.randomBytes(32)
    keys.set(m.id, key)
    const meta = { v: 1 as const, ...(m.users ? { participants: { userIds: m.users } } : {}), ...(m.date ? { date: m.date } : {}), ...(m.place ? { location: { name: m.place, nameSource: 'manual', derivedMode: 'fixed' } } : {}) }
    rows.push({
      ...base, id: m.id, parentId: FOLDER, kind: MEMORY_KIND,
      encryptedName: sealName(folderKey, addr(m.id), m.name), wrappedContentKey: wrapContentKey(folderKey, addr(m.id), key),
      wrappedMeta: sealNodeMeta(key, { scopeId, nodeId: m.id }, meta),
    })
  }
  return { rows, keys }
}

const mocked = { listMyScopes: vi.mocked(listMyScopes), openScopeById: vi.mocked(openScopeById), fetchTree: vi.mocked(fetchTree) }
let epochKey: Uint8Array

beforeEach(() => {
  vi.clearAllMocks()
  resetParticipantIndex()
  epochKey = v1.randomBytes(32)
  mocked.openScopeById.mockImplementation(async (id) => openedScope({ scopeId: id, key: epochKey.slice(), name: id === G1 ? 'Family group' : undefined, kind: id === G1 ? ScopeKind.Group : ScopeKind.Tenant }))
})

describe('loadParticipantIndex', () => {
  it('indexes the Memories that name participants across the tenant and a group, never the personal scope', async () => {
    mocked.listMyScopes.mockResolvedValue([
      scopeSummary({ id: T1, kind: ScopeKind.Tenant }), scopeSummary({ id: G1, kind: ScopeKind.Group }), scopeSummary({ id: P1, kind: ScopeKind.Personal }),
    ])
    const t1 = scopeRows(T1, epochKey, [
      { id: M1, name: 'Rome', users: [ANNA, BEN], date: { mode: 'single', from: '2026-05-01', to: '2026-05-01' }, place: 'Roma' },
      { id: M2, name: 'Nobody listed' },
    ])
    const g1 = scopeRows(G1, epochKey, [{ id: M3, name: 'Camping', users: [ANNA] }])
    mocked.fetchTree.mockImplementation(async (id) => ({ rows: id === T1 ? t1.rows : g1.rows, etag: `"${id}-1"` }))

    const index = await loadParticipantIndex()

    expect([...index.byUser.get(ANNA)!].sort()).toEqual([`${T1}/${M1}`, `${G1}/${M3}`])
    expect([...index.byUser.get(BEN)!]).toEqual([`${T1}/${M1}`])
    const rome = index.memories.get(`${T1}/${M1}`)!
    expect(rome.node.name).toBe('Rome')
    expect(rome.date?.from).toBe('2026-05-01')
    expect(rome.placeName).toBe('Roma')
    expect(rome.scopeToken).toBe('')
    const camping = index.memories.get(`${G1}/${M3}`)!
    expect(camping.scopeToken).toBe(`@${G1}`)
    expect(camping.scopeLabel).toBe('Family group')
    expect(index.memories.has(`${T1}/${M2}`)).toBe(false) // no participants, not indexed
    expect(index.partial).toBe(false)
    expect(mocked.fetchTree.mock.calls.map((c) => c[0]).sort()).toEqual([T1, G1]) // the personal scope was never read
  })

  it('revalidates by ETag: a 304 reuses the entry with no scope opened and no rows decrypted', async () => {
    mocked.listMyScopes.mockResolvedValue([scopeSummary({ id: T1, kind: ScopeKind.Tenant })])
    const t1 = scopeRows(T1, epochKey, [{ id: M1, name: 'Rome', users: [ANNA] }])
    mocked.fetchTree.mockResolvedValueOnce({ rows: t1.rows, etag: '"7"' })
    await loadParticipantIndex()
    expect(mocked.openScopeById).toHaveBeenCalledTimes(1)

    mocked.fetchTree.mockResolvedValueOnce(null) // 304
    const again = await loadParticipantIndex()

    expect(mocked.fetchTree).toHaveBeenLastCalledWith(T1, '"7"')
    expect(mocked.openScopeById).toHaveBeenCalledTimes(1) // not opened again
    expect([...again.byUser.get(ANNA)!]).toEqual([`${T1}/${M1}`])
  })

  it('reads a scope again when its revision moved, and a Memory whose participants were removed drops out', async () => {
    mocked.listMyScopes.mockResolvedValue([scopeSummary({ id: T1, kind: ScopeKind.Tenant })])
    mocked.fetchTree.mockResolvedValueOnce({ rows: scopeRows(T1, epochKey, [{ id: M1, name: 'Rome', users: [ANNA] }]).rows, etag: '"1"' })
    await loadParticipantIndex()
    mocked.fetchTree.mockResolvedValueOnce({ rows: scopeRows(T1, epochKey, [{ id: M1, name: 'Rome' }]).rows, etag: '"2"' })
    const index = await loadParticipantIndex()
    expect(index.byUser.has(ANNA)).toBe(false)
  })

  it('stops at the per-scope cap and says the result is partial', async () => {
    mocked.listMyScopes.mockResolvedValue([scopeSummary({ id: T1, kind: ScopeKind.Tenant })])
    const many = Array.from({ length: MAX_MEMORIES_PER_SCOPE + 3 }, (_, i) => ({ id: id(1000 + i), name: `M${i}`, users: [ANNA] }))
    mocked.fetchTree.mockResolvedValue({ rows: scopeRows(T1, epochKey, many).rows, etag: '"1"' })
    const index = await loadParticipantIndex()
    expect(index.byUser.get(ANNA)!.size).toBe(MAX_MEMORIES_PER_SCOPE)
    expect(index.partial).toBe(true)
  })

  it('a scope that cannot be read makes the result partial; every scope failing is an error', async () => {
    mocked.listMyScopes.mockResolvedValue([scopeSummary({ id: T1, kind: ScopeKind.Tenant }), scopeSummary({ id: G1, kind: ScopeKind.Group })])
    const t1 = scopeRows(T1, epochKey, [{ id: M1, name: 'Rome', users: [ANNA] }])
    mocked.fetchTree.mockImplementation(async (id) => {
      if (id === G1) throw new Error('offline')
      return { rows: t1.rows, etag: '"1"' }
    })
    const index = await loadParticipantIndex()
    expect(index.partial).toBe(true)
    expect([...index.byUser.get(ANNA)!]).toEqual([`${T1}/${M1}`])

    resetParticipantIndex()
    mocked.fetchTree.mockRejectedValue(new Error('offline'))
    await expect(loadParticipantIndex()).rejects.toThrow('offline')
  })

  it('a scope whose tree does not open for this viewer counts as failed, the readable one is still indexed', async () => {
    mocked.listMyScopes.mockResolvedValue([scopeSummary({ id: T1, kind: ScopeKind.Tenant }), scopeSummary({ id: G1, kind: ScopeKind.Group })])
    const t1 = scopeRows(T1, epochKey, [{ id: M1, name: 'Rome', users: [ANNA] }])
    const g1 = scopeRows(G1, epochKey, [{ id: M3, name: 'Camping', users: [ANNA] }])
    // the group Memory's key is wrapped under a key the viewer does not hold: the chain does not reach it
    g1.rows[1] = { ...g1.rows[1], wrappedContentKey: wrapContentKey(v1.randomBytes(32), { scopeId: G1, nodeId: M3, epoch: 0, placementVersion: 1 }, v1.randomBytes(32)) }
    mocked.fetchTree.mockImplementation(async (id) => ({ rows: id === T1 ? t1.rows : g1.rows, etag: '"1"' }))
    const index = await loadParticipantIndex()
    expect([...index.byUser.get(ANNA)!]).toEqual([`${T1}/${M1}`]) // what cannot be opened is absent
    expect(index.partial).toBe(true)
  })

  it('a reset zeroes the held keys, empties the cache and abandons a build in flight', async () => {
    mocked.listMyScopes.mockResolvedValue([scopeSummary({ id: T1, kind: ScopeKind.Tenant })])
    mocked.fetchTree.mockResolvedValue({ rows: scopeRows(T1, epochKey, [{ id: M1, name: 'Rome', users: [ANNA] }]).rows, etag: '"1"' })
    const index = await loadParticipantIndex()
    const key = index.memories.get(`${T1}/${M1}`)!.key
    expect(key.some((b) => b !== 0)).toBe(true)
    resetParticipantIndex()
    expect(key.every((b) => b === 0)).toBe(true)
    mocked.fetchTree.mockResolvedValueOnce(null) // a cache that was emptied cannot be served from
    await expect(loadParticipantIndex()).rejects.toThrow()

    // a reset between the listing of the scopes and the end of the walk abandons the build
    mocked.fetchTree.mockImplementation(async () => {
      resetParticipantIndex()
      return { rows: [], etag: null }
    })
    await expect(loadParticipantIndex()).rejects.toSatisfy(isAbandoned)
  })
})
