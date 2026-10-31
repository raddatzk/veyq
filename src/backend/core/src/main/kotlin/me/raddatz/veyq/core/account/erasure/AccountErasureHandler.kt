package me.raddatz.veyq.core.account.erasure

import me.raddatz.veyq.core.account.email.EmailChangeEntity
import me.raddatz.veyq.core.account.email.EmailConfirmationEntity
import me.raddatz.veyq.core.account.login.LoginChallengeEntity
import me.raddatz.veyq.core.account.login.SessionService
import me.raddatz.veyq.core.account.pairing.PairingStore
import me.raddatz.veyq.core.account.recovery.AccountRecoveryEntity
import me.raddatz.veyq.core.admin.tenant.TenantDeletionMail
import me.raddatz.veyq.core.admin.tenant.TenantHandler
import me.raddatz.veyq.core.admin.tenant.TenantInviteEntity
import me.raddatz.veyq.core.admin.tenant.TenantStore
import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.crypto.AuthAttestation
import me.raddatz.veyq.core.http.FailureStatus
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.identity.AccountErasureEntity
import me.raddatz.veyq.core.identity.AccountStore
import me.raddatz.veyq.core.identity.ModeratorEntity
import me.raddatz.veyq.core.identity.ServerRoles
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.moderation.ModerationHandler
import me.raddatz.veyq.core.moderation.ErasureDoor
import me.raddatz.veyq.core.moderation.ModerationOutcome
import me.raddatz.veyq.core.moderation.ServerOwnerDelivery
import me.raddatz.veyq.core.moderation.ServerOwnerDeliveryIn
import me.raddatz.veyq.core.spaces.scope.ScopesStore
import me.raddatz.veyq.core.tenant.TenantEntity
import me.raddatz.veyq.core.tenant.TenantIngressProjector
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.transaction.Transactional
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import java.time.Duration
import java.time.Instant

/** A tenant whose last OWNER the account is, and who else belongs to it. */
data class LastOwnerTenant(val tenantId: String, val name: String, val otherMemberIds: List<String>)

/** One choice for a last-owner tenant: a member to make its owner, or null to delete the tenant along. */
data class OwnerChoice(val tenantId: String, val newOwnerId: String?)

/** What erasing an account decides ([AccountErasureHandler.preview]): its last-owner tenants, and where it is the last
 *  holder of the moderation key, whom it delivers the key to. */
data class ErasurePreview(val tenants: List<LastOwnerTenant>, val serverOwnerDelivery: ServerOwnerDelivery?)

/** A pending erasure as the Server Owner sees it. */
data class PendingErasureView(val address: String, val requestedAt: Instant, val requestedBy: String, val dueAt: Instant)

sealed interface ErasureOutcome {
    /** Requested: the purge erases the account at [dueAt]; [deletedTenants] went offline with it, due at [tenantsDueAt]
     *  (a tenant deletion's own window, decision 10), to be mailed. */
    data class Pending(
        val accountId: String, val address: String, val requestedAt: Instant, val dueAt: Instant,
        val deletedTenants: List<String>, val tenantsDueAt: Instant,
    ) : ErasureOutcome
    data object NotFound : ErasureOutcome, HttpFailure by httpFailure("account_not_found", FailureStatus.NOT_FOUND)
    data object ServerOwnerAccount : ErasureOutcome, HttpFailure by httpFailure("server_owner_account", FailureStatus.CONFLICT)
    data object AttestationFailed : ErasureOutcome, HttpFailure by httpFailure("attestation_failed", FailureStatus.FORBIDDEN)
    data object ChoiceRequired : ErasureOutcome, HttpFailure by httpFailure("last_owner_choice_required", FailureStatus.CONFLICT)
    data object AlreadyPending : ErasureOutcome, HttpFailure by httpFailure("account_erasure_pending", FailureStatus.CONFLICT)
    data class Refused(val failure: HttpFailure) : ErasureOutcome, HttpFailure by failure
}

sealed interface StopErasureOutcome {
    data object Stopped : StopErasureOutcome
    data object NotFound : StopErasureOutcome, HttpFailure by httpFailure("account_erasure_not_found", FailureStatus.NOT_FOUND)
}

