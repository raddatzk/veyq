package me.raddatz.veyq.mobile.search

/** A variable of the test process's environment (an opt-in such as `VEYQ_MEASURE`), or null. */
internal expect fun testEnv(name: String): String?

/** The process's used heap in bytes after a collection, where the platform tells (the JVM); null elsewhere. */
internal expect fun usedHeapAfterGc(): Long?
