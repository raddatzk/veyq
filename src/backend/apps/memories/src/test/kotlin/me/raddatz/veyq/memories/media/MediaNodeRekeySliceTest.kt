package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.node.rekey.NodeRekeyStageEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.ShareIdentityEntity
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.seedScopeMember
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds
import me.raddatz.veyq.core.platform.capabilities.TenantCapabilitySwitchFixtures
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import java.time.Instant
import java.util.Base64
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.hasItems
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Memories' part of a node's re-key (ADR 0109 point 2): `listMediaRekey` serves, and Memories' participant
 * in core's `commitNodeRekey` rewrites, every field of a Memory sealed under its key — each medium's meta and EXIF, the
 * `K_blob` wrap of its original and of each derivative, a trashed medium's too, and every named bucket. A `WRITE_ONLY`
 * guest's medium and its primary bucket are under the guest's own `K_up` (point 5): neither listed, nor required, nor
 * touched. The commit is fail closed: a blob wrap not staged is `rekey_parts_missing`, a medium whose meta moved since
 * staging `rekey_parts_stale`, and either writes nothing — core's rows included; each has its control on the same commit.
 * Core's own parts (children, shares, identities) are `RekeyCoreSliceTest`'s.
 */
@QuarkusTest
class MediaNodeRekeySliceTest {

    @Test
    fun theListingServesEveryKeySealedRowButAWriteOnlyGuestsInPages() {
        val w = world()
        val first = list(w, "?limit=1").statusCode(200)
            .body("media.size()", equalTo(1)).body("more", equalTo(true))
            .body("buckets.size()", equalTo(2)).body("buckets.bucketId", hasItems(w.sub, w.rwPrimary))
            // a READ_WRITE contributor's primary names the identity whose display name it copies, and its link
            .body("buckets.find { it.bucketId == '${w.rwPrimary}' }.shareId", equalTo(w.rwShareId))
            .body("buckets.find { it.bucketId == '${w.rwPrimary}' }.identityId", equalTo(w.rwIdentityId))
            .body("buckets.find { it.bucketId == '${w.sub}' }.shareId", nullValue())
            .body("buckets.find { it.bucketId == '${w.sub}' }.identityId", nullValue())
            .extract()
        val second = list(w, "?limit=1&since=${first.path<String>("cursor")}").statusCode(200)
            .body("media.size()", equalTo(1)).body("more", equalTo(false))
            .extract()
        val served = listOf(first.path<String>("media[0].mediaId"), second.path<String>("media[0].mediaId"))
        assertEquals(setOf(w.live, w.trashed), served.toSet(), "the live and the trashed medium, never the WRITE_ONLY guest's")
        list(w, "").statusCode(200)
            .body("media.find { it.mediaId == '${w.live}' }.wrappedMeta", equalTo(META_A))
            .body("media.find { it.mediaId == '${w.live}' }.wrappedExif", equalTo(EXIF_A))
            .body("media.find { it.mediaId == '${w.live}' }.blobs.role", hasItems("ORIGINAL", "THUMBNAIL"))
            .body("media.find { it.mediaId == '${w.trashed}' }.wrappedExif", nullValue())
        // only a caller with the current grant reads it (a plain member's leg: aMemberWithTheCurrentGrantReKeysAMemory)
        QuarkusTransaction.requiringNew().run {
            MembershipEntity.update("grantedEpoch = 0 where scopeId = ?1 and userId = ?2", w.scopeId, w.owner)
        }
        list(w, "").statusCode(403).body("code", equalTo("grant_behind"))
    }

