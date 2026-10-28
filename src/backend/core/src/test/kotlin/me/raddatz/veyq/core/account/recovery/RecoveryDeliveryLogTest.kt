package me.raddatz.veyq.core.account.recovery

import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.PersistenceException
import me.raddatz.veyq.core.admin.tenant.TenantMailSender
import me.raddatz.veyq.core.identity.AccountStore
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.tenant.ResolvedTenant
import me.raddatz.veyq.core.testing.LogCapture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The recovery worker's catch logs the tenant and the cause's class, never the address and never the cause's message
 * (ADR 0092 § 3): a database error's text carries the values it was bound with, the address among them. The lever is a
 * store whose lookup fails with exactly such a message, and a mailer that can send, so the request is handed to the
 * worker. Bites with `e.message` (or the address) back in the line; the control is the line itself, awaited on the
 * same capture, since the worker writes it after the request was answered.
 */
@QuarkusTest
class RecoveryDeliveryLogTest {

    @Inject
    lateinit var handler: RecoveryHandler

    @Inject
    lateinit var sender: TenantMailSender

    @Test
    fun aDatabaseErrorInTheWorkerIsLoggedByItsClassNeverByTheAddress() {
        QuarkusMock.installMockForType(SendingMailer(sender), RecoveryMailer::class.java)
        QuarkusMock.installMockForType(FailingStore(), AccountStore::class.java)
        val address = "recover-${UUID.randomUUID()}@example.org"

        val records = LogCapture().use { capture ->
            assertEquals(RequestOutcome.ACCEPTED, handler.request(address, TENANT))
            capture.await { "recovery delivery for tenant" in it.text }
            capture.records()
        }

        val line = records.single { "recovery delivery for tenant" in it.text }
        assertTrue(TENANT.id in line.text && "PersistenceException" in line.text, "the tenant and the class: <$line>")
        records.forEach { assertFalse(address in it.text, "the address is in a log record: <$it>") }
    }

    /** A tenant that can send, so [RecoveryHandler.request] hands the address to the worker. */
    class SendingMailer(sender: TenantMailSender) : RecoveryMailer(sender) {
        override fun canSend(tenantId: String): Boolean = true
    }

    /** A lookup that fails the way a database does: with the bound value in its message. */
    class FailingStore : AccountStore(me.raddatz.veyq.core.identity.keychain.KeyChainStore()) {
        override fun findByEmail(email: String, lock: Boolean): VeyqUser? =
            throw PersistenceException("could not execute statement [select … where email=?] bound '$email'")
    }

    private companion object {
        val TENANT = ResolvedTenant("7265636f-7665-4000-8000-00000000106c", "recovery-log.test")
    }
}
