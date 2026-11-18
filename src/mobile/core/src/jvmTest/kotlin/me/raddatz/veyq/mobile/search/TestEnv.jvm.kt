package me.raddatz.veyq.mobile.search

internal actual fun testEnv(name: String): String? = System.getenv(name)

internal actual fun usedHeapAfterGc(): Long? {
    val r = Runtime.getRuntime()
    repeat(3) { System.gc(); Thread.sleep(50) }
    return r.totalMemory() - r.freeMemory()
}
