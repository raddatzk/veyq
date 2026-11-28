package me.raddatz.veyq.mobile.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The appearance and language choices (ADR 0162): what an install keeps reads back as its choice, anything else as the system's. */
class AppearanceTest {
    @Test
    fun aKeptThemeReadsBackAndAnythingElseIsTheSystems() {
        for (t in ThemeChoice.entries) assertEquals(t, ThemeChoice.of(t.id))
        assertEquals(ThemeChoice.SYSTEM, ThemeChoice.of(null), "nothing kept")
        assertEquals(ThemeChoice.SYSTEM, ThemeChoice.of("sepia"), "a value no entry has")
        assertEquals(ThemeChoice.SYSTEM, ThemeChoice.of("DARK"), "the web's stored values are lower case")
    }

    @Test
    fun aKeptLanguageReadsBackWithItsTag() {
        for (l in LanguageChoice.entries) assertEquals(l, LanguageChoice.of(l.id))
        assertEquals(LanguageChoice.SYSTEM, LanguageChoice.of(null))
        assertEquals(LanguageChoice.SYSTEM, LanguageChoice.of("fr"))
        assertNull(LanguageChoice.SYSTEM.tag)
        assertEquals("de", LanguageChoice.DE.tag)
        assertEquals("en", LanguageChoice.EN.tag)
    }

    @Test
    fun aPlatformsPerAppLanguageIsTheChoiceItStandsFor() {
        assertEquals(LanguageChoice.SYSTEM, LanguageChoice.ofTags(emptyList()), "no per-app language set")
        assertEquals(LanguageChoice.DE, LanguageChoice.ofTags(listOf("de-AT")))
        assertEquals(LanguageChoice.DE, LanguageChoice.ofTags(listOf("de_DE")))
        assertEquals(LanguageChoice.EN, LanguageChoice.ofTags(listOf("en-GB", "de")))
        assertEquals(LanguageChoice.SYSTEM, LanguageChoice.ofTags(listOf("fr-FR", "de")), "a language without words: the system's fallback")
    }
}
