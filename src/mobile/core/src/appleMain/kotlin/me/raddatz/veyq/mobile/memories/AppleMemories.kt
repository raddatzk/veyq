package me.raddatz.veyq.mobile.memories

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.media.ByteSource
import me.raddatz.veyq.mobile.session.TenantSession
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseeko
import platform.posix.ftello
import platform.posix.fwrite
import platform.posix.memcpy
import platform.posix.remove

/**
 * Memories' media for the Apple app, what Swift cannot do with core's types itself: a tier's plaintext as `NSData`, read
 * off the main thread (`MemoryMedia.read`); a range of an original for the player (`RangeReader`); an original written to
 * a file as it is read, for the system's save sheet.
 */
@OptIn(ExperimentalForeignApi::class)
object AppleMemories {
    private fun ByteArray.toNSData(): NSData = if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

    /** [blob] of [item], whole (a tile, a preview); null where it does not read here. */
    @Throws(Exception::class)
    suspend fun read(tenant: TenantSession, item: MemoryMedia.Item, blob: MemoryMedia.Blob): NSData? = withContext(Dispatchers.Default) {
        MemoryMedia.read(tenant, item, blob)?.toNSData()
    }

    /** Up to [length] bytes of [reader] from [position] (fewer only at its end). */
    @Throws(Exception::class)
    suspend fun readRange(reader: RangeReader, position: Long, length: Int): NSData = withContext(Dispatchers.Default) {
        reader.read(position, length).toNSData()
    }

    /**
     * Uploads the file at [path] into Memory or chapter [nodeId] (`MediaUpload.upload`), read at any offset as it is sealed:
     * its given [type], [name] and [modifiedAt], and the tiers the app derived ([preview], [thumbnail], the size as shown).
     */
    @Throws(Exception::class)
    suspend fun upload(
        tenant: TenantSession, scopeId: String, nodeId: String, path: String, type: String?, name: String?, modifiedAt: Long?,
        preview: NSData?, thumbnail: NSData?, width: Int?, height: Int?, now: Long,
    ): MediaUpload.Result = withContext(Dispatchers.Default) {
        val file = fopen(path, "rb") ?: return@withContext MediaUpload.Result(MediaUpload.Outcome.FAILED, code = "unreadable")
        try {
            fseeko(file, 0, SEEK_END)
            val size = ftello(file)
            val source = ByteSource(size) { from, length ->
                val n = minOf(length.toLong(), size - from).coerceAtLeast(0).toInt()
                val out = ByteArray(n)
                if (n > 0) {
                    fseeko(file, from, SEEK_SET)
                    val read = out.usePinned { fread(it.addressOf(0), 1u, n.toULong(), file) }.toInt()
                    if (read != n) return@ByteSource out.copyOf(read)
                }
                out
            }
            MediaUpload.upload(
                tenant, scopeId, nodeId, MediaUpload.Original(source, type, name, modifiedAt),
                MediaUpload.Derived(preview?.toByteArray(), thumbnail?.toByteArray(), width, height), now,
            )
        } finally {
            fclose(file)
        }
    }

    private fun NSData.toByteArray(): ByteArray {
        val out = ByteArray(length.toInt())
        if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
        return out
    }

    /** Writes [item]'s original to [path] as it is read; false (and nothing left at [path]) when it did not finish. */
    @Throws(Exception::class)
    suspend fun download(tenant: TenantSession, item: MemoryMedia.Item, path: String): Boolean = withContext(Dispatchers.Default) {
        val out = fopen(path, "wb") ?: return@withContext false
        var done = false
        try {
            done = MemoryMedia.stream(tenant, item, item.original) { bytes ->
                if (bytes.isNotEmpty()) {
                    val n = bytes.usePinned { fwrite(it.addressOf(0), 1u, bytes.size.toULong(), out) }
                    if (n != bytes.size.toULong()) throw IllegalStateException("short write to $path")
                }
            }
        } finally {
            fclose(out)
            if (!done) remove(path)
        }
        done
    }
}
