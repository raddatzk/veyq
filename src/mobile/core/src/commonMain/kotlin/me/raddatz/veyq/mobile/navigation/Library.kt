package me.raddatz.veyq.mobile.navigation

/** A space the member belongs to, by its decrypted name (the tenant's, a group's, or the personal one). */
data class SpaceRef(val scopeId: String, val kind: String, val name: String)

/**
 * What the shell lists, decrypted. The signed-in session implements it from the member's scopes; a list is read across
 * every scope (ADR 0122 decision 4), since the API serves a scope's tree, not a kind across scopes.
 */
interface Library {
    suspend fun spaces(): List<SpaceRef>

    /** Every object the member can open, across every scope. */
    suspend fun objects(): List<ObjectRef>

    /** The member's favorites, across every scope (one sealed set per scope, ADR 0068). */
    suspend fun favorites(): List<ObjectRef>
}

/** The key a stored use names an object by: the store keeps no name, so nothing decrypted rests on the device. */
data class UseKey(val scopeId: String, val nodeId: String, val lastUsedEpochMs: Long)

/**
 * Recent (ADR 0122 decision 5): per install, never synced, never sent. The platform persists [UseKey]s only; the names
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
