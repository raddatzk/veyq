package me.raddatz.veyq.mobile.memories

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.raddatz.veyq.mobile.blob.ApplicationBlobs
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.memories.music.AudioFacts
import me.raddatz.veyq.mobile.audio.AudioFiles
import me.raddatz.veyq.mobile.memories.music.MusicUpload
import me.raddatz.veyq.mobile.memories.music.TrackLabel
import me.raddatz.veyq.mobile.navigation.Hidden
import me.raddatz.veyq.mobile.navigation.Home
import me.raddatz.veyq.mobile.navigation.RecentUses
import me.raddatz.veyq.mobile.composition.ShellModel
import me.raddatz.veyq.mobile.offline.KeptRead
import me.raddatz.veyq.mobile.offline.Pin
import me.raddatz.veyq.mobile.session.AccountDoor
import me.raddatz.veyq.mobile.session.LiveRun
import me.raddatz.veyq.mobile.session.NodeReport
import me.raddatz.veyq.mobile.session.NodeTrash
import me.raddatz.veyq.mobile.session.SoftwareDeviceKeys
import me.raddatz.veyq.mobile.session.StaySignedIn
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.session.ThisDevice
import me.raddatz.veyq.mobile.session.VeyqAccount
import java.io.ByteArrayInputStream
import java.nio.file.Files
import javax.sound.sampled.AudioSystem
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * A Memory's music against a running dev instance (ADR 0142 decisions 7 and 10–12; `VEYQ_LIVE`, skipped without one).
 *
 * The dev principal makes a Memory in its default space and adds two tracks as the apps add them (`MusicUpload`: checked,
 * then the original alone with its listed tier): a WAV of 2 s silence, 3 s tone, 2 s silence (its length read by the JVM's
 * own audio system, standing in for a platform's player) and an MP3 of bare frames (its length counted from them). A fresh
 * session reads both back: the WAV trimmed to its tone within a scan window, the MP3 untrimmed with its length, each typed by
 * its bytes. Two sessions rename the MP3 from the same listing: the first's name is taken, the second's is a conflict that
 * names the first's. The WAV is reported as an ITEM: the report is accepted, and the keys it hands over open its blob. Last,
 * a device of its own pins the Memory: both tracks' originals are kept, every frame of each.
 *
 * The Memory is trashed and purged and every device taken off the account however the case ends; the store is wiped.
 * The ITEM report is not: each run leaves one open report in the dev instance's moderation queue (nothing here may
 * decide it), named "veyq live test (MusicLiveTest)" in its reason.
 */
class MusicLiveTest {
    private val liveRun = LiveRun()

    @AfterTest
    fun takeOffTheDevices() = liveRun.removeAll()

    private val client: (io.ktor.client.HttpClientConfig<*>.() -> Unit) -> HttpClient = { block -> HttpClient(OkHttp, block) }

    private val wav = AudioFiles.wavBytes(AudioFiles.Spec(16, 1, 8000, listOf(2.0 to 0.0, 3.0 to 0.5, 2.0 to 0.0)))
    private val mp3Frames = 400
    private val mp3 = AudioFiles.mp3Stream(mp3Frames * AudioFiles.MP3_FRAME)
    private val mp3Ms = (mp3Frames * 1152 * 1000.0 / 44100).roundToLong()

    /** A platform's length of a picked file: the JVM's audio system for a WAV, the MP3's frames counted. */
    private fun lengthOf(bytes: ByteArray, type: String): Long? = when (type) {
        "audio/wav" -> AudioSystem.getAudioInputStream(ByteArrayInputStream(bytes)).use { (it.frameLength * 1000.0 / it.format.frameRate).roundToLong() }
        "audio/mpeg" -> mp3Ms
        else -> null
    }

    private suspend fun add(tenant: TenantSession, scopeId: String, nodeId: String, bytes: ByteArray, name: String, claimed: String?): String {
        val taken = MusicUpload.check(AudioFiles.picked(bytes, name, claimed)) { lengthOf(bytes, it) }
        val job = MusicUpload.Job(tenant, scopeId, nodeId, taken as MusicUpload.Check.Taken)
        job.run {}
        return assertNotNull(job.mediaId)
    }

