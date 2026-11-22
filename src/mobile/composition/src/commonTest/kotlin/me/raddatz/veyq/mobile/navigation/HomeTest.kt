package me.raddatz.veyq.mobile.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.raddatz.veyq.mobile.words.Msg

class HomeTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

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
        assertEquals(listOf("Reisen", "angebot.pdf", "Ein Link", "Ostsee 2026", "Sri Lanka"), Home.level(nodes, "s", null, null).map { it.name })
        assertEquals(listOf("Rom"), Home.level(nodes, "s", "f", null).map { it.name })
    }

    /** #1032: a kept link is core's — listed with everything, as the web's `All` tab lists it; no application's filter keeps it. */
    @Test
    fun aKeptLinkIsListedWithEverythingAndByNoApplicationsFilter() {
        val all = Home.level(nodes, "s", null, null)
        val link = all.single { it.nodeId == "b" }
        assertEquals(NodeAt("s", "b", "SHARE_BOOKMARK", "Ein Link"), link.asBookmark(), "the row does not open as a bookmark's page")
        assertNull(link.asObject(), "a kept link became an application's object (the drawer's lists)")
        for (app in ApplicationId.entries) assertTrue(Home.level(nodes, "s", null, app).none { it.isBookmark }, "$app's filter kept the link")
        assertNull(nodes.first().asBookmark())
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
        assertEquals(listOf(listOf("Reisen"), listOf("angebot.pdf", "Ein Link", "Ostsee 2026", "Sri Lanka")), groups.map { g -> g.nodes.map { it.name } })
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

    /**
     * #1062: a level numbers its folders as the web's `LevelView` does (`displayNamesOf` of the level's folders, in creation
     * order; the vectors' first `displayNames` case) — beside the name, which a rename and a move read; a Memory of the same
     * name is never numbered, nor numbers a folder.
     */
    @Test
    fun aLevelNumbersFoldersOfOneNameInCreationOrder_besideTheName() {
        val level = Home.level(
            listOf(
                NodeRef("s", "c", "box", NodeRef.FOLDER, "Anna", identity = true, createdAt = 3.0),
                NodeRef("s", "a", "box", NodeRef.FOLDER, "Anna", identity = true, createdAt = 1.0),
                NodeRef("s", "b", "box", NodeRef.FOLDER, "Ben", identity = true, createdAt = 2.0),
                NodeRef("s", "d", "box", NodeRef.FOLDER, "Anna", identity = true, createdAt = 2.0),
                NodeRef("s", "m", "box", "MEMORY", "Anna", createdAt = 0.0),
            ),
            "s", "box", null,
        )
        assertEquals(mapOf("a" to "Anna", "d" to "Anna (2)", "c" to "Anna (3)", "b" to "Ben", "m" to "Anna"), level.associate { it.nodeId to it.displayName })
        assertEquals(listOf(null, "Anna (2)", "Anna (3)", null), level.filter { it.isFolder }.map { it.label })
        assertTrue(level.all { it.name == "Anna" || it.name == "Ben" }, "the label went into the name")
        // the path a row enters carries its label
        assertEquals(FolderRef("d", "Anna", "Anna (2)"), level.single { it.nodeId == "d" }.asFolder())
        // a level without a repeated folder name labels nothing
        assertTrue(Home.level(nodes, "s", null, null).all { it.label == null })
    }
}
