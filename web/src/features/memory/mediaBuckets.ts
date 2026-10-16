// en.memory.buckets (web) — the per-uploader "Beiträge" grouping (ADR 0034, cr-0113). Split out of media.ts
// (#96): buckets are uploader *metadata* (opaque owner_key + a K_node-sealed folder name), not part of the
// encrypted media-blob data layer — they touch no blob, no K_blob, and none of media.ts's crypto/transport
// core, so this is a clean one-way seam. Re-exported from ./media so existing importers keep their path.
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { bucketsApi } from './api'
import { withRefresh } from '../../app/sessionRefresh'
import { type BucketSummary } from '../../api/generated/media'
import { mediaKey, uploaderOriginOf, type UploaderOrigin } from './mediaKey'
import { openIdentityName } from '../../core/sharing/guestIdentity'

// Bucket names sealed under K_node (cr-0113), each bound to its object (ADR 0072): a user-created sub-bucket's name
// to (scope, node, bucket); a contributor primary's name IS the identity's sealed display name (cr-0101, copied
// server-side onto the bucket), bound to (scope, share, identity) — the row carries the share and names the identity.
// A WRITE_ONLY identity's primary is the exception to "under K_node": its name is sealed under that guest's own key,
// which the row's [BucketItem.uploader] opens through the Memory's drop key (ADR 0031 point 7).
const bucketNameContext = (scopeId: string, nodeId: string, bucketId: string) =>
  ({ purpose: 'bucket-name', scopeId, nodeId, bucketId }) as const
const OWNER_SHARE_PREFIX = 'share:'

/** One uploader bucket in a node: the opaque owner_key + structure + the (sealed) name + a live media count. */
export type BucketItem = {
  bucketId: string
  ownerKey: string
  parentBucketId?: string
  isPrimary: boolean
  /** The K_node-sealed name (opaque base64): a contributor primary + every sub-bucket carry one; a member's primary
   *  is undefined (the client shows the operator-visible username). Unwrap with [openBucketName]. */
  sealedName?: string
  mediaCount: number
  /** A contributor primary's share — its name is bound to it (ADR 0072). */
  shareId?: string
  /** A WRITE_ONLY identity's primary: its name is sealed under that guest's key, opened from here ([mediaKey]). */
  uploader?: UploaderOrigin
}

/**
 * Unwrap a bucket name under K_node — best-effort (null when absent/unopenable, so the picker falls back to a
 * neutral label). A **contributor** primary's name is the identity's sealed display name (cr-0101, copied
 * server-side); a **sub-bucket** seals with its own purpose (cr-0113). Both are bound to their object (ADR 0072),
 * addressed by the node the caller opened and the row's bucket. Operator-blind — the server relays ciphertext.
 *
 * A READ_WRITE guest's name is sealed under the key of its link's ROOT, which need not be this node: a folder link's
 * guest who uploads into a Memory below, a Memory link's guest into a chapter (5a.5). So a contributor primary also
 * tries `ancestorKeys` — the keys of this node's ancestors, nearest first, as a member holds them through the chain
 * (`contentKeyChain`) — through core's {@link openIdentityName}, which every application reads a guest's name with. A
 * sub-bucket's name and a WRITE_ONLY guest's (under its own `K_up`) open under this node's only.
 */
export function openBucketName(
  scopeId: string, nodeId: string, memoryKey: Uint8Array, bucket: BucketItem, ancestorKeys: readonly Uint8Array[] = [],
): string | null {
  const sealed = bucket.sealedName
  if (!sealed) return null
  try {
    const guest = contributorOf(bucket)
    if (guest) {
      const keys = bucket.uploader ? [mediaKey(scopeId, memoryKey, bucket)] : [memoryKey, ...ancestorKeys]
      return openIdentityName(scopeId, guest.shareId, guest.identityId, sealed, keys)
    }
    const context = bucketNameContext(scopeId, nodeId, bucket.bucketId)
    return new TextDecoder().decode(v1.openField(context, mediaKey(scopeId, memoryKey, bucket), fromB64std(sealed)))
  } catch {
    return null // not under this node's key (an uploader's K_up that does not open included): the neutral fallback label
  }
}

