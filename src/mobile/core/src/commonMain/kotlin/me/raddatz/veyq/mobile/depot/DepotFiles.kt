package me.raddatz.veyq.mobile.depot

import io.ktor.client.statement.bodyAsText
import me.raddatz.veyq.mobile.api.depot.apis.FilesApi
import me.raddatz.veyq.mobile.session.NodeCreate
import me.raddatz.veyq.mobile.blob.ApplicationBlobs
import me.raddatz.veyq.mobile.crypto.BlobMeta
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.session.TenantSession

/**
 * Depot's files as a member reads them (the web's `features/depot/`, `docs/apps/depot.md`): a `FILE` node is core's — its
 * name, slug token and key — and its content one core attachment in the node's `FILE_CONTENT` slot, its key wrapped under
 * the file's key bound to that slot (`v1.json#file_blob`), its type, uploaded name and modified time the blob's sealed meta
 * (`blob-meta`, `BlobMeta`); its size is the blob's, less a frame's overhead each. Depot writes no node meta. The listing is
 * the server's `listFiles` per folder, joined with the tenant's tree by node id.
 */
object DepotFiles {
    const val FILE = "FILE"
    const val DROPBOX = "DEPOT_DROPBOX"
    const val ROLE = "FILE_CONTENT"

    /** A file as its row and its page show it; [blobId] null while its upload has not completed ("not uploaded yet"). */
    class File(
        val scopeId: String,
        val nodeId: String,
        val blobId: String?,
        val chunkCount: Int,
        val byteSize: Long,
        internal val wrappedBlobKey: String?,
        /** The plaintext's size: the blob's less [V1.FRAME_OVERHEAD] a frame. */
        val size: Long?,
        /** The sealed meta's type and uploaded name — claims only, never what decides how the bytes are shown. */
        val type: String?,
        val uploadedName: String?,
        val modifiedAt: Long?,
        /** Who uploaded the content: `user:<id>` a member, `share:<identityId>` a link's guest (`uploaderKey`). */
        val uploaderKey: String?,
    ) {
        val hasContent: Boolean get() = blobId != null
    }

    /** Every file of the tenant's trees with its content's reference and opened meta, by node id. */
    @Throws(Exception::class)
    suspend fun list(tenant: TenantSession): Map<String, File> {
        val out = HashMap<String, File>()
        for ((scopeId, nodes) in tenant.trees()) {
            val files = nodes.filter { it.kind == FILE }
            if (files.isEmpty()) continue
            val byId = files.associateBy { it.id }
            for (parentId in files.map { it.parentId }.distinct()) {
                val page = runCatching { tenant.account.call { FilesApi(tenant.base, tenant.account.http).listFiles(scopeId, parentId) } }.getOrNull() ?: continue
                for (item in page.items) {
                    val node = byId[item.nodeId] ?: continue
                    out[node.id] = file(tenant, node, item)
                }
            }
        }
        return out
    }

    /**
     * [file]'s content handed to [sink] a frame at a time, in order (frames [first] to [until], exclusive): a file of any size
     * is read without holding it whole, a page that decides by the bytes reads the first frame alone. Its key must open
     * under the file's own key bound to its slot (a reference moved from another node fails here, before any fetch).
     */
    @Throws(Exception::class)
    suspend fun read(tenant: TenantSession, file: File, first: Int = 0, until: Int = Int.MAX_VALUE, sink: suspend (ByteArray) -> Unit) {
        val blobId = file.blobId ?: throw IllegalStateException("no content")
        val node = tenant.node(file.scopeId, file.nodeId) ?: throw IllegalStateException("not in the tree")
        val blobs = ApplicationBlobs(tenant)
        val key = blobs.openKey(file.scopeId, node, blobId, ROLE, file.wrappedBlobKey!!) ?: throw IllegalStateException("key_unreadable")
        blobs.framesTo(file.scopeId, blobId, file.chunkCount, key, first, minOf(until, file.chunkCount), sink)
    }

    /** The name [file] (node [nodeName]) is downloaded as (core's `downloadName`: the node's name, else its uploaded one). */
    fun downloadName(file: File?, nodeName: String): String =
        me.raddatz.veyq.mobile.blob.DownloadName.of(file?.type, "file", nodeName, file?.uploadedName)

