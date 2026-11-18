package me.raddatz.veyq.mobile.map

import me.raddatz.veyq.mobile.offline.AccountOffline
import me.raddatz.veyq.mobile.offline.Reader
import me.raddatz.veyq.mobile.offline.Writer

/** A tile as kept beyond the session: its bytes as served, its validators and when it stops being fresh (epoch ms). */
class ShelvedTile(val bytes: ByteArray, val etag: String?, val lastModified: String?, val expires: Long) {
    internal fun encode(): ByteArray = Writer().apply {
        byte(VERSION)
        text(etag.orEmpty())
        text(lastModified.orEmpty())
        long(expires)
        int(bytes.size)
        raw(bytes)
    }.bytes()

    internal companion object {
        private const val VERSION = 1

        /** A kept tile's content, or null where it is not one this version wrote (then it is fetched again). */
        fun decode(content: ByteArray): ShelvedTile? = runCatching {
            val r = Reader(content)
            require(r.byte() == VERSION)
            val etag = r.text().ifEmpty { null }
            val lastModified = r.text().ifEmpty { null }
            val expires = r.long()
            val bytes = r.raw(r.int())
            require(r.done)
            ShelvedTile(bytes, etag, lastModified, expires)
        }.getOrNull()
    }
}

/**
 * Where a tile outlives the session (ADR 0143 D4 as amended for inbox #939): the account's offline store, sealed in its
 * automatic tier ([AccountOffline.tile]) — counted against the person's limit, evicted least recently viewed first,
 * dropped by "Clear cache" and by a wipe, never kept where the device keeps no trust or the limit is `0`. A tile is named
 * by the served template and its `z/x/y`, so another template's tiles are never served for this one.
 *
 * [MapTiles] reads it only behind its gate — the services offered, the consent holding for the hosts served now, and the
 * account online — so **no kept tile is ever shown offline** (D9; `tile.openstreetmap.org`'s policy forbids offline use),
 * and it is never filled ahead of what is viewed.
 */
interface TileShelf {
    /** The tile kept as [id], null where none is. */
    suspend fun get(id: String): ShelvedTile?

    /** Keeps [tile] as [id], replacing what was kept (a store that takes nothing — no trust, the limit `0` — keeps nothing). */
    suspend fun put(id: String, tile: ShelvedTile)

    /** Drops every tile kept for this tenant but those of [template] (null: all of them). */
    suspend fun drop(template: String?)

    companion object {
        /** A tile's name on the shelf: the template it was served under and its `z/x/y`. */
        fun id(template: String, key: TileKey): String = "$template\n${key.id}"

        /** The shelf of [tenant] in [offline] — read at each use, since the store opens after the account does. */
        internal fun of(offline: () -> AccountOffline?, tenant: String): TileShelf = object : TileShelf {
            override suspend fun get(id: String): ShelvedTile? = offline()?.tile(tenant, id)?.let(ShelvedTile::decode)
            override suspend fun put(id: String, tile: ShelvedTile) {
                offline()?.keepTile(tenant, id, tile.encode())
            }
            override suspend fun drop(template: String?) {
                offline()?.dropTiles(tenant, template?.let { "$it\n" })
            }
        }
    }
}
