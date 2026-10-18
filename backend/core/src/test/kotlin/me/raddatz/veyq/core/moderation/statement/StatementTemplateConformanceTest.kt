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
        fun passages(n: JsonNode) =
            n.map { Passage(it.path("id").asText(), it.path("full").asBoolean(), it.path("when").takeIf { w -> !w.isMissingNode }?.asText(), texts(it)) }
        assertEquals(passages(file.path("passages")), StatementTemplate.PASSAGES)
        assertEquals(passages(file.path("reporterDecision").path("passages")), StatementTemplate.REPORTER_DECISION)
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
        val full = StatementTemplate.renderMail(StatementLang.EN, StatementForm.FULL, fields.copy(provision = "§ 86a StGB"), "club.example.org")
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
        // The provision follows the ground where it was named, the territorial sentence only in the full form.
        assertTrue(full.contains("The ground: it breaches the terms of use that apply here. It relies on § 86a StGB."), full)
        assertFalse(reduced.contains("It relies on"), reduced)
        assertTrue(full.contains("It applies wherever this veyq instance is reached, and lasts until it is lifted."), full)
        assertTrue(full.contains("you can object once, in veyq"), full)
        assertFalse(full.contains("{"), "a placeholder left: $full")
        val de = StatementTemplate.renderMail(StatementLang.DE, StatementForm.FULL, fields, "club.example.org")
        assertTrue(de.contains("Person, die es hochgeladen hat und Person, der der Bereich gehört"), de)
    }

    @Test
    fun aServerOwnersStepCarriesItsFactsAndSendsTheObjectionToTheContact() {
        val fields = StatementFields(
            measureKind = "TENANT_OFFLINE", roles = listOf(StatementRole.TENANT_OWNER), basis = StatementBasis.OWN_FINDING,
            ground = StatementGround.ILLEGAL_CONTENT, date = "2026-10-18 09:00 UTC", contact = "abuse@example.org", hasFacts = true,
            provision = "§ 130 StGB", channel = ObjectionChannel.BY_CONTACT, mailFacts = "The space hosts it publicly.",
        )
        val mail = StatementTemplate.renderMail(StatementLang.EN, StatementForm.FULL, fields, "club.example.org")
        assertTrue(mail.contains("a space you own, club.example.org, was taken offline"), mail)
        assertTrue(mail.contains("What they found: The space hosts it publicly."), mail)
        assertTrue(mail.contains("after their own finding"), mail)
        assertTrue(mail.contains("If you disagree, write to abuse@example.org"), mail)
        assertFalse(mail.contains("in veyq, where you see this statement"), mail)
        assertFalse(mail.contains("{"), "a placeholder left: $mail")
        // Without a contact, the objection passage has no address to name and is left out.
        assertFalse(StatementTemplate.renderMail(StatementLang.EN, StatementForm.FULL, fields.copy(contact = null), "x").contains("write to"))
    }

    @Test
    fun aReportersRedressFollowsTheForm() {
        val full = StatementTemplate.renderRedress(StatementLang.EN, StatementForm.FULL, "abuse@example.org")
        val reduced = StatementTemplate.renderRedress(StatementLang.EN, StatementForm.REDUCED, null)
        assertEquals(
            "If you have more information, you can report it again. Questions go to abuse@example.org. You can also go to court. " +
                "You can also complain to the Digital Services Coordinator of the Member State where you live or are established.",
            full,
        )
        assertEquals("If you have more information, you can report it again.", reduced)
    }
}
