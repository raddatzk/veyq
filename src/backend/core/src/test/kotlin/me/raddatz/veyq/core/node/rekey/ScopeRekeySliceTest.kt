package me.raddatz.veyq.core.node.rekey

import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.admin.tenant.VerificationEntity
import me.raddatz.veyq.core.admin.tenant.VerificationKey
import me.raddatz.veyq.core.identity.TenantProfileEntity
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.membership.TestGrants
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.ShareWriteProof
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.seedScopeMember
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import me.raddatz.veyq.core.testing.TestScopeRekey
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * ADR 0115's acceptance criteria on the server (uc.spaces.rekey-after-rotation; § Bite tests): a rotation replaces the
 * scope key, so its commit swaps in a staged value for every scope-level row sealed under the old one — each scope-root
 * entry, live and trashed, the group's name and icon, a pending invitee's name key, every link's recovery copy, and in a
 * tenant every member's profile and verification — and marks every node due; it refuses, writing nothing, when a row has
 * no staged value or changed since it was staged; and a scope-level write sealed under the old epoch after it is
 * `stale_epoch`. Each refusal beside its positive control on the same rows.
 */
@QuarkusTest
class ScopeRekeySliceTest {

    @Test
    fun everyStagedGroupRowIsSwappedInAndEveryNodeIsDue() {
        val g = groupWorld()
        // The listing serves every row the commit will require, and pages across kinds.
        parts(g.owner, g.scopeId).statusCode(200)
            .body("currentEpoch", equalTo(0))
            .body("groupName.encryptedName", equalTo(NAME)).body("icon.sealedIcon", equalTo(ICON))
            .body("rootNodes.size()", equalTo(2)).body("groupInvitees.size()", equalTo(1)).body("shares.size()", equalTo(1))
            .body("members.size()", equalTo(0)).body("more", equalTo(false))
        val first = parts(g.owner, g.scopeId, limit = 2).statusCode(200).body("more", equalTo(true)).extract()
        val cursor = first.path<String>("cursor")
        val second = parts(g.owner, g.scopeId, since = cursor, limit = 2).statusCode(200)
            .body("groupName", nullValue()).body("icon", nullValue()).body("rootNodes.size()", equalTo(0))
            .body("more", equalTo(false)).extract()
        assertEquals(2, second.path<List<Any>>("groupInvitees").size + second.path<List<Any>>("shares").size, "the second page holds the rest")

        val rekeyId = UUID.randomUUID()
        stage(g.owner, g.scopeId, rekeyId, g.fullStage()).statusCode(204)
        rotate(g.owner, g.scopeId, rekeyId).statusCode(201).body("epoch", equalTo(1))

        tx {
            val scope = ScopeEntity.findById(g.scopeId)!!
            assertEquals(NEW_NAME, scope.encryptedName); assertEquals(1, scope.nameEpoch)
            assertEquals(NEW_ICON, scope.sealedIcon); assertEquals(1, scope.iconEpoch)
            val live = NodeEntity.findById(g.liveRoot)!!
            assertEquals(NEW_WRAP, live.wrappedContentKey); assertEquals(NEW_ROOT_NAME, live.encryptedName)
            assertEquals(NEW_SLUG, live.slugToken); assertEquals(1L, live.placementRevision, "the re-wrap binds placementRevision + 1")
            val trashed = NodeEntity.findById(g.trashedRoot)!!
            assertEquals(NEW_WRAP, trashed.wrappedContentKey); assertEquals(g.trashedRoot, trashed.slugToken, "the freed slug is kept")
            assertEquals(NEW_NAME_KEY, MembershipEntity.find("userId = ?1 and scopeId = ?2", g.invitee, g.scopeId).firstResult()!!.sealedNameKey)
            val share = ShareEntity.findById(g.shareId)!!
            assertEquals(NEW_COPY, share.sealedShareKey); assertEquals(1, share.sealedShareKeyEpoch)
            assertEquals(0, share.mintedEpoch, "the epoch the link was minted at stays beside its moved copy (ADR 0075 point 4)")
            // ADR 0114 decision 4: every node of the scope, below the root and in the trash too, is due for its own re-key.
            assertTrue(NodeEntity.list("scopeId", g.scopeId).all { it.rekeyDue }, "every node is due after the commit")
            assertEquals(0L, ScopeRekeyStageEntity.count("rekeyId", rekeyId.toString()), "the commit took the stage")
        }
        assertTrue(tx { ScopeRekeying.lags(g.scopeId) }, "the scope is re-keying while a node is due")
        // A due node takes no write under its key, and its own commit is what clears it (the node half, RekeyCoreSliceTest).
        given().header("Authorization", bearer(g.owner)).contentType(ContentType.JSON)
            .body("""{"wrappedMeta":"bWV0YQ==","metaRevision":0,"nodeEpoch":0}""")
            .`when`().patch("/api/scopes/${g.scopeId}/nodes/${g.liveRoot}").then()
            .statusCode(409).body("code", equalTo("node_rekey_pending"))
        given().header("Authorization", bearer(g.owner)).`when`().get("/api/scopes/${g.scopeId}/rekey/nodes").then().statusCode(200)
            .body("nodes.find { it.id == '${g.liveRoot}' }.due", equalTo(true))
            .body("nodes.find { it.id == '${g.liveRoot}' }.epoch", equalTo(0))
            .body("nodes.find { it.id == '${g.liveRoot}' }.scopeEpoch", equalTo(0))
    }

