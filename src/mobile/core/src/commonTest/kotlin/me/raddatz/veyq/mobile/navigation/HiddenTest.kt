package me.raddatz.veyq.mobile.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HiddenTest {
    private var stored: Set<String> = emptySet()
    private fun hidden() = Hidden({ stored = it }, stored)

    private val family = SpaceRef("t", "TENANT", "Familie")
    private val mine = SpaceRef("p", "PERSONAL", "Personal")
    private val spaces = listOf(family, mine)

    @Test
    fun aHiddenSpaceReachesNoListOfTheApp() {
        val h = hidden()
        h.setSpaceHidden("p", true)
        assertEquals(listOf(family), h.visibleSpaces(spaces))
        val nodes = listOf(NodeRef("t", "a", null, "MEMORY", "A"), NodeRef("p", "b", null, "MEMORY", "B"))
        assertEquals(listOf("a"), h.visibleNodes(nodes, spaces).map { it.nodeId })
        val objects = nodes.mapNotNull { it.asObject() }
        assertEquals(listOf("a"), h.visibleObjects(objects, spaces).map { it.nodeId })
    }

    @Test
    fun theLastVisibleSpaceStays() {
        val h = hidden()
        h.setSpaceHidden("p", true)
        assertFalse(h.canHideSpace("t", spaces), "hiding the last visible space is refused")
        assertTrue(h.canHideSpace("p", spaces), "a hidden one can always be shown again")
        h.setSpaceHidden("t", true)
        assertEquals(1, h.visibleSpaces(spaces).size, "even forced, one space stays")
    }

    @Test
    fun aHiddenTenantLeavesTheSwitcher_butTheCurrentOneStays() {
        val h = hidden()
        val a = TenantRef("1", "Familie", "familie.example")
        val b = TenantRef("2", "Verein", "verein.example")
        h.setTenantHidden("srv#u", "2", true)
        assertEquals(listOf(a), h.visibleTenants("srv#u", listOf(a, b), current = a))
        assertEquals(listOf(a, b), h.visibleTenants("srv#u", listOf(a, b), current = b))
        assertEquals(listOf(a, b), h.visibleTenants("other#u", listOf(a, b), current = a), "a tenant is hidden per connection")
    }

    @Test
    fun itPersistsIdsOnly_andComesBack() {
        hidden().setSpaceHidden("p", true)
        assertTrue(stored.all { it.startsWith("space:") || it.startsWith("tenant:") })
        assertTrue(hidden().isSpaceHidden("p"))
    }
}
