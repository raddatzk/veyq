package me.raddatz.veyq.core.admin.operator

import me.raddatz.veyq.core.adminchannel.givenAdminChannel
import me.raddatz.veyq.core.admin.instance.InstanceMailSettingsHandler
import me.raddatz.veyq.core.admin.instance.InstanceMailSettingsSliceTest
import me.raddatz.veyq.core.admin.instance.InstanceMailUpdateOutcome
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.ensureUser
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.specification.RequestSpecification
import jakarta.inject.Inject
import jakarta.mail.Part
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The operator details (uc.admin.operator-details; ADR 0093 §§ 11 and 14, ADR 0035 decision 7, ADR 0097 decision 5),
 * over the full HTTP stack on the admin channel and the public listener:
 *   - the values are set and cleared, each may stay empty, an out-of-bounds one is refused with nothing stored;
 *   - the notification address is confirmed by a mailed code ([me.raddatz.veyq.core.admin.instance.CodeConfirmation]):
 *     a wrong code spends an attempt, a changed address unconfirms without a write to the confirmation, no code goes
 *     out while instance mail is not confirmed;
 *   - the public read never carries the notification address, asserted on the raw JSON with a control beside it.
 *
 * Instance mail runs over a real SMTP server in the JVM (GreenMail), set up through its handler and confirmed with the
 * code it mailed, as `InstanceNoticeMailSliceTest` does. Ports are this class's own (`TestMailbox` says how to find
 * the taken ones).
 */
@QuarkusTest
class OperatorDetailsSliceTest {

    @Inject
    lateinit var instanceMail: InstanceMailSettingsHandler

    private lateinit var mailbox: GreenMail

    @BeforeEach
    fun seed() {
        ensureUser(OWNER_ID, OWNER_EMAIL, "SERVER_OWNER")
        ensureUser(MEMBER_ID, "operator-member@example.com", "MEMBER")
        clear()
        mailbox = GreenMail(ServerSetup(MAIL_PORT, "127.0.0.1", "smtp")).also { it.start() }
    }

    @AfterEach
    fun restore() {
        mailbox.stop()
        clear()
    }

    // --- the values ----------------------------------------------------------------------------------------------

    @Test
    fun theValuesAreSetAndClearedAndEveryOneMayStayEmpty() {
        put("{}").statusCode(200)
            .body("contactEmail", nullValue()).body("contactLanguages.size()", equalTo(0)).body("providerName", nullValue())
            .body("notificationConfirmation", equalTo("NOT_SET"))
        put(FULL).statusCode(200)
            .body("contactEmail", equalTo(CONTACT)).body("contactLanguages", equalTo(listOf("de", "en")))
            .body("statementEmail", equalTo("statements@example.org")).body("providerName", equalTo("Example e. V."))
            .body("providerAddress", equalTo("Musterstraße 1\n12345 Musterstadt")).body("providerEmail", equalTo("info@example.org"))
            .body("providerRepresentative", equalTo("Erika Mustermann")).body("supervisoryAuthority", equalTo("LfD Musterland"))
            .body("notificationEmail", equalTo(NOTIFY))
        owner().`when`().delete(PATH).then().statusCode(200)
            .body("contactEmail", nullValue()).body("notificationEmail", nullValue()).body("providerName", nullValue())
            .body("notificationConfirmation", equalTo("NOT_SET"))
        assertNull(row()!!.codeHash, "clearing drops an outstanding code with the address")
    }

    @Test
    fun anOutOfBoundsValueIsRefusedAndNothingIsStored() {
        put("""{"providerName":"Example e. V."}""").statusCode(200)
        put("""{"contactEmail":"not-an-address"}""").statusCode(400).body("code", equalTo("invalid_request"))
        put("""{"providerName":"${"x".repeat(201)}"}""").statusCode(400)
        put("""{"contactLanguages":["de","not a tag"]}""").statusCode(400)
        put("""{"contactLanguages":[${(1..13).joinToString(",") { "\"de\"" }}]}""").statusCode(400)
        owner().`when`().get(PATH).then().body("providerName", equalTo("Example e. V.")).body("contactEmail", nullValue())
    }

