package me.raddatz.veyq.mobile.memories

/**
 * What a photo view of a Memory shows (ADR 0135 decision 2; the web's `features/memories/galleryMedium.ts`): every medium
 * but the kinds another view of the node claims — a GPX track (the Map tab's, ADR 0130) and a music track (the Music tab's,
 * ADR 0135). The gallery's rows ([GalleryModel]), and so its count, its grid and its lightbox, ask it; a type no view claims
 * (a PDF an archive brought) stays a row with its icon: hiding it would leave a medium nobody can see, save or delete.
 * The claims are the original's sealed type and name (`blob-meta`), as the web reads them.
 */
object GalleryMedium {
    /** A GPX track's type (the web's `GPX_MEDIA_TYPE`). */
    const val GPX_TYPE = "application/gpx+xml"

    /** The generic types a `.gpx` file is often declared as (the web's `XML_TYPES`). */
    private val XML_TYPES = setOf("application/xml", "text/xml", "application/octet-stream")

    /** A track: its type is GPX's, or its name ends in `.gpx` and its type is none or a generic one (`isTrackMeta`). */
    fun isTrack(type: String?, name: String?): Boolean {
        val t = type?.trim()?.lowercase().orEmpty()
        if (t == GPX_TYPE) return true
        return name?.trim()?.lowercase().orEmpty().endsWith(".gpx") && (t.isEmpty() || t in XML_TYPES)
    }

    /** A music track: its type is an audio one (`isAudioMeta`). */
    fun isAudio(type: String?): Boolean = type?.trim()?.lowercase().orEmpty().startsWith("audio/")

    /** Whether a photo view shows a medium of [type] named [name] (`isGalleryMedium`). */
    fun shows(type: String?, name: String?): Boolean = !isTrack(type, name) && !isAudio(type)
}
