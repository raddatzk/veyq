package me.raddatz.veyq.mobile.depot

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.blob.FileUploadSource
import me.raddatz.veyq.mobile.session.TenantSession
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.remove

/**
 * Depot's files for the Apple app: what Swift cannot hand core itself (a suspending reader), done here over a file — an
 * upload job reads the picked file at any offset a frame at a time ([FileUploadSource]), a download writes the plaintext
 * to a file as it is read, a view reads the frames it needs. Nothing is held whole but what a view shows.
 */
@OptIn(ExperimentalForeignApi::class)
object AppleDepot {
    private fun ByteArray.toNSData(): NSData = if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

    /** The picked file at [url] uploaded as a new file under [parentId] of [scopeId]: a job for the upload queue. */
    fun uploadJob(
        tenant: TenantSession, scopeId: String, parentId: String?, url: NSURL, name: String, size: Long, type: String, modifiedAt: Long?,
    ): DepotFiles.UploadJob = DepotFiles.UploadJob(tenant, scopeId, parentId, FileUploadSource(url, null, name, size, type, modifiedAt), AppKinds::tokenInput)

    /** One file of a picked directory: its path below the directory, where it lies on the device, and its facts. */
    class FolderFile(val relativePath: String, val path: String, val size: Long, val type: String, val modifiedAt: Long?)

    /**
     * Depot's "Upload folder…": the folders of the directory at [root] (named [rootName]) whose [files] Swift listed made
     * under [parentId] (`FolderUpload.prepare`), its files handed back as jobs for the upload queue, each read inside the
     * directory's security scope.
     */
    @Throws(Exception::class)
    suspend fun prepareFolder(
        tenant: TenantSession, scopeId: String, parentId: String?, root: NSURL, rootName: String, files: List<FolderFile>,
    ): FolderUpload.Report = withContext(Dispatchers.Default) {
        val entries = files.map { f ->
            val name = f.relativePath.substringAfterLast('/')
            FolderUpload.Entry(f.relativePath, FileUploadSource(NSURL.fileURLWithPath(f.path), root, name, f.size, f.type, f.modifiedAt))
        }
        FolderUpload.prepare(tenant, scopeId, parentId, rootName, entries, AppKinds::tokenInput)
    }

    /** [file]'s content replaced with the picked file at [url] over [expectedBlobId]: a job for the upload queue. */
    fun replaceJob(
        tenant: TenantSession, file: DepotFiles.File, url: NSURL, name: String, size: Long, type: String, modifiedAt: Long?, expectedBlobId: String?,
    ): DepotFiles.ReplaceJob = DepotFiles.ReplaceJob(tenant, file, FileUploadSource(url, null, name, size, type, modifiedAt), expectedBlobId, name)

    /** Writes [file]'s content to [path] as it is read; false (and nothing left at [path]) when it did not finish. */
    @Throws(Exception::class)
    suspend fun download(tenant: TenantSession, file: DepotFiles.File, path: String): Boolean = withContext(Dispatchers.Default) {
        val out = fopen(path, "wb") ?: return@withContext false
        var done = false
        try {
            DepotFiles.read(tenant, file) { bytes ->
                if (bytes.isNotEmpty()) {
                    val n = bytes.usePinned { fwrite(it.addressOf(0), 1u, bytes.size.toULong(), out) }
                    if (n != bytes.size.toULong()) throw IllegalStateException("short write to $path")
                }
            }
            done = true
        } finally {
            fclose(out)
            if (!done) remove(path)
        }
        done
    }

    /** What a file's page shows: the decision (`FileView`) and what it needs read — an image's bytes, a text's characters. */
    class Viewed(val decision: FileView.Decision, val data: NSData?, val text: String?)

    /** [file]'s view: its first frame decided by its bytes (the node's name a claim only when it has an extension). */
    @Throws(Exception::class)
    suspend fun view(tenant: TenantSession, file: DepotFiles.File, nodeName: String): Viewed = withContext(Dispatchers.Default) {
        var head = ByteArray(0)
        DepotFiles.read(tenant, file, 0, 1) { head = it }
        val named = if (me.raddatz.veyq.mobile.blob.Sniff.extensionOf(nodeName).isNotEmpty()) nodeName else file.uploadedName
        val d = FileView.decide(head, file.chunkCount, file.size ?: 0, file.type, named)
        when (d.kind) {
            FileView.Kind.IMAGE -> Viewed(d, read(tenant, file, file.chunkCount), null)
            FileView.Kind.TEXT -> {
                val parts = ArrayList<ByteArray>()
                DepotFiles.read(tenant, file, 0, d.frames) { parts += it }
                val all = parts.fold(ByteArray(0)) { a, b -> a + b }
                Viewed(d, null, me.raddatz.veyq.mobile.blob.Sniff.decodeText(all.copyOf(minOf(all.size, FileView.TEXT_CAP))))
            }
            else -> Viewed(d, null, null)
        }
    }

    /** [file]'s first [frames] frames' plaintext, joined (a view's head, an image, a text's first MiB). */
    @Throws(Exception::class)
    suspend fun read(tenant: TenantSession, file: DepotFiles.File, frames: Int): NSData = withContext(Dispatchers.Default) {
        val parts = ArrayList<ByteArray>()
        DepotFiles.read(tenant, file, 0, frames) { parts += it }
        val all = ByteArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) {
            p.copyInto(all, o)
            o += p.size
        }
        all.toNSData()
    }
}
