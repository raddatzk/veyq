// A single-file share on the wire (inbox #378, ADR 0080 decisions 1 and 6, ADR 0072): the key a member hands the file's
// share dialog is the FILE's own, opened through the key chain (scope key → folder → file), and the mint request —
// recorded at `fetch`, no mock of the API client — wraps THAT key under `K_share` and seals the file's name under it. A
// guest holding only the fragment key then opens exactly the file: its key, its name (the link's own sealed name) and
// the blob's sealed size, type and date, every seal bound to the file node (a blob listed for another node opens
// nothing, `fileFacts`). No folder above and nothing beside it is reachable from what the link carries.
import { describe, it, expect, vi, afterEach } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { nodeContentKey, type TreeNode } from '../../core/node/tree'
import { FOLDER_KIND } from '../../core/node/kind'
import { mintShare } from '../../core/sharing/manageShares'
import { SHARE_KEY } from '../../core/sharing/shareTree'
import { SHARE_MEMORY_NAME, type PublicShareHead } from '../../core/sharing/resolvePublicShare'
import { guestTreeSource } from '../../core/sharing/guestAccess'
import { planBlob } from '../../core/blob/transport'
import { ShareRole, type PublicShare } from '../../api/generated/sharing'
import type { ScopeKey } from '../spaces/epochs'
import { treeNode } from '../../test/models'
import { fileFacts } from './download'
import { FILE_KIND } from './kinds'

const SCOPE_ID = '22222222-2222-4222-8222-222222222222'
const TOP = '5c09e000-0000-4000-8000-0000000000a1'
const FILE = '5c09e000-0000-4000-8000-0000000000a2'
const OTHER = '5c09e000-0000-4000-8000-0000000000a3'

afterEach(() => vi.unstubAllGlobals())

const k0 = v1.randomBytes(32)
const EPOCHS: ScopeKey = { epoch: 0, key: k0 }
const keyTop = v1.randomBytes(32)
const keyFile = v1.randomBytes(32)
const keyOther = v1.randomBytes(32)
const wrapUnder = (kek: Uint8Array, id: string, k: Uint8Array) =>
  b64std(v1.seal({ purpose: 'content-key', scopeId: SCOPE_ID, nodeId: id, epoch: 0, placementRevision: 0 }, kek, k))
const node = (id: string, parentId: string | null, kind: string, kek: Uint8Array, k: Uint8Array, name: string): TreeNode => treeNode({
  id, parentId, kind, orderIndex: 0, epoch: 0, name, wrappedContentKey: wrapUnder(kek, id, k),
  wrappedMeta: null, metaRevision: 0, placementRevision: 0,
})
const TREE: TreeNode[] = [
  node(TOP, null, FOLDER_KIND, k0, keyTop, 'Reisen'),
  node(FILE, TOP, FILE_KIND, keyTop, keyFile, 'Ticket.pdf'),
  node(OTHER, TOP, FILE_KIND, keyTop, keyOther, 'Hotel.pdf'),
]

function recordFetch() {
  const bodies: { url: string; method: string; json: Record<string, unknown> }[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const json = JSON.parse(String(init?.body)) as Record<string, unknown>
    bodies.push({ url: String(input), method: init?.method ?? 'GET', json })
    return new Response(JSON.stringify({ shareId: json.shareId }), { status: 201, headers: { 'Content-Type': 'application/json' } })
  }))
  return bodies
}

describe('a single-file share on the wire', () => {
  it.each([ShareRole.Read, ShareRole.ReadWrite])('a %s link of a file wraps the file\'s own key, and its guest opens that file alone', async (role) => {
    const bodies = recordFetch()
    const fileKey = nodeContentKey(SCOPE_ID, EPOCHS, TREE, FILE)! // what the host hands the dialog: k0 → folder → file
    expect(Array.from(fileKey)).toEqual(Array.from(keyFile))

    const minted = await mintShare(SCOPE_ID, FILE, { key: fileKey, epoch: 0 }, EPOCHS, 'Ticket.pdf', role)

    const { json } = bodies[0]
    expect(json).toMatchObject({ shareId: minted.shareId, nodeId: FILE, role, nodeEpoch: 0 })
    expect(json.sealedDropPublicKey).toBeUndefined() // no WRITE_ONLY link on a file: no drop key either
    const wrapped = v1.open(SHARE_KEY, minted.kShare, fromB64std(json.wrap as string))
    expect(Array.from(wrapped)).toEqual(Array.from(keyFile))
    for (const other of [k0, keyTop, keyOther]) expect(Array.from(wrapped)).not.toEqual(Array.from(other))
    const name = v1.openField(SHARE_MEMORY_NAME, minted.kShare, fromB64std(json.sealedName as string))
    expect(new TextDecoder().decode(name)).toBe('Ticket.pdf')
    expect(JSON.stringify(json)).not.toContain('Ticket')

    // The guest: the resolve serves the file alone (core's walk), its key from the share wrap, its name the link's.
    const blob = planBlob(SCOPE_ID, { ownerId: FILE, role: 'FILE_CONTENT' }, keyFile, new Blob([new Uint8Array(5000)]), {
      type: 'application/pdf', name: 'Ticket.pdf', modifiedAt: 1_700_000_000_000,
    })
    const head: PublicShareHead = {
      shareId: minted.shareId, kShare: minted.kShare, scopeId: SCOPE_ID, name: new TextDecoder().decode(name), noteByLocale: {}, role, rootKind: FILE_KIND,
      share: { role, scopeId: SCOPE_ID, rootKind: FILE_KIND, wrap: json.wrap as string, nodes: [{ id: FILE, parentId: null, kind: FILE_KIND, epoch: 0, scopeEpoch: 0 }] } as unknown as PublicShare,
    }
    const tree = guestTreeSource(head, { hash: '#k=x' })
    expect(tree.nodes.map((n) => n.id)).toEqual([FILE])
    expect(tree.nameOf(FILE)).toBe('Ticket.pdf')
    expect(Array.from(tree.keyOf(FILE)!)).toEqual(Array.from(keyFile))
    const listed = { blobId: blob.blobId, chunkCount: blob.chunkCount, byteSize: blob.byteSize, wrappedBlobKey: blob.wrappedBlobKey, wrappedBlobMeta: blob.wrappedBlobMeta ?? null }
    expect(fileFacts(SCOPE_ID, tree.keyOf(FILE)!, FILE, listed).meta).toMatchObject({ type: 'application/pdf', name: 'Ticket.pdf' })
    // A blob the server listed for ANOTHER node opens nothing under this file's key (ADR 0072: the seal binds the node).
    expect(fileFacts(SCOPE_ID, tree.keyOf(FILE)!, OTHER, listed).meta).toBeNull()
    expect(tree.keyOf(OTHER), 'nothing beside the file').toBeNull()
    expect(tree.keyOf(TOP), 'nothing above the file').toBeNull()
  })
})
