package com.veyq.app.features.content.favorites

import com.veyq.app.generated.api.FavoritesApi
import com.veyq.app.generated.model.Favorites
import com.veyq.app.generated.model.PutFavoritesRequest
import com.veyq.app.shared.http.HttpFailure
import com.veyq.app.shared.http.httpError
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Response
import java.util.UUID
import com.veyq.app.shared.ratelimit.RateLimitExempt

/**
 * HTTP adapter for per-(user, scope) favorites (en.memory.favorites, cr-0031). Implements the generated
 * [FavoritesApi]; thin (ADR 0001) — delegates to [FavoritesHandler]. `@Authenticated`; the caller (from the
 * `SecurityIdentity` principal) must hold a grant for the scope (a non-member gets 404). Operator-blind: only
 * the caller's sealed ciphertext crosses; the server never sees which Memories are pinned.
 */
@Authenticated
@RateLimitExempt(
    reason =
        "Authenticated; one size-capped sealed blob per scope, membership-gated. Nothing to enumerate and " +
        "nothing to amplify.",
)
class FavoritesResource(
    private val handler: FavoritesHandler,
    private val identity: SecurityIdentity,
) : FavoritesApi {

    override fun getFavorites(scopeId: UUID): Response =
        when (val o = handler.get(identity.principal.name, scopeId.toString())) {
            is GetFavoritesOutcome.Found -> Response.ok(Favorites(ciphertext = o.ciphertext)).build()
            is HttpFailure -> httpError(o)
        }

    override fun putFavorites(scopeId: UUID, putFavoritesRequest: PutFavoritesRequest): Response =
        when (val o = handler.put(identity.principal.name, scopeId.toString(), putFavoritesRequest.ciphertext)) {
            PutFavoritesOutcome.Stored -> Response.noContent().build()
            is HttpFailure -> httpError(o)
        }
}