    /** What an upload or a replace ended in. */
    enum class Outcome {
        DONE,
        /** A sibling of another kind (a folder, a drop box) holds the name: nothing was sent (`depot.nameTakenBy`). */
        TAKEN_BY_OTHER,
        /** A file of the name is here: the person replaces it, or picks another name ([Uploaded.existing]). */
        EXISTS,
        /** The file's content changed since it was read (`stale_file`): replace it anyway over [Uploaded.currentBlobId]. */
        STALE,
        /** Its space's key is being renewed: try again in a moment. */
        BUSY,
        /** The tenant has no room left for it (`quota_exceeded`). */
        NO_ROOM,
        FAILED,
    }

    class Uploaded(val outcome: Outcome, val nodeId: String? = null, val existing: String? = null, val currentBlobId: String? = null)

    /**
     * The sibling of [parentId] (null: the space's top) a new file named [name] would collide with (the web's
     * `collidingSibling`): the name folded as a file's, each sibling's as its own kind's ([tokenInput]); null where none does.
     */
    @Throws(Exception::class)
    suspend fun collision(tenant: TenantSession, scopeId: String, parentId: String?, name: String, tokenInput: (String, String) -> String?): OpenNode? {
        val folded = tokenInput(FILE, name) ?: return null
        return tenant.trees()[scopeId].orEmpty().firstOrNull { it.parentId == parentId && tokenInput(it.kind, it.name) == folded }
    }

    /**
     * Uploads a new file [name] of [size] bytes under [parentId] (null: the space's top) — the web's `fileUploadJob`: the
     * name checked against the level first, the `FILE` node created (no meta), its content sealed under a fresh key and
     * sent frame by frame from [read], then attached (`completeFile` at the node's epoch). A failure after the node was
     * created moves it to the trash, unless its content was attached after all (an answer lost on the way).
     */
    @Throws(Exception::class)
    suspend fun upload(
        tenant: TenantSession, scopeId: String, parentId: String?, name: String, size: Long, type: String, modifiedAt: Long?,
        tokenInput: (String, String) -> String?, read: suspend (from: Long, length: Int) -> ByteArray,
    ): Uploaded {
        val n = name.trim()
        collision(tenant, scopeId, parentId, n, tokenInput)?.let { other ->
            return if (other.kind == FILE) Uploaded(Outcome.EXISTS, existing = other.id) else Uploaded(Outcome.TAKEN_BY_OTHER)
        }
        val created = when (val r = NodeCreate.create(tenant, scopeId, parentId, FILE, n, me.raddatz.veyq.mobile.crypto.Naming.fileNameSlug(n))) {
            is NodeCreate.Result.Done -> r.created
            is NodeCreate.Result.Refused -> return Uploaded(if (r.reason == NodeCreate.Reason.NAME_TAKEN) Outcome.TAKEN_BY_OTHER else Outcome.FAILED)
        }
        tenant.forgetTree()
        val done = attach(tenant, scopeId, created.nodeId, size, type, n, modifiedAt, null, read)
        if (done.outcome != Outcome.DONE) abandon(tenant, scopeId, parentId, created.nodeId)
        return done
    }

    /**
     * Replaces [file]'s content with [size] bytes from [read] (the web's replace: the node keeps its name; the server frees
     * the old content in the same write, a compare-and-set on [expectedBlobId] — there are no versions).
     */
    @Throws(Exception::class)
    suspend fun replace(
        tenant: TenantSession, file: File, size: Long, type: String, name: String?, modifiedAt: Long?, expectedBlobId: String?,
        read: suspend (from: Long, length: Int) -> ByteArray,
    ): Uploaded = attach(tenant, file.scopeId, file.nodeId, size, type, name, modifiedAt, expectedBlobId, read)

