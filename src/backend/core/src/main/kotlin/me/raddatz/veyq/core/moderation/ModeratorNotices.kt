package me.raddatz.veyq.core.moderation

import me.raddatz.veyq.core.admin.instance.CodeConfirmation
import me.raddatz.veyq.core.admin.instance.InstanceNoticeMailer
import me.raddatz.veyq.core.admin.operator.OperatorDetailsStore
import me.raddatz.veyq.core.generated.model.DeliveryResult
import me.raddatz.veyq.core.identity.ModeratorEntity
import me.raddatz.veyq.core.identity.VeyqUser
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import java.util.Locale

/**
 * Moderation notices (ADR 0097 decisions 3 and 6, ADR 0099 § 7, ADR 0093 § 2): the instance mail reaches every accepted
 * moderator whose address is **confirmed** — by the code typed into the moderation surface — and the instance contact's
 * notification address once the Server Owner confirmed it, and nobody else. The recipients are read from the roster, each
 * account's own address and the operator details at the moment of sending, never from a request; the text names no case
 * content and no link (ADR 0093 § 2: category and "new case" only — `CaseNotices` writes it). Where the moderation page is
 * goes in as plain text ([PAGE_EN], [PAGE_DE]): a path and a settings entry, never a host — a mail client links a URL.
 */
@ApplicationScoped
class ModeratorNotices(
    private val mailer: InstanceNoticeMailer,
    private val operator: OperatorDetailsStore,
) {

    /** Mail [subject]/[body] to every confirmed moderator. Answers how many went out. */
    fun mailConfirmedModerators(subject: String, body: String): Int =
        recipients(withContact = false).count { mailer.mailTo(it, subject, body, "a moderation notice") == DeliveryResult.SENT }

    /**
     * Mail [subject]/[body] to every confirmed moderator and the instance contact's confirmed notification address, each
     * address once. Answers how many went out.
     */
    fun mailModerationNotice(subject: String, body: String): Int =
        recipients(withContact = true).count { mailer.mailTo(it, subject, body, "a moderation notice") == DeliveryResult.SENT }

    companion object {
        /** Where a moderator opens the moderation page, as every moderation notice names it: plain text, no host, no link (owner 2026-10-22). */
        const val PAGE_EN = "Settings → Moderation in veyq, or /moderation on the address of any space you can sign in to"
        const val PAGE_DE = "Einstellungen → Moderation in veyq, oder /moderation auf der Adresse eines Bereichs, in dem du dich anmelden kannst"
    }

    private fun recipients(withContact: Boolean): List<String> = QuarkusTransaction.requiringNew().call {
        val moderators = ModeratorEntity.list("removedAt is null and acceptedAt is not null").mapNotNull { m ->
            VeyqUser.findById(m.accountId)?.email?.takeIf { ModeratorAddress.confirmed(m, it) }
        }
        val contact = if (!withContact) null else operator.find()?.let { row ->
            row.values.notificationEmail?.takeIf { CodeConfirmation.confirmed(row.confirmedAt, row.confirmedAddress, it) }
        }
        (moderators + listOfNotNull(contact)).distinctBy { it.lowercase(Locale.ROOT) }
    }
}
