package me.raddatz.veyq.mobile.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class ConnectionsTest {
    private class Fake(override val key: String, override val tenants: List<TenantRef>) : Connection {
        override val label: String get() = key
    }

    private val family = TenantRef("t1", "Familie", "familie.example")
    private val club = TenantRef("t2", "Verein", "verein.example")
    private val work = TenantRef("t3", "Arbeit", "veyq.work.example")

    @Test
    fun oneServerManyTenants_anotherServerItsOwn() {
        val c = Connections<Fake>()
        val home = Fake("example#u1", listOf(family, club))
        val job = Fake("work.example#u9", listOf(work))
        c.add(home, family)
        c.add(job, work)
        assertEquals(2, c.all.size)
        assertEquals(job to work, c.current)
        c.switchTo(home, club)
        assertEquals(home to club, c.current)
    }

    @Test
    fun signingInTwiceToTheSameAccountKeepsTheFirst() {
        val c = Connections<Fake>()
        val first = Fake("example#u1", listOf(family, club))
        c.add(first, family)
        val again = Fake("example#u1", listOf(family, club))
        val added = c.add(again, club)
        assertSame(first, added.connection)
        assertSame(again, added.duplicate, "the duplicate goes back to the caller to close")
        assertEquals(1, c.all.size)
        assertEquals(first to club, c.current)
    }

    @Test
    fun removingTheCurrentServerFallsBackToAnother_orToNone() {
        val c = Connections<Fake>()
        val home = Fake("example#u1", listOf(family))
        val job = Fake("work.example#u9", listOf(work))
        c.add(home, family)
        c.add(job, work)
        c.remove(job)
        assertEquals(home to family, c.current)
        c.remove(home)
        assertNull(c.current)
    }

    @Test
    fun aTenantOfAnotherServerIsRefused() {
        val c = Connections<Fake>()
        val home = Fake("example#u1", listOf(family))
        c.add(home, family)
        assertFailsWith<IllegalArgumentException> { c.switchTo(home, work) }
    }
}
