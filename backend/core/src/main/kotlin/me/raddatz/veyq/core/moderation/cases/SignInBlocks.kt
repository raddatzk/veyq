package me.raddatz.veyq.core.moderation.cases

import me.raddatz.veyq.core.account.login.SessionService
import me.raddatz.veyq.core.generated.model.AccountSignInBlock
import me.raddatz.veyq.core.generated.model.ModerationSteps
import me.raddatz.veyq.core.generated.model.OpenModerationProposal
import me.raddatz.veyq.core.http.FailureStatus
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.identity.AccountStore
import me.raddatz.veyq.core.identity.ServerRoles
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.moderation.ModerationEvent
import me.raddatz.veyq.core.moderation.ModerationLog
import me.raddatz.veyq.core.moderation.block.BlockKind
import me.raddatz.veyq.core.moderation.block.MeasureTargets
import me.raddatz.veyq.core.moderation.block.ModerationBlockEntity
import me.raddatz.veyq.core.moderation.block.ModerationProposalEntity
import me.raddatz.veyq.core.moderation.block.ProposalOutcome
import me.raddatz.veyq.core.moderation.block.ProposalStep
import me.raddatz.veyq.core.moderation.report.ModerationCaseEntity
import me.raddatz.veyq.core.moderation.statement.StatementMail
import me.raddatz.veyq.core.moderation.statement.StepStatementInput
import me.raddatz.veyq.core.moderation.statement.StepStatements
import me.raddatz.veyq.core.persistence.createOrConflict
import me.raddatz.veyq.core.tenant.TenantEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** The Server Owner's steps' refusals (codes per `instance.openapi.yaml`). */
object StepFailures {
    val accountNotFound = httpFailure("account_not_found", FailureStatus.NOT_FOUND)
    val caseNotFound = httpFailure("case_not_found", FailureStatus.NOT_FOUND)
    val blockExists = httpFailure("sign_in_block_exists", FailureStatus.CONFLICT)
    val serverOwner = httpFailure("cannot_block_server_owner", FailureStatus.CONFLICT)
    val blockNotFound = httpFailure("sign_in_block_not_found", FailureStatus.NOT_FOUND)
    val proposalNotFound = httpFailure("proposal_not_found", FailureStatus.NOT_FOUND)
}

/**
 * The Server Owner's half of the measures (ADR 0099 § 6, decided with the owner 2; uc.moderation.take-a-measure step 5),
 * on the admin channel only: the moderators' open proposals, a member's sign-in block and its lift, and settling a
 * proposal — taken by the step itself (a block of its account, the suspension of its tenant), or declined.
 *
 * **A sign-in block ends every session at once** (owner decision Q10). It takes the account row's lock first — the lock
 * every sign-in and every pairing delegation takes before it adds a session (`AccountStore.lock`) — writes the block and
 * revokes every family under it: a sign-in that won the lock issued its family before, and the revoke sees it; one that
 * waited reads the block and is refused (`LoginHandler.verify`). No window leaves a session alive beside a block.
 *
 * **Over content it carries a statement of reasons** (ADR 0093 § 7, DSA Art. 17(1)(d)): a block that answers a case or
 * takes a moderator's open proposal, always; one with neither when the Server Owner says so. The statement is written in
 * the block's transaction ([StepStatements]) and mailed to the account's address with the facts after the commit. A
 * block that takes a proposal and names no case is recorded on the proposal's case, so its records go with that case's.
 */
