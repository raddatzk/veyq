package me.raddatz.veyq.mobile.offline

import me.raddatz.veyq.mobile.blob.deviceFiles

/** A fresh, empty directory of this test's own under the platform's temporary directory. */
expect fun freshTestDirectory(): String

/** Removes [path] with everything below it, through the calls under test (each test's own cleanup, not its oracle). */
fun removeAll(path: String) {
    val f = deviceFiles
    for (n in f.list(path).orEmpty()) {
        val p = "$path${f.separator}$n"
        if (!f.remove(p)) removeAll(p)
    }
    f.removeDirectory(path)
}

/** Every file below [path] with its bytes, read through the calls under test. */
fun everyFile(path: String): Map<String, ByteArray> {
    val f = deviceFiles
    val out = HashMap<String, ByteArray>()
    for (n in f.list(path).orEmpty()) {
        val p = "$path${f.separator}$n"
        val bytes = f.read(p)
        if (bytes != null) out[p] = bytes else out += everyFile(p)
    }
    return out
}