/**
 * The account erasure's two doors and its stop (uc.account.erase-account, ADR 0101 decisions 1-3 and 9). Both doors
 * start the same erasure, in one transaction under the account row's lock — the lock a sign-in takes, so no session
 * can be issued past the request:
 *  1. the moderator roster ([ModerationHandler.leaveForErasure]) — first, as it is the one step that may refuse: at the
 *     member's door the last holder of the moderation key delivers it to the Server Owner in the request, and is
 *     refused without that delivery; at the Server Owner's the key goes with them, once the Server Owner confirmed it
 *     (ADR 0101 decision 9);
 *  2. the last-owner tenants: at the member's door each one with other members carries the member's choice (a member
 *     made OWNER, or the tenant deleted along), and one with no other member is deleted along; the Server Owner's door
 *     chooses nothing and leaves such a tenant with no owner;
 *  3. the member removal in every other tenant ([TenantHandler.removeForErasure]): own scopes into the scope trash with
 *     the membership kept, shared ones cut and their links off, a sole-owned group handed on;
 *  4. every session ends, and the pending invites, pairings, recoveries and address changes go;
 *  5. the pending erasure, due the erasure's own window after the request ([AccountErasureWindow], decision 3), which
 *     [AccountPurge] carries out with the own scopes. A stored due date is never recomputed: a request keeps the window
 *     it was made under. A tenant deleted along waits out a tenant deletion's window instead (decision 10, the scope
 *     trash's retention), as one the Server Owner deletes does.
 * After the commit: the routers of a tenant deleted along are withdrawn, its members are mailed, and so is the account.
 * A refusal at any step rolls everything back: nothing is ever half requested.
 */
