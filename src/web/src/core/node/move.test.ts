// Core's move (inbox #358, `move.ts`): the move browser's model (where it opens, its trail, the places it lists and where
// "Move here" may be chosen), the door per session, and the refusals by name.
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

import { moveHereBlock, moveHereBlockOf, movePlaces, moveRefusalOf, moveStart, moveTrail, nodeMover, planMove, MoveDeclined, type MovePlace } from './move'
import { createRegistry } from '../applications/registry'
import { FOLDER_KIND } from './kind'
import { PlacementConflictError, type TreeNode } from './tree'
import { GuestTreeError } from '../sharing/guestTree'
import type { Access, TreeSource } from '../access'
import type { EpochKeys } from '../../features/spaces/epochs'
import type { GuestIdentity } from '../sharing/guestIdentity'
import { treeNode, memberAccessOf, guestAccessOf, memberTreeOf } from '../../test/models'
import type { PublicShareHead } from '../sharing/resolvePublicShare'
import { nothingOfItsOwnToRekey } from './rekey/hooks'

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
const byId = (id: string) => NODES.find((x) => x.id === id)!
const placesOf = (places: MovePlace[]) => places.map((p) => `${p.node.id}${p.blocked ? `:${p.blocked}` : ''}`).sort()
/** "Move here" at each of `ats` for `node`: `<at>` where it may be chosen, `<at>:<reason>` where not (ROOT: the scope root). */
const hereAt = (tree: TreeSource, node: TreeNode, mode: Access['mode'], ats: (string | null)[]) =>
  ats.map((at) => {
    const block = moveHereBlock(tree, [node], mode, at)
    return `${at ?? 'ROOT'}${block ? `:${block}` : ''}`
  })

beforeEach(() => {
  holder.reg = createRegistry()
  holder.reg.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: ITEM, icon: 'images', placement: { root: true, parents: [FOLDER_KIND] } })
  holder.reg.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: PART, icon: 'image', placement: { root: false, parents: [ITEM] } })
  holder.reg.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: BOX, icon: 'inbox', placement: { root: true, parents: [FOLDER_KIND] } })
  for (const f of Object.values(calls)) f.mockClear()
})

describe('the move browser: what a member is offered', () => {
  it('a folder: opens where it is, lists the folders there (itself blocked), never a drop box or a Memory-like ITEM', () => {
    const a = byId('a')
    expect(moveStart(member, [a])).toBe('home')
    expect(moveTrail(member, 'home')).toEqual([null, 'home'])
    expect(placesOf(movePlaces(member, [a], 'member', 'home')), 'itself listed, never to be opened; the drop box not at all').toEqual(['a:self', 'b'])
    expect(placesOf(movePlaces(member, [a], 'member', 'b')), 'an ITEM takes no folder').toEqual([])
    expect(placesOf(movePlaces(member, [a], 'member', null)), 'item2 is an ITEM').toEqual(['home'])
    // "Move here": the scope root and another folder yes; where it is, itself and below it no
    expect(hereAt(member, a, 'member', [null, 'home', 'b', 'a', 'a1'])).toEqual(['ROOT', 'home:current', 'b', 'a:self', 'a1:inside'])
  })

  it('the trail runs from the scope root (null) down to the place on screen, whatever its depth', () => {
    expect(moveTrail(member, null)).toEqual([null])
    expect(moveTrail(member, 'a1')).toEqual([null, 'home', 'a', 'a1'])
  })

  it('an identity folder (a member\'s move out of a drop box): opens in the drop box, which lists nothing and is where it is', () => {
    const g = byId('guest')
    expect(moveStart(member, [g])).toBe('box')
    expect(moveTrail(member, 'box')).toEqual([null, 'home', 'box'])
    expect(placesOf(movePlaces(member, [g], 'member', 'box')), 'no identity folder is offered').toEqual([])
    expect(hereAt(member, g, 'member', ['box', 'home', 'a', null])).toEqual(['box:current', 'home', 'a', 'ROOT'])
  })

  it('a kind with a narrower placement (a chapter): the folders stay the way down, an ITEM is listed and takes it', () => {
    const part = byId('part')
    expect(moveStart(member, [part])).toBe('item')
    expect(placesOf(movePlaces(member, [part], 'member', null))).toEqual(['home', 'item2'])
    expect(placesOf(movePlaces(member, [part], 'member', 'b'))).toEqual(['item'])
    expect(hereAt(member, part, 'member', ['item', null, 'home', 'b', 'item2'])).toEqual(['item:current', 'ROOT:kind', 'home:kind', 'b:kind', 'item2'])
  })

  it('a kind without a placement hint can go nowhere (fail closed), though the folders still open', () => {
    const x = n('x', 'UNREGISTERED', 'a')
    expect(hereAt(member, x, 'member', [null, 'home', 'b', 'a1', 'item2'])).toEqual(['ROOT:kind', 'home:kind', 'b:kind', 'a1:kind', 'item2:kind'])
    expect(placesOf(movePlaces(member, [x], 'member', null)), 'no ITEM is listed for it').toEqual(['home'])
  })

  it('never offers a guest\'s identity folder, nor "Move here" in one or below it', () => {
    // a tree where an identity folder sits directly in a folder, with a folder inside it
    const odd = [n('f', FOLDER_KIND, null), n('idf', FOLDER_KIND, 'f', { shareIdentityId: 'id-2' }), n('deep', FOLDER_KIND, 'idf'), n('m', ITEM, null)]
    const t = memberTreeOf(() => odd)
    expect(placesOf(movePlaces(t, [odd[3]], 'member', 'f'))).toEqual([])
    expect(hereAt(t, odd[3], 'member', [null, 'f', 'idf', 'deep'])).toEqual(['ROOT:current', 'f', 'idf:identityFolder', 'deep:identityFolder'])
  })

  it('a LIST of nodes (inbox #421): a place is offered only where it takes every one, each of them blocked', () => {
    const [a, b] = [byId('a'), byId('b')]
    expect(moveStart(member, [a, b])).toBe('home')
    expect(placesOf(movePlaces(member, [a, b], 'member', 'home'))).toEqual(['a:self', 'b:self'])
    expect(moveHereBlock(member, [a, b], 'member', 'home'), 'every one is here already').toBe('current')
    expect(moveHereBlock(member, [a, b], 'member', 'a1'), 'one of them holds the place').toBe('inside')
    expect(moveHereBlock(member, [a, byId('item2')], 'member', null), 'one is here, the other is not: it may be chosen').toBeNull()
    expect(moveStart(member, [a, byId('item2')]), 'from two places: the top').toBeNull()
    // an ITEM is listed for a chapter-like kind alone, never once a folder is among the moved
    expect(placesOf(movePlaces(member, [byId('part'), a], 'member', null))).toEqual(['home'])
    expect(moveHereBlock(member, [byId('part'), byId('a1')], 'member', 'item2')).toBe('kind')
  })
})

