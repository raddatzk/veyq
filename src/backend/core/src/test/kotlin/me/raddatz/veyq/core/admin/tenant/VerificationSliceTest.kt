package me.raddatz.veyq.core.admin.tenant

import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import jakarta.inject.Inject
import me.raddatz.veyq.core.account.erasure.AccountPurge
import me.raddatz.veyq.core.identity.AccountErasureEntity
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.membership.TenantRoots
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.tenant.TenantEntity
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Slice tests for the members' verifications of each other (ADR 0111 decisions 12-13; `listVerifications`,
 * `putMyVerification`): one opaque row per (tenant, verifier, subject), written only into the caller's own records,
 * read by every member of the tenant and by nobody else, and gone with either account, with the tenant, and with a
 * member's removal from it. Every refusal has its positive control on the same predicate, and every negative is
 * anchored on its status and its code. The gate's other states (a proposal, a suspended tenant) are
 * `OperatorBlindProbeTest`'s.
 */
@QuarkusTest
class VerificationSliceTest {

    @Inject lateinit var accountPurge: AccountPurge

    @Inject lateinit var tenantPurge: TenantPurge

    // --- write and read ---

    @Test
    fun aMembersVerificationIsReadByEveryMemberOfTheTenantTheWriterIncluded() {
        val t = world()
        put(t, t.a, t.b, sealed = S1).then().statusCode(204)

        for (reader in listOf(t.owner, t.a, t.b, t.c)) {
            list(t, reader).then().statusCode(200)
                .body("verifications.size()", equalTo(1))
                .body("verifications[0].verifierUserId", equalTo(t.a))
                .body("verifications[0].subjectUserId", equalTo(t.b))
                .body("verifications[0].epoch", equalTo(0))
                .body("verifications[0].sealedStatement", equalTo(S1))
                .body("more", equalTo(false))
        }
        // the stored time is the server's, served as it was written
        val createdAt = list(t, t.c).then().extract().path<String>("verifications[0].createdAt")
        assertTrue(Instant.parse(createdAt) <= Instant.now())
    }

    @Test
    fun anOutsiderGetsTheMembershipNotFoundOnReadAndOnWrite() {
        val t = world()
        val other = world()
        put(t, t.a, t.b, sealed = S1).then().statusCode(204) // control: a member writes
        list(t, t.a).then().statusCode(200).body("verifications.size()", equalTo(1)) // control: a member reads

        // a member of another tenant is a stranger here: what an invented tenant answers (ADR 0042), and nothing written
        list(t, other.a).then().statusCode(404).body("code", equalTo("tenant_not_found"))
        put(t, other.a, t.b, sealed = S2).then().statusCode(404).body("code", equalTo("tenant_not_found"))
        val invented = t.copy(tenantId = UUID.randomUUID().toString())
        list(invented, other.a).then().statusCode(404).body("code", equalTo("tenant_not_found"))
        put(invented, other.a, t.b, sealed = S2).then().statusCode(404).body("code", equalTo("tenant_not_found"))
        assertEquals(listOf(Row(t.a, t.b, 0, S1)), rows(t.tenantId))
        // and one tenant's rows never show through another's read
        list(other, other.a).then().statusCode(200).body("verifications.size()", equalTo(0))
        // no token: the generic 401
        given().`when`().get("/api/tenants/${t.tenantId}/verifications").then().statusCode(401)
    }

    @Test
    fun theVerifierIsAlwaysTheCallerAndASecondPutReplacesTheFirst() {
        val t = world()
        put(t, t.a, t.c, sealed = S1).then().statusCode(204)
        // B's put of subject C writes B's own row and leaves A's untouched
        put(t, t.b, t.c, sealed = S2).then().statusCode(204)
        assertEquals(listOf(Row(t.a, t.c, 0, S1), Row(t.b, t.c, 0, S2)).sortedBy { it.verifier }, rows(t.tenantId).sortedBy { it.verifier })

        // a second put by A of C replaces A's row: one row, the second's content, a new time
        val firstAt = storedAt(t.tenantId, t.a, t.c)
        bumpEpoch(t.tenantId, 1)
        put(t, t.a, t.c, sealed = S3, epoch = 1).then().statusCode(204)
        val after = rows(t.tenantId).filter { it.verifier == t.a }
        assertEquals(listOf(Row(t.a, t.c, 1, S3)), after)
        assertTrue(storedAt(t.tenantId, t.a, t.c) >= firstAt)
        assertEquals(Row(t.b, t.c, 0, S2), rows(t.tenantId).single { it.verifier == t.b }, "B's row is B's alone")
    }

