package me.raddatz.veyq.mobile.bridge

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.memories.CaptureTime
import me.raddatz.veyq.mobile.memories.GalleryModel
import me.raddatz.veyq.mobile.memories.MediaDetails
import me.raddatz.veyq.mobile.memories.MemoryCreate
import me.raddatz.veyq.mobile.memories.MemoryMedia
import me.raddatz.veyq.mobile.memories.banner.Banner
import me.raddatz.veyq.mobile.memories.banner.BannerWrite
import me.raddatz.veyq.mobile.memories.people.ParticipantIndex
import me.raddatz.veyq.mobile.memories.people.PersonMemories
import me.raddatz.veyq.mobile.memories.place.MemoryDate
import me.raddatz.veyq.mobile.memories.place.MemoryFacts
import me.raddatz.veyq.mobile.navigation.ApplicationId
import me.raddatz.veyq.mobile.navigation.Connection
import me.raddatz.veyq.mobile.navigation.Hidden
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.NodeRef
import me.raddatz.veyq.mobile.navigation.ObjectRef
import me.raddatz.veyq.mobile.navigation.PersonProfile
import me.raddatz.veyq.mobile.navigation.RecentUses
import me.raddatz.veyq.mobile.navigation.SpaceRef
import me.raddatz.veyq.mobile.navigation.TenantRef
import me.raddatz.veyq.mobile.session.Person
import me.raddatz.veyq.mobile.session.SessionModel
import me.raddatz.veyq.mobile.session.SessionModel.Gate
import me.raddatz.veyq.mobile.session.StaySignedIn
import me.raddatz.veyq.mobile.session.TrustChange
import me.raddatz.veyq.mobile.session.TrustedDevice
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Curating a Memory through the bridge (ADR 0144 decision 8): the page's people line and banner, the participants picker,
 * a medium's capture-time editor, the banner's picture, frame, player and dialog (a picked file decoded by a host the test
 * plays through real [Host] requests, [HostBannerPictures]), A2's question before a trash, *I was there* at a Memory's
 * birth, and a person's page — every call and watch as JSON, over core's own `GalleryModel`, `ParticipantsPicker`,
 * `CaptureTimeEditor`, `BannerEditor`, `PersonProfile` and `PersonMemories` on a Memory the test plays as the server. The
 * dispatch is one line per call: what is held here is that each reaches its model, answers what the host acts on at once,
 * and that no dialog or page outlives its page or its tenant.
 */
class CurationDispatchTest {
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
     * A Memory the test plays as the server: listed are Anna (u1, a member) and "gone" (no member any more); its banner is
     * a video whose file is photo "a"'s original (the same digest); "b" was added by a former member. What each write
     * reached is recorded; a banner save waits for its cancel while [holdSave].
     */
    private class PlayedMemory {
        val members = listOf(Person("u1", "anna", "Anna"), Person("u2", "ben", "Ben"), Person("u3", "cleo", "Cléo"))
        @Volatile var participants = listOf("u1", "gone")
        @Volatile var holdSave = false
        val sent = CopyOnWriteArrayList<String>()
        val video = ByteArray(2 * V1.FRAME_PLAINTEXT + 77) { (it % 241).toByte() }
        private val frames = video.toList().chunked(V1.FRAME_PLAINTEXT).map { it.toByteArray() }
        val trashed = CopyOnWriteArrayList<String>()

        private fun blob(id: String) = MemoryMedia.Blob(id, 1, 29L + V1.FRAME_OVERHEAD, "", null)

        private fun item(id: String, at: Long, owner: String, sha256: String?) = MemoryMedia.Item(
            "s", "m", id, blob("o-$id"), blob("t-$id"), blob("p-$id"),
            MemoryMedia.Meta(4000, 3000, at, null, "exif", null, null, null, null), "image/jpeg", "$id.jpeg", owner, false, ByteArray(0),
            sha256 = sha256,
        )