describe('the browser for a copy (uc.platform.copy-node): the current place is allowed, the node and its inside are not', () => {
  const copyAt = (node: TreeNode, ats: (string | null)[]) =>
    ats.map((at) => {
      const block = moveHereBlock(member, [node], 'member', at, 'copy')
      return `${at ?? 'ROOT'}${block ? `:${block}` : ''}`
    })

  it('a folder may be copied where it is (the copy is `Name (2)` there), never into itself or below it', () => {
    const a = byId('a')
    expect(copyAt(a, [null, 'home', 'b', 'a', 'a1'])).toEqual(['ROOT', 'home', 'b', 'a:self', 'a1:inside'])
    expect(placesOf(movePlaces(member, [a], 'member', 'home')), 'itself listed, never to be opened').toEqual(['a:self', 'b'])
  })

  it('a chapter-like kind may be copied into its own ITEM, never where its placement does not reach; nothing into a guest\'s folder', () => {
    expect(copyAt(byId('part'), ['item', 'item2', null, 'b'])).toEqual(['item', 'item2', 'ROOT:kind', 'b:kind'])
    expect(copyAt(byId('a'), ['guest', 'inner'])).toEqual(['guest:identityFolder', 'inner:identityFolder'])
  })

  it('several: their own level is allowed; a place ANY of them holds is blocked, naming it', () => {
    const [a, b] = [byId('a'), byId('b')]
    expect(moveHereBlockOf(member, [a, b], 'member', 'home', 'copy')).toBeNull()
    expect(moveHereBlockOf(member, [a, b], 'member', 'a1', 'copy')).toEqual({ block: 'inside', node: a })
    expect(moveHereBlockOf(member, [a, b], 'member', 'home'), 'a move there is still refused').toEqual({ block: 'current', node: null })
  })
})

