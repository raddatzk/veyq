// Core's move (inbox #358, `move.ts`): the door per session, a selection's writes, and the refusals by name. Where a node may
// go is the platform's (`treeRules.ts`), held by contracts/platform/tree/v1-vectors.json (`tree.vectors.test.ts`).
// Kinds other than FOLDER are made-up tokens with a `placement` like an application's (core knows none); the registry is
// a fresh one per test behind a delegating export (as `access.test.ts`). The placement writes themselves are mocked here
// — their wire is `tree.wire.test.ts` / `guestTree.wire.test.ts` and `MoveNodeDialog.wire.test.ts`.
import { describe, it, expect, vi, beforeEach } from 'vitest'

const holder = vi.hoisted(() => ({ reg: null as null | import('../applications/registry').Registry }))
vi.mock('../applications/registry', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../applications/registry')>()
  const r = () => (holder.reg ??= actual.createRegistry())
  return {
    ...actual,
    registry: new Proxy({} as import('../applications/registry').Registry, {
      get: (_t, name: string) => (r() as unknown as Record<string, (...a: unknown[]) => unknown>)[name].bind(r()),
    }),
  }
})
const calls = vi.hoisted(() => ({ moveNode: vi.fn(async () => {}), place: vi.fn(async () => {}), loadTree: vi.fn(async () => {}) }))
vi.mock('./tree', async (o) => ({ ...(await o<typeof import('./tree')>()), moveNode: calls.moveNode }))
vi.mock('./treeStore', async () => {
  const { reactive } = await import('vue')
  return { treeStore: reactive({ nodes: [], loaded: true, error: false }), loadTree: calls.loadTree }
})
vi.mock('../sharing/guestTree', async (o) => ({
  ...(await o<typeof import('../sharing/guestTree')>()),
  placeShareNodeAsGuest: calls.place,
  guestTreeContext: (head: unknown, ids: unknown) => ({ head, ids }),
}))

import { moveRefusalOf, nodeMover, MoveDeclined } from './move'
import { createRegistry } from '../applications/registry'
import { FOLDER_KIND } from './kind'
import { PlacementConflictError } from './tree'
import { GuestTreeError } from '../sharing/guestTree'
import type { Access } from '../access'
import type { ScopeKey } from '../../features/spaces/epochs'
import type { GuestIdentity } from '../sharing/guestIdentity'
import { memberAccessOf, guestAccessOf } from '../../test/models'
import type { PublicShareHead } from '../sharing/resolvePublicShare'
import { nothingOfItsOwnToRekey } from './rekey/hooks'
import { testKind } from '../../test/testApplication'

const ITEM = 'ITEM' // lives at the root or in a folder (as a Memory, a file)
const PART = 'PART' // lives only in an ITEM (as a chapter)
const BOX = 'BOX' // a drop box: takes no placed child

beforeEach(() => {
  holder.reg = createRegistry()
  testKind(holder.reg, { rekeyContent: nothingOfItsOwnToRekey, kind: ITEM, icon: 'images', placement: { root: true, parents: [FOLDER_KIND] } })
  testKind(holder.reg, { rekeyContent: nothingOfItsOwnToRekey, kind: PART, icon: 'image', placement: { root: false, parents: [ITEM] } })
  testKind(holder.reg, { rekeyContent: nothingOfItsOwnToRekey, kind: BOX, icon: 'inbox', placement: { root: true, parents: [FOLDER_KIND] } })
  for (const f of Object.values(calls)) f.mockClear()
})

