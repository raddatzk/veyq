package me.raddatz.veyq.mobile.blob

import me.raddatz.veyq.mobile.media.ByteSource

/**
 * A file picked for an upload (the web's `File`): what the system listed of it — its [name], [size], given [type] and
 * [modifiedAt] (null where the listing has none, or where the picker's copy carries no date of the file's) — and its
 * bytes at any offset, read a frame at a time, so a file of any size is sealed without being held whole. Each platform
 * reads its own: Android a content URI's descriptor, Apple a file URL (`FileUploadSource`). An upload job reads it on its
 * own thread and [close]s it when its run ends; a later run opens it again.
 */
abstract class UploadSource(val name: String, val size: Long, val type: String?, val modifiedAt: Long?) {
    /** Up to [length] bytes from [from] (fewer only at its end). */
    abstract suspend fun read(from: Long, length: Int): ByteArray

    /** Lets go of what reading held (a descriptor, a security scope); a later read opens it again. */
    open fun close() {}

    /** Its bytes as core's readers take them. */
    fun bytes(): ByteSource = ByteSource(size) { from, length -> read(from, length) }

    /** This file under [name] (a folder upload's planned name). */
    fun named(name: String): UploadSource {
        val inner = this
        return object : UploadSource(name, size, type, modifiedAt) {
            override suspend fun read(from: Long, length: Int) = inner.read(from, length)
            override fun close() = inner.close()
        }
    }

    companion object {
        /** A source read by [reader], nothing held between reads. */
        fun of(name: String, size: Long, type: String?, modifiedAt: Long?, reader: suspend (from: Long, length: Int) -> ByteArray): UploadSource =
            object : UploadSource(name, size, type, modifiedAt) {
                override suspend fun read(from: Long, length: Int) = reader(from, length)
            }
    }
}
