package me.raddatz.veyq.core.admin.tenant

import me.raddatz.veyq.core.admin.operator.OperatorDetailsEntity
import me.raddatz.veyq.core.admin.operator.OperatorDetailsHandler
import me.raddatz.veyq.core.admin.operator.OperatorValues
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.moderation.guestwrite.GuestWriteRecords
import me.raddatz.veyq.core.moderation.guestwrite.GuestWriteRetentionEntity
import me.raddatz.veyq.core.platform.capabilities.CapabilitySwitchFixtures
import me.raddatz.veyq.core.terms.OperatorSections
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.path.json.JsonPath
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

/**
 * The operator section of the terms over the full HTTP layer (ADR 0035 decision 6; decisions 2-4 of
 * cr-operator-section-of-the-terms; uc.account.accept-terms, uc.admin.tenant-terms, uc.sharing.share-accept): served
 * after the space's text by the member read and the public read, in its one form (ADR 0093 § 14), and the gate on the
 * pair (space revision, operator revision). Each negative stands beside its control on the same predicate.
 *
 * The operator row, the retention, the switches and the served text are instance-wide H2 state: [restore] puts each back.
 */
@QuarkusTest
class TenantTermsOperatorSectionSliceTest {

    @Inject lateinit var sections: OperatorSections
    @Inject lateinit var details: OperatorDetailsHandler
    @Inject lateinit var records: GuestWriteRecords
    @Inject lateinit var em: jakarta.persistence.EntityManager

    @BeforeEach
    fun start() = restore()

    @AfterEach
    fun restore() {
        QuarkusTransaction.requiringNew().run {
            GuestWriteRetentionEntity.deleteAll()
            OperatorDetailsEntity.deleteAll()
        }
        CapabilitySwitchFixtures.clear()
        sections.serve(null)
    }

    @Test
    fun aValueChangedInAdminWebRendersAtOnceAndReopensNoGate() {
        val (tenantId, member) = acceptedMember("terms-op-value.example.com")
        details.set(OperatorValues(providerName = "Neuer Verein e. V.", contactEmail = "kontakt@example.org"))

        val after = read(member, tenantId)
        assertTrue(after.getString("operatorSection.text.en").contains("Operator: Neuer Verein e. V."), "the value renders at once")
        assertTrue(after.getBoolean("accepted"), "a changed value reopens no gate")

        // Control: a release that changes the section's text (its revision) reopens it.
        sections.serve(sections.shippedText().let { it.copy(revision = it.revision + 1) })
        val bumped = read(member, tenantId)
        assertFalse(bumped.getBoolean("accepted"), "an operator-revision bump reopens the gate")
        // ... and the old operator revision is no longer acceptable.
        postAccept(member, tenantId, after).then().statusCode(409)
        postAccept(member, tenantId, bumped).then().statusCode(200)
        assertTrue(read(member, tenantId).getBoolean("accepted"))
    }

    @Test
    fun aSpaceWithNoTermsServesTheSectionWithoutAGate() {
        val owner = seedUser(email = "owner@terms-op-none.example.com")
        val tenantId = seedTenant(domain = "terms-op-none.example.com", ownerEmail = "owner@terms-op-none.example.com", owner = owner)
        val r = read(owner, tenantId)
        assertEquals(0, r.getInt("termsRevision"), "revision 0: nothing gates (the client gates only above 0)")
        assertNull(r.get<Any?>("terms"))
        assertTrue(r.getString("operatorSection.text.en").contains("## On this instance"), "the section is served all the same")

        // And publicly, without an account (DSA Art. 14(1)), the same section.
        val public = given().`when`().get("/api/operator/terms").then().statusCode(200).extract().jsonPath()
        assertEquals(r.getString("operatorSection.text.en"), public.getString("text.en"))
        assertEquals(r.getInt("operatorSection.revision"), public.getInt("revision"))
    }

    /**
     * The gate compares a pair (ADR 0035 decision 6): accepting it writes one history row, accepting it again none, and
     * the database itself holds one row per pair — a second row for the same pair is refused by the UNIQUE, a row for
     * another operator revision is not (the control on the same predicate). Bite (cr-operating-mode-retires): a variant
     * column left in the UNIQUE lets the duplicate in.
     */
    @Test
    fun aMemberAcceptsThePairAndTheHistoryHoldsOneRowPerPair() {
        val (tenantId, member) = acceptedMember("terms-op-pair.example.com")
        val shown = read(member, tenantId)
        postAccept(member, tenantId, shown).then().statusCode(200)
        assertEquals(1L, acceptances(member, tenantId), "accepting the pair again adds no row")

        fun insert(operatorRevision: Int) = QuarkusTransaction.requiringNew().run {
            TenantTermsAcceptanceEntity().apply {
                id = UUID.randomUUID().toString(); userId = member; this.tenantId = tenantId
                acceptedRevision = shown.getInt("termsRevision"); this.operatorRevision = operatorRevision; acceptedAt = Instant.now()
            }.persistAndFlush()
        }
        insert(shown.getInt("operatorSection.revision") + 1) // control: another pair is a row of its own
        assertEquals(2L, acceptances(member, tenantId))
        assertThrows<Exception>("the UNIQUE holds one row per pair") { insert(shown.getInt("operatorSection.revision")) }
        assertEquals(2L, acceptances(member, tenantId))
    }