describe('nodeMover: the door per session', () => {
  it('a member moves through moveNode over the loaded tree, then re-reads it', async () => {
    const epochs: ScopeKey = { epoch: 0, key: new Uint8Array(32) }
    const m = nodeMover(() => memberAccessOf({ epochs }))!
    await m.move('a', 'b')
    expect(calls.moveNode).toHaveBeenCalledWith('scope-1', epochs, expect.any(Array), 'a', 'b')
    expect(calls.loadTree).toHaveBeenCalledTimes(1)
    await m.move('a', null) // a member may move to the scope root
    expect(calls.moveNode).toHaveBeenLastCalledWith('scope-1', epochs, expect.any(Array), 'a', null)
  })

  it('a member whose scope is locked sends nothing', async () => {
    await expect(nodeMover(() => memberAccessOf({ epochs: null }))!.move('a', 'b')).rejects.toThrow('scope_locked')
    expect(calls.moveNode).not.toHaveBeenCalled()
  })

  it('a READ_WRITE guest names itself first, places through its link, then re-resolves', async () => {
    const reload = vi.fn(async () => {})
    const head = { shareId: 's1' } as PublicShareHead
    const requestGuest = vi.fn(async (): Promise<GuestIdentity> => ({ identityId: 'g', contributorToken: 'tok' }))
    const m = nodeMover(() => guestAccessOf('READ_WRITE', { head, requestGuest, reload, pwToken: 'pw' }))!
    await m.move('item', 'a')
    expect(requestGuest).toHaveBeenCalledTimes(1)
    expect(calls.place).toHaveBeenCalledWith({ head, ids: { contributorToken: 'tok', fragmentKey: 'frag', pwToken: 'pw' } }, { nodeId: 'item', newParentId: 'a' })
    expect(reload).toHaveBeenCalledTimes(1)
    expect(calls.moveNode, 'never the member\'s write').not.toHaveBeenCalled()
  })

  it('a guest who declines to name itself sends nothing', async () => {
    const m = nodeMover(() => guestAccessOf('READ_WRITE', { requestGuest: async () => null }))!
    await expect(m.move('item', 'a')).rejects.toBeInstanceOf(MoveDeclined)
    expect(calls.place).not.toHaveBeenCalled()
  })

  it('a guest never moves to the scope root (no key of it)', async () => {
    const requestGuest = vi.fn(async () => null)
    await expect(nodeMover(() => guestAccessOf('READ_WRITE', { requestGuest }))!.move('item', null)).rejects.toMatchObject({ code: 'illegal_structure' })
    expect(requestGuest).not.toHaveBeenCalled()
  })

  it('a READ or WRITE_ONLY guest, an unknown role or mode has no door', () => {
    for (const role of ['READ', 'WRITE_ONLY', 'OWNER']) expect(nodeMover(() => guestAccessOf(role)), role).toBeNull()
    expect(nodeMover(() => ({ mode: 'operator' }) as unknown as Access)).toBeNull()
  })
})