/** A contributor primary's identity — its name is that identity's seal; null for every other bucket (it carries its own). */
function contributorOf(b: BucketItem): { shareId: string; identityId: string } | null {
  if (b.isPrimary && b.ownerKey.startsWith(OWNER_SHARE_PREFIX) && b.shareId) {
    return { shareId: b.shareId, identityId: b.ownerKey.slice(OWNER_SHARE_PREFIX.length) }
  }
  return null
}

function toBucket(b: BucketSummary, nodeId: string, sealedDropPrivateKey: string | null | undefined): BucketItem {
  const wrapped = new Map<string, string>(b.isPrimary && b.wrappedIdentityKey ? [[b.ownerKey.slice(OWNER_SHARE_PREFIX.length), b.wrappedIdentityKey]] : [])
  return {
    bucketId: b.bucketId,
    ownerKey: b.ownerKey,
    parentBucketId: b.parentBucketId ?? undefined,
    isPrimary: b.isPrimary,
    sealedName: b.sealedName ?? undefined,
    mediaCount: b.mediaCount,
    shareId: b.shareId ?? undefined,
    uploader: uploaderOriginOf(b.ownerKey, nodeId, sealedDropPrivateKey, wrapped),
  }
}

/** List a node's uploader buckets for the "Beiträge" tab (primaries + sub-buckets) with per-bucket counts. */
export async function listBuckets(scopeId: string, nodeId: string): Promise<BucketItem[]> {
  const res = await withRefresh(() => bucketsApi().listNodeBuckets({ scopeId, nodeId }))
  return res.buckets.map((b) => toBucket(b, nodeId, res.sealedDropPrivateKey))
}

/** Create a sub-bucket under a primary root — the client seals the folder name under K_node. `parentBucketId` nests
 *  it under that root (a contributor's, to curate contributions — cr-0123); omitted ⇒ the caller's own primary. */
export async function createBucket(scopeId: string, nodeId: string, memoryKey: Uint8Array, name: string, parentBucketId?: string): Promise<BucketItem> {
  const bucketId = crypto.randomUUID() // client-minted: the name is sealed bound to it (ADR 0072)
  const sealedName = b64std(v1.sealField(bucketNameContext(scopeId, nodeId, bucketId), memoryKey, v1.utf8(name)))
  return toBucket(await withRefresh(() => bucketsApi().createBucket({ scopeId, nodeId, createBucketRequest: { bucketId, sealedName, parentBucketId } })), nodeId, null)
}

/** Rename one of the caller's own sub-buckets — re-seal the name under K_node (a later slice's UI). */
export async function renameBucket(scopeId: string, nodeId: string, memoryKey: Uint8Array, bucketId: string, name: string): Promise<BucketItem> {
  const sealedName = b64std(v1.sealField(bucketNameContext(scopeId, nodeId, bucketId), memoryKey, v1.utf8(name)))
  return toBucket(await withRefresh(() => bucketsApi().renameBucket({ scopeId, nodeId, bucketId, renameBucketRequest: { sealedName } })), nodeId, null)
}

/** Delete one of the caller's own sub-buckets — its media reparent to the primary (a later slice's UI). */
export async function deleteBucket(scopeId: string, nodeId: string, bucketId: string): Promise<void> {
  await withRefresh(() => bucketsApi().deleteBucket({ scopeId, nodeId, bucketId }))
}

/**
 * The most ids one move request carries — `MoveMediaRequest.mediaIds.maxItems` in `media.openapi.yaml`. The
 * generated client carries no bounds, so this is the one place the contract's value lives on the web side;
 * keep the two equal. The server rejects a longer list with a 400.
 */
export const MOVE_MEDIA_MAX_IDS = 256

/**
 * Move the caller's own media into a target bucket (null = the caller's primary). A selection larger than one
 * request carries goes out as consecutive requests of ≤ [MOVE_MEDIA_MAX_IDS] ids. The slices are **not atomic
 * together**: a failing slice throws after the earlier ones have moved, and the caller's error path reports it.
 */
export async function moveBucketMedia(scopeId: string, nodeId: string, mediaIds: string[], targetBucketId: string | null): Promise<void> {
  for (let i = 0; i < mediaIds.length; i += MOVE_MEDIA_MAX_IDS) {
    const slice = mediaIds.slice(i, i + MOVE_MEDIA_MAX_IDS)
    await withRefresh(() => bucketsApi().moveBucketMedia({ scopeId, nodeId, moveMediaRequest: { mediaIds: slice, targetBucketId } }))
  }
}
