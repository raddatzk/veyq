package me.raddatz.veyq.core.backup

import me.raddatz.veyq.core.account.erasure.AccountPurge
import me.raddatz.veyq.core.admin.tenant.TenantStore
import me.raddatz.veyq.core.adminchannel.givenAdminChannel
import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.blob.NodeAttachmentEntity
import me.raddatz.veyq.core.blobstore.StoreReclaimEntity
import me.raddatz.veyq.core.blobstore.StoreReclaimStore
import me.raddatz.veyq.core.content.NodeSubtreePurge
import me.raddatz.veyq.core.identity.AccountErasureEntity
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.moderation.ModerationEvent
import me.raddatz.veyq.core.moderation.ModerationEventEntity
import me.raddatz.veyq.core.moderation.ModerationLog
import me.raddatz.veyq.core.moderation.block.BlockKind
import me.raddatz.veyq.core.moderation.block.ModerationBlockEntity
import me.raddatz.veyq.core.moderation.cases.BlockOutcome
import me.raddatz.veyq.core.moderation.cases.CaseTestKit
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.dismiss
import me.raddatz.veyq.core.moderation.hold.HoldEntity
import me.raddatz.veyq.core.moderation.hold.HoldPinEntity
import me.raddatz.veyq.core.moderation.hold.HoldRowEntity
import me.raddatz.veyq.core.moderation.report.ReportTestKit.asMember
import me.raddatz.veyq.core.moderation.report.ReportTestKit.body
import me.raddatz.veyq.core.moderation.report.ReportTestKit.item
import me.raddatz.veyq.core.moderation.report.ReportTestKit.openCase
import me.raddatz.veyq.core.moderation.cases.CaseViews
import me.raddatz.veyq.core.moderation.cases.SignInBlocks
import me.raddatz.veyq.core.moderation.report.CaseOrigin
import me.raddatz.veyq.core.moderation.report.CaseState
import me.raddatz.veyq.core.moderation.report.ModerationCaseEntity
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.sharing.GuestTreeShare
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.SharingStore
import me.raddatz.veyq.core.spaces.scope.ScopePurge
import me.raddatz.veyq.core.tenant.TenantEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedAttachment
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.scheduler.Scheduled
import io.quarkus.test.junit.QuarkusTest
import io.restassured.http.ContentType
import io.restassured.specification.RequestSpecification
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The erasure ledger (ADR 0056 decision 8): every hard erasure writes its line in
 * its own transaction, the sweep carries it into the file, and the start erases again what a restore brought back — the
 * shape a restore produces is made here by **writing the erased rows back** with their ids, as a dump taken before the
 * erasure would. Every re-application has a control: the same start leaves a row nobody erased alone.
 *
 * `%test` starts no re-application at boot (`veyq.erasure-ledger.apply-at-start=false`: the database outlives a boot), so
 * [ErasureLedgerStart.run] is called as a restart would; the "before the first request" half is Quarkus' order — every
 * `StartupEvent` observer returns before the socket opens (`AdminListener`). The outbox and the boot's own file are shared:
 * [clean] empties both, so a start sees this class's lines only.
 */
@QuarkusTest
class ErasureLedgerSliceTest {

    @Inject lateinit var start: ErasureLedgerStart
    @Inject lateinit var file: ErasureLedgerFile
    @Inject lateinit var scopes: ScopePurge
    @Inject lateinit var nodes: NodeSubtreePurge
    @Inject lateinit var sharing: SharingStore
    @Inject lateinit var accounts: AccountPurge
    @Inject lateinit var tenants: TenantStore
    @Inject lateinit var reclaim: StoreReclaimStore
    @Inject lateinit var blocks: SignInBlocks
    @Inject lateinit var ledger: ErasureLedger
    @Inject lateinit var log: ModerationLog
    @Inject lateinit var cases: CaseViews
    @Inject lateinit var em: EntityManager

