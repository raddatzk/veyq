package com.veyq.memories.itemfavorites

import com.veyq.core.scope.MembershipEraser
import jakarta.enterprise.context.ApplicationScoped

/**
 * A removed member's item favorites for that scope go with their access (ADR 0068). Unlike the private pin set,
 * these rows feed an aggregate every remaining member sees: left behind, a removed member keeps voting on which
 * photos are the good ones, forever, with no way for anyone — including them — to take it back.
 */
@ApplicationScoped
class ItemFavoritesMembershipEraser : MembershipEraser {
    override fun eraseMembership(userId: String, scopeId: String) {
        ItemFavoritesEntity.delete("userId = ?1 and scopeId = ?2", userId, scopeId)
    }
}
