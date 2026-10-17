package me.raddatz.veyq.core.moderation

import me.raddatz.veyq.core.crypto.ModerationProof
import me.raddatz.veyq.core.generated.api.ModerationApi
import me.raddatz.veyq.core.http.FailureStatus
import me.raddatz.veyq.core.http.httpError
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.identity.ModeratorEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.security.identity.SecurityIdentity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.Response
import org.jboss.resteasy.reactive.server.ServerRequestFilter
import org.jboss.resteasy.reactive.server.SimpleResourceInfo
import java.time.Instant
import java.util.Base64

/**
 * **`ModerationProofGate`** — the one gate every moderation operation passes (ADR 0099 § 2, en.moderation.moderation-key).
 * Two checks, both needed: the `MODERATOR` role (an accepted, unremoved appointment — who acted) and the **key proof**
 * (a signature with the current epoch's moderation key over `moderation-proof` — that the caller holds the key), so a
 * stolen session without the key is worth nothing.
 *
 * **Which operation needs what is derived from the contract, not listed.** An operation of `moderation.openapi.yaml`
 * that declares the proof header ([SIGNATURE_HEADER]) is key-proved; every other one must be named in [WITHOUT_PROOF]
 * with the reason it cannot carry a proof (a newcomer holds no key yet) and whether it needs the role. An operation in
 * neither is refused — fail closed — and `ModerationProofPostureSweepTest` holds every operation to one of the two, and
 * every resource implementing `ModerationApi` to `@Authenticated` or `@PermitAll` (the role is this gate's, not an
 * annotation's: `@RolesAllowed` would answer `not_server_owner`). Later CRs' moderation operations meet it by declaring
 * the headers.
 *
 * Keyed on the matched resource method (whose name is its operationId, as every resource implements the generated
 * interface) and post-match, so behind authentication: an anonymous caller gets the 401 it always got. The statement is
 * rebuilt here from what the server matched — the operation, the `caseId` in the path — and the challenge it issued, so
 * a proof made for one operation or case answers for no other. The challenge is consumed under a lock before the
 * signature is checked, so one challenge proves at most one call, whatever its outcome.
 */
@ApplicationScoped
class ModerationProofGate(private val identity: SecurityIdentity) {

    @ServerRequestFilter
    fun gate(resource: SimpleResourceInfo, context: ContainerRequestContext): Response? {
        if (!ModerationApi::class.java.isAssignableFrom(resource.resourceClass)) return null
        val operation = resource.methodName
        val exempt = WITHOUT_PROOF[operation]
        if (exempt != null && !exempt.needsRole) return null
        if (exempt == null && operation !in PROOF_OPERATIONS) return httpError(notModerator)
        if (identity.isAnonymous || !identity.hasRole(ModeratorEntity.MODERATOR)) return httpError(notModerator)
        if (exempt != null) return null
        val proved = verify(
            accountId = identity.principal.name,
            operation = operation,
            caseId = context.uriInfo.pathParameters.getFirst("caseId"),
            challenge = context.getHeaderString(CHALLENGE_HEADER),
            signature = context.getHeaderString(SIGNATURE_HEADER),
            now = Instant.now(),
        )
        return if (proved) null else httpError(proofInvalid)
    }

    /** Consume [challenge] for [accountId] and verify [signature] over the rebuilt statement under the current key. */
    fun verify(accountId: String, operation: String, caseId: String?, challenge: String?, signature: String?, now: Instant): Boolean {
        if (challenge.isNullOrEmpty() || signature.isNullOrEmpty()) return false
        val challengeBytes = decode(challenge) ?: return false
        val signatureBytes = decode(signature) ?: return false
        val signKey = QuarkusTransaction.requiringNew().call {
            val row = ModerationChallengeEntity.findById(challenge, LockModeType.PESSIMISTIC_WRITE) ?: return@call null
            ModerationChallengeEntity.deleteById(challenge) // single use, consumed under the lock whatever follows
            if (row.accountId != accountId || !row.expiresAt.isAfter(now)) return@call null
            ModerationEpochEntity.find("order by epoch desc").firstResult()?.signPublicKey
        } ?: return false
        val key = decode(signKey) ?: return false
        return ModerationProof.verify(key, ModerationProof.statement(challengeBytes, operation, caseId), signatureBytes)
    }

    /** Why an operation carries no proof, and whether it needs the role. */
    data class Exemption(val needsRole: Boolean, val reason: String)

    companion object {
        const val CHALLENGE_HEADER = "X-Veyq-Moderation-Challenge"
        const val SIGNATURE_HEADER = "X-Veyq-Moderation-Signature"

        private val notModerator = httpFailure("not_moderator", FailureStatus.FORBIDDEN)
        private val proofInvalid = httpFailure("moderation_proof_invalid", FailureStatus.FORBIDDEN)

        /** The moderation operations that carry no key proof, each with its reason (uc.moderation.serve-as-moderator). */
        val WITHOUT_PROOF: Map<String, Exemption> = mapOf(
            "getModerationKey" to Exemption(false, "the published bundle every reporter, mostly a guest, seals to"),
            "getMyModeratorAppointment" to Exemption(false, "any account asks about itself; a pending appointee holds no key"),
            "acceptModeratorAppointment" to Exemption(false, "a pending appointee has neither the role nor the key"),
            "sendModeratorAddressCode" to Exemption(true, "a moderator waiting for the key confirms their address"),
            "confirmModeratorAddress" to Exemption(true, "a moderator waiting for the key confirms their address"),
            "listMyModerationGrants" to Exemption(true, "how a moderator comes to hold the key"),
            "createModerationChallenge" to Exemption(true, "the proof's own input"),
        )

        /** The operations of `ModerationApi` that declare the proof header — derived from the generated interface. */
        val PROOF_OPERATIONS: Set<String> = ModerationApi::class.java.methods
            .filter { m -> m.parameterAnnotations.flatMap { it.toList() }.any { it is HeaderParam && it.value == SIGNATURE_HEADER } }
            .map { it.name }
            .toSet()

        private fun decode(b64: String): ByteArray? = runCatching { Base64.getDecoder().decode(b64) }.getOrNull()
    }
}
