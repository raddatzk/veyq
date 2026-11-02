package me.raddatz.veyq.core.moderation.guestwrite

import me.raddatz.veyq.core.admin.operator.OperatorDetailsEntity
import me.raddatz.veyq.core.adminchannel.givenAdminChannel
import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.http.ClientAddress
import me.raddatz.veyq.core.http.ClientPortSetting
import me.raddatz.veyq.core.moderation.hold.HoldEntity
import me.raddatz.veyq.core.moderation.hold.HoldPinEntity
import me.raddatz.veyq.core.moderation.hold.HoldRowEntity
import me.raddatz.veyq.core.moderation.hold.HoldTarget
import me.raddatz.veyq.core.moderation.hold.HoldTrigger
import me.raddatz.veyq.core.moderation.hold.Holds
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.PUBLIC_SHARES_CONTRIBUTE
import me.raddatz.veyq.core.platform.capabilities.CapabilitySwitchEntity
import me.raddatz.veyq.core.platform.capabilities.CapabilitySwitchFixtures
import me.raddatz.veyq.core.platform.capabilities.TenantCapabilitySwitchFixtures
import me.raddatz.veyq.core.sharing.GuestTreeShare
import me.raddatz.veyq.core.sharing.GuestTreeShare.Link
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.ShareWriteProof
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.specification.RequestSpecification
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The guest-write record with core alone (ADR 0093 § 10, en.moderation.guest-write-record): which writes record, what a
 * row holds, the address of ADR 0094's resolver and never one the client chose, that it has no switch and needs no mode
 * (ADR 0093 § 14), the two-limit purge, the hold's copy, the disclosure on the resolve and the retention setting.
 *
 * The requests come from loopback with the default empty trust list, so the rightmost `X-Forwarded-For` entry is the
 * client (ADR 0094 decision 3) — the same setup as the rate-limit tests. The `header` port source is stated for one test
 * through [ClientPortSetting] (`QuarkusMock`) instead of a profile: `:core`'s test heap holds no further boot.
 *
 * The record table, the retention row, the switches and the operator row are shared H2 state: every test filters by its
 * own objects, and [restore] puts the retention, the switches and the operator row back.
 */
@QuarkusTest
class GuestWriteRecordCoreSliceTest {

    @Inject lateinit var records: GuestWriteRecords
    @Inject lateinit var holds: Holds
    @Inject lateinit var config: VeyqConfig
    @Inject lateinit var em: EntityManager

    private data class Tree(val userId: String, val scopeId: String, val root: String, val a: String, val link: Link)

    private fun tree(role: String = ShareEntity.READ_WRITE): Tree {
        val (userId, scopeId) = seedMemberScope()
        val outer = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val root = seedNode(scopeId, NodeEntity.FOLDER, parentId = outer, wrappedContentKey = CONTENT_KEY)
        val a = seedNode(scopeId, NodeEntity.FOLDER, parentId = root, wrappedContentKey = CONTENT_KEY)
        return Tree(userId, scopeId, root, a, GuestTreeShare.seed(userId, scopeId, root, role))
    }

    @BeforeEach
    fun start() = restore()

    @AfterEach
    fun restore() {
        QuarkusTransaction.requiringNew().run {
            GuestWriteRetentionEntity.deleteAll()
            OperatorDetailsEntity.deleteAll()
        }
        CapabilitySwitchFixtures.clear()
        TenantCapabilitySwitchFixtures.clear()
    }

    // --- the address: ADR 0094's resolver, never the client's choice (ADR 0094 decision 3) ---------------------------

