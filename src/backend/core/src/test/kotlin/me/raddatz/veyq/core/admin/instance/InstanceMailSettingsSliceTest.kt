package me.raddatz.veyq.core.admin.instance

import me.raddatz.veyq.core.adminchannel.givenAdminChannel
import me.raddatz.veyq.core.crypto.ConfigSecretKeyGuard
import me.raddatz.veyq.core.crypto.ConfigSecretKeyState
import me.raddatz.veyq.core.crypto.ConfigSecretSeal
import me.raddatz.veyq.core.crypto.SealedSecret
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.ensureUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import io.restassured.specification.RequestSpecification
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.not
import org.hamcrest.CoreMatchers.nullValue
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The instance mail settings as a Server-Owner setting (uc.admin.learn-of-new-releases, ADR 0097 decision 3), over the
 * full HTTP stack: the password write-only and sealed at rest exactly as a tenant's (ADR 0050), its fingerprint part
 * of the instance-wide key state, a host without a from address refused, and the role gate. What the save sends and
 * what the code confirms are [InstanceNoticeMailSliceTest]'s.
 *
 * Every save here sends its test mail, so the hosts are under `.invalid` (RFC 2606: it never resolves): the send fails
 * at once, inside the JVM, instead of reaching a real relay on the internet.
 */
@QuarkusTest
class InstanceMailSettingsSliceTest {

    @Inject
    lateinit var seal: ConfigSecretSeal

    @Inject
    lateinit var guard: ConfigSecretKeyGuard

    @BeforeEach
    fun seed() {
        ensureUser(OWNER_ID, OWNER_EMAIL, "SERVER_OWNER")
        ensureUser(MEMBER_ID, "instance-mail-member@example.com", "MEMBER")
        clear()
    }

    @AfterEach
    fun restore() = clear()

    @Test
    fun aNewInstanceHasNoInstanceMailAndSaysWhereItWouldGo() {
        owner().`when`().get(PATH).then().statusCode(200)
            .body("host", nullValue())
            .body("passwordSet", equalTo(false))
            .body("confirmation", equalTo("NOT_CONFIGURED"))
            .body("confirmedAt", nullValue())
            .body("address", equalTo(OWNER_EMAIL))
    }

    @Test
    fun theOwnerConfiguresSmtpAndThePasswordIsWriteOnlyAndSealed() {
        save("""{"host":"smtp.veyq.invalid","port":2525,"from":"veyq@example.com","username":"veyq","password":"$SECRET","tls":true}""")
            .statusCode(200)
            .body("host", equalTo("smtp.veyq.invalid"))
            .body("port", equalTo(2525))
            .body("from", equalTo("veyq@example.com"))
            .body("username", equalTo("veyq"))
            .body("passwordSet", equalTo(true))
            .body("configSecretState", equalTo("OK"))

        val body = owner().`when`().get(PATH).then().statusCode(200).extract().asString()
        assertFalse(body.contains(SECRET), "the password never leaves the server")
        assertFalse(body.contains("\"password\":"), "not even as a field")
        val stored = row()
        assertNotNull(stored.passwordCiphertext)
        assertFalse(stored.passwordCiphertext!!.contains(SECRET), "sealed at rest, never plaintext")
        assertEquals(SECRET, seal.unseal(SealedSecret(stored.passwordCiphertext!!, stored.passwordNonce!!)), "control: it is the password, sealed")
        assertEquals(guard.currentFingerprint(), stored.passwordFingerprint, "sealed with the fingerprint of the key that sealed it")
    }

    @Test
    fun omittingThePasswordKeepsTheStoredOne() {
        save("""{"host":"smtp.veyq.invalid","from":"veyq@example.com","username":"veyq","password":"$SECRET","tls":true}""").statusCode(200)
        val sealed = row().passwordCiphertext

        save("""{"host":"smtp2.veyq.invalid","from":"veyq@example.com","username":"veyq","tls":false}""").statusCode(200)
            .body("host", equalTo("smtp2.veyq.invalid"))
            .body("tls", equalTo(false))
            .body("passwordSet", equalTo(true))
        assertEquals(sealed, row().passwordCiphertext)
    }

