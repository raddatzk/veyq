package me.raddatz.veyq.mobile.bridge

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import me.raddatz.veyq.mobile.audio.MusicPlayer
import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.blob.UploadQueue
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.memories.GalleryModel
import me.raddatz.veyq.mobile.memories.MediaDetails
import me.raddatz.veyq.mobile.memories.MediaUploads
import me.raddatz.veyq.mobile.memories.MemoryMedia
import me.raddatz.veyq.mobile.memories.music.AudioFacts
import me.raddatz.veyq.mobile.memories.music.MusicModel
import me.raddatz.veyq.mobile.memories.music.MusicUpload
import me.raddatz.veyq.mobile.memories.music.TrackLabel
import me.raddatz.veyq.mobile.navigation.Connection
import me.raddatz.veyq.mobile.navigation.Hidden
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.NodeRef
import me.raddatz.veyq.mobile.navigation.RecentUses
import me.raddatz.veyq.mobile.navigation.SpaceRef
import me.raddatz.veyq.mobile.navigation.TenantRef
import me.raddatz.veyq.mobile.session.NodeReport
import me.raddatz.veyq.mobile.session.SessionModel
import me.raddatz.veyq.mobile.session.SessionModel.Gate
import me.raddatz.veyq.mobile.session.StaySignedIn
import me.raddatz.veyq.mobile.session.TrustChange
import me.raddatz.veyq.mobile.session.TrustedDevice
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A node's music through the bridge (ADR 0142 decisions 1–13 as the Linux and Windows apps reach them): the core's player
 * over two decks the host plays through `Audio.*` requests — a track's bytes through a reader held by id, never more —,
 * the system's controls told through `Controls.*`, their commands and the bar's back as `Player.*` calls, the decks' events
 * as `Audio.Ended`/`Audio.Failed` calls (a stale one dropped), an untitled track named by the host's words, the Music view
 * with its add (each picked file's length the host's `Media.Duration`), rename, remove, download and report, a lightbox
 * video interrupting the music — and the player, its readers and its decks gone with the tenant. Every call, watch and
 * request crosses as JSON over a real [Host].
 */
class MusicDispatchTest {
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

    private val memory = NodeAt("s", "m", "MEMORY", "Ostsee 2026")

    /**
     * A Memory the test plays as the server: a photo, a video, a titled track with its facts, an untitled one and a FLAC this
     * host's decks do not open; each original's bytes in frames of the suite's size; every reader it hands out kept, so a
     * test sees which were closed.
     */
    private class PlayedMemory {
        val original = "ID3".encodeToByteArray() + ByteArray(2 * V1.FRAME_PLAINTEXT + 77) { ((it * 7) % 251).toByte() }
        private val frames = original.toList().chunked(V1.FRAME_PLAINTEXT).map { it.toByteArray() }
        val items = CopyOnWriteArrayList<MemoryMedia.Item>()
        val readers = CopyOnWriteArrayList<RangeReader>()
        val sent = CopyOnWriteArrayList<String>()

        fun item(id: String, type: String, name: String?, facts: AudioFacts? = null) = MemoryMedia.Item(
            "s", "m", id, MemoryMedia.Blob("o-$id", frames.size, original.size.toLong() + frames.size * V1.FRAME_OVERHEAD, "", null), null, null,
            MemoryMedia.Meta(null, null, null, null, null, null, null, null, null, null, facts), type, name, "user:u1", false, ByteArray(0),
        )

        init {
            items += item("p", "image/jpeg", "p.jpg")
            items += item("v", "video/mp4", "v.mp4")
            items += item("a", "audio/mpeg", "Alfama.mp3", AudioFacts(300_000, 0, 300_000))
            items += item("u", "audio/mpeg", null)
        }

