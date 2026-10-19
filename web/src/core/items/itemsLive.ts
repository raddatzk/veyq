// The platform's live refresh of an item list inside a node (inbox #410, ADR 0033): the client half of core's
// revisioned item list. An application's list endpoint answers a conditional GET (`If-None-Match` = the node's item
// revision → `304`, nothing to do), a keyset delta past the cursor the server answered last — in pages of a bounded
// size — and, only when a removal happened since the client last synced, the node's current id set; this module is
// everything the client does
// with that, so an application supplies only its FETCH (its generated client) and its REDUCER (what its view does
// with a delta). Memories' gallery is the first user (`features/memory/useMediaGalleryLive.ts`); core names no application.
//
// Four pieces, none holding state of their own:
//
// - {@link readItemPages} — the one page loop of a full read (a first load or a poll): the pages, merged into one
//   delta with the first page's revisions and the last page's cursor (ADR 0033 decision 6).
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
 *  gate) and the keyset cursor the server answered with the last page it read (undefined: none yet). `key` names what
 *  the poll was made for (a node id) so a result for a view that has moved on is dropped. */
export interface ItemsPollCursor<K = unknown> {
  key: K
  revision: number
  deleteRevision: number
  since?: string
}

/** One conditional read of the list, after every page of it ([readItemPages]): nothing changed (`304`), or the delta
 *  with the first page's revisions and — only when a removal advanced past the echoed `sinceDelete` — the id set, and
 *  the cursor the next poll continues from (null: no page carried a row, keep the one held). */
export type ItemsPollPage<T> =
  | { notModified: true }
  | { notModified: false; items: T[]; revision: number; deleteRevision: number; ids: string[] | null; cursor: string | null }

/** A page that changed something, as the reducer sees it. */
export type ItemsChange<T> = Extract<ItemsPollPage<T>, { notModified: false }>

/** ONE page of the list as the server answers it (ADR 0033 decision 6): at most a limit of items, `more` while items
 *  remain past them, and the keyset `cursor` of its last item. */
export type ItemsOnePage<T> = { notModified: true } | (ItemsChange<T> & { more: boolean })

/**
 * Read an item list in pages — a first load and a poll alike (ADR 0033 decision 6), the one loop every full read of a
 * revisioned list goes through. [first] is the caller's read (conditional for a poll; a `304` ends it at once); every
 * later page is [next] from the page before's cursor, which the caller asks without `If-None-Match` and with the FIRST
 * page's delete revision. The answer keeps the first page's revisions — read before its rows, so a change committed
 * while the client pages moves the server's revision past them and the next poll pulls it — and the last page's cursor.
 *
 * The pages are one delta: an item a later page carries again (an edit committed while paging) replaces the earlier copy,
 * and the id set is the first page's plus every id a later page carried — those were read after it, so dropping them by
 * the first page's set would lose an add made while the client paged; a removal after the first page is the next poll's
 * (its delete revision moved past the kept one). A later page's own id set is not used, for the same reason.
 */
export async function readItemPages<T>(
  first: () => Promise<ItemsOnePage<T>>,
  next: (cursor: string, deleteRevision: number) => Promise<ItemsOnePage<T>>,
  idOf: (item: T) => string,
): Promise<ItemsPollPage<T>> {
  const head = await first()
  if (head.notModified) return head
  const items = new Map<string, T>(head.items.map((item) => [idOf(item), item] as const))
  const later: string[] = []
  let page: ItemsOnePage<T> = head
  let cursor = head.cursor
  while (page.more) {
    const from = page.cursor
    if (!from) throw new Error('items_page_without_cursor') // a page that says more must say where it ends
    page = await next(from, head.deleteRevision)
    if (page.notModified) throw new Error('items_page_not_modified') // a later page is never conditional
    if (page.more && page.cursor === from) throw new Error('items_page_did_not_advance')
    for (const item of page.items) {
      const id = idOf(item)
      items.delete(id) // the later copy is the newer one
      items.set(id, item)
      later.push(id)
    }
    cursor = page.cursor ?? cursor
  }
  return {
    notModified: false,
    items: [...items.values()],
    revision: head.revision,
    deleteRevision: head.deleteRevision,
    ids: head.ids ? [...new Set([...head.ids, ...later])] : null,
    cursor,
  }
}

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
