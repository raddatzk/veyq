// Shared gallery thumbnail pipeline (web) — the one decrypt-per-tile → object-URL-map pipeline behind every grid of a
// Memory (the gallery and its contributors' view, the trash, a guest's share), extracted into a composable (cr-0150,
// audit #74/P3d). Owns the thumbnail object-URL lifecycle via `useObjectUrls` — so a thumbnail URL has exactly ONE
// revoke owner, including a donated optimistic-upload preview (adopted, then superseded exactly once).
//
// **A thumbnail is read only for a tile near the viewport** (ADR 0105 decision 2, inbox #489). A grid announces the tiles
// it mounts, nearest to the viewport's centre first (`MediaGrid`'s `window`), and the caller hands them in with [want],
// one list per OWNER (a grid, the cover): the pipeline reads the thumbnails of every wanted tile it does not hold, in
// that order, and drops the reads of a tile no owner wants any more while they still wait in the read scheduler
// (`core/blob/readScheduler.ts`, which bounds and batches them, inbox #488 — once started, a read runs to its end). The
// object URLs it keeps are bounded: twice the largest window seen, at least [MIN_KEPT]; past that, the tiles wanted
// longest ago are revoked, and a tile that comes back is read again. A read that failed for good leaves its tile
// `failed`, so the grid shows an error tile, never a shimmer that never ends.
//
// Thumbnails decrypted close together are installed together ([INSTALL_EVERY_MS]): the store's map changes in place
// (`useObjectUrls`), and the grid re-renders once per install, not once per arriving tile.
import { getCurrentScope, onScopeDispose, ref } from 'vue'
import { useObjectUrls } from '../../shared/state/useObjectUrls'

/** How long decrypted thumbnails gather before they are installed together: at most ten grid renders a second while a
 *  gallery fills, and the first tile at most this late. */
export const INSTALL_EVERY_MS = 100

