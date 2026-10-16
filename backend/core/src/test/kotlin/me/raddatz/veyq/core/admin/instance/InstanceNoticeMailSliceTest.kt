package me.raddatz.veyq.core.admin.instance

import me.raddatz.veyq.core.account.email.EmailConfirmationMailer
import me.raddatz.veyq.core.adminchannel.givenAdminChannel
import me.raddatz.veyq.core.admin.tenant.MailSettingsRow
import me.raddatz.veyq.core.admin.tenant.TenantMailSender
import me.raddatz.veyq.core.admin.tenant.TenantStore
import me.raddatz.veyq.core.crypto.ConfigSecretKeyGuard
import me.raddatz.veyq.core.crypto.ConfigSecretSeal
import me.raddatz.veyq.core.generated.model.DeliveryResult
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.ensureUser
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Instance mail end to end (uc.admin.learn-of-new-releases, ADR 0097 decisions 3–5): a save sends the code to the
 * Server Owner's address and the code confirms; each notice is mailed once across two checks, only once confirmed, and
 * never after the account's address changed; a dated notice carries an `.ics` that parses; the instance's settings and
 * a tenant's never reach each other's mail; a key that cannot open the password sends nothing and says so; a failed
 * send leaves the notice for the next check.
 *
 * Over a real SMTP server in the JVM (GreenMail, as tenant mail is tested — `TestMailbox` says why it is not a stub)
 * and the real `MailClient`. The settings that point at it are saved through the handler, not over HTTP: the contract
 * takes a bare DNS hostname, and the in-JVM server listens on `127.0.0.1`. Every other step — the confirmation, the
 * read — goes over HTTP. Ports are this class's own (`TestMailbox` names how to find the taken ones).
 */
@QuarkusTest
class InstanceNoticeMailSliceTest {

    @Inject
    lateinit var handler: InstanceMailSettingsHandler

    @Inject
    lateinit var check: ReleaseCheck

    @Inject
    lateinit var notices: ReleaseNoticeStore

    @Inject
    lateinit var seal: ConfigSecretSeal

    @Inject
    lateinit var keyGuard: ConfigSecretKeyGuard

    @Inject
    lateinit var tenantStore: TenantStore

    @Inject
    lateinit var tenantSender: TenantMailSender

    @Inject
    lateinit var confirmationMailer: EmailConfirmationMailer

    private lateinit var source: FakeReleaseSource
    private lateinit var mailbox: GreenMail

    @BeforeEach
    fun seed() {
        ensureUser(OWNER_ID, OWNER_EMAIL, "SERVER_OWNER")
        setOwnerEmail(OWNER_EMAIL)
        InstanceMailSettingsSliceTest.clear()
        ReleaseCheckSliceTest.reset()
        source = FakeReleaseSource()
        notices.saveSetting(enabled = true, source = "${source.base}/m")
        mailbox = GreenMail(ServerSetup(INSTANCE_PORT, "127.0.0.1", "smtp")).also { it.start() }
    }

    @AfterEach
    fun restore() {
        mailbox.stop()
        source.close()
        InstanceMailSettingsSliceTest.clear()
        ReleaseCheckSliceTest.reset()
        setOwnerEmail(OWNER_EMAIL)
    }

    @Test
    fun aSaveMailsTheCodeToTheServerOwnerAndTheCodeConfirms() {
        save(INSTANCE_PORT)
        owner().`when`().get(PATH).then().statusCode(200).body("confirmation", equalTo("CODE_SENT")).body("confirmedAt", nullValue())
        val mail = mailsTo(OWNER_EMAIL).single()
        assertEquals(listOf(OWNER_EMAIL), mail.allRecipients.map { it.toString() }, "only the Server Owner's own address")
        val code = codeIn(mail)

        confirm(wrong(code)).statusCode(409).body("code", equalTo("mail_code_invalid"))
        owner().`when`().get(PATH).then().body("confirmation", equalTo("CODE_SENT")) // a wrong entry is not a confirmation
        confirm(code).statusCode(200).body("confirmation", equalTo("CONFIRMED")).body("confirmedAt", notNullValue())
        confirm(code).statusCode(409) // spent
    }

    @Test
    fun aSaveWhoseTestMailCannotBeDeliveredSaysSoAndConfirmsNothing() {
        save(DEAD_PORT)
        owner().`when`().get(PATH).then().statusCode(200).body("confirmation", equalTo("SEND_FAILED"))
        confirm("00000000").statusCode(409)
        save(INSTANCE_PORT) // control: the same save against a live relay sends
        owner().`when`().get(PATH).then().body("confirmation", equalTo("CODE_SENT"))
    }

