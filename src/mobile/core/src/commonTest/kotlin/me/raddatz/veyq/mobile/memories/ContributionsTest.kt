package me.raddatz.veyq.mobile.memories

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.NodeRef
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import me.raddatz.veyq.mobile.words.Piece
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A Memory's Contributions view over a gallery the test plays, as the server decides it (`BucketsResource`, ADR 0034
 * point 6): the caller's own root told from another member's (#991) — a virtual "You" while another member's primary
 * exists, a move offered within one root only, a sub-bucket under a primary only; a curation's re-read after the gallery's
 * (#990); the lightbox over the chosen bucket, not the gallery (#996).
 */
class ContributionsTest {
    private val memory = NodeAt("s", "m", "MEMORY", "Ostsee 2026")
    private val me = "u-me"
    private val ownerPrimary = "p-owner"
    private val ownerSub = "s-owner"

    private fun blob(id: String) = MemoryMedia.Blob(id, 1, 29L + V1.FRAME_OVERHEAD, "", null)

    private fun item(id: String, owner: String, bucket: String?, at: Long) = MemoryMedia.Item(
        "s", "m", id, blob("o-$id"), blob("t-$id"), blob("p-$id"),
        MemoryMedia.Meta(4000, 3000, at, null, "exif", null, null, null, null), "image/jpeg", "$id.jpg", owner, false, ByteArray(0),
        bucketId = bucket,
    )

    /** The server the test plays: the gallery's media and the buckets, a move re-filing a medium as the server would. */
    private inner class Server {
        var items = listOf(
            item("o1", "user:u-owner", ownerPrimary, 3),
            item("o2", "user:u-owner", ownerPrimary, 2),
            item("o3", "user:u-owner", ownerSub, 1),
            item("mine", "user:$me", null, 4),
        )
        val moves = mutableListOf<Pair<List<String>, String?>>()
        val creates = mutableListOf<String?>()

        fun buckets() = Buckets.Listed(
            listOf(
                Buckets.Bucket(ownerPrimary, "user:u-owner", null, true, items.count { it.bucketId == ownerPrimary }, null, null, null),
                Buckets.Bucket(ownerSub, "user:u-owner", ownerPrimary, false, items.count { it.bucketId == ownerSub }, null, null, null),
            ),
            null,
        )

        val gallery = object : GalleryModel.Door {
            override suspend fun gallery() = MemoryMedia.Gallery(MemoryMedia.order(items), 1, 0)
            override suspend fun scopeNodes() = emptyList<NodeRef>()
            override suspend fun read(item: MemoryMedia.Item, blob: MemoryMedia.Blob) = blob.blobId.encodeToByteArray()
            override fun reader(item: MemoryMedia.Item) = null
            override suspend fun stream(item: MemoryMedia.Item, sink: suspend (ByteArray) -> Unit) = false
            override suspend fun exif(item: MemoryMedia.Item): MediaDetails.Exif? = null
            override suspend fun uploaders(items: List<MemoryMedia.Item>) = emptyMap<String, MediaDetails.Uploader>()
            override fun upload(sources: List<UploadSource>) = 0
            override val revisions = MutableStateFlow<Any?>(Any())
            override val uploaded = MutableSharedFlow<String>()
        }

        val door = object : Contributions.Door {
            override suspend fun list() = buckets() to mapOf(ownerSub to "Sunsets")
            override val self = me
            override fun person(userId: String) = if (userId == "u-owner") "Olga" else null
            override suspend fun create(name: String, parent: String?) = Buckets.Outcome.DONE.also { creates += parent }
            override suspend fun rename(bucketId: String, name: String) = Buckets.Outcome.DONE
            override suspend fun delete(bucketId: String) = Buckets.Outcome.DONE
            override suspend fun move(mediaIds: List<String>, target: String?): Buckets.Outcome {
                moves += mediaIds to target
                items = items.map { if (it.mediaId in mediaIds) item(it.mediaId, it.ownerKey!!, target, it.meta!!.capturedAt!!) else it }
                return Buckets.Outcome.DONE
            }
        }
    }

    private suspend fun TestScope.open(server: Server): Contributions {
        val g = GalleryModel(memory, server.gallery, backgroundScope, backgroundScope).also { runCurrent() }
        return Contributions(g, server.door).also { it.read() }
    }

    // #991: another member's primary is theirs, not the caller's — "You" stays, and the picker opens on it
    @Test
    fun anotherMembersPrimaryIsNotTakenForTheCallers() = runTest {
        val c = open(Server())
        val rows = c.current.rows
        assertEquals(listOf(Buckets.SELF, ownerPrimary, ownerSub), rows.map { it.bucketId })
        assertEquals(Piece.Said(Notice(Msg.MEMORY_BUCKETS_YOU)), rows[0].label)
        assertEquals(listOf(true, false, false), rows.map { it.own }, "only the caller's own root is theirs")
        assertEquals(Piece.Plain("Olga"), rows[1].label)
        assertEquals(Buckets.SELF, c.current.selected, "the picker opens on the caller's own")
        assertEquals(listOf("mine"), c.current.media.map { it.mediaId }, "'You' holds the caller's unbucketed media, not another member's")
        assertEquals(1, rows[0].mediaCount)
    }

    // #991: what the server takes on another member's root — a sub-bucket under its primary, a move within it — and nothing across
    @Test
    fun onlyWhatTheServerTakesIsOffered_aMoveStaysWithinItsRoot() = runTest {
        val s = Server()
        val c = open(s)
        assertEquals(emptyList(), c.current.moveTargets.map { it.bucketId }, "nothing of the caller's own to move into yet")
        c.pick(ownerPrimary)
        assertEquals(true to false, c.current.createsSub to c.current.renames)
        assertEquals(listOf(ownerSub), c.current.moveTargets.map { it.bucketId }, "the owner's root only, never the caller's 'You'")
        assertEquals(Buckets.Outcome.REFUSED, c.move(listOf("o1"), Buckets.SELF), "a move across roots is not sent")
        c.pick(ownerSub)
        assertEquals(false to true, c.current.createsSub to c.current.renames)
        assertEquals(listOf(ownerPrimary), c.current.moveTargets.map { it.bucketId })
        assertEquals(Buckets.Outcome.REFUSED, c.create("Nested"), "no sub-bucket under a sub-bucket")
        assertEquals(emptyList(), s.moves)
        assertEquals(emptyList(), s.creates)
        c.pick(ownerPrimary)
        assertEquals(Buckets.Outcome.DONE, c.create("Beach"))
        assertEquals(listOf<String?>(ownerPrimary), s.creates, "made under the owner's primary, as the server allows a member")
    }

    // #990: a curation's re-read follows the gallery's, so a moved medium has left the bucket once the move answers
    @Test
    fun aMovedMediumHasLeftItsBucketWhenTheMoveAnswers() = runTest {
        val s = Server()
        val c = open(s)
        c.pick(ownerPrimary)
        assertEquals(listOf("o1", "o2"), c.current.media.map { it.mediaId })
        assertEquals(Buckets.Outcome.DONE, c.move(listOf("o1"), ownerSub))
        assertEquals(listOf("o2"), c.current.media.map { it.mediaId }, "the chosen bucket no longer lists the moved medium")
        assertEquals(1, c.current.row?.mediaCount)
        c.pick(ownerSub)
        assertEquals(listOf("o1", "o3"), c.current.media.map { it.mediaId })
    }

    // #996: a bucket's tile opens a lightbox over that bucket's media, which follows them across the gallery's reads
    @Test
    fun aBucketsLightboxPagesTheBucket() = runTest {
        val s = Server()
        val c = open(s)
        c.pick(ownerPrimary)
        val box = assertNotNull(c.lightbox("o2"))
        assertEquals(listOf("o1", "o2"), box.current.rows.map { it.mediaId }, "the bucket's media, not the gallery's")
        assertEquals("o2", box.current.row?.mediaId)
        c.move(listOf("o1"), ownerSub)
        runCurrent()
        assertEquals(listOf("o2"), box.current.rows.map { it.mediaId }, "it follows the bucket across a re-read")
        box.close()
    }
}
