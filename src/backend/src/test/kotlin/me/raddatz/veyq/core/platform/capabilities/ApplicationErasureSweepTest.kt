package me.raddatz.veyq.core.platform.capabilities

import me.raddatz.veyq.core.backup.BackupRetentionEntity
import me.raddatz.veyq.core.backup.ErasureLedgerFile
import me.raddatz.veyq.core.backup.ErasureLedgerStart
import me.raddatz.veyq.core.backup.ErasureOutboxEntity
import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.blob.NodeAttachmentEntity
import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.blobstore.BlobStore
import me.raddatz.veyq.core.blobstore.RecordingBlobStore
import me.raddatz.veyq.core.blobstore.StoreReclaimEntity
import me.raddatz.veyq.core.blobstore.StoreReclaimSweep
import me.raddatz.veyq.core.blobstore.ageReclaimRowsPastThePresignBound
import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.content.NodeSubtreePurge
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.moderation.guestwrite.GuestWriteRecordEntity
import me.raddatz.veyq.core.moderation.hold.HoldEntity
import me.raddatz.veyq.core.moderation.hold.HoldPinEntity
import me.raddatz.veyq.core.moderation.hold.HoldRowEntity
import me.raddatz.veyq.core.moderation.hold.HoldTarget
import me.raddatz.veyq.core.moderation.hold.HoldTrigger
import me.raddatz.veyq.core.moderation.hold.Holds
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.node.NodeSubtreeEraser
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.sharing.GuestTreeShare
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.ShareIdentityEntity
import me.raddatz.veyq.core.testing.CoverKit
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedAttachment
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.depot.DepotNodeKinds
import me.raddatz.veyq.expenses.ExpensesNodeKinds
import me.raddatz.veyq.memories.media.MediaDerivativeEntity
import me.raddatz.veyq.memories.media.MediaEntity
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.hibernate.Session
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * What the erasure of an application left switched off does to the content (uc.platform.erase-switched-off-application,
 * the CR's slice tests (a) to (e)), with every application booted: a root test, because its controls are another
 * application's nodes and its completeness sweep reads every application's tables. The day is driven into the past —
 * a switch-off one window and a minute ago — so the server's own clock (a node's `createdAt`) and the purge's agree.
 *
 * **The completeness sweep is derived, not listed** ((b)): every table of the schema, every id-shaped column, is read
 * for an id of the erased content — the erased nodes, and transitively every row that named one before the erase (a
 * medium, its derivatives, their blobs, an attachment, a record). The applications' migrations' tables are asserted to
 * be among the ones read, so an application's new table is swept the day its migration lands. Only [KEEPERS] may still
 * name an erased id, each for the reason it gives. Bitten by leaving one eraser out of the erase ([withoutEraser]): the
 * sweep names that eraser's table.
 */
@QuarkusTest
class ApplicationErasureSweepTest {

    @Inject lateinit var tenantSwitches: TenantCapabilitySwitchStore
    @Inject lateinit var store: ApplicationErasureStore
    @Inject lateinit var purge: ApplicationErasurePurge
    @Inject lateinit var subtrees: NodeSubtreePurge
    @Inject lateinit var holds: Holds
    @Inject lateinit var sweep: StoreReclaimSweep
    @Inject lateinit var blobStore: BlobStore
    @Inject lateinit var config: VeyqConfig
    @Inject lateinit var em: EntityManager
    @Inject lateinit var ledgerStart: ErasureLedgerStart
    @Inject lateinit var ledgerFile: ErasureLedgerFile

    private val recorder get() = blobStore as RecordingBlobStore

    @AfterEach
    fun switchEverythingBackOn() {
        TenantCapabilitySwitchFixtures.clear()
        CapabilitySwitchFixtures.clear()
        QuarkusTransaction.requiringNew().run {
            ApplicationErasureEntity.deleteAll()
            ErasureOutboxEntity.deleteAll()
            BackupRetentionEntity.deleteAll()
            HoldPinEntity.deleteAll()
            HoldRowEntity.deleteAll()
            HoldEntity.deleteAll()
        }
        Files.deleteIfExists(ledgerFile.path)
    }

    // --- (a) to (d): Memories off past its day --------------------------------------------------------------------------

