package me.raddatz.veyq.core.account.erasure

import me.raddatz.veyq.core.admin.instance.InstanceNoticeMailer
import me.raddatz.veyq.core.generated.model.DeliveryResult
import me.raddatz.veyq.core.identity.AccountErasureEntity
import me.raddatz.veyq.core.identity.VeyqUser
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The one mail an erasure request sends (ADR 0101 decision 2): to the account's address, through the
 * instance mail (ADR 0097 decision 3), **after** the request committed and while the address still exists — that the
 * erasure was requested, when it completes, that only the Server Owner can stop it until then, and what stays. A
 * stolen session that erases an account is so seen by its owner. A mail that did not go out is retried by the purge's
 * hourly tick for seven days, as a statement's is ([retryDue]); [AccountErasureEntity.mailedAt] records that it went.
 * Nothing here logs an address (ADR 0092 § 1).
 */
@ApplicationScoped
class AccountErasureMail(private val mailer: InstanceNoticeMailer) {
    private val log = Logger.getLogger(AccountErasureMail::class.java)

    private data class Outgoing(val address: String, val body: String)

    /** Mail [accountId]'s address about its pending erasure, unless that already went out. Answers whether it went now. */
    fun requested(accountId: String): Boolean = runCatching {
        val outgoing = QuarkusTransaction.requiringNew().call {
            val pending = AccountErasureEntity.findById(accountId)?.takeIf { it.mailedAt == null } ?: return@call null
            val address = VeyqUser.findById(accountId)?.email ?: return@call null
            Outgoing(address, body(pending.requestedBy, pending.dueAt))
        } ?: return@runCatching false
        val sent = mailer.mailTo(outgoing.address, SUBJECT, outgoing.body, "an account erasure") == DeliveryResult.SENT
        if (sent) QuarkusTransaction.requiringNew().run { AccountErasureEntity.findById(accountId)?.mailedAt = Instant.now() }
        sent
    }.getOrElse {
        log.warn("an account erasure could not be mailed")
        false
    }

    /** Every pending erasure of the last seven days whose mail did not go out. Answers how many went out now. */
    fun retryDue(now: Instant): Int {
        val owed = QuarkusTransaction.requiringNew().call {
            AccountErasureEntity.list("mailedAt is null and requestedAt > ?1", now.minus(RETRY_WINDOW)).map { it.accountId }
        }
        return owed.count { requested(it) }
    }

    companion object {
        private val RETRY_WINDOW: Duration = Duration.ofDays(7)
        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)
        const val SUBJECT = "veyq: your account is being erased / dein Konto wird gelöscht"

        fun body(requestedBy: String, dueAt: Instant): String {
            val byOwner = requestedBy == AccountErasureEntity.SERVER_OWNER
            val en = if (byOwner) "The Server Owner of this veyq server erased your account at a request that reached them."
            else "Your account on this veyq server was erased in its settings."
            val de = if (byOwner) "Die Person, die diesen veyq-Server betreibt, hat dein Konto auf eine Anfrage hin gelöscht."
            else "Dein Konto auf diesem veyq-Server wurde in seinen Einstellungen gelöscht."
            val date = DATE.format(dueAt)
            return """
                |$en You can no longer sign in. On $date the account and what is kept about it are erased for good. Until
                |then only the Server Owner can stop the erasure — if you did not ask for it, contact them now.
                |
                |What stays: what you put into spaces you did not own alone belongs to those spaces and shows as a former
                |member's. Moderation records and holds that name your account stay to their own legal ends. Copies in the
                |server's backups stay until those backups expire.
                |
                |—
                |
                |$de Du kannst dich nicht mehr anmelden. Am $date werden das Konto und was über dich gespeichert ist endgültig
                |gelöscht. Bis dahin kann nur die Person, die den Server betreibt, die Löschung stoppen — wenn du sie nicht
                |veranlasst hast, wende dich jetzt an sie.
                |
                |Was bleibt: Was du in Bereiche gelegt hast, die dir nicht allein gehörten, gehört diesen Bereichen und
                |erscheint als das eines ehemaligen Mitglieds. Moderationsunterlagen und Sicherungen eines Falls, die dein
                |Konto nennen, bleiben bis zu ihrem gesetzlichen Ende. Kopien in den Backups des Servers bleiben, bis diese
                |Backups ablaufen.
            """.trimMargin()
        }
    }
}
