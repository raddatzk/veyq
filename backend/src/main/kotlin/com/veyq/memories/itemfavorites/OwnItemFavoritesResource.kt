package com.veyq.memories.itemfavorites

import com.veyq.app.generated.api.OwnItemFavoritesApi
import com.veyq.app.generated.model.OwnItemFavoritesEntry
import com.veyq.core.ratelimit.RateLimitExempt
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Response
import java.util.UUID

/**
 * HTTP adapter for the caller's **own** item-mark rows across every scope (`GET /api/item-favorites`, ADR 0068 §9):
 * what an account-key rotation reads to re-tag the caller's marks under the new Master Key. Thin (ADR 0001) —
 * delegates to [ItemFavoritesHandler.listOwn], which leaves out every scope the scope-access gate would refuse.
 *
 * Its own interface, not a method on [ItemFavoritesResource]: one generated interface has one root `@Path`, and an
 * `/api` root shared with the per-node routes made those lose to the `/api/scopes` resources (RESTEasy matches the
 * most specific root first and does not fall back). Operator-blind: the server returns rows it already attributes
 * to the caller, so it learns nothing, and only opaque ciphertext crosses.
 */
@Authenticated
@RateLimitExempt(
    reason =
        "Authenticated; returns only the caller's own size-capped sealed rows, each already readable one node at a " +
            "time. Nothing to enumerate and nothing to amplify.",
)
class OwnItemFavoritesResource(
    private val handler: ItemFavoritesHandler,
    private val identity: SecurityIdentity,
) : OwnItemFavoritesApi {

    override fun listOwnItemFavorites(): Response =
        Response.ok(
            handler.listOwn(identity.principal.name).map {
                OwnItemFavoritesEntry(
                    scopeId = UUID.fromString(it.scopeId),
                    nodeId = UUID.fromString(it.nodeId),
                    ciphertext = it.ciphertext,
                )
            },
        ).build()
}
