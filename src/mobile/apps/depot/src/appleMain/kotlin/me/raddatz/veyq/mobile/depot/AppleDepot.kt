package me.raddatz.veyq.mobile.depot

import me.raddatz.veyq.mobile.blob.DirectoryWalk
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.blob.FileUploadSource
import me.raddatz.veyq.mobile.session.TenantSession
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.create
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.blob.FileSave
import me.raddatz.veyq.mobile.blob.FileSink
import me.raddatz.veyq.mobile.sharing.AppleSharing
import me.raddatz.veyq.mobile.sharing.GuestLevel
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice

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

    /**
     * One entry Swift's walk of a picked directory met: its path below the directory, where it lies on the device, what it
     * is ([DirectoryWalk.Kind]: Swift says, the core keeps — `FolderUpload.keeps`) and a file's facts.
     */
    class FolderFile(
        val relativePath: String, val path: String, val kind: DirectoryWalk.Kind, val size: Long, val type: String, val modifiedAt: Long?,
    )

    /**
     * Depot's "Upload folder…": what Swift's walk of the directory at [root] met, as the core's walk entries
     * (`DepotUploads.uploadFolder` keeps and says them), each file read inside the directory's security scope.
     */
    fun found(root: NSURL, files: List<FolderFile>): List<DirectoryWalk.Found> = files.map { f ->
        val name = f.relativePath.substringAfterLast('/')
        val source = if (f.kind == DirectoryWalk.Kind.FILE) FileUploadSource(NSURL.fileURLWithPath(f.path), root, name, f.size, f.type, f.modifiedAt) else null
        DirectoryWalk.Found(f.relativePath, f.kind, source)
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

// Depot's part of Swift's door into a received link ([AppleSharing]): a guest's folder uploaded, a guest's file saved, as
// extensions of it, so Swift calls them where it did (`AppleSharing.shared.saveFile(…)`, a category of `AppleSharing`;
// ADR 0156, the pattern of Expenses' `AppleSessions` extensions).

/**
 * A READ_WRITE guest's folder upload into [level] ([DepotGuest.uploadFolder]), off the caller's thread: what it says, each
 * line — one that threw says the folder failed, never nothing.
 */
suspend fun AppleSharing.guestUploadFolder(level: GuestLevel, path: String, found: List<DirectoryWalk.Found>): List<Notice> =
    withContext(Dispatchers.Default) {
        try {
            DepotGuest.uploadFolder(level, null, path, found).said
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            listOf(Notice(Msg.DEPOT_FOLDER_FAILED, DirectoryWalk.rootName(null, path)))
        }
    }

/**
 * A guest's file saved at [path] (`FileModel.save` into core's [FileSink], which leaves nothing at [path] when it did not
 * finish), awaited to its end; null where there is nothing to save.
 */
@Throws(Exception::class)
suspend fun AppleSharing.saveFile(file: FileModel, path: String): FileSave.End? {
    val save = file.save { produce ->
        withContext(Dispatchers.Default) { FileSink.write(path, kept = { it }) { sink -> produce(sink) } }
    } ?: return null
    return save.state.first { it.end != null }.end
}
