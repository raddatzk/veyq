// @vitest-environment happy-dom
// Web data-layer tests for uploader buckets (uc.memory.buckets, en.memory.buckets, cr-0113): the K_mem name
// unseal (operator-blind — a wrong AAD context can never open a name) + the bucket wrappers' request shaping.
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../api/client', () => ({
  mediaApi: vi.fn(),
  bucketsApi: vi.fn(),
}))

import { listBuckets, createBucket, moveBucketMedia, openBucketName } from './media'
import { bucketsApi } from '../../api/client'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'

const SCOPE = '7c3f0e2a-9b1d-4a6e-8b2c-1f2d3e4a5b6c'
const NODE = '4a6e8b2c-1f2d-3e4a-5b6c-7c3f0e2a9b1d'

const SHARE = 'S'.repeat(43) // a share id: 43 base64url chars
const IDENTITY = '5c09e000-0000-4000-8000-0000000000b7'
const BUCKET = '5c09e000-0000-4000-8000-0000000000b8'

/** Seal a name under K_mem with a given seal context, exactly as the sealing sites do. */
function sealName(key: Uint8Array, name: string, ctx: v1.SealContext): string {
  return b64std(v1.seal(ctx, key, v1.utf8(name)))
}
const identityNameCtx = { purpose: 'share-identity-name', scopeId: SCOPE, shareId: SHARE, identityId: IDENTITY } as const
const bucketNameCtx = (bucketId = BUCKET) => ({ purpose: 'bucket-name', scopeId: SCOPE, nodeId: NODE, bucketId }) as const
/** A contributor's primary bucket row: its owner key names the identity, the row carries the share. */
const contributorRow = (sealedName: string) =>
  ({ bucketId: BUCKET, ownerKey: `share:${IDENTITY}`, isPrimary: true, mediaCount: 1, sealedName, shareId: SHARE })
/** A user-created sub-bucket row. */
const subRow = (sealedName: string, bucketId = BUCKET) =>
  ({ bucketId, ownerKey: 'user:u1', isPrimary: false, mediaCount: 0, sealedName })

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
      const listNodeBuckets = vi.fn().mockResolvedValue({
        buckets: [
          { bucketId: 'b1', ownerKey: 'user:u1', isPrimary: true, mediaCount: 3, parentBucketId: null, sealedName: null },
          { bucketId: 'b2', ownerKey: 'share:s1', isPrimary: true, mediaCount: 1, parentBucketId: null, sealedName: 'YQ==', shareId: SHARE },
        ],
      })
      vi.mocked(bucketsApi).mockReturnValue({ listNodeBuckets } as unknown as ReturnType<typeof bucketsApi>)
      const out = await listBuckets(SCOPE, NODE)
      expect(listNodeBuckets).toHaveBeenCalledWith({ scopeId: SCOPE, nodeId: NODE })
      expect(out).toEqual([
        { bucketId: 'b1', ownerKey: 'user:u1', isPrimary: true, mediaCount: 3, parentBucketId: undefined, sealedName: undefined },
        { bucketId: 'b2', ownerKey: 'share:s1', isPrimary: true, mediaCount: 1, parentBucketId: undefined, sealedName: 'YQ==', shareId: SHARE },
      ])
    })
  })

  describe('createBucket', () => {
    it('seals the folder name under K_mem before sending — the server never sees the cleartext', async () => {
      const key = v1.randomBytes(32)
      const createBucketFn = vi.fn().mockResolvedValue({ bucketId: 'b3', ownerKey: 'user:u1', isPrimary: false, mediaCount: 0, parentBucketId: 'b1', sealedName: 'x' })
      vi.mocked(bucketsApi).mockReturnValue({ createBucket: createBucketFn } as unknown as ReturnType<typeof bucketsApi>)
      await createBucket(SCOPE, NODE, key, 'Urlaub 2026')
      const req = createBucketFn.mock.calls[0][0].createBucketRequest
      expect(req.sealedName).not.toContain('Urlaub') // ciphertext, never the cleartext name
      // the client mints the bucket id and binds the name to it (ADR 0072)
      expect(req.bucketId).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/)
      expect(openBucketName(SCOPE, NODE, key, subRow(req.sealedName, req.bucketId))).toBe('Urlaub 2026') // round-trips only via K_mem
      expect(openBucketName(SCOPE, NODE, key, subRow(req.sealedName, BUCKET))).toBeNull() // …and only at its own bucket
    })
  })

  describe('moveBucketMedia', () => {
    it('passes the media ids + target (null = the caller primary)', async () => {
      const move = vi.fn().mockResolvedValue(undefined)
      vi.mocked(bucketsApi).mockReturnValue({ moveBucketMedia: move } as unknown as ReturnType<typeof bucketsApi>)
      await moveBucketMedia(SCOPE, NODE, ['m1', 'm2'], null)
      expect(move).toHaveBeenCalledWith({ scopeId: SCOPE, nodeId: NODE, moveMediaRequest: { mediaIds: ['m1', 'm2'], targetBucketId: null } })
    })

    // The contract caps one request at `MoveMediaRequest.mediaIds.maxItems` (256), and the server enforces it —
    // so a larger selection must still move every id, in slices that each stay inside the bound.
    it('moves a selection larger than one request carries in slices of at most 256, every id exactly once', async () => {
      const move = vi.fn().mockResolvedValue(undefined)
      vi.mocked(bucketsApi).mockReturnValue({ moveBucketMedia: move } as unknown as ReturnType<typeof bucketsApi>)
      const ids = Array.from({ length: 300 }, (_, i) => `m${i}`)
      await moveBucketMedia(SCOPE, NODE, ids, 'b1')
      expect(move).toHaveBeenCalledTimes(2)
      const sent = move.mock.calls.map(([req]) => (req as { moveMediaRequest: { mediaIds: string[]; targetBucketId: string } }).moveMediaRequest)
      expect(sent.map((r) => r.mediaIds.length)).toEqual([256, 44])
      expect(sent.flatMap((r) => r.mediaIds)).toEqual(ids)
      expect(sent.every((r) => r.targetBucketId === 'b1')).toBe(true)
    })
  })
})