    /** One content upload and its attachment at the node's epoch ([NodeEpochWrite]: a new key epoch seals all again). */
    private suspend fun attach(
        tenant: TenantSession, scopeId: String, nodeId: String, size: Long, type: String, name: String?, modifiedAt: Long?,
        expectedBlobId: String?, read: suspend (from: Long, length: Int) -> ByteArray,
    ): Uploaded {
        val (_, scopeEpoch) = tenant.scopeKey(scopeId) ?: return Uploaded(Outcome.FAILED)
        var node: OpenNode? = tenant.node(scopeId, nodeId) ?: return Uploaded(Outcome.FAILED)
        var ended: Uploaded? = null
        val result = me.raddatz.veyq.mobile.session.NodeEpochWrite.run(
            pull = { me.raddatz.veyq.mobile.session.NodeRekey.pull(tenant, scopeId, listOf(nodeId)) },
            belowFloor = { node!!.scopeEpoch < scopeEpoch },
            reread = {
                tenant.forgetTree()
                node = tenant.node(scopeId, nodeId)
                node != null
            },
            attempt = {
                val n = node!!
                val code = try {
                    val (uploadId, plan) = ApplicationBlobs(tenant).reserveAndPumpFrames(scopeId, n, ROLE, size, type, name, modifiedAt, read)
                    val r = tenant.account.respond {
                        FilesApi(tenant.base, tenant.account.http).completeFile(
                            scopeId, nodeId,
                            me.raddatz.veyq.mobile.api.depot.models.FileCompleteRequest(nodeEpoch = n.epoch, uploadId = uploadId, expectedBlobId = expectedBlobId),
                        )
                    }
                    if (r.success) {
                        // the server echoes the blob it stored: anything else was substituted under the seals
                        ended = if (r.body().blobId == plan.blobId) Uploaded(Outcome.DONE, nodeId) else Uploaded(Outcome.FAILED)
                        null
                    } else {
                        val body = runCatching { r.response.bodyAsText() }.getOrDefault("")
                        val o = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject }.getOrNull()
                        val code = (o?.get("code") as? kotlinx.serialization.json.JsonPrimitive)?.content
                        if (code == "stale_file") {
                            ended = Uploaded(Outcome.STALE, nodeId, currentBlobId = (o?.get("currentBlobId") as? kotlinx.serialization.json.JsonPrimitive)?.content)
                        }
                        code ?: "complete ${r.status}"
                    }
                } catch (e: ApplicationBlobs.UploadRefused) {
                    if (e.code == "quota_exceeded") ended = Uploaded(Outcome.NO_ROOM)
                    e.code
                }
                val answer = when (code) {
                    null -> me.raddatz.veyq.mobile.session.NodeEpochWrite.Answer.DONE
                    "node_rekey_pending" -> me.raddatz.veyq.mobile.session.NodeEpochWrite.Answer.REKEY_PENDING
                    "stale_epoch" -> me.raddatz.veyq.mobile.session.NodeEpochWrite.Answer.STALE_EPOCH
                    else -> me.raddatz.veyq.mobile.session.NodeEpochWrite.Answer.OTHER
                }
                answer to code
            },
        )
        tenant.forgetTree()
        return when (result) {
            is me.raddatz.veyq.mobile.session.NodeEpochWrite.Result.Answered -> ended ?: Uploaded(Outcome.FAILED)
            me.raddatz.veyq.mobile.session.NodeEpochWrite.Result.Busy -> Uploaded(Outcome.BUSY)
            else -> Uploaded(Outcome.FAILED)
        }
    }

    /** A created file whose content did not attach goes to the trash — unless it is attached after all (`abandon`). */
    private suspend fun abandon(tenant: TenantSession, scopeId: String, parentId: String?, nodeId: String) {
        val page = runCatching { tenant.account.call { FilesApi(tenant.base, tenant.account.http).listFiles(scopeId, parentId) } }.getOrNull()
        val item = page?.items?.firstOrNull { it.nodeId == nodeId }
        if (item != null && item.fileBlob != null) return
        runCatching { me.raddatz.veyq.mobile.session.NodeTrash.delete(tenant, scopeId, nodeId) }
        tenant.forgetTree()
    }

    private fun file(tenant: TenantSession, node: OpenNode, item: me.raddatz.veyq.mobile.api.depot.models.FileListItem): File {
        val blob = item.fileBlob
            ?: return File(node.scopeId, node.id, null, 0, 0, null, null, null, null, null, item.uploaderKey)
        val key = ApplicationBlobs(tenant).openKey(node.scopeId, node, blob.blobId, ROLE, blob.wrappedBlobKey)
        val meta = if (key == null) null else blob.wrappedBlobMeta?.let { m -> runCatching { BlobMeta.open(tenant.account.suite, key, unb64(m)) }.getOrNull() }
        return File(
            node.scopeId, node.id, blob.blobId, blob.chunkCount, blob.byteSize, blob.wrappedBlobKey,
            blob.byteSize - V1.FRAME_OVERHEAD.toLong() * blob.chunkCount,
            meta?.type, meta?.name, meta?.modifiedAt, item.uploaderKey,
        )
    }
}