        fun door(node: NodeAt, uploads: MediaUploads) = object : GalleryModel.Door {
            override suspend fun gallery() = MemoryMedia.Gallery(MemoryMedia.order(items.toList()), 1, 0)
            override suspend fun scopeNodes() = emptyList<NodeRef>()
            override suspend fun read(item: MemoryMedia.Item, blob: MemoryMedia.Blob) = blob.blobId.encodeToByteArray()
            override fun reader(item: MemoryMedia.Item) =
                RangeReader(original.size.toLong(), frames.size) { first, until, sink -> (first until until).forEach { sink(frames[it]) } }.also { readers += it }
            override suspend fun stream(item: MemoryMedia.Item, sink: suspend (ByteArray) -> Unit): Boolean {
                frames.forEach { sink(it) }
                return true
            }
            override suspend fun exif(item: MemoryMedia.Item): MediaDetails.Exif? = null
            override suspend fun uploaders(items: List<MemoryMedia.Item>) = emptyMap<String, MediaDetails.Uploader>()
            override fun upload(sources: List<UploadSource>) = uploads.upload(node.scopeId, node.nodeId, node.name, sources)
            override val revisions = MutableStateFlow<Any?>(Any())
            override val uploaded = MutableSharedFlow<String>()
            override suspend fun trash(mediaId: String): GalleryModel.Removal {
                sent += "trash:$mediaId"
                items.removeIf { it.mediaId == mediaId }
                return GalleryModel.Removal.DONE
            }
            override suspend fun restore(mediaId: String) = GalleryModel.Removal.DONE
        }

        /** Memories' uploads, a music track's job recording what it would upload. */
        fun uploads(queue: UploadQueue) = MediaUploads(queue, object : MediaUploads.Door {
            override fun job(scopeId: String, nodeId: String, source: UploadSource): UploadQueue.Job = error("no photos here")
            override fun musicJob(scopeId: String, nodeId: String, track: MusicUpload.Check.Taken) = object : UploadQueue.Job {
                override val name get() = track.source.name
                override val sizeBytes get() = track.source.size
                override suspend fun run(progress: (Double) -> Unit) {
                    sent += "upload:${track.source.name}:${track.type}:${track.facts.durationMs}"
                    sent += "trim:${track.source.name}:${track.facts.trimStartMs}-${track.facts.trimEndMs}"
                }
            }
            override fun changed() {}
            override fun left(source: UploadSource) {}
        })

        /** The Music view's door as the shell's, its length measured by the host's measure. */
        fun musicDoor(measure: MusicUpload.Measure, uploads: MediaUploads, node: NodeAt) = object : MusicModel.Door {
            override suspend fun check(source: UploadSource) = MusicUpload.check(
                source,
                endsOf = { type, durationMs, limitMs -> suspendCancellableCoroutine { c -> measure.ends(source, type, durationMs, limitMs) { e -> c.resume(e) } } },
            ) { type ->
                suspendCancellableCoroutine { c -> measure.durationMs(source, type) { ms -> c.resume(ms) } }
            }
            override fun queue(tracks: List<MusicUpload.Check.Taken>) = uploads.uploadMusic(node.scopeId, node.nodeId, node.name, tracks)
            override fun left(source: UploadSource) {}
            override suspend fun rename(item: MemoryMedia.Item, name: String?): TrackLabel.Outcome {
                sent += "rename:${item.mediaId}:$name"
                return TrackLabel.Outcome.Done(name ?: "")
            }
            override suspend fun report(item: MemoryMedia.Item, category: String, reason: String, contact: String): NodeReport.Sent {
                sent += "report:${item.mediaId}"
                return NodeReport.Sent(NodeReport.Outcome.SENT, "abcd1234", "2026-11-16T10:00:00Z")
            }
            override suspend fun reportOffered() = true
            override val origin = "https://a.example"
        }
    }

    /**
     * The host the tests play over a real [Host]: each request recorded as JSON reads it back; a deck loads a track of 5 min (no next track is opened ahead)
     * (a FLAC it cannot open), stands at 0 ms, plays; the words of an untitled track are "Track <n>"; a picked `.mp3` lasts
     * 7 s and nothing else is read. What the core tells, it answers `Done`, as a host does — no one waits for it.
     */
    private class PlayedHost {
        val asked: MutableList<HostRequest> = java.util.Collections.synchronizedList(ArrayList())
        lateinit var host: Host

