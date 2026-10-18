package me.raddatz.veyq.core.moderation.report

import me.raddatz.veyq.core.moderation.ModeratorNotices
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import java.time.Duration
import java.time.Instant

/**
 * The reminder of an undecided case (ADR 0093 § 11, owner 2026-10-18; DSA Art. 16(6) "timely"): **no case closes
 * itself** — that would be a decision no person took — so while a case is open its moderators are reminded, at
 * [THRESHOLDS] (30, 75 and 90 days) after its first report. Every accepted moderator with a confirmed address and the
 * instance contact's confirmed notification address get one mail per run of the hourly cleanup that finds a reminder
 * due, naming each case only by its category and its age in days — never a key, a link, an id or anything of a report,
 * as the new-case mail ([CaseNotices]). The queue marks a case older than [OVERDUE] as overdue (`CaseViews`).
 *
 * Each case counts the reminders it got (`reminders_sent`, 0 to 3), so each threshold reminds once; a case first seen
 * past several thresholds is reminded once, at the latest. Claimed before the send, released when nothing went out
 * (no confirmed address, no instance mail), so a later run retries — [CaseNotices]' shape.
 */
@ApplicationScoped
class CaseReminders(private val notices: ModeratorNotices) {

    /** Mail every reminder due at [now]. Answers how many cases it named. */
    fun remind(now: Instant = Instant.now()): Int {
        val claimed = QuarkusTransaction.requiringNew().call {
            ModerationCaseEntity.find("state = ?1 and remindersSent < ?2", CaseState.OPEN.name, THRESHOLDS.size)
                .withLock(LockModeType.PESSIMISTIC_WRITE).list()
                .mapNotNull { case ->
                    val first = firstReportOf(case.id) ?: return@mapNotNull null
                    val days = Duration.between(first, now).toDays()
                    val due = THRESHOLDS.count { days >= it.toDays() }
                    if (due <= case.remindersSent) return@mapNotNull null
                    val before = case.remindersSent
                    case.remindersSent = due
                    Claimed(case.id, before, ReportCategory.valueOf(case.category), days)
                }
        }
        if (claimed.isEmpty()) return 0
        if (notices.mailModerationNotice(SUBJECT, body(claimed.map { it.category to it.days })) == 0) {
            QuarkusTransaction.requiringNew().run {
                claimed.forEach { c -> ModerationCaseEntity.update("remindersSent = ?1 where id = ?2", c.before, c.caseId) }
            }
        }
        return claimed.size
    }

    private data class Claimed(val caseId: String, val before: Int, val category: ReportCategory, val days: Long)

    companion object {
        /** When an undecided case is reminded of, after its first report. */
        val THRESHOLDS: List<Duration> = listOf(Duration.ofDays(30), Duration.ofDays(75), Duration.ofDays(90))

        /** After how long an undecided case is overdue in the queue. */
        val OVERDUE: Duration = Duration.ofDays(90)

        const val SUBJECT = "veyq: moderation cases wait for a decision / Moderationsfälle warten auf eine Entscheidung"

        /** When [caseId]'s first report arrived, in the caller's transaction; null for a case with none (an order's). */
        fun firstReportOf(caseId: String): Instant? {
            val reportIds = ReportShareEntity.list("caseId", caseId).map { it.reportId }.distinct()
            if (reportIds.isEmpty()) return null
            return ModerationReportEntity.list("id in ?1", reportIds).minOfOrNull { it.receivedAt }
        }

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

        /** The mail's text: each case's category and age in days, the oldest first — nothing else (ADR 0093 § 2). */
        fun body(cases: List<Pair<ReportCategory, Long>>): String {
            val ordered = cases.sortedByDescending { it.second }
            val en = ordered.joinToString("\n") { (c, d) -> "  ${EN.getValue(c)}, reported $d days ago" }
            val de = ordered.joinToString("\n") { (c, d) -> "  ${DE.getValue(c)}, gemeldet vor $d Tagen" }
            return """
                |These moderation cases on this veyq instance still wait for a decision:
                |$en
                |
                |Open the moderation page on any space you can sign in to, to decide them. A case is never closed on its own.
                |
                |—
                |
                |Diese Moderationsfälle auf dieser veyq-Instanz warten noch auf eine Entscheidung:
                |$de
                |
                |Öffne die Moderationsseite in einem beliebigen Bereich, in dem du dich anmelden kannst, um sie zu entscheiden.
                |Ein Fall wird nie von selbst geschlossen.
            """.trimMargin()
        }
    }
}
