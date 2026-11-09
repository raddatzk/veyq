package me.raddatz.veyq.mobile.session

import me.raddatz.veyq.mobile.crypto.Naming
import me.raddatz.veyq.mobile.navigation.NodeRef

/**
 * A folder created (core's, the web's `FolderNameDialog` creating at a level): a node of kind `FOLDER` named [create]'s name,
 * trimmed, its slug token over the name's slug (a folder's fold, `registry.tokenInputFor`), no meta. The tree read before is
 * forgotten after, so the next read lists the new folder.
 */
object FolderCreate {
    @Throws(Exception::class)
    suspend fun create(tenant: TenantSession, scopeId: String, parentId: String?, name: String): NodeCreate.Result {
        val n = name.trim()
        val r = NodeCreate.create(tenant, scopeId, parentId, NodeRef.FOLDER, n, Naming.slugify(n))
        tenant.forgetTree()
        return r
    }
}
