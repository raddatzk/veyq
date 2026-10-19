package me.raddatz.veyq.core.account.rotation

import me.raddatz.veyq.core.account.deviceregister.DeviceRegisterEntity
import me.raddatz.veyq.core.account.identitypins.IdentityPinEntryEntity
import me.raddatz.veyq.core.account.identitypins.IdentityPinsEntity
import me.raddatz.veyq.core.account.login.SessionFamilyEntity
import me.raddatz.veyq.core.account.login.SessionService
import me.raddatz.veyq.core.account.pairing.DevicePairingEntity
import me.raddatz.veyq.core.tenant.TenantEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.tasks.CapabilityTags
import me.raddatz.veyq.core.tasks.JobDeclineEntity
import me.raddatz.veyq.core.tasks.JobEntity
import me.raddatz.veyq.core.spaces.scope.ScopeEpochEntity
import me.raddatz.veyq.core.crypto.SecretKeyProof
import me.raddatz.veyq.core.crypto.TestProofKey
import me.raddatz.veyq.core.identity.MasterKeyWrapEntity
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.membership.TestAdmission
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.hasItem
import org.hamcrest.CoreMatchers.not
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Slice tests for key rotation after a compromise (uc.account.rotate-keys, ADR 0060).
 *
 * The happy path is the least interesting case here, as it is in `RecoverySliceTest` and for the same reason:
 * what this operation is *for* is a state where an attacker holds the account's key material, so the tests that
 * matter are the ones about what rotation takes away and who is left unable to act. Three carry weight —
 *
 *  - **every device wrap dies** and both cold wraps are rewritten, or nothing is written at all;
 *  - **the rotator cannot claim their own rotation** (ADR 0060 Decision 4), which is the difference between an
 *    exclusion mechanism and an attacker's finishing move;
 *  - **a co-member still can**, which is the control the exclusion needs: a rotation nobody can complete looks
 *    identical to a correctly-excluded one if only the negative is asserted.
 *
 * The cold-credential gate (Decision 2) is asserted here for its **server** half: a session is not enough, the
 * request must carry a signature under the account's Secret-Key proof key, and the bite is a caller who holds a
 * valid session and signs with a key of their own — exactly what a device-link holder can do. Its password half
 * (re-opening the `PASSWORD` wrap) is client-side and `web/` owns that proof.
 *
 * Seeds use UUID-unique ids: the H2 mem DB lives for the whole JVM and is shared across test classes.
 */
@QuarkusTest
class RotationSliceTest {

    @Inject
    lateinit var sessions: SessionService

    @Test
    fun rotationReplacesEveryKeyRewritesBothColdWrapsAndDeletesTheDeviceOnes() {
        val user = seedAccount()
        seedWrap(user, "DEVICE", "ZGV2aWNlLXdyYXA=")
        seedWrap(user, "DEVICE_KEY", "ZGV2aWNlLWtleS13cmFw")
        seedWrap(user, "PASSKEY", "cGFzc2tleS13cmFw")

        rotate(user).then().statusCode(200)

        assertEquals(NEW_IDENTITY_PUB, field(user) { it.identityPublicKey }, "the directory value is republished")
        assertEquals(NEW_PRIV, field(user) { it.wrappedIdentityPrivateKey }, "the identity private key rides the new Master Key")
        assertEquals(PROOF_KEY.publicKey, field(user) { it.secretKeyProofPublicKey }, "the Secret Key does not rotate, so neither does its proof key")

        assertEquals(NEW_PW_WRAP, wrapOf(user, "PASSWORD"), "the floor holds the new Master Key")
        assertEquals(NEW_REC_WRAP, wrapOf(user, "RECOVERY"), "so does the withheld recovery wrap — the Emergency Kit stays valid")
        // The whole point of the operation: a device-held KEK cannot be rewritten from here, so the wrap that
        // the granted device can still open must not survive holding the OLD Master Key.
        assertNull(wrapOf(user, "DEVICE"), "a trusted-device wrap cannot be rewritten, so it goes")
        assertNull(wrapOf(user, "DEVICE_KEY"), "and the password-less one especially — it needs no password at all")
        assertNull(wrapOf(user, "PASSKEY"), "every non-cold method goes; the survivor list is a whitelist")
    }

    @Test
    fun aSessionWithoutTheSecretKeyCannotRotate() {
        // inbox #159's second door, and the whole reason this check is on the server. The caller holds a valid
        // session and a well-formed request carrying a fresh identity — everything a device-link holder has — and
        // signs it with a key of their own, because they do not hold the Secret Key the account's proof key is
        // derived from. The positive control is the SAME request signed by the account's proof key: without it a
        // rotation that refused everything would pass this test just as well.
        val user = seedAccount()

        rotate(user, signature = OTHER_KEY.sign(rotationStatement()))
            .then().statusCode(403).body("code", equalTo("secret_key_proof_failed"))

        assertEquals(OLD_IDENTITY_PUB, field(user) { it.identityPublicKey }, "the attacker's identity is not installed")
        assertEquals(OLD_PRIV, field(user) { it.wrappedIdentityPrivateKey })
        assertEquals(OLD_PW_WRAP, wrapOf(user, "PASSWORD"), "nothing was written")

        rotate(user).then().statusCode(200)
        assertEquals(NEW_IDENTITY_PUB, field(user) { it.identityPublicKey }, "control: the account's own proof key rotates")
    }

    @Test
    fun aCapturedRotationSignatureCannotBeReplayed() {
        // The statement binds the identity it replaces. Once that rotation has applied, the same bytes — a
        // captured request replayed against the rotated account — describe a state that no longer exists.
        val user = seedAccount()
        rotate(user).then().statusCode(200)

        rotate(user).then().statusCode(403).body("code", equalTo("secret_key_proof_failed"))
    }

    @Test
    fun anAccountWithNoProofKeyCannotRotate() {
        // Born before the proof key existed: nothing is migrated, so such an account can prove nothing and is
        // refused rather than waved through (ADR 0054 § Consequences: the proof key is a correctness dependency).
        val user = seedAccount()
        QuarkusTransaction.requiringNew().run { VeyqUser.findById(user)!!.secretKeyProofPublicKey = null }

        rotate(user).then().statusCode(403).body("code", equalTo("secret_key_proof_failed"))
        assertEquals(OLD_IDENTITY_PUB, field(user) { it.identityPublicKey })
    }

