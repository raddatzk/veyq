package me.raddatz.veyq.core.account.rotation

import me.raddatz.veyq.core.admin.tenant.MintMemberInvite
import me.raddatz.veyq.core.admin.tenant.TenantInviteEntity
import me.raddatz.veyq.core.admin.tenant.TenantStore
import me.raddatz.veyq.core.crypto.GrantSignature
import me.raddatz.veyq.core.crypto.InviteAcceptProof
import me.raddatz.veyq.core.crypto.JoinEndorsement
import me.raddatz.veyq.core.crypto.Tokens
import me.raddatz.veyq.core.identity.MasterKeyWrapEntity
import me.raddatz.veyq.core.identity.TenantProfileEntity
import me.raddatz.veyq.core.identity.TenantProfileStore
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.membership.TestGrants
import me.raddatz.veyq.core.membership.TestInviteKey
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.TestFixtures
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import me.raddatz.veyq.core.testing.raceRounds
import me.raddatz.veyq.core.testing.whileUncommitted
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * An owner's securing re-signs what it rooted (ADR 0111 decisions 20 and 21, a staged invite
 * declared or re-signed): the two plan reads, the two stage writes and the
 * publish's completeness over the caller's open invites and the join records it rooted — every one staged, or the whole
 * publish refused `409 rotation_plan_changed`, nothing written. A re-signed invite gets the new identity's signatures and
 * key, a declared one ends, an endorsement lands on the profile and is served. Every refusal is anchored on its status
 * and code and has a control that differs in one input. Signatures are real; ciphertext is a stand-in.
 */
@QuarkusTest
class RotationRootedSliceTest {

    @Inject lateinit var tenants: TenantStore
    @Inject lateinit var profiles: TenantProfileStore

    // --- the plans ---------------------------------------------------------------------------------------------

    @Test
    fun thePlansListTheCallersOpenInvitesAndTheRecordsItRootedInEveryTenant() {
        val w = seedWorld()
        val otherTenant = seedTenant(owner = w.rotation.userId)
        val elsewhere = seedInvite(otherTenant, w.rotation.userId, w.rotation.current.bundle)
        val foreign = seedInvite(w.tenantId, w.coOwner, TestFixtures.IDENTITY_PUBLIC_KEY)

        given().header("Authorization", w.rotation.bearer()).`when`().get("/api/account/rotation/plan/invites").then().statusCode(200)
            .body("size()", equalTo(2))
            .body("find { it.inviteId == '${w.invite.inviteId}' }.tenantId", equalTo(w.tenantId))
            .body("find { it.inviteId == '${w.invite.inviteId}' }.invitePublicKey", equalTo(w.invite.bundle))
            .body("find { it.inviteId == '${w.invite.inviteId}' }.anchorWrappedGrant", equalTo(TestGrants.WRAPPED))
            .body("find { it.inviteId == '${w.invite.inviteId}' }.anchorEpoch", equalTo(0))
            .body("find { it.inviteId == '${w.invite.inviteId}' }.anchorGrantSignature", equalTo(TestGrants.SIGNATURE))
            .body("find { it.inviteId == '${w.invite.inviteId}' }.inviteKeySignature", equalTo(TestGrants.SIGNATURE))
            .body("find { it.inviteId == '${elsewhere.inviteId}' }.tenantId", equalTo(otherTenant))
            .body("find { it.inviteId == '${foreign.inviteId}' }", equalTo(null))
        given().header("Authorization", w.rotation.bearer()).`when`().get("/api/account/rotation/plan/join-records").then().statusCode(200)
            .body("size()", equalTo(1))
            .body("[0].tenantId", equalTo(w.tenantId))
            .body("[0].userId", equalTo(w.member))
            .body("[0].sealedJoinRecord", equalTo(TestGrants.JOIN_RECORD))
            .body("[0].joinEndorsement", equalTo(null))
    }

    // --- the publish applies them ----------------------------------------------------------------------------

