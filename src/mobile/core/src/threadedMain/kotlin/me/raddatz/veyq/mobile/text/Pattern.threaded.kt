package me.raddatz.veyq.mobile.text

// On the JVM and natively: Kotlin's Regex.

internal actual fun compilePattern(source: String): Any = Regex(source)

internal actual fun patternMatches(compiled: Any, text: String): Boolean = (compiled as Regex).matches(text)

internal actual fun patternGroups(compiled: Any, text: String): List<String>? = (compiled as Regex).matchEntire(text)?.groupValues