    @Test
    fun theRotatorCannotClaimTheirOwnRotationButACoMemberCan() {
        // ADR 0060 Decision 4. The negative alone would pass just as well against a rotation nobody can ever
        // complete — a job that was never originated, a scope nobody holds a grant in — so the co-member's
        // successful claim on the SAME job is the control that gives the refusal its meaning.
        val rotator = seedAccount()
        val coMember = seedAccount()
        val shared = seedTenantScope()
        seedGrantedMembership(rotator, shared)
        seedGrantedMembership(coMember, shared)

        rotate(rotator).then().statusCode(200)
            .body("queuedScopeIds", hasItem(shared.scopeId))
            .body("soleMemberScopeIds", not(hasItem(shared.scopeId)))

        val jobId = rotationJobIn(shared)
        assertNotNull(jobId, "a shared scope must carry a queued rotation for somebody else")

        claim(rotator, CapabilityTags.SCOPE_ROTATION).then().statusCode(204)
        claim(coMember, CapabilityTags.SCOPE_ROTATION).then().statusCode(200).body("jobId", equalTo(jobId))
    }

    @Test
    fun aScopeThatAlreadyHadALiveRotationStillExcludesTheRotator() {
        // The coalescing trap: origination joins a scope's already-unclaimed rotation job (a removal cut
        // moments earlier), and the naive shape then writes no exclusion — leaving the rotation this account
        // triggered claimable by the very account it locks out.
        val rotator = seedAccount()
        val coMember = seedAccount()
        val shared = seedTenantScope()
        seedGrantedMembership(rotator, shared)
        seedGrantedMembership(coMember, shared)
        val preExisting = seedRotationJob(shared)

        rotate(rotator).then().statusCode(200).body("queuedScopeIds", hasItem(shared.scopeId))

        assertEquals(preExisting, rotationJobIn(shared), "the rotation coalesces onto the live job rather than adding a second")
        claim(rotator, CapabilityTags.SCOPE_ROTATION).then().statusCode(204)
        claim(coMember, CapabilityTags.SCOPE_ROTATION).then().statusCode(200).body("jobId", equalTo(preExisting))
    }

    @Test
    fun aSoleMemberScopeIsLeftForTheCallerAndQueuesNothing() {
        // ADR 0060 Decision 5: with one member there is nobody to be re-admitted by and nobody to exclude but
        // the rotator's own old identity, so queuing there would be a permanent "awaiting access" nobody could
        // resolve. The split is drawn on member count, not scope kind.
        val user = seedAccount()
        val personal = seedPersonalScope()
        seedGrantedMembership(user, personal)

        rotate(user).then().statusCode(200)
            .body("soleMemberScopeIds", hasItem(personal.scopeId))
            .body("queuedScopeIds", not(hasItem(personal.scopeId)))

        assertNull(rotationJobIn(personal), "nobody else could ever claim it, so nothing is queued")
    }

    @Test
    fun aPendingConsentProposalDoesNotMakeAScopeShared() {
        // A `pendingConsent` row holds no grant and is skipped by grant-delivery until the invitee accepts, so
        // it cannot complete a rotation either. Counting it would queue a job with no possible claimant.
        val user = seedAccount()
        val scope = seedTenantScope()
        seedGrantedMembership(user, scope)
        seedPendingConsentMembership(seedAccount(), scope)

        rotate(user).then().statusCode(200)
            .body("soleMemberScopeIds", hasItem(scope.scopeId))
            .body("queuedScopeIds", not(hasItem(scope.scopeId)))
    }

    @Test
    fun aGrantTheRotatorSignedIsStillServedWithTheKeyThatSignedIt() {
        // Inbox #233, ADR 0028 § What is still open. The rotator R wrote co-owner O2's grant through a real grant
        // path (R's rotation of the tenant scope re-granting O2) and signed it with R's OLD identity. After R rotates
        // their account keys, both reads must still pair that signature with the OLD key: O2's client verifies the
        // signature under the served key BEFORE it consults its pin of R, so serving R's new key fails the grant as
        // `bad_signature` and O2 — the owner who must complete the covering rotation — cannot open the scope at all.
        val rotator = seedAccount()
        val coOwner = seedUser(identityPublicKey = CO_OWNER_PUB)
        val shared = seedTenantScope()
        seedGrantedMembership(rotator, shared)
        seedGrantedMembership(coOwner, shared)
        given().header("Authorization", bearer(rotator)).contentType(ContentType.JSON)
            .body(
                """{"epoch":1,"wrappedPrev":"$ROT_PREV","grants":[
                   {"userId":"$rotator","wrappedGrant":"$ROTATOR_GRANT","grantSignature":"$SIG_FOR_ROTATOR"},
                   {"userId":"$coOwner","wrappedGrant":"$CO_OWNER_GRANT","grantSignature":"$SIG_FOR_CO_OWNER"}],
                   ${TestAdmission.openJson(shared.scopeId)}}""",
            )
            .`when`().post("/api/scopes/${shared.scopeId}/epochs").then().statusCode(201)
        assertGrantServedTo(coOwner, shared, OLD_IDENTITY_PUB) // before: nothing has rotated yet

        rotate(rotator).then().statusCode(200)

        assertGrantServedTo(coOwner, shared, OLD_IDENTITY_PUB)

        // Positive control: a grant R writes AFTER rotating is served with R's NEW key — so the old key above is
        // the snapshot of the grant's own moment, not a key that simply never updates.
        val personalId = UUID.randomUUID().toString()
        given().header("Authorization", bearer(rotator)).header("X-Forwarded-Host", shared.domain)
            .contentType(ContentType.JSON)
            .body("""{"scopeId":"$personalId","wrappedGrant":"$ROTATOR_GRANT","grantSignature":"$SIG_FOR_ROTATOR"}""")
            .`when`().post("/api/scopes").then().statusCode(201)
        given().header("Authorization", bearer(rotator)).header("X-Forwarded-Host", shared.domain)
            .`when`().get("/api/scopes").then().statusCode(200)
            .body("find { it.id == '$personalId' }.granterIdentityPublicKey", equalTo(NEW_IDENTITY_PUB))
    }

    /** Both reads that serve a grant (`/api/scopes`, `/api/scopes/my-tenants`) hand [recipient] the rotation's grant
     *  on [scope], signed by the rotator, beside [granterKey]. */
    private fun assertGrantServedTo(recipient: String, scope: SeededScope, granterKey: String) {
        given().header("Authorization", bearer(recipient)).header("X-Forwarded-Host", scope.domain)
            .`when`().get("/api/scopes").then().statusCode(200)
            .body("find { it.id == '${scope.scopeId}' }.wrappedGrant", equalTo(CO_OWNER_GRANT))
            .body("find { it.id == '${scope.scopeId}' }.grantSignature", equalTo(SIG_FOR_CO_OWNER))
            .body("find { it.id == '${scope.scopeId}' }.granterIdentityPublicKey", equalTo(granterKey))
        given().header("Authorization", bearer(recipient))
            .`when`().get("/api/scopes/my-tenants").then().statusCode(200)
            .body("find { it.tenantId == '${scope.tenantId}' }.grantSignature", equalTo(SIG_FOR_CO_OWNER))
            .body("find { it.tenantId == '${scope.tenantId}' }.granterIdentityPublicKey", equalTo(granterKey))
    }

