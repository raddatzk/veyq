package me.raddatz.veyq.core.account.rotation

import me.raddatz.veyq.core.account.deviceregister.DeviceRegisterEntity
import me.raddatz.veyq.core.account.email.EmailChangeEntity
import me.raddatz.veyq.core.account.erasure.AccountPurge
import me.raddatz.veyq.core.identity.AccountErasureEntity
import me.raddatz.veyq.core.account.identitypins.IdentityPinEntryEntity
import me.raddatz.veyq.core.account.identitypins.IdentityPinsEntity
import me.raddatz.veyq.core.account.login.SessionFamilyEntity
import me.raddatz.veyq.core.account.login.SessionService
import me.raddatz.veyq.core.account.pairing.DevicePairingEntity
import me.raddatz.veyq.core.account.recovery.RecoveryStore
import me.raddatz.veyq.core.crypto.GrantSignature
import me.raddatz.veyq.core.crypto.SecretKeyProof
import me.raddatz.veyq.core.crypto.Tokens
import me.raddatz.veyq.core.identity.MasterKeyWrapEntity
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.identity.keychain.KeyChainStepEntity
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.membership.TestGrants
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.tasks.CapabilityTags
import me.raddatz.veyq.core.tasks.JobDeclineEntity
import me.raddatz.veyq.core.tasks.JobEntity
import me.raddatz.veyq.core.tenant.TenantEntity
import me.raddatz.veyq.core.testing.TestFixtures
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.hasItem
import org.hamcrest.CoreMatchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

/**
 * Slice tests for securing the account (uc.account.rotate-keys, ADR 0060, ADR 0111 decisions 6-10, cr-secure-the-account
 * Phase 2): the stage, the publish, the key-chain step, the commit gate after it and the states.
 *
 * What the publish is FOR is that no state after it can lose a space (#556): one transaction rewrites the account and
 * grants every scope the account holds a grant on — every tenant — at its current epoch to the identity it publishes,
 * signed by it. So the tests that carry weight are the ones that (a) find every such grant written and verifying under
 * the new key, (b) refuse every publish whose set is not the server's, with nothing written, and (c) refuse every step or
 * proof not made by the stored current keys. Every negative has a positive control differing in one input, anchored on
 * its status and `code`.
 *
 * Every signature is real (the server verifies the step, both proofs, the attestation and each staged grant); every
 * ciphertext is a stand-in (it opens none). Seeds use UUID-unique ids: the H2 database lives for the whole JVM.
 */
@QuarkusTest
class RotationSliceTest {

    @Inject lateinit var sessions: SessionService
    @Inject lateinit var purge: RotationStagePurge
    @Inject lateinit var recoveries: RecoveryStore
    @Inject lateinit var erasure: AccountPurge

    // --- the publish re-grants every scope (ways 2-4, server half) ---------------------------------------------------

    @Test
    fun everyScopeIsRegrantedToTheNewIdentityInThePublish() {
        val (rotation, world) = seedWorld()
        val before = grantOf(world.coMember, world.tenantId)

        rotation.run().then().statusCode(200)
            .body("regrantedScopeIds", hasItem(world.personal))
            .body("regrantedScopeIds", hasItem(world.tenantId))
            .body("regrantedScopeIds", hasItem(world.group))

        for ((scope, kind) in listOf(world.personal to "PERSONAL", world.tenantId to "TENANT", world.group to "GROUP")) {
            assertSelfGranted(rotation, scope, kind, epochOf(scope))
        }
        assertEquals(before, grantOf(world.coMember, world.tenantId), "control: another member's grant on the tenant scope is untouched")
        assertEquals(rotation.next.bundle, field(rotation.userId) { it.identityPublicKey })
    }

    @Test
    fun aScopeInAnotherTenantIsRegrantedToo() {
        val (rotation, world) = seedWorld()
        val other = seedTenant()
        member(rotation.userId, other, other, MembershipEntity.MEMBER, epoch = 0)
        val otherPersonal = personalScope(rotation.userId, other)

        // The plan is Host-independent: read on tenant A's Host it lists tenant B's scopes with the caller's grant rows.
        given().header("Authorization", rotation.bearer()).header("X-Forwarded-Host", "t-${world.tenantId}.example.com")
            .`when`().get("/api/account/rotation/plan").then().statusCode(200)
            .body("find { it.scopeId == '$other' }.tenantId", equalTo(other))
            .body("find { it.scopeId == '$other' }.kind", equalTo("TENANT"))
            .body("find { it.scopeId == '$otherPersonal' }.wrappedGrant", equalTo(TestFixtures.GRANT))
            .body("find { it.scopeId == '$otherPersonal' }.currentEpoch", equalTo(0))

        rotation.run().then().statusCode(200).body("regrantedScopeIds", hasItem(otherPersonal))
        assertSelfGranted(rotation, otherPersonal, "PERSONAL", 0)
        assertSelfGranted(rotation, other, "TENANT", 0)
    }

    @Test
    fun rotationReplacesEveryKeyRewritesBothColdWrapsAndDeletesTheDeviceOnes() {
        val (rotation, _) = seedWorld()
        seedWrap(rotation.userId, "DEVICE", "ZGV2aWNlLXdyYXA=")
        seedWrap(rotation.userId, "DEVICE_KEY", "ZGV2aWNlLWtleS13cmFw")
        seedWrap(rotation.userId, "PASSKEY", "cGFzc2tleS13cmFw")

        rotation.run().then().statusCode(200)

        val u = rotation.userId
        assertEquals(rotation.next.bundle, field(u) { it.identityPublicKey }, "the directory value is republished")
        assertEquals(TestRotation.NEW_PRIV, field(u) { it.wrappedIdentityPrivateKey })
        assertEquals(rotation.next.proof.publicKey, field(u) { it.secretKeyProofPublicKey }, "the Secret Key rotates, and its proof key with it")
        assertEquals(rotation.next.auth.publicKey, field(u) { it.authPublicKey }, "the auth key the attestation vouched for")
        assertEquals(TestRotation.NEW_PW_WRAP, wrapOf(u, "PASSWORD"))
        assertEquals(TestRotation.NEW_REC_WRAP, wrapOf(u, "RECOVERY"))
        assertNull(wrapOf(u, "DEVICE"), "a trusted-device wrap cannot be rewritten, so it goes")
        assertNull(wrapOf(u, "DEVICE_KEY"))
        assertNull(wrapOf(u, "PASSKEY"))
    }

