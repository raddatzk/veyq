// A guest's identity folder in the member's tree (ADR 0080 decision 11, cr-files-application F3): its entry is sealed
// under the WRITE_ONLY guest's own key K_up, which the member opens through the parent's drop key —
//   K_SC → drop box key → drop private key → K_up → the folder's name and key → its file below,
// with REAL crypto and the real dropKey seals. And a guest-sealed entry that does not open leaves that folder out
// instead of failing the whole space; a member-sealed one still fails it (the strict rule is untouched).
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../api/client', () => ({ contentApi: vi.fn() }))

import * as v1 from '@veyq/crypto/v1'
import { listTree, listTrash, nodeContentKey, sealName, wrapContentKey, openName, type NodeAddress } from './tree'
import { dropKeysForShare, mintIdentityKey, openDropPublicKey } from '../sharing/dropKey'
import { walkSubtree } from '../sharing/shareTree'
import { epochsOf } from '../../features/spaces/epochsTestUtil'
import { contentApi } from '../../api/client'
import { fakeApi } from '../../test/apiMock'
import type { ContentApi, NodeSummary, TrashedNodeSummary } from '../../api/generated/content'
import type { ShareNode } from '../../api/generated/sharing'

const SCOPE = '11111111-1111-1111-1111-111111111111'
const BOX = '22222222-2222-4222-8222-222222222222'
const FOLDER = '33333333-3333-4333-8333-333333333333'
const FILE = '44444444-4444-4444-8444-444444444444'
const IDENTITY = '55555555-5555-4555-8555-555555555555'
const scopeKey = v1.randomBytes(32)
const epochs = epochsOf(scopeKey)
const at = (nodeId: string): NodeAddress => ({ scopeId: SCOPE, nodeId, epoch: 0, placementRevision: 0 })

/** The world: a member's drop box at the root with a drop key, a guest's folder under its K_up, a file in the folder. */
function world({ folderNameKey }: { folderNameKey?: Uint8Array } = {}) {
  const boxKey = v1.randomBytes(32)
  const kShare = v1.randomBytes(32)
  const drop = dropKeysForShare(SCOPE, BOX, boxKey, kShare, null)
  const { identityKey, wrappedIdentityKey } = mintIdentityKey(SCOPE, BOX, IDENTITY, openDropPublicKey(kShare, drop.sealedDropPublicKey))
  const folderKey = v1.randomBytes(32)
  const fileKey = v1.randomBytes(32)
  const row = (id: string, parentId: string | null, kind: string, entryKey: Uint8Array, key: Uint8Array, name: string, extra: Partial<NodeSummary> = {}): NodeSummary => ({
    blockedByOperator: false,
    id, parentId: parentId ?? undefined, kind, orderIndex: 0, epoch: 0, metaRevision: 0, placementRevision: 0, createdAt: new Date(0),
    encryptedName: sealName(entryKey, at(id), name), slugToken: 'c2x1Zw==', wrappedContentKey: wrapContentKey(entryKey, at(id), key), ...extra,
  })
  const rows = [
    row(BOX, null, 'DEPOT_DROPBOX', scopeKey, boxKey, 'Einsendungen', { sealedDropPrivateKey: drop.sealedDropPrivateKey }),
    { ...row(FOLDER, BOX, 'FOLDER', identityKey, folderKey, 'Adelgunde'), shareIdentityId: IDENTITY, wrappedIdentityKey,
      ...(folderNameKey ? { encryptedName: sealName(folderNameKey, at(FOLDER), 'Adelgunde') } : {}) },
    row(FILE, FOLDER, 'FILE', folderKey, fileKey, 'Bericht.pdf'),
  ]
  return { rows, boxKey, folderKey, fileKey, identityKey, wrappedIdentityKey, sealedDropPrivateKey: drop.sealedDropPrivateKey }
}

function serve(rows: NodeSummary[], trash: TrashedNodeSummary[] = []) {
  fakeApi(contentApi, {
    listNodes: vi.fn<ContentApi['listNodes']>().mockResolvedValue(rows),
    listTrashedNodes: vi.fn<ContentApi['listTrashedNodes']>().mockResolvedValue(trash),
  })
}

beforeEach(() => vi.clearAllMocks())

describe('a guest identity folder in the member tree', () => {
  it('opens the folder through the drop box key and the guest K_up, and the file below it', async () => {
    const w = world()
    serve(w.rows)
    const tree = await listTree(SCOPE, epochs)
    expect(tree.map((n) => [n.id, n.name])).toEqual([[BOX, 'Einsendungen'], [FOLDER, 'Adelgunde'], [FILE, 'Bericht.pdf']])
    expect(tree.find((n) => n.id === FOLDER)).toMatchObject({ shareIdentityId: IDENTITY, wrappedIdentityKey: w.wrappedIdentityKey })
    expect(nodeContentKey(SCOPE, epochs, tree, FOLDER)).toEqual(w.folderKey)
    expect(nodeContentKey(SCOPE, epochs, tree, FILE)).toEqual(w.fileKey)
  })

  it('a folder entry sealed under the drop box key itself (no K_up) does not open: the folder is left out, not the space', async () => {
    const w = world({ folderNameKey: v1.randomBytes(32) }) // a guest's garbage name
    serve(w.rows)
    const tree = await listTree(SCOPE, epochs)
    expect(tree.map((n) => n.id)).toEqual([BOX]) // the folder and the file below it are left out
  })

  it('control: a member-sealed entry that does not open still fails the tree (the strict rule)', async () => {
    const w = world()
    const broken = { ...w.rows[0], encryptedName: sealName(v1.randomBytes(32), at(BOX), 'x') }
    serve([broken, ...w.rows.slice(1)])
    await expect(listTree(SCOPE, epochs)).rejects.toThrow()
  })

  it('a wrapped K_up of another identity does not open: the folder is left out', async () => {
    const w = world()
    const other = { ...w.rows[1], shareIdentityId: '66666666-6666-4666-8666-666666666666' }
    serve([w.rows[0], other, w.rows[2]])
    expect((await listTree(SCOPE, epochs)).map((n) => n.id)).toEqual([BOX])
  })

  it('the trash opens a trashed identity folder through the same chain', async () => {
    const w = world()
    serve([w.rows[0]], [{
      blockedByOperator: false,
      id: FOLDER, parentId: BOX, kind: 'FOLDER', encryptedName: w.rows[1].encryptedName, epoch: 0, placementRevision: 0,
      deletedAt: new Date(), purgeAfter: new Date(), attached: false, shareIdentityId: IDENTITY, wrappedIdentityKey: w.wrappedIdentityKey,
    }])
    const tree = await listTree(SCOPE, epochs)
    expect((await listTrash(SCOPE, epochs, tree)).map((t) => t.name)).toEqual(['Adelgunde'])
  })

  it('a link guest of a folder above the drop box walks into the identity folder too', () => {
    const w = world()
    const share = w.rows.map((r) => ({ ...r, parentId: r.id === BOX ? undefined : r.parentId }))
    const walked = walkSubtree(SCOPE, share as unknown as ShareNode[], w.boxKey)
    expect(walked.names.get(FOLDER)).toBe('Adelgunde')
    expect(walked.keys.get(FILE)).toEqual(w.fileKey)
    expect(openName(w.identityKey, at(FOLDER), w.rows[1].encryptedName)).toBe('Adelgunde')
  })
})
