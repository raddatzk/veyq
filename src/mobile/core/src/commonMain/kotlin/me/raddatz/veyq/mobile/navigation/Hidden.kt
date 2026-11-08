package me.raddatz.veyq.mobile.navigation

/**
 * The tenants and spaces the member hid on this device (owner 2026-11-08, "manage servers"). Like Recent (ADR 0122
 * decision 6) it is per install, never synced and never sent, and it keeps ids only: a tenant as its connection's key
 * and its tenant id, a space as its scope id.
 *
 * A hidden tenant is left out of the switcher; a hidden space is left out of the whole app on this device — Home's
 * space switcher, the applications' lists, Favorites, Recent, Search. Nothing is hidden from the server: this is the
 * member's view, not an access decision.
 */
class Hidden(private val persist: (Set<String>) -> Unit, initial: Set<String>) {
    private var keys: Set<String> = initial

    private fun tenantKey(connectionKey: String, tenantId: String) = "tenant:$connectionKey#$tenantId"
    private fun spaceKey(scopeId: String) = "space:$scopeId"

    fun isTenantHidden(connectionKey: String, tenantId: String): Boolean = tenantKey(connectionKey, tenantId) in keys
    fun isSpaceHidden(scopeId: String): Boolean = spaceKey(scopeId) in keys

    fun setTenantHidden(connectionKey: String, tenantId: String, hidden: Boolean) = set(tenantKey(connectionKey, tenantId), hidden)
    fun setSpaceHidden(scopeId: String, hidden: Boolean) = set(spaceKey(scopeId), hidden)

    /** The spaces left to show; never empty while there are spaces — the last visible one stays. */
    fun visibleSpaces(spaces: List<SpaceRef>): List<SpaceRef> = spaces.filterNot { isSpaceHidden(it.scopeId) }.ifEmpty { spaces.take(1) }

    /** Whether a space may be hidden: not the tenant's last visible one. */
    fun canHideSpace(scopeId: String, spaces: List<SpaceRef>): Boolean =
        isSpaceHidden(scopeId) || visibleSpaces(spaces).count { it.scopeId != scopeId } > 0

    /** The nodes of the visible spaces only, so a hidden space reaches no list of the app. */
    fun visibleNodes(nodes: List<NodeRef>, spaces: List<SpaceRef>): List<NodeRef> {
        val shown = visibleSpaces(spaces).map { it.scopeId }.toSet()
        return nodes.filter { it.scopeId in shown }
    }

    fun visibleObjects(objects: List<ObjectRef>, spaces: List<SpaceRef>): List<ObjectRef> {
        val shown = visibleSpaces(spaces).map { it.scopeId }.toSet()
        return objects.filter { it.scopeId in shown }
    }

    /** The tenants of a connection the switcher lists: the visible ones, and the current one always. */
    fun visibleTenants(connectionKey: String, tenants: List<TenantRef>, current: TenantRef?): List<TenantRef> =
        tenants.filter { it == current || !isTenantHidden(connectionKey, it.tenantId) }

    fun clear() {
        keys = emptySet()
        persist(keys)
    }

    private fun set(key: String, hidden: Boolean) {
        keys = if (hidden) keys + key else keys - key
        persist(keys)
    }
}
