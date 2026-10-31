package me.raddatz.veyq.core.moderation.order

import me.raddatz.veyq.core.admin.operator.OperatorDetailsHandler
import me.raddatz.veyq.core.generated.model.ModerationGround
import me.raddatz.veyq.core.generated.model.ShareGuestNotice
import me.raddatz.veyq.core.generated.model.ShareGuestStatement
import me.raddatz.veyq.core.generated.model.ShareOrderRemoval
import me.raddatz.veyq.core.moderation.block.BlockKind
import me.raddatz.veyq.core.moderation.block.ModerationBlockEntity
import me.raddatz.veyq.core.moderation.block.ModerationBlockReachEntity
import me.raddatz.veyq.core.moderation.statement.ModerationStatementEntity
import me.raddatz.veyq.core.moderation.statement.RecipientKind
import me.raddatz.veyq.core.moderation.statement.StatementBasis
import me.raddatz.veyq.core.moderation.statement.StatementMail
import me.raddatz.veyq.core.moderation.statement.StatementRecipientEntity
import me.raddatz.veyq.core.moderation.statement.StatementRecipients
import me.raddatz.veyq.core.moderation.statement.Statements
import me.raddatz.veyq.core.sharing.SharingStore
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * What a guest uploader is told of a measure on its upload (ADR 0093 §§ 7, 12; uc.moderation.authority-order step 5,
 * uc.moderation.object-to-a-measure step 5). A guest has no account and no address, so nothing reaches it by mail; it is
 * told **on the link it came through**, by the client that holds its contributor token, even while that link is blocked:
 *
 *  - an authority's **removal order** (TCO Art. 11(1), which asks it with no condition of a known contact): when each
 *    order was received, and the instance contact from which the reasons or a copy are given on request (Art. 11(2)) —
 *    nothing else, never the reference or the authority;
 *  - a **moderator's measure** (DSA Art. 17; its Art. 17(2) asks it only where electronic contact details are known,
 *    which a link is not, but the route exists and the safe side costs nothing — owner, inbox #813): the statement's
 *    clear fields, those a member's [Statements.mine] carries, **without the facts** — they are sealed once under a key
 *    wrapped to each member's identity key (`moderation-facts-key`), and a guest identity has no identity key to wrap
 *    it to. The guest asks the instance contact for them, and objects there (ADR 0093 § 7).
 *
 * Both from the same rows and under the same rules:
 *
 *  - only an identity the [contributorToken] names on [shareId] ([SharingStore.findIdentity] — the link's own row,
 *    whatever its state);
 *  - only the statements that name that identity as a recipient (`GUEST`, its uploads in the case's held rows,
 *    [StatementRecipients]);
 *  - only while the measure stands, and **never while an authority's deferral holds the statement back**
 *    (TCO Art. 11(3), ADR 0093 § 7): the same row the statement's mail waits on, so the two cannot disagree.
 *
 * An order's own statements (basis `ORDER`) count as removals only on a case a removal order opened; a moderator's
 * measure (basis `REPORT`, a case's, of a kind with a wire name) as a statement. Anything else — and nothing to tell —
 * answers null, which the resource turns into the link's ordinary unknown answer: the read tells nobody else the link
 * exists. The server learns nothing it did not hold: the rows are the measure's own, and the read is § 12's.
 */
@ApplicationScoped
class GuestNotices(private val store: SharingStore, private val operator: OperatorDetailsHandler) {

    fun noticeFor(shareId: String, contributorToken: String, now: Instant = Instant.now()): ShareGuestNotice? =
        QuarkusTransaction.requiringNew().call {
            val identity = store.findIdentity(shareId, contributorToken) ?: return@call null
            val statementIds = StatementRecipientEntity.list("recipientKind = ?1 and recipientId = ?2", RecipientKind.GUEST.name, identity.id)
                .map { it.statementId }.distinct()
            if (statementIds.isEmpty()) return@call null
            val standing = ModerationStatementEntity.list("id in ?1", statementIds)
                .filter { s -> s.deferredUntil?.isAfter(now) != true }
                .mapNotNull { s -> ModerationBlockEntity.findById(s.measureId)?.takeIf { it.activeKey != null }?.let { s to it } }
            val received = standing.filter { (s, _) -> s.basis == StatementBasis.ORDER.name }
                .mapNotNull { (s, _) -> s.caseId }
                .distinct()
                .flatMap { caseId -> AuthorityOrderEntity.list("caseId = ?1 and kind = ?2", caseId, OrderKind.REMOVAL.name) }
                .map { it.receivedAt }
                .distinct()
                .sorted()
            val statements = standing.filter { (s, _) -> s.basis == StatementBasis.REPORT.name }
                .mapNotNull { (s, m) -> view(s, m) }
                .sortedWith(compareByDescending<ShareGuestStatement> { it.takenAt }.thenBy { it.id })
            if (received.isEmpty() && statements.isEmpty()) return@call null
            ShareGuestNotice(
                removals = received.map { ShareOrderRemoval(orderReceivedAt = it.atOffset(ZoneOffset.UTC)) },
                statements = statements,
                contactAddress = StatementMail.contactOf(operator),
            )
        }

    /** A moderator's measure's statement, as its guest uploader reads it; null for what a member would not read either. */
    private fun view(s: ModerationStatementEntity, m: ModerationBlockEntity): ShareGuestStatement? {
        // A Server Owner's step's statement is a mail's alone (ADR 0093 § 7): its kind has no wire name, and it has no case.
        if (s.caseId == null) return null
        val kind = m.blockKind.wire ?: return null
        return ShareGuestStatement(
            id = UUID.fromString(s.id),
            measureKind = ShareGuestStatement.MeasureKind.valueOf(kind),
            targetId = m.targetId,
            shareId = when (kind) {
                "SHARE" -> m.targetId
                "GUEST_IDENTITY" -> m.shareId
                else -> null
            },
            // Everything from one uploader: the ids it blocks — this identity's own uploads — so each placeholder finds it.
            covers = if (m.blockKind == BlockKind.CONTENT_UPLOADER) ModerationBlockReachEntity.list("blockId", m.id).map { it.targetId } else emptyList(),
            ground = ModerationGround.valueOf(s.ground),
            provision = s.provision,
            takenAt = m.takenAt.atOffset(ZoneOffset.UTC),
        )
    }
}
