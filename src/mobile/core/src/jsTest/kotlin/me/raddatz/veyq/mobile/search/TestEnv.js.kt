package me.raddatz.veyq.mobile.search

// No Kotlin/JS test task runs (ADR 0155 decision 6): the common tests compile for JavaScript as a check, and the
// suite's conformance runs under Node through `@veyq/core-js`. These actuals exist so they compile.
internal actual fun testEnv(name: String): String? = null

internal actual fun usedHeapAfterGc(): Long? = null
