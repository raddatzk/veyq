package me.raddatz.veyq.mobile.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import me.raddatz.veyq.mobile.words.Msg

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
    fun aLevelGroupsItsFoldersApart_headedOnlyWhereBothAreThere() {
        val groups = Home.groups(Home.level(nodes, "s", null, null))
        assertEquals(listOf(true, false), groups.map { it.folders })
        assertEquals(listOf(listOf("Reisen"), listOf("angebot.pdf", "Ostsee 2026", "Sri Lanka")), groups.map { g -> g.nodes.map { it.name } })
        assertEquals(listOf(Msg.HOME_GROUP_FOLDERS, Msg.HOME_GROUP_CONTENTS), groups.map { it.heading })
        // one group alone needs no heading
        val onlyContents = Home.groups(Home.level(nodes, "s", "f", null))
        assertEquals(listOf(false), onlyContents.map { it.folders })
        assertEquals(listOf<Msg?>(null), onlyContents.map { it.heading })
        assertEquals(emptyList(), Home.groups(emptyList()))
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
