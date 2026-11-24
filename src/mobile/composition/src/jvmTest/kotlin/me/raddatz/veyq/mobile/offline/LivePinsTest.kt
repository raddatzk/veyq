package me.raddatz.veyq.mobile.offline

import me.raddatz.veyq.mobile.memories.createMemory
import me.raddatz.veyq.mobile.media.Derivation
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.api.createClientPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.blob.ApplicationBlobs
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.media.ByteSource
import me.raddatz.veyq.mobile.memories.MediaUpload
import me.raddatz.veyq.mobile.memories.MemoryMedia
import me.raddatz.veyq.mobile.navigation.Hidden
import me.raddatz.veyq.mobile.navigation.Home
import me.raddatz.veyq.mobile.navigation.RecentUses
import me.raddatz.veyq.mobile.navigation.ShellModel
import me.raddatz.veyq.mobile.session.AccountDoor
import me.raddatz.veyq.mobile.session.FolderCreate
import me.raddatz.veyq.mobile.session.LiveRun
import me.raddatz.veyq.mobile.session.NodeCreate
import me.raddatz.veyq.mobile.session.NodeTrash
import me.raddatz.veyq.mobile.session.Reach
import me.raddatz.veyq.mobile.session.ShareLinks
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import me.raddatz.veyq.mobile.session.LiveHttp

/**
 * A pinned Memory kept and synced against a running dev instance (ADR 0140 decision 9; `VEYQ_LIVE`, skipped without one).
 *
 * A second session of the dev principal (a device of this test's own, remembered at LOGIN, its store in a temporary
 * directory) makes a folder with a Memory of one photo in its default space and pins the Memory through the session's door,
 * as every app opens a tenant: its size first, then the sync the door started fetches it. A third session — another
 * device — adds a photo; this account is closed and opened again online, and the sync the open starts keeps the new photo.
 * Then the folder's subtree is re-keyed (a link on it minted and switched off, `ShareLinks.switchOff` with the build's
 * kinds: the folder gets a fresh key, the Memory's entry is sealed under it anew, the tree's revision moves; the Memory's
 * own re-key moves wraps and sealed fields and keeps every `K_blob`, ADR 0149 decision 2) and the next sync fetches no frame: every pinned frame counted before and
 * after is in place. Last, the account is closed, the network cut and the account unlocked again: the Memory opens from
 * the store with both photos, no request having left.
 *
 * The folder is trashed and purged and every device taken off the account however the case ends; the store is wiped.
 */
class LivePinsTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

    private val liveRun = LiveRun()

    @AfterTest
    fun takeOffTheDevices() = liveRun.removeAll()

    @Volatile private var reachable = true
    @Volatile private var sentWhileCut = 0

    private val cut = createClientPlugin("CutNetwork") {
        onRequest { _, _ ->
            if (!reachable) {
                sentWhileCut++
                throw java.io.IOException("the network is cut")
            }
        }
    }

    private val client: (HttpClientConfig<*>.() -> Unit) -> HttpClient = { block -> LiveHttp.client { block(); install(cut) } }

    private suspend fun waitKept(pins: OfflinePins, node: String) = withContext(Dispatchers.Default) {
        withTimeout(3.minutes) { pins.pins.first { p -> p.any { it.node == node && it.state == Pin.State.KEPT } } }
    }

    /** Every blob whose frames the store holds, with the frame indexes held. */
    private suspend fun AccountOffline.frames(): Map<String, Set<Int>> =
        store.entries().filter { it.key.kind == KeptRead.FRAMES }.associate { it.key.id to store.frames(it.key) }

    private fun photo(): ByteArray = File(System.getProperty("veyq.repoRoot"), "src/web/src/features/memories/__fixtures__/exif-sample.jpg").readBytes()

    private suspend fun upload(tenant: TenantSession, scopeId: String, nodeId: String, name: String, at: Long): String {
        val bytes = photo()
        val up = MediaUpload.upload(
            tenant, scopeId, nodeId,
            MediaUpload.Original(ByteSource(bytes.size.toLong()) { from, length -> bytes.copyOfRange(from.toInt(), minOf(bytes.size.toLong(), from + length).toInt()) }, "image/jpeg", name, at),
            Derivation.Derived(bytes, bytes, null, null), System.currentTimeMillis(),
        )
        assertEquals(MediaUpload.Outcome.DONE, up.outcome, up.code)
        return up.mediaId!!
    }

    /** The app's process ends: nothing is logged out or wiped (a retire would log the session out), the store is let go. */
    private suspend fun processEnd(a: VeyqAccount) {
        a.offlinePins?.stop()
        a.offline?.close()
    }

    @Test
    fun aPinnedMemoryIsKeptSyncedAfterANewMediumAndKeepsItsFramesThroughAReKey() = runBlocking {
        val origin = liveRun.origin
        org.junit.Assume.assumeTrue("VEYQ_LIVE is not set: a live case", origin != null)
        val p = BouncyCastlePrimitives
        val env = liveRun.env
        val root = Files.createTempDirectory("veyq-pins-live").toFile()
        val device = ThisDevice(liveRun.store(), SoftwareDeviceKeys(p), "live pins test", p, offlineRoot = root.absolutePath).also { it.offlineFiles = me.raddatz.veyq.mobile.offline.SizedVolume() }
        val door = AccountDoor(p, device, client)
        var account = VeyqAccount.signIn(p, origin!!, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"), device, client)
        assertEquals(VeyqAccount.TrustOutcome.REMEMBERED, account.trustThisDevice(null, StaySignedIn.LOGIN), "the dev instance does not let a device stay signed in")
        var tenant = door.open(account, account.defaultTenant!!)
        val shell = ShellModel(tenant, Hidden({}, emptySet()), RecentUses({}, emptyList())) { System.currentTimeMillis() }
        shell.load(openLastUsed = false)
        val scopeId = assertNotNull(Home.defaultSpace(shell.state.first { it != null }!!.lists.spaces)?.scopeId, "no space")
        val n = System.currentTimeMillis() % 100_000
        val folder = (FolderCreate.create(tenant, scopeId, null, "Offline pins $n") as NodeCreate.Result.Done).created.nodeId
        tenant.forgetTree()
        val made = shell.createMemory(scopeId, folder, "Pinned memory $n")
        val memory = assertNotNull(made.node, "the Memory was not made: ${made.notice}").nodeId
        val other = VeyqAccount.signIn(p, origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"), liveRun.device("live pins second session"), client)
        try {
            upload(tenant, scopeId, memory, "Pinned photo 1.jpg", 1_000_000L)
            tenant.forgetTree()

            // pinned through the door, as an app pins: the size first, then the sync the door started fetches it
            door.opened(tenant)
            val pins = assertNotNull(account.offlinePins, "a remembered account on a device with a store directory keeps no pins")
            assertTrue(pins.pinnable("MEMORY"))
            val plan = assertNotNull(pins.plan(tenant, scopeId, memory))
            assertEquals(2, plan.blobs, "a photo's pin keeps its thumbnail and its preview")
            assertTrue(plan.bytes > 0)
            assertTrue(pins.pin(tenant, scopeId, memory))
            waitKept(pins, memory)
            val firstFrames = assertNotNull(account.offline).frames()
            assertEquals(2, firstFrames.size)

            // another session adds a photo; the next online open keeps it
            val otherTenant = other.open(other.defaultTenant!!)
            val later = upload(otherTenant, scopeId, memory, "Pinned photo 2.jpg", 2_000_000L)
            val laterBlobs = assertNotNull(MemoryMedia.list(otherTenant, scopeId, memory)).items.first { it.mediaId == later }.let { listOfNotNull(it.thumbnail?.blobId, it.preview?.blobId) }
            processEnd(account)
            account = VeyqAccount.resume(p, assertNotNull(device.trusted().firstOrNull()), device, client)
            assertEquals(Reach.Online, account.reach.value)
            tenant = door.open(account, account.defaultTenant!!)
            door.opened(tenant)
            val synced = assertNotNull(account.offlinePins)
            withContext(Dispatchers.Default) {
                withTimeout(3.minutes) {
                    while (!assertNotNull(account.offline).frames().keys.containsAll(laterBlobs) || synced.progress.value.isNotEmpty()) kotlinx.coroutines.delay(250)
                }
            }
            val before = assertNotNull(account.offline).frames()
            assertEquals(4, before.size, "not both photos' tiles and previews are kept")

            // the folder's subtree re-keyed: the tree's revision moves, no frame is fetched again
            val rules = AppKinds.rules
            tenant.rekeyRules = rules
            val minted = ShareLinks.mint(tenant, scopeId, folder, ShareLinks.READ, "Pins", "", null)
            assertEquals(ShareLinks.Outcome.CREATED, minted.outcome, minted.refusal)
            val shareId = minted.url!!.substringAfter("/s/").substringBefore('#')
            val o = assertNotNull(account.offline)
            val treeKey = o.key(KeptRead.NODES, tenant.tenant.tenantId, scopeId, scopeId)
            val revision = o.store.entry(treeKey)?.revision
            // the folder re-keyed here, and the Memory below it (ADR 0149 decision 2: its blobs and their K_blobs are kept)
            val closed = ShareLinks.switchOff(tenant, scopeId, folder, shareId, rules.kinds, rules.tokenInput) { java.util.UUID.randomUUID().toString() }
            assertTrue(closed == ShareLinks.Closed.CLOSED || closed == ShareLinks.Closed.CLOSED_REKEY_PENDING, "the link did not close: $closed")
            val fetched = ApplicationBlobs.fetchedFrames
            tenant.forgetTree()
            synced.sync()
            assertTrue(o.store.entry(treeKey)?.revision != revision, "the re-key did not move the tree's revision")
            assertEquals(before, assertNotNull(account.offline).frames(), "the re-key changed the pinned frames")
            assertEquals(fetched, ApplicationBlobs.fetchedFrames, "the re-key's sync fetched frames again")
            assertEquals(Pin.State.KEPT, synced.pins.value.single { it.node == memory }.state)

            // the app goes, the network is cut, the account unlocked again: the Memory opens from the store
            processEnd(account)
            reachable = false
            account = VeyqAccount.resume(p, assertNotNull(device.trusted().firstOrNull()), device, client)
            assertIs<Reach.Offline>(account.reach.value)
            val offlineTenant = account.open(account.defaultTenant!!)
            val kept = assertNotNull(MemoryMedia.list(offlineTenant, scopeId, memory), "the Memory is not in the kept tree")
            assertNotNull(kept.offline)
            assertEquals(2, kept.items.size, "not both photos open offline")
            for (item in kept.items) assertNotNull(MemoryMedia.read(offlineTenant, item, assertNotNull(item.thumbnail)))
            println("veyq-live: pinned Memory kept (${before.size} blobs, ${before.values.sumOf { it.size }} frames), synced after a new photo, re-keyed folder with no frame fetched again, opened offline; $sentWhileCut requests refused while cut")
        } finally {
            reachable = true
            runCatching { account.retire() }
            val otherTenant = runCatching { other.open(other.defaultTenant!!) }.getOrNull()
            if (otherTenant != null) {
                runCatching { NodeTrash.delete(otherTenant, scopeId, folder) }
                runCatching { NodeTrash.purge(otherTenant, scopeId, folder) }
            }
            runCatching { other.signOut() }
            val ended = runCatching { VeyqAccount.resume(p, device.trusted().first(), device, client) }.getOrNull()
            runCatching { ended?.signOut() }
            assertTrue(root.listFiles().orEmpty().isEmpty(), "the store outlived the sign-out: ${root.listFiles()?.map { it.name }}")
            root.deleteRecursively()
        }
    }
}