    /**
     * The migration that made the acceptance a pair (V202610211103) keeps one row per pair of the history it found: where
     * a member had accepted both variants of one pair, the earlier row and its time stay. Its copy statement, run against
     * a history with the variant column as it stood; a row of another pair is the control.
     */
    @Test
    fun theMigrationKeepsTheEarliestRowOfAPairAcceptedInBothVariants() {
        val copy = javaClass.classLoader.getResource("db/migration/V202610211103__core_terms_acceptance_is_a_pair.sql")!!.readText()
            .let { sql -> sql.substring(sql.indexOf("INSERT INTO veyq_tenant_terms_acceptance")).substringBefore(";") }
        val (tenantId, member) = acceptedMember("terms-op-migration.example.com", accept = false)
        val (early, late, other) = List(3) { UUID.randomUUID().toString() }
        val t0 = java.time.LocalDateTime.of(2026, 10, 18, 9, 0)
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery(
                "CREATE TABLE veyq_tenant_terms_acceptance_prev (id UUID, user_id UUID, tenant_id UUID, accepted_revision INT, " +
                    "operator_revision INT, variant VARCHAR(16), accepted_at DATETIME(3))",
            ).executeUpdate()
            for ((id, operatorRevision, variant, at) in listOf(
                listOf(late, 2, "FULL", t0.plusHours(1)), listOf(early, 2, "REDUCED", t0), listOf(other, 1, "FULL", t0.plusHours(2)),
            )) {
                em.createNativeQuery("INSERT INTO veyq_tenant_terms_acceptance_prev VALUES (?1, ?2, ?3, 1, ?4, ?5, ?6)")
                    .setParameter(1, UUID.fromString(id as String)).setParameter(2, UUID.fromString(member)).setParameter(3, UUID.fromString(tenantId))
                    .setParameter(4, operatorRevision).setParameter(5, variant).setParameter(6, at).executeUpdate()
            }
        }
        try {
            QuarkusTransaction.requiringNew().run { em.createNativeQuery(copy).executeUpdate() }
            val kept = QuarkusTransaction.requiringNew().call {
                TenantTermsAcceptanceEntity.list("userId = ?1 and tenantId = ?2", member, tenantId).map { it.id }.toSet()
            }
            assertEquals(setOf(early, other), kept, "the earlier of the pair's two rows stays, and the other pair's row")
        } finally {
            QuarkusTransaction.requiringNew().run { em.createNativeQuery("DROP TABLE veyq_tenant_terms_acceptance_prev").executeUpdate() }
        }
    }

    @Test
    fun theRecordsPointerIsAlwaysServedAndReopensNoGate() {
        // The record's Art. 13 information is the privacy notice's (ADR 0035 decision 8); the section keeps a
        // pointer, always there since the record is kept wherever a guest may write (ADR 0093 § 10).
        val (tenantId, member) = acceptedMember("terms-op-record.example.com")
        val r = read(member, tenantId)
        assertTrue(r.getString("operatorSection.record.en").contains("privacy notice of this instance"))
        assertNull(r.get<Any?>("operatorSection.variant"), "the section has one form: no variant on the wire")
        assertNull(r.get<Any?>("operatorSection.objection"), "the right to object moved to the notice")
        records.setRetention(12)
        assertTrue(read(member, tenantId).getBoolean("accepted"), "a changed N reopens no gate")
    }

    // --- helpers ---

    /** A tenant with published terms and a member who accepted the served pair (unless [accept] is false). */
    private fun acceptedMember(domain: String, accept: Boolean = true): Pair<String, String> {
        val owner = seedUser(email = "owner@$domain")
        val tenantId = seedTenant(domain = domain, ownerEmail = "owner@$domain", owner = owner)
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{"terms":{"de":"Regeln","en":"Rules"}}""")
            .`when`().put("/api/tenants/$tenantId/terms").then().statusCode(200)
        val member = seedUser(email = "member@$domain")
        QuarkusTransaction.requiringNew().run {
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); userId = member; this.tenantId = tenantId
                scopeId = tenantId; role = MembershipEntity.MEMBER; wrappedGrant = "Z3JhbnQ="; createdAt = Instant.now()
            }.persist()
        }
        if (accept) {
            postAccept(member, tenantId, read(member, tenantId)).then().statusCode(200)
            assertTrue(read(member, tenantId).getBoolean("accepted"))
        }
        return tenantId to member
    }

    private fun read(user: String, tenantId: String): JsonPath =
        given().header("Authorization", bearer(user)).`when`().get("/api/tenants/$tenantId/terms")
            .then().statusCode(200).extract().jsonPath()

    /** Accept the pair [shown] carried. */
    private fun postAccept(user: String, tenantId: String, shown: JsonPath) =
        given().header("Authorization", bearer(user)).contentType(ContentType.JSON)
            .body("""{"revision":${shown.getInt("termsRevision")},"operatorRevision":${shown.getInt("operatorSection.revision")}}""")
            .`when`().post("/api/tenants/$tenantId/terms/accept")

    private fun acceptances(userId: String, tenantId: String): Long =
        QuarkusTransaction.requiringNew().call { TenantTermsAcceptanceEntity.count("userId = ?1 and tenantId = ?2", userId, tenantId) }
}
