package me.raddatz.veyq.mobile.qr

import java.io.File

internal actual fun readRepoFile(path: String): ByteArray = File(System.getProperty("veyq.repoRoot"), path).readBytes()
