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

/**
 * Depot's files for the Apple app: what Swift cannot hand core itself — an upload reads the picked file at any offset a
 * frame at a time ([FileUploadSource]), a file page's shown bytes cross as `NSData`. Nothing is held whole but what a view
 * shows, and nothing decrypted is written to show it.
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

    /** The picked file at [url] as an upload reads it, a frame at a time at any offset (a file page's replace, `FileModel.replace`). */
    fun source(url: NSURL, name: String, size: Long, type: String, modifiedAt: Long?): me.raddatz.veyq.mobile.blob.UploadSource =
        FileUploadSource(url, null, name, size, type, modifiedAt)

    /**
     * The bytes a file's page shows from memory — an image's, a PDF's (`FileModel.Shown`) — handed to Swift as they are;
     * nothing is written to show them (ADR 0047). Null for any other view.
     */
    fun bytes(shown: FileModel.Shown): NSData? = when (shown) {
        is FileModel.Shown.Image -> shown.bytes.toNSData()
        is FileModel.Shown.Pdf -> shown.bytes.toNSData()
        else -> null
    }
}
