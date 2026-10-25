package me.raddatz.veyq.expenses.pot

import me.raddatz.veyq.core.moderation.guestwrite.GuestWriteRecordEntity
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds
import me.raddatz.veyq.core.platform.capabilities.CapabilitySwitchFixtures
import me.raddatz.veyq.core.platform.capabilities.ExternalServiceStore
import me.raddatz.veyq.core.platform.capabilities.ServiceAddress
import me.raddatz.veyq.core.platform.capabilities.StoredExternalServiceAddresses
import me.raddatz.veyq.core.platform.capabilities.TenantCapabilitySwitchFixtures
import me.raddatz.veyq.core.sharing.GuestTreeShare
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.ShareIdentityEntity
import me.raddatz.veyq.core.sharing.ShareWriteProof
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.expenses.ExpensesNodeKinds
import me.raddatz.veyq.expenses.pot.PotWire.Rev
import me.raddatz.veyq.expenses.pot.PotWire.append
import me.raddatz.veyq.expenses.pot.PotWire.body
import me.raddatz.veyq.expenses.pot.PotWire.erase
import me.raddatz.veyq.expenses.pot.PotWire.guestAppend
import me.raddatz.veyq.expenses.pot.PotWire.guestList
import me.raddatz.veyq.expenses.pot.PotWire.list
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.inject.Inject
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.hamcrest.Matchers.containsInAnyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * A Pot's revisions (ADR 0108 decisions 3-6, CR decisions 1-6), each negative anchored on its status and code beside a
 * positive control on the same predicate: the append is all or none on its records' latest revisions; only a member's
 * erasure removes a revision, and only its record's earlier ones; a Pot is shared `READ` or `READ_WRITE`, never
 * `WRITE_ONLY`; a guest reads every revision but no writer, and each revision it appends is one guest write; switched
 * off, Expenses is read-only; a guest's read names the exchange-rate table exactly while its tenant has the rates on.
 */
@QuarkusTest
class PotSliceTest {

    @Inject
    lateinit var services: ExternalServiceStore

    @Inject
    lateinit var addresses: StoredExternalServiceAddresses

    @Test
    fun aStaleAppendLandsNothingOfItsBatch_aFreshOneLandsAll() {
        val (userId, scopeId) = seedMemberScope()
        val pot = seedNode(scopeId, ExpensesNodeKinds.POT, wrappedContentKey = CONTENT_KEY)
        val a = UUID.randomUUID().toString()
        val b = UUID.randomUUID().toString()
        val c = UUID.randomUUID().toString()
        append(userId, scopeId, pot, listOf(Rev(a), Rev(b))).statusCode(204)

        // b's revision 1 again: its latest is 1, so the whole batch is refused — a's revision 2 and the new c included.
        append(userId, scopeId, pot, listOf(Rev(a, 2), Rev(b, 1), Rev(c)))
            .statusCode(409).body("code", equalTo("stale_revision"))
            .body("stale.size()", equalTo(1)).body("stale[0].entryId", equalTo(b)).body("stale[0].revision", equalTo(1))
        assertEquals(listOf("$a:1", "$b:1").sorted(), addresses(pot), "nothing of the stale batch is stored")
        // a revision ahead of its record's latest names the latest; a new record's 0
        append(userId, scopeId, pot, listOf(Rev(c, 2)))
            .statusCode(409).body("stale[0].entryId", equalTo(c)).body("stale[0].revision", equalTo(0))

        append(userId, scopeId, pot, listOf(Rev(a, 2), Rev(b, 2), Rev(c))).statusCode(204) // control: on the latest, all land
        assertEquals(listOf("$a:1", "$a:2", "$b:1", "$b:2", "$c:1").sorted(), addresses(pot).sorted())
    }

