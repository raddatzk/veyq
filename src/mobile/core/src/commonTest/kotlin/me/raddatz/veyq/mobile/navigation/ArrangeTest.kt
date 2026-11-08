package me.raddatz.veyq.mobile.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

class ArrangeTest {
    private fun ref(id: String, name: String = id) = ObjectRef("s1", id, "POT", ApplicationId.EXPENSES, name)

    @Test
    fun favoritesThenRecentThenAll_eachByLastUse_eachObjectOnce() {
        val a = ref("a"); val b = ref("b"); val c = ref("c"); val d = ref("d"); val e = ref("e")
        val list = arrange(
            favorites = listOf(a, b),
            uses = listOf(Use(a, 10), Use(b, 30), Use(c, 20), Use(d, 40), Use(c, 50)),
            all = listOf(a, b, c, d, e),
        )
        assertEquals(listOf(b, a), list.favorites)
        assertEquals(listOf(c, d), list.recent, "a favorite is not listed again under Recent; c's latest use wins")
        assertEquals(listOf(e), list.all, "All holds only what neither list above shows")
    }

    @Test
    fun unusedObjectsSortByNameAfterUsedOnes() {
        val x = ref("x", "Zebra"); val y = ref("y", "apple"); val z = ref("z", "Mango")
        val list = arrange(favorites = listOf(x, y, z), uses = listOf(Use(x, 1)))
        assertEquals(listOf(x, y, z), list.favorites)
    }

    @Test
    fun limitsCutTheShortLists() {
        val refs = (1..10).map { ref("n$it") }
        val list = arrange(favorites = refs.take(3), uses = refs.mapIndexed { i, r -> Use(r, i.toLong()) }, favoritesLimit = 2, recentLimit = 4)
        assertEquals(2, list.favorites.size)
        assertEquals(listOf("n10", "n9", "n8", "n7"), list.recent.map { it.nodeId })
    }

    @Test
    fun aSwitchedOffApplicationIsAbsent() {
        assertEquals(
            listOf(ApplicationId.MEMORIES, ApplicationId.DEPOT),
            availableApplications(setOf("expenses")).map { it.id },
        )
    }
}
