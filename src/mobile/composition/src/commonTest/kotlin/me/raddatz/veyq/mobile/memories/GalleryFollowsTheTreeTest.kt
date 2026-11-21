package me.raddatz.veyq.mobile.memories

import me.raddatz.veyq.mobile.media.Derivation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.blob.UploadQueue
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.navigation.Hidden
import me.raddatz.veyq.mobile.navigation.RecentUses
import me.raddatz.veyq.mobile.composition.ShellModel
import me.raddatz.veyq.mobile.offline.PlayedTenant
import me.raddatz.veyq.mobile.offline.removeAll
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * An open Memory's page follows a peer's change of the node's meta — participants, date, place, banner — through its live
 * poll (inbox #953), as the web's open node follows it through the level poll (`useLevelLive.ts`, ADR 0081): each tick asks
 * whether the scope's tree moved with the revision the last tree read was answered with (`listNodes`, `If-None-Match`), and
 * only where it moved are the shell's lists read anew, which the page follows. A tree that did not move costs a `304` and
 * keeps the lists as they were (the control: the follow is not a re-read on every tick).
 */
class GalleryFollowsTheTreeTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

    private val played = mutableListOf<PlayedTenant>()

    @AfterTest
    fun cleanUp() = played.forEach { removeAll(it.root) }

    private val a = "aaaaaaaa-0000-4000-8000-000000000001"
    private val b = "bbbbbbbb-0000-4000-8000-000000000002"

    @Test
    fun anOpenMemorysPageFollowsAPeersParticipantsChangeThroughItsLivePoll() = runTest {
        val t = PlayedTenant().also { played += it }
        val s = t.scope("s1")
        val m = t.node(s, "memory", name = "Summer")
        t.participants(m, a)
        t.remember()
        val account = t.resume()
        val tenant = account.open(account.tenants.first())
        val shell = ShellModel(tenant, Hidden({}, emptySet()), RecentUses({}, emptyList())) { 0L }
        shell.load(openLastUsed = false)
        val uploads = shell.mediaUploads(UploadQueue(), object : Derivation.Deriver {
            override fun derive(source: UploadSource, type: String, done: (Derivation.Derived) -> Unit) = Unit
        }, {}, {})
        val gallery = shell.galleryModel(assertNotNull(shell.galleryAt(s.id, m.id)), uploads)
        assertEquals(listOf(a), waitFor { gallery.current.facts?.participants?.takeIf { it.isNotEmpty() } })
        val live = backgroundScope.launch { gallery.live() }

        // nothing moved: the ticks ask the tree, and a 304 keeps the shell's lists as they were
        val lists = shell.current!!.lists
        val nodes = "/api/scopes/${s.id}/nodes"
        // the played server appends to its request log on its own thread: a count that met a write is read again
        fun askedNow(): Int? = runCatching { t.requests.toList().count { it == nodes } }.getOrNull()
        val asked = assertNotNull(waitFor { askedNow() })
        assertNotNull(waitFor { askedNow()?.takeIf { it >= asked + 2 } }, "the live poll asks the scope's tree")
        assertSame(lists, shell.current!!.lists, "a tree that did not move is not read anew")

        // a peer's change of the participants moves the tree: the page follows without a refresh by hand
        t.participants(m, a, b)
        assertEquals(listOf(a, b), waitFor { gallery.current.facts?.participants?.takeIf { it.size == 2 } })
        live.cancel()
        gallery.close()
        account.retire()
    }

    /** [read]'s first non-null answer, the models' watches running on their own threads: at most five seconds. */
    private suspend fun <R : Any> waitFor(read: () -> R?): R? {
        repeat(500) {
            read()?.let { return it }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { kotlinx.coroutines.delay(10) }
        }
        return read()
    }
}
