package me.raddatz.veyq.mobile.blob

import me.raddatz.veyq.mobile.PlatformApi
import kotlin.native.HiddenFromObjC
/**
 * Files in a directory the app owns — the offline store's (ADR 0140 decision 3) —, named by the host at start, never by
 * a dialog. The core does all reading and writing (ADR 0137
 * decision 3's line): `nativeMain` over `Files.kt`'s calls (POSIX on Apple and Linux, Win32's wide calls on Windows),
 * `jvmMain` over `java.nio` (Android).
 * Common code reaches files only through this seam: `Files.kt`'s calls are `nativeMain`'s, so a `commonMain` caller of
 * them compiles for no JVM target.
 *
 * A seam, so a test can stop a write where a crash would ([write] then [rename] is how a file is replaced whole).
 */
@PlatformApi
@HiddenFromObjC
interface DeviceFiles {
    /** What joins a directory's path and a name in it. */
    val separator: Char

    /** Creates directory [path] (its parent must exist); true when it exists afterwards. */
    fun createDirectory(path: String): Boolean

    /** The names directory [path] holds; null where it does not list (it does not exist). */
    fun list(path: String): List<String>?

    /** The whole file at [path]; null where it does not open. */
    fun read(path: String): ByteArray?

    /** [length] bytes of [path] from [offset]; null where it does not open or holds fewer. */
    fun read(path: String, offset: Long, length: Int): ByteArray?

    /** The size of the file at [path]; null where there is none. */
    fun size(path: String): Long?

    /** Writes [bytes] as the whole file at [path] (created or emptied) and flushes them to the device; throws when they
     *  did not all land. Never the way to replace a file another reader may read: write beside, then [rename]. */
    fun write(path: String, bytes: ByteArray)

    /** Appends [bytes] to the file at [path] (created when missing), flushed to the device; the offset they start at. */
    fun append(path: String, bytes: ByteArray): Long

    /** Renames [from] to [to], replacing a file there in one step, and makes the rename durable; throws when it did not. */
    fun rename(from: String, to: String)

    /** Removes the file at [path]; true when it went (or was not there). */
    fun remove(path: String): Boolean

    /** Removes the empty directory [path]; true when it went (or was not there). */
    fun removeDirectory(path: String): Boolean

    /** The bytes free for this user on the volume holding [path]; null where the system does not say. */
    fun freeBytes(path: String): Long?

    /** The size of the volume holding [path]; null where the system does not say (the free-space floor's whole, ADR 0140 decision 8). */
    fun totalBytes(path: String): Long?
}

/** This platform's [DeviceFiles]. */
@PlatformApi
@HiddenFromObjC
expect val deviceFiles: DeviceFiles
