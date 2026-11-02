package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.testing.TestNodeRekey
import me.raddatz.veyq.core.identity.PrincipalKey
import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.membership.TenantRoots
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import me.raddatz.veyq.core.testing.whileUncommitted
import me.raddatz.veyq.core.testing.holdForOverlap
import me.raddatz.veyq.core.testing.raceRounds
import io.quarkus.arc.Arc
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A share root's re-sealed name (`setShareSealedName`, ADR 0080 decision 14): when a member renames a share root,
 * that member's client re-seals the name of every share on it — other members' included — under each share's
 * `K_share` and stores it here. Any member who may write in the share's space may, not only the owner (`updateShare`
 * stays owner-gated, ADR 0036 point 3); a reader and a stranger may not; the write changes `sealed_name` and no other
 * column; a deleted or unknown share is the 404; the bounds are the create field's; and it takes only the share row's
 * lock, so it neither reverts nor deadlocks with a revoke or a re-activation of the same link.
 *
 * A `FOLDER` share with READ: core alone shares a folder (`ShareRoots`), so this runs in `:core`.
 */
@QuarkusTest
class ShareSealedNameCoreSliceTest {

    @Inject
    lateinit var sharing: SharingStore

    @Inject
    lateinit var handler: SharingHandler

    private val oldName = "b2xkLXNlYWxlZC1uYW1l" // "old-sealed-name"
    private val newName = "bmV3LXNlYWxlZC1uYW1l" // "new-sealed-name"

    /** A group: its owner, a co-member who may write, a member removed from the tenant (still a reader of the group
     *  for the grace window, no writer), a stranger, and a folder in it. */
    private class World(
        val owner: String, val member: String, val reader: String, val stranger: String, val scopeId: String, val folder: String,
    )

    @Test
    fun theOwnerAndAnotherWritingMemberSetItAndResolveServesTheNewName() {
        val w = world()
        val shareId = createShare(w.owner, w.folder)
        resolvedName(shareId, oldName)

        // Another member — not the share's owner — re-seals it, as the member who renamed the root does.
        setName(w.member, shareId, newName).then().statusCode(204)
        resolvedName(shareId, newName)

        // The owner too; the old value is gone either way (last writer wins).
        setName(w.owner, shareId, oldName).then().statusCode(204)
        resolvedName(shareId, oldName)
        setName(w.owner, shareId, newName).then().statusCode(204)
        resolvedName(shareId, newName)
        assertEquals(w.owner, row(shareId).ownerAccount, "a re-seal adopts nothing")
    }

    @Test
    fun aReaderIsRefusedAndWritesNothing() {
        val w = world()
        val shareId = createShare(w.owner, w.folder)
        // The reader really reads the group: it lists the folder's shares (READ passes, WRITE does not).
        given().header("Authorization", bearer(w.reader)).`when`().get("/api/shares?nodeId=${w.folder}")
            .then().statusCode(200).body("size()", equalTo(1))

        setName(w.reader, shareId, newName).then().statusCode(404).body("code", equalTo("share_not_found"))
        assertEquals(oldName, row(shareId).sealedName)
        resolvedName(shareId, oldName)
    }

    @Test
    fun aStrangerIsRefusedAndWritesNothing() {
        val w = world()
        val shareId = createShare(w.owner, w.folder)
        setName(w.stranger, shareId, newName).then().statusCode(404).body("code", equalTo("share_not_found"))
        assertEquals(oldName, row(shareId).sealedName)
        resolvedName(shareId, oldName)
    }

    @Test
    fun everyOtherColumnOfTheShareStaysUntouched() {
        val w = world()
        val shareId = TestIds.shareId()
        val expiry = Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
        given().header("Authorization", bearer(w.owner)).contentType(ContentType.JSON)
            .body(
                """{"shareId":"$shareId",${ShareWriteProof.field(shareId)},"nodeId":"${w.folder}","role":"READ","wrap":"d3JhcA==",""" +
                    """"sealedName":"$oldName",${ShareWriteProof.recovery()},"sealedNote":"bm90ZQ==","sealedShareName":"bGFiZWw=",""" +
                    """"expiresAt":"$expiry","passwordRequired":true,"pwSalt":"c2FsdA==","pwVerifier":"dmVyaWZpZXI=","sealedPassword":"cHc="}""",
            )
            .`when`().post("/api/shares").then().statusCode(201)
        val before = snapshot(row(shareId))

        setName(w.member, shareId, newName).then().statusCode(204)

        val after = row(shareId)
        assertEquals(newName, after.sealedName)
        assertEquals(before, snapshot(after), "a re-seal changed a column other than sealed_name")
        // And the snapshot is not vacuous: every field it holds was set.
        assertTrue(before.values.all { it != null }, "the fixture left a column empty: $before")
    }