        init {
            host = Host(timeout = 5.seconds) { token, json ->
                val request = wire.decodeFromString(HostRequest.serializer(), json)
                asked += request
                val reply = when (request) {
                    is HostRequest.AudioLoad -> if (request.mediaType == "audio/flac") HostReply.Failed("no FLAC here") else HostReply.Value("300000")
                    is HostRequest.AudioPosition -> HostReply.Value("0")
                    is HostRequest.WordsSay -> HostReply.Value("Track ${request.words.args.single()}")
                    is HostRequest.MediaDuration -> if (request.path.endsWith(".mp3")) HostReply.Value("7000") else HostReply.Failed("not read")
                    // a picked `.mp3` decodes to 1 s of silence, 5 s of tone, 1 s of silence (#932): each slice asked, a WAV
                    is HostRequest.MediaSlice -> if (request.path.endsWith(".mp3")) HostReply.Wav else HostReply.Failed("not decoded")
                    else -> HostReply.Done
                }
                val payload = (request as? HostRequest.MediaSlice)?.takeIf { reply == HostReply.Wav }?.let { slice(it.fromMs, it.toMs) }
                host.reply(token, wire.encodeToString(HostReply.serializer(), reply), payload)
            }
        }

        /** The played track's audio from [fromMs] to [toMs] (null: its end) as 8 kHz mono 16-bit WAV: 1 s silent, 5 s loud, 1 s silent. */
        fun slice(fromMs: Long, toMs: Long?): ByteArray {
            val rate = 8
            val samples = (fromMs * rate until (toMs ?: 7000L) * rate).map { i -> if (i in 1000 * rate until 6000 * rate) 16000 else 0 }
            val pcm = ByteArray(samples.size * 2)
            samples.forEachIndexed { i, v -> pcm[2 * i] = v.toByte(); pcm[2 * i + 1] = (v shr 8).toByte() }
            return me.raddatz.veyq.mobile.memories.music.PcmWav.of(pcm, 1, rate * 1000, 16)
        }

        inline fun <reified R : HostRequest> all(): List<R> = synchronized(asked) { asked.filterIsInstance<R>() }

        /** Waits until [count] requests of type [R] were made, and returns them. */
        suspend inline fun <reified R : HostRequest> until(count: Int = 1, timeout: Duration = 10.seconds): List<R> = withTimeout(timeout) {
            while (all<R>().size < count) delay(10)
            all<R>()
        }
    }

