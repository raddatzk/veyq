package me.raddatz.veyq.core.admin.instance

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * A release number, `<major>.<minor>` (ADR 0057 §1). Only a published release parses: a snapshot
 * (`<major>.<minor>-SNAPSHOT`) and an unstamped build are on no release line, so they have nothing to compare.
 */
data class ReleaseVersion(val major: Int, val minor: Int) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int = compareValuesBy(this, other, { it.major }, { it.minor })

    override fun toString(): String = "$major.$minor"

    companion object {
        private val RELEASE = Regex("""^(0|[1-9][0-9]{0,5})\.(0|[1-9][0-9]{0,8})$""")

        /** The release [text] names, or null for anything else — a snapshot, a blank, a tag (`v1.2`). */
        fun parse(text: String?): ReleaseVersion? =
            text?.let { RELEASE.matchEntire(it) }?.let { ReleaseVersion(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
    }
}

/** One published release as the source lists it: its number and the UTC day it was published. */
data class PublishedRelease(val version: ReleaseVersion, val date: LocalDate)

/** The rows of ADR 0097 decision 2. */
enum class NoticeKind { RELEASE, PLANNED_MAJOR, UNSCHEDULED_MAJOR, WINDOW_REMINDER }

/**
 * One thing the check found. [date] is the running major's window end for a planned major and a reminder, null
 * otherwise; [daysAhead] is a reminder's lead. [key] is its identity: a second check that finds it again finds the
 * same key, which is what makes a notice found — and mailed — once.
 */
data class FoundNotice(val kind: NoticeKind, val release: ReleaseVersion, val date: LocalDate?, val daysAhead: Int?) {
    val key: String get() = "$kind|$release|${date ?: "-"}|${daysAhead ?: "-"}"
}

/**
 * What a list of published releases means for an instance running [ReleaseVersion] (ADR 0097 decision 2) — the
 * whole comparison, local and pure: no request, no store, no clock of its own.
 *
 * - every newer release **within the running major** is a [NoticeKind.RELEASE];
 * - every newer **major** is one notice, on its first release: [NoticeKind.UNSCHEDULED_MAJOR] when its manifest marks it
 *   unscheduled (ADR 0085 decision 4), else [NoticeKind.PLANNED_MAJOR] with the day the running major leaves a
 *   store-shipped client's window;
 * - the window's end, 90 and 30 days ahead, is a [NoticeKind.WINDOW_REMINDER] — only the nearer one when both are due,
 *   so an instance that first looks 20 days before the end gets one reminder, not two at once.
 *
 * **The window** (ADR 0085 decisions 3 and 5): a client speaks every major since the train before last, so the
 * running major stays in it until the second train after the one that made it non-current. That train is the first
 * **planned** major above the running one — an unscheduled major widens the window, it does not move it — and trains
 * leave on 1 May, so the window ends on 1 May two years after that train's year. Until a store-shipped client exists
 * this is the day the window *would* close (uc.admin.learn-of-new-releases).
 */
object ReleaseNoticePolicy {

    /** The reminders' leads, in days before the window's end, farthest first. */
    val REMINDER_LEADS = listOf(90, 30)

    /** The first release of every major above [running]'s — the releases whose manifest says planned or unscheduled. */
    fun firstReleasesOfNewerMajors(running: ReleaseVersion, releases: List<PublishedRelease>): List<PublishedRelease> =
        releases.filter { it.version.major > running.major }
            .groupBy { it.version.major }
            .map { (_, ofMajor) -> ofMajor.minBy { it.version } }
            .sortedBy { it.version }

    /** The day the running major leaves the window, given the release that made it non-current. */
    fun windowEnd(train: PublishedRelease): LocalDate = LocalDate.of(train.date.year + 2, 5, 1)

    /**
     * The notices [releases] mean for [running] on [today]. [unscheduledMajors] holds every major whose first release's
     * manifest marks it unscheduled; a major missing from it is planned.
     */
    fun notices(
        running: ReleaseVersion,
        releases: List<PublishedRelease>,
        unscheduledMajors: Set<Int>,
        today: LocalDate,
    ): List<FoundNotice> {
        val sameMajor = releases.filter { it.version.major == running.major && it.version > running }
            .map { FoundNotice(NoticeKind.RELEASE, it.version, null, null) }
        val majors = firstReleasesOfNewerMajors(running, releases)
        val train = majors.firstOrNull { it.version.major !in unscheduledMajors }
        val end = train?.let(::windowEnd)
        val majorNotices = majors.map {
            if (it.version.major in unscheduledMajors) FoundNotice(NoticeKind.UNSCHEDULED_MAJOR, it.version, null, null)
            else FoundNotice(NoticeKind.PLANNED_MAJOR, it.version, end, null)
        }
        val reminder = if (train == null || end == null || !today.isBefore(end)) null else {
            val daysLeft = ChronoUnit.DAYS.between(today, end)
            REMINDER_LEADS.lastOrNull { daysLeft <= it }?.let { FoundNotice(NoticeKind.WINDOW_REMINDER, train.version, end, it) }
        }
        return (sameMajor + majorNotices + listOfNotNull(reminder)).distinctBy { it.key }
    }
}
