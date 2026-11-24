package me.raddatz.veyq.mobile.sharing

import me.raddatz.veyq.mobile.words.Msg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A text another app shared (inbox #1015, ADR 0150 § S): the one share link in it found ([SharedText.linkIn]) and checked
 * as *Open a link* checks a pasted one ([OpenLink.received]). A finder that guessed between two links, opened a foreign
 * one, kept a sentence's closing punctuation on the key or left a non-link text in the field is red here.
 */
class SharedTextTest {
    private val share = "Q".repeat(42) + "w"
    private val key = "A".repeat(43)
    private val link = "https://photos.example.org/s/$share#k=$key"
    private val other = "https://photos.example.org/s/${"R".repeat(42)}w#k=$key"

    private fun one(text: String): String = assertIs<SharedText.Found.One>(SharedText.linkIn(text), text).text

    @Test
    fun theLinkAloneOrAroundWhiteSpace() {
        assertEquals(link, one(link))
        assertEquals(link, one("  \n$link\n "))
    }

    @Test
    fun theOneLinkInASentenceOrAMarkUp() {
        for (text in listOf(
            "Look at this: $link", "$link.", "($link)", "<$link>", "„$link“", "\"$link\",", "Link:$link and more",
            "[the photos]($link)", "First line\n$link\nThanks!", "Our site https://example.org and the photos $link",
        )) assertEquals(link, one(text), text)
        // a report link stays one (the report is the share page's, ADR 0120 decision 6)
        assertEquals("$link&report", one("see $link&report."))
    }

    @Test
    fun theSameLinkTwiceIsOne() {
        assertEquals(link, one("$link — again: $link"))
    }

    @Test
    fun twoDifferentLinksAreNeverGuessed() {
        assertIs<SharedText.Found.Several>(SharedText.linkIn("$link and $other"))
    }

    @Test
    fun aForeignLinkOrNoneAtAll() {
        // the first candidate, for its refusal to say why
        assertEquals("https://example.org/page", one("Read https://example.org/page."))
        assertIs<SharedText.Found.None>(SharedText.linkIn("Just a note, photos.example.org/s/x has no scheme"))
        assertIs<SharedText.Found.None>(SharedText.linkIn("   "))
        // the found text names no key when printed
        assertEquals("One", SharedText.linkIn(link).toString())
    }

    private fun field() = OpenLink { _, _ -> error("not opened in this test") }

    @Test
    fun aReceivedLinkCanOpen() {
        val f = field()
        f.received("Look at this: $link.")
        assertTrue(f.state.value.canOpen)
        assertEquals(link, f.state.value.text)
        assertNull(f.state.value.problem)
    }

    @Test
    fun aReceivedTextSaysWhyItDoesNotOpen() {
        val f = field()
        f.received("Read https://example.org/page.")
        assertFalse(f.state.value.canOpen)
        assertEquals(Msg.LINK_PROBLEM_NOT_A_SHARE, f.state.value.problem)

        f.received("https://photos.example.org/s/$share#moderation")
        assertEquals(Msg.LINK_PROBLEM_MODERATION, f.state.value.problem)
        assertEquals("https://photos.example.org/s/$share#moderation", f.state.value.browser)

        f.received("no link here")
        assertFalse(f.state.value.canOpen)
        assertEquals(Msg.LINK_PROBLEM_NO_LINK_IN_TEXT, f.state.value.problem)
        assertEquals("", f.state.value.text)

        f.received("$link or $other")
        assertFalse(f.state.value.canOpen)
        assertEquals(Msg.LINK_PROBLEM_SEVERAL_LINKS, f.state.value.problem)
        // the text holds two keys: it is not left in the field
        assertEquals("", f.state.value.text)
    }
}
