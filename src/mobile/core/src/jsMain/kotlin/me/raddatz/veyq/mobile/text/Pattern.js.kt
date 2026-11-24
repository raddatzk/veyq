package me.raddatz.veyq.mobile.text

// On JavaScript: the engine's `RegExp`, anchored at both ends.

@Suppress("UNUSED_PARAMETER")
private fun newRegExp(source: String): dynamic = js("new RegExp(source, 'u')")

internal actual fun compilePattern(source: String): Any = newRegExp("^(?:$source)$").unsafeCast<Any>()

internal actual fun patternMatches(compiled: Any, text: String): Boolean = compiled.asDynamic().test(text).unsafeCast<Boolean>()

internal actual fun patternGroups(compiled: Any, text: String): List<String>? {
    val m = compiled.asDynamic().exec(text) ?: return null
    val n = m.length.unsafeCast<Int>()
    return List(n) { i -> m[i]?.unsafeCast<String>() ?: "" }
}