    @Test
    fun aDeletedShareAndAnUnknownIdAre404() {
        val w = world()
        val shareId = createShare(w.owner, w.folder)
        given().header("Authorization", bearer(w.owner)).`when`().delete("/api/shares/$shareId").then().statusCode(204)

        setName(w.owner, shareId, newName).then().statusCode(404).body("code", equalTo("share_not_found"))
        setName(w.member, shareId, newName).then().statusCode(404).body("code", equalTo("share_not_found"))
        setName(w.owner, TestIds.shareId(), newName).then().statusCode(404).body("code", equalTo("share_not_found"))
        assertEquals(null, row(shareId).sealedName, "the re-seal wrote onto the tombstone")
    }

    /**
     * ADR 0114 decisions 5 and 7: a renaming client re-seals under open links only, and a reopen carries the root's
     * current name — so the name a re-activation shows is the one it brought. The server still takes a re-seal of a
     * switched-off share (the client decides which links are open); the reopen's name replaces it.
     */
    @Test
    fun aRevokedShareTakesAReSealAndItsReactivationShowsTheNameItCarries() {
        val w = world()
        val shareId = createShare(w.owner, w.folder)
        given().header("Authorization", bearer(w.owner)).`when`().post("/api/shares/$shareId/revoke").then().statusCode(204)
        setName(w.member, shareId, newName).then().statusCode(204)
        assertEquals(newName, row(shareId).sealedName) // control: the re-seal landed on the switched-off share
        given().header("Authorization", bearer(w.member)).contentType(ContentType.JSON).body(TestNodeRekey.reopenBody(shareId)).`when`().post("/api/shares/$shareId/reactivate").then().statusCode(204)
        resolvedName(shareId, ShareWriteProof.REOPEN_NAME)
    }

    @Test
    fun anOversizedOrNonBase64NameIs400AndWritesNothing() {
        val w = world()
        val shareId = createShare(w.owner, w.folder)
        for (body in listOf(
            """{"sealedName":"${"A".repeat(4100)}"}""", // over the create field's 4096
            """{"sealedName":"not base64!"}""",
            """{"sealedName":""}""",
            "{}",
        )) {
            given().header("Authorization", bearer(w.owner)).contentType(ContentType.JSON).body(body)
                .`when`().put("/api/shares/$shareId/sealed-name").then().statusCode(400)
        }
        assertEquals(oldName, row(shareId).sealedName)
        // Control: the largest name the bound admits lands.
        setName(w.owner, shareId, "A".repeat(4096)).then().statusCode(204)
    }

    // --- races (developer-styleguide §12): the share row's lock, and only it ---

    @Test
    fun aReSealOverlappingARevokeKeepsBothWrites() {
        val w = world()
        raceRounds { round ->
            val shareId = createShare(w.owner, w.folder)
            val outcome = whileUncommitted(
                change = { assertTrue(sharing.revoke(w.owner, shareId), "round $round") },
                overlapping = { handler.setSealedName(w.member, shareId, newName) },
            )
            assertEquals(SetShareSealedNameOutcome.Done, outcome, "round $round")
            val r = row(shareId)
            assertFalse(r.enabled, "round $round: the re-seal switched the revoked link back on")
            assertEquals(newName, r.sealedName, "round $round")
        }
        raceRounds { round ->
            val shareId = createShare(w.owner, w.folder)
            val outcome = whileUncommitted(
                change = { assertTrue(sharing.setSealedName(shareId, newName, PrincipalKey.User(w.owner)), "round $round") },
                overlapping = { handler.revoke(w.owner, shareId) },
            )
            assertEquals(RevokeShareOutcome.Done, outcome, "round $round")
            val r = row(shareId)
            assertFalse(r.enabled, "round $round")
            assertEquals(newName, r.sealedName, "round $round: the revoke reverted the re-seal")
        }
    }

