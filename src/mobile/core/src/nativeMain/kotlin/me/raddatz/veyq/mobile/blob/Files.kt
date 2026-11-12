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
