// Shared gallery thumbnail pipeline (web) — the one decrypt-per-tile → object-URL-map pipeline behind both the
// internal Memory gallery (MemoryDetail) and the public share page (ShareView), extracted into a composable
// (cr-0150, audit #74/P3d). Owns the thumbnail object-URL lifecycle via `useObjectUrls` — so a thumbnail URL has
// exactly ONE revoke owner, including a donated optimistic-upload preview (adopted, then superseded exactly once).
// The only per-caller seam is `decryptTile` (`openGrid` vs `openShareGrid`) + the item-shape adapters.
//
// The reads themselves are scheduled by core (`core/blob/readScheduler.ts`, inbox #488): the pipeline asks for every
// tile at once, in the order it is handed, and the scheduler batches the presigns and bounds what is in flight. What
// the pipeline adds is the end of a read nobody wants any more — a reset aborts the reads still waiting — and the tile
// a read that failed for good leaves behind: marked `failed`, so the grid shows it, never a shimmer that never ends.
import { getCurrentScope, onScopeDispose, ref } from 'vue'
import { useObjectUrls } from '../../shared/state/useObjectUrls'

/** The per-caller seams. `T` is the caller's item type (`MediaItem` internally, `ShareItem` on the share page). */
export interface ThumbnailPipelineCtx<T> {
  idOf: (item: T) => string
  isDerivable: (item: T) => boolean
  /** Decrypt the item's thumbnail tier on device → bytes, or null when unavailable (locked/absent). May throw; an
   *  `AbortError` after `signal` aborted is a read dropped on purpose, any other failure is the tile's for good. */
  decryptTile: (item: T, signal: AbortSignal) => Promise<Uint8Array | null>
  /** A just-listed media's still-live optimistic-upload preview url (marked donated), or null. From useOptimisticTiles. */
  donorPreviewFor: (mediaId: string) => string | null
}

export function useThumbnailPipeline<T>(ctx: ThumbnailPipelineCtx<T>) {
  const store = useObjectUrls()
  // mediaIds whose thumb is currently a donated preview (a placeholder the real decrypt still supersedes). A
  // decrypt-scheduling hint only — revoke ownership is uniformly `store` (useObjectUrls), not split across owners.
  const placeholders = new Set<string>()
  // mediaIds whose thumbnail read failed for good (after the scheduler's retries): the tile's error state. Reassigned on
  // every change, like `store.urls`, so a template reading `failed.value.has(id)` re-renders.
  const failed = ref<ReadonlySet<string>>(new Set())
  const setFailed = (id: string, on: boolean) => {
    if (failed.value.has(id) === on) return
    const next = new Set(failed.value)
    if (on) next.add(id)
    else next.delete(id)
    failed.value = next
  }
  // The reads of the current generation; a reset (revokeAll) and the owner's end abort them, so a Chapter left while
  // its 3,000 tiles still wait does not keep the next one's tiles behind them.
  let reads = new AbortController()
  if (getCurrentScope()) onScopeDispose(() => reads.abort())

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
    for (const id of failed.value) {
      if (!ids.has(id)) setFailed(id, false)
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
    const { signal } = reads
    await Promise.all(
      list.map(async (item) => {
        if (!ctx.isDerivable(item)) return // non-image/video → icon tile, no fetch
        const id = ctx.idOf(item)
        if (store.get(id) && !placeholders.has(id)) return // a real thumbnail is already in place
        try {
          const bytes = await ctx.decryptTile(item, signal)
          if (!bytes || !isCurrent() || signal.aborted) return
          store.set(id, new Blob([bytes as BlobPart])) // revokes the donated placeholder it supersedes (exactly once)
          placeholders.delete(id)
          setFailed(id, false)
        } catch {
          // a read dropped by a reset is nobody's failure; any other is this tile's error state (a donated preview
          // stays visible under it), and the rest of the grid is unaffected
          if (!signal.aborted && isCurrent()) setFailed(id, true)
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
    setFailed(id, false)
  }

  /** Revoke every thumbnail and drop every read still waiting (a Memory/share reset). Unmount also auto-revokes via
   *  `useObjectUrls` and aborts the reads. */
  function revokeAll() {
    reads.abort()
    reads = new AbortController()
    store.clear()
    placeholders.clear()
    failed.value = new Set()
  }

  return { thumbs: store.urls, failed, reconcile, decrypt, sync, dropThumb, revokeAll }
}
