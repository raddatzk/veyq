package me.raddatz.veyq.mobile.navigation

import kotlinx.serialization.Serializable

/** A tenant of a server, as the switcher shows it. */
@Serializable
data class TenantRef(val tenantId: String, val name: String, val domain: String)

/** One server the app is signed in to: an account of its own, with the tenants it belongs to there. */
interface Connection {
    /** The same user on the same server is one connection, however often it signs in. */
    val key: String
    val label: String
    val tenants: List<TenantRef>
}

/**
 * The servers the app is signed in to and which tenant of which is current (owner 2026-11-08: one server can give
 * several tenants, and the app connects to several servers). Held in memory only until a device can stay trusted
 * (ADR 0123 decision 5).
 */
class Connections<C : Connection> {
    private val list = mutableListOf<C>()

    var current: Pair<C, TenantRef>? = null
        private set

    val all: List<C> get() = list.toList()

    /**
     * Adds a connection and makes [tenant] of it current. A second sign-in to a connection already held keeps the
     * first and returns it, with the duplicate for the caller to close.
     */
    fun add(connection: C, tenant: TenantRef): Added<C> {
        val existing = list.firstOrNull { it.key == connection.key }
        val kept = existing ?: connection.also { list += it }
        current = kept to (kept.tenants.firstOrNull { it.tenantId == tenant.tenantId } ?: tenant)
        return Added(kept, duplicate = if (existing != null) connection else null)
    }

    fun switchTo(connection: C, tenant: TenantRef) {
        require(connection in list) { "not a held connection" }
        require(tenant in connection.tenants) { "not a tenant of that connection" }
        current = connection to tenant
    }

    /** Removes a connection; if it was current, the first remaining one's first tenant becomes current. */
    fun remove(connection: C) {
        list.remove(connection)
        if (current?.first == connection) current = list.firstOrNull()?.let { c -> c.tenants.firstOrNull()?.let { c to it } }
    }

    data class Added<C>(val connection: C, val duplicate: C?)
}