    @Test
    fun aNoticeIsMailedOnceAcrossTwoChecks() {
        configureAndConfirm()
        source.mirror("/m", listOf("v7.3" to "2027-02-01", "v7.4" to "2027-03-01", "v8.0" to "2027-05-01"))

        assertEquals(ReleaseCheckRun.Checked(2, mailed = 2), check.check(RUNNING, NOW))
        assertEquals(2, noticeMails().size)
        assertTrue(notices.notices().all { it.mailedAt != null }, "each notice records its mail")

        assertEquals(ReleaseCheckRun.Checked(0, mailed = 0), check.check(RUNNING, NOW.plusSeconds(86_400)))
        assertEquals(2, noticeMails().size, "the second check mails nothing again")
        // The claim itself refuses a notice already claimed — what holds "once" should two runs ever overlap.
        assertFalse(notices.claimForMail(notices.notices().first().id, NOW), "a mailed notice is never claimed again")
    }

    @Test
    fun nothingIsMailedWhileUnconfirmedAndTheNoticesFollowOnceConfirmed() {
        save(INSTANCE_PORT)
        val code = codeIn(mailsTo(OWNER_EMAIL).single())
        source.mirror("/m", listOf("v7.3" to "2027-02-01", "v7.4" to "2027-03-01"))

        assertEquals(ReleaseCheckRun.Checked(1, mailed = 0), check.check(RUNNING, NOW))
        assertEquals(0, noticeMails().size, "unconfirmed: the console only")
        assertEquals(null, notices.notices().single().mailedAt)

        confirm(code).statusCode(200)
        assertEquals(ReleaseCheckRun.Checked(0, mailed = 1), check.check(RUNNING, NOW.plusSeconds(86_400)))
        assertEquals(1, noticeMails().size, "control: confirmed, the notice found earlier is mailed at the next check")
    }

    @Test
    fun aChangedAccountAddressUnconfirmsAndStopsTheMail() {
        configureAndConfirm()
        setOwnerEmail(OTHER_EMAIL)

        owner().`when`().get(PATH).then().statusCode(200)
            .body("confirmation", equalTo("UNCONFIRMED"))
            .body("address", equalTo(OTHER_EMAIL))
        source.mirror("/m", listOf("v7.3" to "2027-02-01", "v7.4" to "2027-03-01"))
        assertEquals(ReleaseCheckRun.Checked(1, mailed = 0), check.check(RUNNING, NOW))
        assertEquals(0, noticeMails().size + mailsTo(OTHER_EMAIL).size, "neither the old nor the new address is mailed")
    }

    @Test
    fun aDatedNoticeCarriesItsDateAsAnIcsThatParses() {
        configureAndConfirm()
        source.mirror("/m", listOf("v7.3" to "2027-02-01", "v8.0" to "2027-05-01"))
        check.check(RUNNING, NOW)

        val notice = notices.notices().single()
        val mail = noticeMails().single()
        val parts = partsOf(mail)
        val text = parts.single { it.isMimeType("text/plain") }.content as String
        assertTrue(text.contains("2029-05-01"), "the date written out:\n$text")
        assertTrue(text.contains(InstanceMailCopy.UPGRADE_PAGE), "the upgrade page")
        val calendar = parts.single { it.contentType.lowercase().startsWith("text/calendar") }
        assertTrue(calendar.contentType.lowercase().contains("method=publish"), calendar.contentType)

        val raw = calendar.inputStream.readBytes().toString(Charsets.UTF_8)
        val event = IcsDocument.parse(raw)
        assertEquals(listOf("VCALENDAR", "VEVENT"), event.components)
        assertEquals("2.0", event.value("VERSION"))
        assertEquals("${notice.id}@veyq", event.value("UID"), "the notice's own id: the same notice is the same event")
        assertEquals("DTSTART;VALUE=DATE" to "20290501", event.property("DTSTART"))
        assertEquals(null, event.property("ATTENDEE"), "an entry, not an invitation")
        assertNotNull(event.value("DTSTAMP"))
    }

    @Test
    fun anUndatedNoticeCarriesNoCalendar() {
        configureAndConfirm()
        source.mirror("/m", listOf("v7.3" to "2027-02-01", "v7.4" to "2027-03-01"))
        check.check(RUNNING, NOW)
        assertFalse(partsOf(noticeMails().single()).any { it.contentType.lowercase().startsWith("text/calendar") })
    }

