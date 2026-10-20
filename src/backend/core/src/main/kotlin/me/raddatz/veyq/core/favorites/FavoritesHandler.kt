package me.raddatz.veyq.core.favorites

import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.membership.AccessMode.READ
import me.raddatz.veyq.core.membership.ScopeAccess
import me.raddatz.veyq.core.membership.refusal
import jakarta.enterprise.context.ApplicationScoped

sealed interface GetFavoritesOutcome {
    data class Found(val ciphertext: String?) : GetFavoritesOutcome
    object Suspended : GetFavoritesOutcome, HttpFailure by Failures.tenantSuspended
    object NoScope : GetFavoritesOutcome, HttpFailure by Failures.scopeNotFound
}

sealed interface PutFavoritesOutcome {
    object Stored : PutFavoritesOutcome
    object Suspended : PutFavoritesOutcome, HttpFailure by Failures.tenantSuspended
    object NoScope : PutFavoritesOutcome, HttpFailure by Failures.scopeNotFound
}

/**
 * Use-case logic for per-(user, scope) node pins (ADR 0068). Scope access is the one gate,
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
        // READ, not WRITE: a pin is the member's own sealed preference, not a change to the scope, so a space waiting for
        // its deletion keeps accepting it (the heart must not bounce back in that window) — ADR 0068 §10.
        access.check(userId, scopeId, READ)
            .refusal(PutFavoritesOutcome.NoScope, PutFavoritesOutcome.Suspended)?.let { return it }
        store.upsert(userId, scopeId, ciphertext)
        return PutFavoritesOutcome.Stored
    }
}