    /** ADR 0111 decision 11, the participant's leg: a plain member holding the current grant lists a Memory's sealed
     *  rows, stages them and commits through core, as an owner does; one whose grant lags is refused (`grant_behind`). */
    @Test
    fun aMemberWithTheCurrentGrantReKeysAMemory() {
        val w = world()
        val behind = seedScopeMember(w.scopeId, grantedEpoch = 0)
        list(w, "", caller = behind).statusCode(403).body("code", equalTo("grant_behind"))
        val member = seedScopeMember(w.scopeId, grantedEpoch = 1)
        list(w, "", caller = member).statusCode(200).body("media.size()", equalTo(2))
        val rekeyId = UUID.randomUUID()
        stageAll(w, rekeyId, caller = member)
        commit(w, rekeyId, caller = member).statusCode(204)
        QuarkusTransaction.requiringNew().run {
            assertEquals(1, NodeEntity.findById(w.memory)!!.epoch)
            assertEquals(NEW_META_A, MediaEntity.findById(w.live)!!.wrappedMeta)
        }
    }

    /**
     * ADR 0111 decision 11 narrowed to where members place nothing (ADR 0080 Decision 11): a Memory carrying a drop key
     * because a contributor link (`WRITE_ONLY`) is rooted on it is no drop box, so a plain member re-keys it — its drop
     * key replaced, the link's drop public key and the guest's `K_up` re-sealed (ADR 0109 point 5) — and is not left
     * waiting for an owner. (Red under the drop-key predicate: `ownerOnly` true and `not_scope_owner` at the commit.)
     */
    @Test
    fun aMemberReKeysAMemoryAContributorLinkGaveADropKey() {
        val w = world()
        QuarkusTransaction.requiringNew().run {
            NodeEntity.findById(w.memory)!!.sealedDropPrivateKey = OLD_DROP_PRIVATE
            ShareEntity.findById(w.shareId)!!.apply { wrap = null; sealedDropPublicKey = OLD_DROP_PUBLIC }
        }
        val member = seedScopeMember(w.scopeId, grantedEpoch = 1)
        given().header("Authorization", bearer(member)).`when`().get("/api/scopes/${w.scopeId}/rekey/nodes").then().statusCode(200)
            .body("nodes.find { it.id == '${w.memory}' }.ownerOnly", equalTo(false))
        val rekeyId = UUID.randomUUID()
        stageMedia(w, rekeyId, caller = member)
        post(
            w, "/rekey/nodes/${w.memory}/$rekeyId/parts",
            """{"fromEpoch":0,"shares":[{"shareId":"${w.shareId}","sealedDropPublicKey":"$NEW_DROP_PUBLIC"},{"shareId":"${w.rwShareId}","wrap":"bmV3LXJ3LXdyYXA="}],
               "identities":[{"identityId":"${w.identityId}","wrappedIdentityKey":"bmV3LWt1cA=="},{"identityId":"${w.rwIdentityId}","sealedDisplayName":"$NEW_RW_NAME"}]}""",
            member,
        ).statusCode(204)
        commit(w, rekeyId, caller = member, sealedDropPrivateKey = NEW_DROP_PRIVATE).statusCode(204)
        QuarkusTransaction.requiringNew().run {
            val memory = NodeEntity.findById(w.memory)!!
            assertEquals(listOf<Any?>(1, NEW_DROP_PRIVATE), listOf(memory.epoch, memory.sealedDropPrivateKey))
            assertEquals(NEW_DROP_PUBLIC, ShareEntity.findById(w.shareId)!!.sealedDropPublicKey)
            assertEquals("bmV3LWt1cA==", ShareIdentityEntity.findById(w.identityId)!!.wrappedIdentityKey)
            assertEquals(OLD_WRAP, BlobEntity.findById(w.guestBlob)!!.wrappedBlobKey, "the guest's own upload stays under its K_up")
        }
    }

