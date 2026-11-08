package me.raddatz.veyq.mobile.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

class HomeTest {
    private val nodes = listOf(
        NodeRef("s", "f", null, "FOLDER", "Reisen"),
        NodeRef("s", "p", null, "EXPENSES_POT", "Sri Lanka"),
        NodeRef("s", "m", null, "MEMORY", "Ostsee 2026"),
        NodeRef("s", "d", null, "FILE", "angebot.pdf"),
        NodeRef("s", "c", "m", "CHAPTER", "Tag 1"),
        NodeRef("s", "b", null, "SHARE_BOOKMARK", "Ein Link"),
        NodeRef("s", "x", "f", "MEMORY", "Rom"),
        NodeRef("t", "y", null, "MEMORY", "Anderer Space"),
    )

    @Test
    fun aLevelIsItsChildren_foldersFirst_onlyThisSpace() {
        assertEquals(listOf("Reisen", "angebot.pdf", "Ostsee 2026", "Sri Lanka"), Home.level(nodes, "s", null, null).map { it.name })
        assertEquals(listOf("Rom"), Home.level(nodes, "s", "f", null).map { it.name })
    }

    @Test
    fun aFilterKeepsOneApplication_andTheFolders() {
        assertEquals(listOf("Reisen", "Ostsee 2026"), Home.level(nodes, "s", null, ApplicationId.MEMORIES).map { it.name })
        assertEquals(listOf("Reisen", "angebot.pdf"), Home.level(nodes, "s", null, ApplicationId.DEPOT).map { it.name })
    }

    @Test
    fun theFiltersAreEverythingThenEachApplicationLeftOn() {
        assertEquals(listOf(null, ApplicationId.MEMORIES, ApplicationId.DEPOT), Home.filters(setOf("expenses")))
    }

    @Test
    fun theDrawerListsNoDepot() {
        assertEquals(listOf(ApplicationId.MEMORIES, ApplicationId.EXPENSES), drawerApplications(emptySet()).map { it.id })
    }

    @Test
    fun homeStartsOnTheTenantsSpace() {
        val spaces = listOf(SpaceRef("p", "PERSONAL", "Personal"), SpaceRef("t", "TENANT", "Familie"))
        assertEquals("t", Home.defaultSpace(spaces)?.scopeId)
    }
}