    // --- the key-chain step (decision 2) -------------------------------------------------------------------------

    @Test
    fun theChainGainsTheStepAndTheHeadMoves() {
        val (rotation, _) = seedWorld()
        val (headBefore, index) = rotation.head()
        assertEquals(0, index, "a seeded account reads as its genesis")
        val step = rotation.stepStatement()

        rotation.run().then().statusCode(200)

        given().header("Authorization", rotation.bearer()).`when`().get("/api/people/${rotation.userId}/key-chain").then().statusCode(200)
            .body("steps.size()", equalTo(2))
            .body("steps[0].identityPublicKey", equalTo(rotation.current.bundle))
            .body("steps[0].secretKeyProofPublicKey", equalTo(rotation.current.proof.publicKey))
            .body("steps[1].index", equalTo(1))
            .body("steps[1].previousHead", equalTo(b64(headBefore)))
            .body("steps[1].identityPublicKey", equalTo(rotation.next.bundle))
            .body("steps[1].secretKeyProofPublicKey", equalTo(rotation.next.proof.publicKey))
            .body("steps[1].identitySignature", equalTo(rotation.current.identity.signB64(step)))
            .body("steps[1].proofSignature", equalTo(rotation.current.proof.signB64(step)))
            .body("head", equalTo(b64(me.raddatz.veyq.core.crypto.KeyChainStatements.head(step))))
    }

    @Test
    fun aStepNotSignedByTheStoredIdentityIsRefused() {
        val (rotation, world) = seedWorld()
        val (stageId, grants) = stageAll(rotation)
        val good = rotation.publish(stageId, grants)
        val thief = TestEd25519.fresh()

        rotation.send(good.copy(stepIdentitySignature = thief.signB64(rotation.stepStatement())))
            .then().statusCode(422).body("code", equalTo("key_chain_step_invalid"))
        assertNothingWritten(rotation, world)

        rotation.send(good).then().statusCode(200) // control: the stored identity's own signature
    }

    @Test
    fun aStepNotSignedByTheStoredProofKeyIsRefused() {
        // The device thief: holds the identity (so the identity half verifies) but never the Secret Key.
        val (rotation, world) = seedWorld()
        val (stageId, grants) = stageAll(rotation)
        val good = rotation.publish(stageId, grants)

        rotation.send(good.copy(stepProofSignature = TestEd25519.fresh().signB64(rotation.stepStatement())))
            .then().statusCode(403).body("code", equalTo("secret_key_proof_failed"))
        assertNothingWritten(rotation, world)

        rotation.send(good).then().statusCode(200)
    }

    @Test
    fun aStepWithAStalePredecessorIsRefused() {
        val (rotation, world) = seedWorld()
        val (stageId, grants) = stageAll(rotation)
        val (head, index) = rotation.head()
        val stale = rotation.stepStatement(ByteArray(32) { 9 } to index)
        // Every signature over the stale step is genuine; the server rebuilds the step from the head it stores.
        val stalePublish = rotation.publish(stageId, grants, step = stale)

        rotation.send(stalePublish).then().statusCode(422).body("code", equalTo("key_chain_step_invalid"))
        assertNothingWritten(rotation, world)

        rotation.send(rotation.publish(stageId, grants, step = rotation.stepStatement(head to index))).then().statusCode(200)
    }

    // --- the stage (decision 3) ---------------------------------------------------------------------------------

    @Test
    fun aStagedGrantSignedByTheOldIdentityIsRefusedAtUpload() {
        val (rotation, world) = seedWorld()
        val stageId = rotation.openStageId()
        val plan = rotation.plan()
        val good = plan.map { rotation.selfGrant(it) }
        val oldSigned = rotation.selfGrant(plan.first(), signer = rotation.current.identity)

        rotation.stage(stageId, good.drop(1) + oldSigned).then().statusCode(422).body("code", equalTo("self_grant_invalid"))
        assertEquals(0L, stagedRows(stageId), "nothing of the refused chunk is stored")

        // A grant naming another identity than the stage's is the same refusal.
        val otherRecipient = rotation.selfGrant(plan.first(), recipient = rotation.current.bundle)
        rotation.stage(stageId, listOf(otherRecipient)).then().statusCode(422).body("code", equalTo("self_grant_invalid"))
        rotation.stage(stageId, listOf(good.first().copy(recipientIdentityDigest = TestGrants.digest(rotation.current.bundle))))
            .then().statusCode(422).body("code", equalTo("self_grant_invalid"))
        assertEquals(0L, stagedRows(stageId))

        rotation.stage(stageId, good).then().statusCode(204) // control: the same chunk, signed by the stage's identity
        assertEquals(good.size.toLong(), stagedRows(stageId))
        assertEquals(3, good.size, "personal, tenant and group: ${world.personal}")
    }

    @Test
    fun aStagedScopeTheCallerHoldsNoGrantOnIsRefused() {
        val (rotation, world) = seedWorld()
        val stageId = rotation.openStageId()
        val foreign = TestRotation.PlanScope(personalScope(world.coMember, world.tenantId), "PERSONAL", 0)
        val unknown = TestRotation.PlanScope(UUID.randomUUID().toString(), "PERSONAL", 0)
        val good = rotation.selfGrant(rotation.plan().first())

        rotation.stage(stageId, listOf(good, rotation.selfGrant(foreign))).then().statusCode(409).body("code", equalTo("rotation_plan_changed"))
        rotation.stage(stageId, listOf(good, rotation.selfGrant(unknown))).then().statusCode(409).body("code", equalTo("rotation_plan_changed"))
        assertEquals(0L, stagedRows(stageId))
        rotation.stage(stageId, listOf(good)).then().statusCode(204)
    }

    @Test
    fun anUnknownExpiredOrForeignStageIs404() {
        val (rotation, world) = seedWorld()
        val other = TestRotation(world.coMember, world.coMemberKeys) { TestFixtures.bearer(world.coMember) }
        val foreignStage = other.openStageId()
        val grant = rotation.selfGrant(rotation.plan().first())

        rotation.stage(UUID.randomUUID().toString(), listOf(grant)).then().statusCode(404).body("code", equalTo("rotation_stage_not_found"))
        rotation.stage(foreignStage, listOf(grant)).then().statusCode(404).body("code", equalTo("rotation_stage_not_found"))

        val first = rotation.openStageId()
        val second = rotation.openStageId()
        rotation.stage(first, listOf(grant)).then().statusCode(404).body("code", equalTo("rotation_stage_not_found"))
        rotation.stage(second, listOf(grant)).then().statusCode(204) // control: the account's open stage
        assertEquals(1L, QuarkusTransaction.requiringNew().call { RotationStageEntity.count("userId", rotation.userId) }, "a second open replaced the first")

        expire(second)
        rotation.stage(second, listOf(grant)).then().statusCode(404).body("code", equalTo("rotation_stage_not_found"))
    }

