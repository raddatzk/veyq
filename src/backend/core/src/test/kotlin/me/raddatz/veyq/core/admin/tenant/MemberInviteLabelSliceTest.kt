package me.raddatz.veyq.core.admin.tenant

import me.raddatz.veyq.core.crypto.SealedValue
import me.raddatz.veyq.core.crypto.Tokens
import me.raddatz.veyq.core.membership.TestGrants
import me.raddatz.veyq.core.membership.TestInviteKey
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedScopeMember
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import me.raddatz.veyq.core.testing.TestScopeRekey
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import io.restassured.response.ValidatableResponse
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * A link member invite's private label (ADR 0129 decision 7, uc.spaces.manage-member-invites step 4): set at mint or
 * later, changed and cleared by an owner, its last editor recorded (ADR 0093 § 8), served only in the owners' pending
 * list, dropped with the invite's key and grant, and its value key's wrap — never the label — moved by a tenant
 * rotation. Every refusal has a control on the same predicate that passes. Distinct domains per test (one JVM, one H2).
 */
@QuarkusTest
class MemberInviteLabelSliceTest {

    @Inject lateinit var store: TenantStore

    @Test
    fun aLinkMintsLabelIsListedToTheOwnersAndNeverInTheOpenInvites(): Unit = TestMailbox(MINT_SMTP_PORT).start().use { mail ->
        val (tenantId, owner) = seedActiveTenant("label-mint.example.com", mail)
        val member = seedScopeMember(tenantId, grantedEpoch = 0)
        val labelled = TestInviteKey()
        mintLink(owner, tenantId, labelled, label = LABEL).then().statusCode(201)
        val plain = TestInviteKey()
        mintLink(owner, tenantId, plain).then().statusCode(201)

        assertEquals(listOf(LABEL, LABEL_WRAP, owner), tx { TenantInviteEntity.findById(labelled.inviteId)!!.run { listOf(sealedLabel, wrappedLabelValueKey, labelEditorUserId) } })
        list(owner, tenantId).statusCode(200)
            .body("find { it.inviteId == '${labelled.inviteId}' }.sealedLabel", equalTo(LABEL))
            .body("find { it.inviteId == '${labelled.inviteId}' }.wrappedLabelValueKey", equalTo(LABEL_WRAP))
            .body("find { it.inviteId == '${plain.inviteId}' }.sealedLabel", nullValue())
        // Every member reads the open invites to carry them through a rotation — never the label.
        given().header("Authorization", bearer(member)).`when`().get("/api/scopes/$tenantId/open-invites")
            .then().statusCode(200).body("find { it.inviteId == '${labelled.inviteId}' }.inviteId", equalTo(labelled.inviteId))
            .body("find { it.inviteId == '${labelled.inviteId}' }.sealedLabel", nullValue())
            .body("find { it.inviteId == '${labelled.inviteId}' }.wrappedLabelValueKey", nullValue())
        // The pending list itself stays the owners'.
        list(member, tenantId).statusCode(403).body("code", equalTo("not_tenant_owner"))
    }

