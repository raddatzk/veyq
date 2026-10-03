package com.veyq.app.features.content.favorites

import com.veyq.app.core.scope.ScopeEraser
import jakarta.enterprise.context.ApplicationScoped

/** The favorites rows of an erased group. Phase 600: references only the scope row, so any phase before core's scope delete would do. */
@ApplicationScoped
class FavoritesScopeEraser : ScopeEraser {
    override val phase = 600

    override fun eraseScope(scopeId: String) {
        FavoritesEntity.delete("scopeId", scopeId)
    }
}
