// A placement write on the wire (ADR 0080 Decision 3): recorded at `fetch` — the JSON the real generated client
// serializes, not the object handed to it — so a field the client dropped or renamed fails here. A rename carries the
// whole placement (parent, re-wrapped key, re-sealed name, slug token, epoch, the version it was built on), and the
// wrap it sends opens only under the destination parent's key at the NEXT version. A restore's token and name are
// derived under the live parent's key (ADR 0080 Decision 8). No mock of the API client.
import { describe, it, expect, vi, afterEach } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { moveNode, renameNode, restoreNode, type TreeNode } from './tree'
import { epochsOf } from '../../features/spaces/epochsTestUtil'

const SCOPE_ID = '11111111-1111-1111-1111-111111111111'
const A = '5c09e000-0000-4000-8000-0000000000a0'
const B = '5c09e000-0000-4000-8000-0000000000b0'
const M = '5c09e000-0000-4000-8000-0000000000c0'

afterEach(() => vi.unstubAllGlobals())

const scopeKey = v1.randomBytes(32)
const keyA = v1.randomBytes(32)
const keyB = v1.randomBytes(32)
const keyM = v1.randomBytes(32)
const at = (placementVersion: number) => ({ scopeId: SCOPE_ID, nodeId: M, epoch: 0, placementVersion })
const sealWrap = (kek: Uint8Array, id: string, k: Uint8Array) =>
  b64std(v1.seal({ purpose: 'content-key', scopeId: SCOPE_ID, nodeId: id, epoch: 0, placementVersion: 3 }, kek, k))
const row = (id: string, parentId: string | null, kek: Uint8Array, k: Uint8Array, name: string): TreeNode => ({
  id, parentId, kind: parentId === null ? 'FOLDER' : 'MEMORY', orderIndex: 0, epoch: 0, name, wrappedContentKey: sealWrap(kek, id, k),
  wrappedMeta: null, metaVersion: 0, placementVersion: 3,
})
// Every node at version 3, so the next one (4) is told apart from both 0 and the one built on.
const tree = (): TreeNode[] => [row(A, null, scopeKey, keyA, 'Reisen'), row(B, null, scopeKey, keyB, 'Archiv'), row(M, A, keyA, keyM, 'Italien')]

function recordFetch() {
  const bodies: { url: string; method: string; json: Record<string, unknown> }[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    bodies.push({ url: String(input), method: init?.method ?? 'GET', json: JSON.parse(String(init?.body)) as Record<string, unknown> })
    return new Response(null, { status: 204 })
  }))
  return bodies
}

describe('a placement write on the wire (ADR 0080 Decision 3)', () => {
  it('a rename carries the whole placement, its wrap opening only under the parent\'s key at version + 1', async () => {
    const bodies = recordFetch()

    await renameNode(SCOPE_ID, epochsOf(scopeKey), tree(), M, 'Neapel')

    expect(bodies).toHaveLength(1)
    const { url, method, json } = bodies[0]
    expect(method).toBe('PATCH')
    expect(url).toContain(`/api/scopes/${SCOPE_ID}/nodes/${M}`)
    expect(Object.keys(json).sort()).toEqual(['encryptedName', 'epoch', 'parentId', 'placementVersion', 'slugToken', 'wrappedContentKey'])
    expect(json).toMatchObject({ parentId: A, epoch: 0, placementVersion: 3 })
    const wrap = fromB64std(json.wrappedContentKey as string)
    expect(Array.from(v1.open({ purpose: 'content-key', ...at(4) }, keyA, wrap))).toEqual(Array.from(keyM))
    expect(() => v1.open({ purpose: 'content-key', ...at(3) }, keyA, wrap)).toThrow(v1.OpenRefused)
    expect(() => v1.open({ purpose: 'content-key', ...at(4) }, scopeKey, wrap)).toThrow(v1.OpenRefused)
    const name = fromB64std(json.encryptedName as string)
    expect(new TextDecoder().decode(v1.open({ purpose: 'node-name', ...at(4) }, keyA, name))).toBe('Neapel')
    expect(json.slugToken).toBe(b64std(v1.slugToken(keyA, A, 'neapel')))
  })

  it('a move\'s wrap opens under the destination parent\'s key at version + 1, and under no other', async () => {
    const bodies = recordFetch()

    await moveNode(SCOPE_ID, epochsOf(scopeKey), tree(), M, B)

    const { json } = bodies[0]
    expect(json).toMatchObject({ parentId: B, placementVersion: 3 })
    const wrap = fromB64std(json.wrappedContentKey as string)
    expect(Array.from(v1.open({ purpose: 'content-key', ...at(4) }, keyB, wrap))).toEqual(Array.from(keyM))
    for (const [key, version] of [[keyA, 4], [keyB, 3], [scopeKey, 4]] as const) {
      expect(() => v1.open({ purpose: 'content-key', ...at(version) }, key, wrap)).toThrow(v1.OpenRefused)
    }
  })
})

