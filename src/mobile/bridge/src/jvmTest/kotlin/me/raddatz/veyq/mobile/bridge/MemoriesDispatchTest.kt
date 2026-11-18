package me.raddatz.veyq.mobile.bridge

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.blob.UploadQueue
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.depot.FileSave
import me.raddatz.veyq.mobile.memories.GalleryModel
import me.raddatz.veyq.mobile.memories.MediaDetails
import me.raddatz.veyq.mobile.memories.MediaUpload
import me.raddatz.veyq.mobile.memories.MediaUploads
import me.raddatz.veyq.mobile.memories.MemoryCreate
import me.raddatz.veyq.mobile.memories.MemoryMedia
import me.raddatz.veyq.mobile.navigation.Connection
import me.raddatz.veyq.mobile.navigation.Hidden
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.NodeHeaderModel
import me.raddatz.veyq.mobile.navigation.NodeRef
import me.raddatz.veyq.mobile.navigation.RecentUses
import me.raddatz.veyq.mobile.navigation.ShellModel
import me.raddatz.veyq.mobile.navigation.SpaceRef
import me.raddatz.veyq.mobile.navigation.TenantRef
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.session.SessionModel
import me.raddatz.veyq.mobile.session.SessionModel.Gate
import me.raddatz.veyq.mobile.session.StaySignedIn
import me.raddatz.veyq.mobile.session.TrustChange
import me.raddatz.veyq.mobile.session.TrustedDevice
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Memories through the bridge (ADR 0123's Consequences, the bridge; ADR 0137): a Memory's page and its lightbox, the
 * media added by path with the tiers a host the test plays makes through real [Host] requests and replies (the JPEGs as the
 * reply's payload, [HostTiers]), a video read through its reader, an original saved, a medium trashed and undone, a chapter
 * made — every call and watch as JSON, over a Memory the test plays as the server ([PlayedMemory]) and core's own
 * `GalleryModel`, `Lightbox` and `MediaUploads`. The dispatch is one line per call: what is held here is that each reaches
 * its model, answers what the host acts on at once, and that nothing outlives what holds it.
 */
class MemoriesDispatchTest {
    private data class Acc(override val key: String, override val tenants: List<TenantRef>) : Connection {
        override val label: String get() = key.substringBefore('#')
    }

    private class Door : SessionModel.Door<Acc, String> {
        val account = Acc("a.example#u1", listOf(TenantRef("ta", "A", "a.example")))
        override val primitives = BouncyCastlePrimitives
        override fun trusted() = emptyList<TrustedDevice>()
        override suspend fun signIn(server: String, email: String, password: String, secretKey: String) = account
        override suspend fun signInWithKey(server: String, email: String, password: String, secretKey: ByteArray): Acc = error("no kit")
        override suspend fun unlockOnDevice(record: TrustedDevice, password: String) = error("no trusted device")
        override suspend fun resume(record: TrustedDevice) = error("no trusted device")
        override suspend fun trust(account: Acc, password: String) {}
        override suspend fun stay(account: Acc, level: StaySignedIn) = TrustChange.FAILED
        override suspend fun trustWithPassword(account: Acc, password: String) = TrustChange.FAILED
        override fun defaultTenant(account: Acc) = account.tenants.firstOrNull()
        override fun trustedBy(account: Acc): TrustedDevice? = null
        override suspend fun open(account: Acc, tenant: TenantRef) = "${account.key}/${tenant.tenantId}"
        override suspend fun spaces(session: String) = listOf(SpaceRef(session, "SHARED", session))
        override suspend fun signOut(account: Acc) {}
        override suspend fun forgetDevice(account: Acc) {}
        override fun staySignedIn() = emptySet<StaySignedIn>()
        override fun declined(account: Acc) = false
        override fun setDeclined(account: Acc, declined: Boolean) {}
    }

    /** A header's door that only reads: a Memory's header, its plan joined by its page's own actions. */
    private object Header : NodeHeaderModel.Door {
        override fun isFavorite(ref: NodeAt) = false
        override fun inIdentityFolder(ref: NodeAt) = false
        override fun linksOn() = true
        override suspend fun reportOffered() = false
        override suspend fun toggleFavorite(ref: NodeAt) = false
        override suspend fun trash(ref: NodeAt): ShellModel.Trashed = error("not here")
        override suspend fun undoTrash(node: OpenNode): Notice? = null
        override suspend fun rename(ref: NodeAt, name: String): ShellModel.Renamed = error("not here")
        override fun closeOpen(ref: NodeAt) {}
        override fun changed() {}
    }

    private val memory = NodeAt("s", "m", "MEMORY", "Ostsee 2026")
    private val chapter = NodeAt("s", "c1", "CHAPTER", "Day one")
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13, 7)

    /**
     * A Memory the test plays as the server for its page and its uploads: a photo, a video with a poster, a video without one
     * and a blocked photo, each tier's bytes its blob's id, the video's original in frames of the suite's size; a trashed
     * medium is out of the listing until restored; a medium uploaded joins it once its job ran; what each call reached is
     * recorded. Chapter "c1" has a cover.
     */
    private class PlayedMemory {
        val original = byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte()) +
            ByteArray(3 * V1.FRAME_PLAINTEXT + 333) { ((it * 5 + it / V1.FRAME_PLAINTEXT) % 249).toByte() }
        private val frames = original.toList().chunked(V1.FRAME_PLAINTEXT).map { it.toByteArray() }
        val items = CopyOnWriteArrayList<MemoryMedia.Item>()
        val trashed: MutableSet<String> = ConcurrentHashMap.newKeySet()
        @Volatile var rowCoverOpens = 0
        val sent = CopyOnWriteArrayList<String>()
        val listings = AtomicInteger()
        val moved = AtomicInteger()
        val derived = ConcurrentHashMap<String, MediaUpload.Derived>()
        val revisions = MutableStateFlow<Any?>(Any())
        /** What the page is told is on its way into it (its placeholder tiles, ADR 0145 decision 3). */
        val tiles = MutableStateFlow<List<me.raddatz.veyq.mobile.memories.UploadTile>>(emptyList())

        private fun blob(id: String, size: Int = 29, chunks: Int = 1) = MemoryMedia.Blob(id, chunks, size.toLong() + chunks * V1.FRAME_OVERHEAD, "", null)

        fun item(id: String, at: Long, nodeId: String = "m", type: String = "image/jpeg", thumbnail: Boolean = true, preview: Boolean = true, blocked: Boolean = false) =
            MemoryMedia.Item(
                "s", nodeId, id,
                if (type.startsWith("video/")) blob("o-$id", original.size, frames.size) else blob("o-$id"),
                if (thumbnail) blob("t-$id") else null, if (preview) blob("p-$id") else null,
                MemoryMedia.Meta(4000, 3000, at * 1000, null, "exif", null, null, null, null), type, "$id.${type.substringAfter('/')}",
                "user:u1", blocked, ByteArray(0),
            )

        init {
            items += item("a", 30)
            items += item("clip", 20, type = "video/webm")
            items += item("raw", 10, type = "video/mp4", thumbnail = false, preview = false)
            items += item("bad", 5, blocked = true)
        }

        fun door(node: NodeAt, uploads: MediaUploads) = object : GalleryModel.Door {
            override suspend fun gallery(): MemoryMedia.Gallery {
                val n = listings.incrementAndGet()
                return MemoryMedia.Gallery(MemoryMedia.order(items.filter { it.nodeId == node.nodeId && it.mediaId !in trashed }), n, 0)
            }
            override suspend fun scopeNodes() = listOf(NodeRef("s", "c1", "m", "CHAPTER", "Day one"), NodeRef("s", "f", null, "FOLDER", "Not one"))
            override suspend fun read(item: MemoryMedia.Item, blob: MemoryMedia.Blob) = blob.blobId.encodeToByteArray()
            override fun reader(item: MemoryMedia.Item) =
                RangeReader(original.size.toLong(), frames.size) { first, until, sink -> (first until until).forEach { sink(frames[it]) } }
            override suspend fun stream(item: MemoryMedia.Item, sink: suspend (ByteArray) -> Unit): Boolean {
                frames.forEach { sink(it) }
                return true
            }
            override suspend fun exif(item: MemoryMedia.Item): MediaDetails.Exif? = null
            override suspend fun uploaders(items: List<MemoryMedia.Item>) =
                items.mapNotNull { it.ownerKey }.toSet().associateWith { MediaDetails.Uploader(MediaDetails.UploaderKind.MEMBER, "Ada") }
            override fun upload(sources: List<UploadSource>) = uploads.upload(node.scopeId, node.nodeId, node.name, sources)
            override val revisions = this@PlayedMemory.revisions
            override val uploaded = uploads.uploaded
            override val uploading = tiles
            override suspend fun moved(since: MemoryMedia.Gallery) = moved.incrementAndGet() == 1
            override suspend fun trash(mediaId: String): GalleryModel.Removal {
                sent += "trash:$mediaId"
                trashed += mediaId
                return GalleryModel.Removal.DONE
            }
            override suspend fun restore(mediaId: String): GalleryModel.Removal {
                sent += "restore:$mediaId"
                trashed -= mediaId
                return GalleryModel.Removal.DONE
            }
            override suspend fun createChapter(name: String): MemoryCreate.Made {
                sent += "chapter:$name"
                return MemoryCreate.Made(NodeAt("s", "c-new", "CHAPTER", name), null)
            }
            override suspend fun cover(nodeId: String): ByteArray? = if (nodeId == "c1") "cover-c1".encodeToByteArray() else null
        }

        /** Memories' uploads over the app's queue, each job the core's shape: its tiers prepared ahead, its file read in frames. */
        fun uploads(queue: UploadQueue, deriver: MediaUpload.Deriver) = MediaUploads(queue, object : MediaUploads.Door {
            override fun job(scopeId: String, nodeId: String, source: UploadSource) = Job(source, deriver, nodeId)
            override fun musicJob(scopeId: String, nodeId: String, track: me.raddatz.veyq.mobile.memories.music.MusicUpload.Check.Taken) =
                Job(track.source, deriver, nodeId)
            override fun changed() {
                sent += "changed"
            }
            override fun left(source: UploadSource) {}
        })

        inner class Job(private val source: UploadSource, private val deriver: MediaUpload.Deriver, private val nodeId: String) : UploadQueue.Job {
            override val name get() = source.name
            override val sizeBytes get() = source.size
            override val prepares get() = true
            private var tiers: MediaUpload.Derived? = null
            private val lock = kotlinx.coroutines.sync.Mutex()

            // once, as the core's job: the queue prepares ahead while its run awaits the same work
            override suspend fun prepare() = lock.withLock {
                if (tiers == null) tiers = suspendCancellableCoroutine { c -> deriver.derive(source, source.type ?: "", { c.resume(it) }) }
            }

            override suspend fun run(progress: (Double) -> Unit) {
                prepare()
                var from = 0L
                while (from < source.size) {
                    val n = minOf(V1.FRAME_PLAINTEXT.toLong(), source.size - from).toInt()
                    source.read(from, n)
                    from += n
                    progress(from.toDouble() / source.size)
                }
                source.close()
                derived[source.name] = tiers!!
                sent += "upload:$nodeId/${source.name}"
                items += item("up-${source.name}", 100 + items.size.toLong(), nodeId, source.type ?: "image/jpeg")
            }
        }
    }

    /**
     * The host the tests play over a real [Host]: each request recorded as JSON reads it back; a video lasts 150 ms; a file's
     * tiers are `P-<name>` and `T-<name>` packed into the reply's payload — but a file whose name starts with "raw" does not
     * decode here (no tiers).
     */
    private class PlayedHost {
        val asked: MutableList<HostRequest> = java.util.Collections.synchronizedList(ArrayList())
        lateinit var host: Host

        init {
            host = Host { token, json ->
                val request = wire.decodeFromString(HostRequest.serializer(), json)
                asked += request
                val (reply, payload) = when (request) {
                    is HostRequest.MediaDuration -> HostReply.Value("150") to null
                    is HostRequest.MediaTiers -> {
                        val name = File(request.path).name
                        if (name.startsWith("raw")) HostReply.Tiers(null, null, 0) to null
                        else ("P-$name".encodeToByteArray() to "T-$name".encodeToByteArray()).let { (p, t) -> HostReply.Tiers(640, 480, p.size) to p + t }
                    }
                    else -> HostReply.Failed("no store here") to null
                }
                host.reply(token, wire.encodeToString(HostReply.serializer(), reply), payload)
            }
        }
    }

    private fun bridge(played: PlayedMemory, host: PlayedHost) = Bridge {
        val hidden = Hidden({}, emptySet())
        Composition(SessionModel(Door(), hidden, RecentUses({}, emptyList())), hidden, JvmFiles, tiers = HostTiers(host.host)) { _ ->
            Tenant(
                shell = null, pot = null, me = "u1", pictures = MutableStateFlow(emptyMap()), loadPictures = {},
                header = { node, facts, _ -> NodeHeaderModel(node, facts, Header) },
                mediaUploads = { queue, deriver, _ -> played.uploads(queue, deriver) },
                gallery = { node, uploads, _ ->
                    // a clock whose first reading is the poll's start and every later one long after: the poll ticks at once
                    val reads = AtomicInteger()
                    GalleryModel(node, played.door(node, uploads), now = { if (reads.getAndIncrement() == 0) 0L else 1_000_000L })
                },
                // a Memory "m" whose cover frames the picture's middle half; every other node names none
                rowCover = { nodeId, held ->
                    val cover = if (nodeId != "m") null else me.raddatz.veyq.mobile.session.NodeCover.read(kotlinx.serialization.json.JsonObject(mapOf("cover" to
                        me.raddatz.veyq.mobile.session.NodeCover.uploaded(me.raddatz.veyq.mobile.blob.ApplicationBlobs.Plan("b-m", 1, 5, "d3JhcA==", "m"), me.raddatz.veyq.mobile.session.NodeCover.Crop(0.25, 0.0, 0.5, 1.0)))))
                    me.raddatz.veyq.mobile.session.NodeCover.row(cover, held) { played.rowCoverOpens++; "row-m".encodeToByteArray() }
                },
                createMemory = { scopeId, parentId, name, _, _ ->
                    MemoryCreate.check(MemoryCreate.MEMORY, parentId?.let { "FOLDER" }, name, emptyList())?.let { MemoryCreate.Made(null, it) }
                        ?: MemoryCreate.Made(NodeAt(scopeId, "m-new", MemoryCreate.MEMORY, name.trim()), null)
                },
            )
        }
    }

    private suspend fun Bridge<*, *>.signedIn() {
        val gate = watching(Watched.Session)
        assertEquals(Answer.Done, ask(Call.SessionStart))
        assertEquals(Answer.Done, ask(Call.SessionSignIn("a.example", "p@a.example", "right", "A3-SECRET")))
        gate.until { (it as Update.Session).gate as? Gate.Signed }
        unwatch(gate.id)
    }

    @Test
    fun aMemoryIsMadeShownFilledByPathPlayedSavedTrashedAndUndoneThroughCalls() = runBlocking {
        val played = PlayedMemory()
        val host = PlayedHost()
        val b = bridge(played, host)
        assertEquals(Answer.Refused(Answer.Why.NO_TENANT_OPEN), b.ask(Call.GalleryOpen(memory)))
        b.signedIn()

        // New Memory: made, or said as the web's dialog says it
        assertEquals(Answer.Created(NodeAt("s", "m-new", "MEMORY", "Ostsee"), null), b.ask(Call.ShellCreateMemory("s", null, " Ostsee ")))
        assertEquals(Answer.Created(null, Notice(Msg.FOLDER_NAME_EMPTY)), b.ask(Call.ShellCreateMemory("s", "f", "  ")))

        // the page: only a Memory's or a chapter's
        assertEquals(Answer.Refused(Answer.Why.NOT_OFFERED), b.ask(Call.GalleryOpen(NodeAt("s", "f", "FOLDER", "Trip"))))
        val g = (b.ask(Call.GalleryOpen(memory)) as Answer.GalleryOpened).gallery
        val page = b.watching(Watched.Gallery(g))
        val shown = page.until { u -> (u as Update.Gallery).shown?.takeIf { it.rows != null } }
        assertEquals(listOf("a", "clip", "raw", "bad"), shown.rows!!.map { it.mediaId }, "the grid's order")
        assertEquals(Notice(Msg.MEMORY_ITEMS, 4), shown.count)
        assertEquals(listOf(chapter), shown.chapters)
        assertEquals(listOf(AppKinds.Create.CHAPTER), shown.creates.map { it.create }, "a Memory's page makes a chapter")
        assertTrue(shown.canUpload && shown.canSave)
        val bad = shown.rows.single { it.mediaId == "bad" }
        assertEquals(Msg.MEMORY_BLOCKED to null, bad.said to bad.tile)
        assertTrue(shown.rows.all { it.canDelete })

        // a tile is the grid's tier, beside the JSON; a video without a poster has none; a chapter's cover by its row
        val (tileAnswer, tile) = b.askWithPayload(Call.GalleryTile(g, "a"))
        assertEquals(Answer.Bytes(null), tileAnswer)
        assertContentEquals("t-a".encodeToByteArray(), tile, "a tile's bytes are not its tier's")
        assertEquals(Answer.Refused(Answer.Why.NO_BYTES), b.ask(Call.GalleryTile(g, "raw")))
        assertContentEquals("cover-c1".encodeToByteArray(), b.askWithPayload(Call.GalleryChapterCover(g, "c1")).second)
        assertEquals(Answer.Refused(Answer.Why.NO_COVER), b.ask(Call.GalleryChapterCover(g, "f")), "a node that is no chapter of this Memory")

        // a list row's cover (Memories' list): the stored picture with its frame and crop once, then the frame alone while
        // the host holds it — a row redrawn on every state opens no blob again
        val (rowAnswer, rowBytes) = b.askWithPayload(Call.ShellRowCover("m", null))
        val rowCover = rowAnswer as Answer.RowCover
        assertEquals(Answer.RowCover(rowCover.frame, me.raddatz.veyq.mobile.session.NodeCover.Crop(0.25, 0.0, 0.5, 1.0), true, null), rowCover)
        assertContentEquals("row-m".encodeToByteArray(), rowBytes, "a row's cover is the stored picture")
        val (heldAnswer, heldBytes) = b.askWithPayload(Call.ShellRowCover("m", rowCover.frame))
        assertEquals(Answer.RowCover(rowCover.frame, rowCover.crop, false, null), heldAnswer, "a held frame is not sent again")
        assertEquals(null, heldBytes)
        assertEquals(1, played.rowCoverOpens, "a held frame's blob is not opened again")
        assertEquals(Answer.Refused(Answer.Why.NO_COVER), b.ask(Call.ShellRowCover("f", null)), "a node with no cover")

        // the justified grid is the core's (ADR 0136's Consequences): every row 4:3, so 1 000 wide (target 200) holds all four in one justified row,
        // 500 wide (target 150) three justified and a widow at the target height; the host only places the frames
        val wide = b.ask(Call.GalleryLayout(g, 1000.0, 0.0, 600.0)) as Answer.GridLayout
        assertEquals(listOf(listOf("a", "clip", "raw", "bad")), wide.rows.map { r -> r.tiles.map { it.mediaId } }, "the grid's rows in its order")
        val row = wide.rows.single()
        assertEquals(1000.0, row.tiles.last().let { it.left + it.width }, 1e-9, "a full row justified to the width")
        assertEquals((1000.0 - 3 * 8.0) / (4 * 4.0 / 3.0), row.height, 1e-9)
        assertEquals(row.height, wide.height, 1e-9)
        val narrow = b.ask(Call.GalleryLayout(g, 500.0, 0.0, 600.0)) as Answer.GridLayout
        assertEquals(listOf(3, 1), narrow.rows.map { it.tiles.size }, "laid out anew at another width")
        assertEquals(listOf(0, 1), narrow.rows.map { it.index })
        assertEquals(150.0, narrow.rows[1].height, 1e-9, "the widow keeps the target height")
        assertEquals(narrow.rows[0].height + 8.0, narrow.rows[1].top, 1e-9, "the gap between rows")
        // only the rows near the view are handed over (ADR 0105): a short view at the top mounts the first row (and its
        // one view below), a view far past the end none — the grid's height and its count stay the whole grid's
        val top = b.ask(Call.GalleryLayout(g, 500.0, 0.0, 10.0)) as Answer.GridLayout
        assertEquals(listOf(0), top.rows.map { it.index }, "the window, not every row")
        assertEquals(narrow.height to 2, top.height to top.count)
        val past = b.ask(Call.GalleryLayout(g, 500.0, 5000.0, 10.0)) as Answer.GridLayout
        assertEquals(emptyList(), past.rows.map { it.index })
        assertEquals(2, past.count)
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_GALLERY), b.ask(Call.GalleryLayout(-1, 500.0, 0.0, 600.0)))

        // what this device sends into the page: placeholder tiles first, each named for a screen reader (ADR 0145 decision 3)
        played.tiles.value = listOf(
            me.raddatz.veyq.mobile.memories.UploadTile("u1", "beach.jpg", false, me.raddatz.veyq.mobile.blob.UploadQueue.Status.UPLOADING, 0.5, null),
            me.raddatz.veyq.mobile.memories.UploadTile("u2", "clip.mp4", true, me.raddatz.veyq.mobile.blob.UploadQueue.Status.FAILED, 0.0, null),
        )
        val sending = page.until { u -> (u as Update.Gallery).shown?.takeIf { it.uploading.size == 2 } }
        assertEquals(listOf("upload:u1", "upload:u2"), sending.uploading.map { it.id })
        assertEquals(listOf(Notice(Msg.MEMORY_UPLOAD_TILE, "beach.jpg"), Notice(Msg.MEMORY_UPLOAD_TILE_FAILED, "clip.mp4")), sending.uploading.map { it.label })
        assertEquals(listOf(false to 0.5, true to 0.0), sending.uploading.map { it.failed to it.progress })
        assertTrue(sending.uploading[1].video)
        val placed = b.ask(Call.GalleryLayout(g, 1000.0, 0.0, 600.0)) as Answer.GridLayout
        assertEquals(listOf("upload:u1", "upload:u2", "a", "clip", "raw", "bad"), placed.rows.flatMap { r -> r.tiles.map { it.mediaId } }, "the placeholders lead the grid")
        played.tiles.value = emptyList()
        page.until { u -> (u as Update.Gallery).shown?.takeIf { it.uploading.isEmpty() } }

        // the header's plan holds the page's own "Add photos" once the page is named
        val header = (b.ask(Call.HeaderOpen(memory, AppKinds.Facts(), 3)) as Answer.HeaderOpened).header
        assertEquals(Answer.Done, b.ask(Call.HeaderFacts(header, AppKinds.Facts(), gallery = g)))
        val plan = b.watching(Watched.Header(header)).until { u ->
            (u as Update.Header).shown?.plan?.takeIf { p -> (p.buttons + p.menu + p.last).any { it.id == GalleryModel.UPLOAD } }
        }
        assertEquals(Msg.MEMORY_ADD, (plan.buttons + plan.menu + plan.last).single { it.id == GalleryModel.UPLOAD }.word)

        // added by path: each read at offsets a frame at a time, its tiers the host's (on the queue's thread, the core's spec)
        val dir = java.nio.file.Files.createTempDirectory("veyq-memory").toFile()
        val photo = File(dir, "photo.jpg").apply { writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()) + ByteArray(2 * V1.FRAME_PLAINTEXT + 10) { (it % 13).toByte() }) }
        val movie = File(dir, "movie.webm").apply { writeBytes(byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte()) + ByteArray(100)) }
        val notes = File(dir, "notes.txt").apply { writeText("not a medium") }
        JvmFiles.reads.clear()
        val queued = b.ask(Call.MemoryUpload(g, listOf(photo.path, movie.path, notes.path, File(dir, "gone.jpg").path)))
        assertEquals(Answer.Queued(2, Notice(Msg.MEMORY_NOT_MEDIA, 2)), queued, "an upload answered before it was queued, or what it left out unsaid")
        val filled = page.until { u -> (u as Update.Gallery).shown?.rows?.takeIf { r -> r.count { it.mediaId.startsWith("up-") } == 2 } }
        assertEquals(listOf("up-movie.webm", "up-photo.jpg"), filled.take(2).map { it.mediaId }.sorted(), "a medium in is read anew on its page")
        val photoReads = JvmFiles.reads.filter { it.startsWith("photo.jpg@") }
        assertTrue(photoReads.isNotEmpty() && photoReads.none { it.endsWith("@whole") }, "a path read whole: $photoReads")
        assertTrue(photoReads.all { it.substringAfter('+').toInt() <= V1.FRAME_PLAINTEXT }, "$photoReads")
        assertEquals(listOf("upload:m/photo.jpg", "upload:m/movie.webm").sorted(), played.sent.filter { it.startsWith("upload:") }.sorted())
        assertContentEquals("P-photo.jpg".encodeToByteArray(), played.derived.getValue("photo.jpg").preview)
        assertContentEquals("T-photo.jpg".encodeToByteArray(), played.derived.getValue("photo.jpg").thumbnail)
        assertEquals(640 to 480, played.derived.getValue("photo.jpg").let { it.width to it.height })
        val asked = synchronized(host.asked) { host.asked.toList() }
        assertTrue(
            HostRequest.MediaTiers(photo.path, "image/jpeg", MediaUpload.PREVIEW_EDGE, MediaUpload.PREVIEW_QUALITY, MediaUpload.THUMBNAIL_EDGE, MediaUpload.THUMBNAIL_QUALITY, 0) in asked,
            "$asked",
        )
        assertTrue(HostRequest.MediaDuration(movie.path) in asked)
        assertEquals(75L, asked.filterIsInstance<HostRequest.MediaTiers>().single { it.path == movie.path }.posterAtMs, "a 150 ms video's poster at its half")
        assertTrue(asked.none { it is HostRequest.MediaTiers && it.path == notes.path }, "a file that is no medium was sent to the host")

        // the live poll while watched: a moved gallery read anew, nothing of its own delivered; no poll outlives its watch
        val before = played.listings.get()
        val live = b.watching(Watched.GalleryLive(g))
        withTimeout(10.seconds) { while (played.listings.get() == before) delay(10) }
        assertNull(live.pending(), "the poll's watch delivered an update of its own")
        b.unwatch(live.id)
        val polls = played.moved.get()
        delay(200.milliseconds)
        assertEquals(polls, played.moved.get())

        // the lightbox: a picture's tier, a video's reader in ranges, closed when paged away, stopped, closed
        val rows = (b.watching(Watched.Gallery(g)).next() as Update.Gallery).shown!!.rows!!
        val clipAt = rows.indexOfFirst { it.mediaId == "clip" }
        val l = (b.ask(Call.LightboxOpen(g, clipAt)) as Answer.LightboxOpened).lightbox
        val box = b.watching(Watched.Lightbox(l))
        val atClip = box.until { u -> (u as Update.Lightbox).shown?.takeIf { it.row?.mediaId == "clip" } }
        assertEquals(Notice(Msg.MEMORY_ITEM_POSITION, clipAt + 1, rows.size), atClip.position)
        assertEquals("clip.webm", atClip.downloadName)
        assertContentEquals("p-a".encodeToByteArray(), b.askWithPayload(Call.LightboxPicture(l, "a")).second, "the lightbox shows the preview")
        val reader = b.ask(Call.MediumReader(l)) as Answer.Reader
        assertEquals(played.original.size.toLong() to "video/webm", reader.size to reader.mediaType)
        val edge = V1.FRAME_PLAINTEXT.toLong()
        val (range, bytes) = b.askWithPayload(Call.ReaderRead(reader.reader, edge - 100, 200))
        assertEquals(Answer.Bytes(null), range)
        assertContentEquals(played.original.copyOfRange((edge - 100).toInt(), (edge + 100).toInt()), bytes, "a range across a frame's edge")
        box.until { u -> (u as Update.Lightbox).shown?.takeIf { it.playing } }
        assertEquals(Answer.Done, b.ask(Call.LightboxGo(l, clipAt - 1)))
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_READER), b.ask(Call.ReaderRead(reader.reader, 0, 4)), "a reader not closed when paged away")
        assertEquals(Answer.Refused(Answer.Why.NO_MEDIUM), b.ask(Call.MediumReader(l)), "a picture opened a reader")
        assertEquals(Answer.Done, b.ask(Call.LightboxGo(l, clipAt)))
        val stopped = (b.ask(Call.MediumReader(l)) as Answer.Reader).reader
        assertEquals(Answer.Done, b.ask(Call.LightboxStop(l)))
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_READER), b.ask(Call.ReaderRead(stopped, 0, 4)), "a reader outlived its player")

        // the details: the listing's facts at once, who added it
        val details = b.ask(Call.LightboxDetails(l, "a")) as Answer.MediumDetails
        assertEquals(Msg.MEMORY_FIELD_CAPTURED to 30_000L, details.rows.first().let { it.label to it.moment })
        assertEquals(me.raddatz.veyq.mobile.words.Piece.Plain("Ada"), details.uploader)
        assertEquals(Answer.Refused(Answer.Why.NO_MEDIUM), b.ask(Call.LightboxDetails(l, "nowhere")))

        // the slideshow's clock is the core's: started, paused
        assertEquals(Answer.Done, b.ask(Call.LightboxSlideshow(l, true)))
        box.until { u -> (u as Update.Lightbox).shown?.takeIf { it.slideshow == SlideshowState.PLAYING } }
        assertEquals(Answer.Done, b.ask(Call.LightboxSlideshow(l, false)))
        box.until { u -> (u as Update.Lightbox).shown?.takeIf { it.slideshow == SlideshowState.PAUSED } }

        // the original saved by the path the dialog gave, watched as a file's save; a blocked one has none to save
        assertEquals(Answer.Done, b.ask(Call.LightboxGo(l, clipAt)))
        val path = File(dir, "saved.webm")
        val save = (b.ask(Call.MediumSave(l, path.path)) as Answer.Saving).save
        val saved = b.watching(Watched.Save(save)).until { u -> (u as Update.Save).shown?.takeIf { it.end != null } }
        assertEquals(FileSave.End.SAVED to "clip.webm", saved.end to saved.name)
        assertContentEquals(played.original, path.readBytes(), "the copy is not the original")
        assertEquals(Answer.Done, b.ask(Call.LightboxGo(l, rows.indexOfFirst { it.mediaId == "bad" })))
        assertEquals(Answer.Refused(Answer.Why.NOTHING_TO_DOWNLOAD), b.ask(Call.MediumSave(l, File(dir, "bad.jpg").path)))
        assertTrue(!File(dir, "bad.jpg").exists())

        // a medium trashed: out of the rows at once, its Undo offered by the answer; undone, back
        val trashed = b.ask(Call.GalleryTrash(g, "a"))
        assertEquals(Answer.Trashed(Notice(Msg.MEMORY_MOVED_TO_TRASH), undoable = true), trashed)
        page.until { u -> (u as Update.Gallery).shown?.takeIf { s -> s.undo != null && s.rows!!.none { it.mediaId == "a" } } }
        assertEquals(Answer.Said(null), b.ask(Call.GalleryUndo(g)))
        page.until { u -> (u as Update.Gallery).shown?.takeIf { s -> s.undo == null && s.rows!!.any { it.mediaId == "a" } } }
        // another from the lightbox; its Undo ends with its window (the core's clock), after which nothing is restored
        assertEquals(Answer.Done, b.ask(Call.LightboxGo(l, rows.indexOfFirst { it.mediaId == "raw" })))
        assertEquals(Answer.Trashed(Notice(Msg.MEMORY_MOVED_TO_TRASH), undoable = true), b.ask(Call.LightboxTrash(l)))
        // (the medium leaves the rows a moment before its Undo is offered: the offer first, then its end)
        page.until { u -> (u as Update.Gallery).shown?.takeIf { s -> s.undo != null && s.rows!!.none { it.mediaId == "raw" } } }
        page.until(timeout = 15.seconds) { u -> (u as Update.Gallery).shown?.takeIf { s -> s.undo == null && s.rows!!.none { it.mediaId == "raw" } } }
        assertEquals(Answer.Said(null), b.ask(Call.GalleryUndo(g)))
        assertEquals(listOf("trash:a", "restore:a", "trash:raw"), played.sent.filter { it.startsWith("trash:") || it.startsWith("restore:") }, "an Undo after its window")

        // a chapter made on a Memory; a chapter's page makes none
        assertEquals(Answer.Created(NodeAt("s", "c-new", "CHAPTER", "Day two"), null), b.ask(Call.GalleryCreateChapter(g, " Day two ")))
        val c = (b.ask(Call.GalleryOpen(chapter)) as Answer.GalleryOpened).gallery
        val inChapter = b.watching(Watched.Gallery(c)).until { u -> (u as Update.Gallery).shown?.takeIf { it.rows != null } }
        assertEquals(emptyList(), inChapter.creates, "a chapter offered outside a Memory")
        assertEquals(emptyList(), inChapter.chapters)
        assertEquals(Answer.Created(null, Notice(Msg.NODE_ILLEGAL_STRUCTURE)), b.ask(Call.GalleryCreateChapter(c, "Inner")))
        assertEquals(listOf("chapter:Day two"), played.sent.filter { it.startsWith("chapter:") })

        // closed: its lightbox with it, the reader too; every page goes with its tenant
        val held = (b.ask(Call.MediumReader(l)).let { it as? Answer.Reader } ?: run {
            b.ask(Call.LightboxGo(l, rows.indexOfFirst { it.mediaId == "clip" }))
            b.ask(Call.MediumReader(l)) as Answer.Reader
        }).reader
        assertEquals(Answer.Done, b.ask(Call.GalleryClose(g)))
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_READER), b.ask(Call.ReaderRead(held, 0, 4)), "a reader outlived its page")
        assertEquals(Update.Lightbox(l, null), b.watching(Watched.Lightbox(l)).next())
        listOf(Call.GalleryTile(g, "a"), Call.MemoryUpload(g, emptyList()), Call.LightboxOpen(g, 0))
            .forEach { assertEquals(Answer.Refused(Answer.Why.NO_SUCH_GALLERY), b.ask(it), "$it") }
        val again = (b.ask(Call.GalleryOpen(memory)) as Answer.GalleryOpened).gallery
        b.watching(Watched.Gallery(again)).until { u -> (u as Update.Gallery).shown?.takeIf { it.rows != null } }
        val box2 = (b.ask(Call.LightboxOpen(again, rows.indexOfFirst { it.mediaId == "clip" })) as Answer.LightboxOpened).lightbox
        val last = (b.ask(Call.MediumReader(box2)) as Answer.Reader).reader
        assertEquals(Answer.Done, b.ask(Call.SessionSignOut("a.example#u1")))
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_READER), b.ask(Call.ReaderRead(last, 0, 4)), "a reader outlived its tenant")
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_GALLERY), b.ask(Call.GalleryTile(again, "a")), "a page outlived its tenant")
        dir.deleteRecursively()
        b.shutdown()
    }

    @Test
    fun theTiersAreAskedOffTheThreadThatDerivesAndRideInBesideTheReply() = runBlocking {
        // the host answers only once derive has returned: an ask on the deriving thread would wait for itself
        val returned = CompletableDeferred<Unit>()
        val asked = java.util.Collections.synchronizedList(ArrayList<HostRequest>())
        lateinit var host: Host
        host = Host(timeout = 5.seconds) { token, json ->
            val request = wire.decodeFromString(HostRequest.serializer(), json)
            asked += request
            val waited = runBlocking { kotlinx.coroutines.withTimeoutOrNull(2.seconds) { returned.await() } }
            val reply = when {
                waited == null -> HostReply.Failed("asked on the deriving thread") to null
                request is HostRequest.MediaDuration -> HostReply.Value("10000") to null
                else -> HostReply.Tiers(2048, 1536, 3) to byteArrayOf(1, 2, 3, 4, 5)
            }
            host.reply(token, wire.encodeToString(HostReply.serializer(), reply.first), reply.second)
        }
        val tiers = HostTiers(host)
        val source = PathSource("/home/p/clip.mp4", UploadSource.of("clip.mp4", 5, "video/mp4", null) { _, n -> ByteArray(n) })
        val done = CompletableDeferred<MediaUpload.Derived>()
        tiers.derive(source, "video/mp4") { done.complete(it) }
        returned.complete(Unit)
        val d = withTimeout(10.seconds) { done.await() }
        assertContentEquals(byteArrayOf(1, 2, 3), d.preview, "the tiers were asked on the deriving thread, or not read from the payload")
        assertContentEquals(byteArrayOf(4, 5), d.thumbnail)
        assertEquals(2048 to 1536, d.width to d.height)
        assertEquals(MediaUpload.POSTER_AT_MS, asked.filterIsInstance<HostRequest.MediaTiers>().single().posterAtMs, "a 10 s video's poster at the core's time")

        // a source that names no path asks nothing; a reply that is no set of tiers goes up alone
        val none = CompletableDeferred<MediaUpload.Derived>()
        tiers.derive(UploadSource.of("x.jpg", 1, "image/jpeg", null) { _, n -> ByteArray(n) }, "image/jpeg") { none.complete(it) }
        assertNull(withTimeout(5.seconds) { none.await() }.preview)
        assertEquals(2, asked.size)
    }
}
