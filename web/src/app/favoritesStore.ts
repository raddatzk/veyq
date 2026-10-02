// Reactive Memory-pin state for the shell. Holds the caller's pinned-Memory id set for the active scope,
// decrypted on device (see features/memory/favorites.ts). Toggling is optimistic — the UI updates at once and
// the whole set is re-sealed + PUT; a failed write reverts. Cleared on sign-out / re-lock.
//
// The pin set seals under the member's **master key**, never under `K_SC` (ADR 0068 §1-§3). `K_SC` is SHARED
// in a group scope, so a pin set sealed under it is readable by every co-member — the data leaked, the UI
// merely never showed it. The seal does **not** branch on scope type even though `K_SC` happens to be private
// in a personal scope: a conditional seal would be defensible on paper and is exactly where a fallback
// survives in the case nobody tests. One seal, every scope — which is what makes the guarantee assertable
// (src/app/favoritesPrivacy.test.ts).
//
// The **item** marks inside a Memory are the other object and live elsewhere: shared on purpose, sealed under
// `K_SC` (features/memory/itemFavorites.ts).
import { reactive } from 'vue'
import { loadFavorites, saveFavorites } from '../features/memory/favorites'
import { masterKey } from './vault'

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
 * Toggle a Memory's pinned state — optimistic, reverts on a failed write.
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
  } catch {
    const reverted = new Set(next)
    if (reverted.has(nodeId)) reverted.delete(nodeId)
    else reverted.add(nodeId)
    favoritesStore.ids = reverted
  }
}

export function clearFavorites(): void {
  favoritesStore.ids = new Set()
  favoritesStore.loaded = false
}
