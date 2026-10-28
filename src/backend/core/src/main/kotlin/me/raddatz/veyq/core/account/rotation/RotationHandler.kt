package me.raddatz.veyq.core.account.rotation

import me.raddatz.veyq.core.account.deviceregister.DeviceRegisterStore
import me.raddatz.veyq.core.account.email.EmailChangeStore
import me.raddatz.veyq.core.account.identitypins.IdentityPinsStore
import me.raddatz.veyq.core.account.identitypins.PinWrite
import me.raddatz.veyq.core.account.login.SessionService
import me.raddatz.veyq.core.account.deviceregister.PutResult as RegisterPut
import me.raddatz.veyq.core.account.identitypins.PutResult as PinsPut
import me.raddatz.veyq.core.account.pairing.PairingStore
import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.crypto.Ed25519Verifier
import me.raddatz.veyq.core.crypto.GrantSignature
import me.raddatz.veyq.core.crypto.InviteAcceptProof
import me.raddatz.veyq.core.crypto.KeyChainStatements
import me.raddatz.veyq.core.crypto.RotationStageDigest
import me.raddatz.veyq.core.crypto.SecretKeyProof
import me.raddatz.veyq.core.http.FailureStatus.CONFLICT
import me.raddatz.veyq.core.http.FailureStatus.FORBIDDEN
import me.raddatz.veyq.core.http.FailureStatus.NOT_FOUND
import me.raddatz.veyq.core.http.FailureStatus.UNPROCESSABLE
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.identity.AccountStore
import me.raddatz.veyq.core.identity.keychain.KeyChainStore
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.moderation.ModerationHandler
import me.raddatz.veyq.core.node.rekey.ScopeRekeying
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.tasks.TasksStore
import me.raddatz.veyq.core.tenant.TenantEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64

/** Outcome of a publish — the scopes it re-granted, or the one refusal the server itself can make. Every refusal wrote
 *  nothing. */
sealed interface RotationOutcome {
    /** Applied: every scope in [regrantedScopeIds] holds a grant of its current epoch to the published identity and a
     *  pending re-key row; each now waits for its next epoch (uc.account.rotate-keys §9). */
    data class Applied(val regrantedScopeIds: List<String>) : RotationOutcome

    /** The account has no `PASSWORD` floor or no `RECOVERY` wrap to rewrite. */
    data object Unavailable : RotationOutcome, HttpFailure by httpFailure("rotation_unavailable", CONFLICT)

    /** The request did not prove the Secret Key: the step's proof half, the rotation proof under the stored proof key, or
     *  the new proof key's possession signature failed (ADR 0060 Decision 2). */
    data object SecretKeyProofFailed : RotationOutcome, HttpFailure by httpFailure("secret_key_proof_failed", FORBIDDEN)

    /** The attestation did not verify under the stored auth key: the caller did not prove the password. */
    data object AttestationFailed : RotationOutcome, HttpFailure by httpFailure("attestation_failed", FORBIDDEN)

    /** The key-chain step's identity half did not verify under the stored identity over the step the server rebuilt from
     *  the stored head — a step signed by another identity, or over a stale predecessor (ADR 0111 decision 7). */
    data object KeyChainStepInvalid : RotationOutcome, HttpFailure by httpFailure("key_chain_step_invalid", UNPROCESSABLE)

    /** The stage is missing, expired or declares another identity, its digest is not the request's, or a membership that
     *  carries a grant is neither staged at its scope's current epoch nor declared unreadable (decision 3). */
    data object PlanChanged : RotationOutcome, HttpFailure by Failures.rotationPlanChanged

    /** The pin store or the device register moved since the client read it — the whole rotation rolled back. The client
     *  re-reads, re-seals and resubmits with the same new keys and signatures (the documents are not in the proof). */
    data object TrustStateStale : RotationOutcome, HttpFailure by httpFailure("stale_revision", CONFLICT)

    /** The caller's session was revoked by a reset, a password change or another rotation this request waited for. */
    data object SessionEnded : RotationOutcome, HttpFailure by Failures.authenticationFailed
}

/** Outcome of staging a chunk of self-grants. Every refusal wrote nothing of the chunk. */
sealed interface StageGrantsOutcome {
    data object Staged : StageGrantsOutcome