    @Test
    fun aStageAppliesNothingBeforeThePublish() {
        val (rotation, world) = seedWorld()
        val before = listOf(world.personal, world.tenantId, world.group).associateWith { grantOf(rotation.userId, it) }
        val (stageId, _) = stageAll(rotation)

        assertEquals(3L, stagedRows(stageId))
        listOf(world.personal, world.tenantId, world.group).forEach {
            assertEquals(before[it], grantOf(rotation.userId, it), "a staged grant is not a grant")
            assertEquals(0, epochOf(it))
        }
        assertEquals(rotation.current.bundle, field(rotation.userId) { it.identityPublicKey })
    }

    @Test
    fun anExpiredStageIsPurged() {
        val (rotation, _) = seedWorld()
        val (stageId, _) = stageAll(rotation)

        purge.sweep(Instant.now())
        assertEquals(3L, stagedRows(stageId), "control: a live stage survives the sweep")

        purge.sweep(Instant.now().plus(2, ChronoUnit.HOURS))
        assertEquals(0L, QuarkusTransaction.requiringNew().call { RotationStageEntity.count("id", stageId) })
        assertEquals(0L, stagedRows(stageId), "its grants go with it")
    }

    @Test
    fun aChunkOverItsBoundIs400() {
        val (rotation, _) = seedWorld()
        val stageId = rotation.openStageId()
        val grant = rotation.selfGrant(rotation.plan().first())

        rotation.stage(stageId, List(257) { grant }).then().statusCode(400).body("code", equalTo("invalid_request"))
        rotation.stage(stageId, emptyList()).then().statusCode(400)
        rotation.stage(stageId, List(256) { grant }).then().statusCode(204) // control: 256 is the bound
    }

    // --- publish refusals: the server's set, the stage, the proofs -------------------------------------------------

    @Test
    fun aPublishMissingAScopeIsRefused() {
        // The delta: a membership arrives between stage and publish (any co-member can add the account to a group).
        val (rotation, world) = seedWorld()
        val (stageId, grants) = stageAll(rotation)
        val late = group(world.tenantId, world.coMember)
        member(rotation.userId, world.tenantId, late, MembershipEntity.MEMBER, epoch = 0)

        rotation.send(rotation.publish(stageId, grants)).then().statusCode(409).body("code", equalTo("rotation_plan_changed"))
        assertNothingWritten(rotation, world)

        // Control: staging only that scope into the same stage, then the same publish with the grown set.
        val delta = rotation.selfGrant(TestRotation.PlanScope(late, "GROUP", 0))
        rotation.stage(stageId, listOf(delta)).then().statusCode(204)
        rotation.send(rotation.publish(stageId, grants + delta)).then().statusCode(200).body("regrantedScopeIds", hasItem(late))
        assertSelfGranted(rotation, late, "GROUP", 0)
    }

    @Test
    fun aSelfGrantAtAStaleEpochIsRefused() {
        val (rotation, world) = seedWorld()
        val (stageId, grants) = stageAll(rotation)
        setEpoch(world.group, 1) // the group rotated after staging

        rotation.send(rotation.publish(stageId, grants)).then().statusCode(409).body("code", equalTo("rotation_plan_changed"))
        assertNothingWritten(rotation, world)

        val moved = rotation.selfGrant(TestRotation.PlanScope(world.group, "GROUP", 1))
        rotation.stage(stageId, listOf(moved)).then().statusCode(204)
        rotation.send(rotation.publish(stageId, grants.filter { it.scopeId != world.group } + moved)).then().statusCode(200)
        assertSelfGranted(rotation, world.group, "GROUP", 1)
    }

    @Test
    fun aStageDigestOtherThanTheStagedRowsIsRefused() {
        val (rotation, world) = seedWorld()
        val (stageId, grants) = stageAll(rotation)
        // Signed over a set without the group: the proof covers it, but the staged rows are another set.
        val partial = rotation.publish(stageId, grants.filter { it.scopeId != world.group })

        rotation.send(partial).then().statusCode(409).body("code", equalTo("rotation_plan_changed"))
        assertNothingWritten(rotation, world)
        rotation.send(rotation.publish(stageId, grants)).then().statusCode(200)
    }

    @Test
    fun aStageOfAnotherIdentityIsRefused() {
        val (rotation, world) = seedWorld()
        val decoy = TestRotation(rotation.userId, rotation.current, TestAccountKeys(), rotation.bearer)
        val stageId = decoy.openStageId()
        val grants = decoy.plan().map { decoy.selfGrant(it) }
        decoy.stage(stageId, grants).then().statusCode(204)

        // The publish installs rotation.next but names a stage that declares decoy.next.
        rotation.send(rotation.publish(stageId, grants)).then().statusCode(409).body("code", equalTo("rotation_plan_changed"))
        assertNothingWritten(rotation, world)
        decoy.send(decoy.publish(stageId, grants)).then().statusCode(200) // control: the identity the stage declares
    }

    @Test
    fun anExpiredOrMissingStageIsRefused() {
        val (rotation, world) = seedWorld()
        val (stageId, grants) = stageAll(rotation)
        val publish = rotation.publish(stageId, grants)
        expire(stageId)

        rotation.send(publish).then().statusCode(409).body("code", equalTo("rotation_plan_changed"))
        rotation.send(rotation.publish(UUID.randomUUID().toString(), grants)).then().statusCode(409).body("code", equalTo("rotation_plan_changed"))
        assertNothingWritten(rotation, world)
    }

    @Test
    fun aSessionWithoutTheSecretKeyCannotRotate() {
        val (rotation, world) = seedWorld()
        val (stageId, grants) = stageAll(rotation)
        val good = rotation.publish(stageId, grants)
        val statement = rotation.statement(rotation.stepStatement(), stageId, decode(good.stagedGrantsDigest))

        rotation.send(good.copy(secretKeyProofSignature = TestEd25519.fresh().signB64(statement)))
            .then().statusCode(403).body("code", equalTo("secret_key_proof_failed"))
        assertNothingWritten(rotation, world)
        rotation.send(good).then().statusCode(200)
    }