        val items = listOf(item("a", 30_000, "user:u2", DIGEST), item("b", 20_000, "user:gone", null))

        fun meta() = JsonObject(
            mapOf(
                "v" to JsonPrimitive(1),
                "participants" to JsonObject(mapOf("userIds" to JsonArray(participants.map(::JsonPrimitive)))),
                Banner.FIELD to Banner.sealed(
                    Banner.Medium.VIDEO, Banner.Ref("b-v", frames.size, video.size.toLong() + frames.size * V1.FRAME_OVERHEAD, "d3JhcA=="),
                    Banner.Ref("b-p", 1, 60, "d3JhcA=="), "video/webm", DIGEST,
                ),
            ),
        )

        fun door() = object : GalleryModel.Door {
            override suspend fun gallery() = MemoryMedia.Gallery(MemoryMedia.order(items.filter { it.mediaId !in trashed }), 1, 0)
            override suspend fun scopeNodes() = emptyList<NodeRef>()
            override suspend fun read(item: MemoryMedia.Item, blob: MemoryMedia.Blob) = blob.blobId.encodeToByteArray()
            override fun reader(item: MemoryMedia.Item): RangeReader? = null
            override suspend fun stream(item: MemoryMedia.Item, sink: suspend (ByteArray) -> Unit) = false
            override suspend fun exif(item: MemoryMedia.Item): MediaDetails.Exif? = null
            override suspend fun uploaders(items: List<MemoryMedia.Item>) = mapOf(
                "user:u2" to MediaDetails.Uploader(MediaDetails.UploaderKind.MEMBER, "Ben", "u2"),
                "user:gone" to MediaDetails.Uploader(MediaDetails.UploaderKind.FORMER, null, "gone"),
            )
            override fun upload(sources: List<UploadSource>) = 0
            override val revisions = MutableStateFlow<Any?>(Any())
            override val uploaded = emptyFlow<String>()
            override suspend fun trash(mediaId: String): GalleryModel.Removal {
                sent += "trash:$mediaId"
                trashed += mediaId
                return GalleryModel.Removal.DONE
            }
            override suspend fun facts() = MemoryFacts.Facts.of(meta())
            override suspend fun members() = members
            override suspend fun setParticipants(wanted: List<String>, openedWith: List<String>): MemoryFacts.Outcome {
                sent += "participants:${wanted.joinToString(",")}"
                participants = me.raddatz.veyq.mobile.memories.Participants.merge(participants, wanted, openedWith)
                return MemoryFacts.Outcome.Written
            }
            override suspend fun captureTime(item: MemoryMedia.Item, overrideMs: Long?): CaptureTime.Outcome {
                sent += "time:${item.mediaId}=$overrideMs"
                return if (overrideMs == PEERS_TURN) CaptureTime.Outcome.Conflict(PEERS_TIME) else CaptureTime.Outcome.Done(overrideMs)
            }
            override suspend fun bannerPicture() = "poster".encodeToByteArray()
            override suspend fun bannerReader() =
                RangeReader(video.size.toLong(), frames.size) { first, until, sink -> (first until until).forEach { sink(frames[it]) } }
            override suspend fun setBanner(
                openedWith: Banner.Slots, picked: BannerWrite.Picked, items: List<MemoryMedia.Item>, progress: (Double) -> Unit,
            ): BannerWrite.Outcome {
                sent += "banner:${picked.medium}:${picked.picture.decodeToString()}"
                progress(0.5)
                if (holdSave) {
                    try {
                        awaitCancellation()
                    } finally {
                        sent += "banner:cancelled"
                    }
                }
                return BannerWrite.Outcome.Done
            }
            override suspend fun removeBanner(openedWith: Banner.Slots): BannerWrite.Outcome {
                sent += "banner:remove:${openedWith.blobId}"
                return BannerWrite.Outcome.Done
            }
        }

