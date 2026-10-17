package me.raddatz.veyq.core.moderation

import me.raddatz.veyq.core.admin.instance.InstanceMailSettingsEntity
import me.raddatz.veyq.core.admin.instance.InstanceMailSettingsHandler
import me.raddatz.veyq.core.admin.instance.InstanceMailUpdateOutcome
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.moderation.ModerationTestKit.appointAndAccept
import me.raddatz.veyq.core.moderation.ModerationTestKit.asOwner
import me.raddatz.veyq.core.moderation.ModerationTestKit.asUser
import me.raddatz.veyq.core.moderation.ModerationTestKit.enable
import me.raddatz.veyq.core.moderation.ModerationTestKit.epochKey
import me.raddatz.veyq.core.moderation.ModerationTestKit.proof
import me.raddatz.veyq.core.moderation.ModerationTestKit.sealedGrant
import me.raddatz.veyq.core.testing.TestFixtures.IDENTITY_PUBLIC_KEY
import me.raddatz.veyq.core.testing.TestFixtures.ensureUser
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.mail.Part
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Instance mail for moderators (ADR 0097 decisions 3, 5 and 6; ADR 0099 § 7): accepting mails a code to the
 * moderator's own address and typing it confirms; the instance mail reaches a confirmed moderator and never an
 * unconfirmed one; a step-down mails the Server Owner "rotation due" once. Over GreenMail on this class's own port.
 */
@QuarkusTest
class ModerationMailSliceTest {

    @Inject
    lateinit var settings: InstanceMailSettingsHandler

    @Inject
    lateinit var notices: ModeratorNotices

    @Inject
    lateinit var rotationDue: RotationDueNotice

    private lateinit var mailbox: GreenMail
    private lateinit var deputy: String
    private lateinit var address: String
    private val key = epochKey()

    @BeforeEach
    fun seed() {
        ModerationTestKit.reset()
        QuarkusTransaction.requiringNew().run { InstanceMailSettingsEntity.deleteAll() }
        ensureUser(OWNER, OWNER_EMAIL, "SERVER_OWNER")
        deputy = UUID.randomUUID().toString()
        address = "mail-deputy-$deputy@example.com"
        seedUser(id = deputy, email = address)
        mailbox = GreenMail(ServerSetup(PORT, "127.0.0.1", "smtp")).also { it.start() }
        val saved = settings.update(OWNER, "127.0.0.1", PORT, "veyq@example.com", null, null, tls = false)
        assertTrue(saved is InstanceMailUpdateOutcome.Ok, "$saved")
        enable(OWNER, key, IDENTITY_PUBLIC_KEY)
    }

    @AfterEach
    fun stop() {
        mailbox.stop()
        QuarkusTransaction.requiringNew().run { InstanceMailSettingsEntity.deleteAll() }
    }

    @Test
    fun acceptingMailsACodeToTheModeratorAndTheCodeConfirms() {
        appointAndAccept(OWNER, address, deputy)
        asUser(deputy).get("/api/moderation/appointment").then().body("confirmation", equalTo("CODE_SENT"))
        val mail = mailsTo(address).single()
        assertEquals(listOf(address), mail.allRecipients.map { it.toString() }, "only the moderator's own address")
        val code = codeIn(mail)
        confirm(code.map { if (it == '9') '0' else it + 1 }.joinToString("")).statusCode(409).body("code", equalTo("mail_code_invalid"))
        confirm(code).statusCode(200).body("confirmation", equalTo("CONFIRMED"))
        confirm(code).statusCode(409)
        asOwner(OWNER).get("/api/instance/moderation").then()
            .body("moderators.find { it.accountId == '$deputy' }.addressConfirmed", equalTo(true))
        // A changed account address unconfirms by itself.
        QuarkusTransaction.requiringNew().run { VeyqUser.findById(deputy)!!.email = "moved-$address" }
        asUser(deputy).get("/api/moderation/appointment").then().body("confirmation", equalTo("UNCONFIRMED"))
    }

    @Test
    fun aConfirmedModeratorIsMailedAndAnUnconfirmedOneIsNot() {
        appointAndAccept(OWNER, address, deputy)
        val other = UUID.randomUUID().toString().also { seedUser(id = it, email = "mail-other-$it@example.com") }
        appointAndAccept(OWNER, "mail-other-$other@example.com", other)
        confirm(codeIn(mailsTo(address).single())).statusCode(200)
        val sent = notices.mailConfirmedModerators("A notice", "Body")
        assertEquals(1, sent)
        mailbox.waitForIncomingEmail(2_000, 3)
        val notice = mailbox.receivedMessages.filter { it.subject == "A notice" }
        assertEquals(listOf(address), notice.flatMap { m -> m.allRecipients.map { it.toString() } })
    }

    @Test
    fun aStepDownMailsTheServerOwnerRotationDueOnce() {
        confirmOwner()
        appointAndAccept(OWNER, address, deputy)
        asUser(OWNER).contentType(ContentType.JSON).headers(proof(OWNER, "putModerationDelivery", key))
            .body(mapOf("identityPublicKey" to IDENTITY_PUBLIC_KEY, "grants" to listOf(mapOf("epoch" to 1, "sealedGrant" to sealedGrant()))))
            .put("/api/moderation/deliveries/$deputy").then().statusCode(204)
        asUser(deputy).headers(proof(deputy, "stepDownAsModerator", key)).delete("/api/moderation/appointment").then().statusCode(204)
        mailbox.waitForIncomingEmail(2_000, 3)
        assertEquals(1, mailbox.receivedMessages.count { it.subject == RotationDueNotice.SUBJECT })
        assertEquals(false, rotationDue.mailIfDue())
        assertEquals(1, mailbox.receivedMessages.count { it.subject == RotationDueNotice.SUBJECT })
    }

    private fun confirmOwner() {
        val code = codeIn(mailsTo(OWNER_EMAIL).single())
        asOwner(OWNER).body(mapOf("code" to code)).post("/api/instance/mail-settings/confirmation").then().statusCode(200)
    }

    private fun confirm(code: String) =
        asUser(deputy).contentType(ContentType.JSON).body(mapOf("code" to code)).post("/api/moderation/appointment/confirmation").then()

    private fun mailsTo(to: String): List<MimeMessage> {
        mailbox.waitForIncomingEmail(2_000, 1)
        return mailbox.receivedMessages.filter { m -> m.allRecipients.any { it.toString().equals(to, ignoreCase = true) } }
    }

    private fun codeIn(mail: MimeMessage): String {
        val body = partsOf(mail).first { it.isMimeType("text/plain") }.content as String
        return CODE.find(body)?.groupValues?.get(1) ?: error("no code in:\n$body")
    }

    private fun partsOf(mail: Part): List<Part> = when (val content = mail.content) {
        is MimeMultipart -> (0 until content.count).flatMap { partsOf(content.getBodyPart(it)) }
        else -> listOf(mail)
    }

    private companion object {
        const val OWNER = "2d2e2f30-3132-4334-8536-373839303a3b"
        const val OWNER_EMAIL = "mail-owner@example.com"
        const val PORT = 13961
        val CODE = Regex("""(?m)^\s+([0-9]{8})\s*$""")
    }
}
