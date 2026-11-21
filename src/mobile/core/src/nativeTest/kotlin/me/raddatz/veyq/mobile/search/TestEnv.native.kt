@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.raddatz.veyq.mobile.search

import kotlinx.cinterop.toKString
import platform.posix.getenv

internal actual fun testEnv(name: String): String? = getenv(name)?.toKString()

internal actual fun usedHeapAfterGc(): Long? = null

internal actual fun writeRepoFile(path: String, text: String): Unit = throw UnsupportedOperationException("the vectors are written on the JVM")