    @Test
    fun aPossessionSignatureByAnotherKeyIsRefused() {
        val (rotation, world) = seedWorld()
        val (stageId, grants) = stageAll(rotation)
        val good = rotation.publish(stageId, grants)
        val statement = rotation.statement(rotation.stepStatement(), stageId, decode(good.stagedGrantsDigest))

        rotation.send(good.copy(secretKeyProofPossession = TestEd25519.fresh().signB64(statement)))
            .then().statusCode(403).body("code", equalTo("secret_key_proof_failed"))
        assertNothingWritten(rotation, world)
        rotation.send(good).then().statusCode(200)
    }

    @Test
    fun aWrongAttestationIsRefused() {
        val (rotation, world) = seedWorld()
        val (stageId, grants) = stageAll(rotation)
        val good = rotation.publish(stageId, grants)

        rotation.send(good.copy(attestationSignature = b64(TestEd25519.fresh().sign(rotation.next.auth.raw))))
            .then().statusCode(403).body("code", equalTo("attestation_failed"))
        assertNothingWritten(rotation, world)
        rotation.send(good).then().statusCode(200)
    }

    @Test
    fun aCapturedRotationSignatureCannotBeReplayed() {
        val (rotation, _) = seedWorld()
        val (stageId, grants) = stageAll(rotation)
        val publish = rotation.publish(stageId, grants)
        rotation.send(publish).then().statusCode(200)

        rotation.send(publish).then().statusCode(409).body("code", equalTo("rotation_plan_changed"))
        assertEquals(2, chainSize(rotation.userId), "no second step")
    }

    @Test
    fun anAccountWithNoProofKeyCannotRotate() {
        val (rotation, world) = seedWorld()
        val (stageId, grants) = stageAll(rotation)
        QuarkusTransaction.requiringNew().run { VeyqUser.findById(rotation.userId)!!.secretKeyProofPublicKey = null }

        rotation.send(rotation.publish(stageId, grants)).then().statusCode(403).body("code", equalTo("secret_key_proof_failed"))
        assertEquals(rotation.current.bundle, field(rotation.userId) { it.identityPublicKey })
        assertEquals(TestFixtures.GRANT, grantOf(rotation.userId, world.personal))
    }

    @Test
    fun anUnreadableScopeKeepsItsGrantAndGetsNoRow() {
        val (rotation, world) = seedWorld()
        val before = grantOf(rotation.userId, world.group)

        rotation.run(unreadable = setOf(world.group)).then().statusCode(200)
            .body("regrantedScopeIds", not(hasItem(world.group)))
            .body("regrantedScopeIds", hasItem(world.personal))

        assertEquals(before, grantOf(rotation.userId, world.group), "declared unreadable: its grant is untouched")
        assertNull(regrantEpoch(rotation.userId, world.group), "and it gets no pending row")
        assertEquals(0, regrantEpoch(rotation.userId, world.personal), "control: a staged scope gets one")
    }

    @Test
    fun aRotatedAccountCanBeSecuredAgainFromTheNewHead() {
        val (rotation, _) = seedWorld()
        rotation.run().then().statusCode(200)
        val again = TestRotation(rotation.userId, rotation.next, TestAccountKeys(), rotation.bearer)
        val (stageId, grants) = stageAll(again)

        // The first publish wrote both documents at revision 1, so the second re-seals against that.
        again.send(again.publish(stageId, grants).copy(pinsRevision = 1, registerRevision = 1)).then().statusCode(200)
        assertEquals(3, chainSize(rotation.userId))
        assertEquals(again.next.proof.publicKey, field(rotation.userId) { it.secretKeyProofPublicKey })
    }

    // --- what the publish also does ---------------------------------------------------------------------------

    @Test
    fun theNextCredentialFlowVerifiesUnderTheNewProofKey() {
        val (rotation, _) = seedWorld()
        rotation.run().then().statusCode(200)
        val changedAuth = TestEd25519.fresh()
        val wrap = SecretKeyProof.Wrap("Y2hhbmdlZC13cmFw", TestRotation.KDF)
        val stmt = SecretKeyProof.passwordChangeStatement(rotation.next.auth.publicKey, changedAuth.publicKey, wrap)

        changePassword(rotation, changedAuth, rotation.current.proof.signB64(stmt))
            .then().statusCode(403).body("code", equalTo("secret_key_proof_failed"))
        changePassword(rotation, changedAuth, rotation.next.proof.signB64(stmt)).then().statusCode(204)

        // Recovery likewise: a reset proved by the old Secret Key is refused, by the new one applied.
        val email = field(rotation.userId) { it.email }!!
        val resetAuth = TestEd25519.fresh().publicKey
        val resetWrap = SecretKeyProof.Wrap("cmVzZXQtd3JhcA==", TestRotation.KDF)
        val refused = mintRecovery(rotation.userId, email)
        recover(refused, resetAuth, rotation.current.proof.signB64(SecretKeyProof.recoveryStatement(refused, resetAuth, resetWrap)))
            .then().statusCode(403).body("code", equalTo("secret_key_proof_failed"))
        val applied = mintRecovery(rotation.userId, email)
        recover(applied, resetAuth, rotation.next.proof.signB64(SecretKeyProof.recoveryStatement(applied, resetAuth, resetWrap))).then().statusCode(204)
        assertEquals(resetAuth, field(rotation.userId) { it.authPublicKey })
    }

    private fun mintRecovery(userId: String, email: String): String {
        val token = Tokens.random()
        QuarkusTransaction.requiringNew().run { recoveries.mint(userId, email, Tokens.hash(token), Instant.now().plusSeconds(3600)) }
        return token
    }

    @Test
    fun anOpenEmailChangeDoesNotSurviveThePublish() {
        val (rotation, world) = seedWorld()
        seedEmailChange(rotation.userId)
        seedEmailChange(world.coMember)

        rotation.run().then().statusCode(200)

        assertEquals(0L, emailChanges(rotation.userId), "a change proved under the old key is void")
        assertEquals(1L, emailChanges(world.coMember), "control: another account's change stays")
        seedEmailChange(rotation.userId)
        assertEquals(1L, emailChanges(rotation.userId), "control: one requested after the publish is untouched")
    }

