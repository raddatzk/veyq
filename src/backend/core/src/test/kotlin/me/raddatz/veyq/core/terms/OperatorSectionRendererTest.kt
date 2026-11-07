package me.raddatz.veyq.core.terms

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

/**
 * The operator section's text and its rendering (ADR 0035 decision 6, decisions 1, 3 and 4 of
 * cr-operator-section-of-the-terms), pure: the shipped `contracts/terms/operator-section.json` is parsed as the jar
 * carries it, and each rule of the renderer is held by a case and its control on the same text.
 */
class OperatorSectionRendererTest {

    private val file = File("../../contracts/terms/operator-section.json")
    private val text = OperatorSectionText.parse(ObjectMapper().readTree(file))

    private val all = SectionValues(
        providerName = "Example e. V.",
        providerAddress = "Musterstraße 1\n12345 Musterstadt",
        providerEmail = "info@example.org",
        providerRepresentative = "Erika Mustermann",
        supervisoryAuthority = "LfD Musterland",
        contactEmail = "contact@example.org",
        contactLanguages = listOf("de", "en"),
        privacyContact = "privacy@example.org",
        retentionDays = 7,
    )

    private fun render(values: SectionValues = all) = OperatorSectionRenderer.render(text, values)

    @Test
    fun theJarCarriesTheContractFileUnchanged() {
        assertEquals(text, OperatorSectionText.load(), "the build's copy of the section differs from contracts/terms/")
    }

    @Test
    fun everyPlaceholderTheFileNamesIsOneTheRendererKnows() {
        val r = render()
        for (t in listOf(r.text, r.record)) {
            assertFalse(Regex("""\{\w+}""").containsMatchIn(t.en + t.de), "an unfilled placeholder: $t")
        }
    }

    @Test
    fun anUnknownPlaceholderIsRefusedRatherThanPrinted() {
        val broken = text.copy(passages = text.passages + SectionPassage("x", false, listOf(SectionLine(SectionTexts("{nobody}", "{nobody}"), false))))
        assertThrows<IllegalStateException> { OperatorSectionRenderer.render(broken, all) }
    }

    @Test
    fun theSectionHasOneFormWithTheAuthoritiesTheDeferralAndTheNoticePath() {
        // ADR 0093 § 14: no operating mode, so no reduced variant — every passage of the text renders on every instance.
        val t = render().text
        for ((text, lang) in listOf(t.en to "en", t.de to "de")) {
            assertTrue(text.contains(if (lang == "en") "### Authorities and contact" else "### Behörden und Kontakt"), "$lang: the authorities")
            assertTrue(text.contains(if (lang == "en") "Only an authority can ask" else "Nur eine Behörde kann verlangen"), "$lang: the deferral")
            assertTrue(text.contains(if (lang == "en") "### Reporting content" else "### Inhalte melden"), "$lang: the notice path")
        }
        assertTrue(t.en.contains("The statement also names the facts found"), "the statement's further content")
        // A file that brings a variant marker back is refused at load, so no second form can creep in unrendered.
        val withPart = ObjectMapper().readTree(file).deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        (withPart.get("passages").get(0) as com.fasterxml.jackson.databind.node.ObjectNode).put("part", "full")
        assertThrows<IllegalArgumentException> { OperatorSectionText.parse(withPart) }
    }

    @Test
    fun anEmptyValueIsAVisibleGap() {
        val unset = all.copy(providerName = null, contactEmail = " ")
        val t = render(unset).text
        assertTrue(t.en.contains("Operator: not stated by this operator"), t.en)
        assertTrue(t.de.contains("Betrieben von: von der Betreiberseite nicht angegeben"), t.de)
        assertTrue(t.en.contains("is not stated by this operator, in these languages"), "a blank value is a gap too")
        // control: a set value replaces its gap
        assertTrue(render().text.en.contains("Operator: Example e. V."))
        assertFalse(render().text.en.contains("not stated by this operator"))
    }

    @Test
    fun anOptionalLineGoesWhereItsValueIsEmpty() {
        assertFalse(render(all.copy(providerRepresentative = null)).text.en.contains("Represented by"))
        assertTrue(render().text.en.contains("Represented by: Erika Mustermann"), "control")
    }

    @Test
    fun theRecordsPointerIsAlwaysThereAndSetApart() {
        // The record's Art. 13 information is the privacy notice's (ADR 0035 decision 8): the section keeps
        // a pointer, always (the record is kept wherever a guest may write, ADR 0093 § 10), and never inside the text.
        val r = render()
        assertTrue(r.record.en.contains("privacy notice of this server") && r.record.de.contains("Datenschutzerklärung dieses Servers"))
        assertFalse(r.record.en.contains("Art. 6(1)(f)"), "the information itself is the notice's, not repeated here")
        assertFalse(r.text.en.contains("privacy notice of this server"), "set apart: never inside the text")
    }

    @Test
    fun theSectionCarriesNoRightToObjectOfItsOwnAnyMore() {
        // A `separate` passage was the record's right to object; it moved with the record (Q27). A file that brings one
        // back is refused at load, so the section cannot carry a second, drifting copy of the notice's.
        val withSeparate = ObjectMapper().readTree(file).deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        (withSeparate.get("passages").get(0) as com.fasterxml.jackson.databind.node.ObjectNode).put("separate", true)
        assertThrows<IllegalArgumentException> { OperatorSectionText.parse(withSeparate) }
        assertEquals(1, text.revision, "the first release ships the section at revision 1; a release that changes its text moves it")
    }

    @Test
    fun aValuesNewlinesRenderInlineSoATypedAddressCannotStartAMarkdownBlock() {
        val r = render(all.copy(providerAddress = "Musterstraße 1\n# 12345 Musterstadt\n- x"))
        assertTrue(r.text.en.contains("Address: Musterstraße 1, # 12345 Musterstadt, - x"), r.text.en)
    }
}
