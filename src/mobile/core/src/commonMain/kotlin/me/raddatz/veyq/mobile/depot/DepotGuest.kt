package me.raddatz.veyq.mobile.depot

import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.raddatz.veyq.mobile.api.apis.SharingApi
import me.raddatz.veyq.mobile.api.depot.apis.ShareFilesApi
import me.raddatz.veyq.mobile.api.depot.models.DropFolderRequest
import me.raddatz.veyq.mobile.api.depot.models.ShareFileCompleteRequest
import me.raddatz.veyq.mobile.api.models.CreateShareNodeRequest
import me.raddatz.veyq.mobile.blob.ApplicationBlobs
import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.blob.UploadQueue
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.crypto.BlobMeta
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.Naming
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.crypto.utf8
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.sharing.GuestAccess
import me.raddatz.veyq.mobile.sharing.GuestDoor
import me.raddatz.veyq.mobile.sharing.GuestPage
import me.raddatz.veyq.mobile.sharing.GuestSession

/**
 * Depot's answers to a link's guest (ADR 0150 decision 5; the web's Depot share bodies): a file opened as its page with a
 * guest's viewer (`FileModel`, `FileRights.Viewer.READ` / `READ_WRITE` — a READ_WRITE guest replaces), and a drop box's
 * WRITE_ONLY link as its drop page ([GuestDropBox]). Every read and write through the link's guest calls; nothing of an
 * account.
 */
object DepotGuest {
    /** A file of a READ or READ_WRITE link. */
    val file = GuestDoor { access, node, _ ->
        val tree = access.tree ?: return@GuestDoor GuestPage.NotShown(node)
        tree.node(node.nodeId) ?: return@GuestDoor GuestPage.NotShown(node)
        val viewer = if (access.role == GuestAccess.READ_WRITE) FileRights.Viewer.READ_WRITE else FileRights.Viewer.READ
        GuestPage.File(node, FileModel(node, GuestFileDoor(access), access.sharesCopies, viewer))
    }

    /** A drop box's WRITE_ONLY link. */
    val dropBox = GuestDoor { access, node, _ -> GuestPage.DropBox(node, GuestDropBox(access, node)) }

    /** The guest's tree node of [node], whose key opens its file. */
    private fun opened(access: GuestAccess, node: NodeAt): OpenNode? = access.tree?.node(node.nodeId)

    /** A file's page over the link (the member's `FileModel.Door`, the guest's reads and a READ_WRITE guest's replace). */
    internal class GuestFileDoor(private val access: GuestAccess) : FileModel.Door {
        override suspend fun file(node: NodeAt): DepotFiles.File? {
            val open = opened(access, node) ?: return null
            val r = ShareFilesApi(access.origin, httpOf(access)).listShareFiles(access.shareId, pwOf(access))
            if (!r.success) return null
            val item = r.body().items.firstOrNull { it.nodeId == node.nodeId } ?: return null
            val blob = item.fileBlob ?: return DepotFiles.File(node.scopeId, node.nodeId, null, 0, 0, null, null, null, null, null, null)
            val key = access.blobs.openKey(node.scopeId, node.nodeId, blob.blobId, DepotFiles.ROLE, open.key, blob.wrappedBlobKey)
            val meta = key?.let { k -> blob.wrappedBlobMeta?.let { runCatching { BlobMeta.open(suiteOf(access), k, unb64(it)) }.getOrNull() } }
            key?.fill(0)
            return DepotFiles.File(
                node.scopeId, node.nodeId, blob.blobId, blob.chunkCount, blob.byteSize, blob.wrappedBlobKey,
                blob.byteSize - V1.FRAME_OVERHEAD.toLong() * blob.chunkCount, meta?.type, meta?.name, meta?.modifiedAt, null,
            )
        }

