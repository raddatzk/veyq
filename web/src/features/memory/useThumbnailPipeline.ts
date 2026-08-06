// Shared gallery thumbnail pipeline (web) — the one decrypt-per-tile → object-URL-map pipeline behind both the
// internal Memory gallery (MemoryDetail) and the public share page (ShareView), extracted into a composable
// (cr-0150, audit #74/P3d). Owns the thumbnail object-URL lifecycle via `useObjectUrls` — so a thumbnail URL has
// exactly ONE revoke owner, including a donated optimistic-upload preview (adopted, then superseded exactly once).
// The only per-caller seam is `decryptTile` (`openGrid` vs `openShareGrid`) + the item-shape adapters.
import { useObjectUrls } from '../../shared/state/useObjectUrls'

/** The per-caller seams. `T` is the caller's item type (`MediaItem` internally, `ShareItem` on the share page). */
export interface ThumbnailPipelineCtx<T> {
  idOf: (item: T) => string
  isDerivable: (item: T) => boolean
  /** Decrypt the item's thumbnail tier on device → bytes, or null when unavailable (locked/absent). May throw. */
  decryptTile: (item: T) => Promise<Uint8Array | null>
  /** A just-listed media's still-live optimistic-upload preview url (marked donated), or null. From useOptimisticTiles. */
  donorPreviewFor: (mediaId: string) => string | null
}

export function useThumbnailPipeline<T>(ctx: ThumbnailPipelineCtx<T>) {
  const store = useObjectUrls()
  // mediaIds whose thumb is currently a donated preview (a placeholder the real decrypt still supersedes). A
  // decrypt-scheduling hint only — revoke ownership is uniformly `store` (useObjectUrls), not split across owners.
  const placeholders = new Set<string>()

  /** Prune thumbs of no-longer-listed media, and donate a done upload's preview for each newly-listed media so its
   *  optimistic tile hands off with no gray gap (the real thumbnail replaces it once decrypted). No decrypt here. */
  function reconcile(listed: readonly T[]) {
    const ids = new Set(listed.map(ctx.idOf))
    for (const id of [...store.urls.value.keys()]) {
      if (!ids.has(id)) {
        store.drop(id)
        placeholders.delete(id)
      }
    }
    for (const item of listed) {
      const id = ctx.idOf(item)
      if (store.get(id) || !ctx.isDerivable(item)) continue
      const donor = ctx.donorPreviewFor(id)
      if (!donor) continue
      store.adopt(id, donor) // single-owner: the donated url's revoke now belongs to `store`
      placeholders.add(id)
    }
  }

  /** Decrypt + install the real thumbnail for each derivable item still lacking one (parallel, operator-blind).
   *  `isCurrent` is the caller's race guard (e.g. still on the same node) — a stale result is dropped. */
  async function decrypt(list: readonly T[], isCurrent: () => boolean = () => true) {
    await Promise.all(
      list.map(async (item) => {
        if (!ctx.isDerivable(item)) return // non-image/video → icon tile, no fetch
        const id = ctx.idOf(item)
        if (store.get(id) && !placeholders.has(id)) return // a real thumbnail is already in place
        try {
          const bytes = await ctx.decryptTile(item)
          if (!bytes || !isCurrent()) return
          store.set(id, new Blob([bytes as BlobPart])) // revokes the donated placeholder it supersedes (exactly once)
          placeholders.delete(id)
        } catch {
          // a single failed tile stays a placeholder (or its donated preview); the rest of the grid is unaffected
        }
      }),
    )
  }

  /** Full refresh for a fresh list: prune + donate, then decrypt the missing tiles. */
  async function sync(listed: readonly T[], isCurrent: () => boolean = () => true) {
    reconcile(listed)
    await decrypt(listed, isCurrent)
  }

  /** Drop one media's thumbnail (optimistic delete / removal), revoking its url. */
  function dropThumb(id: string) {
    store.drop(id)
    placeholders.delete(id)
  }

  /** Revoke every thumbnail (a Memory/share reset). Unmount also auto-revokes via `useObjectUrls`. */
  function revokeAll() {
    store.clear()
    placeholders.clear()
  }

  return { thumbs: store.urls, reconcile, decrypt, sync, dropThumb, revokeAll }
}
