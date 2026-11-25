package me.raddatz.veyq.mobile.memories

import me.raddatz.veyq.mobile.blob.UploadQueue

/**
 * A photo or a video this device is sending into a Memory or a chapter, as its gallery shows it (ADR 0145 decision 3): a
 * placeholder tile at the grid's start, in the order queued — the kind's glyph, its progress, or that it failed (the tray
 * retries it) — until its medium is listed and its own tile takes over. No preview: the picked file is not decoded a
 * second time for it. [id] is the queue item's; [tileId] its id in the grid's layout, never a medium's.
 */
class UploadTile(
    val id: String,
    /** The picked file's name, as the tray names it (the platform's label for the tile). */
    val name: String,
    val isVideo: Boolean,
    val status: UploadQueue.Status,
    /** The fraction sent (0..1); the on-device stage is 0. */
    val progress: Double,
    /** The medium it became, once in: the gallery drops the tile where its listing names it. */
    internal val mediaId: String?,
    /** Added offline, sealed on this device, waiting to be sent (ADR 0153 decision 5: *Waiting to send*). */
    val waiting: Boolean = false,
) {
    val tileId: String get() = PREFIX + id

    // what the gallery's rows ask of the status, read once here: the model then names no status of the queue's — a
    // serializable enum whose companion would carry kotlinx.serialization into the web's platform module (ADR 0155 § The
    // gallery model's move, D2)
    private val gone: Boolean = status == UploadQueue.Status.CANCELLED
    private val done: Boolean = status == UploadQueue.Status.DONE

    /** What the tile says under its glyph while it waits ([waiting]); null otherwise. */
    val said: me.raddatz.veyq.mobile.words.Notice? get() = if (waiting) me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Msg.OFFLINE_WAITING) else null

    val failed: Boolean get() = status == UploadQueue.Status.FAILED

    /** Still on the device, its tiers being made (no byte sent yet). */
    val preparing: Boolean get() = status == UploadQueue.Status.QUEUED || status == UploadQueue.Status.PREPARING

    companion object {
        /** What a placeholder's layout id starts with: a medium's id (a UUID) never does. */
        const val PREFIX = "upload:"

        /** The queue item a layout id names, or null for a medium's. */
        fun idOf(tileId: String): String? = if (tileId.startsWith(PREFIX)) tileId.removePrefix(PREFIX) else null

        /** [tiles] as the grid shows them over its listed [rows]: a cancelled one, and one whose medium is listed, left out. */
        internal fun shown(tiles: List<UploadTile>, listed: Set<String>): List<UploadTile> = tiles.filter { t ->
            !t.gone && !(t.done && (t.mediaId == null || t.mediaId in listed))
        }
    }
}
