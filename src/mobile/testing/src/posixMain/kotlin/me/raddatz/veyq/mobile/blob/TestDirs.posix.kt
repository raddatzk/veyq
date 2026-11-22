@file:OptIn(ExperimentalForeignApi::class)

package me.raddatz.veyq.mobile.blob

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.chmod
import platform.posix.closedir
import platform.posix.geteuid
import platform.posix.lstat
import platform.posix.stat
import platform.posix.symlink
import platform.posix.unlink
import platform.posix.getenv
import platform.posix.mkdir
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.rmdir

actual val separator: String = "/"

actual fun tempDir(): String = getenv("TMPDIR")?.toKString()?.trimEnd('/') ?: "/tmp"

actual fun makeDir(path: String): Boolean = mkdir(path, 0x1C0.convert()) == 0

actual fun removeDir(path: String) {
    rmdir(path)
}

actual fun listDir(path: String): List<String> {
    val d = opendir(path) ?: return emptyList()
    try {
        val out = ArrayList<String>()
        while (true) {
            val e = readdir(d) ?: break
            val n = e.pointed.d_name.toKString()
            if (n != "." && n != "..") out += n
        }
        return out.sorted()
    } finally {
        closedir(d)
    }
}

actual fun makeLink(target: String, at: String): Boolean = symlink(target, at) == 0

actual fun makeUnlistable(dir: String): Boolean = geteuid() != 0u && chmod(dir, 0.convert()) == 0

actual fun makeListable(dir: String) {
    chmod(dir, 0x1C0.convert())
}

actual fun removeTree(path: String) {
    val isDir = memScoped {
        val st = alloc<stat>()
        lstat(path, st.ptr) == 0 && (st.st_mode.toInt() and S_IFMT) == S_IFDIR
    }
    if (!isDir) {
        unlink(path)
        return
    }
    makeListable(path)
    listDir(path).forEach { removeTree("$path/$it") }
    rmdir(path)
}
