package me.raddatz.veyq.core.admin.tenant

import me.raddatz.veyq.core.membership.TestGrants
import me.raddatz.veyq.core.scope.ScopeEntity
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
import org.hamcrest.Matchers.greaterThanOrEqualTo
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The permanent guard for cr-0163: a tenant-**owner** gate must key on an OWNER membership of the tenant **root
 * scope** (`scopeId == tenantId`), not on any OWNER row carrying that `tenantId`. Every scope birth mints its
 * creator an OWNER row (personal-scope auto-birth, group creation), so a `tenantId`-only check answered true for
 * ordinary members — a privilege escalation across invite mint/list/reissue/revoke, tenant-member removal, terms
 * update, and the owner-only member email (ADR 0023).
 *
 * Seeds the exact actor no existing test seeds: an ordinary tenant member who **owns their own scopes** (a personal
 * scope auto-born + a self-created group) but holds **no** OWNER row on the root scope. Under the old query that
 * actor read as a tenant owner; under the fixed one they are refused at every owner-gated endpoint. A second actor
 * — a real root-scope owner — still passes, proving the fix does not over-tighten.
 */
@QuarkusTest
class TenantOwnerGateTest {

    @Inject
    lateinit var tenantStore: TenantStore

    @Test
    fun ordinaryMemberWhoOwnsScopesIsRefusedAtEveryOwnerGatedEndpoint() {
        val tenantId = seedActiveTenant("gate.example.com")
        // the escalation actor: belongs to the tenant, OWNER of a personal scope AND a self-made group, but has NO
        // OWNER row on the root scope — a tenant *member*, not a tenant *owner*.
        val actor = seedUser(email = "actor@gate.example.com")
        membership(actor, tenantId, tenantId, MembershipEntity.MEMBER)
        seedScope(tenantId, ScopeEntity.PERSONAL).also { membership(actor, tenantId, it, MembershipEntity.OWNER) }
        val group = seedScope(tenantId, ScopeEntity.GROUP).also { membership(actor, tenantId, it, MembershipEntity.OWNER) }

        val a = bearer(actor)
        // --- the 5 TenantHandler ops + terms: the owner gate fires first, so a random invite/member id still 403s
        given().header("Authorization", a).contentType(ContentType.JSON).body("""{${TestGrants.memberInviteJson(tenantId, "x@gate.example.com")}}""")
            .`when`().post("/api/tenants/$tenantId/member-invites")
            .then().statusCode(403).body("code", equalTo("not_tenant_owner"))
        given().header("Authorization", a)
            .`when`().get("/api/tenants/$tenantId/member-invites")
            .then().statusCode(403).body("code", equalTo("not_tenant_owner"))
        given().header("Authorization", a).contentType(ContentType.JSON)
            .body("""{${TestGrants.memberInviteJson(tenantId, "x@gate.example.com")}}""")
            .`when`().post("/api/tenants/$tenantId/member-invites/${UUID.randomUUID()}/reissue")
            .then().statusCode(403).body("code", equalTo("not_tenant_owner"))
        given().header("Authorization", a)
            .`when`().delete("/api/tenants/$tenantId/member-invites/${UUID.randomUUID()}")
            .then().statusCode(403).body("code", equalTo("not_tenant_owner"))
        // the link way (ADR 0129): its mint and re-issue are owner-gated alike
        given().header("Authorization", a).contentType(ContentType.JSON).body("""{${TestGrants.memberInviteLinkJson(tenantId)}}""")
            .`when`().post("/api/tenants/$tenantId/member-invite-links")
            .then().statusCode(403).body("code", equalTo("not_tenant_owner"))
        given().header("Authorization", a).contentType(ContentType.JSON).body("""{${TestGrants.memberInviteLinkJson(tenantId)}}""")
            .`when`().post("/api/tenants/$tenantId/member-invite-links/${UUID.randomUUID()}/reissue")
            .then().statusCode(403).body("code", equalTo("not_tenant_owner"))
        given().header("Authorization", a)
            .`when`().delete("/api/tenants/$tenantId/members/${UUID.randomUUID()}")
            .then().statusCode(403).body("code", equalTo("not_tenant_owner"))
        given().header("Authorization", a).contentType(ContentType.JSON).body("""{"terms":{"de":"x","en":"y"}}""")
            .`when`().put("/api/tenants/$tenantId/terms")
            .then().statusCode(403).body("code", equalTo("not_tenant_owner"))

        // --- listScopeMembers (ADR 0023 email gate): the actor is a member+grant-holder of their own group, so they
        // pass the scope-member/grant gates, but as a non-tenant-owner they must see NO member emails.
        val other = seedUser(email = "other@gate.example.com").also { membership(it, tenantId, group, MembershipEntity.MEMBER) }
        given().header("Authorization", a)
            .`when`().get("/api/scopes/$group/members")
            .then().statusCode(200)
            .body("email.findAll { it != null }.size()", equalTo(0))

        // sanity: `other` is a real member row that a tenant owner *would* see the email of (asserted below)
        checkNotNull(other)
    }