@ApplicationScoped
class AccountErasureHandler(
    private val accounts: AccountStore,
    private val sessions: SessionService,
    private val tenants: TenantStore,
    private val tenantHandler: TenantHandler,
    private val scopes: ScopesStore,
    private val moderation: ModerationHandler,
    private val pairings: PairingStore,
    private val mail: AccountErasureMail,
    private val tenantMail: TenantDeletionMail,
    private val ingress: TenantIngressProjector,
    private val window: AccountErasureWindow,
    config: VeyqConfig,
) {
    /** A tenant deletion's window (decision 10), the one `TenantHandler.delete` gives a tenant the Server Owner deletes. */
    private val tenantDeletionRetention: Duration = config.scope().delete().retention()

    /** What the erasure decides about (`getOwnAccountErasurePreview`); null for the Server Owner's own account. */
    fun preview(accountId: String): ErasurePreview? = QuarkusTransaction.requiringNew().call {
        val user = VeyqUser.findById(accountId) ?: return@call ErasurePreview(emptyList(), null)
        if (user.serverRole == ServerRoles.SERVER_OWNER) null
        else ErasurePreview(lastOwnerTenants(accountId), moderation.serverOwnerDeliveryFor(accountId))
    }

    /**
     * The member's door: the proof is a login challenge for the account's own address, signed with its auth key over
     * the account and the challenge. The challenge is spent in a transaction of its own before anything else, so it is
     * gone whether or not the proof verifies, and a refusal later rolls back nothing but the request ([eraseOwnIn]).
     */
    fun eraseOwn(
        accountId: String, nonce: String, signature: String, choices: List<OwnerChoice>, delivery: ServerOwnerDeliveryIn? = null,
    ): ErasureOutcome {
        val challengeEmail = QuarkusTransaction.requiringNew().call { spendChallenge(nonce) }
        return finish(refusable { eraseOwnIn(accountId, challengeEmail, nonce, signature, choices, delivery) })
    }

    /**
     * [eraseOwn]'s request, **in the caller's transaction** — the race test holds it uncommitted
     * (`ErasureKeyDeliveryRacesSliceTest`). [challengeEmail] is the address the spent challenge was minted for.
     * A refusal after a write throws ([refuse]); the caller's transaction then rolls back whole.
     *
     * [delivery]: the moderation key for the Server Owner, which the last holder must carry (decision 9). With one from an
     * accepted moderator (anyone else's is ignored, and locks nothing), the Server Owner's account row is locked after
     * the person's and before anything else — before the moderation epoch's
     * row above all — so an identity rotation of the Server Owner, which holds that row from its first read, either
     * committed before the identity is read ([ModerationHandler.leaveForErasure]: `identity_changed`) or waits for this
     * transaction and then finds the grants it delivered, which it deletes as exposed (ADR 0099 § 4).
     *
     * **Lock order** (checked against every writer of these rows, 2026-10-31): the person's account row → the Server
     * Owner's account row → the current moderation epoch's row → the Server Owner's moderator row → the person's
     * moderator row → the grants → the scope rows, ascending. Every other writer of an account row locks that one row
     * alone (`AccountStore.lock` / `findByEmail(lock = true)`: a sign-in, a password change, a recovery, a pairing, the
     * pin store, key material, an address change, a tenant accept, a sign-in block, the purge, the Server Owner's door
     * here, which locks only its target), and the one that goes on to the epoch row — an identity rotation, through
     * [ModerationHandler.onIdentityRotated] — takes it after its account row, as this does. No writer of the epoch row
     * ([ModerationHandler]'s appoint, accept, removal, step-down, mint, delivery; `RotationDueNotice`) locks an account
     * row after it, and no writer locks two account rows but this, in this order: so no cycle.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    fun eraseOwnIn(
        accountId: String, challengeEmail: String?, nonce: String, signature: String, choices: List<OwnerChoice>,
        delivery: ServerOwnerDeliveryIn?,
    ): ErasureOutcome {
        val user = accounts.lock(accountId) ?: return ErasureOutcome.AttestationFailed
        if (user.serverRole == ServerRoles.SERVER_OWNER) return ErasureOutcome.ServerOwnerAccount
        val proven = challengeEmail != null && challengeEmail == user.email &&
            AuthAttestation.verify(user.authPublicKey, AuthAttestation.eraseAccountStatement(accountId, nonce), signature)
        if (!proven) return ErasureOutcome.AttestationFailed
        // A plain read: an appointment accepted after it holds no grant yet, so it is no last holder (ModerationHandler).
        if (delivery != null && ModeratorEntity.isModerator(accountId)) accounts.serverOwnerId()?.let { accounts.lock(it) }
        return start(
            user, AccountErasureEntity.SELF, actorId = accountId, choices = choices.associateBy { it.tenantId }, byOwner = false,
            door = ErasureDoor.Own(delivery),
        )
    }

    /**
     * The Server Owner's door, by address: never refuses a last owner, nor the last holder of the moderation key (an
     * erasure cannot wait on a third person) — the key goes with them once [keyLossConfirmed] says the Server Owner was
     * warned (`moderation_last_holder` until then, decision 9).
     */
    fun eraseByServerOwner(serverOwnerId: String, address: String, keyLossConfirmed: Boolean = false): ErasureOutcome {
        val outcome = refusable {
            val user = accounts.findByEmail(address, lock = true) ?: return@refusable ErasureOutcome.NotFound
            if (user.serverRole == ServerRoles.SERVER_OWNER) return@refusable ErasureOutcome.ServerOwnerAccount
            start(
                user, AccountErasureEntity.SERVER_OWNER, actorId = serverOwnerId, choices = emptyMap(), byOwner = true,
                door = ErasureDoor.ServerOwners(keyLossConfirmed),
            )
        }
        return finish(outcome)
    }

    /** Stop a pending erasure by the account's address (ADR 0101 decision 3): the account signs in again. */
    fun stop(address: String): StopErasureOutcome = QuarkusTransaction.requiringNew().call {
        val user = accounts.findByEmail(address, lock = true) ?: return@call StopErasureOutcome.NotFound
        if (AccountErasureEntity.deleteById(user.id)) StopErasureOutcome.Stopped else StopErasureOutcome.NotFound
    }

    /** Every pending erasure, earliest due first. */
    fun pending(): List<PendingErasureView> = QuarkusTransaction.requiringNew().call {
        AccountErasureEntity.list("order by dueAt, accountId").mapNotNull { e ->
            VeyqUser.findById(e.accountId)?.let { PendingErasureView(it.email, e.requestedAt, e.requestedBy, e.dueAt) }
        }
    }

    /** After the commit: the routers of the tenants deleted along go, their members and the account are mailed. */
    private fun finish(outcome: ErasureOutcome): ErasureOutcome {
        if (outcome !is ErasureOutcome.Pending) return outcome
        if (outcome.deletedTenants.isNotEmpty()) ingress.regenerate()
        outcome.deletedTenants.forEach { tenantMail.tenantDeleted(it, outcome.tenantsDueAt) }
        mail.requested(outcome.accountId)
        return outcome
    }

    private fun start(
        user: VeyqUser, requestedBy: String, actorId: String, choices: Map<String, OwnerChoice>, byOwner: Boolean, door: ErasureDoor,
    ): ErasureOutcome {
        val accountId = user.id
        if (AccountErasureEntity.isPending(accountId)) return ErasureOutcome.AlreadyPending
        val now = Instant.now()
        val dueAt = now.plus(window.current())
        val tenantsDueAt = now.plus(tenantDeletionRetention)

        // 1. The roster — the one step that may refuse after the gates, so it runs before anything is written.
        val left = moderation.leaveForErasure(accountId, actorId, now, door)
        if (left is ModerationOutcome.Refused) return refuse(ErasureOutcome.Refused(left.failure))

        // Every scope the account is in, locked in ascending id order before any owner count is read — the order every
        // multi-scope lock takes (the removal's, a rotation's).
        MembershipEntity.list("userId", accountId).map { it.scopeId }.distinct().sorted().forEach { scopes.lockScope(it) }

        // 2. The last-owner tenants.
        val deleted = mutableListOf<String>()
        if (!byOwner) {
            for (t in lastOwnerTenants(accountId)) {
                if (t.otherMemberIds.isEmpty()) {
                    deleted += t.tenantId
                    continue
                }
                val choice = choices[t.tenantId] ?: return refuse(ErasureOutcome.ChoiceRequired)
                val newOwner = choice.newOwnerId
                when {
                    newOwner == null -> deleted += t.tenantId
                    newOwner in t.otherMemberIds -> tenants.setTenantRole(t.tenantId, newOwner, MembershipEntity.OWNER)
                    else -> return refuse(ErasureOutcome.ChoiceRequired)
                }
            }
            // The tenant deletion's own transition, offline at once; its routers go after the commit ([finish]).
            deleted.forEach { tenants.markForDeletion(it, tenantsDueAt) }
        }

        // 3. The member removal in every other tenant.
        val tenantIds = MembershipEntity.list("userId", accountId).map { it.tenantId }.distinct().sorted()
        for (tenantId in tenantIds - deleted.toSet()) {
            if (!tenantHandler.removeForErasure(tenantId, accountId, allowLastOwner = byOwner)) return refuse(ErasureOutcome.ChoiceRequired)
        }

        // 4. Sessions and everything pending.
        sessions.revokeAllForUser(accountId)
        // Invites to the account, and the member invites it signed (ADR 0111 decision 1): an invite its signer no
        // longer stands behind ends, and its key and grant go in the same statement.
        TenantInviteEntity.update(
            "status = ?1, ${TenantInviteEntity.INVITE_KEY_DROPPED} where (ownerEmail = ?2 or signerUserId = ?3) and status = ?4",
            TenantInviteEntity.SUPERSEDED, user.email, accountId, TenantInviteEntity.ACTIVE,
        )
        pairings.supersede(pairings.liveChannels(accountId).map { it.id })
        AccountRecoveryEntity.update(
            "status = ?1 where userId = ?2 and status = ?3",
            AccountRecoveryEntity.SUPERSEDED, accountId, AccountRecoveryEntity.PENDING,
        )
        EmailConfirmationEntity.update(
            "status = ?1 where userId = ?2 and status = ?3",
            EmailConfirmationEntity.SUPERSEDED, accountId, EmailConfirmationEntity.PENDING,
        )
        EmailChangeEntity.delete("userId", accountId)

        // 5. The pending erasure.
        AccountErasureEntity().apply {
            this.accountId = accountId
            requestedAt = now
            this.requestedBy = requestedBy
            this.dueAt = dueAt
        }.persist()
        return ErasureOutcome.Pending(accountId, user.email, now, dueAt, deleted, tenantsDueAt)
    }

    /** The tenants whose only OWNER [accountId] is — ACTIVE or SUSPENDED ones; a tenant deleted already decides nothing. */
    private fun lastOwnerTenants(accountId: String): List<LastOwnerTenant> =
        MembershipEntity.list("userId = ?1 and role = ?2 and pendingConsent = false", accountId, MembershipEntity.OWNER)
            .filter { it.scopeId == it.tenantId }
            .mapNotNull { root ->
                val tenant = TenantEntity.findById(root.tenantId)
                    ?.takeIf { it.status == TenantEntity.ACTIVE || it.status == TenantEntity.SUSPENDED } ?: return@mapNotNull null
                if (tenants.ownerCount(tenant.id) > 1) return@mapNotNull null
                LastOwnerTenant(tenant.id, tenant.name, tenants.tenantMemberIds(tenant.id).filter { it != accountId }.sorted())
            }
            .sortedBy { it.name }

    /** Spend the login challenge [nonce] under its row lock; its address while it was live, or null. */
    private fun spendChallenge(nonce: String): String? {
        val challenge = LoginChallengeEntity.find("nonce", nonce).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult()
            ?: return null
        LoginChallengeEntity.deleteById(challenge.nonce)
        return challenge.email.takeIf { challenge.expiresAt.isAfter(Instant.now()) }
    }

    /**
     * A refusal after something was written: thrown, so the request's transaction rolls back whole. Trap: not
     * `QuarkusTransaction.setRollbackOnly()` and a returned value, the shape `TenantAcceptHandler.unspend` uses inside
     * a `@Transactional` method — inside `QuarkusTransaction.requiringNew().call` the commit of a rollback-only
     * transaction throws (`ARJUNA016053`), which turned every refusal into a 500 until the slice tests caught it.
     */
    private class Refusal(val outcome: ErasureOutcome) : RuntimeException(null, null, false, false)

    private fun refuse(refusal: ErasureOutcome): Nothing = throw Refusal(refusal)

    /** The request's one transaction; a [Refusal] thrown in it rolls everything back and becomes its answer. */
    private fun refusable(body: () -> ErasureOutcome): ErasureOutcome = try {
        QuarkusTransaction.requiringNew().call(body)
    } catch (e: Exception) {
        generateSequence<Throwable>(e) { it.cause }.filterIsInstance<Refusal>().firstOrNull()?.outcome ?: throw e
    }
}
