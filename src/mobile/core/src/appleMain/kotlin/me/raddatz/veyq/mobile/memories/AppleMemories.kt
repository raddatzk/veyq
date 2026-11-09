package me.raddatz.veyq.mobile.memories

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.session.TenantSession
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
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
