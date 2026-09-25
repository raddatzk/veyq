package com.veyq.app.features.account.rotation

import com.veyq.app.features.account.login.SessionFamilyEntity
import com.veyq.app.features.account.login.SessionService
import com.veyq.app.features.account.pairing.DevicePairingEntity
import com.veyq.app.shared.tenant.TenantEntity
import com.veyq.app.shared.scope.ScopeEntity
import com.veyq.app.features.tasks.CapabilityTags
import com.veyq.app.features.tasks.JobEntity
import com.veyq.app.shared.identity.MasterKeyWrapEntity
import com.veyq.app.shared.identity.VeyqUser
import com.veyq.app.shared.membership.MembershipEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.hasItem
import org.hamcrest.CoreMatchers.not
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
 * The cold-credential gate (Decision 2) has no assertion here on purpose. It is client-side by construction —
 * the server holds no evidence of which wrap opened a vault — so a backend test asserting it would be testing
 * a check that does not and must not exist on this side. `web/` owns that proof.
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
        assertEquals(NEW_REC_TAG, field(user) { it.recoveryMasterKeyTag }, "recovery would otherwise prove possession of a key the account no longer has")

        assertEquals(NEW_PW_WRAP, wrapOf(user, "PASSWORD"), "the floor holds the new Master Key")
        assertEquals(NEW_REC_WRAP, wrapOf(user, "RECOVERY"), "so does the withheld recovery wrap — the Emergency Kit stays valid")
        // The whole point of the operation: a device-held KEK cannot be rewritten from here, so the wrap that
        // the granted device can still open must not survive holding the OLD Master Key.
        assertNull(wrapOf(user, "DEVICE"), "a trusted-device wrap cannot be rewritten, so it goes")
        assertNull(wrapOf(user, "DEVICE_KEY"), "and the password-less one especially — it needs no password at all")
        assertNull(wrapOf(user, "PASSKEY"), "every non-cold method goes; the survivor list is a whitelist")
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
        // The coalescing trap: origination skips a scope that already carries a live rotation job (a removal cut
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
        assertEquals(OLD_REC_TAG, field(user) { it.recoveryMasterKeyTag })
        assertEquals(OLD_PW_WRAP, wrapOf(user, "PASSWORD"), "the floor still opens the Master Key it always did")
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
             "recoveryMasterKeyTag":"$NEW_REC_TAG"}
        """.trimIndent()
        given().header("Authorization", "Bearer ${sessions.issue(user).accessToken}")
            .contentType(ContentType.JSON).body(body)
            .`when`().post("/api/account/rotation").then().statusCode(400)

        assertEquals(OLD_IDENTITY_PUB, field(user) { it.identityPublicKey }, "a rejected request writes nothing")
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    private fun rotate(userId: String, accessToken: String = sessions.issue(userId).accessToken) =
        given().header("Authorization", "Bearer $accessToken").contentType(ContentType.JSON)
            .body(rotationBody()).`when`().post("/api/account/rotation")

    private fun rotationBody() = """
        {"identityPublicKey":"$NEW_IDENTITY_PUB","wrappedIdentityPrivateKey":"$NEW_PRIV",
         "passwordWrap":{"wrappedKey":"$NEW_PW_WRAP","kdfParams":"$KDF"},
         "recoveryWrap":{"wrappedKey":"$NEW_REC_WRAP","kdfParams":"$KDF"},
         "recoveryMasterKeyTag":"$NEW_REC_TAG"}
    """.trimIndent()

    private fun claim(userId: String, tag: String) =
        given().header("Authorization", "Bearer ${sessions.issue(userId).accessToken}").contentType(ContentType.JSON)
            .body("""{"capabilityTags":["$tag"]}""").`when`().post("/api/tasks/claim")

    private fun seedAccount(): String {
        val userId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            VeyqUser().apply {
                id = userId; email = "rot-$userId@example.com"; serverRole = "MEMBER"; suiteId = "v1"
                identityPublicKey = OLD_IDENTITY_PUB; wrappedIdentityPrivateKey = OLD_PRIV
                authPublicKey = "YXV0aC1wdWJsaWMta2V5"; recoveryMasterKeyTag = OLD_REC_TAG
                createdAt = Instant.now()
            }.persist()
        }
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
    private data class SeededScope(val scopeId: String, val tenantId: String)

    private fun seedTenantScope(): SeededScope = seedScope(ScopeEntity.TENANT)

    private fun seedPersonalScope(): SeededScope = seedScope(ScopeEntity.PERSONAL)

    private fun seedScope(kind: String): SeededScope {
        val tenantId = UUID.randomUUID().toString()
        val scopeId = if (kind == ScopeEntity.TENANT) tenantId else UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            TenantEntity().apply {
                id = tenantId; name = "Fam"; domain = "rot-$tenantId.example.com"
                ownerEmail = "o-$tenantId@example.com"; status = TenantEntity.ACTIVE; createdAt = Instant.now()
            }.persist()
            ScopeEntity().apply {
                id = scopeId; this.tenantId = tenantId; this.kind = kind; createdAt = Instant.now()
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

    private fun <T> field(userId: String, read: (VeyqUser) -> T): T? =
        QuarkusTransaction.requiringNew().call<T?> { VeyqUser.findById(userId)?.let(read) }

    private fun wrapOf(userId: String, method: String): String? = QuarkusTransaction.requiringNew().call<String?> {
        MasterKeyWrapEntity.find("userId = ?1 and method = ?2", userId, method).firstResult()?.wrappedKey
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
        const val OLD_REC_TAG = "b2xkLXJlY292ZXJ5LXRhZw=="

        const val NEW_IDENTITY_PUB = "bmV3LWlkZW50aXR5LXB1Yg=="
        const val NEW_PRIV = "bmV3LXdyYXBwZWQtcHJpdg=="
        const val NEW_PW_WRAP = "bmV3LXBhc3N3b3JkLXdyYXA="
        const val NEW_REC_WRAP = "bmV3LXJlY292ZXJ5LXdyYXA="
        const val NEW_REC_TAG = "bmV3LXJlY292ZXJ5LXRhZw=="
    }
}
