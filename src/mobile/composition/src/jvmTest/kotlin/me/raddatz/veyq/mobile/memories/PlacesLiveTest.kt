package me.raddatz.veyq.mobile.memories

import me.raddatz.veyq.mobile.media.Derivation
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.raddatz.veyq.mobile.media.ByteSource
import me.raddatz.veyq.mobile.blob.UploadQueue
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.map.LatLng
import me.raddatz.veyq.mobile.memories.music.TrackLabel
import me.raddatz.veyq.mobile.memories.place.MemoryDate
import me.raddatz.veyq.mobile.memories.place.MemoryFacts
import me.raddatz.veyq.mobile.memories.place.MemoryLocation
import me.raddatz.veyq.mobile.memories.tracks.Gpx
import me.raddatz.veyq.mobile.navigation.Hidden
import me.raddatz.veyq.mobile.navigation.Home
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.RecentUses
import me.raddatz.veyq.mobile.composition.ShellModel
import me.raddatz.veyq.mobile.offline.KeptRead
import me.raddatz.veyq.mobile.offline.Pin
import me.raddatz.veyq.mobile.session.AccountDoor
import me.raddatz.veyq.mobile.session.LiveRun
import me.raddatz.veyq.mobile.session.LocalDays
import me.raddatz.veyq.mobile.session.NodeCover
import me.raddatz.veyq.mobile.session.NodeTrash
import me.raddatz.veyq.mobile.session.SoftwareDeviceKeys
import me.raddatz.veyq.mobile.session.StaySignedIn
import me.raddatz.veyq.mobile.session.ThisDevice
import me.raddatz.veyq.mobile.session.VeyqAccount
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.GregorianCalendar
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * A Memory's date, place and tracks against a running dev instance (ADR 0143 D5, D8, D9; `VEYQ_LIVE`, skipped without one).
 * No request goes to a tile server or a geocoder: the shell builds no map services, so no place name is looked up.
 *
 * The dev principal makes a Memory in its default space. **Concurrent curation:** one session holds the Memory's tree,
 * a second sets its cover, and the first then sets a date range and a place from its stale tree — the `409` re-runs only
 * their own change, so all three are kept. **The settle:** the date and the place are set back to `auto` (the place a
 * name of the member's own), two geotagged photos uploaded (the web's own EXIF fixtures: Munich 2021, Santiago 2020), and
 * the gallery's opening settles: the range runs over both capture days and the position is the photos' medoid. **A
 * track:** a GPX file added through the Map view's model with its label (asked first, sealed at birth), its label edited,
 * removed and brought back by Undo. **Offline:** a device of its own pins the Memory, and every frame of the track's
 * original is kept (its Map view lists it offline).
 *
 * The Memory is trashed and purged, every device taken off the account however the case ends; the store is wiped.
 */
class PlacesLiveTest {
    private val liveRun = LiveRun()

    @AfterTest
    fun takeOffTheDevices() = liveRun.removeAll()

    private val client: (io.ktor.client.HttpClientConfig<*>.() -> Unit) -> HttpClient = { block -> HttpClient(OkHttp, block) }

    private val root = File(System.getProperty("veyq.repoRoot"))
    private fun fixture(name: String) = File(root, "src/web/src/features/memories/__fixtures__/$name").readBytes()
    private fun source(bytes: ByteArray) = ByteSource(bytes.size.toLong()) { from, length ->
        bytes.copyOfRange(from.toInt(), minOf(bytes.size.toLong(), from + length).toInt())
    }

    private val gpx = """<?xml version="1.0" encoding="UTF-8"?><gpx version="1.1" creator="veyq live" xmlns="http://www.topografix.com/GPX/1/1">""" +
        "<trk><name>Isar walk</name><trkseg>" +
        (0 until 400).joinToString("") { i -> """<trkpt lat="${48.13 + i * 0.0001}" lon="${11.57 + i * 0.0001}"><ele>${520 + i % 7}</ele></trkpt>""" } +
        "</trkseg></trk></gpx>"

    private fun picked(name: String, bytes: ByteArray) = UploadSource.of(name, bytes.size.toLong(), null, 1_000_000L) { from, length ->
        bytes.copyOfRange(from.toInt(), minOf(bytes.size.toLong(), from + length).toInt())
    }