    @Test
    fun aRotationMissingOneRowsStagedValueIsRefusedAndWritesNothing() {
        val g = groupWorld()
        val rekeyId = UUID.randomUUID()
        stage(g.owner, g.scopeId, rekeyId, g.fullStage(shares = false)).statusCode(204)
        rotate(g.owner, g.scopeId, rekeyId).statusCode(409).body("code", equalTo("rekey_parts_missing"))
        g.assertUntouched()
        // Control: the missing copy staged under the same id, the same rotation commits.
        stage(g.owner, g.scopeId, rekeyId, """{"fromEpoch":0,"shares":[{"shareId":"${g.shareId}","sealedShareKey":"$NEW_COPY","replacedDigest":"${d(COPY)}"}]}""").statusCode(204)
        rotate(g.owner, g.scopeId, rekeyId).statusCode(201)
    }

    @Test
    fun aRemovalRotationRefusedForAMissingRowLeavesTheMemberInPlace() {
        val g = groupWorld()
        val member = seedScopeMember(g.scopeId, grantedEpoch = 0)
        val rekeyId = UUID.randomUUID()
        stage(g.owner, g.scopeId, rekeyId, g.fullStage(icon = false)).statusCode(204)
        rotate(g.owner, g.scopeId, rekeyId, removes = member).statusCode(409).body("code", equalTo("rekey_parts_missing"))
        assertEquals(1L, tx { MembershipEntity.count("userId = ?1 and scopeId = ?2", member, g.scopeId) }, "the removal rolled back with it")
        g.assertUntouched()
        stage(g.owner, g.scopeId, rekeyId, """{"fromEpoch":0,"icon":{"sealedIcon":"$NEW_ICON","replacedDigest":"${d(ICON)}"}}""").statusCode(204)
        rotate(g.owner, g.scopeId, rekeyId, removes = member).statusCode(201)
        assertEquals(0L, tx { MembershipEntity.count("userId = ?1 and scopeId = ?2", member, g.scopeId) }, "control: the removal landed")
    }