    @Test
    fun aLabelWithoutItsWrapIsTheRequestsOwnFault(): Unit = TestMailbox(MALFORMED_SMTP_PORT).start().use { mail ->
        val (tenantId, owner) = seedActiveTenant("label-malformed.example.com", mail)
        val key = TestInviteKey()
        mintLink(owner, tenantId, key, extra = ""","sealedLabel":"$LABEL"""").then().statusCode(400).body("code", equalTo("invalid_request"))
        mintLink(owner, tenantId, key, extra = ""","wrappedLabelValueKey":"$LABEL_WRAP"""").then().statusCode(400).body("code", equalTo("invalid_request"))
        assertEquals(null, tx { TenantInviteEntity.findById(key.inviteId) }, "nothing minted")
        // Control: both together mint.
        mintLink(owner, tenantId, key, label = LABEL).then().statusCode(201)

        val (_, invite) = seedLinkInvite(tenantId, owner)
        setLabel(owner, tenantId, invite.inviteId, """{"sealedLabel":"$LABEL","wrappedLabelValueKey":"$LABEL_WRAP"}""").statusCode(400)
        setLabel(owner, tenantId, invite.inviteId, """{"sealedLabel":"$LABEL","wrappedLabelValueKey":null,"epoch":0}""").statusCode(400)
        setLabel(owner, tenantId, invite.inviteId, """{"sealedLabel":null,"wrappedLabelValueKey":null,"epoch":0}""").statusCode(400)
        assertEquals(null, labelOf(invite.inviteId), "nothing written")
        setLabel(owner, tenantId, invite.inviteId, labelBody(LABEL, LABEL_WRAP, 0)).statusCode(204)
        assertEquals(SealedValue(LABEL, LABEL_WRAP), labelOf(invite.inviteId))
    }

    @Test
    fun anOwnerSetsChangesAndClearsALabelAndIsItsLastEditor() {
        val owner = seedUser()
        val tenantId = seedTenant(owner = owner)
        val member = seedScopeMember(tenantId, grantedEpoch = 0)
        val (_, invite) = seedLinkInvite(tenantId, owner)

        setLabel(member, tenantId, invite.inviteId, labelBody(LABEL, LABEL_WRAP, 0)).statusCode(403).body("code", equalTo("not_tenant_owner"))
        assertEquals(null, labelOf(invite.inviteId), "a member's write is refused, nothing written")

        setLabel(owner, tenantId, invite.inviteId, labelBody(LABEL, LABEL_WRAP, 0)).statusCode(204)
        setLabel(owner, tenantId, invite.inviteId, labelBody(OTHER_LABEL, OTHER_WRAP, 0)).statusCode(204)
        assertEquals(SealedValue(OTHER_LABEL, OTHER_WRAP), labelOf(invite.inviteId))
        assertEquals(owner, tx { TenantInviteEntity.findById(invite.inviteId)!!.labelEditorUserId })

        setLabel(owner, tenantId, invite.inviteId, """{"sealedLabel":null,"wrappedLabelValueKey":null,"epoch":null}""").statusCode(204)
        tx {
            val row = TenantInviteEntity.findById(invite.inviteId)!!
            assertEquals(listOf(null, null, null), listOf(row.sealedLabel, row.wrappedLabelValueKey, row.labelEditorUserId), "clearing a label clears its editor")
        }
    }

    @Test
    fun aMailedAnEndedAndAnotherTenantsInviteHaveNoLabelToSet() {
        val owner = seedUser()
        val tenantId = seedTenant(owner = owner)
        val (_, mailed) = seedInvite(tenantId, owner, "mailed@label-none.example.com")
        val (_, revoked) = seedLinkInvite(tenantId, owner)
        tx { store.revokeInvite(revoked.inviteId) }
        val (_, overdue) = seedInvite(tenantId, owner, null, Instant.now().minus(1, ChronoUnit.MINUTES))
        val otherOwner = seedUser()
        val otherTenant = seedTenant(owner = otherOwner)
        val (_, foreign) = seedLinkInvite(otherTenant, otherOwner)

        for (id in listOf(mailed.inviteId, revoked.inviteId, overdue.inviteId, foreign.inviteId, UUID.randomUUID().toString())) {
            setLabel(owner, tenantId, id, labelBody(LABEL, LABEL_WRAP, 0)).statusCode(404).body("code", equalTo("invite_not_found"))
        }
        assertEquals(listOf(null, null, null, null), listOf(mailed, revoked, overdue, foreign).map { labelOf(it.inviteId) })
        // Control: a live link invite of this tenant takes it.
        val (_, live) = seedLinkInvite(tenantId, owner)
        setLabel(owner, tenantId, live.inviteId, labelBody(LABEL, LABEL_WRAP, 0)).statusCode(204)
    }

    @Test
    fun anInviteThatEndsDropsItsLabelWithItsKey(): Unit = TestMailbox(REISSUE_SMTP_PORT).start().use { mail ->
        val (tenantId, owner) = seedActiveTenant("label-ends.example.com", mail)
        val revoked = TestInviteKey().also { mintLink(owner, tenantId, it, label = LABEL).then().statusCode(201) }
        given().header("Authorization", bearer(owner)).`when`().delete("/api/tenants/$tenantId/member-invites/${revoked.inviteId}").then().statusCode(204)
        val reissued = TestInviteKey().also { mintLink(owner, tenantId, it, label = LABEL).then().statusCode(201) }
        val fresh = TestInviteKey()
        reissue(owner, tenantId, reissued.inviteId, fresh, label = OTHER_LABEL).then().statusCode(200)

        tx {
            for (id in listOf(revoked.inviteId, reissued.inviteId)) {
                val row = TenantInviteEntity.findById(id)!!
                assertEquals(TenantInviteEntity.SUPERSEDED, row.status)
                assertEquals(listOf(null, null, null), listOf(row.sealedLabel, row.wrappedLabelValueKey, row.labelEditorUserId), "an ended invite holds no label")
            }
        }
        // The re-issue's new invite carries the label its owner's client sealed for it.
        assertEquals(SealedValue(OTHER_LABEL, OTHER_WRAP), labelOf(fresh.inviteId))
    }

    @Test
    fun aLabelWrappedUnderAReplacedEpochIsStale() {
        val owner = seedUser()
        val tenantId = seedTenant(owner = owner)
        val (_, invite) = seedLinkInvite(tenantId, owner)
        rotate(owner, tenantId, UUID.fromString(TestScopeRekey.stageAll(tenantId))).statusCode(201)

        setLabel(owner, tenantId, invite.inviteId, labelBody(LABEL, LABEL_WRAP, 0)).statusCode(409).body("code", equalTo("stale_epoch"))
        assertEquals(null, labelOf(invite.inviteId), "nothing written")
        // Control: the current epoch.
        setLabel(owner, tenantId, invite.inviteId, labelBody(LABEL, LABEL_WRAP, 1)).statusCode(204)
    }

    @Test
    fun aRotationReWrapsTheLabelsValueKeyAndNeverTheLabel() {
        val owner = seedUser()
        val tenantId = seedTenant(owner = owner)
        val (_, labelled) = seedLinkInvite(tenantId, owner, label = SealedValue(LABEL, LABEL_WRAP))
        seedLinkInvite(tenantId, owner) // an unlabelled one is no part of the rotation

        parts(owner, tenantId).statusCode(200).body("inviteLabels.size()", equalTo(1))
            .body("inviteLabels[0].inviteId", equalTo(labelled.inviteId))
            .body("inviteLabels[0].wrappedValueKey", equalTo(LABEL_WRAP))
            .body("inviteLabels[0].sealedLabel", nullValue())
        // A rotation that leaves the label's wrap behind is refused, nothing written (ADR 0115 decision 2).
        val rekeyId = UUID.randomUUID()
        stage(owner, tenantId, rekeyId, """{"fromEpoch":0}""").statusCode(204)
        rotate(owner, tenantId, rekeyId).statusCode(409).body("code", equalTo("rekey_parts_missing"))
        assertEquals(SealedValue(LABEL, LABEL_WRAP), labelOf(labelled.inviteId))
        // A staged label written as its value is no wrap: the stage's schema has no such field.
        stage(owner, tenantId, rekeyId, """{"fromEpoch":0,"inviteLabels":[{"inviteId":"${labelled.inviteId}","sealedLabel":"$OTHER_LABEL",""" +
            """"wrappedValueKey":"$NEW_WRAP","replacedValueKey":"$LABEL_WRAP"}]}""").statusCode(400)
        // A wrap staged as replacing another than the row's is stale.
        stage(owner, tenantId, rekeyId, inviteLabelStage(labelled.inviteId, replaced = OTHER_WRAP)).statusCode(204)
        rotate(owner, tenantId, rekeyId).statusCode(409).body("code", equalTo("rekey_parts_stale"))
        // Control: the wrap staged beside the one it replaces commits, and only the wrap moves.
        stage(owner, tenantId, rekeyId, inviteLabelStage(labelled.inviteId, replaced = LABEL_WRAP)).statusCode(204)
        rotate(owner, tenantId, rekeyId).statusCode(201)
        tx {
            val row = TenantInviteEntity.findById(labelled.inviteId)!!
            assertEquals(listOf(LABEL, NEW_WRAP, owner), listOf(row.sealedLabel, row.wrappedLabelValueKey, row.labelEditorUserId))
        }
    }

    // --- helpers ---

    private fun mintLink(owner: String, tenantId: String, key: TestInviteKey, label: String? = null, extra: String = ""): Response =
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{${TestGrants.memberInviteLinkJson(tenantId, key)}${labelJson(label)}$extra}""")
            .`when`().post("/api/tenants/$tenantId/member-invite-links")

    private fun reissue(owner: String, tenantId: String, inviteId: String, key: TestInviteKey, label: String? = null): Response =
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{${TestGrants.memberInviteLinkJson(tenantId, key)}${labelJson(label)}}""")
            .`when`().post("/api/tenants/$tenantId/member-invite-links/$inviteId/reissue")

    /** A label and its stand-in wrap (each label's own, as each write mints its own value key). */
    private fun labelJson(label: String?): String =
        label?.let { ""","sealedLabel":"$it","wrappedLabelValueKey":"${wrapOf(it)}"""" } ?: ""

    private fun labelBody(label: String, wrap: String, epoch: Int) = """{"sealedLabel":"$label","wrappedLabelValueKey":"$wrap","epoch":$epoch}"""

    private fun setLabel(caller: String, tenantId: String, inviteId: String, body: String): ValidatableResponse =
        given().header("Authorization", bearer(caller)).contentType(ContentType.JSON).body(body)
            .`when`().put("/api/tenants/$tenantId/member-invites/$inviteId/label").then()

    private fun list(caller: String, tenantId: String): ValidatableResponse =
        given().header("Authorization", bearer(caller)).`when`().get("/api/tenants/$tenantId/member-invites").then()

    private fun inviteLabelStage(inviteId: String, replaced: String) =
        """{"fromEpoch":0,"inviteLabels":[{"inviteId":"$inviteId","wrappedValueKey":"$NEW_WRAP","replacedValueKey":"$replaced"}]}"""

    private fun parts(caller: String, scopeId: String): ValidatableResponse =
        given().header("Authorization", bearer(caller)).`when`().get("/api/scopes/$scopeId/rekey/scope/parts").then()

    private fun stage(caller: String, scopeId: String, rekeyId: UUID, body: String): ValidatableResponse =
        given().header("Authorization", bearer(caller)).contentType(ContentType.JSON).body(body)
            .`when`().post("/api/scopes/$scopeId/rekey/scope/$rekeyId/parts").then()

    /** [rotator]'s rotation to the next epoch under [rekeyId], as `ScopeRekeySliceTest` drives it. */
    private fun rotate(rotator: String, scopeId: String, rekeyId: UUID): ValidatableResponse =
        given().header("Authorization", bearer(rotator)).contentType(ContentType.JSON)
            .body(
                """{"epoch":${TestGrants.epochOf(scopeId) + 1},${TestScopeRekey.SUCCESSION_JSON},"rekeyId":"$rekeyId",""" +
                    """"grants":[{"userId":"$rotator","wrappedGrant":"bmV3",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"${TestGrants.digestOf(rotator)}"}],""" +
                    """${TestGrants.othersPendingJson(scopeId, rotator, null)},${TestGrants.openInvitesJson(scopeId)}}""",
            )
            .`when`().post("/api/scopes/$scopeId/epochs").then()

    /** An ACTIVE tenant at epoch 0 with a granted OWNER; mail on through [mailbox]. */
    private fun seedActiveTenant(domain: String, mailbox: TestMailbox): Pair<String, String> {
        val ownerId = seedUser(email = "owner@$domain")
        val tenantId = seedTenant(domain = domain, ownerEmail = "owner@$domain", owner = ownerId)
        mailbox.receiveFor(store, tenantId, domain)
        return tenantId to ownerId
    }

    private fun seedLinkInvite(tenantId: String, owner: String, label: SealedValue? = null): Pair<String, TestInviteKey> =
        seedInvite(tenantId, owner, null, label = label)

    private fun seedInvite(
        tenantId: String, owner: String, email: String?, expiresAt: Instant = Instant.now().plus(7, ChronoUnit.DAYS),
        label: SealedValue? = null,
    ): Pair<String, TestInviteKey> {
        val key = TestInviteKey()
        val secret = "label-${UUID.randomUUID()}"
        val minted = tx { store.mintMemberInvite(tenantId, email, Tokens.hash(secret), expiresAt, key.stored(owner).copy(label = label)) }
        assertEquals(MintMemberInvite.Minted(key.inviteId), minted)
        return secret to key
    }

    private fun labelOf(inviteId: String): SealedValue? =
        tx { TenantInviteEntity.findById(inviteId)?.let { SealedValue.of(it.sealedLabel, it.wrappedLabelValueKey) } }

    private fun <T> tx(body: () -> T): T = QuarkusTransaction.requiringNew().call(body)

    private companion object {
        const val LABEL = "c2VhbGVkLWxhYmVs"
        const val OTHER_LABEL = "b3RoZXItbGFiZWw="
        const val NEW_WRAP = "bmV3LWxhYmVsLXdyYXA="

        /** A stand-in wrap of the value key [value] is sealed under — distinct per value. */
        fun wrapOf(value: String): String = "d3JhcC0$value"
        val LABEL_WRAP = wrapOf(LABEL)
        val OTHER_WRAP = wrapOf(OTHER_LABEL)

        // One JVM for the whole suite ⇒ ports no other test takes (TestMailbox says how to find the taken ones).
        const val MINT_SMTP_PORT = 13992
        const val MALFORMED_SMTP_PORT = 13993
        const val REISSUE_SMTP_PORT = 13994
    }
}
