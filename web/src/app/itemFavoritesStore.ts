// Reactive item-mark state for the OPEN Memory (ADR 0068). The shared counterpart to favoritesStore: where a
// Memory pin is private to its member, an item mark is seen by the whole scope. Held per node, because that is
// how it is stored and because nothing outside one gallery consumes it.
//
// What this store can and cannot say is the point. It holds every member's marked set for the node, so it can
// answer "is this item marked by anyone", "is it marked by me" and "by how many" — and it can answer nothing
// else, because the wire carries no member id. Aggregate-only is therefore not a discipline the components
// have to keep; it is the only thing the data supports.
import { reactive } from 'vue'
import { loadItemFavorites, saveItemFavorites, type MemberMarks } from '../features/memory/itemFavorites'

export const itemFavoritesStore = reactive({
  nodeId: null as string | null,
  marks: [] as MemberMarks[],
  loaded: false,
})

const mineOf = (marks: MemberMarks[]): Set<string> =>
  marks.find((m) => m.own)?.ids ?? new Set<string>()

/** Is this item marked by the caller? */
export function isMarkedByMe(mediaId: string): boolean {
  return mineOf(itemFavoritesStore.marks).has(mediaId)
}

/** How many members have marked this item — the aggregate, and the most the UI is ever allowed to show. */
export function markCount(mediaId: string): number {
  return itemFavoritesStore.marks.reduce((n, m) => n + (m.ids.has(mediaId) ? 1 : 0), 0)
}

/** Every item anyone in the scope has marked. */
export function markedByAnyone(): Set<string> {
  const all = new Set<string>()
  for (const m of itemFavoritesStore.marks) for (const id of m.ids) all.add(id)
  return all
}

/** Load every member's marked set for this Memory (once per Memory open). */
export async function loadItemFavoritesFor(
  scopeId: string,
  nodeId: string,
  scopeKey: Uint8Array,
): Promise<void> {
  const marks = await loadItemFavorites(scopeId, nodeId, scopeKey)
  itemFavoritesStore.nodeId = nodeId
  itemFavoritesStore.marks = marks
  itemFavoritesStore.loaded = true
}

/**
 * Toggle the caller's mark on an item — optimistic, reverts on a failed write.
 *
 * **Refused until the caller's own set has loaded for THIS node** (ADR 0077 point 5, inbox #274). The write
 * replaces the whole set, so a toggle before the load — or after a load that failed, which leaves `loaded` false
 * — would store a one-element set over the real one. It does nothing instead: no flip, no write.
 */
export async function toggleItemFavorite(
  mediaId: string,
  scopeId: string,
  nodeId: string,
  scopeKey: Uint8Array,
): Promise<void> {
  if (!itemFavoritesStore.loaded || itemFavoritesStore.nodeId !== nodeId) return
  const before = itemFavoritesStore.marks
  const mine = new Set(mineOf(before))
  if (mine.has(mediaId)) mine.delete(mediaId)
  else mine.add(mediaId)

  const others = before.filter((m) => !m.own)
  itemFavoritesStore.marks = [...others, { own: true, ids: mine }]
  try {
    await saveItemFavorites(scopeId, nodeId, scopeKey, mine)
  } catch {
    itemFavoritesStore.marks = before
  }
}

export function clearItemFavorites(): void {
  itemFavoritesStore.nodeId = null
  itemFavoritesStore.marks = []
  itemFavoritesStore.loaded = false
}
