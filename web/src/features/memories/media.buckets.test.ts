// @vitest-environment happy-dom
// Web data-layer tests for uploader buckets (uc.memory.buckets, en.memory.buckets, cr-0113): the K_node name
// unseal (operator-blind — a wrong AAD context can never open a name) + the bucket wrappers' request shaping.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { stubApi } from '../../test/apiMock'

vi.mock('./api', () => ({ mediaApi: vi.fn(), bucketsApi: vi.fn() }))
vi.mock('../../api/client', async (original) => ({ ...(await original<object>()), publicSharesApi: vi.fn() }))

import { listBuckets, createBucket, moveBucketMedia, openBucketName } from './media'
import { bucketsApi } from './api'
import * as v1 from '@veyq/crypto/v1'
import { b64std, b64url, fromB64std } from '@veyq/crypto/base64'
import { publicSharesApi } from '../../api/client'
import { contentKeyChain, type TreeNode } from '../../core/node/tree'
import { createGuestIdentity, identityNameContext } from '../../core/sharing/guestIdentity'
import { epochsOf } from '../spaces/epochsTestUtil'
import { treeNode } from '../../test/models'

const SCOPE = '7c3f0e2a-9b1d-4a6e-8b2c-1f2d3e4a5b6c'
const NODE = '4a6e8b2c-1f2d-3e4a-5b6c-7c3f0e2a9b1d'

const SHARE = 'S'.repeat(43) // a share id: 43 base64url chars
const IDENTITY = '5c09e000-0000-4000-8000-0000000000b7'
const BUCKET = '5c09e000-0000-4000-8000-0000000000b8'

/** Seal a name under K_node with a given seal context, exactly as the sealing sites do. */
function sealName(key: Uint8Array, name: string, ctx: v1.FieldContext): string {
  return b64std(v1.sealField(ctx, key, v1.utf8(name)))
}
const identityNameCtx = { purpose: 'share-identity-name', scopeId: SCOPE, shareId: SHARE, identityId: IDENTITY } as const
const bucketNameCtx = (bucketId = BUCKET) => ({ purpose: 'bucket-name', scopeId: SCOPE, nodeId: NODE, bucketId }) as const
/** A contributor's primary bucket row: its owner key names the identity, the row carries the share. */
const contributorRow = (sealedName: string) =>
  ({ bucketId: BUCKET, ownerKey: `share:${IDENTITY}`, isPrimary: true, mediaCount: 1, sealedName, shareId: SHARE })
/** A user-created sub-bucket row. */
const subRow = (sealedName: string, bucketId = BUCKET) =>
  ({ bucketId, ownerKey: 'user:u1', isPrimary: false, mediaCount: 0, sealedName })

// cr-node-key-hierarchy 5b.4b: a guest of a link rooted ABOVE the node names itself under the ROOT's key — a folder
// link's guest who uploads into a Memory below the folder. The member opens the Memory's buckets holding the Memory's
// key and, through the chain, its ancestors'.
describe('openBucketName — a guest named under an ancestor\'s key', () => {
  const FOLDER = '5c09e000-0000-4000-8000-0000000000f1'
  const node = (id: string, parentId: string | null, kind: string, wrappedContentKey: string): TreeNode =>
    treeNode({ id, parentId, kind, orderIndex: 0, epoch: 0, name: id, wrappedContentKey, wrappedMeta: null, metaRevision: 0, placementRevision: 0 })
  const wrap = (kek: Uint8Array, nodeId: string, k: Uint8Array) =>
    b64std(v1.seal({ purpose: 'content-key', scopeId: SCOPE, nodeId, epoch: 0, placementRevision: 0 }, kek, k))

  it('a Memory below a shared folder: the member reads the folder guest\'s name through the chain', () => {
    const scopeKey = v1.randomBytes(32)
    const folderKey = v1.randomBytes(32)
    const memoryKey = v1.randomBytes(32)
    const tree = [node(FOLDER, null, 'FOLDER', wrap(scopeKey, FOLDER, folderKey)), node(NODE, FOLDER, 'MEMORY', wrap(folderKey, NODE, memoryKey))]
    // what `createGuestIdentity` seals on the folder link, copied by the server onto the guest's bucket in the Memory
    const row = contributorRow(sealName(folderKey, 'Oma', identityNameContext(SCOPE, SHARE, IDENTITY)))
    const [own, ...above] = contentKeyChain(SCOPE, epochsOf(scopeKey), tree, NODE)
    expect(Array.from(own)).toEqual(Array.from(memoryKey))
    expect(openBucketName(SCOPE, NODE, own, row, above)).toBe('Oma')
    // the bite: under the Memory's key alone the name does not open
    expect(openBucketName(SCOPE, NODE, own, row)).toBeNull()
  })

  it('only a guest\'s name climbs: a sub-bucket sealed under an ancestor\'s key stays closed', () => {
    const folderKey = v1.randomBytes(32)
    expect(openBucketName(SCOPE, NODE, v1.randomBytes(32), subRow(sealName(folderKey, 'Urlaub', bucketNameCtx())), [folderKey])).toBeNull()
  })
})

