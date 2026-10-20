// A folder share on the wire (cr-node-key-hierarchy 5b.3, ADR 0080 decisions 1 and 12): the key the shell hands core's
// folder dialog is the folder's own, opened through the key chain, and the mint request — recorded at `fetch`, the JSON
// the real generated client serializes, no mock of the API client — carries the recovery copy (`sealedShareKey` +
// `sealedShareKeyEpoch`), a `wrap` that opens under `K_share` to THAT folder key (not `K_SC`, not the parent's), and
// the folder's name sealed under `K_share`. The folder sits one level down so the chain, not the scope key, yields it.
import { describe, it, expect, vi, afterEach } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { nodeContentKey, type TreeNode } from '../node/tree'
import { FOLDER_KIND } from '../node/kind'
import { mintShare, recoverShareKey } from './manageShares'
import { SHARE_KEY } from './shareTree'
import { SHARE_MEMORY_NAME } from './resolvePublicShare'
import { ShareRole } from '../../api/generated/sharing'
import type { EpochKeys } from '../../features/spaces/epochs'
import { treeNode } from '../../test/models'

const SCOPE_ID = '22222222-2222-4222-8222-222222222222'
const TOP = '5c09e000-0000-4000-8000-0000000000a1'
const SUB = '5c09e000-0000-4000-8000-0000000000a2'

afterEach(() => vi.unstubAllGlobals())

const k0 = v1.randomBytes(32)
const k1 = v1.randomBytes(32)
// A rotated scope: the top folder was made at epoch 0, the copy must still seal under the WRITE key (epoch 1).
const EPOCHS: EpochKeys = { current: 1, keys: new Map([[0, k0], [1, k1]]) }
const keyTop = v1.randomBytes(32)
const keySub = v1.randomBytes(32)
const wrapUnder = (kek: Uint8Array, id: string, k: Uint8Array) =>
  b64std(v1.seal({ purpose: 'content-key', scopeId: SCOPE_ID, nodeId: id, epoch: 0, placementRevision: 0 }, kek, k))
const folder = (id: string, parentId: string | null, kek: Uint8Array, k: Uint8Array, name: string): TreeNode => treeNode({
  id, parentId, kind: FOLDER_KIND, orderIndex: 0, epoch: 0, name, wrappedContentKey: wrapUnder(kek, id, k),
  wrappedMeta: null, metaRevision: 0, placementRevision: 0,
})
const TREE: TreeNode[] = [folder(TOP, null, k0, keyTop, 'Reisen'), folder(SUB, TOP, keyTop, keySub, 'Archiv 2026')]

function recordFetch() {
  const bodies: { url: string; method: string; json: Record<string, unknown> }[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const json = JSON.parse(String(init?.body)) as Record<string, unknown>
    bodies.push({ url: String(input), method: init?.method ?? 'GET', json })
    return new Response(JSON.stringify({ shareId: json.shareId }), { status: 201, headers: { 'Content-Type': 'application/json' } })
  }))
  return bodies
}

describe('a folder share on the wire', () => {
  it.each([ShareRole.Read, ShareRole.ReadWrite])('a %s link of a nested folder wraps the folder\'s own key and seals its name under K_share', async (role) => {
    const bodies = recordFetch()
    // What the shell hands the dialog: the folder's key through the chain (K_SC(0) → top → sub).
    const folderKey = nodeContentKey(SCOPE_ID, EPOCHS, TREE, SUB)!
    expect(Array.from(folderKey)).toEqual(Array.from(keySub))

    const minted = await mintShare(SCOPE_ID, SUB, folderKey, EPOCHS, 'Archiv 2026', role)

    expect(bodies).toHaveLength(1)
    const { url, method, json } = bodies[0]
    expect(method).toBe('POST')
    expect(url).toContain('/api/shares')
    expect(json).toMatchObject({ shareId: minted.shareId, nodeId: SUB, role, sealedShareKeyEpoch: 1 })
    expect(typeof json.sealedShareKey).toBe('string')

    // The wrap opens under K_share to the folder's key — not the scope key of either epoch, not the parent's.
    const wrapped = v1.open(SHARE_KEY, minted.kShare, fromB64std(json.wrap as string))
    expect(Array.from(wrapped)).toEqual(Array.from(keySub))
    for (const other of [k0, k1, keyTop]) expect(Array.from(wrapped)).not.toEqual(Array.from(other))
    expect(() => v1.open(SHARE_KEY, k1, fromB64std(json.wrap as string)), 'the wrap is not sealed under K_SC').toThrow(v1.OpenRefused)

    // The folder's name, under K_share — a guest with only the fragment reads it.
    const name = v1.openField(SHARE_MEMORY_NAME, minted.kShare, fromB64std(json.sealedName as string))
    expect(new TextDecoder().decode(name)).toBe('Archiv 2026')
    expect(JSON.stringify(json)).not.toContain('Archiv')

    // The recovery copy opens under the scope's write key, bound to this folder and share.
    const copy = { sealedShareKey: json.sealedShareKey as string, sealedShareKeyEpoch: json.sealedShareKeyEpoch as number }
    expect(recoverShareKey(EPOCHS, { scopeId: SCOPE_ID, nodeId: SUB, shareId: minted.shareId }, copy)).toEqual(minted.kShare)
    expect(json.sealedDropPublicKey).toBeUndefined()
  })
})
