package me.raddatz.veyq.mobile.sharing

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertFalse

/**
 * `veyq:` links (ADR 0150 § O1): the two forms, and every other input `Open` — and nothing of the input in the answer. A
 * parser that kept the fragment or the query, or answered `Link` for an unknown host, is red here.
 */
class DeepLinkTest {
    private val canary = "CANARY-a3f9c1"

    // by type, not by equality: a parser whose answer grew a field still compiles, and the canary test below catches it
    private fun assertLink(answer: DeepLink, uri: String? = null) = assertIs<DeepLink.Link>(answer, uri)
    private fun assertOpen(answer: DeepLink, uri: String? = null) = assertIs<DeepLink.Open>(answer, uri)

    @Test
    fun theTwoForms() {
        assertLink(DeepLink.parse("veyq://link"))
        assertOpen(DeepLink.parse("veyq://open"))
        // the scheme and the host are case-insensitive; a trailing slash, blanks around and a port change nothing
        assertLink(DeepLink.parse("VEYQ://Link"))
        assertLink(DeepLink.parse("  veyq://link/  "))
        assertLink(DeepLink.parse("veyq://link:443"))
    }

    @Test
    fun aPathAQueryAFragmentAndUserInfoAreIgnored() {
        for (uri in listOf(
            "veyq://link#k=$canary", "veyq://link?u=https://photos.example.org/s/$canary", "veyq://link/s/$canary#k=$canary",
            "veyq://$canary@link", "veyq://link?k=$canary#k=$canary", "veyq://link/?report&k=$canary",
        )) assertLink(DeepLink.parse(uri), uri)
        for (uri in listOf("veyq://open#k=$canary", "veyq://open?u=$canary", "veyq://open/$canary")) {
            assertOpen(DeepLink.parse(uri), uri)
        }
    }

    /** Whatever the input, the answer holds none of it: it is one of two objects with no field. */
    @Test
    fun noCanaryReachesTheAnswer() {
        for (uri in listOf("veyq://link#k=$canary", "veyq://link?u=$canary", "veyq://$canary@link/$canary", "veyq://$canary", "https://x/s/$canary#k=$canary")) {
            val answer = DeepLink.parse(uri)
            assertFalse(canary in answer.toString(), "$uri → $answer")
            assertFalse(canary.lowercase() in answer.toString().lowercase(), "$uri → $answer")
        }
    }

    @Test
    fun everyOtherInputOpensTheAppWhereItIs() {
        for (uri in listOf(
            "", "   ", "veyq:", "veyq:link", "veyq:/link", "veyq:///link", "veyq://", "veyq://links", "veyq://lin", "veyq://link.example.org",
            "veyq://open-link", "veyq://evil.example", "veyq://link@evil.example", "https://link", "https://photos.example.org/s/x#k=y",
            "veyqpair:link", "veyqsignin://link", "xveyq://link", "link", "://link", "veyq//link",
        )) assertOpen(DeepLink.parse(uri), uri)
    }
}
