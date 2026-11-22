package me.raddatz.veyq.mobile.composition

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.blob.FileUploadSource
import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.depot.FolderUpload
import me.raddatz.veyq.mobile.media.Derivation
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.posix.memcpy

/**
 * What the Apple app's core UI cannot do with the platform's types itself and asks of no application (ADR 0154 decision
 * 3, inbox #1066): any core state watched, a picked file as an upload's source and back, a range of a reader as `NSData`,
 * the tiers the app derived as core takes them, and a picked or dropped directory's walk as the core's walk entries. Memories' own
 * `AppleMemories` keeps its copies for its own UI; the core UI names no application's object for these.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
object ApplePlatform {
    /** A watch the app stops when its view goes. */
    class Watch internal constructor(private val job: Job) {
        fun stop() = job.cancel()
    }

    /**
     * [flow] watched: [listener] is called at once and on every change, on core's threads, until the watch is stopped
     * (a Swift box reads the model's `current` on the main actor then).
     */
    fun watch(flow: StateFlow<*>, listener: (Any?) -> Unit): Watch =
        Watch(CoroutineScope(Dispatchers.Default).launch { flow.collect { listener(it) } })

    /**
     * The picked file at [url] as an upload reads it, a frame at a time at any offset: its given [type], [name] and
     * [modifiedAt] (nil for a copy a picker made, whose date is the copy's).
     */
    fun source(url: NSURL, name: String, size: Long, type: String?, modifiedAt: Long?): UploadSource =
        FileUploadSource(url, null, name, size, type, modifiedAt)

    /** The picked file [source] is (an upload's): where the app's deriver reads it, and its copy is removed. */
    fun urlOf(source: UploadSource): NSURL? = (source as? FileUploadSource)?.url

    /** Up to [length] bytes of [reader] from [position] (fewer only at its end). */
    @Throws(Exception::class)
    suspend fun readRange(reader: RangeReader, position: Long, length: Int): NSData = withContext(Dispatchers.Default) {
        reader.read(position, length).toNSData()
    }

    /** The tiers the app derived ([preview], [thumbnail], the size as shown), as core takes them. */
    fun derived(preview: NSData?, thumbnail: NSData?, width: Int?, height: Int?): Derivation.Derived =
        Derivation.Derived(preview?.toByteArray(), thumbnail?.toByteArray(), width, height)

    /** What one entry of a picked or dropped directory is, as Swift's walk tells it (the core keeps or says it). */
    enum class EntryKind { FILE, DIRECTORY, LINK, UNREADABLE, OTHER }

    /** One entry Swift's walk of a directory met: its path below the directory, where it lies, what it is, a file's facts. */
    class Entry(val relativePath: String, val path: String, val kind: EntryKind, val size: Long, val type: String, val modifiedAt: Long?)

    /**
     * What Swift's walk of the directory at [root] met, as the core's walk entries (the folder upload's and the drop's),
     * each file read inside the directory's security scope.
     */
    fun found(root: NSURL, entries: List<Entry>): List<FolderUpload.Found> = entries.map { e ->
        val name = e.relativePath.substringAfterLast('/')
        val kind = when (e.kind) {
            EntryKind.FILE -> FolderUpload.Kind.FILE
            EntryKind.DIRECTORY -> FolderUpload.Kind.DIRECTORY
            EntryKind.LINK -> FolderUpload.Kind.LINK
            EntryKind.UNREADABLE -> FolderUpload.Kind.UNREADABLE
            EntryKind.OTHER -> FolderUpload.Kind.OTHER
        }
        val source = if (kind == FolderUpload.Kind.FILE) FileUploadSource(NSURL.fileURLWithPath(e.path), root, name, e.size, e.type, e.modifiedAt) else null
        FolderUpload.Found(e.relativePath, kind, source)
    }

    private fun ByteArray.toNSData(): NSData = if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

    private fun NSData.toByteArray(): ByteArray {
        val out = ByteArray(length.toInt())
        if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
        return out
    }
}