    @Test
    fun aTenantsSettingsNeverSendAnInstanceNotice(): Unit = GreenMail(ServerSetup(TENANT_PORT, "127.0.0.1", "smtp")).let { tenantBox ->
        tenantBox.start()
        try {
            val tenantId = seedTenant()
            saveTenantRelay(tenantId, TENANT_PORT)
            assertTrue(tenantSender.sendable(tenantSender.settings(tenantId)), "control: the tenant's relay can send")
            source.mirror("/m", listOf("v7.3" to "2027-02-01", "v7.4" to "2027-03-01"))

            assertEquals(ReleaseCheckRun.Checked(1, mailed = 0), check.check(RUNNING, NOW))
            assertEquals(0, tenantBox.receivedMessages.size, "no instance notice goes out through a tenant's relay")
            assertEquals(0, mailbox.receivedMessages.size)
        } finally {
            tenantBox.stop()
        }
    }

    @Test
    fun theInstanceSettingsNeverSendATenantsMail() {
        configureAndConfirm()
        val tenantId = seedTenant()

        assertFalse(tenantSender.sendable(tenantSender.settings(tenantId)), "a tenant without settings cannot send")
        val result = confirmationMailer.sendConfirmationLink(tenantId, "t.example.com", "member@example.com", "token", NOW)
        assertEquals(DeliveryResult.FALLBACK_COPYLINK, result, "the instance relay is no tenant's fallback")
        assertEquals(1, mailbox.receivedMessages.size, "the code mail and nothing else reached the instance relay")
    }

    @Test
    fun aKeyThatCannotOpenThePasswordSendsNothingAndSaysWhy() {
        val sealed = seal.seal("relay-password")
        QuarkusTransaction.requiringNew().run {
            InstanceMailSettingsEntity().apply {
                host = "127.0.0.1"; port = INSTANCE_PORT; fromAddress = FROM; username = "veyq"; tls = false
                passwordCiphertext = sealed.ciphertext; passwordNonce = sealed.nonce; passwordFingerprint = "the-lost-key"
                ownerUserId = OWNER_ID; confirmedAddress = OWNER_EMAIL; confirmedAt = NOW
            }.persist()
        }
        owner().`when`().get(PATH).then().statusCode(200)
            .body("confirmation", equalTo("CONFIRMED"))
            .body("configSecretState", equalTo("MISMATCH"))
        source.mirror("/m", listOf("v7.3" to "2027-02-01", "v7.4" to "2027-03-01"))

        mailbox.setUser(FROM, "veyq", "relay-password") // the relay takes this login: only the key stands in the way

        assertEquals(ReleaseCheckRun.Checked(1, mailed = 0), check.check(RUNNING, NOW))
        assertEquals(0, mailbox.receivedMessages.size)
        assertEquals(null, notices.notices().single().mailedAt, "kept for when the key is back")

        // Control: the same row, its password now known to be sealed under the current key, sends.
        QuarkusTransaction.requiringNew().run {
            InstanceMailSettingsEntity.findById(InstanceMailSettingsEntity.SINGLETON_ID)!!.passwordFingerprint = keyGuard.currentFingerprint()
        }
        assertEquals(ReleaseCheckRun.Checked(0, mailed = 1), check.check(RUNNING, NOW.plusSeconds(86_400)))
    }

    @Test
    fun aFailedSendLeavesTheNoticeForTheNextCheck() {
        configureAndConfirm()
        pointRelayAt(DEAD_PORT)
        source.mirror("/m", listOf("v7.3" to "2027-02-01", "v7.4" to "2027-03-01"))

        assertEquals(ReleaseCheckRun.Checked(1, mailed = 0), check.check(RUNNING, NOW))
        assertEquals(null, notices.notices().single().mailedAt, "the claim was given back")

        pointRelayAt(INSTANCE_PORT)
        assertEquals(ReleaseCheckRun.Checked(0, mailed = 1), check.check(RUNNING, NOW.plusSeconds(86_400)))
        assertEquals(1, noticeMails().size)
    }

    // --- helpers

    private fun owner(): RequestSpecification = givenAdminChannel().header("Authorization", bearer(OWNER_ID))

    private fun save(port: Int) {
        val outcome = handler.update(OWNER_ID, "127.0.0.1", port, FROM, null, null, tls = false)
        assertTrue(outcome is InstanceMailUpdateOutcome.Ok, "$outcome")
    }

    private fun confirm(code: String) =
        owner().contentType(ContentType.JSON).body("""{"code":"$code"}""").`when`().post("$PATH/confirmation").then()

    private fun configureAndConfirm() {
        save(INSTANCE_PORT)
        confirm(codeIn(mailsTo(OWNER_EMAIL).single())).statusCode(200).body("confirmation", equalTo("CONFIRMED"))
    }

    /** Move the confirmed relay without a save (a save would ask again): what a relay that went down looks like. */
    private fun pointRelayAt(port: Int) {
        QuarkusTransaction.requiringNew().run { InstanceMailSettingsEntity.findById(InstanceMailSettingsEntity.SINGLETON_ID)!!.port = port }
    }

