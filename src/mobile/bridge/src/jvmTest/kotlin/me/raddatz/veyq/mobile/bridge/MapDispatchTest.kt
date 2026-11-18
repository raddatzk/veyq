package me.raddatz.veyq.mobile.bridge

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.blob.UploadQueue
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.map.LatLng
import me.raddatz.veyq.mobile.map.MapConsent
import me.raddatz.veyq.mobile.map.MapHttp
import me.raddatz.veyq.mobile.map.MapOffer
import me.raddatz.veyq.mobile.map.MapServices
import me.raddatz.veyq.mobile.memories.GalleryModel
import me.raddatz.veyq.mobile.memories.MediaDetails
import me.raddatz.veyq.mobile.memories.MediaUploads
import me.raddatz.veyq.mobile.memories.MemoryMedia
import me.raddatz.veyq.mobile.memories.music.MusicUpload
import me.raddatz.veyq.mobile.memories.music.TrackLabel
import me.raddatz.veyq.mobile.memories.place.DateEditor
import me.raddatz.veyq.mobile.memories.place.MemoriesMap
import me.raddatz.veyq.mobile.memories.place.MemoryDate
import me.raddatz.veyq.mobile.memories.place.MemoryFacts
import me.raddatz.veyq.mobile.memories.place.MemoryLocation
import me.raddatz.veyq.mobile.memories.place.PlaceEditor
import me.raddatz.veyq.mobile.memories.place.PlaceNames
import me.raddatz.veyq.mobile.memories.tracks.Gpx
import me.raddatz.veyq.mobile.memories.tracks.TrackList
import me.raddatz.veyq.mobile.memories.tracks.TrackUpload
import me.raddatz.veyq.mobile.navigation.Connection
import me.raddatz.veyq.mobile.navigation.Hidden
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.NodeRef
import me.raddatz.veyq.mobile.navigation.RecentUses
import me.raddatz.veyq.mobile.navigation.SpaceRef
import me.raddatz.veyq.mobile.navigation.TenantRef
import me.raddatz.veyq.mobile.session.MemoryDeviceStore
import me.raddatz.veyq.mobile.session.NodeReport
import me.raddatz.veyq.mobile.session.SessionModel
import me.raddatz.veyq.mobile.session.SessionModel.Gate
import me.raddatz.veyq.mobile.session.StaySignedIn
import me.raddatz.veyq.mobile.session.TrustChange
import me.raddatz.veyq.mobile.session.TrustedDevice
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A Memory's date, place and maps through the bridge (ADR 0143 decision 11, as the Linux and Windows apps reach them): the
 * core's raster map — the view the core holds, the tiles in view placed in normalised space and asked by id as bytes beside
 * the JSON (`Map.Tile` → `Answer.Bytes`), none before this device's consent and none after its withdrawal —, the Memories
 * map's clusters and taps, a photo's small map, the date and place editors (a member's pan is the place), and the Map view's
 * tracks drawn as lines; every map gone with its page and with its tenant. The tile server and the geocoder are played
 * ([PlayedServices]): no test calls a real one. Every call, watch and update crosses as JSON.
 */
class MapDispatchTest {
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

    /** The tile server and the geocoder the test plays: every request the map path sends, by its address. */
    private class PlayedServices {
        val sent = CopyOnWriteArrayList<String>()
        val tiles: List<String> get() = sent.filter { "/reverse" !in it }
        val client = HttpClient(MockEngine { r ->
            val url = r.url.toString()
            sent += url
            if ("/reverse" in url) respond("""{"address":{"suburb":"Mitte","city":"Berlin","country":"Deutschland"}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            else respond(PNG, HttpStatusCode.OK, headersOf(HttpHeaders.CacheControl, "max-age=600"))
        }) { expectSuccess = false }
        val store = MemoryDeviceStore()

        fun services() = MapServices(OFFER, MapConsent.of(store), MapHttp(client, "https://a.example"))
    }

    private val memory = NodeAt("s", "m", "MEMORY", "Berlin 2026")

    /** A Memory the test plays as the server: two geotagged photos, a GPX track; its date a range; what is written recorded. */
    private class PlayedMemory {
        val items = CopyOnWriteArrayList<MemoryMedia.Item>()
        val sent = CopyOnWriteArrayList<String>()
        val places = CopyOnWriteArrayList<MemoryLocation?>()
        val dates = CopyOnWriteArrayList<MemoryDate>()
        val gpx = """<gpx><trk><name>Spree</name><trkseg><trkpt lat="52.52" lon="13.40"/><trkpt lat="52.51" lon="13.42"/><trkpt lat="52.50" lon="13.45"/></trkseg></trk></gpx>"""

        fun item(id: String, type: String, name: String, lat: Double? = null, lng: Double? = null) = MemoryMedia.Item(
            "s", "m", id, MemoryMedia.Blob("o-$id", 1, 100, "", null), null, null,
            MemoryMedia.Meta(null, null, 1_780_000_000_000, null, "exif", lat, lng, null, null), type, name, "user:u1", false, ByteArray(0),
        )

        init {
            items += item("p1", "image/jpeg", "p1.jpg", 52.520, 13.405)
            items += item("p2", "image/jpeg", "p2.jpg", 52.516, 13.377)
            items += item("t1", Gpx.MEDIA_TYPE, "Spree.gpx")
        }

        fun door(node: NodeAt, uploads: MediaUploads) = object : GalleryModel.Door {
            override suspend fun gallery() = MemoryMedia.Gallery(MemoryMedia.order(items.toList()), 1, 0)
            override suspend fun scopeNodes() = emptyList<NodeRef>()
            override suspend fun read(item: MemoryMedia.Item, blob: MemoryMedia.Blob) =
                if (item.mediaId == "t1") gpx.encodeToByteArray() else blob.blobId.encodeToByteArray()
            override fun reader(item: MemoryMedia.Item): RangeReader? = null
            override suspend fun stream(item: MemoryMedia.Item, sink: suspend (ByteArray) -> Unit): Boolean {
                sink(gpx.encodeToByteArray())
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
            override suspend fun facts() = MemoryFacts.Facts(MemoryDate(MemoryDate.Mode.RANGE, "2026-07-01", "2026-07-14"), places.lastOrNull())
        }

        fun uploads(queue: UploadQueue) = MediaUploads(queue, object : MediaUploads.Door {
            override fun job(scopeId: String, nodeId: String, source: UploadSource): UploadQueue.Job = error("no photos here")
            override fun musicJob(scopeId: String, nodeId: String, track: MusicUpload.Check.Taken): UploadQueue.Job = error("no music here")
            override fun trackJob(scopeId: String, nodeId: String, track: TrackUpload.Check.Taken, label: TrackLabel.Label) = object : UploadQueue.Job {
                override val name get() = track.source.name
                override val sizeBytes get() = track.source.size
                override suspend fun run(progress: (Double) -> Unit) {
                    sent += "upload:${track.source.name}:${label.name}:${label.activity}"
                }
            }
            override fun changed() {}
            override fun left(source: UploadSource) {}
        })

        fun trackDoor(uploads: MediaUploads, node: NodeAt) = object : TrackList.Door {
            override suspend fun check(source: UploadSource, held: Int) = TrackUpload.check(source, held)
            override fun queue(tracks: List<Pair<TrackUpload.Check.Taken, TrackLabel.Label>>) = uploads.uploadTracks(node.scopeId, node.nodeId, node.name, tracks)
            override fun left(source: UploadSource) {}
            override suspend fun relabel(item: MemoryMedia.Item, label: TrackLabel.Label): TrackLabel.Outcome {
                sent += "relabel:${item.mediaId}:${label.name}:${label.activity}"
                return TrackLabel.Outcome.Done(label.name)
            }
            override suspend fun report(item: MemoryMedia.Item, category: String, reason: String, contact: String) =
                NodeReport.Sent(NodeReport.Outcome.SENT, "abcd1234", "2026-11-16T10:00:00Z")
            override suspend fun reportOffered() = true
            override val origin = "https://a.example"
        }
    }

    private class Played(val memory: PlayedMemory = PlayedMemory(), val server: PlayedServices = PlayedServices()) {
        @Volatile var services: MapServices? = null
    }

    private fun bridge(p: Played) = Bridge {
        val hidden = Hidden({}, emptySet())
        Composition(SessionModel(Door(), hidden, RecentUses({}, emptyList())), hidden, JvmFiles, language = "de") { _ ->
            Tenant(
                shell = null, pot = null, me = "u1", pictures = MutableStateFlow(emptyMap()), loadPictures = {},
                mediaUploads = { queue, _, _ -> p.memory.uploads(queue) },
                gallery = { node, uploads, music -> GalleryModel(node, p.memory.door(node, uploads), music = music) },
                mapServices = { _ -> p.server.services().also { p.services = it } },
                dateEditor = { g -> DateEditor(g.current.facts?.date, g.current.autoRange) { d -> p.memory.dates += d; MemoryFacts.Outcome.Written } },
                placeEditor = { g ->
                    val points = g.current.gpsPoints
                    PlaceEditor(g.current.facts?.location, MemoryLocation.medoid(points), points, p.services?.let { PlaceNames(it) { "de" } }, save = { place ->
                        p.memory.places += place
                        MemoryFacts.Outcome.Written
                    })
                },
                memoriesMap = { MemoriesMap(listOf(MemoriesMap.Pin("s", "m", "Berlin 2026", BERLIN, hasCover = true), MemoriesMap.Pin("s", "a", "Alps", ALPS, hasCover = false))) },
                memoryDates = { mapOf("m" to MemoryDate(MemoryDate.Mode.RANGE, "2026-07-01", "2026-07-14")) },
                trackList = { g, uploads -> TrackList(g, p.memory.trackDoor(uploads, g.node)) },
                profile = { userId -> me.raddatz.veyq.mobile.navigation.PersonProfile.of(userId, emptyMap(), emptySet()) },
                personMemories = { userId ->
                    me.raddatz.veyq.mobile.memories.people.PersonMemories(userId, object : me.raddatz.veyq.mobile.memories.people.PersonMemories.Door {
                        override suspend fun index() = personIndex
                        override fun open(ref: me.raddatz.veyq.mobile.navigation.ObjectRef) {}
                    })
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

    private suspend fun Bridge<*, *>.gallery(): Long {
        val g = (ask(Call.GalleryOpen(memory)) as Answer.GalleryOpened).gallery
        watching(Watched.Gallery(g)).until { u -> (u as Update.Gallery).shown?.takeIf { it.rows != null && it.date != null } }
        return g
    }

    /** Map [map] sized by the host and shown with its tiles: the map as drawn. */
    private suspend fun Bridge<*, *>.shownMap(map: Long): MapShown {
        assertEquals(Answer.Done, ask(Call.MapView(map, null, null, null, 800.0, 600.0, 2.0)))
        return watching(Watched.Map(map)).until { u -> (u as Update.Map).shown?.takeIf { it.view != null && it.tiles.isNotEmpty() } }
    }

    @Test
    fun aMapAsksForNoTileBeforeTheConsentAndHandsEachOneInViewAsBytes() = runBlocking {
        val p = Played()
        val b = bridge(p)
        assertEquals(Answer.Refused(Answer.Why.NO_TENANT_OPEN), b.ask(Call.MapOpenMemories))
        b.signedIn()
        val settings = b.watching(Watched.MapServices)
        val map = (b.ask(Call.MapOpenMemories) as Answer.MapOpened).map
        val drawn = b.watching(Watched.Map(map))
        assertNull((drawn.next() as Update.Map).shown!!.view, "a map holds no view before the host sized it")
        assertEquals(Answer.Done, b.ask(Call.MapView(map, null, null, null, 800.0, 600.0, 2.0)))
        val asked = drawn.until { u -> (u as Update.Map).shown?.takeIf { it.view != null } }
        assertEquals(MapStatus.Consent("tiles.example", "nominatim.example", renewal = false), asked.state, "the consent names the hosts served")
        assertEquals(emptyList(), asked.tiles, "tiles placed before the consent")
        assertEquals("© OpenStreetMap contributors", asked.attribution)
        assertEquals(1L, asked.view!!.moved, "the core's opening view")
        assertEquals(listOf("m", "a"), asked.clusters.map { it.pin.node.nodeId }, "the two Memories framed apart")
        assertEquals(listOf("m", null), asked.clusters.map { it.pin.cover }, "a pin names the cover its row has")
        assertEquals(Answer.Refused(Answer.Why.NO_BYTES), b.ask(Call.MapTile(map, "4/8/5")))
        delay(200)
        assertEquals(emptyList(), p.server.sent.toList(), "a request left before the consent")

        // the consent: the tiles in view, each asked by its id and handed beside the JSON
        assertEquals(Answer.Done, b.ask(Call.MapTurnOn))
        val shown = drawn.until { u -> (u as Update.Map).shown?.takeIf { it.state == MapStatus.Shown && it.tiles.isNotEmpty() } }
        assertEquals("tiles.example", (settings.until { u -> (u as Update.MapServices).shown?.takeIf { it.consentTileHost != null } }).consentTileHost)
        val first = shown.tiles.first()
        val (answer, bytes) = b.askWithPayload(Call.MapTile(map, first.tile))
        assertEquals(Answer.Bytes("image/png"), answer)
        assertContentEquals(PNG, bytes)
        assertEquals(listOf("https://tiles.example/${first.tile}.png"), p.server.tiles, "the tile the host asked for, and only it")
        assertEquals(Answer.Refused(Answer.Why.NO_BYTES), b.ask(Call.MapTile(map, "19/0/0")), "a tile out of view was answered")
        val v = shown.view!!

        // a pan is the host's: the view follows, the core did not move it
        assertEquals(Answer.Done, b.ask(Call.MapView(map, v.x + 0.001, v.y, v.zoom, 800.0, 600.0, 2.0)))
        val panned = drawn.until { u -> (u as Update.Map).shown?.view?.takeIf { it.x != v.x } }
        assertEquals(1L, panned.moved)
        // a tap on a lone pin opens its Memory; zoomed out, both are one cluster, framed by the core
        val tapped = b.ask(Call.MapTap(map, "m")) as Answer.MapTapped
        assertEquals(GalleryModel.page(NodeAt("s", "m", "MEMORY", "Berlin 2026")), tapped.open!!.page)
        assertEquals(Answer.Done, b.ask(Call.MapView(map, v.x, v.y, 1.0, 800.0, 600.0, 2.0)))
        drawn.until { u -> (u as Update.Map).shown?.clusters?.singleOrNull()?.takeIf { it.count == 2 } }
        assertEquals(Answer.MapTapped(null, emptyList()).let { wire.encodeToString(Answer.serializer(), it) }, wire.encodeToString(Answer.serializer(), b.ask(Call.MapTap(map, "m"))))
        assertEquals(2L, drawn.until { u -> (u as Update.Map).shown?.view?.takeIf { it.moved == 2L } }.moved, "the cluster framed")

        // the withdrawal: no tile placed, none answered, none asked
        val before = p.server.sent.size
        assertEquals(Answer.Done, b.ask(Call.MapTurnOff))
        drawn.until { u -> (u as Update.Map).shown?.takeIf { it.state is MapStatus.Consent && it.tiles.isEmpty() } }
        assertEquals(Answer.Refused(Answer.Why.NO_BYTES), b.ask(Call.MapTile(map, first.tile)), "a tile answered after the withdrawal")
        assertNull(settings.until { u -> (u as Update.MapServices).shown?.takeIf { it.state is MapStatus.Consent } }.consentTileHost)
        delay(200)
        assertEquals(before, p.server.sent.size, "a request left after the withdrawal")
        b.shutdown()
    }

    /**
     * A map is its page's and its tenant's (ADR 0143 decision 4, D11): the place editor's map and the Map view's close with
     * their gallery, a photo's with its lightbox, and every map — the Memories tab's too — with the tenant, its services
     * closed: no tile of a map left open is answered after a sign-out, and no request leaves.
     */
    /** Person u2 is on Berlin (located) and on Harz (no place): their map holds Berlin's pin alone. */
    private val personIndex = run {
        val berlin = NodeRef("s", "m", null, "MEMORY", "Berlin 2026")
        val harz = NodeRef("g", "harz", null, "MEMORY", "Harz")
        me.raddatz.veyq.mobile.memories.people.ParticipantIndex.Index(
            mapOf("u2" to setOf(berlin, harz)),
            mapOf(
                berlin to me.raddatz.veyq.mobile.memories.people.ParticipantIndex.Memory(
                    berlin, SpaceRef("s", "TENANT", "s"), MemoryDate(MemoryDate.Mode.SINGLE, "2026-07-01", null), null,
                    MemoriesMap.Pin("s", "m", "Berlin 2026", BERLIN, hasCover = true), listOf("u2"),
                ),
                harz to me.raddatz.veyq.mobile.memories.people.ParticipantIndex.Memory(harz, SpaceRef("g", "GROUP", "g"), null, null, null, listOf("u2")),
            ),
            partial = false,
        )
    }

    @Test
    fun aPersonsMapShowsTheirLocatedMemoriesAndGoesWithTheirPage() = runBlocking {
        val p = Played()
        val b = bridge(p)
        b.signedIn()
        assertEquals(Answer.Done, b.ask(Call.MapTurnOn))
        val person = (b.ask(Call.PersonOpen("u2")) as Answer.PersonOpened).person
        val section = b.watching(Watched.Person(person)).until { u -> (u as Update.Person).shown?.memories?.takeIf { !it.loading } }
        assertEquals(2 to 1, section.rows.size to section.located)
        val map = (b.ask(Call.PersonMap(person)) as Answer.MapOpened).map
        val shown = b.shownMap(map)
        assertEquals(listOf("m"), shown.clusters.map { it.pin.node.nodeId }, "the person's map is not their located Memories")
        val tapped = b.ask(Call.MapTap(map, "m")) as Answer.MapTapped
        assertEquals("m", tapped.open?.page?.nodeId, "a pin opens its Memory through Shell.Open")
        // the map goes with the person's page
        assertEquals(Answer.Done, b.ask(Call.PersonClose(person)))
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_MAP), b.ask(Call.MapTile(map, shown.tiles.first().tile)), "a person's map outlived their page")
        b.shutdown()
    }

    @Test
    fun aMapItsTilesAndItsServicesGoWithItsPageAndItsTenant() = runBlocking {
        val p = Played()
        val b = bridge(p)
        b.signedIn()
        assertEquals(Answer.Done, b.ask(Call.MapTurnOn))
        val g = b.gallery()
        val place = b.ask(Call.PlaceOpen(g)) as Answer.PlaceOpened
        val tracks = b.ask(Call.TracksOpen(g)) as Answer.TracksOpened
        val box = (b.ask(Call.LightboxOpen(g, 0)) as Answer.LightboxOpened).lightbox
        assertTrue((b.ask(Call.LightboxDetails(box, "p1")) as Answer.MediumDetails).map, "a geotagged photo offers its map")
        val photo = (b.ask(Call.LightboxMap(box, "p1")) as Answer.MapOpened).map
        val memories = (b.ask(Call.MapOpenMemories) as Answer.MapOpened).map
        val pageMaps = listOf(place.map!!, tracks.map!!, photo)
        for (m in pageMaps + memories) {
            val tile = b.shownMap(m).tiles.first().tile
            assertEquals(Answer.Bytes("image/png"), b.askWithPayload(Call.MapTile(m, tile)).first, "map $m answered no tile")
        }
        val photoShown = b.watching(Watched.Map(photo)).until { u -> (u as Update.Map).shown?.photo }
        assertEquals(HeldMap.point(LatLng(52.520, 13.405)).x, photoShown.at.x)

        // the lightbox closes its photo's map; the gallery its place's and its Map view's
        val photoTile = b.shownMap(photo).tiles.first().tile
        assertEquals(Answer.Done, b.ask(Call.LightboxClose(box)))
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_MAP), b.ask(Call.MapTile(photo, photoTile)), "a photo's map outlived its lightbox")
        assertEquals(Answer.Done, b.ask(Call.GalleryClose(g)))
        for (m in pageMaps) {
            assertEquals(Answer.Refused(Answer.Why.NO_SUCH_MAP), b.ask(Call.MapTile(m, "0/0/0")), "map $m outlived its page")
            assertEquals(Update.Map(m, null).let { wire.encodeToString(Update.serializer(), it) }, wire.encodeToString(Update.serializer(), b.watching(Watched.Map(m)).next()))
        }
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_PLACE), b.ask(Call.PlaceSave(place.place)), "a place editor outlived its page")
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_TRACKS), b.ask(Call.TracksSeen(tracks.tracks)), "a Map view outlived its page")

        // the tenant closes the Memories tab's map and the services: nothing answered, nothing asked
        val tile = b.shownMap(memories).tiles.first().tile
        assertEquals(Answer.Bytes("image/png"), b.askWithPayload(Call.MapTile(memories, tile)).first)
        val services = b.watching(Watched.MapServices)
        services.until { u -> (u as Update.MapServices).shown }
        val sent = p.server.sent.size
        assertEquals(Answer.Done, b.ask(Call.SessionSignOut("a.example#u1")))
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_MAP), b.askWithPayload(Call.MapTile(memories, tile)).first, "a map left open after the tenant closed answered its tile")
        services.until { u -> (u as Update.MapServices).takeIf { it.shown == null } }
        assertEquals(Answer.Refused(Answer.Why.NO_MAP_SERVICES), b.ask(Call.MapTurnOn), "map services outlived their tenant")
        delay(200)
        assertEquals(sent, p.server.sent.size, "a request left after the tenant closed")
        b.shutdown()
    }

    @Test
    fun theDateAndThePlaceAreEditedThroughTheirEditorsAndAPanIsThePlace() = runBlocking {
        val p = Played()
        val b = bridge(p)
        b.signedIn()
        assertEquals(Answer.Done, b.ask(Call.MapTurnOn))
        val g = b.gallery()
        val page = b.watching(Watched.Gallery(g)).until { u -> (u as Update.Gallery).shown?.takeIf { it.date != null } }
        assertTrue(page.editsFacts)
        assertEquals(1, page.tracks)
        assertEquals(listOf(DateMode.RANGE, "2026-07-01", "2026-07-14"), page.date!!.let { listOf(it.mode, it.from, it.to) })
        assertNull(page.place)
        assertFalse(page.placePending, "no place, nothing pending")
        // a place armed at birth (uc.memory.place A1): no name yet, and the line says it is pending
        p.memory.places += MemoryLocation("", MemoryLocation.NameSource.GEOCODED, null, MemoryLocation.DerivedMode.AUTO)
        assertEquals(Answer.Done, b.ask(Call.GalleryReload(g)))
        val pending = b.watching(Watched.Gallery(g)).until { u -> (u as Update.Gallery).shown?.takeIf { it.placePending } }
        assertNull(pending.place, "a pending place has no name to show")
        p.memory.places.removeAt(p.memory.places.lastIndex)
        assertEquals(Answer.Done, b.ask(Call.GalleryReload(g)))
        b.watching(Watched.Gallery(g)).until { u -> (u as Update.Gallery).shown?.takeIf { !it.placePending } }
        val dates = b.ask(Call.MemoriesDates) as Answer.MemoryDates
        assertEquals(listOf("m" to "2026-07-14"), dates.dates.map { it.nodeId to it.date.to })

        // the date: a range that ends before it starts is said and not saved
        val d = (b.ask(Call.DateOpen(g)) as Answer.DateOpened).date
        val date = b.watching(Watched.Date(d))
        assertEquals(DateMode.RANGE, (date.next() as Update.Date).shown!!.mode)
        assertEquals(Answer.Done, b.ask(Call.DateFrom(d, "2026-07-20")))
        assertTrue(date.until { u -> (u as Update.Date).shown?.takeIf { it.rangeInvalid } }.rangeInvalid)
        assertEquals(Answer.Refused(Answer.Why.NOT_TAKEN), b.ask(Call.DateSave(d)))
        assertEquals(Answer.Done, b.ask(Call.DateTo(d, "2026-07-30")))
        assertEquals(Answer.FactsSaved(FactsSave.SAVED), b.ask(Call.DateSave(d)))
        assertEquals(MemoryDate(MemoryDate.Mode.RANGE, "2026-07-20", "2026-07-30"), p.memory.dates.single())
        assertEquals(Answer.Done, b.ask(Call.DateClose(d)))
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_DATE), b.ask(Call.DateSave(d)))

        // the place: auto at the photos' medoid, named by the geocoder; a member's pan makes it fixed
        val opened = b.ask(Call.PlaceOpen(g)) as Answer.PlaceOpened
        val map = assertNotNull(opened.map)
        val place = b.watching(Watched.Place(opened.place))
        val named = place.until { u -> (u as Update.Place).shown?.takeIf { it.name.isNotEmpty() } }
        assertEquals(listOf(PlaceMode.AUTO, PlaceNaming.GEOCODED, true, false), listOf(named.mode, named.naming, named.canFollow, named.canRemove))
        assertEquals(listOf("suburb", "city", "country"), named.levels.map { it.level })
        val shown = b.shownMap(map)
        assertTrue(shown.centrePin)
        assertEquals(2, shown.dots.size, "the photos' dots")
        val v = shown.view!!
        // a resize keeps the place
        assertEquals(Answer.Done, b.ask(Call.MapView(map, v.x, v.y, v.zoom, 900.0, 700.0, 2.0)))
        delay(200)
        assertEquals(PlaceMode.AUTO, (b.watching(Watched.Place(opened.place)).next() as Update.Place).shown!!.mode, "a resize moved the place")
        assertEquals(Answer.Done, b.ask(Call.MapView(map, v.x + 0.0001, v.y, v.zoom, 900.0, 700.0, 2.0)))
        place.until { u -> (u as Update.Place).shown?.takeIf { it.mode == PlaceMode.FIXED } }
        assertEquals(Answer.Done, b.ask(Call.PlaceTyped(opened.place, "Am Kupfergraben")))
        place.until { u -> (u as Update.Place).shown?.takeIf { it.naming == PlaceNaming.MANUAL && it.name == "Am Kupfergraben" } }
        assertEquals(Answer.FactsSaved(FactsSave.SAVED), b.ask(Call.PlaceSave(opened.place)))
        val saved = assertNotNull(p.memory.places.single())
        assertEquals(listOf("Am Kupfergraben", MemoryLocation.DerivedMode.FIXED), listOf(saved.name, saved.derivedMode))

        // *Follow the photos*: auto again, and the core moves the map back
        assertEquals(Answer.Done, b.ask(Call.PlaceFollow(opened.place)))
        place.until { u -> (u as Update.Place).shown?.takeIf { it.mode == PlaceMode.AUTO } }
        assertEquals(2L, b.watching(Watched.Map(map)).until { u -> (u as Update.Map).shown?.view?.takeIf { it.moved == 2L } }.moved)
        assertEquals(Answer.Done, b.ask(Call.PlaceClose(opened.place)))
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_MAP), b.ask(Call.MapTile(map, "0/0/0")), "a place's map outlived its editor")
        b.shutdown()
    }

    @Test
    fun theMapViewListsDrawsAddsEditsSavesReportsAndRemovesItsTracks() = runBlocking {
        val p = Played()
        val b = bridge(p)
        b.signedIn()
        assertEquals(Answer.Done, b.ask(Call.MapTurnOn))
        val g = b.gallery()
        val opened = b.ask(Call.TracksOpen(g)) as Answer.TracksOpened
        val t = opened.tracks
        val view = b.watching(Watched.Tracks(t))
        val listed = view.until { u -> (u as Update.Tracks).shown?.takeIf { s -> s.tracks?.singleOrNull()?.status == TrackStatus.READY } }
        val spree = listed.tracks!!.single()
        assertEquals(listOf("t1", "Spree", "Spree.gpx"), listOf(spree.mediaId, spree.name, spree.fileName))
        assertTrue(spree.facts.first().distance != null && spree.shown && !spree.selected && spree.canRemove)
        assertEquals(TrackLabel.ACTIVITIES, listed.activities)
        assertTrue(listed.canAdd && listed.canEdit && listed.canDownload && listed.canReport)

        // drawn as a line in normalised space beside the photos' dots; a selected track framed by the core
        val map = assertNotNull(opened.map)
        val drawn = b.shownMap(map)
        val line = drawn.tracks.single()
        assertEquals(HeldMap.point(LatLng(52.52, 13.40)).x, line.segments.single().first().x)
        assertEquals(2, drawn.dots.size)
        assertEquals(Answer.Done, b.ask(Call.TracksSelect(t, "t1")))
        val selected = b.watching(Watched.Map(map)).until { u -> (u as Update.Map).shown?.takeIf { it.view?.moved == 2L && it.tracks.single().selected } }
        assertTrue(selected.view!!.zoom > drawn.view!!.zoom, "the selected track was not framed")
        assertEquals(Answer.Done, b.ask(Call.TracksShow(t, "t1", false)))
        b.watching(Watched.Map(map)).until { u -> (u as Update.Map).shown?.takeIf { it.tracks.isEmpty() } }

        // picked files: the one that is no GPX said, the other's label asked before anything is uploaded
        val dir = kotlin.io.path.createTempDirectory("tracks-add").toFile()
        val ride = File(dir, "ride.gpx").apply { writeText(p.memory.gpx.replace("Spree", "Ride")) }
        val text = File(dir, "notes.gpx").apply { writeText("not a track") }
        val added = b.ask(Call.TracksAdd(t, listOf(ride.path, text.path, File(dir, "missing.gpx").path))) as Answer.TracksAdded
        assertEquals(1, added.said.size)
        val asking = view.until { u -> (u as Update.Tracks).shown?.asking?.takeIf { it.isNotEmpty() } }
        assertEquals(listOf("ride.gpx" to "Ride"), asking.map { it.fileName to it.fileTrackName })
        assertFalse(p.memory.sent.any { it.startsWith("upload:") }, "a track uploaded before its label")
        assertEquals(Answer.Queued(1), b.ask(Call.TracksConfirm(t, listOf(TrackLabelShown("Morning", "cycling")))))
        withTimeout(10.seconds) { while ("upload:ride.gpx:Morning:cycling" !in p.memory.sent) delay(10) }

        // edit, download, report, remove
        assertEquals(Answer.Said(null), b.ask(Call.TracksEdit(t, "t1", TrackLabelShown("Spree", "running"))))
        assertTrue("relabel:t1:Spree:running" in p.memory.sent)
        val target = File(dir, "Spree.gpx")
        val save = (b.ask(Call.TracksDownload(t, "t1", target.path)) as Answer.Saving).save
        b.watching(Watched.Save(save)).until { u -> (u as Update.Save).shown?.takeIf { it.end != null } }
        assertEquals(p.memory.gpx, target.readText())
        val report = (b.ask(Call.TracksReport(t, "t1")) as Answer.ReportOpened).report
        assertEquals(Answer.Done, b.ask(Call.ReportClose(report)))
        assertTrue(b.ask(Call.TracksRemove(t, "t1")) is Answer.Trashed)
        assertTrue("trash:t1" in p.memory.sent)
        view.until { u -> (u as Update.Tracks).shown?.tracks?.takeIf { it.isEmpty() } }
        dir.deleteRecursively()
        b.shutdown()
    }

    companion object {
        val OFFER = MapOffer("https://tiles.example/{z}/{x}/{y}.png", "© OpenStreetMap contributors", "https://nominatim.example")
        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13)
        val BERLIN = LatLng(52.52, 13.405)
        val ALPS = LatLng(47.27, 11.39)
    }
}
