package me.raddatz.veyq.core.platform.capabilities

import me.raddatz.veyq.core.admin.instance.InstanceNoticeMailer
import me.raddatz.veyq.core.generated.model.DeliveryResult
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.tenant.TenantEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import org.jboss.logging.Logger
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The two mails of an application left switched off (uc.platform.erase-switched-off-application steps 2 and 3): the
 * **notice** once a tenant's erasure row exists, and the **reminder** [REMINDER_AHEAD] before its
 * day. Every member of the tenant (`pendingConsent = false`, the tenant owner among them) at their account address,
 * through the instance mail (ADR 0097 decision 3): an erasure is the host's to announce, as a tenant's deletion is
 * (`TenantDeletionMail`). Sent by [ApplicationErasurePurge]'s tick, never by the switch's request, so no switch waits on
 * a relay.
 *
 * **Each once.** A row is claimed under its own row lock — its [ApplicationErasureEntity.noticeMailedAt] or
 * [ApplicationErasureEntity.reminderMailedAt] stamped — and mailed after that commit, one try per member. The stamp
 * records that the attempt was made, not that it reached anyone: without instance mail every try answers `FAILED` and
 * nothing is mailed, while the banner and the owner's panel still carry the day and nothing is refused. A row of a
 * tenant that is not `ACTIVE` stays unclaimed (offline, its members could not open what the mail says they can), and
 * is mailed if the tenant is active again before the day. Nothing here logs an address (ADR 0092 § 1).
 */
@ApplicationScoped
class ApplicationErasureMail(private val mailer: InstanceNoticeMailer) {
    private val log = Logger.getLogger(ApplicationErasureMail::class.java)

    /** One claimed mail: its words and the addresses it goes to. */
    data class Outgoing(val subject: String, val body: String, val addresses: List<String>)

    /** Claim every row whose notice is owed and mail it. Answers how many mails went out. */
    fun notices(now: Instant): Int = send(claim(now, "noticeMailedAt is null") { row, tenant ->
        row.noticeMailedAt = now
        if (row.dueAt.isAfter(now)) notice(row, tenant.name) else null
    }, "an application's switch-off notice")

    /**
     * Claim every row whose reminder is due — its day [REMINDER_AHEAD] away or nearer, not yet passed, in a window longer
     * than [REMINDER_AHEAD] — and mail it. A notice that itself went out inside those seven days already named the day,
     * so its reminder is stamped and not sent. Answers how many mails went out.
     */
    fun reminders(now: Instant): Int = send(claim(now, "reminderMailedAt is null and dueAt > ?1 and dueAt <= ?2", now, now.plus(REMINDER_AHEAD)) { row, tenant ->
        if (Duration.between(row.offSince, row.dueAt) <= REMINDER_AHEAD) return@claim null
        val notice = row.noticeMailedAt ?: return@claim null // the notice first; the reminder at a later tick
        row.reminderMailedAt = now
        if (notice.isBefore(row.dueAt.minus(REMINDER_AHEAD))) reminder(row, tenant.name) else null
    }, "an application's erasure reminder")

    /**
     * In one transaction: lock the rows [query] names, in key order, and hand each of an `ACTIVE` tenant to [take], which
     * stamps its claim and answers the mail to send, or null. Re-read under the lock, so two ticks never claim one row.
     */
    private fun claim(
        now: Instant, query: String, vararg params: Any, take: (ApplicationErasureEntity, TenantEntity) -> Outgoing?,
    ): List<Outgoing> = runCatching {
        QuarkusTransaction.requiringNew().call {
            ApplicationErasureEntity.find("$query order by tenantId, application", *params)
                .withLock(LockModeType.PESSIMISTIC_WRITE).list()
                .mapNotNull { row ->
                    val tenant = TenantEntity.findById(row.tenantId)?.takeIf { it.status == TenantEntity.ACTIVE }
                        ?: return@mapNotNull null
                    take(row, tenant)?.copy(addresses = members(row.tenantId))
                }
        }
    }.getOrElse {
        log.warn("the application erasure mails could not be claimed at $now; they stay owed")
        emptyList()
    }

    private fun send(outgoing: List<Outgoing>, what: String): Int = outgoing.sumOf { mail ->
        mail.addresses.count { address ->
            runCatching { mailer.mailTo(address, mail.subject, mail.body, what) == DeliveryResult.SENT }.getOrDefault(false)
        }
    }

    /** Every member of the tenant who accepted its terms, at their account address. */
    private fun members(tenantId: String): List<String> =
        MembershipEntity.list("scopeId = ?1 and pendingConsent = false", tenantId)
            .map { it.userId }.distinct().mapNotNull { VeyqUser.findById(it)?.email }

    companion object {
        /** The reminder goes this long before the day; a window this long or shorter has none. */
        val REMINDER_AHEAD: Duration = Duration.ofDays(7)

        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)

        private fun title(application: String) = CapabilityTexts.BY_ID.getValue(application).title

        fun notice(row: ApplicationErasureEntity, space: String): Outgoing {
            val name = title(row.application)
            val day = DATE.format(row.dueAt)
            val byOperator = row.switchedBy == SwitchLevel.INSTANCE.name
            val whoEn = if (byOperator) "The operator of this veyq instance" else "The owner of the space"
            val whoDe = if (byOperator) "Die Person, die diese veyq-Instanz betreibt," else "Die Person, der der Bereich gehört,"
            val onlyEn = if (byOperator) " Only the operator can switch it back on." else ""
            val onlyDe = if (byOperator) " Wieder einschalten kann es nur die Betreiberseite." else ""
            val body = """
                |$whoEn switched ${name.en} off in the space "$space". Everything in it can still be opened and downloaded
                |until $day; then it is erased, unless ${name.en} is switched on again before. Download what you want to
                |keep.$onlyEn
                |
                |—
                |
                |$whoDe hat ${name.de} im Bereich „$space“ abgeschaltet. Alles darin lässt sich bis zum $day öffnen und
                |herunterladen; dann wird es gelöscht, wenn ${name.de} nicht vorher wieder eingeschaltet wird. Lade
                |herunter, was du behalten willst.$onlyDe
            """.trimMargin()
            return Outgoing(
                "veyq: ${name.en} is switched off in a space you belong to / ${name.de} ist in einem Bereich abgeschaltet, dem du angehörst",
                body, emptyList(),
            )
        }

        fun reminder(row: ApplicationErasureEntity, space: String): Outgoing {
            val name = title(row.application)
            val day = DATE.format(row.dueAt)
            val body = """
                |${name.en} is still switched off in the space "$space". On $day everything in it is erased. Download what
                |you want to keep before then.
                |
                |—
                |
                |${name.de} ist im Bereich „$space“ weiter abgeschaltet. Am $day wird alles darin gelöscht. Lade vorher
                |herunter, was du behalten willst.
            """.trimMargin()
            return Outgoing(
                "veyq: ${name.en} in \"$space\" is erased on $day / ${name.de} in „$space“ wird am $day gelöscht",
                body, emptyList(),
            )
        }
    }
}