    @BeforeEach
    @AfterEach
    fun clean() {
        QuarkusTransaction.requiringNew().run {
            ErasureOutboxEntity.deleteAll()
            BackupRetentionEntity.deleteAll()
        }
        Files.deleteIfExists(file.path)
    }

    private fun lines(): List<LedgerLine> = file.read().lines

    /** Write [rows] back as a restore would: the same ids, the same columns, in their foreign keys' order. */
    private fun restore(vararg rows: Any) = QuarkusTransaction.requiringNew().run { rows.forEach { em.merge(it) } }

    private fun <T> read(body: () -> T): T = QuarkusTransaction.requiringNew().call(body)

    // --- the file: written in the erasure's transaction, swept in, dropped at B -----------------------------------------

    @Test
    fun anErasureEntersTheOutboxInItsTransactionAndTheSweepCarriesItIntoTheFileOnce() {
        val (_, scopeId) = seedMemberScope()
        scopes.purge(scopeId)
        assertEquals(1L, read { ErasureOutboxEntity.count("kind = ?1 and subjectId = ?2 and sentAt is null", "SCOPE", scopeId) })
        assertTrue(lines().isEmpty(), "nothing is in the file before the sweep")
        assertEquals(1, file.sweep())
        assertEquals(listOf("SCOPE" to scopeId), lines().map { it.kind to it.id })
        assertEquals(0, file.sweep(), "a swept row is not appended again")
        assertEquals(1, lines().size)
        val line = lines().single()
        assertEquals(setOf("at", "kind", "id", "scope"), line.fields.keys, "ids and a time — never content or a name")
    }

    /** "The last line wins" reads the file's order, which is the outbox's: lines written in one millisecond — a case's
     *  last event and its close, in one transaction — must still come out in the order they were written. */
    @Test
    fun linesWrittenInOneMillisecondKeepTheirOrder() {
        val ids = (1..40).map { "%02d-%s".format(it, UUID.randomUUID()) }
        QuarkusTransaction.requiringNew().run { ids.forEach { ledger.erased(ErasureKind.SCOPE, it) } }
        val times = read { ErasureOutboxEntity.list("order by recordedAt").map { it.recordedAt } }
        assertTrue(times.zipWithNext().all { (a, b) -> b.isAfter(a) }, "every row strictly after the one before")
        file.sweep()
        assertEquals(ids, lines().map { it.id }, "the file in the order the lines were written")
    }

    @Test
    fun aLineOlderThanBIsDroppedAtStartAndAYoungerOneStays() {
        val now = Instant.now()
        val old = LedgerLine(mapOf("at" to now.minus(Duration.ofDays(31)).toString(), "kind" to "SCOPE", "id" to UUID.randomUUID().toString()))
        val young = LedgerLine(mapOf("at" to now.minus(Duration.ofDays(29)).toString(), "kind" to "SCOPE", "id" to UUID.randomUUID().toString()))
        Files.createDirectories(file.path.parent)
        Files.writeString(file.path, "${old.json()}\n${young.json()}\n")
        start.run(now)
        assertEquals(listOf(young.id), lines().map { it.id }, "30 days by default: the 31-day line went, the 29-day one stayed")
        // B as the Server Owner sets it: at 7 days the 29-day line goes too.
        QuarkusTransaction.requiringNew().run { BackupRetentionEntity().apply { retentionDays = 7 }.persist() }
        start.run(now)
        assertTrue(lines().isEmpty())
    }