    @Test
    fun aCommitReSealsEveryMediumBlobAndBucketNameAndMovesTheEpoch() {
        val w = world()
        val rekeyId = UUID.randomUUID()
        stageAll(w, rekeyId)
        commit(w, rekeyId).statusCode(204)

        QuarkusTransaction.requiringNew().run {
            val a = MediaEntity.findById(w.live)!!
            assertEquals(listOf<Any?>(NEW_META_A, 1L, NEW_EXIF_A, w.owner), listOf(a.wrappedMeta, a.metaRevision, a.wrappedExif, a.metaEditorUserId))
            val b = MediaEntity.findById(w.trashed)!!
            assertEquals(NEW_META_B to 1L, b.wrappedMeta to b.metaRevision, "the trashed medium is re-sealed too")
            for (blob in w.liveBlobs + w.trashedBlob) assertEquals(newWrap(blob), BlobEntity.findById(blob)!!.wrappedBlobKey, blob)
            assertEquals(NEW_SUB, BucketEntity.findById(w.sub)!!.sealedName)
            // not staged: the commit copied the contributor's name again, as core's part of the same commit re-sealed it
            assertEquals(NEW_RW_NAME, BucketEntity.findById(w.rwPrimary)!!.sealedName)
            // the WRITE_ONLY guest's rows: under its K_up, kept as they are
            assertEquals(OLD_WRAP, BlobEntity.findById(w.guestBlob)!!.wrappedBlobKey)
            assertEquals(GUEST_NAME, BucketEntity.findById(w.guestPrimary)!!.sealedName)
            assertEquals(1, NodeEntity.findById(w.memory)!!.epoch)
            assertEquals(0L, NodeRekeyStageEntity.count("rekeyId", rekeyId.toString()))
        }
        // an open gallery's next poll carries both live rows' new wraps: the commit moved the item revision
        given().header("Authorization", bearer(w.owner)).`when`().get("/api/scopes/${w.scopeId}/nodes/${w.memory}/media")
            .then().statusCode(200).body("media.find { it.mediaId == '${w.live}' }.wrappedBlobKey", equalTo(newWrap(w.liveBlobs[0])))
    }

    /**
     * A rotation's re-key reaches a switched-off Memories (uc.platform.switch-application-off E1): it is the security of
     * what exists, so neither the stage nor the commit asks the switch — while the same tenant's meta write is refused
     * (the predicate the re-key is held against).
     */
    @Test
    fun aRekeyLandsWhileMemoriesIsSwitchedOff() {
        val w = world()
        val tenantId = QuarkusTransaction.requiringNew().call { ScopeEntity.findById(w.scopeId)!!.tenantId }
        val rekeyId = UUID.randomUUID()
        TenantCapabilitySwitchFixtures.with(tenantId, CapabilityIds.MEMORIES) {
            given().header("Authorization", bearer(w.owner)).contentType(ContentType.JSON)
                .body("""{"wrappedMeta":"$NEW_META_A","metaRevision":0,"nodeEpoch":0}""")
                .`when`().put("/api/scopes/${w.scopeId}/media/${w.live}/meta")
                .then().statusCode(422).body("code", equalTo("capability_disabled"))
            stageAll(w, rekeyId)
            commit(w, rekeyId).statusCode(204)
        }
        QuarkusTransaction.requiringNew().run { assertEquals(1, NodeEntity.findById(w.memory)!!.epoch) }
    }

    @Test
    fun aBlobWrapNotStagedRefusesTheWholeCommit() {
        val w = world()
        val rekeyId = UUID.randomUUID()
        stageAll(w, rekeyId, skipBlob = w.liveBlobs[1]) // the THUMBNAIL's wrap
        commit(w, rekeyId).statusCode(409).body("code", equalTo("rekey_parts_missing"))
        assertUntouched(w)

        stageMedia(w, rekeyId, onlyBlob = w.liveBlobs[1]) // control: the missing wrap staged under the same id
        commit(w, rekeyId).statusCode(204)
        assertEquals(1, QuarkusTransaction.requiringNew().call { NodeEntity.findById(w.memory)!!.epoch })
    }

    @Test
    fun aMediumWhoseMetaMovedSinceStagingIsStale() {
        val w = world()
        val rekeyId = UUID.randomUUID()
        stageAll(w, rekeyId)
        QuarkusTransaction.requiringNew().run { MediaEntity.findById(w.live)!!.metaRevision = 1 } // an edit landed meanwhile
        commit(w, rekeyId).statusCode(409).body("code", equalTo("rekey_parts_stale"))
        assertUntouched(w, liveRevision = 1)

        stageAll(w, rekeyId, liveRevision = 1) // control: re-staged against the revision it has now
        commit(w, rekeyId).statusCode(204)
        assertEquals(2L, QuarkusTransaction.requiringNew().call { MediaEntity.findById(w.live)!!.metaRevision })
    }