    @Test
    fun aMemorysMusicIsAddedReadRenamedReportedAndKeptOffline() = runBlocking {
        val origin = liveRun.origin
        org.junit.Assume.assumeTrue("VEYQ_LIVE is not set: a live case", origin != null)
        val p = BouncyCastlePrimitives
        val env = liveRun.env
        suspend fun signIn(label: String, device: ThisDevice = liveRun.device(label)) =
            VeyqAccount.signIn(p, origin!!, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"), device, client)
        val one = signIn("live music test")
        val tenant = one.open(one.defaultTenant!!)
        val shell = ShellModel(tenant, Hidden({}, emptySet()), RecentUses({}, emptyList())) { System.currentTimeMillis() }
        shell.load(openLastUsed = false)
        val scopeId = assertNotNull(Home.defaultSpace(shell.state.first { it != null }!!.lists.spaces)?.scopeId, "no space")
        val n = System.currentTimeMillis() % 100_000
        val memory = assertNotNull(MemoryCreate.create(tenant, scopeId, null, MemoryCreate.MEMORY, "Music live $n").node, "the Memory was not made").nodeId
        tenant.forgetTree()
        val root = Files.createTempDirectory("veyq-music-live").toFile()
        var pinned: VeyqAccount? = null
        try {
            val wavId = add(tenant, scopeId, memory, wav, "Tone with silence.wav", "audio/x-wav")
            val mp3Id = add(tenant, scopeId, memory, mp3, "Frames.mp3", "video/mp4")

            // a fresh session reads both back, as listed
            val two = signIn("live music second session")
            val other = two.open(two.defaultTenant!!)
            val listed = assertNotNull(MemoryMedia.list(other, scopeId, memory)).items
            val w = listed.single { it.mediaId == wavId }
            val m = listed.single { it.mediaId == mp3Id }
            assertEquals("audio/wav" to "audio/mpeg", w.type to m.type, "typed by the bytes, never the claim")
            val wf = assertNotNull(w.meta?.facts, "the WAV's facts")
            assertEquals(7000L, wf.durationMs)
            assertTrue(abs(wf.trimStartMs - 2000) <= AudioFacts.SCAN_WINDOW_MS && abs(wf.trimEndMs - 5000) <= AudioFacts.SCAN_WINDOW_MS, "the WAV trimmed to its tone: $wf")
            assertEquals(AudioFacts.untrimmed(mp3Ms), m.meta?.facts, "the MP3 untrimmed, with its length")
            assertTrue(listOf(w, m).all { it.isAudio && it.thumbnail == null && it.preview == null }, "a track has no picture")

            // two sessions rename the MP3 from the same listing: the second loses to the first and hears its name
            val mine = assertNotNull(MemoryMedia.list(tenant, scopeId, memory)).items.single { it.mediaId == mp3Id }
            assertEquals(TrackLabel.Outcome.Done("Alpha"), TrackLabel.rename(other, m, "Alpha"))
            assertEquals(TrackLabel.Outcome.Conflict("Alpha"), TrackLabel.rename(tenant, mine, "Beta"))
            assertEquals("Alpha", assertNotNull(MemoryMedia.list(tenant, scopeId, memory)).items.single { it.mediaId == mp3Id }.meta?.trackName)

            // the WAV reported as an ITEM: accepted, and the keys it hands over open its blob
            val keys = assertNotNull(MediumReport.keys(tenant, w))
            val blobKey = keys.single { it.role == "BLOB" && it.ref == w.original.blobId }.key
            assertContentEquals(wav, ApplicationBlobs(tenant).frames(scopeId, w.original.blobId, w.original.chunkCount, blobKey), "the report's key opens the WAV")
            assertTrue(keys.any { it.role == "MEDIA_META" }, "the listed tier's key is handed over")
            val sent = MediumReport.report(tenant, w, NodeReport.CATEGORIES.last().token, "veyq live test (MusicLiveTest): a test track, reported to prove the ITEM report", "")
            assertTrue(sent.outcome == NodeReport.Outcome.SENT || sent.outcome == NodeReport.Outcome.MODERATION_OFF, "the report was refused: ${sent.outcome}")
            two.signOut()

            // a device of its own pins the Memory: both tracks' originals kept, every frame
            val device = ThisDevice(liveRun.store(), SoftwareDeviceKeys(p), "live music pins", p, offlineRoot = root.absolutePath)
            val door = AccountDoor(p, device, client)
            val account = signIn("live music pins", device).also { pinned = it }
            assertEquals(VeyqAccount.TrustOutcome.REMEMBERED, account.trustThisDevice(null, StaySignedIn.LOGIN))
            val t = door.open(account, account.defaultTenant!!)
            door.opened(t)
            val pins = assertNotNull(account.offlinePins)
            val plan = assertNotNull(pins.plan(t, scopeId, memory))
            assertEquals(2, plan.blobs, "a pin keeps both tracks' originals")
            assertTrue(pins.pin(t, scopeId, memory))
            withContext(Dispatchers.Default) {
                withTimeout(3.minutes) { pins.pins.first { ps -> ps.any { it.node == memory && it.state == Pin.State.KEPT } } }
            }
            val store = assertNotNull(account.offline).store
            val frames = store.entries().filter { it.key.kind == KeptRead.FRAMES }.associate { it.key.id to store.frames(it.key).size }
            assertEquals(mapOf(w.original.blobId to w.original.chunkCount, m.original.blobId to m.original.chunkCount), frames.filterKeys { it in setOf(w.original.blobId, m.original.blobId) })
            println("veyq-live: music added (WAV trimmed ${wf.trimStartMs}..${wf.trimEndMs} of ${wf.durationMs} ms, MP3 untrimmed $mp3Ms ms), renamed with a conflict, reported (${sent.outcome} ${sent.reference}), pinned (${frames.values.sum()} frames)")
        } finally {
            runCatching { pinned?.retire() }
            runCatching { NodeTrash.delete(tenant, scopeId, memory) }
            runCatching { NodeTrash.purge(tenant, scopeId, memory) }
            runCatching { one.signOut() }
            root.deleteRecursively()
        }
    }
}