    // --- the notification address's code ------------------------------------------------------------------------

    @Test
    fun aSavedNotificationAddressGetsACodeAndAWrongCodeSpendsAnAttempt() {
        readyInstanceMail()
        put("""{"notificationEmail":"$NOTIFY"}""").statusCode(200)
            .body("notificationConfirmation", equalTo("CODE_SENT")).body("instanceMailReady", equalTo(true))
        val mail = mailsTo(NOTIFY).single()
        assertEquals(listOf(NOTIFY), mail.allRecipients.map { it.toString() }, "only the notification address")
        val code = codeIn(mail)

        confirm(wrong(code)).statusCode(409).body("code", equalTo("operator_code_invalid"))
        assertEquals(1, row()!!.codeAttempts, "a wrong code spends an attempt")
        owner().`when`().get(PATH).then().body("notificationConfirmation", equalTo("CODE_SENT"))
        confirm(code).statusCode(200)
            .body("notificationConfirmation", equalTo("CONFIRMED")).body("notificationConfirmedAt", notNullValue())
        confirm(code).statusCode(409) // spent
    }

    @Test
    fun theLastWrongEntrySpendsTheCode() {
        readyInstanceMail()
        put("""{"notificationEmail":"$NOTIFY"}""").statusCode(200)
        val code = codeIn(mailsTo(NOTIFY).single())
        repeat(5) { confirm(wrong(code)).statusCode(409) }
        confirm(code).statusCode(409)
        owner().`when`().get(PATH).then().body("notificationConfirmation", equalTo("UNCONFIRMED"))
    }

    @Test
    fun aChangedAddressUnconfirmsWithoutAWriteToTheConfirmation() {
        readyInstanceMail()
        put("""{"notificationEmail":"$NOTIFY"}""").statusCode(200)
        confirm(codeIn(mailsTo(NOTIFY).single())).statusCode(200).body("notificationConfirmation", equalTo("CONFIRMED"))
        val confirmedAt = row()!!.confirmedAt

        put("""{"notificationEmail":"$OTHER_NOTIFY"}""").statusCode(200)
            .body("notificationConfirmation", equalTo("CODE_SENT")).body("notificationConfirmedAt", nullValue())
        val after = row()!!
        assertEquals(NOTIFY, after.confirmedAddress, "the confirmation itself was not written")
        assertEquals(confirmedAt, after.confirmedAt)

        // The confirmed address saved again is confirmed again, with no new code asked.
        val mailsBefore = mailbox.receivedMessages.size
        put("""{"notificationEmail":"$NOTIFY"}""").statusCode(200).body("notificationConfirmation", equalTo("CONFIRMED"))
        assertEquals(mailsBefore, mailbox.receivedMessages.size, "no code for an address that is confirmed")
    }

    @Test
    fun noCodeGoesOutWhileInstanceMailIsNotConfirmed() {
        put("""{"notificationEmail":"$NOTIFY"}""").statusCode(200)
            .body("notificationConfirmation", equalTo("UNCONFIRMED")).body("instanceMailReady", equalTo(false))
        assertNull(row()!!.codeHash)
        assertEquals(0, mailbox.receivedMessages.size)

        // Control: once instance mail is confirmed, the same save sends one.
        readyInstanceMail()
        put("""{"notificationEmail":"$NOTIFY"}""").statusCode(200).body("notificationConfirmation", equalTo("CODE_SENT"))
        assertEquals(1, mailsTo(NOTIFY).size)
    }

    // --- the public read ---------------------------------------------------------------------------------------

    @Test
    fun thePublicReadNeverCarriesTheNotificationAddressOnAnyHost() {
        put(FULL).statusCode(200)
        for (host in listOf("localhost", "some-tenant.example.com")) {
            val raw = given().header("Host", host).`when`().get("/api/operator").then().statusCode(200).extract().asString()
            assertFalse(raw.contains(NOTIFY), "the notification address never leaves the admin channel ($host): $raw")
            assertFalse(raw.contains("notification", ignoreCase = true), "not even as a field name ($host)")
            // Control on the same body: the published values are there.
            assertTrue(raw.contains(CONTACT) && raw.contains("Example e. V.") && raw.contains("LfD Musterland"), raw)
        }
    }

