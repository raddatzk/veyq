package me.raddatz.veyq.mobile.blob

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

actual fun inflate(bytes: ByteArray, cap: Int): ByteArray? {
    val inflater = Inflater()
    try {
        inflater.setInput(bytes)
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        while (!inflater.finished()) {
            val n = try {
                inflater.inflate(chunk)
            } catch (_: DataFormatException) {
                return null
            }
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null
            if (out.size() + n > cap) return null
            out.write(chunk, 0, n)
        }
        return out.toByteArray()
    } finally {
        inflater.end()
    }
}