    @Test
    fun rotationSupersedesLivePairingChannelsAndRevokesEveryOtherSession() {
        val user = seedAccount()
        val pending = seedPairing(user, DevicePairingEntity.PENDING)
        val sealed = seedPairing(user, DevicePairingEntity.SEALED)
        val collected = seedPairing(user, DevicePairingEntity.COLLECTED)
        val otherFamily = sessions.issue(user).let { familyIdOf(user, it.accessToken) }

        val token = sessions.issue(user).accessToken
        val ownFamily = familyIdOf(user, token)
        rotate(user, token).then().statusCode(200)

        // A live channel's envelope carries (or is about to carry) the OLD Master Key and a live delegated
        // session — a later collect would hand a device a dead key and a working login.
        assertEquals(DevicePairingEntity.SUPERSEDED, pairingStatus(pending))
        assertEquals(DevicePairingEntity.SUPERSEDED, pairingStatus(sealed))
        assertEquals(DevicePairingEntity.COLLECTED, pairingStatus(collected), "a spent channel is already terminal; nothing to supersede")

        assertEquals(SessionFamilyEntity.REVOKED, familyStatus(otherFamily), "the granted device loses server access too")
        assertEquals(SessionFamilyEntity.ACTIVE, familyStatus(ownFamily), "signing out the device doing the rotation would strand the ceremony")
    }

    @Test
    fun withoutARecoveryWrapNothingIsWritten() {
        // A rotation that applied its identity half and then found nothing to rewrite would leave an account
        // whose published key nobody can wrap to and whose Master Key nothing can open. It refuses instead, and
        // the account it refuses on must be exactly the account it started with.
        val user = seedAccount()
        QuarkusTransaction.requiringNew().run { MasterKeyWrapEntity.delete("userId = ?1 and method = ?2", user, "RECOVERY") }

        rotate(user).then().statusCode(409).body("code", equalTo("rotation_unavailable"))

        assertEquals(OLD_IDENTITY_PUB, field(user) { it.identityPublicKey }, "the directory value is untouched")
        assertEquals(OLD_PRIV, field(user) { it.wrappedIdentityPrivateKey })
        assertEquals(OLD_PW_WRAP, wrapOf(user, "PASSWORD"), "the floor still opens the Master Key it always did")
    }

    @Test
    fun aStalePinStoreRevisionRollsTheWholeRotationBack() {
        // ADR 0060 § Consequences: the two Master-Key-sealed documents are re-sealed INSIDE the rotation's
        // transaction, so a rotation that cannot write them does not happen. The lever is the pin store's
        // `revision` in the request, one behind the stored one. What makes this a transaction rather than two
        // writes is the account half: the identity and both cold wraps were already rewritten when the pin write
        // found itself stale, and they must be exactly as before. The control is the same seed with the matching
        // revision — without it a rotation that refused everything would pass the negative just as well.
        val user = seedAccount()
        seedWrap(user, "DEVICE", "ZGV2aWNlLXdyYXA=")
        seedPins(user, OLD_PINS, revision = 3)
        seedRegister(user, OLD_REGISTER, revision = 2)

        rotate(user, pinsRevision = 2, registerRevision = 2)
            .then().statusCode(409).body("code", equalTo("stale_revision"))

        assertEquals(OLD_IDENTITY_PUB, field(user) { it.identityPublicKey }, "the directory value is not published")
        assertEquals(OLD_PRIV, field(user) { it.wrappedIdentityPrivateKey })
        assertEquals(OLD_PW_WRAP, wrapOf(user, "PASSWORD"), "the floor still opens the Master Key it always did")
        assertEquals(OLD_REC_WRAP, wrapOf(user, "RECOVERY"))
        assertEquals("ZGV2aWNlLXdyYXA=", wrapOf(user, "DEVICE"), "nothing was deleted either")
        assertEquals(OLD_PINS to 3L, pinsRow(user))
        assertEquals(mapOf(OLD_PIN_ID to OLD_ENTRY), pinEntries(user), "no entry of the rolled-back write landed")
        assertEquals(OLD_REGISTER to 2L, registerRow(user))

        rotate(user, pinsRevision = 3, registerRevision = 2).then().statusCode(200)
        assertEquals(NEW_IDENTITY_PUB, field(user) { it.identityPublicKey }, "control: the matching revisions rotate")
        assertEquals(NEW_PINS to 4L, pinsRow(user), "the pin store's head is the request's, one revision up")
        assertEquals(mapOf(NEW_PIN_ID to NEW_ENTRY), pinEntries(user), "every entry re-keyed: the old id is gone")
        assertEquals(NEW_REGISTER to 3L, registerRow(user), "and so is the register")
    }

    @Test
    fun aStaleRegisterRevisionRollsTheWholeRotationBack() {
        // The register is its own lever: stale there, with the pin store's revision matching. The pin write has
        // already landed in the transaction when the register write finds itself stale, so the pin row being
        // untouched is what proves the rollback reaches the documents too, not only the account half.
        val user = seedAccount()
        seedPins(user, OLD_PINS, revision = 3)
        seedRegister(user, OLD_REGISTER, revision = 2)

        rotate(user, pinsRevision = 3, registerRevision = 1)
            .then().statusCode(409).body("code", equalTo("stale_revision"))

        assertEquals(OLD_IDENTITY_PUB, field(user) { it.identityPublicKey }, "the directory value is not published")
        assertEquals(OLD_PW_WRAP, wrapOf(user, "PASSWORD"))
        assertEquals(OLD_PINS to 3L, pinsRow(user), "the pin write rolled back with the rest")
        assertEquals(mapOf(OLD_PIN_ID to OLD_ENTRY), pinEntries(user), "its entries too")
        assertEquals(OLD_REGISTER to 2L, registerRow(user))

        rotate(user, pinsRevision = 3, registerRevision = 2).then().statusCode(200)
        assertEquals(NEW_REGISTER to 3L, registerRow(user), "control: the matching revision rotates")
    }

    @Test
    fun anAccountWithNoDocumentYetGetsBothBornInTheRotation() {
        // An account that never wrote either document sends each sealed for revision 1 against revision 0 — the
        // stores' born-empty rule — rather than leaving the field out (a required field is not a mode).
        val user = seedAccount()
        assertNull(pinsRow(user))
        assertNull(registerRow(user))

        rotate(user, pinsRevision = 0, registerRevision = 0).then().statusCode(200)

        assertEquals(NEW_PINS to 1L, pinsRow(user))
        assertEquals(mapOf(NEW_PIN_ID to NEW_ENTRY), pinEntries(user))
        assertEquals(NEW_REGISTER to 1L, registerRow(user))
    }