    @Test
    fun theRightmostEntryIsRecordedAndTheClientsOwnLeftmostIsInNoRecordAndNoLog() {
        val t = tree()
        val logs = capture {
            val id = guestCreate(t, xff = "198.51.100.66, 203.0.113.77")
            assertEquals(listOf("203.0.113.77" to null), seen(id), "the stop entry, with no port: it carries none")
        }
        assertEquals(0L, count("clientIp = ?1", "198.51.100.66"), "the client's own leftmost entry is in no record")
        assertFalse(logs.contains("198.51.100.66"), "…and in no log record")
        assertFalse(logs.contains("203.0.113.77"), "the recorded address rides no log either (ADR 0092 § 1)")
    }

    @Test
    fun aStopEntrysOwnPortIsRecorded() {
        val t = tree()
        val id = guestCreate(t, xff = "203.0.113.77:51234")
        assertEquals(listOf("203.0.113.77" to 51234), seen(id))
    }

    @Test
    fun aPortOnAnEntryLeftOfTheStopIsNeverRead() {
        val t = tree()
        val logs = capture {
            val id = guestCreate(t, xff = "198.51.100.66:4711, 203.0.113.77")
            assertEquals(listOf("203.0.113.77" to null), seen(id), "the stop entry carries no port: none is recorded")
        }
        assertEquals(0L, count("clientPort = ?1", 4711), "4711 is in no record")
        assertFalse(logs.contains("4711"), "…and in no log record")
    }

    /** ADR 0094 decision 7: the configured `header` source states the port the stop entry lacks; under `none` the same
     *  request records none, whatever the header says. */
    @Test
    fun theHeaderSourceStatesThePortAndNoneReadsNoHeader() {
        val t = tree()
        val none = guestCreate(t, xff = "203.0.113.77", portHeader = "203.0.113.77:51234")
        assertEquals(listOf("203.0.113.77" to null), seen(none), "under `none` the header is never read")
        QuarkusMock.installMockForType(HeaderSourceSetting(config), ClientPortSetting::class.java)
        val header = guestCreate(t, xff = "203.0.113.77", portHeader = "203.0.113.77:51234")
        assertEquals(listOf("203.0.113.77" to 51234), seen(header), "under `header` the edge's statement gives the port")
    }

    @Test
    fun noAddressNoRecordButTheWriteStands() {
        val t = tree()
        val id = guestCreate(t, xff = "unknown") // the stop entry is no IP address: the resolver yields none (ADR 0094)
        assertEquals(emptyList<Pair<String, Int?>>(), seen(id), "no address, nothing recorded")
        assertTrue(QuarkusTransaction.requiringNew().call { NodeEntity.findById(id) } != null, "the write itself stood")
        val control = guestCreate(t, xff = "203.0.113.77")
        assertEquals(1, seen(control).size, "control: the same write with an address records one")
    }

    // --- which writes record ------------------------------------------------------------------------------------------

    @Test
    fun namingItselfCreatingAndPlacingEachRecordOnceWithItsObjectAndFieldGroup() {
        val t = tree()
        val identityId = UUID.randomUUID().toString()
        given().contentType(ContentType.JSON).header(ShareWriteProof.HEADER, ShareWriteProof.token(t.link.shareId))
            .header("X-Forwarded-For", "203.0.113.10")
            .body("""{"identityId":"$identityId","sealedDisplayName":"bmFtZQ==","nodeEpoch":0}""")
            .`when`().post("/api/shares/${t.link.shareId}/identities").then().statusCode(201)
        val node = guestCreate(t, xff = "203.0.113.11")
        GuestTreeShare.place(t.link, node, t.root, placementRevision = 0).statusCode(204)

        val identityRows = rows(identityId)
        assertEquals(listOf(Triple("IDENTITY", "name", identityId)), identityRows.map { Triple(it.objectKind, it.fieldGroup, it.shareIdentityId) })
        val nodeRows = rows(node).sortedBy { it.observedAt }
        assertEquals(listOf("NODE" to "name", "NODE" to "name"), nodeRows.map { it.objectKind to it.fieldGroup }, "the create, then the move")
        assertEquals("203.0.113.11", nodeRows.first().clientIp)
        val row = nodeRows.first()
        assertEquals(row.observedAt.plus(Duration.ofDays(7)), row.expiresAt, "kept N = 7 days from the write by default")
        assertEquals(0, row.observedAt.nano % 1_000_000, "the time is in milliseconds")
    }

