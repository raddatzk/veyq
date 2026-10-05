package com.veyq.memories.favorites

import com.veyq.core.http.Failures
import com.veyq.core.http.HttpFailure
import com.veyq.core.membership.AccessMode.READ
import com.veyq.core.membership.AccessMode.WRITE
import com.veyq.core.membership.ScopeAccess
import com.veyq.core.membership.refusal
import jakarta.enterprise.context.ApplicationScoped

sealed interface GetFavoritesOutcome {
    data class Found(val ciphertext: String?) : GetFavoritesOutcome
    object Suspended : GetFavoritesOutcome, HttpFailure by Failures.tenantSuspended
    object NoScope : GetFavoritesOutcome, HttpFailure by Failures.scopeNotFound
}

sealed interface PutFavoritesOutcome {
    object Stored : PutFavoritesOutcome
    object Suspended : PutFavoritesOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : PutFavoritesOutcome, HttpFailure by Failures.scopeDeleting
    object NoScope : PutFavoritesOutcome, HttpFailure by Failures.scopeNotFound
}

/**
 * Use-case logic for per-(user, scope) favorites (cr-0031). Scope access is the one gate,
 * [ScopeAccess], asked first (uc.spaces.scope-access): a non-member sees a 404, not a reveal. The handler never inspects the blob.
 */
@ApplicationScoped
class FavoritesHandler(private val store: FavoritesStore, private val access: ScopeAccess) {

    fun get(userId: String, scopeId: String): GetFavoritesOutcome {
        access.check(userId, scopeId, READ)
            .refusal(GetFavoritesOutcome.NoScope, GetFavoritesOutcome.Suspended)?.let { return it }
        return GetFavoritesOutcome.Found(store.get(userId, scopeId))
    }

    fun put(userId: String, scopeId: String, ciphertext: String): PutFavoritesOutcome {
        access.check(userId, scopeId, WRITE)
            .refusal(PutFavoritesOutcome.NoScope, PutFavoritesOutcome.Suspended, PutFavoritesOutcome.Deleting)?.let { return it }
        store.upsert(userId, scopeId, ciphertext)
        return PutFavoritesOutcome.Stored
    }
}