// ADR 0093 § 9, B9: the server copies a guest's `sealedDisplayName` VERBATIM onto its primary bucket's `sealedName`
// (`MediaStore.primarySealedNameFor`). The field envelope carries its own salt, so that copy opens as the identity's name
// with nothing else moved — a salt in a column of its own would have had to travel with it, and this is where it would not.
describe('a guest\'s primary bucket name is the identity name the server copied verbatim', () => {
  it('the envelope createGuestIdentity sends opens through openBucketName as the contributor primary\'s name', async () => {
    const { createShareIdentity } = stubApi(publicSharesApi, { createShareIdentity: { identityId: 'ignored', contributorToken: 'tok' } })
    const key = v1.randomBytes(32)
    const identity = await createGuestIdentity({ shareId: SHARE, scopeId: SCOPE }, key, { name: 'Oma', fragmentKey: b64url(v1.randomBytes(32)) })
    const sent = createShareIdentity.mock.calls[0][0].createShareIdentityRequest.sealedDisplayName
    const row = { ...contributorRow(sent), ownerKey: `share:${identity.identityId}` }
    expect(fromB64std(sent)[0]).toBe(0x03)
    expect(openBucketName(SCOPE, NODE, key, row)).toBe('Oma')
    expect(openBucketName(SCOPE, NODE, v1.randomBytes(32), row)).toBeNull() // control: only the key it was sealed under
  })
})

