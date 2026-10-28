package me.raddatz.veyq.core.account.erasure

import me.raddatz.veyq.core.account.deviceregister.DeviceRegisterEntity
import me.raddatz.veyq.core.account.email.EmailChangeEntity
import me.raddatz.veyq.core.account.email.EmailConfirmationEntity
import me.raddatz.veyq.core.account.identitypins.IdentityPinEntryEntity
import me.raddatz.veyq.core.account.identitypins.IdentityPinsEntity
import me.raddatz.veyq.core.account.login.AccessTokenEntity
import me.raddatz.veyq.core.account.login.RefreshTokenEntity
import me.raddatz.veyq.core.account.login.SessionFamilyEntity
import me.raddatz.veyq.core.account.pairing.DevicePairingEntity
import me.raddatz.veyq.core.account.recovery.AccountRecoveryEntity
import me.raddatz.veyq.core.account.login.LoginChallengeEntity
import me.raddatz.veyq.core.admin.tenant.TenantInviteEntity
import me.raddatz.veyq.core.backup.ErasureKind
import me.raddatz.veyq.core.backup.ErasureLedger
import me.raddatz.veyq.core.admin.tenant.TenantStore
import me.raddatz.veyq.core.admin.tenant.TenantTermsAcceptanceEntity
import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.blobstore.StoreObjectRef
import me.raddatz.veyq.core.blobstore.StoreReclaimStore
import me.raddatz.veyq.core.favorites.FavoritesEntity
import me.raddatz.veyq.core.identity.AccountErasureEntity
import me.raddatz.veyq.core.identity.AccountEraser
import me.raddatz.veyq.core.identity.ColumnFate
import me.raddatz.veyq.core.identity.ERASED
import me.raddatz.veyq.core.identity.MasterKeyWrapEntity
import me.raddatz.veyq.core.identity.ModeratorEntity
import me.raddatz.veyq.core.identity.TenantProfileEntity
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.identity.kept
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.moderation.ModerationChallengeEntity
import me.raddatz.veyq.core.moderation.ModerationGrantEntity
import me.raddatz.veyq.core.moderation.block.BlockKind
import me.raddatz.veyq.core.moderation.cases.SignInBlocks
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.spaces.scope.ScopePurge
import me.raddatz.veyq.core.tasks.JobDeclineEntity
import me.raddatz.veyq.core.tasks.JobEntity
import me.raddatz.veyq.core.tenant.TenantEntity
import io.quarkus.arc.All
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import jakarta.transaction.Transactional
import java.time.Instant
import org.jboss.logging.Logger

/**
 * The account erasure's purge (uc.account.erase-account step 4, ADR 0101 decisions 3-6): once a pending erasure is
 * due — the request plus `veyq.scope.delete.retention`, the scope trash's 30 days — one transaction erases the account
 * with its own scopes and every row keyed on it, and writes the erasure into the erasure ledger. Pending or complete,
 * never partial: a failure rolls the whole account back, is logged, and the next tick tries again.
 *
 * **Its own scopes go first, through the scope erasure** ([ScopePurge.purge]): its personal scope in every tenant and
 * every scope the member removal put into the trash with the membership kept (a solo group). Then the applications'
 * [AccountEraser]s, then core's rows below, then the account row. What stays (decisions 5 and 7) is classified in
 * [CORE_COLUMNS] and each eraser's `columns`: content attribution belongs to the scope and shows as a former member's;
 * holds and the moderation records hang on cases and stay to their own ends; the terms acceptance rows stay while a
 * case names the account ([TermsEvidence]), and [sweepKeptTerms] deletes them once none does.
 *
 * **This list plus every [AccountEraser] is the enumeration, and `scripts/validate_account_erasure_arity.py` is what
 * keeps it complete**: every table reachable by foreign key from `veyq_user` is deleted here or cascades, and every
 * column naming an account without a key is classified. Operator-blind: nothing is decrypted; store objects go only
 * through the reclaim list ([StoreReclaimStore]), the one deleter of bytes (ADR 0062).
 */
