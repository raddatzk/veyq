package me.raddatz.veyq.mobile.memories

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.media.ByteSource
import me.raddatz.veyq.mobile.memories.banner.Banner
import me.raddatz.veyq.mobile.memories.banner.BannerWrite
import me.raddatz.veyq.mobile.memories.banner.StoredMatch
import me.raddatz.veyq.mobile.memories.place.MemoryFacts
import me.raddatz.veyq.mobile.navigation.Hidden
import me.raddatz.veyq.mobile.navigation.Home
import me.raddatz.veyq.mobile.navigation.RecentUses
import me.raddatz.veyq.mobile.navigation.ShellModel
import me.raddatz.veyq.mobile.offline.OfflinePins
import me.raddatz.veyq.mobile.offline.Pin
import me.raddatz.veyq.mobile.session.AccountDoor
import me.raddatz.veyq.mobile.session.LiveRun
import me.raddatz.veyq.mobile.session.NodeTrash
import me.raddatz.veyq.mobile.session.Reach
import me.raddatz.veyq.mobile.session.SoftwareDeviceKeys
import me.raddatz.veyq.mobile.session.StaySignedIn
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.session.ThisDevice
import me.raddatz.veyq.mobile.session.VeyqAccount
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * A Memory's banner against a running dev instance (ADR 0144 decisions 5 and 7; `VEYQ_LIVE`, skipped without one). The dev
 * principal — a device of this test's own, remembered at LOGIN, its store in a temporary directory — makes a Memory in its
 * default space and:
 *  1. sets an **image banner** (the web's EXIF fixture as the picked file and as its "decoded" JPEG: the server decodes
 *     nothing): uploaded, its picture read back;
 *  2. adds a **video** to the gallery (the e2e suite's 6-second VP8 clip, three frames) and replaces the banner by the same
 *     file: the banner shares the medium's ORIGINAL and PREVIEW — **no byte goes to the store** (the store PUTs counted
 *     across the save: 0);
 *  3. adds the same video to the gallery again: its original shares the banner's object (A1) — only its two tiers go to the
 *     store, never the clip's three frames;
 *  4. the first medium is the banner's picture (A2's question would be asked); trashed, the banner stays and still reads;
 *  5. **a peer's banner between open and save:** a second session sets an image banner after this one opened on the video —
 *     this one's save is [BannerWrite.Outcome.Stale], the peer's banner intact;
 *  6. **pinned**, the account closed, the network cut and the account unlocked again: the banner's picture draws and its
 *     video plays from the store;
 *  7. the banner removed (online again): no field, both slots freed.
 *
 * The Memory is trashed and purged and every device taken off the account however the case ends; the store is wiped.
 */
class BannerLiveTest {
    private val liveRun = LiveRun()

    @AfterTest
    fun takeOffTheDevices() = liveRun.removeAll()

    @Volatile private var reachable = true
    @Volatile private var storePuts = 0

    /** Counts what goes to the store (a presigned PUT, never under `/api`), and cuts the network on demand. */
    private val watch = createClientPlugin("BannerLiveWatch") {
        onRequest { request, _ ->
            if (!reachable) throw java.io.IOException("the network is cut")
            if (request.method == HttpMethod.Put && !request.url.build().encodedPath.startsWith("/api")) storePuts++
        }
    }

    private val client: (HttpClientConfig<*>.() -> Unit) -> HttpClient = { block -> HttpClient(OkHttp) { block(); install(watch) } }

    private val root = File(System.getProperty("veyq.repoRoot"))
    private val jpeg = File(root, "src/web/src/features/memories/__fixtures__/exif-sample.jpg").readBytes()
    private val webm = File(root, "tests/e2e/fixtures/testsrc2-6s-vp8.webm").readBytes()

    private fun source(bytes: ByteArray) = ByteSource(bytes.size.toLong()) { from, length -> bytes.copyOfRange(from.toInt(), minOf(bytes.size.toLong(), from + length).toInt()) }
    private fun picked(bytes: ByteArray, name: String, type: String) = UploadSource.of(name, bytes.size.toLong(), type, null) { from, length ->
        bytes.copyOfRange(from.toInt(), minOf(bytes.size.toLong(), from + length).toInt())
    }

    /** The platform's decode, played: the fixture JPEG as the image's JPEG and as a video's poster. */
    private val pictures = object : BannerWrite.Pictures {
        override suspend fun image(source: UploadSource) = jpeg
        override suspend fun poster(source: UploadSource) = jpeg
    }

    private suspend fun facts(tenant: TenantSession, scopeId: String, memory: String): MemoryFacts.Facts {
        tenant.forgetTree()
        return assertNotNull(MemoryFacts.of(tenant, scopeId, memory))
    }

