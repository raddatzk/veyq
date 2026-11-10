package me.raddatz.veyq.mobile.navigation

/** A space the member belongs to, by its decrypted name (the tenant's, a group's, or the personal one). */
data class SpaceRef(val scopeId: String, val kind: String, val name: String)

/** A node of a space's tree, decrypted: a folder or an object of an application. */
data class NodeRef(
    val scopeId: String,
    val nodeId: String,
    val parentId: String?,
    val kind: String,
    val name: String,
    /** A drop box guest's identity folder (the served `shareIdentityId`): nothing is placed into it or below it, ADR 0080
     *  decision 11. */
    val identity: Boolean = false,
) {
    val isFolder: Boolean get() = kind == FOLDER
    val application: ApplicationId? get() = applicationOf(kind)

    fun asObject(): ObjectRef? = application?.let { ObjectRef(scopeId, nodeId, kind, it, name) }
    fun asFolder(): FolderRef? = if (isFolder) FolderRef(nodeId, name) else null

    companion object {
        const val FOLDER = "FOLDER"
    }
}

/**
 * The application whose object a kind is, as each application's kinds file names it
 * (`contracts/platform/kinds/<application>.json`). A folder or a kept link belongs to core and is no object of an
 * application.
 */
fun applicationOf(kind: String): ApplicationId? = when (kind) {
    "MEMORY" -> ApplicationId.MEMORIES
    "FILE", "DEPOT_DROPBOX" -> ApplicationId.DEPOT
    "EXPENSES_POT" -> ApplicationId.EXPENSES
    else -> null
}

/**
 * What the shell lists, decrypted, for one tenant. The signed-in session implements it from the member's scopes; a
 * list is read across every scope (ADR 0122 decision 5), since the API serves a scope's tree, not a kind across scopes.
 */
interface Library {
    suspend fun spaces(): List<SpaceRef>

    /** Every node that opened, across every scope: Home's trees. */
    suspend fun nodes(): List<NodeRef>

    /** Every object the member can open, across every scope. */
    suspend fun objects(): List<ObjectRef>

    /** The member's favorites, across every scope (one sealed set per scope, ADR 0068). */
    suspend fun favorites(): List<ObjectRef>
}

/** The key a stored use names an object by: the store keeps no name, so nothing decrypted rests on the device. */
data class UseKey(val scopeId: String, val nodeId: String, val lastUsedEpochMs: Long) {
    /** The line a platform stores it as (`scope\tnode\tms`). */
    fun encode(): String = "$scopeId\t$nodeId\t$lastUsedEpochMs"

    companion object {
        /** A stored line read back; null for one that is not a use. */
        fun decode(line: String): UseKey? {
            val p = line.split('\t')
            if (p.size != 3) return null
            return p[2].toLongOrNull()?.let { UseKey(p[0], p[1], it) }
        }
    }
}

/**
 * Recent (ADR 0122 decision 6): per install, never synced, never sent. The platform persists [UseKey]s only; the names
 * come from the library each time the list is shown, and a use whose object is gone is dropped.
 */
class RecentUses(private val persist: (List<UseKey>) -> Unit, initial: List<UseKey>, private val keep: Int = 50) {
    private var keys: List<UseKey> = initial.sortedByDescending { it.lastUsedEpochMs }.take(keep)

    fun record(ref: ObjectRef, atEpochMs: Long) {
        keys = (listOf(UseKey(ref.scopeId, ref.nodeId, atEpochMs)) + keys.filterNot { it.scopeId == ref.scopeId && it.nodeId == ref.nodeId })
            .take(keep)
        persist(keys)
    }

    fun resolve(objects: Collection<ObjectRef>): List<Use> {
        val byKey = objects.associateBy { it.scopeId to it.nodeId }
        return keys.mapNotNull { k -> byKey[k.scopeId to k.nodeId]?.let { Use(it, k.lastUsedEpochMs) } }
    }

    fun lastUsed(objects: Collection<ObjectRef>): ObjectRef? = resolve(objects).firstOrNull()?.ref

    fun clear() {
        keys = emptyList()
        persist(keys)
    }
}
