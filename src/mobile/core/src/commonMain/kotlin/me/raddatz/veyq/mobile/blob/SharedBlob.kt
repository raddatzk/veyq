package me.raddatz.veyq.mobile.blob

import me.raddatz.veyq.mobile.PlatformApi
import kotlin.native.HiddenFromObjC
import me.raddatz.veyq.mobile.crypto.BlobMeta
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64

/**
 * A blob that shares a stored object instead of being uploaded again (ADR 0013 § 5), core's for every application — a
 * port of the web's `core/blob/sharedBlob.ts`. The client finds the file it is about to upload already stored for the node
 * — the same sealed size, then the same SHA-256 of the plaintext, read from the stored blob's own `blob-meta` — and asks the
 * server for a new blob ROW on that object (the contract's `SharedBlob`): its own client-minted id, the same `K_blob`
 * wrapped again under the new owner's key bound to the new row's (scope, owner, blob, role) (ADR 0072, no new seal
 * purpose), and its own `blob-meta`. The server copies the object's storage key, shape and manifest and compares nothing.
 *
 * Operator-blind as an upload is: the digest is sealed under the blob's own key, so only a holder of that key — who can read
 * the file anyway — can confirm a file by it. What the server learns is which stored blob the new row shares, the accepted
 * residual of ADR 0102 § What the server sees. The wrap is the web's byte for byte in its binding: `SharedBlobTest` opens a
 * row the web's `shareBlob` made.
 */
object SharedBlob {
    /** The blob-key seal's context: a wrap bound to the record it belongs to (a node, a medium) and to its role. */
    @PlatformApi
    @HiddenFromObjC
    fun keyContext(scopeId: String, ownerId: String, blobId: String, role: String) =
        Context("blob-key", "scopeId" to scopeId, "ownerId" to ownerId, "blobId" to blobId, "role" to role)

    /** The sealed size of [plaintextBytes] as a blob — the `byteSize` a stored blob of the same plaintext carries (an empty one is one frame). */
    fun sealedByteSize(plaintextBytes: Long): Long = plaintextBytes + V1.FRAME_OVERHEAD.toLong() * V1.chunkCount(plaintextBytes)

    /**
     * A stored blob a new row may share: where it lives (its [ownerId] and [role], what its wrap is bound to), its shape, its
     * wrap, and the [key] the wrap is sealed under (a node's, or the key a guest's medium opens under).
     */
    class Stored(
        internal val key: ByteArray, val ownerId: String, val role: String, val blobId: String, val chunkCount: Int, val byteSize: Long,
        internal val wrappedBlobKey: String,
    )

    /** What a new row's `blob-meta` seals ([BlobMeta]'s fields): the type, and where an upload of the file would seal them, its name, modification and digest. */
    class Meta(val type: String, val name: String? = null, val modifiedAt: Long? = null, val sha256: String? = null)

    /** The wire's `SharedBlob`, in place of an upload id; each application maps it to its generated model. */
    data class Request(val sourceBlobId: String, val blobId: String, val wrappedBlobKey: String, val wrappedBlobMeta: String?)

    /** A shared row: the request to send, and the new row's ref (what a sealed meta names: its id, shape and wrap). */
    class Shared(val request: Request, val blobId: String, val chunkCount: Int, val byteSize: Long, val wrappedBlobKey: String)

    /** The source's wrap did not open where it is said to live (a moved ref): nothing to share, the caller uploads. */
    class OpenRefused : Exception("shared_source_unreadable")

    /**
     * A new row [blobId] on the stored object of [source], owned by [ownerId] in [role] under [key]: the source's `K_blob`
     * unwrapped (bound to its own row) and wrapped again bound to the new row; [meta], where given, sealed under the same
     * `K_blob` as its `blob-meta`. Throws [OpenRefused] when the source's wrap does not open.
     */
    fun share(suite: V1, scopeId: String, source: Stored, key: ByteArray, ownerId: String, role: String, blobId: String, meta: Meta?): Shared {
        val blobKey = runCatching {
            suite.open(keyContext(scopeId, source.ownerId, source.blobId, source.role), source.key, unb64(source.wrappedBlobKey))
        }.getOrNull() ?: throw OpenRefused()
        try {
            val wrapped = b64(suite.seal(keyContext(scopeId, ownerId, blobId, role), key, blobKey))
            val sealedMeta = meta?.let { b64(BlobMeta.seal(suite, blobKey, it.type, it.name, it.modifiedAt, it.sha256)) }
            return Shared(Request(source.blobId, blobId, wrapped, sealedMeta), blobId, source.chunkCount, source.byteSize, wrapped)
        } finally {
            blobKey.fill(0)
        }
    }

    /**
     * The first of [candidates] that holds a file of [size] plaintext bytes: a candidate's sealed [byteSize] is compared
     * first, and only where one matches and carries a digest ([sha256]: null for a blob stored without one, never a match)
     * is the file's [digest] read — once —, then compared. Null where none does: the caller uploads.
     */
    suspend fun <T> findEqual(
        size: Long, candidates: List<T>, byteSize: (T) -> Long, sha256: (T) -> String?, digest: suspend () -> String,
    ): T? {
        val sealed = sealedByteSize(size)
        val withDigest = candidates.filter { byteSize(it) == sealed }.mapNotNull { c -> sha256(c)?.let { c to it } }
        if (withDigest.isEmpty()) return null
        val d = digest()
        return withDigest.firstOrNull { it.second == d }?.first
    }
}
