package me.raddatz.veyq.mobile.memories

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.blob.FileSink
import me.raddatz.veyq.mobile.blob.FileUploadSource
import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.depot.FileSave
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.posix.memcpy

/**
 * Memories for the Apple app, what Swift cannot do with core's types itself: a tile's and a lightbox picture's plaintext as
 * `NSData`, read off the main thread (`GalleryModel.tile`, `Lightbox.picture`); a range of an original for the player
 * (`RangeReader`); a picked file as an upload's source; an original saved where the person chose, by core's [FileSink].
 */
@OptIn(ExperimentalForeignApi::class)
object AppleMemories {
    private fun ByteArray.toNSData(): NSData = if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

    /** Medium [mediaId]'s tile in [gallery], whole; null where it has none or it does not read. */
    @Throws(Exception::class)
    suspend fun tile(gallery: GalleryModel, mediaId: String): NSData? = withContext(Dispatchers.Default) {
        gallery.tile(mediaId)?.toNSData()
    }

    /** Chapter [nodeId]'s cover picture as stored, for its row in [gallery] (`GalleryModel.chapterCover`); null where it has none. */
    @Throws(Exception::class)
    suspend fun chapterCover(gallery: GalleryModel, nodeId: String): NSData? = withContext(Dispatchers.Default) {
        gallery.chapterCover(nodeId)?.toNSData()
    }

    /** Medium [mediaId]'s picture in [lightbox], whole; null where it shows none. */
    @Throws(Exception::class)
    suspend fun picture(lightbox: Lightbox, mediaId: String): NSData? = withContext(Dispatchers.Default) {
        lightbox.picture(mediaId)?.toNSData()
    }

    /** Up to [length] bytes of [reader] from [position] (fewer only at its end). */
    @Throws(Exception::class)
    suspend fun readRange(reader: RangeReader, position: Long, length: Int): NSData = withContext(Dispatchers.Default) {
        reader.read(position, length).toNSData()
    }

    /**
     * The picked file at [url] as an upload reads it, a frame at a time at any offset: its given [type], [name] and
     * [modifiedAt] (nil for a copy the photos picker made, whose date is the copy's).
     */
    fun source(url: NSURL, name: String, size: Long, type: String?, modifiedAt: Long?): UploadSource =
        FileUploadSource(url, null, name, size, type, modifiedAt)

    /** The tiers the app derived ([preview], [thumbnail], the size as shown), as core takes them. */
    fun derived(preview: NSData?, thumbnail: NSData?, width: Int?, height: Int?): MediaUpload.Derived =
        MediaUpload.Derived(preview?.toByteArray(), thumbnail?.toByteArray(), width, height)

    private fun NSData.toByteArray(): ByteArray {
        val out = ByteArray(length.toInt())
        if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
        return out
    }

    /** The picked file [source] is (an upload's): where the app's deriver reads it, and its copy is removed. */
    fun urlOf(source: UploadSource): NSURL? = (source as? FileUploadSource)?.url

    /**
     * The original [lightbox] shows saved at [path] (`Lightbox.save` into core's [FileSink], which leaves nothing at [path]
     * when it did not finish), awaited to its end; null where there is nothing to save (a blocked medium).
     */
    @Throws(Exception::class)
    suspend fun save(lightbox: Lightbox, path: String): FileSave.End? {
        val save = lightbox.save { produce ->
            withContext(Dispatchers.Default) { FileSink.write(path, kept = { it }) { sink -> produce(sink) } }
        } ?: return null
        return save.state.first { it.end != null }.end
    }
}