    /**
     * The file is held to B while the instance runs, not only at a start (inbox #592): an instance that runs for months
     * without a restart must not keep account and moderator ids in it past B. Every scheduled job of the file runs here
     * as the scheduler fires it, whichever of them does the trimming.
     */
    @Test
    fun aLineOlderThanBIsDroppedWhileTheInstanceRunsWithoutARestart() {
        val now = Instant.now()
        val old = LedgerLine(mapOf("at" to now.minus(Duration.ofDays(31)).toString(), "kind" to "SCOPE", "id" to UUID.randomUUID().toString()))
        val young = LedgerLine(mapOf("at" to now.minus(Duration.ofDays(29)).toString(), "kind" to "SCOPE", "id" to UUID.randomUUID().toString()))
        Files.createDirectories(file.path.parent)
        Files.writeString(file.path, "${old.json()}\n${young.json()}\n")
        val jobs = ErasureLedgerFile::class.java.declaredMethods.filter { it.isAnnotationPresent(Scheduled::class.java) }
        assertTrue(jobs.isNotEmpty(), "precondition: the file has scheduled jobs")
        jobs.forEach { it.invoke(file) }
        assertEquals(listOf(young.id), lines().map { it.id }, "the 31-day line went, the 29-day one (control) stayed")
    }

    // --- each kind erased again after a restore, and nothing else ---------------------------------------------------------

    @Test
    fun aScopeErasedAfterTheCaptureIsErasedAgainAtStartAndARunTwiceChangesNothing() {
        val (_, scopeId) = seedMemberScope()
        val node = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val attached = seedAttachment(scopeId, node, "cover")
        val (control, _) = seedMemberScope().let { it.scopeId to it.userId }
        val captured = read { listOf(ScopeEntity.findById(scopeId)!!, NodeEntity.findById(node)!!, BlobEntity.findById(attached.blobId)!!,
            NodeAttachmentEntity.findById(attached.attachmentId)!!) }
        scopes.purge(scopeId)
        assertNull(read { ScopeEntity.findById(scopeId) })
        // The restore: the capture predates the erasure, so its rows come back and the reclaim row of its blob does not.
        QuarkusTransaction.requiringNew().run { StoreReclaimEntity.delete("storageKey", attached.storageKey) }
        restore(*captured.toTypedArray())
        assertNotNull(read { ScopeEntity.findById(scopeId) }, "the restore brought the scope back")

        val done = start.run()
        assertEquals(1, done.erased["SCOPE"])
        assertNull(read { ScopeEntity.findById(scopeId) }, "erased again before the instance serves")
        assertNull(read { NodeEntity.findById(node) })
        assertEquals(1L, read { StoreReclaimEntity.count("storageKey", attached.storageKey) }, "its blob onto the reclaim list")
        assertNotNull(read { ScopeEntity.findById(control) }, "control: a scope nobody erased stays")

        val again = start.run()
        assertEquals(null, again.erased["SCOPE"], "a line applied twice erases nothing")
        assertEquals(1L, read { StoreReclaimEntity.count("storageKey", attached.storageKey) }, "…and records no second reclaim row")
    }

    @Test
    fun aNodeErasedAfterTheCaptureIsErasedAgainWithTheSubtreeTheRestoreHangsUnderIt() {
        val (_, scopeId) = seedMemberScope()
        val parent = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val sibling = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val captured = read { NodeEntity.findById(parent)!! }
        QuarkusTransaction.requiringNew().run {
            ScopeEntity.lockRow(scopeId)
            nodes.erase(scopeId, listOf(NodeEntity.findById(parent)!!))
        }
        restore(captured)
        val child = seedNode(scopeId, NodeEntity.FOLDER, parentId = parent, wrappedContentKey = CONTENT_KEY)
        start.run()
        assertNull(read { NodeEntity.findById(parent) })
        assertNull(read { NodeEntity.findById(child) }, "a node is erased with its subtree, or not at all")
        assertNotNull(read { NodeEntity.findById(sibling) }, "control: a node beside it stays")
    }