// ADR 0080 Decision 8: a restore's slug token — and, on the taken-name retry, its re-sealed name — is derived under the
// LIVE parent's key, opened from the tree as it is now. Here the parent A was moved under B while M sat in the trash, so
// A's own key is reached through B; neither B's key nor K_SC (the node's epoch key) may stand in for it. A restore is
// not a placement: the name binds the version M already has (3), and its key wrap is not sent at all.
describe('a restore on the wire (ADR 0080 Decision 8)', () => {
  const sealAt = (kek: Uint8Array, id: string, k: Uint8Array, version: number) =>
    b64std(v1.seal({ purpose: 'content-key', scopeId: SCOPE_ID, nodeId: id, epoch: 0, placementVersion: version }, kek, k))
  const liveTree = (): TreeNode[] => [
    { ...row(B, null, scopeKey, keyB, 'Archiv') },
    // A moved under B: its key is now wrapped under B's, at the version the move took it to
    { ...row(A, B, keyB, keyA, 'Reisen'), kind: 'FOLDER', placementVersion: 5, wrappedContentKey: sealAt(keyB, A, keyA, 5) },
  ]
  const trashed = { id: M, parentId: A, epoch: 0, placementVersion: 3, name: 'Italien' }

  it('an ordinary restore sends only the slug token, derived under the live parent\'s key', async () => {
    const bodies = recordFetch()

    await restoreNode(SCOPE_ID, epochsOf(scopeKey), liveTree(), trashed)

    expect(bodies).toHaveLength(1)
    const { url, method, json } = bodies[0]
    expect(method).toBe('POST')
    expect(url).toContain(`/api/scopes/${SCOPE_ID}/nodes/${M}/restore`)
    expect(Object.keys(json)).toEqual(['slugToken'])
    expect(json.slugToken).toBe(b64std(v1.slugToken(keyA, A, 'italien')))
    for (const stale of [keyB, scopeKey]) expect(json.slugToken).not.toBe(b64std(v1.slugToken(stale, A, 'italien')))
  })

  it('the taken-name retry seals the new name under the live parent\'s key at the node\'s own version', async () => {
    const bodies = recordFetch()

    await restoreNode(SCOPE_ID, epochsOf(scopeKey), liveTree(), trashed, 'Neapel')

    const { json } = bodies[0]
    expect(Object.keys(json).sort()).toEqual(['encryptedName', 'slugToken'])
    expect(json.slugToken).toBe(b64std(v1.slugToken(keyA, A, 'neapel')))
    const name = fromB64std(json.encryptedName as string)
    expect(new TextDecoder().decode(v1.open({ purpose: 'node-name', ...at(3) }, keyA, name))).toBe('Neapel')
    for (const [key, version] of [[keyA, 4], [keyA, 0], [keyB, 3], [scopeKey, 3]] as const) {
      expect(() => v1.open({ purpose: 'node-name', ...at(version) }, key, name)).toThrow(v1.OpenRefused)
    }
  })
})
