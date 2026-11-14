@file:OptIn(ExperimentalForeignApi::class)

package me.raddatz.veyq.mobile.blob

import kotlinx.cinterop.ExperimentalForeignApi
import me.raddatz.veyq.mobile.depot.FolderUpload
import platform.posix.fclose

/**
 * Files and a directory picked on a desktop by the path its dialog (or a drop) handed over (ADR 0131 decision 3 and
 * its Consequences): the core opens them itself, so nothing of a file crosses the bridge and a file of any size is read at
 * offsets a frame at a time, never held whole.
 */
object PickedPaths {
    /** The last part of [path] (either separator): a file's or a directory's own name. */
    fun nameOf(path: String): String = pathName(path)

    /**
     * The regular file at [path] as an upload's source: named by its path's last part, its size and modified time the
     * system's, its type claimed by its name ([MediaTypes]); null where it is no regular file or does not open for reading.
     */
    fun source(path: String): FileSource? {
        val facts = pathFacts(path)?.takeIf { it.kind == PathKind.FILE } ?: return null
        val name = nameOf(path)
        if (!opens(path)) return null
        return FileSource(path, name, facts.size, MediaTypes.byName(name), facts.modifiedAt)
    }

    /** Whether the file at [path] opens for reading (closed again at once): a walk tells an unreadable one apart. */
    private fun opens(path: String): Boolean {
        val f = openFile(path, "rb") ?: return false
        fclose(f)
        return true
    }

    /**
     * What a walk of the directory at [path] meets, depth first (the desktops' own walk, ADR 0131's Consequences; a phone walks through its own
     * door): every entry by its path below the directory (`/`-separated) and what it is — a file with its [source], a
     * directory, a link (reported, never followed, so nothing outside the directory is reached), or an entry that did not
     * read (a directory that does not list, a file that does not open). Hidden entries are met as any other:
     * [FolderUpload.keeps] decides. Null where [path] itself is no directory that lists.
     */
    fun walk(path: String): List<FolderUpload.Found>? {
        if (pathFacts(path)?.kind != PathKind.DIRECTORY) return null
        val root = path.trimEnd('/', '\\').ifEmpty { path }
        val top = listDirectory(root) ?: return null
        val out = ArrayList<FolderUpload.Found>()
        fun visit(dir: String, below: String, names: List<String>) {
            for (name in names.sorted()) {
                val full = "$dir$pathSeparator$name"
                val at = if (below.isEmpty()) name else "$below/$name"
                val facts = pathFacts(full)
                when (facts?.kind) {
                    PathKind.FILE -> out += FolderUpload.Found(at, FolderUpload.Kind.FILE, source(full))
                    PathKind.DIRECTORY -> {
                        val inner = listDirectory(full)
                        if (inner == null) {
                            out += FolderUpload.Found(at, FolderUpload.Kind.UNREADABLE)
                        } else {
                            out += FolderUpload.Found(at, FolderUpload.Kind.DIRECTORY)
                            visit(full, at, inner)
                        }
                    }
                    PathKind.LINK -> out += FolderUpload.Found(at, FolderUpload.Kind.LINK)
                    PathKind.OTHER -> out += FolderUpload.Found(at, FolderUpload.Kind.OTHER)
                    null -> out += FolderUpload.Found(at, FolderUpload.Kind.UNREADABLE)
                }
            }
        }
        visit(root, "", top)
        return out
    }
}