    @Test
    fun aDeletedLinkComesBackLiveAndIsDeletedAgain() {
        val (userId, scopeId) = seedMemberScope()
        val node = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val link = GuestTreeShare.seed(userId, scopeId, node)
        val control = GuestTreeShare.seed(userId, scopeId, node)
        val wrap = read { ShareEntity.findById(link.shareId)!!.wrap!! }
        assertTrue(sharing.delete(link.shareId))
        // The restore: the row a tombstone keeps, back as it was — live, its wrap with it.
        QuarkusTransaction.requiringNew().run {
            ShareEntity.update("deleted = false, enabled = true, wrap = ?1 where id = ?2", wrap, link.shareId)
        }
        assertFalse(read { ShareEntity.findById(link.shareId)!!.deleted }, "the restore brought the link back live")
        start.run()
        val after = read { ShareEntity.findById(link.shareId)!! }
        assertTrue(after.deleted && after.wrap == null, "deleted again, its keys gone")
        assertFalse(read { ShareEntity.findById(control.shareId)!!.deleted }, "control: a link nobody deleted stays")
    }

    @Test
    fun anErasedAccountComesBackLiveAndIsErasedAgain() {
        val erased = seedUser()
        val control = seedUser()
        QuarkusTransaction.requiringNew().run {
            AccountErasureEntity().apply {
                accountId = erased; requestedAt = Instant.now(); requestedBy = AccountErasureEntity.SELF; dueAt = Instant.now()
            }.persist()
        }
        val captured = read { VeyqUser.findById(erased)!! }
        accounts.purge(erased)
        assertNull(read { VeyqUser.findById(erased) })
        restore(captured) // a capture before the request: the account, live, with no pending erasure
        assertEquals(1, start.run().erased["ACCOUNT"])
        assertNull(read { VeyqUser.findById(erased) })
        assertNull(read { AccountErasureEntity.findById(erased) }, "the pending row the re-application made goes with it")
        assertNotNull(read { VeyqUser.findById(control) }, "control: an account nobody erased stays")
    }

    @Test
    fun aRevokedTenantComesBackAndIsDeletedAgain() {
        val tenant = seedTenant(status = TenantEntity.PENDING, tenantScope = false)
        val control = seedTenant(status = TenantEntity.PENDING, tenantScope = false)
        val captured = read { TenantEntity.findById(tenant)!! }
        QuarkusTransaction.requiringNew().run { tenants.deleteTenant(tenant) }
        restore(captured)
        start.run()
        assertNull(read { TenantEntity.findById(tenant) })
        assertNotNull(read { TenantEntity.findById(control) }, "control: a tenant nobody deleted stays")
    }

    @Test
    fun aReclaimedKeyIsALineAndARestoredRowStillNamingItIsCounted() {
        val (_, scopeId) = seedMemberScope()
        val key = UUID.randomUUID().toString().replace("-", "")
        QuarkusTransaction.requiringNew().run { reclaim.record(key, 1) }
        val row = read { StoreReclaimEntity.find("storageKey", key).firstResult()!!.id }
        reclaim.settle(row)
        reclaim.settle(row) // settled already: no second line (the read before the delete holds it; the delete count guards two instances racing)
        assertEquals(1L, read { ErasureOutboxEntity.count("kind = ?1 and subjectId = ?2", "STORAGE_KEY", key) })
        // An old version's blob row a restore brought back, with no line of its own.
        QuarkusTransaction.requiringNew().run {
            BlobEntity().apply {
                id = UUID.randomUUID().toString(); this.scopeId = scopeId; storageKey = key; chunkCount = 1; byteSize = 1
                wrappedBlobKey = "YmxvYg=="
            }.persist()
        }
        val done = start.run()
        assertEquals(1, done.erasedKeys)
        assertEquals(1L, done.rowsNamingErasedKeys, "counted, and served as a missing object is")
    }

    @Test
    fun aLineOfAKindNoApplicationClaimsIsCountedAndAppliesNothing() {
        Files.createDirectories(file.path.parent)
        Files.writeString(file.path, LedgerLine(mapOf("at" to Instant.now().toString(), "kind" to "NO_SUCH_KIND", "id" to "x")).json() + "\n{torn\n")
        assertEquals(1, file.read().unreadable)
        val done = start.run()
        assertEquals(1, done.unknown)
        assertEquals(1, done.lines, "the torn line is not applied")
        assertEquals(0, file.read().unreadable, "…and the start's rewrite drops it: it can be neither applied nor dated")
    }