    @Test
    fun thePublishClearsDeclinesTargetingTheRotator() {
        val (rotation, world) = seedWorld()
        val targetingRotator = seedDeliveryJob(world.group, membershipOf(rotation.userId, world.group))
        val targetingOther = seedDeliveryJob(world.group, membershipOf(world.coMember, world.group))
        decline(targetingRotator, world.coMember)
        decline(targetingOther, rotation.userId)

        rotation.run().then().statusCode(200)

        assertEquals(0L, declines(targetingRotator), "a worker that could not anchor the old key tries the new one")
        assertEquals(1L, declines(targetingOther), "control: a decline about someone else stays")
    }

    @Test
    fun aRolledBackRotationLeavesNothing() {
        val (rotation, world) = seedWorld()
        seedEmailChange(rotation.userId)
        val job = seedDeliveryJob(world.group, membershipOf(rotation.userId, world.group))
        decline(job, world.coMember)
        seedPins(rotation.userId, revision = 3)
        val (stageId, grants) = stageAll(rotation)

        rotation.send(rotation.publish(stageId, grants).copy(pinsRevision = 2)).then().statusCode(409).body("code", equalTo("stale_revision"))

        assertNothingWritten(rotation, world)
        assertEquals(3L, stagedRows(stageId), "the stage is not spent")
        assertEquals(1L, emailChanges(rotation.userId), "no void")
        assertEquals(1L, declines(job), "no decline cleared")

        rotation.send(rotation.publish(stageId, grants).copy(pinsRevision = 3)).then().statusCode(200) // control
    }

    @Test
    fun aStalePinStoreRevisionRollsTheWholeRotationBack() {
        // ADR 0060 § Consequences: both Master-Key-sealed documents are re-sealed INSIDE the publish's transaction, so a
        // rotation that cannot write them does not happen — the account half, the step and the stage included.
        val (rotation, world) = seedWorld()
        seedPins(rotation.userId, revision = 3)
        val (stageId, grants) = stageAll(rotation)

        rotation.send(rotation.publish(stageId, grants).copy(pinsRevision = 2)).then().statusCode(409).body("code", equalTo("stale_revision"))
        assertNothingWritten(rotation, world)
        assertEquals(mapOf("01".repeat(32) to "b2xkLWVudHJ5"), pinEntries(rotation.userId), "the old entry stays under the old key")

        rotation.send(rotation.publish(stageId, grants).copy(pinsRevision = 3)).then().statusCode(200) // control
        assertEquals(mapOf(TestRotation.NEW_PIN_ID to TestRotation.NEW_ENTRY), pinEntries(rotation.userId), "every entry replaced")
    }

    @Test
    fun aStaleRegisterRevisionRollsTheWholeRotationBack() {
        val (rotation, world) = seedWorld()
        QuarkusTransaction.requiringNew().run {
            DeviceRegisterEntity().apply { accountId = rotation.userId; sealedBlob = "b2xkLXJlZ2lzdGVy"; revision = 2 }.persist()
        }
        val (stageId, grants) = stageAll(rotation)

        rotation.send(rotation.publish(stageId, grants).copy(registerRevision = 1)).then().statusCode(409).body("code", equalTo("stale_revision"))
        assertNothingWritten(rotation, world)
        assertEquals("b2xkLXJlZ2lzdGVy" to 2L, registerRow(rotation.userId))

        rotation.send(rotation.publish(stageId, grants).copy(registerRevision = 2)).then().statusCode(200) // control
        assertEquals(TestRotation.NEW_REGISTER to 3L, registerRow(rotation.userId))
    }

    @Test
    fun anAccountWithNoDocumentYetGetsBothBornInTheRotation() {
        val (rotation, _) = seedWorld()
        rotation.run().then().statusCode(200)
        assertEquals(TestRotation.NEW_REGISTER to 1L, registerRow(rotation.userId))
        assertEquals(TestRotation.NEW_PINS to 1L, QuarkusTransaction.requiringNew().call<Pair<String?, Long>?> {
            IdentityPinsEntity.findById(rotation.userId)?.let { it.sealedBlob to it.revision }
        })
    }

    @Test
    fun aGrantTheRotatorSignedIsStillServedWithTheKeyThatSignedIt() {
        // Inbox #233, ADR 0028 Decision 6. The rotator wrote the co-member's grant (a commit of the group,
        // through its pending row after a first securing) and signed it with that identity. Securing the account again
        // must leave that grant served with the key that SIGNED it: the co-member's client verifies before it reads its
        // pin. Control: the rotator's own self-grant of the publish is served with its new key.
        val (rotation, world) = seedWorld()
        rotation.run().then().statusCode(200)
        commit(rotation.userId, world.group, 1, everyone(world.group, rotation)).then().statusCode(201)
        val signedBy = rotation.next.bundle
        val again = TestRotation(rotation.userId, rotation.next, TestAccountKeys(), rotation.bearer)
        val (stageId, grants) = stageAll(again)
        again.send(again.publish(stageId, grants).copy(pinsRevision = 1, registerRevision = 1)).then().statusCode(200)

        assertEquals(rotation.userId to signedBy, granterOf(world.coMember, world.group), "the snapshot of the grant's own moment")
        assertEquals(rotation.userId to again.next.bundle, granterOf(rotation.userId, world.group), "control: a grant written now")
        given().header("Authorization", TestFixtures.bearer(world.coMember)).header("X-Forwarded-Host", "t-${world.tenantId}.example.com")
            .`when`().get("/api/scopes").then().statusCode(200)
            .body("find { it.id == '${world.group}' }.granterIdentityPublicKey", equalTo(signedBy))
    }

    @Test
    fun rotationSupersedesLivePairingChannelsAndRevokesEveryOtherSession() {
        val (rotation, _) = seedWorld()
        val pending = seedPairing(rotation.userId, DevicePairingEntity.PENDING)
        val collected = seedPairing(rotation.userId, DevicePairingEntity.COLLECTED)
        val otherFamily = sessions.issue(rotation.userId).familyId
        val own = sessions.issue(rotation.userId)

        rotation.run(accessToken = own.accessToken).then().statusCode(200)

        assertEquals(DevicePairingEntity.SUPERSEDED, pairingStatus(pending))
        assertEquals(DevicePairingEntity.COLLECTED, pairingStatus(collected))
        assertEquals(SessionFamilyEntity.REVOKED, familyStatus(otherFamily))
        assertEquals(SessionFamilyEntity.ACTIVE, familyStatus(own.familyId))
    }

