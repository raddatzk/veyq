package me.raddatz.veyq.core.account.erasure

import me.raddatz.veyq.core.adminchannel.givenAdminChannel
import me.raddatz.veyq.core.admin.instance.InstanceMailSettingsEntity
import me.raddatz.veyq.core.admin.tenant.TenantInviteEntity
import me.raddatz.veyq.core.admin.tenant.TenantTermsAcceptanceEntity
import me.raddatz.veyq.core.blob.NodeAttachmentEntity
import me.raddatz.veyq.core.crypto.AuthAttestation
import me.raddatz.veyq.core.favorites.FavoritesEntity
import me.raddatz.veyq.core.identity.AccountErasureEntity
import me.raddatz.veyq.core.identity.ModeratorEntity
import me.raddatz.veyq.core.identity.TenantProfileEntity
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.membership.TenantRoots
import me.raddatz.veyq.core.moderation.ModerationEpochEntity
import me.raddatz.veyq.core.moderation.ModerationGrantEntity
import me.raddatz.veyq.core.moderation.block.ModerationBlockEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.tenant.TenantEntity
import me.raddatz.veyq.core.testing.TestFixtures
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import me.raddatz.veyq.core.testing.RACE_ROUNDS
import me.raddatz.veyq.core.testing.race
import me.raddatz.veyq.core.account.login.SessionFamilyEntity
import me.raddatz.veyq.core.testing.TestFixtures.newUser
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Slice tests for the account erasure (uc.account.erase-account, ADR 0101): both doors, the lockout at the request, the
 * last owner's choice, the stop, and the purge after the window — run here by [AccountPurge.purge], the forced entry.
 * Every negative carries its positive control on the same predicate, and anchors on the specific code.
 */
@QuarkusTest
class AccountErasureSliceTest {

    @Inject
    lateinit var purge: AccountPurge

    @Inject
    lateinit var mail: AccountErasureMail

    // --- the request, the lockout and the purge ---

    @Test
    fun aMemberErasesTheirAccountAndThePurgeErasesWhatIsTheirsButNotTheSharedScopes() {
        val owner = seedUser()
        val tenant = seedTenant(owner = owner)
        val (u, kp) = keyedUser()
        val personal = UUID.randomUUID().toString()
        val solo = UUID.randomUUID().toString()
        val shared = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            TenantRoots.join(u, tenant)
            scope(personal, tenant, ScopeEntity.PERSONAL, personalOwner = u)
            member(u, tenant, personal, MembershipEntity.OWNER)
            scope(solo, tenant, ScopeEntity.GROUP)
            member(u, tenant, solo, MembershipEntity.OWNER)
            scope(shared, tenant, ScopeEntity.GROUP)
            member(owner, tenant, shared, MembershipEntity.OWNER)
            member(u, tenant, shared, MembershipEntity.MEMBER)
            FavoritesEntity().apply { id = "$u|$shared"; userId = u; scopeId = shared; ciphertext = "Yw==" }.persist()
            TenantProfileEntity().apply { id = UUID.randomUUID().toString(); userId = u; tenantId = tenant; username = "u${u.take(8)}" }.persist()
            accept(u, tenant)
        }
        // Its address, copied: a tenant whose owner invite went to it, and that invite, spent long ago.
        val invitedTenant = seedTenant(ownerEmail = emailOf(u))
        QuarkusTransaction.requiringNew().run {
            TenantInviteEntity().apply {
                id = UUID.randomUUID().toString(); tenantId = invitedTenant; ownerEmail = emailOf(u); tokenHash = UUID.randomUUID().toString()
                status = TenantInviteEntity.CONSUMED; expiresAt = Instant.now(); createdAt = Instant.now(); role = TenantInviteEntity.OWNER
            }.persist()
        }
        val node = TestFixtures.seedNode(shared, "FOLDER", wrappedContentKey = TestFixtures.CONTENT_KEY)
        val upload = TestFixtures.seedAttachment(shared, node, "FILE_CONTENT", uploaderUserId = u)
        val token = TestFixtures.bearer(u)
        given().header("Authorization", token).`when`().get("/api/account/email").then().statusCode(200) // control

