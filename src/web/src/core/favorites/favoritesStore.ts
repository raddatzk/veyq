// Reactive node-pin state for the shell (core's: any kind of node can be pinned). Holds the caller's pinned node-id set for the
// active scope, decrypted on device (see ./favorites.ts). Toggling is optimistic — the UI updates at once and
// the whole set is re-sealed + PUT; a failed write reverts. Cleared on sign-out / re-lock.
//
// The pin set seals under the member's **master key**, never under `K_SC` (ADR 0068 §1-§3). `K_SC` is SHARED
// in a group scope, so a pin set sealed under it is readable by every co-member — the data leaked, the UI
// merely never showed it. The seal does **not** branch on scope type even though `K_SC` happens to be private
// in a personal scope: a conditional seal would be defensible on paper and is exactly where a fallback
// survives in the case nobody tests. One seal, every scope — which is what makes the guarantee assertable
// (src/core/favorites/favoritesPrivacy.test.ts).
//
// The **item** marks inside a Memory are the other object and Memories': shared on purpose, sealed under
// `K_SC` (features/memories/itemFavorites.ts).
//
// **A pinned id is inert when its node does not resolve.** The server cannot read the set, so a purged node's id cannot be
// removed server-side; every view resolves the ids against the loaded tree and skips what is not there (the sidebar), and a
// trashed node's pin survives so a restore hands the heart back. No client-side prune: a tree that is merely not loaded yet
// would be read as "gone", and a prune on that is a lost pin. The ceiling (ADR 0068, ~1 600 ids) is the only cost.
import { reactive } from 'vue'
import { loadFavorites, saveFavorites } from './favorites'
import { masterKey } from '../../app/vault'

export const favoritesStore = reactive({
  ids: new Set<string>(),
  loaded: false,
})

export function isFavorite(nodeId: string): boolean {
  return favoritesStore.ids.has(nodeId)
}

/** The member's own key, or a throw — every path here runs behind an unlocked vault. */
function ownKey(): Uint8Array {
  const mk = masterKey()
  if (!mk) throw new Error('favorites require an unlocked vault')
  return mk
}

/** Load + decrypt the pinned set for the active scope (once per space open). */
export async function loadFavoritesFor(scopeId: string): Promise<void> {
  favoritesStore.ids = await loadFavorites(scopeId, ownKey())
  favoritesStore.loaded = true
}

/**
 * Toggle a node's pinned state — optimistic; a failed write reverts and rethrows (the heart's owner says so).
 *
 * **Refused until the pin set has loaded** (ADR 0077 point 5, inbox #274): the write replaces the whole set, so a
 * toggle before the load (or after one that failed) would store a one-element set over the real pins.
 */
export async function toggleFavorite(nodeId: string, scopeId: string): Promise<void> {
  if (!favoritesStore.loaded) return
  const next = new Set(favoritesStore.ids)
  if (next.has(nodeId)) next.delete(nodeId)
  else next.add(nodeId)
  favoritesStore.ids = next
  try {
    await saveFavorites(scopeId, ownKey(), next)
  } catch (e) {
    const reverted = new Set(next)
    if (reverted.has(nodeId)) reverted.delete(nodeId)
    else reverted.add(nodeId)
    favoritesStore.ids = reverted
    throw e // the heart's owner says the pin did not land (inbox #500) - a silent spring-back reads as a missed click
  }
}

export function clearFavorites(): void {
  favoritesStore.ids = new Set()
  favoritesStore.loaded = false
}
