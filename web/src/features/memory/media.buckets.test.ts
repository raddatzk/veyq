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

/** Seal a name under K_mem with a given AAD context, exactly as the sealing sites do. */
function sealName(key: Uint8Array, name: string, ctx: string): string {
  return b64std(v1.symWrap(key, v1.utf8(name), ctx, v1.randomBytes(12)))
}

describe('media buckets (cr-0113)', () => {
  beforeEach(() => vi.clearAllMocks())

  describe('openBucketName', () => {
    it('unseals a contributor primary name under the share-identity context', () => {
      const key = v1.randomBytes(32)
      expect(openBucketName(key, sealName(key, 'Oma', 'share-identity-name'), 'contributor')).toBe('Oma')
    })
    it('unseals a sub-bucket name under the bucket context', () => {
      const key = v1.randomBytes(32)
      expect(openBucketName(key, sealName(key, 'Urlaub', 'bucket-name'), 'sub')).toBe('Urlaub')
    })
    it('returns null under the wrong AAD context (operator-blind — the name is bound to its purpose)', () => {
      const key = v1.randomBytes(32)
      expect(openBucketName(key, sealName(key, 'Oma', 'share-identity-name'), 'sub')).toBeNull()
    })
    it('returns null on garbage rather than throwing (falls back to a neutral label)', () => {
      expect(openBucketName(v1.randomBytes(32), 'bm90LWEtd3JhcA==', 'contributor')).toBeNull()
    })
  })

  describe('listBuckets', () => {
    it('maps the response to BucketItem[] (nulls → undefined)', async () => {
      const listNodeBuckets = vi.fn().mockResolvedValue({
        buckets: [
          { bucketId: 'b1', ownerKey: 'user:u1', isPrimary: true, mediaCount: 3, parentBucketId: null, sealedName: null },
          { bucketId: 'b2', ownerKey: 'share:s1', isPrimary: true, mediaCount: 1, parentBucketId: null, sealedName: 'YQ==' },
        ],
      })
      vi.mocked(bucketsApi).mockReturnValue({ listNodeBuckets } as unknown as ReturnType<typeof bucketsApi>)
      const out = await listBuckets(SCOPE, NODE)
      expect(listNodeBuckets).toHaveBeenCalledWith({ scopeId: SCOPE, nodeId: NODE })
      expect(out).toEqual([
        { bucketId: 'b1', ownerKey: 'user:u1', isPrimary: true, mediaCount: 3, parentBucketId: undefined, sealedName: undefined },
        { bucketId: 'b2', ownerKey: 'share:s1', isPrimary: true, mediaCount: 1, parentBucketId: undefined, sealedName: 'YQ==' },
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
      expect(openBucketName(key, req.sealedName, 'sub')).toBe('Urlaub 2026') // round-trips only via K_mem
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
