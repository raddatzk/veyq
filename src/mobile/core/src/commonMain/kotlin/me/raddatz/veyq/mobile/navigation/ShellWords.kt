package me.raddatz.veyq.mobile.navigation

import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import me.raddatz.veyq.mobile.words.Piece

/** What the shell says and finds that no application decides, for both apps (ADR 0127). */
object ShellWords {
    /** A space's name: the personal and the group space by their words, another by its own name, none as Home. */
    fun space(space: SpaceRef?): Piece = when (space?.kind) {
        null -> Piece.Said(Notice(Msg.DRAWER_HOME))
        "PERSONAL" -> Piece.Said(Notice(Msg.SPACE_PERSONAL))
        "GROUP" -> Piece.Said(Notice(Msg.SPACE_GROUP))
        else -> Piece.Plain(space.name)
    }

    /** Search's hits: the objects whose name holds the query, any case; none for a blank query. */
    fun search(objects: List<ObjectRef>, query: String): List<ObjectRef> {
        val q = query.trim()
        return if (q.isEmpty()) emptyList() else objects.filter { it.name.contains(q, ignoreCase = true) }
    }
}
