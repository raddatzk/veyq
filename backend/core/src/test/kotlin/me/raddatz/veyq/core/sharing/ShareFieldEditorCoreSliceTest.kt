package me.raddatz.veyq.core.sharing

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
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The last editor of a share's sealed fields (ADR 0093 § 8, en.platform.field-editor): its root's name (`sealed_name`),
 * its note and its private label, each recorded from the member who wrote it — a create, an edit, a re-seal of the name
 * by any member, a re-issue — and cleared with the field, by an edit that clears it and by the tombstone. Only members
 * write a share's fields. Each write lands on a field another member wrote last, or that a stand-in editor holds, so
 * the editor is seen to change.
 */
@QuarkusTest
class ShareFieldEditorCoreSliceTest {

    private data class World(val owner: String, val member: String, val folder: String)

    private fun world(): World {
        val (owner, scopeId, tenantId) = seedMemberScope(ScopeEntity.GROUP)
        val member = seedUser()
        QuarkusTransaction.requiringNew().run {
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); userId = member; this.tenantId = tenantId
                this.scopeId = scopeId; role = MembershipEntity.MEMBER; wrappedGrant = "Z3JhbnQ="; createdAt = Instant.now()
            }.persist()
            TenantRoots.join(member, tenantId)
        }
        return World(owner, member, seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY))
    }

    @Test
    fun aCreateNamesItsCallerForEveryFieldItSets() {
        val w = world()
        val full = create(w, note = "bm90ZQ==", label = "bGFiZWw=")
        assertEquals(Triple(w.owner, w.owner, w.owner), editors(full))
        val bare = create(w, note = null, label = null)
        assertEquals(Triple(w.owner, null, null), editors(bare), "a field not set has no editor")
    }

    @Test
    fun aMembersReSealOfTheNameTakesTheNameOver() {
        val w = world()
        val shareId = create(w, note = "bm90ZQ==", label = null)
        given().header("Authorization", bearer(w.member)).contentType(ContentType.JSON).body("""{"sealedName":"cmUtc2VhbGVk"}""")
            .`when`().put("/api/shares/$shareId/sealed-name").then().statusCode(204)
        assertEquals(Triple(w.member, w.owner, null), editors(shareId), "the name only")
    }

    @Test
    fun anEditNamesItsCallerForEachFieldItSetsAndClearsTheEditorOfOneItClears() {
        val w = world()
        val shareId = create(w, note = "bm90ZQ==", label = "bGFiZWw=")
        val standIn = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            ShareEntity.findById(shareId)!!.apply { noteEditorUserId = standIn; shareNameEditorUserId = standIn; nameEditorUserId = standIn }
        }
        given().header("Authorization", bearer(w.owner)).contentType(ContentType.JSON)
            .body("""{"sealedNote":null,"sealedShareName":"bmV3LWxhYmVs"}""")
            .`when`().patch("/api/shares/$shareId").then().statusCode(204)
        assertEquals(Triple(standIn, null, w.owner), editors(shareId), "the root's name is no field of the edit")
    }

    @Test
    fun aReissueNamesItsCallerOnTheNewLinkAndTheTombstonesHoldNoEditor() {
        val w = world()
        val old = create(w, note = "bm90ZQ==", label = "bGFiZWw=")
        val renewed = TestIds.shareId()
        given().header("Authorization", bearer(w.owner)).contentType(ContentType.JSON)
            .body(
                """{"shareId":"$renewed",${ShareWriteProof.field(renewed)},"wrap":"d3JhcA==","sealedName":"c2VhbGVk",""" +
                    """"sealedNote":"bm90ZQ==",${ShareWriteProof.recovery()}}""",
            )
            .`when`().post("/api/shares/$old/reissue").then().statusCode(201)
        assertEquals(Triple(w.owner, w.owner, null), editors(renewed))
        assertEquals(Triple(null, null, null), editors(old), "the re-issued link is a tombstone")

        given().header("Authorization", bearer(w.owner)).`when`().delete("/api/shares/$renewed").then().statusCode(204)
        assertEquals(Triple(null, null, null), editors(renewed), "a deleted link is a tombstone")
        assertNull(QuarkusTransaction.requiringNew().call { ShareEntity.findById(renewed)!!.sealedName }, "control: the seals went too")
    }

    // --- HTTP and rows ---------------------------------------------------------------------------------------------

    private fun create(w: World, note: String?, label: String?): String {
        val shareId = TestIds.shareId()
        val extra = (note?.let { ""","sealedNote":"$it"""" } ?: "") + (label?.let { ""","sealedShareName":"$it"""" } ?: "")
        given().header("Authorization", bearer(w.owner)).contentType(ContentType.JSON)
            .body(
                """{"shareId":"$shareId",${ShareWriteProof.field(shareId)},"nodeId":"${w.folder}","role":"READ","wrap":"d3JhcA==",""" +
                    """"sealedName":"c2VhbGVk",${ShareWriteProof.recovery()}$extra}""",
            )
            .`when`().post("/api/shares").then().statusCode(201)
        return shareId
    }

    /** The editors of (name, note, private label). */
    private fun editors(shareId: String): Triple<String?, String?, String?> = QuarkusTransaction.requiringNew().call {
        ShareEntity.findById(shareId)!!.let { Triple(it.nameEditorUserId, it.noteEditorUserId, it.shareNameEditorUserId) }
    }
}
