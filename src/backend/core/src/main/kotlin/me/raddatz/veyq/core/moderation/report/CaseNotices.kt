package me.raddatz.veyq.core.moderation.report

import me.raddatz.veyq.core.moderation.ModeratorNotices
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import java.time.Duration
import java.time.Instant

/**
 * The "new case" mail (ADR 0093 § 2, ADR 0099 § 7; owner decision Q7): every accepted moderator with a confirmed address
 * and the instance contact's confirmed notification address learn the category and "new case" — never a key, a link, an
 * id or anything of the report. The first three categories go at once ([mailUrgent], right after the report's commit and
 * again by the hourly cleanup until one goes out); the other two in **at most one mail a day** ([mailBundle], the hourly
 * cleanup), which counts the new cases per category. A day that already had an urgent mail still gets its bundle.
 *
 * Claimed before the send, released when nothing went out: the cases' `notice_due` is cleared under their row locks in
 * one short transaction, the mail is sent outside it, and a send that reached nobody (no confirmed address, no instance
 * mail) puts the claim back — so a later run retries, and no case is mailed twice.
 */
@ApplicationScoped
class CaseNotices(private val notices: ModeratorNotices) {

    /** Mail every case that owes an urgent notice. Answers how many cases it named. */
    fun mailUrgent(now: Instant = Instant.now()): Int = mail(ModerationCaseEntity.NOTICE_URGENT, now)

    /** Mail the day's bundle, if one is owed and the last went out a day or more ago. Answers how many cases it named. */
    fun mailBundle(now: Instant = Instant.now()): Int {
        val last = QuarkusTransaction.requiringNew().call {
            ModerationCaseEntity.find("noticeKind = ?1 order by noticedAt desc", ModerationCaseEntity.NOTICE_BUNDLE).firstResult()?.noticedAt
        }
        if (last != null && last.plus(DAY).isAfter(now)) return 0
        return mail(ModerationCaseEntity.NOTICE_BUNDLE, now)
    }

    private fun mail(kind: String, now: Instant): Int {
        val claimed = QuarkusTransaction.requiringNew().call {
            ModerationCaseEntity.find("noticeDue", kind).withLock(LockModeType.PESSIMISTIC_WRITE).list().onEach {
                it.noticeDue = null
                it.noticedAt = now
                it.noticeKind = kind
            }.map { it.id to ReportCategory.valueOf(it.category) }
        }
        if (claimed.isEmpty()) return 0
        val counts = claimed.groupingBy { it.second }.eachCount()
        val sent = if (kind == ModerationCaseEntity.NOTICE_URGENT) {
            notices.mailModerationNotice(URGENT_SUBJECT, body(counts))
        } else {
            notices.mailModerationNotice(BUNDLE_SUBJECT, body(counts))
        }
        if (sent == 0) {
            QuarkusTransaction.requiringNew().run {
                // Only this claim's cases, whose due mark nothing else sets while it is cleared (an attach raises only a
                // BUNDLE mark it finds).
                ModerationCaseEntity.update(
                    "noticeDue = ?1, noticedAt = null, noticeKind = null where id in ?2 and noticeDue is null",
                    kind, claimed.map { it.first },
                )
            }
        }
        return claimed.size
    }

    companion object {
        private val DAY: Duration = Duration.ofDays(1)

        const val URGENT_SUBJECT = "veyq: a new moderation case / ein neuer Moderationsfall"
        const val BUNDLE_SUBJECT = "veyq: new moderation cases / neue Moderationsfälle"

        private val EN = mapOf(
            ReportCategory.CHILD_SEXUAL_ABUSE to "child sexual abuse material",
            ReportCategory.TERRORIST_CONTENT to "terrorist content",
            ReportCategory.THREAT_TO_LIFE to "a threat to life or safety",
            ReportCategory.ILLEGAL_CONTENT to "other illegal content",
            ReportCategory.TERMS_BREACH to "a breach of the terms",
        )
        private val DE = mapOf(
            ReportCategory.CHILD_SEXUAL_ABUSE to "Darstellung sexuellen Missbrauchs von Kindern",
            ReportCategory.TERRORIST_CONTENT to "terroristische Inhalte",
            ReportCategory.THREAT_TO_LIFE to "Gefahr für Leben oder Sicherheit",
            ReportCategory.ILLEGAL_CONTENT to "andere rechtswidrige Inhalte",
            ReportCategory.TERMS_BREACH to "Verstoß gegen die Nutzungsbedingungen",
        )

        /** The mail's text: the new cases per category, most urgent first, and where the page is — nothing else (ADR 0093 § 2). */
        fun body(counts: Map<ReportCategory, Int>): String {
            val ordered = ReportCategory.entries.filter { (counts[it] ?: 0) > 0 }
            val en = ordered.joinToString("\n") { "  ${counts.getValue(it)} × ${EN.getValue(it)}" }
            val de = ordered.joinToString("\n") { "  ${counts.getValue(it)} × ${DE.getValue(it)}" }
            return """
                |New moderation cases were reported on this veyq server:
                |$en
                |
                |Open the moderation page to see them: ${ModeratorNotices.PAGE_EN}.
                |
                |—
                |
                |Auf diesem veyq-Server wurden neue Moderationsfälle gemeldet:
                |$de
                |
                |Öffne die Moderationsseite, um sie zu sehen: ${ModeratorNotices.PAGE_DE}.
            """.trimMargin()
        }
    }
}