    @Test
    fun aHostWithoutAFromAddressIsRefusedAndNothingIsStored() {
        save("""{"host":"smtp.veyq.invalid","tls":true}""").statusCode(400).body("code", equalTo("invalid_request"))
        save("""{"host":"smtp.veyq.invalid","from":"not-an-address","tls":true}""").statusCode(400)
        save("""{"host":"smtp.veyq.invalid","from":"veyq@example.com"}""").statusCode(400) // a missing tls is no answer
        owner().`when`().get(PATH).then().statusCode(200).body("confirmation", equalTo("NOT_CONFIGURED"))
        save("""{"host":"smtp.veyq.invalid","from":"veyq@example.com","tls":true}""").statusCode(200) // control
    }

    @Test
    fun theInstanceMailPasswordJoinsTheInstanceWideKeyState() {
        assertEquals(ConfigSecretKeyState.OK, committedState(), "precondition")
        val sealed = seal.seal(SECRET)
        QuarkusTransaction.requiringNew().run {
            InstanceMailSettingsEntity().apply {
                host = "smtp.veyq.invalid"; fromAddress = "veyq@example.com"; username = "veyq"
                passwordCiphertext = sealed.ciphertext; passwordNonce = sealed.nonce; passwordFingerprint = "the-lost-key"
                ownerUserId = OWNER_ID
            }.persist()
        }

        assertEquals(ConfigSecretKeyState.MISMATCH, committedState(), "a password sealed under another key is seen instance-wide")
        owner().`when`().get("/api/instance/config-secret").then().statusCode(200).body("state", equalTo("MISMATCH"))
        owner().`when`().get(PATH).then().statusCode(200).body("configSecretState", equalTo("MISMATCH"))

        // Re-entering it seals it under the current key: the recovery path.
        save("""{"host":"smtp.veyq.invalid","from":"veyq@example.com","username":"veyq","password":"$SECRET","tls":true}""")
            .statusCode(200).body("configSecretState", equalTo("OK"))
        assertEquals(ConfigSecretKeyState.OK, committedState())
    }

    @Test
    fun aCodeIsRefusedWhenNoneIsOutstanding() {
        save("""{"host":"smtp.veyq.invalid","from":"veyq@example.com","tls":true}""").statusCode(200)
        owner().contentType(ContentType.JSON).body("""{"code":"12345678"}""")
            .`when`().post("$PATH/confirmation").then().statusCode(409).body("code", equalTo("mail_code_invalid"))
        owner().contentType(ContentType.JSON).body("""{"code":""}""")
            .`when`().post("$PATH/confirmation").then().statusCode(400)
    }

    @Test
    fun aMemberIsNotTheServerOwnerAndIsRefused() {
        val member = givenAdminChannel().header("Authorization", bearer(MEMBER_ID))
        member.`when`().get(PATH).then().statusCode(403)
        givenAdminChannel().header("Authorization", bearer(MEMBER_ID)).contentType(ContentType.JSON)
            .body("""{"host":"smtp.veyq.invalid","from":"veyq@example.com","tls":true}""").`when`().put(PATH).then().statusCode(403)
        givenAdminChannel().header("Authorization", bearer(MEMBER_ID)).contentType(ContentType.JSON)
            .body("""{"code":"12345678"}""").`when`().post("$PATH/confirmation").then().statusCode(403)
        assertEquals(null, QuarkusTransaction.requiringNew().call { InstanceMailSettingsEntity.findById(InstanceMailSettingsEntity.SINGLETON_ID) })
    }

    @Test
    fun theReadNamesNoTenantSetting() {
        owner().`when`().get(PATH).then().statusCode(200).body(not(containsString("subjectTemplate")))
    }

    private fun owner(): RequestSpecification = givenAdminChannel().header("Authorization", bearer(OWNER_ID))

    private fun save(json: String): ValidatableResponse =
        owner().contentType(ContentType.JSON).body(json).`when`().put(PATH).then()

    private fun row(): InstanceMailSettingsEntity =
        QuarkusTransaction.requiringNew().call { InstanceMailSettingsEntity.findById(InstanceMailSettingsEntity.SINGLETON_ID)!! }

    private fun committedState(): ConfigSecretKeyState = QuarkusTransaction.requiringNew().call { guard.readState() }

    companion object {
        private const val PATH = "/api/instance/mail-settings"
        private const val SECRET = "correct-horse-instance"
        private const val OWNER_ID = "5b8d0f2a-4c6e-4b1a-9d3f-7e1a3c5e7a92"
        private const val OWNER_EMAIL = "instance-mail-owner@example.com"
        private const val MEMBER_ID = "e1f3a5c7-9b2d-4f6a-8c0e-3a5c7e9b1d24"

        fun clear() {
            QuarkusTransaction.requiringNew().run { InstanceMailSettingsEntity.deleteAll() }
        }
    }
}
