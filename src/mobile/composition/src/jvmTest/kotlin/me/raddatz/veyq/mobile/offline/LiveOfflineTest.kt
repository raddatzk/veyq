package me.raddatz.veyq.mobile.offline

import me.raddatz.veyq.mobile.media.Derivation
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.api.createClientPlugin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.media.ByteSource
import me.raddatz.veyq.mobile.memories.MediaUpload
import me.raddatz.veyq.mobile.memories.MemoryMedia
import me.raddatz.veyq.mobile.navigation.Hidden
import me.raddatz.veyq.mobile.navigation.Home
import me.raddatz.veyq.mobile.navigation.RecentUses
import me.raddatz.veyq.mobile.composition.ShellModel
import me.raddatz.veyq.mobile.session.LiveRun
import me.raddatz.veyq.mobile.session.NodeTrash
import me.raddatz.veyq.mobile.session.Reach
import me.raddatz.veyq.mobile.session.SoftwareDeviceKeys
import me.raddatz.veyq.mobile.session.StaySignedIn
import me.raddatz.veyq.mobile.session.ThisDevice
import me.raddatz.veyq.mobile.session.VeyqAccount
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A gallery viewed online opened from the offline store with the server unreachable, after the account was closed and
 * unlocked again (ADR 0140 decisions 4 and 5) — on a running dev instance (`VEYQ_LIVE`), skipped without one.
 *
 * A second session of the dev principal (a device of this test's own, remembered at LOGIN, its store in a temporary
 * directory) makes a Memory with two photos in its default space (each with a preview and a thumbnail tier: a tier is kept, a photo's original never) and views it: the listing
 * and both tiles are kept. A third session — another device — trashes the first photo; the first reads the gallery
 * online again, then its account is closed (as a process end leaves it, nothing wiped), the network is cut (every request
 * of its clients fails before it is sent), and the account unlocked again: from the store. The trashed photo is absent
 * there and its neighbour (the positive control) present, its tile the bytes read online, no request having left.
 *
 * Bite (ADR 0140 decision 5, the CR's bite a): merge the newer listing into the kept one instead of replacing it
 * (`VeyqAccount.kept`) → the trashed photo is shown from the store. The Memory is trashed and purged and every device
 * taken off the account however the case ends.
 */
class LiveOfflineTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

    private val liveRun = LiveRun()

    @AfterTest
    fun takeOffTheDevices() = liveRun.removeAll()

    @Volatile private var reachable = true
    @Volatile private var sentWhileCut = 0

    /** Every request of a client this makes fails before it is sent while [reachable] is false: the network cut. */
    private val cut = createClientPlugin("CutNetwork") {
        onRequest { _, _ ->
            if (!reachable) {
                sentWhileCut++
                throw java.io.IOException("the network is cut")
            }
        }
    }

    private val client: (HttpClientConfig<*>.() -> Unit) -> HttpClient = { block -> HttpClient(OkHttp) { block(); install(cut) } }

    @Test
    fun aGalleryViewedOnlineOpensFromTheStoreWithTheServerUnreachable() = runBlocking {
        val origin = liveRun.origin
        org.junit.Assume.assumeTrue("VEYQ_LIVE is not set: a live case", origin != null)
        val p = BouncyCastlePrimitives
        val env = liveRun.env
        val root = Files.createTempDirectory("veyq-offline-live").toFile()
        val keys = SoftwareDeviceKeys(p)
        val device = ThisDevice(liveRun.store(), keys, "live offline test", p, offlineRoot = root.absolutePath).also { it.offlineFiles = me.raddatz.veyq.mobile.offline.SizedVolume() }
        var account = VeyqAccount.signIn(p, origin!!, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"), device, client)
        assertEquals(VeyqAccount.TrustOutcome.REMEMBERED, account.trustThisDevice(null, StaySignedIn.LOGIN), "the dev instance does not let a device stay signed in")
        assertNotNull(account.offline, "a remembered account on a device with a store directory keeps no store")
        val tenant = account.open(account.defaultTenant!!)
        val shell = ShellModel(tenant, Hidden({}, emptySet()), RecentUses({}, emptyList())) { System.currentTimeMillis() }
        shell.load(openLastUsed = false)
        val scopeId = assertNotNull(Home.defaultSpace(shell.state.first { it != null }!!.lists.spaces)?.scopeId, "no space")
        val made = shell.createMemory(scopeId, null, "Offline memory ${System.currentTimeMillis() % 100_000}")
        val memory = assertNotNull(made.node, "the Memory was not made: ${made.notice}")
        val other = VeyqAccount.signIn(p, origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"), liveRun.device("live offline second session"), client)
        try {
            val photo = File(System.getProperty("veyq.repoRoot"), "src/web/src/features/memories/__fixtures__/exif-sample.jpg").readBytes()
            val ids = (1..2).map { i ->
                val up = MediaUpload.upload(
                    tenant, scopeId, memory.nodeId,
                    MediaUpload.Original(ByteSource(photo.size.toLong()) { from, length -> photo.copyOfRange(from.toInt(), minOf(photo.size.toLong(), from + length).toInt()) }, "image/jpeg", "Offline photo $i.jpg", 1_000_000L * i),
                    Derivation.Derived(photo, photo, null, null), System.currentTimeMillis(),
                )
                assertEquals(MediaUpload.Outcome.DONE, up.outcome, up.code)
                up.mediaId!!
            }
            // viewed online: the listing and both tiles
            tenant.forgetTree()
            val online = assertNotNull(MemoryMedia.list(tenant, scopeId, memory.nodeId))
            assertEquals(ids.toSet(), online.items.map { it.mediaId }.toSet())
            val tiles = online.items.associate { it.mediaId to assertNotNull(MemoryMedia.read(tenant, it, assertNotNull(it.thumbnail))) }

            // another session trashes the first photo; this one reads the gallery online again
            assertEquals(me.raddatz.veyq.mobile.memories.GalleryModel.Removal.DONE, MemoryMedia.trash(other.open(other.defaultTenant!!), scopeId, ids[0]))
            assertEquals(listOf(ids[1]), MemoryMedia.list(tenant, scopeId, memory.nodeId)!!.items.map { it.mediaId })

            // the app goes, the network is cut, the account unlocked again
            account.retire()
            reachable = false
            val record = assertNotNull(device.trusted().firstOrNull(), "the device forgot its trust")
            account = VeyqAccount.resume(p, record, device, client)
            assertIs<Reach.Offline>(account.reach.value)
            val offlineTenant = account.open(account.defaultTenant!!)
            val kept = assertNotNull(MemoryMedia.list(offlineTenant, scopeId, memory.nodeId), "the Memory is not in the kept tree")
            assertNotNull(kept.offline, "the listing is not the store's")
            assertEquals(listOf(ids[1]), kept.items.map { it.mediaId }, "the trashed photo was shown from the store (or its neighbour was not)")
            assertContentEquals(tiles.getValue(ids[1]), MemoryMedia.read(offlineTenant, kept.items.single(), kept.items.single().thumbnail!!))
            println("veyq-live: gallery opened offline as of ${kept.offline?.asOf}, ${kept.items.size} medium, ${sentWhileCut} requests refused while cut")
        } finally {
            reachable = true
            runCatching { account.retire() }
            val otherTenant = runCatching { other.open(other.defaultTenant!!) }.getOrNull()
            if (otherTenant != null) {
                runCatching { NodeTrash.delete(otherTenant, scopeId, memory.nodeId) }
                runCatching { NodeTrash.purge(otherTenant, scopeId, memory.nodeId) }
            }
            runCatching { other.signOut() }
            // a remembered account's sign-out forgets its trust and wipes its store
            val ended = runCatching { VeyqAccount.resume(p, device.trusted().first(), device, client) }.getOrNull()
            runCatching { ended?.signOut() }
            assertTrue(root.listFiles().orEmpty().isEmpty(), "the store outlived the sign-out: ${root.listFiles()?.map { it.name }}")
            root.deleteRecursively()
        }
    }
}