    @Test
    fun aRefusedWriteAndAMembersWriteRecordNothing() {
        val t = tree()
        // A refused create (a scope epoch not the scope's) wrote nothing and records nothing.
        val refused = UUID.randomUUID().toString()
        GuestTreeShare.create(t.link, t.a, NodeEntity.FOLDER, nodeId = refused, scopeEpoch = 9).statusCode(409)
        assertEquals(0, seen(refused).size)
        // A member's create records nothing — control: the same create as a guest records one.
        val member = UUID.randomUUID().toString()
        given().header("Authorization", bearer(t.userId)).header("X-Forwarded-For", "203.0.113.12").contentType(ContentType.JSON)
            .body(
                """{"nodeId":"$member","parentId":"${t.a}","parentEpoch":0,"kind":"FOLDER","encryptedName":"bg==","slugToken":"${GuestTreeShare.slug()}",""" +
                    """"orderIndex":0,"wrappedContentKey":"a21lbQ==","epoch":0}""",
            )
            .`when`().post("/api/scopes/${t.scopeId}/nodes").then().statusCode(201)
        assertEquals(0, seen(member).size, "a member's write is never recorded")
        assertEquals(1, seen(guestCreate(t, xff = "203.0.113.12")).size, "control: a guest's is")
    }

    @Test
    fun anErasedNodeTakesItsRecordsWith() {
        val t = tree()
        val placeholder = guestCreate(t, xff = "203.0.113.13")
        assertEquals(1, seen(placeholder).size)
        GuestTreeShare.abandon(t.link, placeholder).statusCode(204) // the trash purge's erase, at once
        assertEquals(0, seen(placeholder).size, "the record went with its node (GuestWriteRecordNodeSubtreeEraser)")
    }

    // --- the switch, the purge and the hold -------------------------------------------------------------------------

    @Test
    fun thePurgeDeletesALiveRowAtItsEndWhileAHoldCopyStays() {
        val t = tree()
        val held = guestCreate(t, xff = "203.0.113.14")
        val holdId = QuarkusTransaction.requiringNew().call {
            holds.take(null, HoldTarget.Subtree(t.scopeId, listOf(held)), HoldTrigger.CASE_OPEN, Instant.now().plus(Duration.ofDays(1)))
        }
        try {
            records.purge(Instant.now())
            assertEquals(1, seen(held).size, "control: within N, the purge keeps the live row")
            records.purge(Instant.now().plus(Duration.ofDays(8)))
            assertEquals(0, seen(held).size, "past its end, the purge deletes the live row")
            val copies = QuarkusTransaction.requiringNew().call {
                HoldRowEntity.list("holdId = ?1 and sourceTable = ?2", holdId, "veyq_guest_write_record").map { it.payload }
            }
            assertEquals(1, copies.size, "the hold's copy of the held node's record stays")
            assertTrue(copies.single().contains("203.0.113.14"), "the copy is the row as stored")
        } finally {
            QuarkusTransaction.requiringNew().run {
                HoldRowEntity.delete("holdId", holdId); HoldPinEntity.delete("holdId", holdId); HoldEntity.deleteById(holdId)
            }
        }
    }

