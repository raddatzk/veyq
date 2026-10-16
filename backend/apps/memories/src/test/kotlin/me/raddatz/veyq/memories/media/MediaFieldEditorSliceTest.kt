package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.identity.PrincipalKey
import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.membership.TenantRoots
import me.raddatz.veyq.core.sharing.ShareWriteProof
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import java.time.Instant
import java.util.UUID
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The last editor of Memories' sealed fields (ADR 0093 § 8, en.platform.field-editor): a medium's meta and a bucket's
 * name, each recorded from the authenticated caller of every write — the uploader at attach (a member as `user:{id}`,
 * a link's guest as `share:{identityId}`), then the member who edits it — and cleared with the field. A contributor's
 * primary bucket copies its guest identity's display name, so that identity is the name's editor. Each write lands on
 * a field the OTHER kind of principal (or another member) wrote last, so the editor is seen to change hands.
 */
@QuarkusTest
class MediaFieldEditorSliceTest {

    private val meta = "bWV0YQ=="

    @Test
    fun aMediumsMetaIsItsUploadersThenTheEditingMembersAndAClearedOneHasNone() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val members = memberUpload(userId, scopeId, memory, meta)
        assertEquals(PrincipalKey.User(userId), media(members).metaEditor)
        val bare = memberUpload(userId, scopeId, memory, null)
        assertNull(media(bare).metaEditor, "no meta, no editor of it")

        val (identity, token, shareId) = contributor(userId, memory)
        val guests = guestUpload(shareId, token, meta)
        assertEquals(PrincipalKey.Share(identity), media(guests).metaEditor, "a guest's upload names the guest")

        // Control, the other kind: the member's edit takes the guest's meta over; a cleared meta has no editor.
        putMeta(userId, scopeId, guests, """{"wrappedMeta":"ZWRpdGVk","metaRevision":0}""")
        assertEquals(PrincipalKey.User(userId), media(guests).metaEditor)
        putMeta(userId, scopeId, guests, """{"wrappedMeta":null,"metaRevision":1}""")
        assertNull(media(guests).metaEditor)
    }

    @Test
    fun aContributorsPrimaryNamesItsIdentityAMembersSubBucketAndRenameNameTheMember() {
        val (userId, scopeId, tenantId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        memberUpload(userId, scopeId, memory, meta)
        val membersPrimary = primaryOf(memory, PrincipalKey.User(userId))
        assertNull(membersPrimary.sealedName); assertNull(membersPrimary.nameEditor, "a member's primary has no name")

        val (identity, token, shareId) = contributor(userId, memory)
        guestUpload(shareId, token, meta)
        val contributors = primaryOf(memory, PrincipalKey.Share(identity))
        assertEquals(PrincipalKey.Share(identity), contributors.nameEditor, "the copied display name is the identity's")

        // A member names a sub-bucket under the contributor's root: the member, not the root's owner, is its editor.
        val sub = given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"bucketId":"${TestIds.uuid()}","sealedName":"c3Vi","parentBucketId":"${contributors.id}"}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$memory/buckets")
            .then().statusCode(200).extract().path<String>("bucketId")
        assertEquals(PrincipalKey.User(userId), bucket(sub).nameEditor)

        // Another member's rename takes it over.
        val other = addMember(scopeId, tenantId)
        given().header("Authorization", bearer(other)).contentType(ContentType.JSON).body("""{"sealedName":"cmVuYW1lZA=="}""")
            .`when`().patch("/api/scopes/$scopeId/nodes/$memory/buckets/$sub").then().statusCode(200)
        assertEquals(PrincipalKey.User(other), bucket(sub).nameEditor)
    }

    // --- HTTP and rows ---------------------------------------------------------------------------------------------

    private fun memberUpload(userId: String, scopeId: String, nodeId: String, wrappedMeta: String?): String {
        val uploadId = given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[${TestBlob.SMALL.declare("YQ==")}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch")
            .then().statusCode(200).extract().path<String>("results[0].uploadId")
        val metaField = wrappedMeta?.let { ""","wrappedMeta":"$it"""" } ?: ""
        return given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"mediaId":"${TestBlob.ownerOf(uploadId)}","originalUploadId":"$uploadId","nodeId":"$nodeId"$metaField}]}""")
            .`when`().post("/api/scopes/$scopeId/media-batch")
            .then().statusCode(200).body("results[0].error", nullValue()).extract().path("results[0].mediaId")
    }

    private data class Contributor(val identityId: String, val token: String, val shareId: String)

    /** A READ_WRITE link on [memory] and a guest that named itself on it, as the client does. */
    private fun contributor(userId: String, memory: String): Contributor {
        val shareId = given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"$memory","role":"READ_WRITE","wrap":"d3JhcA==","sealedName":"c2VhbGVk",${ShareWriteProof.recovery()}}""")
            .`when`().post("/api/shares").then().statusCode(201).extract().path<String>("shareId")
        val r = given().contentType(ContentType.JSON)
            .body("""{"identityId":"${TestIds.uuid()}","sealedDisplayName":"Z3Vlc3Q="}""")
            .header(ShareWriteProof.HEADER, ShareWriteProof.token(shareId)).`when`().post("/api/shares/$shareId/identities")
            .then().statusCode(201).extract()
        return Contributor(r.path("identityId"), r.path("contributorToken"), shareId)
    }

    private fun guestUpload(shareId: String, token: String, wrappedMeta: String): String {
        val uploadId = given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"$token","items":[${TestBlob.SMALL.declare("Ymxvyi13cmFw")}]}""")
            .`when`().post("/api/shares/$shareId/blob-uploads")
            .then().statusCode(200).extract().path<String>("results[0].uploadId")
        return given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"$token","items":[{"mediaId":"${TestBlob.ownerOf(uploadId)}","originalUploadId":"$uploadId","wrappedMeta":"$wrappedMeta"}]}""")
            .`when`().post("/api/shares/$shareId/media")
            .then().statusCode(200).body("results[0].error", nullValue()).extract().path("results[0].mediaId")
    }

    private fun putMeta(userId: String, scopeId: String, mediaId: String, body: String) {
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON).body(body)
            .`when`().put("/api/scopes/$scopeId/media/$mediaId/meta").then().statusCode(200)
    }

    private fun addMember(scopeId: String, tenantId: String): String {
        val userId = seedUser()
        QuarkusTransaction.requiringNew().run {
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); this.userId = userId; this.tenantId = tenantId
                this.scopeId = scopeId; role = MembershipEntity.MEMBER; wrappedGrant = "Z3JhbnQ="; createdAt = Instant.now()
            }.persist()
            TenantRoots.join(userId, tenantId)
        }
        return userId
    }

    private fun media(id: String): MediaEntity = QuarkusTransaction.requiringNew().call { MediaEntity.findById(id)!! }

    private fun bucket(id: String): BucketEntity = QuarkusTransaction.requiringNew().call { BucketEntity.findById(id)!! }

    private fun primaryOf(nodeId: String, owner: PrincipalKey): BucketEntity = QuarkusTransaction.requiringNew().call {
        BucketEntity.find("nodeId = ?1 and ownerKey = ?2 and isPrimary = true", nodeId, owner.format()).firstResult()!!
    }
}