    @Test
    fun aRotationThatKeepsAPinEntryIsRefused() {
        // ADR 0104: a new Master Key is a new pin-index key, so the rotation carries the WHOLE re-keyed set. A write
        // without replaceAll would keep the old entries under ids and seals of the retired key, which every device
        // then refuses — so it is a 400 with nothing written. The control is the same request with replaceAll.
        val user = seedAccount()
        seedPins(user, OLD_PINS, revision = 3)

        rotate(user, pinsRevision = 3, registerRevision = 0, pinsReplaceAll = false)
            .then().statusCode(400).body("code", equalTo("invalid_request"))
        assertEquals(OLD_IDENTITY_PUB, field(user) { it.identityPublicKey }, "nothing published")
        assertEquals(OLD_PINS to 3L, pinsRow(user))
        assertEquals(mapOf(OLD_PIN_ID to OLD_ENTRY), pinEntries(user))

        rotate(user, pinsRevision = 3, registerRevision = 0).then().statusCode(200)
        assertEquals(mapOf(NEW_PIN_ID to NEW_ENTRY), pinEntries(user), "control: the whole set is replaced")
    }

    @Test
    fun rotationWithoutAuthIs401() {
        given().contentType(ContentType.JSON).body(rotationBody())
            .`when`().post("/api/account/rotation")
            .then().statusCode(401).body("code", equalTo("authentication_failed"))
    }

    @Test
    fun aRequestMissingAColdWrapIsRejectedAtTheBoundary() {
        // The contract has no way to say "rotate identity only" (ADR 0060 Decision 1, pinned by
        // scripts/validate_rotation_shape.py). Omitting a required cold wrap is the closest a client can come,
        // and it is a 400 before any handler runs — never a partial rotation.
        val user = seedAccount()
        val body = """
            {"identityPublicKey":"$NEW_IDENTITY_PUB","wrappedIdentityPrivateKey":"$NEW_PRIV",
             "passwordWrap":{"wrappedKey":"$NEW_PW_WRAP","kdfParams":"$KDF"},
             "secretKeyProofSignature":"${PROOF_KEY.sign(rotationStatement())}",
             "identityPins":{"revision":0,"head":"$NEW_PINS","put":[],"remove":[],"replaceAll":true},
             "deviceRegister":{"blob":"$NEW_REGISTER","revision":0}}
        """.trimIndent()
        given().header("Authorization", bearer(user))
            .contentType(ContentType.JSON).body(body)
            .`when`().post("/api/account/rotation").then().statusCode(400)

        assertEquals(OLD_IDENTITY_PUB, field(user) { it.identityPublicKey }, "a rejected request writes nothing")
    }

    // --- the queued rotation an account rotation leaves behind (uc.spaces.complete-queued-rotation) -----------

    @Test
    fun anAccountRotationDuringALeaseGetsItsOwnJobAndExcludesTheRotatorFromBoth() {
        // D3 + D5: a leased job's worker may already have read the directory with the OLD identity in it, so the
        // rotation gets a fresh job; and the rotator is excluded from the leased one too, or its lease expiring
        // would hand the rotator their own rotation by lazy reclaim.
        val rotator = seedAccount()
        val coOwner = seedAccount()
        val shared = seedTenantScope()
        seedGrantedMembership(rotator, shared)
        seedGrantedMembership(coOwner, shared)
        val leased = seedRotationJob(shared)
        claim(coOwner, CapabilityTags.SCOPE_ROTATION).then().statusCode(200).body("jobId", equalTo(leased))

        rotate(rotator).then().statusCode(200)

        val fresh = rotationJobsIn(shared) - leased
        assertEquals(1, fresh.size, "an account rotation during a lease queues a job of its own")
        assertEquals(JobEntity.PENDING, statusOf(fresh.single()))
        assertEquals(ROTATION_ORIGINATOR, declineReason(fresh.single(), rotator), "excluded from the fresh job")
        assertEquals(ROTATION_ORIGINATOR, declineReason(leased, rotator), "and from the leased one")
        expireLease(leased)
        claim(rotator, CapabilityTags.SCOPE_ROTATION).then().statusCode(204)
        // Positive control: both jobs are real work for the other owner.
        claim(coOwner, CapabilityTags.SCOPE_ROTATION).then().statusCode(200).body("jobId", equalTo(leased))
        claim(coOwner, CapabilityTags.SCOPE_ROTATION).then().statusCode(200).body("jobId", equalTo(fresh.single()))
    }

    @Test
    fun theRotatorCannotCommitWhileTheirExclusionIsLive() {
        // D7 clause 1 (#206): for an owner rotator the claim was the only place ADR 0060 Decision 4 was enforced;
        // a client of their own could post the commit directly.
        val rotator = seedAccount()
        val coOwner = seedAccount()
        val pending = seedTenantScope()
        val leased = seedTenantScope()
        listOf(pending, leased).forEach { seedGrantedMembership(rotator, it); seedGrantedMembership(coOwner, it) }
        rotate(rotator).then().statusCode(200)
        claimJob(coOwner, leased) // the leased leg: every live job of that scope is held by the other owner

        listOf(pending, leased).forEach { scope ->
            commit(rotator, scope, rotator, coOwner).then().statusCode(403).body("code", equalTo("rotation_excluded"))
            assertEquals(0, epochOf(scope), "nothing stored")
            assertEquals(0L, chainLinks(scope), "no chain link stored")
            // Positive control, same scope and moment: the other owner's identical commit lands.
            commit(coOwner, scope, rotator, coOwner).then().statusCode(201)
        }

        // Reason filter: only the rotation exclusion counts — a worker's own decline is about one job.
        val worker = seedAccount()
        val other = seedTenantScope()
        seedGrantedMembership(worker, other)
        val job = seedRotationJob(other)
        assertEquals(job, claimJob(worker, other))
        given().header("Authorization", bearer(worker)).contentType(ContentType.JSON).body("""{"reason":"identity_changed"}""")
            .`when`().post("/api/tasks/$job/decline").then().statusCode(200)
        commit(worker, other, worker).then().statusCode(201)
    }

    @Test
    fun afterTheCoveringRotationTheRotatorIsBehindUntilReAdmitted() {
        // D7 clause 2 (#206): once the covering rotation landed (skipping the rotator's changed key), the rotator's
        // live declines are all on DONE jobs and only their behind grant still holds them — until a human re-admits.
        val tenant = seedTenantScope()
        val rotator = seedAccount()
        val coOwner = seedAccount()
        seedGrantedMembership(rotator, tenant)
        seedGrantedMembership(coOwner, tenant)
        val group = seedGroup(tenant, rotator, coOwner)
        rotate(rotator).then().statusCode(200)
        val job = claimJob(coOwner, group)
        commit(coOwner, group, coOwner).then().statusCode(201) // a client that sees the rotator's key as changed
        complete(coOwner, job).then().statusCode(204)

        commit(rotator, group, rotator, coOwner).then().statusCode(409).body("code", equalTo("stale_epoch"))
        assertEquals(1, epochOf(group), "nothing stored")

        // Re-admission ends it: grant-delivery writes the rotator the current epoch, through the real reconcile.
        val delivery = claimJob(coOwner, group, CapabilityTags.GRANT_DELIVERY)
        given().header("Authorization", bearer(coOwner)).contentType(ContentType.JSON)
            .body("""{"sealedResult":"cmUtYWRtaXR0ZWQ=","epoch":1,${TestAdmission.SIGNATURE_JSON}}""")
            .`when`().post("/api/tasks/$delivery/complete").then().statusCode(204)
        commit(rotator, group, rotator, coOwner).then().statusCode(201)

        // Grant-less: an owner promoted before any grant reached them holds no key to chain from.
        val promoted = seedAccount()
        seedMembership(promoted, tenant, MembershipEntity.MEMBER)
        seedMembership(promoted, group, MembershipEntity.MEMBER, grant = null)
        setRole(coOwner, group, promoted, MembershipEntity.OWNER)
        commit(promoted, group, promoted, coOwner).then().statusCode(409).body("code", equalTo("stale_epoch"))
        assertEquals(2, epochOf(group))
    }