    // --- moderation survives a restore (decision 7) ------------------------------------------------------------------------

    @Test
    fun aBlockedAccountIsBlockedAgainAndALiftedOneIsNot() {
        val owner = seedUser(serverRole = "SERVER_OWNER")
        val blocked = seedUser()
        val lifted = seedUser()
        assertEquals(BlockOutcome.Blocked(null), blocks.block(owner, "u-$blocked@example.com", null))
        assertEquals(BlockOutcome.Blocked(null), blocks.block(owner, "u-$lifted@example.com", null))
        val liftedRow = read { ModerationBlockEntity.find("targetId", lifted).firstResult()!!.id }
        assertNull(blocks.unblock(owner, liftedRow))
        // The restore: a capture before either block.
        QuarkusTransaction.requiringNew().run { ModerationBlockEntity.delete("targetId in ?1", listOf(blocked, lifted)) }
        val done = start.run()
        assertEquals(1, done.reblocked)
        val key = ModerationBlockEntity.activeKeyOf(BlockKind.MEMBER_SIGN_IN, blocked)
        assertEquals(1L, read { ModerationBlockEntity.count("activeKey", key) }, "blocked again, on its basis")
        assertEquals(0L, read { ModerationBlockEntity.count("targetId = ?1 and activeKey is not null", lifted) }, "a lift is never re-blocked")
        assertEquals(0, start.run().reblocked, "a block in force is not taken twice")
    }

    /** A measure the erasure of its target ended (`MeasureTargets.erased`) is a lift line too: a restore of a capture from
     *  before the erasure brings the account and its block back, and the start erases the one and lifts the other. */
    @Test
    fun aMeasureEndedByItsTargetsErasureIsALiftAndARestoreDoesNotBringItBack() {
        val owner = seedUser(serverRole = "SERVER_OWNER")
        val member = seedUser()
        assertEquals(BlockOutcome.Blocked(null), blocks.block(owner, "u-$member@example.com", null))
        val block = read { ModerationBlockEntity.find("targetId", member).firstResult()!!.id }
        val user = read { VeyqUser.findById(member)!! }
        QuarkusTransaction.requiringNew().run {
            AccountErasureEntity().apply {
                accountId = member; requestedAt = Instant.now(); requestedBy = AccountErasureEntity.SERVER_OWNER; dueAt = Instant.now()
            }.persist()
        }
        accounts.purge(member)
        assertEquals(listOf("BLOCK", "LIFT"), read {
            ErasureOutboxEntity.list("subjectId = ?1 order by recordedAt", block).map { it.kind }
        }, "the eraser's end of the measure is a lift line")
        // The restore: the account and its block, in force, as a capture before the erasure holds them.
        restore(user)
        QuarkusTransaction.requiringNew().run {
            ModerationBlockEntity.update("liftedAt = null, activeKey = ?1 where id = ?2",
                ModerationBlockEntity.activeKeyOf(BlockKind.MEMBER_SIGN_IN, member), block)
        }
        start.run()
        assertNull(read { VeyqUser.findById(member) })
        assertEquals(0L, read { ModerationBlockEntity.count("targetId = ?1 and activeKey is not null", member) })
        assertTrue(read { ModerationBlockEntity.findById(block)!!.endedWithTarget }, "still a measure that ended with its target")
    }

    @Test
    fun aBlockLiftedAfterTheCaptureStaysLifted() {
        val owner = seedUser(serverRole = "SERVER_OWNER")
        val member = seedUser()
        assertEquals(BlockOutcome.Blocked(null), blocks.block(owner, "u-$member@example.com", null))
        val row = read { ModerationBlockEntity.find("targetId", member).firstResult()!!.id }
        assertNull(blocks.unblock(owner, row))
        // The restore: the capture held the block in force.
        QuarkusTransaction.requiringNew().run {
            ModerationBlockEntity.update("liftedAt = null, liftedBy = null, activeKey = ?1 where id = ?2",
                ModerationBlockEntity.activeKeyOf(BlockKind.MEMBER_SIGN_IN, member), row)
        }
        assertEquals(1, start.run().relifted)
        assertEquals(0L, read { ModerationBlockEntity.count("targetId = ?1 and activeKey is not null", member) })
        // Lifted by the Server Owner, as the line says — never read as a measure that ended with its erased target.
        assertEquals(owner, read { ModerationBlockEntity.findById(row)!!.liftedBy })
    }

