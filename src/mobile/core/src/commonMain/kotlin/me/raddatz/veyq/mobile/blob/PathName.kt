package me.raddatz.veyq.mobile.blob

/** The last part of [path] (either separator): a file's or a directory's own name, as a desktop's picker or drop named it. */
fun pathName(path: String): String = path.trimEnd('/', '\\').split('/', '\\').lastOrNull { it.isNotEmpty() } ?: path
