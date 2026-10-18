package me.raddatz.veyq.core.moderation.order

import me.raddatz.veyq.core.admin.operator.OperatorDetailsHandler
import me.raddatz.veyq.core.generated.model.ShareOrderNotice
import me.raddatz.veyq.core.generated.model.ShareOrderRemoval
import me.raddatz.veyq.core.moderation.block.ModerationBlockEntity
import me.raddatz.veyq.core.moderation.statement.ModerationStatementEntity
import me.raddatz.veyq.core.moderation.statement.RecipientKind
import me.raddatz.veyq.core.moderation.statement.StatementBasis
import me.raddatz.veyq.core.moderation.statement.StatementMail
import me.raddatz.veyq.core.moderation.statement.StatementRecipientEntity
import me.raddatz.veyq.core.sharing.SharingStore
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.time.ZoneOffset

/**
 * A guest uploader's notice of an authority's removal order (TCO Art. 11(1), ADR 0093 § 12; uc.moderation.authority-order
 * step 5). A guest has no account and no address, so DSA Art. 17(2) leaves it untold of a moderator's measure; a
 * removal order is different — Art. 11(1) asks the host to make the removal known to the content provider with no
 * condition of a known contact. So the guest identity whose upload the order blocked learns it **on the link it came
 * through**, from the client that holds its contributor token, even while that link is blocked — and nothing else:
 *
 *  - only an identity the [contributorToken] names on [shareId] ([SharingStore.findIdentity] — the link's own row,
 *    whatever its state);
 *  - only the statements of reasons that name that identity as a recipient (`GUEST`, its uploads in the case's held rows,
 *    `StatementRecipients`) with the basis `ORDER`, on a case a **removal** order opened — a moderator's measure never;
 *  - only while the measure stands, and **never while an authority's deferral holds the statement back**
 *    (TCO Art. 11(3)): the same row the statement's mail waits on, so the two cannot disagree.
 *
 * Anything else answers null, which the resource turns into the link's ordinary unknown answer — the read tells nobody
 * else the link exists. What it tells: when each order was received, and the instance contact, from which the reasons or
 * a copy of the order are given on request (Art. 11(2)). Never the reference, the authority, or another identity's.
 */
@ApplicationScoped
class GuestOrderNotices(private val store: SharingStore, private val operator: OperatorDetailsHandler) {

    fun noticeFor(shareId: String, contributorToken: String, now: Instant = Instant.now()): ShareOrderNotice? =
        QuarkusTransaction.requiringNew().call {
            val identity = store.findIdentity(shareId, contributorToken) ?: return@call null
            val statementIds = StatementRecipientEntity.list("recipientKind = ?1 and recipientId = ?2", RecipientKind.GUEST.name, identity.id)
                .map { it.statementId }.distinct()
            if (statementIds.isEmpty()) return@call null
            val received = ModerationStatementEntity.list("id in ?1 and basis = ?2", statementIds, StatementBasis.ORDER.name)
                .filter { s -> s.deferredUntil?.isAfter(now) != true }
                .filter { s -> ModerationBlockEntity.findById(s.measureId)?.activeKey != null }
                .mapNotNull { it.caseId }
                .distinct()
                .flatMap { caseId -> AuthorityOrderEntity.list("caseId = ?1 and kind = ?2", caseId, OrderKind.REMOVAL.name) }
                .map { it.receivedAt }
                .distinct()
                .sorted()
            if (received.isEmpty()) return@call null
            ShareOrderNotice(
                removals = received.map { ShareOrderRemoval(orderReceivedAt = it.atOffset(ZoneOffset.UTC)) },
                contactAddress = StatementMail.contactOf(operator),
            )
        }
}