        /** In a guest's identity folder (a READ_WRITE guest replaces nothing there): the node or an ancestor is one. */
        override fun inIdentityFolder(node: NodeAt): Boolean {
            val tree = access.tree ?: return false
            var at = tree.node(node.nodeId)
            while (at != null) {
                if (at.shareIdentityId != null) return true
                at = at.parentId?.let { tree.node(it) }
            }
            return false
        }

        private fun keyOf(file: DepotFiles.File): ByteArray {
            val open = access.tree?.node(file.nodeId) ?: throw IllegalStateException("not in the link")
            return access.blobs.openKey(file.scopeId, file.nodeId, file.blobId ?: error("no content"), DepotFiles.ROLE, open.key, file.wrappedBlobKey!!)
                ?: throw IllegalStateException("key_unreadable")
        }

        override suspend fun read(file: DepotFiles.File, first: Int, until: Int, sink: suspend (ByteArray) -> Unit) {
            access.blobs.Frames(file.blobId ?: error("no content"), file.chunkCount, keyOf(file)).read(first, minOf(until, file.chunkCount), sink)
        }

        override fun reader(file: DepotFiles.File): RangeReader = access.blobs.reader(file.blobId!!, file.chunkCount, file.size ?: 0, keyOf(file))

        /** A guest reads no member's name (ADR 0108 decision 2): nobody is named. */
        override suspend fun uploader(file: DepotFiles.File): Uploaders.Uploader? = null

        override fun replaceJob(file: DepotFiles.File, source: UploadSource, expectedBlobId: String?): DepotFiles.ReplaceJob =
            DepotFiles.ReplaceJob(file, source, expectedBlobId, source.name) { s, expected, progress ->
                replace(file, s, expected, progress)
            }

        /** A READ_WRITE guest's replace (the web's `guestFileUploadJob` with a `replace` target): content under a fresh key, completed. */
        private suspend fun replace(file: DepotFiles.File, s: UploadSource, expected: String?, progress: (Double) -> Unit): DepotFiles.Uploaded {
            val session = access.session.value ?: return DepotFiles.Uploaded(DepotFiles.Outcome.FAILED)
            val open = access.tree?.node(file.nodeId) ?: return DepotFiles.Uploaded(DepotFiles.Outcome.FAILED)
            return contentUpload(access, session, file.scopeId, open.id, open.key, open.epoch, s, expected, null, progress)
        }
    }

    internal fun httpOf(access: GuestAccess) = access.client.http
    internal fun pwOf(access: GuestAccess) = access.pwToken
    internal fun suiteOf(access: GuestAccess) = access.suite

    /**
     * Content [s] uploaded as file [nodeId]'s blob under [nodeKey] and completed (`completeShareFile`, the node's [epoch]
     * stated) over [expected], as the guest [session] — into [target] where the upload names one (a drop box's guest
     * folder's file).
     */
    internal suspend fun contentUpload(
        access: GuestAccess, session: GuestSession, scopeId: String, nodeId: String, nodeKey: ByteArray, epoch: Int, s: UploadSource,
        expected: String?, target: String?, progress: (Double) -> Unit,
    ): DepotFiles.Uploaded {
        val size = s.size
        val (uploadId, plan) = try {
            progress(0.0)
            access.blobs.reserveAndPumpAll(
                scopeId, nodeId, nodeKey, session.contributorToken,
                listOf(ApplicationBlobs.Outgoing(DepotFiles.ROLE, size, s.type ?: "application/octet-stream", s.name, s.modifiedAt, null, s::read)),
                nodeId = target ?: nodeId,
            ) { progress(it.toDouble() / size.coerceAtLeast(1)) }.single()
        } catch (e: ApplicationBlobs.UploadRefused) {
            return DepotFiles.Uploaded(if (e.code == "quota_exceeded") DepotFiles.Outcome.NO_ROOM else DepotFiles.Outcome.FAILED)
        }
        val r = ShareFilesApi(access.origin, httpOf(access)).completeShareFile(
            access.shareId, nodeId, ShareFileCompleteRequest(epoch, session.contributorToken, uploadId, expected), pwOf(access),
        )
        if (r.success) {
            return if (r.body().blobId == plan.blobId) DepotFiles.Uploaded(DepotFiles.Outcome.DONE, nodeId) else DepotFiles.Uploaded(DepotFiles.Outcome.FAILED)
        }
        val body = runCatching { r.response.bodyAsText() }.getOrDefault("")
        val o = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject }.getOrNull()
        val code = (o?.get("code") as? kotlinx.serialization.json.JsonPrimitive)?.content
        return when (code) {
            "stale_file" -> DepotFiles.Uploaded(DepotFiles.Outcome.STALE, nodeId, currentBlobId = (o?.get("currentBlobId") as? kotlinx.serialization.json.JsonPrimitive)?.content)
            "quota_exceeded" -> DepotFiles.Uploaded(DepotFiles.Outcome.NO_ROOM)
            else -> DepotFiles.Uploaded(DepotFiles.Outcome.FAILED)
        }
    }
}