    @Test
    fun aPublishReSignsTheInvitesEndsTheDeclaredOnesAndEndorsesTheRecords() {
        val w = seedWorld()
        val declared = seedInvite(w.tenantId, w.rotation.userId, w.rotation.current.bundle)
        val foreign = seedInvite(w.tenantId, w.coOwner, TestFixtures.IDENTITY_PUBLIC_KEY)
        val stageId = stageGrants(w)
        stageInvites(w, stageId, resigned(w, w.invite), declaredEntry(declared)).then().statusCode(204)
        val endorsement = endorse(w, w.member)
        stageEndorsements(w, stageId, """{"tenantId":"${w.tenantId}","userId":"${w.member}","endorsement":"$endorsement","recordDigest":"${recordDigest(w, w.member)}"}""").then().statusCode(204)

        w.rotation.send(w.rotation.publish(stageId, w.grants)).then().statusCode(200)

        val inv = tx { TenantInviteEntity.findById(w.invite.inviteId)!! }
        val next = w.rotation.next.bundle
        assertEquals(TenantInviteEntity.ACTIVE, inv.status)
        assertEquals(next, inv.signerIdentityPublicKey, "the signer key is the published identity")
        assertTrue(InviteAcceptProof.inviteKeyVerifies(next, w.tenantId, inv.id, inv.invitePublicKey!!, inv.expiresAt.epochSecond, inv.inviteKeySignature))
        assertTrue(GrantSignature.verify(next, anchorStatement(inv), inv.anchorGrantSignature), "the anchor re-signed by the new key")
        assertEquals(next to inv.anchorGrantSignature, inv.granterIdentityPublicKey to inv.grantSignature, "the grant IS the anchor: re-signed too")
        assertEquals(TestGrants.WRAPPED to 0, inv.anchorWrappedGrant to inv.anchorEpoch, "the anchor's grant and epoch are untouched")

        val ended = tx { TenantInviteEntity.findById(declared.inviteId)!! }
        assertEquals(TenantInviteEntity.SUPERSEDED, ended.status, "a declared invite ends")
        assertEquals(List(12) { null }, columns(ended), "with its key, grant and anchor")

        val other = tx { TenantInviteEntity.findById(foreign.inviteId)!! }
        assertEquals(TenantInviteEntity.ACTIVE to TestFixtures.IDENTITY_PUBLIC_KEY, other.status to other.signerIdentityPublicKey, "control: another owner's invite")

        assertEquals(endorsement, tx { profile(w.member, w.tenantId).joinEndorsement })
        given().header("Authorization", TestFixtures.bearer(w.coOwner)).`when`().get("/api/scopes/${w.tenantId}/members").then().statusCode(200)
            .body("find { it.userId == '${w.member}' }.joinEndorsement", equalTo(endorsement))
    }

    @Test
    fun aDeclaredRecordClearsItsEndorsement() {
        val w = seedWorld()
        tx { TenantProfileEntity.update("joinEndorsement = ?1 where userId = ?2", TestGrants.SIGNATURE, w.member) }
        val stageId = stageGrants(w)
        stageInvites(w, stageId, resigned(w, w.invite)).then().statusCode(204)
        stageEndorsements(w, stageId, """{"tenantId":"${w.tenantId}","userId":"${w.member}","endorsement":null}""").then().statusCode(204)
        w.rotation.send(w.rotation.publish(stageId, w.grants)).then().statusCode(200)
        assertNull(tx { profile(w.member, w.tenantId).joinEndorsement })
    }

    @Test
    fun anOpenInviteLeftUnstagedRefusesThePublish() {
        val w = seedWorld()
        val stageId = stageGrants(w)
        stageEndorsements(w, stageId, endorsed(w)).then().statusCode(204)
        val publish = w.rotation.publish(stageId, w.grants)

        w.rotation.send(publish).then().statusCode(409).body("code", equalTo("rotation_plan_changed"))
        assertNothingWritten(w)

        // Control: staged, the same publish goes through.
        stageInvites(w, stageId, resigned(w, w.invite)).then().statusCode(204)
        w.rotation.send(publish).then().statusCode(200)
    }

