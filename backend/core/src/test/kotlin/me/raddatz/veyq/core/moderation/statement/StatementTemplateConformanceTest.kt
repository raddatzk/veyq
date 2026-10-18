package me.raddatz.veyq.core.moderation.statement

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Conformance pin (decision 1 of cr-statement-of-reasons-and-objection): the statement's texts the backend renders for the
 * mail equal `contracts/moderation/statement-of-reasons.json`, every one of them — web/'s `template.test.ts` holds its own
 * copy to the same file, so the mail and the placeholder render one template. And the form: the reduced one leaves out
 * exactly the passages the file marks `full`.
 */
class StatementTemplateConformanceTest {

    private val file: JsonNode = ObjectMapper().readTree(File("../contracts/moderation/statement-of-reasons.json"))

    private fun texts(n: JsonNode) = Texts(n.path("en").asText(), n.path("de").asText())

    @Test
    fun everyTextEqualsTheContract() {
        assertEquals(file.path("measures").fieldNames().asSequence().toList(), StatementTemplate.MEASURES.keys.toList())
        file.path("measures").fields().forEach { (k, v) ->
            val pinned = StatementTemplate.MEASURES.getValue(k)
            assertEquals(Texts(v.path("en").path("measure").asText(), v.path("de").path("measure").asText()), pinned.measure, k)
            assertEquals(Texts(v.path("en").path("reach").asText(), v.path("de").path("reach").asText()), pinned.reach, k)
        }
        for ((key, pinned) in listOf("roles" to StatementTemplate.ROLES, "basis" to StatementTemplate.BASIS, "grounds" to StatementTemplate.GROUNDS)) {
            assertEquals(file.path(key).fieldNames().asSequence().toList(), pinned.keys.toList(), key)
            file.path(key).fields().forEach { (k, v) -> assertEquals(texts(v), pinned.getValue(k), "$key.$k") }
        }
        assertEquals(texts(file.path("rolesJoiner")), StatementTemplate.ROLES_JOINER)
        assertEquals(
            file.path("passages").map { Passage(it.path("id").asText(), it.path("full").asBoolean(), it.path("when").takeIf { w -> !w.isMissingNode }?.asText(), texts(it)) },
            StatementTemplate.PASSAGES,
        )
        assertEquals(texts(file.path("mail").path("subject")), StatementTemplate.MAIL_SUBJECT)
        assertEquals(texts(file.path("mail").path("factsInVeyq")), StatementTemplate.MAIL_FACTS_IN_VEYQ)
        // Every enum value the wire carries has its text.
        assertEquals(StatementRole.entries.map { it.name }, StatementTemplate.ROLES.keys.toList())
        assertEquals(StatementBasis.entries.map { it.name }, StatementTemplate.BASIS.keys.toList())
        assertEquals(StatementGround.entries.map { it.name }, StatementTemplate.GROUNDS.keys.toList())
    }

    @Test
    fun theReducedFormLeavesOutWhatOnlyTheFullFormCarriesAndTheMailNeverTheFacts() {
        val fields = StatementFields(
            measureKind = "CONTENT", roles = listOf(StatementRole.UPLOADER, StatementRole.SCOPE_OWNER), basis = StatementBasis.REPORT,
            ground = StatementGround.TERMS_BREACH, date = "2026-10-18 09:00 UTC", contact = "abuse@example.org", hasFacts = true,
        )
        val full = StatementTemplate.renderMail(StatementLang.EN, StatementForm.FULL, fields, "club.example.org")
        val reduced = StatementTemplate.renderMail(StatementLang.EN, StatementForm.REDUCED, fields.copy(hasFacts = false, contact = null), "club.example.org")
        val onlyFull = StatementTemplate.PASSAGES.filter { it.full }.map { it.text.en.substringBefore("{") }
        assertTrue(onlyFull.isNotEmpty())
        onlyFull.forEach { assertTrue(full.contains(it), "full lacks: $it\n$full") }
        onlyFull.forEach { assertFalse(reduced.contains(it), "reduced carries: $it\n$reduced") }
        assertTrue(full.contains("the person who uploaded it and an owner of the space it lies in"), full)
        assertTrue(full.contains("What they found is in veyq: sign in at club.example.org"), full)
        assertTrue(full.contains("Questions go to abuse@example.org."), full)
        assertFalse(reduced.contains("What they found"), reduced)
        assertFalse(reduced.contains("Questions go to"), reduced)
        assertTrue(reduced.contains("The ground: it breaches the terms of use that apply here."), reduced)
        assertFalse(full.contains("{"), "a placeholder left: $full")
        val de = StatementTemplate.renderMail(StatementLang.DE, StatementForm.FULL, fields, "club.example.org")
        assertTrue(de.contains("Person, die es hochgeladen hat und Person, der der Bereich gehört"), de)
    }
}
