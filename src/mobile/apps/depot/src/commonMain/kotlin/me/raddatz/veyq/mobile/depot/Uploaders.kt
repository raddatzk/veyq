package me.raddatz.veyq.mobile.depot

import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.sharing.IdentityNames

/**
 * Who uploaded a file's current content, as its page names it (the web's `features/depot/uploader.ts`; ADR 0083 decision 8).
 * The server serves the upload's opener to members only, as an opaque key: `user:<id>` is a member, named by core's display
 * rule while they are in the file's space, else a former member; `share:<identity>` is a link's guest, named by the name
 * it gave itself ([guestName], inbox #1301): the identity folder the file lies in inside a drop box, else its own sealed
 * name the listing serves, opened under the file's key or the nearest ancestor's — a guest whose name opens nowhere is
 * "a guest".
 */
object Uploaders {
    enum class Kind { MEMBER, FORMER, GUEST }

    /** [kind]; a member's [userId] and [name]; a guest's [name] as it named itself, bare (the page marks it), or null. */
    class Uploader(val kind: Kind, val userId: String? = null, val name: String? = null)

    /** Who uploaded it, as a file's page says it: a member by name, one who left, a guest by the name it gave itself. */
    fun words(u: Uploader): me.raddatz.veyq.mobile.words.Notice {
        val who: Any = when (u.kind) {
            Kind.MEMBER -> u.name.orEmpty()
            Kind.FORMER -> me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Msg.PEOPLE_FORMER_MEMBER)
            Kind.GUEST -> u.name?.let { me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Msg.DEPOT_GUEST_NAME, it) }
                ?: me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Msg.DEPOT_A_GUEST)
        }
        return me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Msg.DEPOT_UPLOADED_BY, who)
    }

    private const val USER = "user:"
    private const val SHARE = "share:"

    /** A guest's own name as the listing serves it: sealed (base64), bound to the share it came by. */
    class SealedName(val sealedName: String, val shareId: String)

    internal fun sealedOf(n: me.raddatz.veyq.mobile.api.depot.models.FileUploaderName?): SealedName? = n?.let { SealedName(it.sealedName, it.shareId) }

    /** One member of the file's space as the naming needs them: the id and the name core's display rule gives. */
    class Member(val userId: String, val name: String)

    /**
     * The uploader [key] names (the web's `uploaderOf`): a guest by [guestName]'s answer for its identity (none: unnamed); a
     * member against the space's current [members] — null where they could not be read (left unnamed rather than called
     * former) —, one not among them a former member. Null: nothing to show.
     */
    fun of(key: String?, members: List<Member>?, guestName: (identityId: String) -> String?): Uploader? {
        key ?: return null
        if (key.startsWith(SHARE)) return Uploader(Kind.GUEST, name = guestName(key.removePrefix(SHARE))?.takeIf { it.isNotBlank() })
        if (!key.startsWith(USER) || members == null) return null
        val userId = key.removePrefix(USER)
        val member = members.firstOrNull { it.userId == userId } ?: return Uploader(Kind.FORMER)
        return Uploader(Kind.MEMBER, userId, member.name)
    }

    /**
     * The name of guest [identity] who uploaded file [fileId] as a member's [tree] reads it (the web's `guestUploaderName`,
     * inbox #431 and #1301): inside a drop box the identity folder of THAT identity the file lies in names it (its own seal
     * is under its `K_up`, which this does not open); else its [sealed] name, opened under the file's key or the nearest
     * ancestor's (a READ_WRITE guest named itself under its link's root: the file, or a folder above it). Null where none opens.
     */
    fun guestName(suite: V1, tree: List<OpenNode>, scopeId: String, fileId: String, identity: String, sealed: SealedName?): String? {
        val path = upward(tree, fileId)
        path.firstOrNull { it.shareIdentityId == identity }?.let { return it.name.takeIf { n -> n.isNotBlank() } }
        sealed ?: return null
        return IdentityNames.open(suite, scopeId, sealed.shareId, identity, sealed.sealedName, path.asSequence().map { it.key })
    }

    /** [file]'s uploader, or null where there is none to show or the space's members could not be read. */
    @Throws(Exception::class)
    suspend fun of(tenant: TenantSession, file: DepotFiles.File): Uploader? {
        val key = file.uploaderKey ?: return null
        val members = if (!key.startsWith(USER)) emptyList()
        // a kept read (ADR 0144 decision 7): offline the list as last read online names them
        else tenant.account.members(tenant.base, tenant.tenant.tenantId, file.scopeId)
            ?.map { m -> Member(m.userId, tenant.people[m.userId]?.displayName ?: m.username) }
        val tree = if (key.startsWith(SHARE)) tenant.trees()[file.scopeId].orEmpty() else emptyList()
        return of(key, members) { identity -> guestName(tenant.account.suite, tree, file.scopeId, file.nodeId, identity, file.uploaderName) }
    }

    /** [fileId] and its ancestors as [tree] serves them, nearest first; a served parent cycle stops. */
    private fun upward(tree: List<OpenNode>, fileId: String): List<OpenNode> {
        val byId = tree.associateBy { it.id }
        val seen = HashSet<String>()
        val path = ArrayList<OpenNode>()
        var n = byId[fileId]
        while (n != null && seen.add(n.id)) {
            path += n
            n = n.parentId?.let { byId[it] }
        }
        return path
    }
}