@ApplicationScoped
class SignInBlocks(
    private val accounts: AccountStore,
    private val sessions: SessionService,
    private val log: ModerationLog,
    private val statements: StepStatements,
    private val mail: StatementMail,
    private val targets: MeasureTargets,
) {

    /** The open proposals and the sign-in blocks in force. */
    fun steps(): ModerationSteps = QuarkusTransaction.requiringNew().call {
        val proposals = ModerationProposalEntity.list("outcome = ?1 order by proposedAt", ProposalOutcome.OPEN.name)
        val cases = if (proposals.isEmpty()) emptyMap() else ModerationCaseEntity.list("id in ?1", proposals.map { it.caseId }.distinct()).associateBy { it.id }
        ModerationSteps(
            proposals = proposals.mapNotNull { p ->
                val case = cases[p.caseId] ?: return@mapNotNull null
                val member = p.step == ProposalStep.MEMBER_SIGN_IN.name
                val tenant = if (member) null else TenantEntity.findById(p.targetId)
                OpenModerationProposal(
                    id = UUID.fromString(p.id),
                    caseId = UUID.fromString(p.caseId),
                    category = OpenModerationProposal.Category.valueOf(case.category),
                    step = OpenModerationProposal.Step.valueOf(p.step),
                    accountId = if (member) UUID.fromString(p.targetId) else null,
                    address = if (member) VeyqUser.findById(p.targetId)?.email else null,
                    tenantId = if (member) null else UUID.fromString(p.targetId),
                    tenantDomain = tenant?.domain,
                    proposedAt = p.proposedAt.atOffset(ZoneOffset.UTC),
                )
            },
            signInBlocks = ModerationBlockEntity.list(
                "kind = ?1 and activeKey is not null order by takenAt", BlockKind.MEMBER_SIGN_IN.name,
            ).map { b ->
                AccountSignInBlock(
                    id = UUID.fromString(b.id),
                    accountId = UUID.fromString(b.targetId),
                    address = VeyqUser.findById(b.targetId)?.email ?: "",
                    caseId = b.caseId?.let(UUID::fromString),
                    blockedAt = b.takenAt.atOffset(ZoneOffset.UTC),
                )
            },
        )
    }

    /**
     * Block the sign-in of the account under [address], for [caseId] or none; over content ([overContent], or a case or an
     * open proposal) with its [statement]. Answers the refusal, or null when done — the statement mailed after the commit.
     */
    fun block(
        serverOwnerId: String, address: String, caseId: String?, now: Instant = Instant.now(),
        overContent: Boolean = false, statement: StepStatementInput? = null,
    ): HttpFailure? {
        val refusal = createOrConflict(conflict = { StepFailures.blockExists }) {
            blockIn(serverOwnerId, address, caseId, now, overContent, statement)
        }
        if (refusal == null && statement != null) mailStatement(statement)
        return refusal
    }

    /** [block]'s writes, **in the caller's transaction** — the race test holds them uncommitted (`ModerationRacesSliceTest`). */
    @Transactional(Transactional.TxType.MANDATORY)
    fun blockIn(
        serverOwnerId: String, address: String, caseId: String?, now: Instant,
        overContent: Boolean = false, statement: StepStatementInput? = null,
    ): HttpFailure? {
        // Locked as the first read of the account: the lock a sign-in and a pairing delegation take before they add a
        // session (AccountStore.lock).
        val user = accounts.findByEmail(address, lock = true) ?: return StepFailures.accountNotFound
        if (caseId != null && ModerationCaseEntity.findById(caseId) == null) return StepFailures.caseNotFound
        if (user.serverRole == ServerRoles.SERVER_OWNER) return StepFailures.serverOwner
        val key = ModerationBlockEntity.activeKeyOf(BlockKind.MEMBER_SIGN_IN, user.id)
        if (ModerationBlockEntity.count("activeKey", key) > 0) return StepFailures.blockExists
        // A block that takes a moderator's proposal answers its case; it is over content then, as one on a case is.
        val onCase = caseId ?: proposedCase(ProposalStep.MEMBER_SIGN_IN, user.id)
        statements.refusal(overContent || onCase != null, statement)?.let { return it }
        val row = ModerationBlockEntity().apply {
            id = UUID.randomUUID().toString()
            kind = BlockKind.MEMBER_SIGN_IN.name
            targetId = user.id
            this.caseId = onCase
            takenBy = serverOwnerId
            takenAt = now
            activeKey = key
        }
        row.persist()
        sessions.revokeAllForUser(user.id)
        if (onCase != null) {
            log.appendCase(ModerationEvent.MEASURE_TAKEN, now, onCase, moderatorId = serverOwnerId, reason = BlockKind.MEMBER_SIGN_IN.name, targetId = user.id)
        } else {
            log.appendSignInBlock(ModerationEvent.MEASURE_TAKEN, now, serverOwnerId, user.id)
        }
        statement?.let { statements.write(row, it, serverOwnerId, now) }
        settle(ProposalStep.MEMBER_SIGN_IN, user.id, serverOwnerId, now)
        return null
    }

    /**
     * Whether a suspension of [tenantId] may go ahead with [statement] (ADR 0093 § 7): over content when [overContent] or a
     * moderator's proposal to take it offline is open. In the suspension's transaction, before it flips the status.
     */
    fun offlineRefusal(tenantId: String, overContent: Boolean, statement: StepStatementInput?): HttpFailure? =
        statements.refusal(overContent || proposedCase(ProposalStep.TENANT_OFFLINE, tenantId) != null, statement)

    /**
     * [tenantId] was taken offline, **in the caller's transaction** (the suspension's): with a [statement] — over content —
     * a `TENANT_OFFLINE` measure records it (on the proposal's case where one was open) and carries the statement; every
     * open proposal to take it offline is settled as taken.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    fun takenOffline(tenantId: String, serverOwnerId: String, statement: StepStatementInput?, now: Instant) =
        tenantStep(BlockKind.TENANT_OFFLINE, tenantId, serverOwnerId, statement, now)

    /**
     * [tenantId] was deleted, **in the caller's transaction** (the deletion's; ADR 0101 decision 10): over content, a
     * `TENANT_DELETED` measure records it and carries the statement (DSA Art. 17(1)(c), a termination of the service for its
     * owners), exactly as a suspension's `TENANT_OFFLINE` does; every open proposal to take it offline is settled as taken.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    fun deleted(tenantId: String, serverOwnerId: String, statement: StepStatementInput?, now: Instant) =
        tenantStep(BlockKind.TENANT_DELETED, tenantId, serverOwnerId, statement, now)

    private fun tenantStep(kind: BlockKind, tenantId: String, serverOwnerId: String, statement: StepStatementInput?, now: Instant) {
        if (statement != null) {
            val onCase = proposedCase(ProposalStep.TENANT_OFFLINE, tenantId)
            val row = ModerationBlockEntity().apply {
                id = UUID.randomUUID().toString()
                this.kind = kind.name
                targetId = tenantId
                caseId = onCase
                takenBy = serverOwnerId
                takenAt = now
                activeKey = ModerationBlockEntity.activeKeyOf(kind, tenantId)
            }
            row.persist()
            if (onCase != null) {
                log.appendCase(ModerationEvent.MEASURE_TAKEN, now, onCase, moderatorId = serverOwnerId, reason = row.kind, targetId = tenantId)
            } else {
                log.appendCaselessStep(ModerationEvent.MEASURE_TAKEN, now, serverOwnerId, tenantId, row.kind)
            }
            statements.write(row, statement, serverOwnerId, now)
        }
        settle(ProposalStep.TENANT_OFFLINE, tenantId, serverOwnerId, now)
    }

    /** [tenantId] came back, **in the caller's transaction** (the reactivation's): a `TENANT_OFFLINE` measure in force is lifted. */
    @Transactional(Transactional.TxType.MANDATORY)
    fun backOnline(tenantId: String, serverOwnerId: String, now: Instant) = lift(BlockKind.TENANT_OFFLINE, tenantId, serverOwnerId, now)

    /** [tenantId]'s deletion was stopped, **in the caller's transaction**: a `TENANT_DELETED` measure in force is lifted. */
    @Transactional(Transactional.TxType.MANDATORY)
    fun deletionStopped(tenantId: String, serverOwnerId: String, now: Instant) = lift(BlockKind.TENANT_DELETED, tenantId, serverOwnerId, now)

    /**
     * [targetId] — an account or a tenant — was erased, **in its purge's transaction** (ADR 0101 decisions 7, 10): every
     * measure of [kinds] on it still in force ends with it, lifted by no one and logged so, dated from [now] — the one
     * rule every eraser follows ([MeasureTargets.erased]).
     */
    @Transactional(Transactional.TxType.MANDATORY)
    fun targetErased(targetId: String, kinds: List<BlockKind>, now: Instant) = targets.erased(kinds, listOf(targetId), now)

    private fun lift(kind: BlockKind, tenantId: String, serverOwnerId: String, now: Instant) {
        val row = ModerationBlockEntity.find("activeKey", ModerationBlockEntity.activeKeyOf(kind, tenantId)).firstResult()
            ?: return
        row.liftedAt = now
        row.liftedBy = serverOwnerId
        row.activeKey = null
        val caseId = row.caseId
        if (caseId != null) {
            log.appendCase(ModerationEvent.MEASURE_LIFTED, now, caseId, moderatorId = serverOwnerId, reason = row.kind, targetId = tenantId)
        } else {
            log.appendCaselessStep(ModerationEvent.MEASURE_LIFTED, now, serverOwnerId, tenantId, row.kind)
        }
    }

    /** Mail a step's [statement] with its facts — after the step committed, once (`StatementMail.statementSent`). */
    fun mailStatement(statement: StepStatementInput) {
        mail.statementSent(statement.statementId, statement.facts?.takeIf { it.isNotBlank() })
    }

    /** The case of the oldest open proposal of [step] on [targetId], or null. */
    private fun proposedCase(step: ProposalStep, targetId: String): String? =
        ModerationProposalEntity.find(
            "step = ?1 and targetId = ?2 and outcome = ?3 order by proposedAt", step.name, targetId, ProposalOutcome.OPEN.name,
        ).firstResult()?.caseId

    /** Lift the sign-in block [blockId]; one already lifted changes nothing. */
    @Transactional
    fun unblock(serverOwnerId: String, blockId: String, now: Instant = Instant.now()): HttpFailure? {
        val row = ModerationBlockEntity.find("id = ?1 and kind = ?2", blockId, BlockKind.MEMBER_SIGN_IN.name).firstResult()
            ?: return StepFailures.blockNotFound
        if (row.activeKey == null) return null
        row.liftedAt = now
        row.liftedBy = serverOwnerId
        row.activeKey = null
        val caseId = row.caseId
        if (caseId != null) {
            log.appendCase(ModerationEvent.MEASURE_LIFTED, now, caseId, moderatorId = serverOwnerId, reason = row.kind, targetId = row.targetId)
        } else {
            log.appendSignInBlock(ModerationEvent.MEASURE_LIFTED, now, serverOwnerId, row.targetId)
        }
        return null
    }

    /** Decline the open proposal [proposalId]; one already settled changes nothing. */
    @Transactional
    fun decline(serverOwnerId: String, proposalId: String, now: Instant = Instant.now()): HttpFailure? {
        val p = ModerationProposalEntity.findById(proposalId) ?: return StepFailures.proposalNotFound
        if (p.outcome != ProposalOutcome.OPEN.name) return null
        settleOne(p, ProposalOutcome.DECLINED, serverOwnerId, now)
        return null
    }

    /**
     * The step [step] was taken on [targetId] (a block of the account, the suspension of the tenant): every open proposal
     * of it is settled as taken, **in the caller's transaction**.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    fun settle(step: ProposalStep, targetId: String, serverOwnerId: String, now: Instant = Instant.now()) =
        ModerationProposalEntity.list("step = ?1 and targetId = ?2 and outcome = ?3", step.name, targetId, ProposalOutcome.OPEN.name)
            .forEach { settleOne(it, ProposalOutcome.TAKEN, serverOwnerId, now) }

    private fun settleOne(p: ModerationProposalEntity, outcome: ProposalOutcome, serverOwnerId: String, now: Instant) {
        p.outcome = outcome.name
        p.settledAt = now
        p.settledBy = serverOwnerId
        p.openKey = null
        val event = if (outcome == ProposalOutcome.TAKEN) ModerationEvent.PROPOSAL_TAKEN else ModerationEvent.PROPOSAL_DECLINED
        log.appendCase(event, now, p.caseId, moderatorId = serverOwnerId, reason = p.step, targetId = p.targetId)
    }
}