    @Test
    fun aBatchOutsideItsContractIsRefusedWhole_andARecordBelongsToOnePot() {
        val (userId, scopeId) = seedMemberScope()
        val pot = seedNode(scopeId, ExpensesNodeKinds.POT, wrappedContentKey = CONTENT_KEY)
        val other = seedNode(scopeId, ExpensesNodeKinds.POT, wrappedContentKey = CONTENT_KEY)
        val a = UUID.randomUUID().toString()
        append(userId, scopeId, pot, listOf(Rev(a), Rev(a, 2))).statusCode(400).body("code", equalTo("invalid_request"))
        append(userId, scopeId, pot, listOf(Rev(a, body = body(length = 748)))).statusCode(400).body("code", equalTo("invalid_request"))
        assertEquals(emptyList<String>(), addresses(pot), "a refused batch stores nothing")
        // every size class is one length (744, 2792, 10984, 21908): the largest lands
        append(userId, scopeId, pot, listOf(Rev(a, body = body(length = 21908)))).statusCode(204)

        append(userId, scopeId, other, listOf(Rev(a))).statusCode(409).body("code", equalTo("id_taken"))
        append(userId, scopeId, other, listOf(Rev(a, 2))).statusCode(409).body("code", equalTo("id_taken"))
        assertEquals(emptyList<String>(), addresses(other))
        append(userId, scopeId, pot, listOf(Rev(a, 2))).statusCode(204) // control: in its own Pot

        // a Folder is no Pot; a trashed Pot takes nothing and still reads
        val folder = seedNode(scopeId, "FOLDER", wrappedContentKey = CONTENT_KEY)
        append(userId, scopeId, folder, listOf(Rev())).statusCode(404).body("code", equalTo("node_not_found"))
        given().header("Authorization", bearer(userId)).`when`().delete("/api/scopes/$scopeId/nodes/$pot").then().statusCode(204)
        append(userId, scopeId, pot, listOf(Rev(a, 3))).statusCode(404).body("code", equalTo("node_not_found"))
        list(userId, scopeId, pot).statusCode(200).body("revisions.size()", equalTo(2))
    }

    @Test
    fun aGuestErasesNothing_aMembersErasureRemovesTheRecordsEarlierRevisionsOnly() {
        val (userId, scopeId) = seedMemberScope()
        val pot = seedNode(scopeId, ExpensesNodeKinds.POT, wrappedContentKey = CONTENT_KEY)
        val link = GuestTreeShare.seed(userId, scopeId, pot, ShareEntity.READ_WRITE)
        val person = UUID.randomUUID().toString()
        val entry = UUID.randomUUID().toString()
        append(userId, scopeId, pot, listOf(Rev(person, body = body('P')), Rev(entry))).statusCode(204)
        append(userId, scopeId, pot, listOf(Rev(person, 2, body = body('Q')))).statusCode(204)

        // A link has no erasure: no route on its own path (an unmatched route, Quarkus' own 404), none on the member's.
        given().contentType(ContentType.JSON)
            .body("""{"nodeEpoch":0,"contributorToken":"${link.token}","entryId":"$person","revision":3,"sealedBody":"${body('E')}","wrappedRevisionKey":"${PotWire.WRAP}"}""")
            .`when`().post("/api/shares/${link.shareId}/pots/$pot/erasures").then().statusCode(404)
        given().contentType(ContentType.JSON)
            .body("""{"nodeEpoch":0,"entryId":"$person","revision":3,"sealedBody":"${body('E')}","wrappedRevisionKey":"${PotWire.WRAP}"}""")
            .`when`().post("/api/scopes/$scopeId/pots/$pot/erasures").then().statusCode(401).body("code", equalTo("authentication_failed"))
        // A guest's delete is a revision: nothing it had is gone.
        guestAppend(link, pot, listOf(Rev(person, 3, tombstone = true))).statusCode(204)
        assertEquals(listOf("$entry:1", "$person:1", "$person:2", "$person:3").sorted(), addresses(pot).sorted())

        erase(userId, scopeId, pot, entry, 5).statusCode(409).body("code", equalTo("stale_revision"))
            .body("stale[0].entryId", equalTo(entry)).body("stale[0].revision", equalTo(1))
        erase(userId, scopeId, pot, UUID.randomUUID().toString(), 2).statusCode(404).body("code", equalTo("node_not_found"))
        assertEquals(4, addresses(pot).size, "a refused erasure erases nothing")

        erase(userId, scopeId, pot, person, 4).statusCode(204) // control: a member's erasure
        assertEquals(listOf("$entry:1", "$person:4").sorted(), addresses(pot).sorted(), "only the record's earlier revisions went")
        assertFalse(bodies(pot).any { it == body('P') || it == body('Q') }, "no earlier spelling of the name is held")
        list(userId, scopeId, pot).statusCode(200)
            .body("deleteRevision", equalTo(1))
            .body("revisionIds.entryId", containsInAnyOrder(entry, person))
            .body("revisions.find { it.entryId == '$person' }.erasure", equalTo(true))
        list(userId, scopeId, pot, "?sinceDelete=1").statusCode(200).body("revisionIds", nullValue())
    }

