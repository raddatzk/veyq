package me.raddatz.veyq.mobile.navigation

import kotlinx.serialization.Serializable
import me.raddatz.veyq.mobile.words.Msg

/** A folder on Home's path. */
@Serializable
data class FolderRef(val nodeId: String, val name: String)

/**
 * Home (ADR 0122): one space's tree, as the web's level view shows it — folders and the objects of every application
 * side by side. A filter keeps one application's objects; the folders stay in every filter, so the tree stays walkable
 * (the web's level tabs, `uc.memory.browse`).
 */
object Home {
    /** The filters Home offers: everything, then each application the operator and the tenant owner left on. */
    fun filters(applicationsOff: Set<String>): List<ApplicationId?> = listOf<ApplicationId?>(null) + availableApplications(applicationsOff).map { it.id }

    /** One level: the children of [parentId] (null: the space's root), folders first, each group by name. */
    fun level(nodes: List<NodeRef>, scopeId: String, parentId: String?, filter: ApplicationId?): List<NodeRef> =
        nodes.filter { it.scopeId == scopeId && it.parentId == parentId }
            .filter { it.isFolder || (it.application != null && (filter == null || it.application == filter)) }
            .sortedWith(compareBy<NodeRef> { !it.isFolder }.thenBy { it.name.lowercase() })

    /**
     * One group of a level, as the web's level view draws it (`LevelView`'s groups): the folders, then the contents.
     * [heading] is null where the level has one group only, which then needs none.
     */
    @Serializable
    data class Group(val folders: Boolean, val nodes: List<NodeRef>, @Serializable(with = me.raddatz.veyq.mobile.words.MsgKey::class) val heading: Msg?)

    /** A level ([level]'s order) in its groups: the folders apart from the rest, an empty group left out. */
    fun groups(level: List<NodeRef>): List<Group> {
        val (folders, contents) = level.partition { it.isFolder }
        val both = folders.isNotEmpty() && contents.isNotEmpty()
        return listOfNotNull(
            folders.takeIf { it.isNotEmpty() }?.let { Group(true, it, Msg.HOME_GROUP_FOLDERS.takeIf { both }) },
            contents.takeIf { it.isNotEmpty() }?.let { Group(false, it, Msg.HOME_GROUP_CONTENTS.takeIf { both }) },
        )
    }

    /** The space Home starts on: the tenant's own, else the first. */
    fun defaultSpace(spaces: List<SpaceRef>): SpaceRef? = spaces.firstOrNull { it.kind == "TENANT" } ?: spaces.firstOrNull()
}
