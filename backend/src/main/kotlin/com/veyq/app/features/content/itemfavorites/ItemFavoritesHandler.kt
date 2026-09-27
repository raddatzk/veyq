package com.veyq.app.features.content.itemfavorites

import com.veyq.app.shared.http.Failures
import com.veyq.app.shared.http.HttpFailure
import com.veyq.app.shared.membership.AccessMode.READ
import com.veyq.app.shared.membership.AccessMode.WRITE
import com.veyq.app.shared.membership.ScopeAccess
import com.veyq.app.shared.membership.refusal
import jakarta.enterprise.context.ApplicationScoped

sealed interface GetItemFavoritesOutcome {
    data class Found(val entries: List<MemberItemFavorites>) : GetItemFavoritesOutcome
    object Suspended : GetItemFavoritesOutcome, HttpFailure by Failures.tenantSuspended
    object NoScope : GetItemFavoritesOutcome, HttpFailure by Failures.scopeNotFound
}

sealed interface PutItemFavoritesOutcome {
    object Stored : PutItemFavoritesOutcome
    object Suspended : PutItemFavoritesOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : PutItemFavoritesOutcome, HttpFailure by Failures.scopeDeleting
    object NoScope : PutItemFavoritesOutcome, HttpFailure by Failures.scopeNotFound
}

/**
 * Use-case logic for per-(member, scope, node) item favorites (ADR 0068). Scope access is the one gate,
 * [ScopeAccess], asked first (uc.spaces.scope-access): a non-member sees a 404, not a reveal. The handler never inspects
 * a blob.
 *
 * The read is membership-gated but **not** caller-filtered — every member's blob crosses, by design. The write
 * takes the caller's id from the token and the body carries none, so a caller cannot address another member's
 * row at all.
 */
@ApplicationScoped
class ItemFavoritesHandler(private val store: ItemFavoritesStore, private val access: ScopeAccess) {

    fun get(userId: String, scopeId: String, nodeId: String): GetItemFavoritesOutcome {
        access.check(userId, scopeId, READ)
            .refusal(GetItemFavoritesOutcome.NoScope, GetItemFavoritesOutcome.Suspended)?.let { return it }
        return GetItemFavoritesOutcome.Found(store.listForNode(userId, scopeId, nodeId))
    }

    fun put(userId: String, scopeId: String, nodeId: String, ciphertext: String): PutItemFavoritesOutcome {
        access.check(userId, scopeId, WRITE)
            .refusal(PutItemFavoritesOutcome.NoScope, PutItemFavoritesOutcome.Suspended, PutItemFavoritesOutcome.Deleting)?.let { return it }
        store.upsert(userId, scopeId, nodeId, ciphertext)
        return PutItemFavoritesOutcome.Stored
    }
}
