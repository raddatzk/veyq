package me.raddatz.veyq.expenses.pot

import me.raddatz.veyq.core.sharing.GuestIdentityUsage
import me.raddatz.veyq.expenses.receipt.PotReceiptEntity
import jakarta.enterprise.context.ApplicationScoped

/** A link identity is still in use while a revision or a receipt names it as its writer — the name a member's history
 *  shows for it (ADR 0108 decision 6), the link a receipt's bytes count against (ADR 0073 §4), and
 *  `fk_expenses_revision_identity` / `fk_expenses_receipt_identity` would refuse its delete anyway. */
@ApplicationScoped
class ExpensesGuestIdentityUsage : GuestIdentityUsage {
    override fun stillReferenced(identityId: String): Boolean =
        PotRevisionEntity.count("writerIdentityId", identityId) > 0 || PotReceiptEntity.count("writerIdentityId", identityId) > 0
}