    @Test
    fun theOperationsAreTheServerOwnersOnTheAdminChannelOnly() {
        val member = givenAdminChannel().header("Authorization", bearer(MEMBER_ID))
        member.`when`().get(PATH).then().statusCode(403).body("code", equalTo("not_server_owner"))
        givenAdminChannel().header("Authorization", bearer(MEMBER_ID)).contentType(ContentType.JSON).body("{}")
            .`when`().put(PATH).then().statusCode(403)
        given().header("Authorization", bearer(OWNER_ID)).`when`().get(PATH).then().statusCode(404) // the public port
        owner().`when`().get(PATH).then().statusCode(200) // control
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    private fun owner(): RequestSpecification = givenAdminChannel().header("Authorization", bearer(OWNER_ID))

    private fun put(body: String) = owner().contentType(ContentType.JSON).body(body).`when`().put(PATH).then()

    private fun confirm(code: String) =
        owner().contentType(ContentType.JSON).body("""{"code":"$code"}""").`when`().post("$PATH/notification/confirmation").then()

    private fun row(): OperatorDetailsEntity? =
        QuarkusTransaction.requiringNew().call { OperatorDetailsEntity.findById(OperatorDetailsEntity.SINGLETON_ID) }

    /** Instance mail against the in-JVM relay, confirmed with the code it mailed the Server Owner. */
    private fun readyInstanceMail() {
        val outcome = instanceMail.update(OWNER_ID, "127.0.0.1", MAIL_PORT, "veyq@example.com", null, null, tls = false)
        assertTrue(outcome is InstanceMailUpdateOutcome.Ok, "$outcome")
        val code = codeIn(mailsTo(OWNER_EMAIL).single())
        assertTrue(instanceMail.confirm(OWNER_ID, code) is me.raddatz.veyq.core.admin.instance.InstanceMailConfirmOutcome.Ok)
        mailbox.purgeEmailFromAllMailboxes()
    }

    private fun mailsTo(address: String): List<MimeMessage> {
        mailbox.waitForIncomingEmail(2_000, 1)
        return mailbox.receivedMessages.filter { m -> m.allRecipients.any { it.toString().equals(address, ignoreCase = true) } }
    }

    private fun codeIn(mail: MimeMessage): String {
        val body = textOf(mail)
        return CODE.find(body)?.groupValues?.get(1) ?: error("no code in:\n$body")
    }

    private fun textOf(part: Part): String = when (val content = part.content) {
        is String -> content
        is MimeMultipart -> (0 until content.count).map { textOf(content.getBodyPart(it)) }.joinToString("\n")
        else -> ""
    }

    private fun wrong(code: String): String = code.map { if (it == '9') '0' else it + 1 }.joinToString("")

    private fun clear() {
        QuarkusTransaction.requiringNew().run { OperatorDetailsEntity.deleteAll() }
        InstanceMailSettingsSliceTest.clear()
    }

    private companion object {
        const val PATH = "/api/instance/operator"
        const val OWNER_ID = "3a0e5c71-9b2d-4f8e-a6c4-1d7b9e2f5a30"
        const val OWNER_EMAIL = "operator-owner@example.com"
        const val MEMBER_ID = "8c4d2e19-7f3a-4b6e-9d1c-5a2f8e4b7c06"
        const val CONTACT = "contact@example.org"
        const val NOTIFY = "alerts@example.org"
        const val OTHER_NOTIFY = "alerts-new@example.org"
        const val MAIL_PORT = 13970
        val CODE = Regex("""(?m)^\s+([0-9]{8})\s*$""")

        val FULL = """
            {"contactEmail":"$CONTACT","contactLanguages":["de","en"],"statementEmail":"statements@example.org",
             "notificationEmail":"$NOTIFY","providerName":"Example e. V.","providerAddress":"Musterstraße 1\n12345 Musterstadt",
             "providerEmail":"info@example.org","providerRepresentative":"Erika Mustermann","supervisoryAuthority":"LfD Musterland"}
        """.trimIndent()
    }
}