    /**
     * A re-activation takes the scope row, then the share row (`SharingHandler.reactivate`). Here it holds the scope
     * row first and reaches for the share row only once the re-seal is under way: a re-seal that took the share row
     * and then reached for the scope row would hold what the re-activation waits for while waiting for what the
     * re-activation holds — a deadlock, which the engine breaks by failing one of them. Taking the share row alone,
     * the re-seal commits and the re-activation follows. Forced order, not a likely one: one round shows it.
     */
    @Test
    fun aReSealOverlappingAReactivationEndsConsistentWithoutADeadlock() {
        val w = world()
        raceRounds { round ->
            val shareId = createShare(w.owner, w.folder)
            given().header("Authorization", bearer(w.owner)).`when`().post("/api/shares/$shareId/revoke").then().statusCode(204)
            TestNodeRekey.finish(w.scopeId) // the revoke marked the folder due (ADR 0114 decision 1)
            val epoch = TestNodeRekey.epochOf(w.folder)

            val scopeLocked = CountDownLatch(1)
            val reSealRef = AtomicReference<Future<*>>()
            val pool = Executors.newFixedThreadPool(2)
            try {
                val reactivation = pool.submit(
                    Callable {
                        QuarkusTransaction.requiringNew().call {
                            ScopeEntity.lockRow(w.scopeId) // the re-activation's first lock, taken early
                            scopeLocked.countDown()
                            // The re-seal now runs, and takes what it takes: the share row alone ends inside the hold, a
                            // reach for the scope row is seen waiting on it — either is the order this round means.
                            val overlap = holdForOverlap { reSealRef.get()?.isDone == true }
                            overlap to handler.reactivate(w.member, shareId, SharingHandler.ReopenRequest(epoch, ShareWriteProof.REOPEN_WRAP, null, ShareWriteProof.REOPEN_NAME)) // joins: the scope row again, then the share row
                        }
                    },
                )
                assertTrue(scopeLocked.await(10, TimeUnit.SECONDS), "round $round")
                val reSeal = pool.submit(
                    Callable {
                        val request = Arc.container().requestContext()
                        request.activate()
                        try {
                            handler.setSealedName(w.owner, shareId, newName)
                        } finally {
                            request.terminate()
                        }
                    },
                ).also(reSealRef::set)
                val sealed = reSeal.get(30, TimeUnit.SECONDS)
                val (overlap, reactivated) = reactivation.get(30, TimeUnit.SECONDS)
                overlap.requireForced()
                assertEquals(SetShareSealedNameOutcome.Done, sealed, "round $round")
                assertEquals(ReactivateShareOutcome.Done, reactivated, "round $round")
            } finally {
                pool.shutdownNow()
            }
            val r = row(shareId)
            assertTrue(r.enabled, "round $round")
            assertEquals(w.member, r.ownerAccount, "round $round")
            // The re-activation came second and carries the root's name back (ADR 0114 decision 7): its name stands.
            assertEquals(ShareWriteProof.REOPEN_NAME, r.sealedName, "round $round")
        }
    }

    // --- helpers ---

    private fun setName(userId: String, shareId: String, sealedName: String) =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON).body("""{"sealedName":"$sealedName"}""")
            .`when`().put("/api/shares/$shareId/sealed-name")

    private fun resolvedName(shareId: String, expected: String) {
        given().`when`().get("/api/shares/$shareId").then().statusCode(200).body("sealedName", equalTo(expected))
    }

    /** A link on [nodeId], minted at the node's own epoch once an earlier round's revoke of a link there is re-keyed
     *  (a close marks the node due, ADR 0114 decision 1). */
    private fun createShare(userId: String, nodeId: String): String {
        val shareId = TestIds.shareId()
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"shareId":"$shareId",${ShareWriteProof.field(shareId)},"nodeId":"$nodeId","role":"READ","wrap":"d3JhcA==","sealedName":"$oldName",${ShareWriteProof.recovery(nodeEpoch = TestNodeRekey.settled(nodeId))}}""")
            .`when`().post("/api/shares").then().statusCode(201)
        return shareId
    }

    private fun row(shareId: String): ShareEntity = QuarkusTransaction.requiringNew().call { ShareEntity.findById(shareId)!! }

    /** Every column of a live share a re-seal must leave alone. */
    private fun snapshot(s: ShareEntity): Map<String, Any?> = linkedMapOf(
        "ownerAccount" to s.ownerAccount, "scopeId" to s.scopeId, "nodeId" to s.nodeId, "role" to s.role,
        "wrap" to s.wrap, "enabled" to s.enabled, "expiresAt" to s.expiresAt, "sealedNote" to s.sealedNote,
        "sealedShareName" to s.sealedShareName, "sealedShareKey" to s.sealedShareKey,
        "sealedShareKeyEpoch" to s.sealedShareKeyEpoch, "passwordRequired" to s.passwordRequired, "pwSalt" to s.pwSalt,
        "pwVerifier" to s.pwVerifier, "sealedPassword" to s.sealedPassword, "writeVerifier" to s.writeVerifier,
        "reissueOnly" to s.reissueOnly, "createdAt" to s.createdAt,
    )

    private fun world(): World {
        val (owner, scopeId, tenantId) = seedMemberScope(ScopeEntity.GROUP)
        val member = seedUser()
        val reader = seedUser()
        QuarkusTransaction.requiringNew().run {
            membership(member, tenantId, scopeId)
            TenantRoots.join(member, tenantId)
            membership(reader, tenantId, scopeId) // in the group, but no longer in the tenant: reads, never writes
        }
        val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        return World(owner, member, reader, seedUser(), scopeId, folder)
    }

    private fun membership(userId: String, tenantId: String, scopeId: String) {
        MembershipEntity().apply {
            id = UUID.randomUUID().toString(); this.userId = userId; this.tenantId = tenantId
            this.scopeId = scopeId; role = MembershipEntity.MEMBER; wrappedGrant = "Z3JhbnQ="; createdAt = Instant.now()
        }.persist()
    }
}
