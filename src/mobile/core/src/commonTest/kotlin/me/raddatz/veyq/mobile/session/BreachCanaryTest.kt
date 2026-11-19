package me.raddatz.veyq.mobile.session

import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.account.BreachCheck
import me.raddatz.veyq.mobile.offline.PlayedTenant
import me.raddatz.veyq.mobile.session.rotation.keyRotation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The canary (ADR 0151 decision 6, CR bite b): a signed-in account's password change and rotation check the new password
 * through the core's door to a third party — every request the account sent recorded as it reached the engine. No
 * request holds the password, its full SHA-1 or the SHA-1's suffix; the range request goes to the instance's configured
 * address only, carries the app's identification and no `Authorization`, cookie or `Referer`; nothing goes to the veyq
 * server about it.
 */
class BreachCanaryTest {
    private companion object {
        const val FINAL = "correct horse battery staple"
        const val HASH = "ABF7AAD6438836DBE526AA231ABDE2D0EEF74D42"
        const val RANGE = "https://range.example/range/"
        const val HIT_BODY = "AD6438836DBE526AA231ABDE2D0EEF74D42:2401\r\n"
    }

    private fun bodyOf(r: HttpRequestData): String = when (val b = r.body) {
        is OutgoingContent.ByteArrayContent -> b.bytes().decodeToString()
        is TextContent -> b.text
        else -> ""
    }

    @Test
    fun theCheckSendsOnlyAPrefixToTheConfiguredAddressAndNothingThatNamesThePerson() = runTest {
        val t = PlayedTenant()
        val seen = mutableListOf<HttpRequestData>()
        t.route = { r ->
            seen += r
            when {
                r.url.host == "range.example" -> 200 to HIT_BODY
                r.url.encodedPath == "/api/setup" ->
                    200 to """{"setupRequired":false,"version":{"major":${VeyqAccount.MAJOR},"build":1},"capabilities":[{"id":"${BreachCheck.CAPABILITY}","serviceUrl":"$RANGE"}],"suites":["v1"],"instanceSalt":"AAAAAAAAAAAAAAAAAAAAAA=="}"""
                else -> null
            }
        }
        t.remember()
        val account = t.resume()

        val change = account.passwordChange()
        change.check(FINAL)
        assertEquals(BreachCheck.BreachKind.HIT, change.breach.current.kind)
        change.run("whatever the password", FINAL, FINAL, "")
        assertEquals(BreachCheck.BreachKind.HIT, change.breach.current.kind, "a hit was passed without Use it anyway")
        change.useAnyway(true)
        change.run("whatever the password", FINAL, FINAL, "") // refused by the floor: what matters is what it sent

        val rotation = account.keyRotation(null)
        rotation.open()
        rotation.check("$FINAL!")

        val range = seen.filter { it.url.host == "range.example" }
        assertEquals(2, range.size, "the two finished passwords were not each checked once: ${range.map { it.url }}")
        assertEquals(RANGE + HASH.take(5), range[0].url.toString())
        for (r in range) {
            assertEquals("true", r.headers[BreachCheck.PADDING])
            assertEquals(OutsideHttp.userAgent(account.server), r.headers[HttpHeaders.UserAgent])
            for (h in listOf(HttpHeaders.Authorization, HttpHeaders.Cookie, HttpHeaders.Referrer)) assertFalse(r.headers.contains(h), "the range request carries $h")
        }
        for (r in seen) {
            val all = r.url.toString() + "\n" + r.headers.entries().joinToString("\n") { "${it.key}: ${it.value}" } + "\n" + bodyOf(r)
            for (canary in listOf(FINAL, HASH, HASH.drop(5), HASH.lowercase(), HASH.drop(5).lowercase())) {
                assertFalse(canary in all, "a request holds the password or its hash: ${r.url}")
            }
            if (r.url.host != "range.example") assertFalse("range" in r.url.encodedPath, "the check went to the veyq server: ${r.url}")
        }
        assertTrue(seen.any { it.url.encodedPath == "/api/setup" }, "the address was not read from the instance")
        change.close()
        rotation.close()
        account.retire()
    }
}
