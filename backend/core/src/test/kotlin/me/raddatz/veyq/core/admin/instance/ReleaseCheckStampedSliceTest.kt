package me.raddatz.veyq.core.admin.instance

import me.raddatz.veyq.core.adminchannel.givenAdminChannel
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.ensureUser
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The release check on an instance that **is** stamped — the profile [InstanceReleaseSliceTest] already boots — so the
 * scheduled entry point itself, with the instance's own release, is what asks the source; [ReleaseCheckSliceTest]'s
 * unstamped no-request has its positive control here. And the console shows a notice only while its release is newer
 * than the running one.
 */
@QuarkusTest
@TestProfile(InstanceReleaseSliceTest.Stamped::class)
class ReleaseCheckStampedSliceTest {

    @Inject
    lateinit var check: ReleaseCheck

    @Inject
    lateinit var store: ReleaseNoticeStore

    private lateinit var source: FakeReleaseSource

    @BeforeEach
    fun seed() {
        ensureUser(OWNER_ID, "release-stamped-owner@example.com", "SERVER_OWNER")
        source = FakeReleaseSource()
        ReleaseCheckSliceTest.reset()
        store.saveSetting(enabled = true, source = "${source.base}/m")
    }

    @AfterEach
    fun restore() {
        source.close()
        ReleaseCheckSliceTest.reset()
    }

    @Test
    fun theScheduledRunAsksTheSourceWithTheInstancesOwnRelease() {
        source.mirror("/m", listOf("v77.12" to "2027-01-01", "v77.13" to "2027-02-01", "v77.14" to "2027-03-01"))

        check.run()

        assertTrue(source.seen.isNotEmpty(), "a stamped instance asks its source")
        assertTrue(source.seen.none { it.mentions(InstanceReleaseSliceTest.STAMPED_RELEASE) }, "and names no release of its own")
        assertEquals(listOf("77.14"), store.notices().map { it.release.toString() })
    }

    @Test
    fun theConsoleShowsANoticeOnlyWhileItsReleaseIsNewerThanTheRunningOne() {
        // As if found while an older release ran: one is behind the running release now, one is still ahead.
        source.mirror("/m", listOf("v77.12" to "2027-01-01", "v77.13" to "2027-02-01", "v77.14" to "2027-03-01"))
        check.check("77.11", ReleaseCheckSliceTest.NOW)
        assertEquals(setOf("77.12", "77.13", "77.14"), store.notices().map { it.release.toString() }.toSet())

        givenAdminChannel().header("Authorization", bearer(OWNER_ID))
            .`when`().get("/api/instance/release/notices").then().statusCode(200)
            .body("releaseLine", equalTo(true))
            .body("notices.release", equalTo(listOf("77.14")))
    }

    private companion object {
        const val OWNER_ID = "c3e5a7b9-1d2f-4a6c-8e0b-2d4f6a8c0e13"
    }
}
