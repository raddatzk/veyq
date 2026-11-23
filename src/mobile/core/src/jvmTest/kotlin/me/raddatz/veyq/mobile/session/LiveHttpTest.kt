package me.raddatz.veyq.mobile.session

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The live runs' one meeting with the instance's per-IP budget (inbox #1191, [LiveHttp]): a `429` naming a `Retry-After`
 * is sent again after that wait, again while the call's waits stay within the bound; nothing else is retried. And every
 * live case builds its client there, never OkHttp on its own — the one place, not fifty call sites.
 */
class LiveHttpTest {
    /** A played server answering [answers] in turn (status and `Retry-After`, the last one from then on), counting the calls. */
    private class Played(vararg val answers: Pair<HttpStatusCode, String?>, bound: Long = LiveHttp.MAX_WAIT_SECONDS) {
        var calls = 0
        val client = HttpClient(MockEngine {
            val (status, retryAfter) = answers[minOf(calls++, answers.size - 1)]
            respond("", status, retryAfter?.let { headersOf(HttpHeaders.RetryAfter, it) } ?: headersOf())
        }) { install(LiveHttp.budgetWait) { maxWaitSeconds = bound } }
    }

    @Test
    fun aTurnedBackCallIsSentAgainAfterItsRetryAfter() = runBlocking {
        val played = Played(HttpStatusCode.TooManyRequests to "1", HttpStatusCode.OK to null)
        val started = System.nanoTime()
        val status = played.client.post("http://played/api/login/challenge").status
        val waited = (System.nanoTime() - started) / 1_000_000
        assertEquals(HttpStatusCode.OK, status)
        assertEquals(2, played.calls)
        assertTrue(waited >= 1_000, "sent again before its Retry-After: after $waited ms")
    }

    /** A live case runs in `runTest`, whose virtual time skips a `delay`: the wait is the wall clock's there too. */
    @Test
    fun theWaitIsTheWallClocksInsideRunTest() = kotlinx.coroutines.test.runTest {
        val played = Played(HttpStatusCode.TooManyRequests to "1", HttpStatusCode.OK to null)
        val started = System.nanoTime()
        assertEquals(HttpStatusCode.OK, played.client.post("http://played/api/login/challenge").status)
        val waited = (System.nanoTime() - started) / 1_000_000
        assertTrue(waited >= 1_000, "the wait was skipped by the test's virtual time: $waited ms")
    }

    @Test
    fun againWhileTheWaitsStayWithinTheBound() = runBlocking {
        // a drained bucket answers 429 again: waited out again until the next wait would pass the bound (3 s: three of 1 s)
        val drained = Played(HttpStatusCode.TooManyRequests to "0", bound = 3)
        assertEquals(HttpStatusCode.TooManyRequests, drained.client.post("http://played/api/login/verify").status)
        assertEquals(4, drained.calls)
        val freed = Played(HttpStatusCode.TooManyRequests to "0", HttpStatusCode.TooManyRequests to "0", HttpStatusCode.OK to null, bound = 3)
        assertEquals(HttpStatusCode.OK, freed.client.post("http://played/api/login/verify").status)
        assertEquals(3, freed.calls)
        // a wait past the bound, an unreadable one or none: not waited, not sent again
        for (retryAfter in listOf("3", "Wed, 21 Oct 2026 07:28:00 GMT", null)) {
            val played = Played(HttpStatusCode.TooManyRequests to retryAfter, HttpStatusCode.OK to null, bound = 3)
            assertEquals(HttpStatusCode.TooManyRequests, played.client.post("http://played/api/login/challenge").status, "Retry-After $retryAfter")
            assertEquals(1, played.calls, "Retry-After $retryAfter")
        }
        // another status naming a Retry-After (a 503) is no budget: not sent again
        val unavailable = Played(HttpStatusCode.ServiceUnavailable to "1", HttpStatusCode.OK to null)
        assertEquals(HttpStatusCode.ServiceUnavailable, unavailable.client.post("http://played/api/x").status)
        assertEquals(1, unavailable.calls)
    }

    @Test
    fun aFailureACaseProvokesIsNotRetried() = runBlocking {
        var calls = 0
        val client = HttpClient(MockEngine { calls++; throw IOException("cut") }) { install(LiveHttp.budgetWait) }
        assertFailsWith<IOException> { client.post("http://played/api/x") }
        assertEquals(1, calls)
    }

    @Test
    fun everyLiveCaseBuildsItsClientThroughLiveHttp() {
        val mobile = File(System.getProperty("veyq.repoRoot"), "src/mobile")
        val sources = mobile.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".gradle" }
            .filter { it.isFile && it.extension == "kt" && ("Test${File.separator}kotlin" in it.path || it.name == "LiveRun.kt") && it.name != "LiveHttpTest.kt" }
            .toList()
        assertTrue(sources.count { "LiveHttp.client" in it.readText() } > 20, "no live case found under $mobile: the scan reads nothing")
        val own = Regex("""\bHttpClient\(\s*(io\.ktor\.client\.engine\.okhttp\.)?OkHttp\b""")
        val offenders = sources.filter { own.containsMatchIn(it.readText()) }.map { it.relativeTo(mobile).path }.sorted()
        assertEquals(emptyList(), offenders, "a live case builds OkHttp itself: its sign-ins miss the budget's wait (LiveHttp.client)")
    }
}
