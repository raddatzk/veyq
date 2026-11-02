package me.raddatz.veyq.depot.share

import me.raddatz.veyq.core.platform.capabilities.ApplicationSwitch
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds
import me.raddatz.veyq.core.http.FailureStatus.FORBIDDEN
import me.raddatz.veyq.core.http.FailureStatus.NOT_FOUND
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.ClientSeen
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.sharing.IdentityFolderOutcome
import me.raddatz.veyq.core.sharing.IdentityFolders
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.ShareGuestGate
import me.raddatz.veyq.core.sharing.ShareWriteGate
import me.raddatz.veyq.depot.DepotNodeKinds
import jakarta.enterprise.context.ApplicationScoped

sealed interface DropFolderOutcome {
    data class Folder(val folder: IdentityFolderOutcome.Folder) : DropFolderOutcome
    object NotFound : DropFolderOutcome, HttpFailure by httpFailure("share_not_found", NOT_FOUND)
    /** No `WRITE_ONLY` link, no drop box at its root (or a trashed one), an unknown token, or the identity's folder in
     *  the trash — a 403. */
    object NotContributable : DropFolderOutcome, HttpFailure by httpFailure("share_not_contributable", FORBIDDEN)
    object PasswordIncorrect : DropFolderOutcome, HttpFailure by httpFailure("share_password_incorrect", FORBIDDEN)
    object RateLimited : DropFolderOutcome, HttpFailure by Failures.rateLimited
    object StaleEpoch : DropFolderOutcome, HttpFailure by Failures.staleEpoch
    object IdTaken : DropFolderOutcome, HttpFailure by Failures.idTaken
    object SlugTaken : DropFolderOutcome, HttpFailure by Failures.slugTaken
}

/**
 * A drop-box guest's own folder (`ensureShareDropFolder`, ADR 0080 decision 11, docs/apps/depot.md § The drop box): past
 * core's guest gate, a `WRITE_ONLY` link whose root is Depot's `DEPOT_DROPBOX` gets its identity's folder from core's
 * [IdentityFolders] — the mechanism is core's, the decision that THIS kind takes identity folders is Depot's. A
 * Memories `WRITE_ONLY` link (a `MEMORY` root) is refused here: its guests' uploads are media, not nodes.
 */
@ApplicationScoped
class DropFolderHandler(
    private val gate: ShareGuestGate,
    private val folders: IdentityFolders,
    private val applications: ApplicationSwitch,
) {
    fun ensure(
        shareId: String, pwToken: String?, contributorToken: String, nodeId: String, encryptedName: String,
        slugToken: String, scopeEpoch: Int, wrappedContentKey: String, seen: ClientSeen?,
    ): DropFolderOutcome {
        // The folder is a row a guest adds, so it spends the per-share upload budget, as a guest's create does.
        val gated = when (val g = gate.writeGate(shareId, pwToken, contributorToken, spendUploadBudget = true, onlyRole = ShareEntity.WRITE_ONLY)) {
            is ShareWriteGate.Admitted -> g
            ShareWriteGate.NotFound -> return DropFolderOutcome.NotFound
            ShareWriteGate.PasswordIncorrect -> return DropFolderOutcome.PasswordIncorrect
            ShareWriteGate.RateLimited -> return DropFolderOutcome.RateLimited
            ShareWriteGate.NotContributable -> return DropFolderOutcome.NotContributable
        }
        applications.requireWritable(CapabilityIds.DEPOT, gated.scopeId) // switched off, read-only (uc.platform.switch-application-off)
        if (gated.rootKind != DepotNodeKinds.DROPBOX) return DropFolderOutcome.NotContributable
        return when (val o = folders.ensure(gated, nodeId, encryptedName, slugToken, scopeEpoch, wrappedContentKey, seen)) {
            is IdentityFolderOutcome.Folder -> DropFolderOutcome.Folder(o)
            IdentityFolderOutcome.NotFound -> DropFolderOutcome.NotFound
            IdentityFolderOutcome.NotContributable -> DropFolderOutcome.NotContributable
            IdentityFolderOutcome.StaleEpoch -> DropFolderOutcome.StaleEpoch
            IdentityFolderOutcome.IdTaken -> DropFolderOutcome.IdTaken
            IdentityFolderOutcome.SlugTaken -> DropFolderOutcome.SlugTaken
        }
    }
}
