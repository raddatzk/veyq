package me.raddatz.veyq.mobile.offline

import me.raddatz.veyq.mobile.session.TenantSession

/**
 * What may be queued offline (ADR 0153 decision 3) — the one closed list, beside [KeptRead]: one value per write of the
 * decision's table, each naming the API [operations] its sender calls (the online path it goes through; its intent's shape
 * is its sender's, [QueuedNodes] for core's). A model asks [me.raddatz.veyq.mobile.session.VeyqAccount.queues] before it
 * offers a control offline; a write not named here stays refused offline with ADR 0140's notice.
 *
 * `QueuedTest` runs every value's sender over a played server and derives the operations it calls: one the value does not
 * declare, or one in the decision's never-queue column (sharing, rights, keys, account and device writes, a move, a
 * restore, a purge, …), fails it — the list is checked, not trusted. An application's rows have their senders in the
 * application ([me.raddatz.veyq.mobile.memories.QueuedMemories], …), reached through [me.raddatz.veyq.mobile.AppKinds.queuedSender].
 */
enum class Queued(internal val operations: Set<String>) {
    /** Core: a folder created (`FolderCreate`, its id the write's). */
    FOLDER_CREATE(setOf("createNode")),

    /** Core: a node renamed, stating the placement revision the person saw (`NodeRename`, never its rebuild). */
    NODE_RENAME(setOf("updateNode")),

    /** Core: a node to its space's trash (`NodeTrash.delete`): a folder, a Memory, a chapter, a Depot file. */
    NODE_TRASH(setOf("deleteNode")),

    /** Memories: a Memory or a chapter made (`MemoryCreate`, its id the write's). */
    MEMORY_CREATE(setOf("createNode")),

    /**
     * Memories: a photo or video added (`MediaUpload`, its id the write's): its frames sealed at queue time, reserved,
     * PUT to the store (no API operation) and completed; a window signed where the start's ran out.
     */
    MEDIA_ADD(setOf("startBlobUploads", "presignBlobUploadWindows", "completeMedia")),

    /** Memories: a medium's capture time set or reset (`CaptureTime`, the person's own field held where it moved). */
    MEDIA_CAPTURE_TIME(setOf("updateMediaMeta")),

    /** Memories: a medium to its node's trash (`MemoryMedia.trash`). */
    MEDIA_TRASH(setOf("deleteMedia")),

    /** Memories: a mark set or taken away (`ItemMarks`, the person's own set written whole). */
    MEDIA_MARK(setOf("putItemFavorites")),

    /**
     * Depot: a new file (`DepotFiles.upload`, its node's id the write's): the node made, its frames sealed at queue time
     * reserved, PUT and completed; a node left empty by a refusal given up (to the trash).
     */
    DEPOT_FILE_ADD(setOf("createNode", "startBlobUploads", "presignBlobUploadWindows", "completeFile", "deleteNode")),

    /**
     * Expenses: an entry added, edited or deleted (`PotSession.sendQueued`): its receipts reserved, PUT and completed, then
     * its revision appended naming the heads the person read.
     */
    POT_ENTRY(setOf("startBlobUploads", "presignBlobUploadWindows", "completeApplicationBlob", "appendPotRevisions")),
    ;

    internal companion object {
        /** The value a queued write names; null where this build no longer knows it (the write is then held as refused). */
        fun of(op: String): Queued? = entries.firstOrNull { it.name == op }
    }
}

/**
 * How one queued write ended this time (ADR 0153 decision 4): [Sent] (re-sealed under the keys current now, a re-key met
 * on the way included), [Held] for the person (what it would overwrite changed meanwhile: a conflict), [Refused] (the
 * target gone or no longer the person's to write), or [Waiting] (the server not reached: tried again at the next reconnect).
 * A reason is a code, never content.
 */
sealed interface QueuedOutcome {
    data object Sent : QueuedOutcome
    data class Held(val reason: String) : QueuedOutcome
    data class Refused(val reason: String) : QueuedOutcome
    data object Waiting : QueuedOutcome
}

/** A [Queued] write's sender: its online path called with the write's intent, never a copy of it. */
internal fun interface QueuedSender {
    suspend fun send(tenant: TenantSession, write: QueuedWrite): QueuedOutcome
}