    // --- the world --------------------------------------------------------------------------------------------------

    /**
     * An owner's scope at epoch 0 with a Memory at its root: a live medium (meta, EXIF, original and a thumbnail), a
     * trashed one (meta, original), the owner's unnamed primary and a named sub-bucket; a `WRITE_ONLY` link with one guest
     * whose medium and primary are under its `K_up`. Then the scope rotates to 1 and the owner's grant follows.
     */
    private data class World(
        val owner: String, val scopeId: String, val memory: String,
        val live: String, val liveBlobs: List<String>, val trashed: String, val trashedBlob: String,
        val sub: String, val guestBlob: String, val guestPrimary: String, val shareId: String, val identityId: String,
        val rwShareId: String, val rwIdentityId: String, val rwPrimary: String,
    )

    private fun world(): World {
        val (owner, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val shareId = Base64.getUrlEncoder().withoutPadding().encodeToString(UUID.randomUUID().toString().toByteArray()).take(43).padEnd(43, 'A')
        val identityId = UUID.randomUUID().toString()
        val ids = List(9) { UUID.randomUUID().toString() }
        val (live, original, thumb, trashed, trashedBlob, primary, sub, guestMedium, guestBlob) = ids
        val guestPrimary = UUID.randomUUID().toString()
        val rwShareId = Base64.getUrlEncoder().withoutPadding().encodeToString(UUID.randomUUID().toString().toByteArray()).take(43).padEnd(43, 'A')
        val rwIdentityId = UUID.randomUUID().toString()
        val rwPrimary = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            listOf(original, thumb, trashedBlob, guestBlob).forEach { blob(scopeId, it) }
            bucket(scopeId, memory, primary, "user:$owner", null, primary = true)
            bucket(scopeId, memory, sub, "user:$owner", "c3Vi", primary = false, parent = primary)
            medium(scopeId, memory, live, original, META_A, EXIF_A, owner, primary)
            MediaDerivativeEntity().apply { id = UUID.randomUUID().toString(); mediaId = live; kind = "THUMBNAIL"; blobId = thumb }.persist()
            medium(scopeId, memory, trashed, trashedBlob, META_B, null, owner, primary).deletedAt = Instant.now()
            ShareEntity().apply {
                id = shareId; ownerAccount = owner; this.scopeId = scopeId; nodeId = memory; role = "WRITE_ONLY"
                wrap = "b2xkLXNoYXJlLXdyYXA="; sealedShareKey = "cmVjb3Zlcnk="; sealedShareKeyEpoch = 0; createdAt = Instant.now()
            }.persist()
            ShareIdentityEntity().apply {
                id = identityId; this.shareId = shareId; sealedDisplayName = GUEST_NAME; wrappedIdentityKey = "a3Vw"
                tokenHash = "h-$identityId".take(64)
            }.persist()
            bucket(scopeId, memory, guestPrimary, "share:$identityId", GUEST_NAME, primary = true)
            medium(scopeId, memory, guestMedium, guestBlob, "Z3Vlc3QtbWV0YQ==", null, null, guestPrimary).shareIdentityId = identityId
            // a READ_WRITE link with one contributor, whose primary is named with a copy of its display name
            ShareEntity().apply {
                id = rwShareId; ownerAccount = owner; this.scopeId = scopeId; nodeId = memory; role = "READ_WRITE"
                wrap = "b2xkLXJ3LXdyYXA="; sealedShareKey = "cmVjb3Zlcnk="; sealedShareKeyEpoch = 0; createdAt = Instant.now()
            }.persist()
            ShareIdentityEntity().apply {
                id = rwIdentityId; this.shareId = rwShareId; sealedDisplayName = RW_NAME; tokenHash = "h-$rwIdentityId".take(64)
            }.persist()
            bucket(scopeId, memory, rwPrimary, "share:$rwIdentityId", RW_NAME, primary = true)
            ScopeEntity.findById(scopeId)!!.currentEpoch = 1
            MembershipEntity.update("grantedEpoch = 1 where scopeId = ?1 and userId = ?2", scopeId, owner)
        }
        return World(
            owner, scopeId, memory, live, listOf(original, thumb), trashed, trashedBlob, sub, guestBlob, guestPrimary, shareId, identityId,
            rwShareId, rwIdentityId, rwPrimary,
        )
    }

