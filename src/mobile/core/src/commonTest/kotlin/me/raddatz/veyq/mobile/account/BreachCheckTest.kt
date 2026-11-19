package me.raddatz.veyq.mobile.account

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.raddatz.veyq.mobile.account.BreachCheck.BreachKind
import me.raddatz.veyq.mobile.account.BreachCheck.BreachState
import me.raddatz.veyq.mobile.session.OutsideHttp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The breached-password check's own rule, the web test's (`passwordWarning.test.ts`) case for case (ADR 0151 decision 5):
 * automatic over the FINISHED password — when its field is left, and at submit when it changed since —, never while
 * typing, advisory. The requests as they reached the engine are the observable: what leaves the device is the point.
 */
class BreachCheckTest {
    private companion object {
        // the web test's oracle, computed outside the code under test:
        // SHA-1("correct horse battery staple") = ABF7AAD6438836DBE526AA231ABDE2D0EEF74D42
        const val FINAL = "correct horse battery staple"
        const val PREFIX = "ABF7A"
        const val HIT_BODY = "0018A45C4D1DEF81644B54AB7F969B88D65:1\r\nAD6438836DBE526AA231ABDE2D0EEF74D42:2401\r\n"
        const val CLEAN_BODY = "0018A45C4D1DEF81644B54AB7F969B88D65:1\r\n"
        const val HIBP = "https://api.pwnedpasswords.com/range/"
    }

    /** A played range service: every request recorded; [answer] decides (a body and status, or a throw); [gate] holds it. */
    private class Played {
        val sent = mutableListOf<HttpRequestData>()
        var body = CLEAN_BODY
        var status = HttpStatusCode.OK
        var fail = false
        var gate: CompletableDeferred<Unit>? = null
        val http = OutsideHttp(HttpClient(MockEngine { r ->
            sent += r
            gate?.await()
            if (fail) throw IllegalStateException("the network is down")
            respond(body, status)
        }), "https://veyq.example")

        fun check(address: String? = HIBP) = BreachCheck(http) { address }
        val urls get() = sent.map { it.url.toString() }
    }

    private suspend fun until(what: () -> Boolean) = withContext(Dispatchers.Default) { withTimeout(10_000) { while (!what()) delay(5) } }

    @Test
    fun sendsNothingWhileThePasswordIsBeingTyped() = runTest {
        val played = Played()
        val check = played.check()
        for (pw in listOf("correct", "correct horse", "correct horse battery", "correct horse battery st", FINAL)) {
            check.edited()
            delay(1_000)
        }
        assertTrue(played.sent.isEmpty())
    }

    // bite (a): the padding header dropped → red here
    @Test
    fun onLeavingTheFieldOneRequestCarryingOnlyThePrefixPaddedCleanProceeds() = runTest {
        val played = Played()
        val check = played.check()
        check.check(FINAL)
        assertEquals(listOf(HIBP + PREFIX), played.urls)
        assertEquals("true", played.sent.single().headers["Add-Padding"])
        assertEquals(BreachKind.CLEAN, check.current.kind)
        // the submit of the unchanged password asks nothing again
        assertTrue(check.proceed(FINAL))
        assertEquals(1, played.sent.size)
    }

    @Test
    fun aSecondLeaveOnAnUnchangedPasswordSendsNoSecondRequest() = runTest {
        val played = Played()
        val check = played.check()
        check.check(FINAL)
        check.check(FINAL)
        assertEquals(1, played.sent.size)
    }

    @Test
    fun aLeaveWhileTheFirstQueryIsInFlightSharesIt() = runTest {
        val played = Played().apply { gate = CompletableDeferred() }
        val check = played.check()
        launch(Dispatchers.Default) { check.check(FINAL) }
        until { played.sent.size == 1 }
        assertEquals(BreachKind.CHECKING, check.current.kind)
        val verdict = async(Dispatchers.Default) { check.proceed(FINAL) }
        played.gate!!.complete(Unit)
        assertTrue(verdict.await())
        assertEquals(1, played.sent.size)
    }

    @Test
    fun aPasswordChangedSinceTheLeaveIsQueriedAgainOnSubmit() = runTest {
        val played = Played()
        val check = played.check()
        check.check("a different password")
        check.edited()
        assertTrue(check.proceed(FINAL))
        assertEquals(2, played.sent.size)
        assertFalse(PREFIX in played.urls[0])
        assertEquals(HIBP + PREFIX, played.urls[1])
    }

    @Test
    fun aSubmitWithNoLeaveBeforeItQueriesOnce() = runTest {
        val played = Played()
        assertTrue(played.check().proceed(FINAL))
        assertEquals(1, played.sent.size)
    }

    @Test
    fun sendsNothingForAPasswordThePolicyRefuses() = runTest {
        val played = Played()
        val check = played.check()
        check.check("")
        check.check("123456789") // one under the floor of 10
        check.proceed("123456789")
        assertTrue(played.sent.isEmpty())
        assertEquals(BreachState(), check.current)
        // the control on the same predicate: exactly at the floor IS sent
        check.check("0123456789")
        assertEquals(1, played.sent.size)
    }

