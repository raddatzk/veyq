package me.raddatz.veyq.mobile.memories

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.raddatz.veyq.mobile.api.memories.apis.MediaApi
import me.raddatz.veyq.mobile.media.ByteSource
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.navigation.Home
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.session.NodeTrash
import me.raddatz.veyq.mobile.session.ShareLinks
import me.raddatz.veyq.mobile.session.VeyqAccount
import me.raddatz.veyq.mobile.session.testDevice
import me.raddatz.veyq.mobile.words.Msg
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.test.AfterTest
import me.raddatz.veyq.mobile.session.LiveRun

/**
 * A Memory's create, trash, Undo and links (ADR 0136's Consequences) on a running dev instance (`VEYQ_LIVE`), through the core's models: with
 * `VEYQ_LIVE_MEMORY_GUESTS=<file>` an "App memory <n>" is made at the default space's top by `ShellModel.createMemory`, a
 * chapter in it by `GalleryModel.createChapter`; two photos (the web's EXIF fixture) are uploaded, one trashed and undone
 * (`GalleryModel.trash`/`undo`: back in the rows), the other trashed and purged for good; a WRITE_ONLY link minted (the
 * Memory's drop key set) and a second (the same drop key kept), the first's address written to that file for a web guest to
 * upload through the web's guest page meanwhile (a script reading the file drives it; `VEYQ_LIVE_MEMORY_WAIT_S`, default
 * 300); the guest's photo is read back by `GalleryModel` — its tile opened under the guest's `K_up` through the drop key.
 * The Memory is trashed and purged however it ends. Reported skipped without `VEYQ_LIVE`.
 */
class MemoryActionsLiveTest {
    /** Every device this case signs in with, taken off the account again after it, whatever it came to. */
    private val liveRun = LiveRun()

    @AfterTest
    fun takeOffTheDevices() = liveRun.removeAll()

