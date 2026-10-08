// A placement write on the wire (ADR 0080 Decision 3): recorded at `fetch` — the JSON the real generated client
// serializes, not the object handed to it — so a field the client dropped or renamed fails here. A rename carries the
// whole placement (parent, re-wrapped key, re-sealed name, slug token, epoch, the version it was built on), and the
// wrap it sends opens only under the destination parent's key at the NEXT version. A restore's token and name are
// derived under the live parent's key (ADR 0080 Decision 8). A listing's `createdAt` reaches the tree (ADR 0015). No
// mock of the API client.
import { describe, it, expect, vi, afterEach } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { createContentNode, listTree, moveNode, renameNode, restoreNode, type TreeNode } from './tree'
import { epochsOf } from '../../features/spaces/epochsTestUtil'
import { registry } from '../applications/registry'
import { fileNameSlug } from '../../shared/naming'
import { treeNode } from '../../test/models'

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
const row = (id: string, parentId: string | null, kek: Uint8Array, k: Uint8Array, name: string): TreeNode => treeNode({
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
  const trashed = { id: M, parentId: A, kind: 'MEMORY', epoch: 0, placementVersion: 3, name: 'Italien' }

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

// Inbox #350: a node's slug token is folded by ITS kind's function, on every write of it. A test kind registers the
// file folding (`fileNameSlug`) as its hook — this node-environment suite gets no application registry, so it registers
// on the app's registry itself; the FOLDER keeps `slugify`. Each write's token is compared to both foldings, so a
// caller that passes the wrong kind (or none) is told apart from one that passes the right one.
describe('a slug token is folded by its node\'s kind (inbox #350)', () => {
  const LEAF = 'LEAFTEST'
  registry.registerKind({ kind: LEAF, icon: 'pi pi-file', shareable: false, slug: fileNameSlug })
  const NAME = 'Day1.MD'
  const leafToken = (key: Uint8Array, slot: string) => b64std(v1.slugToken(key, slot, 'day1.md'))
  const folderToken = (key: Uint8Array, slot: string) => b64std(v1.slugToken(key, slot, 'day1-md'))
  const leafRow = (): TreeNode => ({ ...row(M, A, keyA, keyM, 'Italien'), kind: LEAF })
  function recordCreate() {
    const bodies: Record<string, unknown>[] = []
    vi.stubGlobal('fetch', vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      const json = JSON.parse(String(init?.body)) as Record<string, unknown>
      bodies.push(json)
      return new Response(JSON.stringify({ nodeId: json.nodeId }), { status: 201, headers: { 'Content-Type': 'application/json' } })
    }))
    return bodies
  }

  it('a create folds with the created kind; a folder of the same name keeps slugify', async () => {
    const bodies = recordCreate()
    await createContentNode(SCOPE_ID, epochsOf(scopeKey), { id: A, key: keyA }, LEAF, NAME)
    await createContentNode(SCOPE_ID, epochsOf(scopeKey), { id: A, key: keyA }, 'FOLDER', NAME)
    expect(bodies[0].slugToken).toBe(leafToken(keyA, A))
    expect(bodies[1].slugToken).toBe(folderToken(keyA, A))
    expect(bodies[0].slugToken).not.toBe(bodies[1].slugToken)
  })

  it('a rename and a move fold with the node\'s own kind', async () => {
    const bodies = recordFetch()
    const t = [...tree().filter((n) => n.id !== M), leafRow()]
    await renameNode(SCOPE_ID, epochsOf(scopeKey), t, M, NAME)
    await moveNode(SCOPE_ID, epochsOf(scopeKey), [...tree().filter((n) => n.id !== M), { ...leafRow(), name: NAME }], M, B)
    expect(bodies[0].json.slugToken).toBe(leafToken(keyA, A))
    expect(bodies[0].json.slugToken).not.toBe(folderToken(keyA, A))
    expect(bodies[1].json.slugToken).toBe(leafToken(keyB, B))
  })

  it('a restore folds with the trashed node\'s kind, on the plain restore and the taken-name retry', async () => {
    const bodies = recordFetch()
    const trashed = { id: M, parentId: A, kind: LEAF, epoch: 0, placementVersion: 3, name: NAME }
    await restoreNode(SCOPE_ID, epochsOf(scopeKey), tree(), trashed)
    await restoreNode(SCOPE_ID, epochsOf(scopeKey), tree(), trashed, 'FOTO.jpg')
    expect(bodies[0].json.slugToken).toBe(leafToken(keyA, A))
    expect(bodies[1].json.slugToken).toBe(b64std(v1.slugToken(keyA, A, 'foto.jpg')))
    await restoreNode(SCOPE_ID, epochsOf(scopeKey), tree(), { ...trashed, kind: 'FOLDER' })
    expect(bodies[2].json.slugToken).toBe(folderToken(keyA, A))
  })
})

// The listing on the wire (ADR 0015): the server's `createdAt` reaches the TreeNode as epoch milliseconds, every digit of
// the millisecond kept — the instant siblings are ordered by. Served as JSON at `fetch`, read through the real client.
describe('a listing on the wire', () => {
  it('carries each node\'s server-observed createdAt to the millisecond', async () => {
    const name = b64std(v1.seal({ purpose: 'node-name', scopeId: SCOPE_ID, nodeId: A, epoch: 0, placementVersion: 3 }, scopeKey, v1.utf8('Reisen')))
    const summary = {
      id: A, kind: 'FOLDER', encryptedName: name, slugToken: 'c2x1Zw==', orderIndex: 0, epoch: 0,
      wrappedContentKey: sealWrap(scopeKey, A, keyA), metaVersion: 0, placementVersion: 3, createdAt: '2026-10-08T10:15:30.123Z',
    }
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify([summary]), { status: 200, headers: { 'Content-Type': 'application/json' } })))

    const [listed] = await listTree(SCOPE_ID, epochsOf(scopeKey))

    expect(listed.name).toBe('Reisen')
    expect(listed.createdAt).toBe(Date.UTC(2026, 9, 8, 10, 15, 30, 123))
  })
})
