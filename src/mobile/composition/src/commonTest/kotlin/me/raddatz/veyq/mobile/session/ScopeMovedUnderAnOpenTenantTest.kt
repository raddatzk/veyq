package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.offline.PlayedTenant
import me.raddatz.veyq.mobile.offline.removeAll
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Another device gives a space a new lock while this one's tenant stays open (inbox #1021): the rotation re-seals every
 * scope-root entry under the new key and re-grants the account, so the key the tenant opened with opens no root any more.
 * The next read of the tree reads the scope list anew and opens the moved grant — verified, its succession from the held
 * key checked — as the web re-opens a scope at every entry (`openScopeById`) and at a write's pull (`refreshActiveScope`);
 * a re-key that finds the scope ahead of the held key opens it anew too, rather than leaving the write to wait.
 */
class ScopeMovedUnderAnOpenTenantTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

    private val played = mutableListOf<PlayedTenant>()

    @AfterTest
    fun cleanUp() = played.forEach { removeAll(it.root) }

    @Test
    fun aSpaceGivenANewLockElsewhereIsReadUnderItsNewKey_withoutTheTenantOpenedAnew() = runTest {
        val t = PlayedTenant().also { played += it }
        val s = t.scope("s1")
        t.node(s, "memory", name = "Summer")
        t.remember()
        val account = t.resume()
        val tenant = account.open(account.tenants.first())
        assertEquals(listOf("Summer"), tenant.nodes().map { it.name })
        val told = tenant.rekeyed.value

        t.relock(s)
        tenant.forgetTree() // what the live poll's moved revision does
        assertEquals(listOf("Summer"), tenant.nodes().map { it.name }, "the space read empty under the key from before its new lock")
        val (key, epoch) = assertNotNull(tenant.scopeKey(s.id))
        assertEquals(1, epoch)
        assertContentEquals(s.key, key)
        assertEquals(listOf(s.id), tenant.rekeyingScopes, "the new lock's due nodes are this session's to resume")
        assertTrue(tenant.rekeyed.value > told, "the models over the session were not told the key changed")

        // the control: a read with nothing moved keeps the key it holds and tells no one
        val after = tenant.rekeyed.value
        tenant.forgetTree()
        assertEquals(listOf("Summer"), tenant.nodes().map { it.name })
        assertSame(key, tenant.scopeKey(s.id)?.first)
        assertEquals(after, tenant.rekeyed.value)
        account.retire()
    }

    @Test
    fun aNewKeyTheHeldOneDoesNotLeadToStaysClosed() = runTest {
        val t = PlayedTenant().also { played += it }
        val s = t.scope("s1")
        t.node(s, "memory", name = "Summer")
        t.remember()
        val account = t.resume()
        val tenant = account.open(account.tenants.first())
        assertEquals(listOf("Summer"), tenant.nodes().map { it.name })

        t.relock(s)
        s.successions.clear() // a server serving a key no statement links to the held one
        tenant.forgetTree()
        assertEquals(emptyList(), tenant.nodes().map { it.name })
        assertEquals(GrantOutcome.Reason.EPOCH_UNLINKED, tenant.closed[s.id])
        assertEquals(null, tenant.scopeKey(s.id))
        account.retire()
    }

    @Test
    fun aWritesPullThatMeetsTheScopeAheadOpensItAnew_ratherThanWaiting() = runTest {
        val t = PlayedTenant().also { played += it }
        val s = t.scope("s1")
        val m = t.node(s, "memory", name = "Summer")
        t.remember()
        val account = t.resume()
        val tenant = account.open(account.tenants.first())
        tenant.rekeyRules = NodeRekey.Rules({ null }) { _, _ -> null }
        assertEquals(listOf("Summer"), tenant.nodes().map { it.name })

        t.relock(s)
        // the path is re-keyed already: what is left is that this device holds the scope at the epoch before
        t.route = { r -> if (r.url.encodedPath == "/api/scopes/${s.id}/rekey/nodes") 200 to """{"currentEpoch":${s.epoch},"nodes":[]}""" else null }
        assertTrue(NodeRekey.pull(tenant, s.id, listOf(m.id)), "the pull left the write to wait behind a key this device can open")
        assertEquals(1, tenant.scopeKey(s.id)?.second)
        assertFalse(tenant.closed.containsKey(s.id))
        account.retire()
    }
}
