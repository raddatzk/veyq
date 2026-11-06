package me.raddatz.veyq.core.moderation.statement

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Conformance pin (ADR 0093 § 7): the statement's texts the backend renders for the
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
        fun measureEquals(v: JsonNode, pinned: MeasureTexts, at: String) {
            assertEquals(Texts(v.path("en").path("measure").asText(), v.path("de").path("measure").asText()), pinned.measure, at)
            assertEquals(Texts(v.path("en").path("reach").asText(), v.path("de").path("reach").asText()), pinned.reach, at)
            // A measure's words per relation (inbox #821): the same roles in the same order, each worded as the file words it.
            assertEquals(v.path("byRole").fieldNames().asSequence().toList(), pinned.byRole.keys.toList(), "$at.byRole")
            v.path("byRole").properties().forEach { (role, w) ->
                assertTrue(role in StatementTemplate.ROLES, "$at.byRole.$role names a role the template knows")
                measureEquals(w, pinned.byRole.getValue(role), "$at.byRole.$role")
            }
        }
        file.path("measures").properties().forEach { (k, v) -> measureEquals(v, StatementTemplate.MEASURES.getValue(k), k) }
        for ((key, pinned) in listOf("roles" to StatementTemplate.ROLES, "basis" to StatementTemplate.BASIS, "grounds" to StatementTemplate.GROUNDS)) {
            assertEquals(file.path(key).fieldNames().asSequence().toList(), pinned.keys.toList(), key)
            file.path(key).properties().forEach { (k, v) -> assertEquals(texts(v), pinned.getValue(k), "$key.$k") }
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
        assertTrue(mail.contains("It applies wherever this veyq server is reached, and lasts until it is lifted."), mail)
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
    fun aBlockedLinkIsWordedByTheReadersRelationToIt() {
        // Inbox #821: the creator reads "a link you created", an uploader of what it reaches "a link to something you
        // uploaded", any other reader the neutral words; a reader holding both relations reads the creator's.
        val fields = StatementFields(
            measureKind = "SHARE", roles = listOf(StatementRole.SHARE_CREATOR), basis = StatementBasis.REPORT,
            ground = StatementGround.TERMS_BREACH, date = "2026-10-18 09:00 UTC", contact = "abuse@example.org", hasFacts = true,
            provision = "§ 86a StGB",
        )
        fun measureOf(lang: StatementLang, vararg roles: StatementRole) =
            StatementTemplate.mailBody(fields.copy(roles = roles.toList()), "club.example.org").let { body ->
                val part = if (lang == StatementLang.EN) body.substringBefore("\n\n—\n\n") else body.substringAfter("\n\n—\n\n")
                part.substringAfter(if (lang == StatementLang.EN) "took a measure: " else "Maßnahme ergriffen: ").substringBefore(" You are told").substringBefore(" Du erhältst")
            }
        assertEquals(
            "a link you created was blocked. Everyone who opens it is told the link does not exist; nothing was deleted, " +
                "and the link cannot be reactivated or issued again while the block stands.",
            measureOf(StatementLang.EN, StatementRole.SHARE_CREATOR),
        )
        assertEquals(
            "a link to something you uploaded was blocked. Everyone who opens it is told the link does not exist; nothing was deleted.",
            measureOf(StatementLang.EN, StatementRole.UPLOADER),
        )
        assertEquals(
            "ein Link zu etwas, das du hochgeladen hast, wurde gesperrt. Wer ihn öffnet, erfährt, dass es den Link nicht gibt; " +
                "gelöscht wurde nichts.",
            measureOf(StatementLang.DE, StatementRole.UPLOADER),
        )
        assertTrue(measureOf(StatementLang.DE, StatementRole.SHARE_CREATOR).startsWith("ein von dir erstellter Link wurde gesperrt."))
        assertTrue(measureOf(StatementLang.EN, StatementRole.UPLOADER, StatementRole.SHARE_CREATOR).startsWith("a link you created was blocked."))
        // A reader with neither relation reads the neutral words, never "you created".
        assertTrue(measureOf(StatementLang.EN, StatementRole.SCOPE_OWNER).startsWith("a link was blocked. Everyone who opens it"))
        assertTrue(measureOf(StatementLang.DE).startsWith("ein Link wurde gesperrt. Wer ihn öffnet"))
        // Control: a measure without per-relation words reads the same whoever is told.
        val content = fields.copy(measureKind = "CONTENT")
        assertEquals(
            StatementTemplate.renderMail(StatementLang.EN, content.copy(roles = listOf(StatementRole.UPLOADER)), "x").substringBefore(" You are told"),
            StatementTemplate.renderMail(StatementLang.EN, content.copy(roles = listOf(StatementRole.SCOPE_OWNER)), "x").substringBefore(" You are told"),
        )
    }

    @Test
    fun aMeasureOnACaseNoReportOpenedIsTheHostsOwnInitiativeAndOnlyARemovalOrdersOwnBlockNamesTheOrder() {
        // DSA Art. 17(3)(b) asks: an Art. 16 notice, or own initiative (inbox #820). A report's case reads the report; a
        // case an authority's order opened — no Art. 16 notice — and no case at all read own initiative; a restored case's
        // stub reads as a report's. `ORDER` is never a host's measure's: only a removal order's own block.
        assertEquals(StatementBasis.REPORT, StatementBasis.ofCase("REPORT"))
        assertEquals(StatementBasis.OWN_FINDING, StatementBasis.ofCase("ORDER"))
        assertEquals(StatementBasis.REPORT, StatementBasis.ofCase("RESTORED"))
        assertEquals(StatementBasis.OWN_FINDING, StatementBasis.ofCase(null))
        val fields = StatementFields(
            measureKind = "CONTENT", roles = listOf(StatementRole.SCOPE_OWNER), basis = StatementBasis.OWN_FINDING,
            ground = StatementGround.ILLEGAL_CONTENT, date = "2026-10-31 09:00 UTC", contact = "abuse@example.org", hasFacts = true,
            provision = "§ 130 StGB",
        )
        // The own-initiative words name neither an order nor a report, so a confidential order is never revealed.
        val en = StatementTemplate.renderMail(StatementLang.EN, fields, "club.example.org")
        assertTrue(en.contains("The measure was taken after their own finding."), en)
        assertFalse(en.contains("authority") || en.contains("report by"), en)
        val de = StatementTemplate.renderMail(StatementLang.DE, fields, "club.example.org")
        assertTrue(de.contains("Die Maßnahme wurde ergriffen nach eigener Feststellung."), de)
        assertFalse(de.contains("Behörde") || de.contains("Meldung"), de)
        // The other bases read in the dative "nach" asks for: a report's and an order's own.
        assertTrue(
            StatementTemplate.renderMail(StatementLang.DE, fields.copy(basis = StatementBasis.REPORT), "x")
                .contains("Die Maßnahme wurde ergriffen nach einer Meldung von jemandem, der es sehen konnte."),
        )
        assertTrue(
            StatementTemplate.renderMail(StatementLang.DE, fields.copy(basis = StatementBasis.ORDER), "x")
                .contains("Die Maßnahme wurde ergriffen nach einer Anordnung einer Behörde. "),
        )
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
                "Where the Digital Services Act (DSA) applies to this server, you can also complain to the Digital Services " +
                "Coordinator of the Member State where you live or are established.",
            StatementTemplate.renderRedress(StatementLang.EN, "abuse@example.org"),
        )
        val noContact = StatementTemplate.renderRedress(StatementLang.DE, null)
        assertTrue(noContact.contains("Fragen gehen an von der Betreiberseite nicht angegeben."), noContact)
        assertTrue(noContact.contains("Soweit das Gesetz über digitale Dienste (DSA) für diesen Server gilt"), noContact)
    }
}
