// Core's move (inbox #358, `move.ts`): the destination picker's rows, the door per session, and the refusals by name.
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

import { moveDestinations, moveRefusalOf, nodeMover, MoveDeclined, type MoveDestination } from './move'
import { createRegistry } from '../applications/registry'
import { FOLDER_KIND } from './kind'
import { PlacementConflictError, type TreeNode } from './tree'
import { GuestTreeError } from '../sharing/guestTree'
import type { Access, TreeSource } from '../access'
import type { EpochKeys } from '../../features/spaces/epochs'
import type { GuestIdentity } from '../sharing/guestIdentity'
import { treeNode, memberAccessOf, guestAccessOf, memberTreeOf } from '../../test/models'
import type { PublicShareHead } from '../sharing/resolvePublicShare'

const ITEM = 'ITEM' // lives at the root or in a folder (as a Memory, a file)
const PART = 'PART' // lives only in an ITEM (as a chapter)
const BOX = 'BOX' // a drop box: takes no placed child

const n = (id: string, kind: string, parentId: string | null, over: Partial<TreeNode> = {}): TreeNode =>
  treeNode({ id, kind, name: id, parentId, ...over })

// home/ ─ a/ ─ a1/      box (BOX) ─ guest/ (identity) ─ inner/
//       ─ b/            item (ITEM) ─ part (PART)
//                       item2 (ITEM)
const NODES = [
  n('home', FOLDER_KIND, null), n('a', FOLDER_KIND, 'home'), n('a1', FOLDER_KIND, 'a'), n('b', FOLDER_KIND, 'home'),
  n('box', BOX, 'home'), n('guest', FOLDER_KIND, 'box', { shareIdentityId: 'id-1' }), n('inner', FOLDER_KIND, 'guest'),
  n('item', ITEM, 'b'), n('part', PART, 'item'), n('item2', ITEM, null),
]
const member = memberTreeOf(() => NODES)
const rowsOf = (rows: MoveDestination[]) => rows.map((r) => `${'.'.repeat(r.depth)}${r.id ?? 'ROOT'}${r.blocked ? `:${r.blocked}` : ''}`)

beforeEach(() => {
  holder.reg = createRegistry()
  holder.reg.registerKind({ kind: ITEM, icon: 'i', shareable: false, placement: { root: true, parents: [FOLDER_KIND] } })
  holder.reg.registerKind({ kind: PART, icon: 'p', shareable: false, placement: { root: false, parents: [ITEM] } })
  holder.reg.registerKind({ kind: BOX, icon: 'b', shareable: false, placement: { root: true, parents: [FOLDER_KIND] } })
  for (const f of Object.values(calls)) f.mockClear()
})

describe('moveDestinations: what the picker offers a member', () => {
  it('a folder: the scope root and every folder, never itself, below itself, where it is, or a drop box\'s guest folder', () => {
    expect(rowsOf(moveDestinations(member, NODES[1], 'member', 'Space'))).toEqual([
      'ROOT',
      '.home:current',
      '..a:self', '...a1:inside', // itself and below it: listed (the tree stays readable), never pickable
      '..b',
    ])
  })

  it('names the scope root by the label the host gives, and every other row by the tree', () => {
    const rows = moveDestinations(member, NODES[3], 'member', 'Familie')
    expect(rows[0]).toMatchObject({ id: null, name: 'Familie', kind: null, blocked: null })
    expect(rows.find((r) => r.id === 'a1')).toMatchObject({ name: 'a1', kind: FOLDER_KIND, depth: 3 })
  })

  it('an identity folder (a member\'s move out of a drop box): every folder outside, the drop box itself not listed', () => {
    const rows = rowsOf(moveDestinations(member, NODES[5], 'member', 'S'))
    expect(rows).toEqual(['ROOT', '.home', '..a', '...a1', '..b'])
  })

  it('a kind with a narrower placement (a chapter): only the containers that take it are pickable', () => {
    const rows = rowsOf(moveDestinations(member, NODES[8], 'member', 'S'))
    // folders are listed as the way down, but do not take a PART; the Memory-like ITEMs do, its own is where it is
    expect(rows).toEqual(['ROOT:kind', '.home:kind', '..a:kind', '...a1:kind', '..b:kind', '...item:current', '.item2'])
  })

  it('a kind without a placement hint is offered nowhere (fail closed)', () => {
    const rows = moveDestinations(member, n('x', 'UNREGISTERED', 'a'), 'member', 'S')
    expect(rows.every((r) => r.blocked !== null)).toBe(true)
  })

  it('never offers anything inside a guest\'s identity folder, even where it is listed', () => {
    // a tree where an identity folder sits directly in a folder (listed, as a folder), with a folder inside it
    const odd = [n('f', FOLDER_KIND, null), n('idf', FOLDER_KIND, 'f', { shareIdentityId: 'id-2' }), n('deep', FOLDER_KIND, 'idf'), n('m', ITEM, null)]
    const rows = rowsOf(moveDestinations(memberTreeOf(() => odd), odd[3], 'member', 'S'))
    expect(rows).toEqual(['ROOT:current', '.f', '..idf:identityFolder', '...deep:identityFolder'])
  })
})

describe('moveDestinations: what the picker offers a guest (its subtree, its folders)', () => {
  // A guest's view rooted at `home`: the served root carries parentId null; nothing above it exists.
  const sub = NODES.filter((x) => x.id !== 'item2').map((x) => (x.id === 'home' ? { ...x, parentId: null } : x))
  const guest: TreeSource<TreeNode> = { ...memberTreeOf(() => sub), rootId: 'home' }

  it('starts at the share root, never the scope root; only folders take a guest\'s child', () => {
    expect(rowsOf(moveDestinations(guest, sub.find((x) => x.id === 'item')!, 'guest'))).toEqual([
      'home', '.a', '..a1', '.b:current',
    ])
  })

  it('another ITEM that would take a PART for a member is no guest\'s destination (only a folder lets a guest\'s child in)', () => {
    const withTwo = [...sub, n('item3', ITEM, 'a')]
    const g: TreeSource<TreeNode> = { ...memberTreeOf(() => withTwo), rootId: 'home' }
    const part = withTwo.find((x) => x.id === 'part')!
    expect(moveDestinations(g, part, 'guest').find((r) => r.id === 'item3')?.blocked).toBe('kind')
    // control: the same row is a member's destination
    expect(moveDestinations(memberTreeOf(() => withTwo), part, 'member', 'S').find((r) => r.id === 'item3')?.blocked).toBeNull()
  })

  it('a chapter-like kind has nowhere to go for a guest: no folder takes it, no ITEM is a guest\'s destination', () => {
    const rows = moveDestinations(guest, sub.find((x) => x.id === 'part')!, 'guest')
    expect(rows.map((r) => r.id)).toContain('item')
    expect(rows.every((r) => r.blocked !== null)).toBe(true)
  })
})

describe('nodeMover: the door per session', () => {
  it('a member moves through moveNode over the loaded tree, then re-reads it', async () => {
    const epochs: EpochKeys = { current: 0, keys: new Map() }
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

describe('moveRefusalOf: every refusal by its code', () => {
  const coded = (status: number, code: string) => ({ response: new Response(JSON.stringify({ code }), { status, headers: { 'content-type': 'application/json' } }) })

  it.each([
    [409, 'slug_taken', 'slug_taken'],
    [422, 'illegal_structure', 'illegal_structure'],
    [422, 'illegal_scope', 'illegal_scope'],
    [409, 'stale_placement', 'stale_placement'],
    [409, 'stale_epoch', 'stale_epoch'],
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
