package me.raddatz.veyq.core.admin.tenant

import me.raddatz.veyq.core.crypto.SealedValue
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import me.raddatz.veyq.core.http.FailureStatus
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.membership.AccessMode.READ
import me.raddatz.veyq.core.membership.AccessMode.WRITE
import me.raddatz.veyq.core.membership.MembershipStore
import me.raddatz.veyq.core.membership.ScopeAccess
import me.raddatz.veyq.core.membership.refusal
import me.raddatz.veyq.core.scope.ScopeEntity
import java.time.Instant

/** Result of writing the caller's verification of a member (ADR 0111 decision 12). */
sealed interface PutVerificationOutcome {
    data object Stored : PutVerificationOutcome
    /** No such tenant, or the caller is not an accepted member of it (indistinguishable, ADR 0042). */
    data object NotFound : PutVerificationOutcome, HttpFailure by Failures.tenantNotFound
    data object Suspended : PutVerificationOutcome, HttpFailure by Failures.tenantSuspended
    /** The caller named themselves: a verification is of another member. */
    data object OfSelf : PutVerificationOutcome, HttpFailure by httpFailure("verification_of_self", FailureStatus.BAD_REQUEST)
    /** The subject is no accepted member of the tenant. */
    data object MemberNotFound : PutVerificationOutcome, HttpFailure by Failures.memberNotFound
    /** The statement's value key is wrapped under a tenant epoch that is not the current one (ADR 0115 decision 3): the
     *  tenant has one key at a time, and a rotation re-wraps every verification's key under it, so none lands under
     *  another. */
    data object StaleEpoch : PutVerificationOutcome, HttpFailure by Failures.staleEpoch
}

/** Result of reading a tenant's verifications. */
sealed interface ListVerificationsOutcome {
    data class Listed(val page: VerificationPageView) : ListVerificationsOutcome
    data object NotFound : ListVerificationsOutcome, HttpFailure by Failures.tenantNotFound
    data object Suspended : ListVerificationsOutcome, HttpFailure by Failures.tenantSuspended
}

/**
 * The members' verifications of each other in a tenant (ADR 0111 decisions 12-13). Each is one opaque ciphertext the
 * server stores and relays and cannot check: it checks only who may write which row and that the epoch it names is the
 * tenant's current one. Both operations sit behind the tenant membership gate (the directory's, ADR 0042) and nothing a
 * guest reaches.
 *
 * **The verifier is always the caller** — the row a put writes is keyed by the authenticated caller's id, never by
 * anything in the request — so no member can write, replace or withhold another member's verification.
 */
@ApplicationScoped
class TenantVerificationHandler(
    private val access: ScopeAccess,
    private val memberships: MembershipStore,
    private val store: VerificationStore,
) {
    fun put(callerId: String, tenantId: String, subjectUserId: String, epoch: Int, statement: SealedValue): PutVerificationOutcome {
        access.check(callerId, tenantId, WRITE)
            .refusal(PutVerificationOutcome.NotFound, PutVerificationOutcome.Suspended)?.let { return it }
        if (subjectUserId == callerId) return PutVerificationOutcome.OfSelf
        return QuarkusTransaction.requiringNew().call {
            // The tenant scope's row lock — the one a member's removal holds while its cut deletes their rows
            // (VerificationMembershipEraser) and a rotation holds while it moves the epoch: the gate, the subject and
            // the epoch are asked again beneath it, so a put racing either sees the state it leaves.
            val scope = ScopeEntity.lockRow(tenantId) ?: return@call PutVerificationOutcome.NotFound
            access.check(callerId, tenantId, WRITE)
                .refusal(PutVerificationOutcome.NotFound, PutVerificationOutcome.Suspended)?.let { return@call it }
            if (!memberships.belongsToTenant(subjectUserId, tenantId)) return@call PutVerificationOutcome.MemberNotFound
            if (epoch != scope.currentEpoch) return@call PutVerificationOutcome.StaleEpoch
            store.put(tenantId, callerId, subjectUserId, epoch, statement, Instant.now())
            PutVerificationOutcome.Stored
        }
    }

    fun list(
        callerId: String,
        tenantId: String,
        subjectUserId: String?,
        verifierUserId: String?,
        after: Pair<String, String>?,
        limit: Int,
    ): ListVerificationsOutcome {
        access.check(callerId, tenantId, READ)
            .refusal(ListVerificationsOutcome.NotFound, ListVerificationsOutcome.Suspended)?.let { return it }
        return ListVerificationsOutcome.Listed(
            QuarkusTransaction.requiringNew().call { store.page(tenantId, subjectUserId, verifierUserId, after, limit) },
        )
    }

    companion object {
        /** The most rows one page serves, and the page a read that names no limit gets — the contract's maximum. */
        const val PAGE_CAP = 1000

        /** The wire cursor `verifierUserId:subjectUserId` as a pair; the contract's pattern already shaped it. */
        fun parseAfter(raw: String?): Pair<String, String>? =
            raw?.split(':')?.takeIf { it.size == 2 }?.let { it[0] to it[1] }
    }
}
