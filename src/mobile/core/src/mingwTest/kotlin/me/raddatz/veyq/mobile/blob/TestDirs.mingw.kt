@file:OptIn(ExperimentalForeignApi::class)

package me.raddatz.veyq.mobile.blob

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.getenv
import platform.windows.CreateDirectoryW
import platform.windows.CreateSymbolicLinkW
import platform.windows.DeleteFileW
import platform.windows.FILE_ATTRIBUTE_DIRECTORY
import platform.windows.FILE_ATTRIBUTE_REPARSE_POINT
import platform.windows.GetFileAttributesW
import platform.windows.INVALID_FILE_ATTRIBUTES
import platform.windows.FindClose
import platform.windows.FindFirstFileW
import platform.windows.FindNextFileW
import platform.windows.INVALID_HANDLE_VALUE
import platform.windows.RemoveDirectoryW
import platform.windows.WIN32_FIND_DATAW

// The directory as Windows itself lists it (Win32's wide calls, never the C runtime's the core uses), so a name the core
// wrote under another code page shows as the other name it is.

internal actual val separator: String = "\\"

internal actual fun tempDir(): String = (getenv("TEMP")?.toKString() ?: "C:\\Windows\\Temp").trimEnd('\\')

internal actual fun makeDir(path: String): Boolean = CreateDirectoryW(path, null) != 0

internal actual fun removeDir(path: String) {
    RemoveDirectoryW(path)
}

internal actual fun listDir(path: String): List<String> = memScoped {
    val data = alloc<WIN32_FIND_DATAW>()
    val h = FindFirstFileW("$path\\*", data.ptr)
    if (h == INVALID_HANDLE_VALUE) return@memScoped emptyList()
    val out = ArrayList<String>()
    try {
        do {
            val n = data.cFileName.toKString()
            if (n != "." && n != "..") out += n
        } while (FindNextFileW(h, data.ptr) != 0)
    } finally {
        FindClose(h)
    }
    out.sorted()
}

/** SYMBOLIC_LINK_FLAG_DIRECTORY | SYMBOLIC_LINK_FLAG_ALLOW_UNPRIVILEGED_CREATE (developer mode). */
private const val DIRECTORY_LINK: UInt = 0x3u

internal actual fun makeLink(target: String, at: String): Boolean = CreateSymbolicLinkW(at, target, DIRECTORY_LINK).toInt() != 0

internal actual fun makeUnlistable(dir: String): Boolean = false

internal actual fun makeListable(dir: String) {}

internal actual fun removeTree(path: String) {
    val attributes = GetFileAttributesW(path)
    if (attributes == INVALID_FILE_ATTRIBUTES) return
    val directory = attributes.toInt() and FILE_ATTRIBUTE_DIRECTORY != 0
    // a link to a directory is removed as the directory entry it is, never followed into
    if (directory && attributes.toInt() and FILE_ATTRIBUTE_REPARSE_POINT == 0) listDir(path).forEach { removeTree("$path\\$it") }
    if (directory) RemoveDirectoryW(path) else DeleteFileW(path)
}