/**
 * A drop box's page for the guest of its WRITE_ONLY link (ADR 0150 decision 5; the web's `DropBoxShareBody` over
 * `dropBox.ts`): the guest names itself — a fresh `K_up` wrapped to the drop box's drop public key, kept as its session in
 * the credential store —, and each file it adds goes into its own folder (made with its first file, its name the guest's,
 * sealed under `K_up`), as a file node created and filled as the guest. It sees nothing else of the drop box; what it sent
 * in this visit is listed by name.
 */
class GuestDropBox internal constructor(private val access: GuestAccess, val node: NodeAt) {
    /** The guest's session (null: it has not named itself). */
    val session: StateFlow<GuestSession?> get() = access.session

    private val _sent = MutableStateFlow<List<String>>(emptyList())

    /** The names of the files sent from this device in this visit, in order. */
    val sent: StateFlow<List<String>> = _sent.asStateFlow()

    /** Name the guest (the drop page's first step). */
    suspend fun name(displayName: String): Boolean = runCatching { access.nameUploader(displayName.trim()) }.isSuccess

    /** *Not you?* */
    fun forget() = access.forgetGuest()

    /** [sources] queued into the guest's folder; how many were (none before the guest named itself, or without a queue). */
    fun upload(sources: List<UploadSource>): Int {
        val queue = access.queue ?: return 0
        if (session.value == null || sources.isEmpty()) return 0
        queue.enqueue(sources.map { Job(it) }, node.name, null, null, null)
        return sources.size
    }

    /** One file sent now (a test's, and the queue's job): its own node in the guest's folder, its content, completed. */
    suspend fun send(source: UploadSource, progress: (Double) -> Unit = {}): DepotFiles.Uploaded {
        val session = session.value ?: return DepotFiles.Uploaded(DepotFiles.Outcome.FAILED)
        val kUp = access.uploaderKey(session) ?: return DepotFiles.Uploaded(DepotFiles.Outcome.FAILED)
        try {
            val folder = ensureFolder(session, kUp)
            val file = createFile(session, folder, source.name) ?: return DepotFiles.Uploaded(DepotFiles.Outcome.TAKEN_BY_OTHER)
            try {
                val done = DepotGuest.contentUpload(access, session, access.scopeId, file.first, file.second, 0, source, null, file.first, progress)
                if (done.outcome == DepotFiles.Outcome.DONE) _sent.value = _sent.value + source.name
                return done
            } finally {
                file.second.fill(0)
            }
        } finally {
            kUp.fill(0)
        }
    }