    @Test
    fun aWriteOnlyLinkOnAPotIsRefused_aReadWriteOneIsCreated() {
        val (userId, scopeId) = seedMemberScope()
        val pot = seedNode(scopeId, ExpensesNodeKinds.POT, wrappedContentKey = CONTENT_KEY)
        createShare(userId, pot, "WRITE_ONLY").statusCode(422).body("code", equalTo("illegal_scope"))
        assertEquals(0L, QuarkusTransaction.requiringNew().call { ShareEntity.count("nodeId", pot) })
        createShare(userId, pot, "READ_WRITE").statusCode(201) // control
        createShare(userId, pot, "READ").statusCode(201)
    }

    @Test
    fun aGuestReadsNoWriter_aMemberReadsEachOne() {
        val (userId, scopeId) = seedMemberScope()
        val pot = seedNode(scopeId, ExpensesNodeKinds.POT, wrappedContentKey = CONTENT_KEY)
        val link = GuestTreeShare.seed(userId, scopeId, pot, ShareEntity.READ_WRITE)
        val identity = QuarkusTransaction.requiringNew().call { ShareIdentityEntity.find("shareId", link.shareId).firstResult()!!.id }
        val mine = UUID.randomUUID().toString()
        val theirs = UUID.randomUUID().toString()
        append(userId, scopeId, pot, listOf(Rev(mine))).statusCode(204)
        guestAppend(link, pot, listOf(Rev(theirs))).statusCode(204)

        list(userId, scopeId, pot).statusCode(200)
            .body("revisions.find { it.entryId == '$mine' }.writer", equalTo("user:$userId"))
            .body("revisions.find { it.entryId == '$theirs' }.writer", equalTo("share:$identity"))
            .body("guestWriters.size()", equalTo(1)).body("guestWriters[0].identityId", equalTo(identity))
            .body("guestWriters[0].shareId", equalTo(link.shareId)).body("guestWriters[0].sealedName", notNullValue())
        val guest = guestList(link.shareId, pot).statusCode(200).body("revisions.size()", equalTo(2)).extract()
        for (i in 0..1) assertFalse(guest.path<Map<String, Any?>>("revisions[$i]").containsKey("writer"), "a guest is served no writer")
        assertFalse(guest.path<Map<String, Any?>>("").containsKey("guestWriters"), "nor anyone's name")

        // The guest's tag is a digest of what it is served: an echo is a 304, a new revision a new tag.
        val tag = guest.header("ETag")
        guestList(link.shareId, pot, ifNoneMatch = tag).statusCode(304)
        append(userId, scopeId, pot, listOf(Rev(mine, 2))).statusCode(204)
        val next = guestList(link.shareId, pot, ifNoneMatch = tag).statusCode(200).extract().header("ETag")
        assertNotEquals(tag, next)
        // pages by (entry id, revision), the later one untagged
        val first = guestList(link.shareId, pot, "?limit=2").statusCode(200).body("more", equalTo(true)).extract()
        guestList(link.shareId, pot, "?limit=2&after=${first.path<String>("cursor")}").statusCode(200)
            .body("revisions.size()", equalTo(1)).body("more", equalTo(false)).header("ETag", nullValue())
        // A Pot outside the link's subtree is answered as an unknown link.
        val elsewhere = seedNode(scopeId, ExpensesNodeKinds.POT, wrappedContentKey = CONTENT_KEY)
        guestList(link.shareId, elsewhere).statusCode(404).body("code", equalTo("share_not_found"))
    }

