// en.memory.buckets (web) — the per-uploader "Beiträge" grouping (ADR 0034, cr-0113). Split out of media.ts
// (#96): buckets are uploader *metadata* (opaque owner_key + a K_mem-sealed folder name), not part of the
// encrypted media-blob data layer — they touch no blob, no K_blob, and none of media.ts's crypto/transport
// core, so this is a clean one-way seam. Re-exported from ./media so existing importers keep their path.
import * as v1 from '../../shared/crypto/v1'
import { b64std, fromB64std } from '../../shared/base64'
import { bucketsApi } from '../../api/client'
import { withRefresh } from '../account/login/login'
import { type BucketSummary } from '../../api/generated/media'

// AAD contexts for bucket names sealed under K_mem (cr-0113): a contributor primary's name is the identity's
// sealed display name (cr-0101, copied server-side onto the bucket); a user-created sub-bucket seals with its own.
const SHARE_IDENTITY_NAME_CTX = 'share-identity-name'
const BUCKET_NAME_CTX = 'bucket-name'

/** One uploader bucket in a node: the opaque owner_key + structure + the (sealed) name + a live media count. */
export type BucketItem = {
  bucketId: string
  ownerKey: string
  parentBucketId?: string
  isPrimary: boolean
  /** The K_mem-sealed name (opaque base64): a contributor primary + every sub-bucket carry one; a member's primary
   *  is undefined (the client shows the operator-visible username). Unwrap with [openBucketName]. */
  sealedName?: string
  mediaCount: number
}

/**
 * Unwrap a bucket name under K_mem — best-effort (null when absent/unopenable, so the picker falls back to a
 * neutral label). A **contributor** primary's name is the identity's sealed display name (cr-0101, copied
 * server-side); a **sub-bucket** seals with its own tag (cr-0113). Operator-blind — the server relays ciphertext.
 */
export function openBucketName(memoryKey: Uint8Array, sealedName: string, kind: 'contributor' | 'sub'): string | null {
  const ctx = kind === 'contributor' ? SHARE_IDENTITY_NAME_CTX : BUCKET_NAME_CTX
  try {
    return new TextDecoder().decode(v1.symUnwrap(memoryKey, fromB64std(sealedName), ctx))
  } catch {
    return null // not openable → the picker uses a neutral fallback label
  }
}

function toBucket(b: BucketSummary): BucketItem {
  return {
    bucketId: b.bucketId,
    ownerKey: b.ownerKey,
    parentBucketId: b.parentBucketId ?? undefined,
    isPrimary: b.isPrimary,
    sealedName: b.sealedName ?? undefined,
    mediaCount: b.mediaCount,
  }
}

/** List a node's uploader buckets for the "Beiträge" tab (primaries + sub-buckets) with per-bucket counts. */
export async function listBuckets(scopeId: string, nodeId: string): Promise<BucketItem[]> {
  const res = await withRefresh(() => bucketsApi().listNodeBuckets({ scopeId, nodeId }))
  return res.buckets.map(toBucket)
}

/** Create a sub-bucket under a primary root — the client seals the folder name under K_mem. `parentBucketId` nests
 *  it under that root (a contributor's, to curate contributions — cr-0123); omitted ⇒ the caller's own primary. */
export async function createBucket(scopeId: string, nodeId: string, memoryKey: Uint8Array, name: string, parentBucketId?: string): Promise<BucketItem> {
  const sealedName = b64std(v1.symWrap(memoryKey, v1.utf8(name), BUCKET_NAME_CTX, v1.randomBytes(12)))
  return toBucket(await withRefresh(() => bucketsApi().createBucket({ scopeId, nodeId, createBucketRequest: { sealedName, parentBucketId } })))
}

/** Rename one of the caller's own sub-buckets — re-seal the name under K_mem (a later slice's UI). */
export async function renameBucket(scopeId: string, nodeId: string, memoryKey: Uint8Array, bucketId: string, name: string): Promise<BucketItem> {
  const sealedName = b64std(v1.symWrap(memoryKey, v1.utf8(name), BUCKET_NAME_CTX, v1.randomBytes(12)))
  return toBucket(await withRefresh(() => bucketsApi().renameBucket({ scopeId, nodeId, bucketId, renameBucketRequest: { sealedName } })))
}

/** Delete one of the caller's own sub-buckets — its media reparent to the primary (a later slice's UI). */
export async function deleteBucket(scopeId: string, nodeId: string, bucketId: string): Promise<void> {
  await withRefresh(() => bucketsApi().deleteBucket({ scopeId, nodeId, bucketId }))
}

/** Move the caller's own media into a target bucket (null = the caller's primary) — a later slice's UI. */
export async function moveBucketMedia(scopeId: string, nodeId: string, mediaIds: string[], targetBucketId: string | null): Promise<void> {
  await withRefresh(() => bucketsApi().moveBucketMedia({ scopeId, nodeId, moveMediaRequest: { mediaIds, targetBucketId } }))
}
