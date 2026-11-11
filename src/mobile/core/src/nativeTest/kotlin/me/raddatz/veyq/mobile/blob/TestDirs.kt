package me.raddatz.veyq.mobile.blob

// A test's own directory, made, listed and removed by the platform's own calls — the oracle [FilesTest] holds the
// core's file calls to.

internal expect val separator: String

internal expect fun tempDir(): String

internal expect fun makeDir(path: String): Boolean

internal expect fun removeDir(path: String)

/** The names in [path] as the system lists them, sorted. */
internal expect fun listDir(path: String): List<String>
