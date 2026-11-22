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

    /**
     * One level: the children of [parentId] (null: the space's root) in the web's order (the platform's
     * [me.raddatz.veyq.mobile.tree.LevelOrder]: folders first, then every other node mixed by name, equal names by
     * creation). A kept link is core's and belongs to no application: listed with everything (the web's `All` tab,
     * `levelTabs.kindVisible`), left out by an application's filter.
     */
    fun level(nodes: List<NodeRef>, scopeId: String, parentId: String?, filter: ApplicationId?): List<NodeRef> {
        val (folders, others) = nodes.filter { it.scopeId == scopeId && it.parentId == parentId }
            .filter { it.isFolder || (it.isBookmark && filter == null) || (it.application != null && (filter == null || it.application == filter)) }
            .partition { it.isFolder }
        return me.raddatz.veyq.mobile.tree.LevelOrder.order(folders, others, { it.nodeId }, { it.name }, { it.createdAt })
    }

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
