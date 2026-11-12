@file:OptIn(ExperimentalForeignApi::class)

package me.raddatz.veyq.mobile.blob

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.windows.FILE_ATTRIBUTE_DIRECTORY
import platform.windows.FILE_ATTRIBUTE_REPARSE_POINT
import platform.windows.FindClose
import platform.windows.FindFirstFileW
import platform.windows.FindNextFileW
import platform.windows.GetFileAttributesExW
import platform.windows.INVALID_HANDLE_VALUE
import platform.windows.WIN32_FILE_ATTRIBUTE_DATA
import platform.windows.WIN32_FIND_DATAW
import platform.windows._GET_FILEEX_INFO_LEVELS

// Windows: a path's facts and a directory's names through Win32's wide calls (UTF-16, as `openFile`). A reparse point —
// a symbolic link, a junction — is reported as a link and never followed: `GetFileAttributesExW` reads the point itself.

/** 100-ns intervals from 1601-01-01 to 1970-01-01. */
private const val EPOCH_FILETIME = 116_444_736_000_000_000L

internal actual fun pathFacts(path: String): PathFacts? = memScoped {
    val data = alloc<WIN32_FILE_ATTRIBUTE_DATA>()
    if (GetFileAttributesExW(path, _GET_FILEEX_INFO_LEVELS.GetFileExInfoStandard, data.ptr) == 0) return@memScoped null
    val attributes = data.dwFileAttributes.toInt()
    val kind = when {
        attributes and FILE_ATTRIBUTE_REPARSE_POINT != 0 -> PathKind.LINK
        attributes and FILE_ATTRIBUTE_DIRECTORY != 0 -> PathKind.DIRECTORY
        else -> PathKind.FILE
    }
    val size = (data.nFileSizeHigh.toLong() shl 32) or data.nFileSizeLow.toLong()
    val written = (data.ftLastWriteTime.dwHighDateTime.toLong() shl 32) or data.ftLastWriteTime.dwLowDateTime.toLong()
    PathFacts(kind, size, (written - EPOCH_FILETIME) / 10_000)
}

internal actual fun listDirectory(path: String): List<String>? = memScoped {
    val data = alloc<WIN32_FIND_DATAW>()
    val h = FindFirstFileW("${path.trimEnd('\\')}\\*", data.ptr)
    if (h == INVALID_HANDLE_VALUE) return@memScoped null
    val out = ArrayList<String>()
    try {
        do {
            val n = data.cFileName.toKString()
            if (n != "." && n != "..") out += n
        } while (FindNextFileW(h, data.ptr) != 0)
    } finally {
        FindClose(h)
    }
    out
}

internal actual val pathSeparator: Char = '\\'