    @Test
    fun aMemorysDatePlaceAndTracksAreCuratedSettledAndKeptOffline() = runBlocking {
        val origin = liveRun.origin
        org.junit.Assume.assumeTrue("VEYQ_LIVE is not set: a live case", origin != null)
        val p = BouncyCastlePrimitives
        val env = liveRun.env
        suspend fun signIn(label: String, device: ThisDevice = liveRun.device(label)) =
            VeyqAccount.signIn(p, origin!!, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"), device, client)
        val one = signIn("live places test")
        val tenant = one.open(one.defaultTenant!!)
        val shell = ShellModel(tenant, Hidden({}, emptySet()), RecentUses({}, emptyList())) { System.currentTimeMillis() }
        shell.load(openLastUsed = false)
        val scopeId = assertNotNull(Home.defaultSpace(shell.state.first { it != null }!!.lists.spaces)?.scopeId, "no space")
        val n = System.currentTimeMillis() % 100_000
        val name = "Places live $n"
        val memory = assertNotNull(MemoryCreate.create(tenant, scopeId, null, MemoryCreate.MEMORY, name).node, "the Memory was not made").nodeId
        tenant.forgetTree()
        val queue = UploadQueue()
        val uploads = shell.mediaUploads(queue, object : Derivation.Deriver {
            override fun derive(source: UploadSource, type: String, done: (Derivation.Derived) -> Unit) = done(Derivation.Derived(null, null, null, null))
        }, left = {}) {}
        val dir = Files.createTempDirectory("veyq-places-live").toFile()
        var pinned: VeyqAccount? = null
        var gallery: GalleryModel? = null
        try {
            // concurrent curation: this session's tree is read, then a second session sets the cover
            assertNotNull(tenant.node(scopeId, memory))
            val two = signIn("live places second session")
            val other = two.open(two.defaultTenant!!)
            assertEquals(NodeCover.Outcome.DONE, NodeCover.set(other, scopeId, memory, jpeg()))
            val range = MemoryDate(MemoryDate.Mode.RANGE, "2026-05-01", "2026-05-04")
            val lisbon = MemoryLocation("Lisboa", MemoryLocation.NameSource.MANUAL, LatLng(38.7223, -9.1393), MemoryLocation.DerivedMode.FIXED)
            assertEquals(MemoryFacts.Outcome.Written, MemoryFacts.setDate(tenant, scopeId, memory, range), "the date over a stale tree")
            assertEquals(MemoryFacts.Outcome.Written, MemoryFacts.setLocation(tenant, scopeId, memory, lisbon))
            other.forgetTree()
            val read = assertNotNull(MemoryFacts.of(other, scopeId, memory))
            assertEquals(range to lisbon, read.date to read.location, "the date and the place as set")
            assertNotNull(NodeCover.openNode(other, memory), "the peer's cover kept beside them")
            two.signOut()

            // back to auto, and two geotagged photos: the opening settles the range and the medoid
            assertEquals(MemoryFacts.Outcome.Written, MemoryFacts.setDate(tenant, scopeId, memory, MemoryDate.NONE))
            assertEquals(MemoryFacts.Outcome.Written, MemoryFacts.setLocation(tenant, scopeId, memory, MemoryLocation("Our trip", MemoryLocation.NameSource.MANUAL, null, MemoryLocation.DerivedMode.AUTO)))
            for ((file, label) in listOf("exif-sample.jpg" to "Munich.jpg", "exif-sample-fallbacks.jpg" to "Santiago.jpg")) {
                val up = MediaUpload.upload(tenant, scopeId, memory, MediaUpload.Original(source(fixture(file)), "image/jpeg", label, 1_000_000), Derivation.Derived(null, null, null, null), System.currentTimeMillis())
                assertEquals(MediaUpload.Outcome.DONE, up.outcome, up.code)
            }
            tenant.forgetTree()
            val g = shell.galleryModel(NodeAt(scopeId, memory, "MEMORY", name), uploads).also { gallery = it }
            val settled = withTimeout(60_000) { g.state.first { it.facts?.date?.from != null && it.facts?.location?.derived != null } }
            val munich = GregorianCalendar(2021, 6, 15, 18, 4, 5).timeInMillis
            val santiago = GregorianCalendar(2020, 0, 2, 3, 4, 5).timeInMillis
            assertEquals(MemoryDate(MemoryDate.Mode.AUTO, LocalDays.isoOf(santiago), LocalDays.isoOf(munich)), settled.facts?.date, "the auto range over both photos")
            val place = assertNotNull(settled.facts?.location)
            assertEquals("Our trip" to MemoryLocation.DerivedMode.AUTO, place.name to place.derivedMode, "the member's name kept, the place auto")
            assertEquals(MemoryLocation.medoid(settled.gpsPoints), place.derived, "the position is the photos' medoid")
            assertTrue(place.derived in settled.gpsPoints)

            // a track: added with its label, edited, removed and brought back
            val tracks = shell.trackList(g, uploads) {}
            tracks.active(true)
            assertEquals(emptyList(), tracks.add(listOf(picked("Isar.gpx", gpx.encodeToByteArray()))))
            assertEquals("Isar walk", tracks.current.asking.single().fileTrackName)
            assertEquals(1, tracks.confirm(listOf(TrackLabel.Label("Sunday walk", "walking"))))
            val added = withTimeout(120_000) { tracks.state.first { s -> s.entries.orEmpty().any { it.status == me.raddatz.veyq.mobile.memories.tracks.TrackList.Status.READY } } }.entries!!.single()
            assertEquals(Triple("Sunday walk", "walking", 400), Triple(added.ownName, added.activity, added.lines.single().size), "the label sealed at birth, the track read")
            val item = g.current.tracks.single()
            assertEquals(Gpx.MEDIA_TYPE, item.type)
            assertNull(tracks.edit(added.mediaId, TrackLabel.Label("Isar, Sunday", "running")))
            val edited = withTimeout(60_000) { g.state.first { s -> s.tracks.singleOrNull()?.meta?.trackName == "Isar, Sunday" } }.tracks.single()
            assertEquals("running", edited.meta?.trackActivity)
            tracks.remove(added.mediaId)
            assertTrue(g.current.tracks.isEmpty(), "removed at once")
            assertNull(g.undo())
            withTimeout(60_000) { g.state.first { s -> s.tracks.any { it.mediaId == added.mediaId } } }

            // a device of its own pins the Memory: the track's original kept, every frame
            val device = ThisDevice(liveRun.store(), SoftwareDeviceKeys(p), "live places pins", p, offlineRoot = dir.absolutePath).also { it.offlineFiles = me.raddatz.veyq.mobile.offline.SizedVolume() }
            val door = AccountDoor(p, device, client)
            val account = signIn("live places pins", device).also { pinned = it }
            assertEquals(VeyqAccount.TrustOutcome.REMEMBERED, account.trustThisDevice(null, StaySignedIn.LOGIN))
            val t = door.open(account, account.defaultTenant!!)
            door.opened(t)
            val pins = assertNotNull(account.offlinePins)
            assertTrue(pins.pin(t, scopeId, memory))
            withContext(Dispatchers.Default) {
                withTimeout(3.minutes) { pins.pins.first { ps -> ps.any { it.node == memory && it.state == Pin.State.KEPT } } }
            }
            val store = assertNotNull(account.offline).store
            val kept = store.entries().filter { it.key.kind == KeptRead.FRAMES && it.key.id == item.original.blobId }.sumOf { store.frames(it.key).size }
            assertEquals(item.original.chunkCount, kept, "every frame of the track's original kept")
            println("veyq-live: date and place kept beside a peer's cover, settled to ${settled.facts?.date} at ${place.derived}, a track added, relabelled, removed and restored, pinned ($kept of ${item.original.chunkCount} frames)")
        } finally {
            gallery?.close()
            uploads.close()
            runCatching { pinned?.retire() }
            runCatching { NodeTrash.delete(tenant, scopeId, memory) }
            runCatching { NodeTrash.purge(tenant, scopeId, memory) }
            runCatching { one.signOut() }
            dir.deleteRecursively()
        }
    }

    private fun jpeg(): ByteArray {
        val image = BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply { color = Color(0x2E7D32); fillRect(0, 0, 64, 48); dispose() }
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpeg", it) }.toByteArray()
    }
}
