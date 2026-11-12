package me.raddatz.veyq.mobile.blob

// A test's own directory, made, listed and removed by the platform's own calls — the oracle [FilesTest] holds the
// core's file calls to.

internal expect val separator: String

internal expect fun tempDir(): String

internal expect fun makeDir(path: String): Boolean

internal expect fun removeDir(path: String)

/** The names in [path] as the system lists them, sorted. */
internal expect fun listDir(path: String): List<String>

/** A symbolic link at [at] to the directory [target]; false where this host may not make one. */
internal expect fun makeLink(target: String, at: String): Boolean

/** Takes every right to list [dir] away; false where this host still could list it (root, Windows). */
internal expect fun makeUnlistable(dir: String): Boolean

/** Gives [dir] its rights back. */
internal expect fun makeListable(dir: String)

/** Removes [path] whole: a file, a link (never what it points to) or a directory with everything in it. */
internal expect fun removeTree(path: String)