    @Test
    fun aCaseTheRestoreLacksComesBackAsAStubKeepingItsRetention() {
        val (_, scopeId) = seedMemberScope()
        val closed = UUID.randomUUID().toString()
        val open = UUID.randomUUID().toString()
        val closedAt = Instant.now()
        QuarkusTransaction.requiringNew().run {
            for (id in listOf(closed, open)) {
                ModerationCaseEntity().apply {
                    this.id = id; itemKind = "NODE"; itemId = UUID.randomUUID().toString(); this.scopeId = scopeId
                    category = "ILLEGAL_CONTENT"; state = CaseState.OPEN.name; openKey = "NODE:$id"
                }.persist()
                log.appendCase(ModerationEvent.RECEIVED, closedAt.minusSeconds(60), id)
            }
            ModerationCaseEntity.findById(closed)!!.apply { state = CaseState.CLOSED.name; this.closedAt = closedAt; openKey = null }
            ModerationCaseEntity.getEntityManager().flush()
            log.closeCase(closed, closedAt)
        }
        val retainUntil = ModerationLog.retentionEnd(closedAt)
        // The restore: a capture before both cases.
        QuarkusTransaction.requiringNew().run {
            ModerationEventEntity.delete("caseId in ?1", listOf(closed, open))
            ModerationCaseEntity.delete("id in ?1", listOf(closed, open))
        }
        assertEquals(2, start.run().caseStubs)
        read {
            val stub = ModerationCaseEntity.findById(closed)!!
            assertEquals(CaseOrigin.RESTORED.name, stub.origin)
            assertEquals(CaseState.CLOSED.name, stub.state)
            assertEquals(listOf(ModerationEvent.RESTORED.name to retainUntil),
                ModerationEventEntity.list("caseId", closed).map { it.event to it.expiresAt }, "its retention date kept, no report")
            assertEquals(CaseState.OPEN.name, ModerationCaseEntity.findById(open)!!.state, "an undecided case is still owed a decision")
        }
        val queued = cases.queue(null).single { it.id.toString() == open }
        assertEquals("RESTORED", queued.origin.name, "the queue shows that a case existed")
        assertEquals(0, start.run().caseStubs, "a stub is made once")
    }