    @Test
    fun aRootedRecordLeftUnstagedRefusesThePublish() {
        val w = seedWorld()
        val stageId = stageGrants(w)
        stageInvites(w, stageId, resigned(w, w.invite)).then().statusCode(204)
        val publish = w.rotation.publish(stageId, w.grants)

        w.rotation.send(publish).then().statusCode(409).body("code", equalTo("rotation_plan_changed"))
        assertNothingWritten(w)

        stageEndorsements(w, stageId, endorsed(w)).then().statusCode(204)
        w.rotation.send(publish).then().statusCode(200)
    }

    @Test
    fun anAcceptBetweenStageAndPublishRefusesItUntilTheNewRecordIsStaged() {
        val w = seedWorld()
        val secret = "rooted-race-${UUID.randomUUID()}"
        val email = "race-${UUID.randomUUID()}@example.com"
        val second = seedInvite(w.tenantId, w.rotation.userId, w.rotation.current.bundle, secret = secret, email = email)
        val stageId = stageGrants(w)
        stageInvites(w, stageId, resigned(w, w.invite), resigned(w, second)).then().statusCode(204)
        stageEndorsements(w, stageId, endorsed(w)).then().statusCode(204)
        val publish = w.rotation.publish(stageId, w.grants)

        // A newcomer accepts the second invite: a join record the stage does not cover.
        val newcomer = seedUser(email = email)
        accept(newcomer, secret, second).then().statusCode(200)
        assertEquals(w.rotation.userId, tx { profile(newcomer, w.tenantId).joinSignerUserId })

        w.rotation.send(publish).then().statusCode(409).body("code", equalTo("rotation_plan_changed"))
        assertNothingWritten(w)

        // Control: the delta staged (the spent invite needs nothing), the same publish goes through.
        stageEndorsements(w, stageId, """{"tenantId":"${w.tenantId}","userId":"$newcomer","endorsement":"${endorse(w, newcomer)}","recordDigest":"${recordDigest(w, newcomer)}"}""")
            .then().statusCode(204)
        w.rotation.send(publish).then().statusCode(200)
    }

    @Test
    fun anAcceptUncommittedInATenantTheOwnerLeftHoldsThePublishUntilItIsSeen() {
        // The publish locks the tenant scope of every tenant the account rooted something in — also one it no longer holds
        // a grant on, which its own grants would not name. An accept holds that lock while it writes a join record the
        // account rooted: the publish waits for it and then refuses the record it did not stage. Without the lock it
        // reads past the uncommitted record, publishes, and the accept commits a record naming the retired key.
        raceRounds { round ->
            val w = seedLeftWorld()
            val stageId = stageGrants(w)
            stageEndorsements(w, stageId, """{"tenantId":"${w.tenantId}","userId":"${w.member}","endorsement":null}""").then().statusCode(204)
            val publish = w.rotation.publish(stageId, w.grants)
            val newcomer = seedUser()

            val answer = whileUncommitted(
                change = {
                    tenantsLock(w.tenantId)
                    profiles.create(newcomer, w.tenantId, "n${UUID.randomUUID().toString().take(8)}", joinRecord = TestGrants.SEALED_JOIN_RECORD, joinSignerUserId = w.rotation.userId)
                },
                overlapping = { w.rotation.send(publish).then().extract().let { it.statusCode() to it.path<String?>("code") } },
            )
            assertEquals(409 to "rotation_plan_changed", answer, "round $round: the accept's record is seen, unstaged")
            assertEquals(w.rotation.current.bundle, tx { VeyqUser.findById(w.rotation.userId)!!.identityPublicKey }, "round $round: nothing published")

            // Control: the new record staged, the same publish goes through.
            stageEndorsements(w, stageId, """{"tenantId":"${w.tenantId}","userId":"$newcomer","endorsement":null}""").then().statusCode(204)
            w.rotation.send(publish).then().statusCode(200)
        }
    }

