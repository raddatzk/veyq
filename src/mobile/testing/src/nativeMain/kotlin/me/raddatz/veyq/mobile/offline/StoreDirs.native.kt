package me.raddatz.veyq.mobile.offline

import me.raddatz.veyq.mobile.blob.deviceFiles
import me.raddatz.veyq.mobile.blob.tempDir
import me.raddatz.veyq.mobile.crypto.hex
import me.raddatz.veyq.mobile.session.testPrimitives

actual fun freshTestDirectory(): String {
    val dir = tempDir() + deviceFiles.separator + "veyq-offline-" + hex(testPrimitives.randomBytes(8))
    check(deviceFiles.createDirectory(dir)) { "cannot make $dir" }
    return dir
}
