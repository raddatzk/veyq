package me.raddatz.veyq.mobile.offline

internal actual fun freshTestDirectory(): String = java.nio.file.Files.createTempDirectory("veyq-offline-").toString()
