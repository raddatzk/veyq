// What of a picked file the Memory already stores (ADR 0013 § 5, uc.memory.upload): an upload into the cover or banner
// dialog of a file equal to a gallery original, or a gallery upload of a file equal to the video banner, shares the stored
// object instead of storing the bytes again. Equal is: the same sealed size, then the same SHA-256 of the plaintext - the
// `sha256` a gallery original's and a video banner's `blob-meta` carry (sealed under the blob's own key), and the video
// banner's `sha256` in the node's sealed meta. A medium uploaded before the digest existed carries none and is never matched.
//
// Which tier a slot shares (only where the stored bytes are the very bytes the slot would hold):
//  - a cover, an image banner: the medium's PREVIEW - the same spec (long edge 2048, quality 0.85) of the same original;
//  - a video banner: the medium's ORIGINAL (the slot holds the file as it is), and its PREVIEW (the first-frame poster) as
//    the banner's poster;
//  - a gallery original: a video banner's BANNER blob. Never a cover or an image banner: they hold a derived JPEG, not the
//    file, and a medium's tiers are derived from its original in one pass.
// A share is a member's write only; a guest's upload is never matched.
import { findEqual, sealedByteSize, type StoredBlob } from '../../core/blob/sharedBlob'
import type { CoverFindContext } from '../../core/applications/registry'
import { treeStore } from '../../core/node/treeStore'
import { unsealNodeMeta } from '../../core/node/nodeMeta'
import { normalizeBanner } from './banner/bannerModel'
import { listMedia, originalBlobMeta, type MediaItem } from './media'
import { mediaKey } from './mediaKey'

/** A medium whose original equals a picked file, with the key its blobs' wraps open under. */
export type EqualMedium = { item: MediaItem; key: Uint8Array }

/**
 * The first live, unblocked medium of `items` whose original equals `file` (size first, the digest only for a medium of the
 * same size, `findEqual`), or null. `digest`, when the caller already has the file's, saves reading it again.
 */
export async function findEqualMedium(
  scopeId: string, memoryKey: Uint8Array, items: readonly MediaItem[], file: Blob, signal?: AbortSignal, digest?: string,
): Promise<EqualMedium | null> {
  const candidates = items
    .filter((item) => !item.blocked && !item.deletedAt)
    .map((item) => ({ item, byteSize: item.original.byteSize, sha256: () => originalBlobMeta(scopeId, memoryKey, item)?.sha256 ?? null }))
  if (digest !== undefined) {
    const size = sealedByteSize(file.size)
    const hit = candidates.find((c) => c.byteSize === size && c.sha256() === digest)
    return hit ? withKey(scopeId, memoryKey, hit.item) : null
  }
  const hit = await findEqual(file, candidates, signal)
  return hit ? withKey(scopeId, memoryKey, hit.item) : null
}

function withKey(scopeId: string, memoryKey: Uint8Array, item: MediaItem): EqualMedium | null {
  try {
    return { item, key: mediaKey(scopeId, memoryKey, item) }
  } catch {
    return null // a guest's medium whose chain does not open: no source to share
  }
}

/** A medium's tier as a blob a new row may share - null when the medium has no such tier (the upload then runs). */
export function storedTier(m: EqualMedium, kind: 'ORIGINAL' | 'PREVIEW'): StoredBlob | null {
  const ref = kind === 'ORIGINAL' ? m.item.original : m.item.derivatives.find((d) => d.kind === kind)?.ref
  return ref ? { key: m.key, ref } : null
}

/**
 * Memories' `cover.findStored` (`register.ts`): the PREVIEW of the node's own gallery medium whose original equals the picked
 * image - what core's cover upload would derive from it anyway. Null for anything else; core then uploads.
 */
export async function findStoredCover(ctx: CoverFindContext): Promise<StoredBlob | null> {
  if (!ctx.file.type.startsWith('image/')) return null
  const hit = await findEqualMedium(ctx.scopeId, ctx.nodeKey, await listMedia(ctx.scopeId, ctx.nodeId), ctx.file, ctx.signal)
  return hit ? storedTier(hit, 'PREVIEW') : null
}

/**
 * The node's video banner as the stored original of a gallery upload of the same file: its `BANNER` blob, matched by the
 * banner's sealed `sha256` and its size. Read from the tree's copy of the node's meta; null for none, an image banner, or a
 * meta that does not open. The returned function answers per file, so the meta is opened once per batch.
 */
export function bannerOriginalOf(scopeId: string, nodeId: string, nodeKey: Uint8Array): (digest: string, byteSize: number) => StoredBlob | null {
  const node = treeStore.nodes.find((n) => n.id === nodeId)
  let banner = null
  try {
    banner = node ? normalizeBanner(unsealNodeMeta(nodeKey, { scopeId, nodeId }, node.wrappedMeta).banner, nodeId) : null
  } catch {
    banner = null
  }
  return (digest, byteSize) =>
    banner?.medium === 'video' && banner.sha256 === digest && banner.blob.byteSize === byteSize ? { key: nodeKey, ref: banner.blob } : null
}
