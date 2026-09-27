package com.veyq.app.features.admin.tenant

import com.veyq.app.shared.membership.MembershipEntity
import com.veyq.app.testing.TestFixtures.bearer
import com.veyq.app.testing.TestFixtures.seedTenant
import com.veyq.app.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Slice tests for server terms of service (uc.admin.tenant-terms + uc.account.accept-terms, cr-0114 / ADR 0035),
 * over the full HTTP layer: the **owner** sets per-locale terms (a normalized change bumps `terms_version`, an
 * idempotent re-save does not); a **non-owner** is refused; a **member** accepts the current version and is
 * **re-staled** by a bump; a stale accept is 409; a non-member read/accept is 404. Distinct domains/emails so
 * the JVM-lived H2 mem DB can't bleed state.
 */
@QuarkusTest
class TenantTermsSliceTest {

    private fun termsBody(de: String?, en: String?): String {
        val fields = buildList {
            if (de != null) add("\"de\":${quote(de)}")
            if (en != null) add("\"en\":${quote(en)}")
        }.joinToString(",")
        return """{"terms":{$fields}}"""
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    @Test
    fun ownerSetsTermsBumpsVersionAndIdempotentReSaveDoesNot() {
        val (tenantId, owner) = seedActiveTenant("terms-set.example.com", "owner@terms-set.example.com")

        // First publish → version 1, echoed back.
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body(termsBody("## Regeln\nSei nett.", "## Rules\nBe kind."))
            .`when`().put("/api/tenants/$tenantId/terms")
            .then().statusCode(200)
            .body("termsVersion", equalTo(1))
            .body("acceptedVersion", equalTo(0))
            .body("terms.de", equalTo("## Regeln\nSei nett."))
            .body("terms.en", equalTo("## Rules\nBe kind."))

        // Idempotent re-save (only whitespace differs → normalizes equal) → NO bump.
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body(termsBody("  ## Regeln\nSei nett.  ", "## Rules\nBe kind.\n"))
            .`when`().put("/api/tenants/$tenantId/terms")
            .then().statusCode(200).body("termsVersion", equalTo(1))

        // A real change → bump to 2.
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body(termsBody("## Regeln v2", "## Rules v2"))
            .`when`().put("/api/tenants/$tenantId/terms")
            .then().statusCode(200).body("termsVersion", equalTo(2))
    }

    @Test
    fun nonOwnerCannotUpdateTerms() {
        val (tenantId, _) = seedActiveTenant("terms-authz.example.com", "owner@terms-authz.example.com")
        val member = seedUser(email = "member@terms-authz.example.com")
        seedMembership(tenantId, member, MembershipEntity.MEMBER)

        given().header("Authorization", bearer(member)).contentType(ContentType.JSON)
            .body(termsBody("nope", "nope"))
            .`when`().put("/api/tenants/$tenantId/terms")
            .then().statusCode(403).body("code", equalTo("not_tenant_owner"))
    }

    @Test
    fun memberAcceptsThenABumpReStalesAndAStaleAcceptIs409() {
        val (tenantId, owner) = seedActiveTenant("terms-accept.example.com", "owner@terms-accept.example.com")
        val member = seedUser(email = "member@terms-accept.example.com")
        seedMembership(tenantId, member, MembershipEntity.MEMBER)

        // Owner publishes v1.
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body(termsBody("Regeln", "Rules"))
            .`when`().put("/api/tenants/$tenantId/terms").then().statusCode(200)

        // Member sees a stale acceptance (0 < 1) then accepts v1.
        given().header("Authorization", bearer(member))
            .`when`().get("/api/tenants/$tenantId/terms")
            .then().statusCode(200).body("termsVersion", equalTo(1)).body("acceptedVersion", equalTo(0))

        given().header("Authorization", bearer(member)).contentType(ContentType.JSON).body("""{"version":1}""")
            .`when`().post("/api/tenants/$tenantId/terms/accept")
            .then().statusCode(200).body("termsVersion", equalTo(1)).body("acceptedVersion", equalTo(1))

        // Owner bumps to v2 → the member is re-staled.
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body(termsBody("Regeln v2", "Rules v2"))
            .`when`().put("/api/tenants/$tenantId/terms").then().statusCode(200).body("termsVersion", equalTo(2))

        given().header("Authorization", bearer(member))
            .`when`().get("/api/tenants/$tenantId/terms")
            .then().statusCode(200).body("termsVersion", equalTo(2)).body("acceptedVersion", equalTo(1))

        // Accepting the now-stale version 1 is refused; accepting the current 2 clears the gate.
        given().header("Authorization", bearer(member)).contentType(ContentType.JSON).body("""{"version":1}""")
            .`when`().post("/api/tenants/$tenantId/terms/accept")
            .then().statusCode(409).body("code", equalTo("terms_version_changed"))

        given().header("Authorization", bearer(member)).contentType(ContentType.JSON).body("""{"version":2}""")
            .`when`().post("/api/tenants/$tenantId/terms/accept")
            .then().statusCode(200).body("acceptedVersion", equalTo(2))
    }

    @Test
    fun readOrAcceptByANonMemberIs404() {
        val (tenantId, owner) = seedActiveTenant("terms-outsider.example.com", "owner@terms-outsider.example.com")
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body(termsBody("Regeln", "Rules"))
            .`when`().put("/api/tenants/$tenantId/terms").then().statusCode(200)

        val outsider = seedUser(email = "outsider@terms-outsider.example.com")
        given().header("Authorization", bearer(outsider))
            .`when`().get("/api/tenants/$tenantId/terms")
            .then().statusCode(404).body("code", equalTo("tenant_not_found"))

        given().header("Authorization", bearer(outsider)).contentType(ContentType.JSON).body("""{"version":1}""")
            .`when`().post("/api/tenants/$tenantId/terms/accept")
            .then().statusCode(404).body("code", equalTo("tenant_not_found"))
    }

    // --- helpers ---

    /** An ACTIVE tenant + its TENANT scope + a granted OWNER membership. Returns (tenantId, ownerUserId). */
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
