package me.raddatz.veyq.files.content

import me.raddatz.veyq.core.quota.UploadAdmission
import me.raddatz.veyq.files.FilesNodeKinds
import jakarta.enterprise.context.ApplicationScoped

/**
 * The Files half of a link's usage (ADR 0073 §4): nothing. A file's blob is a core node attachment, which core counts
 * itself (`StorageQuota.linkUsage`); Files has no blob rows of its own. It still measures its kind, because core's
 * terms never admit a link alone — a kind no admission measures is refused at a link start, so an application whose
 * admission went missing fails closed (cr-files-application P3-S5's decision). Guests reach a `FILE` only from F2 on.
 */
@ApplicationScoped
class FilesUploadAdmission : UploadAdmission {
    override fun supports(nodeKind: String): Boolean = nodeKind == FilesNodeKinds.FILE

    override fun linkBlobUsage(shareId: String): Long = 0
}
