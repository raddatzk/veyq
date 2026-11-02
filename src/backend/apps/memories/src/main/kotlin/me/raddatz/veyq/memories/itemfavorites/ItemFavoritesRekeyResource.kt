package me.raddatz.veyq.memories.itemfavorites

import me.raddatz.veyq.memories.generated.api.ItemFavoritesRekeyApi
import me.raddatz.veyq.memories.generated.model.ItemFavoritesRekeyRow
import me.raddatz.veyq.memories.generated.model.ScopeItemFavoritesRekeyPage
import me.raddatz.veyq.memories.generated.model.ScopeItemFavoritesRekeyStage
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpError
import me.raddatz.veyq.core.membership.AccessMode.READ
import me.raddatz.veyq.core.membership.AccessMode.WRITE
import me.raddatz.veyq.core.node.rekey.RekeyOutcome
import me.raddatz.veyq.core.node.rekey.RekeyRight
import me.raddatz.veyq.core.node.rekey.ScopeRekeyHandler
import me.raddatz.veyq.core.node.rekey.ScopeRekeyStage
import me.raddatz.veyq.core.ratelimit.RateLimitExempt
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.validation.validated
import io.quarkus.panache.common.Sort
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Response
import java.util.UUID

/**
 * HTTP adapter for Memories' part of a rotation's scope-level move (ADR 0115 decision 2): every item-mark row's value key
 * wrap of the scope, paged by its opaque `markId` — never a member id (ADR 0068 § 9) — and the stage of their re-wrapped
 * keys under the rotation's re-key id, which core's `rotateScope` swaps in through [ItemFavoritesScopeRekeyParticipant].
 * Wraps only, both ways: no row's ciphertext is served or staged here. The
 * caller's rights are core's scope re-key's ([ScopeRekeyHandler.refusal]: the rotator, holding the current grant).
 * Not held by the application switch: a rotation is the security of what exists (ADR 0109), and a group removal must
 * not wait on Memories being on.
 */
@Authenticated
@RateLimitExempt(
    reason =
        "Authenticated, rotator-gated re-wrap of one scope's item marks' keys, read and staged at the contract's caps; its " +
            "volume is bounded by the rows the scope holds. No anonymous surface and no external cost.",
)
class ItemFavoritesRekeyResource(
    private val scopeRekey: ScopeRekeyHandler,
    private val identity: SecurityIdentity,
) : ItemFavoritesRekeyApi {

    override fun listScopeItemFavoritesRekey(scopeId: UUID, since: String?, limit: Int?): Response {
        val sid = scopeId.toString()
        scopeRekey.refusal(identity.principal.name, sid, READ)?.let { return refused(it) }
        val after = since?.takeIf { it.isNotEmpty() }?.let { runCatching { UUID.fromString(it).toString() }.getOrNull() ?: return refused(RekeyOutcome.InvalidRequest) }
        val scope = ScopeEntity.findById(sid) ?: return refused(RekeyRight.NoScope)
        val n = limit ?: PAGE
        val rows = ItemFavoritesEntity.find(
            if (after == null) "scopeId = ?1" else "scopeId = ?1 and markId > ?2", Sort.by("markId"),
            *listOfNotNull(sid, after).toTypedArray(),
        ).range(0, n).list()
        val page = rows.take(n)
        return Response.ok(
            ScopeItemFavoritesRekeyPage(
                currentEpoch = scope.currentEpoch,
                marks = page.map { ItemFavoritesRekeyRow(UUID.fromString(it.markId), UUID.fromString(it.nodeId), it.wrappedValueKey) },
                more = rows.size > n,
                cursor = page.lastOrNull()?.markId,
            ),
        ).build()
    }

    override fun stageScopeItemFavoritesRekey(scopeId: UUID, rekeyId: UUID, scopeItemFavoritesRekeyStage: ScopeItemFavoritesRekeyStage): Response {
        val sid = scopeId.toString()
        val marks = scopeItemFavoritesRekeyStage.marks.orEmpty()
        // The rights first: what a row holds is answered to the rotator only.
        scopeRekey.refusal(identity.principal.name, sid, WRITE)?.let { return refused(it) }
        // Each wrap names the digest of the wrap it replaces (ADR 0115 decision 2): a row that holds another by now —
        // replaced since the read (a write mints a fresh value key), or gone, a mark of another scope among them — refuses
        // the batch, so a write between the read and this stage is never replaced by a re-wrap of the key it replaced. The
        // digest kept beside the wrap is the commit's compare-and-set for a write after it.
        val stored = if (marks.isEmpty()) emptyMap()
        else ItemFavoritesEntity.list("scopeId = ?1 and markId in ?2", sid, marks.map { it.markId.toString() })
            .associate { it.markId to it.wrappedValueKey }
        val values = marks.associate { m ->
            val id = m.markId.toString()
            val now = ScopeRekeyStage.digest(stored[id])
            if (now != m.replacedDigest) return refused(RekeyOutcome.PartsStale)
            ScopeRekeyStage.Key(id, ItemFavoritesScopeRekeyParticipant.FIELD) to ScopeRekeyStage.Value(m.wrappedValueKey, now)
        }
        return when (val o = scopeRekey.stagePart(
            identity.principal.name, sid, rekeyId.toString(), scopeItemFavoritesRekeyStage.fromEpoch.validated(),
            ItemFavoritesScopeRekeyParticipant.PART, values,
        )) {
            RekeyOutcome.Staged -> Response.noContent().build()
            else -> refused(o)
        }
    }

    private fun refused(o: RekeyOutcome): Response = when (o) {
        is HttpFailure -> httpError(o)
        else -> error("not a refusal: $o")
    }

    private companion object {
        /** The contract's default page, the stage's cap: a row's wrap is under 128 characters. */
        const val PAGE = 256
    }
}
