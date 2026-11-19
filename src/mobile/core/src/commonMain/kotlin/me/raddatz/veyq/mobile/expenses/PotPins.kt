package me.raddatz.veyq.mobile.expenses

import me.raddatz.veyq.mobile.blob.ApplicationBlobs
import me.raddatz.veyq.mobile.offline.PinBlob
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.session.TenantSession

/**
 * What a pin keeps of a Pot (ADR 0153 decision 6, `AppKinds.pins`): its whole ledger, read through the kept read (each
 * sync replaces the kept copy whole, `TenantPotDoor.whole`), and the frames of every receipt it lists that is held — not
 * one a revision dropped, not one the operator blocked. Its cover is core's, as every node's.
 */
internal object PotPins {
    suspend fun pinned(tenant: TenantSession, node: OpenNode): List<PinBlob> {
        TenantPotDoor(tenant).whole(node)
        val blobs = ApplicationBlobs(tenant)
        val listing = blobs.list(node.scopeId, node.id) ?: return emptyList()
        return listing.blobs.filter { it.role == Receipts.ROLE && !it.blocked && !it.dropped }.mapNotNull { b ->
            blobs.openKey(node.scopeId, node, b.blobId, b.role, b.wrappedBlobKey)?.let { PinBlob(node.scopeId, b.blobId, b.chunkCount, b.byteSize, it) }
        }
    }
}
