package me.raddatz.veyq.mobile.depot

import me.raddatz.veyq.mobile.api.apis.ScopesApi
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.session.TenantSession

/**
 * Who uploaded a file's current content, as its page names it (the web's `features/depot/uploader.ts`; ADR 0083 decision 8).
 * The server serves the upload's opener to members only, as an opaque key: `user:<id>` is a member, named by core's display
 * rule while they are in the file's space, else a former member; `share:<identity>` is a link's guest, named by the guest's
 * folder the file lies in (the drop box's row for that identity) — a guest whose name this cannot open (a READ_WRITE
 * guest's own sealed name, not opened here yet) is "a guest".
 */
object Uploaders {
    enum class Kind { MEMBER, FORMER, GUEST }

    /** [kind]; a member's [userId] and [name]; a guest's [name] as it named itself, bare (the page marks it), or null. */
    class Uploader(val kind: Kind, val userId: String? = null, val name: String? = null)

    private const val USER = "user:"
    private const val SHARE = "share:"

    /** [file]'s uploader, or null where there is none to show or the space's members could not be read. */
    @Throws(Exception::class)
    suspend fun of(tenant: TenantSession, file: DepotFiles.File): Uploader? {
        val key = file.uploaderKey ?: return null
        if (key.startsWith(SHARE)) {
            val identity = key.removePrefix(SHARE)
            return Uploader(Kind.GUEST, name = guestFolder(tenant.trees()[file.scopeId].orEmpty(), file.nodeId, identity)?.name?.takeIf { it.isNotBlank() })
        }
        if (!key.startsWith(USER)) return null
        val userId = key.removePrefix(USER)
        val members = runCatching {
            tenant.account.call { ScopesApi(tenant.base, tenant.account.http).listScopeMembers(file.scopeId) }
        }.getOrNull() ?: return null
        val member = members.firstOrNull { it.userId == userId } ?: return Uploader(Kind.FORMER)
        return Uploader(Kind.MEMBER, userId, tenant.people[userId]?.displayName ?: member.username)
    }

    /** The identity folder of [identity] that [fileId] lies in, nearest first; a served parent cycle stops. */
    internal fun guestFolder(tree: List<OpenNode>, fileId: String, identity: String): OpenNode? {
        val byId = tree.associateBy { it.id }
        val seen = HashSet<String>()
        var n = byId[fileId]
        while (n != null && seen.add(n.id)) {
            if (n.shareIdentityId == identity) return n
            n = n.parentId?.let { byId[it] }
        }
        return null
    }
}
