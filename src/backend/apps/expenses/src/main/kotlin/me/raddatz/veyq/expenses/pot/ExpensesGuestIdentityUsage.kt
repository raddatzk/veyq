package me.raddatz.veyq.expenses.pot

import me.raddatz.veyq.core.sharing.GuestIdentityUsage
import jakarta.enterprise.context.ApplicationScoped

/** A link identity is still in use while a revision names it as its writer — the name a member's history shows for it
 *  (ADR 0108 decision 6), and `fk_expenses_revision_identity` would refuse its delete anyway. A receipt a guest
 *  completed is core's blob service's to count (`ApplicationBlobGuestIdentityUsage`). */
@ApplicationScoped
class ExpensesGuestIdentityUsage : GuestIdentityUsage {
    override fun stillReferenced(identityId: String): Boolean = PotRevisionEntity.count("writerIdentityId", identityId) > 0
}