    @Test
    fun aGuestBatchRecordsOneGuestWritePerRevision_aStaleOneNone() {
        val (userId, scopeId) = seedMemberScope()
        val pot = seedNode(scopeId, ExpensesNodeKinds.POT, wrappedContentKey = CONTENT_KEY)
        val link = GuestTreeShare.seed(userId, scopeId, pot, ShareEntity.READ_WRITE)
        val revs = List(3) { Rev() }
        guestAppend(link, pot, revs, xff = "203.0.113.61:41000").statusCode(204)
        val rows = QuarkusTransaction.requiringNew().call { PotRevisionEntity.list("nodeId", pot).map { it.id }.toSet() }
        val records = QuarkusTransaction.requiringNew().call { GuestWriteRecordEntity.list("clientIp", "203.0.113.61") }
        assertEquals(rows, records.map { it.objectId }.toSet(), "one record per revision, naming its row")
        assertEquals(setOf("ITEM" to null), records.map { it.objectKind to it.fieldGroup }.toSet())
        assertEquals(3, records.size)

        guestAppend(link, pot, listOf(revs[0].copy(revision = 1), Rev()), xff = "203.0.113.62").statusCode(409)
        assertEquals(0L, QuarkusTransaction.requiringNew().call { GuestWriteRecordEntity.count("clientIp", "203.0.113.62") }, "a refused batch records nothing")

        // A READ link writes nothing.
        val read = GuestTreeShare.seed(userId, scopeId, pot, ShareEntity.READ)
        guestAppend(read, pot, listOf(Rev())).statusCode(403).body("code", equalTo("share_not_contributable"))
    }

    @Test
    fun offExpensesIsReadOnly_anAppendIsRefusedWhileTheListReadsAndThePotGoesToTheTrash() {
        val (userId, scopeId, tenantId) = seedMemberScope()
        val pot = seedNode(scopeId, ExpensesNodeKinds.POT, wrappedContentKey = CONTENT_KEY)
        val link = GuestTreeShare.seed(userId, scopeId, pot, ShareEntity.READ_WRITE)
        val a = UUID.randomUUID().toString()
        append(userId, scopeId, pot, listOf(Rev(a))).statusCode(204)
        TenantCapabilitySwitchFixtures.with(tenantId, CapabilityIds.EXPENSES) {
            append(userId, scopeId, pot, listOf(Rev(a, 2))).statusCode(422).body("code", equalTo("capability_disabled"))
            guestAppend(link, pot, listOf(Rev(a, 2))).statusCode(422).body("code", equalTo("capability_disabled"))
            list(userId, scopeId, pot).statusCode(200).body("revisions.size()", equalTo(1))
            guestList(link.shareId, pot).statusCode(200).body("revisions.size()", equalTo(1))
        }
        assertEquals(listOf("$a:1"), addresses(pot), "nothing landed while off")
        append(userId, scopeId, pot, listOf(Rev(a, 2))).statusCode(204) // control: on, it lands
        TenantCapabilitySwitchFixtures.with(tenantId, CapabilityIds.EXPENSES) {
            given().header("Authorization", bearer(userId)).`when`().delete("/api/scopes/$scopeId/nodes/$pot").then().statusCode(204)
        }
    }

