package me.raddatz.veyq.core.moderation

import me.raddatz.veyq.core.admin.instance.InstanceNoticeMailer
import me.raddatz.veyq.core.generated.model.DeliveryResult
import me.raddatz.veyq.core.identity.ModeratorEntity
import me.raddatz.veyq.core.identity.VeyqUser
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped

/**
 * Moderation notices to the moderators (ADR 0097 decisions 3 and 6, ADR 0099 § 7): the instance mail reaches every
 * accepted moderator whose address is **confirmed** — by the code typed into the moderation surface — and nobody else.
 * No notice kind uses it yet: a new case is `cr-report-and-hold`'s. The recipients are read from the roster and each
 * account's own address at the moment of sending, never from a request; the text names no case content and no link
 * (ADR 0093 § 2: category and "new case" only).
 */
@ApplicationScoped
class ModeratorNotices(private val mailer: InstanceNoticeMailer) {

    /** Mail [subject]/[body] to every confirmed moderator. Answers how many went out. */
    fun mailConfirmedModerators(subject: String, body: String): Int {
        val recipients = QuarkusTransaction.requiringNew().call {
            ModeratorEntity.list("removedAt is null and acceptedAt is not null").mapNotNull { m ->
                VeyqUser.findById(m.accountId)?.email?.takeIf { ModeratorAddress.confirmed(m, it) }
            }
        }
        return recipients.count { mailer.mailTo(it, subject, body, "a moderation notice") == DeliveryResult.SENT }
    }
}
