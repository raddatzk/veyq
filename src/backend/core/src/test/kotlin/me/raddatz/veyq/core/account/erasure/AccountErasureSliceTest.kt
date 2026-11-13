package me.raddatz.veyq.core.account.erasure

import me.raddatz.veyq.core.adminchannel.givenAdminChannel
import me.raddatz.veyq.core.admin.instance.InstanceMailSettingsEntity
import me.raddatz.veyq.core.admin.tenant.TenantInviteEntity
import me.raddatz.veyq.core.admin.tenant.TenantStore
import me.raddatz.veyq.core.crypto.Tokens
import me.raddatz.veyq.core.membership.TestInviteKey
import me.raddatz.veyq.core.admin.tenant.TenantTermsAcceptanceEntity
import me.raddatz.veyq.core.blob.NodeAttachmentEntity
import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.crypto.AuthAttestation
import me.raddatz.veyq.core.account.identitypins.IdentityPinEntryEntity
import me.raddatz.veyq.core.account.identitypins.IdentityPinsEntity
import me.raddatz.veyq.core.favorites.FavoritesEntity
import me.raddatz.veyq.core.identity.AccountErasureEntity
import me.raddatz.veyq.core.identity.ModeratorEntity
import me.raddatz.veyq.core.identity.TenantProfileEntity
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.membership.TenantRoots
import me.raddatz.veyq.core.moderation.ModerationEpochEntity
import me.raddatz.veyq.core.moderation.ModerationGrantEntity
import me.raddatz.veyq.core.moderation.ModerationHandler
import me.raddatz.veyq.core.moderation.ModerationOutcome
import me.raddatz.veyq.core.moderation.ModerationEventEntity
import me.raddatz.veyq.core.moderation.ModerationLog
import me.raddatz.veyq.core.moderation.ModerationTestKit
import me.raddatz.veyq.core.moderation.block.ModerationBlockEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.tasks.CapabilityTags
import me.raddatz.veyq.core.tasks.JobEntity
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
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import jakarta.enterprise.inject.Vetoed
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Duration
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

    @Inject
    lateinit var config: VeyqConfig

    @Inject
    lateinit var moderation: ModerationHandler

    @Inject
    lateinit var tenants: TenantStore

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
            IdentityPinsEntity().apply { accountId = u; sealedBlob = "aA=="; revision = 1 }.persist()
            IdentityPinEntryEntity().apply { accountId = u; pinId = "ab".repeat(32); sealedEntry = "ZQ==" }.persist()
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
            assertNull(IdentityPinsEntity.findById(u), "its pin store's head is gone")
            assertEquals(0L, IdentityPinEntryEntity.count("accountId", u), "and every entry under it (ADR 0104)")
            assertEquals(0L, TenantProfileEntity.count("userId", u))
            assertEquals(0L, MembershipEntity.count("userId", u))
            assertEquals(0L, TenantTermsAcceptanceEntity.count("userId", u), "no case names it: its terms rows go")
            assertNull(AccountErasureEntity.findById(u))
            assertEquals(0L, TenantInviteEntity.count("ownerEmail", emailOf(u)), "no invite keeps its address")
            assertEquals("", TenantEntity.findById(invitedTenant)!!.ownerEmail, "nor does the tenant its owner invite went to")
        }
    }

    @Test
    fun theLinkInvitesAnErasedAccountSignedEndAndAnotherSignersStayOpen() {
        // A link invite is bound to nobody (ADR 0129 decision 2), so only its signer ties it to the erased account: the
        // erasure ends the member invites it signed (ADR 0111 decision 1), their keys with them.
        val owner = seedUser()
        val tenant = seedTenant(owner = owner)
        val u = seedUser()
        QuarkusTransaction.requiringNew().run { TenantRoots.join(u, tenant) }
        val signedByU = TestInviteKey()
        val signedByOwner = TestInviteKey()
        tx {
            for ((key, signer) in listOf(signedByU to u, signedByOwner to owner)) {
                tenants.mintMemberInvite(tenant, null, Tokens.hash(UUID.randomUUID().toString()), Instant.now().plusSeconds(3600), key.stored(signer))
            }
        }

        eraseByOwner(serverOwnerToken(), emailOf(u)).then().statusCode(200)

        tx {
            val ended = TenantInviteEntity.findById(signedByU.inviteId)!!
            assertEquals(TenantInviteEntity.SUPERSEDED to null, ended.status to ended.invitePublicKey, "the link its signer erased ends, its key gone")
            // Control on the same statement: the link another owner signed stays open.
            assertEquals(TenantInviteEntity.ACTIVE, TenantInviteEntity.findById(signedByOwner.inviteId)!!.status)
        }
    }

    // --- the window (ADR 0101 decision 3) ---

    /**
     * The erasure is due its own window after the request — 14 days by default, not the scope trash's 30 — and the
     * purge's tick erases the account and its own scopes then, while the scope trash alone would still keep them: once
     * due, and not before. Driven through [AccountPurge.run], the hourly tick, with the clock moved by hand.
     */
    @Test
    fun theErasureIsDueItsOwnWindowAfterTheRequestAndTheTickTakesTheOwnScopesThen() {
        val window = config.account().erasure().window()
        val scopeRetention = config.scope().delete().retention()
        assertEquals(Duration.ofDays(14), window, "the default")
        assertTrue(window < scopeRetention, "control: shorter than the scope trash's retention, so the two are told apart")
        val tenant = seedTenant(owner = seedUser())
        val (u, kp) = keyedUser()
        val personal = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            TenantRoots.join(u, tenant)
            scope(personal, tenant, ScopeEntity.PERSONAL, personalOwner = u)
            member(u, tenant, personal, MembershipEntity.OWNER)
        }

        val served = Instant.parse(eraseOwn(u, kp, TestFixtures.bearer(u)).then().statusCode(200).extract().path<String>("dueAt"))

        val pending = tx { AccountErasureEntity.findById(u)!! }
        assertEquals(window, Duration.between(pending.requestedAt, pending.dueAt), "due the window after the request")
        assertTrue(Duration.between(served, pending.dueAt).abs() < Duration.ofSeconds(1), "the member is told that date")

        purge.run()
        tx {
            assertNotNull(VeyqUser.findById(u), "not due yet: the tick leaves the account")
            assertNotNull(ScopeEntity.findById(personal), "and its own scope")
        }

        // The window passes: the request, its due date and the own scope's trash marker move back by it and a minute.
        val span = window.plusMinutes(1)
        QuarkusTransaction.requiringNew().run {
            AccountErasureEntity.findById(u)!!.apply { requestedAt = requestedAt.minus(span); dueAt = dueAt.minus(span) }
            ScopeEntity.findById(personal)!!.apply { deletedAt = deletedAt!!.minus(span) }
        }
        tx {
            assertTrue(
                ScopeEntity.findById(personal)!!.deletedAt!!.plus(scopeRetention).isAfter(Instant.now()),
                "control: the scope trash alone would keep the own scope for weeks yet",
            )
        }

        purge.run()
        tx {
            assertNull(VeyqUser.findById(u), "due: the tick erases the account")
            assertNull(ScopeEntity.findById(personal), "with its own scope, at the account's date")
            assertNull(AccountErasureEntity.findById(u))
        }
    }

    /**
     * A configured window is honoured, and a request keeps the date it was given: the window is read at the request and
     * stored as its due date, so a later change moves no pending erasure.
     */
    @Test
    fun aConfiguredWindowIsHonouredAndARequestKeepsTheDateItWasGiven() {
        QuarkusMock.installMockForType(FixedWindow(config, Duration.ofDays(3)), AccountErasureWindow::class.java)
        val first = seedUser()
        eraseByOwner(serverOwnerToken(), emailOf(first)).then().statusCode(200)
        val firstPending = tx { AccountErasureEntity.findById(first)!! }
        assertEquals(Duration.ofDays(3), Duration.between(firstPending.requestedAt, firstPending.dueAt))

        QuarkusMock.installMockForType(FixedWindow(config, Duration.ofDays(20)), AccountErasureWindow::class.java)
        val second = seedUser()
        eraseByOwner(serverOwnerToken(), emailOf(second)).then().statusCode(200)
        purge.run()
        tx {
            val secondPending = AccountErasureEntity.findById(second)!!
            assertEquals(Duration.ofDays(20), Duration.between(secondPending.requestedAt, secondPending.dueAt), "the new window")
            assertEquals(firstPending.dueAt, AccountErasureEntity.findById(first)!!.dueAt, "the earlier request keeps its date")
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
        // The purge ended the measure with the account; its record, a step on no case, keeps the rows to its own date.
        val lifted = QuarkusTransaction.requiringNew().call {
            ModerationBlockEntity.findById(blockId)!!.also { assertNull(it.activeKey) }.liftedAt!!
        }
        QuarkusTransaction.requiringNew().run { purge.sweepKeptTerms(ModerationLog.retentionEnd(lifted).minusSeconds(60)) }
        tx { assertEquals(1L, TenantTermsAcceptanceEntity.count("userId", named)) }
        // Past that date nothing names the account any more, and the sweep deletes them.
        QuarkusTransaction.requiringNew().run { purge.sweepKeptTerms(ModerationLog.retentionEnd(lifted).plusSeconds(60)) }
        tx { assertEquals(0L, TenantTermsAcceptanceEntity.count("userId", named)) }
    }

    /**
     * The erasure asks the sweep's question (ADR 0101 decision 6): a step on no case lifted the day before still names the
     * account while its record is kept, so the erasure keeps the terms rows to the step's own date, not only a measure in
     * force does.
     */
    @Test
    fun aStepLiftedTheDayBeforeTheErasureKeepsTheTermsRowsToItsDate() {
        val tenant = seedTenant(owner = seedUser())
        val named = seedUser()
        val control = seedUser()
        val lifted = Instant.now().minus(Duration.ofDays(1))
        QuarkusTransaction.requiringNew().run {
            listOf(named, control).forEach { TenantRoots.join(it, tenant); accept(it, tenant) }
            ModerationBlockEntity().apply {
                id = UUID.randomUUID().toString(); kind = "MEMBER_SIGN_IN"; targetId = named; takenBy = UUID.randomUUID().toString()
                takenAt = lifted.minus(Duration.ofDays(1)); liftedAt = lifted; liftedBy = takenBy; activeKey = null
            }.persist()
        }
        val ownerToken = serverOwnerToken()
        listOf(named, control).forEach { eraseByOwner(ownerToken, emailOf(it)).then().statusCode(200) }
        purge.purge(named)
        purge.purge(control)
        tx {
            assertNull(VeyqUser.findById(named))
            assertEquals(1L, TenantTermsAcceptanceEntity.count("userId", named), "a kept record of a lifted step keeps the evidence")
            assertEquals(0L, TenantTermsAcceptanceEntity.count("userId", control), "control: no record, no rows")
        }
        QuarkusTransaction.requiringNew().run { purge.sweepKeptTerms(ModerationLog.retentionEnd(lifted).minusSeconds(60)) }
        tx { assertEquals(1L, TenantTermsAcceptanceEntity.count("userId", named), "kept while the step's record is") }
        QuarkusTransaction.requiringNew().run { purge.sweepKeptTerms(ModerationLog.retentionEnd(lifted).plusSeconds(60)) }
        tx { assertEquals(0L, TenantTermsAcceptanceEntity.count("userId", named), "past the step's date they go") }
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

    // --- the last owner (ADR 0101 decision 9) ---

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
    fun anErasedSoleGroupOwnersGroupPassesToTheOldestAcceptedMemberAndItsProposalsExpire() {
        // The erasure runs the tenant removal's cascade (ADR 0101 decision 2), so its orphan rule is the same (#598):
        // only an accepted member is handed the group, and the only owner's departure expires every open proposal.
        val tenant = seedTenant(owner = seedUser())
        val u = seedUser()
        val proposed = seedUser()
        val elder = seedUser()
        val group = UUID.randomUUID().toString()
        tx {
            listOf(u, proposed, elder).forEach { TenantRoots.join(it, tenant) }
            scope(group, tenant, ScopeEntity.GROUP)
            member(u, tenant, group, MembershipEntity.OWNER)
            member(proposed, tenant, group, MembershipEntity.MEMBER)
            member(elder, tenant, group, MembershipEntity.MEMBER)
            // The proposal is the group's oldest other row.
            MembershipEntity.update(
                "pendingConsent = true, createdAt = ?1 where userId = ?2 and scopeId = ?3",
                Instant.parse("2025-01-01T00:00:00Z"), proposed, group,
            )
        }

        eraseByOwner(serverOwnerToken(), emailOf(u)).then().statusCode(200)

        tx {
            assertEquals(MembershipEntity.OWNER, roleOf(elder, group), "the oldest accepted member owns the group")
            assertNull(roleOf(proposed, group), "the open proposal expired, never made an owner")
            assertNull(ScopeEntity.findById(group)!!.deletedAt, "an accepted member remains, so the group carries on")
            assertEquals(
                1L,
                JobEntity.count(
                    "scopeId = ?1 and capabilityTag = ?2 and status = ?3", group, CapabilityTags.SCOPE_ROTATION, JobEntity.PENDING,
                ),
                "the group's rotation is queued",
            )
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
            // A tenant deleted along waits out a tenant deletion's window (decision 10), not the account's.
            val requested = AccountErasureEntity.findById(u)!!.requestedAt
            for (t in listOf(withOthers, alone)) {
                assertEquals(config.scope().delete().retention(), Duration.between(requested, TenantEntity.findById(t)!!.deletionDueAt))
            }
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
    fun theLastHolderIsRefusedUntilTheErasureDeliversEveryLiveEpochToTheServerOwnerWhoIsThenAModerator() {
        ModerationTestKit.reset()
        try {
            val (u, kp) = keyedUser()
            val stranger = seedUser()
            val ownerToken = serverOwnerToken() // the instance has a Server Owner before anything is read
            QuarkusTransaction.requiringNew().run {
                epoch(1)
                epoch(2)
                moderator(u, 1)
                grant(u, 2)
            }
            val token = TestFixtures.bearer(u)

            // The preview names the Server Owner, its identity and chain head, and the two epochs to deliver.
            val preview = given().header("Authorization", token).`when`().get("/api/account/erasure")
                .then().statusCode(200).extract()
            val owner: String = preview.path("serverOwnerDelivery.serverOwnerId")
            val identity: String = preview.path("serverOwnerDelivery.identityPublicKey")
            assertEquals(listOf(1, 2), preview.path<List<Int>>("serverOwnerDelivery.epochs"))
            assertEquals(tx { VeyqUser.findById(owner)!!.identityPublicKey }, identity)
            assertNotNull(preview.path<String>("serverOwnerDelivery.keyChainHead"))
            // The holder may read the Server Owner's chain to anchor that identity, and still no stranger's.
            given().header("Authorization", token).`when`().get("/api/people/$owner/key-chain").then().statusCode(200)
            given().header("Authorization", token).`when`().get("/api/people/$stranger/key-chain").then().statusCode(404)

            // No delivery, a delivery missing an epoch, one naming an epoch the holder does not hold, one sealed to
            // another identity: refused, and nothing written.
            eraseOwn(u, kp, token).then().statusCode(409).body("code", equalTo("moderation_delivery_required"))
            eraseOwn(u, kp, token, delivery = delivery(identity, 2)).then().statusCode(409)
                .body("code", equalTo("moderation_delivery_required"))
            eraseOwn(u, kp, token, delivery = delivery(identity, 1, 2, 3)).then().statusCode(409)
                .body("code", equalTo("moderation_delivery_required"))
            eraseOwn(u, kp, token, delivery = delivery("c3RhbGUtaWRlbnRpdHk=", 1, 2)).then().statusCode(409)
                .body("code", equalTo("identity_changed"))
            tx {
                assertNull(AccountErasureEntity.findById(u))
                assertTrue(ModeratorEntity.isModerator(u))
                assertEquals(0L, ModerationGrantEntity.count("moderatorId", owner), "a refused delivery stores no grant")
                assertFalse(ModeratorEntity.isModerator(owner), "nor an appointment")
            }

            eraseOwn(u, kp, token, delivery = delivery(identity, 1, 2)).then().statusCode(200)
            tx {
                assertNotNull(AccountErasureEntity.findById(u))
                assertTrue(ModeratorEntity.isModerator(owner), "the Server Owner is an accepted moderator, with no acceptance step")
                assertEquals(u, ModeratorEntity.findById(owner)!!.appointedBy, "appointed by the holder's delivery, as the log says")
                assertEquals(
                    setOf(1 to "Zw1=", 2 to "Zw2="),
                    ModerationGrantEntity.list("moderatorId", owner).map { it.epoch to it.sealedGrant }.toSet(),
                    "holding every live epoch, as sealed by the holder's client",
                )
                assertEquals(setOf(u), ModerationGrantEntity.list("moderatorId", owner).map { it.deliveredBy }.toSet())
                assertFalse(ModeratorEntity.isModerator(u), "the holder left the roster as a step-down does")
                assertEquals(0L, ModerationGrantEntity.count("moderatorId", u))
                assertNotNull(ModerationEpochEntity.findById(2)!!.rotationDueAt, "the epoch the holder held is due for rotation")
                assertEquals(2L, ModerationEventEntity.count("event = ?1 and moderatorId = ?2", "DELIVERED", owner))
            }
            // The roster the Server Owner reads says so.
            givenAdminChannel().header("Authorization", "Bearer $ownerToken").`when`().get("/api/instance/moderation")
                .then().statusCode(200)
                .body("moderators.find { it.accountId == '$owner' }.state", equalTo("ACCEPTED"))
                .body("moderators.find { it.accountId == '$owner' }.holdsCurrentEpoch", equalTo(true))
        } finally {
            ModerationTestKit.reset()
        }
    }

    @Test
    fun aHolderWhoIsNotTheLastIsServedNoDeliveryAndErasesWithoutOne() {
        ModerationTestKit.reset()
        try {
            val (u, kp) = keyedUser()
            val other = seedUser()
            QuarkusTransaction.requiringNew().run {
                epoch(1)
                moderator(u, 1)
                moderator(other, 1)
            }
            val token = TestFixtures.bearer(u)
            given().header("Authorization", token).`when`().get("/api/account/erasure")
                .then().statusCode(200).body("serverOwnerDelivery", nullValue())
            eraseOwn(u, kp, token).then().statusCode(200)
            tx {
                assertFalse(ModeratorEntity.isModerator(u))
                assertTrue(ModeratorEntity.isModerator(other), "the other holder keeps the key")
                assertEquals(0L, ModerationGrantEntity.count("moderatorId", u))
            }
        } finally {
            ModerationTestKit.reset()
        }
    }

    /** The current epoch is not the whole key: a moderator accepted after epoch 1 holds 2 from its mint before anyone
     *  delivered them 1, so the one who alone holds 1 is its last holder all the same. */
    @Test
    fun theOnlyHolderOfAnOlderLiveEpochDeliversItThoughAnotherHoldsTheCurrentOne() {
        ModerationTestKit.reset()
        try {
            val (u, kp) = keyedUser()
            val other = seedUser()
            serverOwnerToken()
            QuarkusTransaction.requiringNew().run {
                epoch(1)
                epoch(2)
                moderator(u, 1)
                grant(u, 2)
                moderator(other, 2)
            }
            val token = TestFixtures.bearer(u)
            val preview = given().header("Authorization", token).`when`().get("/api/account/erasure")
                .then().statusCode(200).extract()
            val owner: String = preview.path("serverOwnerDelivery.serverOwnerId")
            val identity: String = preview.path("serverOwnerDelivery.identityPublicKey")
            assertEquals(listOf(1, 2), preview.path<List<Int>>("serverOwnerDelivery.epochs"))
            // A step-down would lose epoch 1 as well, so it is refused like the current epoch's last holder's.
            assertTrue(moderation.stepDown(u) is ModerationOutcome.Refused)

            eraseOwn(u, kp, token).then().statusCode(409).body("code", equalTo("moderation_delivery_required"))
            eraseOwn(u, kp, token, delivery = delivery(identity, 1, 2)).then().statusCode(200)
            tx {
                assertEquals(setOf(1, 2), ModerationGrantEntity.list("moderatorId", owner).map { it.epoch }.toSet(), "epoch 1 lives on")
                assertTrue(ModeratorEntity.isModerator(other), "the other holder keeps the current epoch")
            }
        } finally {
            ModerationTestKit.reset()
        }
    }

    @Test
    fun theServerOwnersDoorErasesTheLastHolderOnlyOnceTheKeysLossIsConfirmedAndTheKeyIsThenGone() {
        ModerationTestKit.reset()
        try {
            val (u, _) = keyedUser()
            QuarkusTransaction.requiringNew().run {
                epoch(1)
                moderator(u, 1)
            }
            val ownerToken = serverOwnerToken()
            eraseByOwner(ownerToken, emailOf(u)).then().statusCode(409).body("code", equalTo("moderation_last_holder"))
            eraseByOwner(ownerToken, emailOf(u), confirm = "false").then().statusCode(409).body("code", equalTo("moderation_last_holder"))
            tx {
                assertNull(AccountErasureEntity.findById(u), "unconfirmed: nothing written")
                assertTrue(ModeratorEntity.isModerator(u))
                assertEquals(1L, ModerationGrantEntity.count("epoch", 1))
            }

            eraseByOwner(ownerToken, emailOf(u), confirm = "true").then().statusCode(200)
            tx {
                assertNotNull(AccountErasureEntity.findById(u))
                assertFalse(ModeratorEntity.isModerator(u))
                assertEquals(0L, ModerationGrantEntity.count("epoch", 1), "nobody holds the key any more")
                assertEquals(0L, ModeratorEntity.count("removedAt is null and acceptedAt is not null"), "and the roster is empty")
                assertNotNull(ModerationEpochEntity.findById(1)!!.rotationDueAt)
            }
            // Control: an account that holds no key needs no confirmation at this door.
            val (plain, _) = keyedUser()
            eraseByOwner(ownerToken, emailOf(plain)).then().statusCode(200)
        } finally {
            ModerationTestKit.reset()
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

    private fun eraseOwn(userId: String, kp: KeyPair, token: String, choices: String = "[]", delivery: String? = null): Response {
        val nonce = challenge(emailOf(userId))
        return eraseOwnWith(token, nonce, sign(kp, AuthAttestation.eraseAccountStatement(userId, nonce)!!), choices, delivery)
    }

    private fun eraseOwnWith(token: String, nonce: String, signature: String, choices: String = "[]", delivery: String? = null): Response =
        given().header("Authorization", token).contentType(ContentType.JSON)
            .body(
                """{"nonce":"$nonce","attestationSignature":"$signature","lastOwnerChoices":$choices""" +
                    (delivery?.let { ""","serverOwnerDelivery":$it""" } ?: "") + "}",
            )
            .`when`().post("/api/account/erasure")

    /** A delivery to the Server Owner of [epochs], sealed to [identity]; each grant is "Zw" + its epoch, opaque. */
    private fun delivery(identity: String, vararg epochs: Int): String =
        """{"identityPublicKey":"$identity","grants":[${epochs.joinToString(",") { """{"epoch":$it,"sealedGrant":"Zw$it="}""" }}]}"""

    private fun eraseByOwner(ownerToken: String, address: String, confirm: String? = null): Response =
        givenAdminChannel().header("Authorization", "Bearer $ownerToken").contentType(ContentType.JSON)
            .body("""{"address":"$address"${confirm?.let { ""","confirmModerationKeyLoss":$it""" } ?: ""}}""")
            .`when`().post("/api/instance/accounts/erasures")

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
        grant(accountId, epoch)
    }

    private fun grant(accountId: String, epoch: Int) {
        ModerationGrantEntity().apply {
            id = UUID.randomUUID().toString(); moderatorId = accountId; this.epoch = epoch; sealedGrant = "Zw=="
            deliveredBy = accountId
        }.persist()
    }

    private fun epoch(epoch: Int) {
        ModerationEpochEntity().apply { this.epoch = epoch; kemPublicKey = "a2Vt"; signPublicKey = "c2lnbg=="; reason = "BY_HAND" }.persist()
    }

    private fun roleOf(userId: String, tenantId: String): String? =
        MembershipEntity.find("userId = ?1 and scopeId = ?2", userId, tenantId).firstResult()?.role

    private fun <T> tx(block: () -> T): T = QuarkusTransaction.requiringNew().call(block)

    /** Another window for one test ([AccountErasureWindow]'s KDoc says why not a profile). `@Vetoed`: the scope is
     *  inherited, and as a bean of its own it would make every injection of the window ambiguous. */
    @Vetoed
    class FixedWindow(config: VeyqConfig, private val fixed: Duration) : AccountErasureWindow(config) {
        override fun current(): Duration = fixed
    }

    private companion object {
        // This class's own SMTP port (TestMailbox says how to find the taken ones).
        const val MAIL_PORT = 13950
    }
}