    @Test
    fun aRecordReplacedSinceStagingRefusesThePublish() {
        // A rejoin writes a new record into a profile the stage covers: the staged endorsement covered the old bytes.
        val w = seedWorld()
        val stageId = stageGrants(w)
        stageInvites(w, stageId, resigned(w, w.invite)).then().statusCode(204)
        stageEndorsements(w, stageId, endorsed(w)).then().statusCode(204)
        val publish = w.rotation.publish(stageId, w.grants)
        tx { TenantProfileEntity.update("sealedJoinRecord = ?1 where userId = ?2", "bmV3LXJlY29yZA==", w.member) }

        w.rotation.send(publish).then().statusCode(409).body("code", equalTo("rotation_plan_changed"))
        assertNothingWritten(w)

        stageEndorsements(w, stageId, endorsed(w)).then().statusCode(204)
        w.rotation.send(publish).then().statusCode(200)
    }

    @Test
    fun anInviteWhoseAnchorMovedSinceStagingRefusesThePublish() {
        val w = seedWorld()
        val stageId = stageGrants(w)
        stageInvites(w, stageId, resigned(w, w.invite)).then().statusCode(204)
        stageEndorsements(w, stageId, endorsed(w)).then().statusCode(204)
        val publish = w.rotation.publish(stageId, w.grants)
        tx { TenantInviteEntity.update("anchorEpoch = 1 where id = ?1", w.invite.inviteId) }

        w.rotation.send(publish).then().statusCode(409).body("code", equalTo("rotation_plan_changed"))
        assertNothingWritten(w)

        // Control: re-staged at the anchor epoch the invite holds now.
        stageInvites(w, stageId, resigned(w, w.invite, anchorEpoch = 1)).then().statusCode(204)
        w.rotation.send(publish).then().statusCode(200)
    }

    @Test
    fun afterThePublishAnAcceptNamingTheRetiredKeyIsInviteChanged() {
        val w = seedWorld()
        val secret = "rooted-after-${UUID.randomUUID()}"
        val email = "after-${UUID.randomUUID()}@example.com"
        val second = seedInvite(w.tenantId, w.rotation.userId, w.rotation.current.bundle, secret = secret, email = email)
        val retired = TestGrants.digest(w.rotation.current.bundle)
        val stageId = stageGrants(w)
        stageInvites(w, stageId, resigned(w, w.invite), resigned(w, second)).then().statusCode(204)
        stageEndorsements(w, stageId, endorsed(w)).then().statusCode(204)
        w.rotation.send(w.rotation.publish(stageId, w.grants)).then().statusCode(200)

        val newcomer = seedUser(email = email)
        accept(newcomer, secret, second, retired).then().statusCode(409).body("code", equalTo("invite_changed"))
        // Control: the key the publish installed.
        accept(newcomer, secret, second, TestGrants.digest(w.rotation.next.bundle)).then().statusCode(200)
    }

    // --- the stage refusals -----------------------------------------------------------------------------------

    @Test
    fun aReSignatureNotByTheStagesIdentityIsRefused() {
        val w = seedWorld()
        val stageId = w.rotation.openStageId()
        stageInvites(w, stageId, resigned(w, w.invite, signer = w.rotation.current.identity))
            .then().statusCode(422).body("code", equalTo("invite_resign_invalid"))
        stageInvites(w, stageId, resigned(w, w.invite, anchorSigner = w.rotation.current.identity))
            .then().statusCode(422).body("code", equalTo("invite_resign_invalid"))
        assertEquals(0L, tx { RotationStageInviteEntity.count("stageId", stageId) }, "nothing staged")
        stageInvites(w, stageId, resigned(w, w.invite)).then().statusCode(204)
        assertEquals(1L, tx { RotationStageInviteEntity.count("stageId", stageId) })
    }