    @Test
    fun realRootScopeOwnerStillPasses(): Unit = TestMailbox(GATE_SMTP_PORT).start().use { mail ->
        // The mint below is the proof that the owner gate *passes*, so this tenant has to be able to send:
        // since ADR 0055 §2 a member invite with no mailer is a 503, which would say nothing about authz.
        val tenantId = seedActiveTenant("owner.example.com", mail)
        val owner = seedUser(email = "owner@owner.example.com")
        membership(owner, tenantId, tenantId, MembershipEntity.OWNER) // OWNER on the root scope — a real tenant owner
        // a group the owner belongs to, with another member carrying an email
        val group = seedScope(tenantId, ScopeEntity.GROUP).also { membership(owner, tenantId, it, MembershipEntity.OWNER) }
        val member = seedUser(email = "m@owner.example.com").also { membership(it, tenantId, group, MembershipEntity.MEMBER) }

        val o = bearer(owner)
        // passes the owner gate: mint succeeds (201), list succeeds (200) — not the 403 the escalation actor gets
        given().header("Authorization", o).contentType(ContentType.JSON).body("""{${TestGrants.memberInviteJson(tenantId, "new@owner.example.com")}}""")
            .`when`().post("/api/tenants/$tenantId/member-invites")
            .then().statusCode(201)
        given().header("Authorization", o).contentType(ContentType.JSON).body("""{${TestGrants.memberInviteLinkJson(tenantId)}}""")
            .`when`().post("/api/tenants/$tenantId/member-invite-links")
            .then().statusCode(201)
        given().header("Authorization", o)
            .`when`().get("/api/tenants/$tenantId/member-invites")
            .then().statusCode(200)
        // and the owner-only email IS served to a real tenant owner (the flip's other side)
        given().header("Authorization", o)
            .`when`().get("/api/scopes/$group/members")
            .then().statusCode(200)
            .body("email.findAll { it != null }.size()", greaterThanOrEqualTo(1))
        checkNotNull(member)
    }

    // --- seed helpers ---

    private fun seedActiveTenant(domain: String, mailbox: TestMailbox? = null): String {
        val tenantId = seedTenant(domain = domain, ownerEmail = "o@$domain")
        mailbox?.receiveFor(tenantStore, tenantId, domain)
        return tenantId
    }

    /** One JVM for the whole suite ⇒ a port nothing else binds (13925-13937 are taken). */
    private companion object { const val GATE_SMTP_PORT = 13938 }

    private fun seedScope(tenantId: String, kind: String): String {
        val scopeId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            ScopeEntity().apply {
                id = scopeId; this.tenantId = tenantId; this.kind = kind
                if (kind == ScopeEntity.GROUP) encryptedName = "bmFtZQ=="
            }.persist()
        }
        return scopeId
    }

    private fun membership(userId: String, tenantId: String, scopeId: String, role: String) {
        QuarkusTransaction.requiringNew().run {
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); this.userId = userId; this.tenantId = tenantId
                this.scopeId = scopeId; this.role = role; wrappedGrant = "Z3JhbnQ="; createdAt = Instant.now()
            }.persist()
        }
    }
}
