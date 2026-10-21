package me.raddatz.veyq.core.moderation.statement

import me.raddatz.veyq.core.generated.model.StatementDelivery
import me.raddatz.veyq.core.generated.model.StatementDelivery.Outcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The one function that turns a statement's send into what a Server Owner's step answers (ADR 0093 § 7), on every case —
 * the partial one included, which the slice tests cannot reach: a real mailbox does not refuse one of two recipients.
 */
class StatementMailingTest {

    private fun tried(vararg sent: Pair<String, Boolean>) = StatementMailing(sent.map { StatementMailing.Tried(it.first, it.second) })

    @Test
    fun everyMailWentOutIsReached() {
        val m = tried("a@example.com" to true, "b@example.com" to true)
        assertEquals(StatementDelivery(Outcome.REACHED, emptyList()), m.delivery())
        assertEquals(2, m.sent)
    }

    @Test
    fun noMailWentOutNamesEveryRecipient() {
        val m = tried("a@example.com" to false, "b@example.com" to false)
        assertEquals(StatementDelivery(Outcome.NOT_ALL_REACHED, listOf("a@example.com", "b@example.com")), m.delivery())
        assertEquals(0, m.sent)
    }

    @Test
    fun oneOfTwoWentOutNamesTheOtherOnly() {
        val m = tried("a@example.com" to true, "b@example.com" to false)
        assertEquals(StatementDelivery(Outcome.NOT_ALL_REACHED, listOf("b@example.com")), m.delivery())
        assertEquals(1, m.sent)
    }

    @Test
    fun noRecipientIsNobodyToTell() {
        assertEquals(StatementDelivery(Outcome.NOBODY_TO_TELL, emptyList()), tried().delivery())
    }

    @Test
    fun aListThatCouldNotBeReadIsUnknown() {
        val m = StatementMailing(null)
        assertEquals(StatementDelivery(Outcome.UNKNOWN, emptyList()), m.delivery())
        assertEquals(0, m.sent)
    }
}