    @Test
    fun withoutARecoveryWrapNothingIsWritten() {
        val (rotation, world) = seedWorld()
        QuarkusTransaction.requiringNew().run { MasterKeyWrapEntity.delete("userId = ?1 and method = ?2", rotation.userId, "RECOVERY") }

        rotation.run().then().statusCode(409).body("code", equalTo("rotation_unavailable"))
        assertNothingWritten(rotation, world)
    }

    @Test
    fun aRotationThatKeepsAPinEntryIsRefused() {
        val (rotation, _) = seedWorld()
        val (stageId, grants) = stageAll(rotation)
        rotation.send(rotation.publish(stageId, grants).copy(pinsReplaceAll = false)).then().statusCode(400).body("code", equalTo("invalid_request"))
    }

    @Test
    fun rotationWithoutAuthIs401() {
        given().contentType(ContentType.JSON).body("{}").`when`().post("/api/account/rotation").then().statusCode(401)
        given().`when`().get("/api/account/rotation/plan").then().statusCode(401)
        given().contentType(ContentType.JSON).body("""{"identityPublicKey":"AQ=="}""").`when`().post("/api/account/rotation/stage").then().statusCode(401)
    }

    @Test
    fun aRequestMissingARequiredFieldIsRejectedAtTheBoundary() {
        val (rotation, _) = seedWorld()
        val (stageId, grants) = stageAll(rotation)
        val json = rotation.publish(stageId, grants).json()
        for (field in listOf("\"secretKeyProofPossession\"", "\"authPublicKey\"", "\"stagedGrantsDigest\"")) {
            val without = json.replace(Regex("$field:\"[^\"]*\",?"), "")
            given().header("Authorization", rotation.bearer()).contentType(ContentType.JSON).body(without)
                .`when`().post("/api/account/rotation").then().statusCode(400).body("code", equalTo("invalid_request"))
        }
    }

    // --- the commit gate after the publish (decision 4) --------------------------------------------------------

    @Test
    fun aMemberWithAWaitingRowCommitsTheNextEpoch() {
        val (rotation, world) = seedWorld()
        rotation.run().then().statusCode(200)

        // The rotator is a plain MEMBER of the group; its waiting row lets it commit epoch 1, granting everyone.
        commit(rotation.userId, world.group, 1, everyone(world.group, rotation)).then().statusCode(201)
        assertEquals(1, epochOf(world.group))
    }

    @Test
    fun theGateRefusesWithoutTheRowOrWithARemoval() {
        val (rotation, world) = seedWorld()
        // Without a row (no publish yet): a plain member is no committer.
        commit(rotation.userId, world.group, 1, everyone(world.group, rotation))
            .then().statusCode(403).body("code", equalTo("not_scope_owner"))

        rotation.run().then().statusCode(200)
        val third = seedUser()
        member(third, world.tenantId, world.group, MembershipEntity.MEMBER, epoch = 0)
        // With the row, a removal stays an owner's act.
        commit(rotation.userId, world.group, 1, everyone(world.group, rotation).filterKeys { it != third }, removes = third)
            .then().statusCode(403).body("code", equalTo("not_scope_owner"))
        assertEquals(0, epochOf(world.group))
        commit(rotation.userId, world.group, 1, everyone(world.group, rotation)).then().statusCode(201) // control
    }

    @Test
    fun afterSomeoneElsesCommitTheRowAnswersStaleEpoch() {
        val (rotation, world) = seedWorld()
        rotation.run().then().statusCode(200)
        // The co-member owns the group and commits first, granting the rotator's NEW key.
        commit(world.coMember, world.group, 1, everyone(world.group, rotation)).then().statusCode(201)

        commit(rotation.userId, world.group, 2, everyone(world.group, rotation))
            .then().statusCode(409).body("code", equalTo("stale_epoch"))
        assertEquals("secured", stateOf(rotation, world.group), "someone else's commit finished the row")
    }

    // --- states (decision 8) -------------------------------------------------------------------------------------

    @Test
    fun theStatesFollowTheRows() {
        val (rotation, world) = seedWorld()
        val node = TestFixtures.seedNode(world.personal, "FOLDER", wrappedContentKey = TestFixtures.CONTENT_KEY)
        rotation.run().then().statusCode(200)
        assertEquals("waiting", stateOf(rotation, world.personal))
        assertEquals("waiting", stateOf(rotation, world.tenantId))

        commit(rotation.userId, world.personal, 1, mapOf(rotation.userId to TestGrants.digest(rotation.next.bundle))).then().statusCode(201)
        assertEquals("rekeying", stateOf(rotation, world.personal), "a node still carries epoch 0")
        QuarkusTransaction.requiringNew().run { NodeEntity.update("epoch = 1 where id = ?1", node) }
        assertEquals("secured", stateOf(rotation, world.personal))

        QuarkusTransaction.requiringNew().run { TenantEntity.update("status = ?1 where id = ?2", TenantEntity.SUSPENDED, world.tenantId) }
        given().header("Authorization", rotation.bearer()).`when`().get("/api/account/rotation/scopes").then().statusCode(200)
            .body("find { it.scopeId == '${world.tenantId}' }.state", equalTo("blocked"))
            .body("find { it.scopeId == '${world.tenantId}' }.reason", equalTo("tenant_suspended"))
    }

    // --- purge and erasure ---------------------------------------------------------------------------------------

    @Test
    fun anErasedAccountLeavesNoStepRowOrStage() {
        val (rotation, world) = seedWorld()
        rotation.run().then().statusCode(200)
        rotation.openStageId()
        assertEquals(2, chainSize(rotation.userId))

        QuarkusTransaction.requiringNew().run {
            AccountErasureEntity().apply {
                accountId = rotation.userId; requestedAt = Instant.now(); requestedBy = AccountErasureEntity.SELF; dueAt = Instant.now()
            }.persist()
        }
        erasure.purge(rotation.userId)
        assertNull(field(rotation.userId) { it.id }, "the account is gone")

        assertEquals(0, chainSize(rotation.userId))
        assertEquals(0L, QuarkusTransaction.requiringNew().call { RotationRegrantEntity.count("userId", rotation.userId) })
        assertEquals(0L, QuarkusTransaction.requiringNew().call { RotationStageEntity.count("userId", rotation.userId) })
    }

    // --- fixtures ------------------------------------------------------------------------------------------------

    /** A rotator (plain MEMBER of a tenant and of a group the co-member owns, owner of its personal space) and the
     *  co-member, each with real keys. */
    private data class World(
        val tenantId: String, val personal: String, val group: String, val coMember: String, val coMemberKeys: TestAccountKeys,
    )

