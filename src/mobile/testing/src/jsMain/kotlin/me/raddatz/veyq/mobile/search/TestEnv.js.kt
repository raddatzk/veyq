package me.raddatz.veyq.mobile.search

// No Kotlin/JS test task runs (ADR 0155 decision 6): the common tests compile for JavaScript as a check, and the
// suite's conformance runs under Node through `@veyq/core-js`. These actuals exist so they compile.
actual fun testEnv(name: String): String? = null

actual fun usedHeapAfterGc(): Long? = null

actual fun writeRepoFile(path: String, text: String): Unit = throw UnsupportedOperationException("the vectors are written on the JVM")
