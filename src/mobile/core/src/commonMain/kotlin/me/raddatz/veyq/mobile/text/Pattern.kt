package me.raddatz.veyq.mobile.text

/**
 * A regular expression run by the platform's own engine, matched against a whole text: on the JVM and natively Kotlin's
 * [Regex], on JavaScript the engine's `RegExp` itself (flag `u`). Code a façade reaches uses this, never [Regex]: Kotlin's
 * `Regex` on JavaScript is a class of its own over `RegExp`, and once anything reaches it, it ships in the standard
 * library's module file with every entry (ADR 0155 § Expenses' move, measured). The sources are the formats' patterns,
 * which both engines read alike (ASCII classes, a lookahead, `\p{L}`).
 */
class Pattern(
    /** The pattern as written. */
    val source: String,
) {
    private val compiled: Any = compilePattern(source)

    /** Whether the whole of [text] matches. */
    fun matches(text: String): Boolean = patternMatches(compiled, text)

    /** The groups of a whole match of [text], the whole match first and a group that took no part as "", or null. */
    fun groups(text: String): List<String>? = patternGroups(compiled, text)
}

internal expect fun compilePattern(source: String): Any

internal expect fun patternMatches(compiled: Any, text: String): Boolean

internal expect fun patternGroups(compiled: Any, text: String): List<String>?