        eraseOwn(u, kp, token).then().statusCode(200).body("dueAt", notNullValue())

        // The account stopped at once: its session, its sign-in, its memberships of shared scopes.
        given().header("Authorization", token).`when`().get("/api/account/email").then().statusCode(401)
        assertFalse(signsIn(u, kp))
        tx {
            assertNotNull(AccountErasureEntity.findById(u))
            assertNotNull(ScopeEntity.findById(personal)!!.deletedAt, "its own scope is in the scope trash")
            assertNotNull(ScopeEntity.findById(solo)!!.deletedAt, "a group it is alone in is in the scope trash")
            assertEquals(0L, MembershipEntity.count("userId = ?1 and scopeId in ?2", u, listOf(tenant, shared)))
        }

        purge.purge(u)

        tx {
            assertNull(VeyqUser.findById(u), "the account row is gone")
            assertNull(ScopeEntity.findById(personal))
            assertNull(ScopeEntity.findById(solo))
            assertNotNull(ScopeEntity.findById(shared), "a shared scope is the scope's, not the person's")
            assertEquals(u, NodeAttachmentEntity.findById(upload.attachmentId)!!.uploaderUserId, "content keeps its attribution")
            assertEquals(0L, FavoritesEntity.count("userId", u), "its favorites in the shared scope are gone")
            assertEquals(0L, TenantProfileEntity.count("userId", u))
            assertEquals(0L, MembershipEntity.count("userId", u))
            assertEquals(0L, TenantTermsAcceptanceEntity.count("userId", u), "no case names it: its terms rows go")
            assertNull(AccountErasureEntity.findById(u))
            assertEquals(0L, TenantInviteEntity.count("ownerEmail", emailOf(u)), "no invite keeps its address")
            assertEquals("", TenantEntity.findById(invitedTenant)!!.ownerEmail, "nor does the tenant its owner invite went to")
        }
    }

    @Test
    fun aMeasureOnTheAccountKeepsItsTermsRowsUntilNoCaseNamesItAnyMore() {
        val tenant = seedTenant(owner = seedUser())
        val named = seedUser()
        val control = seedUser()
        val blockId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            listOf(named, control).forEach { TenantRoots.join(it, tenant); accept(it, tenant) }
            ModerationBlockEntity().apply {
                id = blockId; kind = "MEMBER_SIGN_IN"; targetId = named; takenBy = UUID.randomUUID().toString()
                takenAt = Instant.now(); activeKey = "MEMBER_SIGN_IN:$named"
            }.persist()
        }
        val ownerToken = serverOwnerToken()
        listOf(named, control).forEach { eraseByOwner(ownerToken, emailOf(it)).then().statusCode(200) }
        purge.purge(named)
        purge.purge(control)
        tx {
            assertNull(VeyqUser.findById(named))
            assertEquals(1L, TenantTermsAcceptanceEntity.count("userId", named), "a measure in force keeps the evidence")
            assertEquals(0L, TenantTermsAcceptanceEntity.count("userId", control), "control: no case, no rows")
        }
        // Still in force: the sweep keeps them.
        QuarkusTransaction.requiringNew().run { purge.sweepKeptTerms(Instant.now()) }
        tx { assertEquals(1L, TenantTermsAcceptanceEntity.count("userId", named)) }
        // Lifted, with no case behind it: nothing names the account any more, and the sweep deletes them.
        QuarkusTransaction.requiringNew().run {
            ModerationBlockEntity.findById(blockId)!!.apply { activeKey = null; liftedAt = Instant.now() }
        }
        QuarkusTransaction.requiringNew().run { purge.sweepKeptTerms(Instant.now()) }
        tx { assertEquals(0L, TenantTermsAcceptanceEntity.count("userId", named)) }
    }

    @Test
    fun signInIsRefusedDuringTheWindowAndAdmittedAgainAfterAStop() {
        val (u, kp) = keyedUser()
        assertTrue(signsIn(u, kp), "control: it signs in before")
        val ownerToken = serverOwnerToken()
        eraseByOwner(ownerToken, emailOf(u)).then().statusCode(200).body("requestedBy", equalTo("SERVER_OWNER"))
        assertFalse(signsIn(u, kp), "pending: refused as a wrong password is")
        eraseByOwner(ownerToken, emailOf(u)).then().statusCode(409).body("code", equalTo("account_erasure_pending"))
        val listed = givenAdminChannel().header("Authorization", "Bearer $ownerToken")
            .`when`().get("/api/instance/accounts/erasures").then().statusCode(200)
            .extract().path<List<String>>("erasures.address")
        assertTrue(emailOf(u) in listed)

        stopByOwner(ownerToken, emailOf(u)).then().statusCode(204)
        assertTrue(signsIn(u, kp), "stopped: it signs in again")
        stopByOwner(ownerToken, emailOf(u)).then().statusCode(404).body("code", equalTo("account_erasure_not_found"))
    }

    @Test
    fun theServerOwnersOwnAccountIsRefusedAtBothDoors() {
        val (owner, kp) = keyedUser(serverRole = "SERVER_OWNER")
        val token = TestFixtures.bearer(owner)
        eraseOwn(owner, kp, token).then().statusCode(409).body("code", equalTo("server_owner_account"))
        eraseByOwner(token.removePrefix("Bearer "), emailOf(owner)).then().statusCode(409).body("code", equalTo("server_owner_account"))
        tx { assertNull(AccountErasureEntity.findById(owner)) }
        // Control: a member at the same door is erased.
        val (member, mkp) = keyedUser()
        eraseOwn(member, mkp, TestFixtures.bearer(member)).then().statusCode(200)
    }

    @Test
    fun aWrongProofOrASpentChallengeErasesNothing() {
        val (u, kp) = keyedUser()
        val token = TestFixtures.bearer(u)
        val nonce = challenge(emailOf(u))
        eraseOwnWith(token, nonce, sign(otherKeyPair(), AuthAttestation.eraseAccountStatement(u, nonce)!!))
            .then().statusCode(403).body("code", equalTo("attestation_failed"))
        // The challenge was spent by the failed attempt: a right proof over it is no proof now.
        eraseOwnWith(token, nonce, sign(kp, AuthAttestation.eraseAccountStatement(u, nonce)!!))
            .then().statusCode(403).body("code", equalTo("attestation_failed"))
        tx { assertNull(AccountErasureEntity.findById(u)) }
        given().header("Authorization", token).`when`().get("/api/account/email").then().statusCode(200)
        // Control: a fresh challenge and the right key.
        eraseOwn(u, kp, token).then().statusCode(200)
    }

    // --- the last owner (ADR 0101 decision 9, owner Q18) ---

    @Test
    fun theLastOwnerWithMembersChoosesAndNoChoiceIsRefused() {
        val (u, kp) = keyedUser()
        val other = seedUser()
        val tenant = seedTenant(owner = u)
        QuarkusTransaction.requiringNew().run { TenantRoots.join(other, tenant) }
        val token = TestFixtures.bearer(u)

        given().header("Authorization", token).`when`().get("/api/account/erasure").then().statusCode(200)
            .body("tenants.find { it.tenantId == '$tenant' }.otherMemberIds", equalTo(listOf(other)))
        eraseOwn(u, kp, token).then().statusCode(409).body("code", equalTo("last_owner_choice_required"))
        tx {
            assertNull(AccountErasureEntity.findById(u), "a refusal writes nothing")
            assertEquals(MembershipEntity.OWNER, roleOf(u, tenant))
        }
        // A new owner who is no member of it is no choice either.
        eraseOwn(u, kp, token, """[{"tenantId":"$tenant","newOwnerId":"${seedUser()}"}]""")
            .then().statusCode(409).body("code", equalTo("last_owner_choice_required"))

        eraseOwn(u, kp, token, """[{"tenantId":"$tenant","newOwnerId":"$other"}]""").then().statusCode(200)
        tx {
            assertEquals(MembershipEntity.OWNER, roleOf(other, tenant), "the member it named owns the tenant now")
            assertEquals(TenantEntity.ACTIVE, TenantEntity.findById(tenant)!!.status)
        }
    }

    @Test
    fun theLastOwnerMayDeleteTheTenantAlongAndASoleMembersTenantGoesWithoutAChoice() {
        val (u, kp) = keyedUser()
        val withOthers = seedTenant(owner = u)
        val alone = seedTenant(owner = u)
        QuarkusTransaction.requiringNew().run { TenantRoots.join(seedUser(), withOthers) }
        eraseOwn(u, kp, TestFixtures.bearer(u), """[{"tenantId":"$withOthers","newOwnerId":null}]""").then().statusCode(200)
        tx {
            assertEquals(TenantEntity.PENDING_DELETION, TenantEntity.findById(withOthers)!!.status)
            assertEquals(TenantEntity.PENDING_DELETION, TenantEntity.findById(alone)!!.status)
            assertNotNull(TenantEntity.findById(alone)!!.deletionDueAt)
        }
    }

    @Test
    fun theServerOwnersDoorNeverRefusesALastOwnerAndLeavesTheTenantOwnerless() {
        val u = seedUser()
        val tenant = seedTenant(owner = u)
        QuarkusTransaction.requiringNew().run { TenantRoots.join(seedUser(), tenant) }
        val ownerToken = serverOwnerToken()
        eraseByOwner(ownerToken, emailOf(u)).then().statusCode(200)
        givenAdminChannel().header("Authorization", "Bearer $ownerToken").`when`().get("/api/tenants")
            .then().statusCode(200)
            .body("find { it.tenantId == '$tenant' }.ownerless", equalTo(true))
            .body("find { it.tenantId == '$tenant' }.status", equalTo("ACTIVE"))
    }

    @Test
    fun theLastHolderOfTheModerationKeyIsRefusedUntilAnotherHoldsIt() {
        val (u, kp) = keyedUser()
        val other = seedUser()
        val epoch = QuarkusTransaction.requiringNew().call {
            val next = (ModerationEpochEntity.listAll().maxOfOrNull { it.epoch } ?: 0) + 1
            ModerationEpochEntity().apply { this.epoch = next; kemPublicKey = "a2Vt"; signPublicKey = "c2lnbg=="; reason = "BY_HAND" }.persist()
            moderator(u, next)
            next
        }
        try {
            val token = TestFixtures.bearer(u)
            eraseOwn(u, kp, token).then().statusCode(409).body("code", equalTo("moderation_last_holder"))
            tx { assertTrue(ModeratorEntity.isModerator(u)) }
            QuarkusTransaction.requiringNew().run { moderator(other, epoch) }
            eraseOwn(u, kp, token).then().statusCode(200)
            tx {
                assertFalse(ModeratorEntity.isModerator(u), "it left the roster as a step-down does")
                assertEquals(0L, ModerationGrantEntity.count("moderatorId", u))
                assertNotNull(ModerationEpochEntity.findById(epoch)!!.rotationDueAt, "the epoch is due for rotation")
            }
        } finally {
            QuarkusTransaction.requiringNew().run {
                ModerationGrantEntity.delete("epoch", epoch)
                ModeratorEntity.delete("accountId in ?1", listOf(u, other))
                ModerationEpochEntity.deleteById(epoch)
            }
        }
    }

    // --- the mails (ADR 0101 decisions 2 and 10), over a real SMTP server in the JVM ---

    @Test
    fun theErasureMailIsRetriedUntilItGoesOutAndADeletionMailsEveryMember(): Unit =
        GreenMail(ServerSetup(MAIL_PORT, "127.0.0.1", "smtp")).let { box ->
            box.start()
            try {
                QuarkusTransaction.requiringNew().run { InstanceMailSettingsEntity.deleteAll() }
                val u = seedUser()
                eraseByOwner(serverOwnerToken(), emailOf(u)).then().statusCode(200)
                tx { assertNull(AccountErasureEntity.findById(u)!!.mailedAt, "no instance mail: it is owed") }
                assertEquals(0, box.receivedMessages.count { it.allRecipients.single().toString() == emailOf(u) })

                QuarkusTransaction.requiringNew().run {
                    InstanceMailSettingsEntity().apply {
                        host = "127.0.0.1"; port = MAIL_PORT; fromAddress = "veyq@example.com"; tls = false
                    }.persist()
                }
                assertTrue(mail.retryDue(Instant.now()) >= 1, "the purge's tick sends what it owed")
                tx { assertNotNull(AccountErasureEntity.findById(u)!!.mailedAt) }
                assertEquals(0, mail.retryDue(Instant.now()), "control: a mail that went out is not sent twice")
                val erasureMail = box.receivedMessages.single { it.allRecipients.single().toString() == emailOf(u) }
                assertTrue(erasureMail.subject.startsWith("veyq: your account is being erased"))

                // A tenant deletion tells every member, and only its members.
                val member = seedUser()
                val owner = seedUser()
                val tenant = seedTenant(owner = owner)
                QuarkusTransaction.requiringNew().run { TenantRoots.join(member, tenant) }
                givenAdminChannel().header("Authorization", "Bearer ${serverOwnerToken()}").contentType(ContentType.JSON)
                    .body("""{"domain":"t-$tenant.example.com"}""").`when`().post("/api/tenants/$tenant/deletion")
                    .then().statusCode(200)
                val deletionMails = box.receivedMessages.filter { it.allRecipients.single().toString() in setOf(emailOf(owner), emailOf(member)) }
                assertEquals(2, deletionMails.size, "the owner and the member")
                assertTrue(deletionMails.all { it.subject.startsWith("veyq: a space you belong to is being deleted") })
            } finally {
                QuarkusTransaction.requiringNew().run { InstanceMailSettingsEntity.deleteAll() }
                box.stop()
            }
        }

    // --- the race: a sign-in that overlaps the request ---

    /**
     * The request and a sign-in both take the account row's lock, so whichever wins, no session outlives the request:
     * a sign-in before it is revoked by it, one after it is refused. Raced [RACE_ROUNDS] times on fresh accounts.
     */
    @Test
    fun noSessionOutlivesAnErasureASignInRacedAgainst() {
        repeat(RACE_ROUNDS) {
            val (u, kp) = keyedUser()
            val token = TestFixtures.bearer(u)
            val loginNonce = challenge(emailOf(u))
            val eraseNonce = challenge(emailOf(u))
            val eraseSig = sign(kp, AuthAttestation.eraseAccountStatement(u, eraseNonce)!!)
            val loginSig = sign(kp, Base64.getDecoder().decode(loginNonce))
            val (erased, _) = race(
                { eraseOwnWith(token, eraseNonce, eraseSig).statusCode },
                {
                    given().contentType(ContentType.JSON)
                        .body("""{"email":"${emailOf(u)}","nonce":"$loginNonce","signature":"$loginSig"}""")
                        .`when`().post("/api/login/verify").statusCode
                },
            )
            assertEquals(200, erased)
            tx { assertEquals(0L, SessionFamilyEntity.count("userId = ?1 and status = ?2", u, SessionFamilyEntity.ACTIVE)) }
        }
    }

    // --- helpers ---

    private fun keyedUser(serverRole: String = "MEMBER"): Pair<String, KeyPair> {
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val id = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            newUser(id = id, serverRole = serverRole, authPublicKey = b64(raw(kp))).persist()
        }
        return id to kp
    }

    private fun otherKeyPair(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    private fun emailOf(userId: String): String = "u-$userId@example.com"

    private fun serverOwnerToken(): String = TestFixtures.bearer(seedUser(serverRole = "SERVER_OWNER")).removePrefix("Bearer ")

    private fun challenge(email: String): String =
        given().contentType(ContentType.JSON).body("""{"email":"$email"}""")
            .`when`().post("/api/login/challenge").then().statusCode(200).extract().path("nonce")

    private fun signsIn(userId: String, kp: KeyPair): Boolean {
        val nonce = challenge(emailOf(userId))
        return given().contentType(ContentType.JSON)
            .body("""{"email":"${emailOf(userId)}","nonce":"$nonce","signature":"${sign(kp, Base64.getDecoder().decode(nonce))}"}""")
            .`when`().post("/api/login/verify").statusCode == 200
    }

    private fun eraseOwn(userId: String, kp: KeyPair, token: String, choices: String = "[]"): Response {
        val nonce = challenge(emailOf(userId))
        return eraseOwnWith(token, nonce, sign(kp, AuthAttestation.eraseAccountStatement(userId, nonce)!!), choices)
    }

    private fun eraseOwnWith(token: String, nonce: String, signature: String, choices: String = "[]"): Response =
        given().header("Authorization", token).contentType(ContentType.JSON)
            .body("""{"nonce":"$nonce","attestationSignature":"$signature","lastOwnerChoices":$choices}""")
            .`when`().post("/api/account/erasure")

    private fun eraseByOwner(ownerToken: String, address: String): Response =
        givenAdminChannel().header("Authorization", "Bearer $ownerToken").contentType(ContentType.JSON)
            .body("""{"address":"$address"}""").`when`().post("/api/instance/accounts/erasures")

    private fun stopByOwner(ownerToken: String, address: String): Response =
        givenAdminChannel().header("Authorization", "Bearer $ownerToken").contentType(ContentType.JSON)
            .body("""{"address":"$address"}""").`when`().post("/api/instance/accounts/erasures/stop")

    private fun sign(kp: KeyPair, message: ByteArray): String {
        val sig = Signature.getInstance("Ed25519")
        sig.initSign(kp.private)
        sig.update(message)
        return b64(sig.sign())
    }

    private fun raw(kp: KeyPair): ByteArray = kp.public.encoded.let { it.copyOfRange(it.size - 32, it.size) }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun scope(id: String, tenantId: String, kind: String, personalOwner: String? = null) {
        ScopeEntity().apply { this.id = id; this.tenantId = tenantId; this.kind = kind; this.personalOwner = personalOwner }.persist()
    }

    private fun member(userId: String, tenantId: String, scopeId: String, role: String) {
        MembershipEntity().apply {
            id = UUID.randomUUID().toString(); this.userId = userId; this.tenantId = tenantId; this.scopeId = scopeId
            this.role = role; wrappedGrant = TestFixtures.GRANT; createdAt = Instant.now()
        }.persist()
    }

    private fun accept(userId: String, tenantId: String) {
        TenantTermsAcceptanceEntity().apply {
            id = UUID.randomUUID().toString(); this.userId = userId; this.tenantId = tenantId; acceptedRevision = 1
            acceptedAt = Instant.now()
        }.persist()
    }

    private fun moderator(accountId: String, epoch: Int) {
        ModeratorEntity().apply {
            this.accountId = accountId; appointedBy = UUID.randomUUID().toString(); appointedAt = Instant.now()
            acceptedAt = Instant.now()
        }.persist()
        ModerationGrantEntity().apply {
            id = UUID.randomUUID().toString(); moderatorId = accountId; this.epoch = epoch; sealedGrant = "Zw=="
            deliveredBy = accountId
        }.persist()
    }

    private fun roleOf(userId: String, tenantId: String): String? =
        MembershipEntity.find("userId = ?1 and scopeId = ?2", userId, tenantId).firstResult()?.role

    private fun <T> tx(block: () -> T): T = QuarkusTransaction.requiringNew().call(block)

    private companion object {
        // This class's own SMTP port (TestMailbox says how to find the taken ones).
        const val MAIL_PORT = 13950
    }
}
