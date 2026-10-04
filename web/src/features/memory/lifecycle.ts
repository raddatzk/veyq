// The Memories application's lifecycle hooks (ADR 0079; registered in `register.ts`). The shell calls them through
// the registry at the three moments an application holds state it must keep in step with the platform: a scope is
// reset, a scope is entered, the Master Key rotates.
import type { LifecycleHooks } from '../../core/applications/registry'
import { resetCovers } from './coverStore'
import { clearFavorites, favoritesStore, loadFavoritesFor } from './favoritesStore'
import { resealFavoritesUnderNewMasterKey } from './favorites'
import { retagItemMarksUnderNewMasterKey } from './itemFavorites'

export const memoriesLifecycle: LifecycleHooks = {
  // No stale pins or covers bleed across scopes; every decrypted cover URL is revoked rather than left dangling.
  onScopeReset() {
    clearFavorites()
    resetCovers()
  },

  // The pin set seals under the member's MASTER key (ADR 0068), which does not rotate with the scope, so no epoch key
  // is needed here. Loads once per reset: a reset puts `loaded` back to false, a second entry is a no-op.
  async onScopeEntered(scopeId) {
    if (!favoritesStore.loaded) await loadFavoritesFor(scopeId)
  },

  // Pins and item marks are sealed/tagged under the master key, so a rotation that skipped them would leave them
  // undecryptable. Each runs on its own and is best-effort exactly as before: an unreadable pin set is an empty one
  // (the Memories are all still there, just no longer pinned), and an item mark whose tag the new key does not verify
  // counts as someone else's set (ADR 0068 §9) — neither may abort the rest of the rotation.
  async onMasterKeyRotated(oldMk, newMk, scopeIds) {
    try {
      await resealFavoritesUnderNewMasterKey(oldMk, newMk, scopeIds)
    } catch {
      /* see above */
    }
    try {
      await retagItemMarksUnderNewMasterKey(oldMk, newMk)
    } catch {
      /* see above */
    }
  },
}