describe('media buckets (cr-0113)', () => {
  beforeEach(() => vi.clearAllMocks())

  describe('openBucketName', () => {
    it('unseals a contributor primary name under the share-identity context (bound to share + identity)', () => {
      const key = v1.randomBytes(32)
      expect(openBucketName(SCOPE, NODE, key, contributorRow(sealName(key, 'Oma', identityNameCtx)))).toBe('Oma')
    })
    it('unseals a sub-bucket name under the bucket context (bound to node + bucket)', () => {
      const key = v1.randomBytes(32)
      expect(openBucketName(SCOPE, NODE, key, subRow(sealName(key, 'Urlaub', bucketNameCtx())))).toBe('Urlaub')
    })
    it('returns null under the wrong purpose (operator-blind — the name is bound to its purpose)', () => {
      const key = v1.randomBytes(32)
      expect(openBucketName(SCOPE, NODE, key, subRow(sealName(key, 'Oma', identityNameCtx)))).toBeNull()
    })
    it('returns null for a name the server moved onto another bucket / identity (bound to its object)', () => {
      const key = v1.randomBytes(32)
      const other = '5c09e000-0000-4000-8000-0000000000b9'
      expect(openBucketName(SCOPE, NODE, key, subRow(sealName(key, 'Urlaub', bucketNameCtx()), other))).toBeNull()
      const otherIdentity = { ...identityNameCtx, identityId: other }
      expect(openBucketName(SCOPE, NODE, key, contributorRow(sealName(key, 'Oma', otherIdentity)))).toBeNull()
    })
    it('returns null on garbage or an unnamed bucket rather than throwing (falls back to a neutral label)', () => {
      expect(openBucketName(SCOPE, NODE, v1.randomBytes(32), contributorRow('bm90LWEtd3JhcA=='))).toBeNull()
      expect(openBucketName(SCOPE, NODE, v1.randomBytes(32), { ...subRow(''), sealedName: undefined })).toBeNull()
    })
  })

  describe('listBuckets', () => {
    it('maps the response to BucketItem[] (nulls → undefined)', async () => {
      const { listNodeBuckets } = stubApi(bucketsApi, {
        listNodeBuckets: {
          buckets: [
            { bucketId: 'b1', ownerKey: 'user:u1', isPrimary: true, mediaCount: 3, parentBucketId: null, sealedName: null },
            { bucketId: 'b2', ownerKey: 'share:s1', isPrimary: true, mediaCount: 1, parentBucketId: null, sealedName: 'YQ==', shareId: SHARE },
          ],
        },
      })
      const out = await listBuckets(SCOPE, NODE)
      expect(listNodeBuckets).toHaveBeenCalledWith({ scopeId: SCOPE, nodeId: NODE })
      expect(out).toEqual([
        { bucketId: 'b1', ownerKey: 'user:u1', isPrimary: true, mediaCount: 3, parentBucketId: undefined, sealedName: undefined },
        { bucketId: 'b2', ownerKey: 'share:s1', isPrimary: true, mediaCount: 1, parentBucketId: undefined, sealedName: 'YQ==', shareId: SHARE },
      ])
    })
  })

  describe('createBucket', () => {
    it('seals the folder name under K_node before sending — the server never sees the cleartext', async () => {
      const key = v1.randomBytes(32)
      const { createBucket: createBucketFn } = stubApi(bucketsApi, {
        createBucket: { bucketId: 'b3', ownerKey: 'user:u1', isPrimary: false, mediaCount: 0, parentBucketId: 'b1', sealedName: 'x' },
      })
      await createBucket(SCOPE, NODE, key, 'Urlaub 2026')
      const req = createBucketFn.mock.calls[0][0].createBucketRequest
      expect(req.sealedName).not.toContain('Urlaub') // ciphertext, never the cleartext name
      // the client mints the bucket id and binds the name to it (ADR 0072)
      expect(req.bucketId).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/)
      expect(openBucketName(SCOPE, NODE, key, subRow(req.sealedName, req.bucketId))).toBe('Urlaub 2026') // round-trips only via K_node
      expect(openBucketName(SCOPE, NODE, key, subRow(req.sealedName, BUCKET))).toBeNull() // …and only at its own bucket
    })
  })

  describe('moveBucketMedia', () => {
    it('passes the media ids + target (null = the caller primary)', async () => {
      const { moveBucketMedia: move } = stubApi(bucketsApi, { moveBucketMedia: undefined })
      await moveBucketMedia(SCOPE, NODE, ['m1', 'm2'], null)
      expect(move).toHaveBeenCalledWith({ scopeId: SCOPE, nodeId: NODE, moveMediaRequest: { mediaIds: ['m1', 'm2'], targetBucketId: null } })
    })

    // The contract caps one request at `MoveMediaRequest.mediaIds.maxItems` (256), and the server enforces it —
    // so a larger selection must still move every id, in slices that each stay inside the bound.
    it('moves a selection larger than one request carries in slices of at most 256, every id exactly once', async () => {
      const { moveBucketMedia: move } = stubApi(bucketsApi, { moveBucketMedia: undefined })
      const ids = Array.from({ length: 300 }, (_, i) => `m${i}`)
      await moveBucketMedia(SCOPE, NODE, ids, 'b1')
      expect(move).toHaveBeenCalledTimes(2)
      const sent = move.mock.calls.map(([req]) => req.moveMediaRequest)
      expect(sent.map((r) => r.mediaIds.length)).toEqual([256, 44])
      expect(sent.flatMap((r) => r.mediaIds)).toEqual(ids)
      expect(sent.every((r) => r.targetBucketId === 'b1')).toBe(true)
    })
  })
})