    /**
     * Inbox #543: a guest's idle poll — its echoed tag still the one of what the read would serve — is a `304` that reads
     * no revision row, however large the Pot (the tag is remembered per the Pot's item revision and the rate switch,
     * [SharePotTags]). Bites: every change a guest is served still moves the tag — a member's edit, a guest's own
     * append, a tombstone, an erasure — and each is a read of the rows again. Control: a read with no tag reads them.
     */
    @Test
    fun aGuestsIdlePollReadsNoRevision_everyChangeStillMovesTheTag() {
        val (userId, scopeId) = seedMemberScope()
        val pot = seedNode(scopeId, ExpensesNodeKinds.POT, wrappedContentKey = CONTENT_KEY)
        val link = GuestTreeShare.seed(userId, scopeId, pot, ShareEntity.READ_WRITE)
        val dinner = UUID.randomUUID().toString()
        val anna = UUID.randomUUID().toString()
        append(userId, scopeId, pot, (1..120).map { Rev() } + Rev(dinner) + Rev(anna)).statusCode(204)
        val readsRevisions = { sql: List<String> -> sql.any { it.contains("veyq_expenses_revision") } }

        var tag = guestList(link.shareId, pot).statusCode(200).extract().header("ETag")
        val idle = StatementLog.during { guestList(link.shareId, pot, ifNoneMatch = tag).statusCode(304) }
        assertFalse(readsRevisions(idle), "an idle poll reads no revision: $idle")
        val fresh = StatementLog.during { guestList(link.shareId, pot).statusCode(200) }
        assertTrue(readsRevisions(fresh), "control: a read with no tag reads the rows")

        val changes = listOf<Pair<String, () -> Unit>>(
            "a member's edit" to { append(userId, scopeId, pot, listOf(Rev(dinner, 2))).statusCode(204) },
            "a guest's append" to { guestAppend(link, pot, listOf(Rev())).statusCode(204) },
            "a tombstone" to { append(userId, scopeId, pot, listOf(Rev(dinner, 3, tombstone = true))).statusCode(204) },
            "an erasure" to { erase(userId, scopeId, pot, anna, 2).statusCode(204) },
        )
        for ((what, change) in changes) {
            change()
            val next = guestList(link.shareId, pot, ifNoneMatch = tag).statusCode(200).extract().header("ETag")
            assertNotEquals(tag, next, "$what moves the guest's tag")
            tag = next
            guestList(link.shareId, pot, ifNoneMatch = tag).statusCode(304)
        }
    }

    /**
     * The guest's half of `expenses.exchange-rates` (ADR 0108 decision 8, option (a), owner 2026-10-25): a guest's client
     * holds only the instance's manifest, so its Pot read carries the table's address exactly while the entry is active
     * for the Pot's tenant. Negative: the tenant owner switched the rates off while the instance has them on — the guest's
     * device must not fetch, as the notice on that tenant's host names no fetch. Control: both on, the address served.
     */
    @Test
    fun aGuestsReadNamesTheRateTableExactlyWhileItsTenantHasTheRatesOn() {
        val (userId, scopeId, tenantId) = seedMemberScope()
        val pot = seedNode(scopeId, ExpensesNodeKinds.POT, wrappedContentKey = CONTENT_KEY)
        val link = GuestTreeShare.seed(userId, scopeId, pot, ShareEntity.READ_WRITE)
        // control: on at both levels, under Expenses on, and configured — the seeded ECB table's address
        val on = guestList(link.shareId, pot).statusCode(200).body("exchangeRateService", equalTo(SEEDED_RATES)).extract().header("ETag")

        TenantCapabilitySwitchFixtures.with(tenantId, CapabilityIds.EXPENSES_EXCHANGE_RATES) {
            val off = guestList(link.shareId, pot, ifNoneMatch = on).statusCode(200).body("exchangeRateService", nullValue()).extract().header("ETag")
            assertNotEquals(on, off, "a polling guest learns the switch")
        }
        TenantCapabilitySwitchFixtures.with(tenantId, CapabilityIds.EXPENSES) {
            guestList(link.shareId, pot).statusCode(200).body("exchangeRateService", nullValue()) // under Expenses
        }
        CapabilitySwitchFixtures.with(CapabilityIds.EXPENSES_EXCHANGE_RATES) {
            guestList(link.shareId, pot).statusCode(200).body("exchangeRateService", nullValue()) // the instance's switch
        }
        setRates(null)
        try {
            guestList(link.shareId, pot).statusCode(200).body("exchangeRateService", nullValue()) // no address
        } finally {
            setRates(SEEDED_RATES)
        }
        guestList(link.shareId, pot, ifNoneMatch = on).statusCode(304) // back on: the very read the guest held
    }

    private fun setRates(url: String?) {
        QuarkusTransaction.requiringNew().run { services.set(CapabilityIds.EXPENSES_EXCHANGE_RATES, url?.let { ServiceAddress(it, null) }) }
        addresses.invalidate()
    }