    /** No open stage of the caller has this id — unknown, expired, replaced or another account's. */
    data object StageNotFound : StageGrantsOutcome, HttpFailure by httpFailure("rotation_stage_not_found", NOT_FOUND)

    /** A grant names a scope the caller holds no grant on (or none at all). */
    data object PlanChanged : StageGrantsOutcome, HttpFailure by Failures.rotationPlanChanged

    /** A grant does not verify under the stage's identity, or names another identity. */
    data object SelfGrantInvalid : StageGrantsOutcome, HttpFailure by httpFailure("self_grant_invalid", UNPROCESSABLE)
}

/** One staged self-grant as a chunk carries it. */
data class StagedSelfGrantInput(
    val scopeId: String, val epoch: Int, val wrappedGrant: String, val grantSignature: String, val recipientIdentityDigest: String,
)

/** Everything a publish carries (`RotateAccountKeysRequest`), as the handler takes it. */
data class RotationRequest(
    val identityPublicKey: String,
    val wrappedIdentityPrivateKey: String,
    val passwordWrap: RewrittenWrap,
    val recoveryWrap: RewrittenWrap,
    val secretKeyProofSignature: String,
    val pins: PinWrite,
    val register: ResealedDocument,
    val stepIdentitySignature: String,
    val stepProofSignature: String,
    val stageId: String,
    val stagedGrantsDigest: String,
    val unreadableScopeIds: Set<String>,
    val authPublicKey: String,
    val attestationSignature: String,
    val secretKeyProofPublicKey: String,
    val secretKeyProofPossession: String,
)

/** One Master-Key-sealed account document as the rotation re-sealed it: the blob under `MK'`, guarded by the
 *  revision the client read. */
data class ResealedDocument(val blob: String, val expectedRevision: Long)

/** One scope a rotation must carry, with the caller's own grant on it ([RotationHandler.plan]). */
data class RotationPlanRow(
    val scopeId: String,
    val kind: String,
    val tenantId: String,
    val currentEpoch: Int,
    val blocked: RotationBlock?,
    val wrappedGrant: String,
    val grantedEpoch: Int,
    val granterUserId: String?,
    val grantSignature: String?,
    val granterIdentityPublicKey: String?,
)

/** One re-granted scope's state ([RotationHandler.scopeStates]). */
data class RotationStateRow(val scopeId: String, val state: RegrantState, val blocked: RotationBlock?)

/**
 * Securing the account (uc.account.rotate-keys; ADR 0060, ADR 0111 decisions 6-10; cr-secure-the-account): the stage,
 * the publish, the plan and the states.
 *
 * **Access first, security after.** The client stages, in chunks, one self-grant per scope the account holds a grant on —
 * the scope's current epoch key sealed to the identity it is about to publish, signed by it, verified here at upload.
 * Then ONE transaction publishes: it appends the key-chain step (signed by the stored identity AND the stored Secret-Key
 * proof key), rewrites the account under the new Master Key, Secret Key and auth key, and applies the stage — so from the
 * moment it commits every scope holds a grant of its current epoch to the published identity, and no lost answer, closed
 * tab or later failure can strand one (#556). The next epoch of each is the account's to mint afterwards (`rotateScope`'s
 * gate). Nothing private of the old identity is kept anywhere; the chain keeps its public keys.
 *
 * **The gate's server half is here** (ADR 0060 Decision 2). A session is not enough: a device-link holder has one without
 * the password, a phished password buys one, and a stolen signed-in device holds the identity too. So the step's second
 * signature and the rotation proof are by the stored Secret-Key proof key, which none of them holds; the attestation by
 * the stored auth key is the password's half. The server rebuilds every statement itself.
 */
