@file:OptIn(ExperimentalForeignApi::class)

package me.raddatz.veyq.mobile.blob

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.FILE

// A file on the device opened by the path its platform's dialog handed over (ADR 0137 decision 3): the one place the
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

// The offline store's directory (ADR 0140 decision 3): a directory the app owns, named by the host at start, whose
// files the core writes beside and renames over (`DeviceFiles`). Per platform as the calls above.

/** Creates directory [path] (its parent must exist), readable by this user alone where the system has modes; true when
 *  it exists afterwards. */
internal expect fun createDirectory(path: String): Boolean

/** Removes the empty directory [path]; true when it went. */
internal expect fun removeDirectory(path: String): Boolean

/** Renames [from] to [to], replacing a file there in one step; true when it moved. */
internal expect fun renameFile(from: String, to: String): Boolean

/** Flushes [file]'s writes through to the device (`fsync`, Windows' `FlushFileBuffers`); true when they reached it. */
internal expect fun syncFile(file: CPointer<FILE>): Boolean

/** Makes a rename in directory [path] durable where the system needs that (POSIX: the directory's own `fsync`). */
internal expect fun syncDirectory(path: String)

/** The bytes free for this user on the volume that holds [path]; null where the system does not say. */
internal expect fun freeBytes(path: String): Long?

/** The size of the volume that holds [path]; null where the system does not say. */
internal expect fun totalBytes(path: String): Long?
