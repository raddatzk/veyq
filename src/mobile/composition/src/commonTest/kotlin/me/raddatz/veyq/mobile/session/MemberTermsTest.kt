package me.raddatz.veyq.mobile.session

import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.offline.PlayedTenant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The tenant's terms over the generated client (uc.account.accept-terms; `tenants.openapi.yaml` `getTenantTerms`,
 * `acceptTenantTerms`), against a server the test plays: the read as the gate and Settings' page use it, the accept with
 * the pair it names, and how each refusal ends. It names no application: the account opens over the whole composition.
 */
class MemberTermsTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

    private val played = mutableListOf<PlayedTenant>()

    private suspend fun tenant(route: (path: String, method: String, body: String) -> Pair<Int, String>?): TenantSession {
        val t = PlayedTenant().also { played += it }
        t.route = { r ->
            val body = (r.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()
            route(r.url.encodedPath, r.method.value, body)
        }
        t.remember()
        val account = t.resume()
        return account.open(account.tenants.first())
    }

    private val served = """{"termsRevision":3,"accepted":false,"terms":{"de":"## Regeln","en":"## Rules"},
        "operatorSection":{"revision":2,"text":{"de":"## Auf diesem Server","en":"## On this server"},"record":{"en":"### Your address"}}}"""

    @Test
    fun the_read_is_the_gates_condition_and_the_text_in_the_viewers_language() = runTest {
        val ts = tenant { path, method, _ -> if (method == "GET" && path.endsWith("/terms")) 200 to served else null }
        val read = MemberTerms.read(ts)!!
        assertTrue(read.stale)
        assertEquals(
            MemberTerms.TermsShown(3, 2, true, "## Regeln", "## Auf diesem Server", "### Your address", "http://a.example/privacy#guest-write-record"),
            read.shown("de"),
        )
        assertEquals("## Rules", read.shown("en").terms)
        assertEquals("## Rules", read.shown("fr").terms, "a language the space has not: English")
        assertEquals(read.shown("de"), MemberTerms.page(ts, "de"))
    }

    @Test
    fun terms_accepted_or_none_set_are_not_stale_and_a_read_that_fails_is_none() = runTest {
        val accepted = tenant { path, _, _ -> if (path.endsWith("/terms")) 200 to served.replace("\"accepted\":false", "\"accepted\":true") else null }
        assertFalse(MemberTerms.read(accepted)!!.stale)
        val none = tenant { path, _, _ ->
            if (path.endsWith("/terms")) 200 to """{"termsRevision":0,"accepted":false,"operatorSection":{"revision":1,"text":{"en":"## On this server"},"record":{}}}""" else null
        }
        val read = MemberTerms.read(none)!!
        assertFalse(read.stale, "revision 0 never gates")
        assertEquals(MemberTerms.TermsShown(0, 1, false, "", "## On this server", "", "http://a.example/privacy#guest-write-record"), read.shown("en"))
        val failing = tenant { path, _, _ -> if (path.endsWith("/terms")) 500 to "{}" else null }
        assertNull(MemberTerms.read(failing))
        assertNull(MemberTerms.page(failing, "en"))
    }

    @Test
    fun an_accept_names_the_pair_and_tells_a_moved_pair_from_a_failure() = runTest {
        var status = 200
        var code = "terms_revision_changed"
        val sent = mutableListOf<String>()
        val ts = tenant { path, method, body ->
            if (method == "POST" && path.endsWith("/terms/accept")) {
                sent += body
                status to (if (status == 200) served.replace("\"accepted\":false", "\"accepted\":true") else """{"code":"$code"}""")
            } else null
        }
        assertEquals(MemberTerms.Answer.ACCEPTED, MemberTerms.accept(ts, 3, 2))
        assertEquals(mapOf("revision" to "3", "operatorRevision" to "2"), fields(sent.single()))
        status = 409
        assertEquals(MemberTerms.Answer.CHANGED, MemberTerms.accept(ts, 3, 2))
        code = "conflict"
        assertEquals(MemberTerms.Answer.FAILED, MemberTerms.accept(ts, 3, 2), "another 409 is no moved pair")
        status = 404
        assertEquals(MemberTerms.Answer.FAILED, MemberTerms.accept(ts, 3, 2))
        played.single().reachable = false
        assertEquals(MemberTerms.Answer.FAILED, MemberTerms.accept(ts, 3, 2))
    }

    private fun fields(json: String): Map<String, String> =
        Regex("\"(\\w+)\"\\s*:\\s*(\\d+)").findAll(json).associate { it.groupValues[1] to it.groupValues[2] }
}