    @Test
    fun aRowChangedSinceItWasStagedIsStale() {
        val g = groupWorld()
        val rekeyId = UUID.randomUUID()
        stage(g.owner, g.scopeId, rekeyId, g.fullStage()).statusCode(204)
        // The group is renamed at epoch 0 after the rotator staged its name: the staged value is a re-seal of the old one.
        given().header("Authorization", bearer(g.owner)).contentType(ContentType.JSON)
            .body("""{"encryptedName":"cmVuYW1lZA==","nameEpoch":0}""")
            .`when`().post("/api/scopes/${g.scopeId}/name").then().statusCode(204)
        rotate(g.owner, g.scopeId, rekeyId).statusCode(409).body("code", equalTo("rekey_parts_stale"))
        assertEquals(0, TestGrants.epochOf(g.scopeId), "nothing written")
        assertEquals("cmVuYW1lZA==", tx { ScopeEntity.findById(g.scopeId)!!.encryptedName }, "the rename stands")
        // Control: the name re-staged against what the row holds now, the rotation commits.
        stage(g.owner, g.scopeId, rekeyId, """{"fromEpoch":0,"groupName":{"encryptedName":"$NEW_NAME","replacedDigest":"${d("cmVuYW1lZA==")}"}}""").statusCode(204)
        rotate(g.owner, g.scopeId, rekeyId).statusCode(201)
    }

    /** ADR 0115 decision 2: each staged value names the digest of the ciphertext it was opened from, so a write between
     *  the rotator's read and its stage is refused at the stage — never replaced by a re-seal of what it replaced. */
    @Test
    fun aRowWrittenBetweenTheReadAndTheStageIsRefusedAtTheStage() {
        val g = groupWorld()
        parts(g.owner, g.scopeId).statusCode(200).body("groupName.encryptedName", equalTo(NAME)) // the rotator reads
        given().header("Authorization", bearer(g.owner)).contentType(ContentType.JSON)
            .body("""{"encryptedName":"cmVuYW1lZA==","nameEpoch":0}""")
            .`when`().post("/api/scopes/${g.scopeId}/name").then().statusCode(204)
        val rekeyId = UUID.randomUUID()
        stage(g.owner, g.scopeId, rekeyId, g.fullStage()).statusCode(409).body("code", equalTo("rekey_parts_stale"))
        assertEquals(0L, tx { ScopeRekeyStageEntity.count("rekeyId", rekeyId.toString()) }, "nothing of the batch is staged")
        // Control: re-read, the same batch against the renamed row stages, and the rotation commits.
        stage(g.owner, g.scopeId, rekeyId, g.fullStage(nameDigest = d("cmVuYW1lZA=="))).statusCode(204)
        rotate(g.owner, g.scopeId, rekeyId).statusCode(201)
        assertEquals(NEW_NAME, tx { ScopeEntity.findById(g.scopeId)!!.encryptedName })
    }

    @Test
    fun aMemberFieldWithoutItsDigestIsMalformed() {
        val owner = seedUser()
        val tenantId = seedTenant(owner = owner)
        tx { profile(owner, tenantId, name = "b3duZXI=", avatar = null, join = null) }
        stage(owner, tenantId, UUID.randomUUID(), """{"fromEpoch":0,"members":[{"userId":"$owner","sealedName":"bmV3"}]}""")
            .statusCode(400).body("code", equalTo("invalid_request"))
        stage(owner, tenantId, UUID.randomUUID(), """{"fromEpoch":0,"members":[{"userId":"$owner","replacedNameDigest":"${d("b3duZXI=")}"}]}""")
            .statusCode(400).body("code", equalTo("invalid_request"))
        // Control: the field with its digest stages.
        stage(owner, tenantId, UUID.randomUUID(), """{"fromEpoch":0,"members":[{"userId":"$owner","sealedName":"bmV3","replacedNameDigest":"${d("b3duZXI=")}"}]}""")
            .statusCode(204)
    }