        /** Ben (u2) is on the group's Harz (dated) and the tenant's Ostsee (undated); Anna on Ostsee only. */
        val index = run {
            val harz = NodeRef("g", "harz", null, "MEMORY", "Harz")
            val ostsee = NodeRef("s", "m", null, "MEMORY", "Ostsee 2026")
            ParticipantIndex.Index(
                mapOf("u2" to setOf(ostsee, harz), "u1" to setOf(ostsee)),
                mapOf(
                    harz to ParticipantIndex.Memory(harz, SpaceRef("g", "GROUP", "g"), MemoryDate(MemoryDate.Mode.SINGLE, "2026-08-01", null), null, null, listOf("u2")),
                    ostsee to ParticipantIndex.Memory(ostsee, SpaceRef("s", "TENANT", "s"), null, null, null, listOf("u1", "u2")),
                ),
                partial = true,
            )
        }
    }

    /** The host the tests play over a real [Host]: a picked file's tiers are `P-<name>` and `T-<name>`; one named "raw…" does not decode. */
    private class PlayedHost {
        val asked: MutableList<HostRequest> = java.util.Collections.synchronizedList(ArrayList())
        lateinit var host: Host

        init {
            host = Host { token, json ->
                val request = wire.decodeFromString(HostRequest.serializer(), json)
                asked += request
                val (reply, payload) = when (request) {
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
        Composition(SessionModel(Door(), hidden, RecentUses({}, emptyList())), hidden, JvmFiles, bannerPictures = HostBannerPictures(host.host)) { _ ->
            Tenant(
                shell = null, pot = null, me = "u1", pictures = MutableStateFlow(mapOf("u2" to byteArrayOf(1))), loadPictures = {},
                mediaUploads = { queue, _, _ -> me.raddatz.veyq.mobile.memories.MediaUploads(queue, NoUploads) },
                gallery = { node, _, _ -> GalleryModel(node, played.door()) },
                createMemory = { scopeId, _, name, wasThere ->
                    played.sent += "create:$name:$wasThere"
                    MemoryCreate.Made(NodeAt(scopeId, "m-new", MemoryCreate.MEMORY, name), null)
                },
                offersWasThere = { scopeId -> scopeId == "s" },
                profile = { userId -> PersonProfile.of(userId, played.members.associateBy { it.userId }, emptySet()) },
                personMemories = { userId ->
                    PersonMemories(userId, object : PersonMemories.Door {
                        override suspend fun index() = played.index
                        override fun open(ref: ObjectRef) {}
                    })
                },
            )
        }
    }

    /** Memories' uploads the tests never run. */
    private object NoUploads : me.raddatz.veyq.mobile.memories.MediaUploads.Door {
        override fun job(scopeId: String, nodeId: String, source: UploadSource) = error("no uploads here")
        override fun musicJob(scopeId: String, nodeId: String, track: me.raddatz.veyq.mobile.memories.music.MusicUpload.Check.Taken) = error("no uploads here")
        override fun changed() {}
        override fun left(source: UploadSource) {}
    }

    private suspend fun Bridge<*, *>.signedIn() {
        val gate = watching(Watched.Session)
        assertEquals(Answer.Done, ask(Call.SessionStart))
        assertEquals(Answer.Done, ask(Call.SessionSignIn("a.example", "p@a.example", "right", "A3-SECRET")))
        gate.until { (it as Update.Session).gate as? Gate.Signed }
        unwatch(gate.id)
    }

    /** Memory "m"'s page opened and read, with its people line named. */
    private suspend fun Bridge<*, *>.page(): Pair<Long, GalleryShown> {
        val g = (ask(Call.GalleryOpen(memory)) as Answer.GalleryOpened).gallery
        val shown = watching(Watched.Gallery(g)).until { u -> (u as Update.Gallery).shown?.takeIf { it.rows != null && it.people?.named == true && it.banner != null } }
        return g to shown
    }

    @Test
    fun theParticipantsAreShownPickedAndSavedThroughCalls() = runBlocking {
        val played = PlayedMemory()
        val b = bridge(played, PlayedHost())
        assertEquals(Answer.Refused(Answer.Why.NO_TENANT_OPEN), b.ask(Call.ShellOffersWasThere("s")))
        b.signedIn()

        // New Memory: *I was there* offered where the core says, and carried to the core with the name
        assertEquals(Answer.WasThere(true), b.ask(Call.ShellOffersWasThere("s")))
        assertEquals(Answer.WasThere(false), b.ask(Call.ShellOffersWasThere("personal")))
        assertEquals(Answer.Created(NodeAt("s", "m-new", "MEMORY", "Harz"), null), b.ask(Call.ShellCreateMemory("s", null, "Harz", wasThere = true)))
        b.ask(Call.ShellCreateMemory("s", null, "Amrum"))
        assertEquals(listOf("create:Harz:true", "create:Amrum:false"), played.sent.filter { it.startsWith("create:") }, "I was there did not reach the core")

        // the people line: Anna named, opening her profile; "gone" a former member, opening nothing
        val (g, page) = b.page()
        val people = page.people!!
        assertEquals(listOf("u1" to "Anna", "gone" to null), people.people.map { it.userId to it.name })
        assertEquals(listOf(true to false, false to true), people.people.map { it.opensProfile to it.former })
        assertEquals(listOf("u1", "gone") to 0, people.avatars to people.more)
        assertTrue(people.edits && !people.add)

        // the picker: the scope's members read, the former member above them ticked; a tick refused for an id not listed
        val p = (b.ask(Call.ParticipantsOpen(g)) as Answer.ParticipantsOpened).participants
        val picker = b.watching(Watched.Participants(p))
        val read = picker.until { u -> (u as Update.Participants).shown?.takeIf { it.members != null } }
        assertEquals(listOf("u1", "u2", "u3"), read.members!!.map { it.userId })
        assertEquals(listOf(true, false, false), read.members.map { it.ticked })
        assertEquals(listOf(MemberShownOf("gone", null, true)), read.former.map { MemberShownOf(it.userId, it.name, it.ticked) })
        assertEquals(Answer.Done, b.ask(Call.ParticipantsToggle(p, "u3")))
        assertEquals(Answer.Refused(Answer.Why.NOT_TAKEN), b.ask(Call.ParticipantsToggle(p, "stranger")), "an id outside the scope's members was ticked")
        assertEquals(Answer.Done, b.ask(Call.ParticipantsToggle(p, "gone")))
        assertEquals(Answer.Done, b.ask(Call.ParticipantsSearch(p, "cle")))
        val searched = picker.until { u -> (u as Update.Participants).shown?.takeIf { it.query == "cle" } }
        assertEquals(listOf("u3"), searched.members!!.map { it.userId }, "the search folds accents")
        assertEquals(Answer.FactsSaved(FactsSave.SAVED), b.ask(Call.ParticipantsSave(p)))
        assertEquals(listOf("participants:u1,u3"), played.sent.filter { it.startsWith("participants:") })
        picker.until { u -> (u as Update.Participants).shown?.takeIf { it.saved } }
        // the header follows the write
        b.watching(Watched.Gallery(g)).until { u -> (u as Update.Gallery).shown?.people?.takeIf { l -> l.people.map { it.userId } == listOf("u1", "u3") } }

        // a picker closes with its page
        assertEquals(Answer.Done, b.ask(Call.GalleryClose(g)))
        assertEquals(Update.Participants(p, null).encoded(), b.watching(Watched.Participants(p)).next().encoded())
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_PARTICIPANTS), b.ask(Call.ParticipantsSave(p)), "a picker outlived its page")
        b.shutdown()
    }

