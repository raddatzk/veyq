package me.raddatz.veyq.mobile.offline

actual fun freshTestDirectory(): String = java.nio.file.Files.createTempDirectory("veyq-offline-").toString()
