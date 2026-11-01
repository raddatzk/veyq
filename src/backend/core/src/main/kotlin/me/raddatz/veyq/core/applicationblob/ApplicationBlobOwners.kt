package me.raddatz.veyq.core.applicationblob

/** What one role of an application's stored blobs allows (ADR 0112 decision 2). */
data class ApplicationBlobRole(
    /** How many unreleased blobs of the role one owner holds at most — a completion past it is `too_many_blobs`. */
    val maxPerOwner: Int,
    /** Whether a `READ_WRITE` link guest may add one (a guest's start and its completion). */
    val guestMayAdd: Boolean,
)

/** Where an owner stands when a blob is completed for it — the row's state at birth ([ApplicationBlobs.attach]). */
enum class OwnerState {
    /** The owner has written nothing yet (Expenses: an entry before its first revision): nothing keeps the blob. */
    NEW,

    /** The owner keeps its blobs now (a live record): the blob is born kept. */
    KEEPING,

    /** The owner has ended (Expenses: its latest revision a tombstone): the blob is born released with it, and the
     *  owner's next keep — a restore — keeps it as it keeps the others. */
    ENDED,
}

/** The application's answer to a completion for an owner ([ApplicationBlobOwners.admit]). */
sealed interface OwnerAdmission {
    data class Admitted(val state: OwnerState) : OwnerAdmission

    /** The owner may hold no blob in this node (Expenses: an entry of another Pot), or the blob's id names one of the
     *  application's own records — a reportable item's id beside the blob's (`id_taken`). */
    object Taken : OwnerAdmission
}

/**
 * What an application implements to store its blobs through core's blob service (ADR 0112 decision 2) — its roles, and
 * which of its rows keeps a blob; every platform duty over the blobs is the service's. The application also declares
 * each role to core's member start with its size cap ([me.raddatz.veyq.core.blob.MemberBlobRoles]), and tells the
 * service in its own writes which blobs an owner keeps and which it released ([ApplicationBlobs.keep],
 * [ApplicationBlobs.releaseOwners], [ApplicationBlobs.release]); a report of its own record asks the service for the
 * record's blobs ([ApplicationBlobs.blobIdsOfOwner]).
 *
 * **Exactly one implementation per kind** ([accepts]), as for the other kind seams: a kind none accepts stores no blob
 * through the service, and two that accept it refuse it too (`ApplicationBlobs.ownersOf`). Core names no application's
 * kind or role: it asks every implementation.
 */
interface ApplicationBlobOwners {
    /** Whether the blobs of a node of [nodeKind] are this application's. */
    fun accepts(nodeKind: String): Boolean

    /** The roles this application stores through the service, each with its rule. */
    fun roles(): Map<String, ApplicationBlobRole>

    /**
     * Lock the application's rows of [ownerId] — in a completion's transaction, BEFORE the node's row: an application's
     * writers take their own rows first, then the service's, then the node's ([ApplicationBlobs]' lock order). None for an
     * owner with no row yet.
     */
    fun lockOwner(ownerId: String) {}

    /**
     * Whether the owner [ownerId] may hold the blob [blobRowId] in the node [nodeId] of [scopeId], and where it stands —
     * asked under the node's lock, which every writer of the application's records holds, so a plain read sees every
     * record row committed before.
     */
    fun admit(scopeId: String, nodeId: String, ownerId: String, blobRowId: String): OwnerAdmission
}