    @Test
    fun theRotatorReadsEachQueuedRotationsStateFromTheServer() {
        // D9 (#225): the status is the server's, re-readable, and `secured` never shows while the old identity can
        // hold the current epoch. Every leg is set up before the one rotation, which covers every scope at once.
        val tenant = seedTenantScope()
        val rotator = seedAccount()
        val coOwner = seedAccount()
        val member = seedAccount()
        listOf(rotator, coOwner, member).forEach { seedGrantedMembership(it, tenant) }
        val waitingThenFailed = seedGroup(tenant, rotator, coOwner)
        val unswept = seedGroup(tenant, rotator, coOwner)
        val securedByDone = seedGroup(tenant, rotator, coOwner)
        val securedByBehind = seedGroup(tenant, rotator, coOwner)
        val ordering = seedGroup(tenant, rotator, coOwner)
        val soleOwner = seedGroup(tenant, rotator)
        seedMembership(member, soleOwner, MembershipEntity.MEMBER)
        val olderJob = seedRotationJob(ordering)
        assertEquals(olderJob, claimJob(coOwner, ordering))
        states(coOwner).then().statusCode(200).body("size()", equalTo(0)) // never rotated: nothing to read

        rotate(rotator).then().statusCode(200)

        assertEquals("waiting", stateOf(rotator, waitingThenFailed))
        assertEquals("stalled", stateOf(rotator, soleOwner), "no owner but the excluded rotator")
        setRole(rotator, soleOwner, member, MembershipEntity.OWNER) // E4's remedy
        assertEquals("waiting", stateOf(rotator, soleOwner), "a granted owner without a decline makes it claimable")

        deadLetter(coOwner, waitingThenFailed)
        assertEquals("failed", stateOf(rotator, waitingThenFailed))

        repeat(MAX_ATTEMPTS - 1) { fail(coOwner, claimJob(coOwner, unswept)) }
        expireLease(claimJob(coOwner, unswept)) // the last attempt crashes: LEASED, expired, never swept
        assertEquals("failed", stateOf(rotator, unswept), "dead in fact although the row still reads LEASED")

        val covering = claimJob(coOwner, securedByDone)
        commit(coOwner, securedByDone, coOwner).then().statusCode(201)
        complete(coOwner, covering).then().statusCode(204)
        assertEquals("secured", stateOf(rotator, securedByDone))

        deadLetter(coOwner, securedByBehind)
        commit(coOwner, securedByBehind, coOwner).then().statusCode(201) // a synchronous rotation, no job behind it
        assertEquals("secured", stateOf(rotator, securedByBehind), "the old identity's grant is behind")

        commit(coOwner, ordering, rotator, coOwner).then().statusCode(201) // keeps the rotator current on purpose
        complete(coOwner, olderJob).then().statusCode(204)
        deadLetter(coOwner, ordering)
        assertEquals("failed", stateOf(rotator, ordering), "the newest excluding job decides, not an older DONE one")
    }

    @Test
    fun twoCoveringJobsOfOneInstantAreDecidedWithoutTheRandomId() {
        // The newest covering job is read by `created_at` (millisecond). Forced to the SAME instant, the dead-lettered
        // job still decides over an older DONE one whichever way their random ids sort — several scopes, so a tie
        // that fell to the id would be red here with near-certainty (the pure rule: TasksStoreNewestCoveringTest).
        val tenant = seedTenantScope()
        val rotator = seedAccount()
        val coOwner = seedAccount()
        listOf(rotator, coOwner).forEach { seedGrantedMembership(it, tenant) }
        val scopes = List(8) { seedGroup(tenant, rotator, coOwner) }
        val older = scopes.associateWith { seedRotationJob(it) }
        scopes.forEach { assertEquals(older.getValue(it), claimJob(coOwner, it)) }
        rotate(rotator).then().statusCode(200)
        scopes.forEach { scope ->
            val newer = rotationJobsIn(scope).single { it != older.getValue(scope) }
            QuarkusTransaction.requiringNew().run {
                val at = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                JobEntity.update("status = ?1, createdAt = ?2 where id = ?3", JobEntity.DONE, at, older.getValue(scope))
                JobEntity.update("status = ?1, createdAt = ?2 where id = ?3", JobEntity.FAILED, at, newer)
            }
            assertEquals("failed", stateOf(rotator, scope), "a tie of one instant: the dead-lettered job decides, not the id")
        }
    }

    @Test
    fun fromFailedTheRotatorRequeuesAndTheExclusionIsBack() {
        // D9 + Q6: re-queue only from `failed`; it excludes the rotator again, which closes the Q5 lapse.
        val tenant = seedTenantScope()
        val rotator = seedAccount()
        val coOwner = seedAccount()
        val cut = seedAccount()
        listOf(rotator, coOwner, cut).forEach { seedGrantedMembership(it, tenant) }
        val scope = seedGroup(tenant, rotator, coOwner)
        val twin = seedGroup(tenant, rotator, coOwner)
        val joined = seedGroup(tenant, rotator, coOwner)
        seedMembership(cut, joined, MembershipEntity.MEMBER)
        val alone = seedGroup(tenant, rotator)
        rotate(rotator).then().statusCode(200)
        listOf(scope, twin, joined).forEach { deadLetter(coOwner, it) }
        val jobsBefore = rotationJobsIn(scope).size

        requeue(rotator, scope).then().statusCode(200).body("scopeId", equalTo(scope.scopeId)).body("state", equalTo("waiting"))
        val requeued = rotationJobsIn(scope).single { statusOf(it) == JobEntity.PENDING }
        assertEquals(ROTATION_ORIGINATOR, declineReason(requeued, rotator))
        commit(rotator, scope, rotator, coOwner).then().statusCode(403).body("code", equalTo("rotation_excluded"))
        // The control on the same predicate: the twin, not re-queued, is the Q5 lapse the re-queue closes.
        commit(rotator, twin, rotator, coOwner).then().statusCode(201)

        requeue(rotator, scope).then().statusCode(409).body("code", equalTo("rotation_not_failed"))
        assertEquals(jobsBefore + 1, rotationJobsIn(scope).size, "a refused re-queue writes nothing")
        requeue(rotator, alone).then().statusCode(404).body("code", equalTo("rotation_not_found"))
        assertEquals(requeued, claimJob(coOwner, scope))

        // A removal queued an unclaimed job since the dead-letter: the re-queue joins it and excludes the rotator.
        given().header("Authorization", bearer(coOwner)).`when`().delete("/api/tenants/${tenant.tenantId}/members/$cut")
            .then().statusCode(204)
        val removalJob = rotationJobsIn(joined).single { statusOf(it) == JobEntity.PENDING }
        assertNull(declineReason(removalJob, rotator))
        requeue(rotator, joined).then().statusCode(200)
        assertEquals(listOf(removalJob), rotationJobsIn(joined).filter { statusOf(it) == JobEntity.PENDING }, "joined, not added")
        assertEquals(ROTATION_ORIGINATOR, declineReason(removalJob, rotator))
    }

