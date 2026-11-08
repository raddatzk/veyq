package me.raddatz.veyq.mobile.navigation

/** A folder on Home's path. */
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

    /** The space Home starts on: the tenant's own, else the first. */
    fun defaultSpace(spaces: List<SpaceRef>): SpaceRef? = spaces.firstOrNull { it.kind == "TENANT" } ?: spaces.firstOrNull()
}
