@file:OptIn(ExperimentalJsExport::class)

package me.raddatz.veyq.mobile.files.web

// The plain values the file facts' façade takes and returns (ADR 0155 decision 5): external interfaces, so the `.d.ts` names
// each field with its type and the web passes its own objects — a `Set<string>` is a `TakenNames`.

/** A streamable container: the element that plays it (`video`, `audio`) and the type it is served under. */
@JsExport
external interface StreamShape {
    val element: String
    val type: String
}

/** The folded names a directory has handed out: the web's own `Set<string>`. */
@JsExport
external interface TakenNames {
    fun has(value: String): Boolean
    fun add(value: String)
}
