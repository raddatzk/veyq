package me.raddatz.veyq.mobile.search

/**
 * Whether [this] holds [t] (`String.contains`): the search asks it of every document's name on every keystroke. On
 * JavaScript the common `contains` goes through the standard library's `indexOf` with its optional arguments, which was
 * a quarter of the web's query time; there it is the string's own `includes` (ADR 0155's speed criterion, measured
 * 2026-11-20). The same answer everywhere.
 */
internal expect fun String.holds(t: String): Boolean
