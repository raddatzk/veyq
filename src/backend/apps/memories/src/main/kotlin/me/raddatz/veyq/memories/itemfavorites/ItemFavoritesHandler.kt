package me.raddatz.veyq.memories.itemfavorites

import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.membership.Access
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.membership.AccessMode.READ
import me.raddatz.veyq.core.membership.AccessMode.WRITE
import me.raddatz.veyq.core.membership.ScopeAccess
import me.raddatz.veyq.core.membership.refusal
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped

sealed interface GetItemFavoritesOutcome {
    data class Found(val entries: List<MemberItemFavorites>) : GetItemFavoritesOutcome
    object Suspended : GetItemFavoritesOutcome, HttpFailure by Failures.tenantSuspended
    object NoScope : GetItemFavoritesOutcome, HttpFailure by Failures.scopeNotFound
}

/** One of the caller's own item-mark rows, addressed by the slot it is stored at. */
data class OwnItemFavorites(val scopeId: String, val nodeId: String, val ciphertext: String)

sealed interface PutItemFavoritesOutcome {
    object Stored : PutItemFavoritesOutcome
    /** Sealed under a scope epoch that is not the current one (ADR 0115 decision 3) — nothing written. */
    object StaleEpoch : PutItemFavoritesOutcome, HttpFailure by Failures.staleEpoch
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

    /**
     * The caller's own rows across every scope the gate still lets them read — what an account-key rotation re-tags
     * (ADR 0068 §9). **A filter, not an answer:** a row of a scope the caller has left or was only proposed to, or
     * whose tenant is suspended, is left out rather than refused, so the read says nothing about which scopes those
     * are. The filter is the gate's own READ answer, asked once per scope, so "may this caller see this scope" keeps
     * its one definition (uc.spaces.scope-access). A co-member's row cannot appear: the store reads by the caller's id.
     */
    fun listOwn(userId: String): List<OwnItemFavorites> {
        val readable = HashMap<String, Boolean>()
        return store.listOwn(userId)
            .filter { row -> readable.getOrPut(row.scopeId) { access.check(userId, row.scopeId, READ) == Access.Ok } }
            .map { OwnItemFavorites(it.scopeId, it.nodeId, it.ciphertext) }
    }

    /**
     * The gate runs twice: first as the fast path, then under the scope row's lock, taken first, in the write's own
     * transaction. Every removal holds that lock while it deletes the removed member's rows (developer-styleguide §12),
     * so checked only before it, a member being removed wrote a row the removal never saw, into a group they had left.
     * The stated [epoch] is judged under the same lock (ADR 0115 decision 3): the blob is sealed under the scope key,
     * which a rotation's commit replaces while it holds that lock and re-seals every row, so a row lands only at the
     * scope's current epoch — one under the old key after the commit is `stale_epoch`.
     */
    fun put(userId: String, scopeId: String, nodeId: String, ciphertext: String, epoch: Int): PutItemFavoritesOutcome {
        refusal(userId, scopeId)?.let { return it }
        return QuarkusTransaction.requiringNew().call {
            val current = store.lockScope(scopeId)
            refusal(userId, scopeId)?.let { return@call it }
            if (current != epoch) return@call PutItemFavoritesOutcome.StaleEpoch
            store.upsert(userId, scopeId, nodeId, ciphertext)
            PutItemFavoritesOutcome.Stored
        }
    }

    private fun refusal(userId: String, scopeId: String): PutItemFavoritesOutcome? =
        access.check(userId, scopeId, WRITE)
            .refusal(PutItemFavoritesOutcome.NoScope, PutItemFavoritesOutcome.Suspended, PutItemFavoritesOutcome.Deleting)
}
