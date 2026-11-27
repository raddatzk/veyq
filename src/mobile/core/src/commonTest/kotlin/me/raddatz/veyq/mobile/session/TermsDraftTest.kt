package me.raddatz.veyq.mobile.session

import me.raddatz.veyq.mobile.session.MemberTerms.LangText
import me.raddatz.veyq.mobile.session.MemberTerms.TermsDraft
import me.raddatz.veyq.mobile.words.Msg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The owner's terms editor's rules (the web's `TenantTermsPanel` over `LocaleMarkdownEditor`, uc.admin.tenant-terms): a
 * language's presence is the answer, adding one is an act, a blank one is said, the template's question names what it
 * replaces, and the preview falls through to a language with text.
 */
class TermsDraftTest {
    private val none = TermsDraft(0, emptyList())

    @Test
    fun a_language_is_added_in_the_webs_order_once_and_only_a_known_one() {
        val de = none.withLanguage("de")
        assertEquals(listOf("en"), de.addable.map { it.lang })
        val both = de.withLanguage("en")
        assertEquals(listOf("en", "de"), both.texts.map { it.lang }, "the web's SUPPORTED_LOCALES order")
        assertEquals(emptyList(), both.addable)
        assertSame(both, both.withLanguage("en"), "a language present changes nothing")
        assertSame(none, none.withLanguage("fr"), "an unknown language is not added")
        assertEquals(listOf("de"), both.without("en").texts.map { it.lang })
    }

    @Test
    fun a_blank_language_is_said_by_its_own_name() {
        val d = none.withLanguage("en").withLanguage("de").withText("en", "  ")
        assertEquals("English, Deutsch", d.blankNames)
        assertEquals(Msg.TERMS_BLANK_LOCALE_WARNING, d.blankWarning)
        val written = d.withText("en", "x").withText("de", "y")
        assertNull(written.blankNames)
        assertNull(written.blankWarning)
    }

    @Test
    fun the_template_asks_to_fill_an_empty_text_and_to_replace_a_written_one() {
        assertEquals(Msg.TERMS_INSERT_TEMPLATE_ASK, LangText("de", " ").templateAsk)
        assertEquals(Msg.TERMS_INSERT_TEMPLATE_OVERWRITE_ASK, LangText("de", "Meins").templateAsk)
        assertEquals(listOf(false, true), listOf(LangText("de", " ").templateReplaces, LangText("de", "Meins").templateReplaces))
        val filled = none.withLanguage("de").withTemplate("de")
        assertEquals(TermsTemplate.byLanguage.getValue("de"), filled.texts.single().text)
        assertEquals(true, LangText("en", "").templated)
        assertEquals(false, LangText("fr", "").templated)
    }

    @Test
    fun the_preview_falls_through_to_a_language_with_text() {
        val d = none.withLanguage("en").withLanguage("de").withText("de", "Deutsch")
        assertEquals("Deutsch", d.preview("en"), "an empty English falls through")
        assertEquals("Deutsch", d.preview("fr"))
        assertEquals("Hi", d.withText("en", "Hi").preview("en"))
        assertEquals("", none.preview("en"))
    }
}