    @Test
    fun anOwnerSeesAStuckCompromiseRotationOnTheirOwnRowAndRequeuesIt() {
        // D10 (#234): the scope's other owners are the ones who can act, so the state shows on their own rows.
        val tenant = seedTenantScope()
        val rotator = seedAccount()
        val coOwner = seedAccount()
        val thirdOwner = seedAccount()
        val member = seedAccount()
        val decliner = seedAccount()
        val keyless = seedAccount()
        listOf(rotator, coOwner, thirdOwner, member, decliner, keyless).forEach { seedGrantedMembership(it, tenant) }
        val group = seedGroup(tenant, rotator, coOwner, thirdOwner)
        seedMembership(member, group, MembershipEntity.MEMBER)
        val stalled = seedGroup(tenant, rotator, decliner)
        seedMembership(keyless, stalled, MembershipEntity.OWNER, grant = null)
        rotate(rotator).then().statusCode(200)

        assertNull(ownerRow(coOwner, tenant, group), "waiting needs nobody")
        deadLetter(coOwner, group)
        assertEquals("failed", ownerRow(coOwner, tenant, group))
        assertEquals("failed", ownerRow(thirdOwner, tenant, group))
        assertNull(ownerRow(member, tenant, group), "a plain member learns nothing they could act on")

        val declined = claimJob(decliner, stalled)
        given().header("Authorization", bearer(decliner)).contentType(ContentType.JSON).body("""{"reason":"identity_changed"}""")
            .`when`().post("/api/tasks/$declined/decline").then().statusCode(200)
        assertEquals("stalled", ownerRow(decliner, tenant, stalled), "no granted owner without a decline is left")

        // The tenant root, read through the switcher's row.
        deadLetter(coOwner, tenant)
        given().header("Authorization", bearer(coOwner)).`when`().get("/api/scopes/my-tenants").then().statusCode(200)
            .body("find { it.tenantId == '${tenant.tenantId}' }.compromiseRotation", equalTo("failed"))

        val jobsBefore = rotationJobsIn(group).size
        requeueAsOwner(thirdOwner, group).then().statusCode(200).body("compromiseRotation", nullValue())
        val requeued = rotationJobsIn(group).single { statusOf(it) == JobEntity.PENDING }
        assertEquals(ROTATION_ORIGINATOR, declineReason(requeued, rotator), "the failed rotator is excluded again")
        assertNull(declineReason(requeued, thirdOwner), "the owner who asked is not")
        assertEquals(requeued, claimJob(thirdOwner, group))
        commit(rotator, group, rotator, coOwner).then().statusCode(403).body("code", equalTo("rotation_excluded"))

        requeueAsOwner(thirdOwner, group).then().statusCode(409).body("code", equalTo("rotation_not_failed"))
        requeueAsOwner(member, group).then().statusCode(403).body("code", equalTo("not_scope_owner"))
        assertEquals(jobsBefore + 1, rotationJobsIn(group).size, "a refused re-queue writes nothing")
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    /** A rotation of this fixture's account. The two document revisions default to 0 — what an account that never
     *  wrote either document sends; a test that seeds a document passes the revision it seeded (or a stale one). */
    private fun rotate(
        userId: String,
        accessToken: String = sessions.issue(userId).accessToken,
        signature: String = PROOF_KEY.sign(rotationStatement()),
        pinsRevision: Long = 0,
        registerRevision: Long = 0,
        pinsReplaceAll: Boolean = true,
    ) = given().header("Authorization", "Bearer $accessToken").contentType(ContentType.JSON)
        .body(rotationBody(signature, pinsRevision, registerRevision, pinsReplaceAll)).`when`().post("/api/account/rotation")

    private fun rotationBody(
        signature: String = PROOF_KEY.sign(rotationStatement()),
        pinsRevision: Long = 0,
        registerRevision: Long = 0,
        pinsReplaceAll: Boolean = true,
    ) = """
        {"identityPublicKey":"$NEW_IDENTITY_PUB","wrappedIdentityPrivateKey":"$NEW_PRIV",
         "passwordWrap":{"wrappedKey":"$NEW_PW_WRAP","kdfParams":"$KDF"},
         "recoveryWrap":{"wrappedKey":"$NEW_REC_WRAP","kdfParams":"$KDF"},
         "secretKeyProofSignature":"$signature",
         "identityPins":{"revision":$pinsRevision,"head":"$NEW_PINS","put":[{"pinId":"$NEW_PIN_ID","entry":"$NEW_ENTRY"}],"remove":[],"replaceAll":$pinsReplaceAll},
         "deviceRegister":{"blob":"$NEW_REGISTER","revision":$registerRevision}}
    """.trimIndent()

    /** What a legitimate client signs for this fixture's rotation: from the seeded identity to the new one. */
    private fun rotationStatement() = SecretKeyProof.rotationStatement(
        currentIdentityPublicKey = OLD_IDENTITY_PUB,
        identityPublicKey = NEW_IDENTITY_PUB,
        wrappedIdentityPrivateKey = NEW_PRIV,
        passwordWrap = SecretKeyProof.Wrap(NEW_PW_WRAP, KDF),
        recoveryWrap = SecretKeyProof.Wrap(NEW_REC_WRAP, KDF),
    )

    private fun claim(userId: String, tag: String, scope: SeededScope? = null) =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"capabilityTags":["$tag"]${scope?.let { ""","scopeId":"${it.scopeId}"""" } ?: ""}}""")
            .`when`().post("/api/tasks/claim")

    private fun claimJob(userId: String, scope: SeededScope, tag: String = CapabilityTags.SCOPE_ROTATION): String =
        claim(userId, tag, scope).then().statusCode(200).extract().path("jobId")

    private fun fail(userId: String, jobId: String): String =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON).body("{}")
            .`when`().post("/api/tasks/$jobId/fail").then().statusCode(200).extract().path("status")

    private fun complete(userId: String, jobId: String) =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON).body("{}")
            .`when`().post("/api/tasks/$jobId/complete")

