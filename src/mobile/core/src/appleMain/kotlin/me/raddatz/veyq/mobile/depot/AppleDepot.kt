package me.raddatz.veyq.mobile.depot

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.session.TenantSession
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseeko
import platform.posix.fwrite
import platform.posix.remove

/**
 * Depot's files for the Apple app: what Swift cannot hand core itself (a suspending reader), done here over a path — an
 * upload reads the picked file at any offset a frame at a time, a download writes the plaintext to a file as it is read,
 * a view reads the frames it needs. Nothing is held whole but what a view shows.
 */
@OptIn(ExperimentalForeignApi::class)
object AppleDepot {
    private fun ByteArray.toNSData(): NSData = if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

    /** Runs [use] over a reader of the file at [path] (the plaintext from an offset, a frame long at most). */
    private suspend fun <T> reading(path: String, use: suspend (suspend (Long, Int) -> ByteArray) -> T): T {
        val file = fopen(path, "rb") ?: throw IllegalStateException("cannot read $path")
        try {
            return use { from, length ->
                if (fseeko(file, from, SEEK_SET) != 0) throw IllegalStateException("cannot seek $path")
                val buf = ByteArray(length)
                if (length == 0) return@use buf
                val n = buf.usePinned { fread(it.addressOf(0), 1u, length.toULong(), file) }.toInt()
                buf.copyOf(n)
            }
        } finally {
            fclose(file)
        }
    }

    /** Uploads the file at [path] as a new file [name] under [parentId] (`DepotFiles.upload`). */
    @Throws(Exception::class)
    suspend fun upload(
        tenant: TenantSession, scopeId: String, parentId: String?, path: String, name: String, size: Long, type: String, modifiedAt: Long?,
    ): DepotFiles.Uploaded = withContext(Dispatchers.Default) {
        reading(path) { read -> DepotFiles.upload(tenant, scopeId, parentId, name, size, type, modifiedAt, AppKinds::tokenInput, read) }
    }

    /** Replaces [file]'s content with the file at [path] over [expectedBlobId] (`DepotFiles.replace`). */
    @Throws(Exception::class)
    suspend fun replace(
        tenant: TenantSession, file: DepotFiles.File, path: String, name: String, size: Long, type: String, modifiedAt: Long?, expectedBlobId: String?,
    ): DepotFiles.Uploaded = withContext(Dispatchers.Default) {
        reading(path) { read -> DepotFiles.replace(tenant, file, size, type, name, modifiedAt, expectedBlobId, read) }
    }

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