@ApplicationScoped
class AccountPurge(
    private val scopePurge: ScopePurge,
    private val tenants: TenantStore,
    private val reclaim: StoreReclaimStore,
    private val mail: AccountErasureMail,
    private val steps: SignInBlocks,
    private val ledger: ErasureLedger,
    @All erasers: MutableList<AccountEraser>,
) {
    private val erasers: List<AccountEraser> = erasers.toList()
    private val log = Logger.getLogger(AccountPurge::class.java)

    /**
     * The hourly tick: every due erasure in a transaction of its own (one account that cannot be erased must not hold
     * back the others — the scope purge's reason), then the erasure mails that did not go out, then the kept terms rows
     * no case keeps any more.
     */
    @Scheduled(every = "{veyq.account.erasure.purge.every}")
    fun run() {
        val now = Instant.now()
        val due = QuarkusTransaction.requiringNew().call {
            AccountErasureEntity.list("dueAt <= ?1", now).map { it.accountId }
        }
        due.forEach { accountId ->
            try {
                QuarkusTransaction.requiringNew().run { eraseAccount(accountId, now) }
            } catch (e: Exception) {
                log.error("the erasure of an account failed; it stays pending and the purge continues", e)
            }
        }
        runCatching { mail.retryDue(now) }.onFailure { log.warn("the erasure mails could not be retried", it) }
        runCatching { QuarkusTransaction.requiringNew().run { sweepKeptTerms(now) } }
            .onFailure { log.warn("the kept terms acceptance rows could not be swept", it) }
    }

    /** Erase one pending account now, due or not (tests, a forced run) — transactional. */
    @Transactional
    fun purge(accountId: String) = eraseAccount(accountId, null)

    /**
     * The erasure ledger's re-application (ADR 0056 decision 8): the account [accountId] was erased at [requestedAt]'s
     * request's end, and a restore brought its row back — live, if the capture predates the request, so no pending row
     * names it. Its pending row is made again, due now, and the one purge erases it as it did then: its own scopes (each
     * also a line of its own), every row keyed on it, its address wherever it was copied. Nothing is mailed — the person
     * was told when it happened. False when no account row exists: nothing came back. In the caller's transaction.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    fun reapply(accountId: String, requestedAt: Instant): Boolean {
        val user = VeyqUser.findById(accountId, LockModeType.PESSIMISTIC_WRITE) ?: return false
        val pending = AccountErasureEntity.findById(accountId) ?: AccountErasureEntity().apply {
            this.accountId = user.id
            this.requestedAt = requestedAt
            requestedBy = AccountErasureEntity.SERVER_OWNER
            dueAt = Instant.now()
        }.also { it.persist() }
        pending.dueAt = Instant.now()
        eraseAccount(accountId, null)
        return true
    }

    private fun eraseAccount(accountId: String, cutoff: Instant?) {
        // The account row's lock first — the lock every sign-in, stop and request takes — and the work list's predicate
        // again under it: a stop that landed after the list was read wins (developer-styleguide §12).
        val user = VeyqUser.findById(accountId, LockModeType.PESSIMISTIC_WRITE) ?: return
        val pending = AccountErasureEntity.findById(accountId) ?: return
        if (cutoff != null && pending.dueAt.isAfter(cutoff)) return

        // Its own scopes, through the scope erasure: the personal ones, and every scope the removal trashed with the
        // membership kept. Ascending ids, the order every multi-scope lock takes.
        val trashed = MembershipEntity.list("userId", accountId).map { it.scopeId }
            .filter { ScopeEntity.findById(it)?.deletedAt != null }
        val personal = ScopeEntity.list("personalOwner", accountId).map { it.id }
        (trashed + personal).distinct().sorted().forEach { scopePurge.purge(it) }

        erasers.forEach { it.eraseAccount(accountId) }
        eraseRows(accountId, user.email, pending.requestedAt)
    }

    private fun eraseRows(accountId: String, address: String, requestedAt: Instant) {
        // The address, wherever it was copied: the invites sent to it (any state), a login challenge for it, and a
        // tenant's record of the address its owner invite went to (the tenant stays; the address does not).
        TenantInviteEntity.delete("ownerEmail", address)
        LoginChallengeEntity.delete("email", address)
        tenants.forgetOwnerAddress(address)
        val families = SessionFamilyEntity.list("userId", accountId).map { it.id }
        if (families.isNotEmpty()) {
            AccessTokenEntity.delete("familyId in ?1", families)
            RefreshTokenEntity.delete("familyId in ?1", families)
        }
        SessionFamilyEntity.delete("userId", accountId)
        MasterKeyWrapEntity.delete("userId", accountId)
        IdentityPinEntryEntity.delete("accountId", accountId)
        IdentityPinsEntity.delete("accountId", accountId)
        DeviceRegisterEntity.delete("accountId", accountId)
        DevicePairingEntity.delete("userId", accountId)
        AccountRecoveryEntity.delete("userId", accountId)
        EmailConfirmationEntity.delete("userId", accountId)
        EmailChangeEntity.delete("userId", accountId)
        TenantProfileEntity.delete("userId", accountId)
        MembershipEntity.delete("userId", accountId)
        FavoritesEntity.delete("userId", accountId)
        ModerationGrantEntity.delete("moderatorId", accountId)
        ModeratorEntity.delete("accountId", accountId)
        ModerationChallengeEntity.delete("accountId", accountId)
        JobDeclineEntity.delete("workerId", accountId)
        // A job it leased goes back to the queue for another key-holder; one it finished forgets who did.
        JobEntity.update(
            "status = ?1, leaseHolder = null, leaseExpiresAt = null where leaseHolder = ?2 and status = ?3",
            JobEntity.PENDING, accountId, JobEntity.LEASED,
        )
        JobEntity.update("leaseHolder = null where leaseHolder = ?1", accountId)
        // An upload it opened and never finished: its row is the only pointer to chunks already in the store.
        val sessions = UploadSessionEntity.list("openerUserId", accountId)
        reclaim.record(sessions.map { StoreObjectRef(it.storageKey, it.chunkCount) })
        UploadSessionEntity.delete("openerUserId", accountId)
        // The terms acceptance history (ADR 0101 decision 6): kept under the opaque id while a case names the account.
        if (!TermsEvidence.namesAtErasure(accountId)) TenantTermsAcceptanceEntity.delete("userId", accountId)
        // After the terms question, which asks whether one stands: a measure on the account ends with it.
        steps.targetErased(accountId, listOf(BlockKind.MEMBER_SIGN_IN, BlockKind.REPORTER_ACCOUNT, BlockKind.CONTENT_UPLOADER), Instant.now())
        AccountErasureEntity.deleteById(accountId)
        // The erasure ledger's line (ADR 0056 decision 8, ADR 0101 decision 8), in this transaction, before the row goes:
        // a restore of a capture older than this brings the account back, and the start erases it again ([reapply]).
        ledger.erased(ErasureKind.ACCOUNT, accountId)
        VeyqUser.deleteById(accountId)
        log.infof("an account was erased (requested %s)", requestedAt)
    }

    /**
     * The terms acceptance rows an erasure kept (ADR 0101 decision 6) — this account purge's or a tenant's
     * ([me.raddatz.veyq.core.admin.tenant.TenantPurge]) — go once no moderation case names their account any more: the
     * case's log retention date, no period of their own. A kept row is one whose account or tenant no longer exists; a
     * row of a living account in a living tenant is never touched, neither of them was erased.
     */
    fun sweepKeptTerms(now: Instant) {
        val kept = TenantTermsAcceptanceEntity.getEntityManager()
            .createQuery(
                "select a.id, a.userId from TenantTermsAcceptanceEntity a where " +
                    "not exists (select 1 from VeyqUser u where u.id = a.userId) or " +
                    "not exists (select 1 from TenantEntity t where t.id = a.tenantId)",
                Array<Any>::class.java,
            ).resultList
        kept.groupBy({ it[1] as String }, { it[0] as String })
            .filterKeys { !TermsEvidence.namesUntil(it, now) }
            .values.flatten().chunked(500)
            .forEach { TenantTermsAcceptanceEntity.delete("id in ?1", it) }
    }

    companion object {
        /**
         * Every core column that names an account without a foreign key, and what the erasure does with it (ADR 0101
         * decision 3). Read by `scripts/validate_account_erasure_arity.py`; an application classifies its own in its
         * [AccountEraser].
         */
        val CORE_COLUMNS: Map<String, ColumnFate> = mapOf(
            // Erased with the account.
            "veyq_scope_favorites.user_id" to ERASED,
            "veyq_job.lease_holder" to ERASED,
            "veyq_job_decline.worker_id" to ERASED,
            "veyq_upload_session.opener_user_id" to ERASED,
            "veyq_moderation_challenge.account_id" to ERASED,
            "veyq_tenant_terms_acceptance.user_id" to ERASED,
            // Not an account id (SELF or SERVER_OWNER), but spelled like one; the row goes with the account.
            "veyq_account_erasure.requested_by" to ERASED,
            // Its own scopes go with it; any other scope never names it here.
            "veyq_scope.personal_owner" to ERASED,
            // Content attribution: the scope's, shown as a former member's (ADR 0101 decision 5, ADR 0093 § 8).
            "veyq_scope.name_editor_user_id" to kept("content attribution: the scope's name, shown as a former member's"),
            "veyq_scope.icon_editor_user_id" to kept("content attribution: the scope's icon, shown as a former member's"),
            "veyq_node.name_editor_user_id" to kept("content attribution: a node's name, shown as a former member's"),
            "veyq_node.meta_editor_user_id" to kept("content attribution: a node's meta, shown as a former member's"),
            "veyq_node_attachment.uploader_user_id" to kept("content attribution: an attachment's uploader"),
            "veyq_share.owner_account" to kept("a link in a shared scope stays the scope's, switched off at the removal"),
            "veyq_share.name_editor_user_id" to kept("content attribution: a link's name"),
            "veyq_share.note_editor_user_id" to kept("content attribution: a link's note"),
            "veyq_share.share_name_editor_user_id" to kept("content attribution: a link's shown name"),
            "veyq_membership.added_by" to kept("another member's membership names who added them"),
            "veyq_membership.granter_user_id" to kept("another member's grant names who wrapped it, for its signature"),
            // A member invite is a placeholder member (ADR 0111): the erasure supersedes the account's live invites —
            // to its address or signed by it — and that statement nulls both columns; what stays is another owner's
            // live invite a rotation by this account re-granted, which names it like a membership's grant does.
            "veyq_tenant_invite.signer_user_id" to kept("superseded with its key dropped when the signer's account is erased"),
            "veyq_tenant_invite.granter_user_id" to kept("a live invite's grant names who wrapped it, for its signature"),
            // The moderation records the law keeps to their case's log retention date (ADR 0101 decision 7).
            "veyq_moderation_report.reporter_account" to kept("a report the person made, kept to its case's log retention"),
            "veyq_moderation_block.target_id" to kept("a measure on the account stays as the record it is"),
            "veyq_moderation_block_reach.target_id" to kept("a node or an item an uploader's block covers, never an account"),
            "veyq_moderation_block.taken_by" to kept("the moderation log's actor, kept to its case's log retention"),
            "veyq_moderation_block.lifted_by" to kept("the moderation log's actor, kept to its case's log retention"),
            "veyq_moderation_proposal.target_id" to kept("a proposed step on the account, kept to its case's log retention"),
            "veyq_moderation_proposal.proposed_by" to kept("the moderation log's actor, kept to its case's log retention"),
            "veyq_moderation_proposal.settled_by" to kept("the moderation log's actor, kept to its case's log retention"),
            "veyq_moderation_statement_recipient.recipient_id" to kept("a statement's recipient, kept to its case's log retention"),
            "veyq_moderation_objection.objector_account" to kept("an objection, kept to its case's log retention"),
            "veyq_moderation_objection.decided_by" to kept("the moderation log's actor, kept to its case's log retention"),
            "veyq_moderation_deferral.entered_by" to kept("the moderation log's actor, kept to its case's log retention"),
            "veyq_authority_order.entered_by" to kept("an authority's order names who entered it, kept as its proof"),
            "veyq_moderation_event.moderator_id" to kept("the moderation log, kept to its own retention date"),
            "veyq_moderation_event.actor_id" to kept("the moderation log, kept to its own retention date"),
            "veyq_moderation_event.target_id" to kept("the moderation log, kept to its own retention date"),
            "veyq_moderation_grant.delivered_by" to kept("another moderator's grant names whose client sealed it"),
            "veyq_moderator.appointed_by" to kept("the Server Owner, whose account is never erased"),
            "veyq_instance_mail_settings.owner_user_id" to kept("the Server Owner, whose account is never erased"),
            // Addresses (ADR 0101 decision 4: the address goes, wherever it was copied).
            "veyq_user.email" to ERASED,
            "veyq_login_challenge.email" to ERASED,
            "veyq_tenant.owner_email" to ERASED,
            "veyq_tenant_invite.owner_email" to ERASED,
            "veyq_account_recovery.email" to ERASED,
            "veyq_email_confirmation.email" to ERASED,
            "veyq_email_change.new_email" to ERASED,
            "veyq_moderator.code_address" to ERASED,
            "veyq_moderator.confirmed_address" to ERASED,
            "veyq_instance_mail_settings.code_address" to kept("the Server Owner's own confirmation, never an erasable account's"),
            "veyq_instance_mail_settings.confirmed_address" to kept("the Server Owner's own confirmation, never an erasable account's"),
            "veyq_instance_mail_settings.from_address" to kept("the instance's sender mailbox, no account's"),
            "veyq_tenant_mail_settings.from_address" to kept("a tenant's sender mailbox, its owner's setting; goes with the tenant"),
            "veyq_operator_details.contact_email" to kept("the operator's published contact, typed by the Server Owner; no account's"),
            "veyq_operator_details.statement_email" to kept("the operator's published address, typed by the Server Owner; no account's"),
            "veyq_operator_details.provider_email" to kept("the operator's published address, typed by the Server Owner; no account's"),
            "veyq_operator_details.provider_address" to kept("the operator's postal address, typed by the Server Owner"),
            "veyq_operator_details.notification_email" to kept("the operator's notification mailbox, typed by the Server Owner"),
            "veyq_operator_details.code_address" to kept("the operator's notification mailbox's confirmation"),
            "veyq_operator_details.confirmed_address" to kept("the operator's notification mailbox's confirmation"),
        )
    }
}