    @Test
    fun memoriesOffPastItsDayIsErasedInEveryScopeAndNothingElseIs() {
        val w = memoriesWorld()
        QuarkusTransaction.requiringNew().run { ErasureOutboxEntity.deleteAll(); StoreReclaimEntity.deleteAll() }
        switchOff(w.tenant, MEMORIES)
        val doomedIds = closure(w.erased)
        assertTrue(w.medium in doomedIds && doomedIds.size >= w.erased.size + 8, "the closure reached the rows hanging off: $doomedIds")

        val tick = purge.run(Instant.now())
        assertEquals(w.erased.size, tick.erasedNodes, "every node of Memories' kinds, chapters within their memory")

        // (a) the content: personal space and group, trashed included, with what hangs off it.
        read {
            w.erased.forEach { assertNull(NodeEntity.findById(it), "node $it is erased") }
            assertNull(MediaEntity.findById(w.medium))
            assertEquals(0L, MediaDerivativeEntity.count("mediaId", w.medium), "its derivatives go with it")
            assertEquals(0L, NodeAttachmentEntity.count("nodeId", w.memory), "the cover's attachment goes")
            assertEquals(0L, GuestWriteRecordEntity.count("objectId", w.medium), "the guest-write record goes")
            assertTrue(ShareEntity.findById(w.link)!!.deleted, "the link is a tombstone")
            // Controls on the same predicate: another kind of the same tenant, a core folder, another tenant.
            assertNotNull(NodeEntity.findById(w.folder), "a core folder that held a Memory stays")
            assertNotNull(NodeEntity.findById(w.file), "the same tenant's Depot file stays")
            assertEquals(1L, NodeAttachmentEntity.count("nodeId", w.file), "with its content")
            assertNotNull(NodeEntity.findById(w.pot), "the same tenant's Pot stays")
            assertNotNull(NodeEntity.findById(w.otherTenantMemory), "a second tenant's Memory stays")
            assertNotNull(ScopeEntity.findById(w.personal), "the scope stays")
            assertNotNull(ScopeEntity.findById(w.group), "and the group")
            assertEquals(2L, MembershipEntity.count("scopeId", w.group), "with its memberships")
        }

        // (b) the derived sweep: nothing outside the keepers names what went.
        assertEquals(emptyMap<String, Long>(), residue(doomedIds), "rows that still name erased content")

        // (c) the ledger: one NODE line per erased node, and a restore that brings one back is erased again.
        val lines = read { ErasureOutboxEntity.list("kind", "NODE").map { it.subjectId } }
        assertEquals(w.erased.toSet(), lines.toSet(), "one NODE line per erased node")
        assertEquals(w.erased.size, lines.size)

        // (d) a held medium's bytes stay; the unheld one's are reclaimed.
        QuarkusTransaction.requiringNew().run { StoreReclaimEntity.update("nextAttemptAt = ?1", Instant.now().minusSeconds(60)) }
        recorder.reset()
        ageReclaimRowsPastThePresignBound(config)
        repeat(3) { sweep.run() }
        assertTrue(recorder.deletedKeys.containsAll(w.unheldKeys), "the unheld medium's chunks are reclaimed")
        assertTrue(w.heldKeys.none { it in recorder.deletedKeys }, "a held medium's chunks stay in the store")
        read {
            assertTrue(HoldRowEntity.count("holdId", w.hold) > 0, "the hold keeps its copy of the rows")
            assertTrue(HoldPinEntity.count("holdId", w.hold) > 0, "and its pins")
        }

        // The window ends with every scope done, and the application is still off: a fresh day, from now.
        val fresh = read { ApplicationErasureEntity.findById(ApplicationErasureKey(w.tenant, MEMORIES))!! }
        assertTrue(fresh.offSince.isAfter(Instant.now().minusSeconds(120)), "a fresh window starts at the end")
        assertTrue(fresh.noticeMailedAt?.isBefore(fresh.offSince) == false, "and its notice went again, in the same tick")
    }

    @Test
    fun aRestoreThatBringsAnErasedMemoryBackErasesItAgain() {
        val w = memoriesWorld()
        val captured = read { NodeEntity.findById(w.memory2)!! }
        QuarkusTransaction.requiringNew().run { ErasureOutboxEntity.deleteAll() }
        switchOff(w.tenant, MEMORIES)
        purge.run(Instant.now())
        assertNull(read { NodeEntity.findById(w.memory2) })
        // The shape a restore from a capture before the day produces: the row back with its id.
        QuarkusTransaction.requiringNew().run { em.merge(captured) }
        assertNotNull(read { NodeEntity.findById(w.memory2) })
        // The outbox's lines enter the file the way the sweep carries them; the start then applies the file.
        ledgerStart.run()
        assertNull(read { NodeEntity.findById(w.memory2) }, "the ledger's NODE line erases it again")
        assertNotNull(read { NodeEntity.findById(w.folder) }, "control: the core folder it sat in stays")
    }

