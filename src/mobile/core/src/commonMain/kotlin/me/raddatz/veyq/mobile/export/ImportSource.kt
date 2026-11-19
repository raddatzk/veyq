package me.raddatz.veyq.mobile.export

import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.crypto.nfc

/**
 * What an import was handed (ADR 0082 decision 7, the web's `ImportInput`): a download as the ZIP it was saved as, or the
 * same download unpacked — a folder holding `.veyq/manifest.json` at its root, its files by their paths below it.
 */
sealed interface ImportInput {
    class Zip(val file: UploadSource) : ImportInput

    /** A picked or dropped folder: its [name] and its [files], each by its `/`-separated path below the folder. */
    class Folder(val name: String, val files: List<Pair<String, UploadSource>>) : ImportInput

    /** Lets go of what the files' reads held. */
    fun close() = when (this) {
        is Zip -> file.close()
        is Folder -> files.forEach { it.second.close() }
    }
}

/**
 * The files an import reads, by archive path (the web's `importSource.ts`): a ZIP's entries ([ZipReader]) or a folder's
 * files, keyed by their paths in Unicode NFC (a file system may hand out the decomposed spelling of a name the archive
 * wrote composed). Both are read the same way: the manifest's paths name the files, nothing else is opened, and nothing is
 * held whole — a file streams a frame at a time, and a hook reads a checked payload by offset.
 */
interface ImportSource {
    val isZip: Boolean

    fun has(path: String): Boolean

    /** The file's size, or null where there is none. */
    fun size(path: String): Long?

    /** The CRC-32 a ZIP entry's directory records; null for a folder's file, which carries none. */
    fun crc32(path: String): Long?

    /** The file's bytes to [sink], [ZipReader.READ_CHUNK] at a time; a ZIP entry's CRC-32 checked at the end. */
    suspend fun stream(path: String, sink: suspend (ByteArray) -> Unit)

    /** Up to [length] bytes of the file from [from] (fewer only at its end); nothing checked. */
    suspend fun read(path: String, from: Long, length: Int): ByteArray

    companion object {
        /** The entries of an opened ZIP. */
        fun zip(zip: ZipReader): ImportSource = object : ImportSource {
            override val isZip = true
            override fun has(path: String) = zip.has(path)
            override fun size(path: String) = zip.entry(path)?.size
            override fun crc32(path: String) = zip.entry(path)?.crc32
            override suspend fun stream(path: String, sink: suspend (ByteArray) -> Unit) = zip.stream(path, sink)
            override suspend fun read(path: String, from: Long, length: Int) = zip.readEntry(path, from, length)
        }

        /** The files of a folder, by their paths below it in NFC. */
        fun folder(files: List<Pair<String, UploadSource>>): ImportSource {
            val byPath = files.associate { (path, source) -> nfc(path) to source }
            fun get(path: String) = byPath[path] ?: throw ZipReader.ZipError(ZipReader.ZipError.Code.MISSING, "no file $path")
            return object : ImportSource {
                override val isZip = false
                override fun has(path: String) = path in byPath
                override fun size(path: String) = byPath[path]?.size
                override fun crc32(path: String): Long? = null
                override suspend fun stream(path: String, sink: suspend (ByteArray) -> Unit) {
                    val f = get(path)
                    var at = 0L
                    while (at < f.size) {
                        val chunk = f.read(at, minOf(ZipReader.READ_CHUNK.toLong(), f.size - at).toInt())
                        if (chunk.isEmpty()) throw ZipReader.ZipError(ZipReader.ZipError.Code.TRUNCATED, "$path ended early")
                        sink(chunk)
                        at += chunk.size
                    }
                }
                override suspend fun read(path: String, from: Long, length: Int) = get(path).read(from, length)
            }
        }

        /**
         * Whether [file] is a download, by its CONTENT (the web's `isDownloadArchive`): `PK\3\4` first, then a ZIP whose
         * directory holds `.veyq/manifest.json` or version 1's `manifest.json`. The cheap test an upload asks before it
         * offers the import: nothing but the first four bytes, the end of the file, the directory and each entry's header
         * and descriptor is read. A damaged archive is no download here (the dialog would refuse it; an upload takes it as a file).
         */
        suspend fun isDownload(file: UploadSource): Boolean {
            if (file.size < 22) return false
            return try {
                val head = file.read(0, 4)
                if (head.size < 4 || head[0] != 0x50.toByte() || head[1] != 0x4b.toByte() || head[2] != 0x03.toByte() || head[3] != 0x04.toByte()) {
                    return false
                }
                val zip = ZipReader.open(file.size, file::read)
                zip.has(Manifest.PATH) || zip.has(Manifest.PATH_V1)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            } finally {
                file.close()
            }
        }

        /** Whether a picked folder's [paths] hold `.veyq/manifest.json` at its root (in NFC): nothing is read. */
        fun holdsManifest(paths: List<String>): Boolean = paths.any { nfc(it) == Manifest.PATH }
    }
}
