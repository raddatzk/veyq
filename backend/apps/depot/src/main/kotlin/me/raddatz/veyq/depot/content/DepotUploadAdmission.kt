package me.raddatz.veyq.depot.content

import me.raddatz.veyq.core.quota.UploadAdmission
import me.raddatz.veyq.depot.DepotNodeKinds
import jakarta.enterprise.context.ApplicationScoped

/**
 * The Depot half of a link's usage (ADR 0073 §4): nothing. A file's blob is a core node attachment, which core counts
 * itself (`StorageQuota.linkUsage`); Depot has no blob rows of its own. It still measures its kind, because core's
 * terms never admit a link alone — a kind no admission measures is refused at a link start, so an application whose
 * admission went missing fails closed (cr-files-application P3-S5's decision). A guest reaches a `FILE` through a
 * `READ_WRITE` folder link or into its own drop-box folder (docs/apps/files.md § Guests, § The drop box).
 */
@ApplicationScoped
class DepotUploadAdmission : UploadAdmission {
    override fun supports(nodeKind: String): Boolean = nodeKind == DepotNodeKinds.FILE

    override fun linkBlobUsage(shareId: String): Long = 0
}
