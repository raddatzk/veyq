package me.raddatz.veyq.mobile.depot

import kotlinx.serialization.Serializable
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.offline.Queued
import me.raddatz.veyq.mobile.offline.QueuedNodes
import me.raddatz.veyq.mobile.offline.QueuedOutcome
import me.raddatz.veyq.mobile.offline.QueuedSender
import me.raddatz.veyq.mobile.offline.QueuedWrite
import me.raddatz.veyq.mobile.session.NodeCreate
import me.raddatz.veyq.mobile.session.Reach
import me.raddatz.veyq.mobile.session.VeyqAccount

/**
 * A Depot file added offline (ADR 0153 decision 3's Depot row; a trash is core's `NODE_TRASH`): its content sealed at once
 * under a fresh `K_blob` and the picked file let go, its node's id minted then; sent through the online upload
 * ([DepotFiles.upload]) — the name checked against the level as it is then, the node made with that id, the frames sent
 * as they were sealed, only `K_blob`'s wrap made under the node's key then. A send cut after the node was made fills that
 * node at the next run (the tree lists it); a name a file of the level holds is held for the person (*replace* or another
 * name), a sibling of another kind holding it too; no room refuses it (*Save a copy*), its empty node given up.
 */
object QueuedDepot {
    @Serializable
    internal data class FileIntent(val parent: String? = null, val name: String, val type: String, val modifiedAt: Long? = null, val size: Long)

    /**
     * [source] queued as a new file named [name] under [parent] (null: the space's top; a folder made offline: it waits for
     * it) of [scope], sealed now; the picked file handed back to [left] whatever came of it. Null where nothing was queued.
     */
    suspend fun file(
        account: VeyqAccount, tenant: String, scope: String, parent: String?, source: UploadSource, nowMs: Long,
        left: (UploadSource) -> Unit, name: String = source.name,
    ): QueuedWrite? {
        try {
            val box = account.outbox ?: return null
            val n = name.trim()
            if (n.isEmpty()) return null
            val blob = box.sealBlob(source.bytes()) ?: return null
            val id = NodeCreate.uuid(account)
            val intent = FileIntent(parent, n, source.type ?: "application/octet-stream", source.modifiedAt, source.size)
            val write = QueuedWrite(
                id, Queued.DEPOT_FILE_ADD.name, tenant, scope, node = id, after = parent?.takeIf { box.write(it) != null },
                intent = QueuedNodes.encode(FileIntent.serializer(), intent), blobs = listOf(blob), at = nowMs,
            )
            if (!box.put(write)) {
                box.dropBlob(blob)
                return null
            }
            return write
        } finally {
            source.close()
            runCatching { left(source) }
        }
    }

    internal val fileSender = QueuedSender { tenant, w ->
        val i = QueuedNodes.decode(FileIntent.serializer(), w)
        val box = tenant.account.outbox ?: return@QueuedSender QueuedOutcome.Refused("refused")
        val blob = w.blobs.singleOrNull() ?: return@QueuedSender QueuedOutcome.Refused(me.raddatz.veyq.mobile.offline.Outbox.FRAME_GONE)
        // a node an earlier run made (its answer lost, or its content cut off) is filled, not made again
        val into = tenant.trees()[w.scope].orEmpty().firstOrNull { it.id == w.id }?.id
        val r = DepotFiles.upload(
            tenant, w.scope, i.parent, i.name, i.size, i.type, i.modifiedAt, AppKinds::tokenInput, { _, _ -> ByteArray(0) }, {}, {},
            into, false, w.id, box.sealed(blob),
        )
        suspend fun givenUp(outcome: QueuedOutcome): QueuedOutcome {
            // a node made for it that holds nothing is given up — not while the server is away: the next run fills it
            if (tenant.account.reach.value == Reach.Online) runCatching { DepotFiles.abandon(tenant, w.scope, i.parent, w.id) }
            return outcome
        }
        when (r.outcome) {
            DepotFiles.Outcome.DONE -> QueuedOutcome.Sent
            DepotFiles.Outcome.EXISTS -> QueuedOutcome.Held(DepotFiles.Codes.EXISTS)
            DepotFiles.Outcome.TAKEN_BY_OTHER -> QueuedOutcome.Held(QueuedNodes.NAME_TAKEN)
            // filling its own node again: the content attached by a run whose answer was lost is there
            DepotFiles.Outcome.STALE -> if (into != null) QueuedOutcome.Sent else QueuedOutcome.Held(DepotFiles.Codes.STALE)
            DepotFiles.Outcome.BUSY -> QueuedOutcome.Waiting
            DepotFiles.Outcome.NO_ROOM -> givenUp(QueuedOutcome.Refused("quota_exceeded"))
            DepotFiles.Outcome.FAILED -> givenUp(QueuedOutcome.Refused("failed"))
        }
    }
}
