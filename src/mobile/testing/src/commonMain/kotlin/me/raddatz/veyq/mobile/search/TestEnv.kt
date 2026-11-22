package me.raddatz.veyq.mobile.search

/** A variable of the test process's environment (an opt-in such as `VEYQ_MEASURE`), or null. */
expect fun testEnv(name: String): String?

/** The process's used heap in bytes after a collection, where the platform tells (the JVM); null elsewhere. */
expect fun usedHeapAfterGc(): Long?

/** Writes [text] over the repository file at [path] (the vectors' write mode); the JVM's only, every other platform refuses. */
expect fun writeRepoFile(path: String, text: String)
