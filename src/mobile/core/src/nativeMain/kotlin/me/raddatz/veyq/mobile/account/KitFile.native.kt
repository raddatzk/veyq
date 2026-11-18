package me.raddatz.veyq.mobile.account

import me.raddatz.veyq.mobile.blob.FileSink
import me.raddatz.veyq.mobile.blob.deviceFiles

internal actual fun saveKitFile(path: String, bytes: ByteArray) {
    val sink = FileSink.open(path) ?: throw IllegalStateException("cannot write $path")
    var keep = false
    try {
        sink.write(bytes)
        keep = true
    } finally {
        sink.close(keep)
    }
    // `FileSink.close` removes the file where closing it failed: what is not there whole was not written.
    if (deviceFiles.size(path) != bytes.size.toLong()) {
        deviceFiles.remove(path)
        throw IllegalStateException("the kit did not land at $path")
    }
}
