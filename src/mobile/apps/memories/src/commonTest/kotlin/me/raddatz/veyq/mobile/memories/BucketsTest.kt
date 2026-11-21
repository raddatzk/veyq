package me.raddatz.veyq.mobile.memories

import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.session.testPrimitives
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import me.raddatz.veyq.mobile.words.Piece
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A Memory's contributions (ADR 0149 decision 6), the web's `media.buckets.test.ts`, `MemoryBuckets.test.ts` and
 * `useUploaderNames.test.ts` twinned: a sub-bucket's name under the node's key, a READ_WRITE guest's under its link root's
 * (an ancestor), each guest marked as a guest's; the picker's rows depth first with a virtual "You" before the own primary
 * exists; a bucket's media; and a guest named in a medium's details from its bucket — "a guest" only where none opens.
 */
class BucketsTest {
    private val p = testPrimitives
    private val suite = V1(p)
    private val scope = "7c3f0e2a-9b1d-4a6e-8b2c-1f2d3e4a5b6c"
    private val node = "4a6e8b2c-1f2d-3e4a-5b6c-7c3f0e2a9b1d"
    private val nodeKey = p.randomBytes(32)
    private val rootKey = p.randomBytes(32)
    private val share = "A".repeat(43)
    private val guest = "8d9e0f1a-2b3c-4d4e-8f5a-6b7c8d9e0f1a"
    private val sub = "5b6c7c3f-0e2a-4b1d-8a6e-8b2c1f2d3e4a"
    private val memberPrimary = "6c7d8d4a-1f3b-4c2e-9b7f-9c3d2a3e4f5b"
    private val guestPrimary = "9e0f1a2b-3c4d-4e5f-8a6b-7c8d9e0f1a2b"

    private fun sealed(ctx: Context, key: ByteArray, s: String) = b64(suite.sealField(ctx, key, s.encodeToByteArray()))

    private val listed = Buckets.Listed(
        listOf(
            Buckets.Bucket(memberPrimary, "user:u1", null, true, 3, null, null, null),
            Buckets.Bucket(sub, "user:u1", memberPrimary, false, 1, null, sealed(Buckets.nameContext(scope, node, sub), nodeKey, "Sunsets"), null),
            // a folder link's guest: its name sealed under the link ROOT's key, an ancestor of this Memory (ADR 0080 decision 6)
            Buckets.Bucket(
                guestPrimary, "share:$guest", null, true, 2, share,
                sealed(Context("share-identity-name", "scopeId" to scope, "shareId" to share, "identityId" to guest), rootKey, "Ben"), null,
            ),
        ),
        null,
    )

    private fun named() = listed.buckets.associate { it.bucketId to Buckets.name(suite, scope, node, nodeKey, listed, it, listOf(rootKey)) }

    @Test
    fun aSubBucketOpensUnderTheNode_aGuestUnderItsLinksRoot() {
        assertEquals(mapOf(memberPrimary to null, sub to "Sunsets", guestPrimary to "Ben"), named())
        assertNull(Buckets.name(suite, scope, node, nodeKey, listed, listed.buckets[2]), "without the root's key the guest is unnamed")
    }

    @Test
    fun thePickersRows_depthFirst_theGuestMarked_aMemberByTheDirectory() {
        val rows = Buckets.rows(listed, named(), { if (it == "u1") "Ana Lima" else null }, unbucketed = 0, self = "u1")
        assertEquals(listOf(memberPrimary to 0, sub to 1, guestPrimary to 0), rows.map { it.bucketId to it.depth }, "each primary by its label, its sub-buckets under it")
        assertEquals(Piece.Plain("Ana Lima"), rows[0].label)
        assertEquals(Piece.Plain("Sunsets"), rows[1].label)
        assertEquals(Piece.Said(Notice(Msg.MEMORY_UPLOADER_GUEST_NAMED, "Ben")), rows[2].label)
        assertEquals(listOf(true, true, false), rows.map { it.own }, "a guest's contribution is not the caller's own root")
        assertEquals(memberPrimary, Buckets.preferred(rows))
    }

    @Test
    fun beforeTheOwnPrimaryExists_aVirtualYouHoldsTheMembersUnbucketedMedia() {
        val guestOnly = Buckets.Listed(listOf(listed.buckets[2]), null)
        val rows = Buckets.rows(guestOnly, named(), { null }, unbucketed = 4, self = "u1")
        val you = rows.single { it.virtual }
        assertEquals(Buckets.SELF, you.bucketId)
        assertEquals(Piece.Said(Notice(Msg.MEMORY_BUCKETS_YOU)), you.label)
        assertEquals(4 to true, you.mediaCount to you.own)
        assertEquals(Buckets.SELF, Buckets.preferred(rows))
        fun item(id: String, owner: String, bucket: String?) = MemoryMedia.Item(
            scope, node, id, MemoryMedia.Blob("b$id", 1, 100, "", null), null, null, null, "image/jpeg", "$id.jpg", owner, false, nodeKey, bucketId = bucket,
        )
        val items = listOf(item("1", "user:u1", null), item("2", "user:u1", sub), item("3", "share:$guest", guestPrimary))
        assertEquals(listOf("1"), Buckets.mediaOf(Buckets.SELF, items, "u1").map { it.mediaId })
        assertEquals(listOf("2"), Buckets.mediaOf(sub, items, "u1").map { it.mediaId })
        assertEquals(listOf("3"), Buckets.mediaOf(guestPrimary, items, "u1").map { it.mediaId })
    }

    @Test
    fun aGuestIsNamedInAMediumsDetailsFromItsBucket() {
        val keys = setOf("share:$guest", "share:other")
        val u = MediaDetails.uploaders(keys, emptyList(), emptyMap(), mapOf("share:$guest" to "Ben"))
        assertEquals(Piece.Said(Notice(Msg.MEMORY_UPLOADER_GUEST_NAMED, "Ben")), MediaDetails.uploaderWords(u.getValue("share:$guest")))
        assertEquals(Piece.Said(Notice(Msg.MEMORY_UPLOADER_GUEST)), MediaDetails.uploaderWords(u.getValue("share:other")), "a guest whose name does not open")
        assertNull(u.getValue("share:$guest").profile, "a guest opens no profile")
    }
}
