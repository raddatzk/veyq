package me.raddatz.veyq.expenses.pot

import me.raddatz.veyq.core.identity.AccountEraser
import me.raddatz.veyq.core.identity.ColumnFate
import me.raddatz.veyq.core.identity.kept
import jakarta.enterprise.context.ApplicationScoped

/**
 * What of Expenses goes with an erased account (ADR 0101 decisions 4-5): nothing of its own. A revision a member wrote
 * belongs to its Pot like a medium to its Memory, and its writer stays under the opaque id, shown as a former member's
 * — the history every balance rests on (ADR 0108 decision 6). The account's own scopes, and their Pots, are erased
 * before this runs (`PotScopeEraser`).
 */
@ApplicationScoped
class ExpensesAccountEraser : AccountEraser {
    override val columns: Map<String, ColumnFate> = mapOf(
        "veyq_expenses_revision.writer_user_id" to kept("content attribution: a revision's writer, shown as a former member's"),
    )

    override fun eraseAccount(accountId: String) {}
}
