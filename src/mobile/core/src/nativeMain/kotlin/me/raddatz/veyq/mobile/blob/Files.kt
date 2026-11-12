@file:OptIn(ExperimentalForeignApi::class)

package me.raddatz.veyq.mobile.blob

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.FILE

// A file on the device opened by the path its platform's dialog handed over (ADR 0130 decision 3): the one place the
// call differs. Apple and Linux take the path as the bytes it is (`posixMain`); Windows takes it as UTF-16, since
// mingw's `fopen` reads a path in the ANSI code page and a name outside it does not open as named (`mingwMain`).

/** [path] (UTF-8) opened in C's [mode]; null when it does not open. */
internal expect fun openFile(path: String, mode: String): CPointer<FILE>?

/** Removes the file at [path]; true when it went. */
internal expect fun removeFile(path: String): Boolean

/** Moves [file]'s position to [offset] from its start, past 2 GiB too; true when it moved. */
internal expect fun seekFile(file: CPointer<FILE>, offset: Long): Boolean

/** What a path is, as the system lists it without following a link ([pathFacts]). */
internal enum class PathKind { FILE, DIRECTORY, LINK, OTHER }

/** A path's kind, its size in bytes (a file's) and when it was last modified (epoch milliseconds, where the system says). */
internal class PathFacts(val kind: PathKind, val size: Long, val modifiedAt: Long?)

/**
 * What [path] is (UTF-8), never following a link: a link — a symbolic link, a Windows reparse point (a junction too) — is
 * reported as one ([PathKind.LINK]), so a walk over it reaches nothing outside the directory picked. Null where the system
 * cannot tell (it does not exist, or may not be read).
 */
internal expect fun pathFacts(path: String): PathFacts?

/** The names directory [path] holds (`.` and `..` left out), as the system lists them; null where it does not list. */
internal expect fun listDirectory(path: String): List<String>?

/** What joins a directory's path and a name in it: `/`, Windows' `\`. */
internal expect val pathSeparator: Char
