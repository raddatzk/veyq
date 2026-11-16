package me.raddatz.veyq.mobile.map

import io.ktor.http.fromHttpToGmtDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.CoroutineContext

/**
 * The raster tiles of one tenant's map service (ADR 0143 D2, D4), fetched for Android, Linux and Windows (Apple's MapKit
 * fetches its own): only while [gate] holds (the services are offered and this device's consent holds for the served
 * hosts, checked when a request is about to leave), only from the served [template] (`https` only, [MapHttp]), only tiles
 * a shown map has in view ([TileView]; no ring ahead — the tile policy forbids pre-emptive fetching), at most [parallel]
 * at once. A tile's bytes go to the platform as they came (PNG/JPEG); the core decodes nothing.
 *
 * **Kept in memory only, for the session** (D4): a bounded LRU of [capacity] tiles, each with its `ETag`/`Last-Modified`
 * and expiry, revalidated conditionally after it expires and never asked with `no-cache` (the policy's headers honoured).
 * [drop] empties it and cancels what is in flight — at a withdrawal, a lock, a sign-out and a tenant switch. Nothing the
 * map path fetches is written to disk.
 */
class MapTiles internal constructor(
    private val http: MapHttp,
    private val template: String,
    private val gate: () -> Boolean,
    private val now: () -> Long,
    context: CoroutineContext,
    private val maxZoom: Int = Mercator.MAX_ZOOM,
    private val capacity: Int = CAPACITY,
    parallel: Int = PARALLEL,
) {
    private class Kept(val bytes: ByteArray, val etag: String?, val lastModified: String?, val expires: Long)

    private val scope = CoroutineScope(SupervisorJob() + context)
    private val permits = Semaphore(parallel)
    private val lock = SpinLock()

    /** Least recently used first. */
    private val cache = LinkedHashMap<TileKey, Kept>()
    private val inFlight = HashMap<TileKey, Deferred<ByteArray?>>()
    private var generation = 0L
    private var closed = false

    /** How many tiles are kept now (a test's look at the drop). */
    internal val cached: Int get() = lock.locked { cache.size }

    /** A map's window onto the tiles: what it shows, and only those it may ask for. */
    fun view(): TileView = TileView()

    inner class TileView internal constructor() {
        private val shown = MutableStateFlow<Set<TileKey>>(emptySet())

        /** The view now shown: its tiles placed in normalised Mercator space, which are all it may ask for now. */
        fun show(viewport: Viewport): List<PlacedTile> {
            val tiles = viewport.tiles(maxZoom)
            shown.value = tiles.mapTo(HashSet()) { it.key }
            return tiles
        }

        /** The bytes of tile [key] as served, or null: not in view, not allowed, not reached. */
        suspend fun tile(key: TileKey): ByteArray? {
            if (key !in shown.value) return null
            return fetch(key)
        }

        /** The map closed: it asks for nothing more. */
        fun close() {
            shown.value = emptySet()
        }
    }

    private suspend fun fetch(key: TileKey): ByteArray? {
        val url = urlOf(key) ?: return null
        val (kept, deferred) = lock.locked {
            if (closed) return null
            val k = cache.remove(key)?.also { cache[key] = it }
            if (k != null && k.expires > now()) return k.bytes
            val gen = generation
            k to (inFlight[key] ?: scope.async { load(key, url, k, gen) }.also { inFlight[key] = it })
        }
        return try {
            deferred.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            null
        } ?: kept?.takeIf { lock.locked { cache[key] === it } }?.bytes
    }

    private suspend fun load(key: TileKey, url: String, kept: Kept?, gen: Long): ByteArray? = try {
        permits.withPermit {
            // the gate, where the request is about to leave: a consent withdrawn while this tile waited sends nothing
            if (!gate()) return@withPermit null
            val conditional = buildList {
                kept?.etag?.let { add("If-None-Match" to it) }
                kept?.lastModified?.let { add("If-Modified-Since" to it) }
            }
            val a = http.get(url, conditional) ?: return@withPermit null
            val at = now()
            when {
                a.status == 304 && kept != null -> {
                    val fresh = Kept(kept.bytes, a.header("ETag") ?: kept.etag, a.header("Last-Modified") ?: kept.lastModified, at + lifetimeMs(a))
                    keep(key, fresh, gen, a)
                    kept.bytes
                }
                a.status in 200..299 -> {
                    keep(key, Kept(a.body, a.header("ETag"), a.header("Last-Modified"), at + lifetimeMs(a)), gen, a)
                    a.body
                }
                else -> null
            }
        }
    } finally {
        lock.locked { if (gen == generation) inFlight.remove(key) }
    }

    private fun keep(key: TileKey, kept: Kept, gen: Long, a: MapAnswer) {
        if (noStore(a)) return
        lock.locked {
            if (gen != generation || closed) return
            cache.remove(key)
            cache[key] = kept
            while (cache.size > capacity) cache.remove(cache.keys.first())
        }
    }

    /** Empties the cache and cancels every request in flight: a withdrawal, a lock, a sign-out, a tenant switch (D4). */
    fun drop() {
        lock.locked {
            generation++
            cache.clear()
            inFlight.clear()
        }
        scope.coroutineContext.cancelChildren()
    }

    /** The tenant's map path closes: [drop], and nothing is asked again. */
    fun close() {
        lock.locked { closed = true }
        drop()
    }

    /** The served template filled for [key]: `{z}`, `{x}`, `{y}`, a `{s}` subdomain as `a`, `{r}` as none; null where
     *  another placeholder is left. */
    private fun urlOf(key: TileKey): String? {
        val url = template.replace("{z}", key.z.toString()).replace("{x}", key.x.toString()).replace("{y}", key.y.toString())
            .replace("{s}", "a").replace("{r}", "")
        return url.takeIf { '{' !in it && '}' !in it }
    }

    companion object {
        /** ≈ 300 tiles in memory (D4): a few screens of a phone or a desktop window. */
        const val CAPACITY = 300

        /** At most this many tile requests at once, as a browser's handful per host. */
        const val PARALLEL = 4

        private fun directives(a: MapAnswer): List<String> =
            a.header("Cache-Control")?.split(',')?.map { it.trim().lowercase() } ?: emptyList()

        private fun noStore(a: MapAnswer) = "no-store" in directives(a)

        /**
         * How long an answer is fresh, from its headers (RFC 9111): `no-cache` none, `max-age`, else `Expires` against
         * `Date`, else a tenth of the time since `Last-Modified`, else none — then it is revalidated whenever asked again.
         */
        internal fun lifetimeMs(a: MapAnswer): Long {
            val d = directives(a)
            if ("no-cache" in d) return 0
            d.firstOrNull { it.startsWith("max-age=") }?.substringAfter('=')?.trim('"')?.toLongOrNull()?.let { return maxOf(0, it) * 1000 }
            fun date(name: String) = a.header(name)?.let { runCatching { it.fromHttpToGmtDate().timestamp }.getOrNull() }
            val served = date("Date") ?: return 0
            date("Expires")?.let { return maxOf(0, it - served) }
            date("Last-Modified")?.let { return maxOf(0, (served - it) / 10) }
            return 0
        }
    }
}

/** A lock for the few lines that touch a map model's in-memory state, which a platform's threads and coroutines share. */
internal class SpinLock {
    private val held = MutableStateFlow(false)

    inline fun <T> locked(block: () -> T): T {
        while (!acquire()) { /* spin: the sections are a few map operations */ }
        try {
            return block()
        } finally {
            release()
        }
    }

    @PublishedApi internal fun acquire(): Boolean = held.compareAndSet(expect = false, update = true)
    @PublishedApi internal fun release() {
        held.value = false
    }
}
