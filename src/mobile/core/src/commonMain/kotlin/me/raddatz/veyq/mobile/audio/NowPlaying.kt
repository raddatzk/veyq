package me.raddatz.veyq.mobile.audio

import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.navigation.ObjectRef

/**
 * A track as an application hands it over (the web's `PlayerTrack`). Without a span the whole file plays. [open] is the
 * application's — it holds the keys — and hands the player a reader, never a key (ADR 0142 decision 1).
 */
class PlayerTrack(
    val id: String,
    val title: String,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val open: suspend () -> TrackSource,
)

/** A track's bytes as a deck plays them, and how to let them go ([release] closes the reader). */
class TrackSource(val reader: RangeReader, val mime: String, val release: () -> Unit)

/**
 * A queue (the web's `PlayerQueue`): [key] names whose it is (Memories: `memories:<scope>:<node>`), [album] what the bar and
 * the system's controls show under the title (the node's name), and [route] the node that started it, which the bar's
 * node name opens (ADR 0142 decision 5: a route, never the web's `href`).
 */
class PlayerQueue(
    val key: String,
    val tracks: List<PlayerTrack>,
    val album: String? = null,
    val route: ObjectRef? = null,
    val startId: String? = null,
)

/** What plays now, for the bar, the header, the lightbox's compact controls and the system's controls. Positions are
 *  within the span, in ms. [playing] is false while paused, an interruption's included (ADR 0142 decision 13). */
data class NowPlaying(
    val key: String,
    val trackId: String,
    val title: String,
    val album: String?,
    val route: ObjectRef?,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
)

/** Why the last queue ended by itself: none of its tracks played on this device (ADR 0142 decision 9). */
enum class PlayerNotice { UNPLAYABLE }