    @Test
    fun aPendingInviteesNameKeySealedToAnotherIdentityIsRefused() {
        val g = groupWorld()
        val rekeyId = UUID.randomUUID()
        // Every seeded account holds the fixture's key, so the stand-in for "another identity" is a digest of none of them.
        stage(g.owner, g.scopeId, rekeyId, g.fullStage(inviteeDigest = TestGrants.digest(TestGrants.WRAPPED))).statusCode(204)
        rotate(g.owner, g.scopeId, rekeyId).statusCode(409).body("code", equalTo("recipient_key_not_current"))
        g.assertUntouched()
        stage(g.owner, g.scopeId, rekeyId, g.fullStage()).statusCode(204)
        rotate(g.owner, g.scopeId, rekeyId).statusCode(201)
    }

    @Test
    fun everyStagedTenantProfileAndVerificationIsSwappedIn() {
        val owner = seedUser()
        val tenantId = seedTenant(owner = owner)
        val member = seedScopeMember(tenantId, grantedEpoch = 0)
        tx {
            profile(owner, tenantId, name = "b3duZXI=", avatar = null, join = null)
            profile(member, tenantId, name = "bWVtYmVy", avatar = "YXZhdGFy", join = "am9pbg==").joinEndorsement = "ZW5kb3JzZWQ="
            VerificationEntity().apply {
                this.tenantId = tenantId; verifierUserId = owner; subjectUserId = member; epoch = 0
                sealedStatement = "dmVyaWZpZWQ="; createdAt = Instant.now()
            }.persist()
        }
        parts(owner, tenantId).statusCode(200).body("members.size()", equalTo(2)).body("verifications.size()", equalTo(1))
            .body("verifications[0].epoch", equalTo(0))
        val rekeyId = UUID.randomUUID()
        // A field staged where the row holds none is stale: the owner has no picture — refused at the stage now, which
        // compares the digest it names with the row's (ADR 0115 decision 2), and the rotation has nothing of the batch.
        stage(owner, tenantId, rekeyId, tenantStage(owner, member, ownerAvatar = true)).statusCode(409).body("code", equalTo("rekey_parts_stale"))
        rotate(owner, tenantId, rekeyId).statusCode(409).body("code", equalTo("rekey_parts_missing"))
        assertEquals("bWVtYmVy", tx { TenantProfileEntity.find("userId", member).firstResult()!!.sealedName }, "nothing written")
        stage(owner, tenantId, rekeyId, tenantStage(owner, member)).statusCode(204)
        rotate(owner, tenantId, rekeyId).statusCode(201)
        tx {
            val p = TenantProfileEntity.find("userId = ?1 and tenantId = ?2", member, tenantId).firstResult()!!
            assertEquals("bmV3LW1lbWJlcg==", p.sealedName); assertEquals("bmV3LWF2YXRhcg==", p.sealedAvatar)
            assertEquals("bmV3LWpvaW4=", p.sealedJoinRecord)
            assertEquals(1, p.avatarRevision, "a new envelope of the picture moves its revision")
            assertEquals("ZW5kb3JzZWQ=", p.joinEndorsement, "the endorsement signs the opened record, so a re-seal keeps it (decision 8)")
            assertEquals("bmV3LW93bmVy", TenantProfileEntity.find("userId = ?1 and tenantId = ?2", owner, tenantId).firstResult()!!.sealedName)
            val v = VerificationEntity.findById(VerificationKey(tenantId, owner, member))!!
            assertEquals("bmV3LXZlcmlmaWVk", v.sealedStatement); assertEquals(1, v.epoch)
        }
    }

