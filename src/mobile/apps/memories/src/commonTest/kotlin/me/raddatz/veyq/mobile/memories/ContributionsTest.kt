package me.raddatz.veyq.mobile.memories

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.NodeRef
import me.raddatz.veyq.mobile.session.OfflineAsOf
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
    private val myPrimary = "p-me"
    private val mySub = "s-me"
    private fun photo(name: String) = UploadSource.of(name, 4, "image/jpeg", null) { _, n -> ByteArray(n) }

    private fun blob(id: String) = MemoryMedia.Blob(id, 1, 29L + V1.FRAME_OVERHEAD, "", null)

    private fun item(id: String, owner: String, bucket: String?, at: Long) = MemoryMedia.Item(
        "s", "m", id, blob("o-$id"), blob("t-$id"), blob("p-$id"),
        MemoryMedia.Meta(4000, 3000, at, null, "exif", null, null, null, null), "image/jpeg", "$id.jpg", owner, false, ByteArray(0),
        bucketId = bucket,
    )

    /** The server the test plays: the gallery's media and the buckets, a move re-filing a medium as the server would. */
    private inner class Server(val ownRoot: Boolean = false) {
        var items = listOf(
            item("o1", "user:u-owner", ownerPrimary, 3),
            item("o2", "user:u-owner", ownerPrimary, 2),
            item("o3", "user:u-owner", ownerSub, 1),
            item("mine", "user:$me", null, 4),
        )
        val moves = mutableListOf<Pair<List<String>, String?>>()
        val creates = mutableListOf<String?>()
        /** How often the bucket list was read, and buckets the server lists beyond the fixed ones (another uploader's primary). */
        var lists = 0
        var extra = listOf<Buckets.Bucket>()

        fun buckets() = Buckets.Listed(
            extra + listOf(
                Buckets.Bucket(ownerPrimary, "user:u-owner", null, true, items.count { it.bucketId == ownerPrimary }, null, null, null),
                Buckets.Bucket(ownerSub, "user:u-owner", ownerPrimary, false, items.count { it.bucketId == ownerSub }, null, null, null),
            ) + if (!ownRoot) emptyList() else listOf(
                Buckets.Bucket(myPrimary, "user:$me", null, true, 0, null, null, null),
                Buckets.Bucket(mySub, "user:$me", myPrimary, false, 0, null, null, null),
            ),
            null,
        )

        /** What the page uploaded: each batch's names, and the filer it was handed (null: none, it lands in the primary). */
        val uploads = mutableListOf<Pair<List<String>, (suspend (String) -> Unit)?>>()
        val queuedOffline = mutableListOf<List<String>>()
        val reach = MutableStateFlow(true)
        var offline: OfflineAsOf? = null
        var moveAnswer = Buckets.Outcome.DONE

        val gallery = object : GalleryModel.Door {
            override suspend fun gallery() = MemoryMedia.Gallery(MemoryMedia.order(items), 1, 0, offline)
            override val online get() = reach
            override val queues get() = true
            override suspend fun queueMedia(sources: List<UploadSource>) = sources.size.also { queuedOffline += sources.map { it.name } }
            override suspend fun scopeNodes() = emptyList<NodeRef>()
            override suspend fun read(item: MemoryMedia.Item, blob: MemoryMedia.Blob) = blob.blobId.encodeToByteArray()
            override fun reader(item: MemoryMedia.Item) = null
            override suspend fun stream(item: MemoryMedia.Item, sink: suspend (ByteArray) -> Unit) = false
            override suspend fun exif(item: MemoryMedia.Item): MediaDetails.Exif? = null
            override suspend fun uploaders(items: List<MemoryMedia.Item>) = emptyMap<String, MediaDetails.Uploader>()
            override fun upload(sources: List<UploadSource>) = sources.size.also { uploads += sources.map { it.name } to null }
            override fun upload(sources: List<UploadSource>, filed: suspend (mediaId: String) -> Unit) =
                sources.size.also { uploads += sources.map { it.name } to filed }
            override val revisions = MutableStateFlow<Any?>(Any())
            override val uploaded = MutableSharedFlow<String>()
        }

        val door = object : Contributions.Door {
            override suspend fun list() = buckets().also { lists++ } to mapOf(ownerSub to "Sunsets")
            override val self = me
            override fun person(userId: String) = if (userId == "u-owner") "Olga" else null
            override suspend fun create(name: String, parent: String?) = Buckets.Outcome.DONE.also { creates += parent }
            override suspend fun rename(bucketId: String, name: String) = Buckets.Outcome.DONE
            override suspend fun delete(bucketId: String) = Buckets.Outcome.DONE
            override suspend fun move(mediaIds: List<String>, target: String?): Buckets.Outcome {
                moves += mediaIds to target
                if (moveAnswer != Buckets.Outcome.DONE) return moveAnswer
                items = items.map { if (it.mediaId in mediaIds) item(it.mediaId, it.ownerKey!!, target, it.meta!!.capturedAt!!) else it }
                return Buckets.Outcome.DONE
            }
        }
    }

    private suspend fun TestScope.open(server: Server): Contributions = opened(server).second

    private suspend fun TestScope.opened(server: Server): Pair<GalleryModel, Contributions> {
        val g = GalleryModel(memory, server.gallery, backgroundScope, backgroundScope).also { runCurrent() }
        return g to Contributions(g, server.door).also { it.read() }
    }

    // #96 (the web's MemoryBuckets.test.ts): a bucket counts what the gallery shows, live — an upload elsewhere moves it
    // with no bucket-list request; the server's `mediaCount` as the list was read is never shown
    @Test
    fun aBucketCountsWhatTheGalleryShowsLiveWithNoRequest() = runTest {
        val s = Server()
        val (g, c) = opened(s)
        backgroundScope.launch { c.follow() }
        runCurrent()
        val listsAtOpen = s.lists
        assertEquals(2, c.current.rows.single { it.bucketId == ownerPrimary }.mediaCount)
        s.items = s.items + item("o4", "user:u-owner", ownerPrimary, 5)
        g.readAnew()
        runCurrent()
        assertEquals(3, c.current.rows.single { it.bucketId == ownerPrimary }.mediaCount, "the count follows the gallery")
        assertEquals(listsAtOpen, s.lists, "no bucket list read for a count")
        c.pick(ownerPrimary)
        assertEquals(listOf("o4", "o1", "o2"), c.current.media.map { it.mediaId }, "the chosen bucket's media follow too")
    }

    // #96: a medium in a bucket the list lacks reads the list again — once per such bucket
    @Test
    fun aMediumInABucketTheListLacksReadsTheListOnce() = runTest {
        val s = Server()
        val (g, c) = opened(s)
        backgroundScope.launch { c.follow() }
        runCurrent()
        val listsAtOpen = s.lists
        s.extra = listOf(Buckets.Bucket("p-guest", "share:i1", null, true, 0, "sh1", null, null))
        s.items = s.items + item("g1", "share:i1", "p-guest", 6)
        g.readAnew()
        runCurrent()
        assertEquals(listsAtOpen + 1, s.lists, "the list read once for the bucket it lacked")
        assertEquals(1, c.current.rows.single { it.bucketId == "p-guest" }.mediaCount)
        s.items = s.items + item("g2", "share:i2", "p-unlisted", 7)
        g.readAnew()
        runCurrent()
        g.readAnew()
        runCurrent()
        assertEquals(listsAtOpen + 2, s.lists, "a bucket the server still lacks is asked for once, not on every read")
    }

    // #99 (the web's MemoryBuckets.test.ts): another uploader whose every medium was deleted leaves the picker; a restore
    // brings them back; an uploader listed with nothing before the gallery showed one stays, and so does the caller's own
    @Test
    fun anUploaderWhoseMediaAreAllDeletedLeavesThePicker() = runTest {
        val s = Server()
        s.extra = listOf(
            Buckets.Bucket("p-guest", "share:i1", null, true, 1, "sh1", null, null),
            Buckets.Bucket("p-quiet", "share:i2", null, true, 0, "sh2", null, null),
        )
        s.items = s.items + item("g1", "share:i1", "p-guest", 6)
        val (g, c) = opened(s)
        backgroundScope.launch { c.follow() }
        runCurrent()
        assertEquals(true, c.current.rows.any { it.bucketId == "p-guest" })
        val restore = s.items
        s.items = s.items.filter { it.mediaId != "g1" }
        g.readAnew()
        runCurrent()
        assertEquals(false, c.current.rows.any { it.bucketId == "p-guest" }, "the emptied uploader has left")
        assertEquals(true, c.current.rows.any { it.bucketId == "p-quiet" }, "a 0 never held on screen means nothing yet")
        s.items = s.items.filter { it.bucketId != ownerPrimary && it.bucketId != ownerSub }
        g.readAnew()
        runCurrent()
        assertEquals(true, c.current.rows.any { it.bucketId == ownerPrimary }, "a primary with a sub-bucket under it stays")
        s.items = restore
        g.readAnew()
        runCurrent()
        assertEquals(1, c.current.rows.single { it.bucketId == "p-guest" }.mediaCount, "a restore brings the uploader back")
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

    // inbox #1065: an upload into the bucket shown — the web's Beiträge drop and the apps' alike. The caller's own sub-bucket
    // files each medium by a move once it is in; their primary needs none; another uploader's root takes none (the server
    // moves within one root only). Levers: drop the filer, file into the primary, or let `intake` take another's root.
    @Test
    fun anUploadIntoTheCallersSubBucketIsFiledThereByAMoveOnceItIsIn() = runTest {
        val s = Server(ownRoot = true)
        val c = open(s)
        c.pick(mySub)
        assertEquals(Contributions.Intake.TAKES, c.intake())
        assertEquals(2, c.upload(listOf(photo("a.jpg"), photo("b.jpg"))))
        val (names, filed) = s.uploads.single()
        assertEquals(listOf("a.jpg", "b.jpg"), names)
        assertNotNull(filed, "a sub-bucket's upload is filed").invoke("new-a")
        assertEquals(listOf<Pair<List<String>, String?>>(listOf("new-a") to mySub), s.moves, "each medium in is moved into the bucket shown")
        assertEquals(null, c.current.refused)
    }

    @Test
    fun anUploadIntoTheCallersPrimaryLandsThereWithNoMove() = runTest {
        val s = Server(ownRoot = true)
        val c = open(s)
        c.pick(myPrimary)
        assertEquals(1, c.upload(listOf(photo("a.jpg"))))
        assertEquals(listOf(listOf("a.jpg") to true), s.uploads.map { it.first to (it.second == null) }, "the primary is where an upload lands: no filer")
        val virtual = Server()
        val v = open(virtual)
        assertEquals(Buckets.SELF, v.current.selected, "control: the virtual 'You'")
        assertEquals(1, v.upload(listOf(photo("b.jpg"))))
        assertEquals(listOf(listOf("b.jpg") to true), virtual.uploads.map { it.first to (it.second == null) })
    }

    @Test
    fun anotherUploadersRootTakesNoUpload() = runTest {
        val s = Server(ownRoot = true)
        val c = open(s)
        for (bucket in listOf(ownerPrimary, ownerSub)) {
            c.pick(bucket)
            assertEquals(Contributions.Intake.NOT_OWN, c.intake(), bucket)
            assertEquals(0, c.upload(listOf(photo("a.jpg"))), bucket)
        }
        assertEquals(emptyList(), s.uploads)
    }

    // offline a sub-bucket's filing would be a move, a write (ADR 0140 decision 7): refused; the primary still queues
    @Test
    fun offlineASubBucketTakesNoUploadThePrimaryStillQueues() = runTest {
        val s = Server(ownRoot = true).also { it.reach.value = false; it.offline = OfflineAsOf(1L) }
        val c = open(s)
        c.pick(mySub)
        assertEquals(Contributions.Intake.OFFLINE, c.intake())
        assertEquals(0, c.upload(listOf(photo("a.jpg"))))
        c.pick(myPrimary)
        assertEquals(Contributions.Intake.TAKES, c.intake())
        assertEquals(1, c.upload(listOf(photo("b.jpg"))))
        runCurrent()
        assertEquals(listOf(listOf("b.jpg")), s.queuedOffline)
        assertEquals(emptyList(), s.uploads)
    }

    @Test
    fun aRefusedFilingIsSaidAsACurationsRefusal() = runTest {
        val s = Server(ownRoot = true).also { it.moveAnswer = Buckets.Outcome.REFUSED }
        val c = open(s)
        c.pick(mySub)
        c.upload(listOf(photo("a.jpg")))
        assertNotNull(s.uploads.single().second).invoke("new-a")
        assertEquals(Buckets.Outcome.REFUSED, c.current.refused, "the medium stays in the primary, and that is said")
    }
}