    // bite (c): proceed() passing a hit → red here
    @Test
    fun aHitBlocksTheSubmitWithNoNewRequestUntilUseItAnyway() = runTest {
        val played = Played().apply { body = HIT_BODY }
        val check = played.check()
        check.check(FINAL)
        assertEquals(BreachState(BreachKind.HIT, 2401), check.current.copy(about = null))
        assertFalse(check.proceed(FINAL))
        assertFalse(check.proceed(FINAL))
        check.useAnyway(true)
        assertEquals(BreachKind.ACCEPTED, check.current.kind)
        assertEquals(2401, check.current.count)
        assertTrue(check.proceed(FINAL))
        assertTrue(check.proceed(FINAL))
        assertEquals(1, played.sent.size)
        check.useAnyway(false)
        assertFalse(check.proceed(FINAL))
    }

    @Test
    fun useItAnywayMeansNothingWithoutAHit() = runTest {
        val played = Played()
        val check = played.check()
        check.useAnyway(true)
        assertEquals(BreachKind.IDLE, check.current.kind)
        check.check(FINAL)
        check.useAnyway(true)
        assertEquals(BreachKind.CLEAN, check.current.kind)
    }

    @Test
    fun anEditClearsTheAcceptance() = runTest {
        val played = Played().apply { body = HIT_BODY }
        val check = played.check()
        check.check(FINAL)
        check.useAnyway(true)
        check.edited()
        assertEquals(BreachKind.IDLE, check.current.kind)
        // even editing straight back: the acceptance was for that moment's decision
        assertFalse(check.proceed(FINAL))
        assertEquals(BreachKind.HIT, check.current.kind)
        assertEquals(2, played.sent.size)
    }

    // bite (d): an unavailable check blocking → red here
    @Test
    fun aCheckThatCouldNotRunGoesAheadAndSaysSo() = runTest {
        val played = Played().apply { fail = true }
        val check = played.check()
        check.check(FINAL)
        assertEquals(BreachKind.UNAVAILABLE, check.current.kind)
        assertTrue(check.proceed(FINAL))
        assertEquals(BreachKind.UNAVAILABLE, check.current.kind)
        assertEquals(1, played.sent.size)
    }

    @Test
    fun aNon2xxIsCouldNotRunTooNotZero() = runTest {
        val played = Played().apply { status = HttpStatusCode.ServiceUnavailable; body = "" }
        val check = played.check()
        assertTrue(check.proceed(FINAL))
        assertEquals(BreachKind.UNAVAILABLE, check.current.kind)
    }

    @Test
    fun aPasswordEditedWhileTheQueryIsInFlightDoesNotProceedOnTheOldVerdict() = runTest {
        val played = Played().apply { gate = CompletableDeferred() }
        val check = played.check()
        val verdict = async(Dispatchers.Default) { check.proceed(FINAL) }
        until { played.sent.size == 1 }
        check.edited()
        played.gate!!.complete(Unit)
        assertFalse(verdict.await())
        assertEquals(BreachKind.IDLE, check.current.kind)
    }

    @Test
    fun asksTheAddressTheInstanceConfiguredAndNothingWhereItRunsNoCheck() = runTest {
        val played = Played()
        played.check("https://hibp.mirror.example/range/").check(FINAL)
        assertEquals(listOf("https://hibp.mirror.example/range/$PREFIX"), played.urls)

        played.sent.clear()
        val off = played.check(null)
        off.check("another long finished password")
        assertTrue(off.proceed("another long finished password"))
        assertTrue(played.sent.isEmpty())
        assertEquals(BreachKind.IDLE, off.current.kind)
        assertTrue(BreachCheck.none().proceed(FINAL))
    }

    @Test
    fun theCountIsReadFromTheRangeAsTheWebReadsIt() {
        val hash = "ABF7AAD6438836DBE526AA231ABDE2D0EEF74D42"
        assertEquals(2401, BreachCheck.countIn(hash, HIT_BODY))
        assertEquals(0, BreachCheck.countIn(hash, CLEAN_BODY))
        // a padding row (count 0) never hits; a lower-case suffix still matches
        assertEquals(0, BreachCheck.countIn(hash, "AD6438836DBE526AA231ABDE2D0EEF74D42:0\n"))
        assertEquals(7, BreachCheck.countIn(hash, "ad6438836dbe526aa231abde2d0eef74d42:7"))
    }

    @Test
    fun theStateNeverPrintsOrSerializesThePassword() = runTest {
        val played = Played()
        val check = played.check()
        check.check(FINAL)
        assertFalse(FINAL in check.current.toString())
        assertFalse(FINAL in kotlinx.serialization.json.Json.encodeToString(BreachState.serializer(), check.current))
    }
}
