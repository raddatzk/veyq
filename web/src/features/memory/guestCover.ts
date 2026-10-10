// A Memory's cover as a share GUEST sees it (inbox #401 S2): the member's `coverStore` is the member's (the active
// scope, `treeStore`, the authed presign), so a guest's card resolves its cover here — the same sealed `cover` of the
// node's `wrappedMeta` (`unsealMemoryCover`, under the node's key the guest's chain opened), its image through the
// link's own reads: a chosen MEDIA's thumbnail from the link's media listing (`listShareMedia`, read once per page and
// only when a cover needs it) opened as the guest's gallery opens it (`openSharedMemory` keys it, a drop-box guest's
// upload included), an UPLOADED cover blob through the gated share presign (`resolveShareBlob`, which serves a node's
// cover blob below the share — `SharingStore.subtreeBlobIds`). Read-only: a guest sets no cover (`setCover` is
// member-only, `capabilities.ts`).
//
// Best-effort like the member's: no cover, a cover whose media is gone, any refusal or decrypt failure ⇒ null (the
// placeholder tile). Key material stays out of Vue reactivity: keys are read from the tree source when a resolve runs.
import { computed, type Ref } from 'vue'
import type { GuestAccess } from '../../core/access'
import { openBlobFromUrls } from '../../core/blob/transport'
import { shareChunkUrls } from '../../core/sharing/guestBlob'
import { useObjectUrls } from '../../shared/state/useObjectUrls'
import type { ShareMediaListing } from '../../api/generated/share-media'
import { hasDerivatives } from './media'
import { unsealMemoryCover, type DecryptedCover, type LoadedCover, type CoverCrop } from './memoryCover'
import { listShareMedia, openShareGrid, openSharedMemory } from './share'

type CoverNode = { id: string; wrappedMeta: string | null }

/** Resolve `node`'s cover for the guest of `access` — `nodeKey` is the node's own key (the chain's); `listing` reads the
 *  link's media listing (memoised by the caller). Null when there is none or it does not open. */
export async function loadGuestNodeCover(
  access: GuestAccess,
  nodeKey: Uint8Array,
  node: CoverNode,
  listing: () => Promise<ShareMediaListing>,
): Promise<DecryptedCover | null> {
  const { scopeId } = access.head
  try {
    const cover = unsealMemoryCover(scopeId, nodeKey, node)
    if (!cover) return null
    let bytes: Uint8Array | null
    if (cover.source.kind === 'upload') {
      bytes = await openBlobFromUrls(scopeId, nodeKey, cover.source.ref, await shareChunkUrls(access.shareId, cover.source.ref.blobId, access.pwToken))
    } else {
      const mediaId = cover.source.mediaId
      // Only this node's media, keyed as the guest's gallery keys them (a drop-box guest's upload through its identity).
      const opened = openSharedMemory(access.head, await listing(), { nodeId: node.id, key: nodeKey })
      const pick = opened.items.find((si) => si.nodeId === node.id && si.item.mediaId === mediaId && hasDerivatives(si.item))
      bytes = pick ? await openShareGrid(access.shareId, pick, access.pwToken) : null
    }
    return bytes ? { blob: new Blob([bytes as BlobPart]), crop: cover.crop } : null
  } catch {
    return null // a refused read, a key that does not open, a malformed tier: the placeholder, never a broken tile
  }
}

/**
 * A guest page's covers, for a component's lifetime (the object URLs are revoked on unmount): `ensure` resolves the
 * covers of `nodes` not yet resolved from their current `wrappedMeta` (keys from `keyOf`), `covers` maps a node id to
 * its loaded cover as the member's `coverStore.covers` does.
 */
export function useGuestCovers(access: GuestAccess): {
  covers: Ref<Map<string, LoadedCover>>
  ensure: (nodes: readonly CoverNode[], keyOf: (id: string) => Uint8Array | null) => void
} {
  const urls = useObjectUrls()
  const crops = new Map<string, CoverCrop>()
  const resolvedFrom = new Map<string, string | null>()
  let listing: Promise<ShareMediaListing> | null = null
  const readListing = () => (listing ??= listShareMedia(access.shareId, access.role, access.pwToken))

  const covers = computed(() => {
    const m = new Map<string, LoadedCover>()
    for (const [id, url] of urls.urls.value) {
      const crop = crops.get(id)
      if (crop) m.set(id, { url, crop })
    }
    return m
  })

  function ensure(nodes: readonly CoverNode[], keyOf: (id: string) => Uint8Array | null) {
    for (const node of nodes) {
      if (resolvedFrom.has(node.id) && resolvedFrom.get(node.id) === node.wrappedMeta) continue
      if (!node.wrappedMeta) {
        resolvedFrom.set(node.id, null)
        continue // no sealed meta, no cover — and nothing to read for it
      }
      const key = keyOf(node.id)
      if (!key) continue
      const meta = node.wrappedMeta
      resolvedFrom.set(node.id, meta)
      void loadGuestNodeCover(access, key, node, readListing).then((cover) => {
        if (resolvedFrom.get(node.id) !== meta) return
        if (!cover) {
          urls.drop(node.id)
          crops.delete(node.id)
          return
        }
        crops.set(node.id, cover.crop)
        urls.set(node.id, cover.blob)
      })
    }
  }

  return { covers, ensure }
}
