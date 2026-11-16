package me.raddatz.veyq.mobile.memories.music

import me.raddatz.veyq.mobile.audio.PlayerQueue
import me.raddatz.veyq.mobile.audio.PlayerTrack
import me.raddatz.veyq.mobile.audio.TrackSource
import me.raddatz.veyq.mobile.memories.GalleryModel
import me.raddatz.veyq.mobile.memories.MemoryMedia
import me.raddatz.veyq.mobile.navigation.NodeAt

/**
 * One of a node's music tracks as its Music view lists it and its queue plays it (the web's `musicQueue.ts` with
 * `MemoryMusic.vue`'s entries, ADR 0142 decision 4): [title] its own name, else its file's ([MusicQueue.trackTitle]) —
 * null for an untitled one, which is said as "Track [number]" —, [number] its place in the view's sorted list, its facts
 * (none: it plays whole, its length unknown).
 */
class MusicTrack internal constructor(
    internal val item: MemoryMedia.Item,
    val title: String?,
    val number: Int,
    /** Its place in the node's listing (the queue's order, ADR 0142 decision 4). */
    internal val listedAt: Int = 0,
) {
    val mediaId: String get() = item.mediaId
    val facts: AudioFacts? get() = item.meta?.facts

    /** The file's whole length, as the list shows it (the web's: the duration, not the trimmed span); null where unknown. */
    val lengthMs: Long? get() = facts?.durationMs

    /** The operator blocked it: no bytes are served (`451`) — listed, never played. */
    val blocked: Boolean get() = item.blocked

    /** Its own name as a member gave it (what Rename starts from); null where it is named by its file. */
    val ownName: String? get() = item.meta?.trackName

    /** Its original's sealed name (what a download is saved as). */
    val fileName: String? get() = item.name
}

/**
 * A Memory's or a chapter's music as the core's player takes it (the web's `musicQueue.ts`, ADR 0135 decisions 5 and 8,
 * ADR 0142 decisions 1 and 4): the node's own tracks — nothing inherited —, a snapshot taken when it starts; each with its
 * title, its trimmed span (none where its facts are absent: it plays whole) and an `open()` that is Memories' alone and
 * hands the player a reader, never a key.
 *
 * **Titles are numbered once** ([tracks]): the Music view's sorted order gives an untitled track its number, and the queue
 * uses the same — the web numbers its sorted tab and its unsorted queue apart (inbox #933), which is not ported.
 */
object MusicQueue {
    /** Whose queue this is: the node, in its space — what the header and the view ask to know whether *this* node plays. */
    fun key(scopeId: String, nodeId: String): String = "memories:$scopeId:$nodeId"

    /** A file's title: its name without its extension (no tags are read, ADR 0135 decision 4), else null. */
    fun musicTitle(name: String?): String? {
        val trimmed = name?.trim() ?: return null
        val dot = trimmed.lastIndexOf('.')
        return (if (dot > 0) trimmed.substring(0, dot) else trimmed).trim().takeIf { it.isNotEmpty() }
    }

    /** A track's title: the name a member gave it (`trackName`), else its file's ([musicTitle]), else null. */
    fun trackTitle(trackName: String?, fileName: String?): String? = trackName?.trim()?.takeIf { it.isNotEmpty() } ?: musicTitle(fileName)

    /** A length as the list shows it: `m:ss`, `h:mm:ss` from an hour on (the web's `formatTrackLength`). */
    fun formatLength(ms: Long): String {
        val total = (maxOf(0L, ms) + 500) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = (total % 60).toString().padStart(2, '0')
        return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$s" else "$m:$s"
    }

    /**
     * The order titles are listed in (the web's `Intl.Collator(undefined, { numeric: true, sensitivity: 'base' })`, as near
     * as the core can without a platform collator): letters without regard to case, runs of digits by their value
     * ("Track 2" before "Track 10"), ties by the title as written.
     */
    val titleOrder: Comparator<String> = Comparator { a, b ->
        val c = compareNatural(a.lowercase(), b.lowercase())
        if (c != 0) c else a.compareTo(b)
    }

    private fun compareNatural(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ei = i
                while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j
                while (ej < b.length && b[ej].isDigit()) ej++
                val na = a.substring(i, ei).trimStart('0')
                val nb = b.substring(j, ej).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = ei
                j = ej
            } else {
                if (ca != cb) return ca.compareTo(cb)
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    /**
     * The node's music tracks among [items] (its listing: every audio medium, a blocked one included — it stays listed),
     * as the Music view lists them: sorted by title ([titleOrder]), the untitled last, each numbered by its place.
     */
    fun tracks(items: List<MemoryMedia.Item>): List<MusicTrack> {
        val named = items.filter { it.isAudio }.map { it to trackTitle(it.meta?.trackName, it.name) }
        val listed = named.withIndex().associate { (i, p) -> p.first.mediaId to i }
        val sorted = named.sortedWith { x, y ->
            val a = x.second
            val b = y.second
            when {
                a == null && b == null -> 0
                a == null -> 1
                b == null -> -1
                else -> titleOrder.compare(a, b)
            }
        }
        return sorted.mapIndexed { i, (item, title) -> MusicTrack(item, title, i + 1, listed.getValue(item.mediaId)) }
    }

    /**
     * The node's [tracks] as a queue for the player, in listing order, [startId] first (null: shuffled from the start): a
     * blocked one left out; each titled as the view titles it ([untitled] names an untitled one by its number), spanned by its facts, opened
     * by [open] — a reader of its original with its sealed type, closed by `release()` —; the queue keyed by the node,
     * its album the node's name, its route the node's page (ADR 0142 decision 5).
     */
    fun queue(
        node: NodeAt, tracks: List<MusicTrack>, untitled: (Int) -> String, open: (MemoryMedia.Item) -> TrackSource?, startId: String? = null,
    ): PlayerQueue = PlayerQueue(
        key = key(node.scopeId, node.nodeId),
        tracks = tracks.filter { !it.blocked }.sortedBy { it.listedAt }.map { t ->
            val facts = t.facts
            PlayerTrack(t.mediaId, t.title ?: untitled(t.number), facts?.trimStartMs, facts?.trimEndMs) {
                open(t.item) ?: throw IllegalStateException("track_unreadable")
            }
        },
        album = node.name,
        route = GalleryModel.page(node),
        startId = startId,
    )

    /** [item]'s original as a deck plays it: read at any offset by [reader], typed by its sealed type (MP3 where none). */
    fun source(item: MemoryMedia.Item, reader: me.raddatz.veyq.mobile.blob.RangeReader): TrackSource =
        TrackSource(reader, item.type?.takeIf { it.isNotBlank() } ?: "audio/mpeg") { reader.close() }
}
