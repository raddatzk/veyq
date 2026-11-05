package me.raddatz.veyq.core.node.rekey

import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.account.rotation.RotationRegrantEntity
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
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * ADR 0115's acceptance criteria on the server (uc.spaces.rekey-after-rotation; § Bite tests), with the owner's
 * decision of 2026-11-02: **a rotation re-wraps keys and never rewrites a value.** Every scope-level value that has an
 * author — the group's name and icon, every link's recovery copy, and in a tenant every member's name, picture and join
 * record and every verification — is sealed under a value key of its own, so the rotation's commit swaps in a staged
 * re-wrap of each value key and the values stay byte-identical; the one thing it re-seals is each scope-root entry, live
 * and trashed. It marks every node due; refuses, writing nothing, a wrap with no staged replacement, one changed since
 * it was staged, and a stage that writes a value (`422 rekey_writes_value` — the same for a member rotator as for an
 * owner); and a scope-level write wrapped under the old epoch after it is `stale_epoch`. Each refusal beside its
 * positive control on the same rows.
 */
@QuarkusTest
class ScopeRekeySliceTest {

    @Test
    fun everyStagedGroupWrapIsSwappedInTheRootsReSealedAndEveryNodeIsDue() {
        val g = groupWorld()
        // The listing serves every wrap the commit will require — never a value's ciphertext — and every root entry,
        // and pages across kinds. A pending invitee's name key is sealed to the invitee, no wrap: not listed.
        parts(g.owner, g.scopeId).statusCode(200)
            .body("currentEpoch", equalTo(0))
            .body("groupName.wrappedNameValueKey", equalTo(NAME_WRAP)).body("groupName.nameEpoch", equalTo(0))
            .body("groupName.encryptedName", nullValue())
            .body("icon.wrappedIconValueKey", equalTo(ICON_WRAP)).body("icon.sealedIcon", nullValue())
            .body("rootNodes.size()", equalTo(2)).body("shares.size()", equalTo(1))
            .body("shares[0].wrappedRecoveryValueKey", equalTo(COPY_WRAP)).body("shares[0].sealedShareKey", nullValue())
            .body("groupInvitees", nullValue()).body("members.size()", equalTo(0)).body("more", equalTo(false))
        val first = parts(g.owner, g.scopeId, limit = 2).statusCode(200).body("more", equalTo(true)).extract()
        val cursor = first.path<String>("cursor")
        parts(g.owner, g.scopeId, since = cursor, limit = 2).statusCode(200)
            .body("groupName", nullValue()).body("icon", nullValue()).body("rootNodes.size()", equalTo(0))
            .body("shares.size()", equalTo(1)).body("more", equalTo(false))

        val rekeyId = UUID.randomUUID()
        stage(g.owner, g.scopeId, rekeyId, g.fullStage()).statusCode(204)
        rotate(g.owner, g.scopeId, rekeyId).statusCode(201).body("epoch", equalTo(1))

        tx {
            val scope = ScopeEntity.findById(g.scopeId)!!
            assertEquals(NEW_NAME_WRAP, scope.wrappedNameValueKey, "the name's key re-wrapped")
            assertEquals(NAME, scope.encryptedName, "the name itself never rewritten"); assertEquals(0, scope.nameEpoch, "nor its epoch")
            assertEquals(NEW_ICON_WRAP, scope.wrappedIconValueKey)
            assertEquals(ICON, scope.sealedIcon); assertEquals(0, scope.iconEpoch)
            val live = NodeEntity.findById(g.liveRoot)!!
            assertEquals(NEW_WRAP, live.wrappedContentKey); assertEquals(NEW_ROOT_NAME, live.encryptedName)
            assertEquals(NEW_SLUG, live.slugToken); assertEquals(1L, live.placementRevision, "the re-wrap binds placementRevision + 1")
            val trashed = NodeEntity.findById(g.trashedRoot)!!
            assertEquals(NEW_WRAP, trashed.wrappedContentKey); assertEquals(g.trashedRoot, trashed.slugToken, "the freed slug is kept")
            assertEquals(NAME_KEY, MembershipEntity.find("userId = ?1 and scopeId = ?2", g.invitee, g.scopeId).firstResult()!!.sealedNameKey,
                "a pending invitee's name key is sealed to the invitee, no wrap under the scope key: the rotation leaves it")
            val share = ShareEntity.findById(g.shareId)!!
            assertEquals(NEW_COPY_WRAP, share.wrappedRecoveryValueKey, "the copy's key re-wrapped")
            assertEquals(COPY, share.sealedShareKey, "the recovery copy never rewritten")
            assertEquals(0, share.sealedShareKeyEpoch, "nor its epoch, the link's mint (ADR 0075 point 4)")
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
    fun aRotationMissingOneWrapIsRefusedAndWritesNothing() {
        val g = groupWorld()
        val rekeyId = UUID.randomUUID()
        stage(g.owner, g.scopeId, rekeyId, g.fullStage(shares = false)).statusCode(204)
        rotate(g.owner, g.scopeId, rekeyId).statusCode(409).body("code", equalTo("rekey_parts_missing"))
        g.assertUntouched()
        // Control: the missing wrap staged under the same id, the same rotation commits.
        stage(g.owner, g.scopeId, rekeyId, """{"fromEpoch":0,"shares":[${g.shareWrap()}]}""").statusCode(204)
        rotate(g.owner, g.scopeId, rekeyId).statusCode(201)
    }

    @Test
    fun aRemovalRotationRefusedForAMissingWrapLeavesTheMemberInPlace() {
        val g = groupWorld()
        val member = seedScopeMember(g.scopeId, grantedEpoch = 0)
        val rekeyId = UUID.randomUUID()
        stage(g.owner, g.scopeId, rekeyId, g.fullStage(icon = false)).statusCode(204)
        rotate(g.owner, g.scopeId, rekeyId, removes = member).statusCode(409).body("code", equalTo("rekey_parts_missing"))
        assertEquals(1L, tx { MembershipEntity.count("userId = ?1 and scopeId = ?2", member, g.scopeId) }, "the removal rolled back with it")
        g.assertUntouched()
        stage(g.owner, g.scopeId, rekeyId, """{"fromEpoch":0,"icon":${rewrap(NEW_ICON_WRAP, ICON_WRAP)}}""").statusCode(204)
        rotate(g.owner, g.scopeId, rekeyId, removes = member).statusCode(201)
        assertEquals(0L, tx { MembershipEntity.count("userId = ?1 and scopeId = ?2", member, g.scopeId) }, "control: the removal landed")
    }

    @Test
    fun aWrapReplacedSinceItWasStagedIsStale() {
        val g = groupWorld()
        val rekeyId = UUID.randomUUID()
        stage(g.owner, g.scopeId, rekeyId, g.fullStage()).statusCode(204)
        // The group is renamed at epoch 0 after the rotator staged: the rename minted a fresh value key, so the staged
        // re-wrap is of the key it replaced.
        rename(g, RENAMED, RENAMED_WRAP, epoch = 0).statusCode(204)
        rotate(g.owner, g.scopeId, rekeyId).statusCode(409).body("code", equalTo("rekey_parts_stale"))
        assertEquals(0, TestGrants.epochOf(g.scopeId), "nothing written")
        tx {
            val scope = ScopeEntity.findById(g.scopeId)!!
            assertEquals(RENAMED, scope.encryptedName, "the rename stands"); assertEquals(RENAMED_WRAP, scope.wrappedNameValueKey)
        }
        // Control: the name's key re-staged against the wrap the row holds now, the rotation commits.
        stage(g.owner, g.scopeId, rekeyId, """{"fromEpoch":0,"groupName":${rewrap(NEW_NAME_WRAP, RENAMED_WRAP)}}""").statusCode(204)
        rotate(g.owner, g.scopeId, rekeyId).statusCode(201)
        assertEquals(RENAMED, tx { ScopeEntity.findById(g.scopeId)!!.encryptedName }, "the rotation re-wrapped the renamed name's key, nothing more")
    }

    /** ADR 0115 decision 2: each staged wrap names the wrap it replaces, so a write between the rotator's read and its
     *  stage — which minted a fresh key — is refused by the commit like one after the stage, never replaced by a re-wrap
     *  of the old one. */
    @Test
    fun aWrapReplacedBetweenTheReadAndTheStageIsRefusedAtTheCommit() {
        val g = groupWorld()
        parts(g.owner, g.scopeId).statusCode(200).body("groupName.wrappedNameValueKey", equalTo(NAME_WRAP)) // the rotator reads
        rename(g, RENAMED, RENAMED_WRAP, epoch = 0).statusCode(204)
        val rekeyId = UUID.randomUUID()
        stage(g.owner, g.scopeId, rekeyId, g.fullStage()).statusCode(204) // against the wrap it read
        rotate(g.owner, g.scopeId, rekeyId).statusCode(409).body("code", equalTo("rekey_parts_stale"))
        assertEquals(RENAMED_WRAP, tx { ScopeEntity.findById(g.scopeId)!!.wrappedNameValueKey }, "the rename stands")
        // Control: re-read, the same batch against the renamed row, and the rotation commits.
        stage(g.owner, g.scopeId, rekeyId, g.fullStage(nameReplaced = RENAMED_WRAP)).statusCode(204)
        rotate(g.owner, g.scopeId, rekeyId).statusCode(201)
        tx {
            val scope = ScopeEntity.findById(g.scopeId)!!
            assertEquals(NEW_NAME_WRAP, scope.wrappedNameValueKey); assertEquals(RENAMED, scope.encryptedName)
        }
    }

    /** A root entry is held by its placement revision: a rename (or a move, or its own re-key) after the read moves it,
     *  and the re-seal of the entry it replaced is refused, nothing written. */
    @Test
    fun aRootEntryWrittenSinceItWasReadIsStale() {
        val g = groupWorld()
        val rekeyId = UUID.randomUUID()
        stage(g.owner, g.scopeId, rekeyId, g.fullStage()).statusCode(204)
        // A member renames the live root: a new name and key wrap at the next placement revision.
        tx { NodeEntity.findById(g.liveRoot)!!.apply { encryptedName = RENAMED; wrappedContentKey = RENAMED_WRAP; placementRevision += 1 } }
        rotate(g.owner, g.scopeId, rekeyId).statusCode(409).body("code", equalTo("rekey_parts_stale"))
        tx {
            val live = NodeEntity.findById(g.liveRoot)!!
            assertEquals(RENAMED, live.encryptedName, "the rename stands"); assertEquals(1L, live.placementRevision)
            assertEquals(0, ScopeEntity.findById(g.scopeId)!!.currentEpoch, "nothing written")
        }
        // Control: the entry re-sealed against the revision it holds now, the rotation commits.
        stage(g.owner, g.scopeId, rekeyId, g.fullStage(liveRevision = 1)).statusCode(204)
        rotate(g.owner, g.scopeId, rekeyId).statusCode(201)
        assertEquals(2L, tx { NodeEntity.findById(g.liveRoot)!!.placementRevision })
    }

    /** A stage carries exactly its schema: a wrap without the wrap it replaces, or a field the schema does not name — the
     *  digest the wire carried before, say — is malformed, nothing staged. */
    @Test
    fun aStageWithoutTheWrapItReplacesOrWithAFieldItDoesNotNameIsMalformed() {
        val owner = seedUser()
        val tenantId = seedTenant(owner = owner)
        tx { profile(owner, tenantId, name = "b3duZXI=", avatar = null, join = null) }
        val rekeyId = UUID.randomUUID()
        for (body in listOf(
            """{"fromEpoch":0,"members":[{"userId":"$owner","name":{"wrappedValueKey":"bmV3"}}]}""",
            """{"fromEpoch":0,"members":[{"userId":"$owner","name":{"replacedValueKey":"${wrapOf("b3duZXI=")}"}}]}""",
            """{"fromEpoch":0,"members":[{"userId":"$owner","wrappedNameValueKey":"bmV3","replacedNameDigest":"bmV3"}]}""",
            """{"fromEpoch":0,"members":[{"userId":"$owner","name":${rewrap("bmV3", wrapOf("b3duZXI="))}}],"sealedName":"Zm9yZ2Vk"}""",
        )) {
            stage(owner, tenantId, rekeyId, body).statusCode(400).body("code", equalTo("invalid_request"))
        }
        assertEquals(0L, tx { ScopeRekeyStageEntity.count("rekeyId", rekeyId.toString()) }, "nothing staged")
        // Control: the wrap beside the wrap it replaces stages.
        stage(owner, tenantId, rekeyId, """{"fromEpoch":0,"members":[{"userId":"$owner","name":${rewrap("bmV3", wrapOf("b3duZXI="))}}]}""")
            .statusCode(204)
    }

    /**
     * The owner's decision of 2026-11-02, on the wire: a member who secured their account holds a pending re-key row and
     * may run the tenant's rotation (ADR 0111 decision 8) — and before the value keys its stage re-sealed every other
     * member's name, so it could rewrite them. A value field smuggled into the stage's body has no field to land in and is
     * refused, `400 invalid_request`, nothing staged; a staged write of a value that reaches the stage anyway (a stage
     * left from before the value keys, a forged row) refuses the whole commit, `422 rekey_writes_value`, nothing written.
     * Control: the same commit carrying only the re-wrapped keys lands, and the owner's name is byte-identical.
     */
    @Test
    fun aMemberRotatorsCommitThatRewritesAnotherMembersNameIsRefusedAndWritesNothing() {
        val t = tenantWorld()
        tx { RotationRegrantEntity().apply { id = UUID.randomUUID().toString(); userId = t.member; scopeId = t.tenantId; epochAtRotation = 0 }.persist() }
        val rekeyId = UUID.randomUUID()
        // A value field in the body: the schema has none, so the batch is refused and nothing of it is staged.
        val smuggled = tenantStage(t.owner, t.member).replace(
            """{"userId":"${t.owner}",""", """{"userId":"${t.owner}","sealedName":"Zm9yZ2Vk",""",
        )
        stage(t.member, t.tenantId, rekeyId, smuggled).statusCode(400).body("code", equalTo("invalid_request"))
        assertEquals(0L, tx { ScopeRekeyStageEntity.count("rekeyId", rekeyId.toString()) }, "nothing staged")
        stage(t.member, t.tenantId, rekeyId, tenantStage(t.owner, t.member)).statusCode(204)
        // A staged write of the owner's name, as a stage from before the value keys held it.
        tx {
            ScopeRekeyStageEntity().apply {
                id = UUID.randomUUID().toString(); this.rekeyId = rekeyId.toString(); scopeId = t.tenantId; part = ScopeRekeyStage.CORE
                itemId = t.owner; field = "member.sealedName"; stageValue = "Zm9yZ2Vk"
                replaced = wrapOf("b3duZXI="); createdAt = Instant.now()
            }.persist()
        }
        rotate(t.member, t.tenantId, rekeyId).statusCode(422).body("code", equalTo("rekey_writes_value"))
        tx {
            val p = TenantProfileEntity.find("userId = ?1 and tenantId = ?2", t.owner, t.tenantId).firstResult()!!
            assertEquals("b3duZXI=", p.sealedName, "the owner's name stands"); assertEquals(wrapOf("b3duZXI="), p.wrappedNameValueKey, "and its wrap")
            assertEquals(0, ScopeEntity.findById(t.tenantId)!!.currentEpoch, "nothing written")
            assertEquals(0L, me.raddatz.veyq.core.spaces.scope.ScopeSuccessionEntity.count("scopeId", t.tenantId))
        }
        // Control: the forged row gone, the same commit carrying only the re-wrapped keys lands.
        tx { ScopeRekeyStageEntity.delete("rekeyId = ?1 and field = ?2", rekeyId.toString(), "member.sealedName") }
        rotate(t.member, t.tenantId, rekeyId).statusCode(201).body("epoch", equalTo(1))
        tx {
            val p = TenantProfileEntity.find("userId = ?1 and tenantId = ?2", t.owner, t.tenantId).firstResult()!!
            assertEquals("b3duZXI=", p.sealedName, "a rotation never rewrites a name")
            assertEquals("bmV3LW93bmVy", p.wrappedNameValueKey, "its key re-wrapped")
        }
    }

    /** The server cannot tell one wrap from another: a wrap of another value's key moved into this slot is staged and
     *  committed like any other. That it opens as no value — unreadable, never another member's name — is the crypto
     *  package's to prove (`valueKey.conformance.test.ts`: the value under a substituted wrap does not open). */
    @Test
    fun aSubstitutedWrapIsAcceptedTheServerCannotTellItFromAnother() {
        val t = tenantWorld()
        val rekeyId = UUID.randomUUID()
        val substituted = tenantStage(t.owner, t.member).replace("bmV3LW1lbWJlcg==", wrapOf("b3duZXI="))
        stage(t.owner, t.tenantId, rekeyId, substituted).statusCode(204)
        rotate(t.owner, t.tenantId, rekeyId).statusCode(201)
        tx {
            val p = TenantProfileEntity.find("userId = ?1 and tenantId = ?2", t.member, t.tenantId).firstResult()!!
            assertEquals(wrapOf("b3duZXI="), p.wrappedNameValueKey, "the moved wrap is stored as sent")
            assertEquals("bWVtYmVy", p.sealedName, "and the name beside it is untouched")
        }
    }

    /** A rename after a rotation mints a fresh value key: the stored wrap is the rename's, not the one the rotation
     *  re-wrapped, beside the new name at the new epoch — and each pending invitee gets the new key sealed to them. */
    @Test
    fun anOwnersRenameAfterARotationStoresAFreshWrapAndReKeysTheInvitee() {
        val g = groupWorld()
        val rekeyId = UUID.randomUUID()
        stage(g.owner, g.scopeId, rekeyId, g.fullStage()).statusCode(204)
        rotate(g.owner, g.scopeId, rekeyId).statusCode(201)
        assertEquals(NEW_NAME_WRAP, tx { ScopeEntity.findById(g.scopeId)!!.wrappedNameValueKey })

        // Refused, nothing written: a pending invitee holding a name key left out (it would open no name), and one
        // sealed to a key that is not the invitee's.
        given().header("Authorization", bearer(g.owner)).contentType(ContentType.JSON)
            .body("""{"encryptedName":"$RENAMED","nameEpoch":1,"wrappedNameValueKey":"$RENAMED_WRAP","inviteeNameKeys":[]}""")
            .`when`().post("/api/scopes/${g.scopeId}/name").then().statusCode(409).body("code", equalTo("rename_incomplete"))
        rename(g, RENAMED, RENAMED_WRAP, epoch = 1, inviteeDigest = TestGrants.digest(TestGrants.WRAPPED))
            .statusCode(409).body("code", equalTo("recipient_key_not_current"))
        given().header("Authorization", bearer(g.owner)).contentType(ContentType.JSON)
            .body("""{"encryptedName":"$RENAMED","nameEpoch":1,"wrappedNameValueKey":"$RENAMED_WRAP","inviteeNameKeys":[{"userId":"${g.invitee}","sealedNameKey":null,"recipientIdentityDigest":"${TestGrants.digestOf(g.invitee)}"}]}""")
            .`when`().post("/api/scopes/${g.scopeId}/name").then().statusCode(400).body("code", equalTo("invalid_request"))
        tx {
            val scope = ScopeEntity.findById(g.scopeId)!!
            assertEquals(NAME, scope.encryptedName); assertEquals(NEW_NAME_WRAP, scope.wrappedNameValueKey)
            assertEquals(NAME_KEY, MembershipEntity.find("userId = ?1 and scopeId = ?2", g.invitee, g.scopeId).firstResult()!!.sealedNameKey)
        }

        rename(g, RENAMED, RENAMED_WRAP, epoch = 1).statusCode(204)
        tx {
            val scope = ScopeEntity.findById(g.scopeId)!!
            assertEquals(RENAMED, scope.encryptedName); assertEquals(1, scope.nameEpoch)
            assertEquals(RENAMED_WRAP, scope.wrappedNameValueKey)
            assertNotEquals(NEW_NAME_WRAP, scope.wrappedNameValueKey, "a fresh key's wrap, not the one the rotation re-wrapped")
            assertEquals(RENAMED_KEY, MembershipEntity.find("userId = ?1 and scopeId = ?2", g.invitee, g.scopeId).firstResult()!!.sealedNameKey)
        }
        // An invitee the owner's device cannot anchor: both null clears its name key; the proposal stands.
        given().header("Authorization", bearer(g.owner)).contentType(ContentType.JSON)
            .body("""{"encryptedName":"$RENAMED","nameEpoch":1,"wrappedNameValueKey":"$NEW_NAME_WRAP","inviteeNameKeys":[{"userId":"${g.invitee}","sealedNameKey":null,"recipientIdentityDigest":null}]}""")
            .`when`().post("/api/scopes/${g.scopeId}/name").then().statusCode(204)
        tx {
            val m = MembershipEntity.find("userId = ?1 and scopeId = ?2", g.invitee, g.scopeId).firstResult()!!
            assertTrue(m.pendingConsent); assertNull(m.sealedNameKey)
        }
    }

    @Test
    fun everyStagedTenantProfileAndVerificationWrapIsSwappedInAndNoValueMoves() {
        val t = tenantWorld()
        parts(t.owner, t.tenantId).statusCode(200).body("members.size()", equalTo(2)).body("verifications.size()", equalTo(1))
            .body("verifications[0].epoch", equalTo(0)).body("verifications[0].wrappedValueKey", equalTo(wrapOf("dmVyaWZpZWQ=")))
            .body("verifications[0].sealedStatement", nullValue())
            .body("members.find { it.userId == '${t.member}' }.wrappedAvatarValueKey", equalTo(wrapOf("YXZhdGFy")))
            .body("members.find { it.userId == '${t.member}' }.sealedName", nullValue())
        val rekeyId = UUID.randomUUID()
        // A wrap staged where the row holds none is stale: the owner has no picture — refused by the commit, which
        // compares every staged wrap with its row (ADR 0115 decision 2), nothing written.
        stage(t.owner, t.tenantId, rekeyId, tenantStage(t.owner, t.member, ownerAvatar = true)).statusCode(204)
        rotate(t.owner, t.tenantId, rekeyId).statusCode(409).body("code", equalTo("rekey_parts_stale"))
        assertEquals(wrapOf("bWVtYmVy"), tx { TenantProfileEntity.find("userId", t.member).firstResult()!!.wrappedNameValueKey }, "nothing written")
        // Control: the owner's row staged again without it — a row staged twice replaces all its fields — and it commits.
        stage(t.owner, t.tenantId, rekeyId, tenantStage(t.owner, t.member)).statusCode(204)
        rotate(t.owner, t.tenantId, rekeyId).statusCode(201)
        tx {
            val p = TenantProfileEntity.find("userId = ?1 and tenantId = ?2", t.member, t.tenantId).firstResult()!!
            assertEquals("bmV3LW1lbWJlcg==", p.wrappedNameValueKey); assertEquals("bmV3LWF2YXRhcg==", p.wrappedAvatarValueKey)
            assertEquals("bmV3LWpvaW4=", p.wrappedJoinRecordValueKey)
            assertEquals(listOf("bWVtYmVy", "YXZhdGFy", "am9pbg=="), listOf(p.sealedName, p.sealedAvatar, p.sealedJoinRecord), "no value rewritten")
            assertEquals(1, p.avatarRevision, "a new wrap beside the picture moves its revision")
            assertEquals("ZW5kb3JzZWQ=", p.joinEndorsement, "the endorsement signs the opened record, which a re-wrap leaves (decision 8)")
            assertEquals("bmV3LW93bmVy", TenantProfileEntity.find("userId = ?1 and tenantId = ?2", t.owner, t.tenantId).firstResult()!!.wrappedNameValueKey)
            val v = VerificationEntity.findById(VerificationKey(t.tenantId, t.owner, t.member))!!
            assertEquals("bmV3LXZlcmlmaWVk", v.wrappedValueKey)
            assertEquals("dmVyaWZpZWQ=", v.sealedStatement, "the statement never rewritten"); assertEquals(0, v.epoch, "nor its epoch")
        }
    }

    @Test
    fun aScopeLevelWriteWrappedUnderTheOldEpochIsStale() {
        val owner = seedUser()
        val tenantId = seedTenant(owner = owner)
        val member = seedScopeMember(tenantId, grantedEpoch = 0)
        tx { profile(owner, tenantId, name = "b3duZXI=", avatar = null, join = null) }
        rotate(owner, tenantId, UUID.fromString(TestScopeRekey.stageAll(tenantId))).statusCode(201)

        fun asOwner() = given().header("Authorization", bearer(owner)).header("X-Forwarded-Host", domainOf(tenantId)).contentType(ContentType.JSON)
        val w = TestGrants.VALUE_KEY_WRAP
        asOwner().body("""{"sealedName":"c3RhbGU=","wrappedNameValueKey":"$w","epoch":0}""").`when`().put("/api/account/profile").then()
            .statusCode(409).body("code", equalTo("stale_epoch"))
        asOwner().body("""{"sealedAvatar":"c3RhbGU=","wrappedAvatarValueKey":"$w","epoch":0}""").`when`().put("/api/account/profile/avatar").then()
            .statusCode(409).body("code", equalTo("stale_epoch"))
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{"epoch":0,"sealedStatement":"c3RhbGU=","wrappedValueKey":"$w"}""")
            .`when`().put("/api/tenants/$tenantId/verifications/$member").then()
            .statusCode(409).body("code", equalTo("stale_epoch"))
        tx {
            assertEquals("b3duZXI=", TenantProfileEntity.find("userId", owner).firstResult()!!.sealedName, "nothing written")
            assertEquals(0L, VerificationEntity.count("tenantId", tenantId))
        }
        // Controls: the same writes at the current epoch land, each storing its wrap beside its value.
        asOwner().body("""{"sealedName":"Y3VycmVudA==","wrappedNameValueKey":"$w","epoch":1}""").`when`().put("/api/account/profile").then()
            .statusCode(200).body("wrappedNameValueKey", equalTo(w))
        asOwner().body("""{"sealedAvatar":"Y3VycmVudA==","wrappedAvatarValueKey":"$w","epoch":1}""").`when`().put("/api/account/profile/avatar").then().statusCode(204)
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{"epoch":1,"sealedStatement":"Y3VycmVudA==","wrappedValueKey":"$w"}""")
            .`when`().put("/api/tenants/$tenantId/verifications/$member").then().statusCode(204)
        tx {
            val p = TenantProfileEntity.find("userId", owner).firstResult()!!
            assertEquals(w, p.wrappedNameValueKey); assertEquals(w, p.wrappedAvatarValueKey)
            assertEquals(w, VerificationEntity.findById(VerificationKey(tenantId, owner, member))!!.wrappedValueKey)
        }
        // A sealed name without the epoch of its wrap, or without its wrap, or a wrap without a name, is the request's
        // own fault.
        asOwner().body("""{"sealedName":"Y3VycmVudA==","wrappedNameValueKey":"$w"}""").`when`().put("/api/account/profile").then()
            .statusCode(400).body("code", equalTo("invalid_request"))
        asOwner().body("""{"sealedName":"Y3VycmVudA==","epoch":1}""").`when`().put("/api/account/profile").then()
            .statusCode(400).body("code", equalTo("invalid_request"))
        asOwner().body("""{"wrappedNameValueKey":"$w","epoch":1}""").`when`().put("/api/account/profile").then()
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

    /**
     * The listing's cursor and the contract's `ScopeRekeySince` are one shape, both ways. Every cursor the server answers
     * — a page per row, through every kind a group and a tenant hold — matches the contract's pattern, so a client that
     * echoes it is never refused. A value outside it is the `400 invalid_request` the operation declares: the API
     * fuzzing's `since` of 79 zeros, which the contract once admitted while the server could not read it (ST202, run of
     * 2026-11-05), and every near miss — the contract refuses each too. A value inside it that names no row is read,
     * never refused (a schema-valid request is no 400) and never a 500: `verifs,a|b`, which a looser pattern admitted,
     * reached the uuid column and failed in the database.
     */
    @Test
    fun theCursorIsTheContractsShapeBothWays() {
        val shape = Regex(sincePattern())
        val g = groupWorld()
        val t = tenantWorld()
        val kinds = mutableSetOf<String>()
        for ((owner, scopeId) in listOf(g.owner to g.scopeId, t.owner to t.tenantId)) {
            var since: String? = null
            do {
                val page = parts(owner, scopeId, since = since, limit = 1).statusCode(200).extract()
                since = page.path<String?>("cursor")
                since?.let {
                    assertTrue(shape.matches(it), "the server answered the cursor '$it', which the contract's $shape refuses")
                    kinds += it.substringBefore(',')
                }
            } while (page.path<Boolean>("more"))
        }
        assertEquals(ScopeRekeyCommit.KINDS.toSet(), kinds, "every kind's cursor was answered and held to the contract")

        val (u, v) = UUID.randomUUID() to UUID.randomUUID()
        for (refused in listOf(
            "0".repeat(79), "nodes,zzzz", "nodes,${u.toString().uppercase()}", "shares,0", "shares,${"A".repeat(44)}",
            "members,$u,", "verifs,$u", "verifs,a|b", "verifs,$u|$v|$u", "groups,$u", ",$u", "nodes",
        )) {
            assertFalse(shape.matches(refused), "the contract admits '$refused'")
            parts(t.owner, t.tenantId, since = refused, limit = 256).statusCode(400).body("code", equalTo("invalid_request"))
        }
        for (admitted in listOf("nodes,$u", "shares,${"A".repeat(43)}", "shares,${"-_".repeat(21)}z", "members,$u", "verifs,$u|$v")) {
            assertTrue(shape.matches(admitted), "the contract refuses '$admitted'")
            parts(t.owner, t.tenantId, since = admitted).statusCode(200)
        }
    }

    // --- the worlds ---

    private inner class GroupWorld(
        val owner: String, val scopeId: String, val liveRoot: String, val trashedRoot: String, val child: String,
        val invitee: String, val shareId: String,
    ) {
        fun shareWrap() = """{"shareId":"$shareId","wrappedValueKey":"$NEW_COPY_WRAP","replacedValueKey":"$COPY_WRAP"}"""

        /** Every value key re-wrapped beside the wrap it replaces and every root entry re-sealed at the placement
         *  revision it read (ADR 0115 decision 2) — no value's ciphertext anywhere. */
        fun fullStage(shares: Boolean = true, icon: Boolean = true, nameReplaced: String = NAME_WRAP, liveRevision: Int = 0): String =
            """{"fromEpoch":0,"groupName":${rewrap(NEW_NAME_WRAP, nameReplaced)},""" +
                (if (icon) """"icon":${rewrap(NEW_ICON_WRAP, ICON_WRAP)},""" else "") +
                """"rootNodes":[""" +
                """{"nodeId":"$liveRoot","placementRevision":$liveRevision,"scopeEpoch":0,"encryptedName":"$NEW_ROOT_NAME","slugToken":"$NEW_SLUG","wrappedContentKey":"$NEW_WRAP"},""" +
                """{"nodeId":"$trashedRoot","placementRevision":0,"scopeEpoch":0,"encryptedName":"$NEW_ROOT_NAME","wrappedContentKey":"$NEW_WRAP"}]""" +
                (if (shares) ""","shares":[${shareWrap()}]""" else "") + "}"

        /** Nothing a refused rotation could have written: the epoch, the wraps, the values, the marks, the statement. */
        fun assertUntouched() = tx {
            val scope = ScopeEntity.findById(scopeId)!!
            assertEquals(0, scope.currentEpoch)
            assertEquals(NAME, scope.encryptedName); assertEquals(NAME_WRAP, scope.wrappedNameValueKey)
            assertEquals(ICON, scope.sealedIcon); assertEquals(ICON_WRAP, scope.wrappedIconValueKey)
            assertEquals(CONTENT_KEY, NodeEntity.findById(liveRoot)!!.wrappedContentKey)
            assertEquals(0L, NodeEntity.findById(liveRoot)!!.placementRevision)
            val share = ShareEntity.findById(shareId)!!
            assertEquals(COPY, share.sealedShareKey); assertEquals(COPY_WRAP, share.wrappedRecoveryValueKey)
            assertEquals(NAME_KEY, MembershipEntity.find("userId = ?1 and scopeId = ?2", invitee, scopeId).firstResult()!!.sealedNameKey)
            assertFalse(NodeEntity.list("scopeId", scopeId).any { it.rekeyDue }, "no node marked due")
            assertEquals(0L, me.raddatz.veyq.core.spaces.scope.ScopeSuccessionEntity.count("scopeId", scopeId))
        }
    }

    /** A group at epoch 0: a live root folder with a child, a trashed root folder, its name and icon each under a value
     *  key of its own, a pending consent proposal holding the name's key, and a link on the live root with its recovery
     *  copy (`ShareWriteProof.recovery`'s, under the stand-in wrap). */
    private fun groupWorld(): GroupWorld {
        val (owner, scopeId, _) = seedMemberScope(ScopeEntity.GROUP)
        val live = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val child = seedNode(scopeId, NodeEntity.FOLDER, parentId = live, wrappedContentKey = CONTENT_KEY)
        val trashed = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val invitee = seedUser()
        tx {
            NodeEntity.findById(trashed)!!.apply { deletedAt = Instant.now(); slugToken = id }
            ScopeEntity.findById(scopeId)!!.apply {
                encryptedName = NAME; wrappedNameValueKey = NAME_WRAP; sealedIcon = ICON; wrappedIconValueKey = ICON_WRAP
            }
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); userId = invitee; tenantId = ScopeEntity.findById(scopeId)!!.tenantId
                this.scopeId = scopeId; role = MembershipEntity.MEMBER; wrappedGrant = TestGrants.WRAPPED; pendingConsent = true
                sealedNameKey = NAME_KEY; addedBy = owner; createdAt = Instant.now()
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

    private data class TenantWorld(val owner: String, val member: String, val tenantId: String)

    /** A tenant at epoch 0: the owner's name, the member's name, picture and join record (endorsed), each under its own
     *  value key ([wrapOf] its value), and the owner's verification of the member. */
    private fun tenantWorld(): TenantWorld {
        val owner = seedUser()
        val tenantId = seedTenant(owner = owner)
        val member = seedScopeMember(tenantId, grantedEpoch = 0)
        tx {
            profile(owner, tenantId, name = "b3duZXI=", avatar = null, join = null)
            profile(member, tenantId, name = "bWVtYmVy", avatar = "YXZhdGFy", join = "am9pbg==").joinEndorsement = "ZW5kb3JzZWQ="
            VerificationEntity().apply {
                this.tenantId = tenantId; verifierUserId = owner; subjectUserId = member; epoch = 0
                sealedStatement = "dmVyaWZpZWQ="; wrappedValueKey = wrapOf("dmVyaWZpZWQ="); createdAt = Instant.now()
            }.persist()
        }
        return TenantWorld(owner, member, tenantId)
    }

    /** A profile whose every field sits under a value key of its own: the stand-in wrap of each is [wrapOf] the value. */
    private fun profile(userId: String, tenantId: String, name: String?, avatar: String?, join: String?): TenantProfileEntity {
        val p = TenantProfileEntity.find("userId = ?1 and tenantId = ?2", userId, tenantId).firstResult() ?: TenantProfileEntity().apply {
            id = UUID.randomUUID().toString(); this.userId = userId; this.tenantId = tenantId; username = "u${userId.take(8)}"
        }
        p.sealedName = name; p.wrappedNameValueKey = name?.let(::wrapOf)
        p.sealedAvatar = avatar; p.wrappedAvatarValueKey = avatar?.let(::wrapOf)
        p.sealedJoinRecord = join; p.wrappedJoinRecordValueKey = join?.let(::wrapOf)
        p.persist()
        return p
    }

    private fun tenantStage(owner: String, member: String, ownerAvatar: Boolean = false): String {
        val ownerAvatarWrap = if (ownerAvatar) ""","avatar":${rewrap("bmV3LWF2YXRhcg==", wrapOf("YXZhdGFy"))}""" else ""
        return """{"fromEpoch":0,"members":[""" +
            """{"userId":"$owner","name":${rewrap("bmV3LW93bmVy", wrapOf("b3duZXI="))}$ownerAvatarWrap},""" +
            """{"userId":"$member","name":${rewrap("bmV3LW1lbWJlcg==", wrapOf("bWVtYmVy"))},""" +
            """"avatar":${rewrap("bmV3LWF2YXRhcg==", wrapOf("YXZhdGFy"))},"joinRecord":${rewrap("bmV3LWpvaW4=", wrapOf("am9pbg=="))}}],""" +
            """"verifications":[{"verifierUserId":"$owner","subjectUserId":"$member","wrappedValueKey":"bmV3LXZlcmlmaWVk",""" +
            """"replacedValueKey":"${wrapOf("dmVyaWZpZWQ=")}"}]}"""
    }

    /** The owner's rename of [g]'s group: the name under a fresh key, that key's [wrap], and the key sealed to the pending
     *  invitee (to [inviteeDigest]'s identity). */
    private fun rename(
        g: GroupWorld, name: String, wrap: String, epoch: Int, inviteeDigest: String = TestGrants.digestOf(g.invitee),
    ): ValidatableResponse =
        given().header("Authorization", bearer(g.owner)).contentType(ContentType.JSON)
            .body(
                """{"encryptedName":"$name","nameEpoch":$epoch,"wrappedNameValueKey":"$wrap",""" +
                    """"inviteeNameKeys":[{"userId":"${g.invitee}","sealedNameKey":"$RENAMED_KEY","recipientIdentityDigest":"$inviteeDigest"}]}""",
            )
            .`when`().post("/api/scopes/${g.scopeId}/name").then()

    /** A stand-in wrap of the value key [value] is sealed under — distinct per value, as each write mints its own key. */
    private fun wrapOf(value: String): String = "d3JhcC0$value"

    /** One value key moved: [wrapped] under the new key, beside the wrap it [replaced] as the rotator read it. */
    private fun rewrap(wrapped: String, replaced: String): String = """{"wrappedValueKey":"$wrapped","replacedValueKey":"$replaced"}"""

    private fun domainOf(tenantId: String): String = tx { me.raddatz.veyq.core.tenant.TenantEntity.findById(tenantId)!!.domain }

    // --- the wire ---

    private fun parts(caller: String, scopeId: String, since: String? = null, limit: Int? = null): ValidatableResponse =
        given().header("Authorization", bearer(caller))
            .apply { since?.let { queryParam("since", it) }; limit?.let { queryParam("limit", it) } }
            .`when`().get("/api/scopes/$scopeId/rekey/scope/parts").then()

    private fun stage(caller: String, scopeId: String, rekeyId: UUID, body: String): ValidatableResponse =
        given().header("Authorization", bearer(caller)).contentType(ContentType.JSON).body(body)
            .`when`().post("/api/scopes/$scopeId/rekey/scope/$rekeyId/parts").then()

    /** [rotator]'s rotation to the next epoch under [rekeyId]: itself re-granted, everyone else declared pending. */
    private fun rotate(rotator: String, scopeId: String, rekeyId: UUID, removes: String? = null): ValidatableResponse =
        given().header("Authorization", bearer(rotator)).contentType(ContentType.JSON)
            .body(
                """{"epoch":${TestGrants.epochOf(scopeId) + 1},${TestScopeRekey.SUCCESSION_JSON},"rekeyId":"$rekeyId",""" +
                    """"grants":[{"userId":"$rotator","wrappedGrant":"bmV3",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"${TestGrants.digestOf(rotator)}"}],""" +
                    """${TestGrants.othersPendingJson(scopeId, rotator, removes)},${TestGrants.openInvitesJson(scopeId)}""" +
                    (removes?.let { ""","removesUserId":"$it"""" } ?: "") + "}",
            )
            .`when`().post("/api/scopes/$scopeId/epochs").then()

    private fun <T> tx(block: () -> T): T = QuarkusTransaction.requiringNew().call(block)

    /** `ScopeRekeySince`'s pattern as `contracts/api/content.openapi.yaml` states it — a missing one is a failure. */
    @Suppress("UNCHECKED_CAST")
    private fun sincePattern(): String {
        val spec = Yaml().load<Map<String, Any?>>(File("../../contracts/api/content.openapi.yaml").readText())
        val parameter = ((spec["components"] as Map<String, Any?>)["parameters"] as Map<String, Any?>)["ScopeRekeySince"] as Map<String, Any?>
        return (parameter["schema"] as Map<String, Any?>)["pattern"] as? String ?: error("ScopeRekeySince states no pattern")
    }

    private companion object {
        const val COPY = "cmVjb3Zlcnk="
        const val COPY_WRAP = TestGrants.VALUE_KEY_WRAP
        const val NAME_KEY = "bmFtZS1rZXk="
        const val NAME = "Z3JvdXAtbmFtZQ=="
        const val NAME_WRAP = "bmFtZS13cmFw"
        const val ICON = "aWNvbg=="
        const val ICON_WRAP = "aWNvbi13cmFw"
        const val RENAMED = "cmVuYW1lZA=="
        const val RENAMED_WRAP = "cmVuYW1lZC13cmFw"
        const val RENAMED_KEY = "cmVuYW1lZC1rZXk="
        const val NEW_NAME_WRAP = "bmV3LW5hbWUtd3JhcA=="
        const val NEW_ICON_WRAP = "bmV3LWljb24td3JhcA=="
        const val NEW_ROOT_NAME = "bmV3LXJvb3QtbmFtZQ=="
        const val NEW_SLUG = "bmV3LXNsdWc="
        const val NEW_WRAP = "bmV3LXJvb3Qtd3JhcA=="
        const val NEW_COPY_WRAP = "bmV3LWNvcHktd3JhcA=="
    }
}
