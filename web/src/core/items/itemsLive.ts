// The platform's live refresh of an item list inside a node (inbox #410, ADR 0033): the client half of core's
// revisioned item list. An application's list endpoint answers a conditional GET (`If-None-Match` = the node's item
// revision → `304`, nothing to do), a keyset delta past the cursor `(updatedAt, id)`, and — only when a removal
// happened since the client last synced — the node's current id set; this module is everything the client does
// with that, so an application supplies only its FETCH (its generated client) and its REDUCER (what its view does
// with a delta). Memories' gallery is the first user (`features/memory/useMediaGalleryLive.ts`); core names no application.
//
// Three pieces, none holding state of their own:
//
// - {@link itemsPollTick} — one tick of the loop: read the caller's cursor, fetch conditionally, and apply a change.
//   A `304`, a stopped run and a node that moved on apply nothing. A failed fetch REJECTS, so the poller backs off.
// - {@link reconcileItems} — the delta merge: upsert by id (a new item inserts, a known one is patched in place so a
//   view holding it stays consistent) and, only when the id set is present, drop what it no longer lists — which
//   also catches a net-zero delete+add, because it is a set and not a count.
// - {@link ITEMS_POLL} — the cadence the loop runs at (`shared/state/livePoller.ts`: visibility-gated, jittered, backing
//   off on errors, fenced on stop). 20 s, not the tree's 5 s: a list inside an open node changes by a person's upload,
//   and an idle poll is one tiny `304`.
//
// Operator-blind: opaque counters, opaque ids and exact timestamps travel; nothing about an item's content does.
import type { LivePollerOptions, LiveTick } from '../../shared/state/livePoller'

/** The cadence of an item list's live poll: 20 s ± 20 %, doubling to 2 min on errors, a second tick at most every 2 s. */
export const ITEMS_POLL: LivePollerOptions = { intervalMs: 20_000, maxBackoffMs: 120_000, jitter: 0.2, minGapMs: 2_000 }

/** What the next poll echoes: the revision it holds (the `If-None-Match`), the delete revision (the `sinceDelete`
 *  gate) and the keyset cursor `"updatedAt,id"` of its newest item (undefined: none yet). `key` names what the poll was
 *  made for (a node id) so a result for a view that has moved on is dropped. */
export interface ItemsPollCursor<K = unknown> {
  key: K
  revision: number
  deleteRevision: number
  since?: string
}

/** One conditional read of the list: nothing changed (`304`), or the delta with both revisions and — only when a
 *  removal advanced past the echoed `sinceDelete` — the current id set. */
export type ItemsPollPage<T> =
  | { notModified: true }
  | { notModified: false; items: T[]; revision: number; deleteRevision: number; ids: string[] | null }

/** A page that changed something, as the reducer sees it. */
export type ItemsChange<T> = Extract<ItemsPollPage<T>, { notModified: false }>

export interface ItemsPollTickOptions<T, K> {
  /** The poll's starting point from the caller's live state, or null when there is nothing to poll (not loaded, locked,
   *  no live poll for this session): the tick then asks the server nothing and waits for the next interval. */
  cursor: () => ItemsPollCursor<K> | null
  /** The conditional read — the application's generated client with `ifNoneMatch`, `since` and `sinceDelete` from the
   *  cursor, a `304` read as `{ notModified: true }`. Rejects on a failure. */
  fetch: (cursor: ItemsPollCursor<K>, signal: AbortSignal) => Promise<ItemsPollPage<T>>
  /** Whether the view the cursor was taken for is still the open one (default: always). */
  isCurrent?: (key: K) => boolean
  /** What the view does with a change: merge the delta, store the new revisions, decrypt, settle. It runs only for a
   *  change of a run that is still live and of a view that is still current, and must check `signal` after every await. */
  apply: (change: ItemsChange<T>, cursor: ItemsPollCursor<K>, signal: AbortSignal) => void | Promise<void>
}

/** One tick of an item list's live loop, for `createLivePoller(...).start`. */
export function itemsPollTick<T, K = unknown>(options: ItemsPollTickOptions<T, K>): LiveTick {
  return async (signal) => {
    const cursor = options.cursor()
    if (!cursor) return 'skip'
    const page = await options.fetch(cursor, signal)
    if (signal.aborted || page.notModified) return
    if (options.isCurrent && !options.isCurrent(cursor.key)) return
    await options.apply(page, cursor, signal)
  }
}

export interface ReconcileHooks<T> {
  idOf: (item: T) => string
  /** An incoming item the view must not (re)add - an optimistic removal its server delete has not confirmed yet. */
  skip?: (id: string) => boolean
  /** An incoming item whose id is already held: patch the HELD object in place (a view holding it stays consistent).
   *  Without it the held object stays untouched. */
  patch?: (held: T, incoming: T) => void
  /** The id set is present: the ids it lists, before anything is dropped (lift an optimistic removal the server confirms). */
  onIds?: (listed: ReadonlySet<string>) => void
  /** A held item the id set no longer lists, as it is dropped. */
  onDrop?: (id: string) => void
}

/**
 * The merge of a keyset delta into the held items: upsert each delta item by id, then — when [ids] is present (a removal
 * advanced the delete revision) — drop every held id it does not list. Returns the new list (insertion order: held
 * items first, then new ones); the caller re-sorts for its view.
 */
export function reconcileItems<T>(held: readonly T[], delta: readonly T[], ids: readonly string[] | null, hooks: ReconcileHooks<T>): T[] {
  const byId = new Map(held.map((item) => [hooks.idOf(item), item] as const))
  for (const incoming of delta) {
    const id = hooks.idOf(incoming)
    if (hooks.skip?.(id)) continue
    const existing = byId.get(id)
    if (existing) hooks.patch?.(existing, incoming)
    else byId.set(id, incoming)
  }
  if (ids) {
    const listed = new Set(ids)
    hooks.onIds?.(listed)
    for (const id of [...byId.keys()]) {
      if (listed.has(id)) continue
      hooks.onDrop?.(id)
      byId.delete(id)
    }
  }
  return [...byId.values()]
}

/** The newest `(updatedAt, id)` keyset cursor across [items] - what a poll echoes as `since`; undefined when none
 *  carries an `updatedAt` (an empty list). Milliseconds by construction (a JS `Date`), which round-trips losslessly
 *  against the server's `TIMESTAMP(3)`, so the same rows are never fetched twice. */
export function newestItemsCursor<T>(items: readonly T[], idOf: (item: T) => string, updatedAtOf: (item: T) => Date | undefined): string | undefined {
  let bestT = -Infinity
  let bestId = ''
  let found = false
  for (const item of items) {
    const at = updatedAtOf(item)
    if (!at) continue
    const t = at.getTime()
    const id = idOf(item)
    if (t > bestT || (t === bestT && id > bestId)) {
      bestT = t
      bestId = id
      found = true
    }
  }
  return found ? `${new Date(bestT).toISOString()},${bestId}` : undefined
}