    @Test
    fun aVerificationOfOneselfIsRefused() {
        val t = world()
        put(t, t.a, t.a, sealed = S1).then().statusCode(400).body("code", equalTo("verification_of_self"))
        assertEquals(emptyList<Row>(), rows(t.tenantId))
        put(t, t.a, t.b, sealed = S1).then().statusCode(204) // control: another subject is accepted
    }

    @Test
    fun aSubjectWhoIsNoMemberOfTheTenantIs404MemberNotFound() {
        val t = world()
        val outsider = world().a // a real account, a member of another tenant only
        val proposed = seedUser()
        QuarkusTransaction.requiringNew().run {
            // an unaccepted proposal on the tenant scope is no member either
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); userId = proposed; tenantId = t.tenantId; scopeId = t.tenantId
                role = MembershipEntity.MEMBER; wrappedGrant = "AQ=="; createdAt = Instant.now(); pendingConsent = true
            }.persist()
        }
        for (subject in listOf(outsider, proposed, UUID.randomUUID().toString())) {
            put(t, t.a, subject, sealed = S1).then().statusCode(404).body("code", equalTo("member_not_found"))
        }
        assertEquals(emptyList<Row>(), rows(t.tenantId))
        put(t, t.a, t.b, sealed = S1).then().statusCode(204) // control: a member is accepted
    }

    /** ADR 0115 decision 3: the tenant has one key at a time, and a rotation re-seals every verification under it — so
     *  a statement sealed under any other epoch, an earlier one as much as one ahead, is `409 stale_epoch`. */
    @Test
    fun anEpochOtherThanTheTenantScopesCurrentOneIsStale() {
        val t = world()
        bumpEpoch(t.tenantId, 2)
        put(t, t.a, t.b, sealed = S1, epoch = 3).then().statusCode(409).body("code", equalTo("stale_epoch"))
        put(t, t.a, t.c, sealed = S1, epoch = 0).then().statusCode(409).body("code", equalTo("stale_epoch"))
        assertEquals(emptyList<Row>(), rows(t.tenantId))
        put(t, t.a, t.b, sealed = S1, epoch = 2).then().statusCode(204) // control: the current epoch is accepted
        // a body outside the contract: the shape's 400
        putRaw(t, t.a, t.b, """{"epoch":-1,"sealedStatement":"$S1"}""").then().statusCode(400).body("code", equalTo("invalid_request"))
        putRaw(t, t.a, t.b, """{"epoch":0,"sealedStatement":"not base64!!"}""").then().statusCode(400).body("code", equalTo("invalid_request"))
        putRaw(t, t.a, t.b, """{"epoch":0,"sealedStatement":"${"A".repeat(516)}"}""").then().statusCode(400).body("code", equalTo("invalid_request"))
        assertEquals(Row(t.a, t.b, 2, S1), rows(t.tenantId).single { it.subject == t.b }, "the refused bodies changed nothing")
    }

    @Test
    fun aMissingEpochIs400AndNotEpochZero() {
        // A required integer the contract types `[integer, "null"]` so its absence is visible: Jackson would bind a
        // missing primitive to 0, the oldest epoch, and store the row (src/backend/CLAUDE.md § Validation).
        val t = world()
        putRaw(t, t.a, t.b, """{"sealedStatement":"$S1"}""").then().statusCode(400).body("code", equalTo("invalid_request"))
        putRaw(t, t.a, t.b, """{"epoch":null,"sealedStatement":"$S1"}""").then().statusCode(400).body("code", equalTo("invalid_request"))
        assertEquals(emptyList<Row>(), rows(t.tenantId))
        put(t, t.a, t.b, sealed = S1, epoch = 0).then().statusCode(204) // control: epoch 0 stated is accepted
    }

    // --- filters and paging ---

    @Test
    fun theFiltersNarrowAndThePagesTileTheSetInVerifierThenSubjectOrder() {
        val t = world()
        val d = joined(t)
        val written = listOf(t.a to t.b, t.a to t.c, t.a to d, t.b to t.a, t.b to t.c, t.c to d)
        written.forEach { (v, s) -> put(t, v, s, sealed = S1).then().statusCode(204) }

        val all = pairs(list(t, t.owner).then().statusCode(200).body("more", equalTo(false)).extract())
        assertEquals(written.toSet(), all.toSet())
        assertEquals(written.size, all.size)
        // ordered by verifier, then subject: the rows of one verifier stand together (the order within is the id
        // columns' own, which the cursor below compares by — the paging proves the two agree)
        val runs = all.map { it.first }.fold(listOf<String>()) { acc, v -> if (acc.lastOrNull() == v) acc else acc + v }
        assertEquals(all.map { it.first }.distinct(), runs)

        assertEquals(setOf(t.a to t.c, t.b to t.c), pairs(list(t, t.a, "subjectUserId" to t.c).then().statusCode(200).extract()).toSet())
        assertEquals(setOf(t.b to t.a, t.b to t.c), pairs(list(t, t.a, "verifierUserId" to t.b).then().statusCode(200).extract()).toSet())
        assertEquals(listOf(t.c to d), pairs(list(t, t.a, "verifierUserId" to t.c, "subjectUserId" to d).then().statusCode(200).extract()))

        // pages of two, each from the last row's cursor, tile the whole list in its order; `more` says whether rows follow
        val paged = mutableListOf<Pair<String, String>>()
        var after: String? = null
        var pages = 0
        while (true) {
            val params = buildList { add("limit" to "2"); after?.let { add("after" to it) } }.toTypedArray()
            val page = list(t, t.b, *params).then().statusCode(200).extract()
            val rows = pairs(page)
            paged += rows
            pages++
            val more = page.path<Boolean>("more")
            if (!more) break
            assertEquals(2, rows.size, "a page that says more follows is full")
            after = "${rows.last().first}:${rows.last().second}"
            assertTrue(pages < 10)
        }
        assertEquals(all, paged)
        assertEquals(3, pages)
        // the last row's cursor serves nothing more
        val last = all.last()
        list(t, t.b, "after" to "${last.first}:${last.second}").then().statusCode(200)
            .body("verifications.size()", equalTo(0)).body("more", equalTo(false))
        // a page exactly the set's size says nothing follows
        list(t, t.b, "limit" to "${all.size}").then().statusCode(200).body("more", equalTo(false))
        list(t, t.b, "limit" to "${all.size - 1}").then().statusCode(200).body("more", equalTo(true))
        // parameters outside the contract
        list(t, t.b, "after" to "nonsense").then().statusCode(400).body("code", equalTo("invalid_request"))
        list(t, t.b, "limit" to "0").then().statusCode(400).body("code", equalTo("invalid_request"))
        list(t, t.b, "limit" to "1001").then().statusCode(400).body("code", equalTo("invalid_request"))
    }

    // --- where the rows go ---

    @Test
    fun aMemberRemovedFromTheTenantLeavesNoRowThereAsVerifierOrSubject() {
        val t = world()
        val other = world()
        put(t, t.a, t.b, sealed = S1).then().statusCode(204)
        put(t, t.b, t.a, sealed = S1).then().statusCode(204)
        put(t, t.c, t.a, sealed = S1).then().statusCode(204)
        put(t, t.c, t.b, sealed = S1).then().statusCode(204) // control: names no A, so it stays
        // A is a member of another tenant too, and its rows there stay (the removal is this tenant's)
        QuarkusTransaction.requiringNew().run { TenantRoots.join(t.a, other.tenantId) }
        put(other, t.a, other.b, sealed = S2).then().statusCode(204)

        given().header("Authorization", bearer(t.owner))
            .`when`().delete("/api/tenants/${t.tenantId}/members/${t.a}").then().statusCode(204)

        assertEquals(listOf(Row(t.c, t.b, 0, S1)), rows(t.tenantId), "every row naming A in this tenant is gone, the rest stays")
        assertEquals(listOf(Row(t.a, other.b, 0, S2)), rows(other.tenantId), "A's rows in another tenant stay")
        // and the removed member can neither read nor write there any more
        list(t, t.a).then().statusCode(404).body("code", equalTo("tenant_not_found"))
        put(t, t.c, t.a, sealed = S1).then().statusCode(404).body("code", equalTo("member_not_found"))
    }

    @Test
    fun anErasedAccountLeavesNoRowAsVerifierNorAsSubject() {
        val t = world()
        put(t, t.a, t.b, sealed = S1).then().statusCode(204)
        put(t, t.b, t.a, sealed = S1).then().statusCode(204)
        put(t, t.c, t.b, sealed = S1).then().statusCode(204) // names no A: stays
        QuarkusTransaction.requiringNew().run {
            AccountErasureEntity().apply {
                accountId = t.a; requestedAt = Instant.now(); requestedBy = AccountErasureEntity.SELF; dueAt = Instant.now()
            }.persist()
        }

        accountPurge.purge(t.a)

        QuarkusTransaction.requiringNew().run { assertNull(VeyqUser.findById(t.a), "the account is erased") }
        assertEquals(listOf(Row(t.c, t.b, 0, S1)), rows(t.tenantId))
    }

    @Test
    fun aDeletedTenantLeavesNoRow() {
        val t = world()
        val other = world()
        put(t, t.a, t.b, sealed = S1).then().statusCode(204)
        put(other, other.a, other.b, sealed = S1).then().statusCode(204)
        QuarkusTransaction.requiringNew().run {
            TenantEntity.update("status = ?1, deletionDueAt = ?2 where id = ?3", TenantEntity.PENDING_DELETION, Instant.now(), t.tenantId)
        }

        tenantPurge.purge(t.tenantId)

        QuarkusTransaction.requiringNew().run { assertNull(TenantEntity.findById(t.tenantId), "the tenant is erased") }
        assertEquals(emptyList<Row>(), rows(t.tenantId))
        assertEquals(1, rows(other.tenantId).size, "another tenant's rows stay")
    }

    // --- helpers ---

    private data class Tenant(val tenantId: String, val owner: String, val a: String, val b: String, val c: String)

    private data class Row(val verifier: String, val subject: String, val epoch: Int, val sealed: String)

    /** An active tenant with an owner and three accepted members. */
    private fun world(): Tenant {
        val owner = seedUser()
        val tenantId = seedTenant(owner = owner)
        val (a, b, c) = List(3) { seedUser() }
        QuarkusTransaction.requiringNew().run { listOf(a, b, c).forEach { TenantRoots.join(it, tenantId) } }
        return Tenant(tenantId, owner, a, b, c)
    }

    private fun joined(t: Tenant): String {
        val u = seedUser()
        QuarkusTransaction.requiringNew().run { TenantRoots.join(u, t.tenantId) }
        return u
    }

    private fun bumpEpoch(tenantId: String, epoch: Int) {
        QuarkusTransaction.requiringNew().run { ScopeEntity.update("currentEpoch = ?1 where id = ?2", epoch, tenantId) }
    }

    private fun put(t: Tenant, caller: String, subject: String, sealed: String, epoch: Int = 0): Response =
        putRaw(t, caller, subject, """{"epoch":$epoch,"sealedStatement":"$sealed"}""")

    private fun putRaw(t: Tenant, caller: String, subject: String, body: String): Response =
        given().header("Authorization", bearer(caller)).contentType(ContentType.JSON).body(body)
            .`when`().put("/api/tenants/${t.tenantId}/verifications/$subject")

    private fun list(t: Tenant, caller: String, vararg params: Pair<String, String>): Response =
        given().header("Authorization", bearer(caller)).apply { params.forEach { (k, v) -> queryParam(k, v) } }
            .`when`().get("/api/tenants/${t.tenantId}/verifications")

    private fun pairs(r: io.restassured.response.ExtractableResponse<Response>): List<Pair<String, String>> {
        val v = r.path<List<String>>("verifications.verifierUserId")
        val s = r.path<List<String>>("verifications.subjectUserId")
        return v.zip(s)
    }

    private fun rows(tenantId: String): List<Row> = QuarkusTransaction.requiringNew().call {
        VerificationEntity.list("tenantId", tenantId).map { Row(it.verifierUserId, it.subjectUserId, it.epoch, it.sealedStatement) }
            .sortedWith(compareBy({ it.verifier }, { it.subject }))
    }

    private fun storedAt(tenantId: String, verifier: String, subject: String): Instant = QuarkusTransaction.requiringNew().call {
        VerificationEntity.findById(VerificationKey(tenantId, verifier, subject))!!.createdAt
    }

    private companion object {
        const val S1 = "c2VhbGVkLXZlcmlmaWNhdGlvbg=="
        const val S2 = "c2Vjb25kLXZlcmlmaWNhdGlvbg=="
        const val S3 = "dGhpcmQtdmVyaWZpY2F0aW9u"
    }
}
