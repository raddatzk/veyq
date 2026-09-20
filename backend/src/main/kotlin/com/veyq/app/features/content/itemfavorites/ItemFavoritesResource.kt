package com.veyq.app.features.content.itemfavorites

import com.veyq.app.generated.api.ItemFavoritesApi
import com.veyq.app.generated.model.ItemFavoritesEntry
import com.veyq.app.generated.model.PutItemFavoritesRequest
import com.veyq.app.shared.http.HttpFailure
import com.veyq.app.shared.http.httpError
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Response
import java.util.UUID
import com.veyq.app.shared.ratelimit.RateLimitExempt

/**
 * HTTP adapter for per-(member, scope, node) item favorites (en.memory.favorites, ADR 0068). Implements the
 * generated [ItemFavoritesApi]; thin (ADR 0001) — delegates to [ItemFavoritesHandler]. `@Authenticated`; the
 * caller must hold a grant for the scope (a non-member gets 404).
 *
 * Operator-blind: only sealed ciphertext crosses. The `memberId` on each entry is sanctioned metadata (scope
 * membership, which the server already holds) and is what lets the client tell its own set apart from the
 * others' — the UI renders an aggregate count from it and never names a member.
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
                    o.entries.map { ItemFavoritesEntry(memberId = UUID.fromString(it.userId), ciphertext = it.ciphertext) },
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