    @Test
    fun aMemoryIsMadeTrashedSharedAndFilledByAGuest() = runBlocking {
        val origin = System.getenv("VEYQ_LIVE")
        val outPath = System.getenv("VEYQ_LIVE_MEMORY_GUESTS")
        org.junit.Assume.assumeTrue("VEYQ_LIVE and VEYQ_LIVE_MEMORY_GUESTS are not set: a live case", origin != null && outPath != null)
        val out = File(outPath!!).also { it.delete() }
        val wait = (System.getenv("VEYQ_LIVE_MEMORY_WAIT_S")?.toLong() ?: 300L).seconds
        val root = File(System.getProperty("veyq.repoRoot"))
        val env = (System.getenv("VEYQ_ENV_FILE")?.let(::File) ?: File(root, ".run/.env")).readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
        val account = VeyqAccount.signIn(
            BouncyCastlePrimitives, origin!!, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"),
            liveRun.device(),
        ) { block -> HttpClient(OkHttp, block) }
        val tenant = account.open(account.defaultTenant!!)
        val shell = me.raddatz.veyq.mobile.navigation.ShellModel(
            tenant, me.raddatz.veyq.mobile.navigation.Hidden({}, emptySet()), me.raddatz.veyq.mobile.navigation.RecentUses({}, emptyList()),
        ) { System.currentTimeMillis() }
        shell.load(openLastUsed = false)
        val scopeId = assertNotNull(Home.defaultSpace(shell.state.first { it != null }!!.lists.spaces)?.scopeId, "no space")
        val name = "App memory ${System.currentTimeMillis() % 100_000}"
        val made = shell.createMemory(scopeId, null, name)
        val memory = assertNotNull(made.node, "the Memory was not made: ${made.notice}")
        val again = shell.createMemory(scopeId, null, name)
        assertTrue(again.node == null && again.notice != null, "its name is taken now")
        val queue = me.raddatz.veyq.mobile.blob.UploadQueue()
        val uploads = shell.mediaUploads(queue, object : MediaUpload.Deriver {
            override fun derive(source: me.raddatz.veyq.mobile.blob.UploadSource, type: String, done: (MediaUpload.Derived) -> Unit) =
                done(MediaUpload.Derived(null, null, null, null))
        }, left = {}) {}
        val gallery = shell.galleryModel(NodeAt(scopeId, memory.nodeId, "MEMORY", name), uploads)
        try {
            val chapter = gallery.createChapter("Day one")
            assertNotNull(chapter.node, "the chapter was not made: ${chapter.notice}")

            val photo = File(root, "src/web/src/features/memories/__fixtures__/exif-sample.jpg").readBytes()
            val ids = (1..2).map { i ->
                val up = MediaUpload.upload(
                    tenant, scopeId, memory.nodeId,
                    MediaUpload.Original(ByteSource(photo.size.toLong()) { from, length -> photo.copyOfRange(from.toInt(), minOf(photo.size.toLong(), from + length).toInt()) }, "image/jpeg", "App photo $i.jpg", 1_000_000L * i),
                    MediaUpload.Derived(null, null, null, null), System.currentTimeMillis(),
                )
                assertEquals(MediaUpload.Outcome.DONE, up.outcome, up.code)
                up.mediaId!!
            }
            gallery.reload()
            gallery.state.first { s -> s.rows?.map { it.mediaId }?.containsAll(ids) == true }
            assertEquals(listOf(Msg.MEMORY_MOVED_TO_TRASH), listOfNotNull(gallery.trash(ids[0])?.msg))
            assertTrue(gallery.current.rows!!.none { it.mediaId == ids[0] }, "trashed: out of the rows")
            assertNull(gallery.undo(), "the Undo did not restore it")
            gallery.state.first { s -> s.rows?.any { it.mediaId == ids[0] } == true }
            gallery.trash(ids[1])
            val api = MediaApi(tenant.base, tenant.account.http)
            assertTrue(tenant.account.respond { api.purgeMedia(scopeId, ids[1]) }.success, "the trashed photo was not purged")
            gallery.reload()
            gallery.state.first { s -> s.rows?.map { it.mediaId } == listOf(ids[0]) }

            val first = ShareLinks.mint(tenant, scopeId, memory.nodeId, ShareLinks.WRITE_ONLY, "App drop", "", null)
            assertEquals(ShareLinks.Outcome.CREATED, first.outcome, first.refusal)
            tenant.forgetTree()
            val drop = assertNotNull(tenant.trees()[scopeId]?.first { it.id == memory.nodeId }?.sealedDropPrivateKey, "the first link set no drop key")
            val second = ShareLinks.mint(tenant, scopeId, memory.nodeId, ShareLinks.WRITE_ONLY, "", "", null)
            assertEquals(ShareLinks.Outcome.CREATED, second.outcome, second.refusal)
            tenant.forgetTree()
            assertEquals(drop, tenant.trees()[scopeId]?.first { it.id == memory.nodeId }?.sealedDropPrivateKey, "the second link replaced the drop key")
            out.writeText(first.url!! + "\n")
            println("made $name, its WRITE_ONLY link written to $out; waiting for a guest's photo")

            val deadline = System.currentTimeMillis() + wait.inWholeMilliseconds
            var guest: GalleryModel.Row? = null
            while (guest == null && System.currentTimeMillis() < deadline) {
                delay(5_000)
                gallery.reload()
                guest = gallery.current.rows?.firstOrNull { it.item.ownerKey?.startsWith("share:") == true }
            }
            val row = assertNotNull(guest, "no guest's photo came within $wait")
            val tile = row.tile?.let { gallery.tile(row.mediaId) }
            val picture = row.picture?.let { gallery.lightbox(0).picture(row.mediaId) }
            assertTrue(tile != null || picture != null, "the guest's photo did not open under its K_up")
            println("    a guest's ${row.name}: tile ${tile?.size} B, picture ${picture?.size} B, read under the guest's K_up")
        } finally {
            gallery.close()
            uploads.close()
            NodeTrash.delete(tenant, scopeId, memory.nodeId)
            NodeTrash.purge(tenant, scopeId, memory.nodeId)
        }
    }
}
