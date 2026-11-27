package me.raddatz.veyq.mobile.session

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The core's starter template is the web's `termsTemplate.ts`, language for language and character for character: the one
 * text an owner inserts into a space's terms is the same on every surface (uc.admin.tenant-terms).
 */
class TermsTemplateIsTheWebsTest {
    @Test
    fun theTemplateIsTheWebsByteForByte() {
        val text = File(System.getProperty("veyq.repoRoot"), "src/web/src/features/tenant/termsTemplate.ts").readText()
        val web = Regex("\\n  (de|en): `(.*?)`,", RegexOption.DOT_MATCHES_ALL).findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(setOf("de", "en"), web.keys, "the web's template has the two languages")
        assertEquals(web, TermsTemplate.byLanguage)
    }
}
