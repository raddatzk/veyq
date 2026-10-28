package me.raddatz.veyq.core.identity.keychain

import me.raddatz.veyq.core.generated.api.KeyChainApi
import me.raddatz.veyq.core.generated.model.KeyChain
import me.raddatz.veyq.core.generated.model.KeyChainStep
import me.raddatz.veyq.core.http.FailureStatus
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpError
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.ratelimit.RateLimitExempt
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response
import java.time.ZoneOffset
import java.util.UUID

/** Outcome of reading a person's key chain. */
sealed interface KeyChainOutcome {
    data class Chain(val userId: String, val links: List<KeyChainLink>) : KeyChainOutcome

    /** No such person — or one who shares no tenant with the caller; indistinguishable (ADR 0042). */
    data object NotFound : KeyChainOutcome, HttpFailure by httpFailure("not_found", FailureStatus.NOT_FOUND)
}

/**
 * The key-chain read (ADR 0111 decision 7, cr-secure-the-account decision 2): a person's whole chain, served to the
 * person and to users who share a tenant with them ([KeyChainStore.mayRead]). Anyone else gets exactly the answer an
 * unknown id gets, read in the same order (gate first), so the read enumerates nobody. Operator-blind: public keys and
 * signatures the server stores, and each step's server-observed time — it already knows every rotation.
 */
@ApplicationScoped
class KeyChainHandler(private val store: KeyChainStore) {
    fun read(callerId: String, userId: String): KeyChainOutcome = QuarkusTransaction.requiringNew().call {
        if (!store.mayRead(callerId, userId)) return@call KeyChainOutcome.NotFound
        store.chainOf(userId)?.let { KeyChainOutcome.Chain(userId, it) } ?: KeyChainOutcome.NotFound
    }
}

/** HTTP adapter for the key-chain read — thin (ADR 0001), [KeyChainHandler] decides. */
@Authenticated
@RateLimitExempt(
    reason =
        "Authenticated, read-only, and answers the same 404 for an unknown id as for a person who shares no tenant with " +
            "the caller, so repeating it enumerates nobody; what it serves (public keys and signatures) the caller's " +
            "directory reads already serve beside a head, and its cost is one indexed read per call.",
)
class KeyChainResource(
    private val handler: KeyChainHandler,
    private val identity: SecurityIdentity,
) : KeyChainApi {

    override fun getKeyChain(userId: UUID): Response =
        when (val outcome = handler.read(identity.principal.name, userId.toString())) {
            is KeyChainOutcome.Chain -> Response.ok(
                KeyChain(
                    userId = userId,
                    head = outcome.links.last().head,
                    steps = outcome.links.map {
                        KeyChainStep(
                            index = it.index, identityPublicKey = it.identityPublicKey,
                            secretKeyProofPublicKey = it.secretKeyProofPublicKey,
                            createdAt = it.createdAt.atOffset(ZoneOffset.UTC), previousHead = it.previousHead,
                            identitySignature = it.identitySignature, proofSignature = it.proofSignature,
                        )
                    },
                ),
            ).build()
            is HttpFailure -> httpError(outcome)
        }
}
