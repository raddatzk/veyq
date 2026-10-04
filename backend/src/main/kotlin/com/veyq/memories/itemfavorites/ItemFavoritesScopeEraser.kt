package com.veyq.memories.itemfavorites

import com.veyq.core.scope.ScopeEraser
import jakarta.enterprise.context.ApplicationScoped

/** The item-favorites rows of an erased group. Phase 610: references only the scope row (`fk_item_favorites_scope`). */
@ApplicationScoped
class ItemFavoritesScopeEraser : ScopeEraser {
    override val phase = 610

    override fun eraseScope(scopeId: String) {
        ItemFavoritesEntity.delete("scopeId", scopeId)
    }
}
