package me.raddatz.veyq.mobile.offline

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.navigation.NodeRef
import me.raddatz.veyq.mobile.session.FolderCreate
import me.raddatz.veyq.mobile.session.NodeCreate
import me.raddatz.veyq.mobile.session.NodeRename
import me.raddatz.veyq.mobile.session.NodeTrash
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.session.VeyqAccount

/**
 * Core's node writes made offline (ADR 0153 decision 3's first row): a folder created, a node renamed, a node trashed —
 * queued as the person's intent ([Outbox]) and sent through the online path each takes ([FolderCreate], [NodeRename],
 * [NodeTrash]), which reads the tree and the keys as they are then and seals under them.
 *
 * - **Queuing** writes nothing a server would read: a folder is its parent and name, its id minted now (what a write into
 *   it waits for, [QueuedWrite.after]); a rename is the name and the placement revision the person saw; a trash its node.
 *   A rename of a folder made offline renames the queued create; a trash of one drops it and every write waiting for it.
 * - **Sending** ends as ADR 0153 decision 4 has it: a create answered `id_taken` (a send whose answer was lost) is read
 *   back from the tree — the folder there as the person made it is sent, anything else held; a rename meeting a placement
 *   changed meanwhile (the tree's revision is not the one seen, or the server answers `stale_placement`) is held, never
 *   rebuilt as online; a target gone is refused; a re-key under way waits for the next run.
 */
object QueuedNodes {
    @Serializable
    internal data class FolderIntent(val parent: String? = null, val name: String)

    @Serializable
    internal data class RenameIntent(val name: String, val placementRevision: Long)

    @Serializable
    internal class TrashIntent

    // ---- queuing

    /**
     * Folder [name] queued in [scope] of [tenant] under [parent] (null: the space's top; a folder made offline: it waits
     * for it). Null where nothing was queued: no store on this device, or the volume below the floor.
     */
    suspend fun folder(account: VeyqAccount, tenant: String, scope: String, parent: String?, name: String, nowMs: Long): QueuedWrite? {
        val box = account.outbox ?: return null
        val n = name.trim()
        if (n.isEmpty()) return null
        val id = NodeCreate.uuid(account)
        val after = parent?.takeIf { box.write(it) != null }
        val write = QueuedWrite(id, Queued.FOLDER_CREATE.name, tenant, scope, node = id, after = after, intent = encode(FolderIntent.serializer(), FolderIntent(parent, n)), at = nowMs)
        return write.takeIf { box.put(it) }
    }

    /**
     * Node [node] of [scope] renamed to [name], stating [placementRevision], the revision the person saw it at. A folder
     * made offline has its queued create renamed instead; a second rename replaces the first and keeps the revision the
     * first saw. False where nothing was queued.
     */
    suspend fun rename(account: VeyqAccount, tenant: String, scope: String, node: String, placementRevision: Long, name: String, nowMs: Long): Boolean {
        val box = account.outbox ?: return false
        val n = name.trim()
        if (n.isEmpty()) return false
        box.write(node)?.takeIf { it.op == Queued.FOLDER_CREATE.name }?.let { made ->
            val intent = decode(FolderIntent.serializer(), made)
            return box.put(made.copy(intent = encode(FolderIntent.serializer(), intent.copy(name = n))))
        }
        val id = RENAME + node
        val seen = box.write(id)?.let { decode(RenameIntent.serializer(), it).placementRevision } ?: placementRevision
        return box.put(QueuedWrite(id, Queued.NODE_RENAME.name, tenant, scope, node = node, intent = encode(RenameIntent.serializer(), RenameIntent(n, seen)), at = nowMs))
    }

    /**
     * Node [node] of [scope] to the trash. A folder made offline is dropped from the queue instead, with every write
     * waiting for it; a queued rename of the node goes with it. False where nothing was queued.
     */
    suspend fun trash(account: VeyqAccount, tenant: String, scope: String, node: String, nowMs: Long): Boolean {
        val box = account.outbox ?: return false
        if (box.write(node)?.op == Queued.FOLDER_CREATE.name) {
            dropWithDependents(box, node)
            return true
        }
        box.write(RENAME + node)?.let { box.remove(it.id) }
        return box.put(QueuedWrite(TRASH + node, Queued.NODE_TRASH.name, tenant, scope, node = node, intent = encode(TrashIntent.serializer(), TrashIntent()), at = nowMs))
    }