// inbox #421: a level's selection moves through the same browser and door - N single-node writes, ONE re-read.
describe('several at once: the writes', () => {
  it('a member: one placement write per node, in order, and the tree re-read ONCE after the last', async () => {
    const epochs: ScopeKey = { epoch: 0, key: new Uint8Array(32) }
    const out = await nodeMover(() => memberAccessOf({ epochs }))!.moveMany([{ nodeId: 'a', to: 'b' }, { nodeId: 'item2', to: 'b' }, { nodeId: 'box', to: 'b' }])
    expect((calls.moveNode.mock.calls as unknown[][]).map((c) => [c[3], c[4]])).toEqual([['a', 'b'], ['item2', 'b'], ['box', 'b']])
    expect(calls.moveNode).toHaveBeenCalledWith('scope-1', epochs, expect.any(Array), 'a', 'b')
    expect(calls.loadTree).toHaveBeenCalledTimes(1)
    expect(out).toEqual([{ nodeId: 'a', error: null }, { nodeId: 'item2', error: null }, { nodeId: 'box', error: null }])
  })

  it('a refusal mid-way leaves the earlier moves, still sends the rest, and says per node how it ended', async () => {
    const refused = new GuestTreeError('slug_taken')
    calls.moveNode.mockImplementationOnce(async () => {}).mockRejectedValueOnce(refused)
    const out = await nodeMover(() => memberAccessOf({ epochs: { epoch: 0, key: new Uint8Array(32) } }))!.moveMany([
      { nodeId: 'a', to: 'b' }, { nodeId: 'item2', to: 'b' }, { nodeId: 'box', to: 'b' },
    ])
    expect(calls.moveNode).toHaveBeenCalledTimes(3)
    expect(out).toEqual([{ nodeId: 'a', error: null }, { nodeId: 'item2', error: refused }, { nodeId: 'box', error: null }])
    expect(calls.loadTree, 'still once').toHaveBeenCalledTimes(1)
  })

  it('a member whose scope is locked sends nothing', async () => {
    await expect(nodeMover(() => memberAccessOf({ epochs: null }))!.moveMany([{ nodeId: 'a', to: 'b' }])).rejects.toThrow('scope_locked')
    expect(calls.moveNode).not.toHaveBeenCalled()
  })

  it('a READ_WRITE guest: named ONCE, each node placed through its link, the link re-resolved once', async () => {
    const reload = vi.fn(async () => {})
    const head = { shareId: 's1' } as PublicShareHead
    const requestGuest = vi.fn(async (): Promise<GuestIdentity> => ({ identityId: 'g', contributorToken: 'tok' }))
    const out = await nodeMover(() => guestAccessOf('READ_WRITE', { head, requestGuest, reload }))!.moveMany([
      { nodeId: 'item', to: 'a' }, { nodeId: 'b', to: 'a' }, { nodeId: 'x', to: null },
    ])
    expect(requestGuest).toHaveBeenCalledTimes(1)
    expect(calls.place.mock.calls.map((c) => (c as unknown[])[1])).toEqual([{ nodeId: 'item', newParentId: 'a' }, { nodeId: 'b', newParentId: 'a' }])
    expect(reload).toHaveBeenCalledTimes(1)
    expect(out[2]).toMatchObject({ nodeId: 'x', error: { code: 'illegal_structure' } })
    expect(calls.moveNode, 'never the member\'s write').not.toHaveBeenCalled()
  })

  it('a guest who declines to name itself sends nothing', async () => {
    const m = nodeMover(() => guestAccessOf('READ_WRITE', { requestGuest: async () => null }))!
    await expect(m.moveMany([{ nodeId: 'item', to: 'a' }])).rejects.toBeInstanceOf(MoveDeclined)
    expect(calls.place).not.toHaveBeenCalled()
  })
})

describe('moveRefusalOf: every refusal by its code', () => {
  const coded = (status: number, code: string) => ({ response: new Response(JSON.stringify({ code }), { status, headers: { 'content-type': 'application/json' } }) })

  it.each([
    [409, 'slug_taken', 'slug_taken'],
    [422, 'illegal_structure', 'illegal_structure'],
    [422, 'illegal_scope', 'illegal_scope'],
    [409, 'stale_placement', 'stale_placement'],
    [409, 'stale_epoch', 'stale_epoch'],
    [409, 'node_rekey_pending', 'node_rekey_pending'],
    [403, 'share_not_contributable', 'share_not_contributable'],
    [404, 'node_not_found', 'gone'],
    [404, 'share_not_found', 'gone'],
    [403, 'share_password_incorrect', 'gone'],
    [429, 'rate_limited', 'rate_limited'],
    [409, 'scope_deleting', 'scope_deleting'],
    [409, 'id_taken', 'failed'],
    [500, 'boom', 'failed'],
  ])('%s %s → %s', async (status, code, expected) => {
    expect(await moveRefusalOf(coded(status, code))).toBe(expected)
  })

  it('a guest\'s typed refusal, a lost race after the retry, and a network failure', async () => {
    expect(await moveRefusalOf(new GuestTreeError('illegal_scope'))).toBe('illegal_scope')
    expect(await moveRefusalOf(new GuestTreeError('slug_taken', 'Anna'))).toBe('slug_taken')
    expect(await moveRefusalOf(new PlacementConflictError('a'))).toBe('stale_placement')
    expect(await moveRefusalOf(new TypeError('Failed to fetch'))).toBe('failed')
  })
})