    private suspend fun addVideo(tenant: TenantSession, scopeId: String, memory: String, name: String): String {
        val up = MediaUpload.upload(
            tenant, scopeId, memory, MediaUpload.Original(source(webm), "video/webm", name, 1_000_000),
            MediaUpload.Derived(jpeg, jpeg, 320, 240), System.currentTimeMillis(),
        )
        assertEquals(MediaUpload.Outcome.DONE, up.outcome, up.code)
        return up.mediaId!!
    }

    @Test
    fun aBannerIsSetSharedWithAnEqualMediumGuardedAgainstAPeerKeptOfflineAndRemoved() = runBlocking {
        val origin = liveRun.origin
        org.junit.Assume.assumeTrue("VEYQ_LIVE is not set: a live case", origin != null)
        val p = BouncyCastlePrimitives
        val env = liveRun.env
        val dir = Files.createTempDirectory("veyq-banner-live").toFile()
        val device = ThisDevice(liveRun.store(), SoftwareDeviceKeys(p), "live banner test", p, offlineRoot = dir.absolutePath)
        val door = AccountDoor(p, device, client)
        var account = VeyqAccount.signIn(p, origin!!, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"), device, client)
        assertEquals(VeyqAccount.TrustOutcome.REMEMBERED, account.trustThisDevice(null, StaySignedIn.LOGIN))
        var tenant = door.open(account, account.defaultTenant!!)
        val shell = ShellModel(tenant, Hidden({}, emptySet()), RecentUses({}, emptyList())) { System.currentTimeMillis() }
        shell.load(openLastUsed = false)
        val scopeId = assertNotNull(Home.defaultSpace(shell.state.first { it != null }!!.lists.spaces)?.scopeId, "no space")
        val n = System.currentTimeMillis() % 100_000
        val memory = assertNotNull(shell.createMemory(scopeId, null, "Banner live $n").node, "the Memory was not made").nodeId
        val other = VeyqAccount.signIn(p, origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"), liveRun.device("live banner peer"), client)
        try {
            // 1. an image banner
            val image = BannerWrite.pick(picked(jpeg, "Munich.jpg", "image/jpeg"), pictures)
            assertEquals(BannerWrite.Outcome.Done, BannerWrite.set(tenant, scopeId, memory, Banner.Slots.NONE, image, emptyList()))
            val first = assertNotNull(facts(tenant, scopeId, memory).banner, "no banner sealed")
            assertEquals(Banner.Medium.IMAGE, first.medium)
            assertNotNull(first.sha256, "the file's digest sealed")
            assertEquals(jpeg.size, Banner.picture(tenant, assertNotNull(tenant.node(scopeId, memory)))?.size, "the picture reads back")

            // 2. a video in the gallery, then the banner replaced by the same file: shared, nothing to the store
            val clip = addVideo(tenant, scopeId, memory, "clip.webm")
            val items = assertNotNull(MemoryMedia.list(tenant, scopeId, memory)).items
            val video = BannerWrite.pick(picked(webm, "clip.webm", "video/webm"), pictures)
            val putsBefore = storePuts
            val opened = facts(tenant, scopeId, memory).bannerSlots
            assertEquals(BannerWrite.Outcome.Done, BannerWrite.set(tenant, scopeId, memory, opened, video, items))
            assertEquals(putsBefore, storePuts, "an equal gallery file's banner uploaded bytes")
            val shared = assertNotNull(facts(tenant, scopeId, memory).banner)
            assertEquals(Banner.Medium.VIDEO, shared.medium)
            assertNotNull(shared.poster, "the medium's PREVIEW as the poster")
            assertEquals(jpeg.size, Banner.picture(tenant, assertNotNull(tenant.node(scopeId, memory)))?.size, "the shared poster reads")

            // 3. the same video into the gallery again: its original shares the banner's object, only its two tiers are stored
            val clipFrames = me.raddatz.veyq.mobile.crypto.V1.chunkCount(webm.size.toLong())
            val beforeA1 = storePuts
            val again = addVideo(tenant, scopeId, memory, "clip again.webm")
            assertEquals(2, storePuts - beforeA1, "not only the two tiers went to the store (the clip is $clipFrames frames)")
            val listed = assertNotNull(MemoryMedia.list(tenant, scopeId, memory)).items
            assertEquals(webm.size.toLong(), listed.first { it.mediaId == again }.original.size, "the shared original reads as the clip")

            // 4. the first medium is the banner's picture: trashed, the banner stays
            val clipItem = listed.first { it.mediaId == clip }
            assertTrue(StoredMatch.isBannerPicture(shared, clipItem), "the trash would not ask")
            assertEquals(GalleryModel.Removal.DONE, MemoryMedia.trash(tenant, scopeId, clip))
            assertEquals(shared.blob.blobId, facts(tenant, scopeId, memory).banner?.blob?.blobId, "the banner went with its medium")
            assertNotNull(Banner.picture(tenant, assertNotNull(tenant.node(scopeId, memory))), "the banner no longer reads")

            // 5. a peer's banner between this one's open and save: stale, the peer's intact
            val openedOnVideo = facts(tenant, scopeId, memory).bannerSlots
            val peer = other.open(other.defaultTenant!!)
            assertEquals(BannerWrite.Outcome.Done, BannerWrite.set(peer, scopeId, memory, facts(peer, scopeId, memory).bannerSlots, BannerWrite.pick(picked(jpeg, "peer.jpg", "image/jpeg"), pictures), emptyList()))
            val peers = assertNotNull(facts(peer, scopeId, memory).banner)
            assertEquals(BannerWrite.Outcome.Stale, BannerWrite.set(tenant, scopeId, memory, openedOnVideo, image, emptyList()))
            assertEquals(peers.blob.blobId, facts(tenant, scopeId, memory).banner?.blob?.blobId, "the peer's banner was overwritten")

            // back to the video (opened on the peer's), for the offline read of a video banner
            val now = assertNotNull(MemoryMedia.list(tenant, scopeId, memory)).items
            assertEquals(BannerWrite.Outcome.Done, BannerWrite.set(tenant, scopeId, memory, facts(tenant, scopeId, memory).bannerSlots, video, now))
            val kept = assertNotNull(facts(tenant, scopeId, memory).banner)

            // 6. pinned, then offline: its picture draws and its video plays from the store
            door.opened(tenant)
            val pins = assertNotNull(account.offlinePins)
            assertTrue(pins.pin(tenant, scopeId, memory))
            waitKept(pins, memory)
            assertTrue(kept.blob.blobId in pins.pins.value.single { it.node == memory }.named[memory].orEmpty(), "the pin did not name the banner")
            account.offlinePins?.stop()
            account.offline?.close()
            reachable = false
            account = VeyqAccount.resume(p, assertNotNull(device.trusted().firstOrNull()), device, client)
            assertIs<Reach.Offline>(account.reach.value)
            val off = account.open(account.defaultTenant!!)
            val node = assertNotNull(off.node(scopeId, memory), "the Memory is not in the kept tree")
            assertEquals(jpeg.size, Banner.picture(off, node)?.size, "the banner's picture does not draw offline")
            val reader = assertNotNull(Banner.reader(off, node))
            assertEquals(webm.size.toLong(), reader.size)
            assertEquals(webm.copyOfRange(0, 64).toList(), reader.read(0, 64).toList(), "the banner's video does not play offline")
            val tail = webm.size - 64L
            assertEquals(webm.copyOfRange(tail.toInt(), webm.size).toList(), reader.read(tail, 64).toList(), "its last frame is not kept")

            // 7. online again: the banner removed, both slots freed
            account.offlinePins?.stop()
            account.offline?.close()
            reachable = true
            account = VeyqAccount.resume(p, assertNotNull(device.trusted().firstOrNull()), device, client)
            tenant = door.open(account, account.defaultTenant!!)
            assertEquals(BannerWrite.Outcome.Done, BannerWrite.remove(tenant, scopeId, memory, facts(tenant, scopeId, memory).bannerSlots))
            assertNull(facts(tenant, scopeId, memory).banner, "the banner is still named")
            assertNotEquals(Banner.Slots.NONE, openedOnVideo)
            assertEquals(Banner.Slots.NONE, facts(tenant, scopeId, memory).bannerSlots)
            println("veyq-live: image banner set, video banner shared with no store PUT, a gallery copy of it storing only its tiers, a peer's banner kept against a stale save, the banner drawn and played offline, removed")
        } finally {
            reachable = true
            runCatching { NodeTrash.delete(tenant, scopeId, memory) }
            runCatching { NodeTrash.purge(tenant, scopeId, memory) }
            runCatching { other.signOut() }
            runCatching { account.retire() }
            val ended = runCatching { VeyqAccount.resume(p, device.trusted().first(), device, client) }.getOrNull()
            runCatching { ended?.signOut() }
            dir.deleteRecursively()
        }
    }

    private suspend fun waitKept(pins: OfflinePins, node: String) = withContext(Dispatchers.Default) {
        withTimeout(3.minutes) { pins.pins.first { p -> p.any { it.node == node && it.state == Pin.State.KEPT } } }
    }
}