describe('the move browser: what a guest is offered (its subtree, its folders)', () => {
  // A guest's view rooted at `home`: the served root carries parentId null; nothing above it exists.
  const sub = NODES.filter((x) => x.id !== 'item2').map((x) => (x.id === 'home' ? { ...x, parentId: null } : x))
  const guest: TreeSource<TreeNode> = { ...memberTreeOf(() => sub), rootId: 'home' }
  const inSub = (id: string) => sub.find((x) => x.id === id)!

  it('the trail starts at the share root, never the scope root; only folders are listed and take a guest\'s child', () => {
    const item = inSub('item')
    expect(moveStart(guest, [item])).toBe('b')
    expect(moveTrail(guest, 'b')).toEqual(['home', 'b'])
    expect(moveTrail(guest, 'home')).toEqual(['home'])
    expect(placesOf(movePlaces(guest, [item], 'guest', 'home'))).toEqual(['a', 'b'])
    expect(placesOf(movePlaces(guest, [inSub('part')], 'guest', 'b')), 'an ITEM is no guest\'s place').toEqual([])
    expect(hereAt(guest, item, 'guest', ['home', 'a', 'a1', 'b'])).toEqual(['home', 'a', 'a1', 'b:current'])
  })

  it('a node whose parent lies above the link opens at the share root', () => {
    expect(moveStart(guest, [n('stray', FOLDER_KIND, 'elsewhere')])).toBe('home')
  })

  it('another ITEM that would take a PART for a member is no guest\'s destination (only a folder lets a guest\'s child in)', () => {
    const withTwo = [...sub, n('item3', ITEM, 'a')]
    const g: TreeSource<TreeNode> = { ...memberTreeOf(() => withTwo), rootId: 'home' }
    const part = withTwo.find((x) => x.id === 'part')!
    expect(moveHereBlock(g, [part], 'guest', 'item3')).toBe('kind')
    // control: the same place is a member's destination, and listed for it
    expect(moveHereBlock(memberTreeOf(() => withTwo), [part], 'member', 'item3')).toBeNull()
    expect(placesOf(movePlaces(memberTreeOf(() => withTwo), [part], 'member', 'a'))).toEqual(['a1', 'item3'])
  })

  it('a chapter-like kind has nowhere to go for a guest: no folder takes it', () => {
    expect(hereAt(guest, inSub('part'), 'guest', ['home', 'a', 'a1', 'b', 'item'])).toEqual(['home:kind', 'a:kind', 'a1:kind', 'b:kind', 'item:current'])
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

// inbox #421: a level's selection moves through the same browser and door - N single-node writes, ONE re-read.
describe('several at once: the place, the pre-check, the writes', () => {
  it('a place is blocked where ANY node cannot go, naming that node; "already here" only when all of them are', () => {
    const [a, b, item] = [byId('a'), byId('b'), byId('item')]
    expect(moveHereBlockOf(member, [a, b], 'member', 'home')).toEqual({ block: 'current', node: null })
    expect(moveHereBlockOf(member, [a, b], 'member', 'a1'), 'a holds the place').toEqual({ block: 'inside', node: a })
    expect(moveHereBlockOf(member, [a, b], 'member', 'b'), 'b is the place').toEqual({ block: 'self', node: b })
    expect(moveHereBlockOf(member, [a, byId('part')], 'member', 'item2'), 'an ITEM takes the PART, not the folder').toEqual({ block: 'kind', node: a })
    expect(moveHereBlockOf(member, [a, byId('a1')], 'member', 'inner'), 'nothing goes into a guest\'s folder').toMatchObject({ block: 'identityFolder' })
    // a node already there blocks nothing: the pre-check leaves it out
    expect(moveHereBlockOf(member, [a, item], 'member', 'b'), 'item is in b already, a may go there').toBeNull()
  })

  it('the pre-check leaves out a node already there and one whose name folds to a sibling\'s there, naming the sibling', () => {
    const tree = [...NODES, n('dest', FOLDER_KIND, 'home'), n('taken', ITEM, 'dest', { name: 'ITEM2' })]
    const t2 = memberTreeOf(() => tree)
    const at = (id: string) => tree.find((x) => x.id === id)!
    const plan = planMove(t2, [at('a'), at('item2'), at('b')], 'dest')
    expect(plan.send.map((x) => x.id)).toEqual(['a', 'b'])
    expect(plan.leftOut).toEqual([{ node: at('item2'), reason: 'nameTaken', sibling: at('taken') }])
    const here = planMove(t2, [at('a'), at('item2')], 'home')
    expect(here.send.map((x) => x.id)).toEqual(['item2'])
    expect(here.leftOut).toEqual([{ node: at('a'), reason: 'current' }])
  })

  it('a member: one placement write per node, in order, and the tree re-read ONCE after the last', async () => {
    const epochs: EpochKeys = { current: 0, keys: new Map() }
    const out = await nodeMover(() => memberAccessOf({ epochs }))!.moveMany([{ nodeId: 'a', to: 'b' }, { nodeId: 'item2', to: 'b' }, { nodeId: 'box', to: 'b' }])
    expect((calls.moveNode.mock.calls as unknown[][]).map((c) => [c[3], c[4]])).toEqual([['a', 'b'], ['item2', 'b'], ['box', 'b']])
    expect(calls.moveNode).toHaveBeenCalledWith('scope-1', epochs, expect.any(Array), 'a', 'b')
    expect(calls.loadTree).toHaveBeenCalledTimes(1)
    expect(out).toEqual([{ nodeId: 'a', error: null }, { nodeId: 'item2', error: null }, { nodeId: 'box', error: null }])
  })

  it('a refusal mid-way leaves the earlier moves, still sends the rest, and says per node how it ended', async () => {
    const refused = new GuestTreeError('slug_taken')
    calls.moveNode.mockImplementationOnce(async () => {}).mockRejectedValueOnce(refused)
    const out = await nodeMover(() => memberAccessOf({ epochs: { current: 0, keys: new Map() } }))!.moveMany([
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