    @Test
    fun aScopeLevelWriteSealedUnderTheOldEpochIsStale() {
        val owner = seedUser()
        val tenantId = seedTenant(owner = owner)
        val member = seedScopeMember(tenantId, grantedEpoch = 0)
        tx { profile(owner, tenantId, name = "b3duZXI=", avatar = null, join = null) }
        rotate(owner, tenantId, UUID.fromString(TestScopeRekey.stageAll(tenantId))).statusCode(201)

        fun asOwner() = given().header("Authorization", bearer(owner)).header("X-Forwarded-Host", domainOf(tenantId)).contentType(ContentType.JSON)
        asOwner().body("""{"sealedName":"c3RhbGU=","epoch":0}""").`when`().put("/api/account/profile").then()
            .statusCode(409).body("code", equalTo("stale_epoch"))
        asOwner().body("""{"sealedAvatar":"c3RhbGU=","epoch":0}""").`when`().put("/api/account/profile/avatar").then()
            .statusCode(409).body("code", equalTo("stale_epoch"))
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{"epoch":0,"sealedStatement":"c3RhbGU="}""")
            .`when`().put("/api/tenants/$tenantId/verifications/$member").then()
            .statusCode(409).body("code", equalTo("stale_epoch"))
        tx {
            assertEquals("b3duZXI=", TenantProfileEntity.find("userId", owner).firstResult()!!.sealedName, "nothing written")
            assertEquals(0L, VerificationEntity.count("tenantId", tenantId))
        }
        // Controls: the same writes at the current epoch land.
        asOwner().body("""{"sealedName":"Y3VycmVudA==","epoch":1}""").`when`().put("/api/account/profile").then().statusCode(200)
        asOwner().body("""{"sealedAvatar":"Y3VycmVudA==","epoch":1}""").`when`().put("/api/account/profile/avatar").then().statusCode(204)
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{"epoch":1,"sealedStatement":"Y3VycmVudA=="}""")
            .`when`().put("/api/tenants/$tenantId/verifications/$member").then().statusCode(204)
        // A sealed name without the epoch it is sealed under is the request's own fault.
        asOwner().body("""{"sealedName":"Y3VycmVudA=="}""").`when`().put("/api/account/profile").then()
            .statusCode(400).body("code", equalTo("invalid_request"))
    }

    @Test
    fun onlyTheRotatorHoldingTheCurrentGrantReadsAndStagesTheScopeLevelRows() {
        val g = groupWorld()
        val member = seedScopeMember(g.scopeId, grantedEpoch = 0)
        parts(member, g.scopeId).statusCode(403).body("code", equalTo("not_scope_owner"))
        stage(member, g.scopeId, UUID.randomUUID(), """{"fromEpoch":0}""").statusCode(403).body("code", equalTo("not_scope_owner"))
        parts(seedUser(), g.scopeId).statusCode(404).body("code", equalTo("scope_not_found"))
        stage(g.owner, g.scopeId, UUID.randomUUID(), """{"fromEpoch":1}""").statusCode(409).body("code", equalTo("stale_epoch"))
        parts(g.owner, g.scopeId).statusCode(200).body("currentEpoch", equalTo(0)).body("cursor", notNullValue())
    }

    // --- the world ---

    private inner class GroupWorld(
        val owner: String, val scopeId: String, val liveRoot: String, val trashedRoot: String, val child: String,
        val invitee: String, val shareId: String,
    ) {
        /** Every row re-sealed, each naming the digest of what the rotator opened (ADR 0115 decision 2). */
        fun fullStage(
            shares: Boolean = true, icon: Boolean = true, inviteeDigest: String = TestGrants.digestOf(invitee),
            nameDigest: String = d(NAME),
        ): String =
            """{"fromEpoch":0,"groupName":{"encryptedName":"$NEW_NAME","replacedDigest":"$nameDigest"},""" +
                (if (icon) """"icon":{"sealedIcon":"$NEW_ICON","replacedDigest":"${d(ICON)}"},""" else "") +
                """"rootNodes":[""" +
                """{"nodeId":"$liveRoot","placementRevision":0,"scopeEpoch":0,"encryptedName":"$NEW_ROOT_NAME","slugToken":"$NEW_SLUG","wrappedContentKey":"$NEW_WRAP","replacedDigest":"${d(CONTENT_KEY)}"},""" +
                """{"nodeId":"$trashedRoot","placementRevision":0,"scopeEpoch":0,"encryptedName":"$NEW_ROOT_NAME","wrappedContentKey":"$NEW_WRAP","replacedDigest":"${d(CONTENT_KEY)}"}],""" +
                """"groupInvitees":[{"userId":"$invitee","sealedNameKey":"$NEW_NAME_KEY","recipientIdentityDigest":"$inviteeDigest","replacedDigest":"${d(NAME_KEY)}"}]""" +
                (if (shares) ""","shares":[{"shareId":"$shareId","sealedShareKey":"$NEW_COPY","replacedDigest":"${d(COPY)}"}]""" else "") + "}"

        /** Nothing a refused rotation could have written: the epoch, the rows, the marks, the statement. */
        fun assertUntouched() = tx {
            val scope = ScopeEntity.findById(scopeId)!!
            assertEquals(0, scope.currentEpoch)
            assertEquals(NAME, scope.encryptedName); assertEquals(ICON, scope.sealedIcon)
            assertEquals(CONTENT_KEY, NodeEntity.findById(liveRoot)!!.wrappedContentKey)
            assertEquals(0L, NodeEntity.findById(liveRoot)!!.placementRevision)
            assertEquals("cmVjb3Zlcnk=", ShareEntity.findById(shareId)!!.sealedShareKey)
            assertEquals("bmFtZS1rZXk=", MembershipEntity.find("userId = ?1 and scopeId = ?2", invitee, scopeId).firstResult()!!.sealedNameKey)
            assertFalse(NodeEntity.list("scopeId", scopeId).any { it.rekeyDue }, "no node marked due")
            assertEquals(0L, me.raddatz.veyq.core.spaces.scope.ScopeSuccessionEntity.count("scopeId", scopeId))
        }
    }

    /** A group at epoch 0: a live root folder with a child, a trashed root folder, its name and icon, a pending consent
     *  proposal holding the name key, and a link on the live root with its recovery copy. */
    private fun groupWorld(): GroupWorld {
        val (owner, scopeId, _) = seedMemberScope(ScopeEntity.GROUP)
        val live = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val child = seedNode(scopeId, NodeEntity.FOLDER, parentId = live, wrappedContentKey = CONTENT_KEY)
        val trashed = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val invitee = seedUser()
        tx {
            NodeEntity.findById(trashed)!!.apply { deletedAt = Instant.now(); slugToken = id }
            ScopeEntity.findById(scopeId)!!.apply { encryptedName = NAME; sealedIcon = ICON }
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); userId = invitee; tenantId = ScopeEntity.findById(scopeId)!!.tenantId
                this.scopeId = scopeId; role = MembershipEntity.MEMBER; wrappedGrant = TestGrants.WRAPPED; pendingConsent = true
                sealedNameKey = "bmFtZS1rZXk="; addedBy = owner; createdAt = Instant.now()
            }.persist()
        }
        val shareId = TestIds.shareId()
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body(
                """{"shareId":"$shareId",${ShareWriteProof.field(shareId)},"nodeId":"$live","role":"READ","wrap":"d3JhcA==",""" +
                    """"sealedName":"c2VhbGVk",${ShareWriteProof.recovery(epoch = 0, nodeEpoch = 0)}}""",
            )
            .`when`().post("/api/shares").then().statusCode(201)
        return GroupWorld(owner, scopeId, live, trashed, child, invitee, shareId)
    }

    private fun profile(userId: String, tenantId: String, name: String?, avatar: String?, join: String?): TenantProfileEntity {
        val p = TenantProfileEntity.find("userId = ?1 and tenantId = ?2", userId, tenantId).firstResult() ?: TenantProfileEntity().apply {
            id = UUID.randomUUID().toString(); this.userId = userId; this.tenantId = tenantId; username = "u${userId.take(8)}"
        }
        p.sealedName = name; p.sealedAvatar = avatar; p.sealedJoinRecord = join
        p.persist()
        return p
    }

    private fun tenantStage(owner: String, member: String, ownerAvatar: Boolean = false): String =
        """{"fromEpoch":0,"members":[""" +
            """{"userId":"$owner","sealedName":"bmV3LW93bmVy","replacedNameDigest":"${d("b3duZXI=")}"""" +
            (if (ownerAvatar) ""","sealedAvatar":"bmV3LWF2YXRhcg==","replacedAvatarDigest":"${d("YXZhdGFy")}"""" else "") + "}," +
            """{"userId":"$member","sealedName":"bmV3LW1lbWJlcg==","replacedNameDigest":"${d("bWVtYmVy")}",""" +
            """"sealedAvatar":"bmV3LWF2YXRhcg==","replacedAvatarDigest":"${d("YXZhdGFy")}",""" +
            """"sealedJoinRecord":"bmV3LWpvaW4=","replacedJoinRecordDigest":"${d("am9pbg==")}"}],""" +
            """"verifications":[{"verifierUserId":"$owner","subjectUserId":"$member","sealedStatement":"bmV3LXZlcmlmaWVk","replacedDigest":"${d("dmVyaWZpZWQ=")}"}]}"""

    /** The digest a rotator names of the ciphertext it opened ([ScopeRekeyStage.digest], the server's own rule). */
    private fun d(opened: String): String = ScopeRekeyStage.digest(opened)!!

    private fun domainOf(tenantId: String): String = tx { me.raddatz.veyq.core.tenant.TenantEntity.findById(tenantId)!!.domain }

    // --- the wire ---

    private fun parts(caller: String, scopeId: String, since: String? = null, limit: Int? = null): ValidatableResponse =
        given().header("Authorization", bearer(caller))
            .apply { since?.let { queryParam("since", it) }; limit?.let { queryParam("limit", it) } }
            .`when`().get("/api/scopes/$scopeId/rekey/scope/parts").then()

    private fun stage(caller: String, scopeId: String, rekeyId: UUID, body: String): ValidatableResponse =
        given().header("Authorization", bearer(caller)).contentType(ContentType.JSON).body(body)
            .`when`().post("/api/scopes/$scopeId/rekey/scope/$rekeyId/parts").then()

    /** [owner]'s rotation to the next epoch under [rekeyId]: itself re-granted, everyone else declared pending. */
    private fun rotate(owner: String, scopeId: String, rekeyId: UUID, removes: String? = null): ValidatableResponse =
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body(
                """{"epoch":${TestGrants.epochOf(scopeId) + 1},${TestScopeRekey.SUCCESSION_JSON},"rekeyId":"$rekeyId",""" +
                    """"grants":[{"userId":"$owner","wrappedGrant":"bmV3",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"${TestGrants.digestOf(owner)}"}],""" +
                    """${TestGrants.othersPendingJson(scopeId, owner, removes)},${TestGrants.openInvitesJson(scopeId)}""" +
                    (removes?.let { ""","removesUserId":"$it"""" } ?: "") + "}",
            )
            .`when`().post("/api/scopes/$scopeId/epochs").then()

    private fun <T> tx(block: () -> T): T = QuarkusTransaction.requiringNew().call(block)

    private companion object {
        const val COPY = "cmVjb3Zlcnk="
        const val NAME_KEY = "bmFtZS1rZXk="
        const val NAME = "Z3JvdXAtbmFtZQ=="
        const val ICON = "aWNvbg=="
        const val NEW_NAME = "bmV3LWdyb3VwLW5hbWU="
        const val NEW_ICON = "bmV3LWljb24="
        const val NEW_ROOT_NAME = "bmV3LXJvb3QtbmFtZQ=="
        const val NEW_SLUG = "bmV3LXNsdWc="
        const val NEW_WRAP = "bmV3LXJvb3Qtd3JhcA=="
        const val NEW_NAME_KEY = "bmV3LW5hbWUta2V5"
        const val NEW_COPY = "bmV3LWNvcHk="
    }
}