    @Test
    fun aCaptureTimeIsCorrectedResetAndConflictedThroughItsEditorAndTheUploaderOpensAProfile() = runBlocking {
        val played = PlayedMemory()
        val b = bridge(played, PlayedHost())
        b.signedIn()
        val (g, page) = b.page()
        val l = (b.ask(Call.LightboxOpen(g, page.rows!!.indexOfFirst { it.mediaId == "a" })) as Answer.LightboxOpened).lightbox

        // the details name a current member's uploader as a way into their profile; a former member's opens nothing
        assertEquals("u2", (b.ask(Call.LightboxDetails(l, "a")) as Answer.MediumDetails).profile)
        assertNull((b.ask(Call.LightboxDetails(l, "b")) as Answer.MediumDetails).profile)

        assertEquals(Answer.Refused(Answer.Why.NOT_OFFERED), b.ask(Call.CaptureTimeOpen(l, "nowhere")))
        val c = (b.ask(Call.CaptureTimeOpen(l, "a")) as Answer.CaptureTimeOpened).captureTime
        val editor = b.watching(Watched.CaptureTime(c))
        val opened = editor.until { u -> (u as Update.CaptureTime).shown }
        assertEquals(Triple(30_000L, false, 30_000L), Triple(opened.atMs, opened.corrected, opened.originalMs))
        assertEquals(Answer.Refused(Answer.Why.NOT_TAKEN), b.ask(Call.CaptureTimeReset(c)), "a reset offered without a correction")
        // the host's picked local time as epoch ms, handed on as it came (the core's write holds it to the minute)
        assertEquals(Answer.FactsSaved(FactsSave.SAVED), b.ask(Call.CaptureTimeSet(c, 1_790_000_040_000)))
        editor.until { u -> (u as Update.CaptureTime).shown?.takeIf { it.corrected && it.atMs == 1_790_000_040_000 } }
        // a peer's correction meanwhile: reopened on theirs, said
        assertEquals(Answer.FactsSaved(FactsSave.REOPENED), b.ask(Call.CaptureTimeSet(c, PEERS_TURN)))
        val conflicted = editor.until { u -> (u as Update.CaptureTime).shown?.takeIf { it.said == CurationSaid.CONFLICT } }
        assertEquals(PEERS_TIME, conflicted.atMs)
        assertEquals(Answer.FactsSaved(FactsSave.SAVED), b.ask(Call.CaptureTimeReset(c)))
        editor.until { u -> (u as Update.CaptureTime).shown?.takeIf { !it.corrected && it.atMs == 30_000L } }
        assertEquals(listOf("time:a=1790000040000", "time:a=$PEERS_TURN", "time:a=null"), played.sent.filter { it.startsWith("time:") })

        // the editor closes with its lightbox
        assertEquals(Answer.Done, b.ask(Call.LightboxClose(l)))
        assertEquals(Update.CaptureTime(c, null).encoded(), b.watching(Watched.CaptureTime(c)).next().encoded())
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_CAPTURE_TIME), b.ask(Call.CaptureTimeSet(c, 1)), "an editor outlived its lightbox")
        b.shutdown()
    }

    @Test
    fun theBannerIsDrawnPlayedPickedSavedCancelledRemovedAndAskedAboutThroughCalls() = runBlocking {
        val played = PlayedMemory()
        val host = PlayedHost()
        val b = bridge(played, host)
        b.signedIn()
        val (g, page) = b.page()

        // what the page draws: a video banner by its picture's key, no ref, wrap or digest; its poster beside the JSON
        assertEquals(Triple(BannerMedium.VIDEO, "b-p", "video/webm"), page.banner!!.let { Triple(it.medium, it.picture, it.mediaType) })
        assertTrue(page.editsBanner)
        val (pictureAnswer, picture) = b.askWithPayload(Call.BannerPicture(g))
        assertEquals(Answer.Bytes(null), pictureAnswer)
        assertContentEquals("poster".encodeToByteArray(), picture)

        // the frame: 16:9 above 720, 1:1 at or below, never taller than 60 % of the viewport; the surround's 32 px picture
        assertEquals(Answer.BannerFramed(1024.0, 576.0, true, 32, 24), b.ask(Call.BannerFrame(1024.0, 2000.0, 4000, 3000)))
        assertEquals(Answer.BannerFramed(720.0, 600.0, false, 18, 32), b.ask(Call.BannerFrame(720.0, 1000.0, 1080, 1920)))

        // the video plays through a reader, in ranges, and goes with its page
        val reader = b.ask(Call.BannerPlay(g)) as Answer.Reader
        assertEquals(played.video.size.toLong() to "video/webm", reader.size to reader.mediaType)
        val edge = V1.FRAME_PLAINTEXT.toLong()
        assertContentEquals(played.video.copyOfRange((edge - 9).toInt(), (edge + 9).toInt()), b.askWithPayload(Call.ReaderRead(reader.reader, edge - 9, 18)).second)

        // the dialog: a pick refused, a pick decoded by the host to the cover's spec, its preview, saved
        val dir = java.nio.file.Files.createTempDirectory("veyq-banner").toFile()
        val raw = File(dir, "raw.jpg").apply { writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1)) }
        val beach = File(dir, "beach.jpg").apply { writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 2)) }
        val clip = File(dir, "clip.webm").apply { writeBytes(byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte(), 3)) }
        val d = (b.ask(Call.BannerOpen(g)) as Answer.BannerOpened).banner
        val dialog = b.watching(Watched.Banner(d))
        assertTrue(dialog.until { u -> (u as Update.Banner).shown }.let { it.hasBanner && it.canRemove && !it.canSave })
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_PATH), b.ask(Call.BannerPick(d, File(dir, "gone.jpg").path)))
        assertEquals(Answer.Refused(Answer.Why.NOT_TAKEN), b.ask(Call.BannerSave(d)), "a save offered before a pick")
        assertEquals(Answer.Done, b.ask(Call.BannerPick(d, raw.path)))
        dialog.until { u -> (u as Update.Banner).shown?.takeIf { it.said == BannerSaid.UNREADABLE && it.picked == null } }
        assertEquals(Answer.Refused(Answer.Why.NO_BYTES), b.ask(Call.BannerPreview(d)))
        assertEquals(Answer.Done, b.ask(Call.BannerPick(d, beach.path)))
        val picked = dialog.until { u -> (u as Update.Banner).shown?.takeIf { it.picked != null && it.canSave } }
        assertEquals(BannerMedium.IMAGE, picked.picked!!.medium)
        assertContentEquals("P-beach.jpg".encodeToByteArray(), b.askWithPayload(Call.BannerPreview(d)).second, "the preview is not the host's picture")
        assertTrue(
            HostRequest.MediaTiers(beach.path, "image/jpeg", 2048, 0.85, me.raddatz.veyq.mobile.memories.MediaUpload.THUMBNAIL_EDGE, me.raddatz.veyq.mobile.memories.MediaUpload.THUMBNAIL_QUALITY, 0) in host.asked,
            "the banner was not decoded to the cover's spec: ${host.asked}",
        )
        assertEquals(Answer.BannerSaved(BannerSave.DONE), b.ask(Call.BannerSave(d)))
        assertEquals(listOf("banner:IMAGE:P-beach.jpg"), played.sent.filter { it.startsWith("banner:IMAGE") })
        dialog.until { u -> (u as Update.Banner).shown?.takeIf { it.done } }
        assertEquals(Answer.Done, b.ask(Call.BannerClose(d)))

        // a save cancelled: the save answers so, the old banner as it was, the dialog can save again
        played.holdSave = true
        val d2 = (b.ask(Call.BannerOpen(g)) as Answer.BannerOpened).banner
        val dialog2 = b.watching(Watched.Banner(d2))
        assertEquals(Answer.Done, b.ask(Call.BannerPick(d2, clip.path)))
        assertEquals(BannerMedium.VIDEO, dialog2.until { u -> (u as Update.Banner).shown?.picked }.medium)
        assertEquals(Answer.Refused(Answer.Why.NOT_TAKEN), b.ask(Call.BannerCancel(d2)), "a cancel taken with no save running")
        coroutineScope {
            val saving = async { b.ask(Call.BannerSave(d2)) }
            dialog2.until { u -> (u as Update.Banner).shown?.takeIf { it.saving && it.progress == 0.5 } }
            assertEquals(Answer.Refused(Answer.Why.NOT_TAKEN), b.ask(Call.BannerPick(d2, beach.path)), "a pick taken while a save runs")
            assertEquals(Answer.Done, b.ask(Call.BannerCancel(d2)))
            assertEquals(Answer.BannerSaved(BannerSave.CANCELLED), saving.await())
        }
        assertTrue("banner:cancelled" in played.sent)
        dialog2.until { u -> (u as Update.Banner).shown?.takeIf { !it.saving && !it.done && it.canSave } }
        assertEquals(Answer.BannerSaved(BannerSave.DONE), b.ask(Call.BannerRemove(d2)))
        assertEquals(listOf("banner:remove:b-v"), played.sent.filter { it.startsWith("banner:remove") })
        played.holdSave = false

        // A2: the banner's own picture asks before its trash; answered, trashed with its Undo and the banner removed too
        assertEquals(Answer.Said(null), b.ask(Call.GalleryTrash(g, "a")))
        b.watching(Watched.Gallery(g)).until { u -> (u as Update.Gallery).shown?.takeIf { it.bannerQuestion == "a" } }
        assertEquals(emptyList(), played.sent.filter { it.startsWith("trash:") }, "trashed before the question was answered")
        assertEquals(Answer.Trashed(Notice(Msg.MEMORY_MOVED_TO_TRASH), undoable = true), b.ask(Call.GalleryTrashBannerPicture(g, removeBanner = true)))
        assertEquals(listOf("trash:a"), played.sent.filter { it.startsWith("trash:") })
        assertEquals(2, played.sent.count { it.startsWith("banner:remove") }, "Remove the banner too was not written")

        // every dialog and reader goes with its page
        val d3 = (b.ask(Call.BannerOpen(g)) as Answer.BannerOpened).banner
        assertEquals(Answer.Done, b.ask(Call.GalleryClose(g)))
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_READER), b.ask(Call.ReaderRead(reader.reader, 0, 4)), "the banner's reader outlived its page")
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_BANNER), b.ask(Call.BannerPick(d3, beach.path)), "a banner dialog outlived its page")
        assertEquals(Update.Banner(d3, null).encoded(), b.watching(Watched.Banner(d3)).next().encoded())
        dir.deleteRecursively()
        b.shutdown()
    }

    @Test
    fun aBannerDialogAndItsSaveGoWithTheTenant() = runBlocking {
        val played = PlayedMemory()
        val b = bridge(played, PlayedHost())
        b.signedIn()
        val (g, _) = b.page()
        val dir = java.nio.file.Files.createTempDirectory("veyq-banner").toFile()
        val beach = File(dir, "beach.jpg").apply { writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 2)) }
        val d = (b.ask(Call.BannerOpen(g)) as Answer.BannerOpened).banner
        val dialog = b.watching(Watched.Banner(d))
        assertEquals(Answer.Done, b.ask(Call.BannerPick(d, beach.path)))
        dialog.until { u -> (u as Update.Banner).shown?.takeIf { it.canSave } }
        val p = (b.ask(Call.ParticipantsOpen(g)) as Answer.ParticipantsOpened).participants

        assertEquals(Answer.Done, b.ask(Call.SessionSignOut("a.example#u1")))
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_BANNER), b.ask(Call.BannerSave(d)), "a banner dialog left open after its tenant closed answered its save")
        dialog.until { u -> (u as Update.Banner).takeIf { it.shown == null } }
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_PARTICIPANTS), b.ask(Call.ParticipantsSave(p)), "a picker outlived its tenant")
        assertEquals(emptyList(), played.sent.filter { it.startsWith("banner:") || it.startsWith("participants:") }, "a write left after the tenant closed")
        dir.deleteRecursively()
        b.shutdown()
    }

    @Test
    fun aPersonsPageShowsTheirFrameAndTheirMemoriesAndGoesWithTheTenant() = runBlocking {
        val played = PlayedMemory()
        val b = bridge(played, PlayedHost())
        assertEquals(Answer.Refused(Answer.Why.NO_TENANT_OPEN), b.ask(Call.PersonOpen("u2")))
        b.signedIn()
        val person = (b.ask(Call.PersonOpen("u2")) as Answer.PersonOpened).person
        val page = b.watching(Watched.Person(person))
        val shown = page.until { u -> (u as Update.Person).shown?.takeIf { it.memories?.loading == false } }
        assertEquals(listOf("u2", "Ben", "~ben"), listOf(shown.userId, shown.name, shown.username))
        assertTrue(shown.picture && !shown.former, "the core holds Ben's picture")
        assertEquals(listOf(ApplicationId.MEMORIES), shown.sections)
        val m = shown.memories!!
        assertEquals(listOf("harz", "m"), m.rows.map { it.ref.nodeId }, "newest start day first, undated last")
        assertEquals(listOf(MemoryDateShown(DateMode.SINGLE, "2026-08-01", null), null).map { it?.let { d -> d.mode to d.from } }, m.rows.map { it.date?.let { d -> d.mode to d.from } })
        assertEquals(listOf(SpaceRef("g", "GROUP", "g"), null), m.rows.map { it.group }, "a group's row names its group, the tenant's none")
        assertTrue(m.rows[0].groupName != null && m.rows[1].groupName == null)
        assertTrue(m.partial && m.offersViews && !m.empty && m.view == MemoriesView.LIST)
        assertEquals(ApplicationId.MEMORIES, m.rows[0].ref.application, "a row opens in Memories ([Call.ShellOpen])")

        // List | Map, not stored; *Try again*; the map needs the tenant's map services
        assertEquals(Answer.Done, b.ask(Call.PersonView(person, MemoriesView.MAP)))
        page.until { u -> (u as Update.Person).shown?.memories?.takeIf { it.view == MemoriesView.MAP } }
        assertEquals(Answer.Done, b.ask(Call.PersonRetry(person)))
        assertEquals(Answer.Refused(Answer.Why.NO_MAP_SERVICES), b.ask(Call.PersonMap(person)))

        // a former member's page: no name, called former, the section still runs
        val gone = (b.ask(Call.PersonOpen("gone")) as Answer.PersonOpened).person
        val formerly = b.watching(Watched.Person(gone)).until { u -> (u as Update.Person).shown?.takeIf { it.memories?.loading == false } }
        assertEquals(Triple(null, true, true), Triple(formerly.name, formerly.former, formerly.memories!!.empty))

        // closed by the host, and every page with its tenant
        assertEquals(Answer.Done, b.ask(Call.PersonClose(gone)))
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_PERSON), b.ask(Call.PersonRetry(gone)))
        assertEquals(Answer.Done, b.ask(Call.SessionSignOut("a.example#u1")))
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_PERSON), b.ask(Call.PersonView(person, MemoriesView.LIST)), "a person's page outlived its tenant")
        page.until { u -> (u as Update.Person).takeIf { it.shown == null } }
        assertEquals(Update.Person(person, null).encoded(), b.watching(Watched.Person(person)).next().encoded())
        b.shutdown()
    }

    private data class MemberShownOf(val userId: String, val name: String?, val ticked: Boolean)

    private fun Update.encoded() = wire.encodeToString(Update.serializer(), this)

    private companion object {
        /** A digest of the banner's file as the web seals it (base64url of 32 bytes). */
        val DIGEST = "q".repeat(42) + "A"

        /** A correction the played server answers as a peer's correction meanwhile ([PEERS_TIME] theirs). */
        const val PEERS_TURN = 1_790_000_100_000L
        const val PEERS_TIME = 1_500_000_000_000L
    }
}
