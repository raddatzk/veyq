package me.raddatz.veyq.mobile.words

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The core's own words (`"core_text": true`), generated from `words.json`: the language picked, the placeholders filled. */
class CoreTextTest {
    @Test
    fun aGermanTagIsGermanAndAnyOtherEnglish() {
        assertEquals("Erstellt am 18. November 2026", CoreText.of(Msg.KIT_PDF_CREATED, "de", "18. November 2026"))
        assertEquals("Notfall-Kit", CoreText.of(Msg.KIT_PDF_HEADING, "de-AT"))
        assertEquals("Notfall-Kit", CoreText.of(Msg.KIT_PDF_HEADING, "DE_ch"))
        assertEquals("Emergency Kit", CoreText.of(Msg.KIT_PDF_HEADING, "en-GB"))
        assertEquals("Emergency Kit", CoreText.of(Msg.KIT_PDF_HEADING, "fr"))
        assertEquals("Emergency Kit", CoreText.of(Msg.KIT_PDF_HEADING, ""))
    }

    @Test
    fun placeholdersAreFilledByNumberInTheLanguagesOrder() {
        assertEquals("November 18, 2026", CoreText.of(Msg.KIT_PDF_DATE, "en", "November", "18", "2026"))
        assertEquals("18. November 2026", CoreText.of(Msg.KIT_PDF_DATE, "de", "November", "18", "2026"))
        // an argument is put in as it is, never read as a pattern
        assertEquals("Help: \$1 {2} \\", CoreText.of(Msg.KIT_PDF_HELP, "en", "\$1 {2} \\"))
    }

    @Test
    fun aKeyThatIsNoCoreTextOrAWrongArgumentCountIsRefused() {
        assertFailsWith<IllegalArgumentException> { CoreText.of(Msg.SIGNIN_KIT_HELD, "en") }
        assertFailsWith<IllegalArgumentException> { CoreText.of(Msg.KIT_PDF_CREATED, "en") }
    }
}
