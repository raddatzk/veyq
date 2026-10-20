package me.raddatz.veyq.core.admin.instance

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI
import java.time.Duration
import java.time.LocalDate

/**
 * The two sources against a real HTTP server (no mock of the client): the GitHub adapter's list and its redirected
 * manifest download, and the refusals every source shares — a status other than 200, a slow answer, an oversized one,
 * JSON of the wrong shape, a manifest that names another release. Each refusal is a [ReleaseSourceException] whose
 * message names the cause, and each has a passing twin in this file on the same route shape.
 */
class ReleaseFeedTest {

    private val source = FakeReleaseSource()

    /**
     * The bound of every read that must succeed — or fail for a reason other than time: generous, because those cases
     * assert what was read, never how fast. It was the slow case's 500 ms (an exchange gives up after twice that), while a
     * passing twin read its local server in 0.27 s cold on a lightly loaded machine: a margin a full suite under heavy
     * load overran ("did not answer within 1000 ms").
     */
    private val http = BoundedGet(Duration.ofSeconds(30))

    @AfterEach
    fun stop() = source.close()

    private fun github() = ReleaseFeed.GitHub(URI.create("${source.base}/repos/raddatzk/veyq/releases?per_page=100"), URI.create("${source.base}/dl/"), http)

    @Test
    fun theGitHubListSkipsDraftsAndKeepsThePublishedDay(): Unit {
        source.json(
            "/repos/raddatzk/veyq/releases",
            """[
              {"tag_name":"v2.1","published_at":"2027-05-20T23:30:00Z","prerelease":false,"draft":false,"assets":[]},
              {"tag_name":"v2.0","published_at":"2027-05-01T08:00:00Z","prerelease":false,"draft":false},
              {"tag_name":"v2.2","published_at":null,"prerelease":false,"draft":true},
              {"tag_name":"v3.0-rc","published_at":"2027-06-01T08:00:00Z","prerelease":true,"draft":false},
              {"tag_name":"v1.9","published_at":"2027-04-01T08:00:00Z","prerelease":true,"draft":false}
            ]""",
        )
        val listed = github().list()
        assertEquals(
            listOf(
                ListedRelease("v2.1", PublishedRelease(ReleaseVersion(2, 1), LocalDate.parse("2027-05-20")), false),
                ListedRelease("v2.0", PublishedRelease(ReleaseVersion(2, 0), LocalDate.parse("2027-05-01")), false),
                ListedRelease("v1.9", PublishedRelease(ReleaseVersion(1, 9), LocalDate.parse("2027-04-01")), true),
            ),
            listed,
            "a draft is skipped, a tag that is no release number is skipped, a prerelease is listed as one",
        )
        assertTrue(source.seen.single().headers.any { it.equals("Accept: application/vnd.github+json", ignoreCase = true) })
    }

    @Test
    fun theGitHubManifestIsFollowedThroughItsRedirect(): Unit {
        source.route("/dl/v2.0/manifest.json", FakeReleaseSource.Route(status = 302, location = "${source.base}/objects/m2"))
        source.json("/objects/m2", FakeReleaseSource.manifest("v2.0", "2027-03-10", unscheduled = true))
        source.route("/dl/v3.0/manifest.json", FakeReleaseSource.Route(status = 302, location = "${source.base}/objects/m3"))
        source.json("/objects/m3", FakeReleaseSource.manifest("v3.0", "2028-05-01", unscheduled = false))

        assertTrue(github().unscheduled("v2.0"))
        assertFalse(github().unscheduled("v3.0"))
    }

    @Test
    fun aMirrorIsReadInTheProducersLayout(): Unit {
        source.mirror("/m", listOf("v1.6" to "2027-03-01", "v2.0" to "2027-05-01"), unscheduledTags = setOf("v2.0"))
        val mirror = ReleaseFeed.Mirror(URI.create("${source.base}/m"), http)
        assertEquals(listOf("v1.6", "v2.0"), mirror.list().map { it.tag })
        assertTrue(mirror.unscheduled("v2.0"))
        assertFalse(mirror.unscheduled("v1.6"))
    }

    @Test
    fun aStatusOtherThan200IsAFailedRead(): Unit {
        source.route("/m/releases.json", FakeReleaseSource.Route(status = 500, body = "{}".toByteArray()))
        val e = assertThrows<ReleaseSourceException> { ReleaseFeed.Mirror(URI.create("${source.base}/m"), http).list() }
        assertTrue(e.message!!.contains("answered 500"), e.message)
    }

    /** The one case about time: a short bound against an answer that comes far later, so even a starved timer fires first. */
    @Test
    fun aSlowAnswerIsAFailedRead(): Unit {
        source.route("/m/releases.json", FakeReleaseSource.Route(body = """{"releases":[]}""".toByteArray(), delayMs = 10_000))
        val impatient = BoundedGet(Duration.ofMillis(500))
        val e = assertThrows<ReleaseSourceException> { ReleaseFeed.Mirror(URI.create("${source.base}/m"), impatient).list() }
        assertTrue(e.message!!.contains("could not be read") || e.message!!.contains("did not answer"), e.message)
    }

    @Test
    fun anAnswerOverItsCapIsAFailedReadAndOneAtItIsNot(): Unit {
        val cap = ReleaseFeed.MAX_LISTING_BYTES
        val padded = { size: Int -> val head = """{"releases":[],"pad":""""; (head + "x".repeat(size - head.length - 2) + "\"}").toByteArray() }
        source.route("/at/releases.json", FakeReleaseSource.Route(body = padded(cap)))
        assertEquals(emptyList<ListedRelease>(), ReleaseFeed.Mirror(URI.create("${source.base}/at"), http).list(), "exactly the cap is read")

        source.route("/over/releases.json", FakeReleaseSource.Route(body = padded(cap + 1)))
        val e = assertThrows<ReleaseSourceException> { ReleaseFeed.Mirror(URI.create("${source.base}/over"), http).list() }
        assertTrue(e.message!!.contains("more than $cap bytes"), e.message)
    }

    @Test
    fun aListOfTheWrongShapeIsAFailedRead(): Unit {
        source.json("/m/releases.json", """{"releases":[{"tag":"v1.6","date":"2027-03-01","prerelease":"no"}]}""")
        val e = assertThrows<ReleaseSourceException> { ReleaseFeed.Mirror(URI.create("${source.base}/m"), http).list() }
        assertTrue(e.message!!.contains("prerelease"), e.message)

        source.json("/n/releases.json", "not json")
        assertThrows<ReleaseSourceException> { ReleaseFeed.Mirror(URI.create("${source.base}/n"), http).list() }
    }

    @Test
    fun aManifestThatNamesAnotherReleaseIsAFailedRead(): Unit {
        source.json("/m/v2.0/manifest.json", FakeReleaseSource.manifest("v2.1", "2027-05-01", unscheduled = true))
        val e = assertThrows<ReleaseSourceException> { ReleaseFeed.Mirror(URI.create("${source.base}/m"), http).unscheduled("v2.0") }
        assertTrue(e.message!!.contains("names another release"), e.message)
    }

    @Test
    fun aTagThatIsNoReleaseNeverReachesAPath(): Unit {
        assertThrows<ReleaseSourceException> { ReleaseFeed.Mirror(URI.create("${source.base}/m"), http).unscheduled("../etc") }
        assertTrue(source.seen.isEmpty(), "refused before any request")
    }
}
