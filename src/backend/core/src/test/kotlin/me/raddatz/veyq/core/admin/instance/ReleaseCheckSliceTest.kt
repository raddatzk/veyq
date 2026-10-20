package me.raddatz.veyq.core.admin.instance

import me.raddatz.veyq.core.adminchannel.givenAdminChannel
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.ensureUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.http.ContentType
import io.restassured.specification.RequestSpecification
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/**
 * The daily release check against a stub source (uc.admin.learn-of-new-releases, ADR 0097), over the real bean, the
 * real store and the real HTTP client: one notice kind per test, a notice found once across two checks, the three
 * cases that make no request at all (unstamped, snapshot, switched off) each beside a control that does, the request
 * naming no release, and a 500, a slow and an oversized answer each leaving the notices and the last success as they
 * were.
 *
 * The suite runs unstamped, so the checks here are driven through [ReleaseCheck.check] with the release they are
 * about; the scheduled `run()` passes the instance's own stamp, and [unstampedRunMakesNoRequest] drives that path.
 * [ReleaseCheckStampedSliceTest] drives it stamped.
 */
@QuarkusTest
class ReleaseCheckSliceTest {

    @Inject
    lateinit var check: ReleaseCheck

    @Inject
    lateinit var store: ReleaseNoticeStore

    private lateinit var source: FakeReleaseSource

    @BeforeEach
    fun seed() {
        ensureUser(OWNER_ID, "release-check-owner@example.com", "SERVER_OWNER")
        ensureUser(MEMBER_ID, "release-check-member@example.com", "MEMBER")
        source = FakeReleaseSource()
        reset()
        store.saveSetting(enabled = true, source = "${source.base}/m")
    }

    @AfterEach
    fun restore() {
        source.close()
        reset()
    }

    @Test
    fun aNewerReleaseOfTheRunningMajorIsNoticed() {
        source.mirror("/m", listOf("v7.2" to "2027-01-01", "v7.3" to "2027-02-01", "v7.4" to "2027-03-01"))

        assertEquals(ReleaseCheckRun.Checked(1), check.check(RUNNING, NOW))

        owner().`when`().get(NOTICES).then().statusCode(200)
            .body("enabled", equalTo(true))
            .body("source", equalTo("${source.base}/m"))
            .body("lastSuccessAt", equalTo("2027-06-01T04:00:00Z"))
            .body("notices.size()", equalTo(1))
            .body("notices[0].kind", equalTo("RELEASE"))
            .body("notices[0].release", equalTo("7.4"))
            .body("notices[0].date", nullValue())
            .body("notices[0].mailedAt", nullValue())
        assertTrue(source.seen.none { it.uri.contains("manifest") }, "a release within the major needs no manifest")
    }

    @Test
    fun aPlannedMajorIsNoticedWithTheDayTheRunningMajorLeavesTheWindow() {
        source.mirror("/m", listOf("v7.3" to "2027-02-01", "v8.0" to "2027-05-01", "v8.1" to "2027-05-20"))

        check.check(RUNNING, NOW)

        val notices = store.notices()
        assertEquals(listOf(NoticeKind.PLANNED_MAJOR), notices.map { it.kind })
        assertEquals("8.0", notices.single().release.toString())
        assertEquals(LocalDate.parse("2029-05-01"), notices.single().date, "ADR 0085 decision 5: the second train after 2027's")
        assertEquals(listOf("/m/releases.json", "/m/v8.0/manifest.json"), source.seen.map { it.uri }, "one manifest: the major's first release")
    }

    @Test
    fun anUnscheduledMajorIsNoticedAsSuch() {
        source.mirror("/m", listOf("v7.3" to "2027-02-01", "v8.0" to "2027-03-10"), unscheduledTags = setOf("v8.0"))

        check.check(RUNNING, NOW)

        val notice = store.notices().single()
        assertEquals(NoticeKind.UNSCHEDULED_MAJOR, notice.kind)
        assertEquals(null, notice.date, "urgent means now, not a date")
    }

    @Test
    fun theWindowReminderFallsDueBeforeTheWindowEnds() {
        source.mirror("/m", listOf("v7.3" to "2027-02-01", "v8.0" to "2027-05-01"))

        check.check(RUNNING, Instant.parse("2029-03-01T04:00:00Z")) // 61 days before 1 May 2029

        val kinds = store.notices().associate { it.kind to it }
        assertEquals(setOf(NoticeKind.PLANNED_MAJOR, NoticeKind.WINDOW_REMINDER), kinds.keys)
        assertEquals(90, kinds.getValue(NoticeKind.WINDOW_REMINDER).daysAhead)
        assertEquals(LocalDate.parse("2029-05-01"), kinds.getValue(NoticeKind.WINDOW_REMINDER).date)
    }

