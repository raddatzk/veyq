package me.raddatz.veyq.mobile.search

actual fun testEnv(name: String): String? = System.getenv(name)

actual fun usedHeapAfterGc(): Long? {
    val r = Runtime.getRuntime()
    repeat(3) { System.gc(); Thread.sleep(50) }
    return r.totalMemory() - r.freeMemory()
}

actual fun writeRepoFile(path: String, text: String) =
    java.io.File(System.getProperty("veyq.repoRoot"), path).writeText(text)