    private fun bridge(played: PlayedMemory, host: PlayedHost) = Bridge {
        val hidden = Hidden({}, emptySet())
        Composition(SessionModel(Door(), hidden, RecentUses({}, emptyList())), hidden, JvmFiles, audio = HostAudio(host.host)) { _ ->
            Tenant(
                shell = null, pot = null, me = "u1", pictures = MutableStateFlow(emptyMap()), loadPictures = {},
                mediaUploads = { queue, _, _ -> played.uploads(queue) },
                gallery = { node, uploads, music -> GalleryModel(node, played.door(node, uploads), music = music) },
                // the order's randomness pinned: each round plays the listing's order
                musicPlayer = { newDeck, controls -> MusicPlayer(newDeck, controls, random = { 0 }) },
                musicModel = { gallery, uploads, measure -> MusicModel(gallery, played.musicDoor(measure, uploads, gallery.node)) },
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

    private suspend fun Bridge<*, *>.gallery(): Long {
        val g = (ask(Call.GalleryOpen(memory)) as Answer.GalleryOpened).gallery
        watching(Watched.Gallery(g)).until { u -> (u as Update.Gallery).shown?.takeIf { it.rows != null } }
        return g
    }

    @Test
    fun theMusicPlaysOnTheHostsDecksAndItsEventsAndCommandsComeBackAsCalls() = runBlocking {
        val played = PlayedMemory()
        val host = PlayedHost()
        val b = bridge(played, host)
        assertEquals(Answer.Refused(Answer.Why.NO_PLAYER), b.ask(Call.PlayerPause), "a player before any tenant")
        b.signedIn()
        val g = b.gallery()
        val page = b.watching(Watched.Gallery(g)).until { u -> (u as Update.Gallery).shown?.takeIf { it.rows != null } }
        assertTrue(page.musicView, "a page with music offers its Music view")
        assertFalse(page.musicHere)
        assertEquals(listOf("p", "v"), page.rows!!.map { it.mediaId }, "a track is no row of the grid")
        val bar = b.watching(Watched.Player)
        assertNull((bar.next() as Update.Player).shown!!.nowPlaying, "the bar hides while nothing plays")

        // the header's play: the untitled track named by the host's words, the first track loaded on a deck by a reader's id
        assertEquals(Answer.Done, b.ask(Call.GalleryMusic(g)))
        assertEquals(Notice(Msg.MUSIC_UNTITLED, 2), host.until<HostRequest.WordsSay>().single().words)
        val load = host.until<HostRequest.AudioLoad>().single()
        assertEquals("audio/mpeg" to played.original.size.toLong(), load.mediaType to load.size)
        val (range, bytes) = b.askWithPayload(Call.ReaderRead(load.reader, 1, 5))
        assertEquals(Answer.Bytes(null), range)
        assertContentEquals(played.original.copyOfRange(1, 6), bytes, "the deck reads the track's bytes through its reader")
        host.until<HostRequest.AudioPlay>()
        host.until<HostRequest.ControlsBind>()
        // the order is the core's; each track its title as the view names it
        val titles = mapOf("a" to "Alfama", "u" to "Track 2")
        val showing = host.until<HostRequest.ControlsShow>().last().now
        val first = showing.trackId
        val second = titles.keys.single { it != first }
        assertEquals(titles.getValue(first) to "Ostsee 2026", showing.title to showing.album, "the system's controls show what plays")
        val now = bar.until { u -> (u as Update.Player).shown?.nowPlaying?.takeIf { it.playing } }
        assertEquals(first to 300_000L, now.trackId to now.durationMs)
        assertEquals(GalleryModel.page(memory), now.route, "the bar's node name opens the page that plays")
        assertTrue(b.watching(Watched.Gallery(g)).until { u -> (u as Update.Gallery).shown?.takeIf { it.musicHere } }.musicHere)

        // the bar's (and the system's) commands are calls: pause told, resume asked again
        assertEquals(Answer.Done, b.ask(Call.PlayerPause))
        assertEquals(load.deck, host.until<HostRequest.AudioPause>().single().deck)
        bar.until { u -> (u as Update.Player).shown?.nowPlaying?.takeIf { !it.playing } }
        assertEquals(Answer.Done, b.ask(Call.PlayerResume))
        host.until<HostRequest.AudioPlay>(2)
        bar.until { u -> (u as Update.Player).shown?.nowPlaying?.takeIf { it.playing } }
        assertEquals(Answer.Done, b.ask(Call.PlayerSeek(1200)))
        assertEquals(1200L, host.until<HostRequest.AudioSeek>().last { it.deck == load.deck }.positionMs)

        // the deck's end, reported as a call, brings the next track in on the other deck; a stale end is dropped
        assertEquals(Answer.Done, b.ask(Call.AudioEnded(load.deck, load.load)))
        val next = host.until<HostRequest.AudioLoad>(2)[1]
        assertTrue(next.deck != load.deck, "the next track comes in on the other deck")
        bar.until { u -> (u as Update.Player).shown?.nowPlaying?.takeIf { it.trackId == second && it.title == titles.getValue(second) } }
        assertEquals(Answer.Done, b.ask(Call.AudioEnded(load.deck, load.load)))
        delay(400)
        assertEquals(2, host.all<HostRequest.AudioLoad>().size, "an end of a load the deck no longer holds was heard")
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_DECK), b.ask(Call.AudioEnded(9_999, 1)))

        // a lightbox video interrupts the music; its end resumes it
        val box = (b.ask(Call.LightboxOpen(g, 1)) as Answer.LightboxOpened).lightbox
        val lightbox = b.watching(Watched.Lightbox(box))
        assertEquals(second, lightbox.until { u -> (u as Update.Lightbox).shown?.music }.trackId, "the compact controls show what plays")
        val pauses = host.all<HostRequest.AudioPause>().size
        assertEquals(Answer.Done, b.ask(Call.LightboxVideo(box, true)))
        host.until<HostRequest.AudioPause>(pauses + 1)
        bar.until { u -> (u as Update.Player).shown?.nowPlaying?.takeIf { !it.playing } }
        assertEquals(Answer.Done, b.ask(Call.LightboxVideo(box, false)))
        bar.until { u -> (u as Update.Player).shown?.nowPlaying?.takeIf { it.playing } }

        // the bar's close: the decks cleared, the system's controls cleared, the readers refused
        assertEquals(Answer.Done, b.ask(Call.PlayerStop))
        host.until<HostRequest.ControlsClear>()
        bar.until { u -> (u as Update.Player).shown?.takeIf { it.nowPlaying == null } }
        withTimeout(5.seconds) { while (played.readers.any { !it.isClosed }) delay(10) }
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_READER), b.ask(Call.ReaderRead(next.reader, 0, 4)), "a deck's reader outlived its clear")
        b.shutdown()
    }

    @Test
    fun aQueueNoneOfWhoseTracksTheHostOpensEndsAndSaysSo() = runBlocking {
        val played = PlayedMemory()
        played.items.removeIf { it.mediaId == "a" || it.mediaId == "u" }
        played.items += played.item("f", "audio/flac", "Fado.flac")
        val host = PlayedHost()
        val b = bridge(played, host)
        b.signedIn()
        val g = b.gallery()
        val bar = b.watching(Watched.Player)
        assertEquals(Answer.Done, b.ask(Call.GalleryMusic(g)))
        val ended = bar.until { u -> (u as Update.Player).shown?.takeIf { it.notice != null } }
        assertEquals(Notice(Msg.MUSIC_NOTHING_PLAYS), ended.notice)
        assertNull(ended.nowPlaying)
        withTimeout(5.seconds) { while (played.readers.any { !it.isClosed }) delay(10) }
        val load = host.all<HostRequest.AudioLoad>().first()
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_READER), b.ask(Call.ReaderRead(load.reader, 0, 4)), "a reader the host did not open is still held")
        b.shutdown()
    }

    @Test
    fun theMusicViewListsAddsRenamesRemovesSavesReportsAndPlaysThroughCalls() = runBlocking {
        val played = PlayedMemory()
        val host = PlayedHost()
        val b = bridge(played, host)
        b.signedIn()
        val g = b.gallery()
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_GALLERY), b.ask(Call.MusicOpen(9_999)))
        val m = (b.ask(Call.MusicOpen(g)) as Answer.MusicOpened).music
        val view = b.watching(Watched.Music(m))
        val shown = view.until { u -> (u as Update.Music).shown?.takeIf { it.tracks != null && it.canAdd } }
        assertEquals(memory, shown.node)
        assertEquals(listOf("a", "u"), shown.tracks!!.map { it.mediaId })
        val (alfama, untitled) = shown.tracks!!
        assertEquals(listOf("Alfama", null), listOf(alfama.title, untitled.title))
        assertEquals(listOf(null, Notice(Msg.MUSIC_UNTITLED, 2)), listOf(alfama.untitled, untitled.untitled), "an untitled track is said by its number")
        assertEquals(listOf("Alfama.mp3", null), listOf(alfama.fileName, untitled.fileName))
        assertEquals("5:00", alfama.length)
        assertTrue(shown.canRename && shown.canDownload && shown.canReport && alfama.canRemove)

        // a track pressed plays first, and is marked
        assertEquals(Answer.Done, b.ask(Call.MusicPlay(m, "u")))
        assertEquals("u", view.until { u -> (u as Update.Music).shown?.tracks?.singleOrNull { it.playing } }.mediaId)

        // picked files: each checked on the device, its length the host's; the one that is no audio said, the rest queued
        val dir = kotlin.io.path.createTempDirectory("music-add").toFile()
        val song = File(dir, "Song.mp3").apply { writeBytes("ID3".encodeToByteArray() + ByteArray(5000) { 1 }) }
        val text = File(dir, "notes.mp3").apply { writeText("not a song at all") }
        val added = b.ask(Call.MusicAdd(m, listOf(song.path, text.path, File(dir, "missing.mp3").path))) as Answer.MusicAdded
        assertEquals(listOf(Notice(Msg.MUSIC_NOT_AUDIO, "notes.mp3")), added.said)
        assertEquals(listOf(song.path), host.all<HostRequest.MediaDuration>().map { it.path }, "the host measured what the core asked")
        withTimeout(10.seconds) { while ("upload:Song.mp3:audio/mpeg:7000" !in played.sent) delay(10) }
        // #932: its silent edges found from the two slices the host decoded, never the whole file
        assertTrue("trim:Song.mp3:1000-6000" in played.sent, "the decoded ends were not scanned: ${played.sent}")
        assertEquals(listOf(0L to 7000L, 0L to null), host.all<HostRequest.MediaSlice>().map { it.fromMs to it.toMs }, "the slices asked")

        // rename, report, download, remove
        assertEquals(Answer.Said(null), b.ask(Call.MusicRename(m, "a", "Lisboa")))
        assertTrue("rename:a:Lisboa" in played.sent)
        val report = (b.ask(Call.MusicReport(m, "a")) as Answer.ReportOpened).report
        assertEquals(Answer.Done, b.ask(Call.ReportClose(report)))
        val target = File(dir, "Alfama.mp3")
        val save = (b.ask(Call.MusicDownload(m, "a", target.path)) as Answer.Saving).save
        b.watching(Watched.Save(save)).until { u -> (u as Update.Save).shown?.takeIf { it.end != null } }
        assertContentEquals(played.original, target.readBytes(), "the track's file saved whole where the host's dialog chose")
        assertTrue(b.ask(Call.MusicRemove(m, "a")) is Answer.Trashed)
        assertTrue("trash:a" in played.sent)
        assertEquals(listOf("u"), view.until { u -> (u as Update.Music).shown?.tracks?.takeIf { t -> t.none { it.mediaId == "a" } } }.map { it.mediaId })

        // the view closes with its gallery
        assertEquals(Answer.Done, b.ask(Call.GalleryClose(g)))
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_MUSIC), b.ask(Call.MusicPlay(m, "u")), "a Music view outlived its page")
        dir.deleteRecursively()
        b.shutdown()
    }

    /**
     * The player is the tenant's (ADR 0142 decision 1): at a sign-out it stops — every reader it opened closed, its decks
     * cleared and gone, the system's controls cleared — and the next tenant's calls and watch find none of it.
     */
    @Test
    fun thePlayerItsReadersAndItsDecksGoWithItsTenant() = runBlocking {
        val played = PlayedMemory()
        val host = PlayedHost()
        val b = bridge(played, host)
        b.signedIn()
        val g = b.gallery()
        val m = (b.ask(Call.MusicOpen(g)) as Answer.MusicOpened).music
        assertEquals(Answer.Done, b.ask(Call.GalleryMusic(g)))
        val load = host.until<HostRequest.AudioLoad>().single()
        host.until<HostRequest.AudioPlay>()
        assertEquals(Answer.Bytes(null), b.askWithPayload(Call.ReaderRead(load.reader, 0, 4)).first)
        val bar = b.watching(Watched.Player)
        bar.until { u -> (u as Update.Player).shown?.nowPlaying }

        assertEquals(Answer.Done, b.ask(Call.SessionSignOut("a.example#u1")))
        // waited for, then asserted: a reader still open after the wait is red by its own words
        kotlinx.coroutines.withTimeoutOrNull(5.seconds) { while (played.readers.any { !it.isClosed }) delay(10) }
        assertTrue(played.readers.isNotEmpty() && played.readers.all { it.isClosed }, "a track's reader outlived the sign-out")
        host.until<HostRequest.ControlsClear>()
        assertTrue(host.all<HostRequest.AudioClear>().any { it.deck == load.deck }, "a deck outlived the sign-out")
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_READER), b.ask(Call.ReaderRead(load.reader, 0, 4)), "a reader outlived its tenant")
        assertEquals(Update.Player(null), bar.until { u -> (u as Update.Player).takeIf { it.shown == null } })
        assertEquals(Answer.Refused(Answer.Why.NO_PLAYER), b.ask(Call.PlayerResume), "a player outlived its tenant")
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_DECK), b.ask(Call.AudioEnded(load.deck, load.load)), "a deck outlived its tenant")
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_MUSIC), b.ask(Call.MusicSeen(m)), "a Music view outlived its tenant")
        b.shutdown()
    }
}
