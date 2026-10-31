package me.raddatz.veyq.core.admin.tenant

import me.raddatz.veyq.core.crypto.ConfigSecretSeal
import me.raddatz.veyq.core.crypto.SealedSecret
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice tests for per-tenant mail settings (uc.admin.tenant-mail-settings, cr-email-smtp-transport / ADR
 * 0048/0049/0050), over the full HTTP layer. Owner-gated CRUD; the SMTP **password is write-only** (never
 * returned, sealed at rest not plaintext); a template outside the closed variable set is rejected. Distinct
 * domains/emails so the JVM-lived H2 mem DB can't bleed state.
 */
@QuarkusTest
class TenantMailSettingsSliceTest {

    @Inject lateinit var store: TenantStore
    @Inject lateinit var seal: ConfigSecretSeal

    private fun settingsBody(
        host: String? = "smtp.example.com",
        port: Int? = 587,
        from: String? = "noreply@family.example.com",
        username: String? = "smtpuser",
        password: String? = "hunter2",
        tls: Boolean = true,
        enabled: Boolean = true,
        subjectTemplate: String? = null,
        bodyTemplate: String? = null,
    ): String {
        fun q(s: String?) = if (s == null) "null" else "\"${s.replace("\"", "\\\"")}\""
        return """
            {"host":${q(host)},"port":${port ?: "null"},"from":${q(from)},"username":${q(username)},
             "password":${q(password)},"tls":$tls,"enabled":$enabled,
             "subjectTemplate":${q(subjectTemplate)},"bodyTemplate":${q(bodyTemplate)}}
        """.trimIndent()
    }

    @Test
    fun ownerConfiguresSmtpAndThePasswordIsWriteOnly() {
        val (tenantId, owner) = seedActiveTenant("mail-set.example.com", "owner@mail-set.example.com")

        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body(settingsBody(subjectTemplate = "You're invited to {{domain}}", bodyTemplate = "Accept: {{link}} (until {{expiry}})"))
            .`when`().put("/api/tenants/$tenantId/mail-settings")
            .then().statusCode(200)
            .body("host", equalTo("smtp.example.com"))
            .body("port", equalTo(587))
            .body("enabled", equalTo(true))
            .body("passwordSet", equalTo(true))
            .body("subjectTemplate", equalTo("You're invited to {{domain}}"))
            .body("password", nullValue()) // write-only: never echoed

        // A subsequent read never returns the password either — only passwordSet.
        given().header("Authorization", bearer(owner))
            .`when`().get("/api/tenants/$tenantId/mail-settings")
            .then().statusCode(200)
            .body("passwordSet", equalTo(true))
            .body("username", equalTo("smtpuser"))
            .body("password", nullValue())
    }

    @Test
    fun theSmtpPasswordIsSealedAtRestNotPlaintext() {
        val (tenantId, owner) = seedActiveTenant("mail-seal.example.com", "owner@mail-seal.example.com")
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body(settingsBody(password = "s3cr3t-smtp-pw"))
            .`when`().put("/api/tenants/$tenantId/mail-settings").then().statusCode(200)

        val row = QuarkusTransaction.requiringNew().call<me.raddatz.veyq.core.admin.tenant.MailSettingsRow?> { store.mailSettingsFor(tenantId) }!!
        assertNotEquals("s3cr3t-smtp-pw", row.passwordCiphertext, "the stored column must be ciphertext, not the input")
        assertTrue(row.passwordSet)
        assertEquals("s3cr3t-smtp-pw", seal.unseal(SealedSecret(row.passwordCiphertext!!, row.passwordNonce!!)), "unsealing must recover the password")
    }

    @Test
    fun omittingThePasswordOnUpdateKeepsTheStoredOne() {
        val (tenantId, owner) = seedActiveTenant("mail-keep.example.com", "owner@mail-keep.example.com")
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body(settingsBody(password = "keep-me")).`when`().put("/api/tenants/$tenantId/mail-settings").then().statusCode(200)

        // Re-save without a password (null) but flipping `enabled` off — the stored secret must survive.
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body(settingsBody(password = null, enabled = false))
            .`when`().put("/api/tenants/$tenantId/mail-settings")
            .then().statusCode(200).body("enabled", equalTo(false)).body("passwordSet", equalTo(true))

        val row = QuarkusTransaction.requiringNew().call<me.raddatz.veyq.core.admin.tenant.MailSettingsRow?> { store.mailSettingsFor(tenantId) }!!
        assertEquals("keep-me", seal.unseal(SealedSecret(row.passwordCiphertext!!, row.passwordNonce!!)))
    }

    @Test
    fun aChangedDestinationWithoutAFreshPasswordIsRefusedAndKeepsTheOldSettings() {
        val (tenantId, owner) = seedActiveTenant("mail-redirect.example.com", "owner@mail-redirect.example.com")
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body(settingsBody(password = "keep-me")).`when`().put("/api/tenants/$tenantId/mail-settings").then().statusCode(200)

        // Each of host, port and tls decides where the kept password would be sent — each alone is refused.
        for (redirect in listOf(
            settingsBody(password = null, host = "smtp.attacker.example"),
            settingsBody(password = null, port = 25),
            settingsBody(password = null, tls = false),
        )) {
            given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
                .body(redirect).`when`().put("/api/tenants/$tenantId/mail-settings")
                .then().statusCode(400).body("code", equalTo("mail_password_required"))
        }
        val row = QuarkusTransaction.requiringNew().call<me.raddatz.veyq.core.admin.tenant.MailSettingsRow?> { store.mailSettingsFor(tenantId) }!!
        assertEquals("smtp.example.com", row.host)
        assertEquals(587, row.port)
        assertEquals(true, row.tls)

        // Clearing the host sends nothing, so it needs no password.
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body(settingsBody(password = null, host = null, enabled = false)).`when`().put("/api/tenants/$tenantId/mail-settings")
            .then().statusCode(200)

        // Supplying the password again with the new destination is the way through.
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body(settingsBody(password = "new-secret", host = "smtp.other.example"))
            .`when`().put("/api/tenants/$tenantId/mail-settings")
            .then().statusCode(200).body("host", equalTo("smtp.other.example")).body("passwordSet", equalTo(true))
    }

    @Test
    fun aTemplateOutsideTheClosedVariableSetIsRejectedAndNotStored() {
        val (tenantId, owner) = seedActiveTenant("mail-tmpl.example.com", "owner@mail-tmpl.example.com")
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body(settingsBody(bodyTemplate = "Welcome to {{scope}} — accept {{link}}"))
            .`when`().put("/api/tenants/$tenantId/mail-settings")
            .then().statusCode(400).body("code", equalTo("invalid_mail_template"))

        // Nothing was persisted — a rejected template can never reach send time.
        val row = QuarkusTransaction.requiringNew().call<me.raddatz.veyq.core.admin.tenant.MailSettingsRow?> { store.mailSettingsFor(tenantId) }
        assertNull(row, "a rejected update must not create a settings row")
    }

    @Test
    fun aSubjectTemplateSpanningLinesIsRefusedAndNotStored() {
        // A line break in the subject would start a header of the owner's choosing in every invite mail.
        val (tenantId, owner) = seedActiveTenant("mail-crlf.example.com", "owner@mail-crlf.example.com")
        for (subject in listOf("Hi {{domain}}\\nBcc: x@evil.example", "Hi {{domain}}\\r\\nBcc: x@evil.example")) {
            given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
                .body(settingsBody(subjectTemplate = subject)).`when`().put("/api/tenants/$tenantId/mail-settings")
                .then().statusCode(400)
        }
        val row = QuarkusTransaction.requiringNew().call<me.raddatz.veyq.core.admin.tenant.MailSettingsRow?> { store.mailSettingsFor(tenantId) }
        assertNull(row, "a refused update must not create a settings row")
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON) // control: one line is fine
            .body(settingsBody(subjectTemplate = "Hi {{domain}}")).`when`().put("/api/tenants/$tenantId/mail-settings")
            .then().statusCode(200)
    }

    @Test
    fun aMissingTlsOrEnabledIs400AndNotStored() {
        // A missing boolean must not bind to false — a missing `tls` would silently turn STARTTLS off.
        val (tenantId, owner) = seedActiveTenant("mail-nobool.example.com", "owner@mail-nobool.example.com")
        for (field in listOf("\"tls\":true,", "\"enabled\":true,")) {
            given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
                .body(settingsBody().replace(field, ""))
                .`when`().put("/api/tenants/$tenantId/mail-settings")
                .then().statusCode(400)
        }
        val row = QuarkusTransaction.requiringNew().call<me.raddatz.veyq.core.admin.tenant.MailSettingsRow?> { store.mailSettingsFor(tenantId) }
        assertNull(row, "a refused update must not create a settings row")
    }

    @Test
    fun nonOwnerMemberIsForbidden() {
        val (tenantId, _) = seedActiveTenant("mail-authz.example.com", "owner@mail-authz.example.com")
        val member = seedUser(email = "member@mail-authz.example.com")
        seedMembership(tenantId, member, MembershipEntity.MEMBER)

        given().header("Authorization", bearer(member))
            .`when`().get("/api/tenants/$tenantId/mail-settings")
            .then().statusCode(403).body("code", equalTo("not_tenant_owner"))

        given().header("Authorization", bearer(member)).contentType(ContentType.JSON).body(settingsBody())
            .`when`().put("/api/tenants/$tenantId/mail-settings")
            .then().statusCode(403).body("code", equalTo("not_tenant_owner"))
    }

    @Test
    fun aNonMemberIs404() {
        val (tenantId, _) = seedActiveTenant("mail-outsider.example.com", "owner@mail-outsider.example.com")
        val outsider = seedUser(email = "outsider@mail-outsider.example.com")
        given().header("Authorization", bearer(outsider))
            .`when`().get("/api/tenants/$tenantId/mail-settings")
            .then().statusCode(404).body("code", equalTo("tenant_not_found"))
    }

    // --- helpers (mirror TenantTermsSliceTest) ---

    private fun seedActiveTenant(domain: String, ownerEmail: String): Pair<String, String> {
        val ownerId = seedUser(email = ownerEmail)
        return seedTenant(domain = domain, ownerEmail = ownerEmail, owner = ownerId) to ownerId
    }

    private fun seedMembership(tenantId: String, userId: String, role: String) =
        QuarkusTransaction.requiringNew().run {
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); this.userId = userId; this.tenantId = tenantId
                scopeId = tenantId; this.role = role; wrappedGrant = "Z3JhbnQ="; createdAt = Instant.now()
            }.persist()
        }
}
