package me.raddatz.veyq.mobile.memories

import me.raddatz.veyq.mobile.media.Derivation
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import me.raddatz.veyq.mobile.blob.FileSink
import me.raddatz.veyq.mobile.blob.FileUploadSource
import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.blob.FileSave
import me.raddatz.veyq.mobile.memories.banner.BannerEditor
import me.raddatz.veyq.mobile.memories.banner.BannerWrite
import me.raddatz.veyq.mobile.memories.people.PersonMemories
import me.raddatz.veyq.mobile.memories.place.PlaceEditor
import me.raddatz.veyq.mobile.memories.tracks.TrackList
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.posix.memcpy

/**
 * Memories for the Apple app, what Swift cannot do with core's types itself: a tile's and a lightbox picture's plaintext as
 * `NSData`, read off the main thread (`GalleryModel.tile`, `Lightbox.picture`); a range of an original for the player
 * (`RangeReader`); a picked file as an upload's source; an original saved where the person chose, by core's [FileSink]; a
 * Memory's curation (ADR 0144): the banner's pictures as `NSData`, the platform's banner decoder as core's
 * [BannerWrite.Pictures], the banner dialog's runs and a person's section read off the main thread, and any core state
 * watched.
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
    fun derived(preview: NSData?, thumbnail: NSData?, width: Int?, height: Int?): Derivation.Derived =
        Derivation.Derived(preview?.toByteArray(), thumbnail?.toByteArray(), width, height)

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

    /** GPX track [mediaId] of [tracks] saved at [path] as [save] saves an original (ADR 0143 D8's *Download GPX*). */
    @Throws(Exception::class)
    suspend fun saveTrack(tracks: TrackList, mediaId: String, path: String): FileSave.End? {
        val save = tracks.save(mediaId) { produce ->
            withContext(Dispatchers.Default) { FileSink.write(path, kept = { it }) { sink -> produce(sink) } }
        } ?: return null
        return save.state.first { it.end != null }.end
    }

    /** A watch the app stops when its view goes; a run (the banner's save) the app cancels by stopping it. */
    class Watch internal constructor(private val job: Job) {
        fun stop() = job.cancel()
    }

    /**
     * [flow] watched: [listener] is called at once and on every change, on core's threads, until the watch is stopped. A
     * Swift box reads the model's `current` on the main actor then (ParticipantsPicker, CaptureTimeEditor, BannerEditor,
     * PersonMemories: their state's types reach Swift typed through `current`, the flow's only untyped).
     */
    fun watch(flow: StateFlow<*>, listener: (Any?) -> Unit): Watch =
        Watch(CoroutineScope(Dispatchers.Default).launch { flow.collect { listener(it) } })

    // ---- a Memory's curation (ADR 0144) ----

    /** The banner's picture (an image, a video's poster) of [gallery] as stored; null for none or one that does not read. */
    @Throws(Exception::class)
    suspend fun bannerPicture(gallery: GalleryModel): NSData? = withContext(Dispatchers.Default) { gallery.bannerPicture()?.toNSData() }

    /** The picture the platform decoded for the banner's dialog's previews ([BannerWrite.Picked.picture]). */
    fun pickedPicture(picked: BannerWrite.Picked): NSData = picked.picture.toNSData()

    /**
     * The platform's banner decoder as core's [BannerWrite.Pictures] (Swift cannot implement a `suspend fun`): [image] and
     * [poster] are handed the picked file's URL and answer once — on any thread — with the cover's JPEG (an image upright,
     * a video's first frame), or null where it does not decode here.
     */
    fun bannerPictures(
        image: (NSURL, (NSData?) -> Unit) -> Unit,
        poster: (NSURL, (NSData?) -> Unit) -> Unit,
    ): BannerWrite.Pictures = object : BannerWrite.Pictures {
        override suspend fun image(source: UploadSource): ByteArray? = decoded(source, image)
        override suspend fun poster(source: UploadSource): ByteArray? = decoded(source, poster)

        private suspend fun decoded(source: UploadSource, port: (NSURL, (NSData?) -> Unit) -> Unit): ByteArray? {
            val url = urlOf(source) ?: return null
            val data = suspendCancellableCoroutine { c ->
                var answered = false
                port(url) { d -> if (!answered) { answered = true; c.resume(d) } }
            }
            return data?.toByteArray()
        }
    }

    /** [source] picked in the banner's [editor]: decoded off the main thread, [done] called on core's threads once it is. */
    fun bannerPick(editor: BannerEditor, source: UploadSource, done: () -> Unit): Watch =
        Watch(CoroutineScope(Dispatchers.Default).launch { try { editor.pick(source) } finally { done() } })

    /**
     * The banner's [editor] saved — or [remove]d — off the main thread (its digest is read frame by frame); [done] called on
     * core's threads with what it came to (null: nothing to do, or stopped). Stopping the returned run is the dialog's
     * Cancel: the old banner stays as it was.
     */
    fun bannerSave(editor: BannerEditor, remove: Boolean, done: (BannerWrite.Outcome?) -> Unit): Watch =
        Watch(CoroutineScope(Dispatchers.Default).launch {
            var out: BannerWrite.Outcome? = null
            try {
                out = if (remove) editor.remove() else editor.save()
            } finally {
                done(out)
            }
        })

    /** A person's Memories section read (it unseals each Memory's meta): off the main thread (src/mobile/CLAUDE.md § Traps). */
    fun loadPerson(section: PersonMemories): Watch = Watch(CoroutineScope(Dispatchers.Default).launch { section.load() })

    /**
     * [editor]'s state handed to [listener] at once and on every change — its levels arrive from the geocoder on core's
     * threads (ADR 0143 D5) — until the watch is stopped; [listener] is called on core's threads.
     */
    fun watchPlace(editor: PlaceEditor, listener: (PlaceEditor.State) -> Unit): Watch =
        Watch(CoroutineScope(Dispatchers.Default).launch { editor.state.collect { listener(it) } })
}
