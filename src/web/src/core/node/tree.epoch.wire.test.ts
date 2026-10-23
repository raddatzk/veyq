// Every write that seals under a node's key states that node's epoch (ADR 0109 point 6), recorded at `fetch` — the JSON
// the real generated client serializes, so a field it drops or renames fails here. The scope is rotated (current epoch
// 2) and the nodes carry DIFFERENT epochs, so a write that states the wrong one (the scope's, the node's for the
// parent's) is told apart: a create states its parent's epoch (`parentEpoch`); a placement keeps the node's own epoch and
// states the destination's. And the device floor (layer 2): a member never seals under a key from before the last
// rotation it holds — such a write is refused before anything is sent.
import { describe, it, expect, vi, afterEach } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { createContentNode, moveNode, type TreeNode } from './tree'
import { NodeKeyBelowFloorError } from './rekey/floor'
import { treeNode } from '../../test/models'
import type { EpochKeys } from '../../features/spaces/epochs'

const SCOPE_ID = '11111111-1111-1111-1111-111111111111'
const A = '5c09e000-0000-4000-8000-0000000000a0'
const B = '5c09e000-0000-4000-8000-0000000000b0'
const M = '5c09e000-0000-4000-8000-0000000000c0'

afterEach(() => vi.unstubAllGlobals())

const k1 = v1.randomBytes(32)
const k2 = v1.randomBytes(32)
const epochs = (): EpochKeys => ({ current: 2, keys: new Map([[1, k1], [2, k2]]) })
const keyA = v1.randomBytes(32)
const keyB = v1.randomBytes(32)
const keyM = v1.randomBytes(32)
const REV = 3
let reads = 0
const wrap = (kek: Uint8Array, id: string, epoch: number, k: Uint8Array) =>
  b64std(v1.seal({ purpose: 'content-key', scopeId: SCOPE_ID, nodeId: id, epoch, placementRevision: REV }, kek, k))
const row = (id: string, parentId: string | null, epoch: number, kek: Uint8Array, k: Uint8Array, name: string): TreeNode => treeNode({
  id, parentId, kind: 'FOLDER', orderIndex: 0, epoch, name, wrappedContentKey: wrap(kek, id, epoch, k),
  wrappedMeta: null, metaRevision: 0, placementRevision: REV,
})
/** A and B re-keyed to the current epoch 2 at the root; M below A still at epoch 1 (its key minted before the rotation). */
const tree = (mEpoch = 1): TreeNode[] => [
  row(A, null, 2, k2, keyA, 'Reisen'), row(B, null, 2, k2, keyB, 'Archiv'), row(M, A, mEpoch, keyA, keyM, 'Italien'),
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

describe('a write states the epoch of the key it seals under (ADR 0109 point 6)', () => {
  it('a create under a parent states the parent\'s epoch as parentEpoch, and returns the epoch its key is minted at', async () => {
    const bodies = recordFetch(201)

    const made = await createContentNode(SCOPE_ID, epochs(), { id: A, key: keyA, epoch: 2 }, 'FOLDER', 'Neapel')

    expect(bodies[0]).toMatchObject({ parentId: A, parentEpoch: 2, epoch: 2 })
    expect(made.epoch).toBe(2)
  })

  it('a create at the scope root states no parentEpoch', async () => {
    const bodies = recordFetch(201)
    await createContentNode(SCOPE_ID, epochs(), null, 'FOLDER', 'Neapel')
    expect('parentEpoch' in bodies[0]).toBe(false)
    expect(bodies[0].epoch).toBe(2)
  })

  it('a move between folders keeps the node\'s own epoch and states the destination\'s as parentEpoch', async () => {
    const bodies = recordFetch()

    await moveNode(SCOPE_ID, epochs(), tree(), M, B)

    expect(bodies[0]).toMatchObject({ parentId: B, epoch: 1, parentEpoch: 2, placementRevision: REV })
    // sealed at the node's own epoch (1), under the destination's key — never at the scope's current one
    const sealed = fromB64std(bodies[0].wrappedContentKey as string)
    const at = (epoch: number) => ({ purpose: 'content-key', scopeId: SCOPE_ID, nodeId: M, epoch, placementRevision: REV + 1 }) as const
    expect(Array.from(v1.open(at(1), keyB, sealed))).toEqual(Array.from(keyM))
    expect(() => v1.open(at(2), keyB, sealed)).toThrow(v1.OpenRefused)
  })

  it('a move into the scope root of a re-keyed node seals under K_SC at its epoch and states no parentEpoch', async () => {
    const bodies = recordFetch()

    await moveNode(SCOPE_ID, epochs(), tree(2), M, null)

    expect(bodies[0].epoch).toBe(2)
    expect('parentEpoch' in bodies[0]).toBe(false)
    const sealed = fromB64std(bodies[0].wrappedContentKey as string)
    const at = { purpose: 'content-key', scopeId: SCOPE_ID, nodeId: M, epoch: 2, placementRevision: REV + 1 } as const
    expect(Array.from(v1.open(at, k2, sealed))).toEqual(Array.from(keyM))
  })
})

describe('the device floor (ADR 0109 point 6, layer 2): no seal under a key from before the last rotation', () => {
  it('a create under a parent below the current epoch is refused before anything is sent; at the current epoch it seals', async () => {
    const bodies = recordFetch(201)

    await expect(createContentNode(SCOPE_ID, epochs(), { id: A, key: keyA, epoch: 1 }, 'FOLDER', 'Neapel'))
      .rejects.toBeInstanceOf(NodeKeyBelowFloorError)
    expect(bodies, 'nothing sealed under the old key left the device').toHaveLength(0)

    await createContentNode(SCOPE_ID, epochs(), { id: A, key: keyA, epoch: 2 }, 'FOLDER', 'Neapel')
    expect(bodies).toHaveLength(1)
  })

  it('a move into a destination below the current epoch is refused before sending, and waited on like a pending re-key', async () => {
    const bodies = recordFetch()
    const lagging = [row(A, null, 2, k2, keyA, 'Reisen'), row(B, null, 1, k1, keyB, 'Archiv'), row(M, A, 2, keyA, keyM, 'Italien')]

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

  it('a move into the scope root of a node below the current epoch is refused before sending', async () => {
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