    @Test
    fun aNoticeIsFoundOnceAcrossTwoChecks() {
        source.mirror("/m", listOf("v7.3" to "2027-02-01", "v7.4" to "2027-03-01", "v8.0" to "2027-05-01"))

        assertEquals(ReleaseCheckRun.Checked(2), check.check(RUNNING, NOW))
        val first = store.notices().associateBy { it.id }
        assertEquals(ReleaseCheckRun.Checked(0), check.check(RUNNING, NOW.plusSeconds(86_400)))

        assertEquals(first, store.notices().associateBy { it.id }, "the same rows, their found-at untouched")
        assertEquals(NOW.plusSeconds(86_400), store.setting().lastSuccessAt, "control: the second check did run and succeed")
    }

    @Test
    fun theRequestNamesNoRelease() {
        source.mirror("/m", listOf("v7.3" to "2027-02-01", "v7.4" to "2027-03-01", "v8.0" to "2027-05-01"))

        check.check(RUNNING, NOW)

        assertTrue(source.seen.isNotEmpty(), "control: the check did ask the source")
        val naming = source.seen.filter { it.mentions(RUNNING) }
        assertEquals(emptyList<FakeReleaseSource.Seen>(), naming, "no path, query or header carries the running release")
        assertTrue(source.seen.all { it.method == "GET" })
    }

    @Test
    fun unstampedRunMakesNoRequest() {
        source.mirror("/m", listOf("v7.4" to "2027-03-01"))

        check.run() // the suite's own build carries no release stamp

        assertTrue(source.seen.isEmpty(), "an unstamped build is on no release line: no request")
        check.check(RUNNING, NOW)
        assertTrue(source.seen.isNotEmpty(), "control: the same source, stamped, is asked")
    }

    @Test
    fun aSnapshotMakesNoRequest() {
        source.mirror("/m", listOf("v7.4" to "2027-03-01"))

        assertEquals(ReleaseCheckRun.NoReleaseLine, check.check("$RUNNING-SNAPSHOT", NOW))

        assertTrue(source.seen.isEmpty(), "a snapshot is on no release line: no request")
        assertEquals(null, store.setting().lastSuccessAt)
    }

    @Test
    fun switchedOffMakesNoRequestAndOnAgainDoes() {
        source.mirror("/m", listOf("v7.4" to "2027-03-01"))
        owner().contentType(ContentType.JSON).body("""{"enabled":false,"source":"${source.base}/m"}""")
            .`when`().put(CHECK).then().statusCode(200).body("enabled", equalTo(false))

        assertEquals(ReleaseCheckRun.Off, check.check(RUNNING, NOW))
        assertTrue(source.seen.isEmpty(), "switched off: no request")

        owner().contentType(ContentType.JSON).body("""{"enabled":true,"source":"${source.base}/m"}""")
            .`when`().put(CHECK).then().statusCode(200).body("enabled", equalTo(true))
        assertEquals(ReleaseCheckRun.Checked(1), check.check(RUNNING, NOW))
        assertTrue(source.seen.isNotEmpty(), "control: on again, the source is asked")
    }

    @Test
    fun aServerErrorLeavesTheNoticesAndTheLastSuccess() =
        failedCheckLeavesEverything(FakeReleaseSource.Route(status = 500, body = NEWER_MAJOR.toByteArray()))

    @Test
    fun aSlowSourceLeavesTheNoticesAndTheLastSuccess() =
        // veyq.release-check.timeout is PT1S in %test, so the exchange gives up after two seconds.
        failedCheckLeavesEverything(FakeReleaseSource.Route(body = NEWER_MAJOR.toByteArray(), delayMs = 3_000))

    @Test
    fun anOversizedAnswerLeavesTheNoticesAndTheLastSuccess() {
        val list = NEWER_MAJOR.removeSuffix("}") + ""","pad":""""
        val body = list + "x".repeat(ReleaseFeed.MAX_LISTING_BYTES + 1 - list.length - 2) + "\"}"
        failedCheckLeavesEverything(FakeReleaseSource.Route(body = body.toByteArray()))
    }