    /** Dead-letter [scope]'s live rotation job through the real path: claim and fail it until it is `failed`. */
    private fun deadLetter(worker: String, scope: SeededScope) {
        repeat(MAX_ATTEMPTS) { if (fail(worker, claimJob(worker, scope)) == "failed") return }
        error("the job did not dead-letter after $MAX_ATTEMPTS failed claims")
    }

    /** A well-formed rotation commit at [scope]'s next epoch, granting [grantees]. */
    private fun commit(userId: String, scope: SeededScope, vararg grantees: String) =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body(
                """{"epoch":${epochOf(scope) + 1},"wrappedPrev":"$ROT_PREV","grants":[${
                    grantees.joinToString(",") { """{"userId":"$it","wrappedGrant":"$ROTATOR_GRANT",${TestAdmission.SIGNATURE_JSON}}""" }
                }],${TestAdmission.openJson(scope.scopeId)}}""",
            )
            .`when`().post("/api/scopes/${scope.scopeId}/epochs")

    private fun setRole(caller: String, scope: SeededScope, target: String, role: String) {
        given().header("Authorization", bearer(caller)).contentType(ContentType.JSON).body("""{"role":"$role"}""")
            .`when`().patch("/api/scopes/${scope.scopeId}/members/$target").then().statusCode(200)
    }

    private fun states(userId: String) =
        given().header("Authorization", bearer(userId)).`when`().get("/api/account/rotation/scopes")

    /** The state [userId]'s own read serves for [scope], or null when it lists no such scope. */
    private fun stateOf(userId: String, scope: SeededScope): String? =
        states(userId).then().statusCode(200).extract().path("find { it.scopeId == '${scope.scopeId}' }.state")

    private fun requeue(userId: String, scope: SeededScope) =
        given().header("Authorization", bearer(userId)).`when`().post("/api/account/rotation/scopes/${scope.scopeId}/requeue")

    private fun requeueAsOwner(userId: String, scope: SeededScope) =
        given().header("Authorization", bearer(userId)).`when`().post("/api/scopes/${scope.scopeId}/rotation/requeue")

    /** The `compromiseRotation` of [userId]'s own row for [scope] in `GET /api/scopes` (the tenant's Host). */
    private fun ownerRow(userId: String, tenant: SeededScope, scope: SeededScope): String? =
        given().header("Authorization", bearer(userId)).header("X-Forwarded-Host", tenant.domain)
            .`when`().get("/api/scopes").then().statusCode(200)
            .body("find { it.id == '${scope.scopeId}' }.id", equalTo(scope.scopeId))
            .extract().path("find { it.id == '${scope.scopeId}' }.compromiseRotation")

    private fun seedAccount(): String {
        val userId = seedUser(
            identityPublicKey = OLD_IDENTITY_PUB, wrappedIdentityPrivateKey = OLD_PRIV,
            secretKeyProofPublicKey = PROOF_KEY.publicKey,
        )
        seedWrap(userId, "PASSWORD", OLD_PW_WRAP)
        seedWrap(userId, "RECOVERY", OLD_REC_WRAP)
        return userId
    }

    private fun seedWrap(userId: String, method: String, wrapped: String) = QuarkusTransaction.requiringNew().run {
        MasterKeyWrapEntity().apply {
            id = UUID.randomUUID().toString(); this.userId = userId; this.method = method
            wrappedKey = wrapped; kdfParams = KDF
        }.persist()
    }

    /** A scope plus the tenant it hangs off. The TENANT root scope is keyed `scopeId == tenantId` (cr-0026);
     *  a PERSONAL one gets its own id, which is why the two ids are carried separately — a membership's
     *  `tenant_id` is a foreign key, and passing the scope id there works only for the tenant root. */
    private data class SeededScope(val scopeId: String, val tenantId: String) {
        /** The tenant's host — what a read of the caller's scopes resolves the tenant from. */
        val domain get() = "rot-$tenantId.example.com"
    }

    private fun seedTenantScope(): SeededScope = seedScope(ScopeEntity.TENANT)

    private fun seedPersonalScope(): SeededScope = seedScope(ScopeEntity.PERSONAL)

