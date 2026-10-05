package me.raddatz.veyq.memories.itemfavorites

import me.raddatz.veyq.memories.generated.api.ItemFavoritesApi
import me.raddatz.veyq.memories.generated.model.ItemFavoritesEntry
import me.raddatz.veyq.memories.generated.model.PutItemFavoritesRequest
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpError
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Response
import java.util.UUID
import me.raddatz.veyq.core.ratelimit.RateLimitExempt

/**
 * HTTP adapter for per-(member, scope, node) item favorites (en.memory.favorites, ADR 0068). Implements the
 * generated [ItemFavoritesApi]; thin (ADR 0001) — delegates to [ItemFavoritesHandler]. `@Authenticated`; the
 * caller must hold a grant for the scope (a non-member gets 404).
 *
 * Operator-blind: only sealed ciphertext crosses, and the sole identity on it is the `self` flag on the
 * caller's own entry. No member id is sent: the array separates the members and an item's count is how many
 * entries hold it, so the aggregate-only rule (ADR 0068 §6) is carried by the data shape rather than by
 * trusting the UI to withhold a name it was handed.
 */
@Authenticated
@RateLimitExempt(
    reason =
        "Authenticated; one size-capped sealed blob per (member, scope, node), membership-gated. Nothing to " +
        "enumerate and nothing to amplify.",
)
class ItemFavoritesResource(
    private val handler: ItemFavoritesHandler,
    private val identity: SecurityIdentity,
) : ItemFavoritesApi {

    override fun getItemFavorites(scopeId: UUID, nodeId: UUID): Response =
        when (val o = handler.get(identity.principal.name, scopeId.toString(), nodeId.toString())) {
            is GetItemFavoritesOutcome.Found ->
                Response.ok(
                    o.entries.map { ItemFavoritesEntry(self = it.own, ciphertext = it.ciphertext) },
                ).build()
            is HttpFailure -> httpError(o)
        }

    override fun putItemFavorites(
        scopeId: UUID,
        nodeId: UUID,
        putItemFavoritesRequest: PutItemFavoritesRequest,
    ): Response =
        when (
            val o = handler.put(
                identity.principal.name,
                scopeId.toString(),
                nodeId.toString(),
                putItemFavoritesRequest.ciphertext,
            )
        ) {
            PutItemFavoritesOutcome.Stored -> Response.noContent().build()
            is HttpFailure -> httpError(o)
        }
}