    private fun saveTenantRelay(tenantId: String, port: Int) {
        QuarkusTransaction.requiringNew().run {
            tenantStore.saveMailSettings(
                tenantId,
                MailSettingsRow(
                    host = "127.0.0.1", port = port, from = "noreply@tenant.example.com",
                    username = null, passwordCiphertext = null, passwordNonce = null, passwordFingerprint = null,
                    tls = false, enabled = true, subjectTemplate = null, bodyTemplate = null,
                ),
            )
        }
    }

    private fun setOwnerEmail(email: String) {
        QuarkusTransaction.requiringNew().run { VeyqUser.findById(OWNER_ID)!!.email = email }
    }

    private fun mailsTo(address: String): List<MimeMessage> {
        mailbox.waitForIncomingEmail(2_000, 1)
        return mailbox.receivedMessages.filter { m -> m.allRecipients.any { it.toString().equals(address, ignoreCase = true) } }
    }

    /** The mails that are not the code mail. */
    private fun noticeMails(): List<MimeMessage> = mailbox.receivedMessages.filterNot { it.subject.startsWith("Confirm your veyq instance mail") }

    private fun codeIn(mail: MimeMessage): String {
        val body = partsOf(mail).first { it.isMimeType("text/plain") }.content as String
        return CODE.find(body)?.groupValues?.get(1) ?: error("no code in:\n$body")
    }

    private fun wrong(code: String): String = code.map { if (it == '9') '0' else it + 1 }.joinToString("")

    private fun partsOf(mail: MimeMessage): List<Part> = when (val content = mail.content) {
        is MimeMultipart -> (0 until content.count).flatMap { i ->
            val part = content.getBodyPart(i)
            (part.content as? MimeMultipart)?.let { inner -> (0 until inner.count).map { inner.getBodyPart(it) } } ?: listOf(part)
        }
        else -> listOf(mail)
    }

    /**
     * An iCalendar document read as RFC 5545 says (§3.1): every physical line ends in CRLF and is at most 75 octets, a
     * line starting with a space continues the one before, `BEGIN`/`END` nest. Strict on purpose — a lenient reader
     * would accept what a mail client might not.
     */
    private class IcsDocument(val components: List<String>, private val properties: List<Pair<String, String>>) {
        fun property(name: String): Pair<String, String>? = properties.firstOrNull { it.first.substringBefore(';') == name }
        fun value(name: String): String? = property(name)?.second

        companion object {
            fun parse(raw: String): IcsDocument {
                assertTrue(raw.endsWith("\r\n"), "the document ends with CRLF")
                val physical = raw.removeSuffix("\r\n").split("\r\n")
                physical.forEach { line ->
                    assertFalse(line.contains('\n') || line.contains('\r'), "a bare line break inside a line: $line")
                    assertTrue(line.toByteArray(Charsets.UTF_8).size <= 75, "a line over 75 octets: $line")
                }
                val logical = mutableListOf<String>()
                physical.forEach { if (it.startsWith(" ")) logical[logical.size - 1] += it.substring(1) else logical += it }
                val stack = ArrayDeque<String>()
                val seen = mutableListOf<String>()
                val props = mutableListOf<Pair<String, String>>()
                logical.forEach { line ->
                    val colon = line.indexOf(':')
                    assertTrue(colon > 0, "a content line has a name and a value: $line")
                    val name = line.substring(0, colon)
                    val value = line.substring(colon + 1)
                    when (name) {
                        "BEGIN" -> { stack.addLast(value); seen += value }
                        "END" -> assertEquals(stack.removeLast(), value, "END closes the open component")
                        else -> { assertTrue(stack.isNotEmpty(), "a property outside a component: $line"); props += name to value }
                    }
                }
                assertTrue(stack.isEmpty(), "every component is closed")
                return IcsDocument(seen, props)
            }
        }
    }

    private companion object {
        const val RUNNING = "7.3"
        val NOW: Instant = Instant.parse("2027-06-01T04:00:00Z")
        const val PATH = "/api/instance/mail-settings"
        const val OWNER_ID = "0d4f6b8a-2c1e-4a3b-9d5f-7b9d1f3a5c84"
        const val OWNER_EMAIL = "instance-notice-owner@example.com"
        const val OTHER_EMAIL = "instance-notice-owner-new@example.com"
        const val FROM = "veyq@instance.example.com"
        const val INSTANCE_PORT = 13944
        const val TENANT_PORT = 13945
        const val DEAD_PORT = 13946 // nothing listens: a refused connection, at once
        val CODE = Regex("""(?m)^\s+([0-9]{8})\s*$""")
    }
}
