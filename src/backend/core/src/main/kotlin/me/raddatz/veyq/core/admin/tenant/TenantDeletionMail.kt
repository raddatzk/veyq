package me.raddatz.veyq.core.admin.tenant

import me.raddatz.veyq.core.admin.instance.InstanceNoticeMailer
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.tenant.TenantEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The mail a tenant's deletion sends (uc.admin.delete-tenant step 3, ADR 0101 decision 10): every member of its TENANT
 * scope, at their account address, through the instance mail (ADR 0097 decision 3) — after the deletion committed, so a
 * mail that fails costs the deletion nothing. It says the space is going and when, and that the account stays (owner,
 * Q20): nothing erases an account its person did not ask to erase. Best effort, one try: the tenant is offline from the
 * deletion on, so the mail informs and gates nothing. Nothing here logs an address (ADR 0092 § 1).
 */
@ApplicationScoped
class TenantDeletionMail(private val mailer: InstanceNoticeMailer) {
    private val log = Logger.getLogger(TenantDeletionMail::class.java)

    private data class Outgoing(val name: String, val addresses: List<String>)

    /** Mail every member of [tenantId] that it is deleted and goes at [dueAt]. Answers how many mails went out. */
    fun tenantDeleted(tenantId: String, dueAt: Instant): Int = runCatching {
        val outgoing = QuarkusTransaction.requiringNew().call {
            val tenant = TenantEntity.findById(tenantId) ?: return@call null
            val members = MembershipEntity.list("scopeId = ?1 and pendingConsent = false", tenantId).map { it.userId }
            Outgoing(tenant.name, members.mapNotNull { VeyqUser.findById(it)?.email })
        } ?: return@runCatching 0
        outgoing.addresses.count { address ->
            mailer.mailTo(address, SUBJECT, body(outgoing.name, dueAt), "a tenant's deletion") ==
                me.raddatz.veyq.core.generated.model.DeliveryResult.SENT
        }
    }.getOrElse {
        log.warn("a tenant's deletion could not be mailed to its members")
        0
    }

    companion object {
        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)
        const val SUBJECT = "veyq: a space you belong to is being deleted / ein Bereich, dem du angehörst, wird gelöscht"

        fun body(name: String, dueAt: Instant): String = """
            |The Server Owner of this veyq instance deleted the space "$name". It is offline from now on, and everything in
            |it is erased on ${DATE.format(dueAt)}, unless the Server Owner stops the deletion before then. Your account
            |stays: you keep it, and your other spaces, until you erase it yourself.
            |
            |—
            |
            |Die Person, die diese veyq-Instanz betreibt, hat den Bereich „$name“ gelöscht. Er ist ab jetzt offline, und
            |alles darin wird am ${DATE.format(dueAt)} gelöscht, wenn die Löschung nicht vorher gestoppt wird. Dein Konto
            |bleibt: Du behältst es und deine anderen Bereiche, bis du es selbst löschst.
        """.trimMargin()
    }
}
