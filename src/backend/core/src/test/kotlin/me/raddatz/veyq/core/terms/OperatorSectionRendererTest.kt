package me.raddatz.veyq.core.terms

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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

    private fun render(variant: SectionVariant, values: SectionValues = all, recordOn: Boolean = true) =
        OperatorSectionRenderer.render(text, variant, values, recordOn)

    @Test
    fun theJarCarriesTheContractFileUnchanged() {
        assertEquals(text, OperatorSectionText.load(), "the build's copy of the section differs from contracts/terms/")
    }

    @Test
    fun everyPlaceholderTheFileNamesIsOneTheRendererKnows() {
        for (variant in SectionVariant.entries) {
            val r = render(variant)
            for (t in listOfNotNull(r.text, r.record)) {
                assertFalse(Regex("""\{\w+}""").containsMatchIn(t.en + t.de), "an unfilled placeholder in $variant: $t")
            }
        }
    }

    @Test
    fun anUnknownPlaceholderIsRefusedRatherThanPrinted() {
        val broken = text.copy(passages = text.passages + SectionPassage("x", SectionPart.BOTH, listOf(SectionLine(SectionTexts("{nobody}", "{nobody}"), false))))
        assertThrows<IllegalStateException> { OperatorSectionRenderer.render(broken, SectionVariant.FULL, all, false) }
    }

    @Test
    fun theFullOnlyPassagesAreExactlyWhatAdr0035ListsUnderDrops() {
        // drops: the authorities bullet, the statement's deferral, and the statement's further content.
        assertEquals(
            setOf("authorities", "deferral", "statement-full"),
            text.passages.filter { it.part == SectionPart.FULL }.map { it.id }.toSet(),
        )
    }

    @Test
    fun theReducedVariantLeavesOutTheAuthoritiesAndTheDeferralAndKeepsTheNoticePath() {
        val full = render(SectionVariant.FULL).text
        val reduced = render(SectionVariant.REDUCED).text
        for ((t, lang) in listOf(full.en to "en", full.de to "de")) {
            assertTrue(t.contains(if (lang == "en") "### Authorities and contact" else "### Behörden und Kontakt"), "control, $lang: full has the authorities")
            assertTrue(t.contains(if (lang == "en") "Only an authority can ask" else "Nur eine Behörde kann verlangen"), "control, $lang: full has the deferral")
        }
        assertFalse(reduced.en.contains("### Authorities and contact"))
        assertFalse(reduced.en.contains("Only an authority can ask"))
        assertFalse(reduced.de.contains("### Behörden und Kontakt"))
        assertFalse(reduced.de.contains("Nur eine Behörde kann verlangen"))
        assertTrue(reduced.en.contains("### Reporting content") && reduced.de.contains("### Inhalte melden"), "the notice path stays, whole")
        assertTrue(reduced.en.contains("can object once per measure"), "the objection stays")
    }

    @Test
    fun anEmptyValueIsAVisibleGapInTheFullVariantAndAbsentInTheReducedOne() {
        val unset = all.copy(providerName = null, contactEmail = " ")
        val full = render(SectionVariant.FULL, unset).text
        assertTrue(full.en.contains("Operator: not stated by this operator"), full.en)
        assertTrue(full.de.contains("Betrieben von: von der Betreiberseite nicht angegeben"), full.de)
        assertTrue(full.en.contains("is not stated by this operator, in these languages"), "a blank value is a gap too")
        val reduced = render(SectionVariant.REDUCED, unset).text
        assertFalse(reduced.en.contains("Operator:"), "reduced: the line goes")
        assertFalse(reduced.en.contains("not stated by this operator"), "reduced: no gap anywhere")
        // control: a set value renders in both
        assertTrue(render(SectionVariant.REDUCED).text.en.contains("Operator: Example e. V."))
    }

    @Test
    fun anOptionalLineGoesInBothVariantsWhereItsValueIsEmpty() {
        val noRep = all.copy(providerRepresentative = null)
        for (variant in SectionVariant.entries) {
            assertFalse(render(variant, noRep).text.en.contains("Represented by"), "$variant")
            assertTrue(render(variant).text.en.contains("Represented by: Erika Mustermann"), "control, $variant")
        }
    }

    @Test
    fun theRecordsPointerFollowsItsSwitchNotTheVariant() {
        // The record's Art. 13 information is the privacy notice's (Q27 of cr-instance-privacy-notice): the section keeps
        // a pointer, there exactly while the record is on, in either variant, and never inside the text.
        for (variant in SectionVariant.entries) {
            val on = render(variant, recordOn = true)
            assertNotNull(on.record, "$variant, on: the pointer")
            assertTrue(on.record!!.en.contains("privacy notice of this instance") && on.record!!.de.contains("Datenschutzerklärung dieser Instanz"))
            assertFalse(on.record!!.en.contains("Art. 6(1)(f)"), "the information itself is the notice's, not repeated here")
            assertFalse(on.text.en.contains("privacy notice of this instance"), "set apart: never inside the text")
            assertNull(render(variant, recordOn = false).record, "$variant, off")
        }
    }

    @Test
    fun theSectionCarriesNoRightToObjectOfItsOwnAnyMore() {
        // A `separate` passage was the record's right to object; it moved with the record (Q27). A file that brings one
        // back is refused at load, so the section cannot carry a second, drifting copy of the notice's.
        val withSeparate = ObjectMapper().readTree(file).deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        (withSeparate.get("passages").get(0) as com.fasterxml.jackson.databind.node.ObjectNode).put("separate", true)
        assertThrows<IllegalArgumentException> { OperatorSectionText.parse(withSeparate) }
        assertEquals(2, text.revision, "the move bumped the section's revision once (Q27)")
    }

    @Test
    fun aValuesNewlinesRenderInlineSoATypedAddressCannotStartAMarkdownBlock() {
        val r = render(SectionVariant.FULL, all.copy(providerAddress = "Musterstraße 1\n# 12345 Musterstadt\n- x"))
        assertTrue(r.text.en.contains("Address: Musterstraße 1, # 12345 Musterstadt, - x"), r.text.en)
    }

    @Test
    fun aDraftSaysSoFirstAndOnlyWhileItIsOne() {
        assertTrue(text.draft, "the section is a draft until a qualified legal review")
        val draft = render(SectionVariant.FULL).text
        assertTrue(draft.en.startsWith("*Draft:") && draft.de.startsWith("*Entwurf:"))
        val reviewed = OperatorSectionRenderer.render(text.copy(draft = false), SectionVariant.FULL, all, false).text
        assertTrue(reviewed.en.startsWith("## On this instance"), "control: no banner once reviewed")
    }
}