    private inner class Job(private val source: UploadSource) : UploadQueue.Job {
        override val name get() = source.name
        override val sizeBytes get() = source.size
        override suspend fun run(progress: (Double) -> Unit) {
            val r = try {
                send(source, progress)
            } finally {
                source.close()
            }
            when (r.outcome) {
                DepotFiles.Outcome.DONE -> {}
                DepotFiles.Outcome.NO_ROOM -> throw UploadQueue.Failed(UploadQueue.QUOTA_EXCEEDED)
                DepotFiles.Outcome.TAKEN_BY_OTHER -> throw UploadQueue.Failed(DepotFiles.Codes.NAME_TAKEN)
                else -> throw UploadQueue.Failed("failed")
            }
        }
    }

    /** The guest's folder as it opened: id, key and epoch. */
    private class Folder(val nodeId: String, val key: ByteArray, val epoch: Int, val scopeEpoch: Int)

    private var folder: Folder? = null

    /** The guest's own folder: made on its first file, the same one on every later call (its wrap opens under `K_up`). */
    private suspend fun ensureFolder(session: GuestSession, kUp: ByteArray): Folder {
        folder?.let { return it }
        val suite = DepotGuest.suiteOf(access)
        val nodeId = access.uuid()
        val scopeEpoch = access.rootScopeEpoch
        val binding = arrayOf<Pair<String, Any?>>("scopeId" to access.scopeId, "nodeId" to nodeId, "epoch" to scopeEpoch.toLong(), "placementRevision" to 0L)
        val folderKey = suite.p.randomBytes(32)
        val name = session.displayName ?: "?"
        val r = ShareFilesApi(access.origin, DepotGuest.httpOf(access)).ensureShareDropFolder(
            access.shareId,
            DropFolderRequest(
                contributorToken = session.contributorToken, nodeId = nodeId,
                encryptedName = b64(suite.sealField(Context("node-name", *binding), kUp, utf8(name))),
                slugToken = b64(suite.slugToken(kUp, node.nodeId, Naming.slugify(name))),
                scopeEpoch = scopeEpoch,
                wrappedContentKey = b64(suite.seal(Context("content-key", *binding), kUp, folderKey)),
            ),
            DepotGuest.pwOf(access),
        )
        folderKey.fill(0)
        check(r.success) { "drop_folder ${r.status}" }
        val res = r.body()
        val at = arrayOf<Pair<String, Any?>>("scopeId" to access.scopeId, "nodeId" to res.nodeId, "epoch" to res.scopeEpoch.toLong(), "placementRevision" to res.placementRevision)
        val key = suite.open(Context("content-key", *at), kUp, unb64(res.wrappedContentKey))
        return Folder(res.nodeId, key, res.epoch, res.scopeEpoch).also { folder = it }
    }

    /** A FILE node named [name] made in [folder] as the guest; its id and key, or null where the name is taken. */
    private suspend fun createFile(session: GuestSession, folder: Folder, name: String): Pair<String, ByteArray>? {
        val suite = DepotGuest.suiteOf(access)
        val nodeId = access.uuid()
        val binding = arrayOf<Pair<String, Any?>>("scopeId" to access.scopeId, "nodeId" to nodeId, "epoch" to folder.scopeEpoch.toLong(), "placementRevision" to 0L)
        val key = suite.p.randomBytes(32)
        val r = SharingApi(access.origin, DepotGuest.httpOf(access)).createShareNode(
            access.shareId, session.contributorToken,
            CreateShareNodeRequest(
                parentEpoch = folder.epoch, nodeId = nodeId, parentId = folder.nodeId, kind = "FILE",
                encryptedName = b64(suite.sealField(Context("node-name", *binding), folder.key, utf8(name))),
                slugToken = b64(suite.slugToken(folder.key, folder.nodeId, Naming.fileNameSlug(name))),
                scopeEpoch = folder.scopeEpoch,
                wrappedContentKey = b64(suite.seal(Context("content-key", *binding), folder.key, key)),
                orderIndex = 0,
            ),
            DepotGuest.pwOf(access),
        )
        if (r.status == 409) {
            key.fill(0)
            return null
        }
        check(r.success && r.body().nodeId == nodeId) { "share_node ${r.status}" }
        return nodeId to key
    }
}