    /** A reported link (a share id, no UUID): the hold copies every record its guests wrote, and the share's own id —
     *  never a UUID — is no object to look up (it would be refused by the native UUID column). */
    @Test
    fun aHoldOnALinkCopiesEveryRecordItsGuestsWrote() {
        val t = tree()
        val other = tree()
        val mine = guestCreate(t, xff = "203.0.113.24")
        val theirs = guestCreate(other, xff = "203.0.113.25")
        val holdId = QuarkusTransaction.requiringNew().call {
            holds.take(null, HoldTarget.Link(t.scopeId, t.link.shareId), HoldTrigger.CASE_OPEN, Instant.now().plus(Duration.ofDays(1)))
        }
        try {
            val copies = QuarkusTransaction.requiringNew().call {
                HoldRowEntity.list("holdId = ?1 and sourceTable = ?2", holdId, "veyq_guest_write_record").map { it.payload }
            }
            assertTrue(copies.any { it.contains(mine) }, "the link's guest's record is held")
            assertFalse(copies.any { it.contains(theirs) }, "control: another link's guest's is not")
        } finally {
            QuarkusTransaction.requiringNew().run {
                HoldRowEntity.delete("holdId", holdId); HoldPinEntity.delete("holdId", holdId); HoldEntity.deleteById(holdId)
            }
        }
    }

    /**
     * The record has no switch of its own (ADR 0093 § 10): where guest writes are switched off, a guest cannot write, so
     * nothing is recorded — the register's AND reaches the record through the write itself (ADR 0088 decision 2).
     */
    @Test
    fun whereGuestWritesAreOffNothingIsWrittenAndNothingRecorded() {
        val t = tree()
        CapabilitySwitchFixtures.with(PUBLIC_SHARES_CONTRIBUTE) {
            val before = QuarkusTransaction.requiringNew().call { GuestWriteRecordEntity.count() }
            guestCreateRefused(t, xff = "203.0.113.20")
            assertEquals(before, QuarkusTransaction.requiringNew().call { GuestWriteRecordEntity.count() }, "a refused write records nothing")
        }
        assertEquals(1, seen(guestCreate(t, xff = "203.0.113.21")).size, "control: switched back on, the same write records one")
    }

    @Test
    fun aShorteningReachesAnOldRowAtTheNextPurgeAndALengtheningDoesNotExtendIt() {
        val t = tree()
        val old = guestCreate(t, xff = "203.0.113.17") // written under N = 7: its end is 7 days ahead
        records.setRetention(30) // a lengthening
        val newer = guestCreate(t, xff = "203.0.113.18") // written under N = 30
        records.purge(Instant.now().plus(Duration.ofDays(8)))
        assertEquals(0, seen(old).size, "eight days on, its own end passed: the lengthening to 30 never extended it")
        assertEquals(1, seen(newer).size, "control: a row written under N = 30 stays")

        val aged = guestCreate(t, xff = "203.0.113.19")
        QuarkusTransaction.requiringNew().run {
            GuestWriteRecordEntity.update("observedAt = ?1 where objectId = ?2", Instant.now().minus(Duration.ofDays(3)), aged)
        }
        records.setRetention(2) // a shortening reaches every row at once
        records.purge(Instant.now())
        assertEquals(0, seen(aged).size, "three days old, older than N as it stands now: gone at the next purge, its 30-day end aside")
        assertEquals(1, seen(newer).size, "control: younger than the new N, it stays")
    }

    // --- no operating mode, no switch (ADR 0093 §§ 10, 14) -----------------------

