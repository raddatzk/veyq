package me.raddatz.veyq.core.admin.tenant

import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
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
    fun anAcceptanceStoresTheServerTimeOncePerVersion() {
        // The acceptance time is audit evidence of WHEN which version was accepted (ADR 0035 §4): the server's own
        // clock, written once per version — a re-acceptance of the recorded version must not move it, a newer one does.
        val (tenantId, owner) = seedActiveTenant("terms-time.example.com", "owner@terms-time.example.com")
        val member = seedUser(email = "member@terms-time.example.com")
        seedMembership(tenantId, member, MembershipEntity.MEMBER)
        publish(owner, tenantId, "Regeln", "Rules")

        val before = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        accept(member, tenantId, 1)
        val (version, first) = storedAcceptance(member, tenantId)
        assertEquals(1, version)
        assertTrue(!first.isBefore(before) && !first.isAfter(Instant.now()), "accepted_at $first is not the server's now")

        // Move the stored time far into the past, so "kept" cannot be confused with "rewritten within the second".
        val past = Instant.parse("2020-01-01T00:00:00Z")
        setStoredAcceptedAt(member, tenantId, past)
        accept(member, tenantId, 1)
        assertEquals(1 to past, storedAcceptance(member, tenantId), "a re-acceptance of the same version moved the time")

        publish(owner, tenantId, "Regeln v2", "Rules v2")
        val beforeBump = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        accept(member, tenantId, 2)
        val (bumped, second) = storedAcceptance(member, tenantId)
        assertEquals(2, bumped)
        assertTrue(!second.isBefore(beforeBump), "accepting a newer version kept the old time $second")
    }

    @Test
    fun aClientSentAcceptanceTimeHasNoEffect() {
        // The contract carries only `version`; a time smuggled into the body is never bound, the server stamps its own.
        val (tenantId, owner) = seedActiveTenant("terms-clienttime.example.com", "owner@terms-clienttime.example.com")
        val member = seedUser(email = "member@terms-clienttime.example.com")
        seedMembership(tenantId, member, MembershipEntity.MEMBER)
        publish(owner, tenantId, "Regeln", "Rules")

        val before = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        given().header("Authorization", bearer(member)).contentType(ContentType.JSON)
            .body("""{"version":1,"acceptedAt":"2000-01-01T00:00:00Z","accepted_at":"2000-01-01T00:00:00Z"}""")
            .`when`().post("/api/tenants/$tenantId/terms/accept")
            .then().statusCode(200).body("acceptedVersion", equalTo(1))
        val (_, stored) = storedAcceptance(member, tenantId)
        assertTrue(!stored.isBefore(before), "accepted_at $stored came from the client, not the server")
    }

    @Test
    fun anAcceptWithoutItsVersionIs400() {
        // A missing `version` binds the primitive to 0, which `minimum: 1` refuses — the refusal is the bound's, so
        // lowering that bound to 0 would let an unversioned accept through.
        val (tenantId, _) = seedActiveTenant("terms-noversion.example.com", "owner@terms-noversion.example.com")
        val member = seedUser(email = "member@terms-noversion.example.com")
        seedMembership(tenantId, member, MembershipEntity.MEMBER)
        given().header("Authorization", bearer(member)).contentType(ContentType.JSON).body("{}")
            .`when`().post("/api/tenants/$tenantId/terms/accept")
            .then().statusCode(400)
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

    private fun publish(owner: String, tenantId: String, de: String, en: String) {
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body(termsBody(de, en))
            .`when`().put("/api/tenants/$tenantId/terms").then().statusCode(200)
    }

    private fun accept(member: String, tenantId: String, version: Int) {
        given().header("Authorization", bearer(member)).contentType(ContentType.JSON).body("""{"version":$version}""")
            .`when`().post("/api/tenants/$tenantId/terms/accept")
            .then().statusCode(200).body("acceptedVersion", equalTo(version))
    }

    private fun storedAcceptance(userId: String, tenantId: String): Pair<Int, Instant> =
        QuarkusTransaction.requiringNew().call {
            val row = TenantTermsAcceptanceEntity.find("userId = ?1 and tenantId = ?2", userId, tenantId).firstResult()!!
            row.acceptedVersion to row.acceptedAt
        }

    private fun setStoredAcceptedAt(userId: String, tenantId: String, at: Instant) =
        QuarkusTransaction.requiringNew().run {
            TenantTermsAcceptanceEntity.find("userId = ?1 and tenantId = ?2", userId, tenantId).firstResult()!!
                .acceptedAt = at
        }

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
