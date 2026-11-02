// Every write that seals under a node's key states that node's own epoch (ADR 0114 decision 3), recorded at `fetch` —
// the JSON the real generated client serializes, so a field it drops or renames fails here. The scope is rotated
// (current epoch 2, its one key K_2; K_1 replaced) and every node's own epoch differs from its scope epoch and from the
// scope's, so a write that states the wrong one (the scope's, the node's scope epoch, the node's for the parent's) is
// told apart: a create states the scope epoch its key is minted under (`scopeEpoch`) and its parent's own epoch
// (`parentEpoch`); a placement keeps the node's own epoch and states the destination's; every seal binds the node's
// `scopeEpoch`. And the device floor (layer 2): a member never seals under a key minted before the last rotation it holds
// — such a write is refused before anything is sent.
import { describe, it, expect, vi, afterEach } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { createContentNode, moveNode, openName, type TreeNode } from './tree'
import { NodeKeyBelowFloorError } from './rekey/floor'
import { treeNode } from '../../test/models'
import type { ScopeKey } from '../../features/spaces/epochs'

const SCOPE_ID = '11111111-1111-1111-1111-111111111111'
const A = '5c09e000-0000-4000-8000-0000000000a0'
const B = '5c09e000-0000-4000-8000-0000000000b0'
const M = '5c09e000-0000-4000-8000-0000000000c0'

afterEach(() => vi.unstubAllGlobals())

const k1 = v1.randomBytes(32)
const k2 = v1.randomBytes(32)
const epochs = (): ScopeKey => ({ epoch: 2, key: k2 })
const keyA = v1.randomBytes(32)
const keyB = v1.randomBytes(32)
const keyM = v1.randomBytes(32)
const REV = 3
let reads = 0
/** The `content-key` seal context: its binding field `epoch` holds the node's scopeEpoch. */
const keyCtx = (id: string, scopeEpoch: number, placementRevision = REV) =>
  ({ purpose: 'content-key', scopeId: SCOPE_ID, nodeId: id, epoch: scopeEpoch, placementRevision }) as const
const wrap = (kek: Uint8Array, id: string, scopeEpoch: number, k: Uint8Array) => b64std(v1.seal(keyCtx(id, scopeEpoch), kek, k))
const row = (id: string, parentId: string | null, epoch: number, scopeEpoch: number, kek: Uint8Array, k: Uint8Array, name: string): TreeNode =>
  treeNode({
    id, parentId, kind: 'FOLDER', orderIndex: 0, epoch, scopeEpoch, name, wrappedContentKey: wrap(kek, id, scopeEpoch, k),
    wrappedMeta: null, metaRevision: 0, placementRevision: REV,
  })
/** A (own epoch 3) and B (own 4) re-keyed under scope epoch 2 at the root, under K_2; M (own 5) below A, its key minted
 *  under scope epoch [mScopeEpoch] (1: before the rotation). */
const tree = (mScopeEpoch = 1): TreeNode[] => [
  row(A, null, 3, 2, k2, keyA, 'Reisen'), row(B, null, 4, 2, k2, keyB, 'Archiv'), row(M, A, 5, mScopeEpoch, keyA, keyM, 'Italien'),
]

/** Records every write's body; a read (the tree re-read a retry does) is counted in `reads` and answered 503. */
function recordFetch(status = 204) {
  const bodies: Record<string, unknown>[] = []
  reads = 0
  vi.stubGlobal('fetch', vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
    if (!init?.body) {
      reads++
      return new Response(null, { status: 503 })
    }
    const json = JSON.parse(String(init?.body)) as Record<string, unknown>
    bodies.push(json)
    return status === 201
      ? new Response(JSON.stringify({ nodeId: json.nodeId }), { status, headers: { 'Content-Type': 'application/json' } })
      : new Response(null, { status })
  }))
  return bodies
}

