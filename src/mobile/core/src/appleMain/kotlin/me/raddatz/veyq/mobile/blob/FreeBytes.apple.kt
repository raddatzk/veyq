@file:OptIn(ExperimentalForeignApi::class)

package me.raddatz.veyq.mobile.blob

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSFileSystemSize
import platform.Foundation.NSNumber

// Apple: the volume's free and whole size as the file manager reports them.

internal actual fun freeBytes(path: String): Long? =
    (NSFileManager.defaultManager.attributesOfFileSystemForPath(path, null)?.get(NSFileSystemFreeSize) as? NSNumber)?.longLongValue

internal actual fun totalBytes(path: String): Long? =
    (NSFileManager.defaultManager.attributesOfFileSystemForPath(path, null)?.get(NSFileSystemSize) as? NSNumber)?.longLongValue
