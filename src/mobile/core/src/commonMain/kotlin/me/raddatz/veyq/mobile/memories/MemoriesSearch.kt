package me.raddatz.veyq.mobile.memories

import kotlinx.serialization.json.JsonObject
import me.raddatz.veyq.mobile.memories.place.MemoryDate
import me.raddatz.veyq.mobile.memories.place.MemoryLocation
import me.raddatz.veyq.mobile.search.MonthSpan
import me.raddatz.veyq.mobile.search.Query
import me.raddatz.veyq.mobile.search.ScopeDocument
import me.raddatz.veyq.mobile.search.SearchFacets
import me.raddatz.veyq.mobile.search.YearSpan
import me.raddatz.veyq.mobile.search.localDate

/**
 * What Memories adds to core's search (ADR 0125 decisions 6 and 8a; the web's `features/memories/search.ts`, ported for
 * ADR 0146 decision 4) — core never names this; `AppKinds.search` wires it in:
 * - a Memory's and a chapter's facets from its sealed meta ([facets]): its date range as years and months, its place's name,
 *   its participants' user ids — never a name;
 * - a medium's document ([mediumDocument]): its original's name, its capture time and its place; a GPX track and a music
 *   track are no document (`GalleryMedium.shows`), nor is a blocked medium (the caller leaves it out first).
 * Held to `contracts/platform/search/v1-vectors.json`'s `facets` and `medium`.
 */
object MemoriesSearch {
    /** The kind token of a photo's and a video's document (the web's `PHOTO_DOCUMENT`, `VIDEO_DOCUMENT`). */
    const val PHOTO = "memories.photo"
    const val VIDEO = "memories.video"

    /** The icon kinds a photo's and a video's row draws (the web's `image`, `play`). */
    const val PHOTO_ICON = "image"
    const val VIDEO_ICON = "play"

    /** How many media one page of a space's listing carries: the contract's cap (the web's `SCOPE_MEDIA_PAGE`). */
    const val SCOPE_MEDIA_PAGE = 1000

    /** The years a Memory's date spans: `from`'s to `to`'s, an ongoing one's up to [nowMs]'s year; none without a start day. */
    fun yearsOfDate(date: MemoryDate?, nowMs: Long): YearSpan? {
        val from = date?.from?.take(4)?.toIntOrNull() ?: return null
        val to = when {
            date.to != null -> date.to.take(4).toIntOrNull()
            date.mode == MemoryDate.Mode.ONGOING -> maxOf(from, localDate(nowMs).first)
            else -> from
        }
        return YearSpan(from, if (to != null && to >= from) to else from)
    }

    /** The months a Memory's date spans (`YYYY-MM`), an ongoing one's up to [nowMs]'s month; none without a start day. */
    fun monthsOfDate(date: MemoryDate?, nowMs: Long): MonthSpan? {
        val from = date?.from?.take(7)?.takeIf { it.isNotEmpty() } ?: return null
        val (y, m) = localDate(nowMs)
        val thisMonth = Query.monthOf(y, m)
        val to = when {
            date.to != null -> date.to.take(7)
            date.mode == MemoryDate.Mode.ONGOING && thisMonth > from -> thisMonth
            else -> from
        }
        return MonthSpan(from, if (to >= from) to else from)
    }

    /** A Memory's or a chapter's facets from its opened meta (the web's `memorySearchFacets`), at [nowMs]. */
    fun facets(meta: JsonObject, nowMs: Long): SearchFacets {
        val date = MemoryDate.of(meta)
        val place = MemoryLocation.of(meta)?.name?.trim()?.takeIf { it.isNotEmpty() }
        val people = Participants.read(meta)
        return SearchFacets(
            years = yearsOfDate(date, nowMs), months = monthsOfDate(date, nowMs),
            places = place?.let { listOf(it) }, people = people.takeIf { it.isNotEmpty() },
        )
    }

    /** The seals a medium's document is read from: a meta edit or a replaced original is a new document. */
    fun revisionOf(metaRevision: Long, blobId: String): String = "$metaRevision:$blobId"

    /** Whether [type] is one well-formed video essence (the web's `isVideoMediaType`: `video/` and a token). */
    fun isVideoType(type: String?): Boolean {
        if (type == null || !type.startsWith("video/") || type.length == 6) return false
        return type.substring(6).all { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' || it in "!#$%&'*+-.^_`|~" }
    }

    /**
     * Medium [mediaId]'s document of node [nodeId] (the web's `mediumDocument`), from its listed tier [meta] (null: it did not
     * open) and its original's sealed [type] and [name]; null for a medium no photo view shows (a GPX track, a music track).
     * [unnamed] names one whose original carries no name (its words: "Photo", "Video").
     */
    fun mediumDocument(
        mediaId: String, nodeId: String, metaRevision: Long, blobId: String,
        meta: MemoryMedia.Meta?, type: String?, name: String?, unnamed: (video: Boolean) -> String,
    ): ScopeDocument? {
        if (!GalleryMedium.shows(type, name)) return null
        val video = isVideoType(type?.trim())
        val place = meta?.placeName?.trim()?.takeIf { it.isNotEmpty() }
        return ScopeDocument(
            kind = if (video) VIDEO else PHOTO, id = mediaId, nodeId = nodeId, revision = revisionOf(metaRevision, blobId),
            name = name?.trim()?.takeIf { it.isNotEmpty() } ?: unnamed(video),
            facets = SearchFacets(capturedAt = meta?.effectiveCapturedAt, places = place?.let { listOf(it) }),
            icon = if (video) VIDEO_ICON else PHOTO_ICON,
        )
    }
}