    /**
     * A dismissal ends the hold at once (ADR 0093 § 4) and writes a `HOLD_ENDED` line: a restore of a capture from before
     * the dismissal brings the case back open and its hold back with its snapshot, its pins and an end up to 90 days
     * ahead — for child sexual abuse longer than § 184b(5) StGB allows. The start ends the hold again and closes the case,
     * as it was; a case reported beside it and never decided keeps its hold.
     */
    @Test
    fun aHoldADismissalEndedAfterTheCaptureEndsAgainAndItsCaseClosesAgain() {
        val mod = CaseTestKit.moderator()
        val m = seedMemberScope(ScopeEntity.GROUP)
        val dismissed = seedNode(m.scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        seedAttachment(m.scopeId, dismissed, "cover")
        val undecided = seedNode(m.scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        asMember(m.userId, m.scopeId, body(listOf(item("NODE", dismissed), item("NODE", undecided)))).then().statusCode(201)
        val caseId = openCase("NODE", dismissed)!!.id
        val control = openCase("NODE", undecided)!!.id
        val hold = CaseTestKit.holdOf(caseId)!!.id
        // The capture: the case open, its hold with its snapshot and its pins, and the log as it stood.
        val captured = read { listOf(HoldEntity.findById(hold)!!) + HoldRowEntity.list("holdId", hold) + HoldPinEntity.list("holdId", hold) }
        val openKey = CaseTestKit.caseRow(caseId).openKey!!
        val events = read { ModerationEventEntity.list("caseId", caseId).map { it.id } }
        assertTrue(read { HoldRowEntity.count("holdId", hold) } > 0 && CaseTestKit.pinsOf(hold) > 0, "the hold holds something")

        mod.dismiss(caseId).then().statusCode(200)
        assertNull(read { HoldEntity.findById(hold) }, "a dismissal ends the hold at once")
        assertEquals(1L, read { ErasureOutboxEntity.count("kind = ?1 and subjectId = ?2", "HOLD_ENDED", hold) }, "…with its line")

        // The restore: the case open again, its hold back with its end ahead, its log without the dismissal. (The case row
        // still exists, so it is written back by an update — a merge onto a live row is lost to field-access dirty tracking.)
        restore(*captured.toTypedArray())
        QuarkusTransaction.requiringNew().run {
            ModerationCaseEntity.update("state = ?1, openKey = ?2, closedAt = null where id = ?3", CaseState.OPEN.name, openKey, caseId)
            ModerationEventEntity.delete("caseId = ?1 and id not in ?2", caseId, events)
        }
        assertEquals(CaseState.OPEN.name, CaseTestKit.caseRow(caseId).state)
        assertNotNull(read { HoldEntity.findById(hold) }, "the restore brought the hold back")

        val done = start.run()
        assertEquals(1, done.erased["HOLD_ENDED"])
        assertEquals(1, done.casesMoved)
        assertNull(read { HoldEntity.findById(hold) }, "ended again before the instance serves")
        assertEquals(0L, read { HoldRowEntity.count("holdId", hold) }, "its snapshot gone")
        assertEquals(0L, CaseTestKit.pinsOf(hold), "its pins released")
        val case = CaseTestKit.caseRow(caseId)
        assertEquals(CaseState.CLOSED.name, case.state, "closed, as it was closed")
        assertNull(case.openKey, "no longer the item's open case")
        assertTrue(read { ModerationEventEntity.list("caseId", caseId).all { it.expiresAt != null } }, "its log dated by the close")
        assertNotNull(CaseTestKit.holdOf(control), "control: the hold of a case nobody decided stays")
        assertEquals(CaseState.OPEN.name, CaseTestKit.caseRow(control).state)

        val again = start.run()
        assertEquals(null, again.erased["HOLD_ENDED"], "a line applied twice ends nothing")
        assertEquals(0, again.casesMoved)
    }

    // --- B, the setting (ADR 0089) ------------------------------------------------------------------------------------------

    @Test
    fun theServerOwnerSetsBWithinSevenTo365() {
        val owner = seedUser(serverRole = "SERVER_OWNER")
        fun admin(): RequestSpecification = givenAdminChannel().header("Authorization", bearer(owner))
        admin().`when`().get(PATH).then().statusCode(200).body("retentionDays", equalTo(30)).body("chosen", equalTo(false))
        admin().contentType(ContentType.JSON).body("""{"retentionDays":90}""").`when`().put(PATH).then().statusCode(200)
            .body("retentionDays", equalTo(90)).body("chosen", equalTo(true))
        for (bad in listOf("""{"retentionDays":6}""", """{"retentionDays":366}""", "{}")) {
            admin().contentType(ContentType.JSON).body(bad).`when`().put(PATH).then().statusCode(400).body("code", equalTo("invalid_request"))
        }
        assertEquals(90, read { BackupRetentionEntity.findById(BackupRetentionEntity.ROW)!!.retentionDays }, "nothing out of range was stored")
        givenAdminChannel().header("Authorization", bearer(seedUser())).`when`().get(PATH).then().statusCode(403)
    }

    companion object {
        const val PATH = "/api/instance/backup-retention"
    }
}