describe('a write states the epoch of the key it seals under (ADR 0114 decision 3, ADR 0115 decision 4)', () => {
  it('a create under a parent states the scope epoch its key is minted under and the parent\'s own epoch; it returns own epoch 0', async () => {
    const bodies = recordFetch(201)

    const made = await createContentNode(SCOPE_ID, epochs(), { id: A, key: keyA, epoch: 3, scopeEpoch: 2 }, 'FOLDER', 'Neapel')

    expect(bodies[0]).toMatchObject({ parentId: A, parentEpoch: 3, scopeEpoch: 2 })
    expect('epoch' in bodies[0], 'a create states no own epoch: a new key is at 0').toBe(false)
    expect(made).toMatchObject({ epoch: 0, scopeEpoch: 2 })
    // its seals bind the scope epoch it is minted under, at placement revision 0 — not its own epoch 0
    const sealed = fromB64std(bodies[0].wrappedContentKey as string)
    expect(Array.from(v1.open(keyCtx(made.nodeId, 2, 0), keyA, sealed))).toEqual(Array.from(made.contentKey))
    expect(() => v1.open(keyCtx(made.nodeId, 0, 0), keyA, sealed)).toThrow(v1.OpenRefused)
    const address = { scopeId: SCOPE_ID, nodeId: made.nodeId, scopeEpoch: 2, placementRevision: 0 }
    expect(openName(keyA, address, bodies[0].encryptedName as string)).toBe('Neapel')
  })

  it('a create at the scope root states no parentEpoch, its scope epoch, and seals under the one key held', async () => {
    const bodies = recordFetch(201)
    const made = await createContentNode(SCOPE_ID, epochs(), null, 'FOLDER', 'Neapel')
    expect('parentEpoch' in bodies[0]).toBe(false)
    expect('epoch' in bodies[0]).toBe(false)
    expect(bodies[0].scopeEpoch).toBe(2)
    expect(made).toMatchObject({ epoch: 0, scopeEpoch: 2 })
    const sealed = fromB64std(bodies[0].wrappedContentKey as string)
    expect(Array.from(v1.open(keyCtx(made.nodeId, 2, 0), k2, sealed))).toEqual(Array.from(made.contentKey))
    expect(() => v1.open(keyCtx(made.nodeId, 2, 0), k1, sealed), 'never under the replaced key').toThrow(v1.OpenRefused)
  })

  it('a move between folders keeps the node\'s own epoch and states the destination\'s as parentEpoch', async () => {
    const bodies = recordFetch()

    await moveNode(SCOPE_ID, epochs(), tree(), M, B)

    expect(bodies[0]).toMatchObject({ parentId: B, epoch: 5, parentEpoch: 4, placementRevision: REV })
    // bound to the node's scope epoch (1), under the destination's key — never the scope's current one, nor its own epoch
    const sealed = fromB64std(bodies[0].wrappedContentKey as string)
    expect(Array.from(v1.open(keyCtx(M, 1, REV + 1), keyB, sealed))).toEqual(Array.from(keyM))
    expect(() => v1.open(keyCtx(M, 2, REV + 1), keyB, sealed)).toThrow(v1.OpenRefused)
    expect(() => v1.open(keyCtx(M, 5, REV + 1), keyB, sealed)).toThrow(v1.OpenRefused)
  })

  it('a move into the scope root of a re-keyed node seals under the scope key bound to its scope epoch and states no parentEpoch', async () => {
    const bodies = recordFetch()

    await moveNode(SCOPE_ID, epochs(), tree(2), M, null)

    expect(bodies[0].epoch).toBe(5)
    expect('parentEpoch' in bodies[0]).toBe(false)
    const sealed = fromB64std(bodies[0].wrappedContentKey as string)
    expect(Array.from(v1.open(keyCtx(M, 2, REV + 1), k2, sealed))).toEqual(Array.from(keyM))
  })
})

describe('the device floor (ADR 0109 point 6, layer 2): no seal under a key from before the last rotation', () => {
  it('a create under a parent whose key is from a scope epoch below the held one is refused before anything is sent; at the held epoch it seals', async () => {
    const bodies = recordFetch(201)

    // the parent's own epoch is high (replaced often), but its key was minted under scope epoch 1: the floor reads that
    await expect(createContentNode(SCOPE_ID, epochs(), { id: A, key: keyA, epoch: 7, scopeEpoch: 1 }, 'FOLDER', 'Neapel'))
      .rejects.toBeInstanceOf(NodeKeyBelowFloorError)
    expect(bodies, 'nothing sealed under the old key left the device').toHaveLength(0)

    // control: a parent at own epoch 0 whose key is from the held scope epoch
    await createContentNode(SCOPE_ID, epochs(), { id: A, key: keyA, epoch: 0, scopeEpoch: 2 }, 'FOLDER', 'Neapel')
    expect(bodies).toHaveLength(1)
  })

  it('a move into a destination below the held scope epoch is refused before sending, and waited on like a pending re-key', async () => {
    const bodies = recordFetch()
    // B's entry the rotation re-sealed under K_2, its key still from scope epoch 1
    const lagging = [row(A, null, 3, 2, k2, keyA, 'Reisen'), row(B, null, 4, 1, k2, keyB, 'Archiv'), row(M, A, 5, 2, keyA, keyM, 'Italien')]

    vi.useFakeTimers() // the wait before the re-read
    try {
      const moved = moveNode(SCOPE_ID, epochs(), lagging, M, B).catch((e: unknown) => e)
      await vi.runAllTimersAsync()
      await moved
    } finally {
      vi.useRealTimers()
    }
    expect(bodies, 'nothing sealed under the old destination key left the device').toHaveLength(0)
    expect(reads, 'the refusal was waited on and the tree re-read, as for node_rekey_pending').toBe(1)
  })

  it('a move into the scope root of a node below the held scope epoch is refused before sending', async () => {
    const bodies = recordFetch()
    vi.useFakeTimers()
    try {
      const moved = moveNode(SCOPE_ID, epochs(), tree(1), M, null).catch((e: unknown) => e)
      await vi.runAllTimersAsync()
      await moved
    } finally {
      vi.useRealTimers()
    }
    expect(bodies).toHaveLength(0)
    expect(reads).toBe(1)
  })
})