    private fun blob(scopeId: String, blobId: String) = BlobEntity().apply {
        id = blobId; this.scopeId = scopeId; storageKey = UUID.randomUUID().toString()
        chunkCount = TestBlob.SMALL.chunkCount; byteSize = TestBlob.SMALL.byteSize; wrappedBlobKey = OLD_WRAP
    }.persist()

    private fun bucket(scopeId: String, nodeId: String, id: String, owner: String, name: String?, primary: Boolean, parent: String? = null) =
        BucketEntity().apply {
            this.id = id; this.scopeId = scopeId; this.nodeId = nodeId; ownerKey = owner; sealedName = name
            isPrimary = primary; parentBucketId = parent; createdAt = Instant.now()
        }.persist()

    private fun medium(
        scopeId: String, nodeId: String, id: String, blobId: String, meta: String, exif: String?, editor: String?, bucketId: String,
    ): MediaEntity = MediaEntity().apply {
        this.id = id; this.scopeId = scopeId; this.nodeId = nodeId; this.blobId = blobId; wrappedMeta = meta; wrappedExif = exif
        metaEditorUserId = editor; createdBy = editor?.let { "user:$it" }; this.bucketId = bucketId; updatedAt = Instant.now()
    }.also { it.persist() }

    private fun assertUntouched(w: World, liveRevision: Long = 0) = QuarkusTransaction.requiringNew().run {
        val a = MediaEntity.findById(w.live)!!
        assertEquals(listOf<Any?>(META_A, liveRevision, EXIF_A), listOf(a.wrappedMeta, a.metaRevision, a.wrappedExif))
        assertEquals(META_B to 0L, MediaEntity.findById(w.trashed)!!.let { it.wrappedMeta to it.metaRevision })
        for (blob in w.liveBlobs + w.trashedBlob) assertEquals(OLD_WRAP, BlobEntity.findById(blob)!!.wrappedBlobKey, blob)
        assertEquals("c3Vi", BucketEntity.findById(w.sub)!!.sealedName)
        assertEquals("b2xkLXNoYXJlLXdyYXA=", ShareEntity.findById(w.shareId)!!.wrap, "core's parts roll back with Memories'")
        assertEquals(0, NodeEntity.findById(w.memory)!!.epoch)
    }

    // --- HTTP -------------------------------------------------------------------------------------------------------

    private fun stageAll(w: World, rekeyId: UUID, skipBlob: String? = null, liveRevision: Long = 0, caller: String = w.owner) {
        stageMedia(w, rekeyId, skipBlob = skipBlob, liveRevision = liveRevision, caller = caller)
        post(
            w, "/rekey/nodes/${w.memory}/$rekeyId/parts",
            """{"fromEpoch":0,"shares":[{"shareId":"${w.shareId}","wrap":"bmV3LXNoYXJlLXdyYXA="},{"shareId":"${w.rwShareId}","wrap":"bmV3LXJ3LXdyYXA="}],
               "identities":[{"identityId":"${w.identityId}","wrappedIdentityKey":"bmV3LWt1cA=="},{"identityId":"${w.rwIdentityId}","sealedDisplayName":"$NEW_RW_NAME"}]}""",
            caller,
        ).statusCode(204)
    }

