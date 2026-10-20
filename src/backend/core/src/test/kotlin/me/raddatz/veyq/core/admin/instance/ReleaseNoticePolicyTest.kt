package me.raddatz.veyq.core.admin.instance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * The comparison of ADR 0097 decision 2, pure: which published releases are which notice for a running release, and
 * the window date ADR 0085 decision 5 gives a planned major — with fixed dates, so the rule is pinned, not today.
 */
class ReleaseNoticePolicyTest {

    private fun v(text: String) = ReleaseVersion.parse(text)!!
    private fun r(text: String, date: String) = PublishedRelease(v(text), LocalDate.parse(date))
    private val running = v("1.5")
    private val today = LocalDate.parse("2027-06-01")

    @Test
    fun onlyAPublishedReleaseParses() {
        assertEquals(ReleaseVersion(1, 5), ReleaseVersion.parse("1.5"))
        assertNull(ReleaseVersion.parse("1.5-SNAPSHOT"), "a snapshot is on no release line")
        assertNull(ReleaseVersion.parse(null), "an unstamped build has no release")
        assertNull(ReleaseVersion.parse(""))
        assertNull(ReleaseVersion.parse("v1.5"), "a tag is not a release number")
        assertNull(ReleaseVersion.parse("01.5"))
    }

    @Test
    fun everyNewerReleaseOfTheRunningMajorIsANoticeAndNoOlderOneIs() {
        val found = ReleaseNoticePolicy.notices(
            running, listOf(r("1.4", "2027-01-01"), r("1.5", "2027-02-01"), r("1.6", "2027-03-01"), r("1.7", "2027-04-01")),
            emptySet(), today,
        )
        assertEquals(
            listOf(FoundNotice(NoticeKind.RELEASE, v("1.6"), null, null), FoundNotice(NoticeKind.RELEASE, v("1.7"), null, null)),
            found,
        )
    }

    @Test
    fun aPlannedMajorCarriesTheDayTheRunningMajorLeavesTheWindow() {
        // Major 2 leaves on the 2027 train: major 1 is non-current from then, and stays in a store-shipped client's
        // window until the second train after it — 1 May 2029 (ADR 0085 decisions 3 and 5).
        val found = ReleaseNoticePolicy.notices(running, listOf(r("2.0", "2027-05-01"), r("2.1", "2027-05-20")), emptySet(), today)
        assertEquals(listOf(FoundNotice(NoticeKind.PLANNED_MAJOR, v("2.0"), LocalDate.parse("2029-05-01"), null)), found)
    }

    @Test
    fun aTrainThatLeftLateStillEndsTheWindowOnTheFirstOfMay() {
        assertEquals(LocalDate.parse("2029-05-01"), ReleaseNoticePolicy.windowEnd(r("2.0", "2027-05-04")))
    }

    @Test
    fun anUnscheduledMajorIsUrgentAndDoesNotMoveTheWindow() {
        // Major 2 was an emergency in March; major 3 is the train. The window is the train's, not the emergency's.
        val found = ReleaseNoticePolicy.notices(
            running, listOf(r("2.0", "2027-03-10"), r("3.0", "2028-05-01")), unscheduledMajors = setOf(2), today,
        )
        assertEquals(
            listOf(
                FoundNotice(NoticeKind.UNSCHEDULED_MAJOR, v("2.0"), null, null),
                FoundNotice(NoticeKind.PLANNED_MAJOR, v("3.0"), LocalDate.parse("2030-05-01"), null),
            ),
            found,
        )
    }

    @Test
    fun aMajorIsOneNoticeOnItsFirstRelease() {
        val majors = ReleaseNoticePolicy.firstReleasesOfNewerMajors(running, listOf(r("2.3", "2027-05-01"), r("2.4", "2027-06-01"), r("1.9", "2027-04-01")))
        assertEquals(listOf(r("2.3", "2027-05-01")), majors)
    }

    @Test
    fun theWindowRemindersFallDueNinetyAndThirtyDaysAheadOneAtATime() {
        val releases = listOf(r("2.0", "2027-05-01"))
        val end = LocalDate.parse("2029-05-01")
        fun reminderOn(day: String) =
            ReleaseNoticePolicy.notices(running, releases, emptySet(), LocalDate.parse(day)).filter { it.kind == NoticeKind.WINDOW_REMINDER }

        assertEquals(emptyList<FoundNotice>(), reminderOn("2029-01-30"), "91 days ahead: not yet")
        assertEquals(listOf(FoundNotice(NoticeKind.WINDOW_REMINDER, v("2.0"), end, 90)), reminderOn("2029-01-31"), "90 days ahead")
        assertEquals(listOf(FoundNotice(NoticeKind.WINDOW_REMINDER, v("2.0"), end, 90)), reminderOn("2029-03-31"), "31 days ahead")
        assertEquals(listOf(FoundNotice(NoticeKind.WINDOW_REMINDER, v("2.0"), end, 30)), reminderOn("2029-04-01"), "30 days ahead: the nearer one only")
        assertEquals(emptyList<FoundNotice>(), reminderOn("2029-05-01"), "the window has closed: nothing left to remind of")
    }

    @Test
    fun noNewerReleaseIsNoNotice() {
        assertEquals(emptyList<FoundNotice>(), ReleaseNoticePolicy.notices(running, listOf(r("1.5", "2027-02-01")), emptySet(), today))
    }

    @Test
    fun aNoticesKeyIsItsIdentityAcrossChecks() {
        assertEquals("PLANNED_MAJOR|2.0|2029-05-01|-", FoundNotice(NoticeKind.PLANNED_MAJOR, v("2.0"), LocalDate.parse("2029-05-01"), null).key)
        assertEquals("WINDOW_REMINDER|2.0|2029-05-01|30", FoundNotice(NoticeKind.WINDOW_REMINDER, v("2.0"), LocalDate.parse("2029-05-01"), 30).key)
    }
}