    @Test
    fun theMembersListIsAConditionalKeysetDelta() {
        val (userId, scopeId) = seedMemberScope()
        val pot = seedNode(scopeId, ExpensesNodeKinds.POT, wrappedContentKey = CONTENT_KEY)
        list(userId, scopeId, pot).statusCode(200).body("revisions.size()", equalTo(0)).body("ledgerRevision", equalTo(0))
        append(userId, scopeId, pot, listOf(Rev(), Rev())).statusCode(204)
        append(userId, scopeId, pot, listOf(Rev())).statusCode(204)
        val first = list(userId, scopeId, pot, "?limit=2").statusCode(200)
            .body("revisions.size()", equalTo(2)).body("more", equalTo(true)).body("ledgerRevision", equalTo(2)).extract()
        val rest = list(userId, scopeId, pot, "?limit=2&since=${first.path<String>("cursor")}").statusCode(200)
            .body("revisions.size()", equalTo(1)).body("more", equalTo(false)).extract()
        val etag = first.header("ETag")
        list(userId, scopeId, pot, ifNoneMatch = etag).statusCode(304)
        // a poll past the cursor carries only what was appended since
        val late = Rev()
        append(userId, scopeId, pot, listOf(late)).statusCode(204)
        list(userId, scopeId, pot, "?since=${rest.path<String>("cursor")}", ifNoneMatch = etag).statusCode(200)
            .body("revisions.size()", equalTo(1)).body("revisions[0].entryId", equalTo(late.entryId)).body("revisionIds", nullValue())
    }

    /** A Pot's purge out of the trash takes its revisions and the guest-write records naming them (control: another Pot's stay). */
    @Test
    fun aPurgedPotTakesItsRevisionsAndTheirGuestWriteRecords() {
        val (userId, scopeId) = seedMemberScope()
        val pot = seedNode(scopeId, ExpensesNodeKinds.POT, wrappedContentKey = CONTENT_KEY)
        val kept = seedNode(scopeId, ExpensesNodeKinds.POT, wrappedContentKey = CONTENT_KEY)
        val link = GuestTreeShare.seed(userId, scopeId, pot, ShareEntity.READ_WRITE)
        append(userId, scopeId, pot, listOf(Rev())).statusCode(204)
        guestAppend(link, pot, listOf(Rev()), xff = "203.0.113.71").statusCode(204)
        append(userId, scopeId, kept, listOf(Rev())).statusCode(204)
        given().header("Authorization", bearer(userId)).`when`().delete("/api/scopes/$scopeId/nodes/$pot").then().statusCode(204)
        QuarkusTransaction.requiringNew().run { subtreePurge.eraseExpiredIn(scopeId, java.time.Instant.now().plusSeconds(1)) }
        assertEquals(emptyList<String>(), addresses(pot))
        assertEquals(0L, QuarkusTransaction.requiringNew().call { GuestWriteRecordEntity.count("clientIp", "203.0.113.71") })
        assertEquals(1, addresses(kept).size, "control: the live Pot keeps its revision")
    }

    @jakarta.inject.Inject lateinit var subtreePurge: me.raddatz.veyq.core.content.NodeSubtreePurge

    // --- helpers ---------------------------------------------------------------------------------------------------

    private fun addresses(pot: String): List<String> =
        QuarkusTransaction.requiringNew().call { PotRevisionEntity.list("nodeId", pot).map { "${it.entryId}:${it.revision}" }.sorted() }

    private fun bodies(pot: String): List<String> =
        QuarkusTransaction.requiringNew().call { PotRevisionEntity.list("nodeId", pot).map { it.sealedBody } }

    private fun createShare(userId: String, nodeId: String, role: String) =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body(
                if (role == "WRITE_ONLY") {
                    """{${ShareWriteProof.newShare()},"nodeId":"$nodeId","role":"$role","sealedDropPublicKey":"ZHJvcA==","sealedDropPrivateKey":"cHJpdg==","sealedName":"c2VhbGVk",${ShareWriteProof.recovery()}}"""
                } else {
                    """{${ShareWriteProof.newShare()},"nodeId":"$nodeId","role":"$role","wrap":"d3JhcA==","sealedName":"c2VhbGVk",${ShareWriteProof.recovery()}}"""
                },
            )
            .`when`().post("/api/shares").then()

    private companion object {
        /** What V202610251600__core_exchange_rate_service.sql seeds: Frankfurter's table of the ECB's reference rates. */
        const val SEEDED_RATES = "https://api.frankfurter.dev/v2/providers/ecb/rates?date={date}"
    }
}