    private fun seedScope(kind: String): SeededScope {
        val tenantId = UUID.randomUUID().toString()
        val scopeId = if (kind == ScopeEntity.TENANT) tenantId else UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            TenantEntity().apply {
                id = tenantId; name = "Fam"; domain = SeededScope(scopeId, tenantId).domain
                ownerEmail = "o-$tenantId@example.com"; status = TenantEntity.ACTIVE
            }.persist()
            ScopeEntity().apply {
                id = scopeId; this.tenantId = tenantId; this.kind = kind
            }.persist()
        }
        return SeededScope(scopeId, tenantId)
    }

    private fun seedGrantedMembership(userId: String, scope: SeededScope) = QuarkusTransaction.requiringNew().run {
        MembershipEntity().apply {
            id = UUID.randomUUID().toString(); this.userId = userId; tenantId = scope.tenantId
            scopeId = scope.scopeId; role = MembershipEntity.OWNER
            wrappedGrant = "bWVtYmVyLWdyYW50"; createdAt = Instant.now()
        }.persist()
    }

    private fun seedPendingConsentMembership(userId: String, scope: SeededScope) = QuarkusTransaction.requiringNew().run {
        MembershipEntity().apply {
            id = UUID.randomUUID().toString(); this.userId = userId; tenantId = scope.tenantId
            scopeId = scope.scopeId; role = MembershipEntity.MEMBER; wrappedGrant = null
            pendingConsent = true; createdAt = Instant.now()
        }.persist()
    }

    private fun seedRotationJob(scope: SeededScope): String {
        val scopeId = scope.scopeId
        val jobId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            JobEntity().apply {
                id = jobId; this.scopeId = scopeId; capabilityTag = CapabilityTags.SCOPE_ROTATION
                resourceRef = scopeId; status = JobEntity.PENDING; attempts = 0; maxAttempts = 3
                createdAt = Instant.now()
            }.persist()
        }
        return jobId
    }

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

    private fun rotationJobIn(scope: SeededScope): String? = QuarkusTransaction.requiringNew().call<String?> {
        JobEntity.find("scopeId = ?1 and capabilityTag = ?2", scope.scopeId, CapabilityTags.SCOPE_ROTATION)
            .firstResult()?.id
    }

    /** Every rotation job of [scope], whatever its status, oldest first. */
    private fun rotationJobsIn(scope: SeededScope): List<String> = QuarkusTransaction.requiringNew().call {
        JobEntity.find("scopeId = ?1 and capabilityTag = ?2 order by createdAt", scope.scopeId, CapabilityTags.SCOPE_ROTATION)
            .list().map { it.id }
    }

    private fun statusOf(jobId: String): String = QuarkusTransaction.requiringNew().call { JobEntity.findById(jobId)!!.status }

    private fun declineReason(jobId: String, workerId: String): String? = QuarkusTransaction.requiringNew().call<String?> {
        JobDeclineEntity.find("jobId = ?1 and workerId = ?2", jobId, workerId).firstResult()?.reason
    }

    private fun expireLease(jobId: String) = QuarkusTransaction.requiringNew().run {
        JobEntity.update("leaseExpiresAt = ?1 where id = ?2", Instant.now().minusSeconds(60), jobId)
    }

    private fun epochOf(scope: SeededScope): Int = TestAdmission.epochOf(scope.scopeId)

    private fun chainLinks(scope: SeededScope): Long =
        QuarkusTransaction.requiringNew().call { ScopeEpochEntity.count("scopeId", scope.scopeId) }

    /** A GROUP in [tenant] that every one of [owners] owns and holds the grant of. */
    private fun seedGroup(tenant: SeededScope, vararg owners: String): SeededScope {
        val group = SeededScope(UUID.randomUUID().toString(), tenant.tenantId)
        QuarkusTransaction.requiringNew().run {
            ScopeEntity().apply {
                id = group.scopeId; tenantId = tenant.tenantId; kind = ScopeEntity.GROUP
                encryptedName = "bmFtZQ=="
            }.persist()
        }
        owners.forEach { seedMembership(it, group, MembershipEntity.OWNER) }
        return group
    }

    private fun seedMembership(userId: String, scope: SeededScope, role: String, grant: String? = "bWVtYmVyLWdyYW50") =
        QuarkusTransaction.requiringNew().run {
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); this.userId = userId; tenantId = scope.tenantId
                scopeId = scope.scopeId; this.role = role; wrappedGrant = grant; createdAt = Instant.now()
            }.persist()
        }

    private fun <T> field(userId: String, read: (VeyqUser) -> T): T? =
        QuarkusTransaction.requiringNew().call<T?> { VeyqUser.findById(userId)?.let(read) }

    private fun wrapOf(userId: String, method: String): String? = QuarkusTransaction.requiringNew().call<String?> {
        MasterKeyWrapEntity.find("userId = ?1 and method = ?2", userId, method).firstResult()?.wrappedKey
    }

    /** A store at `revision`: its head and one entry, as a device wrote them under the OLD Master Key. */
    private fun seedPins(userId: String, blob: String, revision: Long) = QuarkusTransaction.requiringNew().run {
        IdentityPinsEntity().apply {
            accountId = userId; sealedBlob = blob; this.revision = revision
        }.persist()
        IdentityPinEntryEntity().apply {
            accountId = userId; pinId = OLD_PIN_ID; sealedEntry = OLD_ENTRY
        }.persist()
    }

    /** The stored entries as `pinId to sealed entry`. */
    private fun pinEntries(userId: String): Map<String, String> = QuarkusTransaction.requiringNew().call<Map<String, String>> {
        IdentityPinEntryEntity.list("accountId", userId).associate { it.pinId to it.sealedEntry }
    }

    private fun seedRegister(userId: String, blob: String, revision: Long) = QuarkusTransaction.requiringNew().run {
        DeviceRegisterEntity().apply {
            accountId = userId; sealedBlob = blob; this.revision = revision
        }.persist()
    }

    /** The stored pin store as `blob to revision`, or null when the account has no row. */
    private fun pinsRow(userId: String): Pair<String?, Long>? =
        QuarkusTransaction.requiringNew().call<Pair<String?, Long>?> {
            IdentityPinsEntity.findById(userId)?.let { it.sealedBlob to it.revision }
        }

    private fun registerRow(userId: String): Pair<String?, Long>? =
        QuarkusTransaction.requiringNew().call<Pair<String?, Long>?> {
            DeviceRegisterEntity.findById(userId)?.let { it.sealedBlob to it.revision }
        }

    private fun pairingStatus(id: String): String? =
        QuarkusTransaction.requiringNew().call<String?> { DevicePairingEntity.findById(id)?.status }

    private fun familyStatus(familyId: String): String? =
        QuarkusTransaction.requiringNew().call<String?> { SessionFamilyEntity.findById(familyId)?.status }

    /** The family an issued access token belongs to — read the way the account's own session list reads it. */
    private fun familyIdOf(userId: String, accessToken: String): String =
        sessions.listActiveFamilies(userId, accessToken).first { it.current }.id

    private companion object {
        const val KDF = "a2RmLXBhcmFtcw=="

        const val OLD_IDENTITY_PUB = "b2xkLWlkZW50aXR5LXB1Yg=="
        const val OLD_PRIV = "b2xkLXdyYXBwZWQtcHJpdg=="
        const val OLD_PW_WRAP = "b2xkLXBhc3N3b3JkLXdyYXA="
        const val OLD_REC_WRAP = "b2xkLXJlY292ZXJ5LXdyYXA="

        const val NEW_IDENTITY_PUB = "bmV3LWlkZW50aXR5LXB1Yg=="
        const val NEW_PRIV = "bmV3LXdyYXBwZWQtcHJpdg=="
        const val NEW_PW_WRAP = "bmV3LXBhc3N3b3JkLXdyYXA="
        const val NEW_REC_WRAP = "bmV3LXJlY292ZXJ5LXdyYXA="

        const val OLD_PINS = "b2xkLXBpbnM="
        const val NEW_PINS = "bmV3LXBpbnM="
        const val OLD_PIN_ID = "0101010101010101010101010101010101010101010101010101010101010101"
        const val OLD_ENTRY = "b2xkLXBpbi1lbnRyeQ=="
        const val NEW_PIN_ID = "0202020202020202020202020202020202020202020202020202020202020202"
        const val NEW_ENTRY = "bmV3LXBpbi1lbnRyeQ=="
        const val OLD_REGISTER = "b2xkLXJlZ2lzdGVy"

        const val CO_OWNER_PUB = "Y28tb3duZXItaWRlbnRpdHktcHVi"
        const val ROT_PREV = "cm90LXByZXY="
        const val ROTATOR_GRANT = "cm90YXRvci1ncmFudA=="
        const val CO_OWNER_GRANT = "Y28tb3duZXItZ3JhbnQ="
        const val SIG_FOR_ROTATOR = "c2lnbmVkLWZvci10aGUtcm90YXRvcg=="
        const val SIG_FOR_CO_OWNER = "c2lnbmVkLWJ5LXRoZS1vbGQta2V5LWZvci10aGUtY28tb3duZXI="
        const val NEW_REGISTER = "bmV3LXJlZ2lzdGVy"

        /** The job's attempts budget (`veyq.tasks.max-attempts`) — every claim spends one. */
        const val MAX_ATTEMPTS = 5
        const val ROTATION_ORIGINATOR = "rotation_originator"

        /** The Secret-Key proof key every seeded account registers; [OTHER_KEY] is a Secret Key it does not hold. */
        val PROOF_KEY = TestProofKey()
        val OTHER_KEY = TestProofKey()
    }
}