    /**
     * An instance set up with no mode and no operator value (`restore` leaves none): every fixed text renders whole, an
     * empty value as its gap, the record's pointer and the notice's record passages are there, and a guest write is
     * recorded. Positive control on the same predicate: a value set replaces its gap. Bites (cr-operating-mode-retires):
     * the capability check put back into [GuestWriteRecords.record] turns "recorded" red; a `switch` key put back on the
     * notice's record passages turns "the record passage" red.
     */
    @Test
    fun anInstanceWithNoModeServesEveryTextWholeAndRecordsAGuestWrite() {
        val section = given().`when`().get("/api/operator/terms").then().statusCode(200).extract().jsonPath()
        val text = section.getString("text.en")
        assertTrue(text.contains("### Authorities and contact"), "the whole section: the authorities")
        assertTrue(text.contains("Only an authority can ask"), "…the deferral")
        assertTrue(text.contains("Operator: not stated by this operator"), "an empty provider name shows as the gap")
        assertTrue(section.getString("record.en").contains("privacy notice of this instance"), "the record's pointer")

        val notice = given().`when`().get("/api/operator/privacy-notice").then().statusCode(200).extract().jsonPath()
        val ids = notice.getList<String>("passages.id")
        assertTrue("guest-write-record" in ids && "guest-write-record-objection" in ids, "the record passages: $ids")
        assertTrue(notice.getString("passages.find { it.id == 'controller' }.text.en").contains("not stated by this operator"))

        val t = tree()
        assertEquals(listOf("203.0.113.40" to null), seen(guestCreate(t, xff = "203.0.113.40")), "a guest write is recorded")

        // Control: a value set replaces its gap, in both texts.
        QuarkusTransaction.requiringNew().run {
            OperatorDetailsEntity().apply { providerName = "Beispiel e. V." }.persist()
        }
        assertTrue(given().`when`().get("/api/operator/terms").then().extract().jsonPath().getString("text.en").contains("Operator: Beispiel e. V."))
        assertTrue(
            given().`when`().get("/api/operator/privacy-notice").then().extract().jsonPath()
                .getString("passages.find { it.id == 'controller' }.text.en").contains("Beispiel e. V."),
        )
    }

    /** The migration that retired the switch deletes its rows, instance and tenant, and nothing else: its statements, run. */
    @Test
    fun theMigrationDeletesTheRetiredSwitchsRowsAndNoOther() {
        val statements = javaClass.classLoader.getResource("db/migration/V202610211101__core_guest_write_record_has_no_switch.sql")!!
            .readText().lines().filterNot { it.startsWith("--") }.joinToString("\n").split(";").map { it.trim() }.filter { it.isNotEmpty() }
        val tenantId = seedTenant()
        QuarkusTransaction.requiringNew().run {
            for ((table, id) in listOf("veyq_capability_switch" to RETIRED, "veyq_capability_switch" to PUBLIC_SHARES_CONTRIBUTE)) {
                em.createNativeQuery("INSERT INTO $table (id) VALUES (?1)").setParameter(1, id).executeUpdate()
            }
            for (id in listOf(RETIRED, PUBLIC_SHARES_CONTRIBUTE)) {
                em.createNativeQuery("INSERT INTO veyq_tenant_capability_switch (id, tenant_id, capability_id) VALUES (?1, ?2, ?3)")
                    .setParameter(1, UUID.randomUUID()).setParameter(2, UUID.fromString(tenantId)).setParameter(3, id).executeUpdate()
            }
            statements.forEach { em.createNativeQuery(it).executeUpdate() }
        }
        assertEquals(setOf(PUBLIC_SHARES_CONTRIBUTE), instanceSwitches(), "the retired id's instance row is gone; another stays")
        val tenantRows = QuarkusTransaction.requiringNew().call {
            em.createNativeQuery("SELECT capability_id FROM veyq_tenant_capability_switch WHERE tenant_id = ?1")
                .setParameter(1, UUID.fromString(tenantId)).resultList.map { it.toString() }
        }
        assertEquals(listOf(PUBLIC_SHARES_CONTRIBUTE), tenantRows, "…and its tenant row; another tenant row stays")
    }

    // --- the disclosure the resolve serves (decision 6) ---------------------------------------------------------------

    @Test
    fun theResolveServesNToAGuestThatWrites() {
        val write = tree()
        val read = tree(ShareEntity.READ)
        records.setRetention(12)
        resolve(write.link).body("guestWriteRecordDays", equalTo(12))
        resolve(read.link).body("guestWriteRecordDays", nullValue()) // a READ guest is recorded nowhere, so told nothing
    }

    // --- the retention setting (ADR 0089, ADR 0092 § 2) --------------------------------------------------------------

