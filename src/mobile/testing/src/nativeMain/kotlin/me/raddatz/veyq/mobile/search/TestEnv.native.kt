@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.raddatz.veyq.mobile.search

import kotlinx.cinterop.toKString
import platform.posix.getenv

actual fun testEnv(name: String): String? = getenv(name)?.toKString()

actual fun usedHeapAfterGc(): Long? = null

actual fun writeRepoFile(path: String, text: String): Unit = throw UnsupportedOperationException("the vectors are written on the JVM")
