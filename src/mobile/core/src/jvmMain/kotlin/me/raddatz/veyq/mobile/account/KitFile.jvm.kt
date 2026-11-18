package me.raddatz.veyq.mobile.account

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import me.raddatz.veyq.mobile.crypto.Primitives

internal actual fun saveKitFile(path: String, bytes: ByteArray) {
    try {
        FileOutputStream(path).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
    } catch (e: Exception) {
        File(path).delete()
        throw e
    }
}

/**
 * Writes the kit ([KitWriter.pdf]) into [out] and flushes it — Android's save dialog (`CreateDocument`) hands over a
 * content `Uri`, not a path, so the host opens the document's stream and passes it here; the stream stays the caller's to
 * close. Where this throws, the host deletes the document it created (`DocumentsContract.deleteDocument`): the core
 * cannot reach it. The file's bytes are zeroed once written.
 */
fun KitWriter.write(out: OutputStream, p: Primitives, email: String, secretKey: ByteArray, server: String?, words: KitWords, createdEpochDay: Long) {
    val bytes = pdf(p, email, secretKey, server, words, createdEpochDay)
    try {
        out.write(bytes)
        out.flush()
    } finally {
        bytes.fill(0)
    }
}