    private fun stageMedia(w: World, rekeyId: UUID, skipBlob: String? = null, onlyBlob: String? = null, liveRevision: Long = 0, caller: String = w.owner) {
        fun wraps(blobs: List<String>) = blobs.filter { it != skipBlob && (onlyBlob == null || it == onlyBlob) }
            .joinToString(",", "[", "]") { """{"blobId":"$it","wrappedBlobKey":"${newWrap(it)}"}""" }
        val body = if (onlyBlob != null) {
            """{"fromEpoch":0,"media":[{"mediaId":"${w.live}","metaRevision":$liveRevision,"blobs":${wraps(w.liveBlobs)}}]}"""
        } else {
            """{"fromEpoch":0,"media":[
                 {"mediaId":"${w.live}","metaRevision":$liveRevision,"wrappedMeta":"$NEW_META_A","wrappedExif":"$NEW_EXIF_A","blobs":${wraps(w.liveBlobs)}},
                 {"mediaId":"${w.trashed}","metaRevision":0,"wrappedMeta":"$NEW_META_B","blobs":${wraps(listOf(w.trashedBlob))}}],
               "buckets":[{"bucketId":"${w.sub}","sealedName":"$NEW_SUB"}]}"""
        }
        post(w, "/nodes/${w.memory}/media-rekey/$rekeyId", body, caller).statusCode(204)
    }

    /** The Memory's commit body; [sealedDropPrivateKey] is its new drop key, sent when the Memory carries one. */
    private fun commit(w: World, rekeyId: UUID, caller: String = w.owner, sealedDropPrivateKey: String? = null): ValidatableResponse {
        val drop = if (sealedDropPrivateKey == null) "" else ",\"sealedDropPrivateKey\":\"$sealedDropPrivateKey\""
        return post(
            w, "/rekey/nodes/${w.memory}/$rekeyId",
            """{"nodeEpoch":0,"epoch":1,"placementRevision":0,"metaRevision":0,"encryptedName":"bmV3LW1lbW9yeQ==","slugToken":"${slug()}","wrappedContentKey":"bmV3LWtleQ=="$drop}""",
            caller,
        )
    }

    private fun list(w: World, query: String, caller: String = w.owner): ValidatableResponse =
        given().header("Authorization", bearer(caller)).`when`().get("/api/scopes/${w.scopeId}/nodes/${w.memory}/media-rekey$query").then()

    private fun post(w: World, path: String, body: String, caller: String = w.owner): ValidatableResponse =
        given().header("Authorization", bearer(caller)).contentType(ContentType.JSON).body(body)
            .`when`().post("/api/scopes/${w.scopeId}$path").then()

    private fun slug(): String = Base64.getEncoder().encodeToString(UUID.randomUUID().toString().toByteArray())

    /** A distinct base64 re-wrap per blob, so a wrap landing on the wrong blob reads back wrong. */
    private fun newWrap(blobId: String): String = Base64.getEncoder().encodeToString("new-${blobId.take(8)}".toByteArray())

    private operator fun <T> List<T>.component6(): T = this[5]
    private operator fun <T> List<T>.component7(): T = this[6]
    private operator fun <T> List<T>.component8(): T = this[7]
    private operator fun <T> List<T>.component9(): T = this[8]

    private companion object {
        const val OLD_WRAP = "b2xkLXdyYXA="
        const val META_A = "bWV0YS1h"
        const val EXIF_A = "ZXhpZi1h"
        const val META_B = "bWV0YS1i"
        const val NEW_META_A = "bmV3LW1ldGEtYQ=="
        const val NEW_EXIF_A = "bmV3LWV4aWYtYQ=="
        const val NEW_META_B = "bmV3LW1ldGEtYg=="
        const val NEW_SUB = "bmV3LXN1Yg=="
        const val GUEST_NAME = "Z3Vlc3QtbmFtZQ=="
        const val RW_NAME = "Y29udHJpYnV0b3I="
        const val NEW_RW_NAME = "bmV3LWNvbnRyaWJ1dG9y"
        const val OLD_DROP_PRIVATE = "b2xkLWRyb3AtcHJpdg=="
        const val OLD_DROP_PUBLIC = "b2xkLWRyb3AtcHVi"
        const val NEW_DROP_PRIVATE = "bmV3LWRyb3AtcHJpdg=="
        const val NEW_DROP_PUBLIC = "bmV3LWRyb3AtcHVi"
    }
}
