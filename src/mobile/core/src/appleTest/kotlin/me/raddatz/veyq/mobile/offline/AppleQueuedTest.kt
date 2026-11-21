package me.raddatz.veyq.mobile.offline

import me.raddatz.veyq.mobile.media.Derivation
import me.raddatz.veyq.mobile.composition.AppleQueued

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.blob.UploadQueue
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.blob.deviceFiles
import me.raddatz.veyq.mobile.memories.MediaUpload
import me.raddatz.veyq.mobile.memories.MemoryMedia
import me.raddatz.veyq.mobile.navigation.Hidden
import me.raddatz.veyq.mobile.navigation.RecentUses
import me.raddatz.veyq.mobile.composition.ShellModel
import me.raddatz.veyq.mobile.session.Reach
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the Swift app reads of the writes made offline ([AppleQueued], ADR 0153 decision 5): the shell's flows held as
 * states by value — the banner's count, the tray's items, the level shown's queued rows —, the reconnect's notices kept
 * until said ([AppleQueued.saidSeen]), and *Save a copy* written into the save panel's path through [FileSink], nothing
 * left there where nothing was written.
 *
 * Bites (each one edit, seen red, then restored): `saidSeen` keeping the notices → the said case red; `saveCopy` answering
 * true without the tray's answer (`runCatching { …; true }`) → the unknown id's case red.
 */
class AppleQueuedTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

    private val played = mutableListOf<PlayedTenant>()

    @AfterTest
    fun cleanUp() = played.forEach { removeAll(it.root) }

    /** [read]'s first non-null answer, the states running on their own thread: at most two seconds. */
    private suspend fun <R : Any> waitFor(read: () -> R?): R? {
        repeat(200) {
            read()?.let { return it }
            withContext(Dispatchers.Default) { delay(10) }
        }
        return null
    }

    private val deriver = object : Derivation.Deriver {
        override fun derive(source: UploadSource, type: String, done: (Derivation.Derived) -> Unit) =
            done(Derivation.Derived("p".encodeToByteArray(), "t".encodeToByteArray(), 4, 3))
    }

    @Test
    fun theShellsWritesMadeOfflineReadByValueAndACopySavedIntoThePanelsPath() = runTest {
        val t = PlayedTenant().also { played += it }
        val s = t.scope("s1")
        val m = t.node(s, "m1", name = "Summer")
        t.medium(m, "x1")
        t.remember()
        val first = t.resume()
        val online = first.open(first.tenants.first())
        assertNotNull(MemoryMedia.list(online, s.id, m.id))
        ShellModel(online, Hidden({}, emptySet()), RecentUses({}, emptyList())) { 0L }.load(openLastUsed = false)
        first.retire()
        t.reachable = false
        val a = t.resume()
        assertIs<Reach.Offline>(a.reach.value)
        val shell = ShellModel(a.open(a.tenants.first()), Hidden({}, emptySet()), RecentUses({}, emptyList())) { 0L }
        shell.load(openLastUsed = false)
        shell.switchSpace(s.id)
        val q = AppleQueued(shell)
        try {
            assertNull(q.banner.value)
            assertTrue(shell.createFolder(s.id, null, "Trip").queued)
            val photo = "PNG-APPLE-PLAINTEXT"
            assertEquals(1, shell.mediaUploads(UploadQueue(), deriver, {}, {}).queue(s.id, m.id, listOf(
                UploadSource.of("dune.png", photo.length.toLong(), "image/png", null) { from, length -> photo.encodeToByteArray().copyOfRange(from.toInt(), from.toInt() + length) },
            )))
            assertEquals(Notice(Msg.OFFLINE_BANNER_WAITING, 2), waitFor { q.banner.value?.takeIf { it.args == listOf(2) } }, "the banner's count is not read by value")
            assertEquals(listOf(Queued.FOLDER_CREATE, Queued.MEDIA_ADD), waitFor { q.items.value.takeIf { it.size == 2 } }?.map { it.op })
            assertEquals(listOf(Notice(Msg.QUEUED_FOLDER, "Trip")), waitFor { q.here.value.takeIf { it.isNotEmpty() } }?.map { it.title })

            // a refused write's file saved into the panel's path; an unknown write leaves nothing there
            val box = a.outbox!!
            box.update(box.writes()[1].copy(state = QueuedWrite.State.REFUSED, reason = "quota_exceeded"))
            val refused = q.tray.list()[1]
            val path = "${t.root}/saved-copy.png"
            assertTrue(q.saveCopy(refused.id, 0, path))
            assertContentEquals(photo.encodeToByteArray(), deviceFiles.read(path))
            val nothing = "${t.root}/nothing.png"
            assertFalse(q.saveCopy("no-such-write", 0, nothing))
            assertNull(deviceFiles.read(nothing), "a copy that was not written left a file")
            assertTrue(q.tray.discard(refused.id))

            // the reconnect's notices held until the app said them
            t.route = { r ->
                if (r.method == io.ktor.http.HttpMethod.Post && r.url.encodedPath.matches(Regex("/api/scopes/[^/]+/nodes"))) {
                    val body = (r.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    val id = Regex("\"nodeId\"\\s*:\\s*\"([^\"]+)\"").find(body)!!.groupValues[1]
                    201 to """{"nodeId":"$id"}"""
                } else null
            }
            t.reachable = true
            assertTrue(a.reconnect())
            a.awaitSending()
            assertEquals(listOf(Notice(Msg.OFFLINE_SENT, 1)), waitFor { q.said.value.takeIf { it.isNotEmpty() } })
            q.saidSeen()
            assertEquals(emptyList(), q.said.value, "the notices said are said again")
            assertTrue(waitFor { true.takeIf { q.banner.value == null } } == true, "the banner still counts what was sent")
        } finally {
            q.close()
            a.retire()
        }
    }
}