    // --- (b) bitten: one eraser left out -------------------------------------------------------------------------------

    @Test
    fun theSweepNamesTheTableOfAnEraserLeftOut() {
        val w = memoriesWorld()
        switchOff(w.tenant, MEMORIES)
        val doomedIds = closure(w.erased)
        // The item favorites' eraser (phase 610) left out: its rows name a node that is gone.
        withoutEraser("ItemFavoritesNodeSubtreeEraser") { purge.run(Instant.now()) }
        val red = residue(doomedIds)
        assertEquals(setOf("veyq_scope_item_favorites"), red.keys, "the sweep names exactly the left-out eraser's table")
    }

    // --- Depot and Expenses off: their kinds go, Memories stays --------------------------------------------------------

    @Test
    fun depotAndExpensesOffPastTheirDayAreErasedAndMemoriesStays() {
        val (owner, personal, tenant) = seedMemberScope()
        val file = seedNode(personal, DepotNodeKinds.FILE, wrappedContentKey = CONTENT_KEY)
        seedAttachment(personal, file, "FILE_CONTENT")
        val dropBox = seedNode(personal, DepotNodeKinds.DROPBOX, wrappedContentKey = CONTENT_KEY)
        val guestFolder = seedNode(personal, NodeEntity.FOLDER, parentId = dropBox, wrappedContentKey = CONTENT_KEY)
        val pot = seedNode(personal, ExpensesNodeKinds.POT, wrappedContentKey = CONTENT_KEY)
        appendRevision(owner, personal, pot)
        val memory = seedNode(personal, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val erased = listOf(file, dropBox, guestFolder, pot)
        madeBeforeTheSwitchOff(personal)
        switchOff(tenant, DEPOT)
        switchOff(tenant, EXPENSES)
        val doomedIds = closure(erased)

        purge.run(Instant.now())
        read {
            erased.forEach { assertNull(NodeEntity.findById(it), "node $it is erased") }
            assertNotNull(NodeEntity.findById(memory), "control: Memories is on, its Memory stays")
        }
        assertEquals(emptyMap<String, Long>(), residue(doomedIds), "rows that still name erased content")
    }

    // --- (e) switched on after the day -----------------------------------------------------------------------------------

    @Test
    fun switchedOnAfterTheDayWhatExistedGoesAndAMemoryMadeAfterwardsStays() {
        val w = memoriesWorld()
        switchOff(w.tenant, MEMORIES)
        // On again after the day: the erasure is committed, the row stays, and Memories takes writes again.
        QuarkusTransaction.requiringNew().run { tenantSwitches.set(w.tenant, MEMORIES, false, null) }
        assertNotNull(read { ApplicationErasureEntity.findById(ApplicationErasureKey(w.tenant, MEMORIES)) })
        val made = createNode(w.owner, w.personal, null, "MEMORY")

        purge.run(Instant.now())
        read {
            assertNull(NodeEntity.findById(w.memory), "what existed on the day goes")
            assertNotNull(NodeEntity.findById(made), "a Memory made after the switch-on stays")
            assertNull(ApplicationErasureEntity.findById(ApplicationErasureKey(w.tenant, MEMORIES)), "on at the end: the row goes")
        }
    }

    // --- the world ------------------------------------------------------------------------------------------------------

    private class World(
        val tenant: String, val owner: String, val personal: String, val group: String,
        val memory: String, val chapter: String, val memory2: String, val trashed: String, val heldMemory: String,
        val groupMemory: String, val folder: String, val file: String, val pot: String, val otherTenantMemory: String,
        val medium: String, val link: String, val hold: String, val heldKeys: List<String>, val unheldKeys: List<String>,
    ) {
        val erased = listOf(memory, chapter, memory2, trashed, heldMemory, groupMemory)
    }

    private fun memoriesWorld(): World {
        val (owner, personal, tenant) = seedMemberScope()
        val member = me.raddatz.veyq.core.testing.TestFixtures.seedUser()
        val group = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            me.raddatz.veyq.core.membership.TenantRoots.join(member, tenant)
            ScopeEntity().apply { id = group; this.tenantId = tenant; kind = ScopeEntity.GROUP; encryptedName = "bmFtZQ==" }.persist()
            membership(owner, tenant, group, MembershipEntity.OWNER)
            membership(member, tenant, group, MembershipEntity.MEMBER)
        }
        // The personal space: a Memory with a chapter, a medium and its thumbnail, a cover, item marks, a link and a
        // guest-write record; a core folder holding a second Memory; a trashed Memory; a held one.
        val memory = createNode(owner, personal, null, "MEMORY")
        val chapter = createNode(owner, personal, memory, "CHAPTER")
        val (medium, unheldKeys) = medium(owner, personal, memory)
        CoverKit.set(owner, personal, memory, CoverKit.start(owner, personal, memory).uploadId, expectedBlobId = null).statusCode(204)
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON).body("""{"ciphertext":"bWFya3M="}""")
            .`when`().put("/api/scopes/$personal/nodes/$memory/item-favorites").then().statusCode(204)
        val link = GuestTreeShare.seed(owner, personal, memory, ShareEntity.READ).shareId
        QuarkusTransaction.requiringNew().run {
            GuestWriteRecordEntity().apply {
                id = UUID.randomUUID().toString()
                shareIdentityId = ShareIdentityEntity.find("shareId", link).firstResult()!!.id
                objectKind = "ITEM"; objectId = medium; clientIp = "203.0.113.7"
                observedAt = Instant.now(); expiresAt = Instant.now().plus(Duration.ofDays(7))
            }.persist()
        }
        val folder = createNode(owner, personal, null, "FOLDER")
        val memory2 = createNode(owner, personal, folder, "MEMORY")
        val trashed = createNode(owner, personal, null, "MEMORY")
        given().header("Authorization", bearer(owner)).`when`().delete("/api/scopes/$personal/nodes/$trashed").then().statusCode(204)
        val heldMemory = createNode(owner, personal, null, "MEMORY")
        val (heldMedium, heldKeys) = medium(owner, personal, heldMemory)
        val hold = QuarkusTransaction.requiringNew().call {
            holds.take(null, HoldTarget.Item(personal, heldMedium), HoldTrigger.CASE_OPEN, Instant.now().plus(Duration.ofDays(1)))
        }
        // The group: a Memory with a medium.
        val groupMemory = createNode(owner, group, null, "MEMORY")
        medium(owner, group, groupMemory)
        // Controls: another application's nodes in the same tenant, and another tenant's Memory.
        val file = seedNode(personal, DepotNodeKinds.FILE, wrappedContentKey = CONTENT_KEY)
        seedAttachment(personal, file, "FILE_CONTENT")
        val pot = seedNode(personal, ExpensesNodeKinds.POT, wrappedContentKey = CONTENT_KEY)
        val other = seedMemberScope()
        val otherMemory = seedNode(other.scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        switchOff(other.tenantId, DEPOT) // control: the other tenant's switch of another application erases no Memory
        madeBeforeTheSwitchOff(personal, group, other.scopeId)
        return World(
            tenant, owner, personal, group, memory, chapter, memory2, trashed, heldMemory, groupMemory, folder, file, pot,
            otherMemory, medium, link, hold, heldKeys, unheldKeys,
        )
    }