    private fun seedWorld(): Pair<TestRotation, World> {
        val keys = TestAccountKeys()
        val user = seedAccount(keys)
        val coKeys = TestAccountKeys()
        val co = seedAccount(coKeys)
        val tenant = seedTenant(owner = co)
        member(user, tenant, tenant, MembershipEntity.MEMBER, epoch = 0)
        val personal = personalScope(user, tenant)
        val group = group(tenant, co)
        member(user, tenant, group, MembershipEntity.MEMBER, epoch = 0)
        return TestRotation(user, keys) { TestFixtures.bearer(user) } to World(tenant, personal, group, co, coKeys)
    }

    private fun seedAccount(keys: TestAccountKeys): String {
        val id = seedUser(identityPublicKey = keys.bundle, authPublicKey = keys.auth.publicKey, secretKeyProofPublicKey = keys.proof.publicKey)
        seedWrap(id, "PASSWORD", "b2xkLXBhc3N3b3JkLXdyYXA=")
        seedWrap(id, "RECOVERY", "b2xkLXJlY292ZXJ5LXdyYXA=")
        return id
    }

    private fun personalScope(user: String, tenant: String): String {
        val id = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            ScopeEntity().apply { this.id = id; tenantId = tenant; kind = ScopeEntity.PERSONAL; personalOwner = user }.persist()
        }
        member(user, tenant, id, MembershipEntity.OWNER, epoch = 0)
        return id
    }

    private fun group(tenant: String, owner: String): String {
        val id = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            ScopeEntity().apply { this.id = id; tenantId = tenant; kind = ScopeEntity.GROUP; encryptedName = "bmFtZQ==" }.persist()
        }
        member(owner, tenant, id, MembershipEntity.OWNER, epoch = 0)
        return id
    }

    private fun member(user: String, tenant: String, scope: String, role: String, epoch: Int) = QuarkusTransaction.requiringNew().run {
        MembershipEntity().apply {
            id = UUID.randomUUID().toString(); userId = user; tenantId = tenant; scopeId = scope; this.role = role
            wrappedGrant = TestFixtures.GRANT; grantedEpoch = epoch; createdAt = Instant.now()
        }.persist()
    }

    private fun stageAll(rotation: TestRotation): Pair<String, List<StagedGrant>> {
        val stageId = rotation.openStageId()
        val grants = rotation.plan().map { rotation.selfGrant(it) }
        rotation.stage(stageId, grants).then().statusCode(204)
        return stageId to grants
    }

    private fun assertSelfGranted(rotation: TestRotation, scopeId: String, kind: String, epoch: Int) {
        val m = QuarkusTransaction.requiringNew().call {
            MembershipEntity.find("userId = ?1 and scopeId = ?2", rotation.userId, scopeId).firstResult()!!
        }
        assertEquals(TestRotation.SELF_GRANT, m.wrappedGrant, "$scopeId: the staged grant")
        assertEquals(epoch, m.grantedEpoch, "$scopeId: at the current epoch")
        assertEquals(rotation.userId, m.granterUserId, "$scopeId: granted by the caller")
        assertEquals(rotation.next.bundle, m.granterIdentityPublicKey, "$scopeId: signed by the new key")
        val stmt = GrantSignature.statement(scopeId, kind, epoch.toLong(), rotation.userId, rotation.next.bundle, m.wrappedGrant!!)
        assertTrue(GrantSignature.verify(rotation.next.bundle, stmt, m.grantSignature), "$scopeId: verifies under the new identity")
        assertFalse(GrantSignature.verify(rotation.current.bundle, stmt, m.grantSignature), "$scopeId: and not under the old one")
        assertEquals(epoch, regrantEpoch(rotation.userId, scopeId), "$scopeId: a pending re-key row at that epoch")
    }

    /** Nothing of a refused publish: the identity, proof and auth key as seeded, no step, no self-grant, no row. */
    private fun assertNothingWritten(rotation: TestRotation, world: World) {
        val u = rotation.userId
        assertEquals(rotation.current.bundle, field(u) { it.identityPublicKey }, "identity unchanged")
        assertEquals(rotation.current.proof.publicKey, field(u) { it.secretKeyProofPublicKey }, "proof key unchanged")
        assertEquals(rotation.current.auth.publicKey, field(u) { it.authPublicKey }, "auth key unchanged")
        assertEquals("b2xkLXBhc3N3b3JkLXdyYXA=", wrapOf(u, "PASSWORD"), "floor unchanged")
        assertEquals(0, chainSize(u), "no step stored")
        listOf(world.personal, world.tenantId, world.group).forEach {
            assertNotEquals(TestRotation.SELF_GRANT, grantOf(u, it), "no self-grant on $it")
            assertNull(regrantEpoch(u, it), "no pending row on $it")
        }
    }

    private fun commit(caller: String, scope: String, epoch: Int, grants: Map<String, String>, removes: String? = null) =
        given().header("Authorization", TestFixtures.bearer(caller)).contentType(ContentType.JSON)
            .body(
                """{"epoch":$epoch,"wrappedPrev":"cHJldg==","grants":[${
                    grants.entries.joinToString(",") { (u, d) ->
                        """{"userId":"$u","wrappedGrant":"Z3JhbnQ=",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"$d"}"""
                    }
                }],${TestGrants.openInvitesJson(scope)}${removes?.let { ""","removesUserId":"$it"""" } ?: ""}}""",
            )
            .`when`().post("/api/scopes/$scope/epochs")

    /** A grant to every member of [scope] under their CURRENT key (the rotator's new one once published). */
    private fun everyone(scope: String, rotation: TestRotation): Map<String, String> =
        QuarkusTransaction.requiringNew().call { MembershipEntity.find("scopeId", scope).list().map { it.userId } }
            .associateWith { TestGrants.digestOf(it) }
            .also { assertTrue(rotation.userId in it) }

    private fun stateOf(rotation: TestRotation, scope: String): String? =
        given().header("Authorization", rotation.bearer()).`when`().get("/api/account/rotation/scopes").then().statusCode(200)
            .extract().path("find { it.scopeId == '$scope' }.state")

    private fun changePassword(rotation: TestRotation, newAuth: TestEd25519, proof: String) =
        given().header("Authorization", rotation.bearer()).contentType(ContentType.JSON)
            .body(
                """{"passwordWrap":{"wrappedKey":"Y2hhbmdlZC13cmFw","kdfParams":"${TestRotation.KDF}"},"authPublicKey":"${newAuth.publicKey}",
                   "attestationSignature":"${b64(rotation.next.auth.sign(newAuth.raw))}","secretKeyProofSignature":"$proof"}""",
            )
            .`when`().post("/api/account/password")

    private fun recover(token: String, authKey: String, proof: String) =
        given().contentType(ContentType.JSON)
            .body("""{"token":"$token","secretKeyProofSignature":"$proof","wrappedKey":"cmVzZXQtd3JhcA==","kdfParams":"${TestRotation.KDF}","authPublicKey":"$authKey"}""")
            .`when`().post("/api/recovery/complete")

    private fun seedWrap(userId: String, method: String, wrapped: String) = QuarkusTransaction.requiringNew().run {
        MasterKeyWrapEntity().apply {
            id = UUID.randomUUID().toString(); this.userId = userId; this.method = method
            wrappedKey = wrapped; kdfParams = TestRotation.KDF
        }.persist()
    }

    private fun seedPins(userId: String, revision: Long) = QuarkusTransaction.requiringNew().run {
        IdentityPinsEntity().apply { accountId = userId; sealedBlob = "b2xkLXBpbnM="; this.revision = revision }.persist()
        IdentityPinEntryEntity().apply { accountId = userId; pinId = "01".repeat(32); sealedEntry = "b2xkLWVudHJ5" }.persist()
    }

    private fun seedEmailChange(userId: String) = QuarkusTransaction.requiringNew().run {
        EmailChangeEntity.delete("userId", userId)
        EmailChangeEntity().apply {
            id = UUID.randomUUID().toString(); this.userId = userId; newEmail = "new-$userId@example.com"
            authPublicKey = "YXV0aA=="; tokenHash = UUID.randomUUID().toString().replace("-", ""); expiresAt = Instant.now().plusSeconds(3600)
        }.persist()
    }

    private fun pinEntries(userId: String): Map<String, String> = QuarkusTransaction.requiringNew().call<Map<String, String>> {
        IdentityPinEntryEntity.list("accountId", userId).associate { it.pinId to it.sealedEntry }
    }

    private fun registerRow(userId: String): Pair<String?, Long>? = QuarkusTransaction.requiringNew().call<Pair<String?, Long>?> {
        DeviceRegisterEntity.findById(userId)?.let { it.sealedBlob to it.revision }
    }

    private fun granterOf(userId: String, scope: String): Pair<String?, String?> = QuarkusTransaction.requiringNew().call {
        MembershipEntity.find("userId = ?1 and scopeId = ?2", userId, scope).firstResult()!!.let { it.granterUserId to it.granterIdentityPublicKey }
    }

    private fun emailChanges(userId: String): Long = QuarkusTransaction.requiringNew().call { EmailChangeEntity.count("userId", userId) }

    private fun membershipOf(userId: String, scope: String): String =
        QuarkusTransaction.requiringNew().call { MembershipEntity.find("userId = ?1 and scopeId = ?2", userId, scope).firstResult()!!.id }

    private fun seedDeliveryJob(scope: String, membershipId: String): String {
        val id = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            JobEntity().apply {
                this.id = id; scopeId = scope; capabilityTag = CapabilityTags.GRANT_DELIVERY; resourceRef = membershipId
                status = JobEntity.PENDING; attempts = 0; maxAttempts = 3; createdAt = Instant.now()
            }.persist()
        }
        return id
    }

    private fun decline(jobId: String, workerId: String) = QuarkusTransaction.requiringNew().run {
        JobDeclineEntity().apply { id = UUID.randomUUID().toString(); this.jobId = jobId; this.workerId = workerId; reason = "identity_unanchored" }.persist()
    }

    private fun declines(jobId: String): Long = QuarkusTransaction.requiringNew().call { JobDeclineEntity.count("jobId", jobId) }

    private fun seedPairing(userId: String, status: String): String {
        val id = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            DevicePairingEntity().apply {
                this.id = id; handleHash = UUID.randomUUID().toString().replace("-", "").repeat(2)
                this.userId = userId; this.status = status
                expiresAt = Instant.now().plus(1, ChronoUnit.HOURS); createdAt = Instant.now()
            }.persist()
        }
        return id
    }

    private fun pairingStatus(id: String): String? = QuarkusTransaction.requiringNew().call<String?> { DevicePairingEntity.findById(id)?.status }

    private fun familyStatus(familyId: String): String? =
        QuarkusTransaction.requiringNew().call<String?> { SessionFamilyEntity.findById(familyId)?.status }

    private fun expire(stageId: String) = QuarkusTransaction.requiringNew().run {
        RotationStageEntity.update("expiresAt = ?1 where id = ?2", Instant.now().minusSeconds(1), stageId)
    }

    private fun setEpoch(scope: String, epoch: Int) = QuarkusTransaction.requiringNew().run {
        ScopeEntity.update("currentEpoch = ?1 where id = ?2", epoch, scope)
    }

    private fun stagedRows(stageId: String): Long = QuarkusTransaction.requiringNew().call { RotationStageGrantEntity.count("stageId", stageId) }

    private fun regrantEpoch(userId: String, scope: String): Int? = QuarkusTransaction.requiringNew().call<Int?> {
        RotationRegrantEntity.find("userId = ?1 and scopeId = ?2", userId, scope).firstResult()?.epochAtRotation
    }

    private fun chainSize(userId: String): Int = QuarkusTransaction.requiringNew().call { KeyChainStepEntity.count("userId", userId).toInt() }

    private fun grantOf(userId: String, scope: String): String? = QuarkusTransaction.requiringNew().call<String?> {
        MembershipEntity.find("userId = ?1 and scopeId = ?2", userId, scope).firstResult()?.wrappedGrant
    }

    private fun epochOf(scope: String): Int = TestGrants.epochOf(scope)

    private fun <T> field(userId: String, read: (VeyqUser) -> T): T? =
        QuarkusTransaction.requiringNew().call<T?> { VeyqUser.findById(userId)?.let(read) }

    private fun wrapOf(userId: String, method: String): String? = QuarkusTransaction.requiringNew().call<String?> {
        MasterKeyWrapEntity.find("userId = ?1 and method = ?2", userId, method).firstResult()?.wrappedKey
    }

    private fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    private fun decode(s: String): ByteArray = Base64.getDecoder().decode(s)
}