@ApplicationScoped
class RotationHandler(
    private val store: RotationStore,
    private val stages: RotationStageStore,
    private val keyChain: KeyChainStore,
    private val tasks: TasksStore,
    private val pairings: PairingStore,
    private val sessions: SessionService,
    private val identityPins: IdentityPinsStore,
    private val deviceRegister: DeviceRegisterStore,
    private val emailChanges: EmailChangeStore,
    private val accounts: AccountStore,
    private val moderation: ModerationHandler,
    config: VeyqConfig,
) {
    private val stageTtl: Duration = config.account().rotation().stage().ttl()

    // --- the plan (decision 5) ---------------------------------------------------------------------------------

    /**
     * Every scope [userId] holds a grant on, in **every** tenant — a group proposal not yet answered included (it holds a
     * grant from the add on, ADR 0111 decision 4) — with its kind, tenant, current epoch, why its next epoch cannot be
     * committed now, and the caller's own grant row. Host-independent; only the caller's own rows, each one a
     * `listMyScopes` serves the caller tenant by tenant.
     */
    fun plan(userId: String): List<RotationPlanRow> = QuarkusTransaction.requiringNew().call {
        val memberships = MembershipEntity.find("userId = ?1 and wrappedGrant is not null", userId).list()
        if (memberships.isEmpty()) return@call emptyList()
        val scopes = ScopeEntity.find("id in ?1", memberships.map { it.scopeId }.toSet()).list().associateBy { it.id }
        val tenants = TenantEntity.find("id in ?1", scopes.values.map { it.tenantId }.toSet()).list().associateBy { it.id }
        memberships.mapNotNull { m ->
            val scope = scopes[m.scopeId] ?: return@mapNotNull null
            RotationPlanRow(
                scope.id, scope.kind, scope.tenantId, scope.currentEpoch, blockOf(scope, tenants[scope.tenantId]),
                m.wrappedGrant!!, m.grantedEpoch, m.granterUserId, m.grantSignature, m.granterIdentityPublicKey,
            )
        }.sortedBy { it.scopeId }
    }

    // --- the stage (decision 3) --------------------------------------------------------------------------------

    /** Open [userId]'s one stage, declaring [identityPublicKey]; any stage before it goes. Under the account lock, the
     *  serializer of everything a rotation writes. */
    fun openStage(userId: String, identityPublicKey: String): RotationStageEntity = QuarkusTransaction.requiringNew().call {
        accounts.lock(userId)
        stages.open(userId, identityPublicKey, Instant.now().plus(stageTtl))
    }

    /**
     * Stage one chunk into [userId]'s open stage [stageId]: every grant is for a scope the caller holds a grant on
     * (else `409 rotation_plan_changed`), names the stage's identity and verifies under it as the `grant` statement for
     * the scope's own kind, its epoch and the caller (else `422 self_grant_invalid`) — the one place the server checks a
     * grant signature: a consistency check of the stage against the identity the publish then registers under the Secret
     * Key's proof, not a trust judgment. All checked before anything is written: a refused chunk leaves nothing. The epoch
     * is not judged here; the publish does, against the scope's current one.
     */
    fun stageGrants(userId: String, stageId: String, grants: List<StagedSelfGrantInput>): StageGrantsOutcome =
        QuarkusTransaction.requiringNew().call {
            accounts.lock(userId) ?: return@call StageGrantsOutcome.StageNotFound
            val stage = stages.live(userId, stageId, Instant.now()) ?: return@call StageGrantsOutcome.StageNotFound
            val held = grantCarryingScopes(userId, grants.map { it.scopeId }.toSet())
            if (grants.any { it.scopeId !in held }) return@call StageGrantsOutcome.PlanChanged
            val identity = stage.identityPublicKey
            val valid = grants.all { g ->
                InviteAcceptProof.digestMatches(identity, g.recipientIdentityDigest) && GrantSignature.verify(
                    identity,
                    GrantSignature.statement(g.scopeId, held.getValue(g.scopeId), g.epoch.toLong(), userId, identity, g.wrappedGrant),
                    g.grantSignature,
                )
            }
            if (!valid) return@call StageGrantsOutcome.SelfGrantInvalid
            // A scope named twice in one chunk is upserted in order: the last one stands, as a later chunk's would.
            stages.upsert(stage.id, grants.associateBy { it.scopeId }.values.map {
                StagedSelfGrantRow(it.scopeId, it.epoch, it.wrappedGrant, it.grantSignature)
            })
            StageGrantsOutcome.Staged
        }

    // --- the publish -------------------------------------------------------------------------------------------

    /**
     * Publish. **One transaction, every write or none** — a partial apply is a lockout, not a degraded state.
     *
     * Under the account lock (`AccountStore.lock`, inbox #297), taken first, with the caller's session re-read under it:
     *  1. **The stage** — the caller's, live (server-observed), declaring exactly the identity this publishes, and its
     *     digest recomputed over the staged rows equal to `stagedGrantsDigest`; otherwise `409 rotation_plan_changed`.
     *     Before any proof: the digest is what the proofs below sign.
     *  2. **The key-chain step**, rebuilt from the stored head, the stored identity and proof key it retires and the two
     *     keys the request installs: its identity half under the stored identity (`422 key_chain_step_invalid` — a stale
     *     predecessor fails here too, since the step is rebuilt from the stored head), its proof half under the stored
     *     proof key (`403 secret_key_proof_failed`).
     *  3. **The rotation proof** under the stored proof key and **possession** under the new one, over the statement the
     *     server rebuilds (`403 secret_key_proof_failed`); then **the attestation** under the stored auth key
     *     (`403 attestation_failed`).
     * Then the writes: the account rewrite; the pin store and device register (a stale revision rolls everything back,
     * `409 stale_revision`); the live pairing channels superseded; the moderation hook; **the stage applied** in one
     * ascending pass under each scope's row lock ([applyStage], whose set check can still roll everything back, `409
     * rotation_plan_changed`) and deleted; the chain step appended; every open address change voided; the declines of
     * every job targeting the account cleared; and the other sessions cut **last** — the one step that changes what the
     * caller may still do. `presentedAccessToken` spares the family this request arrived on, resolved by
     * `SessionService.revokeOtherFamilies`, never by the request.
     */
    fun rotate(userId: String, presentedAccessToken: String?, r: RotationRequest): RotationOutcome = try {
        QuarkusTransaction.requiringNew().call<RotationOutcome> { applyAll(userId, presentedAccessToken, r) }
    } catch (refused: Refused) {
        refused.outcome
    }

    private fun applyAll(userId: String, presentedAccessToken: String?, r: RotationRequest): RotationOutcome {
        val user = accounts.lock(userId) ?: return RotationOutcome.Unavailable
        if (!sessions.isLiveSessionOf(userId, presentedAccessToken)) return RotationOutcome.SessionEnded
        val now = Instant.now()

        // 1. The stage, before any proof: the digest is what the proofs sign.
        val stage = stages.live(userId, r.stageId, now) ?: return RotationOutcome.PlanChanged
        if (!sameBytes(stage.identityPublicKey, r.identityPublicKey)) return RotationOutcome.PlanChanged
        val staged = stages.grants(stage.id)
        val digest = RotationStageDigest.of(staged.values.map { RotationStageDigest.Entry(it.scopeId, it.epoch.toLong(), it.wrappedGrant) })
        if (digest == null || !MessageDigest.isEqual(digest, decode(r.stagedGrantsDigest))) return RotationOutcome.PlanChanged

        // 2. The step, rebuilt from the stored chain: both halves under the STORED current keys.
        val retiredProofKey = user.secretKeyProofPublicKey ?: return RotationOutcome.SecretKeyProofFailed
        val chain = requireNotNull(keyChain.chainOf(userId)) { "the account row is locked, so its chain reads" }
        val newest = chain.last()
        val index = newest.index + 1
        val step = KeyChainStatements.step(
            userId, index.toLong(), decode(newest.head) ?: ByteArray(0), user.identityPublicKey, retiredProofKey,
            r.identityPublicKey, r.secretKeyProofPublicKey,
        )
        if (!KeyChainStatements.identityHalfVerifies(user.identityPublicKey, step, r.stepIdentitySignature)) {
            return RotationOutcome.KeyChainStepInvalid
        }
        if (!KeyChainStatements.proofHalfVerifies(retiredProofKey, step, r.stepProofSignature)) return RotationOutcome.SecretKeyProofFailed

        // 3. The rotation proof (the gate), possession of the new proof key, and the password's half.
        val statement = SecretKeyProof.rotationStatement(
            currentIdentityPublicKey = user.identityPublicKey,
            identityPublicKey = r.identityPublicKey,
            wrappedIdentityPrivateKey = r.wrappedIdentityPrivateKey,
            passwordWrap = SecretKeyProof.Wrap(r.passwordWrap.wrappedKey, r.passwordWrap.kdfParams),
            recoveryWrap = SecretKeyProof.Wrap(r.recoveryWrap.wrappedKey, r.recoveryWrap.kdfParams),
            keyChainStepStatement = step!!,
            currentAuthPublicKey = user.authPublicKey,
            authPublicKey = r.authPublicKey,
            secretKeyProofPublicKey = r.secretKeyProofPublicKey,
            stageId = stage.id,
            stagedGrantsDigest = digest,
        )
        if (!SecretKeyProof.verify(retiredProofKey, statement, r.secretKeyProofSignature)) return RotationOutcome.SecretKeyProofFailed
        if (!SecretKeyProof.verify(r.secretKeyProofPublicKey, statement, r.secretKeyProofPossession)) {
            return RotationOutcome.SecretKeyProofFailed
        }
        if (!attests(user.authPublicKey, r.authPublicKey, r.attestationSignature)) return RotationOutcome.AttestationFailed

        // The writes. Every refusal from here on throws, so the transaction rolls back whatever was written before it.
        if (!store.applyRotation(
                userId, r.identityPublicKey, r.wrappedIdentityPrivateKey, r.passwordWrap, r.recoveryWrap, r.authPublicKey,
                r.secretKeyProofPublicKey,
            )
        ) {
            return RotationOutcome.Unavailable // checked before its first write: nothing was written
        }
        // Both documents or no rotation: each store's write joins this transaction. (`setRollbackOnly` + returning an
        // outcome does not work here: `call` then tries to commit and fails with a RollbackException, a 500.)
        val pinsStale = identityPins.write(userId, r.pins) !is PinsPut.Written
        if (pinsStale || deviceRegister.put(userId, r.register.blob, r.register.expectedRevision) is RegisterPut.Stale) {
            throw Refused(RotationOutcome.TrustStateStale)
        }
        // A live channel's envelope carries the OLD Master Key and a live delegated session.
        pairings.supersede(pairings.liveChannels(userId).map { it.id })
        // A moderator's grants of the instance moderation key were sealed to the identity this replaces (ADR 0099 § 4).
        moderation.onIdentityRotated(userId)

        val regranted = applyStage(userId, staged, r.unreadableScopeIds, r.identityPublicKey)
        stages.deleteFor(userId)
        keyChain.append(
            userId, chain.first(), index, r.identityPublicKey, r.secretKeyProofPublicKey, r.stepIdentitySignature,
            r.stepProofSignature, KeyChainStatements.head(step), now,
        )
        // Its proof was taken under the proof key and auth key just replaced (decision 7).
        emailChanges.voidAll(userId)
        // A worker that could not anchor the old key tries the new one, which the chain now reaches (decision 10).
        tasks.clearDeclinesTargeting(userId)

        presentedAccessToken?.let { sessions.revokeOtherFamilies(userId, it) }
        return RotationOutcome.Applied(regranted)
    }

    /**
     * Apply the stage: the account's grant-carrying memberships, in **every** tenant, each re-granted at its scope's
     * current epoch to the published identity — or the whole publish refused (`409 rotation_plan_changed`, rolled back).
     *
     * **The set check, under the locks.** The scopes the account holds a grant on are read, then each one's row is locked
     * in ascending id order (account → scope, the order every rotation writer follows; ascending, so two passes cannot
     * deadlock), and the memberships are read again under them. Every grant writer that could give this account a grant
     * holds the lock of the scope it writes or of that scope's tenant root, which the account is a member of: so it either
     * committed before this read — and its membership must be staged — or waits and then meets the published key
     * (`409 recipient_key_not_current`). A membership that appeared between the first read and the locks is refused too.
     * Every grant-carrying membership must be staged at its scope's **current** epoch or declared unreadable; a staged
     * grant for a scope the account no longer holds is not written (writing it would make a membership). An unreadable
     * scope keeps its grant and gets no pending row. Returns the re-granted scope ids, ascending.
     */
    private fun applyStage(
        userId: String, staged: Map<String, StagedSelfGrantRow>, unreadable: Set<String>, identityPublicKey: String,
    ): List<String> {
        val held = MembershipEntity.getEntityManager().createQuery(
            "select m.scopeId from MembershipEntity m where m.userId = :user and m.wrappedGrant is not null", String::class.java,
        ).setParameter("user", userId).resultList.toSortedSet()
        val epochs = held.associateWith { ScopeEntity.lockRow(it)?.currentEpoch }
        val heldNow = MembershipEntity.getEntityManager().createQuery(
            "select m.scopeId from MembershipEntity m where m.userId = :user and m.wrappedGrant is not null", String::class.java,
        ).setParameter("user", userId).resultList.toSortedSet()
        if (!held.containsAll(heldNow)) throw Refused(RotationOutcome.PlanChanged)

        val toWrite = heldNow.filter { it !in unreadable }.associateWith { scopeId ->
            val grant = staged[scopeId] ?: throw Refused(RotationOutcome.PlanChanged)
            if (grant.epoch != epochs[scopeId]) throw Refused(RotationOutcome.PlanChanged)
            grant
        }
        toWrite.forEach { (scopeId, grant) -> store.applySelfGrant(userId, scopeId, grant, identityPublicKey) }
        store.replaceRegrants(userId, toWrite.mapValues { it.value.epoch })
        return toWrite.keys.toList()
    }

    // --- the states (decision 8) -------------------------------------------------------------------------------

    /**
     * Where each scope the caller's latest publish re-granted stands, from its pending re-key rows — only scopes the
     * caller still holds a membership on. Host-independent; empty for an account that never rotated.
     */
    fun scopeStates(userId: String): List<RotationStateRow> = QuarkusTransaction.requiringNew().call {
        val rows = RotationRegrantEntity.find("userId", userId).list()
        if (rows.isEmpty()) return@call emptyList()
        val member = MembershipEntity.find("userId = ?1 and scopeId in ?2", userId, rows.map { it.scopeId }.toSet()).list()
            .map { it.scopeId }.toSet()
        val scopes = ScopeEntity.find("id in ?1", member).list().associateBy { it.id }
        val tenants = TenantEntity.find("id in ?1", scopes.values.map { it.tenantId }.toSet()).list().associateBy { it.id }
        rows.mapNotNull { row ->
            val scope = scopes[row.scopeId] ?: return@mapNotNull null
            val blocked = blockOf(scope, tenants[scope.tenantId])
            val facts = RegrantFacts(row.epochAtRotation, scope.currentEpoch, blocked, ScopeRekeying.lagsBelow(scope.id, scope.currentEpoch))
            RotationStateRow(scope.id, RotationScopeStates.state(facts), blocked)
        }.sortedBy { it.scopeId }
    }

    // --- helpers -----------------------------------------------------------------------------------------------

    /** The scopes among [scopeIds] [userId] holds a grant on, with their kind (what a grant statement signs). */
    private fun grantCarryingScopes(userId: String, scopeIds: Set<String>): Map<String, String> {
        if (scopeIds.isEmpty()) return emptyMap()
        val held = MembershipEntity.find("userId = ?1 and scopeId in ?2 and wrappedGrant is not null", userId, scopeIds).list()
            .map { it.scopeId }.toSet()
        if (held.isEmpty()) return emptyMap()
        return ScopeEntity.find("id in ?1", held).list().associate { it.id to it.kind }
    }

    private fun blockOf(scope: ScopeEntity, tenant: TenantEntity?): RotationBlock? = when {
        TenantEntity.isOffline(tenant?.status) -> RotationBlock.TENANT_SUSPENDED
        scope.deletedAt != null -> RotationBlock.SCOPE_DELETING
        else -> null
    }

    /** The password's half, as `changePassword` verifies its own: the stored auth key's signature over the new key's raw
     *  bytes. Keeping the password derives the same key, and the attestation still verifies. */
    private fun attests(currentAuthPublicKey: String, authPublicKey: String, signature: String): Boolean {
        val key = decode(currentAuthPublicKey) ?: return false
        val next = decode(authPublicKey) ?: return false
        val sig = decode(signature) ?: return false
        return Ed25519Verifier.verify(key, next, sig)
    }

    private fun sameBytes(a: String, b: String): Boolean {
        val x = decode(a) ?: return false
        val y = decode(b) ?: return false
        return MessageDigest.isEqual(x, y)
    }

    private fun decode(b64: String): ByteArray? = try {
        Base64.getDecoder().decode(b64)
    } catch (e: IllegalArgumentException) {
        null
    }

    /** Thrown inside the publish's transaction to roll every write in it back; [rotate] answers its outcome. Never
     *  escapes this class. */
    private class Refused(val outcome: RotationOutcome) : RuntimeException(null, null, false, false)
}
