package com.veyq.app.features.content.itemfavorites

import com.veyq.app.shared.http.Failures
import com.veyq.app.shared.http.HttpFailure
import jakarta.enterprise.context.ApplicationScoped

sealed interface GetItemFavoritesOutcome {
    data class Found(val entries: List<MemberItemFavorites>) : GetItemFavoritesOutcome
    object NoScope : GetItemFavoritesOutcome, HttpFailure by Failures.scopeNotFound
}

sealed interface PutItemFavoritesOutcome {
    object Stored : PutItemFavoritesOutcome
    object NoScope : PutItemFavoritesOutcome, HttpFailure by Failures.scopeNotFound
}

/**
 * Use-case logic for per-(member, scope, node) item favorites (ADR 0068). Scope access = the caller holds a
 * membership for the scope (operator-blind: a non-member sees a 404, not a reveal). The handler never inspects
 * a blob.
 *
 * The read is membership-gated but **not** caller-filtered — every member's blob crosses, by design. The write
 * takes the caller's id from the token and the body carries none, so a caller cannot address another member's
 * row at all.
 */
@ApplicationScoped
class ItemFavoritesHandler(private val store: ItemFavoritesStore) {

    fun get(userId: String, scopeId: String, nodeId: String): GetItemFavoritesOutcome {
        if (!store.isMember(userId, scopeId)) return GetItemFavoritesOutcome.NoScope
        return GetItemFavoritesOutcome.Found(store.listForNode(userId, scopeId, nodeId))
    }

    fun put(userId: String, scopeId: String, nodeId: String, ciphertext: String): PutItemFavoritesOutcome {
        if (!store.isMember(userId, scopeId)) return PutItemFavoritesOutcome.NoScope
        store.upsert(userId, scopeId, nodeId, ciphertext)
        return PutItemFavoritesOutcome.Stored
    }
}