    @Test
    fun aMalformedAnswerLeavesTheNoticesAndTheLastSuccess() =
        failedCheckLeavesEverything(FakeReleaseSource.Route(body = """{"releases":[{"tag":"v9.0"}]}""".toByteArray()))

    /**
     * Each failing answer would, read, have been a new notice — a well-formed list naming a newer major whose manifest
     * is served (the malformed one aside) — so only the refusal under test stands between it and one.
     */
    private fun failedCheckLeavesEverything(failing: FakeReleaseSource.Route) {
        source.mirror("/m", listOf("v7.3" to "2027-02-01", "v7.4" to "2027-03-01"))
        source.json("/m/v9.0/manifest.json", FakeReleaseSource.manifest("v9.0", "2027-05-01", unscheduled = false))
        assertEquals(ReleaseCheckRun.Checked(1), check.check(RUNNING, NOW))
        val before = store.notices()

        // The source now lists a newer major too — and fails while saying so.
        source.route("/m/releases.json", failing)
        val run = check.check(RUNNING, NOW.plusSeconds(86_400))

        assertTrue(run is ReleaseCheckRun.Failed, "the check failed: $run")
        assertEquals(before, store.notices(), "no notice was added, changed or lost")
        assertEquals(NOW, store.setting().lastSuccessAt, "the last success is still the first check's")
    }

    @Test
    fun aSourceMustBeAnHttpUrl() {
        for (bad in listOf("ftp://mirror.example.com", "not a url", "https://user:pw@mirror.example.com", "https://mirror.example.com/?q=1")) {
            owner().contentType(ContentType.JSON).body("""{"enabled":true,"source":"$bad"}""")
                .`when`().put(CHECK).then().statusCode(400).body("code", equalTo("invalid_request"))
        }
        assertEquals("${source.base}/m", store.setting().source, "nothing was stored")
        owner().contentType(ContentType.JSON).body("""{"enabled":true,"source":null}""")
            .`when`().put(CHECK).then().statusCode(200).body("source", nullValue())
        owner().contentType(ContentType.JSON).body("""{"source":null}""")
            .`when`().put(CHECK).then().statusCode(400) // a missing switch is no answer
    }

    @Test
    fun aMemberIsNotTheServerOwnerAndIsRefused() {
        givenAdminChannel().header("Authorization", bearer(MEMBER_ID)).`when`().get(NOTICES).then().statusCode(403)
        givenAdminChannel().header("Authorization", bearer(MEMBER_ID)).contentType(ContentType.JSON)
            .body("""{"enabled":false}""").`when`().put(CHECK).then().statusCode(403)
        assertTrue(store.setting().enabled, "the member's write changed nothing")
    }

    @Test
    fun aNewInstanceChecksTheGitHubReleasesByDefault() {
        reset()
        owner().`when`().get(NOTICES).then().statusCode(200)
            .body("enabled", equalTo(true))
            .body("source", nullValue())
            .body("releaseLine", equalTo(false)) // the suite is unstamped
            .body("lastSuccessAt", nullValue())
    }

    private fun owner(): RequestSpecification = givenAdminChannel().header("Authorization", bearer(OWNER_ID))

    companion object {
        const val RUNNING = "7.3"

        /** A well-formed list naming a newer major. */
        private const val NEWER_MAJOR = """{"releases":[{"tag":"v9.0","date":"2027-05-01","prerelease":false,"manifest":"v9.0/manifest.json"}]}"""
        val NOW: Instant = Instant.parse("2027-06-01T04:00:00Z")
        private const val NOTICES = "/api/instance/release/notices"
        private const val CHECK = "/api/instance/release/check"
        private const val OWNER_ID = "4a7c1f0e-6d2b-4e8a-9c3f-1b5d7e9a2c40"
        private const val MEMBER_ID = "9e2d4b6a-8c1f-4a3e-b5d7-0f2a4c6e8b19"

        /** The migration's state: on, the GitHub source, never succeeded, nothing found. */
        fun reset() {
            QuarkusTransaction.requiringNew().run {
                ReleaseNoticeEntity.deleteAll()
                ReleaseCheckEntity.findById(ReleaseCheckEntity.SINGLETON_ID)?.apply {
                    enabled = true
                    source = null
                    lastSuccessAt = null
                }
            }
        }
    }
}
