package me.raddatz.veyq.core.moderation

import me.raddatz.veyq.core.admin.instance.InstanceNoticeMailer
import me.raddatz.veyq.core.generated.model.DeliveryResult
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import java.time.Instant

/**
 * **Rotation due** — the third kind of instance notice (ADR 0097 decision 6; owner decision Q3 (a)): after a holder of
 * the current epoch steps down, is removed or rotates their identity, reports keep sealing to that epoch until the
 * Server Owner mints the next one, and the Server Owner is told once per epoch — in admin-web (`rotationDueSince`) and
 * by one instance mail. The epoch's `rotation_due_mailed_at` is claimed before the send and released when it fails, so a
 * later run retries and no second mail goes out; [ModerationPurge] runs it hourly, and a step-down right away.
 */
@ApplicationScoped
class RotationDueNotice(private val mailer: InstanceNoticeMailer) {

    /** Mail the notice if one is due and not yet mailed. Answers whether it went out now. */
    fun mailIfDue(now: Instant = Instant.now()): Boolean {
        val epoch = QuarkusTransaction.requiringNew().call {
            val top = ModerationEpochEntity.find("order by epoch desc").firstResult() ?: return@call null
            val current = ModerationEpochEntity.findById(top.epoch, LockModeType.PESSIMISTIC_WRITE) ?: return@call null
            if (current.rotationDueAt == null || current.rotationDueMailedAt != null) return@call null
            current.rotationDueMailedAt = now
            current.epoch
        } ?: return false
        val result = mailer.mailServerOwner(SUBJECT, body(epoch), "a rotation-due notice", now)
        if (result != DeliveryResult.SENT) {
            QuarkusTransaction.requiringNew().run {
                ModerationEpochEntity.update("rotationDueMailedAt = null where epoch = ?1", epoch)
            }
        }
        return result == DeliveryResult.SENT
    }

    companion object {
        const val SUBJECT = "veyq: rotate the moderation key / Moderationsschlüssel erneuern"

        fun body(epoch: Int) = """
            A moderator who held the current moderation key (epoch $epoch) stepped down, was removed or replaced their
            account keys. Reports are still sealed to that key until you rotate it: open the admin console, Server →
            Moderation, and choose "Rotate the key".

            —

            Eine Person, die den aktuellen Moderationsschlüssel (Epoche $epoch) hielt, ist zurückgetreten, wurde entfernt
            oder hat die Schlüssel ihres Kontos ersetzt. Meldungen werden weiter für diesen Schlüssel versiegelt, bis du
            ihn erneuerst: Öffne die Admin-Konsole, Server → Moderation, und wähle „Schlüssel erneuern“.
        """.trimIndent()
    }
}
