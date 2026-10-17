package me.raddatz.veyq.core.blobstore

import java.time.Instant
import java.util.TreeMap

/**
 * A bucket a test writes by hand, listed through [RecordingBlobStore.bucket] — the reconcile's store half in the
 * hermetic suite (cr-bucket-reconcile). Keys list in S3's order (UTF-8 binary, which for these ASCII keys is
 * [String]'s), [StoreListing.storeTime] is [storeTime] — the store's clock, set by the test, never this process's — and
 * [stat] answers what the bucket holds.
 *
 * Its levers, each for one bite: [goneAtStat] keys are listed but absent when asked again (a row drained during the
 * listing); [withoutClock] answers a listing with no `Date`; [onList] runs before each listing with the cursor it was
 * asked for — a test throws from it to fail a page, or does another tick's work in it to interleave two.
 */
class ScriptedBucket(
    @Volatile var digest: String = "scripted-bucket",
    @Volatile var storeTime: Instant = Instant.parse("2026-10-17T12:00:00Z"),
) {
    private val objects = TreeMap<String, StoredObject>()

    val goneAtStat: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    @Volatile
    var withoutClock: Boolean = false

    @Volatile
    var onList: ((String?) -> Unit)? = null

    /** How many listings were asked for — tells "the tick listed nothing" apart from "the tick did not run". */
    @Volatile
    var listings: Int = 0
        private set

    @Synchronized
    fun put(key: String, size: Long, lastModified: Instant) {
        objects[key] = StoredObject(key, size, lastModified)
    }

    @Synchronized
    fun remove(key: String) {
        objects.remove(key)
    }

    @Synchronized
    fun stat(key: String): Long? = if (key in goneAtStat) null else objects[key]?.size

    fun list(startAfter: String?, maxKeys: Int): StoreListing {
        onList?.invoke(startAfter)
        if (withoutClock) throw MissingStoreClock()
        synchronized(this) {
            listings += 1
            val after = if (startAfter == null) objects.values else objects.tailMap(startAfter, false).values
            val page = after.take(maxKeys)
            return StoreListing(page, storeTime, after.size > page.size)
        }
    }
}