/** The fewest object URLs the pipeline keeps before it revokes the ones wanted longest ago. */
export const MIN_KEPT = 300

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
  // every change, so a template reading `failed.value.has(id)` re-renders.
  const failed = ref<ReadonlySet<string>>(new Set())
  const setFailed = (id: string, on: boolean) => {
    if (failed.value.has(id) === on) return
    const next = new Set(failed.value)
    if (on) next.add(id)
    else next.delete(id)
    failed.value = next
  }
  // The listed items by id (from [reconcile]): what a wanted id is read as.
  let listed = new Map<string, T>()
  // Each owner's wanted ids, nearest first; and when each id was last wanted (the eviction's order).
  const owners = new Map<string, readonly string[]>()
  const wantedAt = new Map<string, number>()
  let round = 0
  let largestWindow = 0
  // The reads on their way, each droppable on its own; a reset (revokeAll) and the owner's end drop them all.
  const reading = new Map<string, AbortController>()
  // Decrypted thumbnails not yet installed, and the timer that installs them ([INSTALL_EVERY_MS]).
  let gathered = new Map<string, Blob>()
  let installTimer: ReturnType<typeof setTimeout> | null = null
  function install() {
    if (installTimer !== null) clearTimeout(installTimer)
    installTimer = null
    if (gathered.size === 0) return
    const batch = gathered
    gathered = new Map()
    store.setMany(batch) // revokes each donated placeholder it supersedes (exactly once)
    for (const id of batch.keys()) {
      placeholders.delete(id)
      setFailed(id, false)
    }
    evict()
  }
  function gather(id: string, blob: Blob) {
    gathered.set(id, blob)
    installTimer ??= setTimeout(install, INSTALL_EVERY_MS)
  }
  function discardGathered() {
    if (installTimer !== null) clearTimeout(installTimer)
    installTimer = null
    gathered = new Map()
  }
  function dropReads() {
    for (const c of reading.values()) c.abort()
    reading.clear()
  }
  if (getCurrentScope()) {
    onScopeDispose(() => {
      dropReads()
      discardGathered()
    })
  }

  /** Every id some owner wants, in the owners' order, each once. */
  function wanted(): string[] {
    const seen = new Set<string>()
    for (const ids of owners.values()) for (const id of ids) seen.add(id)
    return [...seen]
  }

  /** Read one item's thumbnail unless it is in place, on its way, or failed for good. */
  function read(item: T) {
    if (!ctx.isDerivable(item)) return // non-image/video → icon tile, no fetch
    const id = ctx.idOf(item)
    if ((store.get(id) && !placeholders.has(id)) || gathered.has(id) || reading.has(id) || failed.value.has(id)) return
    const c = new AbortController()
    reading.set(id, c)
    ctx.decryptTile(item, c.signal).then(
      (bytes) => {
        if (reading.get(id) === c) reading.delete(id)
        if (bytes && !c.signal.aborted) gather(id, new Blob([bytes as BlobPart]))
        if (reading.size === 0) install() // the last read of the window: nothing to wait for
      },
      () => {
        if (reading.get(id) === c) reading.delete(id)
        // a read dropped on purpose is nobody's failure; any other is this tile's error state (a donated preview stays
        // visible under it), and the rest of the grid is unaffected
        if (!c.signal.aborted) setFailed(id, true)
        if (reading.size === 0) install()
      },
    )
  }

  /** Bring the reads in line with what is wanted: drop the unwanted ones still waiting, ask for the missing ones in
   *  order, and revoke past the bound. */
  function pump() {
    round += 1
    const order = wanted()
    largestWindow = Math.max(largestWindow, order.length)
    const keep = new Set(order)
    for (const id of order) wantedAt.set(id, round)
    for (const [id, c] of reading) {
      if (!keep.has(id)) {
        c.abort() // out of the scheduler's queue if it still waits there; a read already running runs to its end
        reading.delete(id)
      }
    }
    for (const id of order) {
      const item = listed.get(id)
      if (item) read(item)
    }
    evict()
  }

  /** Revoke the object URLs wanted longest ago, past twice the largest window seen (at least [MIN_KEPT]). A tile any
   *  owner wants now is never revoked. */
  function evict() {
    const cap = Math.max(MIN_KEPT, 2 * largestWindow)
    const held = store.urls.value
    if (held.size <= cap) return
    const keep = new Set(wanted())
    const stale = [...held.keys()].filter((id) => !keep.has(id)).sort((a, b) => (wantedAt.get(a) ?? 0) - (wantedAt.get(b) ?? 0))
    for (const id of stale.slice(0, held.size - cap)) {
      store.drop(id)
      placeholders.delete(id)
    }
  }

  /** The tiles `owner` wants a thumbnail for, nearest first — replacing what it wanted before. An empty list releases
   *  its tiles (a grid that is hidden or gone). */
  function want(owner: string, ids: readonly string[]) {
    owners.set(owner, ids)
    pump()
  }

  /** Prune thumbs of no-longer-listed media, donate a done upload's preview for each newly-listed media so its
   *  optimistic tile hands off with no gray gap, and read what the owners want of the new list. */
  function reconcile(list: readonly T[]) {
    listed = new Map(list.map((item) => [ctx.idOf(item), item]))
    for (const id of [...store.urls.value.keys()]) {
      if (!listed.has(id)) {
        store.drop(id)
        placeholders.delete(id)
      }
    }
    for (const id of [...gathered.keys()]) if (!listed.has(id)) gathered.delete(id)
    for (const [id, c] of reading) {
      if (!listed.has(id)) {
        c.abort()
        reading.delete(id)
      }
    }
    for (const id of failed.value) if (!listed.has(id)) setFailed(id, false)
    for (const item of list) {
      const id = ctx.idOf(item)
      if (store.get(id) || !ctx.isDerivable(item)) continue
      const donor = ctx.donorPreviewFor(id)
      if (!donor) continue
      store.adopt(id, donor) // single-owner: the donated url's revoke now belongs to `store`
      placeholders.add(id)
    }
    pump()
  }

  /** Drop one media's thumbnail (optimistic delete / removal), revoking its url. */
  function dropThumb(id: string) {
    gathered.delete(id)
    reading.get(id)?.abort()
    reading.delete(id)
    store.drop(id)
    placeholders.delete(id)
    setFailed(id, false)
  }

  /** Revoke every thumbnail and drop every read still waiting (a Memory/share reset). Unmount also auto-revokes via
   *  `useObjectUrls` and drops the reads. What the owners want stays: their grids re-announce over the next list. */
  function revokeAll() {
    dropReads()
    discardGathered()
    store.clear()
    placeholders.clear()
    listed = new Map()
    wantedAt.clear()
    failed.value = new Set()
  }

  return { thumbs: store.urls, failed, reconcile, want, dropThumb, revokeAll }
}
