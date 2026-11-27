package me.raddatz.veyq.mobile.navigation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.raddatz.veyq.mobile.offline.PlayedTenant
import me.raddatz.veyq.mobile.offline.everyFile
import me.raddatz.veyq.mobile.offline.removeAll
import me.raddatz.veyq.mobile.session.VeyqAccount
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The drawer's Recent is each account's (ADR 0122 decision 6, inbox #1423), over a server the test plays with real seals:
 * kept sealed in the account's own store per tenant, never shown to another account on the same device, kept by "Remove
 * all offline content", and nothing of it in clear on disk.
 * Bites (each made red once, then restored): `VeyqAccount.recentUses` handing every account one shared list (the device-wide
 * list of before) → [one_accounts_recent_is_never_another_accounts_on_the_same_device]; `keepRecent` writing its lines beside
 * the store in clear → [nothing_of_recent_is_in_clear_on_disk]; `removeAll` dropping the `recent` kind →
 * [recent_survives_a_restart_and_remove_all_offline_content].
 */
class RecentTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

    private val played = mutableListOf<PlayedTenant>()

    @AfterTest
    fun cleanUp() = played.distinctBy { it.root }.forEach { removeAll(it.root) }

    private var clock = 1_000L

    private suspend fun shellOf(a: VeyqAccount) = ShellModel(a.open(a.tenants.first()), Hidden({}, emptySet())) { clock++ }

    private class Tree(val t: PlayedTenant, val scope: PlayedTenant.Scope, val memory: PlayedTenant.Node)

    private suspend fun tree(on: PlayedTenant? = null, userId: String = "00000000-0000-4000-8000-000000000001"): Tree {
        val t = PlayedTenant(userId = userId, on = on).also { played += it }
        val s = t.scope("s1")
        val f = t.node(s, "f", kind = NodeRef.FOLDER, name = "Trips")
        val m = t.node(s, "m", kind = "MEMORY", parent = f, name = "Rome")
        t.remember()
        return Tree(t, s, m)
    }

    private fun ShellModel.recentIds() = assertNotNull(current).lists.uses.map { it.ref.nodeId }

    /** Waits (in real time: the write runs on the account's own thread) until [a]'s store keeps Recent [ids] in tenant t1. */
    private suspend fun kept(a: VeyqAccount, ids: List<String>) = withContext(Dispatchers.Default) {
        withTimeout(5_000) { while (assertNotNull(a.offline).recent("t1").map { it.nodeId } != ids) delay(10) }
    }

    @Test
    fun one_accounts_recent_is_never_another_accounts_on_the_same_device() = runTest {
        val one = tree()
        val a = one.t.resume()
        val first = shellOf(a)
        first.load(openLastUsed = false)
        first.openFound(one.scope.id, one.memory.id)
        assertEquals(listOf(one.memory.id), first.recentIds())
        // a second account on this device, in the same tenant, seeing the very Memory the first opened
        val two = tree(on = one.t, userId = "00000000-0000-4000-8000-000000000002")
        assertEquals(one.memory.id, two.memory.id)
        val b = two.t.resume()
        val other = shellOf(b)
        other.load(openLastUsed = false)
        assertTrue(assertNotNull(other.current).lists.objects.any { it.nodeId == one.memory.id }, "the second account lists the Memory")
        assertEquals(emptyList(), other.recentIds(), "the second account's Recent shows the first account's Memory")
        // the first account's own still there for it, kept in its own store only
        val again = shellOf(a)
        again.load(openLastUsed = false)
        assertEquals(listOf(one.memory.id), again.recentIds())
        kept(a, listOf(one.memory.id))
        assertEquals(emptyList(), assertNotNull(b.offline).recent("t1"))
    }

    @Test
    fun recent_survives_a_restart_and_remove_all_offline_content() = runTest {
        val x = tree()
        val a = x.t.resume()
        val shell = shellOf(a)
        shell.load(openLastUsed = false)
        shell.openFound(x.scope.id, x.memory.id)
        kept(a, listOf(x.memory.id))
        assertNotNull(a.offline).removeAll()
        // a restart: another connection of the same account reads its store
        a.retire()
        val again = x.t.resume()
        val next = shellOf(again)
        next.load(openLastUsed = false)
        assertEquals(listOf(x.memory.id), next.recentIds())
    }

    @Test
    fun nothing_of_recent_is_in_clear_on_disk() = runTest {
        val x = tree()
        val a = x.t.resume()
        val shell = shellOf(a)
        shell.load(openLastUsed = false)
        shell.openFound(x.scope.id, x.memory.id)
        kept(a, listOf(x.memory.id))
        a.offline?.store?.flush()
        val files = everyFile(x.t.root)
        assertTrue(files.isNotEmpty())
        for ((path, bytes) in files) {
            val text = bytes.decodeToString()
            for (id in listOf(x.scope.id, x.memory.id)) {
                assertTrue(id !in path && id !in text, "id ${x.t.nameOf(id)} in clear in $path")
            }
        }
    }
}