    @Test
    fun theServerOwnerSetsNWithinOneToThirty() {
        val owner = seedUser(serverRole = "SERVER_OWNER")
        fun admin(): RequestSpecification = givenAdminChannel().header("Authorization", bearer(owner))
        admin().`when`().get(PATH).then().statusCode(200).body("retentionDays", equalTo(7)).body("chosen", equalTo(false))
        admin().contentType(ContentType.JSON).body("""{"retentionDays":14}""").`when`().put(PATH).then().statusCode(200)
            .body("retentionDays", equalTo(14)).body("chosen", equalTo(true))
        for (bad in listOf("""{"retentionDays":0}""", """{"retentionDays":31}""", "{}")) {
            admin().contentType(ContentType.JSON).body(bad).`when`().put(PATH).then().statusCode(400).body("code", equalTo("invalid_request"))
        }
        assertEquals(14, records.retentionDays(), "nothing out of range was stored")
        givenAdminChannel().header("Authorization", bearer(seedUser())).`when`().get(PATH).then().statusCode(403)
    }

    // --- a guest's objection (GDPR Art. 21) ---------------------------------------------------------------------------

    /**
     * The Server Owner deletes one identity's live rows on the admin channel; what a hold copied stays, and the answer
     * counts it. Controls on the same predicate: another identity's row stays, and the held copy is still there. Bites:
     * delete by object instead of identity, or delete the hold's copies too, and a control goes red.
     */
    @Test
    fun anObjectionDeletesTheIdentitysLiveRowsAndLeavesWhatAHoldCopied() {
        val owner = seedUser(serverRole = "SERVER_OWNER")
        val t = tree()
        val other = tree()
        val held = guestCreate(t, xff = "203.0.113.30")
        val free = guestCreate(t, xff = "203.0.113.31")
        val theirs = guestCreate(other, xff = "203.0.113.32")
        val identity = rows(held).single().shareIdentityId
        assertEquals(identity, rows(free).single().shareIdentityId, "control: both writes are the one identity's")
        val holdId = QuarkusTransaction.requiringNew().call {
            holds.take(null, HoldTarget.Subtree(t.scopeId, listOf(held)), HoldTrigger.CASE_OPEN, Instant.now().plus(Duration.ofDays(1)))
        }
        fun erase(id: String, caller: String = owner) =
            givenAdminChannel().header("Authorization", bearer(caller)).`when`().delete("$PATH/identities/$id").then()
        try {
            erase(identity).statusCode(200).body("erased", equalTo(2)).body("keptInHolds", equalTo(1))
            assertEquals(0L, count("shareIdentityId = ?1", identity), "every live row of the identity is gone")
            assertEquals(1, seen(theirs).size, "control: another identity's row stays")
            val copies = QuarkusTransaction.requiringNew().call {
                HoldRowEntity.list("holdId = ?1 and sourceTable = ?2", holdId, "veyq_guest_write_record").map { it.payload }
            }
            assertEquals(1, copies.size, "the hold's copy stays until its hold ends")
            assertTrue(copies.single().contains("203.0.113.30"), "…and it is the held write's row")
            erase(identity).statusCode(200).body("erased", equalTo(0)).body("keptInHolds", equalTo(1)) // idempotent
            erase(identity, caller = seedUser()).statusCode(403)
            assertEquals(1, seen(theirs).size, "a non-owner deleted nothing")
        } finally {
            QuarkusTransaction.requiringNew().run {
                HoldRowEntity.delete("holdId", holdId); HoldPinEntity.delete("holdId", holdId); HoldEntity.deleteById(holdId)
            }
        }
    }

    @Test
    fun anIdentityIdThatIsNoUuidMatchesNoRoute() {
        val owner = seedUser(serverRole = "SERVER_OWNER")
        givenAdminChannel().header("Authorization", bearer(owner)).`when`().delete("$PATH/identities/not-a-uuid").then()
            .statusCode(404)
    }