    /** Every node of [scopeIds] made before the switch-offs of [switchOff], as content that was there on the day. */
    private fun madeBeforeTheSwitchOff(vararg scopeIds: String) = QuarkusTransaction.requiringNew().run {
        NodeEntity.update("createdAt = ?1 where scopeId in ?2", Instant.now().minus(store.window).minusSeconds(120), scopeIds.toList())
    }

    /** Switch [application] off for [tenantId] one window and a minute ago, so its day has just passed. */
    private fun switchOff(tenantId: String, application: String) {
        val then = Instant.now().minus(store.window).minusSeconds(60)
        QuarkusTransaction.requiringNew().run { tenantSwitches.set(tenantId, application, true, then) }
    }

    private fun createNode(userId: String, scopeId: String, parentId: String?, kind: String): String {
        val parent = parentId?.let { """"parentId":"$it","parentEpoch":0,""" } ?: ""
        return given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"nodeId":"${UUID.randomUUID()}",$parent"kind":"$kind","encryptedName":"bQ==","slugToken":"${java.util.Base64.getEncoder().encodeToString(UUID.randomUUID().toString().toByteArray())}","orderIndex":0,"wrappedContentKey":"aw=="}""")
            .`when`().post("/api/scopes/$scopeId/nodes")
            .then().statusCode(201).body("nodeId", notNullValue()).extract().path("nodeId")
    }

    /** A medium with a thumbnail on [memory]; answers its id and every chunk key of its blobs. */
    private fun medium(userId: String, scopeId: String, memory: String): Pair<String, List<String>> {
        val original = startOne(userId, scopeId, TestBlob.ofChunks(2), "b3JpZw==", UUID.randomUUID().toString(), "ORIGINAL")
        val thumb = startOne(userId, scopeId, TestBlob.SMALL, "dGh1bWI=", TestBlob.ownerOf(original), "THUMBNAIL")
        val mediaId = given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"nodeId":"$memory","mediaId":"${TestBlob.ownerOf(original)}","nodeEpoch":0,"originalUploadId":"$original","derivatives":[{"kind":"THUMBNAIL","uploadId":"$thumb"}]}]}""")
            .`when`().post("/api/scopes/$scopeId/media-batch")
            .then().statusCode(200).body("results[0].error", nullValue()).extract().path<String>("results[0].mediaId")
        val keys = read {
            val blobIds = listOf(MediaEntity.findById(mediaId)!!.blobId) + MediaDerivativeEntity.list("mediaId", mediaId).map { it.blobId }
            blobIds.map { BlobEntity.findById(it)!! }.flatMap { BlobStore.chunkKeys(it.storageKey, it.chunkCount) }
        }
        return mediaId to keys
    }

    private fun startOne(userId: String, scopeId: String, blob: TestBlob, wrap: String, ownerId: String, role: String): String =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[${blob.declare(wrap, ownerId = ownerId, role = role)}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch")
            .then().statusCode(200).body("results[0].uploadId", notNullValue()).extract().path("results[0].uploadId")

    private fun appendRevision(userId: String, scopeId: String, pot: String) {
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"nodeEpoch":0,"revisions":[{"entryId":"${UUID.randomUUID()}","revision":1,"tombstone":false,"sealedBody":"${"A".repeat(744)}","wrappedRevisionKey":"d3JhcHBlZC1rLXJldg=="}]}""")
            .`when`().post("/api/scopes/$scopeId/pots/$pot/revisions").then().statusCode(204)
    }

    private fun membership(userId: String, tenantId: String, scopeId: String, role: String) {
        MembershipEntity().apply {
            id = UUID.randomUUID().toString(); this.userId = userId; this.tenantId = tenantId
            this.scopeId = scopeId; this.role = role; wrappedGrant = "Z3JhbnQ="; createdAt = Instant.now()
        }.persist()
    }

    // --- the derived sweep ------------------------------------------------------------------------------------------------

    /** An id-shaped column of a table, as the schema names it. */
    private data class Column(val table: String, val column: String)

    /** Every id-shaped column of every table but Flyway's: uuid and short text, never a LOB of ciphertext. The schema is
     *  `DATABASE()` on MariaDB and `PUBLIC` on H2, whose `DATABASE()` names the catalog. */
    private fun columns(): List<Column> = read {
        em.unwrap(Session::class.java).doReturningWork { c ->
            c.prepareStatement(
                "SELECT table_name, column_name, data_type FROM information_schema.columns " +
                    "WHERE table_schema IN (DATABASE(), 'PUBLIC') AND LOWER(table_name) <> 'flyway_schema_history'",
            ).executeQuery().use { rs ->
                generateSequence { if (rs.next()) Triple(rs.getString(1).lowercase(), rs.getString(2).lowercase(), rs.getString(3).lowercase()) else null }
                    .filter { (_, _, type) -> type == "uuid" || type.contains("char") }
                    .map { (t, col, _) -> Column(t, col) }.toList()
            }
        }
    }

    /**
     * [nodeIds] and, transitively, the id of every row that named one of them before the erase — a medium, its
     * derivatives, their blobs, an attachment, a guest-write record — read while they still exist. A keeper's row adds
     * nothing: what it keeps is kept on purpose.
     */
    private fun closure(nodeIds: List<String>): Set<String> {
        val cols = columns()
        val tables = cols.map { it.table }.toSet()
        // Derived from the applications' migrations: every table they create is among the ones this reads.
        val appTables = File("apps").listFiles()!!.flatMap { app ->
            File(app, "src/main/resources/db/migration").listFiles().orEmpty().flatMap { f ->
                Regex("""CREATE TABLE (\w+)""", RegexOption.IGNORE_CASE).findAll(f.readText()).map { it.groupValues[1].lowercase() }
            }
        }.toSet()
        assertTrue(appTables.size >= 5, "read the applications' migrations ($appTables)")
        assertEquals(emptySet<String>(), appTables - tables, "an application table the sweep does not read")
        val ids = nodeIds.toMutableSet()
        var grew = true
        while (grew) {
            grew = false
            cols.filter { it.table !in KEEPERS && cols.any { c -> c.table == it.table && c.column == "id" } }.forEach { col ->
                val named = read { idsOfRowsNaming(col, ids) }
                if (ids.addAll(named)) grew = true
            }
        }
        return ids
    }

    /** Per table outside [KEEPERS], how many rows name one of [ids] in any id-shaped column. */
    private fun residue(ids: Set<String>): Map<String, Long> = read {
        columns().filter { it.table !in KEEPERS }.groupBy { it.table }.mapValues { (_, cols) ->
            cols.sumOf { countNaming(it, ids) }
        }.filterValues { it > 0 }
    }

    private fun idsOfRowsNaming(col: Column, ids: Set<String>): List<String> = em.unwrap(Session::class.java).doReturningWork { c ->
        c.prepareStatement(
            "SELECT CAST(id AS CHAR(64)) FROM ${col.table} WHERE CAST(${col.column} AS CHAR(64)) IN (${ids.joinToString { "?" }})",
        ).use { ps ->
            ids.forEachIndexed { i, id -> ps.setString(i + 1, id) }
            ps.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
        }
    }

    private fun countNaming(col: Column, ids: Set<String>): Long = em.unwrap(Session::class.java).doReturningWork { c ->
        c.prepareStatement(
            "SELECT COUNT(*) FROM ${col.table} WHERE CAST(${col.column} AS CHAR(64)) IN (${ids.joinToString { "?" }})",
        ).use { ps ->
            ids.forEachIndexed { i, id -> ps.setString(i + 1, id) }
            ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    /** Run [block] with the eraser of class [simpleName] left out of [NodeSubtreePurge]'s erase, then put it back. */
    private fun <T> withoutEraser(simpleName: String, block: () -> T): T {
        val field = NodeSubtreePurge::class.java.getDeclaredField("sorted").apply { isAccessible = true }
        val target = unproxied(subtrees)
        @Suppress("UNCHECKED_CAST")
        val all = field.get(target) as List<NodeSubtreeEraser>
        val kept = all.filterNot { realName(it) == simpleName }
        assertEquals(all.size - 1, kept.size, "$simpleName is one of the erasers ${all.map { realName(it) }}")
        field.set(target, kept)
        try {
            return block()
        } finally {
            field.set(target, all)
        }
    }

    private fun realName(o: Any): String = generateSequence(o.javaClass as Class<*>) { it.superclass }
        .first { !it.simpleName.contains("_") && !it.simpleName.contains("$") }.simpleName

    private fun unproxied(bean: Any): Any =
        (bean as? io.quarkus.arc.ClientProxy)?.arc_contextualInstance() ?: bean

    private fun <T> read(body: () -> T): T = QuarkusTransaction.requiringNew().call(body)

    private companion object {
        const val MEMORIES = "memories"
        const val DEPOT = "depot"
        const val EXPENSES = "expenses"

        /** The tables that keep an erased id on purpose, each for its reason. */
        val KEEPERS: Map<String, String> = mapOf(
            "veyq_erasure_outbox" to "the erasure ledger's lines name what was erased, so a restore erases it again (ADR 0056 decision 8)",
            "veyq_share" to "a link on an erased node is a tombstone: its id stays held, its node named (ADR 0029 §7)",
            "veyq_hold" to "a hold outlives what it holds (ADR 0093 § 4)",
            "veyq_hold_row" to "a hold's copy of the rows it holds (ADR 0093 § 4)",
            "veyq_hold_pin" to "a hold's pin on the bytes it holds (ADR 0093 § 4)",
            "veyq_application_erasure" to "the window itself, keyed by tenant and application",
        )
    }
}