    @Test
    fun aStagedInviteMustBeTheCallersOpenOneAtItsAnchorEpoch() {
        val w = seedWorld()
        val foreign = seedInvite(w.tenantId, w.coOwner, TestFixtures.IDENTITY_PUBLIC_KEY)
        val stageId = w.rotation.openStageId()
        stageInvites(w, stageId, resigned(w, foreign)).then().statusCode(409).body("code", equalTo("rotation_plan_changed"))
        stageInvites(w, stageId, declaredEntry(foreign)).then().statusCode(409).body("code", equalTo("rotation_plan_changed"))
        stageInvites(w, stageId, resigned(w, w.invite, anchorEpoch = 1)).then().statusCode(409).body("code", equalTo("rotation_plan_changed"))
        stageInvites(w, stageId, declaredEntry(w.invite)).then().statusCode(204) // control
    }

    @Test
    fun aStagedInviteWithOneSignatureIsMalformed() {
        val w = seedWorld()
        val stageId = w.rotation.openStageId()
        val full = resigned(w, w.invite)
        val half = full.replace(Regex(""""anchorGrantSignature":"[^"]*""""), """"anchorGrantSignature":null""")
        stageInvites(w, stageId, half).then().statusCode(400).body("code", equalTo("invalid_request"))
        stageInvites(w, stageId, full).then().statusCode(204)
    }

    @Test
    fun anEndorsementNotByTheStagesIdentityOrForAnotherRecordIsRefused() {
        val w = seedWorld()
        val stageId = w.rotation.openStageId()
        val byRetired = endorse(w, w.member, signer = w.rotation.current.identity)
        val otherRecord = endorse(w, w.member, record = "b3RoZXItcmVjb3Jk")
        for (bad in listOf(byRetired, otherRecord)) {
            stageEndorsements(w, stageId, """{"tenantId":"${w.tenantId}","userId":"${w.member}","endorsement":"$bad","recordDigest":"${recordDigest(w, w.member)}"}""")
                .then().statusCode(422).body("code", equalTo("join_endorsement_invalid"))
        }
        assertEquals(0L, tx { RotationStageEndorsementEntity.count("stageId", stageId) }, "nothing staged")
        stageEndorsements(w, stageId, endorsed(w)).then().statusCode(204)
    }

    @Test
    fun anEndorsementOfARecordTheCallerDidNotRootIsRefused() {
        val w = seedWorld()
        val stranger = seedUser()
        tx { profileRow(stranger, w.tenantId, rootedBy = w.coOwner) }
        val stageId = w.rotation.openStageId()
        stageEndorsements(w, stageId, """{"tenantId":"${w.tenantId}","userId":"$stranger","endorsement":null}""")
            .then().statusCode(409).body("code", equalTo("rotation_plan_changed"))
        stageEndorsements(w, stageId, """{"tenantId":"${w.tenantId}","userId":"${w.member}","endorsement":null}""").then().statusCode(204)
    }

    // --- fixtures ----------------------------------------------------------------------------------------------

    /** The rotator (an OWNER of the tenant with real keys) who signed [invite] and rooted [member]'s join; a co-owner. */
    private class World(val rotation: TestRotation, val tenantId: String, val coOwner: String, val invite: TestInviteKey, val member: String) {
        lateinit var grants: List<StagedGrant>
    }

    private fun seedWorld(): World {
        val keys = TestAccountKeys()
        val user = seedUser(identityPublicKey = keys.bundle, authPublicKey = keys.auth.publicKey, secretKeyProofPublicKey = keys.proof.publicKey)
        listOf("PASSWORD" to "b2xkLXBhc3N3b3JkLXdyYXA=", "RECOVERY" to "b2xkLXJlY292ZXJ5LXdyYXA=").forEach { (m, w) ->
            tx {
                MasterKeyWrapEntity().apply {
                    id = UUID.randomUUID().toString(); userId = user; method = m; wrappedKey = w; kdfParams = TestRotation.KDF
                }.persist()
            }
        }
        val co = seedUser()
        val tenant = seedTenant(owner = user)
        tx {
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); userId = co; tenantId = tenant; scopeId = tenant; role = MembershipEntity.OWNER
                wrappedGrant = TestFixtures.GRANT; createdAt = Instant.now()
            }.persist()
        }
        val member = seedUser()
        tx {
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); userId = member; tenantId = tenant; scopeId = tenant; role = MembershipEntity.MEMBER
                wrappedGrant = TestFixtures.GRANT; createdAt = Instant.now()
            }.persist()
            profileRow(member, tenant, rootedBy = user)
        }
        val rotation = TestRotation(user, keys) { TestFixtures.bearer(user) }
        return World(rotation, tenant, co, seedInvite(tenant, user, keys.bundle), member)
    }

    /** A rotator who rooted [World.member]'s join in [World.tenantId] and has since left it (no membership there), and
     *  owns another tenant (so its stage holds a self-grant). [World.invite] is the other tenant's. */
    private fun seedLeftWorld(): World {
        val w = seedWorld()
        tx { MembershipEntity.delete("userId = ?1 and scopeId = ?2", w.rotation.userId, w.tenantId) }
        tx { TenantInviteEntity.update("status = ?1 where id = ?2", TenantInviteEntity.SUPERSEDED, w.invite.inviteId) }
        val home = seedTenant(owner = w.rotation.userId)
        return World(w.rotation, w.tenantId, w.coOwner, seedInvite(home, w.rotation.userId, w.rotation.current.bundle), w.member).also {
            tx { TenantInviteEntity.update("status = ?1 where id = ?2", TenantInviteEntity.SUPERSEDED, it.invite.inviteId) }
        }
    }

    /** The tenant scope's lock, as an accept takes it before it writes, in the caller's transaction. */
    private fun tenantsLock(tenantId: String) {
        tenants.lockTenantScope(tenantId)
    }

    private fun profileRow(userId: String, tenantId: String, rootedBy: String) {
        TenantProfileEntity().apply {
            id = UUID.randomUUID().toString(); this.userId = userId; this.tenantId = tenantId; username = "m${UUID.randomUUID().toString().take(8)}"
            sealedJoinRecord = TestGrants.JOIN_RECORD; wrappedJoinRecordValueKey = TestGrants.VALUE_KEY_WRAP; joinSignerUserId = rootedBy
        }.persist()
    }

    private fun seedInvite(
        tenantId: String, signer: String, signerKey: String, secret: String = "rooted-${UUID.randomUUID()}",
        email: String = "i-${UUID.randomUUID()}@example.com",
    ): TestInviteKey {
        val key = TestInviteKey()
        val expiresAt = Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
        val minted = tx { tenants.mintMemberInvite(tenantId, email, Tokens.hash(secret), expiresAt, key.stored(signer, signerKey)) }
        assertEquals(MintMemberInvite.Minted(key.inviteId), minted)
        return key
    }

    /** A stage with a self-grant of every plan scope; the world keeps them for the publish. */
    private fun stageGrants(w: World): String {
        val stageId = w.rotation.openStageId()
        w.grants = w.rotation.plan().map { w.rotation.selfGrant(it) }
        w.rotation.stage(stageId, w.grants).then().statusCode(204)
        return stageId
    }

    /** [key]'s invite re-signed by [signer] (the new identity) over the statements the server rebuilds from what it stores. */
    private fun resigned(
        w: World, key: TestInviteKey, signer: TestEd25519 = w.rotation.next.identity,
        anchorSigner: TestEd25519 = signer, anchorEpoch: Int = 0,
    ): String {
        val inv = tx { TenantInviteEntity.findById(key.inviteId)!! }
        val inviteSig = signer.signB64(InviteAcceptProof.inviteKeyStatement(w.tenantId, inv.id, inv.invitePublicKey!!, inv.expiresAt.epochSecond))
        val anchorSig = anchorSigner.signB64(anchorStatement(inv, anchorEpoch))
        return """{"inviteId":"${key.inviteId}","anchorEpoch":$anchorEpoch,"inviteKeySignature":"$inviteSig","anchorGrantSignature":"$anchorSig"}"""
    }

    private fun declaredEntry(key: TestInviteKey): String =
        """{"inviteId":"${key.inviteId}","anchorEpoch":0,"inviteKeySignature":null,"anchorGrantSignature":null}"""

    private fun anchorStatement(inv: TenantInviteEntity, epoch: Int = inv.anchorEpoch ?: 0): ByteArray? =
        GrantSignature.statement(inv.tenantId, ScopeEntity.TENANT, epoch.toLong(), inv.id, inv.invitePublicKey!!, inv.anchorWrappedGrant ?: TestGrants.WRAPPED)

    /** The endorsement of [member]'s record as stored (or of [record]) by [signer] (the new identity) — over the record's
     *  digest, which stands in for the opened record's here: the server cannot open the record and never compares the
     *  two (ADR 0115 decision 8). */
    private fun endorse(w: World, member: String, signer: TestEd25519 = w.rotation.next.identity, record: String? = null): String =
        signer.signB64(JoinEndorsement.statement(w.tenantId, member, w.rotation.userId, digestOf(record ?: tx { profile(member, w.tenantId).sealedJoinRecord!! })))

    /** The `recordDigest` an endorsement of [member]'s stored record names. */
    private fun recordDigest(w: World, member: String): String = digestOf(tx { profile(member, w.tenantId).sealedJoinRecord!! })

    private fun digestOf(record: String): String =
        java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(java.util.Base64.getDecoder().decode(record)))

    private fun endorsed(w: World): String =
        """{"tenantId":"${w.tenantId}","userId":"${w.member}","endorsement":"${endorse(w, w.member)}","recordDigest":"${recordDigest(w, w.member)}"}"""

    private fun stageInvites(w: World, stageId: String, vararg entries: String): Response =
        given().header("Authorization", w.rotation.bearer()).contentType(ContentType.JSON)
            .body("""{"invites":[${entries.joinToString(",")}]}""").`when`().put("/api/account/rotation/stage/$stageId/invites")

    private fun stageEndorsements(w: World, stageId: String, vararg entries: String): Response =
        given().header("Authorization", w.rotation.bearer()).contentType(ContentType.JSON)
            .body("""{"endorsements":[${entries.joinToString(",")}]}""").`when`().put("/api/account/rotation/stage/$stageId/join-endorsements")

    private fun accept(userId: String, secret: String, key: TestInviteKey, signerDigest: String = TestGrants.storedSignerDigest(key.inviteId)) =
        given().header("Authorization", TestFixtures.bearer(userId)).contentType(ContentType.JSON)
            .body(
                """{"bootstrapSecret":"$secret","username":"n${UUID.randomUUID().toString().take(8)}",""" +
                    "${TestGrants.memberJoinJson(key, key.inviteId, userId, TestFixtures.IDENTITY_PUBLIC_KEY, signerIdentityDigest = signerDigest)}}",
            )
            .`when`().post("/api/tenants/accept")

    /** A refused publish wrote nothing: the identity as seeded, the invite still signed by the retired key, no endorsement. */
    private fun assertNothingWritten(w: World) {
        assertEquals(w.rotation.current.bundle, tx { VeyqUser.findById(w.rotation.userId)!!.identityPublicKey })
        val inv = tx { TenantInviteEntity.findById(w.invite.inviteId)!! }
        assertEquals(w.rotation.current.bundle to TestGrants.SIGNATURE, inv.signerIdentityPublicKey to inv.anchorGrantSignature)
        assertNull(tx { profile(w.member, w.tenantId).joinEndorsement })
    }

    private fun columns(i: TenantInviteEntity): List<Any?> = listOf(
        i.invitePublicKey, i.inviteKeySignature, i.signerUserId, i.signerIdentityPublicKey, i.wrappedGrant, i.grantedEpoch,
        i.grantSignature, i.granterUserId, i.granterIdentityPublicKey, i.anchorWrappedGrant, i.anchorEpoch, i.anchorGrantSignature,
    )

    private fun profile(userId: String, tenantId: String): TenantProfileEntity =
        TenantProfileEntity.find("userId = ?1 and tenantId = ?2", userId, tenantId).firstResult()!!

    private fun <T> tx(body: () -> T): T = QuarkusTransaction.requiringNew().call(body)
}
