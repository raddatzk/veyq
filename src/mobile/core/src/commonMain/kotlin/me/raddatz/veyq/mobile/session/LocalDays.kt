package me.raddatz.veyq.mobile.session

import me.raddatz.veyq.mobile.media.localWallClock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The device's local calendar days as [LinkForm] counts them — days since 1970-01-01 —, read in the device's own zone
 * ([localWallClock], every platform's): today, the day an instant falls on, and a day's end as the instant a link's end is
 * sent as (`FolderShareDialog`: 23:59:59.999 of that local day). One rule for every app, so no platform turns a day into an
 * instant of its own.
 */
@OptIn(ExperimentalTime::class)
object LocalDays {
    private const val DAY_MS = 86_400_000L

    /** The local day [epochMs] falls on. */
    fun dayOf(epochMs: Long): Long {
        val seconds = (epochMs).floorDiv(1000L) * 1000L
        val (y, m, d) = civil((seconds).floorDiv(DAY_MS))
        val inDay = (seconds).mod(DAY_MS) / 1000
        // the instant whose local wall clock reads [seconds]' UTC one: the zone's offset is the distance between them
        val wall = localWallClock(y, m, d, (inDay / 3600).toInt(), (inDay / 60 % 60).toInt(), (inDay % 60).toInt()) ?: seconds
        return (epochMs + (seconds - wall)).floorDiv(DAY_MS)
    }

    /** The local day a server instant (ISO 8601) falls on; null where it does not parse. */
    fun dayOf(instant: String): Long? = runCatching { dayOf(Instant.parse(instant).toEpochMilliseconds()) }.getOrNull()

    /** The last millisecond of local day [day], as the instant a link's end is sent as. */
    fun endOf(day: Long): String {
        val (y, m, d) = civil(day)
        val next = localWallClock(y, m, d + 1, 0, 0, 0) ?: ((day + 1) * DAY_MS)
        return Instant.fromEpochMilliseconds(next - 1).toString()
    }

    /** Year, month and day of epoch day [day] (proleptic Gregorian; Howard Hinnant's `civil_from_days`). */
    internal fun civil(day: Long): Triple<Int, Int, Int> {
        val z = day + 719_468
        val era = (z).floorDiv(146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val y = (yoe + era * 400 + if (m <= 2) 1 else 0).toInt()
        return Triple(y, m, d)
    }
}
