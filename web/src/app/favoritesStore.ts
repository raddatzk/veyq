// Reactive favorites state for the shell (cr-0031). Holds the caller's pinned-Memory id set for the active
// scope, decrypted on device (see features/memory/favorites.ts). Toggling is optimistic — the UI updates at
// once and the whole set is re-sealed + PUT; a failed write reverts. Cleared on sign-out / re-lock.
import { reactive } from 'vue'
import { loadFavorites, saveFavorites } from '../features/memory/favorites'
import { keyAt, type EpochKeys } from '../features/spaces/epochs'

export const favoritesStore = reactive({
  ids: new Set<string>(),
  loaded: false,
})

export function isFavorite(nodeId: string): boolean {
  return favoritesStore.ids.has(nodeId)
}

// Favorites seal under the **base epoch** `E_0` (ADR 0024, cr-0089): the pinned set is one member-owned blob
// re-sealed on every toggle, so it must key off a **stable** scope key that a rotation never changes — otherwise a
// rotation would strand the previously-sealed set. For an unrotated scope `E_0` is simply the current key, so this
// is backward-compatible. (Node content still seals under the *write* epoch; favorites are the deliberate exception.)
const stableKey = (epochs: EpochKeys) => keyAt(epochs, 0)

/** Load + decrypt the pinned set for the active scope (once per space open). */
export async function loadFavoritesFor(scopeId: string, epochs: EpochKeys): Promise<void> {
  favoritesStore.ids = await loadFavorites(scopeId, stableKey(epochs))
  favoritesStore.loaded = true
}

/** Toggle a Memory's pinned state — optimistic, reverts on a failed write. */
export async function toggleFavorite(nodeId: string, scopeId: string, epochs: EpochKeys): Promise<void> {
  const next = new Set(favoritesStore.ids)
  if (next.has(nodeId)) next.delete(nodeId)
  else next.add(nodeId)
  favoritesStore.ids = next
  try {
    await saveFavorites(scopeId, stableKey(epochs), next)
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