    /** Write [id] discarded with every write that waits for it, theirs after them. */
    internal suspend fun dropWithDependents(box: Outbox, id: String) {
        val all = box.writes()
        val drop = LinkedHashSet<String>().apply { add(id) }
        var grew = true
        while (grew) {
            grew = false
            for (w in all) if (w.after in drop && drop.add(w.id)) grew = true
        }
        for (d in drop) box.remove(d)
    }

    // ---- sending

    internal val folderSender = QueuedSender { tenant, w ->
        val intent = decode(FolderIntent.serializer(), w)
        when (val r = FolderCreate.create(tenant, w.scope, intent.parent, intent.name, nodeId = w.id)) {
            is NodeCreate.Result.Done -> QueuedOutcome.Sent
            is NodeCreate.Result.Refused -> when {
                // maybe sent already, its answer lost: the folder there as the person made it is theirs
                r.code == ID_TAKEN -> {
                    tenant.forgetTree()
                    val there = tenant.trees()[w.scope].orEmpty().firstOrNull { it.id == w.id }
                    if (there != null && there.kind == NodeRef.FOLDER && there.parentId == intent.parent && there.name == intent.name) QueuedOutcome.Sent
                    else QueuedOutcome.Held(ID_TAKEN)
                }
                r.reason == NodeCreate.Reason.NAME_TAKEN -> QueuedOutcome.Held(NAME_TAKEN)
                r.reason == NodeCreate.Reason.BUSY -> QueuedOutcome.Waiting
                r.reason == NodeCreate.Reason.NOT_ALLOWED -> QueuedOutcome.Refused("illegal_structure")
                r.reason == NodeCreate.Reason.BLOCKED -> QueuedOutcome.Refused("blocked")
                else -> QueuedOutcome.Refused(r.code ?: r.status?.let { "http_$it" } ?: "refused")
            }
        }
    }

    internal val renameSender = QueuedSender { tenant, w ->
        val intent = decode(RenameIntent.serializer(), w)
        val node = w.node ?: return@QueuedSender QueuedOutcome.Refused("refused")
        val r = NodeRename.rename(tenant, w.scope, node, intent.name, AppKinds::tokenInput, w.at, placementSeen = intent.placementRevision)
        when (r.outcome) {
            NodeRename.Outcome.RENAMED -> QueuedOutcome.Sent
            NodeRename.Outcome.NAME_TAKEN -> QueuedOutcome.Held(if (r.code == STALE_PLACEMENT) STALE_PLACEMENT else NAME_TAKEN)
            NodeRename.Outcome.NOT_ALLOWED -> QueuedOutcome.Refused("illegal_structure")
            NodeRename.Outcome.BUSY -> QueuedOutcome.Waiting
            NodeRename.Outcome.FAILED -> QueuedOutcome.Refused(r.code ?: NOT_FOUND)
        }
    }

    internal val trashSender = QueuedSender { tenant, w ->
        val node = w.node ?: return@QueuedSender QueuedOutcome.Refused("refused")
        when (NodeTrash.delete(tenant, w.scope, node)) {
            NodeTrash.Deleted.TRASHED -> QueuedOutcome.Sent
            NodeTrash.Deleted.GONE -> QueuedOutcome.Refused(NOT_FOUND)
            NodeTrash.Deleted.SCOPE_DELETING -> QueuedOutcome.Refused("scope_deleting")
            NodeTrash.Deleted.FAILED -> QueuedOutcome.Refused("refused")
        }
    }

    /** Core's senders, by [Queued] value ([AppKinds.queuedSender] hands them out). */
    internal fun sender(q: Queued): QueuedSender = when (q) {
        Queued.FOLDER_CREATE -> folderSender
        Queued.NODE_RENAME -> renameSender
        Queued.NODE_TRASH -> trashSender
    }

    private const val RENAME = "rename:"
    private const val TRASH = "trash:"
    internal const val ID_TAKEN = "id_taken"
    internal const val NAME_TAKEN = "name_taken"
    internal const val STALE_PLACEMENT = "stale_placement"
    internal const val NOT_FOUND = "not_found"

    internal fun <T> encode(serializer: KSerializer<T>, value: T): JsonObject = Outbox.JSON.encodeToJsonElement(serializer, value).jsonObject

    internal fun <T> decode(serializer: KSerializer<T>, write: QueuedWrite): T = Outbox.JSON.decodeFromJsonElement(serializer, write.intent)
}
