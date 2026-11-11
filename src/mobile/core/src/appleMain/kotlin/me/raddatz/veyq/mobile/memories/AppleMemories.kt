package me.raddatz.veyq.mobile.memories

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.blob.FileSink
import me.raddatz.veyq.mobile.blob.FileUploadSource
import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.session.TenantSession
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.posix.memcpy

/**
 * Memories' media for the Apple app, what Swift cannot do with core's types itself: a tier's plaintext as `NSData`, read
 * off the main thread (`MemoryMedia.read`); a range of an original for the player (`RangeReader`); a picked file as an
 * upload job; an original written to a file as it is read, for the system's save sheet.
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
     * The picked file at [url] added to Memory or chapter [nodeId] of [scopeId]: a job for the upload queue
     * (`MediaUpload.Job`), its tiers derived by the app's [deriver] — its given [type], [name] and [modifiedAt] (nil for
     * a copy the photos picker made, whose date is the copy's).
     */
    fun uploadJob(
        tenant: TenantSession, scopeId: String, nodeId: String, url: NSURL, name: String, size: Long, type: String?, modifiedAt: Long?,
        deriver: MediaUpload.Deriver,
    ): MediaUpload.Job = MediaUpload.Job(
        tenant, scopeId, nodeId, FileUploadSource(url, null, name, size, type, modifiedAt), deriver,
    ) { kotlin.time.Clock.System.now().toEpochMilliseconds() }

    /** The tiers the app derived ([preview], [thumbnail], the size as shown), as core takes them. */
    fun derived(preview: NSData?, thumbnail: NSData?, width: Int?, height: Int?): MediaUpload.Derived =
        MediaUpload.Derived(preview?.toByteArray(), thumbnail?.toByteArray(), width, height)

    private fun NSData.toByteArray(): ByteArray {
        val out = ByteArray(length.toInt())
        if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
        return out
    }

    /** The picked file [source] is (an upload job's): where the app's deriver reads it. */
    fun urlOf(source: me.raddatz.veyq.mobile.blob.UploadSource): NSURL? = (source as? FileUploadSource)?.url

    /** Writes [item]'s original to [path] as it is read (core's [FileSink]); false (and nothing left at [path]) when it did not finish. */
    @Throws(Exception::class)
    suspend fun download(tenant: TenantSession, item: MemoryMedia.Item, path: String): Boolean = withContext(Dispatchers.Default) {
        val out = FileSink.open(path) ?: return@withContext false
        out.fill(kept = { it }) { sink -> MemoryMedia.stream(tenant, item, item.original) { sink(it) } }
    }
}
