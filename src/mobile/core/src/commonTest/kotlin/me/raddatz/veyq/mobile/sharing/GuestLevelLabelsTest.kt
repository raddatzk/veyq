package me.raddatz.veyq.mobile.sharing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.session.OpenNode

/**
 * #1062: a guest's folder page numbers its folders of one name as the web's share page does (`guestAccess.ts`'s `nameOf`
 * and its level): in node-id order, a guest being served no creation instant (the vectors' second `displayNames` case),
 * among the folders only; its title numbers the folder on screen among its siblings alike.
 */
class GuestLevelLabelsTest {
    private fun node(id: String, parent: String?, kind: String, name: String) = OpenNode("s", id, parent, kind, name, ByteArray(0))

    private val tree = GuestTree.over(
        "s",
        listOf(
            node("root", null, "DEPOT_DROPBOX", "Box"),
            node("c", "root", "FOLDER", "Anna"),
            node("a", "root", "FOLDER", "Anna"),
            node("b", "root", "FOLDER", "Ben"),
            node("d", "root", "FOLDER", "Anna"),
            node("f", "root", "FILE", "Anna"),
        ),
    )

    @Test
    fun aGuestsRowsNumberFoldersOfOneNameInIdOrder() {
        val level = GuestLevel(NodeAt("s", "root", "DEPOT_DROPBOX", "Box"), tree)
        assertEquals(mapOf("a" to "Anna", "b" to "Ben", "c" to "Anna (2)", "d" to "Anna (3)", "f" to "Anna"), level.rows.associate { it.nodeId to it.displayName })
        assertTrue(level.rows.all { it.name == "Anna" || it.name == "Ben" }, "the label went into the name")
        assertEquals("Box", level.title)
    }

    @Test
    fun aGuestsFolderPageIsTitledByItsNumberedName() {
        assertEquals("Anna (3)", GuestLevel(NodeAt("s", "d", "FOLDER", "Anna"), tree).title)
        assertEquals("Anna", GuestLevel(NodeAt("s", "a", "FOLDER", "Anna"), tree).title)
    }
}
