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
 * mail equal `contracts/moderation/statement-of-reasons.json`, every one of them — src/web/'s `template.test.ts` holds its own
 * copy to the same file, so the mail and the placeholder render one template. There is one form (ADR 0093 § 14): a file
 * that marks a passage `full` again fails here, since no passage carries the marker.
 */
class StatementTemplateConformanceTest {

    private val file: JsonNode = ObjectMapper().readTree(File("../../contracts/moderation/statement-of-reasons.json"))

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
        assertEquals(texts(file.path("gap")), StatementTemplate.GAP)
        fun passages(n: JsonNode) = n.map {
            assertFalse(it.has("full"), "one form: no passage is marked full (${it.path("id")})")
            Passage(it.path("id").asText(), it.path("when").takeIf { w -> !w.isMissingNode }?.asText(), texts(it))
        }
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
    fun theOneFormCarriesEveryPassageAndTheMailNeverTheFacts() {
        val fields = StatementFields(
            measureKind = "CONTENT", roles = listOf(StatementRole.UPLOADER, StatementRole.SCOPE_OWNER), basis = StatementBasis.REPORT,
            ground = StatementGround.TERMS_BREACH, date = "2026-10-18 09:00 UTC", contact = "abuse@example.org", hasFacts = true,
            provision = "§ 86a StGB",
        )
        val mail = StatementTemplate.renderMail(StatementLang.EN, fields, "club.example.org")
        assertTrue(mail.contains("the person who uploaded it and an owner of the space it lies in"), mail)
        assertTrue(mail.contains("What they found is in veyq: sign in at club.example.org"), mail)
        assertTrue(mail.contains("The measure was taken after a report by someone who could see it."), mail)
        assertTrue(mail.contains("No automated means found or decided it: a person did."), mail)
        assertTrue(mail.contains("The ground: it breaches the terms of use that apply here. It relies on § 86a StGB."), mail)
        assertTrue(mail.contains("It applies wherever this veyq instance is reached, and lasts until it is lifted."), mail)
        assertTrue(mail.contains("you can object once, in veyq"), mail)
        assertTrue(mail.contains("Questions go to abuse@example.org."), mail)
        assertTrue(mail.contains("You can also go to court; you do not need to object first."), mail)
        assertFalse(mail.contains("{"), "a placeholder left: $mail")
        // An instance without a contact shows the gap where the contact goes (ADR 0093 § 14); control: the address above.
        val noContact = StatementTemplate.renderMail(StatementLang.EN, fields.copy(contact = null), "club.example.org")
        assertTrue(noContact.contains("Questions go to not stated by this operator."), noContact)
        val de = StatementTemplate.renderMail(StatementLang.DE, fields.copy(contact = null), "club.example.org")
        assertTrue(de.contains("Person, die es hochgeladen hat und Person, der der Bereich gehört"), de)
        assertTrue(de.contains("Fragen gehen an von der Betreiberseite nicht angegeben."), de)
    }

    @Test
    fun aServerOwnersStepCarriesItsFactsAndSendsTheObjectionToTheContact() {
        val fields = StatementFields(
            measureKind = "TENANT_OFFLINE", roles = listOf(StatementRole.TENANT_OWNER), basis = StatementBasis.OWN_FINDING,
            ground = StatementGround.ILLEGAL_CONTENT, date = "2026-10-18 09:00 UTC", contact = "abuse@example.org", hasFacts = true,
            provision = "§ 130 StGB", channel = ObjectionChannel.BY_CONTACT, mailFacts = "The space hosts it publicly.",
        )
        val mail = StatementTemplate.renderMail(StatementLang.EN, fields, "club.example.org")
        assertTrue(mail.contains("a space you own, club.example.org, was taken offline"), mail)
        assertTrue(mail.contains("What they found: The space hosts it publicly."), mail)
        assertTrue(mail.contains("after their own finding"), mail)
        assertTrue(mail.contains("If you disagree, write to abuse@example.org"), mail)
        assertFalse(mail.contains("in veyq, where you see this statement"), mail)
        assertFalse(mail.contains("{"), "a placeholder left: $mail")
        // Without a contact, the objection names the gap rather than vanishing (ADR 0093 § 14).
        assertTrue(
            StatementTemplate.renderMail(StatementLang.EN, fields.copy(contact = null), "x")
                .contains("If you disagree, write to not stated by this operator"),
        )
    }

    @Test
    fun aReportersRedressIsWholeWithTheDsaQualifierAndTheGap() {
        assertEquals(
            "If you have more information, you can report it again. Questions go to abuse@example.org. You can also go to court. " +
                "Where the Digital Services Act (DSA) applies to this instance, you can also complain to the Digital Services " +
                "Coordinator of the Member State where you live or are established.",
            StatementTemplate.renderRedress(StatementLang.EN, "abuse@example.org"),
        )
        val noContact = StatementTemplate.renderRedress(StatementLang.DE, null)
        assertTrue(noContact.contains("Fragen gehen an von der Betreiberseite nicht angegeben."), noContact)
        assertTrue(noContact.contains("Soweit das Gesetz über digitale Dienste (DSA) für diese Instanz gilt"), noContact)
    }
}