    // --- helpers ----------------------------------------------------------------------------------------------------

    private fun guestCreate(t: Tree, xff: String, portHeader: String? = null): String {
        val id = UUID.randomUUID().toString()
        var spec = given().contentType(ContentType.JSON).header("X-Contributor-Token", t.link.token).header("X-Forwarded-For", xff)
        if (portHeader != null) spec = spec.header(ClientAddress.PORT_HEADER, portHeader)
        spec.body(
            """{"nodeId":"$id","parentId":"${t.a}","parentEpoch":0,"kind":"FOLDER","encryptedName":"bmFtZQ==",""" +
                """"slugToken":"${GuestTreeShare.slug()}","scopeEpoch":0,"wrappedContentKey":"d3JhcA=="}""",
        ).`when`().post("/api/shares/${t.link.shareId}/nodes").then().statusCode(201)
        return id
    }

    /** A guest's create that a switched-off link refuses; it is refused before any write. */
    private fun guestCreateRefused(t: Tree, xff: String) {
        given().contentType(ContentType.JSON).header("X-Contributor-Token", t.link.token).header("X-Forwarded-For", xff).body(
            """{"nodeId":"${UUID.randomUUID()}","parentId":"${t.a}","parentEpoch":0,"kind":"FOLDER","encryptedName":"bmFtZQ==",""" +
                """"slugToken":"${GuestTreeShare.slug()}","scopeEpoch":0,"wrappedContentKey":"d3JhcA=="}""",
        ).`when`().post("/api/shares/${t.link.shareId}/nodes").then().statusCode(org.hamcrest.Matchers.not(201))
    }

    private fun resolve(link: Link) = given().`when`().get("/api/shares/${link.shareId}").then().statusCode(200)

    private fun rows(objectId: String): List<GuestWriteRecordEntity> =
        QuarkusTransaction.requiringNew().call { GuestWriteRecordEntity.list("objectId", objectId) }

    private fun seen(objectId: String): List<Pair<String, Int?>> = rows(objectId).map { it.clientIp to it.clientPort }

    private fun count(query: String, value: Any): Long = QuarkusTransaction.requiringNew().call { GuestWriteRecordEntity.count(query, value) }

    private fun instanceSwitches(): Set<String> =
        QuarkusTransaction.requiringNew().call { CapabilitySwitchEntity.listAll().map { it.id }.toSet() }

    /** Every log record the app emits at its configured levels while [block] runs, as text; a control proves the capture. */
    private fun capture(block: () -> Unit): String {
        val records = mutableListOf<LogRecord>()
        val root = Logger.getLogger("")
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { records.add(record) }
            override fun flush() {}
            override fun close() {}
        }.apply { level = Level.ALL }
        root.addHandler(handler)
        try {
            block()
            Logger.getLogger("me.raddatz.veyq.core.moderation").info(CONTROL)
        } finally {
            root.removeHandler(handler)
        }
        assertTrue(records.any { it.message == CONTROL }, "the capture missed the control record — it is not attached where the logs go")
        return records.joinToString("\n") { "${it.message} ${it.parameters?.joinToString() ?: ""} ${it.thrown ?: ""}" }
    }

    /** The `header` source with the default header name, for one test ([ClientPortSetting]'s KDoc says why not a profile). */
    class HeaderSourceSetting(config: VeyqConfig) : ClientPortSetting(config) {
        override fun current(): VeyqConfig.Http.ClientPort = object : VeyqConfig.Http.ClientPort {
            override fun source() = VeyqConfig.ClientPortSource.HEADER
            override fun header() = ClientAddress.PORT_HEADER
        }
    }

    private companion object {
        const val PATH = "/api/instance/guest-write-record"
        const val CONTROL = "guest-write-record capture control"

        /** The switch the record had until cr-operating-mode-retires, as its stored rows spelt it. */
        const val RETIRED = "core.public-shares.guest-write-record"
    }
}
