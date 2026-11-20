package me.raddatz.veyq.mobile.offline

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.blob.UploadQueue
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.expenses.PotModel
import me.raddatz.veyq.mobile.expenses.PotSession
import me.raddatz.veyq.mobile.memories.MediaUpload
import me.raddatz.veyq.mobile.memories.MemoryMedia
import me.raddatz.veyq.mobile.navigation.ApplicationId
import me.raddatz.veyq.mobile.navigation.Hidden
import me.raddatz.veyq.mobile.navigation.ObjectRef
import me.raddatz.veyq.mobile.navigation.RecentUses
import me.raddatz.veyq.mobile.navigation.ShellModel
import me.raddatz.veyq.mobile.session.OfflineAsOf
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.session.VeyqAccount
import me.raddatz.veyq.mobile.words.Moment
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * The shell's offline banner — the one banner every platform draws (ADR 0140 decision 10) — dates an open page by that
 * page's own kept answer where it has one (a Pot's ledger, a gallery's listing), else by the shell's lists: a Pot whose
 * ledger was kept at 12:27 said 12:29, the lists' time (the Android pass of cr-app-offline-pots-and-space-media).
 *
 * Bite (seen red, then restored): `ShellModel.State.banner` without the open page's time → both pages' banners name the
 * lists' time.
 */
class OfflineBannerTest {
    private val played = mutableListOf<PlayedTenant>()

    @AfterTest
    fun cleanUp() = played.forEach { removeAll(it.root) }

    private var now = LEDGER_AT

    private suspend fun tenantOf(a: VeyqAccount): TenantSession = a.open(a.tenants.first())

    private fun shellOf(tenant: TenantSession) = ShellModel(tenant, Hidden({}, emptySet()), RecentUses({}, emptyList())) { 0L }

    /** [read]'s first non-null answer, the models' watches running on their own thread: at most two seconds. */
    private suspend fun <R : Any> waitFor(read: () -> R?): R? {
        repeat(200) {
            read()?.let { return it }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { kotlinx.coroutines.delay(10) }
        }
        return read()
    }

    @Test
    fun the_words() {
        assertEquals(Notice(Msg.OFFLINE_BANNER, Moment(7)), OfflineShown.banner(OfflineAsOf(42), page = 7))
        assertEquals(Notice(Msg.OFFLINE_BANNER, Moment(42)), OfflineShown.banner(OfflineAsOf(42), page = null))
        assertEquals(Notice(Msg.OFFLINE_BANNER, Moment(7)), OfflineShown.banner(OfflineAsOf(null), page = 7))
        assertEquals(null, OfflineShown.banner(null, page = 7), "online no banner, whatever a page said")
    }

    @Test
    fun an_open_pot_offline_is_dated_by_its_own_kept_ledger_and_home_by_the_lists() = runTest {
        val t = PlayedTenant(clock = { now }).also { played += it }
        val s = t.scope("s1")
        val p = t.pot(s, "p1", name = "Trip")
        t.remember()
        val ref = ObjectRef(s.id, p.id, PotSession.KIND, ApplicationId.EXPENSES, "Trip")
        // the Pot opened online at the ledger's time; the lists read again later
        val first = t.resume()
        PotModel(tenantOf(first), { 0L }).open(ref)
        first.retire()
        now = LISTS_AT
        val second = t.resume()
        tenantOf(second).forgetTree()
        shellOf(tenantOf(second)).load(openLastUsed = false)
        second.retire()

        t.reachable = false
        val a = t.resume()
        val tenant = tenantOf(a)
        val shell = shellOf(tenant)
        shell.load(openLastUsed = false)
        val lists = assertNotNull(shell.current?.offline?.asOf)
        val pots = PotModel(tenant, { 0L })
        pots.open(ref)
        val ledger = assertNotNull(pots.current?.offline?.asOf, "the Pot opened from the store")
        assertNotEquals(lists, ledger, "the ledger and the lists must be kept at different times for this test")
        assertEquals(Notice(Msg.OFFLINE_BANNER, Moment(lists)), shell.current?.banner, "Home is dated by the lists")

        shell.open(ref)
        assertEquals(
            Notice(Msg.OFFLINE_BANNER, Moment(ledger)),
            waitFor { shell.current?.banner?.takeIf { it == Notice(Msg.OFFLINE_BANNER, Moment(ledger)) } } ?: shell.current?.banner,
            "the open Pot is dated by the lists, not its own kept ledger",
        )
        // the Pot closed: the lists' time again
        pots.close()
        assertEquals(
            Notice(Msg.OFFLINE_BANNER, Moment(lists)),
            waitFor { shell.current?.banner?.takeIf { it == Notice(Msg.OFFLINE_BANNER, Moment(lists)) } } ?: shell.current?.banner,
        )
        a.retire()
    }

    @Test
    fun an_open_memory_offline_is_dated_by_its_own_kept_listing() = runTest {
        val t = PlayedTenant(clock = { now }).also { played += it }
        val s = t.scope("s1")
        val m = t.node(s, "m1", name = "Summer")
        t.medium(m, "x1")
        t.remember()
        val first = t.resume()
        assertNotNull(MemoryMedia.list(tenantOf(first), s.id, m.id))
        first.retire()
        now = LISTS_AT
        val second = t.resume()
        shellOf(tenantOf(second)).load(openLastUsed = false)
        second.retire()

        t.reachable = false
        val a = t.resume()
        val tenant = tenantOf(a)
        val shell = shellOf(tenant)
        shell.load(openLastUsed = false)
        val lists = assertNotNull(shell.current?.offline?.asOf)
        val uploads = shell.mediaUploads(UploadQueue(), object : MediaUpload.Deriver {
            override fun derive(source: UploadSource, type: String, done: (MediaUpload.Derived) -> Unit) = Unit
        }, {}, {})
        val gallery = shell.galleryModel(assertNotNull(shell.galleryAt(s.id, m.id)), uploads)
        val listing = assertNotNull(waitFor { gallery.current.offline?.asOf }, "the Memory opened from the store")
        assertNotEquals(lists, listing, "the listing and the lists must be kept at different times for this test")
        shell.open(assertNotNull(shell.current?.lists?.objects?.firstOrNull { it.nodeId == m.id }))
        assertEquals(
            Notice(Msg.OFFLINE_BANNER, Moment(listing)),
            waitFor { shell.current?.banner?.takeIf { it == Notice(Msg.OFFLINE_BANNER, Moment(listing)) } } ?: shell.current?.banner,
            "the open Memory is dated by the lists, not its own kept listing",
        )
        // the page went: the lists' time
        gallery.close()
        assertEquals(Notice(Msg.OFFLINE_BANNER, Moment(lists)), waitFor { shell.current?.banner?.takeIf { it == Notice(Msg.OFFLINE_BANNER, Moment(lists)) } } ?: shell.current?.banner)
        a.retire()
    }

    private companion object {
        const val LEDGER_AT = 1_000_000L
        const val LISTS_AT = 2_000_000L
    }
}
