package me.raddatz.veyq.mobile.navigation

import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import me.raddatz.veyq.mobile.words.Piece

/** What the shell says and finds that no application decides, for both apps (ADR 0161). */
object ShellWords {
    /** A space's name: the personal and the group space by their words, another by its own name, none as Home. */
    fun space(space: SpaceRef?): Piece = when (space?.kind) {
        null -> Piece.Said(Notice(Msg.DRAWER_HOME))
        "PERSONAL" -> Piece.Said(Notice(Msg.SPACE_PERSONAL))
        // a group by the name its key opened on the device (GroupNames); the word where none opens
        "GROUP" -> if (space.name.isNotEmpty()) Piece.Plain(space.name) else Piece.Said(Notice(Msg.SPACE_GROUP))
        else -> Piece.Plain(space.name)
    }

    /**
     * The line under the switcher's space (owner 2026-11-10: spaces and tenants in one switcher): the tenant and the
     * server; the server alone under the tenant's own space, which bears the tenant's name already.
     */
    fun switcherSubtitle(space: SpaceRef?, tenant: String, server: String): String =
        if (space?.kind == "TENANT") server else "$tenant · $server"

    /**
     * One signed-in account as the switcher lists it: its connection's key, its server's host, the address it signed in
     * with, and the tenants the switcher shows of it (`Hidden.visibleTenants`).
     */
    class SwitcherAccount(val connection: String, val host: String, val address: String, val tenants: List<TenantRef>)

    /**
     * A tenant row of the switcher: [name] the tenant's, [detail] the line that says where it is — the tenant's domain
     * (the web's switcher shows it under every tenant), and the account's address after it where another signed-in
     * account lists a tenant of the same name at the same domain. [current] is the tenant open now, of that account.
     */
    @kotlinx.serialization.Serializable
    data class SwitcherEntry(val connection: String, val tenant: TenantRef, val name: String, val detail: String, val current: Boolean) {
        /** Name and detail on one line, for a menu whose item holds one label. */
        val line: String get() = "$name · $detail"
    }

    /**
     * The switcher's tenant rows over every signed-in account, in the accounts' order (owner 2026-11-19: two accounts of one
     * tenant showed as two identical rows, only the selected one with its host). Each row says its domain; a row whose
     * name and domain another account's row shares says its account's address too.
     */
    fun switcherEntries(accounts: List<SwitcherAccount>, currentConnection: String?, currentTenant: String?): List<SwitcherEntry> {
        fun where(a: SwitcherAccount, t: TenantRef) = t.domain.ifBlank { a.host }
        fun key(a: SwitcherAccount, t: TenantRef) = t.name.lowercase() to where(a, t).lowercase()
        val accountsOf = HashMap<Pair<String, String>, MutableSet<String>>()
        accounts.forEach { a -> a.tenants.forEach { t -> accountsOf.getOrPut(key(a, t)) { mutableSetOf() } += a.connection } }
        return accounts.flatMap { a ->
            a.tenants.map { t ->
                val shared = accountsOf[key(a, t)].orEmpty().size > 1
                SwitcherEntry(
                    a.connection, t, t.name,
                    if (shared && a.address.isNotBlank()) "${where(a, t)} · ${a.address}" else where(a, t),
                    a.connection == currentConnection && t.tenantId == currentTenant,
                )
            }
        }
    }

    /**
     * [switcherEntries] over the held [accounts], each listing the tenants [hidden] leaves shown; [current]'s [tenant] is
     * the one open, always listed.
     */
    fun switcher(accounts: List<Connection>, hidden: Hidden, current: Connection?, tenant: TenantRef?): List<SwitcherEntry> =
        switcherEntries(
            accounts.map { c -> SwitcherAccount(c.key, c.label, c.address, hidden.visibleTenants(c.key, c.tenants, if (c == current) tenant else null)) },
            current?.key, tenant?.tenantId,
        )
}
