@file:OptIn(ExperimentalJsExport::class)

package me.raddatz.veyq.mobile.audio.web

// The plain values the music order and fades' façade takes and returns (ADR 0155 decision 5): external interfaces, so the
// `.d.ts` names each field with its type and the web passes its own objects — a deck's span is a `SpanShape`.

/** The span that plays, in milliseconds of the file: from the end of the leading silence to the start of the trailing
 *  one; an [endMs] of `Infinity` where the file's length is not known. */
@JsExport
external interface SpanShape {
    val startMs: Double
    val endMs: Double
}

/** What "previous" does: restart the track playing (`restart` true), or go back to [id], the one played before it. */
@JsExport
external interface PreviousShape {
    val restart: Boolean
    val id: String?
}
