package me.raddatz.veyq.core.admin.tenant

import me.raddatz.veyq.core.admin.operator.OperatingMode
import me.raddatz.veyq.core.admin.operator.OperatingModeHandler
import me.raddatz.veyq.core.admin.operator.OperatorDetailsEntity
import me.raddatz.veyq.core.admin.operator.OperatorDetailsHandler
import me.raddatz.veyq.core.admin.operator.OperatorValues
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.moderation.guestwrite.GuestWriteRecords
import me.raddatz.veyq.core.moderation.guestwrite.GuestWriteRetentionEntity
import me.raddatz.veyq.core.platform.capabilities.CapabilitySwitchEntity
import me.raddatz.veyq.core.platform.capabilities.CapabilitySwitchFixtures
import me.raddatz.veyq.core.platform.capabilities.StoredCapabilitySwitches
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
import java.time.Instant
import java.util.UUID

/**
 * The operator section of the terms over the full HTTP layer (ADR 0035 decision 6; decisions 2-4 of
 * cr-operator-section-of-the-terms; uc.account.accept-terms, uc.admin.tenant-terms, uc.sharing.share-accept): served
 * after the space's text by the member read and the public read, in the operating mode's variant, and the gate on the
 * triple (space revision, operator revision, variant). Each negative stands beside its control on the same predicate.
 *
 * The operator row, the retention, the switches and the served text are instance-wide H2 state: [restore] puts each back.
 */
@QuarkusTest
class TenantTermsOperatorSectionSliceTest {

    @Inject lateinit var sections: OperatorSections
    @Inject lateinit var details: OperatorDetailsHandler
    @Inject lateinit var modes: OperatingModeHandler
    @Inject lateinit var switches: StoredCapabilitySwitches

    @BeforeEach
    fun start() = restore()

    @AfterEach
    fun restore() {
        QuarkusTransaction.requiringNew().run {
            GuestWriteRetentionEntity.deleteAll()
            OperatorDetailsEntity.deleteAll()
        }
        CapabilitySwitchFixtures.clear()
        modes.afterCommit()
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

    @Test
    fun thePrivateModeServesTheReducedVariant() {
        val (tenantId, member) = acceptedMember("terms-op-private.example.com")
        val org = read(member, tenantId)
        assertEquals("FULL", org.getString("operatorSection.variant"))
        val orgText = org.getString("operatorSection.text.en")
        assertTrue(orgText.contains("### Authorities and contact"), "control: organisation has the authorities")
        assertTrue(orgText.contains("Only an authority can ask"), "control: organisation has the deferral")
        assertTrue(orgText.contains("### Reporting content"), "control: organisation has the notice path")

        modes.set(OperatingMode.PRIVATE)
        val priv = read(member, tenantId)
        assertEquals("REDUCED", priv.getString("operatorSection.variant"))
        val privText = priv.getString("operatorSection.text.en")
        assertFalse(privText.contains("### Authorities and contact"), "private: no authorities passage")
        assertFalse(privText.contains("Only an authority can ask"), "private: no deferral passage")
        assertTrue(privText.contains("### Reporting content"), "private: the notice path stays")
    }

    @Test
    fun aModeChangeReopensTheGateAndARecordSwitchDoesNot() {
        val (tenantId, member) = acceptedMember("terms-op-mode.example.com")
        assertTrue(read(member, tenantId).getString("operatorSection.record.en").isNotEmpty(), "organisation: the record is on")

        // Control: switching the record off changes what is served, and reopens nothing.
        CapabilitySwitchFixtures.with(GuestWriteRecords.SWITCH) {
            val off = read(member, tenantId)
            assertNull(off.get<Any?>("operatorSection.record"), "the record's passage follows the switch")
            assertTrue(off.getBoolean("accepted"), "a record switch reopens no gate")
        }

        modes.set(OperatingMode.PRIVATE)
        val priv = read(member, tenantId)
        assertFalse(priv.getBoolean("accepted"), "the mode changed: the gate reopens")
        postAccept(member, tenantId, priv).then().statusCode(200)
        assertTrue(read(member, tenantId).getBoolean("accepted"))
        assertEquals(setOf("FULL", "REDUCED"), variantsAccepted(member, tenantId), "one history row per accepted triple")
    }

    @Test
    fun anAcceptNamingAnotherVariantIsRefused() {
        val (tenantId, member) = acceptedMember("terms-op-variant.example.com", accept = false)
        val r = read(member, tenantId)
        val body = """{"revision":${r.getInt("termsRevision")},"operatorRevision":${r.getInt("operatorSection.revision")},"variant":"REDUCED"}"""
        given().header("Authorization", bearer(member)).contentType(ContentType.JSON).body(body)
            .`when`().post("/api/tenants/$tenantId/terms/accept").then().statusCode(409)
        postAccept(member, tenantId, r).then().statusCode(200) // control: the served triple is accepted
    }

    @Test
    fun theRecordPassageFollowsItsSwitchInBothVariants() {
        val (tenantId, member) = acceptedMember("terms-op-record.example.com")
        fun record() = read(member, tenantId).get<Any?>("operatorSection.record")
        fun objection() = read(member, tenantId).get<Any?>("operatorSection.objection")

        assertTrue(record() != null && objection() != null, "organisation, record on (its preset)")
        CapabilitySwitchFixtures.with(GuestWriteRecords.SWITCH) {
            assertNull(record(), "organisation, record off")
            assertNull(objection(), "organisation, record off: no objection either")
        }

        modes.set(OperatingMode.PRIVATE) // its preset switches the record off
        assertNull(record(), "private, record off")
        QuarkusTransaction.requiringNew().run { CapabilitySwitchEntity.deleteById(GuestWriteRecords.SWITCH) }
        switches.invalidate()
        val on = read(member, tenantId)
        assertEquals("REDUCED", on.getString("operatorSection.variant"))
        assertTrue(on.getString("operatorSection.record.en").contains("Art. 6(1)(f) GDPR"), "private, record switched on by hand: the information is there")
        assertTrue(on.getString("operatorSection.objection.en").contains("Art. 21 GDPR"), "and the right to object, apart")
        assertFalse(on.getString("operatorSection.record.en").contains("not stated by this operator"), "reduced: an unset controller is absent, as in the private mode's privacy texts")
    }

    // --- helpers ---

    /** A tenant with published terms and a member who accepted the served triple (unless [accept] is false). */
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

    /** Accept the triple [shown] carried. */
    private fun postAccept(user: String, tenantId: String, shown: JsonPath) =
        given().header("Authorization", bearer(user)).contentType(ContentType.JSON)
            .body(
                """{"revision":${shown.getInt("termsRevision")},"operatorRevision":${shown.getInt("operatorSection.revision")},""" +
                    """"variant":"${shown.getString("operatorSection.variant")}"}""",
            )
            .`when`().post("/api/tenants/$tenantId/terms/accept")

    private fun variantsAccepted(userId: String, tenantId: String): Set<String?> =
        QuarkusTransaction.requiringNew().call {
            TenantTermsAcceptanceEntity.list("userId = ?1 and tenantId = ?2", userId, tenantId).map { it.variant }.toSet()
        }
}
