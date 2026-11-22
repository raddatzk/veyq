package me.raddatz.veyq.mobile.blob

import me.raddatz.veyq.mobile.search.JsText

/**
 * The one name a blob is saved under (inbox #397; the web draws it through `@veyq/core-js/files`, ADR 0155 decision 7.2):
 * the first of the names that is not blank (trimmed as JavaScript trims) — the owning object's own name (a file's node
 * name: a rename wins over the name it was uploaded under), then the name sealed in the blob's meta —, else the
 * application's fallback, untouched. The chosen name gains the extension the sealed type names ([extensionOf]) only where it
 * has none of its own; an extension it has is never replaced. The save target sanitises for its route.
 * `contracts/platform/files/v1-vectors.json` § `downloadName` holds it on every client.
 */
object DownloadName {
    /** The extension a sealed [type] names, exactly as sealed (no parameters, no case folded), or null for one this table
     *  does not name. Every type a sniff produces for a medium or a track has its row (a type added there needs one here). */
    fun extensionOf(type: String?): String? = when (type) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/heic" -> "heic"
        "application/pdf" -> "pdf"
        "video/mp4" -> "mp4"
        "video/quicktime" -> "mov"
        "video/webm" -> "webm"
        "video/x-matroska" -> "mkv"
        // the audio containers Sniff.stream names, which a music track (ADR 0135) is typed by
        "audio/mpeg" -> "mp3"
        "audio/mp4" -> "m4a"
        "audio/aac" -> "aac"
        "audio/ogg" -> "ogg"
        "audio/wav" -> "wav"
        "audio/flac" -> "flac"
        else -> null
    }

    /** Whether [name] ends in an extension: a dot after a character that is no dot, then 1–16 characters that are no dot
     *  and no white space (`Bericht.PDF` does; `Rom 2026`, `.profile` and `v1.2 notes` do not). */
    fun hasExtension(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        if (dot < 1 || name[dot - 1] == '.') return false
        val len = name.length - dot - 1
        if (len < 1 || len > 16) return false
        for (i in dot + 1 until name.length) if (JsText.isJsSpace(name[i])) return false
        return true
    }

    /** The first of [names] not blank, trimmed; null where every one is. */
    fun named(vararg names: String?): String? {
        for (n in names) {
            if (n == null) continue
            val t = JsText.trim(n)
            if (t.isNotEmpty()) return t
        }
        return null
    }

    /** [name] with the extension [type] names where it has none. */
    fun withExtension(name: String, type: String?): String {
        val ext = extensionOf(type) ?: return name
        return if (hasExtension(name)) name else "$name.$ext"
    }

    /** The name for a blob of the sealed [type]: the first of [names] not blank, else [fallback]. */
    fun of(type: String?, fallback: String, vararg names: String?): String = withExtension(named(*names) ?: fallback, type)
}
